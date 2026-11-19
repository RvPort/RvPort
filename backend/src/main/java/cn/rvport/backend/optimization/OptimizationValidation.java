package cn.rvport.backend.optimization;

import cn.rvport.backend.optimization.OptimizationCycleSummaryResponse.StageRunSummary;
import cn.rvport.backend.optimization.OptimizationCycleSummaryResponse.TestComparisonSummary;
import cn.rvport.backend.project.ProjectOverviewResponse.WorkflowStageStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/** 仅包含安全引用和派生事实，不承载日志、凭据或执行命令。 */
public record OptimizationValidation(
        WorkflowStageStatus status, String reasonCode, String reason,
        @JsonProperty("reason_en") String reasonEn,
        UUID sourceBuildRunId, UUID targetBuildRunId, UUID testBatchId,
        UUID sourceTestRunId, UUID targetTestRunId,
        StageRunSummary build, StageRunSummary test, TestComparisonSummary testComparison,
        NextAction nextAction
) {
    public record NextAction(String stageId, String description,
                             @JsonProperty("description_en") String descriptionEn) { }

    public OptimizationCycleSummaryResponse applyTo(OptimizationCycleSummaryResponse history) {
        return new OptimizationCycleSummaryResponse(history.report(), history.recheck(),
                history.sourceDeploy(), build, test, testComparison);
    }

    public static OptimizationValidation unavailable(String code, String zh, String en) {
        return new OptimizationValidation(WorkflowStageStatus.PENDING, code, zh, en, null, null, null, null, null,
                StageRunSummary.empty(), StageRunSummary.empty(), null, null);
    }
}
