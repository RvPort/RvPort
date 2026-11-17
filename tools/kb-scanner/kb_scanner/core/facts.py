"""归一化事实（Fact）模型：normalizer 阶段的输出单元。

NormalizedFact 把多条证据收敛为关于某个实体的一个规范取值，是后续
detector/classifier 阶段的输入。每条事实必须有至少一条证据支撑，
证据与上下文 ID 均去重排序后冻结。
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass

from .evidence import _validate_id, _validate_ids
from .metadata import JsonValue, freeze_metadata


def _unique_sorted(values: tuple[str, ...]) -> tuple[str, ...]:
    """去重并排序，保证 ID 集合的规范化表示（利于稳定比较）。"""
    return tuple(sorted(set(values)))


@dataclass(frozen=True, slots=True)
class NormalizedFact:
    """事实：subject_id 实体具有规范取值 canonical_value（frozen 不可变）。

    scope 表示事实生效的范围（如 translation-unit/global）；
    evidence_ids 必须非空（无证据不成事实），context_ids 可选，
    producers 记录产出该事实的分析器（溯源）。
    """

    id: str
    kind: str
    subject_id: str
    canonical_value: str
    scope: str
    evidence_ids: tuple[str, ...]
    context_ids: tuple[str, ...] = ()
    producers: tuple[str, ...] = ()
    metadata: Mapping[str, JsonValue] | None = None

    def __post_init__(self) -> None:
        _validate_id(self.id)
        _validate_id(self.subject_id, "subject_id")
        if not all((self.kind, self.canonical_value, self.scope)):
            raise ValueError("normalized fact fields must not be empty")
        evidence_ids = _unique_sorted(tuple(self.evidence_ids))
        if not evidence_ids:
            raise ValueError("normalized fact requires at least one evidence reference")
        context_ids = _unique_sorted(tuple(self.context_ids))
        _validate_ids(evidence_ids, "evidence_id")
        _validate_ids(context_ids, "context_id")
        object.__setattr__(self, "evidence_ids", evidence_ids)
        object.__setattr__(self, "context_ids", context_ids)
        object.__setattr__(self, "producers", _unique_sorted(tuple(self.producers)))
        object.__setattr__(self, "metadata", freeze_metadata(self.metadata))
