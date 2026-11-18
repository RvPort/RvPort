package cn.rvport.backend.report.review;

public record ManualReviewItemRequest(Long expectedVersion, String fileKey, String sourceContentHash,
        SourceSelection.Range range, String title, String titleEn, String description, String descriptionEn,
        String repairReason, String repairReasonEn) {
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void rejectUnknown(String field,Object value) { throw new IllegalArgumentException("REPORT_REQUEST_INVALID"); }
}
