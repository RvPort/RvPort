package cn.rvport.backend.testcomparison;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.archive.ArchiveCandidateRegistrar;
import cn.rvport.backend.artifact.StoredArtifactOwnerType;
import cn.rvport.backend.artifact.StoredArtifactResource;
import cn.rvport.backend.artifact.StoredArtifactService;
import cn.rvport.backend.artifact.StoredArtifactView;
import cn.rvport.backend.automatedtest.AutomatedTestRepository;
import cn.rvport.backend.automatedtest.AutomatedTestRunView;
import cn.rvport.backend.automatedtest.TestBatchRole;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.task.TaskRepository;
import cn.rvport.backend.task.TaskResponse;
import cn.rvport.backend.task.TaskService;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.task.TaskView;
import cn.rvport.backend.testcomparison.runtime.TestComparisonAgentRuntime;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class TestComparisonServiceTests {
    private static final UUID OWNER_ID = UUID.fromString("a0000000-0000-0000-0000-000000000001");

    @Mock private TestComparisonRepository repository;
    @Mock private AutomatedTestRepository automatedTestRepository;
    @Mock private TaskService taskService;
    @Mock private ProjectAccessService projectAccessService;
    @Mock private TestComparisonAgentRuntime agentRuntime;
    @Mock private StoredArtifactService storedArtifactService;
    @Mock private ArchiveCandidateRegistrar archiveCandidateRegistrar;

    private TestComparisonService service;

    @BeforeEach
    void setUp() {
        service = new TestComparisonService(
                repository, automatedTestRepository, taskService, projectAccessService, agentRuntime,
                storedArtifactService, archiveCandidateRegistrar);
    }

    @Test
    void finishedBatchDispatchesComparisonWhenBothSidesAreTerminal() {
        UUID projectId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        AutomatedTestRunView source = testRun(projectId, batchId, TestBatchRole.SOURCE, TaskStatus.SUCCEEDED);
        AutomatedTestRunView target = testRun(projectId, batchId, TestBatchRole.TARGET, TaskStatus.FAILED);
        TestComparisonRunView pending = comparison(projectId, batchId, source.id(), target.id(), TestComparisonStatus.PENDING, null);
        TestComparisonRunView queued = comparison(projectId, batchId, source.id(), target.id(), TestComparisonStatus.QUEUED, UUID.randomUUID());
        when(automatedTestRepository.findById(target.id())).thenReturn(Optional.of(target));
        when(automatedTestRepository.findBatchPeer(target, TestBatchRole.SOURCE)).thenReturn(Optional.of(source));
        when(repository.createIfAbsent(projectId, batchId, source.id(), target.id())).thenReturn(pending);
        when(taskService.getInternal(source.taskId())).thenReturn(taskView(source.taskId(), projectId, creatorId));
        when(taskService.getInternal(target.taskId())).thenReturn(taskView(target.taskId(), projectId, creatorId));
        when(taskService.configurationUserId(source.taskId())).thenReturn(creatorId);
        when(taskService.configurationUserId(target.taskId())).thenReturn(creatorId);
        var profile = new cn.rvport.backend.jooq.tables.records.ModelProfilesRecord(); profile.setId(UUID.randomUUID());
        var runtime = new cn.rvport.backend.jooq.tables.records.AgentRuntimesRecord(); runtime.setId(UUID.randomUUID());
        when(agentRuntime.resolveRunnableConfig(creatorId)).thenReturn(new cn.rvport.backend.agentruntime.AgentModelRuntimeConfig(runtime, profile, null));
        when(taskService.create(any())).thenReturn(task(projectId));
        when(repository.findById(pending.id())).thenReturn(Optional.of(queued));

        service.onAutomatedTestFinished(target.id());

        verify(repository).markQueued(org.mockito.ArgumentMatchers.eq(pending.id()), any());
        verify(taskService).create(any());
    }

    @Test
    void finishedHookIgnoresIncompleteMissingOrUnpairedRuns() {
        UUID id = UUID.randomUUID();
        service.onAutomatedTestFinished(id);

        AutomatedTestRunView noBatch = testRun(UUID.randomUUID(), null, null, TaskStatus.SUCCEEDED);
        when(automatedTestRepository.findById(id)).thenReturn(Optional.of(noBatch));
        service.onAutomatedTestFinished(id);

        UUID batchId = UUID.randomUUID();
        AutomatedTestRunView running = testRun(UUID.randomUUID(), batchId, TestBatchRole.SOURCE, TaskStatus.RUNNING);
        when(automatedTestRepository.findById(id)).thenReturn(Optional.of(running));
        service.onAutomatedTestFinished(id);

        AutomatedTestRunView source = testRun(UUID.randomUUID(), batchId, TestBatchRole.SOURCE, TaskStatus.TIMEOUT);
        when(automatedTestRepository.findById(id)).thenReturn(Optional.of(source));
        when(automatedTestRepository.findBatchPeer(source, TestBatchRole.TARGET)).thenReturn(Optional.empty());
        service.onAutomatedTestFinished(id);

        AutomatedTestRunView peerRunning = testRun(source.projectId(), batchId, TestBatchRole.TARGET, TaskStatus.QUEUED);
        when(automatedTestRepository.findBatchPeer(source, TestBatchRole.TARGET)).thenReturn(Optional.of(peerRunning));
        service.onAutomatedTestFinished(id);

        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"missing", "different", "legacy", "peer-legacy", "source-project", "target-project", "deleted", "no-actor"})
    void inconsistentOrLegacyBatchIdentityNeverCallsModel(String scenario) {
        UUID projectId = UUID.randomUUID(), batchId = UUID.randomUUID();
        var source = testRun(projectId, batchId, TestBatchRole.SOURCE, TaskStatus.SUCCEEDED);
        var target = testRun(projectId, batchId, TestBatchRole.TARGET, TaskStatus.SUCCEEDED);
        var pending = comparison(projectId, batchId, source.id(), target.id(), TestComparisonStatus.PENDING, null);
        var unavailable = comparison(projectId, batchId, source.id(), target.id(), TestComparisonStatus.UNAVAILABLE, null);
        when(automatedTestRepository.findById(source.id())).thenReturn(Optional.of(source));
        when(automatedTestRepository.findBatchPeer(source, TestBatchRole.TARGET)).thenReturn(Optional.of(target));
        if (scenario.equals("deleted")) {
            when(taskService.getInternal(source.taskId())).thenThrow(new ApiException(HttpStatus.NOT_FOUND,"TASK_NOT_FOUND","missing"));
        } else {
            when(taskService.getInternal(source.taskId())).thenReturn(scenario.equals("missing") ? null :
                    taskView(source.taskId(), scenario.equals("source-project") ? UUID.randomUUID() : projectId,
                            scenario.equals("no-actor") ? null : OWNER_ID));
            when(taskService.getInternal(target.taskId())).thenReturn(taskView(target.taskId(),
                    scenario.equals("target-project") ? UUID.randomUUID() : projectId,
                    scenario.equals("different") ? UUID.randomUUID() : OWNER_ID));
        }
        if (scenario.equals("legacy") || scenario.equals("peer-legacy")) {
            when(taskService.configurationUserId(source.taskId())).thenReturn(scenario.equals("legacy") ? null : OWNER_ID);
            if (scenario.equals("peer-legacy")) { when(taskService.configurationUserId(target.taskId())).thenReturn(null); }
        }
        when(repository.createIfAbsent(projectId, batchId, source.id(), target.id())).thenReturn(pending);
        when(repository.findById(pending.id())).thenReturn(Optional.of(unavailable));
        service.onAutomatedTestFinished(source.id());
        verify(repository).markUnavailable(org.mockito.ArgumentMatchers.eq(pending.id()),
                org.mockito.ArgumentMatchers.eq("TEST_COMPARE_MODEL_NOT_CONFIGURED"), org.mockito.ArgumentMatchers.contains("Trusted model execution identity"));
        org.mockito.Mockito.verifyNoInteractions(agentRuntime);
        verify(taskService, org.mockito.Mockito.never()).create(any());
        verify(automatedTestRepository, org.mockito.Mockito.never()).updateStats(any(), any(), any(), any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = TestComparisonStatus.class,
            names = {"SUCCEEDED", "RUNNING", "QUEUED", "FAILED", "CANCELLED", "TIMEOUT", "UNAVAILABLE"})
    void finishedHookDoesNotRedispatchAnExistingComparison(TestComparisonStatus status) {
        UUID projectId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        AutomatedTestRunView source = testRun(projectId, batchId, TestBatchRole.SOURCE, TaskStatus.SUCCEEDED);
        AutomatedTestRunView target = testRun(projectId, batchId, TestBatchRole.TARGET, TaskStatus.CANCELLED);
        TestComparisonRunView existing = comparison(projectId, batchId, source.id(), target.id(), status, UUID.randomUUID());
        when(automatedTestRepository.findById(source.id())).thenReturn(Optional.of(source));
        when(automatedTestRepository.findBatchPeer(source, TestBatchRole.TARGET)).thenReturn(Optional.of(target));
        when(taskService.getInternal(source.taskId())).thenReturn(taskView(source.taskId(), projectId, OWNER_ID));
        when(taskService.getInternal(target.taskId())).thenReturn(taskView(target.taskId(), projectId, OWNER_ID));
        when(taskService.configurationUserId(source.taskId())).thenReturn(OWNER_ID);
        when(taskService.configurationUserId(target.taskId())).thenReturn(OWNER_ID);
        when(repository.createIfAbsent(projectId, batchId, source.id(), target.id())).thenReturn(existing);

        service.onAutomatedTestFinished(source.id());

        verify(repository).createIfAbsent(projectId, batchId, source.id(), target.id());
        org.mockito.Mockito.verifyNoInteractions(agentRuntime, archiveCandidateRegistrar);
        verify(taskService, org.mockito.Mockito.never()).create(any());
        verify(repository, org.mockito.Mockito.never()).markQueued(any(), any());
    }

    @Test
    void modelConfigurationFailureMarksUnavailableAndRegistersArchiveCandidate() {
        UUID projectId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        AutomatedTestRunView source = testRun(projectId, batchId, TestBatchRole.SOURCE, TaskStatus.SUCCEEDED);
        AutomatedTestRunView target = testRun(projectId, batchId, TestBatchRole.TARGET, TaskStatus.SUCCEEDED);
        TestComparisonRunView pending = comparison(projectId, batchId, source.id(), target.id(), TestComparisonStatus.PENDING, null);
        TestComparisonRunView unavailable = comparison(projectId, batchId, source.id(), target.id(), TestComparisonStatus.UNAVAILABLE, null);
        when(automatedTestRepository.findById(source.id())).thenReturn(Optional.of(source));
        when(automatedTestRepository.findBatchPeer(source, TestBatchRole.TARGET)).thenReturn(Optional.of(target));
        when(taskService.getInternal(source.taskId())).thenReturn(taskView(source.taskId(), projectId, OWNER_ID));
        when(taskService.getInternal(target.taskId())).thenReturn(taskView(target.taskId(), projectId, OWNER_ID));
        when(taskService.configurationUserId(source.taskId())).thenReturn(OWNER_ID);
        when(taskService.configurationUserId(target.taskId())).thenReturn(OWNER_ID);
        when(repository.createIfAbsent(projectId, batchId, source.id(), target.id())).thenReturn(pending);
        when(agentRuntime.resolveRunnableConfig(org.mockito.ArgumentMatchers.any())).thenThrow(new ApiException(HttpStatus.BAD_REQUEST, "NO_MODEL", "未配置模型"));
        when(repository.findById(pending.id())).thenReturn(Optional.of(unavailable));

        service.onAutomatedTestFinished(source.id());

        verify(repository).markUnavailable(pending.id(), "TEST_COMPARE_MODEL_NOT_CONFIGURED", "未配置模型");
        verify(archiveCandidateRegistrar).registerTestComparison(
                org.mockito.ArgumentMatchers.eq(unavailable), org.mockito.ArgumentMatchers.eq(source.sourceRevisionId()),
                any(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void listAndFindingsUseDefaultsAndExplicitQueries() {
        UUID projectId = UUID.randomUUID();
        TestComparisonRunView run = comparison(projectId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), TestComparisonStatus.SUCCEEDED, null);
        when(repository.count(org.mockito.ArgumentMatchers.eq(projectId), any())).thenReturn(1L);
        when(repository.list(org.mockito.ArgumentMatchers.eq(projectId), any())).thenReturn(List.of(run));

        var list = service.list(projectId, null, UUID.randomUUID(), false);
        assertThat(list.page()).isEqualTo(1);
        assertThat(list.items()).hasSize(1);
        assertThat(service.list(projectId, new TestComparisonListRequest(
                null, null, null, null, null, 2, 10, null, null), UUID.randomUUID(), false).page())
                .isEqualTo(2);

        when(repository.findById(run.id())).thenReturn(Optional.of(run));
        when(repository.countFindings(org.mockito.ArgumentMatchers.eq(run.id()), any())).thenReturn(0L);
        when(repository.listFindings(org.mockito.ArgumentMatchers.eq(run.id()), any())).thenReturn(List.of());
        assertThat(service.findings(run.id(), null, UUID.randomUUID(), false).pageSize()).isEqualTo(50);
        assertThat(service.findings(run.id(), new TestComparisonFindingListRequest(
                null, null, 2, 10, null, null), UUID.randomUUID(), false).page()).isEqualTo(2);
        assertThat(service.get(run.id(), UUID.randomUUID(), false).id()).isEqualTo(run.id());
    }

    @Test
    void cancellationRequiresTaskAndDelegatesWhenPresent() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        TestComparisonRunView withoutTask = comparison(projectId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), TestComparisonStatus.PENDING, null);
        when(repository.findById(withoutTask.id())).thenReturn(Optional.of(withoutTask));
        assertCode(() -> service.cancel(withoutTask.id(), actorId, false),
                "TEST_COMPARISON_NOT_CANCELLABLE", HttpStatus.BAD_REQUEST);

        TestComparisonRunView running = comparison(projectId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), TestComparisonStatus.RUNNING, UUID.randomUUID());
        TaskResponse cancelled = task(projectId);
        when(repository.findById(running.id())).thenReturn(Optional.of(running));
        when(taskService.cancel(running.taskId(), actorId, true)).thenReturn(cancelled);
        assertThat(service.cancel(running.id(), actorId, true)).isEqualTo(cancelled);
    }

    @Test
    void downloadsAgentOutputAndHandlesMissingOutputOrComparison() {
        UUID projectId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();
        TestComparisonRunView run = comparison(projectId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), TestComparisonStatus.SUCCEEDED, UUID.randomUUID(), artifactId);
        StoredArtifactView artifact = new StoredArtifactView(
                artifactId, projectId, run.taskId(), StoredArtifactOwnerType.TEST_COMPARISON, run.id(), "AGENT_OUTPUT",
                "bucket", "key", "application/json", 2, "sha", Map.of(), OffsetDateTime.now());
        when(repository.findById(run.id())).thenReturn(Optional.of(run));
        when(storedArtifactService.download(artifactId)).thenReturn(
                new StoredArtifactResource(artifact, new ByteArrayResource("{}".getBytes())));

        var download = service.downloadAgentOutput(run.id(), UUID.randomUUID(), false);
        assertThat(download.filename()).isEqualTo("test-comparison-agent-output-" + run.id() + ".json");
        assertThat(download.contentType()).isEqualTo("application/json");

        TestComparisonRunView missing = comparison(projectId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), TestComparisonStatus.SUCCEEDED, null);
        when(repository.findById(missing.id())).thenReturn(Optional.of(missing));
        assertCode(() -> service.downloadAgentOutput(missing.id(), UUID.randomUUID(), false),
                "TEST_COMPARE_AGENT_OUTPUT_NOT_FOUND", HttpStatus.NOT_FOUND);
        assertCode(() -> service.get(UUID.randomUUID(), UUID.randomUUID(), false),
                "TEST_COMPARISON_NOT_FOUND", HttpStatus.NOT_FOUND);
    }

    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code, HttpStatus status) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.code()).isEqualTo(code);
            assertThat(ex.status()).isEqualTo(status);
        });
    }

    private AutomatedTestRunView testRun(UUID projectId, UUID batchId, TestBatchRole role, TaskStatus status) {
        return new AutomatedTestRunView(
                UUID.randomUUID(), projectId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1L, batchId, role, role == null ? null : role.name(), "ctest", "/work", 60, 0, 1, 0, 0,
                status, null, null, null, null, List.of(), OffsetDateTime.now(), OffsetDateTime.now());
    }

    private TestComparisonRunView comparison(
            UUID projectId, UUID batchId, UUID sourceId, UUID targetId, TestComparisonStatus status, UUID taskId
    ) {
        return comparison(projectId, batchId, sourceId, targetId, status, taskId, null);
    }

    private TestComparisonRunView comparison(
            UUID projectId, UUID batchId, UUID sourceId, UUID targetId, TestComparisonStatus status, UUID taskId,
            UUID artifactId
    ) {
        return new TestComparisonRunView(
                UUID.randomUUID(), projectId, taskId, batchId, sourceId, targetId, status, null, null,
                BigDecimal.ZERO, null, null, null, null, null, null, false, Map.of(), Map.of(), Map.of(), artifactId,
                OffsetDateTime.now(), null, null, OffsetDateTime.now());
    }

    private TaskResponse task(UUID projectId) {
        return new TaskResponse(
                UUID.randomUUID(), projectId, "TEST_LOG_COMPARE", TaskStatus.PENDING, 0, "created",
                OffsetDateTime.now(), null, null, null, null, null, Map.of());
    }

    private TaskView taskView(UUID taskId, UUID projectId, UUID creatorId) {
        return new TaskView(
                taskId, projectId, "AUTOMATED_TEST", TaskStatus.SUCCEEDED, 100, "done", creatorId,
                OffsetDateTime.now(), null, null, OffsetDateTime.now(), 60, null, null, 0);
    }
}
