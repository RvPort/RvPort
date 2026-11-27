package cn.rvport.backend.project;

import static cn.rvport.backend.project.ProjectWorkflowStatusService.*;
import static cn.rvport.backend.project.ProjectOverviewResponse.WorkflowStageStatus.*;

import cn.rvport.backend.project.ProjectOverviewResponse.WorkflowStageResponse;
import cn.rvport.backend.report.review.CurrentFixListResolver;
import cn.rvport.backend.report.review.ReportWorkflowSummary;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 推荐为导航焦点，不改变节点执行门禁，也不依据展示顺序。 */
public final class WorkflowRecommendationPolicy {
    public String recommend(List<WorkflowStageResponse> stages, CurrentFixListResolver.Current list,
            ReportWorkflowSummary review) {
        Map<String, WorkflowStageResponse> indexed = stages.stream()
                .collect(Collectors.toMap(WorkflowStageResponse::id, Function.identity()));
        if (!done(indexed, PROJECT)) return PROJECT;
        if (!done(indexed, ASSETS)) return ASSETS;
        switch (review.state()) {
            case GENERATING, PENDING_REVIEW, UNCONFIRMED, NEEDS_CONFIRMATION, UNAVAILABLE -> { return REPORT; }
            default -> { }
        }
        if (list.available()) {
            if (list.itemCount() > 0) return MANUAL;
            return validation(indexed);
        }
        return canStart(indexed, REPORT) ? REPORT : KB;
    }

    private String validation(Map<String, WorkflowStageResponse> stages) {
        if (!done(stages, BUILD)) {
            // 正在构建时仍聚焦构建；环境展示状态不替代实际执行资格。
            if (running(stages, BUILD) || canStart(stages, BUILD)) return BUILD;
            return done(stages, ENV) ? BUILD : ENV;
        }
        if (!done(stages, TEST)) return TEST;
        if (!done(stages, OPTIMIZE)) return actionable(stages, OPTIMIZE) ? OPTIMIZE : null;
        if (!done(stages, ARCHIVE)) return actionable(stages, ARCHIVE) ? ARCHIVE : null;
        return null;
    }

    private boolean actionable(Map<String, WorkflowStageResponse> stages, String id) {
        return running(stages, id) || canStart(stages, id);
    }

    private boolean done(Map<String, WorkflowStageResponse> stages, String id) {
        return stages.containsKey(id) && stages.get(id).status() == DONE;
    }

    private boolean running(Map<String, WorkflowStageResponse> stages, String id) {
        return stages.containsKey(id) && stages.get(id).status() == RUNNING;
    }

    private boolean canStart(Map<String, WorkflowStageResponse> stages, String id) {
        return stages.containsKey(id) && Boolean.TRUE.equals(stages.get(id).canStart());
    }
}
