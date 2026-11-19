package cn.rvport.backend.analysis;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.archive.ArchiveCandidateRegistrar;
import cn.rvport.backend.artifact.StoredArtifactView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class SpecializedAnalysisCompletionServiceTests {

    @Test
    void completesFloatingPointRunAndRegistersArchiveCandidate() {
        Fixture fixture = fixture(SpecializedAnalyzerType.FLOATING_POINT);

        fixture.service.complete(fixture.input, SpecializedAnalysisStatus.COMPLETE, "analyzed", 0, Map.of("findingCount", 1),
                fixture.request, fixture.json, fixture.html, null, fixture.findings);

        verify(fixture.repository).complete(fixture.input.runId(), SpecializedAnalysisStatus.COMPLETE, "analyzed", 0,
                Map.of("findingCount", 1), fixture.request.id(), fixture.json.id(), fixture.html.id(), null, fixture.findings);
        verify(fixture.archive).registerSpecializedAnalysis(fixture.run, Map.of("findingCount", 1),
                List.of(fixture.request, fixture.json, fixture.html));
    }

    @Test
    void memoryOrderIncludesAuditBundleAndUsesOwnSourceType() {
        Fixture fixture = fixture(SpecializedAnalyzerType.MEMORY_ORDER);
        StoredArtifactView audit = artifact();

        fixture.service.complete(fixture.input, SpecializedAnalysisStatus.COMPLETE_WITH_RISK, "risk", 3, Map.of(),
                fixture.request, fixture.json, fixture.html, audit, fixture.findings);

        verify(fixture.archive).registerSpecializedAnalysis(fixture.run, Map.of(),
                List.of(fixture.request, fixture.json, fixture.html, audit));
    }

    @Test
    void completionFailurePropagatesAndPreventsArchiveRegistration() {
        Fixture fixture = fixture(SpecializedAnalyzerType.FLOATING_POINT);
        doThrow(new IllegalStateException("refresh failed")).when(fixture.repository).findById(fixture.input.runId());

        assertThatThrownBy(() -> fixture.service.complete(fixture.input, SpecializedAnalysisStatus.COMPLETE, "ok", 0,
                Map.of(), fixture.request, fixture.json, fixture.html, null, fixture.findings))
                .isInstanceOf(IllegalStateException.class).hasMessage("refresh failed");
        verify(fixture.archive, org.mockito.Mockito.never()).registerSpecializedAnalysis(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap(), anyList());
    }

    private Fixture fixture(SpecializedAnalyzerType type) {
        SpecializedAnalysisRepository repository = mock(SpecializedAnalysisRepository.class);
        ArchiveCandidateRegistrar archive = mock(ArchiveCandidateRegistrar.class);
        SpecializedAnalysisCompletionService service = new SpecializedAnalysisCompletionService(repository, archive);
        UUID findingId = UUID.randomUUID();
        SpecializedAnalysisTaskInput input = new SpecializedAnalysisTaskInput(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "a".repeat(64), 1L, type);
        OffsetDateTime now = OffsetDateTime.now();
        SpecializedAnalysisRunView run = new SpecializedAnalysisRunView(input.runId(), input.projectId(),
                UUID.randomUUID(), type, input.sourceRevisionId(), input.buildRunId(), input.artifactSetId(),
                input.environmentConfigVersion(), "test-tool", "1.0", "1.0", "default",
                SpecializedAnalysisStatus.COMPLETE, "ok", 0, Map.of(), null, null, null, null,
                1, 0, 0, UUID.randomUUID(), now, now, now, now);
        when(repository.findById(input.runId())).thenReturn(java.util.Optional.of(run));
        return new Fixture(service, repository, archive, input, run, findingId,
                artifact(), artifact(), artifact(), List.of(new SpecializedFindingImport("finding-1", "RISK",
                        "TEST", "test", "HIGH", "HIGH", "OPEN", "测试", "Test", "消息", "Message",
                        "建议", "Suggestion", "测试建议", "Test suggestion", "src/test.c", 1, 1, 1, 2,
                        true, "digest", Map.of())));
    }

    private StoredArtifactView artifact() {
        return new StoredArtifactView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                cn.rvport.backend.artifact.StoredArtifactOwnerType.SPECIALIZED_ANALYSIS, UUID.randomUUID(),
                "TEST", "bucket", "object", "application/json", 1L, "a".repeat(64), Map.of(),
                OffsetDateTime.now());
    }

    private record Fixture(SpecializedAnalysisCompletionService service, SpecializedAnalysisRepository repository,
                           ArchiveCandidateRegistrar archive,
                           SpecializedAnalysisTaskInput input, SpecializedAnalysisRunView run, UUID findingId,
                           StoredArtifactView request, StoredArtifactView json, StoredArtifactView html,
                           List<SpecializedFindingImport> findings) { }
}
