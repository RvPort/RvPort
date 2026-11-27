package cn.rvport.backend.report.review;

import static cn.rvport.backend.report.review.ReportWorkflowSummary.State.*;

import cn.rvport.backend.task.TaskStatus;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 在 overview 的一致性事务中读取；不修改报告、草稿或确认记录。 */
@Service
public class ReportWorkflowService {
    private final ReportInputSelectionPolicy inputs;
    private final CurrentFixListResolver fixLists;
    private final ReviewWorkspaceRepository reviews;

    public ReportWorkflowService(ReportInputSelectionPolicy inputs, CurrentFixListResolver fixLists,
            ReviewWorkspaceRepository reviews) {
        this.inputs = inputs;
        this.fixLists = fixLists;
        this.reviews = reviews;
    }

    public record Snapshot(CurrentFixListResolver.Current currentFixList, ReportWorkflowSummary reportReview) { }

    public Snapshot current(UUID project) {
        var selected = inputs.current(project);
        var list = fixLists.current(project, selected.contextHash());
        var generation = reviews.currentGeneration(project, selected.sourceRevisionId(), selected.contextHash()).orElse(null);
        if (generation != null && Set.of(TaskStatus.PENDING, TaskStatus.QUEUED, TaskStatus.RUNNING).contains(generation.status())) {
            return new Snapshot(list, new ReportWorkflowSummary(GENERATING, generation.reportId(), generation.taskId(), null, null, null));
        }
        ReportWorkflowSummary summary;
        switch (list.reasonCode()) {
            case "REPORT_REVIEW_UNAVAILABLE" -> summary = empty(UNAVAILABLE, list.reportId());
            case "REPORT_CONTEXT_CHANGED", "REPORT_READ_ONLY" -> summary = empty(STALE, list.reportId());
            case "UNCONFIRMED", "NEEDS_CONFIRMATION", "CONFIRMED_WITH_ITEMS", "CONFIRMED_EMPTY" -> {
                var counts = reviews.summary(list.reportId());
                long pending = counts.get("pendingReviewCount");
                summary = new ReportWorkflowSummary(pending > 0 ? PENDING_REVIEW : ReportWorkflowSummary.State.valueOf(list.reasonCode()),
                        list.reportId(), null, list.reasonCode(), pending, counts.get("toFixCount"));
            }
            default -> summary = generation != null && generation.status() != TaskStatus.SUCCEEDED
                    ? new ReportWorkflowSummary(GENERATION_FAILED, generation.reportId(), generation.taskId(), null, null, null)
                    : empty(NOT_GENERATED, null);
        }
        return new Snapshot(list, summary);
    }

    private ReportWorkflowSummary empty(ReportWorkflowSummary.State state, UUID report) {
        return new ReportWorkflowSummary(state, report, null, null, null, null);
    }
}
