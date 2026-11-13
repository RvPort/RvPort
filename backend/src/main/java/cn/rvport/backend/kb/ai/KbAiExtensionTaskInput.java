package cn.rvport.backend.kb.ai;

import java.util.UUID;

public record KbAiExtensionTaskInput(
        UUID projectId,
        UUID scanRunId,
        UUID extensionRunId,
        UUID sourceRevisionId,
        String note,
        cn.rvport.backend.modelprofile.ModelExecutionSelection modelExecution
) {
}
