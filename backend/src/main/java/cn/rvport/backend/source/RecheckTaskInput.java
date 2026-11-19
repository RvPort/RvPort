package cn.rvport.backend.source;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RecheckTaskInput(
        UUID projectId,
        UUID recheckRunId,
        UUID baseSourceRevisionId,
        UUID targetSourceRevisionId,
        UUID baseConfirmationId,
        UUID targetConfirmationId
) {
}
