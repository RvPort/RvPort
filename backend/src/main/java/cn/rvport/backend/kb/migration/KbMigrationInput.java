package cn.rvport.backend.kb.migration;

import cn.rvport.backend.modelprofile.ModelExecutionSelection;
import java.util.Map;
import java.util.UUID;

/** KB_MIGRATION 任务的 inputSnapshot 反序列化形态。 */
public record KbMigrationInput(
        UUID projectId,
        UUID migrationRunId,
        UUID scanRunId,
        UUID sourceRevisionId,
        ModelExecutionSelection selection,
        Map<String, Object> executionContext) {

    public KbMigrationInput {
        executionContext = executionContext == null ? Map.of() : Map.copyOf(executionContext);
    }
}
