package cn.rvport.backend.testcomparison;

import cn.rvport.backend.archive.ArchiveCandidateRegistrar;
import cn.rvport.backend.artifact.StoredArtifactService;
import cn.rvport.backend.automatedtest.AutomatedTestRepository;
import cn.rvport.backend.automatedtest.AutomatedTestRunView;
import cn.rvport.backend.automatedtest.TestBatchRole;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.common.web.PageResponse;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.task.RvPortTaskTypes;
import cn.rvport.backend.task.TaskCreateCommand;
import cn.rvport.backend.task.TaskResponse;
import cn.rvport.backend.task.TaskService;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.testcomparison.runtime.TestComparisonAgentRuntime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TestComparisonService {

    private static final String MODEL_NOT_CONFIGURED = "TEST_COMPARE_MODEL_NOT_CONFIGURED";

    private final TestComparisonRepository repository;
    private final AutomatedTestRepository automatedTestRepository;
    private final TaskService taskService;
    private final ProjectAccessService projectAccessService;
    private final TestComparisonAgentRuntime agentRuntime;
    private final StoredArtifactService storedArtifactService;
    private final ArchiveCandidateRegistrar archiveCandidateRegistrar;

    public TestComparisonService(
            TestComparisonRepository repository,
            AutomatedTestRepository automatedTestRepository,
            TaskService taskService,
            ProjectAccessService projectAccessService,
            TestComparisonAgentRuntime agentRuntime,
            StoredArtifactService storedArtifactService,
            ArchiveCandidateRegistrar archiveCandidateRegistrar
    ) {
        this.repository = repository;
        this.automatedTestRepository = automatedTestRepository;
        this.taskService = taskService;
        this.projectAccessService = projectAccessService;
        this.agentRuntime = agentRuntime;
        this.storedArtifactService = storedArtifactService;
        this.archiveCandidateRegistrar = archiveCandidateRegistrar;
    }

    @Transactional
    public void onAutomatedTestFinished(UUID testRunId) {
        AutomatedTestRunView run = automatedTestRepository.findById(testRunId).orElse(null);
        if (run == null || run.testBatchId() == null || run.testBatchRole() == null || !terminal(run.status())) {
            return;
        }
        TestBatchRole peerRole = run.testBatchRole() == TestBatchRole.SOURCE ? TestBatchRole.TARGET : TestBatchRole.SOURCE;
        AutomatedTestRunView peer = automatedTestRepository.findBatchPeer(run, peerRole).orElse(null);
        if (peer == null || !terminal(peer.status())) {
            return;
        }
        AutomatedTestRunView source = run.testBatchRole() == TestBatchRole.SOURCE ? run : peer;
        AutomatedTestRunView target = run.testBatchRole() == TestBatchRole.TARGET ? run : peer;
        createOrDispatch(source.projectId(), source, target, taskCreator(source, target));
    }

    @Transactional(readOnly = true)
    public PageResponse<TestComparisonResponse> list(UUID projectId, TestComparisonListRequest request, UUID actorUserId, boolean superAdmin) {
        projectAccessService.requireProjectAccess(projectId, actorUserId, superAdmin);
        TestComparisonListRequest query = request == null ? new TestComparisonListRequest(null, null, null, null, null, 1, 20, null, null) : request;
        long total = repository.count(projectId, query);
        return PageResponse.of(repository.list(projectId, query).stream().map(TestComparisonResponse::from).toList(),
                query.normalizedPage(), query.normalizedPageSize(), total);
    }

    @Transactional(readOnly = true)
    public TestComparisonResponse get(UUID comparisonId, UUID actorUserId, boolean superAdmin) {
        TestComparisonRunView run = require(comparisonId);
        projectAccessService.requireProjectAccess(run.projectId(), actorUserId, superAdmin);
        return TestComparisonResponse.from(run);
    }

    @Transactional(readOnly = true)
    public PageResponse<TestComparisonFindingResponse> findings(UUID comparisonId, TestComparisonFindingListRequest request, UUID actorUserId, boolean superAdmin) {
        TestComparisonRunView run = require(comparisonId);
        projectAccessService.requireProjectAccess(run.projectId(), actorUserId, superAdmin);
        TestComparisonFindingListRequest query = request == null ? new TestComparisonFindingListRequest(null, null, 1, 50, null, null) : request;
        long total = repository.countFindings(comparisonId, query);
        return PageResponse.of(repository.listFindings(comparisonId, query).stream().map(TestComparisonFindingResponse::from).toList(),
                query.normalizedPage(), query.normalizedPageSize(), total);
    }

    @Transactional
    public TaskResponse cancel(UUID comparisonId, UUID actorUserId, boolean superAdmin) {
        TestComparisonRunView run = require(comparisonId);
        projectAccessService.requireProjectAccess(run.projectId(), actorUserId, superAdmin);
        if (run.taskId() == null) {
            throw badRequest("TEST_COMPARISON_NOT_CANCELLABLE", "当前测试结果比较没有可取消任务");
        }
        return taskService.cancel(run.taskId(), actorUserId, superAdmin);
    }

    @Transactional(readOnly = true)
    public AgentOutputDownload downloadAgentOutput(UUID comparisonId, UUID actorUserId, boolean superAdmin) {
        TestComparisonRunView run = require(comparisonId);
        projectAccessService.requireProjectAccess(run.projectId(), actorUserId, superAdmin);
        if (run.agentOutputArtifactId() == null) {
            throw notFound("TEST_COMPARE_AGENT_OUTPUT_NOT_FOUND", "比较原始输出不存在");
        }
        var resource = storedArtifactService.download(run.agentOutputArtifactId());
        return new AgentOutputDownload(resource.resource(), "test-comparison-agent-output-" + comparisonId + ".json", resource.artifact().contentType());
    }

    private TestComparisonRunView createOrDispatch(UUID projectId, AutomatedTestRunView source, AutomatedTestRunView target, UUID actorUserId) {
        TestComparisonRunView comparison = repository.createIfAbsent(projectId, source.testBatchId(), source.id(), target.id());
        if (comparison.status() != TestComparisonStatus.PENDING) {
            return comparison;
        }
        cn.rvport.backend.agentruntime.AgentModelRuntimeConfig config;
        try {
            if (actorUserId == null) { throw cn.rvport.backend.modelprofile.ModelExecutionSelection.missing(); }
            config = agentRuntime.resolveRunnableConfig(actorUserId);
        } catch (ApiException ex) {
            repository.markUnavailable(comparison.id(), MODEL_NOT_CONFIGURED, ex.getMessage());
            TestComparisonRunView unavailable = require(comparison.id());
            archiveCandidateRegistrar.registerTestComparison(unavailable, source.sourceRevisionId(), Map.of(
                    "status", unavailable.status().name(),
                    "unavailableCode", MODEL_NOT_CONFIGURED,
                    "unavailableMessage", ex.getMessage()
            ), null);
            return unavailable;
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("modelExecution", cn.rvport.backend.modelprofile.ModelExecutionSelection.from(actorUserId, config));
        input.put("projectId", projectId);
        input.put("comparisonRunId", comparison.id());
        input.put("sourceTestRunId", source.id());
        input.put("targetTestRunId", target.id());
        TaskResponse task = taskService.create(new TaskCreateCommand(
                projectId,
                RvPortTaskTypes.TEST_LOG_COMPARE,
                input,
                actorUserId,
                null,
                "测试结果比较任务已创建"
        ));
        repository.markQueued(comparison.id(), task.id());
        return require(comparison.id());
    }

    private UUID taskCreator(AutomatedTestRunView source, AutomatedTestRunView target) {
        if (source.taskId() == null || target.taskId() == null) { return null; }
        try {
            var sourceTask = taskService.getInternal(source.taskId());
            var targetTask = taskService.getInternal(target.taskId());
            if (sourceTask == null || targetTask == null
                    || !source.projectId().equals(target.projectId())
                    || !source.projectId().equals(sourceTask.projectId())
                    || !target.projectId().equals(targetTask.projectId())) { return null; }
            UUID actor = sourceTask.createdBy();
            return actor != null && actor.equals(targetTask.createdBy())
                    && actor.equals(taskService.configurationUserId(source.taskId()))
                    && actor.equals(taskService.configurationUserId(target.taskId())) ? actor : null;
        } catch (ApiException ex) {
            return null;
        }
    }

    private boolean terminal(TaskStatus status) {
        return List.of(TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.CANCELLED, TaskStatus.TIMEOUT).contains(status);
    }

    private TestComparisonRunView require(UUID comparisonId) {
        return repository.findById(comparisonId)
                .orElseThrow(() -> notFound("TEST_COMPARISON_NOT_FOUND", "测试结果比较记录不存在"));
    }

    private ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    private ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public record AgentOutputDownload(Resource resource, String filename, String contentType) {
    }
}
