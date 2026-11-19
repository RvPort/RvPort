package cn.rvport.backend.dependency;

import cn.rvport.backend.migration.NodeReportInclusionState;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record DependencyAnalysisResultItemResponse(
        UUID id,
        DependencyType dependencyType,
        String dependencyName,
        String dependencyVersion,
        String sourceProviderPackage,
        String sourceProviderVersion,
        String sourceDetectedVersion,
        String targetProviderPackage,
        String targetProviderVersion,
        String targetDetectedVersion,
        String providerVersionStatus,
        String versionEvidenceConfidence,
        DependencyTargetMatchStatus targetMatchStatus,
        DependencyRiskLevel riskLevel,
        String locationLabel,
        String recommendation,
        DependencyConfidence confidence,
        Map<String, Object> evidence,
        boolean includedInLatestReport,
        UUID latestReportId,
        OffsetDateTime createdAt
) {
    public static DependencyAnalysisResultItemResponse from(DependencyAnalysisResultItemView view) {
        return from(view, null);
    }

    public static DependencyAnalysisResultItemResponse from(DependencyAnalysisResultItemView view, NodeReportInclusionState inclusion) {
        NodeReportInclusionState state = inclusion == null ? NodeReportInclusionState.empty() : inclusion;
        return new DependencyAnalysisResultItemResponse(
                view.id(),
                view.dependencyType(),
                view.dependencyName(),
                view.dependencyVersion(),
                view.sourceProviderPackage(),
                view.sourceProviderVersion(),
                view.sourceDetectedVersion(),
                view.targetProviderPackage(),
                view.targetProviderVersion(),
                view.targetDetectedVersion(),
                view.providerVersionStatus(),
                view.versionEvidenceConfidence(),
                view.targetMatchStatus(),
                view.riskLevel(),
                view.locationLabel(),
                view.recommendation(),
                view.confidence(),
                view.evidence(),
                state.includedInLatestReport(),
                state.latestReportId(),
                view.createdAt()
        );
    }

    public DependencyAnalysisResultItemResponse withInclusion(NodeReportInclusionState inclusion) {
        NodeReportInclusionState state = inclusion == null ? NodeReportInclusionState.empty() : inclusion;
        return new DependencyAnalysisResultItemResponse(
                id,
                dependencyType,
                dependencyName,
                dependencyVersion,
                sourceProviderPackage,
                sourceProviderVersion,
                sourceDetectedVersion,
                targetProviderPackage,
                targetProviderVersion,
                targetDetectedVersion,
                providerVersionStatus,
                versionEvidenceConfidence,
                targetMatchStatus,
                riskLevel,
                locationLabel,
                recommendation,
                confidence,
                evidence,
                state.includedInLatestReport(),
                state.latestReportId(),
                createdAt
        );
    }
}
