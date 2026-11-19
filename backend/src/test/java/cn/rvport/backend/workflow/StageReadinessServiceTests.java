package cn.rvport.backend.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

import cn.rvport.backend.archive.ArchiveCandidateService;
import cn.rvport.backend.analysis.SpecializedAnalysisRepository;
import cn.rvport.backend.analysis.SpecializedAnalysisToolHealthService;
import cn.rvport.backend.analysis.SpecializedAnalyzerType;
import cn.rvport.backend.analysis.SpecializedAnalysisMode;
import cn.rvport.backend.analysis.SpecializedBitcodeCapability;
import cn.rvport.backend.analysis.SpecializedBitcodeCapabilityResolver;
import cn.rvport.backend.automatedtest.AutomatedTestRepository;
import cn.rvport.backend.buildvalidation.BuildValidationRepository;
import cn.rvport.backend.buildvalidation.BuildValidationRunView;
import cn.rvport.backend.buildvalidation.BuildValidationScope;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.dependency.DependencyAnalysisRepository;
import cn.rvport.backend.kb.KbScanRepository;
import cn.rvport.backend.report.review.ReportInputSelectionPolicy;
import cn.rvport.backend.migration.MigrationInputSourceType;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentResponse;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentService;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentView;
import cn.rvport.backend.report.ReportRepository;
import cn.rvport.backend.source.SourceRepository;
import cn.rvport.backend.source.SourceRevisionType;
import cn.rvport.backend.source.SourceRevisionView;
import cn.rvport.backend.source.deploy.SourceDeployRepository;
import cn.rvport.backend.source.deploy.SourceDeployEnvironmentSummary;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.environment.EnvironmentRole;
import cn.rvport.backend.environment.EnvironmentStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class StageReadinessServiceTests {

    @Test
    void optimizeRemainsViewableWithoutBuildOrTestEvidence() {
        UUID project = UUID.randomUUID(), revision = UUID.randomUUID();
        when(contextService.snapshot(project)).thenReturn(new ProjectContextSnapshot(revision, 1L));
        when(runtimeEnvironmentService.summary(project)).thenReturn(runtime(project, 1L));
        when(sourceRepository.findActive(project)).thenReturn(Optional.of(source(project, revision)));
        var result = service().readiness(project, StageIds.OPTIMIZE, UUID.randomUUID(), false);
        assertThat(result.prerequisites()).singleElement().satisfies(item -> {
            assertThat(item.key()).isEqualTo(StagePrerequisiteKeys.ACTIVE_SOURCE);
            assertThat(item.status()).isEqualTo(StagePrerequisiteStatus.SATISFIED);
        });
        org.mockito.Mockito.verifyNoInteractions(buildValidationRepository, automatedTestRepository);
    }

    @Mock
    private ProjectAccessService projectAccessService;
    @Mock
    private ProjectContextService contextService;
    @Mock
    private SourceRepository sourceRepository;
    @Mock
    private ProjectRuntimeEnvironmentService runtimeEnvironmentService;
    @Mock
    private SourceDeployRepository sourceDeployRepository;
    @Mock
    private BuildValidationRepository buildValidationRepository;
    @Mock
    private KbScanRepository kbScanRepository;
    @Mock
    private DependencyAnalysisRepository dependencyAnalysisRepository;
    @Mock
    private ReportRepository reportRepository;
    @Mock
    private ReportInputSelectionPolicy reportInputs;
    @Mock
    private ArchiveCandidateService archiveCandidateService;
    @Mock
    private AutomatedTestRepository automatedTestRepository;
    @Mock
    private SpecializedAnalysisRepository specializedAnalysisRepository;
    @Mock
    private SpecializedAnalysisToolHealthService specializedToolHealthService;
    @Mock
    private SpecializedBitcodeCapabilityResolver specializedBitcodeCapabilityResolver;

    @Test
    void specializedReadinessReportsAnalyzerAvailabilityForBothAnalyzerTypes() {
        UUID projectId = UUID.randomUUID();
        when(contextService.snapshot(projectId)).thenReturn(new ProjectContextSnapshot(null, 4L));
        when(runtimeEnvironmentService.summary(projectId)).thenReturn(null);
        when(specializedToolHealthService.health(SpecializedAnalyzerType.FLOATING_POINT)).thenReturn(
                new SpecializedAnalysisToolHealthService.ToolHealth(
                        true, "AVAILABLE", List.of(), Map.of("scanner", "scanner"), Map.of("scanner", "v1")));
        when(specializedToolHealthService.health(SpecializedAnalyzerType.MEMORY_ORDER)).thenReturn(
                new SpecializedAnalysisToolHealthService.ToolHealth(
                        false, "UNAVAILABLE", List.of("worker:NOT_EXECUTABLE"), Map.of(), Map.of()));

        StageReadinessResponse floating = serviceWithSpecializedHealth().readiness(
                projectId, StageIds.FLOATING_POINT_ANALYSIS, UUID.randomUUID(), false);
        StageReadinessResponse memory = serviceWithSpecializedHealth().readiness(
                projectId, StageIds.MEMORY_ORDER_ANALYSIS, UUID.randomUUID(), false);

        assertThat(floating.prerequisites()).filteredOn(item ->
                        item.key().equals(StagePrerequisiteKeys.SPECIALIZED_ANALYZER))
                .singleElement().satisfies(item -> {
                    assertThat(item.status()).isEqualTo(StagePrerequisiteStatus.SATISFIED);
                    assertThat(item.reasonCode()).isEqualTo("SPECIALIZED_ANALYZER_AVAILABLE");
                    assertThat(item.details()).containsEntry("analyzerType", "FLOATING_POINT")
                            .containsEntry("toolStatus", "AVAILABLE");
                });
        assertThat(memory.prerequisites()).filteredOn(item ->
                        item.key().equals(StagePrerequisiteKeys.SPECIALIZED_ANALYZER))
                .singleElement().satisfies(item -> {
                    assertThat(item.status()).isEqualTo(StagePrerequisiteStatus.UNAVAILABLE);
                    assertThat(item.reasonCode()).isEqualTo("SPECIALIZED_ANALYZER_UNAVAILABLE");
                    assertThat(item.details()).containsEntry("analyzerType", "MEMORY_ORDER");
                });
    }

    @Test
    void specializedReadinessIncludesResolvedBitcodeCapability() {
        UUID projectId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID sourceEnvironmentId = UUID.randomUUID();
        BuildValidationRunView build = x86BuildRun(
                projectId, revisionId, sourceEnvironmentId, TaskStatus.SUCCEEDED);
        when(contextService.snapshot(projectId)).thenReturn(new ProjectContextSnapshot(revisionId, 4L));
        when(runtimeEnvironmentService.summary(projectId))
                .thenReturn(runtime(projectId, 4L, sourceEnvironmentId, true, null, false));
        when(sourceRepository.findActive(projectId)).thenReturn(Optional.of(source(projectId, revisionId)));
        when(buildValidationRepository.findLatest(
                projectId, revisionId, sourceEnvironmentId, 4L, BuildValidationScope.SOURCE_ONLY))
                .thenReturn(Optional.of(build));
        when(buildValidationRepository.findLatestSucceededSourceBuildWithCompileDatabase(
                projectId, revisionId, sourceEnvironmentId, 4L)).thenReturn(Optional.of(build));
        when(specializedBitcodeCapabilityResolver.resolve(build.id())).thenReturn(
                new SpecializedBitcodeCapability(SpecializedAnalysisMode.AST_BC_MIXED, "PARTIAL",
                        4, 2, 2, "BITCODE_PARTIAL_COVERAGE", null, null, "set"));
        when(specializedToolHealthService.health(SpecializedAnalyzerType.FLOATING_POINT)).thenReturn(
                new SpecializedAnalysisToolHealthService.ToolHealth(
                        true, "AVAILABLE", List.of(), Map.of(), Map.of()));

        StageReadinessResponse response = serviceWithBitcodeResolver().readiness(
                projectId, StageIds.FLOATING_POINT_ANALYSIS, UUID.randomUUID(), false);

        assertThat(response.prerequisites())
                .filteredOn(item -> item.key().equals(StagePrerequisiteKeys.SPECIALIZED_ANALYSIS_EVIDENCE))
                .singleElement()
                .satisfies(item -> assertThat(item.details())
                        .containsEntry("analysisMode", "AST_BC_MIXED")
                        .containsEntry("bcStatus", "PARTIAL")
                        .containsEntry("astOnlyCount", 2));
    }

    @Test
    void prerequisiteFactoryNormalizesNullDetailsAndKeepsExplicitDetails() {
        StagePrerequisiteResponse empty = StagePrerequisiteResponse.of(
                "key", StagePrerequisiteStatus.MISSING, null, "missing", null);
        StagePrerequisiteResponse populated = StagePrerequisiteResponse.of(
                "key", StagePrerequisiteStatus.SATISFIED, UUID.randomUUID(), "ready", Map.of("count", 1));

        assertThat(empty.details()).isEmpty();
        assertThat(populated.details()).containsEntry("count", 1);
    }

    @Test
    void kbReadinessRequiresActiveSourceAndSuccessfulX86Build() {
        UUID projectId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        UUID sourceEnvironmentId = UUID.randomUUID();
        when(contextService.snapshot(projectId)).thenReturn(new ProjectContextSnapshot(sourceRevisionId, 3L));
        when(runtimeEnvironmentService.summary(projectId))
                .thenReturn(runtime(projectId, 3L, sourceEnvironmentId, true, null, false));
        when(sourceRepository.findActive(projectId)).thenReturn(Optional.of(source(projectId, sourceRevisionId)));
        BuildValidationRunView buildRun = x86BuildRun(
                projectId, sourceRevisionId, sourceEnvironmentId, TaskStatus.SUCCEEDED);
        when(buildValidationRepository.findLatest(
                projectId, sourceRevisionId, sourceEnvironmentId, 3L, BuildValidationScope.SOURCE_ONLY))
                .thenReturn(Optional.of(buildRun));

        StageReadinessResponse response = service().readiness(projectId, StageIds.KB, UUID.randomUUID(), false);

        assertThat(response.stageId()).isEqualTo(StageIds.KB);
        assertThat(response.context().sourceRevisionId()).isEqualTo(sourceRevisionId);
        assertThat(response.prerequisites()).filteredOn(item -> item.key().equals(StagePrerequisiteKeys.ACTIVE_SOURCE))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.status()).isEqualTo(StagePrerequisiteStatus.SATISFIED);
                    assertThat(item.referenceId()).isEqualTo(sourceRevisionId);
                });
        assertThat(response.prerequisites()).filteredOn(item -> item.key().equals(StagePrerequisiteKeys.X86_BUILD))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.status()).isEqualTo(StagePrerequisiteStatus.SATISFIED);
                    assertThat(item.referenceId()).isEqualTo(buildRun.id());
                    assertThat(item.reasonCode()).isEqualTo("X86_BUILD_READY");
                });
    }

    @Test
    void kbReadinessReflectsRunningFailedAndMissingX86Build() {
        UUID projectId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        UUID sourceEnvironmentId = UUID.randomUUID();
        when(contextService.snapshot(projectId)).thenReturn(new ProjectContextSnapshot(sourceRevisionId, 3L));
        when(runtimeEnvironmentService.summary(projectId))
                .thenReturn(runtime(projectId, 3L, sourceEnvironmentId, true, null, false));
        when(sourceRepository.findActive(projectId)).thenReturn(Optional.of(source(projectId, sourceRevisionId)));
        when(buildValidationRepository.findLatest(
                projectId, sourceRevisionId, sourceEnvironmentId, 3L, BuildValidationScope.SOURCE_ONLY))
                .thenReturn(
                        Optional.of(x86BuildRun(projectId, sourceRevisionId, sourceEnvironmentId, TaskStatus.RUNNING)),
                        Optional.of(x86BuildRun(projectId, sourceRevisionId, sourceEnvironmentId, TaskStatus.FAILED)),
                        Optional.empty());

        assertKbX86Build(projectId, StagePrerequisiteStatus.RUNNING, "X86_BUILD_RUNNING");
        assertKbX86Build(projectId, StagePrerequisiteStatus.FAILED, "X86_BUILD_FAILED");
        assertKbX86Build(projectId, StagePrerequisiteStatus.MISSING, "X86_BUILD_MISSING");
    }

    @Test
    void buildReadinessReportsMissingTargetEnvironment() {
        UUID projectId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        ProjectRuntimeEnvironmentResponse runtime = new ProjectRuntimeEnvironmentResponse(
                projectId,
                new SourceDeployEnvironmentSummary(UUID.randomUUID(), "x86", EnvironmentRole.SOURCE_X86, "x86_64", EnvironmentStatus.AVAILABLE, true),
                null,
                4L,
                false,
                List.of("targetEnvironment"),
                UUID.randomUUID(),
                OffsetDateTime.now()
        );
        when(contextService.snapshot(projectId)).thenReturn(new ProjectContextSnapshot(sourceRevisionId, 4L));
        when(runtimeEnvironmentService.summary(projectId)).thenReturn(runtime);
        when(sourceRepository.findActive(projectId)).thenReturn(Optional.of(source(projectId, sourceRevisionId)));

        StageReadinessResponse response = service().readiness(projectId, StageIds.BUILD, UUID.randomUUID(), false);

        assertThat(response.prerequisites())
                .anySatisfy(item -> assertThat(item.key()).isEqualTo(StagePrerequisiteKeys.SOURCE_ENVIRONMENT))
                .anySatisfy(item -> {
                    assertThat(item.key()).isEqualTo(StagePrerequisiteKeys.TARGET_ENVIRONMENT);
                    assertThat(item.status()).isEqualTo(StagePrerequisiteStatus.MISSING);
                    assertThat(item.reasonCode()).isEqualTo("TARGET_ENVIRONMENT_MISSING");
                });
    }

    @Test
    void requireSatisfiedReturnsStableErrorDetails() {
        UUID projectId = UUID.randomUUID();
        when(contextService.snapshot(projectId)).thenReturn(new ProjectContextSnapshot(null, 1L));
        when(runtimeEnvironmentService.summary(projectId)).thenReturn(ProjectRuntimeEnvironmentResponse.from(
                cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentView.empty(projectId),
                null,
                null
        ));
        when(sourceRepository.findActive(projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().requireSatisfied(
                projectId,
                StageIds.KB,
                List.of(StagePrerequisiteKeys.ACTIVE_SOURCE),
                UUID.randomUUID(),
                false
        ))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    ApiException apiException = (ApiException) error;
                    assertThat(apiException.code()).isEqualTo("PREREQUISITE_NOT_SATISFIED");
                    assertThat(apiException.details()).containsEntry("stageId", StageIds.KB);
                    assertThat(apiException.details()).containsKey("prerequisite");
                    StagePrerequisiteResponse prerequisite = (StagePrerequisiteResponse) apiException.details().get("prerequisite");
                    assertThat(prerequisite.key()).isEqualTo(StagePrerequisiteKeys.ACTIVE_SOURCE);
                    assertThat(prerequisite.status()).isEqualTo(StagePrerequisiteStatus.MISSING);
                    assertThat(prerequisite.reasonCode()).isEqualTo("ACTIVE_SOURCE_MISSING");
                });
    }

    @Test void reportUsesSameFourSourceSelectionAndAllowsSuccessfulZeroFindings() {
        UUID project=UUID.randomUUID(),source=UUID.randomUUID();
        when(contextService.snapshot(project)).thenReturn(new ProjectContextSnapshot(source,7L));
        when(runtimeEnvironmentService.summary(project)).thenReturn(runtime(project,7L));
        var missing=new ReportInputSelectionPolicy.Source("KB_FINDING",null,null,null,"ANALYSIS_NOT_READY",Map.of(),List.of(),List.of());
        var memory=new ReportInputSelectionPolicy.Source("MEMORY_ORDER_FINDING",UUID.randomUUID(),"COMPLETE","v1",null,Map.of(),List.of(),List.of());
        var service=service();
        when(reportInputs.current(project)).thenReturn(new ReportInputSelectionPolicy.Selection(project,source,"ctx",List.of(missing,memory)));
        var prerequisites=service.readiness(project,StageIds.REPORT,UUID.randomUUID(),false).prerequisites();
        assertThat(prerequisites).anySatisfy(f->{assertThat(f.key()).isEqualTo("KB_SCAN");assertThat(f.status()).isEqualTo(StagePrerequisiteStatus.MISSING);});
        assertThat(prerequisites).anySatisfy(f->{assertThat(f.key()).isEqualTo("INCLUDED_FINDINGS");assertThat(f.details()).containsEntry("count",0L);
            assertThat(f.status()).isEqualTo(StagePrerequisiteStatus.SATISFIED);});
        when(reportInputs.current(project)).thenReturn(new ReportInputSelectionPolicy.Selection(project,source,"ctx",List.of(missing)));
        assertThatThrownBy(()->service.requireSatisfied(project,StageIds.REPORT,List.of("INCLUDED_FINDINGS"),UUID.randomUUID(),false))
                .isInstanceOf(PrerequisiteNotSatisfiedException.class);
    }

    @Test
    void reportReadinessStillRejectsMissingSourceRevision() {
        UUID projectId = UUID.randomUUID();
        when(contextService.snapshot(projectId)).thenReturn(new ProjectContextSnapshot(null, 1L));
        when(runtimeEnvironmentService.summary(projectId)).thenReturn(runtime(projectId, 1L));

        StageReadinessResponse response = service().readiness(projectId, StageIds.REPORT, UUID.randomUUID(), false);

        assertThat(response.prerequisites())
                .filteredOn(item -> item.key().equals(StagePrerequisiteKeys.INCLUDED_FINDINGS))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.status()).isEqualTo(StagePrerequisiteStatus.MISSING);
                    assertThat(item.reasonCode()).isEqualTo("ACTIVE_SOURCE_MISSING");
                });
    }

    @Test
    void everyStageProducesReadinessForAnEmptyProjectContext() {
        UUID projectId = UUID.randomUUID();
        when(contextService.snapshot(projectId)).thenReturn(new ProjectContextSnapshot(null, 0L));
        when(runtimeEnvironmentService.summary(projectId)).thenReturn(null);
        when(sourceRepository.findActive(projectId)).thenReturn(Optional.empty());
        for (String stage : List.of(StageIds.KB, StageIds.X86_BUILD, StageIds.RV_DEPENDENCY_ANALYSIS,
                StageIds.REPORT, StageIds.BUILD, StageIds.TEST, StageIds.OPTIMIZE, StageIds.ARCHIVE)) {
            StageReadinessResponse response = service().readiness(projectId, " " + stage + " ", UUID.randomUUID(), false);
            assertThat(response.stageId()).isEqualTo(stage);
            assertThat(response.prerequisites()).isNotEmpty();
        }
    }

    @Test
    void rejectsMissingBlankAndUnknownStageIds() {
        UUID projectId = UUID.randomUUID();
        assertThatThrownBy(() -> service().readiness(projectId, null, UUID.randomUUID(), false))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("STAGE_ID_REQUIRED");
        assertThatThrownBy(() -> service().readiness(projectId, " ", UUID.randomUUID(), false))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("STAGE_ID_REQUIRED");
        when(contextService.snapshot(projectId)).thenReturn(new ProjectContextSnapshot(null, 0L));
        assertThatThrownBy(() -> service().readiness(projectId, "UNKNOWN", UUID.randomUUID(), false))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("STAGE_ID_INVALID");
    }

    @Test
    void sourceAndTargetEnvironmentReportReadyAndUnavailableStates() {
        UUID projectId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(contextService.snapshot(projectId)).thenReturn(new ProjectContextSnapshot(UUID.randomUUID(), 2L));
        when(sourceRepository.findActive(projectId)).thenReturn(Optional.empty());
        when(runtimeEnvironmentService.summary(projectId)).thenReturn(runtime(projectId, 2L, sourceId, true, targetId, false));

        StageReadinessResponse response = service().readiness(projectId, StageIds.BUILD, UUID.randomUUID(), false);

        assertThat(response.prerequisites()).filteredOn(item -> item.key().equals(StagePrerequisiteKeys.SOURCE_ENVIRONMENT))
                .singleElement().extracting(StagePrerequisiteResponse::status).isEqualTo(StagePrerequisiteStatus.SATISFIED);
        assertThat(response.prerequisites()).filteredOn(item -> item.key().equals(StagePrerequisiteKeys.TARGET_ENVIRONMENT))
                .singleElement().extracting(StagePrerequisiteResponse::status).isEqualTo(StagePrerequisiteStatus.UNAVAILABLE);
    }

    @Test
    void x86BuildIncludesSuccessfulDeploymentWhenContextIsComplete() {
        UUID projectId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        var deployment = new cn.rvport.backend.source.deploy.SourceDeployRunView(
                UUID.randomUUID(), projectId, UUID.randomUUID(), revisionId, sourceId, 3L, "/tmp/source",
                cn.rvport.backend.source.deploy.SourceDeployStrategy.TAR_OVER_SSH, false,
                cn.rvport.backend.task.TaskStatus.SUCCEEDED, 1, 10L, 0, null, null, null, OffsetDateTime.now());
        when(contextService.snapshot(projectId)).thenReturn(new ProjectContextSnapshot(revisionId, 3L));
        when(runtimeEnvironmentService.summary(projectId)).thenReturn(runtime(projectId, 3L, sourceId, true, null, false));
        when(sourceDeployRepository.findLatestSucceeded(projectId, revisionId, sourceId, 3L)).thenReturn(Optional.of(deployment));

        StageReadinessResponse response = service().readiness(projectId, StageIds.X86_BUILD, UUID.randomUUID(), false);

        assertThat(response.prerequisites()).anySatisfy(item -> {
            if (item.key().equals(StagePrerequisiteKeys.SOURCE_DEPLOYMENT)) {
                assertThat(item.status()).isEqualTo(StagePrerequisiteStatus.SATISFIED);
            }
        });
    }

    @Test
    void archiveReadinessDistinguishesAvailableAndMissingCandidates() {
        UUID projectId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        when(contextService.snapshot(projectId)).thenReturn(new ProjectContextSnapshot(revisionId, 1L));
        when(runtimeEnvironmentService.summary(projectId)).thenReturn(null);
        var candidate = new cn.rvport.backend.archive.ArchiveCandidateView(UUID.randomUUID(), projectId, revisionId,
                cn.rvport.backend.archive.ArchiveNodeType.KB_SCAN,
                cn.rvport.backend.archive.ArchiveCandidateType.REPORT, "key", "KB_SCAN", UUID.randomUUID(),
                "report", java.util.Map.of(), List.of(), cn.rvport.backend.archive.ArchiveCandidateStatus.ACTIVE,
                OffsetDateTime.now(), OffsetDateTime.now());
        when(archiveCandidateService.listActive(projectId, revisionId)).thenReturn(List.of(candidate), List.of());

        assertThat(service().readiness(projectId, StageIds.ARCHIVE, UUID.randomUUID(), false).prerequisites())
                .singleElement().extracting(StagePrerequisiteResponse::status).isEqualTo(StagePrerequisiteStatus.SATISFIED);
        assertThat(service().readiness(projectId, StageIds.ARCHIVE, UUID.randomUUID(), false).prerequisites())
                .singleElement().extracting(StagePrerequisiteResponse::status).isEqualTo(StagePrerequisiteStatus.MISSING);
    }

    @Test
    void requireSatisfiedAcceptsKnownSatisfiedKeyAndRejectsUnknownRequiredKey() {
        UUID projectId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        when(contextService.snapshot(projectId)).thenReturn(new ProjectContextSnapshot(revisionId, 1L));
        when(runtimeEnvironmentService.summary(projectId)).thenReturn(null);
        when(sourceRepository.findActive(projectId)).thenReturn(Optional.of(source(projectId, revisionId)));

        assertThatCode(() -> service().requireSatisfied(projectId, StageIds.KB,
                List.of(StagePrerequisiteKeys.ACTIVE_SOURCE), UUID.randomUUID(), false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> service().requireSatisfied(projectId, StageIds.KB,
                List.of("UNKNOWN_KEY"), UUID.randomUUID(), false))
                .isInstanceOf(PrerequisiteNotSatisfiedException.class);
    }

    @Test
    void testReadinessUsesLatestAutomatedTestStatusForRunningAndFailure() {
        UUID projectId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        var running = automatedTestRun(runId, projectId, cn.rvport.backend.task.TaskStatus.RUNNING);
        var failed = automatedTestRun(runId, projectId, cn.rvport.backend.task.TaskStatus.FAILED);
        when(contextService.snapshot(projectId)).thenReturn(new ProjectContextSnapshot(revisionId, 4L));
        when(runtimeEnvironmentService.summary(projectId)).thenReturn(null);
        when(automatedTestRepository.findLatest(projectId, revisionId, 4L))
                .thenReturn(Optional.of(running), Optional.of(failed));

        assertThat(service().readiness(projectId, StageIds.TEST, UUID.randomUUID(), false).prerequisites())
                .singleElement().extracting(StagePrerequisiteResponse::status).isEqualTo(StagePrerequisiteStatus.RUNNING);
        assertThat(service().readiness(projectId, StageIds.TEST, UUID.randomUUID(), false).prerequisites())
                .singleElement().extracting(StagePrerequisiteResponse::status).isEqualTo(StagePrerequisiteStatus.FAILED);
    }

    @Test
    void testReadinessUsesCompletedAutomatedTestStatuses() {
        UUID projectId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        when(contextService.snapshot(projectId)).thenReturn(new ProjectContextSnapshot(revisionId, 4L));
        when(runtimeEnvironmentService.summary(projectId)).thenReturn(null);
        when(automatedTestRepository.findLatest(projectId, revisionId, 4L))
                .thenReturn(Optional.of(automatedTestRun(UUID.randomUUID(), projectId, TaskStatus.SUCCEEDED)),
                        Optional.of(automatedTestRun(UUID.randomUUID(), projectId, TaskStatus.CANCELLED)),
                        Optional.of(automatedTestRun(UUID.randomUUID(), projectId, TaskStatus.TIMEOUT)));

        assertTestPrerequisite(projectId, StagePrerequisiteStatus.SATISFIED, "BUILD_VALIDATION_READY");
        assertTestPrerequisite(projectId, StagePrerequisiteStatus.FAILED, "BUILD_VALIDATION_CANCELLED");
        assertTestPrerequisite(projectId, StagePrerequisiteStatus.FAILED, "BUILD_VALIDATION_TIMEOUT");
    }

    @Test
    void testReadinessFallsBackToPairedBuildValidationResults() {
        UUID projectId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        ProjectRuntimeEnvironmentResponse runtime = runtime(projectId, 4L, sourceId, true, targetId, true);
        when(contextService.snapshot(projectId)).thenReturn(new ProjectContextSnapshot(revisionId, 4L));
        when(runtimeEnvironmentService.summary(projectId)).thenReturn(runtime);
        when(automatedTestRepository.findLatest(projectId, revisionId, 4L)).thenReturn(Optional.empty());

        BuildValidationRunView sourceSucceeded = buildRun(projectId, revisionId, sourceId, TaskStatus.SUCCEEDED,
                OffsetDateTime.now().minusMinutes(2));
        BuildValidationRunView targetSucceeded = buildRun(projectId, revisionId, targetId, TaskStatus.SUCCEEDED,
                OffsetDateTime.now().minusMinutes(1));
        when(buildValidationRepository.findLatest(projectId, revisionId, sourceId, 4L, BuildValidationScope.BOTH))
                .thenReturn(Optional.of(sourceSucceeded));
        when(buildValidationRepository.findLatest(projectId, revisionId, targetId, 4L, BuildValidationScope.BOTH))
                .thenReturn(Optional.of(targetSucceeded));

        StagePrerequisiteResponse ready = service().readiness(projectId, StageIds.TEST, UUID.randomUUID(), false)
                .prerequisites().getFirst();
        assertThat(ready.status()).isEqualTo(StagePrerequisiteStatus.SATISFIED);
        assertThat(ready.details()).containsEntry("sourceBuildRunId", sourceSucceeded.id())
                .containsEntry("targetBuildRunId", targetSucceeded.id());

        BuildValidationRunView sourceFailed = buildRun(projectId, revisionId, sourceId, TaskStatus.FAILED,
                OffsetDateTime.now());
        when(buildValidationRepository.findLatest(projectId, revisionId, sourceId, 4L, BuildValidationScope.BOTH))
                .thenReturn(Optional.of(sourceFailed));
        assertTestPrerequisite(projectId, StagePrerequisiteStatus.FAILED, "BUILD_VALIDATION_FAILED");

        when(buildValidationRepository.findLatest(projectId, revisionId, sourceId, 4L, BuildValidationScope.BOTH))
                .thenReturn(Optional.empty());
        when(buildValidationRepository.findLatest(projectId, revisionId, targetId, 4L, BuildValidationScope.BOTH))
                .thenReturn(Optional.empty());
        assertTestPrerequisite(projectId, StagePrerequisiteStatus.MISSING, "BUILD_VALIDATION_MISSING");
    }

    @Test
    void latestBuildSelectionHandlesMissingTimestampsAndOrdering() {
        StageReadinessService service = service();
        UUID projectId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        BuildValidationRunView noTime = buildRun(projectId, revisionId, UUID.randomUUID(), TaskStatus.RUNNING, null);
        BuildValidationRunView old = buildRun(projectId, revisionId, UUID.randomUUID(), TaskStatus.RUNNING,
                OffsetDateTime.now().minusDays(1));
        BuildValidationRunView recent = buildRun(projectId, revisionId, UUID.randomUUID(), TaskStatus.RUNNING,
                OffsetDateTime.now());

        assertThat((BuildValidationRunView) ReflectionTestUtils.invokeMethod(service, "latest", noTime, recent))
                .isSameAs(recent);
        assertThat((BuildValidationRunView) ReflectionTestUtils.invokeMethod(service, "latest", recent, noTime))
                .isSameAs(recent);
        assertThat((BuildValidationRunView) ReflectionTestUtils.invokeMethod(service, "latest", recent, old))
                .isSameAs(recent);
        assertThat((BuildValidationRunView) ReflectionTestUtils.invokeMethod(service, "latest", old, recent))
                .isSameAs(recent);
    }

    @Test
    void prerequisiteStatusClassifiesEveryActiveState() {
        StageReadinessService service = service();
        assertThat((StagePrerequisiteStatus) ReflectionTestUtils.invokeMethod(service, "status", TaskStatus.PENDING))
                .isEqualTo(StagePrerequisiteStatus.RUNNING);
        assertThat((StagePrerequisiteStatus) ReflectionTestUtils.invokeMethod(service, "status", TaskStatus.QUEUED))
                .isEqualTo(StagePrerequisiteStatus.RUNNING);
        assertThat((StagePrerequisiteStatus) ReflectionTestUtils.invokeMethod(service, "status", TaskStatus.RUNNING))
                .isEqualTo(StagePrerequisiteStatus.RUNNING);
        assertThat((StagePrerequisiteStatus) ReflectionTestUtils.invokeMethod(service, "status", TaskStatus.SUCCEEDED))
                .isEqualTo(StagePrerequisiteStatus.SATISFIED);
        assertThat((StagePrerequisiteStatus) ReflectionTestUtils.invokeMethod(service, "status", TaskStatus.FAILED))
                .isEqualTo(StagePrerequisiteStatus.FAILED);
    }

    @Test
    void dependencyReadinessExplainsEveryMissingX86BuildPrerequisite() {
        UUID projectId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        when(contextService.snapshot(projectId)).thenReturn(new ProjectContextSnapshot(null, 4L));
        when(runtimeEnvironmentService.summary(projectId)).thenReturn(runtime(projectId, 4L, sourceId, true, targetId, true));
        assertDependencyReason(projectId, "ACTIVE_SOURCE_MISSING");

        when(contextService.snapshot(projectId)).thenReturn(new ProjectContextSnapshot(revisionId, 4L));
        when(runtimeEnvironmentService.summary(projectId)).thenReturn(runtime(projectId, 4L, null, false, targetId, true));
        assertDependencyReason(projectId, "SOURCE_ENVIRONMENT_MISSING");

        when(runtimeEnvironmentService.summary(projectId)).thenReturn(runtime(projectId, 4L, sourceId, true, targetId, true));
        when(buildValidationRepository.findLatest(projectId, revisionId, sourceId, 4L, BuildValidationScope.SOURCE_ONLY))
                .thenReturn(Optional.empty());
        assertDependencyReason(projectId, "X86_BUILD_MISSING");

        BuildValidationRunView cancelled = buildRun(projectId, revisionId, sourceId, TaskStatus.CANCELLED,
                OffsetDateTime.now());
        when(buildValidationRepository.findLatest(projectId, revisionId, sourceId, 4L, BuildValidationScope.SOURCE_ONLY))
                .thenReturn(Optional.of(cancelled));
        assertDependencyReason(projectId, "X86_BUILD_CANCELLED");
    }

    @Test
    void environmentPrerequisitesHandleConfiguredIdMissingFromSummary() {
        UUID projectId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(runtimeEnvironmentService.summary(projectId)).thenReturn(new ProjectRuntimeEnvironmentResponse(
                projectId, null, null, 4L, false, List.of(), null, null));

        ProjectRuntimeEnvironmentView runtime = new ProjectRuntimeEnvironmentView(
                projectId, sourceId, targetId, 4L, null, null, null);
        StagePrerequisiteResponse source = ReflectionTestUtils.invokeMethod(service(), "sourceEnvironment", projectId, runtime);
        StagePrerequisiteResponse target = ReflectionTestUtils.invokeMethod(service(), "targetEnvironment", projectId, runtime);

        assertThat(source.reasonCode()).isEqualTo("SOURCE_ENVIRONMENT_MISSING");
        assertThat(target.reasonCode()).isEqualTo("TARGET_ENVIRONMENT_MISSING");
    }

    @Test
    void sourceEnvironmentReportsConfiguredButDisabledEnvironment() {
        UUID projectId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        when(runtimeEnvironmentService.summary(projectId))
                .thenReturn(runtime(projectId, 4L, sourceId, false, null, false));
        ProjectRuntimeEnvironmentView runtime = new ProjectRuntimeEnvironmentView(
                projectId, sourceId, null, 4L, null, null, null);

        StagePrerequisiteResponse source = ReflectionTestUtils.invokeMethod(
                service(), "sourceEnvironment", projectId, runtime);

        assertThat(source.status()).isEqualTo(StagePrerequisiteStatus.UNAVAILABLE);
        assertThat(source.reasonCode()).isEqualTo("SOURCE_ENVIRONMENT_UNAVAILABLE");
    }

    private void assertDependencyReason(UUID projectId, String reason) {
        assertThat(service().readiness(projectId, StageIds.RV_DEPENDENCY_ANALYSIS, UUID.randomUUID(), false)
                .prerequisites().getFirst().reasonCode()).isEqualTo(reason);
    }

    private void assertKbX86Build(UUID projectId, StagePrerequisiteStatus status, String reason) {
        assertThat(service().readiness(projectId, StageIds.KB, UUID.randomUUID(), false).prerequisites())
                .filteredOn(item -> item.key().equals(StagePrerequisiteKeys.X86_BUILD))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.status()).isEqualTo(status);
                    assertThat(item.reasonCode()).isEqualTo(reason);
                });
    }

    private void assertTestPrerequisite(UUID projectId, StagePrerequisiteStatus status, String reason) {
        assertThat(service().readiness(projectId, StageIds.TEST, UUID.randomUUID(), false).prerequisites())
                .singleElement().satisfies(item -> {
                    assertThat(item.status()).isEqualTo(status);
                    assertThat(item.reasonCode()).isEqualTo(reason);
                });
    }

    private StageReadinessService withKbContext(StageReadinessService service) {
        var runtimes = org.mockito.Mockito.mock(cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentRepository.class);
        org.mockito.Mockito.lenient().when(runtimes.find(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            UUID project = call.getArgument(0);
            var runtime = runtimeEnvironmentService.summary(project);
            return runtime == null ? Optional.empty() : Optional.of(new ProjectRuntimeEnvironmentView(project,
                    runtime.sourceEnvironment() == null ? null : runtime.sourceEnvironment().id(),
                    runtime.targetEnvironment() == null ? null : runtime.targetEnvironment().id(),
                    runtime.version(), null, null, null));
        });
        ReflectionTestUtils.setField(service, "scanContextService",
                new cn.rvport.backend.kb.KbScanContextService(sourceRepository, runtimes, buildValidationRepository, kbScanRepository));
        return service;
    }

    private StageReadinessService service() {
        return withKbContext(new StageReadinessService(
                projectAccessService,
                contextService,
                sourceRepository,
                runtimeEnvironmentService,
                sourceDeployRepository,
                buildValidationRepository,
                kbScanRepository,
                dependencyAnalysisRepository,
                reportRepository,
                reportInputs,
                archiveCandidateService,
                automatedTestRepository
        ));
    }

    private StageReadinessService serviceWithSpecializedHealth() {
        return withKbContext(new StageReadinessService(
                projectAccessService,
                contextService,
                sourceRepository,
                runtimeEnvironmentService,
                sourceDeployRepository,
                buildValidationRepository,
                kbScanRepository,
                dependencyAnalysisRepository,
                reportRepository,
                reportInputs,
                archiveCandidateService,
                automatedTestRepository,
                specializedAnalysisRepository,
                specializedToolHealthService
        ));
    }

    private StageReadinessService serviceWithBitcodeResolver() {
        return withKbContext(new StageReadinessService(
                projectAccessService, contextService, sourceRepository, runtimeEnvironmentService,
                sourceDeployRepository, buildValidationRepository, kbScanRepository,
                dependencyAnalysisRepository, reportRepository, reportInputs,
                archiveCandidateService, automatedTestRepository, specializedAnalysisRepository,
                specializedToolHealthService, specializedBitcodeCapabilityResolver));
    }

    private ProjectRuntimeEnvironmentResponse runtime(UUID projectId, long version) {
        return new ProjectRuntimeEnvironmentResponse(
                projectId,
                null,
                null,
                version,
                false,
                List.of("sourceEnvironment", "targetEnvironment"),
                null,
                null
        );
    }

    private ProjectRuntimeEnvironmentResponse runtime(UUID projectId, long version, UUID sourceId,
                                                       boolean sourceEnabled, UUID targetId, boolean targetEnabled) {
        return new ProjectRuntimeEnvironmentResponse(projectId,
                sourceId == null ? null : new SourceDeployEnvironmentSummary(sourceId, "source", EnvironmentRole.SOURCE_X86,
                        "x86_64", EnvironmentStatus.AVAILABLE, sourceEnabled),
                targetId == null ? null : new SourceDeployEnvironmentSummary(targetId, "target", EnvironmentRole.TARGET_RISCV,
                        "riscv64", EnvironmentStatus.AVAILABLE, targetEnabled),
                version, sourceId != null && targetId != null && sourceEnabled && targetEnabled, List.of(), null, null);
    }

    private cn.rvport.backend.automatedtest.AutomatedTestRunView automatedTestRun(
            UUID id, UUID projectId, cn.rvport.backend.task.TaskStatus status) {
        return new cn.rvport.backend.automatedtest.AutomatedTestRunView(id, projectId, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 4L, UUID.randomUUID(),
                cn.rvport.backend.automatedtest.TestBatchRole.SOURCE, "environment", "make test", null, 60,
                status == cn.rvport.backend.task.TaskStatus.SUCCEEDED ? 0 : null, null, null, null, status,
                null, null, null, null, List.of(), null, null);
    }

    private BuildValidationRunView buildRun(UUID projectId, UUID revisionId, UUID environmentId,
                                             TaskStatus status, OffsetDateTime createdAt) {
        return new BuildValidationRunView(
                UUID.randomUUID(), projectId, UUID.randomUUID(), revisionId, environmentId,
                BuildValidationScope.BOTH, null, 4L, UUID.randomUUID(), "hash", "make", "/tmp/source",
                60, status == TaskStatus.SUCCEEDED ? 0 : null, status, null, null, null, null,
                null, null, createdAt, java.util.Map.of(), java.util.Map.of()
        );
    }

    private BuildValidationRunView x86BuildRun(
            UUID projectId,
            UUID revisionId,
            UUID environmentId,
            TaskStatus status
    ) {
        return new BuildValidationRunView(
                UUID.randomUUID(), projectId, UUID.randomUUID(), revisionId, environmentId,
                BuildValidationScope.SOURCE_ONLY, null, 3L, UUID.randomUUID(), "hash", "make", "/tmp/source",
                60, status == TaskStatus.SUCCEEDED ? 0 : null, status, null, null, null, null,
                null, null, OffsetDateTime.now(), java.util.Map.of(), java.util.Map.of()
        );
    }

    private SourceRevisionView source(UUID projectId, UUID sourceRevisionId) {
        return new SourceRevisionView(
                sourceRevisionId,
                projectId,
                SourceRevisionType.GIT,
                "v1",
                "https://example.test/repo.git",
                "https://example.test/repo.git",
                "main",
                "abcdef",
                null,
                "/tmp/source",
                null,
                true,
                UUID.randomUUID(),
                OffsetDateTime.now()
        );
    }

}
