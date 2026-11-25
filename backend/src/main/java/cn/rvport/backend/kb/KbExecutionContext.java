package cn.rvport.backend.kb;

import java.util.UUID;

/** 固定实际使用的输入；只有显式计划才允许无构建，不猜测旧记录。 */
public record KbExecutionContext(UUID sourceEnvironmentId, Long runtimeEnvironmentVersion, UUID buildRunId,
        cn.rvport.backend.analysis.AnalysisInputPlan inputPlan) {
    public KbExecutionContext(UUID sourceEnvironmentId, Long runtimeEnvironmentVersion, UUID buildRunId) {
        this(sourceEnvironmentId, runtimeEnvironmentVersion, buildRunId, null);
    }
    public boolean sourceOnly() {
        return inputPlan != null && inputPlan.inputOrigin() == cn.rvport.backend.analysis.AnalysisInputPlan.InputOrigin.SOURCE_DISCOVERY;
    }
    public boolean complete() {
        if (sourceOnly()) return sourceEnvironmentId == null && runtimeEnvironmentVersion == null && buildRunId == null;
        return sourceEnvironmentId != null && runtimeEnvironmentVersion != null
                && runtimeEnvironmentVersion >= 0 && buildRunId != null;
    }
}
