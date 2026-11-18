package cn.rvport.backend.kb;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record KbScanInput(
        UUID projectId,
        UUID scanRunId,
        UUID sourceRevisionId,
        String ruleVersion,
        String language,
        String sourceArch,
        String targetArch,
        Map<String, Object> scanScope,
        List<String> excludePaths,
        KbExecutionContext executionContext
) {
    public KbScanInput(UUID projectId, UUID scanRunId, UUID sourceRevisionId, String ruleVersion,
            String language, String targetArch, Map<String, Object> scanScope, List<String> excludePaths) {
        this(projectId, scanRunId, sourceRevisionId, ruleVersion, language, null, targetArch, scanScope, excludePaths, null);
    }

    public KbScanInput(UUID projectId, UUID scanRunId, UUID sourceRevisionId, String ruleVersion,
            String language, String targetArch, Map<String, Object> scanScope, List<String> excludePaths,
            KbExecutionContext executionContext) {
        this(projectId, scanRunId, sourceRevisionId, ruleVersion, language, null, targetArch, scanScope, excludePaths, executionContext);
    }

    public KbScanInput(UUID projectId, UUID scanRunId, UUID sourceRevisionId, String ruleVersion,
            String language, String sourceArch, String targetArch, Map<String, Object> scanScope, List<String> excludePaths) {
        this(projectId, scanRunId, sourceRevisionId, ruleVersion, language, sourceArch, targetArch, scanScope, excludePaths, null);
    }
}
