package cn.rvport.backend.kb;

import static cn.rvport.backend.jooq.tables.Findings.FINDINGS;
import static cn.rvport.backend.jooq.tables.KbScanRuns.KB_SCAN_RUNS;
import static cn.rvport.backend.jooq.tables.Tasks.TASKS;
import static cn.rvport.backend.workflow.DomainLatestOrdering.activeFirst;

import cn.rvport.backend.jooq.tables.records.KbScanRunsRecord;
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
import org.jooq.Record;
import org.jooq.SortField;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Repository;

@Repository
public class KbScanRepository {

    private static final Table<?> KB_SCAN_ARTIFACTS = DSL.table(DSL.name("kb_scan_artifacts"));
    private static final Field<UUID> KBA_ID = DSL.field(DSL.name("kb_scan_artifacts", "id"), SQLDataType.UUID);
    private static final Field<UUID> KBA_PROJECT_ID = DSL.field(DSL.name("kb_scan_artifacts", "project_id"), SQLDataType.UUID);
    private static final Field<UUID> KBA_TASK_ID = DSL.field(DSL.name("kb_scan_artifacts", "task_id"), SQLDataType.UUID);
    private static final Field<UUID> KBA_SCAN_RUN_ID = DSL.field(DSL.name("kb_scan_artifacts", "scan_run_id"), SQLDataType.UUID);
    private static final Field<String> KBA_ARTIFACT_TYPE = DSL.field(DSL.name("kb_scan_artifacts", "artifact_type"), SQLDataType.CLOB);
    private static final Field<String> KBA_SCHEMA_VERSION = DSL.field(DSL.name("kb_scan_artifacts", "schema_version"), SQLDataType.CLOB);
    private static final Field<String> KBA_SHA256 = DSL.field(DSL.name("kb_scan_artifacts", "sha256"), SQLDataType.CLOB);
    private static final Field<Long> KBA_SIZE_BYTES = DSL.field(DSL.name("kb_scan_artifacts", "size_bytes"), SQLDataType.BIGINT);
    private static final Field<String> KBA_CONTENT_PATH = DSL.field(DSL.name("kb_scan_artifacts", "content_path"), SQLDataType.CLOB);
    private static final Field<UUID> KBA_STORED_ARTIFACT_ID = DSL.field(DSL.name("kb_scan_artifacts", "stored_artifact_id"), SQLDataType.UUID);
    private static final Field<JSONB> KBA_SUMMARY = DSL.field(DSL.name("kb_scan_artifacts", "summary"), SQLDataType.JSONB);
    private static final Field<OffsetDateTime> KBA_CREATED_AT = DSL.field(DSL.name("kb_scan_artifacts", "created_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public KbScanRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    public UUID createRun(KbScanInput input, UUID taskId) {
        if (input.executionContext() == null || !input.executionContext().complete()) {
            throw new IllegalArgumentException("KB scan requires a complete execution context");
        }
        return dsl.insertInto(KB_SCAN_RUNS)
                .set(KB_SCAN_RUNS.PROJECT_ID, input.projectId())
                .set(KB_SCAN_RUNS.TASK_ID, taskId)
                .set(KB_SCAN_RUNS.SOURCE_REVISION_ID, input.sourceRevisionId())
                .set(KB_SCAN_RUNS.SOURCE_ENVIRONMENT_ID, input.executionContext().sourceEnvironmentId())
                .set(KB_SCAN_RUNS.RUNTIME_ENVIRONMENT_VERSION, input.executionContext().runtimeEnvironmentVersion())
                .set(KB_SCAN_RUNS.BUILD_RUN_ID, input.executionContext().buildRunId())
                .set(KB_SCAN_RUNS.INPUT_PLAN, planJson(input.executionContext().inputPlan()))
                .set(KB_SCAN_RUNS.KB_VERSION, blankToNull(input.ruleVersion()))
                .set(KB_SCAN_RUNS.SCOPE, toJsonb(input.scanScope()))
                .set(KB_SCAN_RUNS.STATUS, TaskStatus.PENDING.name())
                .returning(KB_SCAN_RUNS.ID)
                .fetchOne(KB_SCAN_RUNS.ID);
    }

    public Optional<KbScanView> findById(UUID id) {
        return dsl.selectFrom(KB_SCAN_RUNS)
                .where(KB_SCAN_RUNS.ID.eq(id))
                .fetchOptional()
                .map(this::mapRun);
    }

    public void storeEffectivePlan(UUID runId, cn.rvport.backend.analysis.AnalysisInputPlan requested,
            cn.rvport.backend.analysis.AnalysisInputPlan effective) {
        if (!requested.effectiveInputDigest().equals(effective.originalInputDigest()))
            throw new IllegalArgumentException("ANALYSIS_INPUT_PLAN_MISMATCH");
        JSONB actual = planJson(effective);
        int changed = dsl.update(KB_SCAN_RUNS).set(KB_SCAN_RUNS.EFFECTIVE_INPUT_PLAN, actual)
                .where(KB_SCAN_RUNS.ID.eq(runId).and(KB_SCAN_RUNS.INPUT_PLAN.eq(planJson(requested)))
                        .and(KB_SCAN_RUNS.EFFECTIVE_INPUT_PLAN.isNull().or(KB_SCAN_RUNS.EFFECTIVE_INPUT_PLAN.eq(actual))))
                .execute();
        if (changed != 1) throw new IllegalStateException("ANALYSIS_EFFECTIVE_PLAN_CONFLICT");
    }

    public void lockContext(UUID projectId) {
        dsl.execute("select pg_advisory_xact_lock(hashtextextended('rvport-kb-context:' || cast(? as text), 0))", projectId);
    }

    public Optional<KbScanView> findLatestRunByContext(UUID projectId, UUID sourceRevisionId, KbExecutionContext context) {
        return dsl.select(KB_SCAN_RUNS.fields()).from(KB_SCAN_RUNS)
                .leftJoin(TASKS).on(TASKS.ID.eq(KB_SCAN_RUNS.TASK_ID))
                .where(KB_SCAN_RUNS.PROJECT_ID.eq(projectId).and(KB_SCAN_RUNS.SOURCE_REVISION_ID.eq(sourceRevisionId))
                        .and(contextFilter(context)))
                .orderBy(activeFirst(KB_SCAN_RUNS.STATUS), TASKS.CREATED_AT.desc().nullsLast(), KB_SCAN_RUNS.ID.desc())
                .limit(1).fetchOptional().map(record -> mapRun(record.into(KB_SCAN_RUNS)));
    }

    public Optional<KbScanView> findLatestHistoricalRun(UUID projectId) {
        return dsl.select(KB_SCAN_RUNS.fields()).from(KB_SCAN_RUNS)
                .leftJoin(TASKS).on(TASKS.ID.eq(KB_SCAN_RUNS.TASK_ID))
                .where(KB_SCAN_RUNS.PROJECT_ID.eq(projectId))
                .orderBy(activeFirst(KB_SCAN_RUNS.STATUS), TASKS.CREATED_AT.desc().nullsLast(), KB_SCAN_RUNS.ID.desc())
                .limit(1).fetchOptional().map(record -> mapRun(record.into(KB_SCAN_RUNS)));
    }

    public Optional<KbScanView> findLatestPublishedByContext(UUID projectId, UUID sourceRevisionId, KbExecutionContext context) {
        return dsl.select(KB_SCAN_RUNS.fields()).from(KB_SCAN_RUNS)
                .leftJoin(TASKS).on(TASKS.ID.eq(KB_SCAN_RUNS.TASK_ID))
                .where(KB_SCAN_RUNS.PROJECT_ID.eq(projectId).and(KB_SCAN_RUNS.SOURCE_REVISION_ID.eq(sourceRevisionId))
                        .and(KB_SCAN_RUNS.STATUS.eq(TaskStatus.SUCCEEDED.name())).and(contextFilter(context))
                        .and(DSL.exists(dsl.selectOne().from(KB_SCAN_ARTIFACTS)
                                .where(KBA_SCAN_RUN_ID.eq(KB_SCAN_RUNS.ID).and(KBA_ARTIFACT_TYPE.eq("SCAN_RESULT_JSON"))
                                        .and(KBA_STORED_ARTIFACT_ID.isNotNull())))))
                .orderBy(TASKS.CREATED_AT.desc().nullsLast(), KB_SCAN_RUNS.ID.desc())
                .limit(1).fetchOptional().map(record -> mapRun(record.into(KB_SCAN_RUNS)));
    }

    private Condition contextFilter(KbExecutionContext context) {
        Condition sourceOnly = DSL.condition("coalesce({0}, {1})->>'inputOrigin' = 'SOURCE_DISCOVERY'",
                KB_SCAN_RUNS.EFFECTIVE_INPUT_PLAN, KB_SCAN_RUNS.INPUT_PLAN);
        if (context.sourceOnly()) return sourceOnly;
        return KB_SCAN_RUNS.SOURCE_ENVIRONMENT_ID.eq(context.sourceEnvironmentId())
                .and(KB_SCAN_RUNS.RUNTIME_ENVIRONMENT_VERSION.eq(context.runtimeEnvironmentVersion()))
                .and(KB_SCAN_RUNS.BUILD_RUN_ID.eq(context.buildRunId())).or(sourceOnly);
    }

    private JSONB planJson(cn.rvport.backend.analysis.AnalysisInputPlan plan) {
        if (plan == null) return null;
        try { return JSONB.valueOf(objectMapper.writeValueAsString(plan)); }
        catch (java.io.IOException ex) { throw new IllegalArgumentException("ANALYSIS_PLAN_INVALID", ex); }
    }

    public Optional<KbScanView> findByTaskId(UUID taskId) {
        return dsl.selectFrom(KB_SCAN_RUNS)
                .where(KB_SCAN_RUNS.TASK_ID.eq(taskId))
                .fetchOptional()
                .map(this::mapRun);
    }

    public Optional<KbScanView> findLatestSucceededRunBySourceRevision(UUID projectId, UUID sourceRevisionId) {
        return dsl.selectFrom(KB_SCAN_RUNS)
                .where(KB_SCAN_RUNS.PROJECT_ID.eq(projectId)
                        .and(KB_SCAN_RUNS.SOURCE_REVISION_ID.eq(sourceRevisionId))
                        .and(KB_SCAN_RUNS.STATUS.eq(TaskStatus.SUCCEEDED.name())))
                .orderBy(KB_SCAN_RUNS.FINISHED_AT.desc().nullsLast(), KB_SCAN_RUNS.ID.desc())
                .limit(1)
                .fetchOptional()
                .map(this::mapRun);
    }

    public Optional<KbScanView> findLatestRunBySourceRevision(UUID projectId, UUID sourceRevisionId) {
        return dsl.select(KB_SCAN_RUNS.fields())
                .from(KB_SCAN_RUNS)
                .leftJoin(TASKS).on(TASKS.ID.eq(KB_SCAN_RUNS.TASK_ID))
                .where(KB_SCAN_RUNS.PROJECT_ID.eq(projectId)
                        .and(KB_SCAN_RUNS.SOURCE_REVISION_ID.eq(sourceRevisionId)))
                .orderBy(activeFirst(KB_SCAN_RUNS.STATUS), TASKS.CREATED_AT.desc().nullsLast(), KB_SCAN_RUNS.ID.desc())
                .limit(1)
                .fetchOptional()
                .map(record -> mapRun(record.into(KB_SCAN_RUNS)));
    }

    public Optional<KbScanView> findLatestRun(UUID projectId) {
        return dsl.select(KB_SCAN_RUNS.fields())
                .from(KB_SCAN_RUNS)
                .leftJoin(TASKS).on(TASKS.ID.eq(KB_SCAN_RUNS.TASK_ID))
                .where(KB_SCAN_RUNS.PROJECT_ID.eq(projectId))
                .orderBy(activeFirst(KB_SCAN_RUNS.STATUS), TASKS.CREATED_AT.desc().nullsLast(), KB_SCAN_RUNS.ID.desc())
                .limit(1)
                .fetchOptional()
                .map(record -> mapRun(record.into(KB_SCAN_RUNS)));
    }

    public long countRuns(UUID projectId, KbScanListRequest request) {
        return dsl.selectCount()
                .from(KB_SCAN_RUNS)
                .where(runFilter(projectId, request))
                .fetchOne(0, long.class);
    }

    public List<KbScanView> listRuns(UUID projectId, KbScanListRequest request) {
        int page = request.normalizedPage();
        int pageSize = request.normalizedPageSize();
        return dsl.selectFrom(KB_SCAN_RUNS)
                .where(runFilter(projectId, request))
                .orderBy(runSort(request))
                .limit(pageSize)
                .offset((page - 1) * pageSize)
                .fetch(this::mapRun);
    }

    public void markRunning(UUID runId) {
        dsl.update(KB_SCAN_RUNS)
                .set(KB_SCAN_RUNS.STATUS, TaskStatus.RUNNING.name())
                .set(KB_SCAN_RUNS.STARTED_AT, OffsetDateTime.now())
                .where(KB_SCAN_RUNS.ID.eq(runId))
                .execute();
    }

    public void markSucceeded(UUID runId, Map<String, ?> summary) {
        finish(runId, TaskStatus.SUCCEEDED, summary);
    }

    public void markFailed(UUID runId, Map<String, ?> summary) {
        finish(runId, TaskStatus.FAILED, summary);
    }

    public void markCancelled(UUID runId, Map<String, ?> summary) {
        finish(runId, TaskStatus.CANCELLED, summary);
    }

    public void replaceFindings(UUID scanRunId, List<KbFindingInput> findings) {
        dsl.deleteFrom(FINDINGS)
                .where(FINDINGS.SCAN_RUN_ID.eq(scanRunId))
                .execute();
        for (KbFindingInput finding : findings) {
            dsl.insertInto(FINDINGS)
                    .set(FINDINGS.PROJECT_ID, finding.projectId())
                    .set(FINDINGS.SCAN_RUN_ID, finding.scanRunId())
                    .set(FINDINGS.SOURCE_REVISION_ID, finding.sourceRevisionId())
                    .set(FINDINGS.RULE_ID, finding.ruleId())
                    .set(FINDINGS.CATEGORY, finding.category())
                    .set(FINDINGS.SEVERITY, finding.severity())
                    .set(FINDINGS.TITLE, finding.title())
                    .set(FINDINGS.DESCRIPTION, blankToNull(finding.description()))
                    .set(FINDINGS.FILE_PATH, blankToNull(finding.filePath()))
                    .set(FINDINGS.LINE_START, finding.lineStart())
                    .set(FINDINGS.LINE_END, finding.lineEnd())
                    .set(FINDINGS.SYMBOL, blankToNull(finding.symbol()))
                    .set(FINDINGS.EVIDENCE, toJsonb(finding.evidence()))
                    .set(FINDINGS.SUGGESTION, blankToNull(finding.suggestion()))
                    .set(FINDINGS.FINGERPRINT, finding.fingerprint())
                    .set(FINDINGS.STATUS, "DETECTED")
                    .execute();
        }
    }

    public Optional<FindingView> findFinding(UUID findingId) {
        return dsl.selectFrom(FINDINGS)
                .where(FINDINGS.ID.eq(findingId))
                .fetchOptional()
                .map(record -> FindingView.from(record, jsonbToMap(record.getEvidence())));
    }

    public long countFindings(UUID scanRunId, FindingListRequest request) {
        return dsl.selectCount()
                .from(FINDINGS)
                .where(findingFilter(scanRunId, request))
                .fetchOne(0, long.class);
    }

    public List<FindingView> listFindingViews(UUID scanRunId, FindingListRequest request) {
        int page = request.normalizedPage();
        int pageSize = request.normalizedPageSize();
        return dsl.selectFrom(FINDINGS)
                .where(findingFilter(scanRunId, request))
                .orderBy(findingSort(request))
                .limit(pageSize)
                .offset((page - 1) * pageSize)
                .fetch(record -> FindingView.from(record, jsonbToMap(record.getEvidence())));
    }

    public List<FindingResponse> listFindings(UUID scanRunId, FindingListRequest request) {
        return listFindingViews(scanRunId, request).stream()
                .map(FindingResponse::from)
                .toList();
    }

    public List<FindingView> listAllFindingViews(UUID scanRunId) {
        return dsl.selectFrom(FINDINGS)
                .where(FINDINGS.SCAN_RUN_ID.eq(scanRunId))
                .orderBy(riskRank(FINDINGS.SEVERITY).desc(), FINDINGS.FILE_PATH.asc().nullsLast(), FINDINGS.LINE_START.asc().nullsLast(), FINDINGS.ID.asc())
                .fetch(record -> FindingView.from(record, jsonbToMap(record.getEvidence())));
    }

    public List<UUID> listFindingIds(UUID scanRunId) {
        return dsl.select(FINDINGS.ID)
                .from(FINDINGS)
                .where(FINDINGS.SCAN_RUN_ID.eq(scanRunId))
                .orderBy(riskRank(FINDINGS.SEVERITY).desc(), FINDINGS.FILE_PATH.asc().nullsLast(), FINDINGS.LINE_START.asc().nullsLast())
                .fetch(FINDINGS.ID);
    }

    public void upsertArtifact(KbScanArtifact artifact) {
        dsl.insertInto(KB_SCAN_ARTIFACTS)
                .columns(KBA_PROJECT_ID, KBA_TASK_ID, KBA_SCAN_RUN_ID, KBA_ARTIFACT_TYPE, KBA_SCHEMA_VERSION, KBA_SHA256,
                        KBA_SIZE_BYTES, KBA_CONTENT_PATH, KBA_STORED_ARTIFACT_ID, KBA_SUMMARY)
                .values(artifact.projectId(), artifact.taskId(), artifact.scanRunId(), artifact.artifactType().name(),
                        artifact.schemaVersion(), artifact.sha256(), artifact.sizeBytes(), artifact.contentPath(), artifact.storedArtifactId(), toJsonb(artifact.summary()))
                .onConflict(KBA_SCAN_RUN_ID, KBA_ARTIFACT_TYPE)
                .doUpdate()
                .set(KBA_SCHEMA_VERSION, artifact.schemaVersion())
                .set(KBA_SHA256, artifact.sha256())
                .set(KBA_SIZE_BYTES, artifact.sizeBytes())
                .set(KBA_CONTENT_PATH, artifact.contentPath())
                .set(KBA_STORED_ARTIFACT_ID, artifact.storedArtifactId())
                .set(KBA_SUMMARY, toJsonb(artifact.summary()))
                .execute();
    }

    public long countArtifacts(UUID scanRunId, KbScanArtifactListRequest request) {
        return dsl.selectCount()
                .from(KB_SCAN_ARTIFACTS)
                .where(artifactFilter(scanRunId, request))
                .fetchOne(0, long.class);
    }

    public List<KbScanArtifactSummaryResponse> listArtifacts(UUID scanRunId, KbScanArtifactListRequest request) {
        int page = request.normalizedPage();
        int pageSize = request.normalizedPageSize();
        return dsl.select(KBA_ID, KBA_ARTIFACT_TYPE, KBA_SCHEMA_VERSION, KBA_SHA256, KBA_SIZE_BYTES, KBA_CREATED_AT, KBA_SUMMARY)
                .from(KB_SCAN_ARTIFACTS)
                .where(artifactFilter(scanRunId, request))
                .orderBy(artifactSort(request))
                .limit(pageSize)
                .offset((page - 1) * pageSize)
                .fetch(this::mapArtifactSummary);
    }

    public Optional<KbScanArtifactDownload> findArtifact(UUID scanRunId, UUID artifactId) {
        return dsl.select(KBA_ID, KBA_PROJECT_ID, KBA_SCAN_RUN_ID, KBA_ARTIFACT_TYPE, KBA_SHA256, KBA_SIZE_BYTES, KBA_CONTENT_PATH, KBA_STORED_ARTIFACT_ID)
                .from(KB_SCAN_ARTIFACTS)
                .where(KBA_SCAN_RUN_ID.eq(scanRunId).and(KBA_ID.eq(artifactId)))
                .fetchOptional(record -> new KbScanArtifactDownload(
                        record.get(KBA_ID),
                        record.get(KBA_PROJECT_ID),
                        record.get(KBA_SCAN_RUN_ID),
                        record.get(KBA_ARTIFACT_TYPE),
                        record.get(KBA_SHA256),
                        record.get(KBA_SIZE_BYTES),
                        record.get(KBA_CONTENT_PATH),
                        record.get(KBA_STORED_ARTIFACT_ID)
                ));
    }

    public Optional<KbScanArtifactDownload> findArtifactByType(UUID scanRunId, KbScanArtifactType artifactType) {
        return dsl.select(KBA_ID, KBA_PROJECT_ID, KBA_SCAN_RUN_ID, KBA_ARTIFACT_TYPE, KBA_SHA256, KBA_SIZE_BYTES, KBA_CONTENT_PATH, KBA_STORED_ARTIFACT_ID)
                .from(KB_SCAN_ARTIFACTS)
                .where(KBA_SCAN_RUN_ID.eq(scanRunId).and(KBA_ARTIFACT_TYPE.eq(artifactType.name())))
                .orderBy(KBA_CREATED_AT.desc(), KBA_ID.desc())
                .limit(1)
                .fetchOptional(record -> new KbScanArtifactDownload(
                        record.get(KBA_ID),
                        record.get(KBA_PROJECT_ID),
                        record.get(KBA_SCAN_RUN_ID),
                        record.get(KBA_ARTIFACT_TYPE),
                        record.get(KBA_SHA256),
                        record.get(KBA_SIZE_BYTES),
                        record.get(KBA_CONTENT_PATH),
                        record.get(KBA_STORED_ARTIFACT_ID)
                ));
    }

    private void finish(UUID runId, TaskStatus status, Map<String, ?> summary) {
        dsl.update(KB_SCAN_RUNS)
                .set(KB_SCAN_RUNS.STATUS, status.name())
                .set(KB_SCAN_RUNS.SUMMARY, toJsonb(summary))
                .set(KB_SCAN_RUNS.FINISHED_AT, OffsetDateTime.now())
                .where(KB_SCAN_RUNS.ID.eq(runId))
                .execute();
    }

    private KbScanView mapRun(KbScanRunsRecord record) {
        return KbScanView.from(record, jsonbToMap(record.getScope()), jsonbToMap(record.getSummary()));
    }

    private KbScanArtifactSummaryResponse mapArtifactSummary(Record record) {
        return new KbScanArtifactSummaryResponse(
                record.get(KBA_ID),
                record.get(KBA_ARTIFACT_TYPE),
                record.get(KBA_SCHEMA_VERSION),
                record.get(KBA_SHA256),
                record.get(KBA_SIZE_BYTES),
                record.get(KBA_CREATED_AT),
                jsonbToMap(record.get(KBA_SUMMARY))
        );
    }

    private Condition runFilter(UUID projectId, KbScanListRequest request) {
        Condition condition = KB_SCAN_RUNS.PROJECT_ID.eq(projectId);
        if (request.executionContext() != null) condition = condition.and(contextFilter(request.executionContext()));
        if (request.status() != null) {
            condition = condition.and(KB_SCAN_RUNS.STATUS.eq(request.status().name()));
        }
        if (request.sourceRevisionId() != null) {
            condition = condition.and(KB_SCAN_RUNS.SOURCE_REVISION_ID.eq(request.sourceRevisionId()));
        }
        condition = textEquals(condition, KB_SCAN_RUNS.KB_VERSION, request.kbVersion());
        return condition;
    }

    private List<SortField<?>> runSort(KbScanListRequest request) {
        Field<?> field = switch (blankToDefault(request.sortBy(), "")) {
            case "status" -> KB_SCAN_RUNS.STATUS;
            case "kbVersion" -> KB_SCAN_RUNS.KB_VERSION;
            case "startedAt" -> KB_SCAN_RUNS.STARTED_AT;
            case "finishedAt" -> KB_SCAN_RUNS.FINISHED_AT;
            case "" -> null;
            default -> throw new IllegalArgumentException("Unsupported KB scan sort field: " + request.sortBy());
        };
        if (field == null) {
            return List.of(KB_SCAN_RUNS.STARTED_AT.desc().nullsLast(), KB_SCAN_RUNS.ID.desc());
        }
        return List.of(sort(field, request.sortDirection()), KB_SCAN_RUNS.ID.asc());
    }

    private Condition findingFilter(UUID scanRunId, FindingListRequest request) {
        Condition condition = FINDINGS.SCAN_RUN_ID.eq(scanRunId);
        condition = textEquals(condition, FINDINGS.RULE_ID, request.ruleId());
        condition = textEquals(condition, FINDINGS.CATEGORY, request.category());
        condition = textEquals(condition, FINDINGS.SEVERITY, request.severity());
        condition = textContains(condition, FINDINGS.TITLE, request.title());
        condition = textContains(condition, FINDINGS.FILE_PATH, request.filePath());
        condition = textContains(condition, FINDINGS.SYMBOL, request.symbol());
        condition = textEquals(condition, FINDINGS.STATUS, request.status());
        return condition;
    }

    private List<SortField<?>> findingSort(FindingListRequest request) {
        Field<?> field = switch (blankToDefault(request.sortBy(), "")) {
            case "ruleId" -> FINDINGS.RULE_ID;
            case "category" -> FINDINGS.CATEGORY;
            case "severity" -> FINDINGS.SEVERITY;
            case "title" -> FINDINGS.TITLE;
            case "filePath" -> FINDINGS.FILE_PATH;
            case "lineStart" -> FINDINGS.LINE_START;
            case "status" -> FINDINGS.STATUS;
            case "createdAt" -> FINDINGS.CREATED_AT;
            case "" -> null;
            default -> throw new IllegalArgumentException("Unsupported finding sort field: " + request.sortBy());
        };
        if (field == null) {
            return List.of(riskRank(FINDINGS.SEVERITY).desc(), FINDINGS.FILE_PATH.asc().nullsLast(), FINDINGS.LINE_START.asc().nullsLast());
        }
        return List.of(sort(field, request.sortDirection()), FINDINGS.ID.asc());
    }

    private Condition artifactFilter(UUID scanRunId, KbScanArtifactListRequest request) {
        Condition condition = KBA_SCAN_RUN_ID.eq(scanRunId);
        if (request.artifactType() != null) {
            condition = condition.and(KBA_ARTIFACT_TYPE.eq(request.artifactType().name()));
        }
        condition = textEquals(condition, KBA_SCHEMA_VERSION, request.schemaVersion());
        condition = textEquals(condition, KBA_SHA256, request.sha256());
        return condition;
    }

    private List<SortField<?>> artifactSort(KbScanArtifactListRequest request) {
        Field<?> field = switch (blankToDefault(request.sortBy(), "")) {
            case "artifactType" -> KBA_ARTIFACT_TYPE;
            case "schemaVersion" -> KBA_SCHEMA_VERSION;
            case "sha256" -> KBA_SHA256;
            case "sizeBytes" -> KBA_SIZE_BYTES;
            case "createdAt" -> KBA_CREATED_AT;
            case "" -> null;
            default -> throw new IllegalArgumentException("Unsupported KB artifact sort field: " + request.sortBy());
        };
        if (field == null) {
            return List.of(KBA_CREATED_AT.desc(), KBA_ARTIFACT_TYPE.asc());
        }
        return List.of(sort(field, request.sortDirection()), KBA_ID.asc());
    }

    private SortField<?> sort(Field<?> field, String direction) {
        return "desc".equalsIgnoreCase(direction) ? field.desc().nullsLast() : field.asc().nullsLast();
    }

    private Field<Integer> riskRank(Field<String> riskField) {
        return DSL.when(riskField.eq("critical"), 4)
                .when(riskField.eq("high"), 3)
                .when(riskField.eq("medium"), 2)
                .when(riskField.eq("low"), 1)
                .otherwise(0);
    }

    private Condition textEquals(Condition condition, Field<String> field, String value) {
        return value == null || value.isBlank() ? condition : condition.and(field.eq(value.trim()));
    }

    private Condition textContains(Condition condition, Field<String> field, String value) {
        return value == null || value.isBlank() ? condition : condition.and(field.containsIgnoreCase(value.trim()));
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private JSONB toJsonb(Map<String, ?> value) {
        try {
            return JSONB.valueOf(objectMapper.writeValueAsString(value == null ? Map.of() : value));
        } catch (JsonProcessingException ex) {
            return JSONB.valueOf("{}");
        }
    }

    private Map<String, Object> jsonbToMap(JSONB jsonb) {
        if (jsonb == null || jsonb.data() == null || jsonb.data().isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(jsonb.data(), new TypeReference<>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }
}
