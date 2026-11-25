package cn.rvport.backend.project.runtime;

import cn.rvport.backend.audit.AuditLogService;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.environment.EnvironmentRepository;
import cn.rvport.backend.environment.EnvironmentRole;
import cn.rvport.backend.environment.EnvironmentView;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.task.TaskEventResource;
import cn.rvport.backend.task.TaskEventService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectRuntimeEnvironmentService {

    private final ProjectRuntimeEnvironmentRepository repository;
    private final EnvironmentRepository environmentRepository;
    private final ProjectAccessService projectAccessService;
    private final AuditLogService auditLogService;
    private final TaskEventService taskEventService;

    public ProjectRuntimeEnvironmentService(
            ProjectRuntimeEnvironmentRepository repository,
            EnvironmentRepository environmentRepository,
            ProjectAccessService projectAccessService,
            AuditLogService auditLogService,
            TaskEventService taskEventService
    ) {
        this.repository = repository;
        this.environmentRepository = environmentRepository;
        this.projectAccessService = projectAccessService;
        this.auditLogService = auditLogService;
        this.taskEventService = taskEventService;
    }

    @Transactional(readOnly = true)
    public ProjectRuntimeEnvironmentResponse get(UUID projectId, UUID actorUserId, boolean superAdmin) {
        projectAccessService.requireProjectAccess(projectId, actorUserId, superAdmin);
        return response(config(projectId));
    }

    @Transactional
    public ProjectRuntimeEnvironmentResponse update(
            UUID projectId,
            ProjectRuntimeEnvironmentUpdateRequest request,
            UUID actorUserId,
            boolean superAdmin,
            HttpServletRequest httpRequest
    ) {
        projectAccessService.requireProjectAccess(projectId, actorUserId, superAdmin);
        if (request == null || request.empty()) {
            throw badRequest("PROJECT_RUNTIME_ENVIRONMENT_UPDATE_EMPTY", "运行环境配置更新不能为空");
        }
        ProjectRuntimeEnvironmentView current = config(projectId);
        UUID sourceEnvironmentId = request.sourceEnvironmentId() == null ? current.sourceEnvironmentId() : request.sourceEnvironmentId();
        UUID targetEnvironmentId = request.targetEnvironmentId() == null ? current.targetEnvironmentId() : request.targetEnvironmentId();
        EnvironmentView source = sourceEnvironmentId == null ? null : requireEnvironment(projectId, sourceEnvironmentId, EnvironmentRole.SOURCE_X86);
        EnvironmentView target = targetEnvironmentId == null ? null : requireEnvironment(projectId, targetEnvironmentId, EnvironmentRole.TARGET_RISCV);
        if (sourceEnvironmentId != null && sourceEnvironmentId.equals(targetEnvironmentId)) {
            throw badRequest("PROJECT_RUNTIME_ENVIRONMENT_DUPLICATED", "源环境和目标环境不能相同");
        }
        boolean changed = !Objects.equals(current.sourceEnvironmentId(), sourceEnvironmentId)
                || !Objects.equals(current.targetEnvironmentId(), targetEnvironmentId);
        ProjectRuntimeEnvironmentView updated = repository.upsert(
                projectId,
                sourceEnvironmentId,
                targetEnvironmentId,
                actorUserId,
                changed
        );
        auditLogService.record(projectId, actorUserId, "project.runtime_environment.update", "project", projectId,
                Map.of(
                        "sourceEnvironmentId", sourceEnvironmentId == null ? "" : sourceEnvironmentId.toString(),
                        "targetEnvironmentId", targetEnvironmentId == null ? "" : targetEnvironmentId.toString(),
                        "version", updated.version(),
                        "changed", changed
                ), httpRequest);
        if (changed) {
            List<String> changedFields = new ArrayList<>();
            if (!Objects.equals(current.sourceEnvironmentId(), sourceEnvironmentId)) {
                changedFields.add("runtimeEnvironment.sourceEnvironmentId");
            }
            if (!Objects.equals(current.targetEnvironmentId(), targetEnvironmentId)) {
                changedFields.add("runtimeEnvironment.targetEnvironmentId");
            }
            changedFields.add("runtimeEnvironment.version");
            taskEventService.appendProjectContextChanged(
                    projectId,
                    "context",
                    TaskEventResource.of("project_runtime_environment", projectId),
                    changedFields,
                    "项目运行环境已更新"
            );
        }
        return ProjectRuntimeEnvironmentResponse.from(updated, source, target);
    }

    @Transactional(readOnly = true)
    public ProjectRuntimeEnvironmentView requireComplete(UUID projectId, UUID actorUserId, boolean superAdmin) {
        projectAccessService.requireProjectAccess(projectId, actorUserId, superAdmin);
        ProjectRuntimeEnvironmentView view = config(projectId);
        if (view.sourceEnvironmentId() == null || view.targetEnvironmentId() == null) {
            throw badRequest("PROJECT_RUNTIME_ENVIRONMENTS_REQUIRED", "项目必须配置源环境和目标环境后才能运行该节点");
        }
        requireEnvironment(projectId, view.sourceEnvironmentId(), EnvironmentRole.SOURCE_X86);
        requireEnvironment(projectId, view.targetEnvironmentId(), EnvironmentRole.TARGET_RISCV);
        return view;
    }

    @Transactional(readOnly = true)
    public ProjectRuntimeEnvironmentView requireSource(UUID projectId, UUID actorUserId, boolean superAdmin) {
        projectAccessService.requireProjectAccess(projectId, actorUserId, superAdmin);
        ProjectRuntimeEnvironmentView view = config(projectId);
        if (view.sourceEnvironmentId() == null) {
            throw badRequest("PROJECT_SOURCE_ENVIRONMENT_REQUIRED", "项目必须配置源环境后才能运行该节点");
        }
        requireEnvironment(projectId, view.sourceEnvironmentId(), EnvironmentRole.SOURCE_X86);
        return view;
    }

    @Transactional(readOnly = true)
    public long currentVersion(UUID projectId) {
        return repository.currentVersion(projectId);
    }

    @Transactional(readOnly = true)
    public ProjectRuntimeEnvironmentResponse summary(UUID projectId) {
        return response(config(projectId));
    }

    @Transactional(readOnly = true)
    public void assertEnvironmentNotSelected(UUID environmentId) {
        if (repository.isSelectedEnvironment(environmentId)) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_RUNTIME_ENVIRONMENT_IN_USE", "环境仍被项目运行环境配置引用，不能禁用");
        }
    }

    @Transactional
    public Optional<ProjectRuntimeEnvironmentView> bumpVersionIfSelected(UUID projectId, UUID environmentId, UUID actorUserId) {
        return bumpVersionIfAnySelected(projectId, List.of(environmentId), actorUserId);
    }

    @Transactional
    public Optional<ProjectRuntimeEnvironmentView> bumpVersionIfAnySelected(
            UUID projectId,
            Collection<UUID> environmentIds,
            UUID actorUserId
    ) {
        Optional<ProjectRuntimeEnvironmentView> updated = repository.bumpVersionIfAnySelected(projectId, environmentIds, actorUserId);
        updated.ifPresent(view -> taskEventService.appendProjectContextChanged(
                projectId,
                "context",
                TaskEventResource.of("project_runtime_environment", projectId),
                List.of("runtimeEnvironment.version", "environment.config"),
                "项目运行环境配置已更新"
        ));
        return updated;
    }

    private ProjectRuntimeEnvironmentView config(UUID projectId) {
        return repository.find(projectId).orElseGet(() -> ProjectRuntimeEnvironmentView.empty(projectId));
    }

    private ProjectRuntimeEnvironmentResponse response(ProjectRuntimeEnvironmentView view) {
        EnvironmentView source = view.sourceEnvironmentId() == null
                ? null
                : environmentRepository.findById(view.sourceEnvironmentId()).orElse(null);
        EnvironmentView target = view.targetEnvironmentId() == null
                ? null
                : environmentRepository.findById(view.targetEnvironmentId()).orElse(null);
        return ProjectRuntimeEnvironmentResponse.from(view, source, target);
    }

    private EnvironmentView requireEnvironment(UUID projectId, UUID environmentId, EnvironmentRole role) {
        EnvironmentView environment = environmentRepository.findById(environmentId)
                .orElseThrow(() -> notFound("ENVIRONMENT_NOT_FOUND", "环境不存在"));
        if (!Objects.equals(environment.projectId(), projectId)) {
            throw notFound("ENVIRONMENT_NOT_FOUND", "环境不存在");
        }
        if (!environment.enabled()) {
            throw badRequest("ENVIRONMENT_DISABLED", "环境已禁用");
        }
        if (environment.role() != role) {
            throw badRequest("PROJECT_RUNTIME_ENVIRONMENT_ROLE_INVALID", "环境角色不符合运行环境配置要求");
        }
        return environment;
    }

    private ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    private ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
