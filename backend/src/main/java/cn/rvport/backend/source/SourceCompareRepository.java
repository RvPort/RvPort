package cn.rvport.backend.source;

import static cn.rvport.backend.jooq.tables.DiffItems.DIFF_ITEMS;
import static cn.rvport.backend.jooq.tables.RecheckRuns.RECHECK_RUNS;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.rvport.backend.jooq.tables.records.DiffItemsRecord;
import cn.rvport.backend.jooq.tables.records.RecheckRunsRecord;
import cn.rvport.backend.task.TaskStatus;
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
import org.jooq.SortField;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
public class SourceCompareRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public SourceCompareRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    public Optional<UUID> confirmedReport(UUID projectId, UUID revisionId, UUID confirmationId) {
        return dsl.fetchOptional("""
                select c.report_id from report_fix_list_confirmations c
                join report_review_runs r on r.report_id=c.report_id
                where r.project_id=? and r.source_revision_id=? and c.id=?
                """, projectId, revisionId, confirmationId).map(row -> row.get(0, UUID.class));
    }

    public Optional<UUID> latestConfirmation(UUID projectId, UUID revisionId) {
        return dsl.fetchOptional("""
                select c.id from report_fix_list_confirmations c
                join report_review_runs r on r.report_id=c.report_id
                where r.project_id=? and r.source_revision_id=?
                order by c.confirmed_at desc,c.id desc limit 1
                """, projectId, revisionId).map(row -> row.get(0, UUID.class));
    }

    public Optional<RecheckRunView> latestRecheckRun(UUID projectId, UUID baseConfirmationId, UUID targetConfirmationId) {
        return dsl.selectFrom(RECHECK_RUNS)
                .where(RECHECK_RUNS.PROJECT_ID.eq(projectId)
                        .and(RECHECK_RUNS.BASE_CONFIRMATION_ID.eq(baseConfirmationId))
                        .and(RECHECK_RUNS.TARGET_CONFIRMATION_ID.eq(targetConfirmationId)))
                .orderBy(RECHECK_RUNS.CREATED_AT.desc(), RECHECK_RUNS.ID.desc())
                .limit(1)
                .fetchOptional(record -> RecheckRunView.from(record, jsonbToMap(record.getSummary())));
    }

    public Optional<RecheckRunView> findReusableRecheckRun(UUID projectId, UUID baseConfirmationId, UUID targetConfirmationId) {
        return dsl.selectFrom(RECHECK_RUNS)
                .where(RECHECK_RUNS.PROJECT_ID.eq(projectId)
                        .and(RECHECK_RUNS.BASE_CONFIRMATION_ID.eq(baseConfirmationId))
                        .and(RECHECK_RUNS.TARGET_CONFIRMATION_ID.eq(targetConfirmationId))
                        .and(RECHECK_RUNS.STATUS.in(
                                TaskStatus.PENDING.name(),
                                TaskStatus.QUEUED.name(),
                                TaskStatus.RUNNING.name(),
                                TaskStatus.SUCCEEDED.name()
                        )))
                .orderBy(RECHECK_RUNS.CREATED_AT.desc(), RECHECK_RUNS.ID.desc())
                .limit(1)
                .fetchOptional(record -> RecheckRunView.from(record, jsonbToMap(record.getSummary())));
    }

    public Optional<RecheckRunView> findRecheckRun(UUID recheckRunId) {
        return dsl.selectFrom(RECHECK_RUNS)
                .where(RECHECK_RUNS.ID.eq(recheckRunId))
                .fetchOptional(record -> RecheckRunView.from(record, jsonbToMap(record.getSummary())));
    }

    public RecheckRunView createRecheckRun(UUID id, UUID projectId, UUID taskId, UUID baseSourceRevisionId, UUID targetSourceRevisionId,
            UUID baseReportId, UUID targetReportId, UUID baseConfirmationId, UUID targetConfirmationId, TaskStatus status) {
        RecheckRunsRecord record = dsl.insertInto(RECHECK_RUNS)
                .set(RECHECK_RUNS.ID, id)
                .set(RECHECK_RUNS.PROJECT_ID, projectId)
                .set(RECHECK_RUNS.TASK_ID, taskId)
                .set(RECHECK_RUNS.BASE_SOURCE_REVISION_ID, baseSourceRevisionId)
                .set(RECHECK_RUNS.TARGET_SOURCE_REVISION_ID, targetSourceRevisionId)
                .set(RECHECK_RUNS.BASE_REPORT_ID, baseReportId)
                .set(RECHECK_RUNS.TARGET_REPORT_ID, targetReportId)
                .set(RECHECK_RUNS.BASE_CONFIRMATION_ID, baseConfirmationId)
                .set(RECHECK_RUNS.TARGET_CONFIRMATION_ID, targetConfirmationId)
                .set(RECHECK_RUNS.STATUS, status.name())
                .set(RECHECK_RUNS.SUMMARY, JSONB.valueOf("{}"))
                .returning()
                .fetchOne();
        return RecheckRunView.from(record, Map.of());
    }

    public void updateRecheckStatus(UUID recheckRunId, TaskStatus status, Map<String, Object> summary) {
        dsl.update(RECHECK_RUNS)
                .set(RECHECK_RUNS.STATUS, status.name())
                .set(RECHECK_RUNS.SUMMARY, JSONB.valueOf(toJson(summary == null ? Map.of() : summary)))
                .where(RECHECK_RUNS.ID.eq(recheckRunId))
                .execute();
    }

    public DiffSummaryResponse diffSummary(UUID projectId, UUID recheckRunId) {
        long total = dsl.selectCount()
                .from(DIFF_ITEMS)
                .where(DIFF_ITEMS.PROJECT_ID.eq(projectId)
                        .and(DIFF_ITEMS.RECHECK_RUN_ID.eq(recheckRunId)))
                .fetchOne(0, long.class);
        return new DiffSummaryResponse(
                total,
                grouped(DIFF_ITEMS.DIFF_STATUS, projectId, recheckRunId),
                grouped(DIFF_ITEMS.SOURCE_TYPE, projectId, recheckRunId),
                grouped(DIFF_ITEMS.SEVERITY, projectId, recheckRunId)
        );
    }

    public long countDiffItems(UUID projectId, UUID recheckRunId, RecheckDiffItemListRequest request) {
        return dsl.selectCount()
                .from(DIFF_ITEMS)
                .where(diffFilter(projectId, recheckRunId, request))
                .fetchOne(0, long.class);
    }

    public List<RecheckDiffItemView> listDiffItems(UUID projectId, UUID recheckRunId, RecheckDiffItemListRequest request) {
        int page = request.normalizedPage();
        int pageSize = request.normalizedPageSize();
        return dsl.selectFrom(DIFF_ITEMS)
                .where(diffFilter(projectId, recheckRunId, request))
                .orderBy(diffSort(request))
                .limit(pageSize)
                .offset((page - 1) * pageSize)
                .fetch(record -> RecheckDiffItemView.from(record, jsonbToMap(record.getOldSnapshot()), jsonbToMap(record.getNewSnapshot())));
    }

    public Optional<RecheckDiffItemView> findDiffItem(UUID projectId, UUID recheckRunId, UUID diffItemId) {
        return dsl.selectFrom(DIFF_ITEMS)
                .where(DIFF_ITEMS.PROJECT_ID.eq(projectId)
                        .and(DIFF_ITEMS.RECHECK_RUN_ID.eq(recheckRunId))
                        .and(DIFF_ITEMS.ID.eq(diffItemId)))
                .fetchOptional(record -> RecheckDiffItemView.from(record, jsonbToMap(record.getOldSnapshot()), jsonbToMap(record.getNewSnapshot())));
    }

    public void replaceDiffItems(UUID projectId, UUID recheckRunId, List<DiffItemCreateCommand> items) {
        dsl.deleteFrom(DIFF_ITEMS)
                .where(DIFF_ITEMS.PROJECT_ID.eq(projectId)
                        .and(DIFF_ITEMS.RECHECK_RUN_ID.eq(recheckRunId)))
                .execute();
        for (DiffItemCreateCommand item : items) {
            dsl.insertInto(DIFF_ITEMS)
                    .set(DIFF_ITEMS.RECHECK_RUN_ID, recheckRunId)
                    .set(DIFF_ITEMS.PROJECT_ID, projectId)
                    .set(DIFF_ITEMS.SOURCE_TYPE, item.sourceType())
                    .set(DIFF_ITEMS.OLD_SOURCE_ID, item.oldSourceId())
                    .set(DIFF_ITEMS.NEW_SOURCE_ID, item.newSourceId())
                    .set(DIFF_ITEMS.DIFF_STATUS, item.diffStatus())
                    .set(DIFF_ITEMS.OLD_LOCATION, jsonb(item.oldLocation()))
                    .set(DIFF_ITEMS.NEW_LOCATION, jsonb(item.newLocation()))
                    .set(DIFF_ITEMS.SEVERITY, item.severity())
                    .set(DIFF_ITEMS.FINGERPRINT, item.fingerprint())
                    .set(DIFF_ITEMS.TITLE, item.title())
                    .set(DIFF_ITEMS.LOCATION_LABEL, item.locationLabel())
                    .set(DIFF_ITEMS.RECOMMENDATION, item.recommendation())
                    .set(DIFF_ITEMS.CONFIRMATION_STATUS, item.confirmationStatus())
                    .set(DIFF_ITEMS.MATCH_CONFIDENCE, item.matchConfidence())
                    .set(DIFF_ITEMS.MATCH_REASON, item.matchReason())
                    .set(DIFF_ITEMS.OLD_SNAPSHOT, jsonb(item.oldSnapshot()))
                    .set(DIFF_ITEMS.NEW_SNAPSHOT, jsonb(item.newSnapshot()))
                    .execute();
        }
    }

    private Condition diffFilter(UUID projectId, UUID recheckRunId, RecheckDiffItemListRequest request) {
        Condition condition = DIFF_ITEMS.PROJECT_ID.eq(projectId).and(DIFF_ITEMS.RECHECK_RUN_ID.eq(recheckRunId));
        if (request.diffStatus() != null && !request.diffStatus().isBlank()) {
            condition = condition.and(DIFF_ITEMS.DIFF_STATUS.eq(request.diffStatus().trim()));
        }
        if (request.sourceType() != null && !request.sourceType().isBlank()) {
            condition = condition.and(DIFF_ITEMS.SOURCE_TYPE.eq(request.sourceType().trim()));
        }
        if (request.severity() != null && !request.severity().isBlank()) {
            condition = condition.and(DIFF_ITEMS.SEVERITY.eq(request.severity().trim()));
        }
        if (request.matchConfidence() != null && !request.matchConfidence().isBlank()) {
            condition = condition.and(DIFF_ITEMS.MATCH_CONFIDENCE.eq(request.matchConfidence().trim()));
        }
        return condition;
    }

    private List<SortField<?>> diffSort(RecheckDiffItemListRequest request) {
        Field<?> field = switch (blankToDefault(request.sortBy(), "")) {
            case "diffStatus" -> DIFF_ITEMS.DIFF_STATUS;
            case "sourceType" -> DIFF_ITEMS.SOURCE_TYPE;
            case "severity" -> DIFF_ITEMS.SEVERITY;
            case "title" -> DIFF_ITEMS.TITLE;
            case "locationLabel" -> DIFF_ITEMS.LOCATION_LABEL;
            case "matchConfidence" -> DIFF_ITEMS.MATCH_CONFIDENCE;
            case "" -> null;
            default -> throw new IllegalArgumentException("Unsupported diff item sort field: " + request.sortBy());
        };
        if (field == null) {
            return List.of(DIFF_ITEMS.DIFF_STATUS.asc(), DIFF_ITEMS.ID.asc());
        }
        return List.of("desc".equalsIgnoreCase(request.sortDirection()) ? field.desc().nullsLast() : field.asc().nullsLast(), DIFF_ITEMS.ID.asc());
    }

    private Map<String, Long> grouped(Field<String> field, UUID projectId, UUID recheckRunId) {
        Map<String, Long> values = new LinkedHashMap<>();
        dsl.select(field, DSL.count())
                .from(DIFF_ITEMS)
                .where(DIFF_ITEMS.PROJECT_ID.eq(projectId)
                        .and(DIFF_ITEMS.RECHECK_RUN_ID.eq(recheckRunId)))
                .groupBy(field)
                .fetch()
                .forEach(record -> {
                    String key = record.value1();
                    values.put(key == null || key.isBlank() ? "UNKNOWN" : key, record.value2().longValue());
                });
        return values;
    }

    private Map<String, Object> jsonbToMap(JSONB jsonb) {
        if (jsonb == null || jsonb.data() == null || jsonb.data().isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(jsonb.data(), MAP_TYPE);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private JSONB jsonb(Map<String, Object> value) {
        return value == null ? null : JSONB.valueOf(toJson(value));
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to serialize diff item snapshot", ex);
        }
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public record DiffItemCreateCommand(
            String sourceType,
            UUID oldSourceId,
            UUID newSourceId,
            String diffStatus,
            Map<String, Object> oldLocation,
            Map<String, Object> newLocation,
            String severity,
            String title,
            String locationLabel,
            String recommendation,
            String confirmationStatus,
            String fingerprint,
            String matchConfidence,
            String matchReason,
            Map<String, Object> oldSnapshot,
            Map<String, Object> newSnapshot
    ) {
    }
}
