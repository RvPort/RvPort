package cn.rvport.backend.kb;

import cn.rvport.backend.migration.NodeReportInclusionState;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record FindingResponse(
        UUID id,
        UUID projectId,
        UUID scanRunId,
        UUID sourceRevisionId,
        String ruleId,
        String category,
        String severity,
        String title,
        String description,
        String filePath,
        Integer lineStart,
        Integer lineEnd,
        String symbol,
        Map<String, Object> fact,
        Map<String, Object> conclusion,
        List<Map<String, Object>> occurrences,
        Map<String, Object> evidenceChain,
        Map<String, Object> evidence,
        String suggestion,
        String status,
        boolean includedInLatestReport,
        UUID latestReportId,
        OffsetDateTime createdAt
) {
    static FindingResponse from(FindingView view) {
        return from(view, null);
    }

    static FindingResponse from(FindingView view, NodeReportInclusionState inclusion) {
        NodeReportInclusionState state = inclusion == null ? NodeReportInclusionState.empty() : inclusion;
        return new FindingResponse(
                view.id(),
                view.projectId(),
                view.scanRunId(),
                view.sourceRevisionId(),
                view.ruleId(),
                view.category(),
                view.severity(),
                view.title(),
                view.description(),
                view.filePath(),
                view.lineStart(),
                view.lineEnd(),
                view.symbol(),
                fact(view.evidence()),
                conclusion(view.evidence()),
                occurrences(view.evidence()),
                evidenceChain(view.evidence()),
                view.evidence(),
                view.suggestion(),
                view.status(),
                state.includedInLatestReport(),
                state.latestReportId(),
                view.createdAt()
        );
    }

    FindingResponse withInclusion(NodeReportInclusionState inclusion) {
        NodeReportInclusionState state = inclusion == null ? NodeReportInclusionState.empty() : inclusion;
        return new FindingResponse(
                id,
                projectId,
                scanRunId,
                sourceRevisionId,
                ruleId,
                category,
                severity,
                title,
                description,
                filePath,
                lineStart,
                lineEnd,
                symbol,
                fact,
                conclusion,
                occurrences,
                evidenceChain,
                evidence,
                suggestion,
                status,
                state.includedInLatestReport(),
                state.latestReportId(),
                createdAt
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fact(Map<String, Object> evidence) {
        Map<String, Object> finding = finding(evidence);
        Object fact = finding.get("fact");
        return fact instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> conclusion(Map<String, Object> evidence) {
        Map<String, Object> finding = finding(evidence);
        Object conclusion = finding.get("conclusion");
        return conclusion instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> occurrences(Map<String, Object> evidence) {
        Map<String, Object> finding = finding(evidence);
        Object occurrences = finding.get("occurrences");
        if (!(occurrences instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> evidenceChain(Map<String, Object> evidence) {
        Map<String, Object> finding = finding(evidence);
        Object evidenceChain = finding.get("evidenceChain");
        return evidenceChain instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> finding(Map<String, Object> evidence) {
        if (evidence == null) {
            return Map.of();
        }
        Object finding = evidence.get("finding");
        return finding instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
