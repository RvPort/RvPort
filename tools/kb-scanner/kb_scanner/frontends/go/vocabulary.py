"""Go 输入的领域词汇表：后缀集与 Entity/Evidence 工厂。"""
from __future__ import annotations

from pathlib import Path

from ...core import Entity, Evidence, SourceSpan, normalize_project_path, stable_id

IDENTITY_VERSION = 1
PRODUCER = "go-frontend"

GO_SUFFIXES = frozenset({".go"})

MAX_FILE_BYTES = 4 * 1024 * 1024
MAX_TOTAL_BYTES = 64 * 1024 * 1024


def is_go_path(path: str) -> bool:
    """路径是否为 Go 源文件（按后缀整词判定；_arch.go 排除不做——家族词法层处理）。"""
    return Path(path).suffix.lower() in GO_SUFFIXES


def make_go_file_entity(relative_path: str, scanner_version: str) -> Entity:
    """为 Go 输入文件构造 source_file 实体（与 cpp/asm/rust 同 kind）。"""
    path = normalize_project_path(relative_path)
    return Entity(
        id=stable_id("file", path),
        kind="source_file",
        name=path,
        properties={"identityVersion": IDENTITY_VERSION, "language": "go"},
    )


def make_go_evidence(
    *,
    subject_id: str,
    path: str,
    span: SourceSpan,
    kind: str,
    value: str,
    confidence: str = "medium",
    metadata: dict | None = None,
) -> Evidence:
    """一条 Go 证据：kind 落在 CppEvidenceNormalizer 的 _KIND_MAP
    （preprocessor_condition / selection_condition / call_expression /
    inline_asm / include）。"""
    details = {"identityVersion": IDENTITY_VERSION, "language": "go"}
    if metadata:
        details.update(metadata)
    return Evidence(
        id=stable_id("go_evidence", IDENTITY_VERSION, kind, subject_id, value, span),
        kind=kind,
        subject_id=subject_id,
        value=value,
        producer=PRODUCER,
        parse_mode="tree-sitter-go",
        confidence=confidence,
        location=span,
        metadata=details,
    )
