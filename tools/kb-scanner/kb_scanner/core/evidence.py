"""证据（Evidence）层模型：实体、证据与关系，全部 frozen 不可变。

证据是流水线中对"某个实体具有某个事实取值"的原子记录，带溯源
（producer、knowledge_sources）与位置；Relation 把实体连成图，
其自身也由证据支撑。所有 ID 强制为 64 位小写 SHA-256（稳定ID）。
"""

from __future__ import annotations

import re
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any

from .location import SourceSpan
from .metadata import JsonValue, freeze_json, freeze_metadata


_STABLE_ID = re.compile(r"^[0-9a-f]{64}$")


def _validate_id(value: str, field_name: str = "id") -> None:
    """校验 value 是 64 位小写十六进制的 SHA-256 稳定ID。"""
    if not _STABLE_ID.fullmatch(value):
        raise ValueError(f"{field_name} must be a complete lowercase SHA-256")


def _validate_ids(values: tuple[str, ...], field_name: str) -> None:
    for value in values:
        _validate_id(value, field_name)


@dataclass(frozen=True, slots=True)
class Entity:
    """图谱节点：被分析的对象（函数、宏、类型等），id 为稳定ID。

    kind/name 唯一描述实体类别与名称；location 可为 None（如编译器内部
    合成的实体）；properties 冻结为不可变映射。
    """

    id: str
    kind: str
    name: str
    location: SourceSpan | None = None
    properties: Mapping[str, JsonValue] | None = None

    def __post_init__(self) -> None:
        _validate_id(self.id)
        if not self.kind or not self.name:
            raise ValueError("entity kind and name must not be empty")
        object.__setattr__(self, "properties", freeze_metadata(self.properties))


@dataclass(frozen=True, slots=True)
class Evidence:
    """证据：subject_id 实体取值 value 的一条原子观测记录。

    parse_mode 表示该证据来自哪种解析形态（如精确解析/宽松匹配/注释线索），
    下游据此决定可信度权重；producer 是产出该证据的分析器标识，
    knowledge_sources 记录支撑它的知识包来源（溯源）。
    """

    id: str
    kind: str
    subject_id: str
    value: JsonValue
    producer: str
    parse_mode: str
    confidence: str
    location: SourceSpan | None = None
    knowledge_sources: tuple[str, ...] = ()
    metadata: Mapping[str, JsonValue] | None = None

    def __post_init__(self) -> None:
        _validate_id(self.id)
        _validate_id(self.subject_id, "subject_id")
        if not all((self.kind, self.producer, self.parse_mode, self.confidence)):
            raise ValueError("evidence kind, producer, parse_mode and confidence must not be empty")
        object.__setattr__(self, "value", freeze_json(self.value, field_name="value"))
        object.__setattr__(self, "knowledge_sources", tuple(self.knowledge_sources))
        object.__setattr__(self, "metadata", freeze_metadata(self.metadata))


@dataclass(frozen=True, slots=True)
class Relation:
    """实体间的有向关系边（source→target），由证据列表支撑。

    status 采用五态（complete/partial/ambiguous/inactive/unknown），
    confidence 表示关系成立的可信度；id 与两端实体 id 均为稳定ID。
    """

    id: str
    kind: str
    source_id: str
    target_id: str
    evidence_ids: tuple[str, ...] = ()
    confidence: str = "unknown"
    status: str = "unknown"
    metadata: Mapping[str, JsonValue] | None = None

    def __post_init__(self) -> None:
        _validate_id(self.id)
        _validate_id(self.source_id, "source_id")
        _validate_id(self.target_id, "target_id")
        object.__setattr__(self, "evidence_ids", tuple(self.evidence_ids))
        _validate_ids(self.evidence_ids, "evidence_id")
        if not all((self.kind, self.confidence, self.status)):
            raise ValueError("relation kind, confidence and status must not be empty")
        object.__setattr__(self, "metadata", freeze_metadata(self.metadata))
