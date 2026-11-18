package cn.rvport.backend.reportcomparison;

import cn.rvport.backend.report.ReportView;
import cn.rvport.backend.task.TaskStatus;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record ReportComparisonResponse(
        UUID id,
        UUID projectId,
        UUID taskId,
        ReportComparisonReportSummaryResponse baseReport,
        ReportComparisonReportSummaryResponse targetReport,
        TaskStatus status,
        Map<String, Object> summary,
        String errorCode,
        String errorMessage,
        ReportComparisonDiffSummaryResponse diffSummary,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        ReportComparisonBasis basis,
        UUID baseConfirmationId,
        UUID targetConfirmationId
) {
    public static ReportComparisonResponse from(
            ReportComparisonRunView run,
            ReportView baseReport,
            ReportView targetReport,
            ReportComparisonDiffSummaryResponse diffSummary
    ) {
        return new ReportComparisonResponse(
                run.id(),
                run.projectId(),
                run.taskId(),
                ReportComparisonReportSummaryResponse.from(baseReport),
                ReportComparisonReportSummaryResponse.from(targetReport),
                run.status(),
                run.summary(),
                run.errorCode(),
                run.errorMessage(),
                diffSummary == null ? ReportComparisonDiffSummaryResponse.empty() : diffSummary,
                run.startedAt(),
                run.finishedAt(),
                run.createdAt(),
                run.updatedAt(),
                run.basis(), run.baseConfirmationId(), run.targetConfirmationId()
        );
    }
}
