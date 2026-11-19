package cn.rvport.backend.source;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record RecheckCreateRequest(
        @NotNull UUID baseSourceRevisionId,
        @NotNull UUID targetSourceRevisionId,
        @NotNull UUID baseConfirmationId,
        @NotNull UUID targetConfirmationId
) {
}
