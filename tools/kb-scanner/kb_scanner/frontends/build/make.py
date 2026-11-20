"""Makefile 有界静态解析：条件求值、变量展开与派生记录。

frontend 阶段的 Make 解析器：tree-sitter-make 做语法检查 + 行级
正则识别赋值/条件/include/目标规则，配合三值条件栈与有界变量展开，
产出 BuildRecord。所有求值都有预算上限，超限降级为 unknown 而非执行。
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from importlib.metadata import version
from pathlib import Path, PurePosixPath
from typing import Callable

from tree_sitter import Language, Parser
import tree_sitter_make

from .safety import symbolic_uname, unsafe_constructs
from .macro_arguments import parse_macro_recipes
from .vocabulary import BuildRecord, SelectionStatus, record_for_token


_ASSIGNMENT = re.compile(r"^\s*([A-Za-z_][A-Za-z0-9_.-]*)\s*(:=|\+=|\?=|=|!=)\s*(.*?)\s*$")
_CONDITION = re.compile(r"^\s*(ifeq|ifneq|ifdef|ifndef)\b\s*(.*?)\s*$")
_INCLUDE = re.compile(r"^\s*(-?include|sinclude)\s+(.+?)\s*$")
_TARGET = re.compile(r"^\s*([^#\s][^:=]*?)\s*:\s*([^=].*?)?\s*$")
_VARIABLE = re.compile(r"\$\(([^()]+)\)|\$\{([^{}]+)\}")
_SOURCE_SUFFIXES = (".c", ".cc", ".cpp", ".cxx", ".h", ".hpp", ".s", ".S", ".asm")
_X86 = re.compile(r"(?i)(?:^|[^a-z0-9])(?:x86(?:_64)?|amd64|i[3-6]86|avx\w*|sse\w*)(?:$|[^a-z0-9])")
_RISCV = re.compile(r"(?i)(?:^|[^a-z0-9])riscv(?:32|64)?(?:$|[^a-z0-9])")
_GENERIC = re.compile(r"(?i)(?:^|[^a-z0-9])(?:generic|portable|scalar|fallback|default)(?:$|[^a-z0-9])")


@dataclass(frozen=True, slots=True)
class MakeBudgets:
    """解析预算（frozen 不可变）：include 深度、文件数、展开次数、候选 token 数上限。"""

    max_include_depth: int = 8
    max_files: int = 128
    max_expansions: int = 4096
    max_candidates: int = 256

    def __post_init__(self) -> None:
        if min(self.max_include_depth, self.max_files, self.max_expansions, self.max_candidates) < 1:
            raise ValueError("Make budgets must be positive")


@dataclass(frozen=True, slots=True)
class MakeValue:
    """求值后的变量值（frozen 不可变）。

    kind: scalar=单值 / list=多值 / alternatives=候选集合 / unknown=无法求值。
    unknown 必须携带 reason；alternatives 自动去重排序。
    """

    kind: str
    values: tuple[str, ...] = ()
    reason: str | None = None

    def __post_init__(self) -> None:
        if self.kind not in {"scalar", "list", "alternatives", "unknown"}:
            raise ValueError("unsupported Make value kind")
        values = tuple(sorted(set(self.values))) if self.kind == "alternatives" else tuple(self.values)
        if self.kind == "unknown" and not self.reason:
            raise ValueError("unknown Make value requires a reason")
        if self.kind != "unknown" and not values:
            raise ValueError("known Make value requires content")
        object.__setattr__(self, "values", values)

    @property
    def text(self) -> str | None:
        """把值渲染为空格连接的字符串；unknown 恒为 None。"""
        return " ".join(self.values) if self.kind != "unknown" else None


@dataclass(frozen=True, slots=True)
class MakeParseResult:
    """Make 解析结果：记录、最终变量表、诊断与是否部分完成。"""

    records: tuple[BuildRecord, ...] = ()
    variables: tuple[tuple[str, MakeValue], ...] = ()
    diagnostics: tuple[str, ...] = ()
    partial: bool = False


@dataclass(slots=True)
class _ConditionFrame:
    """条件栈帧：记录 ifeq/ifdef 的求值结果与所处 else 状态。

    active 采用三值逻辑：任一外层为 False 或本分支未选中→False；
    存在 None（未知）→None；全为 True→True。
    """

    parent: bool | None
    result: bool | None
    in_else: bool
    group: str
    role: str

    @property
    def active(self) -> bool | None:
        selected = None if self.result is None else (not self.result if self.in_else else self.result)
        if self.parent is False or selected is False:
            return False
        if self.parent is None or selected is None:
            return None
        return True


def parse_make(source: str, path: str, target_arch: str, budgets: MakeBudgets = MakeBudgets(), *, sources=None) -> MakeParseResult:
    """解析单个 Makefile 文本为 BuildRecord 集合。

    主循环逐行状态机：
    - ifeq/ifneq/ifdef/ifndef 压栈求值，else 翻转，endif 弹栈；
    - 赋值行走 _evaluate_expression 有界展开（uname -m 符号化、
      变量替换、函数调用直接 unknown），!= shell 赋值仅识别 uname；
    - include 行与目标规则行各自产记录；
    - 非活动分支（active=False）内的赋值不写入变量表。
    sources 传入时可跨文件解析宏 recipe（parse_macro_recipes）。
    诊断非空即 partial。
    """
    encoded = source.encode("utf-8")
    tree = Parser(Language(tree_sitter_make.language())).parse(encoded)
    diagnostics: list[str] = []
    if tree.root_node.has_error:
        diagnostics.append(f"tree-sitter-make@{version('tree-sitter-make')}: partial syntax")
    records: list[BuildRecord] = []
    variables: dict[str, MakeValue] = {}
    frames: list[_ConditionFrame] = []
    expansions = 0

    for line_number, raw in enumerate(source.splitlines(), 1):
        stripped = raw.strip()
        if not stripped or stripped.startswith("#"):
            continue
        # 条件指令：求值后压栈；后续行的选中状态由整个栈的三值合取决定。
        condition = _CONDITION.match(raw)
        if condition:
            parent = _active(frames)
            result, role, reason = _evaluate_condition(condition.group(1), condition.group(2), variables, target_arch)
            group = f"{path}:{line_number}"
            frames.append(_ConditionFrame(parent, result, False, group, role))
            status = _status(frames)
            records.append(record_for_token("build_condition", stripped, line_number, raw, metadata={"conditionGroup": group, "selectionStatus": status, "role": role, "reason": reason or "condition"}, confidence="medium" if result is None else "high"))
            records.append(record_for_token("build_branch", stripped, line_number, raw, metadata={"conditionGroup": group, "selectionStatus": status, "role": role}))
            continue
        if stripped == "else" or stripped.startswith("else "):
            if not frames:
                diagnostics.append(f"{path}:{line_number}: unmatched else")
                continue
            frames[-1].in_else = True
            role = "fallback" if stripped == "else" else _role(stripped[5:])
            frames[-1].role = role
            records.append(record_for_token("build_branch", stripped, line_number, raw, metadata={"conditionGroup": frames[-1].group, "selectionStatus": _status(frames), "role": role}))
            continue
        if stripped == "endif":
            if frames:
                frames.pop()
            else:
                diagnostics.append(f"{path}:{line_number}: unmatched endif")
            continue

        active = _active(frames)
        # 当前行选中状态：栈合取 True→selected，False→inactive，None→unknown。
        status = SelectionStatus.SELECTED.value if active is True else SelectionStatus.INACTIVE.value if active is False else SelectionStatus.UNKNOWN.value
        condition_group = frames[-1].group if frames else None
        role = frames[-1].role if frames else "unconditional"
        assignment = _ASSIGNMENT.match(raw)
        if assignment:
            name, operator, expression = assignment.groups()
            metadata = {"variable": name, "operator": operator, "selectionStatus": status, "role": role}
            if not condition_group:
                metadata["target"] = "global"
            if condition_group:
                metadata["conditionGroup"] = condition_group
            value = _evaluate_expression(expression, variables, target_arch, budgets, expansions)
            expansions += expression.count("$(") + expression.count("${")
            if expansions > budgets.max_expansions:
                value = MakeValue("unknown", reason="max-expansions")
            probe = symbolic_uname(expression, target_arch)
            if probe.recognized:
                metadata["architectureProbe"] = "uname-m"
            if operator == "!=" and probe.recognized:
                # != 是 shell 赋值：唯一允许的形态是 uname -m（符号化为目标架构）。
                value = MakeValue("scalar", (probe.value or target_arch,))
            elif operator == "!=":
                value = MakeValue("unknown", reason="shell-assignment")
            if active is not False:
                # 只有活动分支内的赋值才更新变量表；inactive 分支的赋值被跳过。
                variables[name] = _assign(variables.get(name), value, operator)
            rendered = value.text if value.text is not None else expression
            if value.reason:
                metadata["reason"] = value.reason
                metadata["selectionStatus"] = SelectionStatus.UNKNOWN.value
            records.append(record_for_token("build_assignment", name, line_number, raw, metadata={**metadata, "expression": expression, "resolvedValue": rendered}, confidence="medium" if value.reason else "high"))
            if metadata.get("architectureProbe"):
                records.append(record_for_token("build_arch_probe", "uname -m", line_number, raw, metadata={**metadata, "resolvedValue": rendered}))
            derived_value = expression if value.reason else rendered
            records.extend(_derived_records(derived_value, line_number, raw, metadata, literal_status=status))
            continue

        include = _INCLUDE.match(raw)
        if include:
            expression = include.group(2).strip()
            value = _evaluate_expression(expression, variables, target_arch, budgets, expansions)
            metadata = {"optional": include.group(1).startswith("-") or include.group(1) == "sinclude", "selectionStatus": status, "role": role}
            if condition_group:
                metadata["conditionGroup"] = condition_group
            if value.reason:
                metadata.update({"reason": value.reason, "selectionStatus": SelectionStatus.UNKNOWN.value})
            records.append(record_for_token("build_include", value.text or expression, line_number, raw, metadata=metadata, confidence="medium" if value.reason else "high"))
            continue

        target = _TARGET.match(raw)
        if target and not raw.startswith("\t"):
            name = target.group(1).strip()
            metadata = {"selectionStatus": status, "role": role, "prerequisites": target.group(2) or ""}
            if condition_group:
                metadata["conditionGroup"] = condition_group
            records.append(record_for_token("build_target", name, line_number, raw, metadata=metadata))
            records.extend(_derived_records(target.group(2) or "", line_number, raw, {**metadata, "target": name}))

    for construct, line_number, text in unsafe_constructs(source, "make"):
        records.append(record_for_token("build_unsupported", construct, line_number, text, metadata={"selectionStatus": "unknown", "reason": construct}, confidence="medium"))
        diagnostics.append(f"{path}:{line_number}: {construct} unsupported/unknown")
    if frames:
        diagnostics.append(f"{path}: unterminated Make condition")
    # 跨文件宏 recipe 解析（编译命令行参数），与行级解析结果合并去重排序。
    records.extend(parse_macro_recipes(source, path, max_expansions=budgets.max_expansions,
                                      max_tokens=budgets.max_candidates, sources=sources, target_arch=target_arch))
    ordered = tuple(sorted({(item.kind, item.line, item.start_column, item.value, str(item.metadata)): item for item in records}.values(), key=lambda item: (item.line, item.start_column, item.kind, item.value)))
    return MakeParseResult(ordered, tuple(sorted(variables.items())), tuple(diagnostics), bool(diagnostics))


def resolve_make_includes(
    entry: str,
    root: Path,
    loader: Callable[[Path], str],
    target_arch: str,
    budgets: MakeBudgets = MakeBudgets(),
) -> MakeParseResult:
    """从入口 Makefile 出发递归解析 include 链，汇聚所有文件的记录。

    DFS（visit 递归）：防环（visited）、防逃逸（resolve 后必须仍在
    root 下）、防超限（深度/文件数预算）。动态 include（含 $ 或通配
    符）记为诊断而非展开。loader 由调用方注入读取函数。
    """
    records: list[BuildRecord] = []
    diagnostics: list[str] = []
    visited: set[str] = set()

    def visit(relative: str, depth: int) -> None:
        normalized = PurePosixPath(relative).as_posix()
        if normalized in visited:
            diagnostics.append(f"{normalized}: include cycle")
            return
        if depth > budgets.max_include_depth or len(visited) >= budgets.max_files:
            diagnostics.append(f"{normalized}: include budget exceeded")
            return
        candidate = (root / normalized).resolve()
        if not candidate.is_relative_to(root.resolve()):
            diagnostics.append(f"{normalized}: include escapes workspace")
            return
        visited.add(normalized)
        try:
            result = parse_make(loader(candidate), normalized, target_arch, budgets)
        except OSError as exc:
            diagnostics.append(f"{normalized}: include unreadable ({type(exc).__name__})")
            return
        records.extend(result.records)
        diagnostics.extend(result.diagnostics)
        for item in result.records:
            if item.kind != "build_include" or item.metadata.get("selectionStatus") == "inactive":
                continue
            value = item.value.strip()
            if "$" in value or any(char in value for char in "*?["):
                diagnostics.append(f"{normalized}:{item.line}: dynamic include")
                continue
            child = (PurePosixPath(normalized).parent / PurePosixPath(value)).as_posix()
            visit(child, depth + 1)

    visit(entry, 0)
    return MakeParseResult(tuple(records), (), tuple(diagnostics), bool(diagnostics))


def _evaluate_expression(expression: str, variables: dict[str, MakeValue], target_arch: str, budgets: MakeBudgets, expansions: int) -> MakeValue:
    """有界表达式求值：uname 符号化 → 变量替换 → token 数检查。

    顺序：先试符号化 uname -m；再排除 shell/eval/call/foreach/wildcard
    函数（直接 unknown）；然后逐个替换 $(VAR)/${VAR}，遇到未定义变量
    即 unknown；最后 token 数超限也 unknown。全部通过才得到 scalar/list。
    """
    expression = expression.strip()
    probe = symbolic_uname(expression, target_arch)
    if probe.recognized:
        return MakeValue("scalar", (probe.value or target_arch,))
    if re.search(r"\$\(\s*(?:shell|eval|call|foreach|wildcard)\b", expression, re.IGNORECASE):
        return MakeValue("unknown", reason="unsupported-function")
    if expansions > budgets.max_expansions:
        return MakeValue("unknown", reason="max-expansions")
    unknown: str | None = None

    def replace(match: re.Match[str]) -> str:
        nonlocal unknown
        name = (match.group(1) or match.group(2) or "").strip()
        value = variables.get(name)
        if value is None or value.text is None:
            unknown = f"unknown-variable:{name}"
            return match.group(0)
        return value.text

    rendered = _VARIABLE.sub(replace, expression)
    if unknown:
        return MakeValue("unknown", reason=unknown)
    tokens = tuple(rendered.split())
    if len(tokens) > budgets.max_candidates:
        return MakeValue("unknown", reason="max-candidates")
    return MakeValue("scalar" if len(tokens) == 1 else "list", tokens or ("",))


def _assign(previous: MakeValue | None, value: MakeValue, operator: str) -> MakeValue:
    """按赋值运算符合并新旧值：?=保留旧值，+=拼接列表，其余覆盖。"""
    if operator == "?=" and previous is not None:
        return previous
    if operator == "+=" and previous is not None and previous.text is not None and value.text is not None:
        return MakeValue("list", tuple((*previous.values, *value.values)))
    return value


def _evaluate_condition(kind: str, expression: str, variables: dict[str, MakeValue], target_arch: str) -> tuple[bool | None, str, str | None]:
    """求值单个 Make 条件，返回 (三值结果, 架构角色, unknown 原因)。

    ifdef/ifndef 查变量表；ifeq/ifneq 按逗号二分后两侧各自有界求值
    再比较。无法解析/求值时返回 None 并给出原因。
    """
    role = _role(expression)
    if kind in {"ifdef", "ifndef"}:
        present = expression.strip() in variables
        return (not present if kind == "ifndef" else present), role, None
    text = expression.strip()
    if text.startswith("(") and text.endswith(")"):
        text = text[1:-1]
    parts = [item.strip().strip('"\'') for item in text.split(",", 1)]
    if len(parts) != 2:
        return None, role, "unsupported-condition"
    left = _evaluate_expression(parts[0], variables, target_arch, MakeBudgets(), 0)
    right = _evaluate_expression(parts[1], variables, target_arch, MakeBudgets(), 0)
    if left.text is None or right.text is None:
        return None, role, left.reason or right.reason
    equal = left.text == right.text
    return (not equal if kind == "ifneq" else equal), role, None


def _active(frames: list[_ConditionFrame]) -> bool | None:
    """栈合取：所有帧 active 的三值 AND（False 优先于 None）。"""
    if not frames:
        return True
    states = [frame.active for frame in frames]
    if False in states:
        return False
    if None in states:
        return None
    return True


def _status(frames: list[_ConditionFrame]) -> str:
    """栈状态转为五态字符串：selected / inactive / unknown。"""
    active = _active(frames)
    return "selected" if active is True else "inactive" if active is False else "unknown"


def _role(value: str) -> str:
    """按文本判定架构角色：riscv > x86 > generic > unknown（词边界匹配）。"""
    if _RISCV.search(value):
        return "riscv"
    if _X86.search(value):
        return "x86"
    if _GENERIC.search(value):
        return "generic"
    return "unknown"


def _derived_records(
    value: str,
    line_number: int,
    raw: str,
    metadata: dict[str, object],
    *,
    literal_status: str | None = None,
) -> list[BuildRecord]:
    """从赋值/ prerequisite 文本派生子记录：-l* 依赖、-开关参数、源码文件、目录。

    token 先剥离引号/分号等包裹字符；literal_status 允许把字面量
    （不含 $）的选中状态从所在行继承下来，unknown-variable 原因随之清除。
    """
    records: list[BuildRecord] = []
    for token in value.split():
        clean = token.strip("'\";,()")
        if not clean:
            continue
        kind = None
        if clean.startswith("-"):
            kind = "build_dependency" if clean.startswith("-l") else "build_argument"
        elif clean.endswith(_SOURCE_SUFFIXES):
            kind = "build_source"
        elif clean.endswith("/"):
            kind = "build_directory"
        if kind:
            item_metadata = dict(metadata)
            if literal_status is not None and "$" not in clean:
                item_metadata["selectionStatus"] = literal_status
                if str(item_metadata.get("reason", "")).startswith("unknown-variable:"):
                    item_metadata.pop("reason", None)
            records.append(record_for_token(kind, clean, line_number, raw, metadata=item_metadata, confidence="medium" if item_metadata.get("selectionStatus") == "unknown" else "high"))
    return records
