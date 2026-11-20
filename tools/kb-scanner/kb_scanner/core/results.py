"""流水线结果模型：各阶段快照与最终执行结果（全部 frozen 不可变）。

每个阶段一个 Snapshot（workspace/knowledge/frontend/normalized/
analysis/detection/classification/assessment），逐阶段携带上一层输出；
PipelineResult 汇总成功状态、退出码、诊断与产物引用。快照内部
统一把列表冻结为 tuple，防止下游阶段篡改上游结果。
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from typing import Generic, TypeVar

from .diagnostics import Diagnostic
from .evidence import Entity, Evidence, Relation
from .facts import NormalizedFact
from .knowledge import KnowledgeEntry, KnowledgeSource
from .classifications import Classification
from .observations import Assessment, Observation
from .paths import ProofPath


T = TypeVar("T")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")


class CompletionStatus(str, Enum):
    """阶段完成度：complete（完整）/ partial（部分）/ unavailable（不可用）/ failed（失败）。"""

    COMPLETE = "complete"
    PARTIAL = "partial"
    UNAVAILABLE = "unavailable"
    FAILED = "failed"


@dataclass(frozen=True, slots=True)
class StageResult(Generic[T]):
    """单个阶段的通用执行结果：值 + 完成度 + 诊断。

    不变式：status 为 complete/partial 时 value 必须非 None——
    部分完成也要求有可用值；失败/不可用阶段才允许值为空。
    """

    value: T | None
    status: CompletionStatus = CompletionStatus.COMPLETE
    diagnostics: tuple[Diagnostic, ...] = ()

    def __post_init__(self) -> None:
        object.__setattr__(self, "diagnostics", tuple(self.diagnostics))
        if self.status in {CompletionStatus.COMPLETE, CompletionStatus.PARTIAL} and self.value is None:
            raise ValueError("complete and partial stage results require a value")


@dataclass(frozen=True, slots=True)
class ArtifactRef:
    """产物引用：输出文件的名字、绝对路径与内容 SHA-256（可校验溯源）。"""

    name: str
    path: Path
    sha256: str

    def __post_init__(self) -> None:
        if not self.name:
            raise ValueError("artifact name must not be empty")
        object.__setattr__(self, "path", self.path.resolve())
        if not _SHA256.fullmatch(self.sha256):
            raise ValueError("artifact sha256 must be a complete lowercase digest")


@dataclass(frozen=True, slots=True)
class WorkspaceSnapshot:
    """workspace 阶段快照：项目根目录与纳入扫描的文件清单。"""

    root: Path
    files: tuple[str, ...]

    def __post_init__(self) -> None:
        object.__setattr__(self, "root", self.root.resolve())
        object.__setattr__(self, "files", tuple(self.files))


@dataclass(frozen=True, slots=True)
class KnowledgeSnapshot:
    """knowledge 阶段快照：知识包身份、版本、来源与条目。"""

    identity: str
    version: str
    sources: tuple[KnowledgeSource, ...] = ()
    entries: tuple[KnowledgeEntry, ...] = ()

    def __post_init__(self) -> None:
        object.__setattr__(self, "sources", tuple(self.sources))
        object.__setattr__(self, "entries", tuple(self.entries))


@dataclass(frozen=True, slots=True)
class FrontendSnapshot:
    """frontend 阶段快照：解析源码得到的实体与证据。"""

    entities: tuple[Entity, ...] = ()
    evidence: tuple[Evidence, ...] = ()

    def __post_init__(self) -> None:
        object.__setattr__(self, "entities", tuple(self.entities))
        object.__setattr__(self, "evidence", tuple(self.evidence))


@dataclass(frozen=True, slots=True)
class NormalizedSnapshot:
    """normalizer 阶段快照：实体/证据之上叠加归一化事实。"""

    entities: tuple[Entity, ...] = ()
    evidence: tuple[Evidence, ...] = ()
    facts: tuple[NormalizedFact, ...] = ()

    def __post_init__(self) -> None:
        object.__setattr__(self, "entities", tuple(self.entities))
        object.__setattr__(self, "evidence", tuple(self.evidence))
        object.__setattr__(self, "facts", tuple(self.facts))


@dataclass(frozen=True, slots=True)
class AnalysisSnapshot:
    """analyzer 阶段快照：关系图、补充实体与证明路径。"""

    relations: tuple[Relation, ...] = ()
    entities: tuple[Entity, ...] = ()
    proof_paths: tuple[ProofPath, ...] = ()

    def __post_init__(self) -> None:
        object.__setattr__(self, "relations", tuple(self.relations))
        object.__setattr__(self, "entities", tuple(self.entities))
        object.__setattr__(self, "proof_paths", tuple(self.proof_paths))


@dataclass(frozen=True, slots=True)
class DetectionSnapshot:
    """detector 阶段快照：产出的观察（候选发现）集合。"""

    observations: tuple[Observation, ...] = ()

    def __post_init__(self) -> None:
        object.__setattr__(self, "observations", tuple(self.observations))


@dataclass(frozen=True, slots=True)
class ClassificationSnapshot:
    """classifier 阶段快照：观察与其分类结论。"""

    observations: tuple[Observation, ...] = ()
    classifications: tuple[Classification, ...] = ()

    def __post_init__(self) -> None:
        object.__setattr__(self, "observations", tuple(self.observations))
        object.__setattr__(self, "classifications", tuple(self.classifications))


@dataclass(frozen=True, slots=True)
class AssessmentSnapshot:
    """assessor 阶段快照：最终评定集合。"""

    assessments: tuple[Assessment, ...] = ()

    def __post_init__(self) -> None:
        object.__setattr__(self, "assessments", tuple(self.assessments))


@dataclass(frozen=True, slots=True)
class PipelineResult:
    """整条流水线的最终结果：成功状态、退出码、诊断与产物引用。

    不变式：success 必须与 exit_code==0 一致，exit_code 不得为负。
    """

    success: bool
    exit_code: int
    status: CompletionStatus
    diagnostics: tuple[Diagnostic, ...] = ()
    artifacts: tuple[ArtifactRef, ...] = ()

    def __post_init__(self) -> None:
        object.__setattr__(self, "diagnostics", tuple(self.diagnostics))
        object.__setattr__(self, "artifacts", tuple(self.artifacts))
        if self.exit_code < 0:
            raise ValueError("exit_code must not be negative")
        if self.success != (self.exit_code == 0):
            raise ValueError("success must match exit_code")
