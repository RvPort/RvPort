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
        OffsetDateTime createdAt
) {
    static ReportResponse from(
            ReportView view,
            List<ReportArtifactSummaryResponse> artifacts,
            LatestComparableReportResponse latestComparableReport,
            ReportComparisonSummaryResponse latestComparison,
            Map<String,Object> review
    ) {
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
                view.createdAt()
        );
    }
}
