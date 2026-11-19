package cn.rvport.backend.reportcomparison;

import java.util.UUID;

public record ReportComparisonTaskInput(
        UUID projectId,
        UUID comparisonRunId,
        UUID baseReportId,
        UUID targetReportId,ReportComparisonBasis basis,UUID baseConfirmationId,UUID targetConfirmationId
) {
    public ReportComparisonTaskInput(UUID project,UUID run,UUID base,UUID target) { this(project,run,base,target,ReportComparisonBasis.REPORT_FACTS,null,null); }
}
