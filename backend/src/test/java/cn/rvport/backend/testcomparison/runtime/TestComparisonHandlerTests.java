package cn.rvport.backend.testcomparison.runtime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.archive.ArchiveCandidateRegistrar;
import cn.rvport.backend.artifact.StoredArtifactOwnerType;
import cn.rvport.backend.artifact.StoredArtifactService;
import cn.rvport.backend.artifact.StoredArtifactView;
import cn.rvport.backend.automatedtest.AutomatedTestRepository;
import cn.rvport.backend.automatedtest.AutomatedTestRunView;
import cn.rvport.backend.automatedtest.TestBatchRole;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.task.RvPortTaskTypes;
import cn.rvport.backend.task.TaskErrorCode;
import cn.rvport.backend.task.TaskExecutionException;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.task.TaskView;
import cn.rvport.backend.testcomparison.TestComparisonRepository;
import cn.rvport.backend.testcomparison.TestComparisonOverallStatus;
import cn.rvport.backend.testcomparison.TestComparisonRiskLevel;
import cn.rvport.backend.testcomparison.TestComparisonStatus;
import cn.rvport.backend.testcomparison.TestComparisonTaskInput;
import cn.rvport.backend.worker.TaskExecutionContext;
import java.math.BigDecimal;
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
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class TestComparisonHandlerTests {
    private static final UUID PROFILE_ID = UUID.randomUUID(), RUNTIME_ID = UUID.randomUUID();
    private cn.rvport.backend.modelprofile.ModelExecutionSelection selection() {
        return new cn.rvport.backend.modelprofile.ModelExecutionSelection(OWNER_ID, PROFILE_ID, RUNTIME_ID);
    }
    private static final UUID OWNER_ID = UUID.fromString("a0000000-0000-0000-0000-000000000001");

    @Mock
    private TestComparisonRepository repository;
    @Mock
    private AutomatedTestRepository automatedTestRepository;
    @Mock
    private TestComparisonInputAggregator inputAggregator;
    @Mock
    private TestComparisonAgentRuntime agentRuntime;
    @Mock
    private StoredArtifactService storedArtifactService;
    @Mock
    private ArchiveCandidateRegistrar archiveCandidateRegistrar;
    @Mock
    private TaskExecutionContext context;

    @org.junit.jupiter.api.BeforeEach
    void allowPublication() {
        var profile = new cn.rvport.backend.jooq.tables.records.ModelProfilesRecord(); profile.setId(PROFILE_ID);
        var runtime = new cn.rvport.backend.jooq.tables.records.AgentRuntimesRecord(); runtime.setId(RUNTIME_ID);
        org.mockito.Mockito.lenient().when(agentRuntime.resolveExecution(eq(selection()), eq(OWNER_ID), any())).thenReturn(
                new cn.rvport.backend.agentruntime.AgentModelRuntimeConfig(runtime, profile, null));
        org.mockito.Mockito.lenient().when(context.publish(any()))
                .thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(0)).get());
    }

    @Test
    void rejectsLostAttemptWithoutConvertingItIntoDomainFailure() {
        Setup setup = setup();
        when(inputAggregator.aggregate(setup.source, setup.target))
                .thenThrow(new cn.rvport.backend.worker.TaskAttemptLostException());
        assertThatThrownBy(() -> handler().handle(context))
                .isInstanceOf(cn.rvport.backend.worker.TaskAttemptLostException.class);
        verify(repository, never()).markFailed(any(), any(), any(), any());
        verify(repository, never()).replaceFindings(any(), any());
        verify(automatedTestRepository, never()).updateStats(any(), any(), any(), any());
        org.mockito.Mockito.verifyNoInteractions(storedArtifactService, archiveCandidateRegistrar, agentRuntime);
    }

    @Test
    void updatesAutomatedTestStatsFromSuccessfulComparisonResult() {
        UUID projectId = UUID.randomUUID();
        UUID comparisonRunId = UUID.randomUUID();
        UUID sourceRunId = UUID.randomUUID();
        UUID targetRunId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TestComparisonTaskInput input = new TestComparisonTaskInput(projectId,  comparisonRunId,  sourceRunId, targetRunId, selection());
        TaskView task = new TaskView(taskId, projectId, RvPortTaskTypes.TEST_LOG_COMPARE, TaskStatus.RUNNING, 20,
                "running", OWNER_ID, OffsetDateTime.now(), null, OffsetDateTime.now(), null, null,
                null, null, 0);
        AutomatedTestRunView source = run(projectId, sourceRunId, TaskStatus.SUCCEEDED, 0, TestBatchRole.SOURCE);
        AutomatedTestRunView target = run(projectId, targetRunId, TaskStatus.SUCCEEDED, 0, TestBatchRole.TARGET);
        TestComparisonInputBundle bundle = new TestComparisonInputBundle(Map.of(), false, Map.of());
        TestComparisonAgentResult result = new TestComparisonAgentResult(
                TestComparisonOverallStatus.EQUIVALENT,
                TestComparisonRiskLevel.INFO,
                BigDecimal.ONE,
                "两侧测试一致",
                "无需处理",
                Map.of("status", "SUCCEEDED", "passedTests", 22, "failedTests", 0, "skippedTests", 0),
                Map.of("status", "SUCCEEDED", "passedTests", 21, "failedTests", 1, "skippedTests", 0),
                List.of(),
                "{}",
                Map.of("parseSource", "comparison-result.json")
        );

        when(context.input(TestComparisonTaskInput.class)).thenReturn(input);
        org.mockito.Mockito.lenient().when(context.task()).thenReturn(task);
        when(automatedTestRepository.findById(sourceRunId)).thenReturn(Optional.of(source));
        org.mockito.Mockito.lenient().when(automatedTestRepository.findById(targetRunId)).thenReturn(Optional.of(target));
        when(inputAggregator.aggregate(source, target)).thenReturn(bundle);
        when(agentRuntime.run(org.mockito.ArgumentMatchers.eq(bundle), org.mockito.ArgumentMatchers.any())).thenReturn(result);
        when(storedArtifactService.storeJson(eq(projectId), eq(taskId), eq(StoredArtifactOwnerType.TEST_COMPARISON),
                eq(comparisonRunId), eq("AGENT_OUTPUT"), any(), any(), eq(context)))
                .thenReturn(artifact(projectId, taskId, comparisonRunId));
        when(repository.findById(comparisonRunId)).thenReturn(Optional.empty());

        handler().handle(context);

        verify(automatedTestRepository).updateStats(sourceRunId, 22, 0, 0);
        verify(automatedTestRepository).updateStats(targetRunId, 21, 1, 0);
        verify(repository).saveResult(eq(comparisonRunId), eq(TestComparisonOverallStatus.EQUIVALENT),
                eq(TestComparisonRiskLevel.INFO), eq(BigDecimal.ONE), eq("两侧测试一致"), eq("无需处理"),
                eq(result.sourceStats()), eq(result.targetStats()), eq(false), eq(Map.of()), any());
    }

    @Test
    void marksComparisonFailedAndStoresDiagnosticsWhenAgentOutputInvalid() {
        UUID projectId = UUID.randomUUID();
        UUID comparisonRunId = UUID.randomUUID();
        UUID sourceRunId = UUID.randomUUID();
        UUID targetRunId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TestComparisonTaskInput input = new TestComparisonTaskInput(projectId,  comparisonRunId,  sourceRunId, targetRunId, selection());
        TaskView task = new TaskView(taskId, projectId, RvPortTaskTypes.TEST_LOG_COMPARE, TaskStatus.RUNNING, 20,
                "running", OWNER_ID, OffsetDateTime.now(), null, OffsetDateTime.now(), null, null,
                null, null, 0);
        AutomatedTestRunView source = run(projectId, sourceRunId, TaskStatus.SUCCEEDED, 0, TestBatchRole.SOURCE);
        AutomatedTestRunView target = run(projectId, targetRunId, TaskStatus.FAILED, 1, TestBatchRole.TARGET);
        TestComparisonInputBundle bundle = new TestComparisonInputBundle(Map.of(), false, Map.of());
        Map<String, Object> diagnostics = Map.of("stdout", "...", "parseSource", "comparison-result.json");

        when(context.input(TestComparisonTaskInput.class)).thenReturn(input);
        when(context.task()).thenReturn(task);
        when(automatedTestRepository.findById(sourceRunId)).thenReturn(Optional.of(source));
        when(automatedTestRepository.findById(targetRunId)).thenReturn(Optional.of(target));
        when(inputAggregator.aggregate(source, target)).thenReturn(bundle);
        when(agentRuntime.run(org.mockito.ArgumentMatchers.eq(bundle), org.mockito.ArgumentMatchers.any())).thenThrow(new TestComparisonAgentOutputException(
                "TEST_COMPARE_AGENT_OUTPUT_INVALID",
                "测试结果比较 agent 输出不是有效 JSON 或未通过语义校验",
                diagnostics
        ));
        when(storedArtifactService.storeJson(eq(projectId), eq(taskId), eq(StoredArtifactOwnerType.TEST_COMPARISON),
                eq(comparisonRunId), eq("AGENT_OUTPUT"), any(), any(), eq(context)))
                .thenReturn(artifact(projectId, taskId, comparisonRunId));

        assertThatThrownBy(() -> handler().handle(context))
                .isInstanceOf(TaskExecutionException.class)
                .extracting("errorCode")
                .isEqualTo("TASK_REMOTE_COMMAND_FAILED");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> artifactCaptor = ArgumentCaptor.forClass(Map.class);
        verify(storedArtifactService).storeJson(eq(projectId), eq(taskId), eq(StoredArtifactOwnerType.TEST_COMPARISON),
                eq(comparisonRunId), eq("AGENT_OUTPUT"), artifactCaptor.capture(), any(), eq(context));
        org.assertj.core.api.Assertions.assertThat(artifactCaptor.getValue()).containsEntry("parseSource", "comparison-result.json");
        verify(repository).markFailed(comparisonRunId, TestComparisonStatus.FAILED,
                "TEST_COMPARE_AGENT_OUTPUT_INVALID", "测试结果比较 agent 输出不是有效 JSON 或未通过语义校验");
        verify(automatedTestRepository, never()).updateStats(any(), any(), any(), any());
        verify(repository, never()).replaceFindings(any(), any());
        verify(repository, never()).saveResult(any(), any(), any(), any(), any(), any(), any(), any(), any(Boolean.class), any(), any());
    }

    @Test
    void marksFailedWhenSourceRunDoesNotExist() {
        Setup setup = setup();
        when(automatedTestRepository.findById(setup.source.id())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler().handle(context))
                .isInstanceOf(TaskExecutionException.class)
                .extracting(ex -> ((TaskExecutionException) ex).errorCode())
                .isEqualTo(TaskErrorCode.RESOURCE_NOT_FOUND);
        verify(repository).markFailed(setup.input.comparisonRunId(), TestComparisonStatus.FAILED,
                TaskErrorCode.RESOURCE_NOT_FOUND, "源测试记录不存在");
        verify(inputAggregator, never()).aggregate(any(), any());
    }

    @Test
    void wrapsApiFailureFromInputAggregation() {
        Setup setup = setup();
        when(inputAggregator.aggregate(setup.source, setup.target))
                .thenThrow(new ApiException(HttpStatus.BAD_REQUEST, "TEST_LOG_INVALID", "日志不可读"));

        assertThatThrownBy(() -> handler().handle(context))
                .isInstanceOf(TaskExecutionException.class)
                .extracting(ex -> ((TaskExecutionException) ex).errorCode())
                .isEqualTo(TaskErrorCode.REMOTE_COMMAND_FAILED);
        verify(repository).markFailed(setup.input.comparisonRunId(), TestComparisonStatus.FAILED,
                "TEST_LOG_INVALID", "日志不可读");
    }

    @Test
    void preservesTaskExecutionFailureFromAgentRuntime() {
        Setup setup = setup();
        TestComparisonInputBundle bundle = new TestComparisonInputBundle(Map.of(), false, Map.of());
        when(inputAggregator.aggregate(setup.source, setup.target)).thenReturn(bundle);
        TaskExecutionException failure = new TaskExecutionException("MODEL_UNAVAILABLE", "模型不可用", null);
        when(agentRuntime.run(org.mockito.ArgumentMatchers.eq(bundle), org.mockito.ArgumentMatchers.any())).thenThrow(failure);

        assertThatThrownBy(() -> handler().handle(context)).isSameAs(failure);
        verify(repository).markFailed(setup.input.comparisonRunId(), TestComparisonStatus.FAILED,
                "MODEL_UNAVAILABLE", "模型不可用");
    }

    @Test
    void recordsGenericRuntimeFailureWithoutChangingException() {
        Setup setup = setup();
        TestComparisonInputBundle bundle = new TestComparisonInputBundle(Map.of(), false, Map.of());
        when(inputAggregator.aggregate(setup.source, setup.target)).thenReturn(bundle);
        RuntimeException failure = new IllegalStateException("unexpected");
        when(agentRuntime.run(org.mockito.ArgumentMatchers.eq(bundle), org.mockito.ArgumentMatchers.any())).thenThrow(failure);

        assertThatThrownBy(() -> handler().handle(context)).isSameAs(failure);
        verify(repository).markFailed(setup.input.comparisonRunId(), TestComparisonStatus.FAILED,
                "TEST_COMPARE_FAILED", "测试结果比较失败");
    }

    @Test
    void toleratesMissingAndNonNumericStatsAndNullClassification() {
        Setup setup = setup();
        TestComparisonInputBundle bundle = new TestComparisonInputBundle(Map.of(), true, Map.of("truncated", true));
        TestComparisonAgentResult result = new TestComparisonAgentResult(
                null, null, BigDecimal.ZERO, "无法判断", "人工检查",
                Map.of("passedTests", "12", "failedTests", "not-a-number"),
                Map.of("passedTests", 3L), List.of(), "{}", Map.of());
        when(inputAggregator.aggregate(setup.source, setup.target)).thenReturn(bundle);
        when(agentRuntime.run(org.mockito.ArgumentMatchers.eq(bundle), org.mockito.ArgumentMatchers.any())).thenReturn(result);
        when(storedArtifactService.storeJson(eq(setup.input.projectId()), eq(setup.task.id()),
                eq(StoredArtifactOwnerType.TEST_COMPARISON), eq(setup.input.comparisonRunId()),
                eq("AGENT_OUTPUT"), any(), any(), eq(context)))
                .thenReturn(artifact(setup.input.projectId(), setup.task.id(), setup.input.comparisonRunId()));
        when(repository.findById(setup.input.comparisonRunId())).thenReturn(Optional.empty());

        handler().handle(context);

        verify(automatedTestRepository).updateStats(setup.source.id(), 12, null, null);
        verify(automatedTestRepository).updateStats(setup.target.id(), 3, null, null);
        verify(repository).saveResult(eq(setup.input.comparisonRunId()), eq(null), eq(null), eq(BigDecimal.ZERO),
                eq("无法判断"), eq("人工检查"), any(), any(), eq(true), eq(Map.of("truncated", true)), any());
    }

    private TestComparisonHandler handler() {
        return new TestComparisonHandler(repository, automatedTestRepository, inputAggregator, agentRuntime,
                storedArtifactService, archiveCandidateRegistrar);
    }

    private Setup setup() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID sourceRunId = UUID.randomUUID();
        UUID targetRunId = UUID.randomUUID();
        TestComparisonTaskInput input = new TestComparisonTaskInput(
                projectId,  UUID.randomUUID(),  sourceRunId, targetRunId, selection());
        TaskView task = new TaskView(taskId, projectId, RvPortTaskTypes.TEST_LOG_COMPARE, TaskStatus.RUNNING, 20,
                "running", OWNER_ID, OffsetDateTime.now(), null, OffsetDateTime.now(), null, null,
                null, null, 0);
        AutomatedTestRunView source = run(projectId, sourceRunId, TaskStatus.SUCCEEDED, 0, TestBatchRole.SOURCE);
        AutomatedTestRunView target = run(projectId, targetRunId, TaskStatus.SUCCEEDED, 0, TestBatchRole.TARGET);
        when(context.input(TestComparisonTaskInput.class)).thenReturn(input);
        org.mockito.Mockito.lenient().when(context.task()).thenReturn(task);
        when(automatedTestRepository.findById(sourceRunId)).thenReturn(Optional.of(source));
        org.mockito.Mockito.lenient().when(automatedTestRepository.findById(targetRunId)).thenReturn(Optional.of(target));
        return new Setup(input, task, source, target);
    }

    private AutomatedTestRunView run(UUID projectId, UUID id, TaskStatus status, int exitCode, TestBatchRole role) {
        return new AutomatedTestRunView(id, projectId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1L, UUID.randomUUID(), role, role.name(), "ctest", "/tmp/project/build",
                120, exitCode, null, null, null, status, null, null, null, "summary", List.of(),
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    private StoredArtifactView artifact(UUID projectId, UUID taskId, UUID comparisonRunId) {
        return new StoredArtifactView(UUID.randomUUID(), projectId, taskId, StoredArtifactOwnerType.TEST_COMPARISON,
                comparisonRunId, "AGENT_OUTPUT", "bucket", "key", "application/json", 10L, "sha",
                Map.of(), OffsetDateTime.now());
    }

    private record Setup(
            TestComparisonTaskInput input,
            TaskView task,
            AutomatedTestRunView source,
            AutomatedTestRunView target
    ) {
    }
}
