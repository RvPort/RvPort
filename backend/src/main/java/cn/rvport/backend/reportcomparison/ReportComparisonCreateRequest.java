package cn.rvport.backend.reportcomparison;

import java.util.UUID;

public record ReportComparisonCreateRequest(UUID baseReportId,ReportComparisonBasis basis,UUID baseConfirmationId,UUID targetConfirmationId) {
    public ReportComparisonCreateRequest(UUID baseReportId) { this(baseReportId,ReportComparisonBasis.REPORT_FACTS,null,null); }
}
