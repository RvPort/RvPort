"""流水线编排器：按固定顺序驱动八个阶段并聚合诊断与状态。

StagedPipelineRunner 是唯一的执行中枢：workspace→knowledge→frontend→
normalizer→analyzer→detector→classifier→assessor→projector 逐阶段推进，
任一阶段 failed/unavailable 即短路停止；所有阶段诊断去重合并，
退出码由 affects_exit_code 的诊断决定。
"""

from __future__ import annotations

from typing import Any

from ..core.context import ScanContext
from ..core.diagnostics import Diagnostic, DiagnosticSeverity, merge_diagnostics
from ..core.results import CompletionStatus, PipelineResult, StageResult
from .profile import PipelineProfile


class StagedPipelineRunner:
    """八阶段流水线编排器（实现 core.protocols.PipelineRunner）。

    无内部可变状态（仅持有 frozen 的 profile），可并发复用。
    """

    __slots__ = ("_profile",)

    def __init__(self, profile: PipelineProfile) -> None:
        """以给定的阶段组件装配（PipelineProfile）构造编排器。"""
        self._profile = profile

    def run(self, context: ScanContext, initial_diagnostics: tuple[Diagnostic, ...] = ()) -> PipelineResult:
        """执行完整流水线；任何未捕获异常都转换为 FATAL 诊断而非崩溃。

        initial_diagnostics 是上游（如 workspace 构建）已产生的诊断，
        会并入最终结果。始终返回 PipelineResult，绝不向上抛异常。
        """
        try:
            return self._run(context, initial_diagnostics)
        except Exception as exc:  # noqa: BLE001 - convert stage invariant failures at the boundary
            fatal = Diagnostic(
                code="pipeline.internal-error",
                stage="orchestrator",
                severity=DiagnosticSeverity.FATAL,
                message=f"Pipeline internal error: {exc}",
                producer="staged-pipeline-runner",
                affects_exit_code=True,
            )
            return self._stopped_result(
                CompletionStatus.FAILED,
                merge_diagnostics(initial_diagnostics, (fatal,)),
            )

    def _run(self, context: ScanContext, initial_diagnostics: tuple[Diagnostic, ...]) -> PipelineResult:
        """顺序执行八个阶段，每阶段经 _accept 检查是否继续。"""
        diagnostics = tuple(initial_diagnostics)
        overall_status = CompletionStatus.COMPLETE

        workspace_result = self._profile.workspace_collector.collect(context)
        diagnostics, overall_status, stopped = self._accept(diagnostics, overall_status, workspace_result)
        if stopped:
            return self._stopped_result(workspace_result.status, diagnostics)
        workspace = workspace_result.value

        knowledge_result = self._profile.knowledge_provider.load(context)
        diagnostics, overall_status, stopped = self._accept(diagnostics, overall_status, knowledge_result)
        if stopped:
            return self._stopped_result(knowledge_result.status, diagnostics)
        knowledge = knowledge_result.value

        # frontend 可选实现 analyze_with_knowledge：有知识包参与的前端解析
        # 能利用知识做更精确的识别，未实现时退回普通 analyze。
        knowledge_frontend = getattr(self._profile.frontend, "analyze_with_knowledge", None)
        frontend_result = (knowledge_frontend(context, workspace, knowledge) if knowledge_frontend is not None
            else self._profile.frontend.analyze(context, workspace))
        diagnostics, overall_status, stopped = self._accept(diagnostics, overall_status, frontend_result)
        if stopped:
            return self._stopped_result(frontend_result.status, diagnostics)
        frontend = frontend_result.value

        normalized_result = self._profile.normalizer.normalize(context, frontend)
        diagnostics, overall_status, stopped = self._accept(diagnostics, overall_status, normalized_result)
        if stopped:
            return self._stopped_result(normalized_result.status, diagnostics)
        normalized = normalized_result.value

        analysis_result = self._profile.analyzer.analyze(context, normalized, knowledge)
        diagnostics, overall_status, stopped = self._accept(diagnostics, overall_status, analysis_result)
        if stopped:
            return self._stopped_result(analysis_result.status, diagnostics)
        analysis = analysis_result.value

        detection_result = self._profile.detector.detect(context, normalized, analysis, knowledge)
        diagnostics, overall_status, stopped = self._accept(diagnostics, overall_status, detection_result)
        if stopped:
            return self._stopped_result(detection_result.status, diagnostics)
        detections = detection_result.value

        classification_result = self._profile.classifier.classify(context, detections, analysis)
        diagnostics, overall_status, stopped = self._accept(diagnostics, overall_status, classification_result)
        if stopped:
            return self._stopped_result(classification_result.status, diagnostics)
        classifications = classification_result.value

        assessment_result = self._profile.assessor.assess(context, classifications, analysis)
        diagnostics, overall_status, stopped = self._accept(diagnostics, overall_status, assessment_result)
        if stopped:
            return self._stopped_result(assessment_result.status, diagnostics)
        assessments = assessment_result.value

        projection_result = self._profile.report_projector.project(
            context,
            workspace,
            knowledge,
            frontend,
            normalized,
            analysis,
            detections,
            classifications,
            assessments,
            diagnostics,
        )
        diagnostics, overall_status, stopped = self._accept(diagnostics, overall_status, projection_result)
        if stopped:
            return self._stopped_result(projection_result.status, diagnostics)

        exit_code = 1 if any(item.affects_exit_code for item in diagnostics) else 0
        return PipelineResult(
            success=exit_code == 0,
            exit_code=exit_code,
            status=overall_status,
            diagnostics=diagnostics,
            artifacts=projection_result.value,
        )

    @staticmethod
    def _accept(
        diagnostics: tuple[Diagnostic, ...],
        overall_status: CompletionStatus,
        result: StageResult[Any],
    ) -> tuple[tuple[Diagnostic, ...], CompletionStatus, bool]:
        """接纳一个阶段结果：合并诊断并判定是否短路停止。

        返回 (合并后诊断, 总体状态, 是否停止)。failed/unavailable 或
        值为 None 时停止；partial 把总体状态降级为 partial 但继续。
        """
        merged = merge_diagnostics(diagnostics, result.diagnostics)
        if result.status is CompletionStatus.FAILED:
            return merged, CompletionStatus.FAILED, True
        if result.status is CompletionStatus.UNAVAILABLE or result.value is None:
            return merged, CompletionStatus.UNAVAILABLE, True
        if result.status is CompletionStatus.PARTIAL:
            overall_status = CompletionStatus.PARTIAL
        return merged, overall_status, False

    @staticmethod
    def _stopped_result(status: CompletionStatus, diagnostics: tuple[Diagnostic, ...]) -> PipelineResult:
        """构造短路停止时的结果：failed 退出码 2，其他非成功退出码 1。"""
        exit_code = 2 if status is CompletionStatus.FAILED else 1
        return PipelineResult(
            success=False,
            exit_code=exit_code,
            status=status,
            diagnostics=diagnostics,
        )
