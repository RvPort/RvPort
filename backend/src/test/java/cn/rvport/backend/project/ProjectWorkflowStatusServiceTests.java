package cn.rvport.backend.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import cn.rvport.backend.archive.ProjectArchiveRepository;
import cn.rvport.backend.automatedtest.AutomatedTestRepository;
import cn.rvport.backend.buildvalidation.BuildValidationRepository;
import cn.rvport.backend.buildvalidation.BuildValidationRunView;
import cn.rvport.backend.buildvalidation.BuildValidationScope;
import cn.rvport.backend.dependency.DependencyAnalysisRepository;
import cn.rvport.backend.environment.EnvironmentRole;
import cn.rvport.backend.environment.EnvironmentStatus;
import cn.rvport.backend.kb.KbScanRepository;
import cn.rvport.backend.optimization.OptimizationCycleRepository;
import cn.rvport.backend.project.ProjectOverviewResponse.WorkflowStageResponse;
import cn.rvport.backend.project.ProjectOverviewResponse.WorkflowStageStatus;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentResponse;
import cn.rvport.backend.report.ReportRepository;
import cn.rvport.backend.source.SourceRevisionResponse;
import cn.rvport.backend.source.SourceRevisionType;
import cn.rvport.backend.source.deploy.SourceDeployEnvironmentSummary;
import cn.rvport.backend.task.TaskRepository;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.task.TaskView;
import cn.rvport.backend.workflow.ProjectContextSnapshot;
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
class ProjectWorkflowStatusServiceTests {
    @Test void specializedOverviewRequiresTheActuallyConsumedBuildAndProfile() {
        UUID project=UUID.randomUUID(), source=UUID.randomUUID(), buildId=UUID.randomUUID();
        var context=new ProjectContextSnapshot(source,1L);
        var sourceEnv=environment(EnvironmentRole.SOURCE_X86,1L);
        var runtime=runtime(project,sourceEnv,null,false);
        var instance=service();
        var analyses=org.mockito.Mockito.mock(cn.rvport.backend.analysis.SpecializedAnalysisRepository.class);
        ReflectionTestUtils.setField(instance,"specializedAnalysisRepository",analyses);
        for (String origin:List.of("SOURCE_DISCOVERY","BUILD_ARTIFACTS")) {
            var run=new cn.rvport.backend.analysis.SpecializedAnalysisRunView(UUID.randomUUID(),project,null,
                    cn.rvport.backend.analysis.SpecializedAnalyzerType.FLOATING_POINT,source,buildId,null,1L,"1","1","2",null,
                    cn.rvport.backend.analysis.SpecializedAnalysisStatus.COMPLETE,null,0,Map.of("inputOrigin",origin),
                    null,UUID.randomUUID(),null,null,0,0,0,null,null,null,null,null);
            when(analyses.latest(project,cn.rvport.backend.analysis.SpecializedAnalyzerType.FLOATING_POINT)).thenReturn(Optional.of(run));
            WorkflowStageResponse result=ReflectionTestUtils.invokeMethod(instance,"specialized",project,context,runtime,
                    cn.rvport.backend.analysis.SpecializedAnalyzerType.FLOATING_POINT,"floating-point-analysis");
            assertThat(result.status()).isEqualTo(origin.equals("SOURCE_DISCOVERY")?WorkflowStageStatus.DONE:WorkflowStageStatus.PENDING);
        }
        var readiness=org.mockito.Mockito.mock(cn.rvport.backend.workflow.StageReadinessService.class);
        ReflectionTestUtils.setField(instance,"readiness",readiness);
        when(readiness.resolve(project,"floating-point-analysis")).thenReturn(new cn.rvport.backend.workflow.StageReadinessResponse(
                "floating-point-analysis",context,List.of(cn.rvport.backend.workflow.StagePrerequisiteResponse.of("SPECIALIZED_ANALYZER",
                cn.rvport.backend.workflow.StagePrerequisiteStatus.UNAVAILABLE,null,"ANALYSIS_PROFILE_UNSUPPORTED"))));
        WorkflowStageResponse changed=ReflectionTestUtils.invokeMethod(instance,"withReadiness",project,
                new WorkflowStageResponse("floating-point-analysis",WorkflowStageStatus.DONE,"COMPLETE"));
        assertThat(changed.status()).isEqualTo(WorkflowStageStatus.PENDING);
        assertThat(changed.canStart()).isFalse();
        when(readiness.resolve(project,"floating-point-analysis")).thenReturn(new cn.rvport.backend.workflow.StageReadinessResponse(
                "floating-point-analysis",context,List.of(cn.rvport.backend.workflow.StagePrerequisiteResponse.of("SPECIALIZED_ANALYZER",
                cn.rvport.backend.workflow.StagePrerequisiteStatus.SATISFIED,null,"READY"))));
        WorkflowStageResponse ready=ReflectionTestUtils.invokeMethod(instance,"withReadiness",project,
                new WorkflowStageResponse("floating-point-analysis",WorkflowStageStatus.DONE,"COMPLETE"));
        assertThat(ready.status()).isEqualTo(WorkflowStageStatus.DONE);
        assertThat(ready.canStart()).isTrue();
        WorkflowStageResponse noEnvironment=ReflectionTestUtils.invokeMethod(instance,"specialized",project,context,runtime(project,null,null,false),
                cn.rvport.backend.analysis.SpecializedAnalyzerType.FLOATING_POINT,"floating-point-analysis");
        assertThat(noEnvironment.status()).isEqualTo(WorkflowStageStatus.PENDING);
        for (int mismatch=0;mismatch<4;mismatch++) {
            var build=new BuildValidationRunView(mismatch==2?UUID.randomUUID():buildId,project,UUID.randomUUID(),source,sourceEnv.id(),
                    BuildValidationScope.SOURCE_ONLY,null,1L,null,"hash","make","/fixture",60,0,
                    mismatch==1?TaskStatus.FAILED:TaskStatus.SUCCEEDED,null,null,null,null,null,null,null,Map.of(),Map.of());
            when(buildValidationRepository.findLatest(project,source,sourceEnv.id(),1L,BuildValidationScope.SOURCE_ONLY))
                    .thenReturn(Optional.of(build));
            var run=new cn.rvport.backend.analysis.SpecializedAnalysisRunView(UUID.randomUUID(),project,null,
                    cn.rvport.backend.analysis.SpecializedAnalyzerType.FLOATING_POINT,source,buildId,null,mismatch==3?2L:1L,"1","1","2",null,
                    cn.rvport.backend.analysis.SpecializedAnalysisStatus.COMPLETE,null,0,Map.of("inputOrigin","BUILD_ARTIFACTS"),
                    null,UUID.randomUUID(),null,null,0,0,0,null,null,null,null,null);
            when(analyses.latest(project,cn.rvport.backend.analysis.SpecializedAnalyzerType.FLOATING_POINT)).thenReturn(Optional.of(run));
            WorkflowStageResponse result=ReflectionTestUtils.invokeMethod(instance,"specialized",project,context,runtime,
                    cn.rvport.backend.analysis.SpecializedAnalyzerType.FLOATING_POINT,"floating-point-analysis");
            assertThat(result.status()).isEqualTo(mismatch==0?WorkflowStageStatus.DONE:WorkflowStageStatus.PENDING);
        }
    }
    @Test
    void memoryOrderExecutionStatesKeepExistingDependencySemantics() {
        for (var status : cn.rvport.backend.analysis.SpecializedAnalysisStatus.values()) {
            WorkflowStageResponse response = ReflectionTestUtils.invokeMethod(service(), "fromSpecializedStatus",
                    "memory-order-analysis", status, "MEMORY_ORDER");
            var expected = switch (status) {
                case PENDING, QUEUED, RUNNING -> WorkflowStageStatus.RUNNING;
                case COMPLETE, COMPLETE_WITH_RISK, PARTIAL -> WorkflowStageStatus.DONE;
                case FAILED, CANCELLED, TIMEOUT -> WorkflowStageStatus.BLOCKED;
            };
            assertThat(response.status()).isEqualTo(expected);
            if (status == cn.rvport.backend.analysis.SpecializedAnalysisStatus.PARTIAL) {
                assertThat(response.analysisComplete()).isFalse();
            }
        }
    }
    @Test
    void memoryOrderPartialKeepsDependencyCompletionAndExposesWarningMetadata() {
        WorkflowStageResponse partial = ReflectionTestUtils.invokeMethod(service(), "fromSpecializedStatus",
                "memory-order-analysis", cn.rvport.backend.analysis.SpecializedAnalysisStatus.PARTIAL, "MEMORY_ORDER");
        assertThat(partial.status()).isEqualTo(WorkflowStageStatus.DONE);
        assertThat(partial.reasonCode()).isEqualTo("MEMORY_ORDER_PARTIAL");
        assertThat(partial.analysisStatus()).isEqualTo("PARTIAL");
        assertThat(partial.analysisComplete()).isFalse();
        assertThat(partial.message()).isEqualTo("部分分析完成");
        assertThat(partial.messageEn()).isNotBlank();
        WorkflowStageResponse floating = ReflectionTestUtils.invokeMethod(service(), "fromSpecializedStatus",
                "floating-point-analysis", cn.rvport.backend.analysis.SpecializedAnalysisStatus.PARTIAL, "FLOATING_POINT");
        assertThat(floating.reasonCode()).isEqualTo("FLOATING_POINT_PARTIAL");
        assertThat(floating.analysisComplete()).isFalse();
        assertThat(floating.analysisStatus()).isEqualTo("PARTIAL");
    }

    @Mock private TaskRepository taskRepository;
    @Mock private KbScanRepository kbScanRepository;
    @Mock private cn.rvport.backend.kb.KbScanContextService scanContextService;
    @Mock private BuildValidationRepository buildValidationRepository;
    @Mock private DependencyAnalysisRepository dependencyAnalysisRepository;
    @Mock private ReportRepository reportRepository;
    @Mock private AutomatedTestRepository automatedTestRepository;
    @Mock private OptimizationCycleRepository optimizationCycleRepository;
    @Mock private ProjectArchiveRepository archiveRepository;

    @Test
    void optimizeUsesValidationInsteadOfCycleExistence() {
        UUID project = UUID.randomUUID(), source = UUID.randomUUID();
        var context = new ProjectContextSnapshot(source, 1L);
        var resolver = org.mockito.Mockito.mock(cn.rvport.backend.optimization.OptimizationValidationService.class);
        var instance = service();
        ReflectionTestUtils.setField(instance, "optimizationValidationService", resolver);
        when(optimizationCycleRepository.findBySourceRevision(project, source)).thenReturn(Optional.of(
                new cn.rvport.backend.optimization.OptimizationCycleView(UUID.randomUUID(), project, source, 1,
                        cn.rvport.backend.optimization.OptimizationCycleStatus.ACTIVE, Map.of(), null, null, null, null)));
        var validation = cn.rvport.backend.optimization.OptimizationValidation.unavailable("OPTIMIZATION_BUILD_MISSING", "缺少构建", "Builds missing");
        when(resolver.resolve(project, context, null)).thenReturn(validation);
        WorkflowStageResponse result = ReflectionTestUtils.invokeMethod(instance, "optimize", project, context, null);
        assertThat(result.status()).isEqualTo(WorkflowStageStatus.PENDING);
        assertThat(result.reasonCode()).isEqualTo("OPTIMIZATION_BUILD_MISSING");
        assertThat(result.message()).isEqualTo("缺少构建");
        assertThat(result.messageEn()).isEqualTo("Builds missing");
    }

    @Test
    void kbMapsOnlyCurrentRunsAndKeepsHistoricalSuccessPending() {
        UUID project = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        var context = new ProjectContextSnapshot(source, 7L);
        for (TaskStatus status : TaskStatus.values()) {
            when(scanContextService.currentRun(project, source)).thenReturn(Optional.of(
                    cn.rvport.backend.kb.KbContextFixtures.scan(project, source, status, cn.rvport.backend.kb.KbContextFixtures.CONTEXT)));
            WorkflowStageResponse result = ReflectionTestUtils.invokeMethod(service(), "kb", project, context);
            assertThat(result.status()).isEqualTo(switch (status) {
                case SUCCEEDED -> WorkflowStageStatus.DONE;
                case PENDING, QUEUED, RUNNING -> WorkflowStageStatus.RUNNING;
                default -> WorkflowStageStatus.BLOCKED;
            });
        }
        when(scanContextService.currentRun(project, source)).thenReturn(Optional.empty());
        WorkflowStageResponse missing = ReflectionTestUtils.invokeMethod(service(), "kb", project, context);
        assertThat(missing.reasonCode()).isEqualTo("KB_SCAN_MISSING");
        when(kbScanRepository.findLatestHistoricalRun(project)).thenReturn(Optional.of(
                cn.rvport.backend.kb.KbContextFixtures.scan(project, source, TaskStatus.SUCCEEDED, null)));
        for (var snapshot : List.of(context, new ProjectContextSnapshot(null, 7L))) {
            WorkflowStageResponse stale = ReflectionTestUtils.invokeMethod(service(), "kb", project, snapshot);
            assertThat(stale.status()).isEqualTo(WorkflowStageStatus.PENDING);
            assertThat(stale.reasonCode()).isEqualTo("KB_SCAN_STALE");
        }
    }

    @Test
    void sourceAnalysisIsRecommendedWithoutRequiringOptionalBuildOrEnvironment() {
        UUID project = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        var revision = new SourceRevisionResponse(source, project, SourceRevisionType.GIT, "main", null, null, null, null, null, true, null, null);
        var service = service();
        var readiness = org.mockito.Mockito.mock(cn.rvport.backend.workflow.StageReadinessService.class);
        ReflectionTestUtils.setField(service, "readiness", readiness);
        when(readiness.resolve(org.mockito.ArgumentMatchers.eq(project), org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> {
            String stage = call.getArgument(1);
            boolean available = List.of("kb", "floating-point-analysis", "memory-order-analysis").contains(stage);
            return new cn.rvport.backend.workflow.StageReadinessResponse(stage, new ProjectContextSnapshot(source, 7L),
                    List.of(cn.rvport.backend.workflow.StagePrerequisiteResponse.of("INPUT",
                            available ? cn.rvport.backend.workflow.StagePrerequisiteStatus.SATISFIED
                                    : cn.rvport.backend.workflow.StagePrerequisiteStatus.MISSING, null, "FIXTURE")));
        });
        var response = service.aggregate(project, revision,
                runtime(project, null, null, false),
                new ProjectContextSnapshot(source, 7L));
        assertThat(response.recommendedStageId()).isEqualTo("kb");
        assertThat(response.stages().stream().filter(stage -> stage.id().equals("kb")).findFirst().orElseThrow().reasonCode()).isEqualTo("KB_SCAN_MISSING");
        assertThat(response.stages().stream().filter(stage -> stage.id().equals("x86-build")).findFirst().orElseThrow().optional()).isTrue();
        assertThat(response.stages().stream().filter(stage -> stage.id().equals("kb")).findFirst().orElseThrow().canStart()).isTrue();
    }

    @Test
    void returnsStableFullWorkflowForEmptyProject() {
        UUID projectId = UUID.randomUUID();
        ProjectRuntimeEnvironmentResponse runtime = runtime(projectId, null, null, false);

        var response = service().aggregate(projectId, null, runtime, new ProjectContextSnapshot(null, 1L));

        assertThat(response.stages()).extracting(WorkflowStageResponse::id).containsExactly(
                "project", "env", "assets", "x86-build", "kb", "rv-dependency-analysis",
                "floating-point-analysis", "memory-order-analysis",
                "report", "manual", "build", "test", "optimize", "archive");
        assertThat(response.stages().getFirst().status()).isEqualTo(WorkflowStageStatus.DONE);
        assertThat(response.recommendedStageId()).isEqualTo("env");
    }

    @Test
    void reportsCurrentVersionEnvironmentTaskAsRunning() {
        UUID projectId = UUID.randomUUID();
        SourceDeployEnvironmentSummary source = environment(EnvironmentRole.SOURCE_X86, 3L);
        SourceDeployEnvironmentSummary target = environment(EnvironmentRole.TARGET_RISCV, 7L);
        ProjectRuntimeEnvironmentResponse runtime = runtime(projectId, source, target, false);
        Map<UUID, Long> versions = Map.of(source.id(), 3L, target.id(), 7L);
        when(taskRepository.findLatestByStageAndResourceVersions(projectId, "environment", versions))
                .thenReturn(Optional.of(task(projectId, TaskStatus.RUNNING)));

        var response = service().aggregate(projectId, null, runtime, new ProjectContextSnapshot(null, 1L));

        assertThat(response.stages()).filteredOn(stage -> stage.id().equals("env")).singleElement()
                .extracting(WorkflowStageResponse::status, WorkflowStageResponse::reasonCode)
                .containsExactly(WorkflowStageStatus.RUNNING, "ENVIRONMENT_TEST_RUNNING");
        assertThat(response.recommendedStageId()).isEqualTo("env");
    }

    @Test
    void preservesLatestTerminalFailureAsBlockedInsteadOfFallingBackToSuccess() {
        UUID projectId = UUID.randomUUID();
        when(taskRepository.findLatestByStage(projectId, "source"))
                .thenReturn(Optional.of(task(projectId, TaskStatus.FAILED)));

        var response = service().aggregate(
                projectId,
                null,
                runtime(projectId, null, null, false),
                new ProjectContextSnapshot(null, 1L));

        assertThat(response.stages()).filteredOn(stage -> stage.id().equals("assets")).singleElement()
                .extracting(WorkflowStageResponse::status, WorkflowStageResponse::reasonCode)
                .containsExactly(WorkflowStageStatus.BLOCKED, "SOURCE_IMPORT_FAILED");
        assertThat(response.recommendedStageId()).isEqualTo("assets");
    }

    @Test
    void marksConfiguredAndTestedEnvironmentsDoneAtBoundary() {
        UUID projectId = UUID.randomUUID();
        SourceDeployEnvironmentSummary source = environment(EnvironmentRole.SOURCE_X86, 1L);
        SourceDeployEnvironmentSummary target = environment(EnvironmentRole.TARGET_RISCV, 1L);
        ProjectRuntimeEnvironmentResponse runtime = runtime(projectId, source, target, true);
        SourceRevisionResponse revision = new SourceRevisionResponse(
                UUID.randomUUID(), projectId, SourceRevisionType.GIT, "v1", null,
                "https://example.test/repo.git", "main", "abc", null, true,
                UUID.randomUUID(), OffsetDateTime.now());

        var response = service().aggregate(
                projectId,
                revision,
                runtime,
                new ProjectContextSnapshot(revision.id(), 1L));

        assertThat(response.stages()).filteredOn(stage -> List.of("env", "assets").contains(stage.id()))
                .extracting(WorkflowStageResponse::status)
                .containsOnly(WorkflowStageStatus.DONE);
    }

    @Test
    void mapsEveryTaskLifecycleStatusToWorkflowStatusAndReason() {
        ProjectWorkflowStatusService service = service();
        for (TaskStatus status : List.of(TaskStatus.PENDING, TaskStatus.QUEUED, TaskStatus.RUNNING)) {
            WorkflowStageResponse stage = ReflectionTestUtils.invokeMethod(service, "fromStatus", "stage", status, "PREFIX");
            assertThat(stage).isEqualTo(new WorkflowStageResponse("stage", WorkflowStageStatus.RUNNING, "PREFIX_RUNNING"));
        }
        WorkflowStageResponse succeeded = ReflectionTestUtils.invokeMethod(service, "fromStatus", "stage", TaskStatus.SUCCEEDED, "PREFIX");
        assertThat(succeeded).isEqualTo(new WorkflowStageResponse("stage", WorkflowStageStatus.DONE, "PREFIX_READY"));
        assertBlockedReason(service, TaskStatus.FAILED, "PREFIX_FAILED");
        assertBlockedReason(service, TaskStatus.CANCELLED, "PREFIX_CANCELLED");
        assertBlockedReason(service, TaskStatus.TIMEOUT, "PREFIX_TIMEOUT");
    }

    @Test
    void environmentDistinguishesIncompleteFailedUntestedAndReadyStates() {
        UUID projectId = UUID.randomUUID();
        SourceDeployEnvironmentSummary source = environment(EnvironmentRole.SOURCE_X86, 2L);
        SourceDeployEnvironmentSummary target = environment(EnvironmentRole.TARGET_RISCV, 3L);

        assertStage(service().aggregate(projectId, null, runtime(projectId, source, null, false),
                new ProjectContextSnapshot(null, 1L)), "env", WorkflowStageStatus.PENDING, "RUNTIME_ENVIRONMENTS_INCOMPLETE");

        Map<UUID, Long> versions = Map.of(source.id(), 2L, target.id(), 3L);
        when(taskRepository.findLatestByStageAndResourceVersions(projectId, "environment", versions))
                .thenReturn(Optional.of(task(projectId, TaskStatus.TIMEOUT)));
        assertStage(service().aggregate(projectId, null, runtime(projectId, source, target, false),
                new ProjectContextSnapshot(null, 1L)), "env", WorkflowStageStatus.BLOCKED, "ENVIRONMENT_TEST_FAILED");

        when(taskRepository.findLatestByStageAndResourceVersions(projectId, "environment", versions))
                .thenReturn(Optional.of(task(projectId, TaskStatus.SUCCEEDED)));
        assertStage(service().aggregate(projectId, null, runtime(projectId, source, target, false),
                new ProjectContextSnapshot(null, 1L)), "env", WorkflowStageStatus.PENDING, "ENVIRONMENT_TEST_REQUIRED");

        assertStage(service().aggregate(projectId, null, runtime(projectId, source, target, true),
                new ProjectContextSnapshot(null, 1L)), "env", WorkflowStageStatus.DONE, "ENVIRONMENT_READY");
    }

    @Test
    void assetsDistinguishesRunningReadyFailedAndMissingStates() {
        UUID projectId = UUID.randomUUID();
        ProjectRuntimeEnvironmentResponse runtime = runtime(projectId, null, null, false);
        SourceRevisionResponse revision = new SourceRevisionResponse(
                UUID.randomUUID(), projectId, SourceRevisionType.GIT, "v1", null,
                "https://example.test/repo.git", "main", "abc", null, true,
                UUID.randomUUID(), OffsetDateTime.now());

        when(taskRepository.findLatestByStage(projectId, "source")).thenReturn(Optional.of(task(projectId, TaskStatus.QUEUED)));
        assertStage(service().aggregate(projectId, null, runtime, new ProjectContextSnapshot(null, 1L)),
                "assets", WorkflowStageStatus.RUNNING, "SOURCE_IMPORT_RUNNING");

        when(taskRepository.findLatestByStage(projectId, "source")).thenReturn(Optional.of(task(projectId, TaskStatus.CANCELLED)));
        assertStage(service().aggregate(projectId, revision, runtime, new ProjectContextSnapshot(revision.id(), 1L)),
                "assets", WorkflowStageStatus.DONE, "ACTIVE_SOURCE_READY");

        when(taskRepository.findLatestByStage(projectId, "source")).thenReturn(Optional.empty());
        assertStage(service().aggregate(projectId, null, runtime, new ProjectContextSnapshot(null, 1L)),
                "assets", WorkflowStageStatus.PENDING, "ACTIVE_SOURCE_MISSING");
    }

    @Test
    void recommendationPrioritizesRunningThenBlockedThenPendingAndFinallyArchive() {
        ProjectWorkflowStatusService service = service();
        List<WorkflowStageResponse> stages = List.of(
                new WorkflowStageResponse("done", WorkflowStageStatus.DONE, "ok"),
                new WorkflowStageResponse("pending", WorkflowStageStatus.PENDING, "wait"),
                new WorkflowStageResponse("blocked", WorkflowStageStatus.BLOCKED, "bad"),
                new WorkflowStageResponse("running", WorkflowStageStatus.RUNNING, "go")
        );
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "recommended", stages)).isEqualTo("running");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "recommended", stages.subList(0, 3))).isEqualTo("blocked");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "recommended", stages.subList(0, 2))).isEqualTo("pending");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "recommended", stages.subList(0, 1))).isEqualTo("archive");
    }

    @Test void recommendationPrefersExecutableAnalysisToOptionalBuildAndTerminalNodes() {
        var service = service();
        var stages = new java.util.ArrayList<WorkflowStageResponse>();
        stages.add(new WorkflowStageResponse("x86-build",WorkflowStageStatus.PENDING,"missing",null,null,null,null,true,true));
        stages.add(new WorkflowStageResponse("kb",WorkflowStageStatus.DONE,"ready",null,true,null,null,false,true));
        stages.add(new WorkflowStageResponse("optimize",WorkflowStageStatus.PENDING,"ready",null,null,null,null,false,true));
        stages.add(new WorkflowStageResponse("archive",WorkflowStageStatus.PENDING,"ready",null,null,null,null,false,true));
        stages.add(new WorkflowStageResponse("floating-point-analysis",WorkflowStageStatus.PENDING,"ready",null,null,null,null,false,true));
        assertThat((String)ReflectionTestUtils.invokeMethod(service,"recommended",stages)).isEqualTo("floating-point-analysis");
        var readiness = org.mockito.Mockito.mock(cn.rvport.backend.workflow.StageReadinessService.class);
        ReflectionTestUtils.setField(service,"readiness",readiness);
        var running = new WorkflowStageResponse("kb",WorkflowStageStatus.RUNNING,"running");
        WorkflowStageResponse response = ReflectionTestUtils.invokeMethod(service,"withReadiness",UUID.randomUUID(),running);
        assertThat(response.canStart()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(readiness);
    }

    @Test
    void manualStageTracksSourceAndReportState() {
        ProjectWorkflowStatusService service = service();
        SourceRevisionResponse source = new SourceRevisionResponse(
                UUID.randomUUID(), UUID.randomUUID(), SourceRevisionType.GIT, "v1", null,
                null, null, null, null, true, UUID.randomUUID(), OffsetDateTime.now());

        assertManual(service, null, WorkflowStageStatus.PENDING, "ACTIVE_SOURCE_MISSING");
        assertManual(service, source, WorkflowStageStatus.RUNNING, "REPORT_RUNNING");
        assertManual(service, source, WorkflowStageStatus.BLOCKED, "REPORT_FAILED");
        assertManual(service, source, WorkflowStageStatus.DONE, "MANUAL_FIX_AVAILABLE");
        assertManual(service, source, WorkflowStageStatus.PENDING, "REPORT_REQUIRED");
    }

    @Test
    void dualEnvironmentBuildDistinguishesReadyRunningFailedAndMissingStates() {
        UUID projectId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        SourceDeployEnvironmentSummary source = environment(EnvironmentRole.SOURCE_X86, 1L);
        SourceDeployEnvironmentSummary target = environment(EnvironmentRole.TARGET_RISCV, 1L);
        ProjectRuntimeEnvironmentResponse runtime = runtime(projectId, source, target, true);
        ProjectContextSnapshot context = new ProjectContextSnapshot(revisionId, 1L);
        ProjectWorkflowStatusService service = service();

        when(buildValidationRepository.findLatest(projectId, revisionId, source.id(), 1L, BuildValidationScope.BOTH))
                .thenReturn(Optional.of(buildRun(projectId, revisionId, source.id(), TaskStatus.SUCCEEDED)));
        when(buildValidationRepository.findLatest(projectId, revisionId, target.id(), 1L, BuildValidationScope.BOTH))
                .thenReturn(Optional.of(buildRun(projectId, revisionId, target.id(), TaskStatus.SUCCEEDED)));
        assertBuild(service, projectId, runtime, context, WorkflowStageStatus.DONE, "BUILD_VALIDATION_READY");

        when(buildValidationRepository.findLatest(projectId, revisionId, target.id(), 1L, BuildValidationScope.BOTH))
                .thenReturn(Optional.empty());
        assertBuild(service, projectId, runtime, context, WorkflowStageStatus.PENDING, "BUILD_VALIDATION_MISSING");

        when(buildValidationRepository.findLatest(projectId, revisionId, source.id(), 1L, BuildValidationScope.BOTH))
                .thenReturn(Optional.empty());
        when(buildValidationRepository.findLatest(projectId, revisionId, target.id(), 1L, BuildValidationScope.BOTH))
                .thenReturn(Optional.of(buildRun(projectId, revisionId, target.id(), TaskStatus.RUNNING)));
        assertBuild(service, projectId, runtime, context, WorkflowStageStatus.RUNNING, "BUILD_VALIDATION_RUNNING");

        when(buildValidationRepository.findLatest(projectId, revisionId, target.id(), 1L, BuildValidationScope.BOTH))
                .thenReturn(Optional.of(buildRun(projectId, revisionId, target.id(), TaskStatus.FAILED)));
        assertBuild(service, projectId, runtime, context, WorkflowStageStatus.BLOCKED, "BUILD_VALIDATION_FAILED");
    }

    @Test void overviewDistinguishesDraftEmptyConfirmationAndStaleReport() {
        var project=UUID.randomUUID();
        var revision=new SourceRevisionResponse(UUID.randomUUID(),project,SourceRevisionType.GIT,"main",null,null,null,null,null,true,null,null);
        var runtime=runtime(project,null,null,false);
        var context=new ProjectContextSnapshot(revision.id(),1L);
        var aggregate=service();
        for (String reason : java.util.List.of("UNCONFIRMED","CONFIRMED_EMPTY","CONFIRMED_WITH_ITEMS","REPORT_CONTEXT_CHANGED","REPORT_READ_ONLY")) {
            boolean available=reason.startsWith("CONFIRMED");
            var current=new cn.rvport.backend.report.review.CurrentFixListResolver.Current(available,UUID.randomUUID(),
                    available?UUID.randomUUID():null,0L,reason.equals("CONFIRMED_WITH_ITEMS")?1:0,reason);
            when(fixLists.current(project)).thenReturn(current);
            var workflow=aggregate.aggregate(project,revision,runtime,context);
            if(reason.startsWith("REPORT_")) {
                assertStage(workflow,"report",WorkflowStageStatus.PENDING,"REPORT_MISSING");
                assertStage(workflow,"manual",WorkflowStageStatus.PENDING,"REPORT_REQUIRED");
            } else {
                assertStage(workflow,"report",WorkflowStageStatus.DONE,"REPORT_READY");
                assertStage(workflow,"manual",WorkflowStageStatus.PENDING,reason.equals("CONFIRMED_WITH_ITEMS")?"MANUAL_FIX_AVAILABLE":reason);
            }
        }
    }
    private void assertBuild(ProjectWorkflowStatusService service, UUID projectId,
                             ProjectRuntimeEnvironmentResponse runtime, ProjectContextSnapshot context,
                             WorkflowStageStatus status, String reason) {
        WorkflowStageResponse result = ReflectionTestUtils.invokeMethod(service, "build", projectId, runtime, context);
        assertThat(result).isEqualTo(new WorkflowStageResponse("build", status, reason));
    }

    private BuildValidationRunView buildRun(UUID projectId, UUID revisionId, UUID environmentId, TaskStatus status) {
        return new BuildValidationRunView(UUID.randomUUID(), projectId, UUID.randomUUID(), revisionId, environmentId,
                BuildValidationScope.BOTH, UUID.randomUUID(), 1L, null, null, "make", null, 60, null, status,
                null, null, null, null, null, null, OffsetDateTime.now(), Map.of(), Map.of());
    }

    private void assertBlockedReason(ProjectWorkflowStatusService service, TaskStatus status, String reason) {
        WorkflowStageResponse stage = ReflectionTestUtils.invokeMethod(service, "fromStatus", "stage", status, "PREFIX");
        assertThat(stage).isEqualTo(new WorkflowStageResponse("stage", WorkflowStageStatus.BLOCKED, reason));
    }

    private void assertStage(ProjectOverviewResponse.WorkflowResponse response, String id,
                             WorkflowStageStatus status, String reason) {
        assertThat(response.stages()).filteredOn(stage -> stage.id().equals(id)).singleElement()
                .isEqualTo(new WorkflowStageResponse(id, status, reason));
    }

    private void assertManual(ProjectWorkflowStatusService service, SourceRevisionResponse source,
                              WorkflowStageStatus reportStatus, String expectedReason) {
        WorkflowStageResponse report = new WorkflowStageResponse("report", reportStatus, "reason");
        WorkflowStageResponse result = ReflectionTestUtils.invokeMethod(service, "manual", report, source,new cn.rvport.backend.report.review.CurrentFixListResolver.Current(true,UUID.randomUUID(),UUID.randomUUID(),1L,1,"CONFIRMED_WITH_ITEMS"));
        assertThat(result).isEqualTo(new WorkflowStageResponse("manual",
                expectedReason.equals("REPORT_FAILED") ? WorkflowStageStatus.BLOCKED : WorkflowStageStatus.PENDING,
                expectedReason));
    }

    private final cn.rvport.backend.report.review.CurrentFixListResolver fixLists=org.mockito.Mockito.mock(cn.rvport.backend.report.review.CurrentFixListResolver.class);
    private ProjectWorkflowStatusService service() {
        var service = new ProjectWorkflowStatusService(
                taskRepository,
                kbScanRepository,
                buildValidationRepository,
                dependencyAnalysisRepository,
                reportRepository,
                automatedTestRepository,
                optimizationCycleRepository,
                archiveRepository);
        ReflectionTestUtils.setField(service,"fixLists",fixLists);
        org.mockito.Mockito.lenient().when(fixLists.current(org.mockito.ArgumentMatchers.any())).thenReturn(
                new cn.rvport.backend.report.review.CurrentFixListResolver.Current(false,null,null,null,0,"REPORT_NOT_GENERATED"));
        ReflectionTestUtils.setField(service, "scanContextService", scanContextService);
        return service;
    }

    private ProjectRuntimeEnvironmentResponse runtime(
            UUID projectId,
            SourceDeployEnvironmentSummary source,
            SourceDeployEnvironmentSummary target,
            boolean ready
    ) {
        return new ProjectRuntimeEnvironmentResponse(
                projectId,
                source,
                target,
                1L,
                ready,
                ready ? List.of() : List.of("environmentTest"),
                null,
                null);
    }

    private SourceDeployEnvironmentSummary environment(EnvironmentRole role, long configVersion) {
        return new SourceDeployEnvironmentSummary(
                UUID.randomUUID(), role.name(), role, role == EnvironmentRole.SOURCE_X86 ? "x86_64" : "riscv64",
                EnvironmentStatus.AVAILABLE, true, configVersion);
    }

    private TaskView task(UUID projectId, TaskStatus status) {
        return new TaskView(
                UUID.randomUUID(), projectId, "TEST", status, 0, null, UUID.randomUUID(), OffsetDateTime.now(),
                null, null, null, null, null, null, 0);
    }
}
