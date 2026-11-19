package cn.rvport.backend.kb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import cn.rvport.backend.migration.NodeReportInclusionState;
import org.junit.jupiter.api.Test;

class KbValueObjectTests {

    @Test
    void findingResponseExtractsOnlyStructuredV2Evidence() {
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("fact", Map.of("kind", "fact"));
        finding.put("conclusion", Map.of("kind", "conclusion"));
        finding.put("occurrences", List.of(Map.of("line", 1), "invalid", 2));
        finding.put("evidenceChain", Map.of("step", 1));
        FindingResponse response = FindingResponse.from(view(Map.of("finding", finding)));

        assertThat(response.fact()).containsEntry("kind", "fact");
        assertThat(response.conclusion()).containsEntry("kind", "conclusion");
        assertThat(response.occurrences()).containsExactly(Map.of("line", 1));
        assertThat(response.evidenceChain()).containsEntry("step", 1);
    }

    @Test
    void findingResponseUsesEmptyStructuresForNullOrMalformedEvidence() {
        List<Map<String, Object>> malformed = new java.util.ArrayList<>();
        malformed.add(null);
        malformed.add(Map.of());
        malformed.add(Map.of("finding", "invalid"));
        malformed.add(Map.of("finding", Map.of("fact", "invalid", "conclusion", List.of(),
                "occurrences", "invalid", "evidenceChain", "invalid")));
        for (Map<String, Object> evidence : malformed) {
            FindingResponse response = FindingResponse.from(view(evidence));
            assertThat(response.fact()).isEmpty();
            assertThat(response.conclusion()).isEmpty();
            assertThat(response.occurrences()).isEmpty();
            assertThat(response.evidenceChain()).isEmpty();
        }
    }

    @Test
    void findingResponseMapsAndReplacesExplicitInclusionState() {
        UUID refId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        NodeReportInclusionState state = new NodeReportInclusionState(
                true, reportId);

        FindingResponse response = FindingResponse.from(view(Map.of()), state);
        assertThat(response.includedInLatestReport()).isTrue();

        FindingResponse cleared = response.withInclusion(null);
        assertThat(cleared.withInclusion(state).latestReportId()).isEqualTo(reportId);
    }

    @Test
    void artifactTypeAcceptsNormalizedFormatsAndRejectsMissingOrUnknownValues() {
        assertThat(KbScanArtifactType.fromDownloadFormat(" JSON ")).isEqualTo(KbScanArtifactType.SCAN_RESULT_JSON);
        assertThat(KbScanArtifactType.fromDownloadFormat("html")).isEqualTo(KbScanArtifactType.SCAN_REPORT_HTML);
        assertThat(KbScanArtifactType.fromDownloadFormat("LOG")).isEqualTo(KbScanArtifactType.SCAN_LOG);
        for (String invalid : new String[]{"", " ", "xml"}) {
            assertThatThrownBy(() -> KbScanArtifactType.fromDownloadFormat(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> KbScanArtifactType.fromDownloadFormat(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scanListCurrentSelectionHonorsStaleFlagAndExplicitRevision() {
        UUID active = UUID.randomUUID();
        UUID explicit = UUID.randomUUID();
        KbScanListRequest defaults = new KbScanListRequest(null, null, null, null, null, null, null, null, null);
        assertThat(defaults.effectiveCurrentOnly()).isTrue();
        assertThat(defaults.withCurrentSourceRevision(active).sourceRevisionId()).isEqualTo(active);

        KbScanListRequest explicitRequest = new KbScanListRequest(1, 20, null, explicit, true, false, null, null, null);
        assertThat(explicitRequest.withCurrentSourceRevision(active).sourceRevisionId()).isEqualTo(explicit);

        KbScanListRequest stale = new KbScanListRequest(1, 20, null, null, true, true, null, null, null);
        assertThat(stale.effectiveCurrentOnly()).isFalse();
        assertThat(stale.withCurrentSourceRevision(active)).isSameAs(stale);
    }

    private FindingView view(Map<String, Object> evidence) {
        return new FindingView(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "rule", "category", "HIGH", "title", "description", "src/a.c", 1, 2,
                "symbol", evidence, "suggestion", "fingerprint", "OPEN", OffsetDateTime.now());
    }
}
