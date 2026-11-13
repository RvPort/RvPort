package cn.rvport.backend.automatedtest;

import static cn.rvport.backend.jooq.tables.Environments.ENVIRONMENTS;
import static cn.rvport.backend.jooq.tables.TestRuns.TEST_RUNS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.rvport.backend.jooq.tables.records.TestRunsRecord;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.testfixture.JooqMockDatabase;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Result;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AutomatedTestRepositoryTests {

    private static final UUID ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID TASK_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID BUILD_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID REVISION_ID = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID ENVIRONMENT_ID = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID BATCH_ID = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-11T10:00:00Z");

    @Test
    void createsPendingAndNormalizesWorkingDirectory() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            AutomatedTestRepository repository = new AutomatedTestRepository(database.dsl(), new ObjectMapper());
            database.enqueueResult(database.result(new Field<?>[]{TEST_RUNS.ID}, new Object[]{ID}));
            AutomatedTestInput input = new AutomatedTestInput(
                    PROJECT_ID, ID, BUILD_ID, REVISION_ID, ENVIRONMENT_ID, 4L, BATCH_ID, TestBatchRole.SOURCE,
                    "ctest", "  /work  ", 300, Map.of(), List.of(), null);

            assertThat(repository.createPending(input, TASK_ID)).isEqualTo(ID);
            assertThat(database.lastExecution().bindings()).contains("/work", TestBatchRole.SOURCE.name());
            database.verifyComplete();
        }
    }

    @Test
    void mapsRunsAndReportArtifactsAndHandlesMalformedArtifacts() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            AutomatedTestRepository repository = new AutomatedTestRepository(database.dsl(), new ObjectMapper());
            TestRunsRecord run = record(TestBatchRole.SOURCE, JSONB.valueOf("[{\"path\":\"report.xml\"}]"));
            database.enqueueResult(joined(database, run, "source"))
                    .enqueueResult(joined(database, record(null, JSONB.valueOf("bad-json")), "target"));

            AutomatedTestRunView byId = repository.findById(ID).orElseThrow();
            AutomatedTestRunView byTask = repository.findByTaskId(TASK_ID).orElseThrow();
            assertThat(byId.environmentName()).isEqualTo("source");
            assertThat(byId.reportArtifacts()).singleElement().satisfies(value -> assertThat(value.get("path")).isEqualTo("report.xml"));
            assertThat(byId.testBatchRole()).isEqualTo(TestBatchRole.SOURCE);
            assertThat(byTask.reportArtifacts()).isEmpty();
            assertThat(byTask.testBatchRole()).isNull();
            database.verifyComplete();
        }
    }

    @Test
    void findsBatchPeerAndShortCircuitsIncompletePeerRequests() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            AutomatedTestRepository repository = new AutomatedTestRepository(database.dsl(), new ObjectMapper());
            AutomatedTestRunView run = view(BATCH_ID);
            assertThat(repository.findBatchPeer(null, TestBatchRole.TARGET)).isEmpty();
            assertThat(repository.findBatchPeer(view(null), TestBatchRole.TARGET)).isEmpty();
            assertThat(repository.findBatchPeer(run, null)).isEmpty();
            assertThat(database.executions()).isEmpty();

            database.enqueueResult(joined(database, record(TestBatchRole.TARGET, null), "target"));
            assertThat(repository.findBatchPeer(run, TestBatchRole.TARGET)).isPresent();
            assertThat(database.lastExecution().normalizedSql()).contains("test_batch_id =", "test_batch_role =", "test_runs.id <>");
            database.verifyComplete();
        }
    }

    @Test
    void checksOptionalBuildRunAndFindsLatestWithContextFilters() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            AutomatedTestRepository repository = new AutomatedTestRepository(database.dsl(), new ObjectMapper());
            assertThat(repository.buildRunExists(PROJECT_ID, null)).isTrue();
            assertThat(database.executions()).isEmpty();
            database.enqueueRows(1).enqueueResult(joined(database, record(TestBatchRole.SOURCE, null), "source"))
                    .enqueueResult(database.result(fieldsWithEnvironmentName()));
            assertThat(repository.buildRunExists(PROJECT_ID, BUILD_ID)).isTrue();
            assertThat(repository.findLatest(PROJECT_ID, REVISION_ID, 4L)).isPresent();
            assertThat(repository.findLatest(PROJECT_ID, null, null)).isEmpty();
            assertThat(database.executions().get(1).normalizedSql()).contains("source_revision_id =", "environment_config_version =", "case when test_runs.status in");
            assertThat(database.executions().get(2).normalizedSql()).doesNotContain("source_revision_id =", "environment_config_version =");
            database.verifyComplete();
        }
    }

    @Test
    void filtersListsSortsAndClampsPaging() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            AutomatedTestRepository repository = new AutomatedTestRepository(database.dsl(), new ObjectMapper());
            AutomatedTestListRequest request = new AutomatedTestListRequest(
                    TaskStatus.SUCCEEDED, ENVIRONMENT_ID, REVISION_ID, -1, 500, "status", "asc");
            database.enqueueResult(database.result(new Field<?>[]{DSL.count()}, new Object[]{1L}))
                    .enqueueResult(joined(database, record(TestBatchRole.SOURCE, null), "source"));
            assertThat(repository.count(PROJECT_ID, request)).isEqualTo(1L);
            assertThat(repository.list(PROJECT_ID, request)).hasSize(1);
            assertThat(database.executions().getFirst().normalizedSql()).contains("status =", "environment_id =", "source_revision_id =");
            assertThat(database.executions().get(1).normalizedSql()).contains("status asc nulls last");
            assertThat(database.executions().get(1).bindings()).contains(100L, 0L);
            database.verifyComplete();
        }

        try (JooqMockDatabase database = new JooqMockDatabase()) {
            AutomatedTestRepository repository = new AutomatedTestRepository(database.dsl(), new ObjectMapper());
            String[] sorts = {"finishedAt", "createdAt", "unknown"};
            for (String ignored : sorts) database.enqueueResult(database.result(fieldsWithEnvironmentName()));
            for (String sort : sorts) repository.list(PROJECT_ID,
                    new AutomatedTestListRequest(null, null, null, 1, 20, sort, "desc"));
            assertThat(database.executions().get(0).normalizedSql()).contains("finished_at desc nulls last");
            assertThat(database.executions().get(1).normalizedSql()).contains("tasks.created_at desc nulls last");
            assertThat(database.executions().get(2).normalizedSql()).contains("started_at desc nulls last");
            database.verifyComplete();
        }
    }

    @Test
    void updatesLifecycleStatsAndSerializationFallback() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            ObjectMapper mapper = Mockito.mock(ObjectMapper.class);
            Mockito.when(mapper.writeValueAsString(Mockito.any()))
                    .thenThrow(new JsonProcessingException("cannot serialize") { });
            AutomatedTestRepository repository = new AutomatedTestRepository(database.dsl(), mapper);
            database.enqueueRows(1).enqueueRows(1).enqueueRows(1);
            repository.markRunning(ID);
            repository.finish(ID, TaskStatus.SUCCEEDED, 0, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    " summary ", List.of(Map.of("path", "report")));
            repository.updateStats(ID, 10, 1, 2);
            assertThat(database.executions().getFirst().normalizedSql()).contains("started_at");
            assertThat(database.executions().get(1).bindings()).contains("summary").anySatisfy(value -> {
                if (value instanceof JSONB jsonb) assertThat(jsonb.data()).isEqualTo("[]");
            });
            assertThat(database.executions().get(2).normalizedSql()).contains("passed_count", "failed_count", "skipped_count");
            database.verifyComplete();
        }
    }

    @Test
    void propagatesDatabaseFailure() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            AutomatedTestRepository repository = new AutomatedTestRepository(database.dsl(), new ObjectMapper());
            database.enqueueFailure(new SQLException("database unavailable"));
            assertThatThrownBy(() -> repository.findById(ID))
                    .isInstanceOf(org.jooq.exception.DataAccessException.class)
                    .hasMessageContaining("database unavailable");
            database.verifyComplete();
        }
    }

    private static TestRunsRecord record(TestBatchRole role, JSONB artifacts) {
        TestRunsRecord record = new TestRunsRecord();
        record.setId(ID); record.setProjectId(PROJECT_ID); record.setTaskId(TASK_ID); record.setBuildRunId(BUILD_ID);
        record.setSourceRevisionId(REVISION_ID); record.setEnvironmentId(ENVIRONMENT_ID); record.setEnvironmentConfigVersion(4L);
        record.setTestBatchId(BATCH_ID); record.setTestBatchRole(role == null ? null : role.name()); record.setCommand("ctest");
        record.setWorkingDirectory("/work"); record.setTimeoutSeconds(300); record.setStatus(TaskStatus.SUCCEEDED.name());
        record.setOutputSummary("done"); record.setReportArtifacts(artifacts); record.setStartedAt(NOW); record.setFinishedAt(NOW);
        return record;
    }

    private static AutomatedTestRunView view(UUID batchId) {
        return new AutomatedTestRunView(ID, PROJECT_ID, TASK_ID, BUILD_ID, REVISION_ID, ENVIRONMENT_ID, 4L, batchId,
                TestBatchRole.SOURCE, "source", "ctest", "/work", 300, 0, 1, 0, 0, TaskStatus.SUCCEEDED,
                null, null, null, "done", List.of(), NOW, NOW);
    }

    private static Result<?> joined(JooqMockDatabase database, TestRunsRecord record, String environmentName) {
        Field<?>[] fields = fieldsWithEnvironmentName();
        Object[] values = new Object[fields.length];
        for (int index = 0; index < TEST_RUNS.fields().length; index++) values[index] = record.get(TEST_RUNS.fields()[index]);
        values[values.length - 1] = environmentName;
        return database.result(fields, values);
    }

    private static Field<?>[] fieldsWithEnvironmentName() {
        Field<?>[] fields = Arrays.copyOf(TEST_RUNS.fields(), TEST_RUNS.fields().length + 1);
        fields[fields.length - 1] = ENVIRONMENTS.NAME;
        return fields;
    }
}
