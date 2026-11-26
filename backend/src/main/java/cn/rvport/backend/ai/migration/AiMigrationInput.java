package cn.rvport.backend.ai.migration;

import cn.rvport.backend.modelprofile.ModelExecutionSelection;
import java.util.Map;
import java.util.UUID;

/** AI_MIGRATION 任务的 inputSnapshot 反序列化形态。 */
public record AiMigrationInput(
        UUID projectId,
        UUID migrationRunId,
        UUID scanRunId,
        UUID sourceRevisionId,
        UUID reportId,
        String reportContextHash,
        ModelExecutionSelection selection,
        Map<String, Object> executionContext) {

    public AiMigrationInput(UUID projectId, UUID migrationRunId, UUID scanRunId, UUID sourceRevisionId,
            ModelExecutionSelection selection, Map<String, Object> executionContext) {
        this(projectId, migrationRunId, scanRunId, sourceRevisionId, null, null, selection, executionContext);
    }

    public AiMigrationInput {
        executionContext = executionContext == null ? Map.of() : Map.copyOf(executionContext);
    }
}
