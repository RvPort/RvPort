package cn.rvport.backend.worker;

import cn.rvport.backend.task.TaskErrorCode;
import java.util.Set;

public class TaskRetryPolicy {

    private static final Set<String> NON_RETRYABLE_CODES = Set.of(
            TaskErrorCode.HANDLER_NOT_FOUND,
            TaskErrorCode.INPUT_INVALID,
            TaskErrorCode.PROGRESS_INVALID,
            TaskErrorCode.CREDENTIAL_INVALID,
            TaskErrorCode.CREDENTIAL_NOT_FOUND,
            TaskErrorCode.CREDENTIAL_DECRYPT_FAILED,
            TaskErrorCode.RESOURCE_NOT_FOUND,
            TaskErrorCode.PROJECT_MISMATCH,
            TaskErrorCode.RESOURCE_DISABLED,
            TaskErrorCode.UNSUPPORTED_OPERATION,
            TaskErrorCode.REMOTE_COMMAND_FAILED,
            TaskErrorCode.REPORT_NO_CONFIRMED_INPUTS,
            TaskErrorCode.ANALYSIS_NON_RETRYABLE,
            TaskErrorCode.AI_MIGRATION_MODEL_NOT_CONFIGURED,
            TaskErrorCode.AI_MIGRATION_NO_ENTRIES,
            TaskErrorCode.AI_MIGRATION_OUTPUT_INVALID
    );

    public boolean shouldRetry(String errorCode) {
        return errorCode == null || !NON_RETRYABLE_CODES.contains(errorCode);
    }
}
