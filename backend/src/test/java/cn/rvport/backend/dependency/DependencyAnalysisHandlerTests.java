package cn.rvport.backend.dependency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.archive.ArchiveCandidateRegistrar;
import cn.rvport.backend.artifact.StoredArtifactCreateCommand;
import cn.rvport.backend.artifact.StoredArtifactOwnerType;
import cn.rvport.backend.artifact.StoredArtifactService;
import cn.rvport.backend.artifact.StoredArtifactView;
import cn.rvport.backend.artifact.StoredArtifactResource;
import cn.rvport.backend.buildvalidation.BuildValidationRepository;
import cn.rvport.backend.buildvalidation.BuildValidationRunView;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.environment.runtime.EnvironmentCollectionResult;
import cn.rvport.backend.environment.runtime.EnvironmentTaskInput;
import cn.rvport.backend.environment.runtime.SshRuntimeService;
import cn.rvport.backend.task.TaskErrorCode;
import cn.rvport.backend.task.TaskExecutionException;
import cn.rvport.backend.task.TaskLogRepository;
import cn.rvport.backend.task.TaskLogService;
import cn.rvport.backend.task.TaskLogType;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.task.TaskView;
import cn.rvport.backend.task.ToolRunRepository;
import cn.rvport.backend.worker.TaskExecutionContext;
import cn.rvport.backend.worker.TaskCancelledException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DependencyAnalysisHandlerTests {

    @Mock
    private DependencyAnalysisRepository repository;
    @Mock
    private BuildValidationRepository buildValidationRepository;
    @Mock
    private TaskLogRepository taskLogRepository;
    @Mock
    private TaskLogService taskLogService;
    @Mock
    private ToolRunRepository toolRunRepository;
    @Mock
    private SshRuntimeService sshRuntimeService;
    @Mock
    private DependencyFactExtractor extractor;
    @Mock
    private StoredArtifactService storedArtifactService;
    @Mock
    private ArchiveCandidateRegistrar archiveCandidateRegistrar;
    @Mock
    private TaskExecutionContext context;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @org.junit.jupiter.api.BeforeEach
    void publicationsExecuteInsideTestContext() {
        org.mockito.Mockito.lenient().when(context.publish(any())).thenAnswer(invocation ->
                invocation.<java.util.function.Supplier<?>>getArgument(0).get());
    }

    @Test
    void usesFreshSourceAndTargetSnapshotsForDependencyMatching() {
        Fixtures fixtures = fixtures();
        DependencyAnalysisResultItem item = item(fixtures);
        when(extractor.extract(eq(fixtures.run), eq(fixtures.buildRun), any(), eq(fixtures.sourceComponents), eq(fixtures.targetComponents), any()))
                .thenReturn(List.of(item));
        ArgumentCaptor<Map<String, Object>> jsonCaptor = stubArtifacts(fixtures);

        handler().handle(context);

        verify(repository, never()).findLatestSucceededSnapshotId(any(), any());
        verify(repository).listTargetComponents(fixtures.sourceSnapshotId);
        verify(repository).listTargetComponents(fixtures.targetSnapshotId);
        verify(repository).replaceResultItems(fixtures.run.id(), List.of(item));
        Map<String, Object> json = jsonCaptor.getValue();
        assertThat(json)
                .containsEntry("environmentRefresh", true)
                .containsEntry("sourceSnapshotId", fixtures.sourceSnapshotId)
                .containsEntry("targetSnapshotId", fixtures.targetSnapshotId)
                .containsEntry("sourceSnapshotCollectedAt", fixtures.sourceCollectedAt)
                .containsEntry("targetSnapshotCollectedAt", fixtures.targetCollectedAt);
        assertThat(json.get("environmentRefreshSummary")).asString().contains("enabled=true");
        verify(repository).markFinished(eq(fixtures.run.id()), eq(TaskStatus.SUCCEEDED), any(), eq(List.of()));
    }

    @Test
    void failsWhenSourceRefreshFailsAndDoesNotUseOldSnapshot() {
        Fixtures fixtures = fixtures(false, true);
        when(sshRuntimeService.collectEnvironment(any(), any()))
                .thenThrow(new TaskExecutionException("SSH_AUTH_FAILED", "环境采集 SSH 执行失败", null));

        assertThatThrownBy(() -> handler().handle(context))
                .isInstanceOf(TaskExecutionException.class)
                .extracting("errorCode")
                .isEqualTo("SSH_AUTH_FAILED");

        verify(repository, never()).findLatestSucceededSnapshotId(any(), any());
        verify(repository, never()).listTargetComponents(any());
        verify(repository).markFinished(eq(fixtures.run.id()), eq(TaskStatus.FAILED), any(), eq(List.of()));
    }

    @Test
    void failsWhenTargetRefreshFailsAndDoesNotUseOldTargetSnapshot() {
        Fixtures fixtures = fixtures(true, false);
        when(sshRuntimeService.collectEnvironment(any(), any()))
                .thenReturn(new EnvironmentCollectionResult(fixtures.sourceSnapshotId, UUID.randomUUID(), 0, Map.of()))
                .thenThrow(new TaskExecutionException("TASK_REMOTE_COMMAND_FAILED", "环境采集失败", null));

        assertThatThrownBy(() -> handler().handle(context))
                .isInstanceOf(TaskExecutionException.class)
                .extracting("errorCode")
                .isEqualTo("TASK_REMOTE_COMMAND_FAILED");

        verify(repository, never()).findLatestSucceededSnapshotId(any(), any());
        verify(repository, never()).listTargetComponents(any());
        verify(repository).markFinished(eq(fixtures.run.id()), eq(TaskStatus.FAILED), any(), eq(List.of()));
    }

    @Test
    void failsRunWhenResultItemsCannotBeStored() {
        Fixtures fixtures = fixtures();
        DependencyAnalysisResultItem item = item(fixtures);
        when(extractor.extract(eq(fixtures.run), eq(fixtures.buildRun), any(), eq(fixtures.sourceComponents), eq(fixtures.targetComponents), any()))
                .thenReturn(List.of(item));
        org.mockito.Mockito.doThrow(new IllegalStateException("write failed")).when(repository).replaceResultItems(fixtures.run.id(), List.of(item));

        assertThatThrownBy(() -> handler().handle(context))
                .isInstanceOf(TaskExecutionException.class)
                .extracting("errorCode")
                .isEqualTo(TaskErrorCode.HANDLER_FAILED);

        verify(repository).replaceResultItems(fixtures.run.id(), List.of(item));
        verify(storedArtifactService, never()).storeJson(any(), any(), any(), any(), any(), any(), any(), any());
        verify(repository).markFinished(eq(fixtures.run.id()), eq(TaskStatus.FAILED), any(), eq(List.of()));
    }

    @Test
    void createsEnvironmentCollectInputWithDefaultScopesAndLongTimeout() {
        Fixtures fixtures = fixtures();
        stubArtifacts(fixtures);
        when(extractor.extract(eq(fixtures.run), eq(fixtures.buildRun), any(), any(), any(), any())).thenReturn(List.of());

        handler().handle(context);

        ArgumentCaptor<EnvironmentTaskInput> inputCaptor = ArgumentCaptor.forClass(EnvironmentTaskInput.class);
        verify(sshRuntimeService, org.mockito.Mockito.times(2)).collectEnvironment(inputCaptor.capture(), any());
        List<EnvironmentTaskInput> inputs = inputCaptor.getAllValues();
        assertThat(inputs).extracting(EnvironmentTaskInput::projectId).containsOnly(fixtures.projectId);
        assertThat(inputs).extracting(EnvironmentTaskInput::environmentId)
                .containsExactly(fixtures.sourceEnvironmentId, fixtures.targetEnvironmentId);
        assertThat(inputs).extracting(EnvironmentTaskInput::collectScopes).containsOnly((List<String>) null);
        assertThat(inputs).extracting(EnvironmentTaskInput::timeoutSeconds).containsOnly(1800);
    }

    @Test
    void rejectsMissingAnalysisRunBeforeCreatingToolRun() {
        UUID runId = UUID.randomUUID();
        when(context.input(DependencyAnalysisTaskInput.class)).thenReturn(new DependencyAnalysisTaskInput(
                UUID.randomUUID(), runId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1L));
        when(repository.findById(runId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler().handle(context))
                .isInstanceOf(TaskExecutionException.class)
                .extracting(ex -> ((TaskExecutionException) ex).errorCode())
                .isEqualTo(TaskErrorCode.RESOURCE_NOT_FOUND);
        verify(toolRunRepository, never()).create(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void marksRunFailedWhenSourceBuildRunIsMissing() {
        Fixtures fixtures = fixtures();
        when(buildValidationRepository.findById(fixtures.buildRun.id())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler().handle(context))
                .isInstanceOf(TaskExecutionException.class)
                .extracting(ex -> ((TaskExecutionException) ex).errorCode())
                .isEqualTo(TaskErrorCode.RESOURCE_NOT_FOUND);
        verify(repository).markFinished(eq(fixtures.run.id()), eq(TaskStatus.FAILED), any(), eq(List.of()));
        verify(toolRunRepository).finish(any(), eq(TaskStatus.FAILED), eq(null), any());
        verify(sshRuntimeService, never()).collectEnvironment(any(), any());
    }

    @Test
    void marksAnalysisAndToolRunCancelled() {
        Fixtures fixtures = fixtures();
        org.mockito.Mockito.doThrow(new TaskCancelledException("cancelled"))
                .when(context).throwIfCancellationRequested();

        assertThatThrownBy(() -> handler().handle(context)).isInstanceOf(TaskCancelledException.class);

        verify(repository).markFinished(fixtures.run.id(), TaskStatus.CANCELLED,
                Map.of("error", "任务已取消"), List.of());
        verify(toolRunRepository).finish(any(), eq(TaskStatus.CANCELLED), eq(null), any());
        verify(sshRuntimeService, never()).collectEnvironment(any(), any());
    }

    @Test
    void mergedBuildLogUsesTailExcerptCombinationAndReadFailureFallback() throws Exception {
        UUID taskId = UUID.randomUUID();
        BuildValidationRunView build = buildRun(taskId, UUID.randomUUID(), UUID.randomUUID(), Map.of());

        when(taskLogRepository.findLatest(taskId, TaskLogType.COMBINED)).thenReturn(Optional.empty());
        when(taskLogService.readTailText(taskId, build.mergedLogId(), 20_000)).thenReturn("tail");
        assertThat((String) ReflectionTestUtils.invokeMethod(handler(), "mergedBuildLog", build)).isEqualTo("tail");

        when(taskLogRepository.findLatest(taskId, TaskLogType.COMBINED)).thenReturn(Optional.of(
                new cn.rvport.backend.task.TaskLogResponse(UUID.randomUUID(), taskId, UUID.randomUUID(),
                        TaskLogType.COMBINED, 1L, 1L, "hash", null, OffsetDateTime.now())));
        when(taskLogService.readTailText(taskId, build.mergedLogId(), 20_000)).thenReturn(" ");
        assertThat((String) ReflectionTestUtils.invokeMethod(handler(), "mergedBuildLog", build)).isEmpty();

        when(taskLogRepository.findLatest(taskId, TaskLogType.COMBINED)).thenReturn(Optional.of(
                new cn.rvport.backend.task.TaskLogResponse(UUID.randomUUID(), taskId, UUID.randomUUID(),
                        TaskLogType.COMBINED, 1L, 1L, "hash", "excerpt", OffsetDateTime.now())));
        when(taskLogService.readTailText(taskId, build.mergedLogId(), 20_000)).thenReturn("tail excerpt");
        assertThat((String) ReflectionTestUtils.invokeMethod(handler(), "mergedBuildLog", build)).isEqualTo("tail excerpt");

        when(taskLogService.readTailText(taskId, build.mergedLogId(), 20_000)).thenReturn("different tail");
        assertThat((String) ReflectionTestUtils.invokeMethod(handler(), "mergedBuildLog", build))
                .isEqualTo("excerpt\ndifferent tail");

        when(taskLogService.readTailText(taskId, build.mergedLogId(), 20_000))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "LOG_MISSING", "missing"));
        assertThat((String) ReflectionTestUtils.invokeMethod(handler(), "mergedBuildLog", build)).isEqualTo("excerpt");
    }

    @Test
    void compileDatabaseArtifactHandlesUnavailableMissingInvalidScopeAndValidContent() {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        DependencyAnalysisView run = analysisRun(projectId, runId);
        List<Object> warnings = new java.util.ArrayList<>();

        BuildValidationRunView unavailable = buildRun(UUID.randomUUID(), projectId, run.sourceRevisionId(), Map.of());
        assertThat((String) ReflectionTestUtils.invokeMethod(handler(), "compileDatabaseJson", run, unavailable, warnings))
                .isNull();

        BuildValidationRunView missingId = buildRun(UUID.randomUUID(), projectId, run.sourceRevisionId(),
                Map.of("compileDatabase", Map.of("available", true)));
        assertThat((String) ReflectionTestUtils.invokeMethod(handler(), "compileDatabaseJson", run, missingId, warnings))
                .isNull();
        assertThat(warnings.toString()).contains("ARTIFACT_ID_MISSING");

        BuildValidationRunView invalidId = buildRun(UUID.randomUUID(), projectId, run.sourceRevisionId(),
                Map.of("compileDatabase", Map.of("available", true, "storedArtifactId", "bad-id")));
        assertThat((String) ReflectionTestUtils.invokeMethod(handler(), "compileDatabaseJson", run, invalidId, warnings))
                .isNull();
        assertThat(warnings.toString()).contains("ARTIFACT_READ_FAILED");

        UUID artifactId = UUID.randomUUID();
        BuildValidationRunView validBuild = buildRun(UUID.randomUUID(), projectId, run.sourceRevisionId(),
                Map.of("compileDatabase", Map.of("available", true, "storedArtifactId", artifactId)));
        StoredArtifactView wrong = new StoredArtifactView(artifactId, UUID.randomUUID(), UUID.randomUUID(),
                StoredArtifactOwnerType.BUILD_VALIDATION, validBuild.id(), "WRONG", "bucket", "key",
                "application/json", 2, "sha", Map.of(), OffsetDateTime.now());
        when(storedArtifactService.download(artifactId)).thenReturn(
                new StoredArtifactResource(wrong, new ByteArrayResource("[]".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertThat((String) ReflectionTestUtils.invokeMethod(handler(), "compileDatabaseJson", run, validBuild, warnings))
                .isNull();
        assertThat(warnings.toString()).contains("ARTIFACT_SCOPE_MISMATCH");

        for (StoredArtifactView scopedWrong : List.of(
                new StoredArtifactView(artifactId, projectId, UUID.randomUUID(), StoredArtifactOwnerType.ARCHIVE,
                        validBuild.id(), "COMPILE_COMMANDS_JSON", "bucket", "key", "application/json", 2, "sha", Map.of(), OffsetDateTime.now()),
                new StoredArtifactView(artifactId, projectId, UUID.randomUUID(), StoredArtifactOwnerType.BUILD_VALIDATION,
                        UUID.randomUUID(), "COMPILE_COMMANDS_JSON", "bucket", "key", "application/json", 2, "sha", Map.of(), OffsetDateTime.now()),
                new StoredArtifactView(artifactId, projectId, UUID.randomUUID(), StoredArtifactOwnerType.BUILD_VALIDATION,
                        validBuild.id(), "WRONG", "bucket", "key", "application/json", 2, "sha", Map.of(), OffsetDateTime.now()))) {
            when(storedArtifactService.download(artifactId)).thenReturn(
                    new StoredArtifactResource(scopedWrong, new ByteArrayResource("[]".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            assertThat((String) ReflectionTestUtils.invokeMethod(handler(), "compileDatabaseJson", run, validBuild, warnings))
                    .isNull();
        }

        StoredArtifactView valid = new StoredArtifactView(artifactId, projectId, UUID.randomUUID(),
                StoredArtifactOwnerType.BUILD_VALIDATION, validBuild.id(), "COMPILE_COMMANDS_JSON", "bucket", "key",
                "application/json", 2, "sha", Map.of(), OffsetDateTime.now());
        when(storedArtifactService.download(artifactId)).thenReturn(
                new StoredArtifactResource(valid, new ByteArrayResource("[]".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertThat((String) ReflectionTestUtils.invokeMethod(handler(), "compileDatabaseJson", run, validBuild, warnings))
                .isEqualTo("[]");
    }

    @Test
    void reportHelpersHandleEmptyInvalidPopulatedAndEscapedValues() {
        assertThat((String) ReflectionTestUtils.invokeMethod(handler(), "htmlRows", (Object) null))
                .contains("无依赖分析条目");
        assertThat((String) ReflectionTestUtils.invokeMethod(handler(), "htmlRows", List.of()))
                .contains("无依赖分析条目");
        assertThat((String) ReflectionTestUtils.invokeMethod(handler(), "htmlRows", List.of("invalid")))
                .contains("无依赖分析条目");

        DependencyAnalysisResultItem item = new DependencyAnalysisResultItem(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), DependencyType.LINK_LIBRARY,
                "lib<&>.so", null, "glibc", "2.38", null, "glibc", "2.39", null, "TARGET_NEWER",
                "PACKAGE_OWNER", DependencyTargetMatchStatus.TARGET_NEWER, DependencyRiskLevel.LOW,
                "lib:/usr/lib", "review", DependencyConfidence.HIGH, Map.of());
        String row = ReflectionTestUtils.invokeMethod(handler(), "htmlRows", List.of(item));
        assertThat(row).contains("lib&lt;&amp;&gt;.so", "glibc@2.38", "risk-LOW");

        assertThat((String) ReflectionTestUtils.invokeMethod(handler(), "chips", (Object) null)).isEmpty();
        assertThat((String) ReflectionTestUtils.invokeMethod(handler(), "chips", Map.of())).isEmpty();
        assertThat((String) ReflectionTestUtils.invokeMethod(handler(), "chips", Map.of("HIGH", 2)))
                .contains("HIGH", "2");
        assertThat((Map<?, ?>) ReflectionTestUtils.invokeMethod(handler(), "mapValue", "not-map")).isEmpty();
        Map<?, ?> mapped = ReflectionTestUtils.invokeMethod(handler(), "mapValue", Map.of("a", 1));
        assertThat(mapped.get("a")).isEqualTo(1);
        assertThat((String) ReflectionTestUtils.invokeMethod(handler(), "packageLabel", null, "1")).isEmpty();
        assertThat((String) ReflectionTestUtils.invokeMethod(handler(), "packageLabel", "pkg", null)).isEqualTo("pkg");
        assertThat((String) ReflectionTestUtils.invokeMethod(handler(), "packageLabel", "pkg", "1")).isEqualTo("pkg@1");
        assertThat((String) ReflectionTestUtils.invokeMethod(handler(), "td", null, null)).isEqualTo("<td></td>");
        assertThat((String) ReflectionTestUtils.invokeMethod(handler(), "td", "x", " ")).isEqualTo("<td>x</td>");
        assertThat((String) ReflectionTestUtils.invokeMethod(handler(), "escapeHtml", (Object) null)).isEmpty();
        assertThat((String) ReflectionTestUtils.invokeMethod(handler(), "escapeHtml", "\"<&>"))
                .isEqualTo("&quot;&lt;&amp;&gt;");
    }

    private DependencyAnalysisHandler handler() {
        return new DependencyAnalysisHandler(repository, buildValidationRepository, taskLogRepository, taskLogService,
                toolRunRepository, sshRuntimeService, extractor, storedArtifactService, archiveCandidateRegistrar,
                objectMapper);
    }

    private DependencyAnalysisView analysisRun(UUID projectId, UUID runId) {
        return new DependencyAnalysisView(runId, projectId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1L, TaskStatus.RUNNING, Map.of(), List.of(), null, null, OffsetDateTime.now());
    }

    private BuildValidationRunView buildRun(
            UUID taskId, UUID projectId, UUID sourceRevisionId, Map<String, Object> buildFacts
    ) {
        return new BuildValidationRunView(UUID.randomUUID(), projectId, taskId, sourceRevisionId, UUID.randomUUID(),
                cn.rvport.backend.buildvalidation.BuildValidationScope.SOURCE_ONLY, UUID.randomUUID(), 1L,
                UUID.randomUUID(), "sha", "make", "/workspace", 60, 0, TaskStatus.SUCCEEDED, null,
                UUID.randomUUID(), null, null, OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now(),
                Map.of(), buildFacts);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, Object>> stubArtifacts(Fixtures fixtures) {
        ArgumentCaptor<Map<String, Object>> jsonCaptor = ArgumentCaptor.forClass(Map.class);
        when(storedArtifactService.storeJson(eq(fixtures.projectId), eq(fixtures.taskId),
                eq(StoredArtifactOwnerType.DEPENDENCY_ANALYSIS), eq(fixtures.run.id()), eq("SUMMARY_JSON"),
                jsonCaptor.capture(), any(), eq(context))).thenReturn(artifact(fixtures, "SUMMARY_JSON", "application/json"));
        when(storedArtifactService.store(any(StoredArtifactCreateCommand.class), eq(context)))
                .thenReturn(artifact(fixtures, "SUMMARY_HTML", "text/html;charset=UTF-8"));
        return jsonCaptor;
    }

    @Test
    void revokedAttemptDoesNotReplaceResultsOrFailAnAlreadyFinalizedRun() {
        Fixtures fixtures = fixtures();
        when(extractor.extract(eq(fixtures.run), eq(fixtures.buildRun), any(), eq(fixtures.sourceComponents), eq(fixtures.targetComponents), any()))
                .thenAnswer(invocation -> {
                    org.mockito.Mockito.doThrow(new cn.rvport.backend.worker.TaskAttemptLostException()).when(context).publish(any());
                    return List.of(item(fixtures));
                });
        assertThatThrownBy(() -> handler().handle(context))
                .isInstanceOf(cn.rvport.backend.worker.TaskAttemptLostException.class);
        verify(repository, never()).replaceResultItems(any(), any());
        verify(repository, never()).markFinished(any(), any(), any(), any());
        org.mockito.Mockito.verifyNoInteractions(archiveCandidateRegistrar);
    }

    private Fixtures fixtures() {
        return fixtures(true, true);
    }

    private Fixtures fixtures(boolean sourceRefreshSucceeds, boolean targetRefreshSucceeds) {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID buildRunId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        UUID sourceEnvironmentId = UUID.randomUUID();
        UUID targetEnvironmentId = UUID.randomUUID();
        UUID sourceSnapshotId = UUID.randomUUID();
        UUID targetSnapshotId = UUID.randomUUID();
        OffsetDateTime sourceCollectedAt = OffsetDateTime.parse("2026-08-24T10:00:00Z");
        OffsetDateTime targetCollectedAt = OffsetDateTime.parse("2026-08-24T10:01:00Z");
        DependencyAnalysisView run = new DependencyAnalysisView(runId, projectId, taskId, sourceRevisionId,
                buildRunId, targetEnvironmentId, 1L, TaskStatus.RUNNING, Map.of(), List.of(),
                null, null, OffsetDateTime.now());
        BuildValidationRunView buildRun = new BuildValidationRunView(buildRunId, projectId, UUID.randomUUID(),
                sourceRevisionId, sourceEnvironmentId, UUID.randomUUID(), UUID.randomUUID(), "sha", "make all", ".",
                120, 0, TaskStatus.SUCCEEDED, null, UUID.randomUUID(), null, null, OffsetDateTime.now(),
                OffsetDateTime.now(), OffsetDateTime.now(), Map.of());
        TaskView task = new TaskView(taskId, projectId, "DEPENDENCY_ANALYSIS", TaskStatus.RUNNING, 0,
                "running", UUID.randomUUID(), OffsetDateTime.now(), null, OffsetDateTime.now(), null, null,
                null, null, 0);
        DependencyAnalysisTaskInput input = new DependencyAnalysisTaskInput(projectId, runId, sourceRevisionId,
                buildRunId, targetEnvironmentId, 1L);
        List<DependencyAnalysisRepository.TargetComponentView> sourceComponents = List.of(
                new DependencyAnalysisRepository.TargetComponentView("gcc", "12.3.1", "TOOL", "/usr/bin/gcc",
                        "TOOL_VERSION", Map.of("ownerPackage", "gcc"))
        );
        List<DependencyAnalysisRepository.TargetComponentView> targetComponents = List.of(
                new DependencyAnalysisRepository.TargetComponentView("gcc", "13.2", "PACKAGE", null,
                        "REPOSITORY_PACKAGE", Map.of())
        );

        when(context.input(DependencyAnalysisTaskInput.class)).thenReturn(input);
        when(context.task()).thenReturn(task);
        when(repository.findById(runId)).thenReturn(Optional.of(run));
        org.mockito.Mockito.lenient().when(buildValidationRepository.findById(buildRunId)).thenReturn(Optional.of(buildRun));
        when(toolRunRepository.create(eq(projectId), eq(taskId), any(), any(), any(), eq(TaskStatus.RUNNING), any()))
                .thenReturn(UUID.randomUUID());
        org.mockito.Mockito.lenient().when(taskLogRepository.findLatest(buildRun.taskId(), TaskLogType.COMBINED))
                .thenReturn(Optional.empty());
        try {
            org.mockito.Mockito.lenient().when(taskLogService.readTailText(
                    buildRun.taskId(), buildRun.mergedLogId(), 20_000)).thenReturn("gcc main.c");
        } catch (ApiException ignored) {
            throw new AssertionError(ignored);
        }
        if (sourceRefreshSucceeds && targetRefreshSucceeds) {
            org.mockito.Mockito.lenient().when(sshRuntimeService.collectEnvironment(any(), any()))
                    .thenReturn(new EnvironmentCollectionResult(sourceSnapshotId, UUID.randomUUID(), 0, Map.of()))
                    .thenReturn(new EnvironmentCollectionResult(targetSnapshotId, UUID.randomUUID(), 0, Map.of()));
            org.mockito.Mockito.lenient().when(repository.findSnapshotCollectedAt(sourceSnapshotId)).thenReturn(Optional.of(sourceCollectedAt));
            org.mockito.Mockito.lenient().when(repository.findSnapshotCollectedAt(targetSnapshotId)).thenReturn(Optional.of(targetCollectedAt));
            org.mockito.Mockito.lenient().when(repository.listTargetComponents(sourceSnapshotId)).thenReturn(sourceComponents);
            org.mockito.Mockito.lenient().when(repository.listTargetComponents(targetSnapshotId)).thenReturn(targetComponents);
        }
        return new Fixtures(projectId, taskId, run, buildRun, sourceEnvironmentId, targetEnvironmentId,
                sourceSnapshotId, targetSnapshotId, sourceCollectedAt, targetCollectedAt, sourceComponents,
                targetComponents);
    }

    private DependencyAnalysisResultItem item(Fixtures fixtures) {
        return new DependencyAnalysisResultItem(fixtures.projectId, fixtures.run.sourceRevisionId(), fixtures.taskId,
                fixtures.run.id(), DependencyType.BUILD_TOOL, "gcc", null, "gcc", "12.3.1", "12.3.1",
                "gcc", "13.2", "13.2", "TARGET_NEWER", "PACKAGE_OWNER",
                DependencyTargetMatchStatus.TARGET_NEWER, DependencyRiskLevel.LOW, "tool:gcc",
                "确认目标 gcc 版本兼容性", DependencyConfidence.HIGH, Map.of());
    }

    private StoredArtifactView artifact(Fixtures fixtures, String type, String contentType) {
        return new StoredArtifactView(UUID.randomUUID(), fixtures.projectId, fixtures.taskId,
                StoredArtifactOwnerType.DEPENDENCY_ANALYSIS, fixtures.run.id(), type, "bucket", "object",
                contentType, 10L, "sha", Map.of(), OffsetDateTime.now());
    }

    private record Fixtures(
            UUID projectId,
            UUID taskId,
            DependencyAnalysisView run,
            BuildValidationRunView buildRun,
            UUID sourceEnvironmentId,
            UUID targetEnvironmentId,
            UUID sourceSnapshotId,
            UUID targetSnapshotId,
            OffsetDateTime sourceCollectedAt,
            OffsetDateTime targetCollectedAt,
            List<DependencyAnalysisRepository.TargetComponentView> sourceComponents,
            List<DependencyAnalysisRepository.TargetComponentView> targetComponents
    ) {
    }
}
