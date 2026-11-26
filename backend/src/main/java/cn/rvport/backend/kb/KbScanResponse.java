package cn.rvport.backend.kb;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record KbScanResponse(
        UUID id,
        UUID projectId,
        UUID taskId,
        UUID sourceRevisionId,
        String kbVersion,
        Map<String, Object> scope,
        String status,
        boolean currentSourceRevision,
        List<String> staleReasons,
        Map<String, Object> summary,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        KbExecutionContext executionContext,
        boolean current,
        String message,
        String message_en
) {
    static KbScanResponse from(KbScanView view, KbScanContextService.Selection selection) {
        return from(view, selection, false);
    }
    static KbScanResponse from(KbScanView view, KbScanContextService.Selection selection, boolean enhancementAvailable) {
        String reason = selection.staleReason(view);
        boolean current = reason == null;
        Map<String,Object> evidence = new java.util.LinkedHashMap<>(view.summary());
        evidence.put("enhancementAvailable", current && enhancementAvailable);
        var plan = view.effectiveInputPlan() != null ? view.effectiveInputPlan()
                : view.executionContext() == null ? null : view.executionContext().inputPlan();
        if (plan != null) {
            evidence.put("inputOrigin", plan.inputOrigin().name());
            evidence.put("inputPlan", plan.summary());
        }
        return new KbScanResponse(view.id(), view.projectId(), view.taskId(), view.sourceRevisionId(),
                view.kbVersion(), view.scope(), view.status().name(),
                java.util.Objects.equals(view.sourceRevisionId(), selection.sourceRevisionId()),
                current ? List.of() : List.of(reason), evidence, view.startedAt(), view.finishedAt(),
                view.executionContext(), current, current ? "当前扫描结果" : "历史扫描结果，需按当前分析输入重新扫描",
                current ? "Current scan result" : "Historical scan result; scan again with current analysis inputs");
    }
    @com.fasterxml.jackson.annotation.JsonProperty public Object inputOrigin() { return summary.get("inputOrigin"); }
    @com.fasterxml.jackson.annotation.JsonProperty public boolean enhancementAvailable() {
        return Boolean.TRUE.equals(summary.get("enhancementAvailable"));
    }
    @com.fasterxml.jackson.annotation.JsonProperty public boolean resultAvailable() {
        return "SUCCEEDED".equals(status) && Boolean.TRUE.equals(summary.get("resultAvailable"));
    }
    @com.fasterxml.jackson.annotation.JsonProperty public boolean analysisComplete() {
        return resultAvailable() && Boolean.TRUE.equals(summary.get("analysisComplete"));
    }
    @com.fasterxml.jackson.annotation.JsonProperty public Map<String,Object> resultApplicability() {
        List<String> reasons = new java.util.ArrayList<>(staleReasons);
        if (!resultAvailable()) reasons.add("ANALYSIS_RESULT_UNAVAILABLE");
        return Map.of("applicable", reasons.isEmpty(), "reasons", List.copyOf(reasons));
    }
}
