package cn.rvport.backend.kb.ai;

import cn.rvport.backend.agentruntime.AgentModelRuntimeConfig;
import cn.rvport.backend.artifact.StoredArtifactOwnerType;
import cn.rvport.backend.artifact.StoredArtifactRepository;
import cn.rvport.backend.artifact.StoredArtifactResource;
import cn.rvport.backend.artifact.StoredArtifactService;
import cn.rvport.backend.artifact.StoredArtifactView;
import cn.rvport.backend.archive.ArchiveCandidateRegistrar;
import cn.rvport.backend.audit.AuditLogService;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.common.web.PageResponse;
import cn.rvport.backend.kb.FindingView;
import cn.rvport.backend.kb.KbScanRepository;
import cn.rvport.backend.kb.KbScanView;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.source.SourceRepository;
import cn.rvport.backend.source.SourceRevisionView;
import cn.rvport.backend.task.RvPortTaskTypes;
import cn.rvport.backend.task.TaskCreateCommand;
import cn.rvport.backend.task.TaskErrorCode;
import cn.rvport.backend.task.TaskExecutionException;
import cn.rvport.backend.task.TaskResponse;
import cn.rvport.backend.task.TaskService;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.worker.TaskExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KbAiExtensionService {

    private static final int DEFAULT_TIMEOUT_SECONDS = 1800;

    private final KbAiExtensionRepository repository;
    private final KbScanRepository kbScanRepository;
    private final SourceRepository sourceRepository;
    private final ProjectAccessService projectAccessService;
    private final TaskService taskService;
    private final AuditLogService auditLogService;
    private final KbAiExtensionAgentRuntime agentRuntime;
    private final StoredArtifactService storedArtifactService;
    private final StoredArtifactRepository storedArtifactRepository;
    private final ArchiveCandidateRegistrar archiveCandidateRegistrar;

    public KbAiExtensionService(
            KbAiExtensionRepository repository,
            KbScanRepository kbScanRepository,
            SourceRepository sourceRepository,
            ProjectAccessService projectAccessService,
            TaskService taskService,
            AuditLogService auditLogService,
            KbAiExtensionAgentRuntime agentRuntime,
            StoredArtifactService storedArtifactService,
            StoredArtifactRepository storedArtifactRepository,
            ArchiveCandidateRegistrar archiveCandidateRegistrar
    ) {
        this.repository = repository;
        this.kbScanRepository = kbScanRepository;
        this.sourceRepository = sourceRepository;
        this.projectAccessService = projectAccessService;
        this.taskService = taskService;
        this.auditLogService = auditLogService;
        this.agentRuntime = agentRuntime;
        this.storedArtifactService = storedArtifactService;
        this.storedArtifactRepository = storedArtifactRepository;
        this.archiveCandidateRegistrar = archiveCandidateRegistrar;
    }

    @Transactional
    public KbAiExtensionCreateResponse create(
            UUID projectId,
            UUID scanRunId,
            KbAiExtensionRequest request,
            UUID actorUserId,
            boolean superAdmin,
            HttpServletRequest httpRequest
    ) {
        projectAccessService.requireProjectAccess(projectId, actorUserId, superAdmin);
        KbScanView scan = requireScan(projectId, scanRunId);
        if (scan.status() != TaskStatus.SUCCEEDED) {
            throw badRequest("KB_AI_EXTENSION_SCAN_NOT_READY", "知识库扫描尚未成功完成");
        }
        long findingCount = kbScanRepository.countFindings(scan.id(), new cn.rvport.backend.kb.FindingListRequest(
                1, 1, null, null, null, null, null, null, null, null, null));
        if (findingCount <= 0) {
            throw badRequest("KB_AI_EXTENSION_FINDINGS_EMPTY", "知识库扫描没有可扩展的 finding");
        }
        AgentModelRuntimeConfig config = agentRuntime.resolveRunnableConfig(actorUserId);
        KbAiExtensionRunView pending = repository.createRun(
                projectId,
                scan.sourceRevisionId(),
                scan.id(),
                config.modelProfile().getId(),
                config.runtime().getId(),
                actorUserId
        );
        Map<String, Object> inputSnapshot = new LinkedHashMap<>();
        inputSnapshot.put("modelExecution", cn.rvport.backend.modelprofile.ModelExecutionSelection.from(actorUserId, config));
        inputSnapshot.put("projectId", projectId);
        inputSnapshot.put("scanRunId", scan.id());
        inputSnapshot.put("extensionRunId", pending.id());
        inputSnapshot.put("sourceRevisionId", scan.sourceRevisionId());
        inputSnapshot.put("note", request == null ? null : request.note());
        TaskResponse task = taskService.create(new TaskCreateCommand(
                projectId,
                RvPortTaskTypes.KB_AI_EXTENSION,
                inputSnapshot,
                actorUserId,
                DEFAULT_TIMEOUT_SECONDS,
                "知识库 AI 扩展任务已创建"
        ));
        KbAiExtensionRunView queued = repository.attachTask(pending.id(), task.id());
        auditLogService.record(projectId, actorUserId, "kb.ai-extension.create", "kb_ai_extension_run", queued.id(),
                Map.of("taskId", task.id(), "scanRunId", scan.id(), "sourceRevisionId", scan.sourceRevisionId()), httpRequest);
        return new KbAiExtensionCreateResponse(task, KbAiExtensionResponse.from(queued));
    }

    @Transactional(readOnly = true)
    public PageResponse<KbAiExtensionResponse> list(UUID projectId, KbAiExtensionListRequest request, UUID actorUserId, boolean superAdmin) {
        projectAccessService.requireProjectAccess(projectId, actorUserId, superAdmin);
        try {
            long total = repository.countRuns(projectId, request);
            List<KbAiExtensionResponse> items = repository.listRuns(projectId, request).stream()
                    .map(KbAiExtensionResponse::from)
                    .toList();
            return PageResponse.of(items, request.normalizedPage(), request.normalizedPageSize(), total);
        } catch (IllegalArgumentException ex) {
            throw badRequest("KB_AI_EXTENSION_QUERY_INVALID", "知识库 AI 扩展查询参数不正确");
        }
    }

    @Transactional(readOnly = true)
    public KbAiExtensionResponse get(UUID runId, UUID actorUserId, boolean superAdmin) {
        return KbAiExtensionResponse.from(requireAccessibleRun(runId, actorUserId, superAdmin));
    }

    @Transactional(readOnly = true)
    public KbAiExtensionReadinessResponse readiness(UUID projectId, UUID scanRunId, UUID actorUserId, boolean superAdmin) {
        projectAccessService.requireProjectAccess(projectId, actorUserId, superAdmin);
        KbScanView scan = requireScan(projectId, scanRunId);
        long findingCount = kbScanRepository.countFindings(scan.id(), new cn.rvport.backend.kb.FindingListRequest(
                1, 1, null, null, null, null, null, null, null, null, null));
        boolean modelConfigured = true;
        Map<String, Object> model = new LinkedHashMap<>();
        try {
            AgentModelRuntimeConfig config = agentRuntime.resolveRunnableConfig(actorUserId);
            model.put("modelProfileId", config.modelProfile().getId());
            model.put("agentRuntimeId", config.runtime().getId());
            model.put("agentType", config.runtime().getAgentType());
            model.put("model", config.model().model());
        } catch (ApiException ex) {
            modelConfigured = false;
            model.put("errorCode", ex.code());
            model.put("message", ex.getMessage());
        }
        String reason = null;
        if (scan.status() != TaskStatus.SUCCEEDED) {
            reason = "KB_SCAN_NOT_SUCCEEDED";
        } else if (findingCount <= 0) {
            reason = "NO_FINDINGS";
        } else if (!modelConfigured) {
            reason = "MODEL_NOT_CONFIGURED";
        }
        return new KbAiExtensionReadinessResponse(reason == null, reason, scan.id(), findingCount, modelConfigured, model);
    }

    @Transactional(readOnly = true)
    public List<KbAiExtensionArtifactResponse> artifacts(UUID runId, UUID actorUserId, boolean superAdmin) {
        KbAiExtensionRunView run = requireAccessibleRun(runId, actorUserId, superAdmin);
        return storedArtifactRepository.listByOwner(StoredArtifactOwnerType.KB_AI_EXTENSION, run.id()).stream()
                .map(KbAiExtensionArtifactResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public DownloadableArtifact downloadArtifact(UUID runId, UUID artifactId, UUID actorUserId, boolean superAdmin) {
        KbAiExtensionRunView run = requireAccessibleRun(runId, actorUserId, superAdmin);
        StoredArtifactView artifact = storedArtifactRepository.findById(artifactId)
                .filter(item -> item.ownerType() == StoredArtifactOwnerType.KB_AI_EXTENSION && Objects.equals(item.ownerId(), run.id()))
                .orElseThrow(() -> notFound("KB_AI_EXTENSION_ARTIFACT_NOT_FOUND", "知识库 AI 扩展制品不存在"));
        StoredArtifactResource resource = storedArtifactService.download(artifact.id());
        return new DownloadableArtifact(filename(artifact), artifact.contentType(), resource.resource());
    }

    public void execute(TaskExecutionContext context) {
        KbAiExtensionTaskInput input = context.input(KbAiExtensionTaskInput.class);
        if (input == null || input.extensionRunId() == null) {
            throw new TaskExecutionException(TaskErrorCode.INPUT_INVALID, "知识库 AI 扩展任务输入非法", null);
        }
        KbAiExtensionRunView run = repository.findById(input.extensionRunId())
                .orElseThrow(() -> new TaskExecutionException(TaskErrorCode.RESOURCE_NOT_FOUND, "知识库 AI 扩展记录不存在", null));
        if (!Objects.equals(run.projectId(), context.task().projectId())) {
            throw new TaskExecutionException(TaskErrorCode.PROJECT_MISMATCH, "知识库 AI 扩展任务项目不匹配", null);
        }
        context.publish(() -> { repository.markRunning(run.id()); return null; });
        context.progress(5, "准备知识库 AI 扩展输入");
        KbAiExtensionAgentResult result = null;
        try {
            AgentModelRuntimeConfig config = agentRuntime.resolveExecution(input.modelExecution(), context.task().createdBy(), run.projectId());
            if (!Objects.equals(run.taskId(), context.task().id()) || !Objects.equals(run.modelProfileId(), config.modelProfile().getId()) || !Objects.equals(run.agentRuntimeId(), config.runtime().getId())) { throw cn.rvport.backend.modelprofile.ModelExecutionSelection.missing(); }
            context.log(cn.rvport.backend.task.TaskLogType.TOOL, input.modelExecution().attemptAudit(config));
            List<FindingView> findings = kbScanRepository.listAllFindingViews(run.kbScanRunId());
            SourceRevisionView revision = sourceRepository.findById(run.sourceRevisionId())
                    .orElseThrow(() -> new TaskExecutionException(TaskErrorCode.RESOURCE_NOT_FOUND, "源码版本不存在", null));
            Path sourceRoot = Path.of(revision.workingCopyPath()).toAbsolutePath().normalize();
            if (!Files.isDirectory(sourceRoot)) {
                throw new TaskExecutionException(TaskErrorCode.RESOURCE_NOT_FOUND, "源码工作副本不存在", null);
            }
            List<Map<String, Object>> exportRows = exportRows(sourceRoot, findings);
            context.progress(20, "调用 AI agent 扩展知识库扫描结果");
            result = agentRuntime.run(sourceRoot, findings, exportRows, config);
            context.progress(75, "解析并保存 AI 扩展结果");
            PersistedResult persisted = persistResult(run, context, exportRows, result);
            context.publish(() -> {
                repository.markFinished(run.id(), persisted.status(), persisted.summary(), persisted.inputArtifactId(), persisted.outputArtifactId(), persisted.agentOutputArtifactId());
                KbAiExtensionRunView completed = repository.findById(run.id()).orElse(run);
                archiveCandidateRegistrar.registerKbAiExtension(completed, persisted.summary(), storedArtifacts(persisted));
                return null;
            });
            context.progress(100, persisted.status() == KbAiExtensionStatus.PARTIAL_SUCCESS ? "知识库 AI 扩展部分完成" : "知识库 AI 扩展完成");
        } catch (cn.rvport.backend.worker.TaskAttemptLostException ex) {
            throw ex;
        } catch (TaskExecutionException ex) {
            context.publish(() -> { repository.markFinished(run.id(), KbAiExtensionStatus.FAILED, Map.of("error", ex.safeMessage()), null, null, null); return null; });
            throw ex;
        } catch (Exception ex) {
            context.publish(() -> { repository.markFinished(run.id(), KbAiExtensionStatus.FAILED, Map.of("error", ex.getMessage() == null ? "未知错误" : ex.getMessage()), null, null, null); return null; });
            throw new TaskExecutionException(TaskErrorCode.HANDLER_FAILED, "知识库 AI 扩展失败", ex);
        } finally {
            if (result != null) {
                deleteQuietly(result.workspace());
            }
        }
    }

    private PersistedResult persistResult(
            KbAiExtensionRunView run,
            TaskExecutionContext context,
            List<Map<String, Object>> exportRows,
            KbAiExtensionAgentResult agentResult
    ) throws IOException {
        Set<UUID> expected = new HashSet<>();
        for (Map<String, Object> row : exportRows) {
            expected.add(UUID.fromString(String.valueOf(row.get("findingId"))));
        }
        Set<UUID> seen = new HashSet<>();
        List<KbAiExtensionRepository.KbAiExtensionResultInput> valid = new ArrayList<>();
        int duplicate = 0;
        int unknown = 0;
        int invalid = 0;
        for (Map<String, Object> row : agentResult.rawRows()) {
            UUID findingId = parseUuid(row.get("findingId"));
            if (findingId == null) {
                invalid++;
                continue;
            }
            if (!expected.contains(findingId)) {
                unknown++;
                continue;
            }
            if (!seen.add(findingId)) {
                duplicate++;
                continue;
            }
            valid.add(new KbAiExtensionRepository.KbAiExtensionResultInput(
                    run.projectId(),
                    run.sourceRevisionId(),
                    run.kbScanRunId(),
                    findingId,
                    text(row.get("aiSummary")),
                    text(row.get("aiSuggestion")),
                    confidence(row.get("confidence")),
                    text(row.get("actionType")),
                    row
            ));
        }
        context.publish(() -> { repository.replaceResults(run.id(), valid); return null; });
        int missing = expected.size() - valid.size();
        KbAiExtensionStatus status = missing == 0 && duplicate == 0 && unknown == 0 && invalid == 0
                ? KbAiExtensionStatus.SUCCEEDED
                : KbAiExtensionStatus.PARTIAL_SUCCESS;
        if (valid.isEmpty()) {
            throw new TaskExecutionException(TaskErrorCode.HANDLER_FAILED, "知识库 AI 扩展未产生有效结果", null);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("inputFindingCount", expected.size());
        summary.put("validResultCount", valid.size());
        summary.put("missingCount", missing);
        summary.put("duplicateCount", duplicate);
        summary.put("unknownCount", unknown);
        summary.put("invalidCount", invalid);
        summary.put("status", status.name());
        StoredArtifactView inputArtifact = storedArtifactService.storeFile(
                run.projectId(), context.task().id(), StoredArtifactOwnerType.KB_AI_EXTENSION, run.id(),
                "INPUT_FINDINGS_JSONL", agentResult.inputFile(), "application/x-ndjson", summary, "jsonl", context);
        StoredArtifactView outputArtifact = storedArtifactService.storeFile(
                run.projectId(), context.task().id(), StoredArtifactOwnerType.KB_AI_EXTENSION, run.id(),
                "OUTPUT_RESULTS_JSONL", agentResult.outputFile(), "application/x-ndjson", summary, "jsonl", context);
        StoredArtifactView agentOutputArtifact = storedArtifactService.storeJson(
                run.projectId(), context.task().id(), StoredArtifactOwnerType.KB_AI_EXTENSION, run.id(),
                "AGENT_OUTPUT_JSON", agentResult.agentOutput(), summary, context);
        return new PersistedResult(status, summary, inputArtifact.id(), outputArtifact.id(), agentOutputArtifact.id());
    }

    private List<Map<String, Object>> exportRows(Path sourceRoot, List<FindingView> findings) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FindingView finding : findings) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("findingId", finding.id());
            row.put("ruleId", finding.ruleId());
            row.put("category", finding.category());
            row.put("severity", finding.severity());
            row.put("title", finding.title());
            row.put("description", finding.description());
            row.put("filePath", finding.filePath());
            row.put("lineStart", finding.lineStart());
            row.put("lineEnd", finding.lineEnd());
            row.put("symbol", finding.symbol());
            row.put("evidence", finding.evidence());
            row.put("schemaVersion", finding.evidence() == null ? null : finding.evidence().get("schemaVersion"));
            row.put("schemaV2Finding", finding.evidence() == null ? Map.of() : finding.evidence().getOrDefault("finding", Map.of()));
            row.put("scannerSuggestion", finding.suggestion());
            row.put("codeContext", codeContext(sourceRoot, finding));
            rows.add(row);
        }
        return rows;
    }

    private List<StoredArtifactView> storedArtifacts(PersistedResult persisted) {
        List<StoredArtifactView> artifacts = new ArrayList<>();
        storedArtifactRepository.findById(persisted.inputArtifactId()).ifPresent(artifacts::add);
        storedArtifactRepository.findById(persisted.outputArtifactId()).ifPresent(artifacts::add);
        storedArtifactRepository.findById(persisted.agentOutputArtifactId()).ifPresent(artifacts::add);
        return artifacts;
    }

    private Map<String, Object> codeContext(Path sourceRoot, FindingView finding) {
        if (finding.filePath() == null || finding.filePath().isBlank() || finding.lineStart() == null) {
            return Map.of();
        }
        try {
            Path file = sourceRoot.resolve(finding.filePath()).toAbsolutePath().normalize();
            if (!file.startsWith(sourceRoot) || !Files.isRegularFile(file)) {
                return Map.of();
            }
            List<String> lines = Files.readAllLines(file);
            int start = Math.max(1, finding.lineStart() - 20);
            int end = Math.min(lines.size(), (finding.lineEnd() == null ? finding.lineStart() : finding.lineEnd()) + 20);
            List<Map<String, Object>> context = new ArrayList<>();
            for (int line = start; line <= end; line++) {
                context.add(Map.of("line", line, "text", lines.get(line - 1),
                        "highlight", line >= finding.lineStart() && line <= (finding.lineEnd() == null ? finding.lineStart() : finding.lineEnd())));
            }
            return Map.of("startLine", start, "endLine", end, "lines", context);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private KbScanView requireScan(UUID projectId, UUID scanRunId) {
        KbScanView scan = kbScanRepository.findById(scanRunId)
                .orElseThrow(() -> notFound("KB_SCAN_NOT_FOUND", "知识库扫描不存在"));
        if (!Objects.equals(scan.projectId(), projectId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PROJECT_ACCESS_DENIED", "无权访问该项目");
        }
        return scan;
    }

    private KbAiExtensionRunView requireAccessibleRun(UUID runId, UUID actorUserId, boolean superAdmin) {
        KbAiExtensionRunView run = repository.findById(runId)
                .orElseThrow(() -> notFound("KB_AI_EXTENSION_NOT_FOUND", "知识库 AI 扩展记录不存在"));
        projectAccessService.requireProjectAccess(run.projectId(), actorUserId, superAdmin);
        return run;
    }

    private UUID parseUuid(Object value) {
        try {
            return value == null ? null : UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private BigDecimal confidence(Object value) {
        if (value == null) {
            return null;
        }
        try {
            BigDecimal parsed = new BigDecimal(String.valueOf(value));
            if (parsed.compareTo(BigDecimal.ZERO) < 0 || parsed.compareTo(BigDecimal.ONE) > 0) {
                return null;
            }
            return parsed;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private String filename(StoredArtifactView artifact) {
        String contentType = artifact.contentType() == null ? "" : artifact.contentType();
        String artifactType = artifact.artifactType() == null ? "" : artifact.artifactType();
        String extension = contentType.contains("ndjson") || artifactType.endsWith("JSONL")
                ? ".jsonl"
                : contentType.contains("json") ? ".json" : ".txt";
        return "kb-ai-extension-%s-%s".formatted(artifact.ownerId(), artifactType.toLowerCase()) + extension;
    }

    private ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    private void deleteQuietly(Path root) {
        if (root == null) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时目录清理失败不影响任务结果。
                }
            });
        } catch (IOException ignored) {
            // ignore
        }
    }

    private record PersistedResult(
            KbAiExtensionStatus status,
            Map<String, Object> summary,
            UUID inputArtifactId,
            UUID outputArtifactId,
            UUID agentOutputArtifactId
    ) {
    }

    public record DownloadableArtifact(String filename, String contentType, Resource resource) {
    }
}
