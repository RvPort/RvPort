package cn.rvport.backend.kb.migration;

import cn.rvport.backend.artifact.StoredArtifactView;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record KbMigrationResponse(
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
        List<StoredArtifactView> artifacts) {

    public static KbMigrationResponse from(KbMigrationRunView run, List<StoredArtifactView> artifacts) {
        return new KbMigrationResponse(
                run.id(),
                run.projectId(),
                run.taskId(),
                run.scanRunId(),
                run.sourceRevisionId(),
                run.status(),
                run.summary(),
                run.itemCount(),
                run.doneCount(),
                run.falsePositiveCount(),
                run.pendingCount(),
                artifacts == null ? List.of() : artifacts);
    }
}
