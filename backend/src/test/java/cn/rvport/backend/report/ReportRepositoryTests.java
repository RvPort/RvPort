package cn.rvport.backend.report;

import static cn.rvport.backend.jooq.tables.ReportInputs.REPORT_INPUTS;
import static cn.rvport.backend.jooq.tables.Reports.REPORTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.rvport.backend.jooq.tables.records.ReportInputsRecord;
import cn.rvport.backend.jooq.tables.records.ReportsRecord;
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
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ReportRepositoryTests {

    @Test void capturesMetadataAndReadsHistoricalRevisionLabelsInOneBatch() throws Exception {
        try(var db=new JooqMockDatabase()) {
            var repo=repository(db);
            assertThat(repo.sourceMetadata(List.of())).isEmpty();assertThat(db.executions()).isEmpty();
            db.enqueueRows(1);
            repo.captureSourceMetadata(REPORT_ID,SourceVersionMetadata.of("版本",null,"SNAPSHOT"));
            assertThat(db.lastExecution().bindings()).contains(REPORT_ID);
            assertThat(db.lastExecution().bindings()[0].toString()).contains("版本","SNAPSHOT");
            var fields=new Field<?>[]{DSL.field("id",UUID.class),DSL.field("version_label",String.class),DSL.field("git_commit",String.class)};
            db.enqueueResult(db.result(fields,new Object[]{REVISION_ID,"版本","aabb"}));
            assertThat(repo.sourceMetadata(List.of(REVISION_ID))).containsEntry(REVISION_ID,SourceVersionMetadata.of("版本","aabb","REVISION_RECORD"));
            assertThat(db.lastExecution().sql()).contains("id=any").doesNotContain("current_source");
            var broken=Mockito.mock(ObjectMapper.class);
            Mockito.when(broken.writeValueAsString(Mockito.any())).thenThrow(new JsonProcessingException("failure"){});
            assertThatThrownBy(()->new ReportRepository(db.dsl(),broken).captureSourceMetadata(REPORT_ID,Map.of())).hasMessage("REPORT_METADATA_INVALID");
            db.verifyComplete();
        }
    }

    private static final UUID REPORT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID TASK_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID REVISION_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID INPUT_ID = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID SET_ID = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-11T10:00:00Z");

    @Test
    void createsFindsAndSelectsLatestReportsWithJsonFallbacks() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            ReportRepository repository = repository(database);
            database.enqueueRecord(report(TaskStatus.PENDING, JSONB.valueOf("{\"count\":1}")))
                    .enqueueRecord(report(TaskStatus.PENDING, JSONB.valueOf("bad")))
                    .enqueueRecord(report(TaskStatus.PENDING, null))
                    .enqueueRecord(report(TaskStatus.SUCCEEDED, null))
                    .enqueueRecord(report(TaskStatus.SUCCEEDED, null))
                    .enqueueRecord(report(TaskStatus.RUNNING, null));
            assertThat(repository.createEvaluation(REPORT_ID, PROJECT_ID, REVISION_ID, TASK_ID, "Evaluation", USER_ID).summary()).containsEntry("count", 1);
            assertThat(repository.findById(REPORT_ID).orElseThrow().summary()).isEmpty();
            assertThat(repository.findByTaskId(TASK_ID)).isPresent();
            assertThat(repository.latest(PROJECT_ID, null)).isPresent();
            assertThat(repository.latestSucceeded(PROJECT_ID, REVISION_ID)).isPresent();
            assertThat(repository.latestEvaluation(PROJECT_ID, null)).isPresent();
            assertThat(database.executions().get(4).normalizedSql()).contains("report_type =", "status =", "case");
            database.verifyComplete();
        }
    }

    @Test
    void filtersCountsSortsAndPaginatesReports() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            ReportRepository repository = repository(database);
            ReportListRequest request = new ReportListRequest(2, 5, REVISION_ID, TaskStatus.SUCCEEDED, " HIGH ",
                    NOW.minusDays(1), NOW.plusDays(1), "riskLevel", "asc");
            database.enqueueResult(database.result(new Field<?>[]{DSL.count()}, new Object[]{1L})).enqueueRecord(report(TaskStatus.SUCCEEDED, null));
            assertThat(repository.count(PROJECT_ID, request)).isEqualTo(1);
            assertThat(repository.list(PROJECT_ID, request)).hasSize(1);
            assertThat(database.executions().getFirst().normalizedSql()).contains("source_revision_id =", "status =", "risk_level =", "created_at >=", "created_at <=");
            assertThat(database.executions().get(1).normalizedSql()).contains("risk_level asc nulls last", "offset ? rows");
            database.verifyComplete();
        }
    }

    @Test
    void supportsAllReportSortsAndRejectsUnknown() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            ReportRepository repository = repository(database);
            String[] sorts = {"generatedAt", "createdAt", "status", ""};
            for (String ignored : sorts) database.enqueueResult(database.result(REPORTS.fields()));
            for (String sort : sorts) repository.list(PROJECT_ID,
                    new ReportListRequest(null, null, null, null, null, null, null, sort, "desc"));
            assertThat(database.executions().get(0).normalizedSql()).contains("generated_at desc nulls last");
            assertThat(database.executions().get(3).normalizedSql()).contains("generated_at desc nulls last", "created_at desc");
            assertThatThrownBy(() -> repository.list(PROJECT_ID,
                    new ReportListRequest(1, 20, null, null, null, null, null, "title", "asc")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported report sort field");
            database.verifyComplete();
        }
    }

    @Test
    void updatesLifecycleWithAndWithoutSummaryAndHandlesMissingRows() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            ReportRepository repository = repository(database);
            database.enqueueRecord(report(TaskStatus.RUNNING, null)).enqueueResult(database.result(REPORTS.fields()))
                    .enqueueRecord(report(TaskStatus.SUCCEEDED, JSONB.valueOf("{\"done\":true}")))
                    .enqueueResult(database.result(REPORTS.fields()));
            assertThat(repository.updateStatus(REPORT_ID, TaskStatus.RUNNING, null)).isNotNull();
            assertThat(repository.updateStatus(REPORT_ID, TaskStatus.FAILED, Map.of("error", true))).isNull();
            assertThat(repository.markSucceeded(REPORT_ID, Map.of("done", true), "LOW").summary()).containsEntry("done", true);
            assertThat(repository.markSucceeded(REPORT_ID, Map.of(), "INFO")).isNull();
            assertThat(database.executions().getFirst().normalizedSql()).doesNotContain("summary =");
            assertThat(database.executions().get(1).normalizedSql()).contains("summary =");
            database.verifyComplete();
        }
    }

    @Test
    void listsPublishedInputSnapshots() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            ReportRepository repository = repository(database);
            database.enqueueResult(database.result(new Field<?>[]{DSL.count()}, new Object[]{1L}))
                    .enqueueRecord(input(JSONB.valueOf("{\"title\":\"SIMD\"}")))
                    .enqueueRecord(input(JSONB.valueOf("bad")));
            ReportInputListRequest request = new ReportInputListRequest(2, 5, ReportInputType.SCAN_FINDING, " SIMD ", "inputType", "desc");
            assertThat(repository.countInputs(REPORT_ID, request)).isEqualTo(1);
            assertThat(repository.listInputs(REPORT_ID, request).getFirst().inputSnapshot()).containsEntry("title", "SIMD");
            assertThat(repository.listAllInputs(REPORT_ID).getFirst().inputSnapshot()).isEmpty();
            assertThat(database.executions().get(0).normalizedSql()).contains("input_type =", "cast", "lower");
            database.verifyComplete();
        }
    }

    @Test
    void blankFiltersAndBothSortDirectionsRetainTheWholeReport() throws Exception {
        try (var db = new JooqMockDatabase()) {
            var repository = repository(db);
            db.enqueueResult(db.result(REPORTS.fields())).enqueueResult(db.result(REPORT_INPUTS.fields()))
                    .enqueueResult(db.result(REPORT_INPUTS.fields()));
            repository.list(PROJECT_ID,new ReportListRequest(1,20,null,null," ",null,null,"riskLevel","asc"));
            repository.listInputs(REPORT_ID,new ReportInputListRequest(1,20,null," ","inputType","desc"));
            repository.listInputs(REPORT_ID,new ReportInputListRequest(1,20,null,null,"createdAt","asc"));
            assertThat(db.executions().get(0).normalizedSql()).contains("risk_level asc").doesNotContain("risk_level =");
            assertThat(db.executions().get(1).normalizedSql()).contains("input_type desc").doesNotContain("lower(");
            assertThat(db.executions().get(2).normalizedSql()).contains("created_at asc");
            db.verifyComplete();
        }
    }

    @Test
    void supportsInputSortsAndRejectsUnknown() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            ReportRepository repository = repository(database);
            for (int index = 0; index < 2; index++) database.enqueueResult(database.result(REPORT_INPUTS.fields()));
            repository.listInputs(REPORT_ID, new ReportInputListRequest(null, null, null, null, "createdAt", "desc"));
            repository.listInputs(REPORT_ID, new ReportInputListRequest(null, null, null, null, "", "asc"));
            assertThat(database.executions().get(0).normalizedSql()).contains("created_at desc nulls last");
            assertThat(database.executions().get(1).normalizedSql()).contains("created_at asc", "id asc");
            assertThatThrownBy(() -> repository.listInputs(REPORT_ID,
                    new ReportInputListRequest(1, 20, null, null, "snapshot", "asc")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported report input sort field");
            database.verifyComplete();
        }
    }

    @Test
    void rejectsNonSerializableJsonAndPropagatesDatabaseFailure() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            ObjectMapper mapper = Mockito.mock(ObjectMapper.class);
            Mockito.when(mapper.writeValueAsString(Mockito.any())).thenThrow(new JsonProcessingException("bad") { });
            ReportRepository repository = new ReportRepository(database.dsl(), mapper);
            assertThatThrownBy(() -> repository.markSucceeded(REPORT_ID, Map.of("bad", true), "HIGH"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not serializable");
            assertThat(database.executions()).isEmpty();
            database.enqueueFailure(new SQLException("database unavailable"));
            assertThatThrownBy(() -> repository.findById(REPORT_ID)).isInstanceOf(org.jooq.exception.DataAccessException.class)
                    .hasMessageContaining("database unavailable");
            database.verifyComplete();
        }
    }

    private static ReportRepository repository(JooqMockDatabase database) {
        return new ReportRepository(database.dsl(), new ObjectMapper());
    }

    private static ReportsRecord report(TaskStatus status, JSONB summary) {
        return new ReportsRecord(REPORT_ID, PROJECT_ID, TASK_ID, ReportType.EVALUATION.name(), status.name(), "Evaluation",
                summary, "LOW", null, null, USER_ID, NOW, NOW, REVISION_ID);
    }

    private static ReportInputsRecord input(JSONB snapshot) {
        ReportInputsRecord record = new ReportInputsRecord();
        record.setId(UUID.randomUUID());
        record.setReportId(REPORT_ID);
        record.setInputType(ReportInputType.SCAN_FINDING.name());
        record.setInputId(INPUT_ID);
        record.setInputSnapshot(snapshot);
        record.setCreatedAt(NOW);
        return record;
    }

}
