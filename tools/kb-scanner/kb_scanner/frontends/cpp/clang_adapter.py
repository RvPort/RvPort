"""libclang 富化适配器：在 tree-sitter 基线之上补充精确语义证据。

frontend 阶段的第二解析通道：按编译数据库逐 TU 调用 libclang，
产出宏定义/展开、枚举声明与成员引用、函数语义、调用 token 等
高置信度证据（parse_mode=clang_enrichment）。libclang 不可用或
单 TU 解析失败只降级为 PARTIAL，绝不阻断 tree-sitter 基线；
所有 Cursor/Token 原生对象都在本模块内复制为标量后丢弃。
"""
from __future__ import annotations

import re
from dataclasses import replace
from pathlib import Path

from ...core import (
    CompilationUnitContext,
    CompletionStatus,
    Diagnostic,
    DiagnosticSeverity,
    Entity,
    Evidence,
    ScanContext,
    SourceLocation,
    SourceSpan,
    StageResult,
    WorkspaceSnapshot,
    merge_diagnostics,
)
from .arguments import normalize_clang_arguments
from .compilation_database import compilation_context_id
from .vocabulary import make_evidence
from .function_semantics import function_semantics
from .macro_records import clang_call_records
from .assert_profile import capture as capture_assert


CLANG_PRODUCER = "clang@18.1.3"
_IDENTIFIER = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


class ClangCppAdapter:
    """libclang 适配器：单个 enrich() 调用富化一个编译单元。

    不变量：产出证据的 subject 必须是 entities_by_path 中已注册的实体；
    证据按 ID 去重；TU 在返回前显式 del 释放原生资源。
    cindex 惰性加载（可注入 loader，便于测试与无 clang 环境）。
    """

    __slots__ = ("_cindex_loader",)

    def __init__(self, cindex_loader=None) -> None:
        """cindex_loader 返回 clang.cindex 模块；缺省为惰性 import。"""
        self._cindex_loader = cindex_loader or _load_cindex

    def enrich(
        self,
        context: ScanContext,
        workspace: WorkspaceSnapshot,
        unit: CompilationUnitContext,
        entities_by_path: dict[str, Entity],
        function_targets=None,
    ) -> StageResult[tuple[Evidence, ...]]:
        """解析单个 TU 并产出富化证据。

        流程：过滤编译参数 → libclang 解析（PARSER_DETAILED_PROCESSING_RECORD
        以获得宏信息）→ 收集 clang 诊断（路径脱敏）→ 深度优先遍历游标，
        按类别转译为证据（函数语义/枚举/宏定义附件/调用 token/assert
        profile）→ 按 ID 去重后返回。解析异常只降级 PARTIAL。
        function_targets 为基线函数名锚点，仅在精确命中时补语义。
        """
        argument_result = normalize_clang_arguments(unit, workspace.root)
        context_id = compilation_context_id(unit, workspace.root)
        diagnostics = argument_result.diagnostics
        translation_unit = None
        try:
            cindex = self._cindex_loader()
            index = cindex.Index.create()
            translation_unit = index.parse(
                str(unit.path),
                args=list(argument_result.accepted),
                options=cindex.TranslationUnit.PARSE_DETAILED_PROCESSING_RECORD,
            )
        except Exception as exc:  # noqa: BLE001 - per-TU degradation is required
            diagnostic = Diagnostic(
                code="clang.translation-unit-unavailable",
                stage="frontend.clang",
                severity=DiagnosticSeverity.WARNING,
                message=f"Clang enrichment unavailable for entry {unit.entry_index}: {type(exc).__name__}",
                producer=CLANG_PRODUCER,
                details={"compilationContextId": context_id, "entryIndex": unit.entry_index},
            )
            return StageResult(
                value=(),
                status=CompletionStatus.PARTIAL,
                diagnostics=merge_diagnostics(diagnostics, (diagnostic,)),
            )

        clang_diagnostics: list[Diagnostic] = []
        has_error = False
        for item in translation_unit.diagnostics:
            severity = _diagnostic_severity(item.severity, cindex)
            has_error = has_error or severity in {DiagnosticSeverity.ERROR, DiagnosticSeverity.FATAL}
            location = _clang_location(item.location, workspace.root)
            clang_diagnostics.append(
                Diagnostic(
                    code="clang.parse-diagnostic",
                    stage="frontend.clang",
                    severity=severity,
                    message=_redact_project_path(item.spelling, workspace.root),
                    location=location,
                    producer=CLANG_PRODUCER,
                    details={"compilationContextId": context_id, "entryIndex": unit.entry_index},
                )
            )

        evidence = []
        # USR → 证据的两张索引表，用于把枚举成员声明/引用绑回所属枚举声明。
        enum_declarations: dict[str, Evidence] = {}
        enum_members: dict[str, Evidence] = {}
        for cursor in _walk_cursor(translation_unit.cursor):
            function = function_semantics(cursor, workspace.root, entities_by_path,
                function_targets, context_id, CLANG_PRODUCER, has_error)
            if function is not None:
                evidence.append(function)
            kind = _cursor_kind(cursor)
            if kind is None or cursor.location.file is None:
                continue
            cursor_path = Path(str(cursor.location.file)).resolve()
            if not cursor_path.is_relative_to(workspace.root):
                continue
            relative_path = cursor_path.relative_to(workspace.root).as_posix()
            entity = entities_by_path.get(relative_path)
            if entity is None:
                continue
            span = _cursor_span(relative_path, cursor)
            if span is None:
                continue
            value = _cursor_value(cursor, kind, workspace.root)
            enum_metadata = _enum_cursor_metadata(cursor, kind, enum_declarations, enum_members, has_error)
            item = make_evidence(
                kind=kind,
                subject_id=entity.id,
                value=value,
                producer=CLANG_PRODUCER,
                parse_mode="clang_enrichment",
                confidence="high" if not has_error else "medium",
                span=span,
                context_id=context_id,
                metadata={"cursorKind": cursor.kind.name, "entryIndex": unit.entry_index, **enum_metadata,
                          **capture_assert(cursor, context_id, has_error)},
            )
            if kind in {'call_expression', 'macro_expansion'}:
                # 调用/宏展开：原样复制 token 并生成 macro_argument_token 附件证据。
                call_data, call_tokens = clang_call_records(cursor, item, lambda token:_token_span(relative_path, token))
                item = replace(item, metadata={**item.metadata, 'macroCall':call_data})
                evidence.extend(call_tokens)
            evidence.append(item)
            usr = _cursor_usr(cursor)
            if usr and kind == "enum_declaration":
                enum_declarations[usr] = item
            elif usr and kind == "enum_member_declaration":
                enum_members[usr] = item
            if kind == "macro_definition":
                attachments, unsupported = _macro_attachments(
                    cursor,
                    relative_path,
                    entity.id,
                    item,
                    context_id,
                    unit.entry_index,
                    has_error,
                )
                evidence.extend(attachments)
                if unsupported:
                    clang_diagnostics.append(
                        Diagnostic(
                            code="clang.unsupported-macro-semantics",
                            stage="frontend.clang",
                            severity=DiagnosticSeverity.WARNING,
                            message="Clang macro tokens require unsupported preprocessing semantics",
                            location=span.start,
                            producer=CLANG_PRODUCER,
                            details={
                                "compilationContextId": context_id,
                                "entryIndex": unit.entry_index,
                                "semantics": unsupported,
                            },
                        )
                    )
        deduplicated = {item.id: item for item in evidence}
        all_diagnostics = merge_diagnostics(diagnostics, clang_diagnostics)
        status = CompletionStatus.PARTIAL if argument_result.diagnostics or has_error or clang_diagnostics else CompletionStatus.COMPLETE
        del translation_unit
        return StageResult(value=tuple(deduplicated.values()), status=status, diagnostics=all_diagnostics)


def _load_cindex():
    """惰性导入 clang.cindex：无 clang 环境时只在真正 enrich 才失败。"""
    from clang import cindex

    return cindex


def _walk_cursor(root):
    """先序遍历游标树（栈实现，子节点倒序入栈保持源码顺序）。"""
    stack = [root]
    while stack:
        cursor = stack.pop()
        yield cursor
        stack.extend(reversed(list(cursor.get_children())))


def _cursor_kind(cursor) -> str | None:
    """把 clang 游标种类映射到 CPP_EVIDENCE_KINDS 中的 kind；不感兴趣的返回 None。

    DECL_REF_EXPR 特判：仅当其引用目标是枚举常量时才映为
    enum_member_reference，其余引用不产出。
    """
    name = cursor if isinstance(cursor, str) else cursor.kind.name
    direct = {
        "MACRO_DEFINITION": "macro_definition",
        "MACRO_INSTANTIATION": "macro_expansion",
        "MACRO_EXPANSION": "macro_expansion",
        "INCLUSION_DIRECTIVE": "include",
        "CALL_EXPR": "call_expression",
        "TYPE_REF": "type_reference",
        "FUNCTION_TEMPLATE": "template_declaration",
        "CLASS_TEMPLATE": "template_declaration",
        "ENUM_DECL": "enum_declaration",
        "ENUM_CONSTANT_DECL": "enum_member_declaration",
    }.get(name)
    if direct is not None:
        return direct
    if not isinstance(cursor, str) and name == "DECL_REF_EXPR" and getattr(getattr(cursor, "referenced", None), "kind", None) is not None:
        if cursor.referenced.kind.name == "ENUM_CONSTANT_DECL":
            return "enum_member_reference"
    return None


def _cursor_usr(cursor) -> str:
    """取游标 USR（统一符号表示）；不可用时返回空串而非抛错。"""
    try:
        return str(cursor.get_usr() or "")
    except (AttributeError, RuntimeError):
        return ""


def _enum_cursor_metadata(
    cursor,
    kind: str,
    declarations: dict[str, Evidence],
    members: dict[str, Evidence],
    parse_has_error: bool,
) -> dict[str, object]:
    """枚举类游标的附加元数据：成员声明/引用通过 USR 绑回所属枚举声明。

    绑定状态规则：解析有错 → partial；能通过 USR 找到目标证据 →
    complete（成员声明）或 partial；目标缺失时引用侧给出空 ID 列表。
    """
    if kind not in {"enum_declaration", "enum_member_declaration", "enum_member_reference"}:
        return {}
    binding = "partial" if parse_has_error else "complete"
    if kind == "enum_declaration":
        return {"enumName": cursor.spelling, "bindingStatus": binding, "semanticBinding": "clang"}
    target = cursor if kind == "enum_member_declaration" else getattr(cursor, "referenced", None)
    if target is None:
        return {"enumName": "", "memberName": cursor.spelling, "bindingStatus": "unknown", "semanticBinding": "clang"}
    parent = getattr(target, "semantic_parent", None)
    enum_name = str(getattr(parent, "spelling", "") or "")
    member_name = str(getattr(target, "spelling", "") or cursor.spelling)
    qualified = f"{enum_name}::{member_name}" if enum_name else member_name
    if kind == "enum_member_declaration":
        declaration = declarations.get(_cursor_usr(parent)) if parent is not None else None
        return {
            "enumName": enum_name,
            "memberName": member_name,
            "qualifiedName": qualified,
            "enumDeclarationEvidenceId": declaration.id if declaration is not None else "",
            "bindingStatus": binding if declaration is not None else "partial",
            "semanticBinding": "clang",
        }
    member = members.get(_cursor_usr(target))
    return {
        "enumName": enum_name,
        "memberName": member_name,
        "qualifiedName": qualified,
        "enumMemberDeclarationEvidenceIds": (member.id,) if member is not None else (),
        "bindingStatus": binding if member is not None else "partial",
        "referenceContext": "semantic_reference",
        "semanticBinding": "clang",
    }


def _cursor_span(relative_path: str, cursor) -> SourceSpan | None:
    """把游标 extent 换算为 SourceSpan；行列无效或跨度非法时返回 None。"""
    extent = cursor.extent
    if extent.start.line < 1 or extent.end.line < 1:
        return None
    start = SourceLocation(relative_path, extent.start.line, max(extent.start.column, 1))
    end = SourceLocation(relative_path, extent.end.line, max(extent.end.column, 1))
    try:
        return SourceSpan(start, end)
    except ValueError:
        return None


def _cursor_value(cursor, kind: str, workspace_root: Path) -> str:
    """取证据值（spelling/displayname 兜底）；include 的绝对路径改写为项目相对路径。"""
    value = cursor.spelling or cursor.displayname or cursor.kind.name
    if kind == "include":
        candidate = Path(value)
        if candidate.is_absolute():
            resolved = candidate.resolve()
            if resolved.is_relative_to(workspace_root):
                return resolved.relative_to(workspace_root).as_posix()
            return candidate.name
    return value


def _macro_attachments(
    cursor,
    relative_path: str,
    subject_id: str,
    definition: Evidence,
    context_id: str,
    entry_index: int,
    parse_has_error: bool,
) -> tuple[tuple[Evidence, ...], tuple[str, ...]]:
    """展开宏定义游标：产出 macro_parameter 与 macro_replacement_token 附件证据。

    思路：函数式宏先按括号配对定位参数表与替换体起点；再对替换体逐
    token 判定角色（参数引用/调用候选/数字/字符串/操作符）。出现 ##、
    #、...、__VA_ARGS__ 等不支持语义时记入 unsupported 集合并降低
    置信度，同时由调用方发 WARNING——绝不假装能展开这些宏。
    """
    try:
        tokens = tuple(cursor.get_tokens())
    except (AttributeError, RuntimeError):
        return (), ()
    if not tokens:
        return (), ()
    spellings = tuple(str(token.spelling) for token in tokens)
    function_like = len(tokens) > 1 and spellings[1] == "(" and _touching(tokens[0], tokens[1])
    parameter_indexes: list[int] = []
    body_index = 1
    unsupported: set[str] = set()
    if function_like:
        depth = 0
        close_index = None
        for index, spelling in enumerate(spellings[1:], start=1):
            if spelling == "(":
                depth += 1
            elif spelling == ")":
                depth -= 1
                if depth == 0:
                    close_index = index
                    break
            elif depth == 1 and (_IDENTIFIER.fullmatch(spelling) or spelling == "..."):
                parameter_indexes.append(index)
        if close_index is None:
            return (), ("unparseable-parameters",)
        body_index = close_index + 1
    parameter_names = {spellings[index] for index in parameter_indexes if spellings[index] != "..."}
    # 扫描替换体与参数表中的不支持语义：## 粘贴、# 字符串化、可变参数。
    for spelling in (*spellings[body_index:], *(spellings[index] for index in parameter_indexes)):
        if spelling in {"##", "#"}:
            unsupported.add("token-pasting" if spelling == "##" else "stringification")
        elif spelling in {"...", "__VA_ARGS__"}:
            unsupported.add("variadic")
    common = {
        "cursorKind": cursor.kind.name,
        "entryIndex": entry_index,
        "macroDefinitionEvidenceId": definition.id,
    }
    confidence = "medium" if parse_has_error or unsupported else "high"
    attachments: list[Evidence] = []
    for ordinal, token_index in enumerate(parameter_indexes):
        token = tokens[token_index]
        attachments.append(
            make_evidence(
                kind="macro_parameter",
                subject_id=subject_id,
                value=spellings[token_index],
                producer=CLANG_PRODUCER,
                parse_mode="clang_enrichment",
                confidence=confidence,
                span=_token_span(relative_path, token),
                context_id=context_id,
                metadata={
                    **common,
                    "ordinal": ordinal,
                    "tokenRole": "variadic" if spellings[token_index] == "..." else "parameter",
                },
            )
        )
    body_spellings = spellings[body_index:]
    for ordinal, token in enumerate(tokens[body_index:]):
        spelling = body_spellings[ordinal]
        next_spelling = body_spellings[ordinal + 1] if ordinal + 1 < len(body_spellings) else None
        attachments.append(
            make_evidence(
                kind="macro_replacement_token",
                subject_id=subject_id,
                value=spelling,
                producer=CLANG_PRODUCER,
                parse_mode="clang_enrichment",
                confidence=confidence,
                span=_token_span(relative_path, token),
                context_id=context_id,
                metadata={
                    **common,
                    "ordinal": ordinal,
                    "tokenRole": _clang_macro_token_role(spelling, next_spelling, parameter_names),
                    "unsupportedSemantics": tuple(sorted(unsupported)),
                },
            )
        )
    return tuple(attachments), tuple(sorted(unsupported))


def _touching(left, right) -> bool:
    """两 token 是否紧邻（左终点的行列 == 右起点的行列），用于识别函数式宏。"""
    return (
        left.extent.end.line,
        left.extent.end.column,
    ) == (
        right.extent.start.line,
        right.extent.start.column,
    )


def _token_span(relative_path: str, token) -> SourceSpan:
    """把 clang token 的 extent 换算为 SourceSpan。"""
    return SourceSpan(
        SourceLocation(relative_path, token.extent.start.line, token.extent.start.column),
        SourceLocation(relative_path, token.extent.end.line, token.extent.end.column),
    )


def _clang_macro_token_role(spelling: str, next_spelling: str | None, parameter_names: set[str]) -> str:
    """判定宏替换 token 的角色：参数引用 / 调用候选（后跟 '('）/ 标识符 / 数字 / 字符串 / 操作符。"""
    if spelling in parameter_names:
        return "parameter_reference"
    if _IDENTIFIER.fullmatch(spelling):
        return "call_candidate" if next_spelling == "(" else "identifier"
    if spelling[:1].isdigit():
        return "number"
    if spelling.startswith(('"', "'", 'u8"', 'u"', 'U"', 'L"')):
        return "string"
    return "operator"


def _clang_location(location, workspace_root: Path) -> SourceLocation | None:
    """把 clang 诊断位置换算为 SourceLocation；工作区外或行列无效返回 None。"""
    if location is None or location.file is None or location.line < 1:
        return None
    path = Path(str(location.file)).resolve()
    if not path.is_relative_to(workspace_root):
        return None
    return SourceLocation(path.relative_to(workspace_root).as_posix(), location.line, max(location.column, 1))


def _diagnostic_severity(value: int, cindex) -> DiagnosticSeverity:
    """把 clang 诊断严重级别映射为扫描器的 DiagnosticSeverity。"""
    if value >= cindex.Diagnostic.Fatal:
        return DiagnosticSeverity.FATAL
    if value >= cindex.Diagnostic.Error:
        return DiagnosticSeverity.ERROR
    if value >= cindex.Diagnostic.Warning:
        return DiagnosticSeverity.WARNING
    return DiagnosticSeverity.INFO


def _redact_project_path(message: str, workspace_root: Path) -> str:
    """诊断消息脱敏：项目根绝对路径替换为 $PROJECT，避免泄漏本机路径。"""
    return message.replace(str(workspace_root), "$PROJECT").replace(workspace_root.as_posix(), "$PROJECT")
