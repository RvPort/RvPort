package cn.rvport.backend.report.review;

import java.util.UUID;

/** 当前报告的导航事实，生成状态与人工确认状态分别表达。 */
public record ReportWorkflowSummary(State state, UUID reportId, UUID taskId, String confirmationState,
        Long pendingReviewCount, Long toFixCount) {
    public enum State {
        NOT_GENERATED, GENERATING, GENERATION_FAILED, UNAVAILABLE, STALE,
        PENDING_REVIEW, UNCONFIRMED, NEEDS_CONFIRMATION, CONFIRMED_WITH_ITEMS, CONFIRMED_EMPTY
    }
}
