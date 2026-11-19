package cn.rvport.backend.migration;

import cn.rvport.backend.report.review.ReviewWorkspaceRepository;
import cn.rvport.backend.report.review.ReportInputSelectionPolicy;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NodeReportInclusionService {

    private final ReviewWorkspaceRepository reviews;
    private final ReportInputSelectionPolicy selection;

    public NodeReportInclusionService(ReviewWorkspaceRepository reviews, ReportInputSelectionPolicy selection) {
        this.reviews = reviews;
        this.selection = selection;
    }

    @Transactional(readOnly = true)
    public NodeReportInclusionState state(
            UUID projectId,
            UUID sourceRevisionId,
            MigrationInputSourceType sourceType,
            UUID sourceRefId
    ) {
        return states(projectId, sourceRevisionId, sourceType, List.of(sourceRefId))
                .getOrDefault(sourceRefId, NodeReportInclusionState.empty());
    }

    @Transactional(readOnly = true)
    public Map<UUID, NodeReportInclusionState> states(
            UUID projectId,
            UUID sourceRevisionId,
            MigrationInputSourceType sourceType,
            List<UUID> sourceRefIds
    ) {
        if (sourceRefIds == null || sourceRefIds.isEmpty()) {
            return Map.of();
        }
        var context = selection.current(projectId);
        if (!sourceRevisionId.equals(context.sourceRevisionId())) return Map.of();
        return reviews.includedSourceRefs(projectId, sourceRevisionId, context.contextHash(), sourceType.name(), sourceRefIds);
    }
}
