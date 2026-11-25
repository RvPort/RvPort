package cn.rvport.backend.ai.migration;

import cn.rvport.backend.agentruntime.AgentModelRuntimeConfig;
import cn.rvport.backend.audit.AuditLogService;
import cn.rvport.backend.artifact.StoredArtifactOwnerType;
import cn.rvport.backend.artifact.StoredArtifactRepository;
import cn.rvport.backend.artifact.StoredArtifactService;
import cn.rvport.backend.artifact.StoredArtifactView;
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
public class AiMigrationService {

    private final AiMigrationRepository repository;
    private final KbScanRepository kbScanRepository;
    private final SourceRepository sourceRepository;
    private final ProjectAccessService projectAccessService;
    private final TaskService taskService;
    private final ModelProfileService models;
    private final AuditLogService auditLogService;
    private final StoredArtifactRepository storedArtifactRepository;
    private final StoredArtifactService storedArtifactService;

    public AiMigrationService(AiMigrationRepository repository, KbScanRepository kbScanRepository,
            SourceRepository sourceRepository, ProjectAccessService projectAccessService, TaskService taskService,
            ModelProfileService models, AuditLogService auditLogService,
            StoredArtifactRepository storedArtifactRepository, StoredArtifactService storedArtifactService) {
        this.repository = repository;
        this.kbScanRepository = kbScanRepository;
        this.sourceRepository = sourceRepository;
        this.projectAccessService = projectAccessService;
        this.taskService = taskService;
        this.models = models;
        this.auditLogService = auditLogService;
        this.storedArtifactRepository = storedArtifactRepository;
        this.storedArtifactService = storedArtifactService;
    }

    @Transactional
    public TaskResponse create(UUID projectId, AiMigrationRequest request, UUID actorUserId, boolean superAdmin,
            HttpServletRequest httpRequest) {
        projectAccessService.requireProjectAccess(projectId, actorUserId, superAdmin);
        SourceRevisionView sourceRevision = sourceRepository.findActive(projectId)
                .orElseThrow(() -> badRequest("SOURCE_REVISION_ACTIVE_NOT_FOUND",
                        "项目没有可用的 active source revision / No active source revision"));
        KbScanView scanRun = resolveScanRun(projectId, sourceRevision, request == null ? null : request.scanRunId());
        AgentModelRuntimeConfig config = resolveStageBinding(actorUserId);
        List<cn.rvport.backend.kb.FindingView> findings = kbScanRepository.listAllFindingViews(scanRun.id());
        if (AiMigrationProjector.project(scanRun.id(), findings).suggestionClass().isEmpty()) {
            throw badRequest("AI_MIGRATION_NO_ENTRIES",
                    "当前扫描没有可迁移条目 / The current scan has no projectable entries");
        }

        ModelExecutionSelection selection = ModelExecutionSelection.from(actorUserId, config);
        // 运行 ID 先于任务生成：TaskMetadataResolver 从任务输入读取 migrationRunId 填 tasks.resource_id。
        UUID runId = UUID.randomUUID();
        Map<String, Object> inputSnapshot = new LinkedHashMap<>();
        inputSnapshot.put("projectId", projectId);
        inputSnapshot.put("scanRunId", scanRun.id());
        inputSnapshot.put("sourceRevisionId", sourceRevision.id());
        inputSnapshot.put("migrationRunId", runId.toString());
        inputSnapshot.put("selection", Map.of(
                "configurationUserId", selection.configurationUserId().toString(),
                "modelProfileId", selection.modelProfileId().toString(),
                "agentRuntimeId", selection.agentRuntimeId().toString()));

        TaskResponse task = taskService.create(new TaskCreateCommand(
                projectId,
                RvPortTaskTypes.AI_MIGRATION,
                inputSnapshot,
                actorUserId,
                null,
                "AI 迁移任务已创建"));
        repository.createRun(runId, projectId, scanRun.id(), sourceRevision.id(), task.id(),
                selection.modelProfileId(), selection.agentRuntimeId());
        auditLogService.record(projectId, actorUserId, "kb.migration.create", "ai_migration_run", runId,
                Map.of("taskId", task.id(), "scanRunId", scanRun.id()), httpRequest);
        return task;
    }

    @Transactional(readOnly = true)
    public PageResponse<AiMigrationResponse> list(UUID projectId, int page, int pageSize, UUID actorUserId,
            boolean superAdmin) {
        projectAccessService.requireProjectAccess(projectId, actorUserId, superAdmin);
        int normalizedPage = Math.max(page, 0);
        int normalizedSize = Math.min(Math.max(pageSize, 1), 100);
        long total = repository.countRuns(projectId);
        List<AiMigrationResponse> runs = repository.listRuns(projectId, normalizedPage * normalizedSize, normalizedSize)
                .stream()
                .map(run -> AiMigrationResponse.from(run, storedArtifactRepository.listByOwner(
                        StoredArtifactOwnerType.AI_MIGRATION, run.id())))
                .toList();
        return PageResponse.of(runs, normalizedPage, normalizedSize, total);
    }

    @Transactional(readOnly = true)
    public AiMigrationResponse detail(UUID runId, UUID actorUserId, boolean superAdmin) {
        AiMigrationRunView run = repository.findRun(runId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AI_MIGRATION_RUN_NOT_FOUND",
                        "迁移运行不存在 / Migration run not found"));
        projectAccessService.requireProjectAccess(run.projectId(), actorUserId, superAdmin);
        return AiMigrationResponse.from(run, storedArtifactRepository.listByOwner(
                StoredArtifactOwnerType.AI_MIGRATION, run.id()));
    }

    /** 迁移产物类型；artifactType 与 AiMigrationRuntimeService 入库时使用的值一致。 */
    public enum ArtifactKind {
        DIFF(AiMigrationRuntimeService.ARTIFACT_TYPE_DIFF, "migration-diff.patch", "text/x-diff"),
        RESULT(AiMigrationRuntimeService.ARTIFACT_TYPE_RESULT, "migration-result.json", "application/json");

        private final String artifactType;
        private final String filename;
        private final String contentType;

        ArtifactKind(String artifactType, String filename, String contentType) {
            this.artifactType = artifactType;
            this.filename = filename;
            this.contentType = contentType;
        }

        public String artifactType() {
            return artifactType;
        }

        public String filename() {
            return filename;
        }

        public String contentType() {
            return contentType;
        }

        public static ArtifactKind fromPathValue(String value) {
            if (value == null) {
                return null;
            }
            String normalized = value.trim().toLowerCase();
            if (normalized.equals("diff") || normalized.equals("patch")) {
                return DIFF;
            }
            if (normalized.equals("result")) {
                return RESULT;
            }
            return null;
        }
    }

    public record DownloadableArtifact(
            String filename,
            String contentType,
            org.springframework.core.io.Resource resource) {
    }

    /** 页面内展示用的产物文本上限；超出部分截断并标记，完整内容走下载。 */
    static final int MAX_ARTIFACT_CONTENT_CHARS = 512 * 1024;

    public record ArtifactContent(
            @com.fasterxml.jackson.annotation.JsonProperty("kind") String kind,
            String filename,
            String content,
            boolean truncated) {

        public static ArtifactContent of(ArtifactKind kind, String filename, String content, boolean truncated) {
            return new ArtifactContent(kind == ArtifactKind.DIFF ? "diff" : "result", filename, content, truncated);
        }
    }

    /** 读取某次运行的迁移产物文本，供页面内展示；归属校验与下载一致。 */
    @Transactional(readOnly = true)
    public ArtifactContent artifactContent(UUID runId, ArtifactKind kind, UUID actorUserId, boolean superAdmin) {
        DownloadableArtifact artifact = downloadArtifact(runId, kind, actorUserId, superAdmin);
        try {
            byte[] bytes = artifact.resource().getInputStream().readAllBytes();
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            if (text.length() <= MAX_ARTIFACT_CONTENT_CHARS) {
                return ArtifactContent.of(kind, artifact.filename(), text, false);
            }
            return ArtifactContent.of(kind, artifact.filename(),
                    text.substring(0, MAX_ARTIFACT_CONTENT_CHARS), true);
        } catch (java.io.IOException ex) {
            throw new ApiException(HttpStatus.NOT_FOUND, "AI_MIGRATION_ARTIFACT_NOT_FOUND",
                    "迁移产物不存在 / Migration artifact not found");
        }
    }

    /** 按类型下载某次运行的迁移产物；归属校验与详情一致，未知类型或产物缺失返回 404。 */
    @Transactional(readOnly = true)
    public DownloadableArtifact downloadArtifact(
            UUID runId, ArtifactKind kind, UUID actorUserId, boolean superAdmin) {
        AiMigrationRunView run = repository.findRun(runId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AI_MIGRATION_RUN_NOT_FOUND",
                        "迁移运行不存在 / Migration run not found"));
        projectAccessService.requireProjectAccess(run.projectId(), actorUserId, superAdmin);
        StoredArtifactView artifact = storedArtifactRepository.findByOwner(
                        StoredArtifactOwnerType.AI_MIGRATION, run.id(), kind.artifactType())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AI_MIGRATION_ARTIFACT_NOT_FOUND",
                        "迁移产物不存在 / Migration artifact not found"));
        return new DownloadableArtifact(kind.filename(), kind.contentType(),
                storedArtifactService.download(artifact.id()).resource());
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
                throw badRequest("AI_MIGRATION_SCAN_NOT_SUCCEEDED",
                        "指定扫描未成功 / The specified scan did not succeed");
            }
            return run;
        }
        return kbScanRepository.findLatestSucceededRunBySourceRevision(projectId, sourceRevision.id())
                .orElseThrow(() -> badRequest("AI_MIGRATION_SCAN_NOT_SUCCEEDED",
                        "当前源码版本没有成功扫描 / No succeeded scan for the current source revision"));
    }

    AgentModelRuntimeConfig resolveStageBinding(UUID actorUserId) {
        try {
            return models.resolveAgentForStage(ModelProfileStage.AI_MIGRATION, actorUserId);
        } catch (ApiException ex) {
            throw badRequest("AI_MIGRATION_MODEL_NOT_CONFIGURED",
                    "未绑定 AI 迁移阶段模型 / Bind a model profile for the KB migration stage first");
        }
    }

    private ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
