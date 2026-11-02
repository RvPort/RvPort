package cn.rvport.backend.agentruntime;

import static cn.rvport.backend.jooq.tables.AgentRuntimes.AGENT_RUNTIMES;

import cn.rvport.backend.codexprovider.AgentType;
import cn.rvport.backend.jooq.tables.records.AgentRuntimesRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

@Repository
public class AgentRuntimeRepository {

    private final DSLContext dsl;

    public AgentRuntimeRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<AgentRuntimesRecord> list() {
        return dsl.selectFrom(AGENT_RUNTIMES)
                .orderBy(AGENT_RUNTIMES.AGENT_TYPE.asc())
                .fetch();
    }

    public Optional<AgentRuntimesRecord> findById(UUID runtimeId) {
        return dsl.selectFrom(AGENT_RUNTIMES)
                .where(AGENT_RUNTIMES.ID.eq(runtimeId))
                .fetchOptional();
    }

    public Optional<AgentRuntimesRecord> findEnabledById(UUID runtimeId) {
        return dsl.selectFrom(AGENT_RUNTIMES)
                .where(AGENT_RUNTIMES.ID.eq(runtimeId).and(AGENT_RUNTIMES.ENABLED.isTrue()))
                .fetchOptional();
    }

    public Optional<AgentRuntimesRecord> findByType(AgentType agentType) {
        return dsl.selectFrom(AGENT_RUNTIMES)
                .where(AGENT_RUNTIMES.AGENT_TYPE.eq(agentType.name()))
                .fetchOptional();
    }

    public void updateProbeResult(
            UUID runtimeId,
            String status,
            String message,
            JSONB details,
            OffsetDateTime probedAt
    ) {
        dsl.update(AGENT_RUNTIMES)
                .set(AGENT_RUNTIMES.LAST_PROBE_STATUS, status)
                .set(AGENT_RUNTIMES.LAST_PROBE_MESSAGE, message)
                .set(AGENT_RUNTIMES.LAST_PROBE_DETAILS, details)
                .set(AGENT_RUNTIMES.LAST_PROBE_AT, probedAt)
                .set(AGENT_RUNTIMES.UPDATED_AT, OffsetDateTime.now())
                .where(AGENT_RUNTIMES.ID.eq(runtimeId))
                .and(AGENT_RUNTIMES.LAST_PROBE_AT.isNull().or(AGENT_RUNTIMES.LAST_PROBE_AT.le(probedAt)))
                .execute();
    }
}
