"""core 门面：集中 re-export 流水线各阶段共享的数据合同类型。

这些类型（事实/证据/观察/发现、快照、稳定ID 等）是八个阶段之间的
公共语言，全部为 frozen dataclass，保证跨阶段传递时不可变。
"""

from .context import CompilationUnitContext, ScanContext, ScanRequest
from .classifications import Classification, IMPACT_CLASSES
from .diagnostics import Diagnostic, DiagnosticSeverity, merge_diagnostics
from .evidence import Entity, Evidence, Relation
from .facts import NormalizedFact
from .ids import stable_id
from .knowledge import KnowledgeEntry, KnowledgeSource
from .location import SourceLocation, SourceSpan, normalize_project_path
from .observations import Assessment, Observation
from .paths import PathSearchResult, ProofPath
from .test_vocabulary import (
    CoverageStatus,
    TestRole,
    TestRoleEvidence,
    TestRoleStrength,
    TestSelectionStatus,
    candidate_test_path,
    explicit_test_target,
)
from .results import (
    AnalysisSnapshot,
    ArtifactRef,
    AssessmentSnapshot,
    ClassificationSnapshot,
    CompletionStatus,
    DetectionSnapshot,
    FrontendSnapshot,
    KnowledgeSnapshot,
    NormalizedSnapshot,
    PipelineResult,
    StageResult,
    WorkspaceSnapshot,
)

__all__ = [
    "AnalysisSnapshot",
    "ArtifactRef",
    "Assessment",
    "AssessmentSnapshot",
    "Classification",
    "ClassificationSnapshot",
    "CompilationUnitContext",
    "CompletionStatus",
    "DetectionSnapshot",
    "Diagnostic",
    "DiagnosticSeverity",
    "Entity",
    "Evidence",
    "FrontendSnapshot",
    "IMPACT_CLASSES",
    "KnowledgeSnapshot",
    "KnowledgeEntry",
    "KnowledgeSource",
    "NormalizedFact",
    "NormalizedSnapshot",
    "Observation",
    "PipelineResult",
    "PathSearchResult",
    "ProofPath",
    "Relation",
    "ScanContext",
    "ScanRequest",
    "SourceLocation",
    "SourceSpan",
    "StageResult",
    "CoverageStatus",
    "TestRole",
    "TestRoleEvidence",
    "TestRoleStrength",
    "TestSelectionStatus",
    "WorkspaceSnapshot",
    "merge_diagnostics",
    "candidate_test_path",
    "explicit_test_target",
    "normalize_project_path",
    "stable_id",
]
