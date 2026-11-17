"""流水线装配描述（Profile）：声明一个应用/流水线由哪些组件构成。

ApplicationProfile 与 PipelineProfile 均为 frozen dataclass，字段类型
是 core.protocols 中的协议——装配只依赖接口契约，具体实现可自由替换。
"""

from __future__ import annotations

from dataclasses import dataclass

from ..core.protocols import (
    Analyzer,
    Assessor,
    Classifier,
    Detector,
    Frontend,
    KnowledgeProvider,
    Normalizer,
    PipelineRunner,
    ReportProjector,
    ScanContextFactory,
    WorkspaceCollector,
)


@dataclass(frozen=True, slots=True)
class ApplicationProfile:
    """应用级装配：上下文工厂 + 流水线运行器（两者都不可为 None）。"""

    context_factory: ScanContextFactory
    runner: PipelineRunner

    def __post_init__(self) -> None:
        if self.context_factory is None or self.runner is None:
            raise ValueError("application profile requires a context factory and runner")


@dataclass(frozen=True, slots=True)
class PipelineProfile:
    """八阶段组件装配：每个阶段一个组件，全部必填（None 即拒绝）。

    字段顺序即流水线顺序：workspace→knowledge→frontend→normalizer→
    analyzer→detector→classifier→assessor→projector。
    """

    workspace_collector: WorkspaceCollector
    knowledge_provider: KnowledgeProvider
    frontend: Frontend
    normalizer: Normalizer
    analyzer: Analyzer
    detector: Detector
    classifier: Classifier
    assessor: Assessor
    report_projector: ReportProjector

    def __post_init__(self) -> None:
        for name in self.__dataclass_fields__:
            if getattr(self, name) is None:
                raise ValueError(f"pipeline profile component {name} must not be None")
