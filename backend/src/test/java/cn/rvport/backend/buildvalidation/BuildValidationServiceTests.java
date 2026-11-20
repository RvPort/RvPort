package cn.rvport.backend.buildvalidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.audit.AuditLogService;
import cn.rvport.backend.common.EnvironmentVariableValidator;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.environment.EnvironmentRepository;
import cn.rvport.backend.environment.EnvironmentRole;
import cn.rvport.backend.environment.EnvironmentStatus;
import cn.rvport.backend.environment.EnvironmentView;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentService;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentResponse;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentView;
import cn.rvport.backend.source.SourceRepository;
import cn.rvport.backend.source.SourceRevisionType;
import cn.rvport.backend.source.SourceRevisionView;
import cn.rvport.backend.source.deploy.SourceDeployRepository;
import cn.rvport.backend.source.deploy.SourceDeployRunView;
import cn.rvport.backend.source.deploy.SourceDeployStrategy;
import cn.rvport.backend.task.RvPortTaskTypes;
import cn.rvport.backend.task.TaskCreateCommand;
import cn.rvport.backend.task.TaskRepository;
import cn.rvport.backend.task.TaskResponse;
import cn.rvport.backend.task.TaskScriptPayloadService;
import cn.rvport.backend.task.TaskScriptPayloadView;
import cn.rvport.backend.task.TaskService;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.workflow.DomainLatestResponse;
import cn.rvport.backend.workflow.ProjectContextService;
import cn.rvport.backend.workflow.ProjectContextSnapshot;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.JSONB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BuildValidationServiceTests {

    @Mock
    private BuildValidationRepository buildValidationRepository;
    @Mock
    private SourceDeployRepository sourceDeployRepository;
    @Mock
    private SourceRepository sourceRepository;
    @Mock
    private EnvironmentRepository environmentRepository;
    @Mock
    private ProjectRuntimeEnvironmentService projectRuntimeEnvironmentService;
    @Mock
    private ProjectAccessService projectAccessService;
    @Mock
    private TaskService taskService;
    @Mock
    private TaskRepository taskRepository;
    @Mock
    private ProjectContextService projectContextService;
    @Mock
    private TaskScriptPayloadService taskScriptPayloadService;
    @Mock
    private AuditLogService auditLogService;

    @Test
    void createsBuildsForTwoSuccessfulDeploymentsAndStoresScriptPayload() {
        BuildValidationService service = service();
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        SourceRevisionView revision = revision(projectId);
        EnvironmentView x86 = environment(projectId, EnvironmentRole.SOURCE_X86);
        EnvironmentView riscv = environment(projectId, EnvironmentRole.TARGET_RISCV);
        SourceDeployRunView x86Deployment = deployment(projectId, revision.id(), x86.id(), TaskStatus.SUCCEEDED);
        SourceDeployRunView riscvDeployment = deployment(projectId, revision.id(), riscv.id(), TaskStatus.SUCCEEDED);
        when(projectRuntimeEnvironmentService.requireComplete(projectId, actorId, false))
                .thenReturn(new ProjectRuntimeEnvironmentView(projectId, x86.id(), riscv.id(), 2L, actorId, null, null));
        when(sourceDeployRepository.findLatestSucceeded(projectId, revision.id(), x86.id(), 2L)).thenReturn(Optional.of(x86Deployment));
        when(sourceDeployRepository.findLatestSucceeded(projectId, revision.id(), riscv.id(), 2L)).thenReturn(Optional.of(riscvDeployment));
        when(sourceRepository.findById(revision.id())).thenReturn(Optional.of(revision));
        when(environmentRepository.findById(x86.id())).thenReturn(Optional.of(x86));
        when(environmentRepository.findById(riscv.id())).thenReturn(Optional.of(riscv));
        when(taskService.create(any())).thenAnswer(invocation -> task(projectId, actorId, invocation.getArgument(0)));
        when(taskScriptPayloadService.save(any(), eq(projectId), any(), eq("make all"), eq(actorId)))
                .thenAnswer(invocation -> payload(invocation.getArgument(0), projectId, invocation.getArgument(2), "hash"));
        when(buildValidationRepository.createPending(any(), eq(projectId), any(), eq(revision.id()), eq(x86.id()),
                eq(x86Deployment.id()), eq(2L), any(), any(), any(), eq(x86Deployment.remotePath()), eq(120), any()))
                .thenAnswer(invocation -> run(invocation.getArgument(0), projectId, invocation.getArgument(2),
                        revision.id(), x86.id(), x86Deployment.id(), invocation.getArgument(7), invocation.getArgument(8),
                        invocation.getArgument(9), x86Deployment.remotePath(), TaskStatus.PENDING));
        when(buildValidationRepository.createPending(any(), eq(projectId), any(), eq(revision.id()), eq(riscv.id()),
                eq(riscvDeployment.id()), eq(2L), any(), any(), any(), eq(riscvDeployment.remotePath()), eq(120), any()))
                .thenAnswer(invocation -> run(invocation.getArgument(0), projectId, invocation.getArgument(2),
                        revision.id(), riscv.id(), riscvDeployment.id(), invocation.getArgument(7), invocation.getArgument(8),
                        invocation.getArgument(9), riscvDeployment.remotePath(), TaskStatus.PENDING));

        BuildValidationCreateResponse response = service.create(projectId,
                new BuildValidationCreateRequest(revision.id(), null, "make all", 120,
                        Map.of("CC", "gcc", "API_TOKEN", "secret")),
                actorId, false, null);

        assertThat(response.builds()).hasSize(2);
        assertThat(response.builds()).extracting(BuildValidationResponse::sourceDeploymentId)
                .containsExactly(x86Deployment.id(), riscvDeployment.id());
        ArgumentCaptor<TaskCreateCommand> commandCaptor = ArgumentCaptor.forClass(TaskCreateCommand.class);
        verify(taskService, org.mockito.Mockito.times(2)).create(commandCaptor.capture());
        assertThat(commandCaptor.getAllValues()).allSatisfy(command ->
                assertThat(command.inputSnapshot().get("buildBatchId")).isNotNull()
                        .isEqualTo(commandCaptor.getValue().inputSnapshot().get("buildBatchId")));
        assertThat(commandCaptor.getAllValues()).allSatisfy(command -> {
            assertThat(command.type()).isEqualTo(RvPortTaskTypes.BUILD_VALIDATION);
            assertThat(command.inputSnapshot()).containsKeys("buildRunId", "sourceDeploymentId", "scriptPayloadId", "scriptSha256",
                    "environmentVariables", "environmentVariablesMasked");
            assertThat((Map<String, Object>) command.inputSnapshot().get("environmentVariables")).containsEntry("CC", "gcc");
            assertThat((Map<String, Object>) command.inputSnapshot().get("environmentVariablesMasked")).containsEntry("API_TOKEN", "****");
            assertThat(command.inputSnapshot().toString()).doesNotContain("make all");
        });
        verify(taskScriptPayloadService, org.mockito.Mockito.times(2)).save(any(), eq(projectId), any(), eq("make all"), eq(actorId));
    }

    @Test
    void createsSourceOnlyBuildForDependencyAnalysis() {
        BuildValidationService service = service();
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        SourceRevisionView revision = revision(projectId);
        EnvironmentView x86 = environment(projectId, EnvironmentRole.SOURCE_X86);
        EnvironmentView riscv = environment(projectId, EnvironmentRole.TARGET_RISCV);
        SourceDeployRunView x86Deployment = deployment(projectId, revision.id(), x86.id(), TaskStatus.SUCCEEDED);
        when(projectRuntimeEnvironmentService.requireSource(projectId, actorId, false))
                .thenReturn(new ProjectRuntimeEnvironmentView(projectId, x86.id(), riscv.id(), 2L, actorId, null, null));
        when(sourceDeployRepository.findLatestSucceeded(projectId, revision.id(), x86.id(), 2L)).thenReturn(Optional.of(x86Deployment));
        when(sourceRepository.findById(revision.id())).thenReturn(Optional.of(revision));
        when(environmentRepository.findById(x86.id())).thenReturn(Optional.of(x86));
        when(taskService.create(any())).thenAnswer(invocation -> task(projectId, actorId, invocation.getArgument(0)));
        when(taskScriptPayloadService.save(any(), eq(projectId), any(), eq("make all"), eq(actorId)))
                .thenAnswer(invocation -> payload(invocation.getArgument(0), projectId, invocation.getArgument(2), "hash"));
        when(buildValidationRepository.createPending(any(), eq(projectId), any(), eq(revision.id()), eq(x86.id()),
                eq(BuildValidationScope.SOURCE_ONLY), eq(x86Deployment.id()), eq(2L), any(), any(), any(),
                eq(x86Deployment.remotePath()), eq(120), any()))
                .thenAnswer(invocation -> new BuildValidationRunView(invocation.getArgument(0), projectId,
                        invocation.getArgument(2), revision.id(), x86.id(), BuildValidationScope.SOURCE_ONLY,
                        x86Deployment.id(), invocation.getArgument(8), invocation.getArgument(9),
                        invocation.getArgument(10), x86Deployment.remotePath(), 120, null,
                        TaskStatus.PENDING, null, null, null, null, null, null, OffsetDateTime.now(), Map.of()));

        BuildValidationCreateResponse response = service.create(projectId,
                new BuildValidationCreateRequest(revision.id(), null, BuildValidationScope.SOURCE_ONLY, "make all", 120, null),
                actorId, false, null);

        assertThat(response.builds()).hasSize(1);
        assertThat(response.builds().getFirst().buildScope()).isEqualTo(BuildValidationScope.SOURCE_ONLY);
        verify(sourceDeployRepository, never()).findLatestSucceeded(projectId, revision.id(), riscv.id(), 2L);
        ArgumentCaptor<TaskCreateCommand> commandCaptor = ArgumentCaptor.forClass(TaskCreateCommand.class);
        verify(taskService).create(commandCaptor.capture());
        assertThat(commandCaptor.getValue().inputSnapshot().get("buildScope")).isEqualTo("SOURCE_ONLY");
    }

    @Test
    void createsBuildWhenNoReusableSourceDeploymentExists() {
        BuildValidationService service = service();
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        SourceRevisionView revision = revision(projectId);
        EnvironmentView x86 = environment(projectId, EnvironmentRole.SOURCE_X86);
        EnvironmentView riscv = environment(projectId, EnvironmentRole.TARGET_RISCV);
        when(projectRuntimeEnvironmentService.requireSource(projectId, actorId, false))
                .thenReturn(new ProjectRuntimeEnvironmentView(projectId, x86.id(), riscv.id(), 2L, actorId, null, null));
        when(sourceRepository.findById(revision.id())).thenReturn(Optional.of(revision));
        when(environmentRepository.findById(x86.id())).thenReturn(Optional.of(x86));
        when(sourceDeployRepository.findLatestSucceeded(projectId, revision.id(), x86.id(), 2L)).thenReturn(Optional.empty());
        when(taskService.create(any())).thenAnswer(invocation -> task(projectId, actorId, invocation.getArgument(0)));
        when(taskScriptPayloadService.save(any(), eq(projectId), any(), eq("make all"), eq(actorId)))
                .thenAnswer(invocation -> payload(invocation.getArgument(0), projectId, invocation.getArgument(2), "hash"));
        String expectedRemotePath = "/workspace/rvport-sources/" + revision.id();
        when(buildValidationRepository.createPending(any(), eq(projectId), any(), eq(revision.id()), eq(x86.id()),
                eq(BuildValidationScope.SOURCE_ONLY), isNull(), eq(2L), any(), any(), any(),
                eq(expectedRemotePath), eq(120), any()))
                .thenAnswer(invocation -> new BuildValidationRunView(invocation.getArgument(0), projectId,
                        invocation.getArgument(2), revision.id(), x86.id(), BuildValidationScope.SOURCE_ONLY,
                        null, invocation.getArgument(8), invocation.getArgument(9),
                        invocation.getArgument(10), expectedRemotePath, 120, null,
                        TaskStatus.PENDING, null, null, null, null, null, null, OffsetDateTime.now(), Map.of()));

        BuildValidationCreateResponse response = service.create(projectId,
                new BuildValidationCreateRequest(revision.id(), null, BuildValidationScope.SOURCE_ONLY, "make all", 120, null),
                actorId, false, null);

        assertThat(response.builds()).hasSize(1);
        assertThat(response.builds().getFirst().sourceDeploymentId()).isNull();
        assertThat(response.builds().getFirst().workingDirectory()).isEqualTo(expectedRemotePath);
        ArgumentCaptor<TaskCreateCommand> commandCaptor = ArgumentCaptor.forClass(TaskCreateCommand.class);
        verify(taskService).create(commandCaptor.capture());
        assertThat(commandCaptor.getValue().inputSnapshot()).containsEntry("sourceDeploymentId", null);
    }

    @Test
    void rejectsDisabledEnvironmentAndRevisionMismatch() {
        BuildValidationService service = service();
        UUID projectId = UUID.randomUUID();
        SourceRevisionView revision = revision(projectId);
        EnvironmentView disabled = new EnvironmentView(UUID.randomUUID(), projectId, "env", EnvironmentRole.SOURCE_X86, "x86_64",
                "127.0.0.1", 22, "root", UUID.randomUUID(), null, "/workspace",
                EnvironmentStatus.DISABLED, false, null, null, UUID.randomUUID(), OffsetDateTime.now(), null);
        EnvironmentView target = environment(projectId, EnvironmentRole.TARGET_RISCV);
        when(projectRuntimeEnvironmentService.requireComplete(eq(projectId), any(), eq(false)))
                .thenReturn(new ProjectRuntimeEnvironmentView(projectId, disabled.id(), target.id(), 2L, UUID.randomUUID(), null, null));
        when(sourceRepository.findById(revision.id())).thenReturn(Optional.of(revision));
        when(environmentRepository.findById(disabled.id())).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> service.create(projectId,
                new BuildValidationCreateRequest(revision.id(), null, "make", 60),
                UUID.randomUUID(), false, null))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("ENVIRONMENT_DISABLED");

        assertThatThrownBy(() -> service.create(projectId,
                new BuildValidationCreateRequest(UUID.randomUUID(), null, "make", 60),
                UUID.randomUUID(), false, null))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("SOURCE_REVISION_NOT_FOUND");
    }

    @Test
    void rejectsUnauthorizedAndCrossProjectDeployment() {
        BuildValidationService service = service();
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "PROJECT_ACCESS_DENIED", "无项目访问权限"))
                .when(projectAccessService).requireProjectAccess(projectId, actorId, false);

        assertThatThrownBy(() -> service.create(projectId,
                new BuildValidationCreateRequest(null, null, "make", 60),
                actorId, false, null))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("PROJECT_ACCESS_DENIED");
        verify(sourceDeployRepository, never()).findLatestSucceeded(any(), any(), any(), org.mockito.ArgumentMatchers.anyLong());

        assertThatThrownBy(() -> service.create(projectId,
                new BuildValidationCreateRequest(null, List.of(UUID.randomUUID()), "make", 60),
                UUID.randomUUID(), true, null))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("BUILD_SOURCE_DEPLOYMENTS_DEPRECATED");
    }

    @Test
    void rejectsEdgeCasesBeforeCreatingTask() {
        BuildValidationService service = service();
        UUID projectId = UUID.randomUUID();
        assertThatThrownBy(() -> service.create(projectId,
                new BuildValidationCreateRequest(null, null, " ", 60),
                UUID.randomUUID(), false, null))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("BUILD_SCRIPT_REQUIRED");

        assertThatThrownBy(() -> service.create(projectId,
                new BuildValidationCreateRequest(null, List.of(UUID.randomUUID(), UUID.randomUUID()), "make", 60),
                UUID.randomUUID(), false, null))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("BUILD_SOURCE_DEPLOYMENTS_DEPRECATED");

        assertThatThrownBy(() -> service.create(projectId,
                new BuildValidationCreateRequest(null, null, "x".repeat(12001), 60),
                UUID.randomUUID(), false, null))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("BUILD_SCRIPT_TOO_LONG");

        assertThatThrownBy(() -> service.create(projectId,
                new BuildValidationCreateRequest(null, null, "make", 60, Map.of("1BAD", "value")),
                UUID.randomUUID(), false, null))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("BUILD_ENV_NAME_INVALID");

        assertThatThrownBy(() -> service.create(projectId,
                new BuildValidationCreateRequest(null, null, "make", 60, Map.of("BIG", "x".repeat(4097))),
                UUID.randomUUID(), false, null))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("BUILD_ENV_VALUE_TOO_LONG");
        verify(taskService, never()).create(any());
    }

    @Test
    void rejectsInvalidListSortAndDelegatesCancel() {
        BuildValidationService service = service();
        UUID projectId = UUID.randomUUID();
        assertThatThrownBy(() -> service.list(projectId,
                new BuildValidationListRequest(1, 20, null, null, null, null, null, "remotePath", "desc"),
                UUID.randomUUID(), false))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("BUILD_QUERY_INVALID");

        UUID buildId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        BuildValidationRunView run = run(buildId, projectId, taskId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "hash", "make", "/workspace", TaskStatus.RUNNING);
        when(buildValidationRepository.findById(buildId)).thenReturn(Optional.of(run));
        when(taskService.cancel(eq(taskId), any(), eq(false))).thenReturn(new TaskResponse(taskId, projectId,
                RvPortTaskTypes.BUILD_VALIDATION, TaskStatus.CANCELLED, 0, "任务已取消",
                OffsetDateTime.now(), null, null, OffsetDateTime.now(), null, null, Map.of()));

        TaskResponse cancelled = service.cancel(buildId, UUID.randomUUID(), false);

        assertThat(cancelled.status()).isEqualTo(TaskStatus.CANCELLED);
        verify(buildValidationRepository).syncStatusByTask(taskId, TaskStatus.CANCELLED);

        UUID finishedBuildId = UUID.randomUUID();
        UUID finishedTaskId = UUID.randomUUID();
        BuildValidationRunView finishedRun = run(finishedBuildId, projectId, finishedTaskId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "hash", "make", "/workspace", TaskStatus.SUCCEEDED);
        when(buildValidationRepository.findById(finishedBuildId)).thenReturn(Optional.of(finishedRun));
        when(taskService.cancel(eq(finishedTaskId), any(), eq(false))).thenReturn(new TaskResponse(finishedTaskId, projectId,
                RvPortTaskTypes.BUILD_VALIDATION, TaskStatus.SUCCEEDED, 100, "任务已完成",
                OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now(), null, null, null, Map.of()));

        TaskResponse alreadyFinished = service.cancel(finishedBuildId, UUID.randomUUID(), false);

        assertThat(alreadyFinished.status()).isEqualTo(TaskStatus.SUCCEEDED);
        verify(buildValidationRepository).syncStatusByTask(finishedTaskId, TaskStatus.SUCCEEDED);
    }

    @Test
    void getReturnsCompileDatabaseSummaryAfterAccessCheck() {
        BuildValidationService service = service();
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID buildId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        SourceRevisionView revision = revision(projectId);
        EnvironmentView environment = environment(projectId, EnvironmentRole.SOURCE_X86);
        UUID artifactId = UUID.randomUUID();
        Map<String, Object> buildFacts = Map.of("compileDatabase", Map.of(
                "available", true,
                "artifactType", BuildValidationArtifacts.COMPILE_COMMANDS_JSON,
                "storedArtifactId", artifactId,
                "sha256", "a".repeat(64),
                "sizeBytes", 128,
                "commandCount", 2
        ));
        BuildValidationRunView run = new BuildValidationRunView(
                buildId,
                projectId,
                taskId,
                revision.id(),
                environment.id(),
                BuildValidationScope.SOURCE_ONLY,
                UUID.randomUUID(),
                3L,
                UUID.randomUUID(),
                "hash",
                "cmake --build build",
                "/workspace/source",
                120,
                0,
                TaskStatus.SUCCEEDED,
                null,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                OffsetDateTime.now().minusMinutes(2),
                OffsetDateTime.now().minusMinutes(1),
                OffsetDateTime.now().minusMinutes(3),
                Map.of(),
                buildFacts
        );
        when(buildValidationRepository.findById(buildId)).thenReturn(Optional.of(run));
        when(sourceRepository.findById(revision.id())).thenReturn(Optional.of(revision));
        when(environmentRepository.findById(environment.id())).thenReturn(Optional.of(environment));
        when(projectRuntimeEnvironmentService.currentVersion(projectId)).thenReturn(3L);
        when(taskScriptPayloadService.requireScript(run.scriptPayloadId(), projectId, taskId, "hash"))
                .thenReturn("cmake --build build\nctest --test-dir build\n");

        BuildValidationResponse response = service.get(buildId, actorId, false);

        verify(projectAccessService).requireProjectAccess(projectId, actorId, false);
        assertThat(response.command()).isEqualTo("cmake --build build\nctest --test-dir build\n");
        assertThat(response.commandSummary()).isEqualTo("cmake --build build");
        assertThat(response.compileDatabase()).containsEntry("available", true)
                .containsEntry("artifactType", BuildValidationArtifacts.COMPILE_COMMANDS_JSON)
                .containsEntry("storedArtifactId", artifactId)
                .containsEntry("commandCount", 2);
    }

    @Test
    void latestReturnsFullScriptForCurrentRunAndKeepsSummary() {
        BuildValidationService service = service();
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        SourceRevisionView revision = revision(projectId);
        EnvironmentView environment = environment(projectId, EnvironmentRole.SOURCE_X86);
        UUID buildId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID scriptPayloadId = UUID.randomUUID();
        String script = """
                #!/usr/bin/env bash
                set -eu
                rm -rf build
                cmake -S . -B build
                cmake --build build
                """;
        BuildValidationRunView run = new BuildValidationRunView(
                buildId,
                projectId,
                taskId,
                revision.id(),
                environment.id(),
                BuildValidationScope.SOURCE_ONLY,
                UUID.randomUUID(),
                7L,
                scriptPayloadId,
                "hash",
                "#!/usr/bin/env bash # sha256:hash",
                "/workspace/source",
                120,
                0,
                TaskStatus.SUCCEEDED,
                null,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                OffsetDateTime.now().minusMinutes(2),
                OffsetDateTime.now().minusMinutes(1),
                OffsetDateTime.now().minusMinutes(3),
                Map.of(),
                Map.of()
        );
        when(projectContextService.snapshot(projectId)).thenReturn(new ProjectContextSnapshot(revision.id(), 7L));
        when(projectRuntimeEnvironmentService.summary(projectId)).thenReturn(new ProjectRuntimeEnvironmentResponse(
                projectId,
                cn.rvport.backend.source.deploy.SourceDeployEnvironmentSummary.from(environment),
                null,
                7L,
                false,
                List.of("targetEnvironment"),
                actorId,
                OffsetDateTime.now()
        ));
        when(buildValidationRepository.findLatest(projectId, revision.id(), environment.id(), 7L, BuildValidationScope.SOURCE_ONLY))
                .thenReturn(Optional.of(run));
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());
        when(sourceRepository.findById(revision.id())).thenReturn(Optional.of(revision));
        when(environmentRepository.findById(environment.id())).thenReturn(Optional.of(environment));
        when(projectRuntimeEnvironmentService.currentVersion(projectId)).thenReturn(7L);
        when(taskScriptPayloadService.requireScript(scriptPayloadId, projectId, taskId, "hash")).thenReturn(script);

        DomainLatestResponse<BuildValidationResponse> response = service.latest(projectId, BuildValidationLatestMode.SOURCE_ONLY, actorId, false);

        assertThat(response.result().command()).isEqualTo(script);
        assertThat(response.result().commandSummary()).isEqualTo("#!/usr/bin/env bash # sha256:hash");
        assertThat(response.result().command()).contains("cmake --build build");
    }

    @Test
    void listKeepsCommandAsSummaryAndDoesNotLoadScriptPayloads() {
        BuildValidationService service = service();
        UUID projectId = UUID.randomUUID();
        SourceRevisionView revision = revision(projectId);
        EnvironmentView environment = environment(projectId, EnvironmentRole.SOURCE_X86);
        UUID taskId = UUID.randomUUID();
        BuildValidationRunView run = run(UUID.randomUUID(), projectId, taskId, revision.id(), environment.id(),
                UUID.randomUUID(), UUID.randomUUID(), "hash", "set -eu # sha256:hash", "/workspace", TaskStatus.SUCCEEDED);
        when(sourceRepository.findActive(projectId)).thenReturn(Optional.of(revision));
        when(projectRuntimeEnvironmentService.currentVersion(projectId)).thenReturn(3L);
        when(buildValidationRepository.count(eq(projectId), any())).thenReturn(1L);
        when(buildValidationRepository.list(eq(projectId), any())).thenReturn(List.of(run));
        when(sourceRepository.findById(revision.id())).thenReturn(Optional.of(revision));
        when(environmentRepository.findById(environment.id())).thenReturn(Optional.of(environment));

        BuildValidationResponse response = service.list(projectId,
                new BuildValidationListRequest(1, 20, null, null, null, null, null, "createdAt", "desc"),
                UUID.randomUUID(), false).items().getFirst();

        assertThat(response.command()).isEqualTo("set -eu # sha256:hash");
        assertThat(response.commandSummary()).isEqualTo("set -eu # sha256:hash");
        verify(taskScriptPayloadService, never()).requireScript(any(), any(), any(), any());
    }

    @Test
    void getFailsSafelyWhenScriptPayloadCannotBeLoaded() {
        BuildValidationService service = service();
        UUID projectId = UUID.randomUUID();
        UUID buildId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID scriptPayloadId = UUID.randomUUID();
        SourceRevisionView revision = revision(projectId);
        EnvironmentView environment = environment(projectId, EnvironmentRole.SOURCE_X86);
        BuildValidationRunView run = run(buildId, projectId, taskId, revision.id(), environment.id(),
                UUID.randomUUID(), scriptPayloadId, "hash", "set -eu # sha256:hash", "/workspace", TaskStatus.SUCCEEDED);
        when(buildValidationRepository.findById(buildId)).thenReturn(Optional.of(run));
        doThrow(new cn.rvport.backend.task.TaskExecutionException("TASK_INPUT_INVALID", "脚本正文 hash 校验失败", null))
                .when(taskScriptPayloadService).requireScript(scriptPayloadId, projectId, taskId, "hash");

        assertThatThrownBy(() -> service.get(buildId, UUID.randomUUID(), false))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("BUILD_SCRIPT_PAYLOAD_UNAVAILABLE");
    }

    @Test
    void listReturnsEmptyWhenCurrentRevisionDoesNotExist() {
        UUID projectId = UUID.randomUUID();
        BuildValidationListRequest request = new BuildValidationListRequest(
                2, 50, null, null, null, null, null, "createdAt", "asc");
        when(sourceRepository.findActive(projectId)).thenReturn(Optional.empty());

        var response = service().list(projectId, request, UUID.randomUUID(), false);

        assertThat(response.items()).isEmpty();
        assertThat(response.page()).isEqualTo(2);
        assertThat(response.pageSize()).isEqualTo(50);
        assertThat(response.total()).isZero();
        verify(buildValidationRepository, never()).count(any(), any());
    }

    @Test
    void rejectsRequestedRevisionFromAnotherProject() {
        UUID projectId = UUID.randomUUID();
        SourceRevisionView foreignRevision = revision(UUID.randomUUID());
        EnvironmentView source = environment(projectId, EnvironmentRole.SOURCE_X86);
        EnvironmentView target = environment(projectId, EnvironmentRole.TARGET_RISCV);
        when(projectRuntimeEnvironmentService.requireComplete(eq(projectId), any(), eq(false)))
                .thenReturn(new ProjectRuntimeEnvironmentView(projectId, source.id(), target.id(), 1L,
                        UUID.randomUUID(), null, null));
        when(sourceRepository.findById(foreignRevision.id())).thenReturn(Optional.of(foreignRevision));

        assertThatThrownBy(() -> service().create(projectId,
                new BuildValidationCreateRequest(foreignRevision.id(), null, "make", 60),
                UUID.randomUUID(), false, null))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("SOURCE_REVISION_NOT_FOUND");
    }

    @Test
    void rejectsMissingAndCrossProjectRuntimeEnvironment() {
        UUID projectId = UUID.randomUUID();
        SourceRevisionView revision = revision(projectId);
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(projectRuntimeEnvironmentService.requireComplete(eq(projectId), any(), eq(false)))
                .thenReturn(new ProjectRuntimeEnvironmentView(projectId, sourceId, targetId, 1L,
                        UUID.randomUUID(), null, null));
        when(sourceRepository.findById(revision.id())).thenReturn(Optional.of(revision));
        when(environmentRepository.findById(sourceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().create(projectId,
                new BuildValidationCreateRequest(revision.id(), null, "make", 60),
                UUID.randomUUID(), false, null))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("ENVIRONMENT_NOT_FOUND");

        when(environmentRepository.findById(sourceId))
                .thenReturn(Optional.of(environment(UUID.randomUUID(), EnvironmentRole.SOURCE_X86)));
        assertThatThrownBy(() -> service().create(projectId,
                new BuildValidationCreateRequest(revision.id(), null, "make", 60),
                UUID.randomUUID(), false, null))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("ENVIRONMENT_NOT_FOUND");
    }

    @Test
    void rejectsNullScriptAndInvalidSortDirectionWhileAllowingBlankSorts() {
        UUID projectId = UUID.randomUUID();
        assertThatThrownBy(() -> service().create(projectId,
                new BuildValidationCreateRequest(null, null, null, 60),
                UUID.randomUUID(), false, null))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("BUILD_SCRIPT_REQUIRED");

        assertThatThrownBy(() -> service().list(projectId,
                new BuildValidationListRequest(1, 20, null, null, null, null, null, "createdAt", "sideways"),
                UUID.randomUUID(), false))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("BUILD_QUERY_INVALID");
    }

    private BuildValidationService service() {
        return new BuildValidationService(buildValidationRepository, sourceDeployRepository, sourceRepository,
                environmentRepository, projectRuntimeEnvironmentService, projectAccessService, taskService,
                taskRepository, projectContextService, taskScriptPayloadService, auditLogService,
                new EnvironmentVariableValidator());
    }

    private SourceRevisionView revision(UUID projectId) {
        return new SourceRevisionView(UUID.randomUUID(), projectId, SourceRevisionType.ARCHIVE, "v1",
                "source.zip", null, null, null, null, "/local/source", null, true,
                UUID.randomUUID(), OffsetDateTime.now());
    }

    private EnvironmentView environment(UUID projectId, EnvironmentRole role) {
        return new EnvironmentView(UUID.randomUUID(), projectId, role.name(), role, role == EnvironmentRole.SOURCE_X86 ? "x86_64" : "riscv64",
                "127.0.0.1", 22, "root", UUID.randomUUID(), null, "/workspace",
                EnvironmentStatus.AVAILABLE, true, null, null, UUID.randomUUID(), OffsetDateTime.now(), null);
    }

    private SourceDeployRunView deployment(UUID projectId, UUID revisionId, UUID environmentId, TaskStatus status) {
        return new SourceDeployRunView(UUID.randomUUID(), projectId, UUID.randomUUID(), revisionId, environmentId,
                "/workspace/rvport-sources/" + revisionId, SourceDeployStrategy.TAR_OVER_SSH, false, status,
                1, 10L, 0, JSONB.valueOf("{}"), OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now());
    }

    private TaskResponse task(UUID projectId, UUID actorId, TaskCreateCommand command) {
        return new TaskResponse(UUID.randomUUID(), projectId, command.type(), TaskStatus.PENDING, 0,
                command.message(), OffsetDateTime.now(), null, null, null, null, null, Map.of());
    }

    private TaskScriptPayloadView payload(UUID id, UUID projectId, UUID taskId, String sha256) {
        return new TaskScriptPayloadView(id, projectId, taskId, sha256, 8, JSONB.valueOf("{}"), "local-v1", UUID.randomUUID(), OffsetDateTime.now());
    }

    private BuildValidationRunView run(UUID id, UUID projectId, UUID taskId, UUID revisionId, UUID environmentId,
                                       UUID deploymentId, UUID scriptPayloadId, String scriptSha256,
                                       String command, String workingDirectory, TaskStatus status) {
        return new BuildValidationRunView(id, projectId, taskId, revisionId, environmentId, deploymentId,
                scriptPayloadId, scriptSha256, command, workingDirectory, 120, null, status, null,
                null, null, null, null, null, OffsetDateTime.now(), Map.of());
    }
}
