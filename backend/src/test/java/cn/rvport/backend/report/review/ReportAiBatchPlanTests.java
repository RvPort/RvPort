package cn.rvport.backend.report.review;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReportAiBatchPlanTests {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final UUID project = UUID.randomUUID(), source = UUID.randomUUID();
    ReviewItemSnapshot item(int id, String origin, ReviewState state) {
        return new ReviewItemSnapshot(new UUID(0, id), origin, "KB_FINDING", source, new UUID(1,id), "identity", "evidence",
                Map.of("text", "sample"), "问题", "Finding", "key", "a.c", 1,1,1,2,"hash",null,null,
                new ReviewDecision(state,null,null),null,Map.of(),project);
    }
    ReportReviewManifest manifest(List<ReviewItemSnapshot> items) {
        return new ReportReviewManifest(project, source, "context", ReportMode.AI_ENHANCED, null,
                new ReportSourceSnapshotService.Manifest(source, "hash", List.of(), null), items, null, null);
    }
    @Test void stableOrderingBatchesAllExpectedItemsAndRetainsExcludedOutsideDenominator() throws Exception {
        var a = item(1,"SCANNER",ReviewState.TO_FIX); var b = item(2,"SCANNER",ReviewState.PENDING_REVIEW);
        var excluded = item(3,"SCANNER",ReviewState.EXCLUDED); var manual = item(4,"MANUAL",ReviewState.TO_FIX);
        var plan = ReportAiBatchPlan.create(manifest(List.of(b, manual, a, excluded)), mapper, 1, 10000, 20000, 20);
        assertThat(plan.totalItems()).isEqualTo(2);
        assertThat(plan.batches()).containsExactly(List.of(a),List.of(b));
        assertThat(ReportAiBatchPlan.create(manifest(List.of(excluded,a,b,manual)), mapper,1,10000,20000,20).digest()).isEqualTo(plan.digest());
        assertThat(ReportAiBatchPlan.create(manifest(List.of()),mapper).batches()).isEmpty();
        var byBytes = ReportAiBatchPlan.create(manifest(List.of(a,b)),mapper,32,2500,10000,20);
        assertThat(byBytes.batches()).hasSize(2);
        assertThat(ReportAiBatchPlan.create(manifest(List.of(a,b)),mapper).batches()).hasSize(1);
    }
    @Test void rejectsDuplicateIdsAndAnyBudgetExcessWithoutTruncating() {
        var a = item(1,"SCANNER",ReviewState.TO_FIX); var b = item(2,"SCANNER",ReviewState.TO_FIX);
        assertThatThrownBy(() -> ReportAiBatchPlan.create(manifest(List.of(a,a)),mapper)).hasMessage("REPORT_AI_REVIEW_INPUT_INVALID");
        for (int[] limits : List.of(new int[]{0,10000,20000,20}, new int[]{1,0,20000,20}, new int[]{1,10000,0,20},
                new int[]{1,10000,20000,1}, new int[]{1,1,20000,20}, new int[]{1,10000,2500,20})) {
            assertThatThrownBy(() -> ReportAiBatchPlan.create(manifest(List.of(a,b)),mapper,limits[0],limits[1],limits[2],limits[3]))
                    .hasMessage("REPORT_AI_REVIEW_INPUT_TOO_LARGE");
        }
    }
}
