package cn.rvport.backend.task;

public final class TaskErrorCode {

    public static final String HANDLER_NOT_FOUND = "TASK_HANDLER_NOT_FOUND";
    public static final String RUNNING_CANCEL_NOT_SUPPORTED = "RUNNING_CANCEL_NOT_SUPPORTED";
    public static final String HANDLER_FAILED = "TASK_HANDLER_FAILED";
    public static final String INPUT_INVALID = "TASK_INPUT_INVALID";
    public static final String PROGRESS_INVALID = "TASK_PROGRESS_INVALID";
    public static final String LOG_WRITE_FAILED = "TASK_LOG_WRITE_FAILED";
    public static final String CREDENTIAL_INVALID = "TASK_CREDENTIAL_INVALID";
    public static final String CREDENTIAL_NOT_FOUND = "TASK_CREDENTIAL_NOT_FOUND";
    public static final String CREDENTIAL_DECRYPT_FAILED = "TASK_CREDENTIAL_DECRYPT_FAILED";
    public static final String RESOURCE_NOT_FOUND = "TASK_RESOURCE_NOT_FOUND";
    public static final String PROJECT_MISMATCH = "TASK_PROJECT_MISMATCH";
    public static final String RESOURCE_DISABLED = "TASK_RESOURCE_DISABLED";
    public static final String RESOURCE_STALE = "TASK_RESOURCE_STALE";
    public static final String UNSUPPORTED_OPERATION = "TASK_UNSUPPORTED_OPERATION";
    public static final String REMOTE_COMMAND_FAILED = "TASK_REMOTE_COMMAND_FAILED";
    public static final String REPORT_NO_CONFIRMED_INPUTS = "REPORT_NO_CONFIRMED_INPUTS";
    public static final String WORKER_LOST = "WORKER_LOST";
    public static final String ANALYSIS_NON_RETRYABLE = "ANALYSIS_NON_RETRYABLE";
    public static final String ANALYSIS_RETRYABLE_RESOURCE = "ANALYSIS_RETRYABLE_RESOURCE";
    public static final String KB_MIGRATION_MODEL_NOT_CONFIGURED = "KB_MIGRATION_MODEL_NOT_CONFIGURED";
    public static final String KB_MIGRATION_NO_ENTRIES = "KB_MIGRATION_NO_ENTRIES";
    public static final String KB_MIGRATION_OUTPUT_INVALID = "KB_MIGRATION_OUTPUT_INVALID";

    private TaskErrorCode() {
    }
}
