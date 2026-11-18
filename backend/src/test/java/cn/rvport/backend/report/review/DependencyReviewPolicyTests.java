package cn.rvport.backend.report.review;

import static org.assertj.core.api.Assertions.*;
import cn.rvport.backend.dependency.DependencyAnalysisResultItemView;
import cn.rvport.backend.dependency.DependencyRiskLevel;
import cn.rvport.backend.dependency.DependencyTargetMatchStatus;
import org.junit.jupiter.api.Test;

class DependencyReviewPolicyTests {
    @Test void allMatchingStatesRespectRiskThresholdWithoutDroppingUnknowns() {
        for (var status : DependencyTargetMatchStatus.values()) {
            for (var risk : DependencyRiskLevel.values()) {
                var decision = DependencyReviewPolicy.decide(status, risk, null, "PACKAGE_OWNER");
                boolean unknown = status == DependencyTargetMatchStatus.MANUAL_CONFIRM_REQUIRED;
                boolean high = risk == DependencyRiskLevel.MEDIUM || risk == DependencyRiskLevel.HIGH || risk == DependencyRiskLevel.CRITICAL;
                assertThat(decision.included()).as("%s/%s", status, risk).isEqualTo(unknown || high);
                assertThat(decision.unknown()).isEqualTo(unknown);
            }
        }
        assertThat(DependencyReviewPolicy.decide(DependencyTargetMatchStatus.REPO_AVAILABLE,
                DependencyRiskLevel.MEDIUM, null, null).reasonCode()).isEqualTo("DEPENDENCY_UNRESOLVED_RISK");
        assertThat(DependencyReviewPolicy.decide(DependencyTargetMatchStatus.TARGET_INSTALLED,
                DependencyRiskLevel.LOW, " ", "").reasonCode()).isEqualTo("DEPENDENCY_SATISFIED");
    }

    @Test void missingConflictingOrUnrecognizedEvidenceRemainsUnknown() {
        assertThat(DependencyReviewPolicy.decide(null, DependencyRiskLevel.LOW, null, null).unknown()).isTrue();
        assertThat(DependencyReviewPolicy.decide(DependencyTargetMatchStatus.MISSING, null, null, null).unknown()).isTrue();
        assertThat(DependencyReviewPolicy.decide(DependencyTargetMatchStatus.TARGET_INSTALLED,
                DependencyRiskLevel.LOW, "NOT_A_STATUS", null).reasonCode()).isEqualTo("DEPENDENCY_PROVIDER_UNKNOWN");
        assertThat(DependencyReviewPolicy.decide(DependencyTargetMatchStatus.TARGET_INSTALLED,
                DependencyRiskLevel.LOW, "TARGET_OLDER", "PACKAGE_OWNER").reasonCode()).isEqualTo("DEPENDENCY_EVIDENCE_CONFLICT");
        assertThat(DependencyReviewPolicy.decide(DependencyTargetMatchStatus.SAME_MAJOR,
                DependencyRiskLevel.LOW, "SAME_MAJOR", null).reasonCode()).isEqualTo("DEPENDENCY_VERSION_EVIDENCE_MISSING");
        assertThat(DependencyReviewPolicy.decide(DependencyTargetMatchStatus.TARGET_NEWER,
                DependencyRiskLevel.LOW, "TARGET_NEWER", " ").unknown()).isTrue();
        assertThat(DependencyReviewPolicy.decide(DependencyTargetMatchStatus.TARGET_NEWER,
                DependencyRiskLevel.LOW, "TARGET_NEWER", "INVALID").reasonCode()).isEqualTo("DEPENDENCY_VERSION_EVIDENCE_UNKNOWN");
    }

    @Test void recognizedEvidenceDoesNotPromoteLowRiskByItself() {
        for (String evidence : new String[]{"PACKAGE_OWNER", "ELF_METADATA", "COMMAND_VERSION"}) {
            assertThat(DependencyReviewPolicy.decide(DependencyTargetMatchStatus.TARGET_NEWER,
                    DependencyRiskLevel.LOW, "TARGET_NEWER", evidence).included()).isFalse();
        }
        var item = new DependencyAnalysisResultItemView(null,null,null,null,null,null,"lib",null,
                null,null,null,null,null,null,"TARGET_OLDER","PACKAGE_OWNER",
                DependencyTargetMatchStatus.TARGET_OLDER,DependencyRiskLevel.HIGH,null,null,null,null,null);
        assertThat(DependencyReviewPolicy.decide(item).included()).isTrue();
        assertThat(DependencyReviewPolicy.decide(item).unknown()).isFalse();
    }
}
