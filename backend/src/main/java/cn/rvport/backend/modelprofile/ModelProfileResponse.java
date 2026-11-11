package cn.rvport.backend.modelprofile;

import cn.rvport.backend.jooq.tables.records.ModelProfilesRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ModelProfileResponse(
        UUID id,
        String name,
        boolean enabled,
        String executablePath,
        String modelProviderType,
        String baseUrl,
        String model,
        String wireApi,
        int timeoutSeconds,
        int maxRuntimeSeconds,
        boolean apiKeyConfigured,
        String apiKeyMasked,
        String lastTestStatus,
        String lastTestMessage,
        String lastTestAgentType,
        Boolean lastTestBasicAvailable,
        Boolean lastTestAgentCompatible,
        Map<String, Object> lastTestDetails,
        OffsetDateTime lastTestedAt,
        String lastConnectivityStatus,
        String lastConnectivityMessage,
        Map<String, Object> lastConnectivityDetails,
        OffsetDateTime lastConnectivityTestedAt,
        Long lastConnectivityDurationMs,
        List<ModelProfileAgentProbeResultResponse> agentProbeResults,
        ActiveAgentProbeRunResponse activeAgentProbeRun,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    static ModelProfileResponse from(ModelProfilesRecord record) {
        boolean apiKeyConfigured = record.getApiKeyEncrypted() != null && !record.getApiKeyEncrypted().isBlank();
        return new ModelProfileResponse(
                record.getId(),
                record.getName(),
                Boolean.TRUE.equals(record.getEnabled()),
                null,
                record.getModelProviderType(),
                record.getBaseUrl(),
                record.getModel(),
                record.getWireApi(),
                record.getTimeoutSeconds(),
                record.getMaxRuntimeSeconds(),
                apiKeyConfigured,
                apiKeyConfigured ? "[CONFIGURED]" : null,
                record.getLastTestStatus(),
                record.getLastTestMessage(),
                record.getLastTestAgentType(),
                record.getLastTestBasicAvailable(),
                record.getLastTestAgentCompatible(),
                jsonbToMap(record.getLastTestDetails()),
                record.getLastTestedAt(),
                record.getLastConnectivityStatus(),
                record.getLastConnectivityMessage(),
                jsonbToMap(record.getLastConnectivityDetails()),
                record.getLastConnectivityTestedAt(),
                record.getLastConnectivityDurationMs(),
                List.of(),
                null,
                record.getCreatedAt(),
                record.getUpdatedAt()
        );
    }

    static ModelProfileResponse from(
            ModelProfilesRecord record,
            List<ModelProfileAgentProbeResultResponse> agentProbeResults,
            ActiveAgentProbeRunResponse activeAgentProbeRun
    ) {
        ModelProfileResponse base = from(record);
        return new ModelProfileResponse(
                base.id(),
                base.name(),
                base.enabled(),
                base.executablePath(),
                base.modelProviderType(),
                base.baseUrl(),
                base.model(),
                base.wireApi(),
                base.timeoutSeconds(),
                base.maxRuntimeSeconds(),
                base.apiKeyConfigured(),
                base.apiKeyMasked(),
                base.lastTestStatus(),
                base.lastTestMessage(),
                base.lastTestAgentType(),
                base.lastTestBasicAvailable(),
                base.lastTestAgentCompatible(),
                base.lastTestDetails(),
                base.lastTestedAt(),
                base.lastConnectivityStatus(),
                base.lastConnectivityMessage(),
                base.lastConnectivityDetails(),
                base.lastConnectivityTestedAt(),
                base.lastConnectivityDurationMs(),
                agentProbeResults == null ? List.of() : List.copyOf(agentProbeResults),
                activeAgentProbeRun,
                base.createdAt(),
                base.updatedAt()
        );
    }

    private static Map<String, Object> jsonbToMap(org.jooq.JSONB value) {
        if (value == null || value.data() == null || value.data().isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(value.data(), MAP_TYPE);
        } catch (Exception ex) {
            return Map.of();
        }
    }
}
