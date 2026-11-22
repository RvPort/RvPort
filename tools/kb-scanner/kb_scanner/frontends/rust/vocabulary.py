"""Rust 输入的领域词汇表：后缀集与 Entity/Evidence 工厂。"""
from __future__ import annotations

from pathlib import Path

from ...core import Entity, Evidence, SourceSpan, normalize_project_path, stable_id

IDENTITY_VERSION = 1
PRODUCER = "rust-frontend"

RUST_SUFFIXES = frozenset({".rs"})

# 单文件/累计预算：与 asm 域同量级；超大 generated Rust 文件降级 PARTIAL。
MAX_FILE_BYTES = 4 * 1024 * 1024
MAX_TOTAL_BYTES = 64 * 1024 * 1024

# 已知架构模块名（std/core::arch::<arch>）：riscv 系是目标，其余是源架构形态线索。
ARCH_MODULE_RISCV = {"riscv32", "riscv64", "riscv_shared"}


def is_rust_path(path: str) -> bool:
    """路径是否为 Rust 源文件（按后缀整词判定）。"""
    return Path(path).suffix.lower() in RUST_SUFFIXES


def make_rust_file_entity(relative_path: str, scanner_version: str) -> Entity:
    """为 Rust 输入文件构造 source_file 实体（与 cpp/asm 同 kind，家族分析可分桶）。"""
    path = normalize_project_path(relative_path)
    return Entity(
        id=stable_id("file", path),
        kind="source_file",
        name=path,
        properties={"identityVersion": IDENTITY_VERSION, "language": "rust"},
    )


def make_rust_evidence(
    *,
    subject_id: str,
    path: str,
    span: SourceSpan,
    kind: str,
    value: str,
    confidence: str = "medium",
    metadata: dict | None = None,
) -> Evidence:
    """一条 Rust 证据：kind 落在既有 CppEvidenceNormalizer 的 _KIND_MAP
    （preprocessor_condition / call_expression / type_reference / include /
    inline_asm），下游零改动。"""
    details = {"identityVersion": IDENTITY_VERSION, "language": "rust"}
    if metadata:
        details.update(metadata)
    return Evidence(
        id=stable_id("rust_evidence", IDENTITY_VERSION, kind, subject_id, value, span),
        kind=kind,
        subject_id=subject_id,
        value=value,
        producer=PRODUCER,
        parse_mode="tree-sitter-rust",
        confidence=confidence,
        location=span,
        metadata=details,
    )
