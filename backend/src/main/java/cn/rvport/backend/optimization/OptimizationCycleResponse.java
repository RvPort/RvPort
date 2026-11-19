package cn.rvport.backend.optimization;

import cn.rvport.backend.source.SourceRevisionResponse;
import java.time.OffsetDateTime;
import java.util.UUID;

public record OptimizationCycleResponse(
        UUID id,
        UUID projectId,
        UUID sourceRevisionId,
        SourceRevisionResponse sourceRevision,
        int cycleNumber,
        OptimizationCycleStatus status,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OptimizationCycleSummaryResponse summary,
        OptimizationCycleSummaryResponse historicalSummary,
        OptimizationValidation validation,
        OptimizationValidation.NextAction nextAction,
        cn.rvport.backend.report.review.CurrentFixListResolver.Current currentFixList
) {
}
