package cn.rvport.backend.report.review;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import cn.rvport.backend.analysis.*;
import cn.rvport.backend.buildvalidation.*;
import cn.rvport.backend.dependency.DependencyAnalysisRepository;
import cn.rvport.backend.kb.KbScanContextService;
import cn.rvport.backend.project.runtime.*;
import cn.rvport.backend.task.TaskStatus;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReportInputSelectionPolicyTests {
    final UUID project=UUID.randomUUID(),revision=UUID.randomUUID(),sourceEnv=UUID.randomUUID(),targetEnv=UUID.randomUUID(),buildId=UUID.randomUUID();
    final KbScanContextService contexts=mock(KbScanContextService.class);
    final ProjectRuntimeEnvironmentRepository environments=mock(ProjectRuntimeEnvironmentRepository.class);
    final BuildValidationRepository builds=mock(BuildValidationRepository.class);
    final DependencyAnalysisRepository dependencies=mock(DependencyAnalysisRepository.class);
    final SpecializedBitcodeCapabilityResolver capabilities=mock(SpecializedBitcodeCapabilityResolver.class);
    final ReportSourceRepository sources=mock(ReportSourceRepository.class);
    final ReportInputSelectionPolicy policy=new ReportInputSelectionPolicy(contexts,environments,builds,dependencies,capabilities,sources);
    final BuildValidationRunView build=build(TaskStatus.SUCCEEDED);
    private BuildValidationRunView build(TaskStatus status) {
        return new BuildValidationRunView(buildId,project,UUID.randomUUID(),revision,sourceEnv,BuildValidationScope.SOURCE_ONLY,
                null,4L,null,null,null,null,null,null,status,null,null,null,null,null,null,null,Map.of(),Map.of());
    }
    @BeforeEach void setup() {
        when(contexts.current(project)).thenReturn(new KbScanContextService.Selection(project,revision,sourceEnv,4,build));
        when(environments.find(project)).thenReturn(Optional.of(new ProjectRuntimeEnvironmentView(project,sourceEnv,targetEnv,4,null,null,null)));
    }
    @Test void noSourceOrOnlyMissingRunsCannotGenerateAndReadsDoNotTriggerWork() {
        when(contexts.current(project)).thenReturn(new KbScanContextService.Selection(project,null,null,1,null));
        when(environments.find(project)).thenReturn(Optional.empty());
        var selected=policy.current(project);
        assertThat(selected.canGenerate()).isFalse(); assertThat(selected.sources()).hasSize(4);
        assertThat(selected.sources()).allMatch(s->!s.available() && s.reasonCode().equals("ANALYSIS_NOT_READY"));
        verifyNoInteractions(builds,dependencies,capabilities,sources);
    }
    @Test void successfulZeroFindingsIsEligibleAndStableContextIgnoresFailedTasks() {
        UUID run=UUID.randomUUID();
        when(sources.kb(project,revision,sourceEnv,4,buildId)).thenReturn(Optional.of(Map.of("id",run,"status","SUCCEEDED")));
        var first=policy.current(project); var second=policy.current(project);
        assertThat(first.canGenerate()).isTrue(); assertThat(first.candidates()).isEmpty();
        assertThat(first.contextHash()).isEqualTo(second.contextHash());
        when(sources.kb(project,revision,sourceEnv,4,buildId)).thenReturn(Optional.of(Map.of("id",UUID.randomUUID(),"status","SUCCEEDED")));
        assertThat(policy.current(project).contextHash()).isNotEqualTo(first.contextHash());
        when(contexts.current(project)).thenReturn(new KbScanContextService.Selection(project,revision,sourceEnv,4,build(TaskStatus.FAILED)));
        assertThat(policy.current(project).canGenerate()).isFalse();
    }
    @Test void selectsAllSourcesUsingTheirExactBuildEnvironmentArtifactAndSnapshotContext() {
        UUID srcSnapshot=UUID.randomUUID(),dstSnapshot=UUID.randomUUID(),depRun=UUID.randomUUID(),fpRun=UUID.randomUUID(),memRun=UUID.randomUUID();
        when(builds.findLatest(project,revision,sourceEnv,4L,BuildValidationScope.SOURCE_ONLY)).thenReturn(Optional.of(build));

        when(dependencies.findLatestSucceededSnapshotId(project,sourceEnv)).thenReturn(Optional.of(srcSnapshot));
        when(dependencies.findLatestSucceededSnapshotId(project,targetEnv)).thenReturn(Optional.of(dstSnapshot));
        when(sources.dependency(project,revision,targetEnv,4,buildId,srcSnapshot,dstSnapshot)).thenReturn(Optional.of(Map.of("id",depRun,"status","SUCCEEDED")));
        when(capabilities.resolve(buildId)).thenReturn(new SpecializedBitcodeCapability(SpecializedAnalysisMode.AST_BC_MIXED,"PARTIAL",2,1,1,"r",null,null,"set"));
        when(sources.specialized(project,revision,"FLOATING_POINT",buildId,4L,"AST_BC_MIXED","set")).thenReturn(Optional.of(Map.of("id",fpRun,"status","COMPLETE","resultSchemaVersion","2.0")));
        when(sources.specialized(project,revision,"MEMORY_ORDER",buildId,4L,"AST_BC_MIXED","set")).thenReturn(Optional.of(Map.of("id",memRun,"status","COMPLETE_WITH_RISK","resultSchemaVersion","1")));
        when(sources.findings("DEPENDENCY_RESULT",depRun)).thenReturn(List.of(
                finding("TARGET_INSTALLED","LOW"),finding("MISSING","HIGH"),finding("unrecognized","LOW"),finding("MISSING","unrecognized")));
        when(sources.findings("FLOATING_POINT_FINDING",fpRun)).thenReturn(List.of(Map.of("id",UUID.randomUUID(),"findingKind","FINDING")));
        when(sources.findings("MEMORY_ORDER_FINDING",memRun)).thenReturn(List.of(Map.of("id",UUID.randomUUID(),"findingKind","SOURCE_CANDIDATE")));
        var result=policy.current(project);
        assertThat(result.canGenerate()).isTrue(); assertThat(result.candidates()).hasSize(5);
        assertThat(result.sources().get(1).findings()).hasSize(4);
        assertThat(result.candidates().getLast()).containsEntry("findingKind","SOURCE_CANDIDATE");
        assertThat(result.sources().get(2).resultVersion()).isEqualTo("2.0");
        verify(sources).dependency(project,revision,targetEnv,4,buildId,srcSnapshot,dstSnapshot);
        verify(sources).specialized(project,revision,"MEMORY_ORDER",buildId,4L,"AST_BC_MIXED","set");
    }
    @Test void missingDependencySnapshotsOrTargetDoesNotFallBackToOldRun() {
        when(builds.findLatest(project,revision,sourceEnv,4L,BuildValidationScope.SOURCE_ONLY)).thenReturn(Optional.of(build));
        policy.current(project);
        verify(sources,never()).dependency(any(),any(),any(),anyLong(),any(),any(),any());
        when(dependencies.findLatestSucceededSnapshotId(project,sourceEnv)).thenReturn(Optional.of(UUID.randomUUID()));
        policy.current(project);
        verify(sources,never()).dependency(any(),any(),any(),anyLong(),any(),any(),any());
        when(environments.find(project)).thenReturn(Optional.of(new ProjectRuntimeEnvironmentView(project,sourceEnv,null,4,null,null,null)));
        assertThat(policy.current(project).canGenerate()).isFalse();
    }
    @Test void unfinishedAnalysisExposesStatusWithoutSupplyingIssuesOrInvalidatingSuccessfulFacts() {
        var run=UUID.randomUUID();
        for (String status:List.of("FAILED","PARTIAL","RUNNING")) {
            when(sources.kb(project,revision,sourceEnv,4,buildId)).thenReturn(Optional.of(Map.of("id",run,"status",status)));
            var source=policy.current(project).sources().getFirst();
            assertThat(source.available()).isFalse();
            assertThat(source.reasonCode()).isEqualTo("ANALYSIS_"+status);
            assertThat(source.status()).isEqualTo(status);
            verify(sources,never()).findings("KB_FINDING",run);
        }
    }
    @Test void publishedPartialZeroFindingsWithoutBuildIsEligibleAndUnrelatedEnvironmentDoesNotInvalidateIt() {
        UUID run = UUID.randomUUID();
        when(contexts.current(project)).thenReturn(new KbScanContextService.Selection(project, revision, null, 0, null));
        when(environments.find(project)).thenReturn(Optional.empty());
        when(sources.specialized(project, revision, "MEMORY_ORDER", null, null, null, null)).thenReturn(Optional.of(
                Map.of("id", run, "status", "PARTIAL", "resultJsonArtifactId", UUID.randomUUID(),
                        "summary", Map.of("missingEvidence", List.of("BITCODE_UNAVAILABLE")))));
        var first = policy.current(project);
        assertThat(first.canGenerate()).isTrue();
        assertThat(first.sources().getLast().status()).isEqualTo("PARTIAL");
        assertThat(first.candidates()).isEmpty();
        when(environments.find(project)).thenReturn(Optional.of(new ProjectRuntimeEnvironmentView(project,sourceEnv,targetEnv,999,null,null,null)));
        assertThat(policy.current(project).contextHash()).isEqualTo(first.contextHash());
        verify(builds, never()).findLatestSucceededSourceBuild(any(),any(),any(),any());
        verify(builds, never()).findLatestSucceededSourceBuildWithCompileDatabase(any(),any(),any(),any());
    }
    @Test void candidateEvidencePreservesCoverageAndMissingEvidenceWithoutCopyingOperationalFields() {
        UUID run = UUID.randomUUID(), finding = UUID.randomUUID();
        var evidence = Map.of("coverage",Map.of("plannedTuCount",2,"failedTuCount",1),
                "missingEvidence",List.of("BITCODE_UNAVAILABLE"),"analysisReasonCodes",List.of("source_failed"),
                "effectiveInputDigest","fixed-input");
        var summary = new LinkedHashMap<String,Object>(evidence);
        summary.put("durationMs",500);
        when(sources.kb(project,revision,sourceEnv,4,buildId)).thenReturn(Optional.of(Map.of(
                "id",run,"status","SUCCEEDED","summary",summary)));
        when(sources.findings("KB_FINDING",run)).thenReturn(List.of(Map.of("id",finding)));
        assertThat(policy.current(project).candidates().getFirst()).containsEntry("analysisEvidence",evidence);
        when(sources.kb(project,revision,sourceEnv,4,buildId)).thenReturn(Optional.of(Map.of(
                "id",run,"status","SUCCEEDED","summary",Map.of("durationMs",500))));
        assertThat(policy.current(project).candidates().getFirst()).doesNotContainKey("analysisEvidence");
    }
    @Test void completeStatusWithoutPublishedArtifactDoesNotSupplyCandidates() {
        when(sources.kb(project,revision,sourceEnv,4,buildId)).thenReturn(Optional.of(Map.of(
                "id",UUID.randomUUID(),"status","SUCCEEDED","resultAvailable",false)));
        assertThat(policy.current(project).canGenerate()).isFalse();
        verify(sources,never()).findings(any(),any());
    }
    private Map<String,Object> finding(String status,String risk) { return Map.of("id",UUID.randomUUID(),"targetMatchStatus",status,"riskLevel",risk); }
}
