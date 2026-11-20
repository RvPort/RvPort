package cn.rvport.backend.ai.migration;

import cn.rvport.backend.jooq.tables.records.AiMigrationRunsRecord;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record AiMigrationRunView(
        UUID id,
        UUID projectId,
        UUID taskId,
        UUID scanRunId,
        UUID sourceRevisionId,
        String status,
        Map<String, Object> summary,
        int itemCount,
        int doneCount,
        int falsePositiveCount,
        int pendingCount,
        UUID modelProfileId,
        UUID agentRuntimeId,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt) {

    static AiMigrationRunView from(AiMigrationRunsRecord record, UUID sourceRevisionId, Map<String, Object> summary) {
        return new AiMigrationRunView(
                record.getId(),
                record.getProjectId(),
                record.getTaskId(),
                record.getScanRunId(),
                sourceRevisionId,
                record.getStatus(),
                summary == null ? Map.of() : summary,
                record.getItemCount() == null ? 0 : record.getItemCount(),
                record.getDoneCount() == null ? 0 : record.getDoneCount(),
                record.getFalsePositiveCount() == null ? 0 : record.getFalsePositiveCount(),
                record.getPendingCount() == null ? 0 : record.getPendingCount(),
                record.getModelProfileId(),
                record.getAgentRuntimeId(),
                record.getCreatedAt(),
                record.getStartedAt(),
                record.getFinishedAt());
    }
}
