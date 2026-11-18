package cn.rvport.backend.task;

import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class TaskMetadataResolver {

    public TaskCreateCommand resolve(TaskCreateCommand command) {
        if (hasMetadata(command)) {
            return command;
        }
        Map<String, ?> input = command.inputSnapshot() == null ? Map.of() : command.inputSnapshot();
        return new TaskCreateCommand(
                command.projectId(),
                command.type(),
                command.inputSnapshot(),
                command.createdBy(),
                command.timeoutSeconds(),
                command.message(),
                stageId(command.type(), input),
                resourceType(command.type()),
                resourceId(command.type(), input),
                resourceMode(command.type(), input),
                environmentRole(input)
        );
    }

    private boolean hasMetadata(TaskCreateCommand command) {
        return notBlank(command.stageId())
                || notBlank(command.resourceType())
                || command.resourceId() != null
                || notBlank(command.resourceMode())
                || notBlank(command.environmentRole());
    }

    private String stageId(String taskType, Map<String, ?> input) {
        if (RvPortTaskTypes.SOURCE_GIT_SYNC.equals(taskType) || RvPortTaskTypes.SOURCE_DEPLOY.equals(taskType)) {
            return "source";
        }
        if (RvPortTaskTypes.ENV_TEST_CONNECTION.equals(taskType)
                || RvPortTaskTypes.ENV_COLLECT.equals(taskType)
                || RvPortTaskTypes.ENV_RUN_SCRIPT.equals(taskType)) {
            return "environment";
        }
        if (RvPortTaskTypes.BUILD_VALIDATION.equals(taskType)) {
            return "SOURCE_ONLY".equals(stringValue(input.get("buildScope"))) ? "x86-build" : "build-validation";
        }
        if (RvPortTaskTypes.DEPENDENCY_ANALYSIS.equals(taskType)
                || RvPortTaskTypes.DEPENDENCY_AUTO_FIX.equals(taskType)) {
            return "dependency-analysis";
        }
        if (RvPortTaskTypes.KB_SCAN.equals(taskType)) {
            return "kb-scan";
        }
        if (RvPortTaskTypes.FLOATING_POINT_ANALYSIS.equals(taskType)) {
            return "floating-point-analysis";
        }
        if (RvPortTaskTypes.MEMORY_ORDER_ANALYSIS.equals(taskType)) {
            return "memory-order-analysis";
        }
        if (RvPortTaskTypes.REPORT_GENERATE.equals(taskType)
                || RvPortTaskTypes.REPORT_COMPARE.equals(taskType)
                || RvPortTaskTypes.RECHECK_DIFF.equals(taskType)) {
            return "evaluation-report";
        }
        if (RvPortTaskTypes.AUTOMATED_TEST.equals(taskType) || RvPortTaskTypes.TEST_LOG_COMPARE.equals(taskType)) {
            return "automated-test";
        }
        if (RvPortTaskTypes.ARCHIVE_PROJECT.equals(taskType)) {
            return "archive";
        }
        return "task";
    }

    private String resourceType(String taskType) {
        return switch (taskType) {
            case RvPortTaskTypes.SOURCE_GIT_SYNC -> "source_import";
            case RvPortTaskTypes.SOURCE_DEPLOY -> "source_deploy_run";
            case RvPortTaskTypes.ENV_TEST_CONNECTION, RvPortTaskTypes.ENV_RUN_SCRIPT -> "environment";
            case RvPortTaskTypes.ENV_COLLECT -> "environment_snapshot";
            case RvPortTaskTypes.BUILD_VALIDATION -> "build_run";
            case RvPortTaskTypes.DEPENDENCY_ANALYSIS -> "dependency_analysis_run";
            case RvPortTaskTypes.DEPENDENCY_AUTO_FIX -> "dependency_auto_fix_run";
            case RvPortTaskTypes.KB_SCAN -> "kb_scan_run";
            case RvPortTaskTypes.FLOATING_POINT_ANALYSIS, RvPortTaskTypes.MEMORY_ORDER_ANALYSIS -> "specialized_analysis_run";
            case RvPortTaskTypes.REPORT_GENERATE -> "report";
            case RvPortTaskTypes.REPORT_COMPARE -> "report_comparison_run";
            case RvPortTaskTypes.RECHECK_DIFF -> "recheck_run";
            case RvPortTaskTypes.AUTOMATED_TEST -> "test_run";
            case RvPortTaskTypes.TEST_LOG_COMPARE -> "test_comparison_run";
            case RvPortTaskTypes.ARCHIVE_PROJECT -> "project_archive";
            default -> taskType;
        };
    }

    private UUID resourceId(String taskType, Map<String, ?> input) {
        return switch (taskType) {
            case RvPortTaskTypes.SOURCE_GIT_SYNC -> uuid(input.get("sourceImportId"));
            case RvPortTaskTypes.SOURCE_DEPLOY -> uuid(input.get("sourceDeploymentId"));
            case RvPortTaskTypes.ENV_TEST_CONNECTION, RvPortTaskTypes.ENV_RUN_SCRIPT -> uuid(input.get("environmentId"));
            case RvPortTaskTypes.ENV_COLLECT -> uuid(input.get("environmentSnapshotId"), input.get("environmentId"));
            case RvPortTaskTypes.BUILD_VALIDATION -> uuid(input.get("buildRunId"));
            case RvPortTaskTypes.DEPENDENCY_ANALYSIS -> uuid(input.get("analysisRunId"));
            case RvPortTaskTypes.DEPENDENCY_AUTO_FIX -> uuid(input.get("autoFixRunId"));
            case RvPortTaskTypes.KB_SCAN -> uuid(input.get("scanRunId"));
            case RvPortTaskTypes.FLOATING_POINT_ANALYSIS, RvPortTaskTypes.MEMORY_ORDER_ANALYSIS -> uuid(input.get("runId"));
            case RvPortTaskTypes.REPORT_GENERATE -> uuid(input.get("reportId"));
            case RvPortTaskTypes.REPORT_COMPARE -> uuid(input.get("comparisonRunId"));
            case RvPortTaskTypes.RECHECK_DIFF -> uuid(input.get("recheckRunId"));
            case RvPortTaskTypes.AUTOMATED_TEST -> uuid(input.get("testRunId"), input.get("testBatchId"));
            case RvPortTaskTypes.TEST_LOG_COMPARE -> uuid(input.get("comparisonRunId"));
            default -> null;
        };
    }

    private String resourceMode(String taskType, Map<String, ?> input) {
        if (RvPortTaskTypes.BUILD_VALIDATION.equals(taskType)) {
            return stringValue(input.get("buildScope"));
        }
        if (RvPortTaskTypes.ENV_RUN_SCRIPT.equals(taskType)) {
            return stringValue(input.get("scriptType"));
        }
        if (RvPortTaskTypes.AUTOMATED_TEST.equals(taskType)) {
            return stringValue(input.get("testBatchRole"));
        }
        return null;
    }

    private String environmentRole(Map<String, ?> input) {
        String role = stringValue(input.get("environmentRole"));
        if (notBlank(role)) {
            return role;
        }
        return stringValue(input.get("role"));
    }

    private UUID uuid(Object first, Object... others) {
        UUID value = uuid(first);
        if (value != null) {
            return value;
        }
        for (Object other : others) {
            value = uuid(other);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private UUID uuid(Object value) {
        if (value instanceof UUID id) {
            return id;
        }
        String text = stringValue(value);
        if (!notBlank(text)) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
