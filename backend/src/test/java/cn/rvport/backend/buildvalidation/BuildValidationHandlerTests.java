package cn.rvport.backend.buildvalidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.archive.ArchiveCandidateRegistrar;
import cn.rvport.backend.buildvalidation.bitcode.BitcodeArtifactService;
import cn.rvport.backend.buildvalidation.bitcode.BitcodeGenerationResult;
import cn.rvport.backend.buildvalidation.bitcode.BitcodeReasonCode;
import cn.rvport.backend.buildvalidation.bitcode.BitcodeStatus;
import cn.rvport.backend.artifact.StoredArtifactCreateCommand;
import cn.rvport.backend.artifact.StoredArtifactOwnerType;
import cn.rvport.backend.artifact.StoredArtifactService;
import cn.rvport.backend.artifact.StoredArtifactView;
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
import cn.rvport.backend.source.deploy.SourceDeployExecutionRequest;
import cn.rvport.backend.source.deploy.SourceDeployExecutor;
import cn.rvport.backend.source.deploy.SourceDeployRunView;
import cn.rvport.backend.source.deploy.SourceDeployStrategy;
import cn.rvport.backend.task.TaskExecutionException;
import cn.rvport.backend.task.TaskLogRepository;
import cn.rvport.backend.task.TaskLogResponse;
import cn.rvport.backend.task.TaskLogType;
import cn.rvport.backend.task.TaskScriptPayloadService;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.task.TaskView;
import cn.rvport.backend.task.ToolRunRepository;
import cn.rvport.backend.worker.TaskCancelledException;
import cn.rvport.backend.worker.TaskExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
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
class BuildValidationHandlerTests {

    @Mock
    private BuildValidationRepository buildValidationRepository;
    @Mock
    private SourceDeployExecutor sourceDeployExecutor;
    @Mock
    private EnvironmentRepository environmentRepository;
    @Mock
    private CredentialService credentialService;
    @Mock
    private TaskScriptPayloadService taskScriptPayloadService;
    @Mock
    private SshClientAdapter sshClientAdapter;
    @Mock
    private TaskLogRepository taskLogRepository;
    @Mock
    private ToolRunRepository toolRunRepository;
    @Mock
    private ArchiveCandidateRegistrar archiveCandidateRegistrar;
    @Mock
    private StoredArtifactService storedArtifactService;
    @Mock
    private BitcodeArtifactService bitcodeArtifactService;
    @Mock
    private TaskExecutionContext context;

    @org.junit.jupiter.api.BeforeEach
    void executeGuardedPublications() {
        org.mockito.Mockito.lenient().when(context.publish(any())).thenAnswer(invocation ->
                invocation.<java.util.function.Supplier<?>>getArgument(0).get());
    }

    @Test
    void executesBuildScriptInDeployedRemotePath() {
        Fixtures fixtures = fixtures(TaskStatus.SUCCEEDED);
        when(sshClientAdapter.execute(any())).thenAnswer(invocation -> {
            SshCommandRequest request = invocation.getArgument(0);
            if (request.streamOutput()) {
                assertThat(request.command()).contains("cat >").contains("cd \"$WORK_DIR\"").contains("bash -x \"$SCRIPT_PATH\"");
                assertThat(request.stdin()).isEqualTo("make all");
            }
            return new SshCommandResult(0, "", "", Duration.ofMillis(50));
        });
        TaskLogResponse combinedLog = log(fixtures.taskId, TaskLogType.COMBINED);
        TaskLogResponse stdoutLog = log(fixtures.taskId, TaskLogType.STDOUT);
        when(taskLogRepository.findLatest(fixtures.taskId, TaskLogType.COMBINED)).thenReturn(Optional.of(combinedLog));
        when(taskLogRepository.findLatest(fixtures.taskId, TaskLogType.STDOUT)).thenReturn(Optional.of(stdoutLog));

        BuildValidationHandler handler = handler();
        handler.handle(context);

        verify(buildValidationRepository).markRunning(fixtures.buildRunId);
        verify(buildValidationRepository).markFinished(eq(fixtures.buildRunId), eq(TaskStatus.SUCCEEDED), eq(0), eq(null), eq(combinedLog.id()), eq(stdoutLog.id()), eq(null));
        verify(toolRunRepository).finish(any(), eq(TaskStatus.SUCCEEDED), eq(0), any());
    }

    @Test
    void injectsEnvironmentVariablesIntoRemoteWrapperWithoutChangingScriptStdin() {
        Fixtures fixtures = fixtures(TaskStatus.SUCCEEDED, true, Map.of(
                "RVPORT_BUILD_FLAG", "value with spaces",
                "QUOTED", "a'b\"c",
                "EMPTY", ""
        ));
        when(sshClientAdapter.execute(any())).thenReturn(new SshCommandResult(0, "", "", Duration.ofMillis(50)));

        handler().handle(context);

        ArgumentCaptor<SshCommandRequest> requestCaptor = ArgumentCaptor.forClass(SshCommandRequest.class);
        verify(sshClientAdapter, org.mockito.Mockito.times(2)).execute(requestCaptor.capture());
        SshCommandRequest buildRequest = requestCaptor.getAllValues().stream()
                .filter(SshCommandRequest::streamOutput)
                .findFirst()
                .orElseThrow();
        assertThat(buildRequest.stdin()).isEqualTo("make all");
        assertThat(buildRequest.command())
                .contains("export RVPORT_BUILD_FLAG='value with spaces'")
                .contains("export QUOTED='a'\"'\"'b\"c'")
                .contains("export EMPTY=''");
        assertThat(fixtures.input().environmentVariables()).containsEntry("RVPORT_BUILD_FLAG", "value with spaces");
    }

    @Test
    void remoteWrapperCollectsCmakeCompilerFlagProbeObservationsBestEffort() {
        fixtures(TaskStatus.SUCCEEDED);
        when(sshClientAdapter.execute(any())).thenReturn(new SshCommandResult(0, "", "", Duration.ofMillis(50)));

        handler().handle(context);

        ArgumentCaptor<SshCommandRequest> requestCaptor = ArgumentCaptor.forClass(SshCommandRequest.class);
        verify(sshClientAdapter, org.mockito.Mockito.times(2)).execute(requestCaptor.capture());
        String buildCommand = requestCaptor.getAllValues().stream()
                .filter(SshCommandRequest::streamOutput)
                .findFirst()
                .orElseThrow()
                .command();
        assertThat(buildCommand)
                .contains("summarize_cmake_compiler_flag_probes")
                .contains("check_c_compiler_flag")
                .contains("check_cxx_compiler_flag")
                .contains("check_compiler_flag")
                .contains("RVPORT_OBSERVATION cmakeCompilerFlagProbe")
                .contains("strace -f -e execve -o .rvport-strace-probe.log true")
                .contains("RVPORT_OBSERVATION_WARNING straceUnavailable reason=probe_failed")
                .contains("RVPORT_OBSERVATION_WARNING cmakeCompilerFlagProbeUnresolved")
                .contains("else\n                    echo \"RVPORT_OBSERVATION_WARNING cmakeCompilerFlagProbePythonUnavailable\" >&2");
    }

    @Test
    void remoteWrapperCollectsDynamicLibraryMetadataForBuildProducedElf() {
        fixtures(TaskStatus.SUCCEEDED);
        when(sshClientAdapter.execute(any())).thenReturn(new SshCommandResult(0, "", "", Duration.ofMillis(50)));

        handler().handle(context);

        ArgumentCaptor<SshCommandRequest> requestCaptor = ArgumentCaptor.forClass(SshCommandRequest.class);
        verify(sshClientAdapter, org.mockito.Mockito.times(2)).execute(requestCaptor.capture());
        String buildCommand = requestCaptor.getAllValues().stream()
                .filter(SshCommandRequest::streamOutput)
                .findFirst()
                .orElseThrow()
                .command();
        assertThat(buildCommand)
                .contains("RVPORT_OBSERVATION resolvedNeededLibrary")
                .contains("resolve_needed_library \"$REL_PATH\" \"$FILE_PATH\" \"$NEEDED\"")
                .contains("""
                        comm -13 "$ELF_BEFORE_SORTED" "$ELF_AFTER_SORTED" | while IFS= read -r LINE; do
                                          echo "RVPORT_OBSERVATION buildProducedElf $LINE"
                                          REL_PATH="${LINE%%	*}"
                                          emit_elf_metadata "$REL_PATH"
                                        done
                        """);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=EnvironmentRole.class, names={"SOURCE_X86", "TARGET_RISCV"})
    void savesCompileDatabaseArtifactWhenRemoteFileIsValid(EnvironmentRole role) {
        Fixtures fixtures = fixtures(TaskStatus.SUCCEEDED, true, Map.of(), role);
        org.mockito.Mockito.lenient().when(bitcodeArtifactService.generate(any())).thenReturn(
                BitcodeGenerationResult.unavailable(BitcodeStatus.UNSUPPORTED, BitcodeReasonCode.COMPILE_DATABASE_UNAVAILABLE));
        String compileCommands = """
                [{"directory":"/workspace/source","command":"gcc -Iinclude -DPROJECT_FEATURE -c main.c","file":"main.c"}]
                """;
        when(sshClientAdapter.execute(any())).thenAnswer(invocation -> {
            SshCommandRequest request = invocation.getArgument(0);
            if (request.streamOutput()) {
                return new SshCommandResult(0, "build ok", "", Duration.ofMillis(50));
            }
            String encoded = Base64.getEncoder().encodeToString(compileCommands.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new SshCommandResult(0, """
                    RVPORT_COMPILE_DATABASE_STATUS=present
                    RVPORT_COMPILE_DATABASE_SIZE=96
                    RVPORT_COMPILE_DATABASE_BASE64_BEGIN
                    %s
                    RVPORT_COMPILE_DATABASE_BASE64_END
                    """.formatted(encoded), "", Duration.ofMillis(10));
        });
        StoredArtifactView artifact = storedArtifact(fixtures.projectId, fixtures.taskId, fixtures.buildRunId, compileCommands.length());
        when(storedArtifactService.store(any(), eq(context))).thenReturn(artifact);

        var sources=org.mockito.Mockito.mock(cn.rvport.backend.source.SourceRepository.class);
        var snapshots=org.mockito.Mockito.mock(cn.rvport.backend.report.review.ReportSourceSnapshotService.class);
        var revision=new cn.rvport.backend.source.SourceRevisionView(fixtures.input.sourceRevisionId(),fixtures.projectId,
                null,null,null,null,null,null,null,null,null,true,null,null);
        when(sources.findById(revision.id())).thenReturn(Optional.of(revision));
        when(snapshots.prepare(revision)).thenReturn(new cn.rvport.backend.report.review.ReportSourceSnapshotService.Manifest(
                revision.id(),"a".repeat(64),java.util.List.of(),null));
        var handler=handler();
        org.springframework.test.util.ReflectionTestUtils.setField(handler,"analysisSources",sources);
        org.springframework.test.util.ReflectionTestUtils.setField(handler,"analysisSnapshots",snapshots);
        handler.handle(context);

        ArgumentCaptor<StoredArtifactCreateCommand> artifactCaptor = ArgumentCaptor.forClass(StoredArtifactCreateCommand.class);
        verify(storedArtifactService).store(artifactCaptor.capture(), eq(context));
        assertThat(artifactCaptor.getValue().ownerType()).isEqualTo(StoredArtifactOwnerType.BUILD_VALIDATION);
        assertThat(artifactCaptor.getValue().ownerId()).isEqualTo(fixtures.buildRunId);
        assertThat(artifactCaptor.getValue().artifactType()).isEqualTo(BuildValidationArtifacts.COMPILE_COMMANDS_JSON);
        assertThat(artifactCaptor.getValue().summary().get("sourceDigest")).isEqualTo("a".repeat(64));
        assertThat(artifactCaptor.getValue().summary().get("remoteSourceRoot")).isEqualTo("/workspace/source");
        if (role == EnvironmentRole.SOURCE_X86) assertThat(artifactCaptor.getValue().summary().get("sourceArch")).isEqualTo("x86");
        else assertThat(artifactCaptor.getValue().summary()).doesNotContainKey("sourceArch");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> factsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(buildValidationRepository).updateBuildFacts(eq(fixtures.buildRunId), factsCaptor.capture());
        Map<?, ?> compileDatabase = (Map<?, ?>) factsCaptor.getValue().get("compileDatabase");
        assertThat(compileDatabase.get("available")).isEqualTo(true);
        assertThat(compileDatabase.get("storedArtifactId")).isEqualTo(artifact.id());
        assertThat(compileDatabase.get("commandCount")).isEqualTo(1);
    }

    @Test
    void keepsBuildSucceededWhenCompileDatabaseIsInvalidJson() {
        Fixtures fixtures = fixtures(TaskStatus.SUCCEEDED);
        String invalidJson = "{\"command\":\"gcc\"}";
        when(sshClientAdapter.execute(any())).thenAnswer(invocation -> {
            SshCommandRequest request = invocation.getArgument(0);
            if (request.streamOutput()) {
                return new SshCommandResult(0, "build ok", "", Duration.ofMillis(50));
            }
            return new SshCommandResult(0, """
                    RVPORT_COMPILE_DATABASE_STATUS=present
                    RVPORT_COMPILE_DATABASE_SIZE=17
                    RVPORT_COMPILE_DATABASE_BASE64_BEGIN
                    %s
                    RVPORT_COMPILE_DATABASE_BASE64_END
                    """.formatted(Base64.getEncoder().encodeToString(invalidJson.getBytes(java.nio.charset.StandardCharsets.UTF_8))), "", Duration.ofMillis(10));
        });

        handler().handle(context);

        verify(storedArtifactService, org.mockito.Mockito.never()).store(any(), eq(context));
        verify(buildValidationRepository).markFinished(eq(fixtures.buildRunId), eq(TaskStatus.SUCCEEDED), eq(0), eq(null), any(), any(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> factsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(buildValidationRepository).updateBuildFacts(eq(fixtures.buildRunId), factsCaptor.capture());
        Map<?, ?> compileDatabase = (Map<?, ?>) factsCaptor.getValue().get("compileDatabase");
        assertThat(compileDatabase.get("available")).isEqualTo(false);
        assertThat(compileDatabase.get("mode")).isEqualTo("FALLBACK");
        assertThat(compileDatabase.get("reason")).isEqualTo("JSON_TOP_LEVEL_NOT_ARRAY");
        verify(context).log(eq(TaskLogType.STDERR), org.mockito.ArgumentMatchers.contains("compileDatabaseArtifactUnavailable"));
    }

    @Test
    void keepsBuildSucceededWhenCompileDatabaseIsMissing() {
        Fixtures fixtures = fixtures(TaskStatus.SUCCEEDED);
        when(sshClientAdapter.execute(any())).thenAnswer(invocation -> {
            SshCommandRequest request = invocation.getArgument(0);
            if (request.streamOutput()) {
                return new SshCommandResult(0, "build ok", "", Duration.ofMillis(50));
            }
            return new SshCommandResult(0, "RVPORT_COMPILE_DATABASE_STATUS=missing\n", "", Duration.ofMillis(10));
        });

        handler().handle(context);

        verify(storedArtifactService, org.mockito.Mockito.never()).store(any(), eq(context));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> factsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(buildValidationRepository).updateBuildFacts(eq(fixtures.buildRunId), factsCaptor.capture());
        Map<?, ?> compileDatabase = (Map<?, ?>) factsCaptor.getValue().get("compileDatabase");
        assertThat(compileDatabase.get("available")).isEqualTo(false);
        assertThat(compileDatabase.get("mode")).isEqualTo("NOT_AVAILABLE");
        assertThat(compileDatabase.get("reason")).isEqualTo("NOT_AVAILABLE");
    }

    @Test
    void keepsBuildSucceededWhenCompileDatabaseIsTooLarge() {
        Fixtures fixtures = fixtures(TaskStatus.SUCCEEDED);
        when(sshClientAdapter.execute(any())).thenAnswer(invocation -> {
            SshCommandRequest request = invocation.getArgument(0);
            if (request.streamOutput()) {
                return new SshCommandResult(0, "build ok", "", Duration.ofMillis(50));
            }
            return new SshCommandResult(0, """
                    RVPORT_COMPILE_DATABASE_STATUS=too_large
                    RVPORT_COMPILE_DATABASE_SIZE=999999999
                    """, "", Duration.ofMillis(10));
        });

        handler().handle(context);

        verify(storedArtifactService, org.mockito.Mockito.never()).store(any(), eq(context));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> factsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(buildValidationRepository).updateBuildFacts(eq(fixtures.buildRunId), factsCaptor.capture());
        Map<?, ?> compileDatabase = (Map<?, ?>) factsCaptor.getValue().get("compileDatabase");
        assertThat(compileDatabase.get("available")).isEqualTo(false);
        assertThat(compileDatabase.get("mode")).isEqualTo("NOT_AVAILABLE");
        assertThat(compileDatabase.get("reason")).isEqualTo("SIZE_LIMIT_EXCEEDED");
    }

    @Test
    void keepsBuildSucceededWhenCompileDatabaseArtifactStoreFails() {
        Fixtures fixtures = fixtures(TaskStatus.SUCCEEDED);
        String compileCommands = """
                [{"directory":"/workspace/source","command":"gcc -Iinclude -c main.c","file":"main.c"}]
                """;
        when(sshClientAdapter.execute(any())).thenAnswer(invocation -> {
            SshCommandRequest request = invocation.getArgument(0);
            if (request.streamOutput()) {
                return new SshCommandResult(0, "build ok", "", Duration.ofMillis(50));
            }
            return new SshCommandResult(0, """
                    RVPORT_COMPILE_DATABASE_STATUS=present
                    RVPORT_COMPILE_DATABASE_SIZE=88
                    RVPORT_COMPILE_DATABASE_BASE64_BEGIN
                    %s
                    RVPORT_COMPILE_DATABASE_BASE64_END
                    """.formatted(Base64.getEncoder().encodeToString(compileCommands.getBytes(java.nio.charset.StandardCharsets.UTF_8))), "", Duration.ofMillis(10));
        });
        when(storedArtifactService.store(any(), eq(context))).thenThrow(new IllegalStateException("object storage down"));

        handler().handle(context);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> factsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(buildValidationRepository).updateBuildFacts(eq(fixtures.buildRunId), factsCaptor.capture());
        Map<?, ?> compileDatabase = (Map<?, ?>) factsCaptor.getValue().get("compileDatabase");
        assertThat(compileDatabase.get("available")).isEqualTo(false);
        assertThat(compileDatabase.get("mode")).isEqualTo("FALLBACK");
        assertThat(compileDatabase.get("reason")).isEqualTo("COLLECTION_FAILED");
        verify(context).log(eq(TaskLogType.STDERR), org.mockito.ArgumentMatchers.contains("compileDatabaseArtifactUnavailable"));
    }

    @Test
    void marksBuildFailedWhenRemoteExitCodeIsNonZero() {
        Fixtures fixtures = fixtures(TaskStatus.SUCCEEDED);
        when(sshClientAdapter.execute(any())).thenReturn(new SshCommandResult(2, "", "compile error", Duration.ofMillis(20)));

        assertThatThrownBy(() -> handler().handle(context))
                .isInstanceOf(TaskExecutionException.class)
                .extracting("errorCode")
                .isEqualTo("TASK_REMOTE_COMMAND_FAILED");
        verify(buildValidationRepository).markFinished(eq(fixtures.buildRunId), eq(TaskStatus.FAILED), eq(2), eq("BUILD_COMMAND_FAILED"), any(), any(), any());
    }

    @Test
    void marksBuildFailedWhenSshFails() {
        Fixtures fixtures = fixtures(TaskStatus.SUCCEEDED);
        when(sshClientAdapter.execute(any()))
                .thenThrow(new SshRuntimeException("SSH_AUTH_FAILED", "auth failed", null));

        assertThatThrownBy(() -> handler().handle(context))
                .isInstanceOf(TaskExecutionException.class)
                .extracting("errorCode")
                .isEqualTo("SSH_AUTH_FAILED");
        verify(buildValidationRepository).markFinished(eq(fixtures.buildRunId), eq(TaskStatus.FAILED), eq(null), eq("SSH_CONNECTION_FAILED"), any(), any(), any());
        verify(toolRunRepository).finish(any(), eq(TaskStatus.FAILED), eq(null), any());
    }

    @Test
    void marksBuildTimeoutWhenSshCommandTimesOut() {
        Fixtures fixtures = fixtures(TaskStatus.SUCCEEDED);
        when(sshClientAdapter.execute(any()))
                .thenThrow(new SshRuntimeException("command timeout after 10 seconds"));

        assertThatThrownBy(() -> handler().handle(context))
                .isInstanceOf(TaskExecutionException.class)
                .extracting("errorCode")
                .isEqualTo("TASK_REMOTE_COMMAND_FAILED");
        verify(buildValidationRepository).markFinished(eq(fixtures.buildRunId), eq(TaskStatus.TIMEOUT), eq(null), eq("BUILD_TIMEOUT"), any(), any(), any());
        verify(toolRunRepository).finish(any(), eq(TaskStatus.TIMEOUT), eq(null), any());
    }

    @Test
    void remoteCommandGuardsMissingWorkingDirectoryAndMarksNonZeroExit() {
        Fixtures fixtures = fixtures(TaskStatus.SUCCEEDED);
        when(sshClientAdapter.execute(any())).thenReturn(new SshCommandResult(127, "", "BUILD_WORKING_DIRECTORY_NOT_FOUND", Duration.ofMillis(20)));

        assertThatThrownBy(() -> handler().handle(context))
                .isInstanceOf(TaskExecutionException.class)
                .extracting("errorCode")
                .isEqualTo("TASK_REMOTE_COMMAND_FAILED");
        ArgumentCaptor<SshCommandRequest> requestCaptor = ArgumentCaptor.forClass(SshCommandRequest.class);
        verify(sshClientAdapter).execute(requestCaptor.capture());
        assertThat(requestCaptor.getValue().command()).contains("BUILD_WORKING_DIRECTORY_NOT_FOUND");
        verify(buildValidationRepository).markFinished(eq(fixtures.buildRunId), eq(TaskStatus.FAILED), eq(127), eq("BUILD_COMMAND_FAILED"), any(), any(), any());
    }

    @Test
    void marksBuildFailedWhenInternalSourceDeployFails() {
        Fixtures fixtures = fixtures(TaskStatus.FAILED);

        assertThatThrownBy(() -> handler().handle(context))
                .isInstanceOf(TaskExecutionException.class)
                .extracting("errorCode")
                .isEqualTo("SOURCE_DEPLOY_REMOTE_TAR_REQUIRED");
        verify(buildValidationRepository).markFinished(eq(fixtures.buildRunId), eq(TaskStatus.FAILED), eq(null), eq("SOURCE_DEPLOYMENT_FAILED"), any(), any(), any());
    }

    @Test
    void marksBuildCancelledWhenContextRequestsCancellation() {
        Fixtures fixtures = fixtures(TaskStatus.SUCCEEDED, false);
        org.mockito.Mockito.doThrow(new TaskCancelledException("cancelled")).when(context).throwIfCancellationRequested();

        assertThatThrownBy(() -> handler().handle(context))
                .isInstanceOf(TaskCancelledException.class);
        verify(buildValidationRepository).markFinished(eq(fixtures.buildRunId), eq(TaskStatus.CANCELLED), eq(null), eq("BUILD_CANCELLED"), any(), any(), any());
    }

    @Test
    void keepsSourceX86BuildSucceededWhenBitcodeGenerationFails() {
        Fixtures fixtures = fixtures(TaskStatus.SUCCEEDED, true, Map.of(), EnvironmentRole.SOURCE_X86);
        String compileCommands = "[{\"directory\":\"/workspace/source\",\"command\":\"g++ -c main.cpp\",\"file\":\"main.cpp\"}]";
        when(sshClientAdapter.execute(any())).thenAnswer(invocation -> {
            SshCommandRequest request = invocation.getArgument(0);
            if (request.streamOutput()) {
                return new SshCommandResult(0, "", "", Duration.ofMillis(10));
            }
            return new SshCommandResult(0, """
                    RVPORT_COMPILE_DATABASE_STATUS=present
                    RVPORT_COMPILE_DATABASE_BASE64_BEGIN
                    %s
                    RVPORT_COMPILE_DATABASE_BASE64_END
                    """.formatted(Base64.getEncoder().encodeToString(
                    compileCommands.getBytes(java.nio.charset.StandardCharsets.UTF_8))), "", Duration.ofMillis(10));
        });
        when(storedArtifactService.store(any(), eq(context))).thenReturn(
                storedArtifact(fixtures.projectId, fixtures.taskId, fixtures.buildRunId, compileCommands.length()));
        when(bitcodeArtifactService.generate(any())).thenReturn(BitcodeGenerationResult.unavailable(
                BitcodeStatus.FAILED, BitcodeReasonCode.TOOLCHAIN_BUNDLE_UNAVAILABLE));

        handler().handle(context);

        verify(buildValidationRepository).markFinished(eq(fixtures.buildRunId), eq(TaskStatus.SUCCEEDED), eq(0),
                eq(null), any(), any(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> factsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(buildValidationRepository).updateBuildFacts(eq(fixtures.buildRunId), factsCaptor.capture());
        Map<?, ?> bitcode = (Map<?, ?>) factsCaptor.getValue().get("bitcode");
        assertThat(bitcode.get("status")).isEqualTo("FAILED");
        assertThat(bitcode.get("reasonCode")).isEqualTo("TOOLCHAIN_BUNDLE_UNAVAILABLE");
    }

    private BuildValidationHandler handler() {
        return new BuildValidationHandler(buildValidationRepository, sourceDeployExecutor, environmentRepository,
                credentialService, taskScriptPayloadService, sshClientAdapter, taskLogRepository, toolRunRepository,
                archiveCandidateRegistrar, storedArtifactService, bitcodeArtifactService, new ObjectMapper());
    }

    private Fixtures fixtures(TaskStatus deploymentStatus) {
        return fixtures(deploymentStatus, true);
    }

    private Fixtures fixtures(TaskStatus deploymentStatus, boolean stubExecutionPath) {
        return fixtures(deploymentStatus, stubExecutionPath, Map.of());
    }

    private Fixtures fixtures(TaskStatus deploymentStatus, boolean stubExecutionPath, Map<String, String> environmentVariables) {
        return fixtures(deploymentStatus, stubExecutionPath, environmentVariables, EnvironmentRole.TARGET_RISCV);
    }

    private Fixtures fixtures(TaskStatus deploymentStatus, boolean stubExecutionPath,
                              Map<String, String> environmentVariables, EnvironmentRole role) {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID buildRunId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        UUID payloadId = UUID.randomUUID();
        BuildValidationTaskInput input = new BuildValidationTaskInput(projectId, buildRunId, deploymentId,
                revisionId, environmentId, null, payloadId, "hash", 8, 120, environmentVariables);
        BuildValidationRunView buildRun = new BuildValidationRunView(buildRunId, projectId, taskId, revisionId,
                environmentId, deploymentId, payloadId, "hash", "make # sha256:hash", "/workspace/source",
                120, null, TaskStatus.RUNNING, null, null, null, null, null, null, OffsetDateTime.now(), Map.of());
        SourceDeployRunView deployment = new SourceDeployRunView(deploymentId, projectId, UUID.randomUUID(), revisionId,
                environmentId, "/workspace/source", SourceDeployStrategy.TAR_OVER_SSH, false, deploymentStatus,
                1, 10L, 0, JSONB.valueOf("{}"), OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now());
        EnvironmentView environment = new EnvironmentView(environmentId, projectId, "target", role,
                role == EnvironmentRole.SOURCE_X86 ? "x86_64" : "riscv64", "127.0.0.1", 22, "root", UUID.randomUUID(), null, "/workspace",
                EnvironmentStatus.AVAILABLE, true, null, null, UUID.randomUUID(), OffsetDateTime.now(), null);
        when(context.input(BuildValidationTaskInput.class)).thenReturn(input);
        when(context.task()).thenReturn(new TaskView(taskId, projectId, "BUILD_VALIDATION", TaskStatus.RUNNING,
                0, "running", UUID.randomUUID(), OffsetDateTime.now(), null, OffsetDateTime.now(),
                null, null, null, null, 0));
        when(buildValidationRepository.findById(buildRunId)).thenReturn(Optional.of(buildRun));
        when(toolRunRepository.create(eq(projectId), eq(taskId), any(), any(), any(), eq(TaskStatus.RUNNING), any()))
                .thenReturn(UUID.randomUUID());
        if (!stubExecutionPath) {
            return new Fixtures(projectId, taskId, buildRunId, input, buildRun);
        }
        if (deploymentStatus == TaskStatus.SUCCEEDED) {
            when(sourceDeployExecutor.ensureDeployed(any(SourceDeployExecutionRequest.class), eq(context))).thenReturn(deployment);
        } else {
            when(sourceDeployExecutor.ensureDeployed(any(SourceDeployExecutionRequest.class), eq(context)))
                    .thenThrow(new TaskExecutionException("SOURCE_DEPLOY_REMOTE_TAR_REQUIRED", "源码部署远端命令执行失败", null));
        }
        if (deploymentStatus == TaskStatus.SUCCEEDED) {
            when(environmentRepository.findById(environmentId)).thenReturn(Optional.of(environment));
            when(taskScriptPayloadService.requireScript(payloadId, projectId, taskId, "hash")).thenReturn("make all");
            when(credentialService.sshRuntimeCredential(environment.credentialId(), projectId))
                    .thenReturn(new CredentialService.SshRuntimeCredential(environment.credentialId(), CredentialKind.SSH_KEY, "root", null, "key", null));
        }
        return new Fixtures(projectId, taskId, buildRunId, input, buildRun);
    }

    private TaskLogResponse log(UUID taskId, TaskLogType type) {
        return new TaskLogResponse(UUID.randomUUID(), taskId, UUID.randomUUID(), type, 10L, 1L, "hash", "ok", OffsetDateTime.now());
    }

    private StoredArtifactView storedArtifact(UUID projectId, UUID taskId, UUID ownerId, long sizeBytes) {
        return new StoredArtifactView(
                UUID.randomUUID(),
                projectId,
                taskId,
                StoredArtifactOwnerType.BUILD_VALIDATION,
                ownerId,
                BuildValidationArtifacts.COMPILE_COMMANDS_JSON,
                "rvport-artifacts",
                "projects/test/compile_commands.json",
                "application/json",
                sizeBytes,
                "a".repeat(64),
                Map.of("commandCount", 1),
                OffsetDateTime.now()
        );
    }

    private record Fixtures(
            UUID projectId,
            UUID taskId,
            UUID buildRunId,
            BuildValidationTaskInput input,
            BuildValidationRunView run
    ) {
    }
}
