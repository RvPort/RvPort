package cn.rvport.backend.report.review;

import cn.rvport.backend.dependency.DependencyAnalysisResultItemView;
import cn.rvport.backend.dependency.DependencyRiskLevel;
import cn.rvport.backend.dependency.DependencyTargetMatchStatus;
import java.util.Set;

/** 只依赖结构化事实，不从建议文案或人工排除推断依赖已经解决。 */
public final class DependencyReviewPolicy {
    private static final Set<String> PROVIDER_STATES = Set.of("SAME_MAJOR", "TARGET_NEWER", "TARGET_OLDER",
            "TARGET_PACKAGE_AVAILABLE_SOURCE_UNOWNED", "NO_TARGET_VERSION_FOUND");
    private static final Set<String> VERSION_EVIDENCE = Set.of("PACKAGE_OWNER", "ELF_METADATA", "COMMAND_VERSION");
    private static final Set<String> SATISFIED = Set.of("SAME_MAJOR", "TARGET_NEWER", "TARGET_INSTALLED");

    private DependencyReviewPolicy() { }

    public record Decision(boolean included, boolean unknown, String reasonCode) { }

    public static Decision decide(DependencyAnalysisResultItemView item) {
        return decide(item.targetMatchStatus(), item.riskLevel(), item.providerVersionStatus(), item.versionEvidenceConfidence());
    }

    public static Decision decide(DependencyTargetMatchStatus status, DependencyRiskLevel risk,
            String providerStatus, String versionEvidence) {
        if (status == null || risk == null || status == DependencyTargetMatchStatus.MANUAL_CONFIRM_REQUIRED) {
            return unknown("DEPENDENCY_CONCLUSION_UNKNOWN");
        }
        boolean hasProvider = providerStatus != null && !providerStatus.isBlank();
        if (hasProvider && !PROVIDER_STATES.contains(providerStatus)) return unknown("DEPENDENCY_PROVIDER_UNKNOWN");
        if (versionEvidence != null && !versionEvidence.isBlank() && !VERSION_EVIDENCE.contains(versionEvidence)) {
            return unknown("DEPENDENCY_VERSION_EVIDENCE_UNKNOWN");
        }
        if (hasProvider && SATISFIED.contains(status.name()) && !SATISFIED.contains(providerStatus)) {
            return unknown("DEPENDENCY_EVIDENCE_CONFLICT");
        }
        if ((status == DependencyTargetMatchStatus.SAME_MAJOR || status == DependencyTargetMatchStatus.TARGET_NEWER)
                && (versionEvidence == null || versionEvidence.isBlank())) return unknown("DEPENDENCY_VERSION_EVIDENCE_MISSING");
        if (risk == DependencyRiskLevel.MEDIUM || risk == DependencyRiskLevel.HIGH || risk == DependencyRiskLevel.CRITICAL) {
            return new Decision(true, false, "DEPENDENCY_UNRESOLVED_RISK");
        }
        return new Decision(false, false, SATISFIED.contains(status.name())
                ? "DEPENDENCY_SATISFIED" : "DEPENDENCY_BELOW_RISK_THRESHOLD");
    }

    private static Decision unknown(String reason) { return new Decision(true, true, reason); }
}
