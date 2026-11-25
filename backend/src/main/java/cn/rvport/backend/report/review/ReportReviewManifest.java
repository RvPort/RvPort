package cn.rvport.backend.report.review;

import java.util.List;
import java.util.UUID;

public record ReportReviewManifest(UUID projectId,UUID sourceRevisionId,String contextHash,ReportMode mode,
        ReportInputSelectionPolicy.Selection selection,ReportSourceSnapshotService.Manifest source,
        List<ReviewItemSnapshot> items,UUID baseReportId,Long baseReviewRevision,
        java.util.Map<String,Object> sourceMetadata,java.util.Map<String,Object> displayEvidence) {
    public ReportReviewManifest(UUID projectId,UUID sourceRevisionId,String contextHash,ReportMode mode,
            ReportInputSelectionPolicy.Selection selection,ReportSourceSnapshotService.Manifest source,
            List<ReviewItemSnapshot> items,UUID baseReportId,Long baseReviewRevision) {
        this(projectId,sourceRevisionId,contextHash,mode,selection,source,items,baseReportId,baseReviewRevision,null,null);
    }
}
