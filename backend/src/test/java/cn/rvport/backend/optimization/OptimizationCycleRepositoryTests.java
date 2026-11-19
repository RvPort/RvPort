package cn.rvport.backend.optimization;

import static cn.rvport.backend.jooq.tables.BuildRuns.BUILD_RUNS;
import static cn.rvport.backend.jooq.tables.DiffItems.DIFF_ITEMS;
import static cn.rvport.backend.jooq.tables.SourceDeployRuns.SOURCE_DEPLOY_RUNS;
import static cn.rvport.backend.jooq.tables.TestRuns.TEST_RUNS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.rvport.backend.jooq.tables.records.OptimizationCyclesRecord;
import cn.rvport.backend.jooq.tables.records.RecheckRunsRecord;
import cn.rvport.backend.jooq.tables.records.ReportsRecord;
import cn.rvport.backend.jooq.tables.records.TestComparisonRunsRecord;
import cn.rvport.backend.report.ReportType;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.testfixture.JooqMockDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

class OptimizationCycleRepositoryTests {

    private static final UUID CYCLE_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID REVISION_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID PREVIOUS_REVISION_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID TASK_ID = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-11T10:00:00Z");

    @Test
    void findsCyclesByAllSupportedSelectorsAndMapsBadJson() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            OptimizationCycleRepository repository = repository(database);
            database.enqueueRecord(cycle(JSONB.valueOf("{\"count\":1}")))
                    .enqueueRecord(cycle(JSONB.valueOf("bad")))
                    .enqueueRecord(cycle(null))
                    .enqueueRecord(cycle(null));
            assertThat(repository.findById(CYCLE_ID).orElseThrow().summary()).containsEntry("count", 1);
            assertThat(repository.findBySourceRevision(PROJECT_ID, REVISION_ID).orElseThrow().summary()).isEmpty();
            assertThat(repository.findActive(PROJECT_ID)).isPresent();
            assertThat(repository.findLatest(PROJECT_ID, null)).isPresent();
            assertThat(database.executions().get(2).normalizedSql()).contains("status =", "cycle_number desc");
            database.verifyComplete();
        }
    }

    @Test
    void findsLatestByRevisionAndMapsBlankOrNullJsonPayloads() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            OptimizationCycleRepository repository = repository(database);
            database.enqueueRecord(cycle(JSONB.valueOf(" ")))
                    .enqueueRecord(cycle(JSONB.valueOf(null)));

            assertThat(repository.findLatest(PROJECT_ID, REVISION_ID).orElseThrow().summary()).isEmpty();
            assertThat(repository.findById(CYCLE_ID).orElseThrow().summary()).isEmpty();
            assertThat(database.executions().getFirst().normalizedSql()).contains("source_revision_id =");
            database.verifyComplete();
        }
    }

    @Test
    void cycleViewUsesEmptySummaryWhenCallerHasNoParsedData() {
        OptimizationCycleView view = OptimizationCycleView.from(cycle(null), null);
        assertThat(view.summary()).isEmpty();
    }

    @Test
    void filtersCountsSortsAndPaginatesCycles() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            OptimizationCycleRepository repository = repository(database);
            OptimizationCycleListRequest request = new OptimizationCycleListRequest(2, 5, REVISION_ID,
                    OptimizationCycleStatus.ACTIVE, NOW.minusDays(1), NOW.plusDays(1), "startedAt", "asc");
            database.enqueueResult(database.result(new Field<?>[]{DSL.count()}, new Object[]{1L})).enqueueRecord(cycle(null));
            assertThat(repository.count(PROJECT_ID, request)).isEqualTo(1);
            assertThat(repository.list(PROJECT_ID, request)).hasSize(1);
            assertThat(database.executions().getFirst().normalizedSql()).contains("source_revision_id =", "status =", "created_at >=", "created_at <=");
            assertThat(database.executions().get(1).normalizedSql()).contains("started_at asc nulls last", "offset ? rows");
            database.verifyComplete();
        }
    }

    @Test
    void supportsEverySortAndRejectsUnknown() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            OptimizationCycleRepository repository = repository(database);
            for (int index = 0; index < 4; index++) database.enqueueResult(database.result(cycleFields()));
            for (String sort : new String[]{"cycleNumber", "createdAt", "updatedAt", ""}) repository.list(PROJECT_ID,
                    new OptimizationCycleListRequest(null, null, null, null, null, null, sort, "desc"));
            assertThat(database.executions().get(2).normalizedSql()).contains("updated_at desc nulls last");
            assertThat(database.executions().get(3).normalizedSql()).contains("cycle_number desc", "id desc");
            assertThatThrownBy(() -> repository.list(PROJECT_ID,
                    new OptimizationCycleListRequest(1, 20, null, null, null, null, "status", "asc")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported optimization cycle sort field");
            database.verifyComplete();
        }
    }

    @Test
    void reactivatesExistingCycleAndSupersedesOthers() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            OptimizationCycleRepository repository = repository(database);
            database.enqueueRecord(cycle(null)).enqueueRows(1).enqueueRecord(cycle(null));
            assertThat(repository.createOrReactivate(PROJECT_ID, REVISION_ID).id()).isEqualTo(CYCLE_ID);
            assertThat(database.executions().get(1).normalizedSql()).contains("status =", "id <>");
            assertThat(database.executions().get(2).normalizedSql()).contains("update optimization_cycles");
            database.verifyComplete();
        }
    }

    @Test
    void createsFirstAndSubsequentCycles() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            OptimizationCycleRepository repository = repository(database);
            database.enqueueResult(database.result(cycleFields()))
                    .enqueueResult(database.result(new Field<?>[]{DSL.max(cn.rvport.backend.jooq.tables.OptimizationCycles.OPTIMIZATION_CYCLES.CYCLE_NUMBER)}, new Object[]{null}))
                    .enqueueRecord(cycle(null)).enqueueRows(0);
            assertThat(repository.createOrReactivate(PROJECT_ID, REVISION_ID).cycleNumber()).isEqualTo(1);
            assertThat(database.executions().get(1).bindings()).isNotNull();
            assertThat(database.executions().get(2).normalizedSql()).contains("insert into optimization_cycles");
            database.verifyComplete();
        }
    }

    @Test
    void recoversConcurrentCreateByReadingWinningCycle() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            OptimizationCycleRepository repository = repository(database);
            database.enqueueResult(database.result(cycleFields()))
                    .enqueueResult(database.result(new Field<?>[]{DSL.max(cn.rvport.backend.jooq.tables.OptimizationCycles.OPTIMIZATION_CYCLES.CYCLE_NUMBER)}, new Object[]{2}))
                    .enqueueFailure(new SQLException("duplicate key"))
                    .enqueueRecord(cycle(null));
            assertThat(repository.createOrReactivate(PROJECT_ID, REVISION_ID)).isNotNull();
            database.verifyComplete();
        }
    }

    @Test
    void summarizesEmptyStagesWithoutFabricatingLatestRuns() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            OptimizationCycleRepository repository = repository(database);
            for (int index = 0; index < 9; index++) database.enqueueResult(database.result(new Field<?>[0]));
            OptimizationCycleSummaryResponse summary = repository.summary(PROJECT_ID, REVISION_ID, null);
            assertThat(summary.report()).isNull();
            assertThat(summary.recheck()).isNull();
            assertThat(summary.sourceDeploy()).isEqualTo(OptimizationCycleSummaryResponse.StageRunSummary.empty());
            assertThat(summary.build()).isEqualTo(OptimizationCycleSummaryResponse.StageRunSummary.empty());
            assertThat(summary.test()).isEqualTo(OptimizationCycleSummaryResponse.StageRunSummary.empty());
            assertThat(summary.testComparison()).isNull();
            database.verifyComplete();
        }
    }

    @Test
    void summarizesPopulatedReportRecheckStagesAndComparison() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            OptimizationCycleRepository repository = repository(database);
            database.enqueueRecord(report())
                    .enqueueRecord(recheck())
                    .enqueueResult(database.result(new Field<?>[]{DSL.count()}, new Object[]{3L}))
                    .enqueueResult(group(database, DIFF_ITEMS.DIFF_STATUS, "FIXED", 2L))
                    .enqueueResult(group(database, DIFF_ITEMS.SEVERITY, "HIGH", 1L))
                    .enqueueResult(group(database, SOURCE_DEPLOY_RUNS.STATUS, TaskStatus.SUCCEEDED.name(), 1L))
                    .enqueueResult(stage(database, SOURCE_DEPLOY_RUNS.ID, SOURCE_DEPLOY_RUNS.STATUS,
                            SOURCE_DEPLOY_RUNS.STARTED_AT, SOURCE_DEPLOY_RUNS.FINISHED_AT))
                    .enqueueResult(group(database, BUILD_RUNS.STATUS, TaskStatus.SUCCEEDED.name(), 1L))
                    .enqueueResult(stage(database, BUILD_RUNS.ID, BUILD_RUNS.STATUS, BUILD_RUNS.STARTED_AT, BUILD_RUNS.FINISHED_AT))
                    .enqueueResult(group(database, TEST_RUNS.STATUS, TaskStatus.FAILED.name(), 1L))
                    .enqueueResult(stage(database, TEST_RUNS.ID, TEST_RUNS.STATUS, TEST_RUNS.STARTED_AT, TEST_RUNS.FINISHED_AT))
                    .enqueueRecord(comparison());
            OptimizationCycleSummaryResponse summary = repository.summary(PROJECT_ID, REVISION_ID, PREVIOUS_REVISION_ID);
            assertThat(summary.report().highRiskCount()).isEqualTo(3);
            assertThat(summary.report().workloadSummary()).isEqualTo("large");
            assertThat(summary.recheck().diffTotal()).isEqualTo(3);
            assertThat(summary.recheck().byDiffStatus()).containsEntry("FIXED", 2L);
            assertThat(summary.sourceDeploy().latestStatus()).isEqualTo(TaskStatus.SUCCEEDED.name());
            assertThat(summary.test().latestStatus()).isEqualTo(TaskStatus.FAILED.name());
            assertThat(summary.testComparison().summary()).isEqualTo("compatible");
            database.verifyComplete();
        }
    }

    private static OptimizationCycleRepository repository(JooqMockDatabase database) {
        return new OptimizationCycleRepository(database.dsl(), new ObjectMapper());
    }

    private static OptimizationCyclesRecord cycle(JSONB summary) {
        return new OptimizationCyclesRecord(CYCLE_ID, PROJECT_ID, REVISION_ID, 1, OptimizationCycleStatus.ACTIVE.name(),
                summary, NOW, null, NOW, NOW);
    }

    private static Field<?>[] cycleFields() {
        return cn.rvport.backend.jooq.tables.OptimizationCycles.OPTIMIZATION_CYCLES.fields();
    }

    private static ReportsRecord report() {
        return new ReportsRecord(RUN_ID, PROJECT_ID, TASK_ID, ReportType.EVALUATION.name(), TaskStatus.SUCCEEDED.name(),
                "Evaluation", JSONB.valueOf("{\"highRiskCount\":\"3\",\"workloadSummary\":\"large\"}"), "HIGH",
                null, null, null, NOW, NOW, REVISION_ID);
    }

    private static RecheckRunsRecord recheck() {
        return new RecheckRunsRecord(RUN_ID, PROJECT_ID, TASK_ID, PREVIOUS_REVISION_ID, REVISION_ID, null, null,
                JSONB.valueOf("{}"), TaskStatus.SUCCEEDED.name(), NOW, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    private static TestComparisonRunsRecord comparison() {
        return new TestComparisonRunsRecord(RUN_ID, PROJECT_ID, TASK_ID, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                TaskStatus.SUCCEEDED.name(), "COMPATIBLE", "LOW", BigDecimal.ONE, "compatible", "ship", null, null,
                null, null, false, JSONB.valueOf("{}"), null, NOW, NOW, NOW, NOW, null, null);
    }

    private static org.jooq.Result<org.jooq.Record> group(JooqMockDatabase database, Field<String> field, String key, long count) {
        return database.result(new Field<?>[]{field, DSL.count()}, new Object[]{key, count});
    }

    private static org.jooq.Result<org.jooq.Record> stage(JooqMockDatabase database, Field<UUID> id, Field<String> status,
                                                           Field<OffsetDateTime> started, Field<OffsetDateTime> finished) {
        return database.result(new Field<?>[]{id, status, started, finished},
                new Object[]{RUN_ID, status == TEST_RUNS.STATUS ? TaskStatus.FAILED.name() : TaskStatus.SUCCEEDED.name(), NOW, NOW});
    }
}
