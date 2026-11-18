package cn.rvport.backend.reportcomparison;

import static cn.rvport.backend.jooq.tables.ReportComparisonDiffItems.REPORT_COMPARISON_DIFF_ITEMS;
import static cn.rvport.backend.jooq.tables.ReportComparisonRuns.REPORT_COMPARISON_RUNS;
import static cn.rvport.backend.jooq.tables.ReportInputs.REPORT_INPUTS;
import static cn.rvport.backend.jooq.tables.Reports.REPORTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.rvport.backend.jooq.tables.records.ReportComparisonDiffItemsRecord;
import cn.rvport.backend.jooq.tables.records.ReportComparisonRunsRecord;
import cn.rvport.backend.jooq.tables.records.ReportInputsRecord;
import cn.rvport.backend.jooq.tables.records.ReportsRecord;
import cn.rvport.backend.report.ReportType;
import cn.rvport.backend.report.ReportView;
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

class ReportComparisonRepositoryTests {

    private static final UUID PROJECT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID BASE_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID TARGET_ID = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final UUID TASK_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID ITEM_ID = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-11T10:00:00Z");

    @Test
    void findsComparableBaseForGeneratedAndPendingTargetReports() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            ReportComparisonRepository repository = new ReportComparisonRepository(database.dsl(), new ObjectMapper());
            database.enqueueRecord(reportRecord(BASE_ID, JSONB.valueOf("{\"high\":2}")))
                    .enqueueRecord(reportRecord(BASE_ID, null));
            ReportView generated = reportView(TARGET_ID, NOW);
            ReportView pending = reportView(TARGET_ID, null);
            assertThat(repository.latestComparableBase(generated).orElseThrow().summary()).containsEntry("high", 2);
            assertThat(repository.latestComparableBase(pending)).isPresent();
            assertThat(database.executions().get(0).normalizedSql()).contains("generated_at <=");
            assertThat(database.executions().get(1).normalizedSql()).contains("created_at <");
            database.verifyComplete();
        }
    }

    @Test
    void createsFindsAndAdvancesComparisonRunLifecycle() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            ReportComparisonRepository repository = new ReportComparisonRepository(database.dsl(), new ObjectMapper());
            ReportComparisonRunsRecord run = run(JSONB.valueOf("bad"), TaskStatus.PENDING);
            database.enqueueRecord(run).enqueueRecord(run).enqueueRecord(run).enqueueRecord(run).enqueueRecord(run)
                    .enqueueRows(1).enqueueRows(1).enqueueRows(1);
            assertThat(repository.createRun(RUN_ID, PROJECT_ID, TASK_ID, BASE_ID, TARGET_ID, TaskStatus.PENDING).summary()).isEmpty();
            assertThat(repository.latestForTargetReport(TARGET_ID).orElseThrow().summary()).isEmpty();
            assertThat(repository.findReusable(PROJECT_ID, BASE_ID, TARGET_ID)).isPresent();
            assertThat(repository.findRun(RUN_ID)).isPresent();
            assertThat(repository.findByTaskId(TASK_ID)).isPresent();
            repository.markRunning(RUN_ID);
            repository.markSucceeded(RUN_ID, Map.of("fixed", 2));
            repository.markFailed(RUN_ID, "FAILED", "reason", null);
            assertThat(database.executions().get(2).normalizedSql()).contains("status in");
            assertThat(database.executions().get(5).normalizedSql()).contains("started_at");
            assertThat(database.executions().get(6).normalizedSql()).contains("error_code", "error_message");
            database.verifyComplete();
        }
    }

    @Test
    void listsInputsChecksExistenceAndSummarizesDiffGroups() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            ReportComparisonRepository repository = new ReportComparisonRepository(database.dsl(), new ObjectMapper());
            database.enqueueRecord(reportInput())
                    .enqueueRows(1)
                    .enqueueResult(database.result(new Field<?>[]{DSL.count()}, new Object[]{2L}))
                    .enqueueResult(database.result(new Field<?>[]{REPORT_COMPARISON_DIFF_ITEMS.DIFF_STATUS, DSL.count()}, new Object[]{"FIXED", 1}))
                    .enqueueResult(database.result(new Field<?>[]{REPORT_COMPARISON_DIFF_ITEMS.SOURCE_TYPE, DSL.count()}, new Object[]{"KB", 2}))
                    .enqueueResult(database.result(new Field<?>[]{REPORT_COMPARISON_DIFF_ITEMS.SEVERITY, DSL.count()}, new Object[]{null, 1}))
                    .enqueueResult(database.result(new Field<?>[]{REPORT_COMPARISON_DIFF_ITEMS.MATCH_CONFIDENCE, DSL.count()}, new Object[]{"EXACT", 1}));
            assertThat(repository.listAllInputs(TARGET_ID)).singleElement()
                    .satisfies(value -> assertThat(value.inputSnapshot()).containsEntry("risk", "HIGH"));
            assertThat(repository.hasInputs(TARGET_ID)).isTrue();
            ReportComparisonDiffSummaryResponse summary = repository.diffSummary(PROJECT_ID, RUN_ID);
            assertThat(summary.total()).isEqualTo(2);
            assertThat(summary.bySeverity()).containsEntry("UNKNOWN", 1L);
            assertThat(summary.byMatchConfidence()).containsEntry("EXACT", 1L);
            database.verifyComplete();
        }
    }

    private static ReportInputsRecord reportInput() {
        ReportInputsRecord record = new ReportInputsRecord();
        record.setId(UUID.randomUUID());
        record.setReportId(TARGET_ID);
        record.setInputType("SCAN_FINDING");
        record.setInputId(ITEM_ID);
        record.setInputSnapshot(JSONB.valueOf("{\"risk\":\"HIGH\"}"));
        record.setCreatedAt(NOW);
        return record;
    }

    @Test
    void filtersListsFindsAndSortsDiffItems() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            ReportComparisonRepository repository = new ReportComparisonRepository(database.dsl(), new ObjectMapper());
            ReportComparisonDiffItemListRequest request = new ReportComparisonDiffItemListRequest(
                    " FIXED ", " KB ", " HIGH ", " EXACT ", " mutex ", "createdAt", "desc", 2, 10);
            database.enqueueResult(database.result(new Field<?>[]{DSL.count()}, new Object[]{1L}))
                    .enqueueRecord(item(JSONB.valueOf("{\"line\":1}"), JSONB.valueOf("{\"line\":2}")))
                    .enqueueRecord(item(null, JSONB.valueOf("bad")));
            assertThat(repository.countDiffItems(PROJECT_ID, RUN_ID, request)).isEqualTo(1L);
            assertThat(repository.listDiffItems(PROJECT_ID, RUN_ID, request)).singleElement()
                    .satisfies(value -> assertThat(value.oldSnapshot()).containsEntry("line", 1));
            assertThat(repository.findDiffItem(PROJECT_ID, RUN_ID, ITEM_ID).orElseThrow().newSnapshot()).isEmpty();
            assertThat(database.executions().getFirst().normalizedSql())
                    .contains("diff_status =", "source_type =", "severity =", "match_confidence =", "lower(report_comparison_diff_items.title) like");
            assertThat(database.executions().get(1).normalizedSql()).contains("created_at desc nulls last", "offset ? rows");
            database.verifyComplete();
        }
    }

    @Test
    void blankDiffFiltersAreIgnoredAndBlankGroupsBecomeUnknown() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            ReportComparisonRepository repository = new ReportComparisonRepository(database.dsl(), new ObjectMapper());
            ReportComparisonDiffItemListRequest blank = new ReportComparisonDiffItemListRequest(
                    " ", " ", " ", " ", " ", null, null, null, null);
            database.enqueueResult(database.result(new Field<?>[]{DSL.count()}, new Object[]{0L}))
                    .enqueueResult(database.result(new Field<?>[]{DSL.count()}, new Object[]{1L}))
                    .enqueueResult(database.result(
                            new Field<?>[]{REPORT_COMPARISON_DIFF_ITEMS.DIFF_STATUS, DSL.count()},
                            new Object[]{" ", 1}))
                    .enqueueResult(database.result(
                            new Field<?>[]{REPORT_COMPARISON_DIFF_ITEMS.SOURCE_TYPE, DSL.count()},
                            new Object[]{"KB", 1}))
                    .enqueueResult(database.result(
                            new Field<?>[]{REPORT_COMPARISON_DIFF_ITEMS.SEVERITY, DSL.count()},
                            new Object[]{"HIGH", 1}))
                    .enqueueResult(database.result(
                            new Field<?>[]{REPORT_COMPARISON_DIFF_ITEMS.MATCH_CONFIDENCE, DSL.count()},
                            new Object[]{"HIGH", 1}));

            assertThat(repository.countDiffItems(PROJECT_ID, RUN_ID, blank)).isZero();
            assertThat(repository.diffSummary(PROJECT_ID, RUN_ID).byStatus()).containsEntry("UNKNOWN", 1L);
            assertThat(database.executions().getFirst().normalizedSql())
                    .doesNotContain("diff_status =", "source_type =", "severity =", "match_confidence =", " like ");
            database.verifyComplete();
        }
    }

    @Test
    void supportsEveryDiffSortAndRejectsUnknownSort() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            ReportComparisonRepository repository = new ReportComparisonRepository(database.dsl(), new ObjectMapper());
            String[] sorts = {"diffStatus", "sourceType", "severity", "title", "locationLabel", "matchConfidence", "", null};
            for (String ignored : sorts) database.enqueueResult(database.result(REPORT_COMPARISON_DIFF_ITEMS.fields()));
            for (String sort : sorts) repository.listDiffItems(PROJECT_ID, RUN_ID,
                    new ReportComparisonDiffItemListRequest(null, null, null, null, null, sort, "asc", null, null));
            assertThat(database.executions().getFirst().normalizedSql()).contains("diff_status asc nulls last");
            assertThat(database.executions().get(6).normalizedSql()).contains("diff_status asc", "id asc");
            assertThatThrownBy(() -> repository.listDiffItems(PROJECT_ID, RUN_ID,
                    new ReportComparisonDiffItemListRequest(null, null, null, null, null, "updatedAt", "asc", 1, 20)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported report comparison");
            database.verifyComplete();
        }
    }

    @Test
    void replacesDiffItemsAndRejectsUnserializableSnapshots() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            ReportComparisonRepository repository = new ReportComparisonRepository(database.dsl(), new ObjectMapper());
            database.enqueueRows(1).enqueueRows(1).enqueueRows(1);
            ReportComparisonRepository.DiffItemCreateCommand command = command(Map.of("line", 1));
            repository.replaceDiffItems(PROJECT_ID, RUN_ID, List.of(command, command));
            assertThat(database.executions()).hasSize(3);
            assertThat(database.executions().get(1).bindings()).containsNull();
            database.verifyComplete();
        }
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            ObjectMapper mapper = Mockito.mock(ObjectMapper.class);
            Mockito.when(mapper.writeValueAsString(Mockito.any()))
                    .thenThrow(new JsonProcessingException("cannot serialize") { });
            ReportComparisonRepository repository = new ReportComparisonRepository(database.dsl(), mapper);
            database.enqueueRows(1);
            assertThatThrownBy(() -> repository.replaceDiffItems(PROJECT_ID, RUN_ID, List.of(command(Map.of("x", 1)))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unable to serialize report comparison");
            database.verifyComplete();
        }
    }

    @Test
    void propagatesDatabaseFailures() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            ReportComparisonRepository repository = new ReportComparisonRepository(database.dsl(), new ObjectMapper());
            database.enqueueFailure(new SQLException("database unavailable"));
            assertThatThrownBy(() -> repository.findRun(RUN_ID))
                    .isInstanceOf(org.jooq.exception.DataAccessException.class).hasMessageContaining("database unavailable");
            database.verifyComplete();
        }
    }

    private static ReportComparisonRunsRecord run(JSONB summary, TaskStatus status) {
        return new ReportComparisonRunsRecord(RUN_ID, PROJECT_ID, TASK_ID, BASE_ID, TARGET_ID, status.name(), summary,
                null, null, NOW, null, NOW, NOW, "REPORT_FACTS", null, null);
    }

    private static ReportsRecord reportRecord(UUID id, JSONB summary) {
        return new ReportsRecord(id, PROJECT_ID, TASK_ID, ReportType.EVALUATION.name(), TaskStatus.SUCCEEDED.name(),
                "report", summary, "HIGH", null, null, UUID.randomUUID(), NOW, NOW, UUID.randomUUID());
    }

    private static ReportView reportView(UUID id, OffsetDateTime generatedAt) {
        return new ReportView(id, PROJECT_ID, TASK_ID, UUID.randomUUID(), ReportType.EVALUATION,
                TaskStatus.SUCCEEDED, "report", Map.of(), "HIGH", UUID.randomUUID(), generatedAt, NOW);
    }

    private static ReportComparisonDiffItemsRecord item(JSONB oldSnapshot, JSONB newSnapshot) {
        return new ReportComparisonDiffItemsRecord(ITEM_ID, RUN_ID, PROJECT_ID, "KB", UUID.randomUUID(), UUID.randomUUID(),
                "FIXED", "HIGH", "mutex", "src/a.c:1", "fix", "CONFIRMED", "fp", "EXACT", "same",
                oldSnapshot, newSnapshot, NOW);
    }

    private static ReportComparisonRepository.DiffItemCreateCommand command(Map<String, Object> oldSnapshot) {
        return new ReportComparisonRepository.DiffItemCreateCommand(
                "KB", null, null, "FIXED", "HIGH", "title", null, null, null, "fp", "EXACT", "same",
                oldSnapshot, Map.of("line", 2));
    }
}
