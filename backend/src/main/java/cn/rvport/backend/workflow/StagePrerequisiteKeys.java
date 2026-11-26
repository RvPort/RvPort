package cn.rvport.backend.workflow;

public final class StagePrerequisiteKeys {

    public static final String ACTIVE_SOURCE = "ACTIVE_SOURCE";
    public static final String SOURCE_ENVIRONMENT = "SOURCE_ENVIRONMENT";
    public static final String TARGET_ENVIRONMENT = "TARGET_ENVIRONMENT";
    public static final String SOURCE_DEPLOYMENT = "SOURCE_DEPLOYMENT";
    public static final String X86_BUILD = "X86_BUILD";
    public static final String KB_SCAN = "KB_SCAN";
    public static final String DEPENDENCY_ANALYSIS = "DEPENDENCY_ANALYSIS";
    public static final String INCLUDED_FINDINGS = "INCLUDED_FINDINGS";
    public static final String REPORT = "REPORT";
    public static final String COMPARABLE_REPORT = "COMPARABLE_REPORT";
    public static final String BUILD_VALIDATION = "BUILD_VALIDATION";
    public static final String ARCHIVE_CANDIDATES = "ARCHIVE_CANDIDATES";
    public static final String SPECIALIZED_ANALYZER = "SPECIALIZED_ANALYZER";
    public static final String SPECIALIZED_ANALYSIS_EVIDENCE = "SPECIALIZED_ANALYSIS_EVIDENCE";

    private StagePrerequisiteKeys() {
    }
}
