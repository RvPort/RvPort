package cn.rvport.backend.workflow;

import java.util.Map;
import java.util.UUID;

public record StagePrerequisiteResponse(
        String key,
        StagePrerequisiteStatus status,
        UUID referenceId,
        String reasonCode,
        Map<String, Object> details,
        boolean required
) {
    public StagePrerequisiteResponse(String key, StagePrerequisiteStatus status, UUID referenceId,
            String reasonCode, Map<String, Object> details) {
        this(key, status, referenceId, reasonCode, details, true);
    }
    public StagePrerequisiteResponse optional() {
        return new StagePrerequisiteResponse(key, status, referenceId, reasonCode, details, false);
    }
    public static StagePrerequisiteResponse of(
            String key,
            StagePrerequisiteStatus status,
            UUID referenceId,
            String reasonCode
    ) {
        return new StagePrerequisiteResponse(key, status, referenceId, reasonCode, Map.of());
    }

    public static StagePrerequisiteResponse of(
            String key,
            StagePrerequisiteStatus status,
            UUID referenceId,
            String reasonCode,
            Map<String, Object> details
    ) {
        return new StagePrerequisiteResponse(key, status, referenceId, reasonCode, details == null ? Map.of() : details);
    }
}
