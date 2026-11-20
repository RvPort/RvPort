"""frontend 阶段的输出词表与证据构造工具。

定义 C/C++ 前端允许产出的全部 Evidence kind（CPP_EVIDENCE_KINDS），
以及实体/证据的统一构造函数与稳定排序键。所有证据的稳定ID
（stable_id，SHA-256）都在这里基于 kind、主体、值、跨度、producer、
parse_mode、编译上下文确定地生成，保证相同输入可复现相同 ID。
"""
from __future__ import annotations

from collections.abc import Mapping, Sequence
from typing import Any

from ...core import Entity, Evidence, SourceSpan, stable_id


IDENTITY_VERSION = 1
CPP_EVIDENCE_KINDS = frozenset(
    {
        "usage_scope",
        "preprocessor_condition",
        "preprocessor_branch",
        "include",
        "macro_definition",
        "macro_expansion",
        "macro_parameter",
        "macro_argument_token",
        "macro_replacement_token",
        "call_expression",
        "type_reference",
        "template_declaration",
        "constexpr_condition",
        "selection_condition",
        "enum_declaration",
        "enum_member_declaration",
        "enum_member_reference",
        "inline_asm",
        "declaration_attribute",
        "function_declaration",
        "function_semantics",
        "comment",
    }
)


def make_source_file_entity(relative_path: str, languages: Sequence[str], scanner_version: str) -> Entity:
    """为单个源文件构造 source_file 实体；ID 由路径+语言候选+扫描器版本派生。"""
    normalized_languages = tuple(sorted(set(languages)))
    return Entity(
        id=stable_id("source_file", IDENTITY_VERSION, relative_path, normalized_languages, scanner_version),
        kind="source_file",
        name=relative_path,
        properties={
            "identityVersion": IDENTITY_VERSION,
            "languageCandidates": normalized_languages,
        },
    )


def make_evidence(
    *,
    kind: str,
    subject_id: str,
    value: Any,
    producer: str,
    parse_mode: str,
    confidence: str,
    span: SourceSpan,
    metadata: Mapping[str, Any] | None = None,
    context_id: str | None = None,
) -> Evidence:
    """构造 Evidence 的唯一入口：校验 kind 合法并统一注入 identityVersion 与编译上下文。

    parse_mode 标识产出方式（tree_sitter_baseline / clang_enrichment）；
    context_id 非空时写入 compilationContextId，用于把证据挂到具体
    编译上下文（溯源/复现依赖它）。
    """
    if kind not in CPP_EVIDENCE_KINDS:
        raise ValueError(f"unsupported C/C++ evidence kind: {kind}")
    details = dict(metadata or {})
    details["identityVersion"] = IDENTITY_VERSION
    if context_id is not None:
        details["compilationContextId"] = context_id
    return Evidence(
        id=stable_id(
            "evidence",
            IDENTITY_VERSION,
            kind,
            subject_id,
            value,
            span,
            producer,
            parse_mode,
            context_id,
        ),
        kind=kind,
        subject_id=subject_id,
        value=value,
        producer=producer,
        parse_mode=parse_mode,
        confidence=confidence,
        location=span,
        metadata=details,
    )


def entity_sort_key(entity: Entity) -> tuple[str, str, str]:
    """实体排序键：kind → name → id，保证 frontend 快照输出确定有序。"""
    return entity.kind, entity.name, entity.id


def evidence_sort_key(evidence: Evidence) -> tuple[object, ...]:
    """证据排序键：位置 → kind 优先级 → producer → 上下文 → ID。

    无位置的排最前；enum/条件类 kind 赋予低于默认值(10)的优先级，
    确保声明先于引用出现，下游归并可做单遍合并。
    """
    if evidence.location is None:
        location: tuple[object, ...] = ("", 0, 0, 0, 0)
    else:
        location = (
            evidence.location.start.path,
            evidence.location.start.line,
            evidence.location.start.column or 0,
            evidence.location.end.line,
            evidence.location.end.column or 0,
        )
    context_id = str(evidence.metadata.get("compilationContextId", ""))
    kind_order = {
        "enum_declaration": 0,
        "enum_member_declaration": 1,
        "constexpr_condition": 2,
        "selection_condition": 2,
        "enum_member_reference": 3,
    }
    return (*location, kind_order.get(evidence.kind, 10), evidence.kind, evidence.producer, context_id, evidence.id)
