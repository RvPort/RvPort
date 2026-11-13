package cn.rvport.backend.automatedtest;

import cn.rvport.backend.audit.AuditLogService;
import cn.rvport.backend.buildvalidation.BuildValidationRepository;
import cn.rvport.backend.buildvalidation.BuildValidationRunView;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.common.web.PageResponse;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentService;
import cn.rvport.backend.task.RvPortTaskTypes;
import cn.rvport.backend.task.TaskCreateCommand;
import cn.rvport.backend.task.TaskRepository;
import cn.rvport.backend.task.TaskResponse;
import cn.rvport.backend.task.TaskService;
import cn.rvport.backend.testcomparison.TestComparisonRepository;
import cn.rvport.backend.workflow.DomainLatestExecutionResponse;
import cn.rvport.backend.workflow.DomainLatestResponse;
import cn.rvport.backend.workflow.ProjectContextService;
import cn.rvport.backend.workflow.ProjectContextSnapshot;
import cn.rvport.backend.workflow.StageIds;
import cn.rvport.backend.workflow.StagePrerequisiteKeys;
import cn.rvport.backend.workflow.StageReadinessService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AutomatedTestService {

    private static final int DEFAULT_TIMEOUT_SECONDS = 1800;

    private final AutomatedTestRepository repository;
    private final AutomatedTestInputValidator validator;
    private final BuildValidationRepository buildValidationRepository;
    private final ProjectAccessService projectAccessService;
    private final ProjectRuntimeEnvironmentService projectRuntimeEnvironmentService;
    private final TaskService taskService;
    private final TaskRepository taskRepository;
    private final ProjectContextService projectContextService;
    private final StageReadinessService stageReadinessService;
    private final TestComparisonRepository testComparisonRepository;
    private final AuditLogService auditLogService;

    @Autowired
    public AutomatedTestService(
            AutomatedTestRepository repository,
            AutomatedTestInputValidator validator,
            BuildValidationRepository buildValidationRepository,
            ProjectAccessService projectAccessService,
            ProjectRuntimeEnvironmentService projectRuntimeEnvironmentService,
            TaskService taskService,
            TaskRepository taskRepository,
            ProjectContextService projectContextService,
            StageReadinessService stageReadinessService,
            TestComparisonRepository testComparisonRepository,
            AuditLogService auditLogService
    ) {
        this.repository = repository;
        this.validator = validator;
        this.buildValidationRepository = buildValidationRepository;
        this.projectAccessService = projectAccessService;
        this.projectRuntimeEnvironmentService = projectRuntimeEnvironmentService;
        this.taskService = taskService;
        this.taskRepository = taskRepository;
        this.projectContextService = projectContextService;
        this.stageReadinessService = stageReadinessService;
        this.testComparisonRepository = testComparisonRepository;
        this.auditLogService = auditLogService;
    }

    AutomatedTestService(
            AutomatedTestRepository repository,
            AutomatedTestInputValidator validator,
            BuildValidationRepository buildValidationRepository,
            ProjectAccessService projectAccessService,
            ProjectRuntimeEnvironmentService projectRuntimeEnvironmentService,
            TaskService taskService,
            TestComparisonRepository testComparisonRepository,
            AuditLogService auditLogService
    ) {
        this(repository, validator, buildValidationRepository, projectAccessService, projectRuntimeEnvironmentService,
                taskService, null, null, null, testComparisonRepository, auditLogService);
    }

    @Transactional
    public TaskResponse create(UUID projectId, AutomatedTestRequest request, UUID actorUserId, boolean superAdmin, HttpServletRequest httpRequest) {
        if (request == null) {
            throw badRequest("TEST_REQUEST_REQUIRED", "自动化测试请求不能为空");
        }
        projectAccessService.requireProjectAccess(projectId, actorUserId, superAdmin);
        if (request.environmentId() != null) {
            throw badRequest("TEST_ENVIRONMENT_SELECTION_DEPRECATED", "自动化测试环境由关联构建记录决定，不能在请求中指定");
        }
        if (request.buildRunId() == null) {
            throw badRequest("BUILD_RUN_REQUIRED", "自动化测试必须关联构建记录");
        }
        if (stageReadinessService != null) {
            stageReadinessService.requireSatisfied(
                    projectId,
                    StageIds.TEST,
                    List.of(StagePrerequisiteKeys.BUILD_VALIDATION),
                    actorUserId,
                    superAdmin
            );
        }
        BuildValidationRunView buildRun = buildValidationRepository.findById(request.buildRunId())
                .orElseThrow(() -> notFound("BUILD_RUN_NOT_FOUND", "构建记录不存在"));
        if (!buildRun.projectId().equals(projectId)) {
            throw notFound("BUILD_RUN_NOT_FOUND", "构建记录不存在");
        }
        if (request.sourceRevisionId() != null && !request.sourceRevisionId().equals(buildRun.sourceRevisionId())) {
            throw badRequest("TEST_SOURCE_REVISION_MISMATCH", "源码版本与构建记录不一致");
        }
        if (buildRun.status() != cn.rvport.backend.task.TaskStatus.SUCCEEDED) {
            throw badRequest("BUILD_RUN_NOT_SUCCEEDED", "自动化测试必须关联成功的构建记录");
        }
        long currentEnvironmentVersion = projectRuntimeEnvironmentService.currentVersion(projectId);
        if (stageReadinessService != null && (buildRun.environmentConfigVersion() == null || buildRun.environmentConfigVersion() != currentEnvironmentVersion)) {
            throw badRequest("BUILD_RUN_STALE", "构建记录已不属于当前运行环境配置");
        }
        Map<String, String> envVars = validator.validateEnvironmentVariables(request.environmentVariables());
        var reportTargets = validator.validateReportTargets(request.reportTargets());
        int timeout = request.timeoutSeconds() == null ? DEFAULT_TIMEOUT_SECONDS : request.timeoutSeconds();
        String workingDirectory = blankToNull(request.workingDirectory());

        Map<String, Object> inputSnapshot = new LinkedHashMap<>();
        inputSnapshot.put("projectId", projectId);
        inputSnapshot.put("buildRunId", request.buildRunId());
        inputSnapshot.put("sourceRevisionId", buildRun.sourceRevisionId());
        inputSnapshot.put("environmentId", buildRun.environmentId());
        inputSnapshot.put("environmentConfigVersion", buildRun.environmentConfigVersion());
        UUID testBatchId = request.testBatchId() == null ? UUID.randomUUID() : request.testBatchId();
        TestBatchRole testBatchRole = request.testBatchRole() == null
                ? inferBatchRole(projectId, buildRun.environmentId())
                : request.testBatchRole();
        inputSnapshot.put("testBatchId", testBatchId);
        inputSnapshot.put("configurationUserId", actorUserId);
        inputSnapshot.put("testBatchRole", testBatchRole == null ? null : testBatchRole.name());
        inputSnapshot.put("command", request.command());
        inputSnapshot.put("workingDirectory", workingDirectory);
        inputSnapshot.put("timeoutSeconds", timeout);
        inputSnapshot.put("environmentVariables", envVars);
        inputSnapshot.put("reportTargets", reportTargets);

        TaskResponse task = taskService.create(new TaskCreateCommand(
                projectId,
                RvPortTaskTypes.AUTOMATED_TEST,
                inputSnapshot,
                actorUserId,
                timeout,
                "自动化测试任务已创建"
        ));
        AutomatedTestInput runInput = new AutomatedTestInput(
                projectId,
                null,
                request.buildRunId(),
                buildRun.sourceRevisionId(),
                buildRun.environmentId(),
                buildRun.environmentConfigVersion(),
                testBatchId,
                testBatchRole,
                request.command(),
                workingDirectory,
                timeout,
                envVars,
                reportTargets,
                actorUserId
        );
        UUID testRunId = repository.createPending(runInput, task.id());
        auditLogService.record(projectId, actorUserId, "automated-test.create", "test_run", testRunId,
                Map.of(
                        "taskId", task.id(),
                        "sourceRevisionId", buildRun.sourceRevisionId(),
                        "environmentId", buildRun.environmentId(),
                        "environmentConfigVersion", buildRun.environmentConfigVersion(),
                        "testBatchId", testBatchId,
                        "testBatchRole", testBatchRole == null ? "" : testBatchRole.name(),
                        "environmentVariables", validator.maskedEnvironmentVariables(envVars)
                ), httpRequest);
        return task;
    }

    private TestBatchRole inferBatchRole(UUID projectId, UUID environmentId) {
        var runtime = projectRuntimeEnvironmentService.summary(projectId);
        if (runtime == null) {
            return null;
        }
        if (environmentId != null && runtime.sourceEnvironment() != null && environmentId.equals(runtime.sourceEnvironment().id())) {
            return TestBatchRole.SOURCE;
        }
        if (environmentId != null && runtime.targetEnvironment() != null && environmentId.equals(runtime.targetEnvironment().id())) {
            return TestBatchRole.TARGET;
        }
        return null;
    }

    @Transactional(readOnly = true)
    public PageResponse<AutomatedTestResponse> list(UUID projectId, AutomatedTestListRequest request, UUID actorUserId, boolean superAdmin) {
        projectAccessService.requireProjectAccess(projectId, actorUserId, superAdmin);
        AutomatedTestListRequest query = request == null ? new AutomatedTestListRequest(null, null, null, 1, 20, null, null) : request;
        long total = repository.count(projectId, query);
        return PageResponse.of(
                repository.list(projectId, query).stream()
                        .map(run -> AutomatedTestResponse.from(
                                run,
                                projectRuntimeEnvironmentService.currentVersion(projectId),
                                testComparisonRepository.findByTestRunId(run.id()).orElse(null)
                        ))
                        .toList(),
                query.normalizedPage(),
                query.normalizedPageSize(),
                total
        );
    }

    @Transactional(readOnly = true)
    public AutomatedTestResponse get(UUID testRunId, UUID actorUserId, boolean superAdmin) {
        AutomatedTestRunView run = repository.findById(testRunId)
                .orElseThrow(() -> notFound("TEST_RUN_NOT_FOUND", "自动化测试记录不存在"));
        projectAccessService.requireProjectAccess(run.projectId(), actorUserId, superAdmin);
        return AutomatedTestResponse.from(
                run,
                projectRuntimeEnvironmentService.currentVersion(run.projectId()),
                testComparisonRepository.findByTestRunId(run.id()).orElse(null)
        );
    }

    @Transactional(readOnly = true)
    public DomainLatestResponse<AutomatedTestResponse> latest(UUID projectId, UUID actorUserId, boolean superAdmin) {
        projectAccessService.requireProjectAccess(projectId, actorUserId, superAdmin);
        ProjectContextSnapshot context = projectContextService.snapshot(projectId);
        return repository.findLatest(projectId, context.sourceRevisionId(), context.runtimeEnvironmentVersion())
                .map(run -> DomainLatestResponse.current(
                        context,
                        DomainLatestExecutionResponse.from(run.id(), taskRepository.findById(run.taskId()).orElse(null)),
                        AutomatedTestResponse.from(run, context.runtimeEnvironmentVersion(), testComparisonRepository.findByTestRunId(run.id()).orElse(null))))
                .orElseGet(() -> repository.findLatest(projectId, null, null)
                        .map(run -> DomainLatestResponse.stale(
                                context,
                                DomainLatestExecutionResponse.from(run.id(), taskRepository.findById(run.taskId()).orElse(null)),
                                AutomatedTestResponse.from(run, context.runtimeEnvironmentVersion(), testComparisonRepository.findByTestRunId(run.id()).orElse(null))))
                        .orElseGet(() -> DomainLatestResponse.empty(context)));
    }

    @Transactional
    public TaskResponse cancel(UUID testRunId, UUID actorUserId, boolean superAdmin) {
        AutomatedTestRunView run = repository.findById(testRunId)
                .orElseThrow(() -> notFound("TEST_RUN_NOT_FOUND", "自动化测试记录不存在"));
        projectAccessService.requireProjectAccess(run.projectId(), actorUserId, superAdmin);
        return taskService.cancel(run.taskId(), actorUserId, superAdmin);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }
}
