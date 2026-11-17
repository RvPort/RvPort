"""观察（Observation）与评定（Assessment）模型：detector/assessor 阶段输出。

Observation 是 detector 从证据/关系图中识别出的"疑似迁移问题"候选；
Assessment 是 assessor 阶段对发现的最终评定（引用分类、观察与证明路径）。
两者均 frozen 不可变，所有引用 ID 均为稳定ID。
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass

from .evidence import _validate_id, _validate_ids
from .metadata import JsonValue, freeze_metadata


@dataclass(frozen=True, slots=True)
class Observation:
    """观察：detector 产出的候选发现，由证据/关系集合支撑。

    status 表征候选的确认状态（candidate 表示待后续阶段评定），
    detector_id 标识产出它的检测器（溯源），knowledge_ids 去重排序。
    """

    id: str
    detector_id: str
    kind: str
    evidence_ids: tuple[str, ...] = ()
    relation_ids: tuple[str, ...] = ()
    confidence: str = "unknown"
    status: str = "candidate"
    metadata: Mapping[str, JsonValue] | None = None
    knowledge_ids: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        _validate_id(self.id)
        if not all((self.detector_id, self.kind, self.confidence, self.status)):
            raise ValueError("observation fields must not be empty")
        object.__setattr__(self, "evidence_ids", tuple(self.evidence_ids))
        object.__setattr__(self, "relation_ids", tuple(self.relation_ids))
        object.__setattr__(self, "knowledge_ids", tuple(sorted(set(self.knowledge_ids))))
        _validate_ids(self.evidence_ids, "evidence_id")
        _validate_ids(self.relation_ids, "relation_id")
        _validate_ids(self.knowledge_ids, "knowledge_id")
        object.__setattr__(self, "metadata", freeze_metadata(self.metadata))


@dataclass(frozen=True, slots=True)
class Assessment:
    """评定：assessor 阶段对某个发现的最终结论（state 为五态之一）。

    汇聚引用 classification_ids（分类结论）、observation_ids（支撑观察）
    与 proof_path_ids（证明路径），构成完整的溯源链条。
    """

    id: str
    kind: str
    state: str
    evidence_ids: tuple[str, ...] = ()
    relation_ids: tuple[str, ...] = ()
    confidence: str = "unknown"
    metadata: Mapping[str, JsonValue] | None = None
    classification_ids: tuple[str, ...] = ()
    observation_ids: tuple[str, ...] = ()
    proof_path_ids: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        _validate_id(self.id)
        if not all((self.kind, self.state, self.confidence)):
            raise ValueError("assessment kind, state and confidence must not be empty")
        object.__setattr__(self, "evidence_ids", tuple(self.evidence_ids))
        object.__setattr__(self, "relation_ids", tuple(self.relation_ids))
        object.__setattr__(self, "classification_ids", tuple(sorted(set(self.classification_ids))))
        object.__setattr__(self, "observation_ids", tuple(sorted(set(self.observation_ids))))
        object.__setattr__(self, "proof_path_ids", tuple(sorted(set(self.proof_path_ids))))
        _validate_ids(self.evidence_ids, "evidence_id")
        _validate_ids(self.relation_ids, "relation_id")
        _validate_ids(self.classification_ids, "classification_id")
        _validate_ids(self.observation_ids, "observation_id")
        _validate_ids(self.proof_path_ids, "proof_path_id")
        object.__setattr__(self, "metadata", freeze_metadata(self.metadata))
