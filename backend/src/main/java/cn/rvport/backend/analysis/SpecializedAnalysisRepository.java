package cn.rvport.backend.analysis;

import static cn.rvport.backend.jooq.tables.SpecializedAnalysisFindings.SPECIALIZED_ANALYSIS_FINDINGS;
import static cn.rvport.backend.jooq.tables.SpecializedAnalysisRuns.SPECIALIZED_ANALYSIS_RUNS;

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
public class SpecializedAnalysisRepository {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public SpecializedAnalysisRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    public SpecializedAnalysisRunView create(
            UUID id, UUID projectId, UUID taskId, SpecializedAnalyzerType analyzerType,
            UUID sourceRevisionId, UUID buildRunId, SpecializedAnalysisTaskInput input,
            Long environmentConfigVersion, UUID createdBy
    ) {
        var record = dsl.insertInto(SPECIALIZED_ANALYSIS_RUNS)
                .set(SPECIALIZED_ANALYSIS_RUNS.ID, id)
                .set(SPECIALIZED_ANALYSIS_RUNS.PROJECT_ID, projectId)
                .set(SPECIALIZED_ANALYSIS_RUNS.TASK_ID, taskId)
                .set(SPECIALIZED_ANALYSIS_RUNS.ANALYZER_TYPE, analyzerType.name())
                .set(SPECIALIZED_ANALYSIS_RUNS.SOURCE_REVISION_ID, sourceRevisionId)
                .set(SPECIALIZED_ANALYSIS_RUNS.BUILD_RUN_ID, buildRunId)
                .set(SPECIALIZED_ANALYSIS_RUNS.INPUT_PLAN, input.inputPlan() == null ? null : planJson(input.inputPlan()))
                .set(SPECIALIZED_ANALYSIS_RUNS.ARTIFACT_SET_ID, input.artifactSetId())
                .set(SPECIALIZED_ANALYSIS_RUNS.ANALYSIS_MODE, input.analysisMode().name())
                .set(SPECIALIZED_ANALYSIS_RUNS.BC_STATUS, input.bcStatus())
                .set(SPECIALIZED_ANALYSIS_RUNS.BC_PLANNED_COUNT, input.bcPlannedCount())
                .set(SPECIALIZED_ANALYSIS_RUNS.BC_USABLE_COUNT, input.bcUsableCount())
                .set(SPECIALIZED_ANALYSIS_RUNS.BC_FAILED_COUNT, input.bcFailedCount())
                .set(SPECIALIZED_ANALYSIS_RUNS.BC_DEGRADATION_REASON, input.bcDegradationReason())
                .set(SPECIALIZED_ANALYSIS_RUNS.ENVIRONMENT_CONFIG_VERSION, environmentConfigVersion)
                .set(SPECIALIZED_ANALYSIS_RUNS.REQUEST_SCHEMA_VERSION, analyzerType == SpecializedAnalyzerType.FLOATING_POINT ? "1.4" : "2")
                .set(SPECIALIZED_ANALYSIS_RUNS.RESULT_SCHEMA_VERSION, analyzerType == SpecializedAnalyzerType.FLOATING_POINT ? "2.0" : "1")
                .set(SPECIALIZED_ANALYSIS_RUNS.POLICY_ID, analyzerType == SpecializedAnalyzerType.MEMORY_ORDER ? "unified-project-analysis-v1" : null)
                .set(SPECIALIZED_ANALYSIS_RUNS.STATUS, SpecializedAnalysisStatus.PENDING.name())
                .set(SPECIALIZED_ANALYSIS_RUNS.CREATED_BY, createdBy)
                .returning()
                .fetchOne();
        return run(record);
    }

    private JSONB planJson(AnalysisInputPlan plan) {
        try { return JSONB.valueOf(objectMapper.writeValueAsString(plan)); }
        catch (java.io.IOException ex) { throw new IllegalArgumentException("ANALYSIS_PLAN_INVALID", ex); }
    }

    public void storeEffectivePlan(UUID runId, AnalysisInputPlan requested, AnalysisInputPlan effective) {
        if (!requested.effectiveInputDigest().equals(effective.originalInputDigest()))
            throw new IllegalArgumentException("ANALYSIS_INPUT_PLAN_MISMATCH");
        JSONB actual = planJson(effective);
        int changed = dsl.update(SPECIALIZED_ANALYSIS_RUNS)
                .set(SPECIALIZED_ANALYSIS_RUNS.EFFECTIVE_INPUT_PLAN, actual)
                .where(SPECIALIZED_ANALYSIS_RUNS.ID.eq(runId)
                        .and(SPECIALIZED_ANALYSIS_RUNS.INPUT_PLAN.eq(planJson(requested)))
                        .and(SPECIALIZED_ANALYSIS_RUNS.EFFECTIVE_INPUT_PLAN.isNull()
                                .or(SPECIALIZED_ANALYSIS_RUNS.EFFECTIVE_INPUT_PLAN.eq(actual)))).execute();
        if (changed != 1) throw new IllegalStateException("ANALYSIS_EFFECTIVE_PLAN_CONFLICT");
    }

    public Optional<AnalysisInputPlan> effectivePlan(UUID runId) {
        return dsl.select(SPECIALIZED_ANALYSIS_RUNS.EFFECTIVE_INPUT_PLAN).from(SPECIALIZED_ANALYSIS_RUNS)
                .where(SPECIALIZED_ANALYSIS_RUNS.ID.eq(runId)).fetchOptional()
                .map(row -> row.value1()).map(json -> {
                    try { return objectMapper.readValue(json.data(), AnalysisInputPlan.class); }
                    catch (java.io.IOException ex) { throw new IllegalStateException("ANALYSIS_PLAN_INVALID", ex); }
                });
    }

    public SpecializedAnalysisRunView create(
            UUID id, UUID projectId, UUID taskId, SpecializedAnalyzerType analyzerType,
            UUID sourceRevisionId, UUID buildRunId, String artifactSetId,
            Long environmentConfigVersion, UUID createdBy
    ) {
        SpecializedAnalysisTaskInput legacyInput = new SpecializedAnalysisTaskInput(
                projectId, id, sourceRevisionId, buildRunId, null, null, null, artifactSetId,
                environmentConfigVersion, analyzerType);
        return create(id, projectId, taskId, analyzerType, sourceRevisionId, buildRunId,
                legacyInput, environmentConfigVersion, createdBy);
    }

    public Optional<SpecializedAnalysisRunView> findById(UUID id) {
        return dsl.selectFrom(SPECIALIZED_ANALYSIS_RUNS)
                .where(SPECIALIZED_ANALYSIS_RUNS.ID.eq(id)).fetchOptional().map(this::run);
    }

    public Optional<SpecializedAnalysisRunView> findByTaskId(UUID taskId) {
        return dsl.selectFrom(SPECIALIZED_ANALYSIS_RUNS)
                .where(SPECIALIZED_ANALYSIS_RUNS.TASK_ID.eq(taskId)).fetchOptional().map(this::run);
    }

    public void updateEvidenceMode(UUID runId, SpecializedBitcodeCapability capability) {
        dsl.update(SPECIALIZED_ANALYSIS_RUNS)
                .set(SPECIALIZED_ANALYSIS_RUNS.ARTIFACT_SET_ID, capability.artifactSetId())
                .set(SPECIALIZED_ANALYSIS_RUNS.ANALYSIS_MODE, capability.mode().name())
                .set(SPECIALIZED_ANALYSIS_RUNS.BC_STATUS, capability.status())
                .set(SPECIALIZED_ANALYSIS_RUNS.BC_PLANNED_COUNT, capability.plannedCount())
                .set(SPECIALIZED_ANALYSIS_RUNS.BC_USABLE_COUNT, capability.usableCount())
                .set(SPECIALIZED_ANALYSIS_RUNS.BC_FAILED_COUNT, capability.failedCount())
                .set(SPECIALIZED_ANALYSIS_RUNS.BC_DEGRADATION_REASON, capability.degradationReason())
                .set(SPECIALIZED_ANALYSIS_RUNS.UPDATED_AT, OffsetDateTime.now())
                .where(SPECIALIZED_ANALYSIS_RUNS.ID.eq(runId)).execute();
    }

    public Optional<SpecializedAnalysisRunView> latest(UUID projectId, SpecializedAnalyzerType analyzerType) {
        return dsl.selectFrom(SPECIALIZED_ANALYSIS_RUNS)
                .where(SPECIALIZED_ANALYSIS_RUNS.PROJECT_ID.eq(projectId)
                        .and(SPECIALIZED_ANALYSIS_RUNS.ANALYZER_TYPE.eq(analyzerType.name())))
                .orderBy(DSL.when(SPECIALIZED_ANALYSIS_RUNS.STATUS.in("PENDING", "QUEUED", "RUNNING"), 0).otherwise(1),
                        SPECIALIZED_ANALYSIS_RUNS.CREATED_AT.desc(), SPECIALIZED_ANALYSIS_RUNS.ID.desc())
                .limit(1).fetchOptional().map(this::run);
    }

    public Optional<SpecializedAnalysisRunView> latestPublished(
            UUID projectId, UUID sourceRevisionId, SpecializedAnalyzerType analyzerType
    ) {
        return latestPublished(projectId, sourceRevisionId, analyzerType, null, null);
    }

    public Optional<SpecializedAnalysisRunView> latestPublished(UUID projectId, UUID sourceRevisionId,
            SpecializedAnalyzerType analyzerType, UUID currentBuildId, Long environmentVersion) {
        return dsl.selectFrom(SPECIALIZED_ANALYSIS_RUNS)
                .where(SPECIALIZED_ANALYSIS_RUNS.PROJECT_ID.eq(projectId)
                        .and(SPECIALIZED_ANALYSIS_RUNS.SOURCE_REVISION_ID.eq(sourceRevisionId))
                        .and(SPECIALIZED_ANALYSIS_RUNS.ANALYZER_TYPE.eq(analyzerType.name()))
                        .and(SPECIALIZED_ANALYSIS_RUNS.STATUS.in("COMPLETE", "COMPLETE_WITH_RISK", "PARTIAL"))
                        .and(DSL.condition("exists(select 1 from projects p where p.id={0} and "
                                + "lower(trim(p.source_arch)) in ('x86','x86_64','amd64','i386','i686','x64') and "
                                + "lower(trim(p.target_arch)) in ('riscv64','riscv','risc-v','risc-v64'))",
                                SPECIALIZED_ANALYSIS_RUNS.PROJECT_ID))
                        .and(SPECIALIZED_ANALYSIS_RUNS.RESULT_JSON_ARTIFACT_ID.isNotNull())
                        .and(DSL.condition("coalesce({0}, {1})->>'inputOrigin' = 'SOURCE_DISCOVERY'",
                                SPECIALIZED_ANALYSIS_RUNS.EFFECTIVE_INPUT_PLAN, SPECIALIZED_ANALYSIS_RUNS.INPUT_PLAN)
                            .or(SPECIALIZED_ANALYSIS_RUNS.BUILD_RUN_ID.eq(currentBuildId)
                                .and(SPECIALIZED_ANALYSIS_RUNS.ENVIRONMENT_CONFIG_VERSION.eq(environmentVersion)))))
                .orderBy(SPECIALIZED_ANALYSIS_RUNS.CREATED_AT.desc(), SPECIALIZED_ANALYSIS_RUNS.ID.desc())
                .limit(1).fetchOptional().map(this::run);
    }

    public long count(UUID projectId, SpecializedAnalyzerType analyzerType, SpecializedAnalysisListRequest request) {
        return dsl.selectCount().from(SPECIALIZED_ANALYSIS_RUNS)
                .where(runFilter(projectId, analyzerType, request)).fetchOne(0, long.class);
    }

    public List<SpecializedAnalysisRunView> list(UUID projectId, SpecializedAnalyzerType analyzerType, SpecializedAnalysisListRequest request) {
        return dsl.selectFrom(SPECIALIZED_ANALYSIS_RUNS)
                .where(runFilter(projectId, analyzerType, request))
                .orderBy(runSort(request), SPECIALIZED_ANALYSIS_RUNS.ID.desc())
                .limit(request.normalizedPageSize())
                .offset((request.normalizedPage() - 1) * request.normalizedPageSize())
                .fetch(this::run);
    }

    public Optional<SpecializedAnalysisFindingView> findFinding(UUID id) {
        return dsl.selectFrom(SPECIALIZED_ANALYSIS_FINDINGS)
                .where(SPECIALIZED_ANALYSIS_FINDINGS.ID.eq(id)).fetchOptional().map(this::finding);
    }

    public long countFindings(UUID runId, SpecializedFindingListRequest request) {
        return dsl.selectCount().from(SPECIALIZED_ANALYSIS_FINDINGS)
                .where(findingFilter(runId, request)).fetchOne(0, long.class);
    }

    public List<SpecializedAnalysisFindingView> listFindings(UUID runId, SpecializedFindingListRequest request) {
        return dsl.selectFrom(SPECIALIZED_ANALYSIS_FINDINGS)
                .where(findingFilter(runId, request))
                .orderBy(findingSort(request), SPECIALIZED_ANALYSIS_FINDINGS.ID.desc())
                .limit(request.normalizedPageSize())
                .offset((request.normalizedPage() - 1) * request.normalizedPageSize())
                .fetch(this::finding);
    }

    public List<UUID> listFindingIds(UUID runId) {
        return dsl.select(SPECIALIZED_ANALYSIS_FINDINGS.ID).from(SPECIALIZED_ANALYSIS_FINDINGS)
                .where(SPECIALIZED_ANALYSIS_FINDINGS.RUN_ID.eq(runId))
                .and(SPECIALIZED_ANALYSIS_FINDINGS.FINDING_KIND.ne("DISCHARGED"))
                .orderBy(SPECIALIZED_ANALYSIS_FINDINGS.ID).fetch(SPECIALIZED_ANALYSIS_FINDINGS.ID);
    }

    public void markRunning(UUID runId, String toolVersion) {
        dsl.update(SPECIALIZED_ANALYSIS_RUNS)
                .set(SPECIALIZED_ANALYSIS_RUNS.STATUS, SpecializedAnalysisStatus.RUNNING.name())
                .set(SPECIALIZED_ANALYSIS_RUNS.TOOL_VERSION, toolVersion)
                .set(SPECIALIZED_ANALYSIS_RUNS.STARTED_AT, OffsetDateTime.now())
                .set(SPECIALIZED_ANALYSIS_RUNS.UPDATED_AT, OffsetDateTime.now())
                .where(SPECIALIZED_ANALYSIS_RUNS.ID.eq(runId)).execute();
    }

    public void complete(
            UUID runId, SpecializedAnalysisStatus status, String verdict, int exitCode,
            Map<String, Object> summary, UUID requestArtifactId, UUID resultJsonArtifactId,
            UUID resultHtmlArtifactId, UUID auditBundleArtifactId,
            List<SpecializedFindingImport> findings
    ) {
        SpecializedAnalysisRunView current = findById(runId).orElseThrow();
        dsl.deleteFrom(SPECIALIZED_ANALYSIS_FINDINGS).where(SPECIALIZED_ANALYSIS_FINDINGS.RUN_ID.eq(runId)).execute();
        for (SpecializedFindingImport item : findings) {
            dsl.insertInto(SPECIALIZED_ANALYSIS_FINDINGS)
                    .set(SPECIALIZED_ANALYSIS_FINDINGS.RUN_ID, runId)
                    .set(SPECIALIZED_ANALYSIS_FINDINGS.PROJECT_ID, current.projectId())
                    .set(SPECIALIZED_ANALYSIS_FINDINGS.SOURCE_REVISION_ID, current.sourceRevisionId())
                    .set(SPECIALIZED_ANALYSIS_FINDINGS.ANALYZER_TYPE, current.analyzerType().name())
                    .set(SPECIALIZED_ANALYSIS_FINDINGS.EXTERNAL_FINDING_ID, item.externalFindingId())
                    .set(SPECIALIZED_ANALYSIS_FINDINGS.FINDING_KIND, item.findingKind())
                    .set(SPECIALIZED_ANALYSIS_FINDINGS.FINDING_CODE, item.findingCode())
                    .set(SPECIALIZED_ANALYSIS_FINDINGS.CATEGORY, item.category())
                    .set(SPECIALIZED_ANALYSIS_FINDINGS.SEVERITY, item.severity())
                    .set(SPECIALIZED_ANALYSIS_FINDINGS.CONFIDENCE, item.confidence())
                    .set(SPECIALIZED_ANALYSIS_FINDINGS.RESULT_STATUS, item.resultStatus())
                    .set(SPECIALIZED_ANALYSIS_FINDINGS.TITLE, item.title())
                    .set(SPECIALIZED_ANALYSIS_FINDINGS.TITLE_EN, item.titleEn())
                    .set(SPECIALIZED_ANALYSIS_FINDINGS.MESSAGE, item.message())
                    .set(SPECIALIZED_ANALYSIS_FINDINGS.MESSAGE_EN, item.messageEn())
                    .set(SPECIALIZED_ANALYSIS_FINDINGS.SUGGESTION, item.suggestion())
                    .set(SPECIALIZED_ANALYSIS_FINDINGS.SUGGESTION_EN, item.suggestionEn())
                    .set(SPECIALIZED_ANALYSIS_FINDINGS.TEST_SUGGESTION, item.testSuggestion())
                    .set(SPECIALIZED_ANALYSIS_FINDINGS.TEST_SUGGESTION_EN, item.testSuggestionEn())
                    .set(SPECIALIZED_ANALYSIS_FINDINGS.FILE_PATH, item.filePath())
                    .set(SPECIALIZED_ANALYSIS_FINDINGS.START_LINE, item.startLine())
                    .set(SPECIALIZED_ANALYSIS_FINDINGS.START_COLUMN, item.startColumn())
                    .set(SPECIALIZED_ANALYSIS_FINDINGS.END_LINE, item.endLine())
                    .set(SPECIALIZED_ANALYSIS_FINDINGS.END_COLUMN, item.endColumn())
                    .set(SPECIALIZED_ANALYSIS_FINDINGS.REQUIRES_HUMAN_CONFIRMATION, item.requiresHumanConfirmation())
                    .set(SPECIALIZED_ANALYSIS_FINDINGS.SEMANTIC_DIGEST, item.semanticDigest())
                    .set(SPECIALIZED_ANALYSIS_FINDINGS.DETAIL, json(item.detail()))
                    .execute();
        }
        int candidates = (int) findings.stream().filter(item -> "SOURCE_CANDIDATE".equals(item.findingKind())).count();
        int diagnostics = number(summary, "diagnosticCount");
        dsl.update(SPECIALIZED_ANALYSIS_RUNS)
                .set(SPECIALIZED_ANALYSIS_RUNS.STATUS, status.name())
                .set(SPECIALIZED_ANALYSIS_RUNS.VERDICT, verdict)
                .set(SPECIALIZED_ANALYSIS_RUNS.EXIT_CODE, exitCode)
                .set(SPECIALIZED_ANALYSIS_RUNS.SUMMARY, json(summary))
                .set(SPECIALIZED_ANALYSIS_RUNS.REQUEST_ARTIFACT_ID, requestArtifactId)
                .set(SPECIALIZED_ANALYSIS_RUNS.RESULT_JSON_ARTIFACT_ID, resultJsonArtifactId)
                .set(SPECIALIZED_ANALYSIS_RUNS.RESULT_HTML_ARTIFACT_ID, resultHtmlArtifactId)
                .set(SPECIALIZED_ANALYSIS_RUNS.AUDIT_BUNDLE_ARTIFACT_ID, auditBundleArtifactId)
                .set(SPECIALIZED_ANALYSIS_RUNS.FINDING_COUNT, (int) findings.stream()
                        .filter(item -> "FINDING".equals(item.findingKind())).count())
                .set(SPECIALIZED_ANALYSIS_RUNS.CANDIDATE_COUNT, candidates)
                .set(SPECIALIZED_ANALYSIS_RUNS.DIAGNOSTIC_COUNT, diagnostics)
                .set(SPECIALIZED_ANALYSIS_RUNS.FINISHED_AT, OffsetDateTime.now())
                .set(SPECIALIZED_ANALYSIS_RUNS.UPDATED_AT, OffsetDateTime.now())
                .where(SPECIALIZED_ANALYSIS_RUNS.ID.eq(runId)).execute();
    }

    public void fail(UUID runId, SpecializedAnalysisStatus status, String message) {
        fail(runId, status, Map.of("error", message == null ? "专项分析失败" : message), null, null, 0);
    }

    public void fail(UUID runId, SpecializedAnalysisStatus status, Map<String, Object> summary,
            Integer exitCode, UUID resultJsonArtifactId, int diagnosticCount) {
        dsl.update(SPECIALIZED_ANALYSIS_RUNS)
                .set(SPECIALIZED_ANALYSIS_RUNS.STATUS, status.name())
                .set(SPECIALIZED_ANALYSIS_RUNS.SUMMARY, json(summary))
                .set(SPECIALIZED_ANALYSIS_RUNS.EXIT_CODE, exitCode)
                .set(SPECIALIZED_ANALYSIS_RUNS.RESULT_JSON_ARTIFACT_ID, resultJsonArtifactId)
                .set(SPECIALIZED_ANALYSIS_RUNS.DIAGNOSTIC_COUNT, diagnosticCount)
                .set(SPECIALIZED_ANALYSIS_RUNS.FINISHED_AT, OffsetDateTime.now())
                .set(SPECIALIZED_ANALYSIS_RUNS.UPDATED_AT, OffsetDateTime.now())
                .where(SPECIALIZED_ANALYSIS_RUNS.ID.eq(runId)).execute();
    }

    private Condition runFilter(UUID projectId, SpecializedAnalyzerType analyzerType, SpecializedAnalysisListRequest request) {
        Condition condition = SPECIALIZED_ANALYSIS_RUNS.PROJECT_ID.eq(projectId)
                .and(SPECIALIZED_ANALYSIS_RUNS.ANALYZER_TYPE.eq(analyzerType.name()));
        if (request.normalizedStatus() != null) condition = condition.and(SPECIALIZED_ANALYSIS_RUNS.STATUS.eq(request.normalizedStatus()));
        if (request.normalizedVerdict() != null) condition = condition.and(DSL.upper(SPECIALIZED_ANALYSIS_RUNS.VERDICT).eq(request.normalizedVerdict()));
        return condition;
    }

    private Condition findingFilter(UUID runId, SpecializedFindingListRequest request) {
        Condition condition = SPECIALIZED_ANALYSIS_FINDINGS.RUN_ID.eq(runId);
        if (request.normalizedSeverity() != null) condition = condition.and(SPECIALIZED_ANALYSIS_FINDINGS.SEVERITY.eq(request.normalizedSeverity()));
        if (request.normalizedStatus() != null) condition = condition.and(DSL.upper(SPECIALIZED_ANALYSIS_FINDINGS.RESULT_STATUS).eq(request.normalizedStatus()));
        if (text(request.category()) != null) condition = condition.and(SPECIALIZED_ANALYSIS_FINDINGS.CATEGORY.eq(request.category().trim()));
        if (text(request.code()) != null) condition = condition.and(SPECIALIZED_ANALYSIS_FINDINGS.FINDING_CODE.eq(request.code().trim()));
        if (text(request.filePath()) != null) condition = condition.and(SPECIALIZED_ANALYSIS_FINDINGS.FILE_PATH.containsIgnoreCase(request.filePath().trim()));
        if (text(request.keyword()) != null) {
            String keyword = request.keyword().trim();
            condition = condition.and(SPECIALIZED_ANALYSIS_FINDINGS.TITLE.containsIgnoreCase(keyword)
                    .or(SPECIALIZED_ANALYSIS_FINDINGS.TITLE_EN.containsIgnoreCase(keyword))
                    .or(SPECIALIZED_ANALYSIS_FINDINGS.MESSAGE.containsIgnoreCase(keyword)));
        }
        return condition;
    }

    private SortField<?> runSort(SpecializedAnalysisListRequest request) {
        Field<?> field = switch (request.normalizedSortBy()) {
            case "finishedAt" -> SPECIALIZED_ANALYSIS_RUNS.FINISHED_AT;
            case "status" -> SPECIALIZED_ANALYSIS_RUNS.STATUS;
            case "findingCount" -> SPECIALIZED_ANALYSIS_RUNS.FINDING_COUNT;
            default -> SPECIALIZED_ANALYSIS_RUNS.CREATED_AT;
        };
        return request.ascending() ? field.asc().nullsLast() : field.desc().nullsLast();
    }

    private SortField<?> findingSort(SpecializedFindingListRequest request) {
        Field<?> field = switch (request.normalizedSortBy()) {
            case "severity" -> DSL.when(SPECIALIZED_ANALYSIS_FINDINGS.SEVERITY.eq("CRITICAL"), 0)
                    .when(SPECIALIZED_ANALYSIS_FINDINGS.SEVERITY.eq("HIGH"), 1)
                    .when(SPECIALIZED_ANALYSIS_FINDINGS.SEVERITY.eq("MEDIUM"), 2)
                    .when(SPECIALIZED_ANALYSIS_FINDINGS.SEVERITY.eq("LOW"), 3).otherwise(4);
            case "filePath" -> SPECIALIZED_ANALYSIS_FINDINGS.FILE_PATH;
            case "startLine" -> SPECIALIZED_ANALYSIS_FINDINGS.START_LINE;
            case "code" -> SPECIALIZED_ANALYSIS_FINDINGS.FINDING_CODE;
            default -> SPECIALIZED_ANALYSIS_FINDINGS.CREATED_AT;
        };
        return request.ascending() ? field.asc().nullsLast() : field.desc().nullsLast();
    }

    private SpecializedAnalysisRunView run(cn.rvport.backend.jooq.tables.records.SpecializedAnalysisRunsRecord r) {
        Map<String,Object> summary = new java.util.LinkedHashMap<>(map(r.getSummary()));
        JSONB plan = r.getEffectiveInputPlan() == null ? r.getInputPlan() : r.getEffectiveInputPlan();
        if (plan != null) {
            try {
                var identity = objectMapper.readValue(plan.data(), AnalysisInputPlan.class);
                summary.put("inputOrigin", identity.inputOrigin().name());
                summary.put("inputPlan", identity.summary());
                summary.put("sourceDigest", identity.sourceDigest());
            } catch (java.io.IOException ex) { throw new IllegalStateException("ANALYSIS_PLAN_INVALID", ex); }
        }
        return new SpecializedAnalysisRunView(r.getId(), r.getProjectId(), r.getTaskId(),
                SpecializedAnalyzerType.valueOf(r.getAnalyzerType()), r.getSourceRevisionId(), r.getBuildRunId(),
                r.getArtifactSetId(), legacyMode(r), legacyStatus(r), value(r.getBcPlannedCount()),
                value(r.getBcUsableCount()), value(r.getBcFailedCount()), r.getBcDegradationReason(),
                r.getEnvironmentConfigVersion(), r.getToolVersion(), r.getRequestSchemaVersion(),
                r.getResultSchemaVersion(), r.getPolicyId(), SpecializedAnalysisStatus.valueOf(r.getStatus()),
                r.getVerdict(), r.getExitCode(), summary, r.getRequestArtifactId(),
                r.getResultJsonArtifactId(), r.getResultHtmlArtifactId(), r.getAuditBundleArtifactId(),
                r.getFindingCount(), r.getCandidateCount(), r.getDiagnosticCount(), r.getCreatedBy(), r.getStartedAt(),
                r.getFinishedAt(), r.getCreatedAt(), r.getUpdatedAt());
    }

    private SpecializedAnalysisMode legacyMode(cn.rvport.backend.jooq.tables.records.SpecializedAnalysisRunsRecord r) {
        if (r.getAnalysisMode() != null) return SpecializedAnalysisMode.valueOf(r.getAnalysisMode());
        return r.getArtifactSetId() == null ? SpecializedAnalysisMode.AST_ONLY : SpecializedAnalysisMode.AST_BC_MIXED;
    }

    private String legacyStatus(cn.rvport.backend.jooq.tables.records.SpecializedAnalysisRunsRecord r) {
        if (r.getBcStatus() != null) return r.getBcStatus();
        return r.getArtifactSetId() == null ? "UNKNOWN" : "LEGACY_UNKNOWN";
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }

    private SpecializedAnalysisFindingView finding(cn.rvport.backend.jooq.tables.records.SpecializedAnalysisFindingsRecord r) {
        return new SpecializedAnalysisFindingView(r.getId(), r.getRunId(), r.getProjectId(), r.getSourceRevisionId(),
                SpecializedAnalyzerType.valueOf(r.getAnalyzerType()), r.getExternalFindingId(), r.getFindingKind(),
                r.getFindingCode(), r.getCategory(), r.getSeverity(), r.getConfidence(), r.getResultStatus(), r.getTitle(),
                r.getTitleEn(), r.getMessage(), r.getMessageEn(), r.getSuggestion(), r.getSuggestionEn(),
                r.getTestSuggestion(), r.getTestSuggestionEn(), r.getFilePath(), r.getStartLine(), r.getStartColumn(),
                r.getEndLine(), r.getEndColumn(), Boolean.TRUE.equals(r.getRequiresHumanConfirmation()),
                r.getSemanticDigest(), map(r.getDetail()), r.getCreatedAt());
    }

    private JSONB json(Map<String, ?> value) {
        try { return JSONB.valueOf(objectMapper.writeValueAsString(value == null ? Map.of() : value)); }
        catch (Exception ex) { throw new IllegalArgumentException("专项分析 JSON 无法序列化", ex); }
    }

    private Map<String, Object> map(JSONB value) {
        if (value == null) return Map.of();
        try { return objectMapper.readValue(value.data(), MAP); }
        catch (Exception ex) { return Map.of(); }
    }

    private static int number(Map<String, Object> value, String key) {
        Object found = value == null ? null : value.get(key);
        return found instanceof Number number ? number.intValue() : 0;
    }

    private static String text(String value) { return value == null || value.isBlank() ? null : value; }
}
