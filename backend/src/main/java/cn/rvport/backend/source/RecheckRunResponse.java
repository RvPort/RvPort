package cn.rvport.backend.source;

import cn.rvport.backend.task.TaskStatus;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record RecheckRunResponse(
        UUID id,
        UUID taskId,
        UUID baseSourceRevisionId,
        UUID targetSourceRevisionId,
        UUID baseConfirmationId,
        UUID targetConfirmationId,
        Map<String, Object> summary,
        TaskStatus rawStatus,
        OffsetDateTime createdAt
) {
    public static RecheckRunResponse from(RecheckRunView view) {
        if (view == null) {
            return null;
        }
        return new RecheckRunResponse(
                view.id(),
                view.taskId(),
                view.baseSourceRevisionId(),
                view.targetSourceRevisionId(),
                view.baseConfirmationId(),
                view.targetConfirmationId(),
                view.summary(),
                view.status(),
                view.createdAt()
        );
    }
}
