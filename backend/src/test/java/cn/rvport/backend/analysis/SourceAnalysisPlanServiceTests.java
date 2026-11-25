package cn.rvport.backend.analysis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.rvport.backend.project.ProjectView;
import cn.rvport.backend.report.review.ReportSourceSnapshotService;
import cn.rvport.backend.source.SourceRevisionView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SourceAnalysisPlanServiceTests {
    final ReportSourceSnapshotService snapshots = mock(ReportSourceSnapshotService.class);
    final cn.rvport.backend.report.review.ReportSourceMaterializer materializer = mock(cn.rvport.backend.report.review.ReportSourceMaterializer.class);
    final SourceAnalysisPlanService service = new SourceAnalysisPlanService(snapshots, materializer);
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;
    final UUID projectId = UUID.randomUUID(), sourceId = UUID.randomUUID();
    ProjectView project() { return new ProjectView(projectId, "test", null, "amd64", "riscv64", null, null, null, null, null, null); }
    SourceRevisionView source(UUID owner) { return new SourceRevisionView(sourceId, owner, null, null, null, null, null,
            null, null, null, null, true, null, null); }

    @Test void capturesFixedSourceAndAnalyzerIdentityWithoutBuildOrEnvironment() {
        var source = source(projectId);
        when(snapshots.prepare(source)).thenReturn(new ReportSourceSnapshotService.Manifest(sourceId, "git:" + "a".repeat(40), List.of(), null));
        for (var type : SpecializedAnalyzerType.values()) {
            var plan = service.create(project(), source, type);
            assertThat(plan.inputOrigin()).isEqualTo(AnalysisInputPlan.InputOrigin.SOURCE_DISCOVERY);
            assertThat(plan.build()).isNull();
            assertThat(plan.artifacts()).isEmpty();
            assertThat(plan.analyzer()).isEqualTo(type.name());
            assertThat(plan.toolVersion()).isEqualTo(type == SpecializedAnalyzerType.FLOATING_POINT ? "0.6.0" : "0.1.0");
            assertThat(plan.identity().effectiveSourceArch()).isEqualTo("x86");
            assertThat(plan.identity().effectiveLanguages()).containsExactly("c", "cpp");
            assertThat(plan.missingEvidence()).containsExactly("COMPILE_DATABASE_UNAVAILABLE", "BITCODE_UNAVAILABLE");
        }
    }

    @Test void rejectsWrongProjectAndUnavailableOrMismatchedSnapshot() {
        assertThatThrownBy(() -> service.create(project(), source(UUID.randomUUID()), SpecializedAnalyzerType.FLOATING_POINT))
                .extracting("code").isEqualTo("ANALYSIS_SOURCE_IDENTITY_MISMATCH");
        verifyNoInteractions(snapshots);
        var source = source(projectId);
        for (var manifest : List.of(
                new ReportSourceSnapshotService.Manifest(UUID.randomUUID(), "a".repeat(64), List.of(), null),
                new ReportSourceSnapshotService.Manifest(sourceId, null, List.of(), null),
                new ReportSourceSnapshotService.Manifest(sourceId, "a".repeat(64), List.of(), "SOURCE_CONTEXT_UNAVAILABLE"))) {
            when(snapshots.prepare(source)).thenReturn(manifest);
            assertThatThrownBy(() -> service.create(project(), source, SpecializedAnalyzerType.FLOATING_POINT))
                    .extracting("code").isEqualTo("ANALYSIS_SOURCE_SNAPSHOT_UNAVAILABLE");
        }
    }

    @Test void specializedProfileRejectsUnknownOrIncompatibleArchitectures() {
        var source = source(projectId);
        when(snapshots.prepare(source)).thenReturn(new ReportSourceSnapshotService.Manifest(sourceId,"a".repeat(64),List.of(),null));
        for (var arch : List.of(List.of("arm64","riscv64"),List.of("sparc","riscv64"),List.of("x86","arm64"))) {
            var project = new ProjectView(projectId,"test",null,arch.get(0),arch.get(1),null,null,null,null,null,null);
            assertThatThrownBy(() -> service.create(project,source,SpecializedAnalyzerType.FLOATING_POINT))
                    .extracting("code").isEqualTo("ANALYSIS_PROFILE_UNSUPPORTED");
        }
    }

    @Test void executionUsesFixedArchiveAndRejectsReplacedArchiveOrWrongRevision() throws Exception {
        var source = source(projectId);
        when(snapshots.prepare(source)).thenReturn(new ReportSourceSnapshotService.Manifest(sourceId, "a".repeat(64), List.of(), null));
        var plan = service.create(project(), source, SpecializedAnalyzerType.FLOATING_POINT);
        assertThatThrownBy(() -> service.materialize(plan, source(UUID.randomUUID()))).extracting("code").isEqualTo("ANALYSIS_SOURCE_IDENTITY_MISMATCH");
        var wrongRevision = new SourceRevisionView(UUID.randomUUID(), projectId, null, null, null, null, null, null, null, null, null, true, null, null);
        assertThatThrownBy(() -> service.materialize(plan, wrongRevision)).extracting("code").isEqualTo("ANALYSIS_SOURCE_IDENTITY_MISMATCH");
        verifyNoInteractions(materializer);
        var directory = java.nio.file.Files.createDirectory(temporary.resolve("fixed"));
        var fixed = new cn.rvport.backend.report.review.ReportSourceMaterializer.Materialized(directory, directory, "a".repeat(64));
        when(materializer.prepare(source)).thenReturn(fixed);
        assertThat(service.materialize(plan, source)).isSameAs(fixed);
        when(materializer.prepare(source)).thenReturn(new cn.rvport.backend.report.review.ReportSourceMaterializer.Materialized(directory, directory, "b".repeat(64)));
        assertThatThrownBy(() -> service.materialize(plan, source)).extracting("code").isEqualTo("ANALYSIS_SOURCE_DIGEST_MISMATCH");
        assertThat(directory).doesNotExist();
    }
    @Test void kbPreservesSelectedLanguagesAndUnknownArchitectureAssumption() {
        var source = source(projectId);
        when(snapshots.prepare(source)).thenReturn(new ReportSourceSnapshotService.Manifest(sourceId, "a".repeat(64), List.of(), null));
        for (String architecture : List.of("arm64", "sparc")) {
            var identity = AnalysisInputPlan.Identity.of("python,java,rust", architecture, "riscv64", Map.of(), List.of(), "rules-hash");
            var plan = service.createKb(source, identity);
            assertThat(plan.identity()).isEqualTo(identity);
            assertThat(plan.assumptions()).isEqualTo(architecture.equals("sparc") ? List.of("SOURCE_ARCH_DEFAULTED_TO_X86") : List.of());
        }
        for (var manifest : List.of(
                new ReportSourceSnapshotService.Manifest(UUID.randomUUID(), "a".repeat(64), List.of(), null),
                new ReportSourceSnapshotService.Manifest(sourceId, null, List.of(), null),
                new ReportSourceSnapshotService.Manifest(sourceId, "a".repeat(64), List.of(), "SOURCE_CONTEXT_UNAVAILABLE"))) {
            when(snapshots.prepare(source)).thenReturn(manifest);
            assertThatThrownBy(() -> service.createKb(source, AnalysisInputPlan.Identity.of("c", "x86", "riscv64", Map.of(), List.of(), "r1")))
                    .extracting("code").isEqualTo("ANALYSIS_SOURCE_SNAPSHOT_UNAVAILABLE");
        }
    }

    @Test void enhancementRequiresMatchingSourceArchitectureAndArtifactOwnership() {
        var source = source(projectId);
        when(snapshots.prepare(source)).thenReturn(new ReportSourceSnapshotService.Manifest(sourceId, "a".repeat(64), List.of(), null));
        var base = service.create(project(), source, SpecializedAnalyzerType.FLOATING_POINT);
        UUID buildId = UUID.randomUUID();
        var build = buildFixture(buildId, projectId, sourceId, "SUCCEEDED", "SOURCE_ONLY");
        var artifact = new cn.rvport.backend.artifact.StoredArtifactView(UUID.randomUUID(), projectId, null,
                cn.rvport.backend.artifact.StoredArtifactOwnerType.BUILD_VALIDATION, buildId, "COMPILE_COMMANDS_JSON", "b", "k", "application/json",
                2, "b".repeat(64), Map.of("sourceDigest", "a".repeat(64), "sourceArch", "x86"), null);
        assertThat(service.enhance(base, null, List.of(artifact))).isSameAs(base);
        assertThat(service.enhance(base, build, List.of())).isSameAs(base);
        var enhanced = service.enhance(base, build, List.of(artifact));
        assertThat(enhanced.inputOrigin()).isEqualTo(AnalysisInputPlan.InputOrigin.BUILD_ARTIFACTS);
        assertThat(enhanced.artifacts()).hasSize(1);
        assertThat(enhanced.missingEvidence()).containsExactly("BITCODE_UNAVAILABLE");
        assertThat(enhanced.assumptions()).doesNotContain("DEFAULT_ASSUMPTION", "C17_CPP17");
        assertThat(enhanced.originalInputDigest()).isNotEqualTo(base.originalInputDigest());
        for (int invalid = 0; invalid < 6; invalid++) {
            var candidate = new cn.rvport.backend.artifact.StoredArtifactView(artifact.id(), invalid == 0 ? UUID.randomUUID() : projectId,
                    null, invalid == 1 ? cn.rvport.backend.artifact.StoredArtifactOwnerType.SOURCE_REVISION : artifact.ownerType(),
                    invalid == 2 ? UUID.randomUUID() : buildId, invalid == 3 ? "OTHER" : artifact.artifactType(), "b", "k", "application/json", 2,
                    artifact.sha256(), Map.of("sourceDigest", invalid == 4 ? "c".repeat(64) : "a".repeat(64),
                            "sourceArch", invalid == 5 ? "arm64" : "x86"), null);
            assertThat(service.enhance(base, build, List.of(candidate)).missingEvidence()).contains("ANALYSIS_ARTIFACT_IDENTITY_MISMATCH");
        }
        for (var invalid : List.of(buildFixture(buildId,projectId,sourceId,"FAILED","SOURCE_ONLY"),
                buildFixture(buildId,projectId,sourceId,"SUCCEEDED","BOTH"),
                buildFixture(buildId,UUID.randomUUID(),sourceId,"SUCCEEDED","SOURCE_ONLY"),
                buildFixture(buildId,projectId,UUID.randomUUID(),"SUCCEEDED","SOURCE_ONLY")))
            assertThat(service.enhance(base, invalid, List.of(artifact)).inputOrigin()).isEqualTo(AnalysisInputPlan.InputOrigin.SOURCE_DISCOVERY);
    }

    private cn.rvport.backend.buildvalidation.BuildValidationRunView buildFixture(UUID id, UUID project, UUID source, String status, String scope) {
        return new cn.rvport.backend.buildvalidation.BuildValidationRunView(id,project,null,source,UUID.randomUUID(),
                cn.rvport.backend.buildvalidation.BuildValidationScope.valueOf(scope),null,1L,null,"sha",null,"/remote/source",null,
                0,cn.rvport.backend.task.TaskStatus.valueOf(status),null,null,null,null,null,null,null,Map.of(),Map.of());
    }
}
