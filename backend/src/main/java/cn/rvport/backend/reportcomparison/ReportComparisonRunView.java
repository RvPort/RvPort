package cn.rvport.backend.reportcomparison;

import cn.rvport.backend.jooq.tables.records.ReportComparisonRunsRecord;
import cn.rvport.backend.task.TaskStatus;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record ReportComparisonRunView(
        UUID id,
        UUID projectId,
        UUID taskId,
        UUID baseReportId,
        UUID targetReportId,
        TaskStatus status,
        Map<String, Object> summary,
        String errorCode,
        String errorMessage,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        ReportComparisonBasis basis,
        UUID baseConfirmationId,
        UUID targetConfirmationId
) {
    static ReportComparisonRunView from(ReportComparisonRunsRecord record, Map<String, Object> summary) {
        return new ReportComparisonRunView(
                record.getId(),
                record.getProjectId(),
                record.getTaskId(),
                record.getBaseReportId(),
                record.getTargetReportId(),
                TaskStatus.valueOf(record.getStatus()),
                summary,
                record.getErrorCode(),
                record.getErrorMessage(),
                record.getStartedAt(),
                record.getFinishedAt(),
                record.getCreatedAt(),
                record.getUpdatedAt(),
                ReportComparisonBasis.valueOf(record.getBasis()), record.getBaseConfirmationId(), record.getTargetConfirmationId()
        );
    }
}
