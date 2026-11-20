"""Workspace-local configuration units; M4 declarations are not executed calls.

Autoconf 项目级分析：把工作区内的 configure/config.guess/config.sub
识别为配置单元，用 Evaluator 逐单元符号执行；.m4/configure.ac 只做
声明级词法识别（declarations），M4 宏永远是声明而非被观测的调用。
每个文件得到独立的 AdapterResult，只有 configure 入口能确立构建用途。
"""
from __future__ import annotations

from dataclasses import dataclass, replace
from pathlib import PurePosixPath
import re

from ....core import stable_id
from ..adapters import AdapterResult, parse_m4
from .flow import Evaluator, State
from .syntax import Node, Source, parse


@dataclass(frozen=True, slots=True)
class Declaration:
    """一条 M4 声明（frozen 不可变）：宏名、字面参数与字节区间。"""

    name: str
    argument: str | None
    start: int
    end: int


def declarations(text: str, *, max_declarations=100000) -> tuple[Declaration, ...]:
    """Lex balanced literal calls; skip opaque macro bodies and quoted text.

    手写词法扫描：跳过 dnl/#注释与 [] 引用文本，识别带平衡括号的
    宏调用；只保留参数为受限字面字符集（字母数字 _./ 空格-）的调用，
    其余整体视为不透明。字节偏移按 UTF-8 增量计算，超预算抛错。
    只登记五个白名单宏（AC_CANONICAL_*/AC_CONFIG_*）。
    """
    result = []
    cursor = 0
    char_offset = byte_offset = 0
    token = re.compile(r"dnl\b[^\n]*|\#[^\n]*|[A-Za-z_][A-Za-z0-9_]*|\[")
    while match := token.search(text, cursor):
        name = match.group()
        cursor = match.end()
        if name.startswith("#") or name.startswith("dnl"):
            continue
        if name == "[":
            depth = 1
            while cursor < len(text) and depth:
                depth += int(text[cursor] == "[") - int(text[cursor] == "]")
                cursor += 1
            continue
        opening = cursor
        while opening < len(text) and text[opening].isspace():
            opening += 1
        argument = "" if name.startswith("AC_CANONICAL_") else None
        if opening < len(text) and text[opening] == "(":
            cursor, parens, brackets = opening + 1, 1, 0
            while cursor < len(text) and parens:
                character = text[cursor]
                if character == "[":
                    brackets += 1
                elif character == "]":
                    brackets = max(0, brackets - 1)
                elif brackets == 0:
                    parens += int(character == "(") - int(character == ")")
                cursor += 1
            body = text[opening + 1:cursor - 1].strip() if parens == 0 else ""
            if body.startswith("[") and body.endswith("]"):
                body = body[1:-1].strip()
            if parens == 0 and re.fullmatch(r"[A-Za-z0-9_./ -]*", body):
                argument = body
        if name in {"AC_CANONICAL_HOST", "AC_CANONICAL_BUILD", "AC_CANONICAL_TARGET", "AC_CONFIG_AUX_DIR", "AC_CONFIG_SUBDIRS"}:
            if len(result) >= max_declarations:
                raise ValueError("autoconf-m4-declaration-budget")
            start = byte_offset + len(text[char_offset:match.start()].encode("utf-8"))
            end = start + len(text[match.start():cursor].encode("utf-8"))
            result.append(Declaration(name, argument, start, end))
            char_offset, byte_offset = cursor, end
    return tuple(result)


def auxiliary_pair(path: str, files: set[str], decls: tuple[Declaration, ...]):
    """定位 config.guess + config.sub 辅助脚本对，返回 (pair|None, 原因码)。

    AC_CONFIG_AUX_DIR 声明唯一且相对时按声明目录查找；未声明时依次
    尝试同目录与上两级父目录。.. 逃逸工作区或文件缺失都返回 None
    附带对应原因。
    """
    directory = PurePosixPath(path).parent
    declared = [item.argument for item in decls if item.name == "AC_CONFIG_AUX_DIR"]
    if declared and (None in declared or len(set(declared)) != 1):
        return None, "autoconf-aux-dir-dynamic-or-conflicting"
    candidates = []
    if declared:
        if not declared[0] or PurePosixPath(declared[0]).is_absolute():
            return None, "autoconf-aux-dir-unavailable"
        candidates = [directory / declared[0]]
    else:
        candidates = [directory, *list(directory.parents)[:2]]
    for candidate in candidates:
        parts = []
        for part in candidate.parts:
            if part == "..":
                if not parts:
                    return None, "autoconf-aux-dir-outside-workspace"
                parts.pop()
            elif part != ".":
                parts.append(part)
        prefix = PurePosixPath(*parts)
        pair = tuple(str(prefix / name) for name in ("config.guess", "config.sub"))
        if all(name in files for name in pair):
            return pair, "autoconf-source-declaration-only"
    return None, "autoconf-helper-pair-unavailable"


def analyze_project(contents: dict[str, str], target: str, *, max_units=256) -> dict[str, AdapterResult]:
    """对工作区内全部 Autoconf 输入做项目级分析，返回 {路径: AdapterResult}。

    流程：configure 系脚本用 bash 语法解析，其余按 m4 处理；configure
    入口优先求值（bound=True），config.guess/config.sub 只在被 bound
    调用后才算被使用（used_helpers）；.m4 走声明识别 + 保留 M4 识别器
    的 raw 事实（selectionStatus 恒为 unknown）。超单元预算记 issue。
    """
    if max_units < 1:
        raise ValueError("Autoconf unit budget must be positive")
    sources = {}
    for path, text in contents.items():
        if PurePosixPath(path).name in {"configure", "config.guess", "config.sub"}:
            try:
                sources[path] = parse(text, path)
            except (OSError, RuntimeError, TypeError, ValueError) as exc:
                data = text.encode("utf-8")
                sources[path] = Source(path, data, Node("unsupported", 0, len(data)), (0,),
                                       "autoconf-parser-unavailable-" + type(exc).__name__)
        else:
            data = text.encode("utf-8")
            sources[path] = Source(path, data, Node("m4", 0, len(data)),
                                   (0, *(i + 1 for i, byte in enumerate(data) if byte == 10)))
    records = {path: [] for path in contents}
    issues = {path: [] for path in contents}
    # Each script has a local result; only configure entry paths establish build use.
    used_helpers = set()
    ordered = sorted(sources.items(), key=lambda item: (PurePosixPath(item[0]).name != "configure", item[0]))
    for number, (path, source) in enumerate(ordered):
        # 每个文件独立建立 Evaluator；configure 先于辅助脚本求值。
        evaluator = Evaluator(sources, target, path)
        if number >= max_units:
            state = State(reason="autoconf-unit-budget")
            evaluator.event(source, source.root, state, "build_unsupported", outcome="unknown", reason=state.reason, bound=False)
            issues[path].append(state.reason)
        elif source.root.kind == "m4":
            try:
                decls = declarations(contents[path])
            except ValueError as exc:
                state = State(reason=str(exc))
                evaluator.event(source, source.root, state, "build_unsupported", outcome="unknown", reason=state.reason, bound=False)
                issues[path].append(state.reason)
                decls = ()
            pair, reason = auxiliary_pair(path, set(contents), decls)
            state = State()
            for item in decls:
                node = Node("m4_declaration", item.start, item.end)
                canonical = item.name.startswith("AC_CANONICAL_")
                children = tuple(str(PurePosixPath(path).parent / name / "configure") for name in (item.argument or "").split()) if item.name == "AC_CONFIG_SUBDIRS" else ()
                evaluator.event(source, node, state, "build_configuration", declaration=item.name,
                                argument=item.argument, helpers=pair, bound=False,
                                declaredSubprojects=children, subprojectsPresent=tuple(name for name in children if name in contents),
                                outcome="unknown" if canonical else "", reason=reason,
                                usage="source-declaration-not-observed-call")
                if canonical:
                    issues[path].append(reason)
            # Preserve the existing bounded M4 recognizer's observations as raw
            # facts only. Its lexical roles must never become acceptance proofs.
            raw = parse_m4(contents[path], path, target)
            for record in raw.records:
                info = {"key": stable_id("autoconf-m4-raw", path, evaluator.digests[path], target,
                                         record.kind, record.value, record.line, record.start_column),
                        "raw": True, "unit": str(PurePosixPath(path).parent), "entry": path,
                        "digest": evaluator.digests[path], "target": target}
                evaluator.records.setdefault(path, []).append(replace(record,
                    metadata={**(record.metadata or {}), "autoconf": info, "selectionStatus": "unknown"}))
            issues[path].extend(raw.diagnostics)
        else:
            # configure 系脚本：先保留原生语法事实，再对未被 bound 使用过的入口做符号执行。
            evaluator.retain(source)
            if path not in used_helpers:
                outcome = evaluator.run(path, bound=PurePosixPath(path).name == "configure")
                if outcome.status == "unknown":
                    issues[path].append(outcome.reason)
        for item_path, items in evaluator.records.items():
            records[item_path].extend(items)
            if item_path != path and any(record.metadata["autoconf"].get("bound") for record in items):
                used_helpers.add(item_path)
    return {path: AdapterResult(tuple(records[path]), tuple(sorted(set(issues[path]))), bool(issues[path])) for path in contents}
