package cn.rvport.backend.report.review;

import static org.assertj.core.api.Assertions.*;
import cn.rvport.backend.common.error.ApiException;
import org.junit.jupiter.api.Test;

class ReviewDecisionTests {
    @Test void excludingAgainDoesNotLoseTheStateToRestore() {
        var pending = new ReviewDecision(ReviewState.PENDING_REVIEW, null, null);
        var excluded = pending.apply(ReviewAction.EXCLUDE, "  不在编译路径  ");
        assertThat(excluded.exclusionReason()).isEqualTo("不在编译路径");
        assertThat(excluded.apply(ReviewAction.EXCLUDE, "补充说明").apply(ReviewAction.RESTORE, null)).isEqualTo(pending);
        var included = pending.apply(ReviewAction.INCLUDE, null);
        assertThat(included.state()).isEqualTo(ReviewState.TO_FIX);
        assertThat(included.apply(ReviewAction.EXCLUDE, "无需迁移").apply(ReviewAction.RESTORE, null)).isEqualTo(included);
        assertThat(excluded.apply(ReviewAction.INCLUDE, null)).isEqualTo(included);
        assertThat(included.apply(ReviewAction.RESTORE, null)).isSameAs(included);
        assertThat(new ReviewDecision(ReviewState.EXCLUDED, null, "原因").apply(ReviewAction.RESTORE, null)).isEqualTo(pending);
        assertThat(new ReviewDecision(ReviewState.EXCLUDED, ReviewState.EXCLUDED, "原因")
                .apply(ReviewAction.RESTORE, null)).isEqualTo(pending);
    }

    @Test void invalidActionsLeaveOriginalDecisionUntouched() {
        var original = new ReviewDecision(ReviewState.TO_FIX, null, null);
        for (String reason : new String[]{null, " ", "x".repeat(4001)}) {
            assertThatThrownBy(() -> original.apply(ReviewAction.EXCLUDE, reason))
                    .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("REPORT_REVIEW_ACTION_INVALID"));
        }
        assertThatThrownBy(() -> original.apply(null, null)).isInstanceOf(ApiException.class);
        assertThat(original.state()).isEqualTo(ReviewState.TO_FIX);
        assertThat(original.apply(ReviewAction.EXCLUDE, "x".repeat(4000)).state()).isEqualTo(ReviewState.EXCLUDED);
        assertThatThrownBy(() -> new ReviewDecision(null, null, null)).isInstanceOf(NullPointerException.class);
    }
}
