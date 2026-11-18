package cn.rvport.backend.source;

import cn.rvport.backend.jooq.tables.records.RecheckRunsRecord;
import cn.rvport.backend.task.TaskStatus;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record RecheckRunView(
        UUID id,
        UUID projectId,
        UUID taskId,
        UUID baseSourceRevisionId,
        UUID targetSourceRevisionId,
        UUID baseConfirmationId,
        UUID targetConfirmationId,
        Map<String, Object> summary,
        TaskStatus status,
        OffsetDateTime createdAt
) {
    public static RecheckRunView from(RecheckRunsRecord record, Map<String, Object> summary) {
        return new RecheckRunView(
                record.getId(),
                record.getProjectId(),
                record.getTaskId(),
                record.getBaseSourceRevisionId(),
                record.getTargetSourceRevisionId(),
                record.getBaseConfirmationId(),
                record.getTargetConfirmationId(),
                summary,
                TaskStatus.valueOf(record.getStatus()),
                record.getCreatedAt()
        );
    }
}
