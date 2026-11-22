"""汇编输入的领域词汇表：后缀集、语言开关与 Entity/Evidence 工厂。"""
from __future__ import annotations

from pathlib import Path

from ...core import Entity, Evidence, SourceLocation, SourceSpan, normalize_project_path, stable_id

IDENTITY_VERSION = 1
PRODUCER = "asm-frontend"

# 汇编源后缀：.s（小写，GNU 惯例不预处理）、.S（大写，含 C 预处理）、.asm（NASM/MSVC 风格）。
# 不含 .inc——已是 C 头文件语义（HEADER_SUFFIXES），保持现状。
ASM_SUFFIXES = frozenset({".s", ".S", ".asm"})

# 单文件/累计/行数预算：超大汇编（内核 style 数十万行）只降级 PARTIAL 不拖垮扫描。
MAX_FILE_BYTES = 1024 * 1024
MAX_TOTAL_BYTES = 32 * 1024 * 1024
MAX_LINES = 50000


def is_assembly_path(path: str) -> bool:
    """路径（文件名或相对路径）是否为汇编源文件（按后缀整词判定）。"""
    return Path(path).suffix.lower() in ASM_SUFFIXES


def make_asm_file_entity(relative_path: str, scanner_version: str) -> Entity:
    """为汇编输入文件构造 source_file Entity（与 cpp frontend 同 kind，家族分析可分桶）。"""
    path = normalize_project_path(relative_path)
    return Entity(
        id=stable_id("file", path),
        kind="source_file",
        name=path,
        properties={"identityVersion": IDENTITY_VERSION, "language": "asm"},
    )


def make_asm_evidence(
    *,
    subject_id: str,
    path: str,
    line: int,
    start_column: int,
    end_column: int,
    value: str,
    byte_offset: int,
    confidence: str = "medium",
) -> Evidence:
    """一条汇编语句证据：kind=inline_asm（复用既有 ASM 检测链）。

    位置为 1-based 行列；行首字节偏移进 metadata（asmByteOffset），
    相同输入必然得到相同稳定 ID。
    """
    normalized_path = normalize_project_path(path)
    span = SourceSpan(
        SourceLocation(normalized_path, line, start_column),
        SourceLocation(normalized_path, line, end_column),
    )
    return Evidence(
        id=stable_id("asm_evidence", IDENTITY_VERSION, subject_id, value, span),
        kind="inline_asm",
        subject_id=subject_id,
        value=value,
        producer=PRODUCER,
        parse_mode="lexical-line",
        confidence=confidence,
        location=span,
        metadata={"identityVersion": IDENTITY_VERSION, "asmByteOffset": byte_offset},
    )
