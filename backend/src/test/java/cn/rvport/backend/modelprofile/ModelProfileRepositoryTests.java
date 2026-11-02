package cn.rvport.backend.modelprofile;

import static cn.rvport.backend.jooq.tables.AgentRuntimes.AGENT_RUNTIMES;
import static cn.rvport.backend.jooq.tables.ModelProfiles.MODEL_PROFILES;
import static cn.rvport.backend.jooq.tables.ModelStageAssignments.MODEL_STAGE_ASSIGNMENTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.rvport.backend.codexprovider.AgentType;
import cn.rvport.backend.jooq.tables.records.AgentRuntimesRecord;
import cn.rvport.backend.jooq.tables.records.ModelProfileAgentProbeResultsRecord;
import cn.rvport.backend.jooq.tables.records.ModelProfileAgentProbeRunsRecord;
import cn.rvport.backend.jooq.tables.records.ModelProfilesRecord;
import cn.rvport.backend.testfixture.JooqMockDatabase;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.Test;

class ModelProfileRepositoryTests {
    private static final UUID PROFILE_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID RUNTIME_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID ACTOR_ID = UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-11T10:00:00Z");

    @Test
    void createsFindsAndChecksCaseInsensitiveNames() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            ModelProfileRepository repository = new ModelProfileRepository(database.dsl());
            ModelProfilesRecord profile = profile();
            database.enqueueRecord(profile).enqueueRecord(profile).enqueueRecord(profile)
                    .enqueueRows(1).enqueueRows(0);

            assertThat(repository.create(PROFILE_ID, "DeepSeek", true, "codex", "OPENAI_COMPATIBLE",
                    "https://llm.example/v1", "deepseek", "RESPONSES", "cipher", 30, 600, ACTOR_ID))
                    .isNotNull();
            assertThat(repository.findById(PROFILE_ID)).contains(profile);
            assertThat(repository.findEnabledById(PROFILE_ID)).contains(profile);
            assertThat(repository.nameExists("DEEPSEEK", null)).isTrue();
            assertThat(repository.nameExists("DeepSeek", PROFILE_ID)).isFalse();
            assertThat(database.executions().get(3).normalizedSql()).contains("lower(model_profiles.name) = ?");
            assertThat(database.executions().get(4).normalizedSql()).contains("model_profiles.id <>");
            database.verifyComplete();
        }
    }

    @Test
    void listsCountsFiltersAndSupportsEverySortChoice() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            ModelProfileRepository repository = new ModelProfileRepository(database.dsl());
            String[] sorts = {"name", "updatedAt", "lastTestedAt", "createdAt", "unknown", ""};
            for (String ignored : sorts) {
                database.enqueueRecord(profile());
            }
            for (String sort : sorts) {
                var request = new ModelProfileListRequest(2, 5, true, " OPENAI_COMPATIBLE ", " PASSED ",
                        " deep ", sort, "asc");
                assertThat(repository.list(request)).hasSize(1);
            }
            database.enqueueResult(database.result(new Field<?>[]{org.jooq.impl.DSL.count()}, new Object[]{6L}));
            assertThat(repository.count(new ModelProfileListRequest(null, null, null, null, null,
                    null, null, null))).isEqualTo(6);
            assertThat(database.executions().getFirst().normalizedSql()).contains("enabled = ?", "model_provider_type = ?",
                    "last_test_status = ?", "lower(model_profiles.name) like ?", "fetch next ? rows only",
                    "offset ? rows", "name asc nulls last");
            assertThat(database.executions().get(4).normalizedSql()).contains("created_at asc nulls last");
            database.verifyComplete();
        }
    }

    @Test
    void updatesOnlyTouchedProfileFieldsAndCanClearExistingNullableValues() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            ModelProfileRepository repository = new ModelProfileRepository(database.dsl());
            ModelProfilesRecord current = profile();
            database.enqueueRecord(current).enqueueRecord(current);

            repository.update(current, "new", false, "new-codex", "CUSTOM", "https://new", "new-model",
                    "CHAT", "new-cipher", true, 45, 900, ACTOR_ID);
            String allFieldsSql = database.lastExecution().normalizedSql();
            assertThat(allFieldsSql).contains("name = ?", "enabled = ?", "base_url = ?", "model = ?",
                    "api_key_encrypted = ?", "timeout_seconds = ?", "max_runtime_seconds = ?");

            repository.update(current, null, null, null, null, null, null, null,
                    null, false, null, null, ACTOR_ID);
            String minimalSql = database.lastExecution().normalizedSql();
            assertThat(minimalSql).contains("base_url = ?", "model = ?", "updated_by = cast(")
                    .doesNotContain("api_key_encrypted = ?", "timeout_seconds = ?");
            database.verifyComplete();
        }
    }

    @Test
    void updatesLegacyAndConnectivityProbeResults() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            ModelProfileRepository repository = new ModelProfileRepository(database.dsl());
            database.enqueueRecord(profile()).enqueueRecord(profile());
            JSONB details = JSONB.valueOf("{\"ok\":true}");

            repository.updateTestResult(PROFILE_ID, "COMPATIBLE", "ok", NOW, AgentType.CODEX.name(),
                    true, true, details, ACTOR_ID);
            assertThat(database.lastExecution().normalizedSql()).contains("last_test_status = ?",
                    "last_test_agent_type = ?", "last_test_details = cast(", "last_tested_at is null", "last_tested_at <= cast(? as timestamp(6) with time zone)");
            repository.updateConnectivityResult(PROFILE_ID, "SUCCEEDED", "reachable", details, NOW, 123L, ACTOR_ID);
            assertThat(database.lastExecution().normalizedSql()).contains("last_connectivity_status = ?",
                    "last_connectivity_duration_ms = ?");
            database.verifyComplete();
        }
    }

    @Test
    void createsFindsAndTransitionsProbeRunsWithOptionalTimestamps() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            ModelProfileRepository repository = new ModelProfileRepository(database.dsl());
            ModelProfileAgentProbeRunsRecord run = probeRun();
            database.enqueueRecord(run).enqueueRecord(run).enqueueRecord(run).enqueueRecord(run).enqueueRecord(run);

            assertThat(repository.createProbeRun(RUN_ID, PROFILE_ID, JSONB.valueOf("[]"), JSONB.valueOf("[]"), ACTOR_ID))
                    .isNotNull();
            assertThat(repository.findProbeRun(RUN_ID)).contains(run);
            assertThat(repository.findActiveProbeRun(PROFILE_ID)).contains(run);
            assertThat(database.lastExecution().normalizedSql()).contains("status in", "created_at desc", "fetch next ? rows only");
            repository.updateProbeRunStatus(RUN_ID, AgentProbeRunStatus.RUNNING, "started", NOW, null);
            assertThat(database.lastExecution().normalizedSql()).contains("started_at = cast(").doesNotContain("finished_at = cast(");
            repository.updateProbeRunStatus(RUN_ID, AgentProbeRunStatus.SUCCESS, "done", null, NOW);
            assertThat(database.lastExecution().normalizedSql()).contains("finished_at = cast(").doesNotContain("started_at = cast(");
            assertThat(database.lastExecution().normalizedSql()).contains("status in");
            assertThat(database.lastExecution().bindings()).contains("PENDING", "RUNNING");
            database.verifyComplete();
        }
    }

    @Test
    void storesListsAndDeduplicatesLatestProbeResultsByRuntime() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            ModelProfileRepository repository = new ModelProfileRepository(database.dsl());
            UUID otherRuntime = UUID.fromString("30000000-0000-0000-0000-000000000099");
            ModelProfileAgentProbeResultsRecord newest = probeResult(RUNTIME_ID, NOW);
            ModelProfileAgentProbeResultsRecord older = probeResult(RUNTIME_ID, NOW.minusMinutes(1));
            ModelProfileAgentProbeResultsRecord other = probeResult(otherRuntime, NOW);
            database.enqueueRecord(newest).enqueueResult(result(database, newest, older, other))
                    .enqueueResult(result(database, newest, older, other));

            assertThat(repository.createProbeResult(newest.getId(), RUN_ID, PROFILE_ID, RUNTIME_ID, AgentType.CODEX.name(),
                    "COMPATIBLE", true, true, JSONB.valueOf("[]"), JSONB.valueOf("{}"), null, "ok",
                    NOW, NOW.plusSeconds(1), 1000L)).isNotNull();
            assertThat(repository.listProbeResultsByRun(RUN_ID)).hasSize(3);
            assertThat(repository.listLatestProbeResults(PROFILE_ID)).containsExactly(newest, other);
            assertThat(database.lastExecution().normalizedSql()).contains("agent_runtime_id asc", "created_at desc");
            database.verifyComplete();
        }
    }

    @Test
    void managesStageAssignmentsIncludingMissingJoinedRecords() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            ModelProfileRepository repository = new ModelProfileRepository(database.dsl());
            database.enqueueRows(1)
                    .enqueueResult(assignmentResult(database, true, true))
                    .enqueueResult(assignmentResult(database, false, false))
                    .enqueueRows(1).enqueueRows(1);

            assertThat(repository.isAssigned(PROFILE_ID)).isTrue();
            var assigned = repository.listAssignments().getFirst();
            assertThat(assigned.profile()).isNotNull();
            assertThat(assigned.agentRuntime()).isNotNull();
            var missing = repository.findAssignment(ModelProfileStage.TEST_LOG_COMPARE).orElseThrow();
            assertThat(missing.profile()).isNull();
            assertThat(missing.agentRuntime()).isNull();
            repository.upsertAssignment(ModelProfileStage.TEST_LOG_COMPARE, PROFILE_ID, RUNTIME_ID, ACTOR_ID);
            assertThat(database.lastExecution().normalizedSql()).contains("on conflict (stage)", "do update");
            repository.delete(PROFILE_ID);
            assertThat(database.lastExecution().normalizedSql()).contains("delete from model_profiles", "model_profiles.id = cast(");
            database.verifyComplete();
        }
    }

    @Test
    void propagatesDatabaseFailures() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            ModelProfileRepository repository = new ModelProfileRepository(database.dsl());
            database.enqueueFailure(new SQLException("database unavailable"));
            assertThatThrownBy(() -> repository.findById(PROFILE_ID))
                    .isInstanceOf(org.jooq.exception.DataAccessException.class)
                    .hasMessageContaining("database unavailable");
            database.verifyComplete();
        }
    }

    private static ModelProfilesRecord profile() {
        return new ModelProfilesRecord(PROFILE_ID, "DeepSeek", true, "codex", "OPENAI_COMPATIBLE",
                "https://llm.example/v1", "deepseek", "RESPONSES", "cipher", 30, 600,
                "COMPATIBLE", "ok", NOW, ACTOR_ID, ACTOR_ID, NOW, NOW, AgentType.CODEX.name(),
                true, true, JSONB.valueOf("{}"), "SUCCEEDED", "reachable", JSONB.valueOf("{}"), NOW, 100L);
    }

    private static ModelProfileAgentProbeRunsRecord probeRun() {
        return new ModelProfileAgentProbeRunsRecord(RUN_ID, PROFILE_ID, AgentProbeRunStatus.PENDING.name(),
                JSONB.valueOf("[]"), JSONB.valueOf("[]"), null, ACTOR_ID, null, null, NOW, NOW);
    }

    private static ModelProfileAgentProbeResultsRecord probeResult(UUID runtimeId, OffsetDateTime createdAt) {
        return new ModelProfileAgentProbeResultsRecord(UUID.randomUUID(), RUN_ID, PROFILE_ID, runtimeId,
                AgentType.CODEX.name(), "COMPATIBLE", true, true, JSONB.valueOf("[]"), JSONB.valueOf("{}"),
                null, "ok", NOW, NOW, 10L, createdAt, createdAt);
    }

    private static Result<Record> result(JooqMockDatabase database, ModelProfileAgentProbeResultsRecord... records) {
        Result<Record> result = database.result(records[0].fields());
        for (ModelProfileAgentProbeResultsRecord record : records) {
            Record row = database.dsl().newRecord(record.fields());
            row.from(record.intoArray());
            result.add(row);
        }
        return result;
    }

    private static Result<Record> assignmentResult(JooqMockDatabase database, boolean includeProfile, boolean includeRuntime) {
        Field<?>[] fields = concat(MODEL_STAGE_ASSIGNMENTS.fields(), MODEL_PROFILES.fields(), AGENT_RUNTIMES.fields());
        Record row = database.dsl().newRecord(fields);
        row.set(MODEL_STAGE_ASSIGNMENTS.STAGE, ModelProfileStage.TEST_LOG_COMPARE.name());
        row.set(MODEL_STAGE_ASSIGNMENTS.MODEL_PROFILE_ID, PROFILE_ID);
        row.set(MODEL_STAGE_ASSIGNMENTS.AGENT_RUNTIME_ID, RUNTIME_ID);
        row.set(MODEL_STAGE_ASSIGNMENTS.UPDATED_AT, NOW);
        if (includeProfile) {
            row.set(MODEL_PROFILES.ID, PROFILE_ID);
            row.set(MODEL_PROFILES.NAME, "DeepSeek");
        }
        if (includeRuntime) {
            row.set(AGENT_RUNTIMES.ID, RUNTIME_ID);
            row.set(AGENT_RUNTIMES.AGENT_TYPE, AgentType.CODEX.name());
            row.set(AGENT_RUNTIMES.NAME, "Codex");
        }
        Result<Record> result = database.result(fields);
        result.add(row);
        return result;
    }

    private static Field<?>[] concat(Field<?>[]... groups) {
        int size = java.util.Arrays.stream(groups).mapToInt(group -> group.length).sum();
        Field<?>[] result = new Field<?>[size];
        int offset = 0;
        for (Field<?>[] group : groups) {
            System.arraycopy(group, 0, result, offset, group.length);
            offset += group.length;
        }
        return result;
    }
}
