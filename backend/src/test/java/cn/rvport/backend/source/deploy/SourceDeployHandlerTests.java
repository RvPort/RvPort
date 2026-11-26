package cn.rvport.backend.source.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
import cn.rvport.backend.source.SourceRepository;
import cn.rvport.backend.source.SourceRevisionType;
import cn.rvport.backend.source.SourceRevisionView;
import cn.rvport.backend.task.TaskExecutionException;
import cn.rvport.backend.task.RvPortTaskTypes;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.task.TaskView;
import cn.rvport.backend.task.ToolRunRepository;
import cn.rvport.backend.worker.TaskExecutionContext;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.jooq.JSONB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SourceDeployHandlerTests {

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
    void deploysSourceArchiveOverSshStdin() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        SourceRevisionView revision = revision(projectId);
        EnvironmentView environment = environment(projectId, null);
        SourceDeployRunView run = run(deploymentId, projectId, taskId, revision.id(), environment.id(), "/workspace/rvport-sources/" + revision.id());
        when(context.input(SourceDeployTaskInput.class)).thenReturn(new SourceDeployTaskInput(projectId, deploymentId, revision.id(), environment.id(), null, false, 120));
        when(context.task()).thenReturn(task(taskId, projectId));
        when(sourceDeployRepository.findById(deploymentId)).thenReturn(Optional.of(run));
        when(sourceRepository.findById(revision.id())).thenReturn(Optional.of(revision));
        when(environmentRepository.findById(environment.id())).thenReturn(Optional.of(environment));
        when(credentialService.sshRuntimeCredential(environment.credentialId(), projectId))
                .thenReturn(new CredentialService.SshRuntimeCredential(environment.credentialId(), CredentialKind.SSH_KEY, "root", null, "key", null));
        when(toolRunRepository.create(eq(projectId), eq(taskId), any(), any(), any(), eq(TaskStatus.RUNNING), any()))
                .thenReturn(UUID.randomUUID());
        when(sshClientAdapter.execute(any())).thenAnswer(invocation -> {
            SshCommandRequest request = invocation.getArgument(0);
            byte[] bytes = request.stdinStream().readAllBytes();
            try (TarArchiveInputStream tar = new TarArchiveInputStream(new GzipCompressorInputStream(new ByteArrayInputStream(bytes)))) {
                assertThat(tar.getNextEntry().getName()).isEqualTo("main.c");
            }
            return new SshCommandResult(0, "SOURCE_DEPLOY=ok\n", "", Duration.ofMillis(25));
        });

        SourceDeployHandler handler = handler();
        assertThat(handler.taskType()).isEqualTo(RvPortTaskTypes.SOURCE_DEPLOY);
        assertThat(handler.handle(context).message()).isEqualTo("源码部署成功");

        ArgumentCaptor<Map<String, ?>> summaryCaptor = ArgumentCaptor.forClass(Map.class);
        verify(sourceDeployRepository).markFinished(eq(deploymentId), eq(TaskStatus.SUCCEEDED), eq(1), any(), eq(0), summaryCaptor.capture());
        assertThat(summaryCaptor.getValue().get("deployStrategy")).isEqualTo("TAR_OVER_SSH");
        verify(sourceDeployRepository).markRunning(deploymentId);
    }

    @Test
    void failsWhenRemoteTarIsMissing() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        SourceRevisionView revision = revision(projectId);
        EnvironmentView environment = environment(projectId, null);
        SourceDeployRunView run = run(deploymentId, projectId, taskId, revision.id(), environment.id(), "/workspace/rvport-sources/" + revision.id());
        when(context.input(SourceDeployTaskInput.class)).thenReturn(new SourceDeployTaskInput(projectId, deploymentId, revision.id(), environment.id(), null, false, 120));
        when(context.task()).thenReturn(task(taskId, projectId));
        when(sourceDeployRepository.findById(deploymentId)).thenReturn(Optional.of(run));
        when(sourceRepository.findById(revision.id())).thenReturn(Optional.of(revision));
        when(environmentRepository.findById(environment.id())).thenReturn(Optional.of(environment));
        when(credentialService.sshRuntimeCredential(environment.credentialId(), projectId))
                .thenReturn(new CredentialService.SshRuntimeCredential(environment.credentialId(), CredentialKind.SSH_PASSWORD, "root", "pwd", null, null));
        when(toolRunRepository.create(eq(projectId), eq(taskId), any(), any(), any(), eq(TaskStatus.RUNNING), any()))
                .thenReturn(UUID.randomUUID());
        when(sshClientAdapter.execute(any())).thenReturn(new SshCommandResult(127, "", "SOURCE_DEPLOY_REMOTE_TAR_REQUIRED", Duration.ofMillis(10)));

        assertThatThrownBy(() -> handler().handle(context))
                .isInstanceOf(TaskExecutionException.class)
                .extracting("errorCode")
                .isEqualTo("SOURCE_DEPLOY_REMOTE_TAR_REQUIRED");
        verify(sourceDeployRepository).markFinished(eq(deploymentId), eq(TaskStatus.FAILED), eq(1), any(), eq(127), any());
    }

    private SourceDeployHandler handler() {
        return new SourceDeployHandler(new SourceDeployExecutor(sourceDeployRepository, sourceRepository, environmentRepository,
                credentialService, sshClientAdapter, toolRunRepository));
    }

    private SourceRevisionView revision(UUID projectId) throws Exception {
        Files.writeString(tempDir.resolve("main.c"), "int main(){return 0;}");
        return new SourceRevisionView(UUID.randomUUID(), projectId, SourceRevisionType.ARCHIVE, "v1",
                "source.zip", null, null, null, null, tempDir.toString(), null, true,
                UUID.randomUUID(), OffsetDateTime.now());
    }

    private EnvironmentView environment(UUID projectId, UUID jumpHostId) {
        return new EnvironmentView(UUID.randomUUID(), projectId, "env", EnvironmentRole.TARGET_RISCV, "riscv64",
                "127.0.0.1", 22, "root", UUID.randomUUID(), jumpHostId, "/workspace",
                EnvironmentStatus.AVAILABLE, true, null, null, UUID.randomUUID(), OffsetDateTime.now(), null);
    }

    private SourceDeployRunView run(UUID id, UUID projectId, UUID taskId, UUID revisionId, UUID environmentId, String remotePath) {
        return new SourceDeployRunView(id, projectId, taskId, revisionId, environmentId, 0L, remotePath,
                SourceDeployStrategy.TAR_OVER_SSH, false, TaskStatus.RUNNING, null, null, null,
                JSONB.valueOf("{}"), null, null, OffsetDateTime.now());
    }

    private TaskView task(UUID id, UUID projectId) {
        return new TaskView(id, projectId, "SOURCE_DEPLOY", TaskStatus.RUNNING, 0, "running",
                UUID.randomUUID(), OffsetDateTime.now(), null, OffsetDateTime.now(), null, null, null, null, 0);
    }
}
