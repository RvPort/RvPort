package cn.rvport.backend.reportcomparison;

import cn.rvport.backend.task.TaskStatus;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record ReportComparisonSummaryResponse(
        UUID id,
        UUID taskId,
        UUID baseReportId,
        UUID targetReportId,
        TaskStatus status,
        Map<String, Object> summary,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        OffsetDateTime createdAt,
        ReportComparisonBasis basis,
        UUID baseConfirmationId,
        UUID targetConfirmationId
) {
    public static ReportComparisonSummaryResponse from(ReportComparisonRunView view) {
        if (view == null) {
            return null;
        }
        return new ReportComparisonSummaryResponse(
                view.id(),
                view.taskId(),
                view.baseReportId(),
                view.targetReportId(),
                view.status(),
                view.summary(),
                view.startedAt(),
                view.finishedAt(),
                view.createdAt(),
                view.basis(), view.baseConfirmationId(), view.targetConfirmationId()
        );
    }
}
