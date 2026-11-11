package cn.rvport.backend.automatedtest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AutomatedTestInput(
        UUID projectId,
        UUID testRunId,
        UUID buildRunId,
        UUID sourceRevisionId,
        UUID environmentId,
        Long environmentConfigVersion,
        UUID testBatchId,
        TestBatchRole testBatchRole,
        String command,
        String workingDirectory,
        Integer timeoutSeconds,
        Map<String, String> environmentVariables,
        List<AutomatedTestReportTarget> reportTargets,
        UUID configurationUserId
) {
}
