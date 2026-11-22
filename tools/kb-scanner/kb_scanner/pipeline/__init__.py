"""pipeline 门面：八阶段流水线的编排与组装（re-export）。

ScanApplication 是对外入口，StagedPipelineRunner 负责按序执行八个阶段，
composite/default/profile/qualification 提供各阶段的组合与装配能力。
"""

from .application import ScanApplication
from .orchestrator import StagedPipelineRunner
from .profile import ApplicationProfile, PipelineProfile
from .qualification import create_knowledge_detection_qualification_profile
from .composite import CompositeAnalyzer, CompositeDetector, CompositeNormalizer, CompositeProjectFrontend
from .default import create_default_application, create_default_profile

__all__ = [
    "ApplicationProfile",
    "CompositeAnalyzer",
    "CompositeDetector",
    "CompositeNormalizer",
    "CompositeProjectFrontend",
    "PipelineProfile",
    "ScanApplication",
    "StagedPipelineRunner",
    "create_knowledge_detection_qualification_profile",
    "create_default_application",
    "create_default_profile",
]
