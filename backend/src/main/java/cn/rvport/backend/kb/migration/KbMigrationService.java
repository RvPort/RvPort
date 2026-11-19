package cn.rvport.backend.kb.migration;

import cn.rvport.backend.agentruntime.AgentModelRuntimeConfig;
import cn.rvport.backend.audit.AuditLogService;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.common.web.PageResponse;
import cn.rvport.backend.kb.KbScanRepository;
import cn.rvport.backend.kb.KbScanView;
import cn.rvport.backend.modelprofile.ModelExecutionSelection;
import cn.rvport.backend.modelprofile.ModelProfileService;
import cn.rvport.backend.modelprofile.ModelProfileStage;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.source.SourceRepository;
import cn.rvport.backend.source.SourceRevisionView;
import cn.rvport.backend.task.RvPortTaskTypes;
import cn.rvport.backend.task.TaskCreateCommand;
import cn.rvport.backend.task.TaskResponse;
import cn.rvport.backend.task.TaskService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KbMigrationService {

    private final KbMigrationRepository repository;
    private final KbScanRepository kbScanRepository;
    private final SourceRepository sourceRepository;
    private final ProjectAccessService projectAccessService;
    private final TaskService taskService;
    private final ModelProfileService models;
    private final AuditLogService auditLogService;

    public KbMigrationService(KbMigrationRepository repository, KbScanRepository kbScanRepository,
            SourceRepository sourceRepository, ProjectAccessService projectAccessService, TaskService taskService,
            ModelProfileService models, AuditLogService auditLogService) {
        this.repository = repository;
        this.kbScanRepository = kbScanRepository;
        this.sourceRepository = sourceRepository;
        this.projectAccessService = projectAccessService;
        this.taskService = taskService;
        this.models = models;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public TaskResponse create(UUID projectId, KbMigrationRequest request, UUID actorUserId, boolean superAdmin,
            HttpServletRequest httpRequest) {
        projectAccessService.requireProjectAccess(projectId, actorUserId, superAdmin);
        SourceRevisionView sourceRevision = sourceRepository.findActive(projectId)
                .orElseThrow(() -> badRequest("SOURCE_REVISION_ACTIVE_NOT_FOUND",
                        "项目没有可用的 active source revision / No active source revision"));
        KbScanView scanRun = resolveScanRun(projectId, sourceRevision, request == null ? null : request.scanRunId());
        AgentModelRuntimeConfig config = resolveStageBinding(actorUserId);
        List<cn.rvport.backend.kb.FindingView> findings = kbScanRepository.listAllFindingViews(scanRun.id());
        if (KbMigrationProjector.project(scanRun.id(), findings).items().isEmpty()) {
            throw badRequest("KB_MIGRATION_NO_ENTRIES",
                    "当前扫描没有可迁移条目 / The current scan has no projectable entries");
        }

        Map<String, Object> inputSnapshot = new LinkedHashMap<>();
        inputSnapshot.put("projectId", projectId);
        inputSnapshot.put("scanRunId", scanRun.id());
        inputSnapshot.put("sourceRevisionId", sourceRevision.id());
        ModelExecutionSelection selection = ModelExecutionSelection.from(actorUserId, config);
        inputSnapshot.put("selection", Map.of(
                "configurationUserId", selection.configurationUserId().toString(),
                "modelProfileId", selection.modelProfileId().toString(),
                "agentRuntimeId", selection.agentRuntimeId().toString()));

        TaskResponse task = taskService.create(new TaskCreateCommand(
                projectId,
                RvPortTaskTypes.KB_MIGRATION,
                inputSnapshot,
                actorUserId,
                null,
                "KB 迁移任务已创建"));
        UUID runId = repository.createRun(projectId, scanRun.id(), sourceRevision.id(), task.id(),
                selection.modelProfileId(), selection.agentRuntimeId());
        auditLogService.record(projectId, actorUserId, "kb.migration.create", "kb_migration_run", runId,
                Map.of("taskId", task.id(), "scanRunId", scanRun.id()), httpRequest);
        return task;
    }

    @Transactional(readOnly = true)
    public PageResponse<KbMigrationResponse> list(UUID projectId, int page, int pageSize, UUID actorUserId,
            boolean superAdmin) {
        projectAccessService.requireProjectAccess(projectId, actorUserId, superAdmin);
        int normalizedPage = Math.max(page, 0);
        int normalizedSize = Math.min(Math.max(pageSize, 1), 100);
        long total = repository.countRuns(projectId);
        List<KbMigrationResponse> runs = repository.listRuns(projectId, normalizedPage * normalizedSize, normalizedSize)
                .stream()
                .map(run -> KbMigrationResponse.from(run, List.of()))
                .toList();
        return PageResponse.of(runs, normalizedPage, normalizedSize, total);
    }

    @Transactional(readOnly = true)
    public KbMigrationResponse detail(UUID runId, UUID actorUserId, boolean superAdmin) {
        KbMigrationRunView run = repository.findRun(runId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "KB_MIGRATION_RUN_NOT_FOUND",
                        "迁移运行不存在 / Migration run not found"));
        projectAccessService.requireProjectAccess(run.projectId(), actorUserId, superAdmin);
        return KbMigrationResponse.from(run, List.of());
    }

    KbScanView resolveScanRun(UUID projectId, SourceRevisionView sourceRevision, UUID scanRunId) {
        if (scanRunId != null) {
            KbScanView run = kbScanRepository.findById(scanRunId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "KB_SCAN_RUN_NOT_FOUND",
                            "扫描运行不存在 / Scan run not found"));
            if (!Objects.equals(run.projectId(), projectId)) {
                throw new ApiException(HttpStatus.NOT_FOUND, "KB_SCAN_RUN_NOT_FOUND",
                        "扫描运行不存在 / Scan run not found");
            }
            if (!cn.rvport.backend.task.TaskStatus.SUCCEEDED.equals(run.status())) {
                throw badRequest("KB_MIGRATION_SCAN_NOT_SUCCEEDED",
                        "指定扫描未成功 / The specified scan did not succeed");
            }
            return run;
        }
        return kbScanRepository.findLatestSucceededRunBySourceRevision(projectId, sourceRevision.id())
                .orElseThrow(() -> badRequest("KB_MIGRATION_SCAN_NOT_SUCCEEDED",
                        "当前源码版本没有成功扫描 / No succeeded scan for the current source revision"));
    }

    AgentModelRuntimeConfig resolveStageBinding(UUID actorUserId) {
        try {
            return models.resolveAgentForStage(ModelProfileStage.KB_MIGRATION, actorUserId);
        } catch (ApiException ex) {
            throw badRequest("KB_MIGRATION_MODEL_NOT_CONFIGURED",
                    "未绑定 KB 迁移阶段模型 / Bind a model profile for the KB migration stage first");
        }
    }

    private ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
