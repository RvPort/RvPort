"""构建输入单文件解析适配器：shell / M4 / JSON 元数据 / RPM。

frontend 阶段的分派层：把各类构建输入文本解析为一组 BuildRecord。
shell 用 tree-sitter-bash + 行级正则，M4 用有界识别器，JSON 元数据
（meson/ctest/compile_commands/CMake FileAPI）直接读取，RPM 委托
rpm 子包。全程不执行项目命令。
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from importlib.metadata import version
from typing import Any

from tree_sitter import Language, Parser
import tree_sitter_bash

from .safety import normalize_target_arch, symbolic_uname, unsafe_constructs
from .vocabulary import BuildRecord, record_for_token


@dataclass(frozen=True, slots=True)
class AdapterResult:
    """单文件解析结果：记录元组 + 诊断字符串 + 是否部分完成（frozen 不可变）。"""

    records: tuple[BuildRecord, ...] = ()
    diagnostics: tuple[str, ...] = ()
    partial: bool = False


_X86 = re.compile(r"(?i)(?:x86(?:_64)?|amd64|i[3-6]86|avx|sse)")
_RISCV = re.compile(r"(?i)riscv(?:32|64)?")
_GENERIC = re.compile(r"(?i)(?:generic|portable|scalar|fallback|default|\*)")


def parse_shell(source: str, path: str, target_arch: str) -> AdapterResult:
    """解析 configure 类 shell 脚本（行级识别，配合 tree-sitter 语法检查）。

    识别四类事实：
    - uname -m 探测：符号化求值为目标架构（build_arch_probe）；
    - case 分支模式：按 x86/riscv/generic 角色判定选中状态（build_branch）；
    - 变量赋值（build_assignment）；
    - 不受支持的命令替换与危险构造（build_unsupported + 诊断）。
    """
    tree = Parser(Language(tree_sitter_bash.language())).parse(source.encode("utf-8"))
    diagnostics = []
    if tree.root_node.has_error:
        diagnostics.append(f"tree-sitter-bash@{version('tree-sitter-bash')}: partial syntax")
    records: list[BuildRecord] = []
    for line_number, raw in enumerate(source.splitlines(), 1):
        text = raw.strip()
        if not text or text.startswith("#"):
            continue
        for match in re.finditer(r"\$\(\s*uname\s+-m\s*\)|`\s*uname\s+-m\s*`", raw):
            probe = symbolic_uname(match.group(0), target_arch)
            records.append(record_for_token("build_arch_probe", "uname -m", line_number, raw, metadata={"selectionStatus": "selected", "resolvedValue": probe.value, "role": "riscv"}))
        branch = re.match(r"^\s*([^#][^)]*)\)", raw)
        if branch:
            pattern = branch.group(1).strip()
            role = _role(pattern)
            status = _branch_status(pattern, target_arch)
            records.append(record_for_token("build_branch", pattern, line_number, raw, metadata={"conditionGroup": f"{path}:case", "selectionStatus": status, "role": role}))
        assignment = re.match(r"^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$", raw)
        if assignment:
            records.append(record_for_token("build_assignment", assignment.group(1), line_number, raw, metadata={"expression": assignment.group(2), "selectionStatus": "unknown", "role": _role(assignment.group(2))}, confidence="medium"))
    for name, line, text in _shell_unsafe(source):
        records.append(record_for_token("build_unsupported", name, line, text, metadata={"selectionStatus": "unknown", "reason": name}, confidence="medium"))
        diagnostics.append(f"{path}:{line}: {name} unsupported/unknown")
    return _result(records, diagnostics)


def parse_m4(source: str, path: str, target_arch: str) -> AdapterResult:
    """解析 .m4 / configure.ac：识别白名单 autoconf 宏与不支持的宏。

    dnl 注释先剥离；白名单宏（AC_CANONICAL_HOST 等）记为 build_condition，
    其余大写宏调用记为 build_unsupported 并附诊断；行内 unsafe_constructs
    （syscmd/include 等）同样记为不支持。
    """
    del target_arch
    records: list[BuildRecord] = []
    diagnostics: list[str] = []
    allowed = {"AC_CANONICAL_HOST", "AC_CANONICAL_TARGET", "AS_CASE", "AM_CONDITIONAL", "AC_ARG_ENABLE", "AC_COMPILE_IFELSE"}
    for line_number, raw in enumerate(source.splitlines(), 1):
        text = re.sub(r"dnl.*$", "", raw).strip()
        if not text:
            continue
        for match in re.finditer(r"\b([A-Z][A-Z0-9_]*)\s*\((.*)", text):
            name, args = match.groups()
            role = _role(args)
            if name in allowed:
                records.append(record_for_token("build_condition", name, line_number, raw, metadata={"expression": args[:512], "selectionStatus": "unknown", "role": role}, confidence="medium"))
            else:
                records.append(record_for_token("build_unsupported", name, line_number, raw, metadata={"selectionStatus": "unknown", "reason": "unsupported-m4-macro"}, confidence="medium"))
                diagnostics.append(f"{path}:{line_number}: unsupported M4 macro {name}")
    for name, line, text in unsafe_constructs(source, "m4"):
        records.append(record_for_token("build_unsupported", name, line, text, metadata={"selectionStatus": "unknown", "reason": name}, confidence="medium"))
        diagnostics.append(f"{path}:{line}: {name} unsupported/unknown")
    return _result(records, diagnostics)


def parse_machine_json(source: str, path: str, input_kind: str, target_arch: str) -> AdapterResult:
    """解析 JSON 元数据输入（meson intro / compile_commands / ctest / CMake FileAPI）。

    思路：先判定整体 selectionStatus（文本含目标架构→selected，含
    x86→inactive，否则 unknown），再产出一条 build_configuration 与
    每个 target 条目的 build_target/build_source/build_argument/
    build_dependency/build_artifact 记录；ctest 与显式 test 标记会
    附加 testRole/testSuite 字段。
    """
    if len(source.encode("utf-8")) > 4 * 1024 * 1024:
        return AdapterResult((), (f"{path}: machine metadata exceeds size budget",), True)
    try:
        payload = json.loads(source)
    except (TypeError, json.JSONDecodeError) as exc:
        return AdapterResult((), (f"{path}: invalid JSON ({type(exc).__name__})",), True)
    records: list[BuildRecord] = []
    diagnostics: list[str] = []
    target = normalize_target_arch(target_arch)
    status = _metadata_status(payload, target)
    configuration = _configuration_name(payload)
    base = {"selectionStatus": status, "configuration": configuration, "schema": _schema(payload), "jsonPointer": "/"}
    records.append(BuildRecord("build_configuration", configuration, 1, 1, 1 + len(configuration), base, "high" if status != "unknown" else "medium"))

    # 不同输入的 targets 取法不同：meson 可能是列表或 dict.targets，
    # compile_commands 是列表，ctest 是 dict.tests，其余递归查找 targets 键。
    if input_kind == "meson":
        targets = payload if isinstance(payload, list) else payload.get("targets", []) if isinstance(payload, dict) else []
    elif input_kind == "compilation-database":
        targets = payload if isinstance(payload, list) else []
    elif input_kind == "ctest":
        targets = payload.get("tests", []) if isinstance(payload, dict) else []
    else:
        targets = _find_targets(payload)
    for index, item in enumerate(targets if isinstance(targets, list) else []):
        if not isinstance(item, dict):
            diagnostics.append(f"{path}: target entry {index} is not an object")
            continue
        target_name = str(item.get("name") or item.get("id") or item.get("output") or item.get("file") or f"entry-{index}")
        metadata = {**base, "target": target_name, "jsonPointer": f"/{index}"}
        declared_type = str(item.get("type") or item.get("kind") or "").lower()
        if input_kind == "ctest" or item.get("is_test") is True or declared_type in {"test", "unit-test", "benchmark-test"}:
            metadata["testRole"] = "confirmed"
            metadata["testSuite"] = str(item.get("suite") or item.get("project") or configuration)
        for source_field, metadata_field in (("coverageTarget", "coverageTarget"), ("tested_source", "testedSource"), ("callTarget", "callTarget"), ("alternativeTestFor", "alternativeTestFor")):
            if isinstance(item.get(source_field), str) and item[source_field]:
                metadata[metadata_field] = item[source_field]
        records.append(BuildRecord("build_target", target_name, 1, 1, 1 + len(target_name), metadata, "high" if status != "unknown" else "medium"))
        for value in _paths(item, ("sources", "source", "filename", "file")):
            records.append(BuildRecord("build_source", value, 1, 1, 1 + len(value), metadata, "high" if status != "unknown" else "medium"))
        for value in _strings(item, ("compile_args", "compile_parameters", "arguments", "command", "fragments")):
            for token in value.split():
                if token.startswith("-"):
                    records.append(BuildRecord("build_argument", token, 1, 1, 1 + len(token), metadata, "high" if status != "unknown" else "medium"))
        for value in _strings(item, ("dependencies", "depends", "linkLibraries")):
            records.append(BuildRecord("build_dependency", value, 1, 1, 1 + len(value), metadata, "high" if status != "unknown" else "medium"))
        for value in _paths(item, ("artifacts", "outputs")):
            records.append(BuildRecord("build_artifact", value, 1, 1, 1 + len(value), metadata, "high" if status != "unknown" else "medium"))
    if input_kind == "cmake" and unsafe_constructs(source, "cmake"):
        diagnostics.append(f"{path}: execute_process text is unsupported")
    return _result(records, diagnostics)


def parse_rpm(source: str, path: str, target_arch: str) -> AdapterResult:
    """RPM spec 解析入口：委托 rpm.parser，有诊断即视为 partial。"""
    from .rpm.parser import parse
    records, diagnostics = parse(source, path, target_arch)
    return AdapterResult(records, diagnostics, bool(diagnostics))


def _shell_unsafe(source: str) -> tuple[tuple[str, int, str], ...]:
    """找出所有无法符号化求值的 shell 命令替换 $(...) / `...`。"""
    found = []
    for line, raw in enumerate(source.splitlines(), 1):
        for match in re.finditer(r"\$\(([^)]*)\)|`([^`]*)`", raw):
            command = (match.group(1) or match.group(2) or "").strip()
            if not symbolic_uname(command, "riscv64").recognized:
                found.append(("shell-command-substitution", line, raw.strip()))
    return tuple(found)


def _role(value: str) -> str:
    """按文本内容判定架构角色：riscv / x86 / generic / unknown（按此优先级）。"""
    if _RISCV.search(value):
        return "riscv"
    if _X86.search(value):
        return "x86"
    if _GENERIC.search(value):
        return "generic"
    return "unknown"


def _branch_status(pattern: str, target_arch: str) -> str:
    """由分支模式角色推导选中状态：riscv 且目标为 riscv→selected，x86→inactive，generic→selected，其余 unknown。"""
    role = _role(pattern)
    if role == "riscv":
        return "selected" if _RISCV.search(normalize_target_arch(target_arch)) else "inactive"
    if role == "x86":
        return "inactive"
    if role == "generic":
        return "selected"
    return "unknown"


def _metadata_status(payload: Any, target_arch: str) -> str:
    """把整个 JSON 序列化为文本后按架构关键词判定 selected/inactive/unknown。"""
    text = json.dumps(payload, sort_keys=True).lower()
    if target_arch in text or (target_arch == "riscv64" and "riscv" in text):
        return "selected"
    if _X86.search(text):
        return "inactive"
    return "unknown"


def _configuration_name(payload: Any) -> str:
    """从元数据顶层取 name/configuration/kind 作为配置名，缺省 "observed"。"""
    if isinstance(payload, dict):
        return str(payload.get("name") or payload.get("configuration") or payload.get("kind") or "observed")
    return "observed"


def _schema(payload: Any) -> str:
    """提取元数据的生成器版本（version/cmake/meson_version）作为 schema 标识。"""
    if isinstance(payload, dict):
        version_value = payload.get("version") or payload.get("cmake") or payload.get("meson_version")
        return str(version_value)[:128] if version_value is not None else "unknown"
    return "unknown"


def _find_targets(payload: Any) -> list[dict[str, Any]]:
    """深度优先递归查找第一个 "targets" 列表（用于未知 schema 的 JSON）。"""
    if isinstance(payload, dict):
        if isinstance(payload.get("targets"), list):
            return [item for item in payload["targets"] if isinstance(item, dict)]
        for value in payload.values():
            found = _find_targets(value)
            if found:
                return found
    if isinstance(payload, list):
        for value in payload:
            found = _find_targets(value)
            if found:
                return found
    return []


def _strings(payload: Any, keys: tuple[str, ...]) -> tuple[str, ...]:
    """按键名集合递归收集所有字符串值（保持出现顺序去重）。"""
    values: list[str] = []
    if isinstance(payload, dict):
        for key, value in payload.items():
            if key in keys:
                values.extend(_flatten_strings(value))
            elif isinstance(value, (dict, list)):
                values.extend(_strings(value, keys))
    elif isinstance(payload, list):
        for item in payload:
            values.extend(_strings(item, keys))
    return tuple(dict.fromkeys(value for value in values if value))


def _paths(payload: Any, keys: tuple[str, ...]) -> tuple[str, ...]:
    """同 _strings，但过滤掉以 "-" 开头的编译选项，只留路径类值。"""
    return tuple(value for value in _strings(payload, keys) if not value.startswith("-"))


def _flatten_strings(value: Any) -> list[str]:
    """把标量/列表/dict（取 path/id/name/fragment 键）拍平为字符串列表。"""
    if isinstance(value, str):
        return [value]
    if isinstance(value, dict):
        return [str(item) for key in ("path", "id", "name", "fragment") if (item := value.get(key)) is not None]
    if isinstance(value, list):
        result = []
        for item in value:
            result.extend(_flatten_strings(item))
        return result
    return []


def _stack_status(stack: list[bool | None]) -> str:
    """把三值条件栈压缩为五态之一：含 False→inactive，含 None→unknown，否则 selected。"""
    if False in stack:
        return "inactive"
    if None in stack:
        return "unknown"
    return "selected"


def _result(records: list[BuildRecord], diagnostics: list[str]) -> AdapterResult:
    """收尾：按 (kind,line,col,value,metadata) 去重并按位置排序；有诊断即 partial。"""
    ordered = tuple(sorted({(item.kind, item.line, item.start_column, item.value, str(item.metadata)): item for item in records}.values(), key=lambda item: (item.line, item.start_column, item.kind, item.value)))
    return AdapterResult(ordered, tuple(dict.fromkeys(diagnostics)), bool(diagnostics))
