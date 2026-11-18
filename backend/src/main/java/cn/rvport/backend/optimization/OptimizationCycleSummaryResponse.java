package cn.rvport.backend.optimization;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record OptimizationCycleSummaryResponse(
        LatestReportSummary report,
        RecheckSummary recheck,
        StageRunSummary sourceDeploy,
        StageRunSummary build,
        StageRunSummary test,
        TestComparisonSummary testComparison
) {
    public static OptimizationCycleSummaryResponse empty() {
        return new OptimizationCycleSummaryResponse(
                null,
                null,
                StageRunSummary.empty(),
                StageRunSummary.empty(),
                StageRunSummary.empty(),
                null
        );
    }

    public record LatestReportSummary(
            UUID id,
            UUID taskId,
            String status,
            String riskLevel,
            Integer highRiskCount,
            String workloadSummary,
            OffsetDateTime generatedAt,
            OffsetDateTime createdAt
    ) {
    }

    public record RecheckSummary(
            UUID id,
            UUID taskId,
            UUID baseSourceRevisionId,
            UUID targetSourceRevisionId,
            String status,
            long diffTotal,
            Map<String, Long> byDiffStatus,
            Map<String, Long> bySeverity,
            OffsetDateTime createdAt
    ) {
    }

    public record StageRunSummary(
            long total,
            String latestStatus,
            UUID latestRunId,
            OffsetDateTime latestStartedAt,
            OffsetDateTime latestFinishedAt,
            Map<String, Long> byStatus
    ) {
        public static StageRunSummary empty() {
            return new StageRunSummary(0, null, null, null, null, Map.of());
        }
    }

    public record TestComparisonSummary(
            UUID id,
            UUID taskId,
            UUID testBatchId,
            String status,
            String overallStatus,
            String riskLevel,
            String summary,
            String recommendation,
            OffsetDateTime createdAt,
            OffsetDateTime finishedAt
    ) {
    }
}
