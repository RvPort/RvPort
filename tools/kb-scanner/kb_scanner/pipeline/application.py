"""扫描应用：连接 CLI 请求与流水线运行的最外层入口。

ScanApplication 先构建扫描上下文（workspace 阶段前置），再把它连同
上下文构建期的诊断交给 PipelineRunner；上下文构建失败时直接短路。
"""

from __future__ import annotations

from ..core.context import ScanRequest
from ..core.results import CompletionStatus, PipelineResult
from .profile import ApplicationProfile


class ScanApplication:
    """扫描应用（frozen profile，无内部可变状态，可复用）。"""

    __slots__ = ("_profile",)

    def __init__(self, profile: ApplicationProfile) -> None:
        self._profile = profile

    def run(self, request: ScanRequest) -> PipelineResult:
        """从 ScanRequest 起跑一次完整扫描。

        上下文构建失败 → 短路返回（failed 退出码 2，否则 1）；
        上下文 partial 且流水线 complete → 总体状态降级为 partial
        （状态取更保守的一方），其余情况透传流水线结果。
        """
        context_result = self._profile.context_factory.build(request)
        if context_result.value is None:
            exit_code = 2 if context_result.status is CompletionStatus.FAILED else 1
            return PipelineResult(
                success=False,
                exit_code=exit_code,
                status=context_result.status,
                diagnostics=context_result.diagnostics,
            )
        result = self._profile.runner.run(context_result.value, context_result.diagnostics)
        if context_result.status is CompletionStatus.PARTIAL and result.status is CompletionStatus.COMPLETE:
            return PipelineResult(
                success=result.success,
                exit_code=result.exit_code,
                status=CompletionStatus.PARTIAL,
                diagnostics=result.diagnostics,
                artifacts=result.artifacts,
            )
        return result
