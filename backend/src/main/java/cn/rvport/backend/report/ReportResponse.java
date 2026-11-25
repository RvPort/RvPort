package cn.rvport.backend.report;

import cn.rvport.backend.reportcomparison.ReportComparisonSummaryResponse;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ReportResponse(
        UUID id,
        UUID projectId,
        UUID taskId,
        UUID sourceRevisionId,
        ReportType reportType,
        cn.rvport.backend.task.TaskStatus status,
        String title,
        Map<String, Object> summary,
        String riskLevel,
        List<ReportArtifactSummaryResponse> artifacts,
        LatestComparableReportResponse latestComparableReport,
        ReportComparisonSummaryResponse latestComparison,
        Map<String,Object> review,
        boolean organizationAvailable,
        String organizationSchemaVersion,
        OffsetDateTime generatedAt,
        OffsetDateTime createdAt,
        String sourceVersionLabel,
        String sourceGitCommit,
        String sourceMetadataOrigin
) {
    @com.fasterxml.jackson.annotation.JsonProperty
    public boolean reviewAvailable() { return review!=null; }
    @com.fasterxml.jackson.annotation.JsonProperty
    public String reviewUnavailableReasonCode() { return review==null?"REPORT_REVIEW_UNAVAILABLE":null; }
    @com.fasterxml.jackson.annotation.JsonProperty
    public String reviewUnavailableMessage() { return review==null?"该报告缺少可用的复核数据":null; }
    @com.fasterxml.jackson.annotation.JsonProperty
    public String reviewUnavailableMessageEn() { return review==null?"Review data is unavailable for this report":null; }
    static ReportResponse from(
            ReportView view,
            List<ReportArtifactSummaryResponse> artifacts,
            LatestComparableReportResponse latestComparableReport,
            ReportComparisonSummaryResponse latestComparison,
            Map<String,Object> review
    ) {
        return from(view,artifacts,latestComparableReport,latestComparison,review,null);
    }
    static ReportResponse from(ReportView view,List<ReportArtifactSummaryResponse> artifacts,
            LatestComparableReportResponse latestComparableReport,ReportComparisonSummaryResponse latestComparison,
            Map<String,Object> review,Map<String,Object> fallback) {
        var metadata=SourceVersionMetadata.resolve(view,fallback);
        return new ReportResponse(
                view.id(),
                view.projectId(),
                view.taskId(),
                view.sourceRevisionId(),
                view.reportType(),
                view.status(),
                view.title(),
                view.summary(),
                view.riskLevel(),
                artifacts == null ? List.of() : artifacts,
                latestComparableReport,
                latestComparison,
                review,
                Boolean.TRUE.equals(view.summary().get("organizationAvailable")),
                view.summary().get("schemaVersion") == null ? null : String.valueOf(view.summary().get("schemaVersion")),
                view.generatedAt(),
                view.createdAt(),
                (String)metadata.get("sourceVersionLabel"),
                (String)metadata.get("sourceGitCommit"),
                (String)metadata.get("sourceMetadataOrigin")
        );
    }
}
