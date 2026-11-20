package cn.rvport.backend.automatedtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.audit.AuditLogService;
import cn.rvport.backend.buildvalidation.BuildValidationRepository;
import cn.rvport.backend.buildvalidation.BuildValidationRunView;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentService;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentResponse;
import cn.rvport.backend.environment.EnvironmentRole;
import cn.rvport.backend.environment.EnvironmentStatus;
import cn.rvport.backend.source.deploy.SourceDeployEnvironmentSummary;
import cn.rvport.backend.task.TaskResponse;
import cn.rvport.backend.task.TaskRepository;
import cn.rvport.backend.task.TaskService;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.testcomparison.TestComparisonRepository;
import cn.rvport.backend.workflow.DomainLatestFreshness;
import cn.rvport.backend.workflow.ProjectContextService;
import cn.rvport.backend.workflow.ProjectContextSnapshot;
import cn.rvport.backend.workflow.StageReadinessService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AutomatedTestServiceTests {

    @Mock
    private AutomatedTestRepository repository;
    @Mock
    private BuildValidationRepository buildValidationRepository;
    @Mock
    private ProjectAccessService projectAccessService;
    @Mock
    private ProjectRuntimeEnvironmentService projectRuntimeEnvironmentService;
    @Mock
    private TaskService taskService;
    @Mock
    private TestComparisonRepository testComparisonRepository;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private TaskRepository taskRepository;
    @Mock
    private ProjectContextService projectContextService;
    @Mock
    private StageReadinessService stageReadinessService;

    @Test
    void createsTestFromBuildRunAndStoresEnvironmentVersion() {
        AutomatedTestService service = service();
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID buildRunId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(buildValidationRepository.findById(buildRunId)).thenReturn(Optional.of(buildRun(
                projectId,
                buildRunId,
                sourceRevisionId,
                environmentId,
                7L
        )));
        when(taskService.create(any())).thenReturn(task(projectId, taskId));

        service.create(projectId, new AutomatedTestRequest(
                buildRunId,
                null,
                null,
                null,
                null,
                "make test",
                null,
                60,
                Map.of("CASE", "smoke"),
                List.of()
        ), actorId, false, null);

        ArgumentCaptor<AutomatedTestInput> input = ArgumentCaptor.forClass(AutomatedTestInput.class);
        verify(repository).createPending(input.capture(), org.mockito.ArgumentMatchers.eq(taskId));
        org.assertj.core.api.Assertions.assertThat(input.getValue().sourceRevisionId()).isEqualTo(sourceRevisionId);
        org.assertj.core.api.Assertions.assertThat(input.getValue().environmentId()).isEqualTo(environmentId);
        org.assertj.core.api.Assertions.assertThat(input.getValue().environmentConfigVersion()).isEqualTo(7L);
    }

    @Test
    void rejectsDeprecatedEnvironmentSelectionAndMissingBuildRun() {
        AutomatedTestService service = service();
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        assertThatThrownBy(() -> service.create(projectId, new AutomatedTestRequest(
                UUID.randomUUID(),
                null,
                UUID.randomUUID(),
                null,
                null,
                "make test",
                null,
                null,
                null,
                null
        ), actorId, false, null))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("TEST_ENVIRONMENT_SELECTION_DEPRECATED");

        assertThatThrownBy(() -> service.create(projectId, new AutomatedTestRequest(
                null,
                null,
                null,
                null,
                null,
                "make test",
                null,
                null,
                null,
                null
        ), actorId, false, null))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("BUILD_RUN_REQUIRED");
    }

    @Test
    void rejectsSourceRevisionMismatch() {
        AutomatedTestService service = service();
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID buildRunId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        when(buildValidationRepository.findById(buildRunId)).thenReturn(Optional.of(buildRun(
                projectId,
                buildRunId,
                sourceRevisionId,
                UUID.randomUUID(),
                3L
        )));

        assertThatThrownBy(() -> service.create(projectId, new AutomatedTestRequest(
                buildRunId,
                UUID.randomUUID(),
                null,
                null,
                null,
                "make test",
                null,
                null,
                null,
                null
        ), actorId, false, null))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("TEST_SOURCE_REVISION_MISMATCH");
    }

    @Test
    void rejectsNullMissingForeignUnsuccessfulAndStaleBuildRuns() {
        AutomatedTestService service = fullService();
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        assertThatThrownBy(() -> service.create(projectId, null, actorId, false, null))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("TEST_REQUEST_REQUIRED");

        UUID buildRunId = UUID.randomUUID();
        AutomatedTestRequest request = request(buildRunId, null);
        when(buildValidationRepository.findById(buildRunId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(projectId, request, actorId, false, null))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("BUILD_RUN_NOT_FOUND");

        when(buildValidationRepository.findById(buildRunId)).thenReturn(Optional.of(buildRun(
                UUID.randomUUID(), buildRunId, UUID.randomUUID(), UUID.randomUUID(), 2L)));
        assertThatThrownBy(() -> service.create(projectId, request, actorId, false, null))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("BUILD_RUN_NOT_FOUND");

        BuildValidationRunView failed = withStatus(buildRun(projectId, buildRunId, UUID.randomUUID(), UUID.randomUUID(), 2L), TaskStatus.FAILED);
        when(buildValidationRepository.findById(buildRunId)).thenReturn(Optional.of(failed));
        assertThatThrownBy(() -> service.create(projectId, request, actorId, false, null))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("BUILD_RUN_NOT_SUCCEEDED");

        BuildValidationRunView stale = buildRun(projectId, buildRunId, UUID.randomUUID(), UUID.randomUUID(), 2L);
        when(buildValidationRepository.findById(buildRunId)).thenReturn(Optional.of(stale));
        when(projectRuntimeEnvironmentService.currentVersion(projectId)).thenReturn(3L);
        assertThatThrownBy(() -> service.create(projectId, request, actorId, false, null))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("BUILD_RUN_STALE");
    }

    @Test
    void createsDefaultedTestAndInfersSourceEnvironmentRole() {
        AutomatedTestService service = fullService();
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID buildRunId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        BuildValidationRunView build = buildRun(projectId, buildRunId, UUID.randomUUID(), environmentId, 5L);
        when(buildValidationRepository.findById(buildRunId)).thenReturn(Optional.of(build));
        when(projectRuntimeEnvironmentService.currentVersion(projectId)).thenReturn(5L);
        when(stageReadinessService.readiness(projectId, "test", actorId, false)).thenReturn(
                new cn.rvport.backend.workflow.StageReadinessResponse("test", new ProjectContextSnapshot(build.sourceRevisionId(), 5L),
                        List.of(cn.rvport.backend.workflow.StagePrerequisiteResponse.of("BUILD_VALIDATION",
                                cn.rvport.backend.workflow.StagePrerequisiteStatus.SATISFIED, buildRunId, "BUILD_VALIDATION_READY",
                                Map.of("sourceBuildRunId", buildRunId, "targetBuildRunId", UUID.randomUUID())))));
        when(projectRuntimeEnvironmentService.summary(projectId)).thenReturn(new cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentResponse(
                projectId,
                new cn.rvport.backend.source.deploy.SourceDeployEnvironmentSummary(environmentId, "x86", cn.rvport.backend.environment.EnvironmentRole.SOURCE_X86, "x86_64", cn.rvport.backend.environment.EnvironmentStatus.AVAILABLE, true),
                null, 5L, false, List.of("targetEnvironment"), actorId, null));
        TaskResponse created = task(projectId, UUID.randomUUID());
        when(taskService.create(any())).thenReturn(created);

        service.create(projectId, new AutomatedTestRequest(buildRunId, null, null, null, null,
                "make test", "  build  ", null, null, null), actorId, false, null);

        ArgumentCaptor<AutomatedTestInput> input = ArgumentCaptor.forClass(AutomatedTestInput.class);
        verify(repository).createPending(input.capture(), org.mockito.ArgumentMatchers.eq(created.id()));
        org.assertj.core.api.Assertions.assertThat(input.getValue().testBatchRole()).isEqualTo(TestBatchRole.SOURCE);
        org.assertj.core.api.Assertions.assertThat(input.getValue().workingDirectory()).isEqualTo("build");
        org.assertj.core.api.Assertions.assertThat(input.getValue().timeoutSeconds()).isEqualTo(1800);
    }

    @Test
    void listsGetsAndCancelsRuns() {
        AutomatedTestService service = fullService();
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        AutomatedTestRunView run = testRun(projectId, TaskStatus.SUCCEEDED);
        when(repository.count(org.mockito.ArgumentMatchers.eq(projectId), any())).thenReturn(1L);
        when(repository.list(org.mockito.ArgumentMatchers.eq(projectId), any())).thenReturn(List.of(run));
        when(repository.findById(run.id())).thenReturn(Optional.of(run));
        when(projectRuntimeEnvironmentService.currentVersion(projectId)).thenReturn(4L);
        TaskResponse cancelled = task(projectId, run.taskId());
        when(taskService.cancel(run.taskId(), actorId, false)).thenReturn(cancelled);

        org.assertj.core.api.Assertions.assertThat(service.list(projectId, null, actorId, false).items()).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(service.get(run.id(), actorId, false).id()).isEqualTo(run.id());
        org.assertj.core.api.Assertions.assertThat(service.cancel(run.id(), actorId, false)).isSameAs(cancelled);
    }

    @Test
    void getAndCancelRejectMissingRun() {
        AutomatedTestService service = fullService();
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(id, UUID.randomUUID(), false))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("TEST_RUN_NOT_FOUND");
        assertThatThrownBy(() -> service.cancel(id, UUID.randomUUID(), false))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("TEST_RUN_NOT_FOUND");
    }

    @Test
    void latestDistinguishesCurrentStaleAndEmptyResults() {
        AutomatedTestService service = fullService();
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        ProjectContextSnapshot context = new ProjectContextSnapshot(UUID.randomUUID(), 6L);
        AutomatedTestRunView current = testRun(projectId, TaskStatus.SUCCEEDED);
        when(projectContextService.snapshot(projectId)).thenReturn(context);
        when(repository.findLatest(projectId, context.sourceRevisionId(), context.runtimeEnvironmentVersion()))
                .thenReturn(Optional.of(current));
        assertThat(service.latest(projectId, actorId, false).freshness()).isEqualTo(DomainLatestFreshness.CURRENT);

        when(repository.findLatest(projectId, context.sourceRevisionId(), context.runtimeEnvironmentVersion()))
                .thenReturn(Optional.empty());
        when(repository.findLatest(projectId, null, null)).thenReturn(Optional.of(current));
        assertThat(service.latest(projectId, actorId, false).freshness()).isEqualTo(DomainLatestFreshness.STALE);

        when(repository.findLatest(projectId, null, null)).thenReturn(Optional.empty());
        assertThat(service.latest(projectId, actorId, false).freshness()).isEqualTo(DomainLatestFreshness.EMPTY);
    }

    @Test
    void infersBatchRoleForMissingSourceTargetAndUnknownRuntimeEnvironments() {
        AutomatedTestService service = fullService();
        UUID projectId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        when(projectRuntimeEnvironmentService.summary(projectId)).thenReturn(null);
        assertThat(inferBatchRole(service, projectId, sourceId)).isNull();

        ProjectRuntimeEnvironmentResponse runtime = new ProjectRuntimeEnvironmentResponse(
                projectId,
                new SourceDeployEnvironmentSummary(sourceId, "source", EnvironmentRole.SOURCE_X86,
                        "x86_64", EnvironmentStatus.AVAILABLE, true),
                new SourceDeployEnvironmentSummary(targetId, "target", EnvironmentRole.TARGET_RISCV,
                        "riscv64", EnvironmentStatus.AVAILABLE, true),
                4L, true, List.of(), actorId, OffsetDateTime.now());
        when(projectRuntimeEnvironmentService.summary(projectId)).thenReturn(runtime);

        assertThat(inferBatchRole(service, projectId, sourceId)).isEqualTo(TestBatchRole.SOURCE);
        assertThat(inferBatchRole(service, projectId, targetId)).isEqualTo(TestBatchRole.TARGET);
        assertThat(inferBatchRole(service, projectId, UUID.randomUUID())).isNull();
        assertThat(inferBatchRole(service, projectId, null)).isNull();
    }

    @Test
    void createsTestWithExplicitBatchMetadataAndBlankWorkingDirectory() {
        AutomatedTestService service = service();
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID buildRunId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        BuildValidationRunView build = buildRun(projectId, buildRunId, sourceRevisionId, UUID.randomUUID(), 2L);
        when(buildValidationRepository.findById(buildRunId)).thenReturn(Optional.of(build));
        TaskResponse task = task(projectId, UUID.randomUUID());
        when(taskService.create(any())).thenReturn(task);

        service.create(projectId, new AutomatedTestRequest(buildRunId, sourceRevisionId, null, batchId,
                TestBatchRole.TARGET, "make test", "   ", 90, Map.of(), List.of()), actorId, false, null);

        ArgumentCaptor<AutomatedTestInput> input = ArgumentCaptor.forClass(AutomatedTestInput.class);
        verify(repository).createPending(input.capture(), org.mockito.ArgumentMatchers.eq(task.id()));
        assertThat(input.getValue().testBatchId()).isEqualTo(batchId);
        assertThat(input.getValue().testBatchRole()).isEqualTo(TestBatchRole.TARGET);
        assertThat(input.getValue().workingDirectory()).isNull();
        assertThat(input.getValue().timeoutSeconds()).isEqualTo(90);
    }

    @Test
    void rejectsBuildRunWithoutEnvironmentVersionWhenReadinessChecksAreEnabled() {
        AutomatedTestService service = fullService();
        UUID projectId = UUID.randomUUID();
        UUID buildRunId = UUID.randomUUID();
        when(buildValidationRepository.findById(buildRunId)).thenReturn(Optional.of(
                buildRun(projectId, buildRunId, UUID.randomUUID(), UUID.randomUUID(), null)));
        when(projectRuntimeEnvironmentService.currentVersion(projectId)).thenReturn(1L);

        assertThatThrownBy(() -> service.create(projectId, request(buildRunId, null), UUID.randomUUID(), false, null))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("BUILD_RUN_STALE");
    }

    @Test
    void listsUsingExplicitQuery() {
        AutomatedTestService service = fullService();
        UUID projectId = UUID.randomUUID();
        AutomatedTestListRequest query = new AutomatedTestListRequest(null, null, null, 2, 5, "createdAt", "desc");

        assertThat(service.list(projectId, query, UUID.randomUUID(), false).page()).isEqualTo(2);
        verify(repository).count(projectId, query);
        verify(repository).list(projectId, query);
    }

    @Test
    void rejectsHistoricalBuildAndAcceptsOnlyCurrentPairedBuildIds() {
        var service = fullService();
        UUID project = UUID.randomUUID(), actor = UUID.randomUUID(), id = UUID.randomUUID(), source = UUID.randomUUID();
        var build = buildRun(project, id, source, UUID.randomUUID(), 2L);
        when(buildValidationRepository.findById(id)).thenReturn(Optional.of(build));
        when(projectRuntimeEnvironmentService.currentVersion(project)).thenReturn(2L);
        var context = new ProjectContextSnapshot(source, 2L);
        when(stageReadinessService.readiness(project, "test", actor, false)).thenReturn(
                new cn.rvport.backend.workflow.StageReadinessResponse("test", context, List.of()));
        assertThatThrownBy(() -> service.create(project, request(id, source), actor, false, null))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("BUILD_RUN_STALE");
        for (String role : List.of("neither", "sourceBuildRunId", "targetBuildRunId")) {
            var details = Map.<String, Object>of("sourceBuildRunId", role.equals("sourceBuildRunId") ? id : UUID.randomUUID(),
                    "targetBuildRunId", role.equals("targetBuildRunId") ? id : UUID.randomUUID());
            when(stageReadinessService.readiness(project, "test", actor, false)).thenReturn(
                    new cn.rvport.backend.workflow.StageReadinessResponse("test", context, List.of(
                            cn.rvport.backend.workflow.StagePrerequisiteResponse.of("BUILD_VALIDATION",
                                    cn.rvport.backend.workflow.StagePrerequisiteStatus.SATISFIED, id, "BUILD_VALIDATION_READY", details))));
            if (role.equals("neither")) {
                assertThatThrownBy(() -> service.create(project, request(id, source), actor, false, null))
                        .isInstanceOf(ApiException.class).extracting("code").isEqualTo("BUILD_RUN_STALE");
                org.mockito.Mockito.verifyNoInteractions(taskService);
            } else {
                var task = task(project, UUID.randomUUID());
                when(taskService.create(any())).thenReturn(task);
                assertThat(service.create(project, request(id, source), actor, false, null)).isSameAs(task);
            }
        }
    }

    private TestBatchRole inferBatchRole(AutomatedTestService service, UUID projectId, UUID environmentId) {
        return ReflectionTestUtils.invokeMethod(service, "inferBatchRole", projectId, environmentId);
    }

    private AutomatedTestService service() {
        return new AutomatedTestService(
                repository,
                new AutomatedTestInputValidator(),
                buildValidationRepository,
                projectAccessService,
                projectRuntimeEnvironmentService,
                taskService,
                testComparisonRepository,
                auditLogService
        );
    }

    private AutomatedTestService fullService() {
        return new AutomatedTestService(repository, new AutomatedTestInputValidator(), buildValidationRepository,
                projectAccessService, projectRuntimeEnvironmentService, taskService, taskRepository,
                projectContextService, stageReadinessService, testComparisonRepository, auditLogService);
    }

    private AutomatedTestRequest request(UUID buildRunId, UUID sourceRevisionId) {
        return new AutomatedTestRequest(buildRunId, sourceRevisionId, null, null, null,
                "make test", null, null, null, null);
    }

    private AutomatedTestRunView testRun(UUID projectId, TaskStatus status) {
        return new AutomatedTestRunView(UUID.randomUUID(), projectId, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 4L, UUID.randomUUID(), TestBatchRole.SOURCE,
                "x86", "make test", null, 60, 0, 3, 0, 0, status, null, null, null,
                "ok", List.of(), OffsetDateTime.now(), OffsetDateTime.now());
    }

    private BuildValidationRunView withStatus(BuildValidationRunView run, TaskStatus status) {
        return new BuildValidationRunView(run.id(), run.projectId(), run.taskId(), run.sourceRevisionId(),
                run.environmentId(), run.buildScope(), run.sourceDeployRunId(), run.environmentConfigVersion(),
                run.scriptPayloadId(), run.scriptSha256(), run.command(), run.workingDirectory(), run.timeoutSeconds(),
                run.exitCode(), status, run.failureCategory(), run.mergedLogId(), run.stdoutLogId(), run.stderrLogId(),
                run.startedAt(), run.finishedAt(), run.createdAt(), run.environmentVariables(), run.buildFacts());
    }

    private BuildValidationRunView buildRun(
            UUID projectId,
            UUID buildRunId,
            UUID sourceRevisionId,
            UUID environmentId,
            Long environmentConfigVersion
    ) {
        return new BuildValidationRunView(
                buildRunId,
                projectId,
                UUID.randomUUID(),
                sourceRevisionId,
                environmentId,
                UUID.randomUUID(),
                environmentConfigVersion,
                null,
                null,
                "make",
                null,
                60,
                null,
                TaskStatus.SUCCEEDED,
                null,
                null,
                null,
                null,
                null,
                null,
                OffsetDateTime.now(),
                Map.of()
        );
    }

    private TaskResponse task(UUID projectId, UUID taskId) {
        return new TaskResponse(
                taskId,
                projectId,
                "AUTOMATED_TEST",
                TaskStatus.PENDING,
                0,
                "created",
                OffsetDateTime.now(),
                null,
                null,
                null,
                null,
                null,
                Map.of()
        );
    }
}
