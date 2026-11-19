package cn.rvport.backend.source;

import static cn.rvport.backend.jooq.tables.DiffItems.DIFF_ITEMS;
import static cn.rvport.backend.jooq.tables.RecheckRuns.RECHECK_RUNS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.rvport.backend.jooq.tables.records.DiffItemsRecord;
import cn.rvport.backend.jooq.tables.records.RecheckRunsRecord;
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

class SourceCompareRepositoryTests {

    private static final UUID PROJECT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID BASE_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID TARGET_ID = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final UUID ITEM_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-11T10:00:00Z");

    @Test
    void findsLatestReusableAndSpecificRunsWhileToleratingBadSummaryJson() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            SourceCompareRepository repository = new SourceCompareRepository(database.dsl(), new ObjectMapper());
            database.enqueueRecord(run(JSONB.valueOf("{\"fixed\":2}"), TaskStatus.SUCCEEDED))
                    .enqueueRecord(run(JSONB.valueOf("bad-json"), TaskStatus.RUNNING))
                    .enqueueResult(database.result(RECHECK_RUNS.fields()));

            assertThat(repository.latestRecheckRun(PROJECT_ID, BASE_ID, TARGET_ID).orElseThrow().summary())
                    .containsEntry("fixed", 2);
            assertThat(repository.findReusableRecheckRun(PROJECT_ID, BASE_ID, TARGET_ID).orElseThrow().summary()).isEmpty();
            assertThat(repository.findRecheckRun(UUID.randomUUID())).isEmpty();
            assertThat(database.executions().get(1).normalizedSql()).contains("status in");
            database.verifyComplete();
        }
    }

    @Test
    void createsAndUpdatesRecheckRunIncludingNullSummary() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            SourceCompareRepository repository = new SourceCompareRepository(database.dsl(), new ObjectMapper());
            database.enqueueRecord(run(JSONB.valueOf("{}"), TaskStatus.PENDING)).enqueueRows(1).enqueueRows(1);

            assertThat(repository.createRecheckRun(RUN_ID, PROJECT_ID, UUID.randomUUID(), BASE_ID, TARGET_ID, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), TaskStatus.PENDING).summary())
                    .isEmpty();
            repository.updateRecheckStatus(RUN_ID, TaskStatus.SUCCEEDED, Map.of("resolved", 3));
            repository.updateRecheckStatus(RUN_ID, TaskStatus.FAILED, null);
            assertThat(database.executions().get(1).bindings()).anySatisfy(value -> {
                if (value instanceof JSONB jsonb) assertThat(jsonb.data()).contains("resolved");
            });
            assertThat(database.executions().get(2).bindings()).anySatisfy(value -> {
                if (value instanceof JSONB jsonb) assertThat(jsonb.data()).isEqualTo("{}");
            });
            database.verifyComplete();
        }
    }

    @Test
    void calculatesDiffSummaryIncludingUnknownGroupKeys() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            SourceCompareRepository repository = new SourceCompareRepository(database.dsl(), new ObjectMapper());
            database.enqueueResult(database.result(new Field<?>[]{DSL.count()}, new Object[]{4L}))
                    .enqueueResult(database.result(new Field<?>[]{DIFF_ITEMS.DIFF_STATUS, DSL.count()},
                            new Object[]{"FIXED", 2}, new Object[]{null, 1}))
                    .enqueueResult(database.result(new Field<?>[]{DIFF_ITEMS.SOURCE_TYPE, DSL.count()}, new Object[]{"KB", 3}))
                    .enqueueResult(database.result(new Field<?>[]{DIFF_ITEMS.SEVERITY, DSL.count()}, new Object[]{"HIGH", 1}));

            DiffSummaryResponse summary = repository.diffSummary(PROJECT_ID, RUN_ID);
            assertThat(summary.total()).isEqualTo(4);
            assertThat(summary.byStatus()).containsEntry("FIXED", 2L).containsEntry("UNKNOWN", 1L);
            assertThat(summary.bySourceType()).containsEntry("KB", 3L);
            assertThat(summary.bySeverity()).containsEntry("HIGH", 1L);
            database.verifyComplete();
        }
    }

    @Test
    void filtersCountsListsAndFindsDiffItems() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            SourceCompareRepository repository = new SourceCompareRepository(database.dsl(), new ObjectMapper());
            RecheckDiffItemListRequest request = new RecheckDiffItemListRequest(
                    " FIXED ", " KB ", " HIGH ", " EXACT ", "severity", "desc", 2, 10);
            database.enqueueResult(database.result(new Field<?>[]{DSL.count()}, new Object[]{1L}))
                    .enqueueRecord(item(JSONB.valueOf("{\"line\":1}"), JSONB.valueOf("{\"line\":2}")))
                    .enqueueRecord(item(null, JSONB.valueOf("bad-json")));

            assertThat(repository.countDiffItems(PROJECT_ID, RUN_ID, request)).isEqualTo(1L);
            assertThat(repository.listDiffItems(PROJECT_ID, RUN_ID, request)).singleElement()
                    .satisfies(item -> assertThat(item.oldSnapshot()).containsEntry("line", 1));
            assertThat(repository.findDiffItem(PROJECT_ID, RUN_ID, ITEM_ID).orElseThrow().newSnapshot()).isEmpty();
            assertThat(database.executions().getFirst().normalizedSql())
                    .contains("diff_status = ?", "source_type = ?", "severity = ?", "match_confidence = ?");
            assertThat(database.executions().get(1).normalizedSql()).contains("severity desc nulls last", "offset ? rows");
            database.verifyComplete();
        }
    }

    @Test
    void supportsEveryDiffSortAndRejectsUnknownSort() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            SourceCompareRepository repository = new SourceCompareRepository(database.dsl(), new ObjectMapper());
            String[] sorts = {"diffStatus", "sourceType", "title", "locationLabel", "matchConfidence", "", null};
            for (String ignored : sorts) database.enqueueResult(database.result(DIFF_ITEMS.fields()));
            for (String sort : sorts) {
                assertThat(repository.listDiffItems(PROJECT_ID, RUN_ID,
                        new RecheckDiffItemListRequest(null, null, null, null, sort, "asc", null, null))).isEmpty();
            }
            assertThat(database.executions().getFirst().normalizedSql()).contains("diff_status asc nulls last");
            assertThat(database.executions().get(5).normalizedSql()).contains("diff_status asc", "diff_items.id asc");
            assertThatThrownBy(() -> repository.listDiffItems(PROJECT_ID, RUN_ID,
                    new RecheckDiffItemListRequest(null, null, null, null, "createdAt", "asc", 1, 20)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported diff item sort field");
            database.verifyComplete();
        }
    }

    @Test
    void replacesDiffItemsForEmptyAndMultipleLists() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            SourceCompareRepository repository = new SourceCompareRepository(database.dsl(), new ObjectMapper());
            database.enqueueRows(2).enqueueRows(0).enqueueRows(0).enqueueRows(0);
            SourceCompareRepository.DiffItemCreateCommand command = new SourceCompareRepository.DiffItemCreateCommand(
                    "KB", UUID.randomUUID(), UUID.randomUUID(), "FIXED", Map.of("line", 1), null, "HIGH", "title",
                    "src/a.c:1", "recommend", "CONFIRMED", "fingerprint", "EXACT", "same", Map.of("a", 1), Map.of("a", 2));

            repository.replaceDiffItems(PROJECT_ID, RUN_ID, List.of());
            repository.replaceDiffItems(PROJECT_ID, RUN_ID, List.of(command, command));

            assertThat(database.executions()).hasSize(4);
            assertThat(database.executions().getFirst().normalizedSql()).startsWith("delete from diff_items");
            assertThat(database.executions().get(2).bindings()).containsNull();
            database.verifyComplete();
        }
    }

    @Test
    void rejectsUnserializableSnapshotsAndPropagatesDatabaseFailure() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            ObjectMapper mapper = Mockito.mock(ObjectMapper.class);
            Mockito.when(mapper.writeValueAsString(Mockito.any()))
                    .thenThrow(new JsonProcessingException("cannot serialize") { });
            SourceCompareRepository repository = new SourceCompareRepository(database.dsl(), mapper);
            database.enqueueRows(1);
            SourceCompareRepository.DiffItemCreateCommand command = new SourceCompareRepository.DiffItemCreateCommand(
                    "KB", null, null, "NEW", Map.of("x", 1), null, null, "title", null, null, null, null, null, null, null, null);

            assertThatThrownBy(() -> repository.replaceDiffItems(PROJECT_ID, RUN_ID, List.of(command)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unable to serialize diff item snapshot");
            database.verifyComplete();
        }

        try (JooqMockDatabase database = new JooqMockDatabase()) {
            SourceCompareRepository repository = new SourceCompareRepository(database.dsl(), new ObjectMapper());
            database.enqueueFailure(new SQLException("database unavailable"));
            assertThatThrownBy(() -> repository.findRecheckRun(RUN_ID))
                    .isInstanceOf(org.jooq.exception.DataAccessException.class)
                    .hasMessageContaining("database unavailable");
            database.verifyComplete();
        }
    }

    private static RecheckRunsRecord run(JSONB summary, TaskStatus status) {
        return new RecheckRunsRecord(RUN_ID, PROJECT_ID, UUID.randomUUID(), BASE_ID, TARGET_ID, null, null, summary, status.name(), NOW, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    private static DiffItemsRecord item(JSONB oldSnapshot, JSONB newSnapshot) {
        return new DiffItemsRecord(
                ITEM_ID, RUN_ID, PROJECT_ID, "KB", UUID.randomUUID(), UUID.randomUUID(), "FIXED", null, null,
                "HIGH", "fingerprint", "title", "src/a.c:1", "recommend", "CONFIRMED", "EXACT", "same",
                oldSnapshot, newSnapshot);
    }
}
