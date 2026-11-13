package cn.rvport.backend.agentruntime;

import cn.rvport.backend.jooq.tables.records.AgentRuntimesRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AgentRuntimeResponse(
        UUID id,
        String agentType,
        String name,
        boolean enabled,
        String executablePath,
        List<String> defaultArgs,
        Map<String, Object> config,
        String lastProbeStatus,
        String lastProbeMessage,
        Map<String, Object> lastProbeDetails,
        OffsetDateTime lastProbeAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static AgentRuntimeResponse from(AgentRuntimesRecord record) {
        return new AgentRuntimeResponse(
                record.getId(),
                record.getAgentType(),
                record.getName(),
                Boolean.TRUE.equals(record.getEnabled()),
                null,
                List.of(),
                Map.of(),
                null,
                null,
                Map.of(),
                null,
                record.getCreatedAt(),
                record.getUpdatedAt()
        );
    }

}
