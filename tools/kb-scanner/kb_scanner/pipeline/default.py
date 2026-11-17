"""默认装配：生产环境使用的完整流水线与应用工厂。

create_default_profile 用各阶段的组合式/正式实现填满 PipelineProfile；
create_default_application 再包上 StagedContextFactory 与编排器，
是 CLI scan 命令背后的全部装配逻辑。
"""

from __future__ import annotations

from ..adapters.cli import StagedContextFactory
from ..assessors import MigrationAssessor
from ..classifiers import MigrationClassifier
from ..frontends.build import ProjectWorkspaceCollector
from ..knowledge import GeneratedFactsKnowledgeProvider
from ..projection import VersionedReportProjector
from .application import ScanApplication
from .composite import CompositeAnalyzer, CompositeDetector, CompositeNormalizer, CompositeProjectFrontend
from .orchestrator import StagedPipelineRunner
from .profile import ApplicationProfile, PipelineProfile


def create_default_profile() -> PipelineProfile:
    """构造默认八阶段流水线装配（各阶段均用生产实现）。"""
    return PipelineProfile(
        workspace_collector=ProjectWorkspaceCollector(),
        knowledge_provider=GeneratedFactsKnowledgeProvider(),
        frontend=CompositeProjectFrontend(),
        normalizer=CompositeNormalizer(),
        analyzer=CompositeAnalyzer(),
        detector=CompositeDetector(),
        classifier=MigrationClassifier(),
        assessor=MigrationAssessor(),
        report_projector=VersionedReportProjector(),
    )


def create_default_application() -> ScanApplication:
    """构造默认扫描应用：上下文工厂 + 默认流水线 + 编排器。"""
    return ScanApplication(ApplicationProfile(StagedContextFactory(), StagedPipelineRunner(create_default_profile())))
