package cn.rvport.backend.optimization;

import static cn.rvport.backend.jooq.tables.BuildRuns.BUILD_RUNS;
import static cn.rvport.backend.jooq.tables.DiffItems.DIFF_ITEMS;
import static cn.rvport.backend.jooq.tables.OptimizationCycles.OPTIMIZATION_CYCLES;
import static cn.rvport.backend.jooq.tables.RecheckRuns.RECHECK_RUNS;
import static cn.rvport.backend.jooq.tables.Reports.REPORTS;
import static cn.rvport.backend.jooq.tables.SourceDeployRuns.SOURCE_DEPLOY_RUNS;
import static cn.rvport.backend.jooq.tables.TestComparisonRuns.TEST_COMPARISON_RUNS;
import static cn.rvport.backend.jooq.tables.TestRuns.TEST_RUNS;

import cn.rvport.backend.jooq.tables.records.OptimizationCyclesRecord;
import cn.rvport.backend.jooq.tables.records.ReportsRecord;
import cn.rvport.backend.jooq.tables.records.TestComparisonRunsRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.OrderField;
import org.jooq.Record;
import org.jooq.SortField;
import org.jooq.TableLike;
import org.jooq.impl.DSL;
import org.jooq.exception.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
public class OptimizationCycleRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public OptimizationCycleRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    public Optional<OptimizationCycleView> findById(UUID cycleId) {
        return dsl.selectFrom(OPTIMIZATION_CYCLES)
                .where(OPTIMIZATION_CYCLES.ID.eq(cycleId))
                .fetchOptional(this::view);
    }

    public Optional<OptimizationCycleView> findBySourceRevision(UUID projectId, UUID sourceRevisionId) {
        return dsl.selectFrom(OPTIMIZATION_CYCLES)
                .where(OPTIMIZATION_CYCLES.PROJECT_ID.eq(projectId)
                        .and(OPTIMIZATION_CYCLES.SOURCE_REVISION_ID.eq(sourceRevisionId)))
                .fetchOptional(this::view);
    }

    public Optional<OptimizationCycleView> findActive(UUID projectId) {
        return dsl.selectFrom(OPTIMIZATION_CYCLES)
                .where(OPTIMIZATION_CYCLES.PROJECT_ID.eq(projectId)
                        .and(OPTIMIZATION_CYCLES.STATUS.eq(OptimizationCycleStatus.ACTIVE.name())))
                .orderBy(OPTIMIZATION_CYCLES.CYCLE_NUMBER.desc(), OPTIMIZATION_CYCLES.ID.desc())
                .limit(1)
                .fetchOptional(this::view);
    }

    public Optional<OptimizationCycleView> findLatest(UUID projectId, UUID sourceRevisionId) {
        Condition condition = OPTIMIZATION_CYCLES.PROJECT_ID.eq(projectId);
        if (sourceRevisionId != null) {
            condition = condition.and(OPTIMIZATION_CYCLES.SOURCE_REVISION_ID.eq(sourceRevisionId));
        }
        return dsl.selectFrom(OPTIMIZATION_CYCLES)
                .where(condition)
                .orderBy(OPTIMIZATION_CYCLES.CYCLE_NUMBER.desc(), OPTIMIZATION_CYCLES.ID.desc())
                .limit(1)
                .fetchOptional(this::view);
    }

    public long count(UUID projectId, OptimizationCycleListRequest request) {
        return dsl.selectCount()
                .from(OPTIMIZATION_CYCLES)
                .where(filter(projectId, request))
                .fetchOne(0, long.class);
    }

    public List<OptimizationCycleView> list(UUID projectId, OptimizationCycleListRequest request) {
        int page = request.normalizedPage();
        int pageSize = request.normalizedPageSize();
        return dsl.selectFrom(OPTIMIZATION_CYCLES)
                .where(filter(projectId, request))
                .orderBy(sort(request))
                .limit(pageSize)
                .offset((page - 1) * pageSize)
                .fetch(this::view);
    }

    public OptimizationCycleView createOrReactivate(UUID projectId, UUID sourceRevisionId) {
        Optional<OptimizationCycleView> existing = findBySourceRevision(projectId, sourceRevisionId);
        if (existing.isPresent()) {
            supersedeOtherCycles(projectId, existing.get().id());
            OptimizationCyclesRecord record = dsl.update(OPTIMIZATION_CYCLES)
                    .set(OPTIMIZATION_CYCLES.STATUS, OptimizationCycleStatus.ACTIVE.name())
                    .set(OPTIMIZATION_CYCLES.UPDATED_AT, OffsetDateTime.now())
                    .where(OPTIMIZATION_CYCLES.ID.eq(existing.get().id()))
                    .returning()
                    .fetchOne();
            return view(record);
        }

        int cycleNumber = nextCycleNumber(projectId);
        try {
            OptimizationCyclesRecord record = dsl.insertInto(OPTIMIZATION_CYCLES)
                    .set(OPTIMIZATION_CYCLES.PROJECT_ID, projectId)
                    .set(OPTIMIZATION_CYCLES.SOURCE_REVISION_ID, sourceRevisionId)
                    .set(OPTIMIZATION_CYCLES.CYCLE_NUMBER, cycleNumber)
                    .set(OPTIMIZATION_CYCLES.STATUS, OptimizationCycleStatus.ACTIVE.name())
                    .set(OPTIMIZATION_CYCLES.SUMMARY, JSONB.valueOf("{}"))
                    .set(OPTIMIZATION_CYCLES.STARTED_AT, OffsetDateTime.now())
                    .returning()
                    .fetchOne();
            supersedeOtherCycles(projectId, record.getId());
            return view(record);
        } catch (DataAccessException | DataIntegrityViolationException ex) {
            return findBySourceRevision(projectId, sourceRevisionId)
                    .orElseThrow(() -> ex);
        }
    }

    public OptimizationCycleSummaryResponse summary(UUID projectId, UUID sourceRevisionId, UUID previousSourceRevisionId) {
        return new OptimizationCycleSummaryResponse(
                latestReport(projectId, sourceRevisionId).orElse(null),
                latestRecheck(projectId, previousSourceRevisionId, sourceRevisionId).orElse(null),
                stageSummary(SOURCE_DEPLOY_RUNS.ID, SOURCE_DEPLOY_RUNS.STATUS, SOURCE_DEPLOY_RUNS.STARTED_AT,
                        SOURCE_DEPLOY_RUNS.FINISHED_AT, SOURCE_DEPLOY_RUNS.PROJECT_ID, SOURCE_DEPLOY_RUNS.SOURCE_REVISION_ID,
                        SOURCE_DEPLOY_RUNS.CREATED_AT, SOURCE_DEPLOY_RUNS, projectId, sourceRevisionId),
                stageSummary(BUILD_RUNS.ID, BUILD_RUNS.STATUS, BUILD_RUNS.STARTED_AT,
                        BUILD_RUNS.FINISHED_AT, BUILD_RUNS.PROJECT_ID, BUILD_RUNS.SOURCE_REVISION_ID,
                        BUILD_RUNS.CREATED_AT, BUILD_RUNS, projectId, sourceRevisionId),
                stageSummary(TEST_RUNS.ID, TEST_RUNS.STATUS, TEST_RUNS.STARTED_AT,
                        TEST_RUNS.FINISHED_AT, TEST_RUNS.PROJECT_ID, TEST_RUNS.SOURCE_REVISION_ID,
                        TEST_RUNS.STARTED_AT, TEST_RUNS, projectId, sourceRevisionId),
                latestTestComparison(projectId, sourceRevisionId).orElse(null)
        );
    }


    private Optional<OptimizationCycleSummaryResponse.LatestReportSummary> latestReport(UUID projectId, UUID sourceRevisionId) {
        return dsl.selectFrom(REPORTS)
                .where(REPORTS.PROJECT_ID.eq(projectId)
                        .and(REPORTS.SOURCE_REVISION_ID.eq(sourceRevisionId)))
                .orderBy(REPORTS.GENERATED_AT.desc().nullsLast(), REPORTS.CREATED_AT.desc(), REPORTS.ID.desc())
                .limit(1)
                .fetchOptional(this::reportSummary);
    }

    private Optional<OptimizationCycleSummaryResponse.RecheckSummary> latestRecheck(
            UUID projectId,
            UUID previousSourceRevisionId,
            UUID sourceRevisionId
    ) {
        Condition condition = RECHECK_RUNS.PROJECT_ID.eq(projectId)
                .and(RECHECK_RUNS.TARGET_SOURCE_REVISION_ID.eq(sourceRevisionId));
        if (previousSourceRevisionId != null) {
            condition = condition.and(RECHECK_RUNS.BASE_SOURCE_REVISION_ID.eq(previousSourceRevisionId));
        }
        return dsl.selectFrom(RECHECK_RUNS)
                .where(condition)
                .orderBy(RECHECK_RUNS.CREATED_AT.desc(), RECHECK_RUNS.ID.desc())
                .limit(1)
                .fetchOptional(record -> new OptimizationCycleSummaryResponse.RecheckSummary(
                        record.getId(),
                        record.getTaskId(),
                        record.getBaseSourceRevisionId(),
                        record.getTargetSourceRevisionId(),
                        record.getStatus(),
                        countDiffItems(projectId, record.getId()),
                        grouped(DIFF_ITEMS, DIFF_ITEMS.DIFF_STATUS, DIFF_ITEMS.PROJECT_ID.eq(projectId).and(DIFF_ITEMS.RECHECK_RUN_ID.eq(record.getId()))),
                        grouped(DIFF_ITEMS, DIFF_ITEMS.SEVERITY, DIFF_ITEMS.PROJECT_ID.eq(projectId).and(DIFF_ITEMS.RECHECK_RUN_ID.eq(record.getId()))),
                        record.getCreatedAt()
                ));
    }

    private Optional<OptimizationCycleSummaryResponse.TestComparisonSummary> latestTestComparison(UUID projectId, UUID sourceRevisionId) {
        var sourceTest = TEST_RUNS.as("source_test");
        var targetTest = TEST_RUNS.as("target_test");
        return dsl.select(TEST_COMPARISON_RUNS.fields())
                .from(TEST_COMPARISON_RUNS)
                .leftJoin(sourceTest).on(sourceTest.ID.eq(TEST_COMPARISON_RUNS.SOURCE_TEST_RUN_ID))
                .leftJoin(targetTest).on(targetTest.ID.eq(TEST_COMPARISON_RUNS.TARGET_TEST_RUN_ID))
                .where(TEST_COMPARISON_RUNS.PROJECT_ID.eq(projectId)
                        .and(sourceTest.SOURCE_REVISION_ID.eq(sourceRevisionId)
                                .or(targetTest.SOURCE_REVISION_ID.eq(sourceRevisionId))))
                .orderBy(TEST_COMPARISON_RUNS.CREATED_AT.desc(), TEST_COMPARISON_RUNS.ID.desc())
                .limit(1)
                .fetchOptional(record -> comparisonSummary(record.into(TEST_COMPARISON_RUNS)));
    }

    private OptimizationCycleSummaryResponse.StageRunSummary stageSummary(
            Field<UUID> idField,
            Field<String> statusField,
            Field<OffsetDateTime> startedAtField,
            Field<OffsetDateTime> finishedAtField,
            Field<UUID> projectIdField,
            Field<UUID> sourceRevisionIdField,
            Field<OffsetDateTime> orderField,
            TableLike<?> table,
            UUID projectId,
            UUID sourceRevisionId
    ) {
        Condition condition = projectIdField.eq(projectId).and(sourceRevisionIdField.eq(sourceRevisionId));
        Map<String, Long> byStatus = grouped(table, statusField, condition);
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        Record latest = dsl.select(idField, statusField, startedAtField, finishedAtField)
                .from(table)
                .where(condition)
                .orderBy(orderField.desc().nullsLast(), idField.desc())
                .limit(1)
                .fetchOne();
        if (latest == null) {
            return OptimizationCycleSummaryResponse.StageRunSummary.empty();
        }
        return new OptimizationCycleSummaryResponse.StageRunSummary(
                total,
                latest.get(statusField),
                latest.get(idField),
                latest.get(startedAtField),
                latest.get(finishedAtField),
                byStatus
        );
    }

    private OptimizationCycleSummaryResponse.LatestReportSummary reportSummary(ReportsRecord record) {
        Map<String, Object> summary = jsonbToMap(record.getSummary());
        return new OptimizationCycleSummaryResponse.LatestReportSummary(
                record.getId(),
                record.getTaskId(),
                record.getStatus(),
                record.getRiskLevel(),
                integerValue(summary.get("highRiskCount")),
                stringValue(summary.get("workloadSummary")),
                record.getGeneratedAt(),
                record.getCreatedAt()
        );
    }

    private OptimizationCycleSummaryResponse.TestComparisonSummary comparisonSummary(TestComparisonRunsRecord record) {
        return new OptimizationCycleSummaryResponse.TestComparisonSummary(
                record.getId(),
                record.getTaskId(),
                record.getTestBatchId(),
                record.getStatus(),
                record.getOverallStatus(),
                record.getRiskLevel(),
                record.getSummary(),
                record.getRecommendation(),
                record.getCreatedAt(),
                record.getFinishedAt()
        );
    }

    private long countDiffItems(UUID projectId, UUID recheckRunId) {
        return dsl.selectCount()
                .from(DIFF_ITEMS)
                .where(DIFF_ITEMS.PROJECT_ID.eq(projectId).and(DIFF_ITEMS.RECHECK_RUN_ID.eq(recheckRunId)))
                .fetchOne(0, long.class);
    }

    private Map<String, Long> grouped(TableLike<?> table, Field<String> field, Condition condition) {
        Map<String, Long> values = new LinkedHashMap<>();
        dsl.select(field, DSL.count())
                .from(table)
                .where(condition)
                .groupBy(field)
                .fetch()
                .forEach(record -> values.put(defaultKey(record.value1()), record.value2().longValue()));
        return values;
    }

    private void supersedeOtherCycles(UUID projectId, UUID activeCycleId) {
        dsl.update(OPTIMIZATION_CYCLES)
                .set(OPTIMIZATION_CYCLES.STATUS, OptimizationCycleStatus.SUPERSEDED.name())
                .set(OPTIMIZATION_CYCLES.UPDATED_AT, OffsetDateTime.now())
                .where(OPTIMIZATION_CYCLES.PROJECT_ID.eq(projectId)
                        .and(OPTIMIZATION_CYCLES.ID.ne(activeCycleId))
                        .and(OPTIMIZATION_CYCLES.STATUS.eq(OptimizationCycleStatus.ACTIVE.name())))
                .execute();
    }

    private int nextCycleNumber(UUID projectId) {
        Integer max = dsl.select(DSL.max(OPTIMIZATION_CYCLES.CYCLE_NUMBER))
                .from(OPTIMIZATION_CYCLES)
                .where(OPTIMIZATION_CYCLES.PROJECT_ID.eq(projectId))
                .fetchOne(0, Integer.class);
        return max == null ? 1 : max + 1;
    }

    private Condition filter(UUID projectId, OptimizationCycleListRequest request) {
        Condition condition = OPTIMIZATION_CYCLES.PROJECT_ID.eq(projectId);
        if (request.sourceRevisionId() != null) {
            condition = condition.and(OPTIMIZATION_CYCLES.SOURCE_REVISION_ID.eq(request.sourceRevisionId()));
        }
        if (request.status() != null) {
            condition = condition.and(OPTIMIZATION_CYCLES.STATUS.eq(request.status().name()));
        }
        if (request.createdFrom() != null) {
            condition = condition.and(OPTIMIZATION_CYCLES.CREATED_AT.ge(request.createdFrom()));
        }
        if (request.createdTo() != null) {
            condition = condition.and(OPTIMIZATION_CYCLES.CREATED_AT.le(request.createdTo()));
        }
        return condition;
    }

    private List<SortField<?>> sort(OptimizationCycleListRequest request) {
        Field<?> field = switch (blankToDefault(request.sortBy(), "")) {
            case "cycleNumber" -> OPTIMIZATION_CYCLES.CYCLE_NUMBER;
            case "createdAt" -> OPTIMIZATION_CYCLES.CREATED_AT;
            case "updatedAt" -> OPTIMIZATION_CYCLES.UPDATED_AT;
            case "startedAt" -> OPTIMIZATION_CYCLES.STARTED_AT;
            case "" -> null;
            default -> throw new IllegalArgumentException("Unsupported optimization cycle sort field: " + request.sortBy());
        };
        if (field == null) {
            return List.of(OPTIMIZATION_CYCLES.CYCLE_NUMBER.desc(), OPTIMIZATION_CYCLES.ID.desc());
        }
        OrderField<?> sort = "asc".equalsIgnoreCase(request.sortDirection()) ? field.asc().nullsLast() : field.desc().nullsLast();
        return List.of((SortField<?>) sort, OPTIMIZATION_CYCLES.ID.desc());
    }

    private OptimizationCycleView view(OptimizationCyclesRecord record) {
        return OptimizationCycleView.from(record, jsonbToMap(record.getSummary()));
    }

    private Map<String, Object> jsonbToMap(JSONB jsonb) {
        if (jsonb == null || jsonb.data() == null || jsonb.data().isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(jsonb.data(), MAP_TYPE);
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private String defaultKey(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
