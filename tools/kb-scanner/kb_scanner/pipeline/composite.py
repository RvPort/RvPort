"""组合式阶段实现：把多个子前端/归一化器/分析器/检测器装配成一个阶段。

每个 Composite* 类实现 core.protocols 中对应协议，内部按依赖顺序调用
子组件并合并结果：诊断去重合并、实体/证据/事实按稳定ID 去重排序、
任一子组件失败即整阶段失败。这是 default 装配的骨架。
"""

from __future__ import annotations

from ..analyzers import BuildSelectionAnalyzer, EvidenceGraphAnalyzer, TestAssetAnalyzer, TestSelectionAnalyzer
from ..core import (
    AnalysisSnapshot,
    CompletionStatus,
    DetectionSnapshot,
    FrontendSnapshot,
    KnowledgeSnapshot,
    NormalizedSnapshot,
    ScanContext,
    StageResult,
    WorkspaceSnapshot,
    merge_diagnostics,
)
from ..detectors import BuildSelectionDetector, LocalX86Detector, TestSelectionDetector
from ..detectors.demand import FactProofDemand
from ..frontends.build import BuildFrontend
from ..frontends.cpp import CppFrontend
from ..frontends.cpp.workspace import language_candidates_for_path
from ..normalization import BuildEvidenceNormalizer, CppEvidenceNormalizer
from ..frontends.family_inventory import annotate_inventory
from ..analyzers.architecture_family import ArchitectureFamilyAnalyzer
from ..analyzers.function_multiversion.analysis import FunctionMultiversionAnalyzer
from ..detectors.function_multiversion import FunctionMultiversionDetector
from ..analyzers.candidate_usage.analysis import CandidateUsageAnalyzer
from .usage_frontend import UsageAnchorSelector
from ..analyzers.test_content import TestContentAnalyzer


class CompositeProjectFrontend:
    """组合前端：C/C++ 源码前端 + 构建系统前端，再叠加家族清单标注。"""

    __slots__ = ("_cpp", "_build")

    def __init__(self, cpp: CppFrontend | None = None, build: BuildFrontend | None = None) -> None:
        self._cpp = cpp or CppFrontend()
        self._build = build or BuildFrontend()

    def analyze_with_knowledge(self, context, workspace, knowledge):
        """带知识包的前端入口：用知识构建用法锚点选择器后走 analyze。"""
        return self.analyze(context, workspace, usage_selector=UsageAnchorSelector(context, knowledge))

    def analyze(self, context: ScanContext, workspace: WorkspaceSnapshot, *, usage_selector=None) -> StageResult[FrontendSnapshot]:
        """执行前端阶段：源码前端只看语言匹配的文件，构建前端看全部文件。

        两个子前端的实体/证据经 _merge_frontend 去重合并（按稳定ID），
        最后 annotate_inventory 为实体补注架构家族清单信息。
        """
        cpp_workspace = WorkspaceSnapshot(
            workspace.root,
            tuple(path for path in workspace.files if language_candidates_for_path(path, context.languages)),
        )
        cpp_result = self._cpp.analyze(context, cpp_workspace,
            **({"usage_selector": usage_selector} if usage_selector is not None else {}))
        build_result = self._build.analyze(context, workspace)
        return annotate_inventory(_merge_frontend(cpp_result, build_result), workspace, context)


class CompositeNormalizer:
    """组合归一化器：C/C++ 证据归一化 + 构建证据归一化，结果合并。"""

    __slots__ = ("_cpp", "_build")

    def __init__(self, cpp: CppEvidenceNormalizer | None = None, build: BuildEvidenceNormalizer | None = None) -> None:
        self._cpp = cpp or CppEvidenceNormalizer()
        self._build = build or BuildEvidenceNormalizer()

    def normalize(self, context: ScanContext, frontend: FrontendSnapshot) -> StageResult[NormalizedSnapshot]:
        """执行归一化阶段：按证据 kind 前缀把证据分流给两个子归一化器。

        kind 以 "build_" 开头的证据走构建归一化器，其余走 C/C++ 归一化器；
        实体随之分流（source_file 实体两边都保留）。
        """
        cpp_evidence = tuple(item for item in frontend.evidence if not item.kind.startswith("build_"))
        cpp_subjects = {item.subject_id for item in cpp_evidence}
        cpp_entities = tuple(item for item in frontend.entities if item.id in cpp_subjects or item.kind == "source_file")
        cpp_result = self._cpp.normalize(context, FrontendSnapshot(cpp_entities, cpp_evidence))
        build_result = self._build.normalize(context, frontend)
        return _merge_normalized(cpp_result, build_result)


class CompositeAnalyzer:
    """组合分析器：按依赖链顺序串联八个子分析器。

    链序：证据图→构建选择→测试资产→测试内容→测试选择→架构家族→
    函数多版本→候选用法。每个子分析器消费上一环的 AnalysisSnapshot，
    逐环增量合并诊断；任一环失败/不可用即整阶段短路。
    """

    __slots__ = ("_source", "_build", "_test_assets", "_test_selection", "_family", "_multiversion", "_usage", "_test_content")

    def __init__(
        self,
        source: EvidenceGraphAnalyzer | None = None,
        build: BuildSelectionAnalyzer | None = None,
        test_assets: TestAssetAnalyzer | None = None,
        test_selection: TestSelectionAnalyzer | None = None,
        family: ArchitectureFamilyAnalyzer | None = None,
        multiversion: FunctionMultiversionAnalyzer | None = None,
        usage: CandidateUsageAnalyzer | None = None,
        test_content: TestContentAnalyzer | None = None,
    ) -> None:
        self._source = source or EvidenceGraphAnalyzer(demand_selector=FactProofDemand())
        self._build = build or BuildSelectionAnalyzer()
        self._test_assets = test_assets or TestAssetAnalyzer()
        self._test_selection = test_selection or TestSelectionAnalyzer()
        self._family = family or ArchitectureFamilyAnalyzer()
        self._multiversion = multiversion or FunctionMultiversionAnalyzer()
        self._usage = usage or CandidateUsageAnalyzer()
        self._test_content = test_content or TestContentAnalyzer()

    def analyze(self, context: ScanContext, facts: NormalizedSnapshot, knowledge: KnowledgeSnapshot) -> StageResult[AnalysisSnapshot]:
        """执行 analyzer 阶段：先分流源码侧事实，再沿八环依赖链推进。

        源码侧子分析器只接收非 build_ 前缀的证据/事实（构建事实由
        构建分析器单独消费）。最终状态：任一环 partial 则整体 partial。
        """
        source_evidence = tuple(item for item in facts.evidence if not item.kind.startswith("build_"))
        source_ids = {item.subject_id for item in source_evidence}
        source_entities = tuple(item for item in facts.entities if item.id in source_ids or item.kind == "source_file")
        source_facts = tuple(item for item in facts.facts if not item.kind.startswith("build_"))
        source_result = self._source.analyze(context, NormalizedSnapshot(source_entities, source_evidence, source_facts), knowledge)
        if source_result.status in {CompletionStatus.FAILED, CompletionStatus.UNAVAILABLE} or source_result.value is None:
            return source_result
        build_result = self._build.analyze(context, facts, knowledge, source_result.value)
        diagnostics = merge_diagnostics(source_result.diagnostics, build_result.diagnostics)
        if build_result.status in {CompletionStatus.FAILED, CompletionStatus.UNAVAILABLE} or build_result.value is None:
            return StageResult(None, build_result.status, diagnostics)
        asset_result = self._test_assets.analyze(context, facts, knowledge, build_result.value)
        diagnostics = merge_diagnostics(diagnostics, asset_result.diagnostics)
        if asset_result.status in {CompletionStatus.FAILED, CompletionStatus.UNAVAILABLE} or asset_result.value is None:
            return StageResult(None, asset_result.status, diagnostics)
        content_result = self._test_content.analyze(context, facts, knowledge, asset_result.value)
        diagnostics = merge_diagnostics(diagnostics, content_result.diagnostics)
        if content_result.status in {CompletionStatus.FAILED, CompletionStatus.UNAVAILABLE} or content_result.value is None:
            return StageResult(None, content_result.status, diagnostics)
        test_result = self._test_selection.analyze(context, facts, knowledge, content_result.value)
        diagnostics = merge_diagnostics(diagnostics, test_result.diagnostics)
        if test_result.status in {CompletionStatus.FAILED, CompletionStatus.UNAVAILABLE} or test_result.value is None:
            return StageResult(None, test_result.status, diagnostics)
        family_result = self._family.analyze(context, facts, knowledge, test_result.value)
        diagnostics = merge_diagnostics(diagnostics, family_result.diagnostics)
        if family_result.status in {CompletionStatus.FAILED, CompletionStatus.UNAVAILABLE} or family_result.value is None:
            return StageResult(None, family_result.status, diagnostics)
        multiversion_result = self._multiversion.analyze(context, facts, knowledge, family_result.value)
        diagnostics = merge_diagnostics(diagnostics, multiversion_result.diagnostics)
        if multiversion_result.status in {CompletionStatus.FAILED, CompletionStatus.UNAVAILABLE} or multiversion_result.value is None:
            return StageResult(None, multiversion_result.status, diagnostics)
        usage_result = self._usage.analyze(context, facts, knowledge, multiversion_result.value)
        diagnostics = merge_diagnostics(diagnostics, usage_result.diagnostics)
        if usage_result.status in {CompletionStatus.FAILED, CompletionStatus.UNAVAILABLE} or usage_result.value is None:
            return StageResult(None, usage_result.status, diagnostics)
        status = CompletionStatus.PARTIAL if CompletionStatus.PARTIAL in {
            source_result.status, build_result.status, asset_result.status, content_result.status, test_result.status, family_result.status, multiversion_result.status, usage_result.status
        } else CompletionStatus.COMPLETE
        return StageResult(usage_result.value, status, diagnostics)


class CompositeDetector:
    """组合检测器：本地 x86/构建选择/测试选择/函数多版本四个子检测器。

    前三个检测器相互独立地产出观察，按稳定ID 去重合并后交给
    函数多版本检测器做增量检测；最终观察按 (detector_id, kind, id)
    排序，保证输出顺序确定。
    """

    __slots__ = ("_local", "_build", "_test", "_multiversion")

    def __init__(
        self,
        local: LocalX86Detector | None = None,
        build: BuildSelectionDetector | None = None,
        test: TestSelectionDetector | None = None,
        multiversion: FunctionMultiversionDetector | None = None,
    ) -> None:
        # Real compilation/testing is intentionally outside this static scanner's
        # contract, not a missing analysis stage.  Do not downgrade every result
        # merely because the scanner correctly stays within that boundary.
        self._local = local or LocalX86Detector(deferred_capabilities={})
        self._build = build or BuildSelectionDetector()
        self._test = test or TestSelectionDetector()
        self._multiversion = multiversion or FunctionMultiversionDetector()

    def detect(
        self,
        context: ScanContext,
        facts: NormalizedSnapshot,
        analysis: AnalysisSnapshot,
        knowledge: KnowledgeSnapshot,
    ) -> StageResult[DetectionSnapshot]:
        """执行 detector 阶段：串行调用四个子检测器并逐级合并。"""
        local_result = self._local.detect(context, facts, analysis, knowledge)
        if local_result.status in {CompletionStatus.FAILED, CompletionStatus.UNAVAILABLE} or local_result.value is None:
            return local_result
        build_result = self._build.detect(context, facts, analysis, knowledge)
        diagnostics = merge_diagnostics(local_result.diagnostics, build_result.diagnostics)
        if build_result.status in {CompletionStatus.FAILED, CompletionStatus.UNAVAILABLE} or build_result.value is None:
            return StageResult(None, build_result.status, diagnostics)
        test_result = self._test.detect(context, facts, analysis, knowledge)
        diagnostics = merge_diagnostics(diagnostics, test_result.diagnostics)
        if test_result.status in {CompletionStatus.FAILED, CompletionStatus.UNAVAILABLE} or test_result.value is None:
            return StageResult(None, test_result.status, diagnostics)
        observations = {item.id: item for item in (*local_result.value.observations, *build_result.value.observations, *test_result.value.observations)}
        multiversion_result = self._multiversion.detect(context, facts, analysis, knowledge, DetectionSnapshot(tuple(observations.values())))
        diagnostics = merge_diagnostics(diagnostics, multiversion_result.diagnostics)
        if multiversion_result.status in {CompletionStatus.FAILED, CompletionStatus.UNAVAILABLE} or multiversion_result.value is None:
            return StageResult(None, multiversion_result.status, diagnostics)
        status = CompletionStatus.PARTIAL if CompletionStatus.PARTIAL in {
            local_result.status, build_result.status, test_result.status, multiversion_result.status
        } else CompletionStatus.COMPLETE
        return StageResult(DetectionSnapshot(tuple(sorted(multiversion_result.value.observations, key=lambda item: (item.detector_id, item.kind, item.id)))), status, diagnostics)


def _merge_frontend(*results: StageResult[FrontendSnapshot]) -> StageResult[FrontendSnapshot]:
    """合并多个前端结果：按稳定ID 去重实体/证据，并校验引用完整性。

    任何子结果失败、或证据引用了不存在的实体，都判定整阶段 FAILED；
    实体按 (kind, name, id)、证据按 id 排序，输出顺序确定。
    """
    diagnostics = merge_diagnostics(*(item.diagnostics for item in results))
    if any(item.status is CompletionStatus.FAILED or item.value is None for item in results):
        return StageResult(None, CompletionStatus.FAILED, diagnostics)
    entities = {value.id: value for item in results for value in item.value.entities}
    evidence = {value.id: value for item in results for value in item.value.evidence}
    if any(value.subject_id not in entities for value in evidence.values()):
        return StageResult(None, CompletionStatus.FAILED, diagnostics)
    status = CompletionStatus.PARTIAL if any(item.status is not CompletionStatus.COMPLETE for item in results) else CompletionStatus.COMPLETE
    return StageResult(FrontendSnapshot(tuple(sorted(entities.values(), key=lambda item: (item.kind, item.name, item.id))), tuple(sorted(evidence.values(), key=lambda item: item.id))), status, diagnostics)


def _merge_normalized(*results: StageResult[NormalizedSnapshot]) -> StageResult[NormalizedSnapshot]:
    """合并多个归一化结果：去重实体/证据/事实并校验引用闭合。

    不变式：每条事实的 subject 必须存在于实体集合，且其引用的全部
    证据 ID 必须存在；违例判 FAILED。事实按 (kind, scope, id) 排序。
    """
    diagnostics = merge_diagnostics(*(item.diagnostics for item in results))
    if any(item.status is CompletionStatus.FAILED or item.value is None for item in results):
        return StageResult(None, CompletionStatus.FAILED, diagnostics)
    entities = {value.id: value for item in results for value in item.value.entities}
    evidence = {value.id: value for item in results for value in item.value.evidence}
    facts = {value.id: value for item in results for value in item.value.facts}
    if any(value.subject_id not in entities or any(identifier not in evidence for identifier in value.evidence_ids) for value in facts.values()):
        return StageResult(None, CompletionStatus.FAILED, diagnostics)
    status = CompletionStatus.PARTIAL if any(item.status is not CompletionStatus.COMPLETE for item in results) else CompletionStatus.COMPLETE
    return StageResult(NormalizedSnapshot(tuple(sorted(entities.values(), key=lambda item: (item.kind, item.name, item.id))), tuple(sorted(evidence.values(), key=lambda item: item.id)), tuple(sorted(facts.values(), key=lambda item: (item.kind, item.scope, item.id)))), status, diagnostics)
