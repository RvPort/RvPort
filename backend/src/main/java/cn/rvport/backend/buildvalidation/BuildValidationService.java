package cn.rvport.backend.buildvalidation;

import cn.rvport.backend.audit.AuditLogService;
import cn.rvport.backend.common.EnvironmentVariableValidator;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.common.web.PageResponse;
import cn.rvport.backend.environment.EnvironmentRepository;
import cn.rvport.backend.environment.EnvironmentView;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentResponse;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentService;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentView;
import cn.rvport.backend.source.SourceRepository;
import cn.rvport.backend.source.SourceRevisionResponse;
import cn.rvport.backend.source.SourceRevisionView;
import cn.rvport.backend.source.deploy.SourceDeployEnvironmentSummary;
import cn.rvport.backend.source.deploy.SourceDeployRepository;
import cn.rvport.backend.source.deploy.SourceDeployRunView;
import cn.rvport.backend.task.RvPortTaskTypes;
import cn.rvport.backend.task.TaskCreateCommand;
import cn.rvport.backend.task.TaskRepository;
import cn.rvport.backend.task.TaskResponse;
import cn.rvport.backend.task.TaskExecutionException;
import cn.rvport.backend.task.TaskScriptPayloadService;
import cn.rvport.backend.task.TaskScriptPayloadView;
import cn.rvport.backend.task.TaskService;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.workflow.DomainLatestExecutionResponse;
import cn.rvport.backend.workflow.DomainLatestFreshness;
import cn.rvport.backend.workflow.DomainLatestResponse;
import cn.rvport.backend.workflow.ProjectContextService;
import cn.rvport.backend.workflow.ProjectContextSnapshot;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BuildValidationService {

    private static final int DEFAULT_TIMEOUT_SECONDS = 3600;

    private final BuildValidationRepository buildValidationRepository;
    private final SourceDeployRepository sourceDeployRepository;
    private final SourceRepository sourceRepository;
    private final EnvironmentRepository environmentRepository;
    private final ProjectRuntimeEnvironmentService projectRuntimeEnvironmentService;
    private final ProjectAccessService projectAccessService;
    private final TaskService taskService;
    private final TaskRepository taskRepository;
    private final ProjectContextService projectContextService;
    private final TaskScriptPayloadService taskScriptPayloadService;
    private final AuditLogService auditLogService;
    private final EnvironmentVariableValidator environmentVariableValidator;

    @Autowired
    public BuildValidationService(
            BuildValidationRepository buildValidationRepository,
            SourceDeployRepository sourceDeployRepository,
            SourceRepository sourceRepository,
            EnvironmentRepository environmentRepository,
            ProjectRuntimeEnvironmentService projectRuntimeEnvironmentService,
            ProjectAccessService projectAccessService,
            TaskService taskService,
            TaskRepository taskRepository,
            ProjectContextService projectContextService,
            TaskScriptPayloadService taskScriptPayloadService,
            AuditLogService auditLogService,
            EnvironmentVariableValidator environmentVariableValidator
    ) {
        this.buildValidationRepository = buildValidationRepository;
        this.sourceDeployRepository = sourceDeployRepository;
        this.sourceRepository = sourceRepository;
        this.environmentRepository = environmentRepository;
        this.projectRuntimeEnvironmentService = projectRuntimeEnvironmentService;
        this.projectAccessService = projectAccessService;
        this.taskService = taskService;
        this.taskRepository = taskRepository;
        this.projectContextService = projectContextService;
        this.taskScriptPayloadService = taskScriptPayloadService;
        this.auditLogService = auditLogService;
        this.environmentVariableValidator = environmentVariableValidator;
    }

    BuildValidationService(
            BuildValidationRepository buildValidationRepository,
            SourceDeployRepository sourceDeployRepository,
            SourceRepository sourceRepository,
            EnvironmentRepository environmentRepository,
            ProjectRuntimeEnvironmentService projectRuntimeEnvironmentService,
            ProjectAccessService projectAccessService,
            TaskService taskService,
            TaskScriptPayloadService taskScriptPayloadService,
            AuditLogService auditLogService,
            EnvironmentVariableValidator environmentVariableValidator
    ) {
        this(buildValidationRepository, sourceDeployRepository, sourceRepository, environmentRepository,
                projectRuntimeEnvironmentService, projectAccessService, taskService, null, null, taskScriptPayloadService,
                auditLogService, environmentVariableValidator);
    }

    @Transactional
    public BuildValidationCreateResponse create(
            UUID projectId,
            BuildValidationCreateRequest request,
            UUID actorUserId,
            boolean superAdmin,
            HttpServletRequest httpRequest
    ) {
        projectAccessService.requireProjectAccess(projectId, actorUserId, superAdmin);
        validateScript(request.script());
        Map<String, String> environmentVariables = environmentVariableValidator.validate(request.environmentVariables(), "BUILD");
        if (request.sourceDeploymentIds() != null && !request.sourceDeploymentIds().isEmpty()) {
            throw badRequest("BUILD_SOURCE_DEPLOYMENTS_DEPRECATED", "构建环境由项目运行环境配置和成功源码部署记录决定，不能在请求中指定源码部署记录");
        }
        BuildValidationScope buildScope = request.effectiveBuildScope();
        ProjectRuntimeEnvironmentView runtimeEnvironments = buildScope == BuildValidationScope.SOURCE_ONLY
                ? projectRuntimeEnvironmentService.requireSource(projectId, actorUserId, superAdmin)
                : projectRuntimeEnvironmentService.requireComplete(projectId, actorUserId, superAdmin);
        SourceRevisionView sourceRevision = requireRequestedOrActiveSourceRevision(projectId, request.sourceRevisionId());
        EnvironmentView sourceEnvironment = requireEnvironment(projectId, runtimeEnvironments.sourceEnvironmentId());
        List<EnvironmentView> environments = buildScope == BuildValidationScope.SOURCE_ONLY
                ? List.of(sourceEnvironment)
                : List.of(
                        sourceEnvironment,
                        requireEnvironment(projectId, runtimeEnvironments.targetEnvironmentId())
                );

        UUID buildBatchId = UUID.randomUUID();
        List<BuildValidationResponse> builds = environments.stream()
                .map(environment -> createOne(projectId, environment, sourceRevision, runtimeEnvironments.version(), buildScope, buildBatchId, request, environmentVariables, actorUserId, httpRequest))
                .toList();
        return new BuildValidationCreateResponse(builds);
    }

    @Transactional(readOnly = true)
    public PageResponse<BuildValidationResponse> list(UUID projectId, BuildValidationListRequest request, UUID actorUserId, boolean superAdmin) {
        projectAccessService.requireProjectAccess(projectId, actorUserId, superAdmin);
        validateSort(request);
        BuildValidationListRequest effectiveRequest = currentRequest(projectId, request);
        if (effectiveRequest == null) {
            return PageResponse.of(List.of(), request.normalizedPage(), request.normalizedPageSize(), 0);
        }
        long total = buildValidationRepository.count(projectId, effectiveRequest);
        List<BuildValidationResponse> items = buildValidationRepository.list(projectId, effectiveRequest)
                .stream()
                .map(this::response)
                .toList();
        return PageResponse.of(items, effectiveRequest.normalizedPage(), effectiveRequest.normalizedPageSize(), total);
    }

    @Transactional(readOnly = true)
    public BuildValidationResponse get(UUID buildId, UUID actorUserId, boolean superAdmin) {
        BuildValidationRunView run = requireAccessible(buildId, actorUserId, superAdmin);
        return response(run, true);
    }

    @Transactional(readOnly = true)
    public DomainLatestResponse<BuildValidationResponse> latest(
            UUID projectId,
            BuildValidationLatestMode mode,
            UUID actorUserId,
            boolean superAdmin
    ) {
        projectAccessService.requireProjectAccess(projectId, actorUserId, superAdmin);
        ProjectContextSnapshot context = projectContextService.snapshot(projectId);
        ProjectRuntimeEnvironmentResponse summary = projectRuntimeEnvironmentService.summary(projectId);
        ProjectRuntimeEnvironmentView runtime = summary == null
                ? ProjectRuntimeEnvironmentView.empty(projectId)
                : new ProjectRuntimeEnvironmentView(
                        projectId,
                        summary.sourceEnvironment() == null ? null : summary.sourceEnvironment().id(),
                        summary.targetEnvironment() == null ? null : summary.targetEnvironment().id(),
                        summary.version(),
                        summary.updatedBy(),
                        null,
                        summary.updatedAt()
                );
        UUID environmentId = mode == BuildValidationLatestMode.SOURCE_ONLY
                ? runtime.sourceEnvironmentId()
                : null;
        BuildValidationScope scope = mode == BuildValidationLatestMode.SOURCE_ONLY
                ? BuildValidationScope.SOURCE_ONLY
                : BuildValidationScope.BOTH;
        return buildValidationRepository.findLatest(projectId, context.sourceRevisionId(), environmentId, context.runtimeEnvironmentVersion(), scope)
                .map(run -> DomainLatestResponse.current(
                        context,
                        DomainLatestExecutionResponse.from(run.id(), taskRepository.findById(run.taskId()).orElse(null)),
                        response(run, true)))
                .orElseGet(() -> buildValidationRepository.findLatest(projectId, null, null, null, scope)
                        .map(run -> DomainLatestResponse.stale(
                                context,
                                DomainLatestExecutionResponse.from(run.id(), taskRepository.findById(run.taskId()).orElse(null)),
                                response(run, false)))
                        .orElseGet(() -> DomainLatestResponse.empty(context)));
    }

    @Transactional
    public TaskResponse cancel(UUID buildId, UUID actorUserId, boolean superAdmin) {
        BuildValidationRunView run = requireAccessible(buildId, actorUserId, superAdmin);
        TaskResponse task = taskService.cancel(run.taskId(), actorUserId, superAdmin);
        buildValidationRepository.syncStatusByTask(run.taskId(), task.status());
        return task;
    }

    private BuildValidationResponse createOne(
            UUID projectId,
            EnvironmentView environment,
            SourceRevisionView sourceRevision,
            long environmentConfigVersion,
            BuildValidationScope buildScope,
            UUID buildBatchId,
            BuildValidationCreateRequest request,
            Map<String, String> environmentVariables,
            UUID actorUserId,
            HttpServletRequest httpRequest
    ) {
        SourceDeployRunView deployment = sourceDeployRepository
                .findLatestSucceeded(projectId, sourceRevision.id(), environment.id(), environmentConfigVersion)
                .orElse(null);
        UUID sourceDeploymentId = deployment == null ? null : deployment.id();
        String workingDirectory = deployment == null ? remotePath(environment, sourceRevision.id()) : deployment.remotePath();
        UUID buildRunId = UUID.randomUUID();
        UUID scriptPayloadId = UUID.randomUUID();
        String scriptSha256 = sha256(request.script());
        int timeoutSeconds = effectiveTimeout(request.timeoutSeconds());

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("projectId", projectId);
        input.put("buildRunId", buildRunId);
        input.put("buildScope", buildScope.name());
        input.put("buildBatchId", buildBatchId);
        input.put("sourceDeploymentId", sourceDeploymentId);
        input.put("sourceRevisionId", sourceRevision.id());
        input.put("environmentId", environment.id());
        input.put("environmentConfigVersion", environmentConfigVersion);
        input.put("scriptPayloadId", scriptPayloadId);
        input.put("scriptSha256", scriptSha256);
        input.put("scriptLength", request.script().length());
        input.put("timeoutSeconds", timeoutSeconds);
        input.put("environmentVariables", environmentVariables);
        input.put("environmentVariablesMasked", environmentVariableValidator.mask(environmentVariables));

        TaskResponse task = taskService.create(new TaskCreateCommand(
                projectId,
                RvPortTaskTypes.BUILD_VALIDATION,
                input,
                actorUserId,
                timeoutSeconds,
                "构建验证任务已创建"
        ));
        TaskScriptPayloadView payload = taskScriptPayloadService.save(scriptPayloadId, projectId, task.id(), request.script(), actorUserId);
        BuildValidationRunView run = buildScope == BuildValidationScope.BOTH
                ? buildValidationRepository.createPending(
                        buildRunId,
                        projectId,
                        task.id(),
                        sourceRevision.id(),
                        environment.id(),
                        sourceDeploymentId,
                        environmentConfigVersion,
                        payload.id(),
                        payload.scriptSha256(),
                        commandSummary(request.script(), payload.scriptSha256()),
                        workingDirectory,
                        timeoutSeconds,
                        environmentVariableValidator.mask(environmentVariables)
                )
                : buildValidationRepository.createPending(
                        buildRunId,
                        projectId,
                        task.id(),
                        sourceRevision.id(),
                        environment.id(),
                        buildScope,
                        sourceDeploymentId,
                        environmentConfigVersion,
                        payload.id(),
                        payload.scriptSha256(),
                        commandSummary(request.script(), payload.scriptSha256()),
                        workingDirectory,
                        timeoutSeconds,
                        environmentVariableValidator.mask(environmentVariables)
                );
        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("taskId", task.id());
        auditDetails.put("sourceDeploymentId", sourceDeploymentId);
        auditDetails.put("sourceRevisionId", sourceRevision.id());
        auditDetails.put("environmentId", environment.id());
        auditDetails.put("buildScope", buildScope.name());
        auditDetails.put("environmentVariables", environmentVariableValidator.mask(environmentVariables));
        auditLogService.record(projectId, actorUserId, "build.validation.create", "build_run", run.id(),
                auditDetails,
                httpRequest);
        return response(run);
    }

    private SourceRevisionView requireRequestedOrActiveSourceRevision(UUID projectId, UUID requestedSourceRevisionId) {
        if (requestedSourceRevisionId != null) {
            SourceRevisionView revision = sourceRepository.findById(requestedSourceRevisionId)
                    .orElseThrow(() -> notFound("SOURCE_REVISION_NOT_FOUND", "源码版本不存在"));
            if (!Objects.equals(revision.projectId(), projectId)) {
                throw notFound("SOURCE_REVISION_NOT_FOUND", "源码版本不存在");
            }
            return revision;
        }
        return sourceRepository.findActive(projectId)
                .orElseThrow(() -> badRequest("SOURCE_REVISION_REQUIRED", "项目没有当前源码版本"));
    }

    private EnvironmentView requireEnvironment(UUID projectId, UUID environmentId) {
        EnvironmentView environment = environmentRepository.findById(environmentId)
                .orElseThrow(() -> notFound("ENVIRONMENT_NOT_FOUND", "环境不存在"));
        if (!Objects.equals(environment.projectId(), projectId)) {
            throw notFound("ENVIRONMENT_NOT_FOUND", "环境不存在");
        }
        if (!environment.enabled()) {
            throw badRequest("ENVIRONMENT_DISABLED", "环境已禁用");
        }
        return environment;
    }

    private BuildValidationRunView requireAccessible(UUID buildId, UUID actorUserId, boolean superAdmin) {
        BuildValidationRunView run = buildValidationRepository.findById(buildId)
                .orElseThrow(() -> notFound("BUILD_RUN_NOT_FOUND", "构建运行不存在"));
        projectAccessService.requireProjectAccess(run.projectId(), actorUserId, superAdmin);
        return run;
    }

    private BuildValidationResponse response(BuildValidationRunView run) {
        return response(run, false);
    }

    private BuildValidationResponse response(BuildValidationRunView run, boolean includeScript) {
        SourceRevisionResponse sourceRevision = sourceRepository.findById(run.sourceRevisionId())
                .map(SourceRevisionResponse::from)
                .orElse(null);
        SourceDeployEnvironmentSummary environment = environmentRepository.findById(run.environmentId())
                .map(SourceDeployEnvironmentSummary::from)
                .orElse(null);
        long currentVersion = projectRuntimeEnvironmentService.currentVersion(run.projectId());
        String commandSummary = run.command();
        String command = includeScript ? requireScriptForResponse(run) : commandSummary;
        return BuildValidationResponse.from(run, sourceRevision, environment, currentVersion, command, commandSummary);
    }

    private String requireScriptForResponse(BuildValidationRunView run) {
        if (run.scriptPayloadId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "BUILD_SCRIPT_PAYLOAD_UNAVAILABLE", "构建脚本正文不可用");
        }
        try {
            return taskScriptPayloadService.requireScript(
                    run.scriptPayloadId(),
                    run.projectId(),
                    run.taskId(),
                    run.scriptSha256()
            );
        } catch (TaskExecutionException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "BUILD_SCRIPT_PAYLOAD_UNAVAILABLE", "构建脚本正文不可用");
        }
    }

    private BuildValidationListRequest currentRequest(UUID projectId, BuildValidationListRequest request) {
        if (!request.effectiveCurrentOnly()) {
            return request;
        }
        UUID activeSourceRevisionId = sourceRepository.findActive(projectId)
                .map(SourceRevisionView::id)
                .orElse(null);
        if (activeSourceRevisionId == null && request.sourceRevisionId() == null) {
            return null;
        }
        return request.withCurrentContext(activeSourceRevisionId, projectRuntimeEnvironmentService.currentVersion(projectId));
    }

    private void validateScript(String script) {
        if (script == null || script.isBlank()) {
            throw badRequest("BUILD_SCRIPT_REQUIRED", "构建脚本不能为空");
        }
        if (script.length() > 12000) {
            throw badRequest("BUILD_SCRIPT_TOO_LONG", "构建脚本长度不能超过 12000 字符");
        }
    }

    private String commandSummary(String script, String scriptSha256) {
        String firstLine = script.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse("build script");
        if (firstLine.length() > 120) {
            firstLine = firstLine.substring(0, 120);
        }
        return firstLine + " # sha256:" + scriptSha256;
    }

    private String remotePath(EnvironmentView environment, UUID sourceRevisionId) {
        String root = environment.workingDirectory().replace('\\', '/').replaceAll("/+$", "");
        return root + "/rvport-sources/" + sourceRevisionId;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private int effectiveTimeout(Integer timeoutSeconds) {
        return timeoutSeconds == null ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
    }

    private void validateSort(BuildValidationListRequest request) {
        String sortBy = request.sortBy();
        if (sortBy != null && !sortBy.isBlank()
                && !List.of("createdAt", "startedAt", "finishedAt", "status", "environmentId").contains(sortBy.trim())) {
            throw badRequest("BUILD_QUERY_INVALID", "构建验证排序字段不正确");
        }
        String direction = request.sortDirection();
        if (direction != null && !direction.isBlank() && !List.of("asc", "desc").contains(direction.trim().toLowerCase())) {
            throw badRequest("BUILD_QUERY_INVALID", "构建验证排序方向不正确");
        }
    }

    private ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    private ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
