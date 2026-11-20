"""构建 frontend 的领域词汇表：BuildRecord / Entity / Evidence 构造。

定义构建输入类型枚举、构建类证据 kind 集合，以及把解析出的
BuildRecord 统一包装成核心 Entity / Evidence（含稳定ID）的工厂函数。
"""
from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from enum import Enum
from typing import Any

from ...core import Entity, Evidence, SourceLocation, SourceSpan, normalize_project_path, stable_id


IDENTITY_VERSION = 1


class BuildInputKind(str, Enum):
    """构建输入类型：决定由哪个解析器处理及 producer 标识。"""
    MAKE = "make"
    SHELL = "shell"
    M4 = "m4"
    CMAKE = "cmake"
    CMAKE_SOURCE = "cmake-source"
    MESON = "meson"
    CTEST = "ctest"
    COMPILATION_DATABASE = "compilation-database"
    RPM = "rpm"
    GORELEASER = "goreleaser"


class SelectionStatus(str, Enum):
    """构建元素选中状态：selected=目标架构选中，inactive=未选中，not_selected=测试等显式未选，unknown=无法判定。"""
    SELECTED = "selected"
    INACTIVE = "inactive"
    NOT_SELECTED = "not_selected"
    UNKNOWN = "unknown"


BUILD_EVIDENCE_KINDS = frozenset(
    {
        "build_assignment",
        "build_condition",
        "build_branch",
        "build_include",
        "build_target",
        "build_source",
        "build_directory",
        "build_argument",
        "build_dependency",
        "build_artifact",
        "build_arch_probe",
        "build_configuration",
        "build_package_section",
        "build_arch_constraint",
        "build_unsupported",
    }
)


@dataclass(frozen=True, slots=True)
class BuildRecord:
    """解析中间产物：一次构建语法元素的出现记录（frozen 不可变）。

    各解析器产出的最小单元，随后由 make_build_evidence 包装为 Evidence。
    位置为 1-based 的行列坐标；kind 必须属于 BUILD_EVIDENCE_KINDS。
    """

    kind: str
    value: str
    line: int
    start_column: int
    end_column: int
    metadata: Mapping[str, Any] | None = None
    confidence: str = "high"

    def __post_init__(self) -> None:
        """校验 kind/value/位置/confidence 合法性，非法即抛 ValueError。"""
        if self.kind not in BUILD_EVIDENCE_KINDS:
            raise ValueError(f"unsupported build evidence kind: {self.kind}")
        if not self.value:
            raise ValueError("build record value must not be empty")
        if self.line < 1 or self.start_column < 1 or self.end_column < self.start_column:
            raise ValueError("invalid build record location")
        if self.confidence not in {"high", "medium", "low"}:
            raise ValueError("unsupported build record confidence")


def make_build_file_entity(relative_path: str, input_kind: BuildInputKind, scanner_version: str) -> Entity:
    """为构建输入文件构造 build_file Entity；ID 为基于路径+类型+版本的稳定ID（SHA-256）。"""
    path = normalize_project_path(relative_path)
    return Entity(
        id=stable_id("build_file", IDENTITY_VERSION, path, input_kind.value, scanner_version),
        kind="build_file",
        name=path,
        properties={"identityVersion": IDENTITY_VERSION, "inputKind": input_kind.value},
    )


def make_build_evidence(
    record: BuildRecord,
    *,
    path: str,
    subject_id: str,
    producer: str,
    input_kind: BuildInputKind,
) -> Evidence:
    """把 BuildRecord 包装为核心 Evidence。

    - parse_mode 固定为 "static-build"（静态构建分析，非执行观察）。
    - Evidence ID 是基于 kind/主体/值/位置/producer/metadata 的稳定ID，
      相同输入必然得到相同 ID。
    - metadata 合并 record.metadata 后补充 identityVersion 与 inputKind。
    """
    normalized_path = normalize_project_path(path)
    span = SourceSpan(
        SourceLocation(normalized_path, record.line, record.start_column),
        SourceLocation(normalized_path, (record.metadata or {}).get("endLine", record.line),
                       (record.metadata or {}).get("endColumn", record.end_column)),
    )
    metadata = dict(record.metadata or {})
    metadata.update({"identityVersion": IDENTITY_VERSION, "inputKind": input_kind.value})
    return Evidence(
        id=stable_id(
            "build_evidence",
            IDENTITY_VERSION,
            record.kind,
            subject_id,
            record.value,
            span,
            producer,
            metadata,
        ),
        kind=record.kind,
        subject_id=subject_id,
        value=record.value,
        producer=producer,
        parse_mode="static-build",
        confidence=record.confidence,
        location=span,
        metadata=metadata,
    )


def record_for_token(
    kind: str,
    value: str,
    line: int,
    line_text: str,
    *,
    metadata: Mapping[str, Any] | None = None,
    confidence: str = "high",
) -> BuildRecord:
    """以行内 token 定位构造 BuildRecord：在行文本中查找 value 的列偏移。

    找不到（如 value 是摘要值而非原文）时列号从 1 开始。
    """
    index = line_text.find(value)
    start = index + 1 if index >= 0 else 1
    return BuildRecord(kind, value, line, start, start + len(value), metadata, confidence)
