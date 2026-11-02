package cn.rvport.backend.agentruntime;

import static cn.rvport.backend.jooq.tables.AgentRuntimes.AGENT_RUNTIMES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.rvport.backend.codexprovider.AgentType;
import cn.rvport.backend.jooq.tables.records.AgentRuntimesRecord;
import cn.rvport.backend.testfixture.JooqMockDatabase;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.JSONB;
import org.junit.jupiter.api.Test;

class AgentRuntimeRepositoryTests {
    private static final UUID ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-11T10:00:00Z");

    @Test
    void listsAndFindsRuntimesByIdentityEnabledStateAndType() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            AgentRuntimeRepository repository = new AgentRuntimeRepository(database.dsl());
            database.enqueueRecord(record()).enqueueRecord(record()).enqueueRecord(record()).enqueueRecord(record());
            assertThat(repository.list()).hasSize(1);
            assertThat(repository.findById(ID)).isPresent();
            assertThat(repository.findEnabledById(ID)).isPresent();
            assertThat(repository.findByType(AgentType.CODEX)).isPresent();
            assertThat(database.executions().getFirst().normalizedSql()).contains("agent_type asc");
            assertThat(database.executions().get(2).normalizedSql()).contains("enabled = true");
            database.verifyComplete();
        }
    }

    @Test
    void updatesProbeFieldsAndPropagatesDatabaseFailure() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            AgentRuntimeRepository repository = new AgentRuntimeRepository(database.dsl());
            database.enqueueRows(1);
            repository.updateProbeResult(ID, "COMPATIBLE", "ok", JSONB.valueOf("{\"tools\":true}"), NOW);
            assertThat(database.lastExecution().bindings()).contains("COMPATIBLE", "ok", ID)
                    .anySatisfy(value -> assertThat(String.valueOf(value)).contains("2026-09-11 10:00:00"));
            assertThat(database.lastExecution().normalizedSql()).contains("last_probe_at is null", "last_probe_at <= cast(? as timestamp(6) with time zone)");
            database.enqueueFailure(new SQLException("database unavailable"));
            assertThatThrownBy(repository::list).isInstanceOf(org.jooq.exception.DataAccessException.class)
                    .hasMessageContaining("database unavailable");
            database.verifyComplete();
        }
    }

    private static AgentRuntimesRecord record() {
        return new AgentRuntimesRecord(ID, AgentType.CODEX.name(), "Codex", true, "codex", JSONB.valueOf("[]"),
                JSONB.valueOf("{}"), null, null, null, null, NOW, NOW);
    }
}
