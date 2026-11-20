"""分类（Classification）模型：classifier 阶段对观察的影响面定性。

Classification 把同一语义根因的观察归并为一个非破坏性解释，impact
限定为 IMPACT_CLASSES 五类（阻断/非阻断可处理/受保护不活跃/提示/未知）。
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass

from .evidence import _validate_id, _validate_ids
from .metadata import JsonValue, freeze_metadata


IMPACT_CLASSES = frozenset(
    {"blocking_issue", "actionable_non_blocking", "guarded_inactive", "hint", "unknown"}
)
# 影响面五类：blocking_issue（阻断）、actionable_non_blocking（可处理非阻断）、
# guarded_inactive（受保护不活跃）、hint（提示）、unknown（未知）。


@dataclass(frozen=True, slots=True)
class Classification:
    """A stable, non-destructive interpretation of one semantic observation root.

    分类（Classification）：对一个语义观察根因的稳定、非破坏性解释。
    policy_id 标识产出该分类的策略（溯源）；各类引用 ID 集合在
    __post_init__ 中统一去重排序并校验为稳定ID，impact 必须属于五类。
    """

    id: str
    policy_id: str
    impact: str
    reason: str
    observation_ids: tuple[str, ...] = ()
    evidence_ids: tuple[str, ...] = ()
    knowledge_ids: tuple[str, ...] = ()
    relation_ids: tuple[str, ...] = ()
    proof_path_ids: tuple[str, ...] = ()
    confidence: str = "unknown"
    metadata: Mapping[str, JsonValue] | None = None

    def __post_init__(self) -> None:
        _validate_id(self.id)
        if not all((self.policy_id, self.reason, self.confidence)):
            raise ValueError("classification identity, reason and confidence must not be empty")
        if self.impact not in IMPACT_CLASSES:
            raise ValueError(f"unsupported classification impact: {self.impact}")
        for field in ("observation_ids", "evidence_ids", "knowledge_ids", "relation_ids", "proof_path_ids"):
            values = tuple(sorted(set(getattr(self, field))))
            _validate_ids(values, field.removesuffix("s"))
            object.__setattr__(self, field, values)
        object.__setattr__(self, "metadata", freeze_metadata(self.metadata))
