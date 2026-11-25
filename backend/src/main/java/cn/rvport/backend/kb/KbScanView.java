package cn.rvport.backend.kb;

import cn.rvport.backend.jooq.tables.records.KbScanRunsRecord;
import cn.rvport.backend.task.TaskStatus;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record KbScanView(
        UUID id,
        UUID projectId,
        UUID taskId,
        UUID sourceRevisionId,
        String kbVersion,
        Map<String, Object> scope,
        TaskStatus status,
        Map<String, Object> summary,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        KbExecutionContext executionContext,
        cn.rvport.backend.analysis.AnalysisInputPlan effectiveInputPlan
) {
    public KbScanView(UUID id, UUID projectId, UUID taskId, UUID sourceRevisionId, String kbVersion,
            Map<String, Object> scope, TaskStatus status, Map<String, Object> summary,
            OffsetDateTime startedAt, OffsetDateTime finishedAt, KbExecutionContext executionContext) {
        this(id,projectId,taskId,sourceRevisionId,kbVersion,scope,status,summary,startedAt,finishedAt,executionContext,null);
    }
    public boolean sourceOnly() {
        return effectiveInputPlan == null ? executionContext != null && executionContext.sourceOnly()
                : effectiveInputPlan.inputOrigin() == cn.rvport.backend.analysis.AnalysisInputPlan.InputOrigin.SOURCE_DISCOVERY;
    }
    public KbScanView(UUID id, UUID projectId, UUID taskId, UUID sourceRevisionId, String kbVersion,
            Map<String, Object> scope, TaskStatus status, Map<String, Object> summary,
            OffsetDateTime startedAt, OffsetDateTime finishedAt) {
        this(id, projectId, taskId, sourceRevisionId, kbVersion, scope, status, summary, startedAt, finishedAt, null);
    }
    static KbScanView from(KbScanRunsRecord record, Map<String, Object> scope, Map<String, Object> summary) {
        return new KbScanView(
                record.getId(),
                record.getProjectId(),
                record.getTaskId(),
                record.getSourceRevisionId(),
                record.getKbVersion(),
                scope,
                TaskStatus.valueOf(record.getStatus()),
                summary,
                record.getStartedAt(),
                record.getFinishedAt(),
                new KbExecutionContext(record.getSourceEnvironmentId(), record.getRuntimeEnvironmentVersion(), record.getBuildRunId(), plan(record.getInputPlan())),
                plan(record.getEffectiveInputPlan())
        );
    }

    private static cn.rvport.backend.analysis.AnalysisInputPlan plan(org.jooq.JSONB value) {
        if (value == null) return null;
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readValue(value.data(),
                cn.rvport.backend.analysis.AnalysisInputPlan.class); }
        catch (java.io.IOException ex) { throw new IllegalArgumentException("ANALYSIS_PLAN_INVALID", ex); }
    }
}
