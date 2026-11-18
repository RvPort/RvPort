package cn.rvport.backend.report;

import java.util.UUID;

public record ReportGenerateRequest(UUID sourceRevisionId,cn.rvport.backend.report.review.ReportMode mode,
        String selectionToken,UUID baseReportId,Long expectedDraftVersion) {
    /** 阶段绑定和执行身份只能由后端决定，未声明字段也不能作为覆盖参数。 */
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("REPORT_REQUEST_INVALID");
    }

    public ReportGenerateRequest(UUID sourceRevisionId) { this(sourceRevisionId,null,null,null,null); }
}
