package cn.rvport.backend.automatedtest.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.archive.ArchiveCandidateRegistrar;
import cn.rvport.backend.artifact.StoredArtifactCreateCommand;
import cn.rvport.backend.artifact.StoredArtifactOwnerType;
import cn.rvport.backend.artifact.StoredArtifactRepository;
import cn.rvport.backend.artifact.StoredArtifactService;
import cn.rvport.backend.artifact.StoredArtifactView;
import cn.rvport.backend.automatedtest.AutomatedTestInput;
import cn.rvport.backend.automatedtest.AutomatedTestReportTarget;
import cn.rvport.backend.automatedtest.AutomatedTestReportTargetType;
import cn.rvport.backend.automatedtest.AutomatedTestRepository;
import cn.rvport.backend.automatedtest.AutomatedTestRunView;
import cn.rvport.backend.automatedtest.TestBatchRole;
import cn.rvport.backend.environment.runtime.SshCommandResult;
import cn.rvport.backend.environment.runtime.SshRuntimeException;
import cn.rvport.backend.environment.runtime.SshRuntimeService;
import cn.rvport.backend.task.RvPortTaskTypes;
import cn.rvport.backend.task.TaskExecutionException;
import cn.rvport.backend.task.TaskLogRepository;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.task.TaskView;
import cn.rvport.backend.testcomparison.TestComparisonService;
import cn.rvport.backend.worker.TaskCancelledException;
import cn.rvport.backend.worker.TaskExecutionContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class AutomatedTestHandlerTests {
    private static final UUID PROJECT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TASK_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID RUN_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID REVISION_ID = UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final UUID ENVIRONMENT_ID = UUID.fromString("50000000-0000-0000-0000-000000000005");
    private static final UUID ARTIFACT_ID = UUID.fromString("60000000-0000-0000-0000-000000000006");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-11T10:00:00Z");

    private AutomatedTestRepository repository;
    private SshRuntimeService sshRuntimeService;
    private StoredArtifactService storedArtifactService;
    private StoredArtifactRepository storedArtifactRepository;
    private TaskLogRepository taskLogRepository;
    private TestComparisonService testComparisonService;
    private ArchiveCandidateRegistrar archiveCandidateRegistrar;
    private TaskExecutionContext context;
    private AutomatedTestHandler handler;

    @BeforeEach
    void setUp() {
        repository = mock(AutomatedTestRepository.class);
        sshRuntimeService = mock(SshRuntimeService.class);
        storedArtifactService = mock(StoredArtifactService.class);
        storedArtifactRepository = mock(StoredArtifactRepository.class);
        taskLogRepository = mock(TaskLogRepository.class);
        testComparisonService = mock(TestComparisonService.class);
        archiveCandidateRegistrar = mock(ArchiveCandidateRegistrar.class);
        context = mock(TaskExecutionContext.class);
        when(context.publish(any())).thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(0)).get());
        handler = new AutomatedTestHandler(repository, sshRuntimeService, storedArtifactService,
                storedArtifactRepository, taskLogRepository, testComparisonService, archiveCandidateRegistrar);
        when(context.task()).thenReturn(task());
        when(sshRuntimeService.temporaryScriptCommand(any())).thenReturn("sh /tmp/test.sh");
    }

    @Test
    void rejectsLateSshResultWithoutFinishingOrStartingComparison() {
        when(context.input(AutomatedTestInput.class)).thenReturn(input(RUN_ID, Map.of(), null, List.of()));
        when(sshRuntimeService.executeRemote(any(), any(), anyString(), anyBoolean(), anyInt(), eq(context)))
                .thenAnswer(call -> {
                    doThrow(new cn.rvport.backend.worker.TaskAttemptLostException()).when(context).publish(any());
                    return result(0, "ok", "");
                });
        assertThatThrownBy(() -> handler.handle(context))
                .isInstanceOf(cn.rvport.backend.worker.TaskAttemptLostException.class);
        verify(repository, never()).finish(any(), any(), any(), any(), any(), any(), any(), any());
        org.mockito.Mockito.verifyNoInteractions(testComparisonService, archiveCandidateRegistrar, storedArtifactService);
    }

    @Test
    void executesSuccessfulTestAndArchivesFinishedRun() {
        Map<String, String> environment = new java.util.LinkedHashMap<>();
        environment.put("TOKEN", "a'b");
        environment.put("EMPTY", null);
        AutomatedTestInput input = input(RUN_ID, environment, null, List.of());
        when(context.input(AutomatedTestInput.class)).thenReturn(input);
        when(sshRuntimeService.executeRemote(any(), any(), anyString(), eq(true), eq(300), eq(context)))
                .thenReturn(result(0, "ok", ""));
        when(repository.findById(RUN_ID)).thenReturn(Optional.of(run(TaskStatus.SUCCEEDED, TestBatchRole.SOURCE)));

        assertThat(handler.taskType()).isEqualTo(RvPortTaskTypes.AUTOMATED_TEST);
        assertThat(handler.handle(context).message()).isEqualTo("自动化测试完成");
        verify(repository).markRunning(RUN_ID);
        verify(repository).finish(eq(RUN_ID), eq(TaskStatus.SUCCEEDED), eq(0), isNull(), isNull(), isNull(),
                eq("exitCode=0, durationMs=1000, reportArtifacts=0"), eq(List.of()));
        verify(archiveCandidateRegistrar).registerAutomatedTest(any(), any(), eq(List.of()));
        verify(testComparisonService).onAutomatedTestFinished(RUN_ID);

        ArgumentCaptor<String> script = ArgumentCaptor.forClass(String.class);
        verify(sshRuntimeService).executeRemote(any(), eq("sh /tmp/test.sh"), script.capture(), eq(true), eq(300), eq(context));
        assertThat(script.getValue()).contains("export TOKEN='a'\"'\"'b'", "export EMPTY=''", "make test", "exit $?");
    }

    @Test
    void resolvesRunFromTaskAndRejectsMissingRun() {
        AutomatedTestInput withoutRun = input(null, Map.of(), null, List.of());
        when(context.input(AutomatedTestInput.class)).thenReturn(withoutRun);
        when(repository.findByTaskId(TASK_ID)).thenReturn(Optional.of(run(TaskStatus.PENDING, null)));
        when(sshRuntimeService.executeRemote(any(), any(), anyString(), anyBoolean(), anyInt(), eq(context)))
                .thenReturn(result(0, "", ""));
        handler.handle(context);
        verify(repository).markRunning(RUN_ID);

        AutomatedTestRepository missingRepository = mock(AutomatedTestRepository.class);
        AutomatedTestHandler missingHandler = new AutomatedTestHandler(missingRepository, sshRuntimeService,
                storedArtifactService, storedArtifactRepository, taskLogRepository, testComparisonService,
                archiveCandidateRegistrar);
        assertThatThrownBy(() -> missingHandler.handle(context)).isInstanceOf(TaskExecutionException.class)
                .hasMessageContaining("记录不存在");
        verify(missingRepository, never()).markRunning(any());
    }

    @Test
    void recordsNonZeroExitAndPropagatesExecutionFailure() {
        when(context.input(AutomatedTestInput.class)).thenReturn(input(RUN_ID, null, " ", List.of()));
        when(sshRuntimeService.executeRemote(any(), any(), anyString(), eq(true), anyInt(), eq(context)))
                .thenReturn(result(2, "failed", "compiler error"));
        assertThatThrownBy(() -> handler.handle(context)).isInstanceOf(TaskExecutionException.class)
                .hasMessageContaining("自动化测试执行失败");
        verify(repository, times(2)).finish(eq(RUN_ID), eq(TaskStatus.FAILED), eq(2), isNull(), isNull(), isNull(),
                anyString(), eq(List.of()));
        verify(testComparisonService, times(2)).onAutomatedTestFinished(RUN_ID);
    }

    @Test
    void collectsFileReportAndPassesStoredArtifactToArchive() {
        when(context.input(AutomatedTestInput.class)).thenReturn(input(RUN_ID, Map.of(), "/work",
                List.of(new AutomatedTestReportTarget(AutomatedTestReportTargetType.FILE, "reports/result.json"))));
        String encoded = Base64.getEncoder().encodeToString("{\"passed\":1}".getBytes(StandardCharsets.UTF_8));
        when(sshRuntimeService.executeRemote(any(), any(), any(), anyBoolean(), anyInt(), eq(context)))
                .thenReturn(result(0, "ok", ""), result(0, "RVPORT_REPORT_SIZE=12\n" + encoded, ""));
        StoredArtifactView artifact = artifact();
        when(storedArtifactService.store(any(), eq(context))).thenReturn(artifact);
        when(storedArtifactRepository.findById(ARTIFACT_ID)).thenReturn(Optional.of(artifact));
        when(repository.findById(RUN_ID)).thenReturn(Optional.of(run(TaskStatus.SUCCEEDED, TestBatchRole.TARGET)));

        handler.handle(context);

        ArgumentCaptor<StoredArtifactCreateCommand> command = ArgumentCaptor.forClass(StoredArtifactCreateCommand.class);
        verify(storedArtifactService).store(command.capture(), eq(context));
        assertThat(command.getValue().contentType()).isEqualTo("application/json");
        assertThat(command.getValue().extension()).isEqualTo("json");
        assertThat(new String(command.getValue().content(), StandardCharsets.UTF_8)).isEqualTo("{\"passed\":1}");
        verify(archiveCandidateRegistrar).registerAutomatedTest(any(), any(), eq(List.of(artifact)));
    }

    @Test
    void filtersUnsafeDirectoryEntriesAndRecordsSkippedAndFailedReports() {
        when(context.input(AutomatedTestInput.class)).thenReturn(input(RUN_ID, Map.of(), null,
                List.of(new AutomatedTestReportTarget(AutomatedTestReportTargetType.DIRECTORY, "reports"))));
        when(sshRuntimeService.executeRemote(any(), any(), any(), anyBoolean(), anyInt(), eq(context)))
                .thenReturn(
                        result(0, "ok", ""),
                        result(0, "reports/a.html\n/absolute.json\n../escape.txt\nreports/b.\n", ""),
                        result(0, "RVPORT_REPORT_TOO_LARGE=9999999", ""),
                        result(0, "not-base64!", "")
                );
        handler.handle(context);

        ArgumentCaptor<List<Map<String, Object>>> reports = reportsCaptor();
        verify(repository, atLeastOnce()).finish(eq(RUN_ID), eq(TaskStatus.SUCCEEDED), eq(0), isNull(), isNull(), isNull(),
                anyString(), reports.capture());
        assertThat(reports.getValue()).extracting(item -> item.get("status"))
                .containsExactly("SKIPPED", "FAILED");
        verify(storedArtifactService, never()).store(any(), any());
        ArgumentCaptor<String> commands = ArgumentCaptor.forClass(String.class);
        verify(sshRuntimeService, times(4)).executeRemote(any(), commands.capture(), any(), anyBoolean(), anyInt(), eq(context));
        assertThat(commands.getAllValues().get(1)).contains("find 'reports' -maxdepth 4", "head -n 100");
    }

    @Test
    void ignoresMissingOrFailedReportDirectories() {
        when(context.input(AutomatedTestInput.class)).thenReturn(input(RUN_ID, Map.of(), null,
                List.of(new AutomatedTestReportTarget(AutomatedTestReportTargetType.DIRECTORY, "empty"),
                        new AutomatedTestReportTarget(AutomatedTestReportTargetType.DIRECTORY, "failed"))));
        when(sshRuntimeService.executeRemote(any(), any(), any(), anyBoolean(), anyInt(), eq(context)))
                .thenReturn(result(0, "ok", ""), result(0, " ", ""), result(1, "ignored", "error"));
        assertThat(handler.handle(context).message()).contains("完成");
        verify(storedArtifactService, never()).store(any(), any());
    }

    @Test
    void recordsCancellationAfterCommandWithoutRunningReportCollection() {
        when(context.input(AutomatedTestInput.class)).thenReturn(input(RUN_ID, Map.of(), null,
                List.of(new AutomatedTestReportTarget(AutomatedTestReportTargetType.FILE, "report.txt"))));
        when(sshRuntimeService.executeRemote(any(), any(), anyString(), eq(true), anyInt(), eq(context)))
                .thenReturn(result(0, "ok", ""));
        doThrow(new TaskCancelledException("cancelled")).when(context).throwIfCancellationRequested();
        assertThatThrownBy(() -> handler.handle(context)).isInstanceOf(TaskCancelledException.class);
        verify(repository).finish(eq(RUN_ID), eq(TaskStatus.CANCELLED), eq(0), isNull(), isNull(), isNull(),
                eq("任务已取消"), eq(List.of()));
        verify(testComparisonService).onAutomatedTestFinished(RUN_ID);
    }

    @Test
    void mapsSshTimeoutAndOrdinaryFailureToTerminalStatuses() {
        when(context.input(AutomatedTestInput.class)).thenReturn(input(RUN_ID, Map.of(), null, List.of()));
        when(sshRuntimeService.executeRemote(any(), any(), anyString(), eq(true), anyInt(), eq(context)))
                .thenThrow(new SshRuntimeException("命令超时"));
        assertThatThrownBy(() -> handler.handle(context)).isInstanceOf(TaskExecutionException.class)
                .hasMessageContaining("SSH 执行失败");
        verify(repository).finish(eq(RUN_ID), eq(TaskStatus.TIMEOUT), isNull(), isNull(), isNull(), isNull(),
                anyString(), eq(List.of()));

        setUp();
        when(context.input(AutomatedTestInput.class)).thenReturn(input(RUN_ID, Map.of(), null, List.of()));
        when(sshRuntimeService.executeRemote(any(), any(), anyString(), eq(true), anyInt(), eq(context)))
                .thenThrow(new SshRuntimeException("remote failed", new IllegalStateException("connection reset")));
        assertThatThrownBy(() -> handler.handle(context)).isInstanceOf(TaskExecutionException.class);
        verify(repository).finish(eq(RUN_ID), eq(TaskStatus.FAILED), isNull(), isNull(), isNull(), isNull(),
                anyString(), eq(List.of()));
    }

    @Test
    void skipsMissingReportAndHandlesPlainTextExtension() {
        when(context.input(AutomatedTestInput.class)).thenReturn(input(RUN_ID, Map.of(), " ",
                List.of(new AutomatedTestReportTarget(AutomatedTestReportTargetType.FILE, "report"))));
        when(sshRuntimeService.executeRemote(any(), any(), any(), anyBoolean(), anyInt(), eq(context)))
                .thenReturn(result(0, "ok", ""), result(1, "", "missing"));
        handler.handle(context);
        ArgumentCaptor<List<Map<String, Object>>> reports = reportsCaptor();
        verify(repository).finish(eq(RUN_ID), eq(TaskStatus.SUCCEEDED), eq(0), isNull(), isNull(), isNull(),
                anyString(), reports.capture());
        assertThat(reports.getValue().getFirst()).containsEntry("status", "SKIPPED").containsEntry("sizeBytes", 0L);
    }

    @Test
    void collectsHtmlAndPlainTextReportsWithExpectedMetadata() {
        when(context.input(AutomatedTestInput.class)).thenReturn(input(RUN_ID, Map.of(), null, List.of(
                new AutomatedTestReportTarget(AutomatedTestReportTargetType.FILE, "report.htm"),
                new AutomatedTestReportTarget(AutomatedTestReportTargetType.FILE, "README"))));
        String encoded = Base64.getEncoder().encodeToString("ok".getBytes(StandardCharsets.UTF_8));
        when(sshRuntimeService.executeRemote(any(), any(), any(), anyBoolean(), anyInt(), eq(context)))
                .thenReturn(result(0, "ok", ""), result(0, encoded, ""), result(0, encoded, ""));
        when(storedArtifactService.store(any(), eq(context))).thenReturn(artifact());
        handler.handle(context);

        ArgumentCaptor<StoredArtifactCreateCommand> commands = ArgumentCaptor.forClass(StoredArtifactCreateCommand.class);
        verify(storedArtifactService, times(2)).store(commands.capture(), eq(context));
        assertThat(commands.getAllValues()).extracting(StoredArtifactCreateCommand::contentType)
                .containsExactly("text/html", "text/plain");
        assertThat(commands.getAllValues()).extracting(StoredArtifactCreateCommand::extension)
                .containsExactly("htm", "txt");
    }

    @Test
    void stopsCollectingContentAfterAggregateReportSizeLimit() {
        List<AutomatedTestReportTarget> targets = java.util.stream.IntStream.range(0, 11)
                .mapToObj(index -> new AutomatedTestReportTarget(AutomatedTestReportTargetType.FILE,
                        "reports/report-" + index + ".bin"))
                .toList();
        when(context.input(AutomatedTestInput.class)).thenReturn(input(RUN_ID, Map.of(), null, targets));
        String encoded = Base64.getEncoder().encodeToString(new byte[5 * 1024 * 1024]);
        SshCommandResult report = result(0, encoded, "");
        SshCommandResult[] responses = new SshCommandResult[12];
        responses[0] = result(0, "ok", "");
        java.util.Arrays.fill(responses, 1, responses.length, report);
        when(sshRuntimeService.executeRemote(any(), any(), any(), anyBoolean(), anyInt(), eq(context)))
                .thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));
        when(storedArtifactService.store(any(), eq(context))).thenReturn(artifact());
        handler.handle(context);

        verify(storedArtifactService, times(10)).store(any(), eq(context));
        ArgumentCaptor<List<Map<String, Object>>> reports = reportsCaptor();
        verify(repository).finish(eq(RUN_ID), eq(TaskStatus.SUCCEEDED), eq(0), isNull(), isNull(), isNull(),
                anyString(), reports.capture());
        assertThat(reports.getValue()).hasSize(11);
        assertThat(reports.getValue().getLast()).containsEntry("status", "SKIPPED")
                .containsEntry("message", "报告总大小超过限制");
    }

    @Test
    void mapsReportContentTypesAndExtensionsAcrossBoundaries() {
        assertThat((String) ReflectionTestUtils.invokeMethod(handler, "contentType", "RESULT.JSON"))
                .isEqualTo("application/json");
        assertThat((String) ReflectionTestUtils.invokeMethod(handler, "contentType", "report.html"))
                .isEqualTo("text/html");
        assertThat((String) ReflectionTestUtils.invokeMethod(handler, "contentType", "report.HTM"))
                .isEqualTo("text/html");
        assertThat((String) ReflectionTestUtils.invokeMethod(handler, "contentType", "report.xml"))
                .isEqualTo("text/plain");
        assertThat((String) ReflectionTestUtils.invokeMethod(handler, "extension", "README")).isEqualTo("txt");
        assertThat((String) ReflectionTestUtils.invokeMethod(handler, "extension", "report.")).isEqualTo("txt");
        assertThat((String) ReflectionTestUtils.invokeMethod(handler, "extension", "report.j$so@n")).isEqualTo("json");
    }

    @Test
    void classifiesCollectedReportMapsAndResolvesStoredArtifacts() {
        Map<String, Object> collected = Map.of("status", "COLLECTED", "artifactId", ARTIFACT_ID.toString());
        Map<String, Object> missingId = Map.of("status", "COLLECTED");
        Map<String, Object> skipped = Map.of("status", "SKIPPED", "artifactId", ARTIFACT_ID.toString());

        assertThat((boolean) ReflectionTestUtils.invokeMethod(handler, "collected", collected)).isTrue();
        assertThat((boolean) ReflectionTestUtils.invokeMethod(handler, "collected", missingId)).isFalse();
        assertThat((boolean) ReflectionTestUtils.invokeMethod(handler, "collected", skipped)).isFalse();
        assertThat((boolean) ReflectionTestUtils.invokeMethod(handler, "collected", (Map<String, Object>) null)).isFalse();
        assertThat((List<?>) ReflectionTestUtils.invokeMethod(handler, "collectedArtifacts", (List<?>) null)).isEmpty();
        assertThat((List<?>) ReflectionTestUtils.invokeMethod(handler, "collectedArtifacts", List.of())).isEmpty();

        when(storedArtifactRepository.findById(ARTIFACT_ID)).thenReturn(Optional.of(artifact()));
        @SuppressWarnings("unchecked")
        List<StoredArtifactView> artifacts = ReflectionTestUtils.invokeMethod(handler, "collectedArtifacts",
                List.of(collected, skipped));
        assertThat(artifacts).containsExactly(artifact());

        when(storedArtifactRepository.findById(ARTIFACT_ID)).thenReturn(Optional.empty());
        assertThat((List<?>) ReflectionTestUtils.invokeMethod(handler, "collectedArtifacts", List.of(collected))).isEmpty();
    }

    @Test
    void formatsSummariesWithNullRoleAndNullReports() {
        SshCommandResult result = new SshCommandResult(0, "", "", Duration.ofMillis(321));
        assertThat((String) ReflectionTestUtils.invokeMethod(handler, "outputSummary", result, (List<?>) null))
                .isEqualTo("exitCode=0, durationMs=321, reportArtifacts=0");
        assertThat((String) ReflectionTestUtils.invokeMethod(handler, "outputSummary", result,
                List.of(Map.of(), Map.of()))).endsWith("reportArtifacts=2");

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = ReflectionTestUtils.invokeMethod(handler, "archiveSummary",
                run(TaskStatus.SUCCEEDED, null), result, null);
        assertThat(summary).containsEntry("testBatchRole", null)
                .containsEntry("reportArtifactCount", 0L)
                .containsEntry("durationMs", 321L);
    }

    @Test
    void recognizesTimeoutTextInMessageOrCauseOnly() {
        assertThat((boolean) ReflectionTestUtils.invokeMethod(handler, "timeout",
                new SshRuntimeException("执行超时", null))).isTrue();
        assertThat((boolean) ReflectionTestUtils.invokeMethod(handler, "timeout",
                new SshRuntimeException("failed", new IllegalStateException("TIMEOUT waiting")))).isTrue();
        assertThat((boolean) ReflectionTestUtils.invokeMethod(handler, "timeout",
                new SshRuntimeException("connection reset", null))).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<Map<String, Object>>> reportsCaptor() {
        return ArgumentCaptor.forClass((Class<List<Map<String, Object>>>) (Class<?>) List.class);
    }

    private static AutomatedTestInput input(UUID runId, Map<String, String> environment, String workingDirectory,
                                            List<AutomatedTestReportTarget> targets) {
        return new AutomatedTestInput(PROJECT_ID, runId, UUID.randomUUID(), REVISION_ID, ENVIRONMENT_ID, 1L,
                UUID.randomUUID(), TestBatchRole.SOURCE, "make test", workingDirectory, 300, environment, targets, null);
    }

    private static TaskView task() {
        return new TaskView(TASK_ID, PROJECT_ID, RvPortTaskTypes.AUTOMATED_TEST, TaskStatus.RUNNING, 10, "running",
                UUID.randomUUID(), NOW, NOW, NOW, null, 300, null, null, 0);
    }

    private static SshCommandResult result(int exitCode, String stdout, String stderr) {
        return new SshCommandResult(exitCode, stdout, stderr, Duration.ofSeconds(1));
    }

    private static AutomatedTestRunView run(TaskStatus status, TestBatchRole role) {
        return new AutomatedTestRunView(RUN_ID, PROJECT_ID, TASK_ID, UUID.randomUUID(), REVISION_ID, ENVIRONMENT_ID,
                1L, UUID.randomUUID(), role, "env", "make test", "/work", 300, 0, null, null, null, status,
                null, null, null, null, List.of(), NOW, NOW.plusSeconds(1));
    }

    private static StoredArtifactView artifact() {
        return new StoredArtifactView(ARTIFACT_ID, PROJECT_ID, TASK_ID, StoredArtifactOwnerType.AUTOMATED_TEST,
                RUN_ID, "TEST_REPORT_0", "bucket", "key", "application/json", 12L, "sha", Map.of(), NOW);
    }
}
