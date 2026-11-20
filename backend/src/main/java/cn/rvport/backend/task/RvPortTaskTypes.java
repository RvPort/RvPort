package cn.rvport.backend.task;

public final class RvPortTaskTypes {

    public static final String SOURCE_GIT_SYNC = "SOURCE_GIT_SYNC";
    public static final String SOURCE_DEPLOY = "SOURCE_DEPLOY";
    public static final String ENV_TEST_CONNECTION = "ENV_TEST_CONNECTION";
    public static final String ENV_COLLECT = "ENV_COLLECT";
    public static final String ENV_RUN_SCRIPT = "ENV_RUN_SCRIPT";
    public static final String DEPENDENCY_ANALYSIS = "DEPENDENCY_ANALYSIS";
    public static final String DEPENDENCY_AUTO_FIX = "DEPENDENCY_AUTO_FIX";
    public static final String KB_SCAN = "KB_SCAN";
    public static final String AI_MIGRATION = "AI_MIGRATION";
    public static final String REPORT_GENERATE = "REPORT_GENERATE";
    public static final String REPORT_COMPARE = "REPORT_COMPARE";
    public static final String RECHECK_DIFF = "RECHECK_DIFF";
    public static final String BUILD_VALIDATION = "BUILD_VALIDATION";
    public static final String AUTOMATED_TEST = "AUTOMATED_TEST";
    public static final String TEST_LOG_COMPARE = "TEST_LOG_COMPARE";
    public static final String ARCHIVE_PROJECT = "ARCHIVE_PROJECT";
    public static final String FLOATING_POINT_ANALYSIS = "FLOATING_POINT_ANALYSIS";
    public static final String MEMORY_ORDER_ANALYSIS = "MEMORY_ORDER_ANALYSIS";

    private RvPortTaskTypes() {
    }
}
