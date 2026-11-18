package cn.rvport.backend.report.review;

import cn.rvport.backend.common.error.ApiException;
import java.util.Objects;
import org.springframework.http.HttpStatus;

/** 人工决定独立于扫描风险与 AI 判断；恢复只恢复处置状态，不恢复确认快照。 */
public record ReviewDecision(ReviewState state, ReviewState restoreState, String exclusionReason) {
    public ReviewDecision {
        Objects.requireNonNull(state, "state");
    }

    public ReviewDecision apply(ReviewAction action, String reason) {
        if (action == null) throw invalid();
        return switch (action) {
            case INCLUDE -> new ReviewDecision(ReviewState.TO_FIX, null, null);
            case EXCLUDE -> {
                if (reason == null || reason.isBlank() || reason.length() > 4000) throw invalid();
                yield new ReviewDecision(ReviewState.EXCLUDED,
                        state == ReviewState.EXCLUDED ? restoreState : state, reason.trim());
            }
            case RESTORE -> state != ReviewState.EXCLUDED ? this
                    : new ReviewDecision(restoreState == null || restoreState == ReviewState.EXCLUDED
                            ? ReviewState.PENDING_REVIEW : restoreState, null, null);
        };
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "REPORT_REVIEW_ACTION_INVALID",
                "处置动作或排除原因不正确");
    }
}
