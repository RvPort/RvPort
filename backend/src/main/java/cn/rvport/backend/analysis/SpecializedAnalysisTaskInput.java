package cn.rvport.backend.analysis;

import java.util.UUID;

public record SpecializedAnalysisTaskInput(
        UUID projectId,
        UUID runId,
        UUID sourceRevisionId,
        UUID buildRunId,
        UUID compileDatabaseArtifactId,
        UUID bitcodeManifestArtifactId,
        UUID bitcodeBundleArtifactId,
        String artifactSetId,
        SpecializedAnalysisMode analysisMode,
        String bcStatus,
        int bcPlannedCount,
        int bcUsableCount,
        int bcFailedCount,
        String bcDegradationReason,
        Long environmentConfigVersion,
        SpecializedAnalyzerType analyzerType,
        AnalysisInputPlan inputPlan
) {
    public SpecializedAnalysisTaskInput(UUID projectId, UUID runId, UUID sourceRevisionId, UUID buildRunId,
            UUID compileDatabaseArtifactId, UUID bitcodeManifestArtifactId, UUID bitcodeBundleArtifactId,
            String artifactSetId, SpecializedAnalysisMode analysisMode, String bcStatus, int bcPlannedCount,
            int bcUsableCount, int bcFailedCount, String bcDegradationReason, Long environmentConfigVersion,
            SpecializedAnalyzerType analyzerType) {
        this(projectId, runId, sourceRevisionId, buildRunId, compileDatabaseArtifactId, bitcodeManifestArtifactId,
                bitcodeBundleArtifactId, artifactSetId, analysisMode, bcStatus, bcPlannedCount, bcUsableCount,
                bcFailedCount, bcDegradationReason, environmentConfigVersion, analyzerType, null);
    }
    public SpecializedAnalysisTaskInput(
            UUID projectId, UUID runId, UUID sourceRevisionId, UUID buildRunId,
            UUID compileDatabaseArtifactId, UUID bitcodeManifestArtifactId, UUID bitcodeBundleArtifactId,
            String artifactSetId, Long environmentConfigVersion, SpecializedAnalyzerType analyzerType
    ) {
        this(projectId, runId, sourceRevisionId, buildRunId, compileDatabaseArtifactId,
                bitcodeManifestArtifactId, bitcodeBundleArtifactId, artifactSetId,
                artifactSetId == null ? SpecializedAnalysisMode.AST_ONLY : SpecializedAnalysisMode.AST_BC_COMPLETE,
                artifactSetId == null ? "MISSING" : "READY", artifactSetId == null ? 0 : 1,
                artifactSetId == null ? 0 : 1, 0, artifactSetId == null ? "BITCODE_ARTIFACTS_MISSING" : null,
                environmentConfigVersion, analyzerType);
    }
}
