package cn.rvport.backend.source.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.rvport.backend.credential.CredentialKind;
import cn.rvport.backend.credential.CredentialService;
import cn.rvport.backend.environment.EnvironmentRepository;
import cn.rvport.backend.environment.EnvironmentRole;
import cn.rvport.backend.environment.EnvironmentStatus;
import cn.rvport.backend.environment.EnvironmentView;
import cn.rvport.backend.environment.runtime.SshClientAdapter;
import cn.rvport.backend.environment.runtime.SshCommandRequest;
import cn.rvport.backend.environment.runtime.SshCommandResult;
import cn.rvport.backend.environment.runtime.SshRuntimeException;
import cn.rvport.backend.source.SourceRepository;
import cn.rvport.backend.source.SourceRevisionType;
import cn.rvport.backend.source.SourceRevisionView;
import cn.rvport.backend.task.TaskExecutionException;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.task.TaskView;
import cn.rvport.backend.task.ToolRunRepository;
import cn.rvport.backend.worker.TaskExecutionContext;
import cn.rvport.backend.worker.TaskCancelledException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.JSONB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SourceDeployExecutorTests {

    @TempDir
    Path tempDir;

    @Mock
    private SourceDeployRepository sourceDeployRepository;
    @Mock
    private SourceRepository sourceRepository;
    @Mock
    private EnvironmentRepository environmentRepository;
    @Mock
    private CredentialService credentialService;
    @Mock
    private SshClientAdapter sshClientAdapter;
    @Mock
    private ToolRunRepository toolRunRepository;
    @Mock
    private TaskExecutionContext context;

    @org.junit.jupiter.api.BeforeEach
    void executeGuardedPublications() {
        org.mockito.Mockito.lenient().when(context.publish(any())).thenAnswer(invocation ->
                invocation.<java.util.function.Supplier<?>>getArgument(0).get());
    }

    @Test
    void reusesLatestSucceededDeploymentWithoutTransfer() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        SourceRevisionView revision = revision(projectId, tempDir);
        EnvironmentView environment = environment(projectId, "/workspace");
        SourceDeployRunView reusable = run(UUID.randomUUID(), projectId, taskId, revision.id(), environment.id(), "/workspace/rvport-sources/" + revision.id(), TaskStatus.SUCCEEDED);
        when(context.task()).thenReturn(task(taskId, projectId));
        when(sourceRepository.findById(revision.id())).thenReturn(Optional.of(revision));
        when(environmentRepository.findById(environment.id())).thenReturn(Optional.of(environment));
        when(sourceDeployRepository.findLatestSucceeded(projectId, revision.id(), environment.id(), 3L)).thenReturn(Optional.of(reusable));

        SourceDeployRunView result = executor().ensureDeployed(request(projectId, taskId, revision.id(), environment.id(), 3L, null), context);

        assertThat(result.id()).isEqualTo(reusable.id());
        verify(sourceDeployRepository, never()).createPending(any(), any(), any(), any(), any(), any(Long.class), any(), eq(false));
        verify(sshClientAdapter, never()).execute(any());
    }

    @Test
    void createsAndTransfersDeploymentWhenReusableRecordDoesNotExist() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        SourceRevisionView revision = revision(projectId, tempDir);
        Files.writeString(tempDir.resolve("main.c"), "int main(void) { return 0; }\n");
        EnvironmentView environment = environment(projectId, "/workspace");
        SourceDeployRunView pending = run(UUID.randomUUID(), projectId, taskId, revision.id(), environment.id(), "/workspace/rvport-sources/" + revision.id(), TaskStatus.PENDING);
        when(context.task()).thenReturn(task(taskId, projectId));
        when(sourceDeployRepository.findLatestSucceeded(projectId, revision.id(), environment.id(), 3L)).thenReturn(Optional.empty());
        when(sourceRepository.findById(revision.id())).thenReturn(Optional.of(revision));
        when(environmentRepository.findById(environment.id())).thenReturn(Optional.of(environment));
        when(sourceDeployRepository.createPending(any(), eq(projectId), eq(taskId), eq(revision.id()), eq(environment.id()), eq(3L),
                eq("/workspace/rvport-sources/" + revision.id()), eq(false))).thenReturn(pending);
        when(toolRunRepository.create(eq(projectId), eq(taskId), any(), any(), any(), eq(TaskStatus.RUNNING), any())).thenReturn(UUID.randomUUID());
        lenient().when(credentialService.sshRuntimeCredential(environment.credentialId(), projectId))
                .thenReturn(new CredentialService.SshRuntimeCredential(environment.credentialId(), CredentialKind.SSH_KEY, "root", null, "key", null));
        when(sshClientAdapter.execute(any(SshCommandRequest.class))).thenReturn(new SshCommandResult(0, "SOURCE_DEPLOY=ok\n", "", Duration.ofMillis(20)));
        lenient().when(sourceDeployRepository.findById(pending.id())).thenReturn(Optional.of(pending));

        SourceDeployRunView result = executor().ensureDeployed(request(projectId, taskId, revision.id(), environment.id(), 3L, null), context);

        assertThat(result.id()).isEqualTo(pending.id());
        verify(sourceDeployRepository).markRunning(pending.id());
        verify(sourceDeployRepository).markFinished(eq(pending.id()), eq(TaskStatus.SUCCEEDED), eq(1), any(), eq(0), any());
        verify(sshClientAdapter).execute(any(SshCommandRequest.class));
    }

    @Test
    void rejectsExplicitDeploymentFromAnotherProject() {
        UUID projectId = UUID.randomUUID();
        UUID otherProjectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        SourceDeployRunView foreign = run(UUID.randomUUID(), otherProjectId, taskId, revisionId, environmentId, "/workspace/source", TaskStatus.SUCCEEDED);
        when(context.task()).thenReturn(task(taskId, projectId));
        when(sourceDeployRepository.findById(foreign.id())).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> executor().ensureDeployed(new SourceDeployExecutionRequest(
                projectId, taskId, foreign.id(), revisionId, environmentId, 3L, null, false, 60
        ), context))
                .isInstanceOf(TaskExecutionException.class)
                .extracting("errorCode")
                .isEqualTo("TASK_PROJECT_MISMATCH");
    }

    @Test
    void rejectsEnvironmentWithoutWorkingDirectory() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        SourceRevisionView revision = revision(projectId, tempDir);
        EnvironmentView environment = environment(projectId, " ");
        when(context.task()).thenReturn(task(taskId, projectId));
        when(sourceDeployRepository.findLatestSucceeded(projectId, revision.id(), environment.id(), 3L)).thenReturn(Optional.empty());
        when(sourceRepository.findById(revision.id())).thenReturn(Optional.of(revision));
        when(environmentRepository.findById(environment.id())).thenReturn(Optional.of(environment));

        assertThatThrownBy(() -> executor().ensureDeployed(request(projectId, taskId, revision.id(), environment.id(), 3L, null), context))
                .isInstanceOf(TaskExecutionException.class)
                .extracting("errorCode")
                .isEqualTo("ENVIRONMENT_WORKING_DIRECTORY_REQUIRED");
    }

    @Test
    void rejectsMissingWorkingCopy() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        SourceRevisionView revision = revision(projectId, tempDir.resolve("missing"));
        EnvironmentView environment = environment(projectId, "/workspace");
        SourceDeployRunView pending = run(UUID.randomUUID(), projectId, taskId, revision.id(), environment.id(), "/workspace/rvport-sources/" + revision.id(), TaskStatus.PENDING);
        when(context.task()).thenReturn(task(taskId, projectId));
        when(sourceDeployRepository.findLatestSucceeded(projectId, revision.id(), environment.id(), 3L)).thenReturn(Optional.empty());
        when(sourceRepository.findById(revision.id())).thenReturn(Optional.of(revision));
        when(environmentRepository.findById(environment.id())).thenReturn(Optional.of(environment));
        when(sourceDeployRepository.createPending(any(), eq(projectId), eq(taskId), eq(revision.id()), eq(environment.id()), eq(3L),
                eq("/workspace/rvport-sources/" + revision.id()), eq(false))).thenReturn(pending);
        when(toolRunRepository.create(eq(projectId), eq(taskId), any(), any(), any(), eq(TaskStatus.RUNNING), any())).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> executor().ensureDeployed(request(projectId, taskId, revision.id(), environment.id(), 3L, null), context))
                .isInstanceOf(TaskExecutionException.class)
                .extracting("errorCode")
                .isEqualTo("TASK_RESOURCE_NOT_FOUND");
        verify(sourceDeployRepository).markFinished(eq(pending.id()), eq(TaskStatus.FAILED), eq(null), eq(null), eq(null), any());
    }

    @Test
    void validatesEveryDeploymentIdentityComponent() {
        SourceDeployExecutor executor = executor();
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        SourceDeployRunView run = run(UUID.randomUUID(), projectId, taskId, revisionId, environmentId,
                "/workspace/source", TaskStatus.RUNNING);

        ReflectionTestUtils.invokeMethod(executor, "validateRunMatches", run,
                request(projectId, taskId, revisionId, environmentId, 3L, null));
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(executor, "validateRunMatches", run,
                request(UUID.randomUUID(), taskId, revisionId, environmentId, 3L, null)))
                .isInstanceOf(TaskExecutionException.class).extracting("errorCode").isEqualTo("TASK_PROJECT_MISMATCH");
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(executor, "validateRunMatches", run,
                request(projectId, taskId, UUID.randomUUID(), environmentId, 3L, null)))
                .isInstanceOf(TaskExecutionException.class).extracting("errorCode").isEqualTo("TASK_PROJECT_MISMATCH");
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(executor, "validateRunMatches", run,
                request(projectId, taskId, revisionId, UUID.randomUUID(), 3L, null)))
                .isInstanceOf(TaskExecutionException.class).extracting("errorCode").isEqualTo("TASK_PROJECT_MISMATCH");
    }

    @Test
    void rejectsMissingForeignAndDisabledRuntimeResources() {
        SourceDeployExecutor executor = executor();
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        SourceDeployExecutionRequest request = request(projectId, taskId, revisionId, environmentId, 3L, null);
        when(sourceRepository.findById(revisionId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(executor, "requireSourceRevision", request))
                .isInstanceOf(TaskExecutionException.class).extracting("errorCode").isEqualTo("TASK_RESOURCE_NOT_FOUND");

        when(sourceRepository.findById(revisionId)).thenReturn(Optional.of(revision(UUID.randomUUID(), tempDir)));
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(executor, "requireSourceRevision", request))
                .isInstanceOf(TaskExecutionException.class).extracting("errorCode").isEqualTo("TASK_PROJECT_MISMATCH");

        when(environmentRepository.findById(environmentId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(executor, "requireEnvironment", request))
                .isInstanceOf(TaskExecutionException.class).extracting("errorCode").isEqualTo("TASK_RESOURCE_NOT_FOUND");

        EnvironmentView foreign = environment(UUID.randomUUID(), "/workspace");
        when(environmentRepository.findById(environmentId)).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(executor, "requireEnvironment", request))
                .isInstanceOf(TaskExecutionException.class).extracting("errorCode").isEqualTo("TASK_PROJECT_MISMATCH");

        EnvironmentView disabled = environment(environmentId, projectId, "/workspace", false, null);
        when(environmentRepository.findById(environmentId)).thenReturn(Optional.of(disabled));
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(executor, "requireEnvironment", request))
                .isInstanceOf(TaskExecutionException.class).extracting("errorCode").isEqualTo("TASK_RESOURCE_DISABLED");
    }

    @Test
    void validatesJumpHostTopologyAndAvailability() {
        SourceDeployExecutor executor = executor();
        UUID projectId = UUID.randomUUID();
        UUID jumpId = UUID.randomUUID();
        EnvironmentView target = environment(UUID.randomUUID(), projectId, "/workspace", true, jumpId);
        when(environmentRepository.findById(jumpId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(executor, "requireJumpHost", target))
                .isInstanceOf(TaskExecutionException.class).extracting("errorCode").isEqualTo("TASK_RESOURCE_NOT_FOUND");

        EnvironmentView foreign = environment(jumpId, UUID.randomUUID(), "/jump", true, null);
        when(environmentRepository.findById(jumpId)).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(executor, "requireJumpHost", target))
                .isInstanceOf(TaskExecutionException.class).extracting("errorCode").isEqualTo("TASK_PROJECT_MISMATCH");

        EnvironmentView disabled = environment(jumpId, projectId, "/jump", false, null);
        when(environmentRepository.findById(jumpId)).thenReturn(Optional.of(disabled));
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(executor, "requireJumpHost", target))
                .isInstanceOf(TaskExecutionException.class).extracting("errorCode").isEqualTo("TASK_RESOURCE_DISABLED");

        EnvironmentView nested = environment(jumpId, projectId, "/jump", true, UUID.randomUUID());
        when(environmentRepository.findById(jumpId)).thenReturn(Optional.of(nested));
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(executor, "requireJumpHost", target))
                .isInstanceOf(TaskExecutionException.class).extracting("errorCode").isEqualTo("TASK_UNSUPPORTED_OPERATION");

        EnvironmentView valid = environment(jumpId, projectId, "/jump", true, null);
        when(environmentRepository.findById(jumpId)).thenReturn(Optional.of(valid));
        assertThat((EnvironmentView) ReflectionTestUtils.invokeMethod(executor, "requireJumpHost", target)).isSameAs(valid);
        assertThat((EnvironmentView) ReflectionTestUtils.invokeMethod(executor, "requireJumpHost",
                environment(UUID.randomUUID(), projectId, "/workspace", true, null))).isNull();
    }

    @Test
    void normalizesRemotePathTimeoutShellQuotingAndTarFailureSignals() {
        SourceDeployExecutor executor = executor();
        UUID projectId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        EnvironmentView environment = environment(UUID.randomUUID(), projectId, "/work/", true, null);
        SourceDeployExecutionRequest generated = request(projectId, UUID.randomUUID(), revisionId,
                environment.id(), 3L, null);
        SourceDeployExecutionRequest explicit = request(projectId, UUID.randomUUID(), revisionId,
                environment.id(), 3L, "/work/rvport-sources/" + revisionId);
        SourceDeployExecutionRequest blank = request(projectId, UUID.randomUUID(), revisionId,
                environment.id(), 3L, " ");

        assertThat((String) ReflectionTestUtils.invokeMethod(executor, "effectiveRemotePath", generated, environment, revisionId))
                .isEqualTo("/work/rvport-sources/" + revisionId);
        assertThat((String) ReflectionTestUtils.invokeMethod(executor, "effectiveRemotePath", explicit, environment, revisionId))
                .isEqualTo("/work/rvport-sources/" + revisionId);
        assertThat((String) ReflectionTestUtils.invokeMethod(executor, "effectiveRemotePath", blank, environment, revisionId))
                .endsWith("/rvport-sources/" + revisionId);
        assertThat((Integer) ReflectionTestUtils.invokeMethod(executor, "effectiveTimeout", (Object) null)).isEqualTo(3600);
        assertThat((Integer) ReflectionTestUtils.invokeMethod(executor, "effectiveTimeout", 2)).isEqualTo(10);
        assertThat((Integer) ReflectionTestUtils.invokeMethod(executor, "effectiveTimeout", 40)).isEqualTo(40);
        assertThat((String) ReflectionTestUtils.invokeMethod(executor, "shellQuote", "a'b"))
                .isEqualTo("'a'\"'\"'b'");

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(executor, "containsTarMissing",
                new SshCommandResult(127, null, "SOURCE_DEPLOY_REMOTE_TAR_REQUIRED", Duration.ZERO))).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(executor, "containsTarMissing",
                new SshCommandResult(127, "SOURCE_DEPLOY_REMOTE_TAR_REQUIRED", null, Duration.ZERO))).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(executor, "containsTarMissing",
                new SshCommandResult(1, null, null, Duration.ZERO))).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(executor, "containsTarMissing",
                new SshCommandResult(1, "other", "other", Duration.ZERO))).isFalse();
    }

    @Test
    void marksRemoteCommandFailureAndClassifiesMissingTar() throws Exception {
        DeploymentFixture fixture = prepareDeployment();
        when(sshClientAdapter.execute(any(SshCommandRequest.class))).thenReturn(
                new SshCommandResult(127, "", "SOURCE_DEPLOY_REMOTE_TAR_REQUIRED", Duration.ofMillis(5)));

        assertThatThrownBy(() -> executor().ensureDeployed(fixture.request(), context))
                .isInstanceOf(TaskExecutionException.class)
                .extracting("errorCode").isEqualTo("SOURCE_DEPLOY_REMOTE_TAR_REQUIRED");
        verify(toolRunRepository).finish(eq(fixture.toolRunId()), eq(TaskStatus.FAILED), eq(127),
                org.mockito.ArgumentMatchers.argThat(summary ->
                        "SOURCE_DEPLOY_REMOTE_TAR_REQUIRED".equals(summary.get("errorCode"))));
    }

    @Test
    void marksCancellationBeforeTransferWithoutCallingSsh() throws Exception {
        DeploymentFixture fixture = prepareDeployment();
        org.mockito.Mockito.doThrow(new TaskCancelledException("cancelled"))
                .when(context).throwIfCancellationRequested();

        assertThatThrownBy(() -> executor().ensureDeployed(fixture.request(), context))
                .isInstanceOf(TaskCancelledException.class);
        verify(sourceDeployRepository).markFinished(fixture.run().id(), TaskStatus.CANCELLED,
                null, null, null, Map.of("error", "任务已取消"));
        verify(toolRunRepository).finish(fixture.toolRunId(), TaskStatus.CANCELLED,
                null, Map.of("error", "任务已取消"));
        verify(sshClientAdapter, never()).execute(any());
    }

    @Test
    void mapsSshFailuresWithAndWithoutSpecificErrorCodes() throws Exception {
        DeploymentFixture fixture = prepareDeployment();
        when(sshClientAdapter.execute(any(SshCommandRequest.class)))
                .thenThrow(new SshRuntimeException("network down"));
        assertThatThrownBy(() -> executor().ensureDeployed(fixture.request(), context))
                .isInstanceOf(TaskExecutionException.class)
                .extracting("errorCode").isEqualTo("TASK_REMOTE_COMMAND_FAILED");

        DeploymentFixture second = prepareDeployment();
        when(sshClientAdapter.execute(any(SshCommandRequest.class)))
                .thenThrow(new SshRuntimeException("SSH_AUTH_FAILED", "denied", null));
        assertThatThrownBy(() -> executor().ensureDeployed(second.request(), context))
                .isInstanceOf(TaskExecutionException.class)
                .extracting("errorCode").isEqualTo("SSH_AUTH_FAILED");
    }

    @Test
    void rejectsEnvironmentWithoutCredentialDuringTransfer() throws Exception {
        DeploymentFixture fixture = prepareDeployment();
        EnvironmentView withoutCredential = new EnvironmentView(
                fixture.environment().id(), fixture.environment().projectId(), "env", EnvironmentRole.TARGET_RISCV,
                "riscv64", "127.0.0.1", 22, "root", null, null, "/workspace",
                EnvironmentStatus.AVAILABLE, true, null, null, UUID.randomUUID(), OffsetDateTime.now(), null);
        when(environmentRepository.findById(withoutCredential.id())).thenReturn(Optional.of(withoutCredential));

        assertThatThrownBy(() -> executor().ensureDeployed(fixture.request(), context))
                .isInstanceOf(TaskExecutionException.class)
                .extracting("errorCode").isEqualTo("TASK_INPUT_INVALID");
        verify(sshClientAdapter, never()).execute(any());
    }

    @Test
    void deployExistingValidatesStoredRunAndExecutesIt() throws Exception {
        DeploymentFixture fixture = prepareDeployment();
        SourceDeployTaskInput input = new SourceDeployTaskInput(
                fixture.request().projectId(), fixture.run().id(), fixture.request().sourceRevisionId(),
                fixture.request().environmentId(), fixture.request().environmentConfigVersion(), false,
                fixture.request().timeoutSeconds());
        when(sourceDeployRepository.findById(fixture.run().id()))
                .thenReturn(Optional.of(fixture.run()), Optional.of(fixture.run()));
        when(sshClientAdapter.execute(any(SshCommandRequest.class))).thenReturn(
                new SshCommandResult(0, "SOURCE_DEPLOY=ok", "", Duration.ofMillis(5)));

        assertThat(executor().deployExisting(input, context).id()).isEqualTo(fixture.run().id());
    }

    @Test
    void retryRejectsEveryMismatchedIdentityBeforeAnyPublicationOrTransfer() throws Exception {
        DeploymentFixture f = prepareDeployment();
        var r = f.run();
        var invalid = java.util.List.of(
                copyRun(r, UUID.randomUUID(), r.taskId(), r.sourceRevisionId(), r.environmentId(), 3L, r.remotePath(), TaskStatus.FAILED),
                copyRun(r, r.projectId(), UUID.randomUUID(), r.sourceRevisionId(), r.environmentId(), 3L, r.remotePath(), TaskStatus.FAILED),
                copyRun(r, r.projectId(), r.taskId(), UUID.randomUUID(), r.environmentId(), 3L, r.remotePath(), TaskStatus.FAILED),
                copyRun(r, r.projectId(), r.taskId(), r.sourceRevisionId(), UUID.randomUUID(), 3L, r.remotePath(), TaskStatus.FAILED),
                copyRun(r, r.projectId(), r.taskId(), r.sourceRevisionId(), r.environmentId(), 4L, r.remotePath(), TaskStatus.FAILED),
                copyRun(r, r.projectId(), r.taskId(), r.sourceRevisionId(), r.environmentId(), null, r.remotePath(), TaskStatus.FAILED),
                copyRun(r, r.projectId(), r.taskId(), r.sourceRevisionId(), r.environmentId(), 3L, "/other/source", TaskStatus.FAILED));
        for (var bad : invalid) {
            when(sourceDeployRepository.findByTaskId(r.taskId())).thenReturn(Optional.of(bad));
            assertThatThrownBy(() -> executor().ensureDeployed(f.request(), context)).isInstanceOf(TaskExecutionException.class);
        }
        when(sourceDeployRepository.findByTaskId(r.taskId())).thenReturn(Optional.of(r));
        var explicitMismatch = new SourceDeployExecutionRequest(r.projectId(), r.taskId(), UUID.randomUUID(),
                r.sourceRevisionId(), r.environmentId(), 3L, null, false, 60);
        assertThatThrownBy(() -> executor().ensureDeployed(explicitMismatch, context)).isInstanceOf(TaskExecutionException.class);
        verify(sourceDeployRepository, never()).markRunning(any());
        verify(sourceDeployRepository, never()).createPending(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        org.mockito.Mockito.verifyNoInteractions(toolRunRepository, sshClientAdapter, credentialService);
    }

    @Test
    void retryReusesTheSameRowAndSuccessfulTaskCacheDoesNotTransferAgain() throws Exception {
        DeploymentFixture f = prepareDeployment();
        var r = f.run();
        var failed = copyRun(r, r.projectId(), r.taskId(), r.sourceRevisionId(), r.environmentId(), 3L, r.remotePath(), TaskStatus.FAILED);
        when(sourceDeployRepository.findByTaskId(r.taskId())).thenReturn(Optional.of(failed));
        when(sshClientAdapter.execute(any())).thenReturn(new SshCommandResult(0, "ok", "", Duration.ZERO));
        assertThat(executor().ensureDeployed(f.request(), context).id()).isEqualTo(r.id());
        verify(sourceDeployRepository).markRunning(r.id());
        verify(sourceDeployRepository, never()).createPending(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        var succeeded = copyRun(r, r.projectId(), r.taskId(), r.sourceRevisionId(), r.environmentId(), 3L, r.remotePath(), TaskStatus.SUCCEEDED);
        when(sourceDeployRepository.findByTaskId(r.taskId())).thenReturn(Optional.of(succeeded));
        assertThat(executor().ensureDeployed(f.request(), context)).isEqualTo(succeeded);
        verify(sshClientAdapter).execute(any());
    }

    @Test
    void successfulCrossTaskCacheIsAllowedButItsPathAndVersionAreStillChecked() throws Exception {
        DeploymentFixture f = prepareDeployment();
        var r = f.run();
        var good = copyRun(r, r.projectId(), UUID.randomUUID(), r.sourceRevisionId(), r.environmentId(), 3L, r.remotePath(), TaskStatus.SUCCEEDED);
        when(sourceDeployRepository.findLatestSucceeded(r.projectId(), r.sourceRevisionId(), r.environmentId(), 3L)).thenReturn(Optional.of(good));
        assertThat(executor().ensureDeployed(f.request(), context)).isEqualTo(good);
        var bad = copyRun(good, r.projectId(), good.taskId(), r.sourceRevisionId(), r.environmentId(), 4L, r.remotePath(), TaskStatus.SUCCEEDED);
        when(sourceDeployRepository.findLatestSucceeded(r.projectId(), r.sourceRevisionId(), r.environmentId(), 3L)).thenReturn(Optional.of(bad));
        assertThatThrownBy(() -> executor().ensureDeployed(f.request(), context)).isInstanceOf(TaskExecutionException.class);
        org.mockito.Mockito.verifyNoInteractions(toolRunRepository, sshClientAdapter);
    }

    @Test
    void explicitSuccessAndForceValidateAssociationWithoutChangingForceSemantics() throws Exception {
        DeploymentFixture f = prepareDeployment();
        var r = f.run();
        var good = copyRun(r, r.projectId(), UUID.randomUUID(), r.sourceRevisionId(), r.environmentId(), 3L, r.remotePath(), TaskStatus.SUCCEEDED);
        when(sourceDeployRepository.findById(good.id())).thenReturn(Optional.of(good));
        var explicit = new SourceDeployExecutionRequest(r.projectId(), r.taskId(), r.id(), r.sourceRevisionId(), r.environmentId(), 3L, null, false, 60);
        assertThat(executor().ensureDeployed(explicit, context)).isEqualTo(good);
        var forced = new SourceDeployExecutionRequest(r.projectId(), r.taskId(), r.id(), r.sourceRevisionId(), r.environmentId(), 3L, null, true, 60);
        var fresh = new SourceDeployRunView(UUID.randomUUID(), r.projectId(), r.taskId(), r.sourceRevisionId(), r.environmentId(), 3L,
                r.remotePath(), r.deployStrategy(), true, TaskStatus.PENDING, null, null, null, JSONB.valueOf("{}"), null, null, null);
        when(sourceDeployRepository.createPending(any(), eq(r.projectId()), eq(r.taskId()), eq(r.sourceRevisionId()), eq(r.environmentId()), eq(3L), eq(r.remotePath()), eq(true))).thenReturn(fresh);
        when(sshClientAdapter.execute(any())).thenReturn(new SshCommandResult(0, "ok", "", Duration.ZERO));
        assertThat(executor().ensureDeployed(forced, context).id()).isEqualTo(fresh.id());
        verify(sourceDeployRepository).markRunning(fresh.id());
        verify(sourceDeployRepository, never()).markRunning(good.id());
    }

    @Test
    void invalidExecutionTaskAndLostLeaseCannotMutateDeployment() throws Exception {
        DeploymentFixture f = prepareDeployment();
        when(context.task()).thenReturn(null, task(UUID.randomUUID(), f.request().projectId()), task(f.request().taskId(), UUID.randomUUID()));
        for (int i = 0; i < 3; i++)
            assertThatThrownBy(() -> executor().ensureDeployed(f.request(), context)).isInstanceOf(TaskExecutionException.class);
        when(context.task()).thenReturn(task(f.request().taskId(), f.request().projectId()));
        when(sourceDeployRepository.findByTaskId(f.request().taskId())).thenReturn(Optional.of(f.run()));
        org.mockito.Mockito.doThrow(new TaskCancelledException("lease lost")).when(context).publish(any());
        assertThatThrownBy(() -> executor().ensureDeployed(f.request(), context)).isInstanceOf(TaskCancelledException.class);
        verify(sourceDeployRepository, never()).markRunning(any());
        org.mockito.Mockito.verifyNoInteractions(toolRunRepository, sshClientAdapter);
    }

    @Test
    void rejectsTraversalRelativeRootsAndArbitraryRemotePaths() {
        UUID project = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        for (String root : java.util.List.of("relative", "C:/work", "/work/../target", "/work/./target", "/work//target", "/work\n/target")) {
            var environment = environment(project, root);
            var request = request(project, UUID.randomUUID(), source, environment.id(), 3L, null);
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(executor(), "effectiveRemotePath", request, environment, source))
                    .isInstanceOf(TaskExecutionException.class);
        }
        var environment = environment(project, "/workspace");
        var request = request(project, UUID.randomUUID(), source, environment.id(), 3L, "/workspace/other");
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(executor(), "effectiveRemotePath", request, environment, source))
                .isInstanceOf(TaskExecutionException.class).extracting("errorCode").isEqualTo("SOURCE_DEPLOY_PATH_MISMATCH");
    }

    @Test
    void rejectsRepositoryResourceIdMismatchBeforeTransfer() throws Exception {
        var fixture = prepareDeployment();
        var request = fixture.request();
        when(sourceRepository.findById(request.sourceRevisionId())).thenReturn(Optional.of(revision(request.projectId(), tempDir)));
        assertThatThrownBy(() -> executor().ensureDeployed(request, context)).isInstanceOf(TaskExecutionException.class);
        verifyNoInteractions(sshClientAdapter);
        var revision = new SourceRevisionView(request.sourceRevisionId(), request.projectId(), SourceRevisionType.ARCHIVE,
                "v1", "source.zip", null, null, null, null, tempDir.toString(), null, true, null, null);
        when(sourceRepository.findById(request.sourceRevisionId())).thenReturn(Optional.of(revision));
        when(environmentRepository.findById(request.environmentId())).thenReturn(Optional.of(environment(request.projectId(), "/workspace")));
        assertThatThrownBy(() -> executor().ensureDeployed(request, context)).isInstanceOf(TaskExecutionException.class);
        verifyNoInteractions(sshClientAdapter);
        verify(sourceDeployRepository, never()).markRunning(any());
    }

    private SourceDeployRunView copyRun(SourceDeployRunView original, UUID project, UUID task, UUID source,
            UUID environment, Long version, String path, TaskStatus status) {
        return new SourceDeployRunView(original.id(), project, task, source, environment, version, path,
                original.deployStrategy(), original.force(), status, null, null, null, JSONB.valueOf("{}"), null, null, null);
    }

    private SourceDeployExecutor executor() {
        return new SourceDeployExecutor(sourceDeployRepository, sourceRepository, environmentRepository,
                credentialService, sshClientAdapter, toolRunRepository);
    }

    private SourceDeployExecutionRequest request(UUID projectId, UUID taskId, UUID revisionId, UUID environmentId, long version, String remotePath) {
        return new SourceDeployExecutionRequest(projectId, taskId, null, revisionId, environmentId, version, remotePath, false, 120);
    }

    private SourceRevisionView revision(UUID projectId, Path workingCopy) {
        return new SourceRevisionView(UUID.randomUUID(), projectId, SourceRevisionType.ARCHIVE, "v1",
                "source.zip", null, null, null, null, workingCopy.toString(), null, true,
                UUID.randomUUID(), OffsetDateTime.now());
    }

    private EnvironmentView environment(UUID projectId, String workingDirectory) {
        return new EnvironmentView(UUID.randomUUID(), projectId, "env", EnvironmentRole.TARGET_RISCV, "riscv64",
                "127.0.0.1", 22, "root", UUID.randomUUID(), null, workingDirectory,
                EnvironmentStatus.AVAILABLE, true, null, null, UUID.randomUUID(), OffsetDateTime.now(), null);
    }

    private EnvironmentView environment(
            UUID id, UUID projectId, String workingDirectory, boolean enabled, UUID jumpHostId
    ) {
        return new EnvironmentView(id, projectId, "env", EnvironmentRole.TARGET_RISCV, "riscv64",
                "127.0.0.1", 22, "root", UUID.randomUUID(), jumpHostId, workingDirectory,
                EnvironmentStatus.AVAILABLE, enabled, null, null, UUID.randomUUID(), OffsetDateTime.now(), null);
    }

    private SourceDeployRunView run(UUID id, UUID projectId, UUID taskId, UUID revisionId, UUID environmentId, String remotePath, TaskStatus status) {
        return new SourceDeployRunView(id, projectId, taskId, revisionId, environmentId, 3L, remotePath,
                SourceDeployStrategy.TAR_OVER_SSH, false, status, null, null, null,
                JSONB.valueOf("{}"), null, null, OffsetDateTime.now());
    }

    private TaskView task(UUID id, UUID projectId) {
        return new TaskView(id, projectId, "BUILD_VALIDATION", TaskStatus.RUNNING, 0, "running",
                UUID.randomUUID(), OffsetDateTime.now(), null, OffsetDateTime.now(), null, null, null, null, 0);
    }

    private DeploymentFixture prepareDeployment() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        SourceRevisionView revision = revision(projectId, tempDir);
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/main.c"), "int main(void) { return 0; }\n");
        EnvironmentView environment = environment(projectId, "/workspace");
        SourceDeployExecutionRequest request = request(
                projectId, taskId, revision.id(), environment.id(), 3L, null);
        SourceDeployRunView pending = run(UUID.randomUUID(), projectId, taskId, revision.id(), environment.id(),
                "/workspace/rvport-sources/" + revision.id(), TaskStatus.PENDING);
        UUID toolRunId = UUID.randomUUID();
        lenient().when(context.task()).thenReturn(task(taskId, projectId));
        lenient().when(sourceDeployRepository.findLatestSucceeded(projectId, revision.id(), environment.id(), 3L))
                .thenReturn(Optional.empty());
        lenient().when(sourceRepository.findById(revision.id())).thenReturn(Optional.of(revision));
        lenient().when(environmentRepository.findById(environment.id())).thenReturn(Optional.of(environment));
        lenient().when(sourceDeployRepository.createPending(any(), eq(projectId), eq(taskId), eq(revision.id()),
                eq(environment.id()), eq(3L), any(), eq(false))).thenReturn(pending);
        lenient().when(toolRunRepository.create(eq(projectId), eq(taskId), any(), any(), any(), eq(TaskStatus.RUNNING), any()))
                .thenReturn(toolRunId);
        lenient().when(credentialService.sshRuntimeCredential(environment.credentialId(), projectId))
                .thenReturn(new CredentialService.SshRuntimeCredential(environment.credentialId(),
                        CredentialKind.SSH_KEY, "root", null, "key", null));
        lenient().when(sourceDeployRepository.findById(pending.id())).thenReturn(Optional.of(pending));
        return new DeploymentFixture(request, pending, environment, toolRunId);
    }

    private record DeploymentFixture(
            SourceDeployExecutionRequest request,
            SourceDeployRunView run,
            EnvironmentView environment,
            UUID toolRunId
    ) {
    }
}
