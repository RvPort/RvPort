"""资质验证专用装配：只跑到 assessor、不做报告投影的流水线。

用于知识包/检测能力的自动化校验场景——projector 换成 _EmptyProjector
（恒返回空产物），其余阶段与默认装配一致。
"""

from __future__ import annotations

from ..assessors import MigrationAssessor
from ..classifiers import MigrationClassifier
from ..core import StageResult
from ..frontends.build import ProjectWorkspaceCollector
from ..knowledge import GeneratedFactsKnowledgeProvider
from .composite import CompositeAnalyzer, CompositeDetector, CompositeNormalizer, CompositeProjectFrontend
from .profile import PipelineProfile


class _EmptyProjector:
    """空投影器：跳过报告生成，project 恒返回空产物元组。"""

    __slots__ = ()

    def project(self, *args: object) -> StageResult[tuple]:
        del args
        return StageResult(())


def create_knowledge_detection_qualification_profile() -> PipelineProfile:
    """构造资质验证流水线：与默认装配相同，但投影阶段为空实现。"""
    return PipelineProfile(
        workspace_collector=ProjectWorkspaceCollector(),
        knowledge_provider=GeneratedFactsKnowledgeProvider(),
        frontend=CompositeProjectFrontend(),
        normalizer=CompositeNormalizer(),
        analyzer=CompositeAnalyzer(),
        detector=CompositeDetector(),
        classifier=MigrationClassifier(),
        assessor=MigrationAssessor(),
        report_projector=_EmptyProjector(),
    )
