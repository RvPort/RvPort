"""Python/Cython 输入的领域词汇表：后缀集与 Entity/Evidence 工厂。"""
from __future__ import annotations

from pathlib import Path

from ...core import Entity, Evidence, SourceSpan, normalize_project_path, stable_id

IDENTITY_VERSION = 1
PRODUCER = "python-frontend"

PY_SUFFIXES = frozenset({".py"})
PYX_SUFFIXES = frozenset({".pyx"})

MAX_FILE_BYTES = 4 * 1024 * 1024
MAX_TOTAL_BYTES = 64 * 1024 * 1024


def is_python_path(path: str) -> bool:
    """路径是否为 Python 源文件（.py 走 AST；.pyx Cython 走行正则）。"""
    return Path(path).suffix.lower() in PY_SUFFIXES | PYX_SUFFIXES


def make_python_file_entity(relative_path: str, scanner_version: str) -> Entity:
    """为 Python/Cython 输入文件构造 source_file 实体（与各语言域同 kind）。"""
    path = normalize_project_path(relative_path)
    language = "cython" if path.lower().endswith(".pyx") else "python"
    return Entity(
        id=stable_id("file", path),
        kind="source_file",
        name=path,
        properties={"identityVersion": IDENTITY_VERSION, "language": language},
    )


def make_python_evidence(
    *,
    subject_id: str,
    path: str,
    span: SourceSpan,
    kind: str,
    value: str,
    confidence: str = "medium",
    metadata: dict | None = None,
) -> Evidence:
    """一条 Python/Cython 证据：kind 落在 CppEvidenceNormalizer 的 _KIND_MAP
    （call_expression / preprocessor_condition / selection_condition /
    include / inline_asm）。"""
    details = {"identityVersion": IDENTITY_VERSION, "language": "python"}
    if metadata:
        details.update(metadata)
    return Evidence(
        id=stable_id("python_evidence", IDENTITY_VERSION, kind, subject_id, value, span),
        kind=kind,
        subject_id=subject_id,
        value=value,
        producer=PRODUCER,
        parse_mode="tree-sitter-python",
        confidence=confidence,
        location=span,
        metadata=details,
    )
