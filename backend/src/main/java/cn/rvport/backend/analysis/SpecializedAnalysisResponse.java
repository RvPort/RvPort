package cn.rvport.backend.analysis;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SpecializedAnalysisResponse(
        UUID id,
        UUID projectId,
        UUID taskId,
        SpecializedAnalyzerType analyzerType,
        UUID sourceRevisionId,
        UUID buildRunId,
        String artifactSetId,
        SpecializedAnalysisMode analysisMode,
        String bcStatus,
        int bcPlannedCount,
        int bcUsableCount,
        int bcFailedCount,
        int astOnlyCount,
        String bcDegradationReason,
        Long environmentConfigVersion,
        String toolVersion,
        SpecializedAnalysisStatus status,
        String verdict,
        Integer exitCode,
        Map<String, Object> summary,
        int findingCount,
        int candidateCount,
        Integer diagnosticCount,
        boolean currentSourceRevision,
        List<String> staleReasons,
        UUID requestArtifactId,
        UUID resultJsonArtifactId,
        UUID resultHtmlArtifactId,
        UUID auditBundleArtifactId,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        OffsetDateTime createdAt
) {
    public static SpecializedAnalysisResponse from(SpecializedAnalysisRunView run, UUID activeSourceRevisionId) {
        return from(run, activeSourceRevisionId, List.of(), false);
    }

    public static SpecializedAnalysisResponse from(SpecializedAnalysisRunView run, UUID activeSourceRevisionId,
            UUID currentBuild, Long environmentVersion, boolean enhancementAvailable) {
        return from(run, activeSourceRevisionId, currentBuild, environmentVersion, enhancementAvailable, true);
    }

    public static SpecializedAnalysisResponse from(SpecializedAnalysisRunView run, UUID activeSourceRevisionId,
            UUID currentBuild, Long environmentVersion, boolean enhancementAvailable, boolean profileCurrent) {
        boolean sourceOnly = "SOURCE_DISCOVERY".equals(run.summary().get("inputOrigin"));
        List<String> reasons = new java.util.ArrayList<>();
        if (!profileCurrent) reasons.add("ANALYSIS_PROFILE_CHANGED");
        if (!sourceOnly) {
            if (!java.util.Objects.equals(run.buildRunId(), currentBuild) || currentBuild == null)
                reasons.add("ANALYSIS_BUILD_CHANGED");
            if (!java.util.Objects.equals(run.environmentConfigVersion(), environmentVersion))
                reasons.add("ANALYSIS_ENVIRONMENT_CHANGED");
        }
        return from(run, activeSourceRevisionId, reasons, enhancementAvailable && profileCurrent);
    }

    private static SpecializedAnalysisResponse from(SpecializedAnalysisRunView run, UUID activeSourceRevisionId,
            List<String> contextReasons, boolean enhancementAvailable) {
        boolean current = java.util.Objects.equals(run.sourceRevisionId(), activeSourceRevisionId);
        Map<String, Object> summary = new java.util.LinkedHashMap<>(run.analyzerType() == SpecializedAnalyzerType.MEMORY_ORDER
                ? MemoryOrderResultPolicy.historicalSummary(run.summary()) : run.summary());
        summary.put("enhancementAvailable", current && enhancementAvailable);
        List<String> reasons = new java.util.ArrayList<>(contextReasons);
        if (!current) reasons.addFirst("SOURCE_REVISION_CHANGED");
        Integer diagnosticCount = run.analyzerType() == SpecializedAnalyzerType.MEMORY_ORDER
                && !Boolean.TRUE.equals(summary.get("diagnosticsAggregated")) ? null : run.diagnosticCount();
        return new SpecializedAnalysisResponse(run.id(), run.projectId(), run.taskId(), run.analyzerType(),
                run.sourceRevisionId(), run.buildRunId(), run.artifactSetId(), run.analysisMode(), run.bcStatus(),
                run.bcPlannedCount(), run.bcUsableCount(), run.bcFailedCount(),
                Math.max(0, run.bcPlannedCount() - run.bcUsableCount()), run.bcDegradationReason(),
                run.environmentConfigVersion(),
                run.toolVersion(), run.status(), run.verdict(), run.exitCode(), summary, run.findingCount(),
                run.candidateCount(), diagnosticCount, current,
                List.copyOf(reasons), run.requestArtifactId(),
                run.resultJsonArtifactId(), run.resultHtmlArtifactId(), run.auditBundleArtifactId(), run.startedAt(),
                run.finishedAt(), run.createdAt());
    }

    @com.fasterxml.jackson.annotation.JsonProperty public boolean resultAvailable() {
        return resultJsonArtifactId != null && List.of(SpecializedAnalysisStatus.COMPLETE,
                SpecializedAnalysisStatus.COMPLETE_WITH_RISK, SpecializedAnalysisStatus.PARTIAL).contains(status);
    }
    @com.fasterxml.jackson.annotation.JsonProperty public boolean analysisComplete() {
        return resultAvailable() && Boolean.TRUE.equals(summary.get("analysisComplete"));
    }
    @com.fasterxml.jackson.annotation.JsonProperty public Object inputOrigin() { return summary.get("inputOrigin"); }
    @com.fasterxml.jackson.annotation.JsonProperty public boolean enhancementAvailable() {
        return Boolean.TRUE.equals(summary.get("enhancementAvailable"));
    }
    @com.fasterxml.jackson.annotation.JsonProperty public Map<String,Object> resultApplicability() {
        List<String> reasons = new java.util.ArrayList<>(staleReasons);
        if (!resultAvailable()) reasons.add("ANALYSIS_RESULT_UNAVAILABLE");
        return Map.of("applicable", reasons.isEmpty(), "reasons", List.copyOf(reasons));
    }
}
