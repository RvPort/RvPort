"""阶段协议（Protocol）：八阶段流水线各组件的结构化接口契约。

每个协议声明一个阶段组件必须实现的方法签名（输入快照→StageResult），
pipeline 编排器依赖这些协议而非具体实现，便于替换与测试替身注入。
协议按流水线顺序排列：workspace→knowledge→frontend→normalizer→
analyzer→detector→classifier→assessor→projector。
"""

from __future__ import annotations

from typing import Protocol

from .context import ScanContext, ScanRequest
from .diagnostics import Diagnostic
from .results import (
    AnalysisSnapshot,
    ArtifactRef,
    AssessmentSnapshot,
    ClassificationSnapshot,
    DetectionSnapshot,
    FrontendSnapshot,
    KnowledgeSnapshot,
    NormalizedSnapshot,
    PipelineResult,
    StageResult,
    WorkspaceSnapshot,
)


class ScanContextFactory(Protocol):
    """workspace 阶段入口：把用户请求归一化为 ScanContext。"""

    def build(self, request: ScanRequest) -> StageResult[ScanContext]: ...


class PipelineRunner(Protocol):
    """流水线运行器：从 ScanContext 起跑完整流水线并返回最终结果。"""

    def run(self, context: ScanContext, initial_diagnostics: tuple[Diagnostic, ...] = ()) -> PipelineResult: ...


class WorkspaceCollector(Protocol):
    """workspace 阶段：枚举待扫描文件，产出 WorkspaceSnapshot。"""

    def collect(self, context: ScanContext) -> StageResult[WorkspaceSnapshot]: ...


class KnowledgeProvider(Protocol):
    """knowledge 阶段：加载并校验迁移知识包。"""

    def load(self, context: ScanContext) -> StageResult[KnowledgeSnapshot]: ...


class Frontend(Protocol):
    """frontend 阶段：解析源码，产出实体与证据。"""

    def analyze(self, context: ScanContext, workspace: WorkspaceSnapshot) -> StageResult[FrontendSnapshot]: ...


class Normalizer(Protocol):
    """normalizer 阶段：把证据收敛为归一化事实。"""

    def normalize(self, context: ScanContext, frontend: FrontendSnapshot) -> StageResult[NormalizedSnapshot]: ...


class Analyzer(Protocol):
    """analyzer 阶段：结合知识包做关系分析与证明路径搜索。"""

    def analyze(
        self,
        context: ScanContext,
        facts: NormalizedSnapshot,
        knowledge: KnowledgeSnapshot,
    ) -> StageResult[AnalysisSnapshot]: ...


class Detector(Protocol):
    """detector 阶段：基于事实/关系/知识产出观察（候选发现）。"""

    def detect(
        self,
        context: ScanContext,
        facts: NormalizedSnapshot,
        analysis: AnalysisSnapshot,
        knowledge: KnowledgeSnapshot,
    ) -> StageResult[DetectionSnapshot]: ...


class Classifier(Protocol):
    """classifier 阶段：对观察做影响面分类。"""

    def classify(
        self,
        context: ScanContext,
        detections: DetectionSnapshot,
        analysis: AnalysisSnapshot,
    ) -> StageResult[ClassificationSnapshot]: ...


class Assessor(Protocol):
    """assessor 阶段：对分类结果做最终评定。"""

    def assess(
        self,
        context: ScanContext,
        classifications: ClassificationSnapshot,
        analysis: AnalysisSnapshot,
    ) -> StageResult[AssessmentSnapshot]: ...


class ReportProjector(Protocol):
    """projector 阶段：把八阶段全部快照投影为输出产物（报告文件等）。"""

    def project(
        self,
        context: ScanContext,
        workspace: WorkspaceSnapshot,
        knowledge: KnowledgeSnapshot,
        frontend: FrontendSnapshot,
        facts: NormalizedSnapshot,
        analysis: AnalysisSnapshot,
        detections: DetectionSnapshot,
        classifications: ClassificationSnapshot,
        assessments: AssessmentSnapshot,
        diagnostics: tuple[Diagnostic, ...],
    ) -> StageResult[tuple[ArtifactRef, ...]]: ...
