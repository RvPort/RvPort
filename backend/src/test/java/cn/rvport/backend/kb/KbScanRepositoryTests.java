package cn.rvport.backend.kb;

import static cn.rvport.backend.jooq.tables.Findings.FINDINGS;
import static cn.rvport.backend.jooq.tables.KbScanRuns.KB_SCAN_RUNS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.rvport.backend.jooq.tables.records.FindingsRecord;
import cn.rvport.backend.jooq.tables.records.KbScanRunsRecord;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.testfixture.JooqMockDatabase;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class KbScanRepositoryTests {

    private static final UUID PROJECT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID REVISION_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID TASK_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID FINDING_ID = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID ARTIFACT_ID = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID STORED_ARTIFACT_ID = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-11T10:00:00Z");

    private static final Field<UUID> KBA_ID = DSL.field(DSL.name("kb_scan_artifacts", "id"), SQLDataType.UUID);
    private static final Field<UUID> KBA_PROJECT_ID = DSL.field(DSL.name("kb_scan_artifacts", "project_id"), SQLDataType.UUID);
    private static final Field<UUID> KBA_SCAN_RUN_ID = DSL.field(DSL.name("kb_scan_artifacts", "scan_run_id"), SQLDataType.UUID);
    private static final Field<String> KBA_ARTIFACT_TYPE = DSL.field(DSL.name("kb_scan_artifacts", "artifact_type"), SQLDataType.CLOB);
    private static final Field<String> KBA_SCHEMA_VERSION = DSL.field(DSL.name("kb_scan_artifacts", "schema_version"), SQLDataType.CLOB);
    private static final Field<String> KBA_SHA256 = DSL.field(DSL.name("kb_scan_artifacts", "sha256"), SQLDataType.CLOB);
    private static final Field<Long> KBA_SIZE_BYTES = DSL.field(DSL.name("kb_scan_artifacts", "size_bytes"), SQLDataType.BIGINT);
    private static final Field<String> KBA_CONTENT_PATH = DSL.field(DSL.name("kb_scan_artifacts", "content_path"), SQLDataType.CLOB);
    private static final Field<UUID> KBA_STORED_ARTIFACT_ID = DSL.field(DSL.name("kb_scan_artifacts", "stored_artifact_id"), SQLDataType.UUID);
    private static final Field<JSONB> KBA_SUMMARY = DSL.field(DSL.name("kb_scan_artifacts", "summary"), SQLDataType.JSONB);
    private static final Field<OffsetDateTime> KBA_CREATED_AT = DSL.field(DSL.name("kb_scan_artifacts", "created_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);

    @Test
    void contextQueriesBindEveryDimensionAndSeparateActiveOrderingFromHistory() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            var repository = repository(database);
            var record = run(TaskStatus.FAILED, null, null);
            record.setSourceEnvironmentId(KbContextFixtures.ENV);
            record.setRuntimeEnvironmentVersion(7L);
            record.setBuildRunId(KbContextFixtures.BUILD);
            database.enqueueRecord(record).enqueueRecord(record).enqueueResult(database.result(new Field<?>[]{DSL.count()}, new Object[]{1L}));
            assertThat(repository.findLatestRunByContext(PROJECT_ID, REVISION_ID, KbContextFixtures.CONTEXT).orElseThrow().executionContext())
                    .isEqualTo(KbContextFixtures.CONTEXT);
            assertThat(repository.findLatestHistoricalRun(PROJECT_ID).orElseThrow().status()).isEqualTo(TaskStatus.FAILED);
            var request = new KbScanListRequest(null, null, null, null, null, null, null)
                    .withContext(REVISION_ID, KbContextFixtures.CONTEXT);
            assertThat(repository.countRuns(PROJECT_ID, request)).isEqualTo(1);
            assertThat(database.executions().get(0).normalizedSql()).contains("case", "created_at desc nulls last", "id desc", "source_environment_id =", "runtime_environment_version =", "build_run_id =");
            assertThat(database.executions().get(0).bindings()).contains(PROJECT_ID, REVISION_ID, KbContextFixtures.ENV, 7L, KbContextFixtures.BUILD);
            assertThat(database.executions().get(1).normalizedSql()).doesNotContain("case").contains("created_at desc nulls last", "id desc");
            assertThat(database.executions().get(2).bindings()).contains(KbContextFixtures.ENV, 7L, KbContextFixtures.BUILD);
            assertThatThrownBy(() -> repository.createRun(new KbScanInput(PROJECT_ID, null, REVISION_ID, null, null, null, Map.of(), List.of()), TASK_ID))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> repository.createRun(new KbScanInput(PROJECT_ID, null, REVISION_ID, null, null, null, Map.of(), List.of(), new KbExecutionContext(null, 7L, null)), TASK_ID))
                    .isInstanceOf(IllegalArgumentException.class);
            database.verifyComplete();
        }
    }

    @Test
    void createsAndFindsRunsIncludingLatestVariants() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            KbScanRepository repository = repository(database);
            database.enqueueResult(database.result(new Field<?>[]{KB_SCAN_RUNS.ID}, new Object[]{RUN_ID}))
                    .enqueueRecord(run(TaskStatus.PENDING, JSONB.valueOf("{\"mode\":\"ALL\"}"), null))
                    .enqueueRecord(run(TaskStatus.RUNNING, null, JSONB.valueOf("bad")))
                    .enqueueRecord(run(TaskStatus.SUCCEEDED, null, null))
                    .enqueueRecord(run(TaskStatus.RUNNING, null, null))
                    .enqueueRecord(run(TaskStatus.RUNNING, null, null));

            UUID created = repository.createRun(new KbScanInput(PROJECT_ID, RUN_ID, REVISION_ID,
                    " v2 ", "c", "x86", "riscv64", Map.of("mode", "ALL"), List.of(), KbContextFixtures.CONTEXT), TASK_ID);
            assertThat(created).isEqualTo(RUN_ID);
            assertThat(repository.findById(RUN_ID).orElseThrow().scope()).containsEntry("mode", "ALL");
            assertThat(repository.findByTaskId(TASK_ID).orElseThrow().summary()).isEmpty();
            assertThat(repository.findLatestSucceededRunBySourceRevision(PROJECT_ID, REVISION_ID)).isPresent();
            assertThat(repository.findLatestRunBySourceRevision(PROJECT_ID, REVISION_ID)).isPresent();
            assertThat(repository.findLatestRun(PROJECT_ID)).isPresent();
            assertThat(database.executions().getFirst().bindings()).contains("v2");
            assertThat(database.executions().get(4).normalizedSql()).contains("left outer join", "case");
            database.verifyComplete();
        }
    }

    @Test
    void countsFiltersAndPaginatesRuns() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            KbScanRepository repository = repository(database);
            KbScanListRequest request = new KbScanListRequest(2, 10, TaskStatus.SUCCEEDED, REVISION_ID,
                    false, true, " v2 ", "status", "desc");
            database.enqueueResult(database.result(new Field<?>[]{DSL.count()}, new Object[]{2L}))
                    .enqueueRecord(run(TaskStatus.SUCCEEDED, null, null));
            assertThat(repository.countRuns(PROJECT_ID, request)).isEqualTo(2L);
            assertThat(repository.listRuns(PROJECT_ID, request)).hasSize(1);
            assertThat(database.executions().getFirst().normalizedSql()).contains("status =", "source_revision_id =", "kb_version =");
            assertThat(database.executions().get(1).normalizedSql()).contains("status desc nulls last", "offset ? rows");
            database.verifyComplete();
        }
    }

    @Test
    void supportsEveryRunSortAndRejectsUnknownSort() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            KbScanRepository repository = repository(database);
            for (int index = 0; index < 5; index++) database.enqueueResult(database.result(KB_SCAN_RUNS.fields()));
            for (String sort : new String[]{"kbVersion", "startedAt", "finishedAt", "", "status"}) {
                repository.listRuns(PROJECT_ID, new KbScanListRequest(null, null, null, null, null, sort, "asc"));
            }
            assertThat(database.executions().get(0).normalizedSql()).contains("kb_version asc nulls last");
            assertThat(database.executions().get(3).normalizedSql()).contains("started_at desc nulls last", "id desc");
            assertThatThrownBy(() -> repository.listRuns(PROJECT_ID,
                    new KbScanListRequest(1, 20, null, null, null, "updatedAt", "asc")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported KB scan sort field");
            database.verifyComplete();
        }
    }

    @Test
    void updatesLifecycleAndFallsBackWhenSummaryCannotSerialize() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            ObjectMapper mapper = Mockito.mock(ObjectMapper.class);
            Mockito.when(mapper.writeValueAsString(Mockito.any())).thenThrow(new JsonProcessingException("bad") { });
            KbScanRepository repository = new KbScanRepository(database.dsl(), mapper);
            database.enqueueRows(1).enqueueRows(1).enqueueRows(1).enqueueRows(1);
            repository.markRunning(RUN_ID);
            repository.markSucceeded(RUN_ID, Map.of("count", 2));
            repository.markFailed(RUN_ID, null);
            repository.markCancelled(RUN_ID, Map.of());
            assertThat(database.executions()).hasSize(4);
            assertThat(database.executions().get(1).bindings()).anySatisfy(value -> {
                if (value instanceof JSONB jsonb) assertThat(jsonb.data()).isEqualTo("{}");
            });
            database.verifyComplete();
        }
    }

    @Test
    void replacesFindingsAndNormalizesOptionalText() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            KbScanRepository repository = repository(database);
            database.enqueueRows(3).enqueueRows(1).enqueueRows(1);
            repository.replaceFindings(RUN_ID, List.of(
                    new KbFindingInput(PROJECT_ID, RUN_ID, REVISION_ID, "R1", "PORTABILITY", "high", "title",
                            " description ", " src/a.c ", 3, 4, " fn ", Map.of("line", 3), " fix ", "fp1"),
                    new KbFindingInput(PROJECT_ID, RUN_ID, REVISION_ID, "R2", "PORTABILITY", "low", "other",
                            " ", null, null, null, "", null, "", "fp2")));
            assertThat(database.executions()).hasSize(3);
            assertThat(database.executions().get(1).bindings()).contains("description", "src/a.c", "fn", "fix", "DETECTED");
            assertThat(database.executions().get(2).bindings()).containsNull();
            database.verifyComplete();
        }
    }

    @Test
    void findsFiltersAndSortsFindingsWithJsonFallback() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            KbScanRepository repository = repository(database);
            FindingListRequest request = new FindingListRequest(2, 5, " R1 ", "PORTABILITY", "high", "vector",
                    "src", "func", "DETECTED", "severity", "desc");
            database.enqueueRecord(finding(JSONB.valueOf("{\"kind\":\"simd\"}")))
                    .enqueueResult(database.result(new Field<?>[]{DSL.count()}, new Object[]{1L}))
                    .enqueueRecord(finding(JSONB.valueOf("bad")))
                    .enqueueRecord(finding(null))
                    .enqueueRecord(finding(null))
                    .enqueueResult(database.result(new Field<?>[]{FINDINGS.ID}, new Object[]{FINDING_ID}));
            assertThat(repository.findFinding(FINDING_ID).orElseThrow().evidence()).containsEntry("kind", "simd");
            assertThat(repository.countFindings(RUN_ID, request)).isEqualTo(1L);
            assertThat(repository.listFindingViews(RUN_ID, request).getFirst().evidence()).isEmpty();
            assertThat(repository.listFindings(RUN_ID, request)).hasSize(1);
            assertThat(repository.listAllFindingViews(RUN_ID)).hasSize(1);
            assertThat(repository.listFindingIds(RUN_ID)).containsExactly(FINDING_ID);
            assertThat(database.executions().get(1).normalizedSql()).contains("lower", "like", "rule_id =", "category =");
            assertThat(database.executions().get(2).normalizedSql()).contains("severity desc nulls last", "offset ? rows");
            database.verifyComplete();
        }
    }

    @Test
    void supportsEveryFindingSortAndRejectsUnknownSort() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            KbScanRepository repository = repository(database);
            String[] sorts = {"ruleId", "category", "title", "filePath", "lineStart", "status", "createdAt", ""};
            for (String ignored : sorts) database.enqueueResult(database.result(FINDINGS.fields()));
            for (String sort : sorts) repository.listFindingViews(RUN_ID,
                    new FindingListRequest(null, null, null, null, null, null, null, null, null, sort, "asc"));
            assertThat(database.executions().get(0).normalizedSql()).contains("rule_id asc nulls last");
            assertThat(database.executions().get(7).normalizedSql()).contains("case", "file_path asc nulls last");
            assertThatThrownBy(() -> repository.listFindingViews(RUN_ID,
                    new FindingListRequest(1, 20, null, null, null, null, null, null, null, "score", "asc")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported finding sort field");
            database.verifyComplete();
        }
    }

    @Test
    void upsertsListsAndDownloadsArtifacts() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            KbScanRepository repository = repository(database);
            KbScanArtifact artifact = new KbScanArtifact(PROJECT_ID, TASK_ID, RUN_ID, KbScanArtifactType.SCAN_RESULT_JSON,
                    "v2", "abc", 42L, "/runtime/result.json", STORED_ARTIFACT_ID, Map.of("count", 1));
            KbScanArtifactListRequest request = new KbScanArtifactListRequest(2, 5, KbScanArtifactType.SCAN_RESULT_JSON,
                    " v2 ", " abc ", "sizeBytes", "desc");
            database.enqueueRows(1)
                    .enqueueResult(database.result(new Field<?>[]{DSL.count()}, new Object[]{1L}))
                    .enqueueResult(artifactSummaryResult(database, JSONB.valueOf("{\"count\":1}")))
                    .enqueueResult(artifactDownloadResult(database))
                    .enqueueResult(artifactDownloadResult(database));
            repository.upsertArtifact(artifact);
            assertThat(repository.countArtifacts(RUN_ID, request)).isEqualTo(1L);
            assertThat(repository.listArtifacts(RUN_ID, request).getFirst().summary()).containsEntry("count", 1);
            assertThat(repository.findArtifact(RUN_ID, ARTIFACT_ID)).isPresent();
            assertThat(repository.findArtifactByType(RUN_ID, KbScanArtifactType.SCAN_RESULT_JSON)).isPresent();
            assertThat(database.executions().getFirst().normalizedSql()).contains("on conflict", "do update");
            assertThat(database.executions().get(2).normalizedSql()).contains("size_bytes desc nulls last", "offset ? rows");
            database.verifyComplete();
        }
    }

    @Test
    void supportsEveryArtifactSortRejectsUnknownAndPropagatesDatabaseFailure() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            KbScanRepository repository = repository(database);
            String[] sorts = {"artifactType", "schemaVersion", "sha256", "createdAt", ""};
            Field<?>[] fields = artifactSummaryFields();
            for (String ignored : sorts) database.enqueueResult(database.result(fields));
            for (String sort : sorts) repository.listArtifacts(RUN_ID,
                    new KbScanArtifactListRequest(null, null, null, null, null, sort, "asc"));
            assertThat(database.executions().get(0).normalizedSql()).contains("artifact_type asc nulls last");
            assertThat(database.executions().get(4).normalizedSql()).contains("created_at desc", "artifact_type asc");
            assertThatThrownBy(() -> repository.listArtifacts(RUN_ID,
                    new KbScanArtifactListRequest(1, 20, null, null, null, "path", "asc")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported KB artifact sort field");
            database.enqueueFailure(new SQLException("database unavailable"));
            assertThatThrownBy(() -> repository.findById(RUN_ID))
                    .isInstanceOf(org.jooq.exception.DataAccessException.class).hasMessageContaining("database unavailable");
            database.verifyComplete();
        }
    }

    private static KbScanRepository repository(JooqMockDatabase database) {
        return new KbScanRepository(database.dsl(), new ObjectMapper());
    }

    private static KbScanRunsRecord run(TaskStatus status, JSONB scope, JSONB summary) {
        return new KbScanRunsRecord(RUN_ID, PROJECT_ID, TASK_ID, REVISION_ID, "v2", scope, status.name(), summary, NOW, NOW, null, null, null);
    }

    private static FindingsRecord finding(JSONB evidence) {
        return new FindingsRecord(FINDING_ID, PROJECT_ID, RUN_ID, REVISION_ID, "R1", "PORTABILITY", "high",
                "vector issue", "description", "src/a.c", 3, 4, "func", evidence, "suggestion", "fp", "DETECTED", NOW);
    }

    private static Field<?>[] artifactSummaryFields() {
        return new Field<?>[]{KBA_ID, KBA_ARTIFACT_TYPE, KBA_SCHEMA_VERSION, KBA_SHA256, KBA_SIZE_BYTES, KBA_CREATED_AT, KBA_SUMMARY};
    }

    private static org.jooq.Result<org.jooq.Record> artifactSummaryResult(JooqMockDatabase database, JSONB summary) {
        return database.result(artifactSummaryFields(), new Object[]{ARTIFACT_ID, KbScanArtifactType.SCAN_RESULT_JSON.name(),
                "v2", "abc", 42L, NOW, summary});
    }

    private static org.jooq.Result<org.jooq.Record> artifactDownloadResult(JooqMockDatabase database) {
        return database.result(new Field<?>[]{KBA_ID, KBA_PROJECT_ID, KBA_SCAN_RUN_ID, KBA_ARTIFACT_TYPE, KBA_SHA256,
                        KBA_SIZE_BYTES, KBA_CONTENT_PATH, KBA_STORED_ARTIFACT_ID},
                new Object[]{ARTIFACT_ID, PROJECT_ID, RUN_ID, KbScanArtifactType.SCAN_RESULT_JSON.name(), "abc", 42L,
                        "/runtime/result.json", STORED_ARTIFACT_ID});
    }
}
