package cn.rvport.backend.analysis;

import cn.rvport.backend.archive.ArchiveCandidateRegistrar;
import cn.rvport.backend.artifact.StoredArtifactView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SpecializedAnalysisCompletionService {

    private final SpecializedAnalysisRepository repository;
    private final ArchiveCandidateRegistrar archiveCandidateRegistrar;

    public SpecializedAnalysisCompletionService(
            SpecializedAnalysisRepository repository,
            ArchiveCandidateRegistrar archiveCandidateRegistrar
    ) {
        this.repository = repository;
        this.archiveCandidateRegistrar = archiveCandidateRegistrar;
    }

    @Transactional
    public void complete(
            SpecializedAnalysisTaskInput input,
            SpecializedAnalysisStatus status,
            String verdict,
            int exitCode,
            Map<String, Object> summary,
            StoredArtifactView requestArtifact,
            StoredArtifactView jsonArtifact,
            StoredArtifactView htmlArtifact,
            StoredArtifactView auditArtifact,
            List<SpecializedFindingImport> findings
    ) {
        repository.complete(input.runId(), status, verdict, exitCode, summary,
                requestArtifact.id(), jsonArtifact.id(), htmlArtifact.id(),
                auditArtifact == null ? null : auditArtifact.id(), findings);
        SpecializedAnalysisRunView completed = repository.findById(input.runId()).orElseThrow();
        archiveCandidateRegistrar.registerSpecializedAnalysis(completed, summary,
                auditArtifact == null
                        ? List.of(requestArtifact, jsonArtifact, htmlArtifact)
                        : List.of(requestArtifact, jsonArtifact, htmlArtifact, auditArtifact));
    }
}
