package cn.rvport.backend.report;

import static cn.rvport.backend.jooq.tables.ReportInputs.REPORT_INPUTS;
import static cn.rvport.backend.jooq.tables.Reports.REPORTS;
import static cn.rvport.backend.workflow.DomainLatestOrdering.activeFirst;

import cn.rvport.backend.task.TaskStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
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
public class ReportRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public ReportRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    public ReportView createEvaluation(UUID reportId, UUID projectId, UUID sourceRevisionId, UUID taskId, String title, UUID generatedBy) {
        var record = dsl.insertInto(REPORTS)
                .set(REPORTS.ID, reportId)
                .set(REPORTS.PROJECT_ID, projectId)
                .set(REPORTS.SOURCE_REVISION_ID, sourceRevisionId)
                .set(REPORTS.TASK_ID, taskId)
                .set(REPORTS.REPORT_TYPE, ReportType.EVALUATION.name())
                .set(REPORTS.STATUS, TaskStatus.PENDING.name())
                .set(REPORTS.TITLE, title)
                .set(REPORTS.GENERATED_BY, generatedBy)
                .returning()
                .fetchOne();
        return ReportView.from(record, jsonbToMap(record.getSummary()));
    }

    public Optional<ReportView> findById(UUID reportId) {
        return dsl.selectFrom(REPORTS)
                .where(REPORTS.ID.eq(reportId))
                .fetchOptional(record -> ReportView.from(record, jsonbToMap(record.getSummary())));
    }

    public Optional<ReportView> findByTaskId(UUID taskId) {
        return dsl.selectFrom(REPORTS)
                .where(REPORTS.TASK_ID.eq(taskId))
                .fetchOptional(record -> ReportView.from(record, jsonbToMap(record.getSummary())));
    }

    public long count(UUID projectId, ReportListRequest request) {
        return dsl.selectCount()
                .from(REPORTS)
                .where(filter(projectId, request))
                .fetchOne(0, long.class);
    }

    public List<ReportView> list(UUID projectId, ReportListRequest request) {
        int page = request.normalizedPage();
        int pageSize = request.normalizedPageSize();
        return dsl.selectFrom(REPORTS)
                .where(filter(projectId, request))
                .orderBy(sort(request))
                .limit(pageSize)
                .offset((page - 1) * pageSize)
                .fetch(record -> ReportView.from(record, jsonbToMap(record.getSummary())));
    }

    public Optional<ReportView> latest(UUID projectId, UUID sourceRevisionId) {
        Condition condition = REPORTS.PROJECT_ID.eq(projectId);
        if (sourceRevisionId != null) {
            condition = condition.and(REPORTS.SOURCE_REVISION_ID.eq(sourceRevisionId));
        }
        return dsl.selectFrom(REPORTS)
                .where(condition)
                .orderBy(REPORTS.GENERATED_AT.desc().nullsLast(), REPORTS.CREATED_AT.desc(), REPORTS.ID.desc())
                .limit(1)
                .fetchOptional(record -> ReportView.from(record, jsonbToMap(record.getSummary())));
    }

    public Optional<ReportView> latestSucceeded(UUID projectId, UUID sourceRevisionId) {
        Condition condition = REPORTS.PROJECT_ID.eq(projectId)
                .and(REPORTS.REPORT_TYPE.eq(ReportType.EVALUATION.name()))
                .and(REPORTS.STATUS.eq(TaskStatus.SUCCEEDED.name()));
        if (sourceRevisionId != null) {
            condition = condition.and(REPORTS.SOURCE_REVISION_ID.eq(sourceRevisionId));
        }
        return dsl.selectFrom(REPORTS)
                .where(condition)
                .orderBy(activeFirst(REPORTS.STATUS), REPORTS.CREATED_AT.desc(), REPORTS.ID.desc())
                .limit(1)
                .fetchOptional(record -> ReportView.from(record, jsonbToMap(record.getSummary())));
    }

    public Optional<ReportView> latestEvaluation(UUID projectId, UUID sourceRevisionId) {
        Condition condition = REPORTS.PROJECT_ID.eq(projectId)
                .and(REPORTS.REPORT_TYPE.eq(ReportType.EVALUATION.name()));
        if (sourceRevisionId != null) {
            condition = condition.and(REPORTS.SOURCE_REVISION_ID.eq(sourceRevisionId));
        }
        return dsl.selectFrom(REPORTS)
                .where(condition)
                .orderBy(activeFirst(REPORTS.STATUS), REPORTS.CREATED_AT.desc(), REPORTS.ID.desc())
                .limit(1)
                .fetchOptional(record -> ReportView.from(record, jsonbToMap(record.getSummary())));
    }

    public ReportView updateStatus(UUID reportId, TaskStatus status, Map<String, ?> summary) {
        var update = dsl.update(REPORTS)
                .set(REPORTS.STATUS, status.name());
        if (summary != null) {
            update = update.set(REPORTS.SUMMARY, toJsonb(summary));
        }
        var record = update.where(REPORTS.ID.eq(reportId))
                .returning()
                .fetchOne();
        return record == null ? null : ReportView.from(record, jsonbToMap(record.getSummary()));
    }

    public ReportView markSucceeded(UUID reportId, Map<String, ?> summary, String riskLevel) {
        var record = dsl.update(REPORTS)
                .set(REPORTS.STATUS, TaskStatus.SUCCEEDED.name())
                .set(REPORTS.SUMMARY, toJsonb(summary))
                .set(REPORTS.RISK_LEVEL, riskLevel)
                .set(REPORTS.GENERATED_AT, OffsetDateTime.now())
                .where(REPORTS.ID.eq(reportId))
                .returning()
                .fetchOne();
        return record == null ? null : ReportView.from(record, jsonbToMap(record.getSummary()));
    }

    public long countInputs(UUID reportId, ReportInputListRequest request) {
        return dsl.selectCount()
                .from(REPORT_INPUTS)
                .where(inputFilter(reportId, request))
                .fetchOne(0, long.class);
    }

    public List<ReportInputView> listInputs(UUID reportId, ReportInputListRequest request) {
        int page = request.normalizedPage();
        int pageSize = request.normalizedPageSize();
        return dsl.selectFrom(REPORT_INPUTS)
                .where(inputFilter(reportId, request))
                .orderBy(inputSort(request))
                .limit(pageSize)
                .offset((page - 1) * pageSize)
                .fetch(record -> ReportInputView.from(record, jsonbToMap(record.getInputSnapshot())));
    }

    public List<ReportInputView> listAllInputs(UUID reportId) {
        return dsl.selectFrom(REPORT_INPUTS)
                .where(REPORT_INPUTS.REPORT_ID.eq(reportId))
                .orderBy(REPORT_INPUTS.CREATED_AT.asc(), REPORT_INPUTS.ID.asc())
                .fetch(record -> ReportInputView.from(record, jsonbToMap(record.getInputSnapshot())));
    }

    private Condition filter(UUID projectId, ReportListRequest request) {
        Condition condition = REPORTS.PROJECT_ID.eq(projectId).and(REPORTS.REPORT_TYPE.eq(ReportType.EVALUATION.name()));
        if (request.sourceRevisionId() != null) {
            condition = condition.and(REPORTS.SOURCE_REVISION_ID.eq(request.sourceRevisionId()));
        }
        if (request.status() != null) {
            condition = condition.and(REPORTS.STATUS.eq(request.status().name()));
        }
        if (request.riskLevel() != null && !request.riskLevel().isBlank()) {
            condition = condition.and(REPORTS.RISK_LEVEL.eq(request.riskLevel().trim()));
        }
        if (request.createdFrom() != null) {
            condition = condition.and(REPORTS.CREATED_AT.ge(request.createdFrom()));
        }
        if (request.createdTo() != null) {
            condition = condition.and(REPORTS.CREATED_AT.le(request.createdTo()));
        }
        return condition;
    }

    private List<SortField<?>> sort(ReportListRequest request) {
        Field<?> field = switch (blankToDefault(request.sortBy(), "")) {
            case "generatedAt" -> REPORTS.GENERATED_AT;
            case "createdAt" -> REPORTS.CREATED_AT;
            case "status" -> REPORTS.STATUS;
            case "riskLevel" -> REPORTS.RISK_LEVEL;
            case "" -> null;
            default -> throw new IllegalArgumentException("Unsupported report sort field: " + request.sortBy());
        };
        if (field == null) {
            return List.of(REPORTS.GENERATED_AT.desc().nullsLast(), REPORTS.CREATED_AT.desc(), REPORTS.ID.desc());
        }
        return List.of("asc".equalsIgnoreCase(request.sortDirection()) ? field.asc().nullsLast() : field.desc().nullsLast(), REPORTS.ID.desc());
    }

    private Condition inputFilter(UUID reportId, ReportInputListRequest request) {
        Condition condition = REPORT_INPUTS.REPORT_ID.eq(reportId);
        if (request.inputType() != null) {
            condition = condition.and(REPORT_INPUTS.INPUT_TYPE.eq(request.inputType().name()));
        }
        if (request.keyword() != null && !request.keyword().isBlank()) {
            condition = condition.and(REPORT_INPUTS.INPUT_SNAPSHOT.cast(String.class).containsIgnoreCase(request.keyword().trim()));
        }
        return condition;
    }

    private List<SortField<?>> inputSort(ReportInputListRequest request) {
        Field<?> field = switch (blankToDefault(request.sortBy(), "")) {
            case "createdAt" -> REPORT_INPUTS.CREATED_AT;
            case "inputType" -> REPORT_INPUTS.INPUT_TYPE;
            case "" -> null;
            default -> throw new IllegalArgumentException("Unsupported report input sort field: " + request.sortBy());
        };
        if (field == null) {
            return List.of(REPORT_INPUTS.CREATED_AT.asc(), REPORT_INPUTS.ID.asc());
        }
        return List.of("desc".equalsIgnoreCase(request.sortDirection()) ? field.desc().nullsLast() : field.asc().nullsLast(), REPORT_INPUTS.ID.asc());
    }

    private JSONB toJsonb(Map<String, ?> value) {
        try {
            return JSONB.valueOf(objectMapper.writeValueAsString(value == null ? Map.of() : value));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Report JSON value is not serializable", ex);
        }
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

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }


}
