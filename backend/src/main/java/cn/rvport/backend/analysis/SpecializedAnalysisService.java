package cn.rvport.backend.analysis;

import cn.rvport.backend.artifact.StoredArtifactOwnerType;
import cn.rvport.backend.artifact.StoredArtifactResource;
import cn.rvport.backend.artifact.StoredArtifactService;
import cn.rvport.backend.artifact.StoredArtifactView;
import cn.rvport.backend.buildvalidation.BuildValidationArtifacts;
import cn.rvport.backend.buildvalidation.BuildValidationRepository;
import cn.rvport.backend.buildvalidation.BuildValidationRunView;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.common.path.ServerPathGuard;
import cn.rvport.backend.common.path.ServerPathException;
import cn.rvport.backend.common.web.PageResponse;
import cn.rvport.backend.config.RvPortProperties;
import cn.rvport.backend.migration.MigrationInputSourceType;
import cn.rvport.backend.migration.NodeReportInclusionService;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentService;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentView;
import cn.rvport.backend.source.SourceRepository;
import cn.rvport.backend.source.SourceRevisionView;
import cn.rvport.backend.task.RvPortTaskTypes;
import cn.rvport.backend.task.TaskCreateCommand;
import cn.rvport.backend.task.TaskRepository;
import cn.rvport.backend.task.TaskResponse;
import cn.rvport.backend.task.TaskService;
import cn.rvport.backend.task.TaskView;
import cn.rvport.backend.workflow.DomainLatestExecutionResponse;
import cn.rvport.backend.workflow.DomainLatestResponse;
import cn.rvport.backend.workflow.ProjectContextService;
import cn.rvport.backend.workflow.ProjectContextSnapshot;
import cn.rvport.backend.workflow.StageIds;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.InvalidPathException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SpecializedAnalysisService {

    private final SpecializedAnalysisRepository repository;
    private final ProjectAccessService accessService;
    private final SourceRepository sourceRepository;
    private final ProjectRuntimeEnvironmentService runtimeEnvironmentService;
    private final BuildValidationRepository buildRepository;
    private final StoredArtifactService artifactService;
    private final TaskService taskService;
    private final TaskRepository taskRepository;
    private final ProjectContextService contextService;
    private final NodeReportInclusionService reportInclusionService;
    private final RvPortProperties properties;
    private final SpecializedAnalysisToolHealthService toolHealthService;
    private final SpecializedBitcodeCapabilityResolver bitcodeCapabilityResolver;
    private final SourceAnalysisPlanService sourcePlans;

    public SpecializedAnalysisService(
            SpecializedAnalysisRepository repository, ProjectAccessService accessService,
            SourceRepository sourceRepository, ProjectRuntimeEnvironmentService runtimeEnvironmentService,
            BuildValidationRepository buildRepository, StoredArtifactService artifactService,
            TaskService taskService, TaskRepository taskRepository, ProjectContextService contextService,
            NodeReportInclusionService reportInclusionService, RvPortProperties properties,
            SpecializedAnalysisToolHealthService toolHealthService,
            SpecializedBitcodeCapabilityResolver bitcodeCapabilityResolver, SourceAnalysisPlanService sourcePlans
    ) {
        this.repository = repository;
        this.accessService = accessService;
        this.sourceRepository = sourceRepository;
        this.runtimeEnvironmentService = runtimeEnvironmentService;
        this.buildRepository = buildRepository;
        this.artifactService = artifactService;
        this.taskService = taskService;
        this.taskRepository = taskRepository;
        this.contextService = contextService;
        this.reportInclusionService = reportInclusionService;
        this.properties = properties;
        this.toolHealthService = toolHealthService;
        this.bitcodeCapabilityResolver = bitcodeCapabilityResolver;
        this.sourcePlans = sourcePlans;
    }

    @Transactional
    public TaskResponse create(UUID projectId, SpecializedAnalyzerType type, UUID actorUserId, boolean superAdmin) {
        var project = accessService.requireProjectAccess(projectId, actorUserId, superAdmin);
        if (!properties.specializedAnalysis().enabled()) {
            throw conflict("SPECIALIZED_ANALYSIS_DISABLED", "专项分析功能未启用");
        }
        SpecializedAnalysisToolHealthService.ToolHealth health = toolHealthService.health(type);
        if (!health.available()) {
            throw conflict("SPECIALIZED_ANALYZER_UNAVAILABLE", "专项分析工具不可用: " + String.join(", ", health.reasons()));
        }
        SourceRevisionView source = sourceRepository.findActive(projectId)
                .orElseThrow(() -> conflict("ACTIVE_SOURCE_REVISION_REQUIRED", "当前项目没有可分析的源码版本"));
        var runtime = runtimeEnvironmentService.summary(projectId);
        UUID environmentId = runtime.sourceEnvironment() == null ? null : runtime.sourceEnvironment().id();
        BuildValidationRunView build = environmentId == null ? null : buildRepository.findLatest(
                projectId, source.id(), environmentId, runtime.version(), cn.rvport.backend.buildvalidation.BuildValidationScope.SOURCE_ONLY)
                .filter(value -> value.status() == cn.rvport.backend.task.TaskStatus.SUCCEEDED
                        && value.buildScope() == cn.rvport.backend.buildvalidation.BuildValidationScope.SOURCE_ONLY
                        && projectId.equals(value.projectId()) && source.id().equals(value.sourceRevisionId())
                        && environmentId.equals(value.environmentId())
                        && java.util.Objects.equals(runtime.version(), value.environmentConfigVersion()))
                .orElse(null);
        StoredArtifactView compileDatabase = build == null ? null : artifactService.findByOwner(
                StoredArtifactOwnerType.BUILD_VALIDATION, build.id(), BuildValidationArtifacts.COMPILE_COMMANDS_JSON).orElse(null);
        if (compileDatabase != null && (!projectId.equals(compileDatabase.projectId())
                || !build.id().equals(compileDatabase.ownerId())
                || compileDatabase.ownerType() != StoredArtifactOwnerType.BUILD_VALIDATION
                || !BuildValidationArtifacts.COMPILE_COMMANDS_JSON.equals(compileDatabase.artifactType())
                || !artifactService.objectExists(compileDatabase))) compileDatabase = null;
        if (compileDatabase == null) build = null;
        AnalysisInputPlan plan = sourcePlans.create(project, source, type);
        SpecializedBitcodeCapability capability = build == null
                ? new SpecializedBitcodeCapability(SpecializedAnalysisMode.AST_ONLY, "MISSING", 0, 0, 0,
                        "SOURCE_DISCOVERY", null, null, null) : bitcodeCapabilityResolver.resolve(build.id());
        if (build != null) {
            var boundArtifacts = new java.util.ArrayList<StoredArtifactView>();
            boundArtifacts.add(compileDatabase);
            if (capability.manifest() != null && capability.bundle() != null) {
                boundArtifacts.add(capability.manifest());
                boundArtifacts.add(capability.bundle());
            }
            plan = sourcePlans.enhance(plan, build, boundArtifacts);
            if (plan.inputOrigin() == AnalysisInputPlan.InputOrigin.SOURCE_DISCOVERY) {
                build = null;
                compileDatabase = null;
                capability = new SpecializedBitcodeCapability(SpecializedAnalysisMode.AST_ONLY, "MISSING", 0, 0, 0,
                        "ANALYSIS_ARTIFACT_IDENTITY_MISMATCH", null, null, null);
            }
        }
        UUID runId = UUID.randomUUID();
        SpecializedAnalysisTaskInput input = new SpecializedAnalysisTaskInput(projectId, runId, source.id(), build == null ? null : build.id(),
                compileDatabase == null ? null : compileDatabase.id(), capability.manifest() == null ? null : capability.manifest().id(),
                capability.bundle() == null ? null : capability.bundle().id(), capability.artifactSetId(),
                capability.mode(), capability.status(), capability.plannedCount(), capability.usableCount(),
                capability.failedCount(), capability.degradationReason(),
                build == null ? null : runtime.version(), type, plan);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("projectId", projectId);
        snapshot.put("runId", runId);
        snapshot.put("sourceRevisionId", source.id());
        snapshot.put("buildRunId", input.buildRunId());
        snapshot.put("compileDatabaseArtifactId", input.compileDatabaseArtifactId());
        snapshot.put("inputPlan", plan);
        snapshot.put("bitcodeManifestArtifactId", input.bitcodeManifestArtifactId());
        snapshot.put("bitcodeBundleArtifactId", input.bitcodeBundleArtifactId());
        snapshot.put("artifactSetId", input.artifactSetId());
        snapshot.put("analysisMode", input.analysisMode().name());
        snapshot.put("bcStatus", input.bcStatus());
        snapshot.put("bcPlannedCount", input.bcPlannedCount());
        snapshot.put("bcUsableCount", input.bcUsableCount());
        snapshot.put("bcFailedCount", input.bcFailedCount());
        snapshot.put("bcDegradationReason", input.bcDegradationReason());
        snapshot.put("environmentConfigVersion", input.environmentConfigVersion());
        snapshot.put("analyzerType", type.name());
        String taskType = type == SpecializedAnalyzerType.FLOATING_POINT
                ? RvPortTaskTypes.FLOATING_POINT_ANALYSIS : RvPortTaskTypes.MEMORY_ORDER_ANALYSIS;
        String stageId = type == SpecializedAnalyzerType.FLOATING_POINT
                ? StageIds.FLOATING_POINT_ANALYSIS : StageIds.MEMORY_ORDER_ANALYSIS;
        TaskResponse task = taskService.create(new TaskCreateCommand(projectId, taskType, snapshot, actorUserId,
                properties.specializedAnalysis().timeoutSeconds(), type.displayName() + "任务已创建", stageId,
                "SPECIALIZED_ANALYSIS", runId, type.name(), null));
        repository.create(runId, projectId, task.id(), type, source.id(), input.buildRunId(), input,
                input.environmentConfigVersion(), actorUserId);
        return task;
    }

    @Transactional(readOnly = true)
    public PageResponse<SpecializedAnalysisResponse> list(UUID projectId, SpecializedAnalyzerType type,
            SpecializedAnalysisListRequest request, UUID actorUserId, boolean superAdmin) {
        var project = accessService.requireProjectAccess(projectId, actorUserId, superAdmin);
        UUID active = contextService.snapshot(projectId).sourceRevisionId();
        var runtime = runtimeEnvironmentService.summary(projectId);
        UUID currentBuild = currentBuild(projectId, active, runtime);
        return PageResponse.of(repository.list(projectId, type, request).stream()
                        .map(run -> response(run, active, currentBuild, runtime.version(), project)).toList(),
                request.normalizedPage(), request.normalizedPageSize(), repository.count(projectId, type, request));
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public DomainLatestResponse<SpecializedAnalysisResponse> latest(UUID projectId, SpecializedAnalyzerType type,
            UUID actorUserId, boolean superAdmin) {
        var project = accessService.requireProjectAccess(projectId, actorUserId, superAdmin);
        ProjectContextSnapshot context = contextService.snapshot(projectId);
        SpecializedAnalysisRunView run = repository.latest(projectId, type).orElse(null);
        if (run == null) return DomainLatestResponse.empty(context);
        TaskView task = run.taskId() == null ? null : taskRepository.findById(run.taskId()).orElse(null);
        DomainLatestExecutionResponse execution = DomainLatestExecutionResponse.from(run.id(), task);
        var runtime = runtimeEnvironmentService.summary(projectId);
        UUID currentBuild = currentBuild(projectId, context.sourceRevisionId(), runtime);
        SpecializedAnalysisResponse result = response(run, context.sourceRevisionId(), currentBuild, runtime.version(), project);
        SpecializedAnalysisResponse published = repository.latestPublished(projectId, context.sourceRevisionId(), type,
                        currentBuild, runtime.version())
                .map(value -> response(value, context.sourceRevisionId(), currentBuild, runtime.version(), project)).orElse(null);
        return new DomainLatestResponse<>(context, result.staleReasons().isEmpty()
                ? cn.rvport.backend.workflow.DomainLatestFreshness.CURRENT : cn.rvport.backend.workflow.DomainLatestFreshness.STALE,
                execution, result, published);
    }

    private UUID currentBuild(UUID projectId, UUID sourceId,
            cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentResponse runtime) {
        return runtime.sourceEnvironment() == null ? null : buildRepository.findLatest(
                projectId, sourceId, runtime.sourceEnvironment().id(), runtime.version(),
                cn.rvport.backend.buildvalidation.BuildValidationScope.SOURCE_ONLY)
                .filter(value -> value.status() == cn.rvport.backend.task.TaskStatus.SUCCEEDED
                        && projectId.equals(value.projectId()) && java.util.Objects.equals(sourceId, value.sourceRevisionId())
                        && runtime.sourceEnvironment().id().equals(value.environmentId())
                        && java.util.Objects.equals(runtime.version(), value.environmentConfigVersion())
                        && value.buildScope() == cn.rvport.backend.buildvalidation.BuildValidationScope.SOURCE_ONLY)
                .map(BuildValidationRunView::id).orElse(null);
    }

    private SpecializedAnalysisResponse response(SpecializedAnalysisRunView run, UUID active, UUID currentBuild, Long version, cn.rvport.backend.project.ProjectView project) {
        boolean enhancement = false;
        if (currentBuild != null && "SOURCE_DISCOVERY".equals(run.summary().get("inputOrigin"))) {
            var artifact = artifactService.findByOwner(StoredArtifactOwnerType.BUILD_VALIDATION, currentBuild,
                    BuildValidationArtifacts.COMPILE_COMMANDS_JSON).orElse(null);
            enhancement = artifact != null && run.projectId().equals(artifact.projectId())
                    && currentBuild.equals(artifact.ownerId()) && artifact.ownerType() == StoredArtifactOwnerType.BUILD_VALIDATION
                    && BuildValidationArtifacts.COMPILE_COMMANDS_JSON.equals(artifact.artifactType())
                    && run.summary().get("sourceDigest") != null
                    && run.summary().get("sourceDigest").equals(artifact.summary().get("sourceDigest"))
                    && "x86".equals(artifact.summary().get("sourceArch"))
                    && artifact.sha256() != null && artifact.sha256().matches("[0-9a-f]{64}")
                    && artifactService.objectExists(artifact);
        }
        return SpecializedAnalysisResponse.from(run, active, currentBuild, version, enhancement, SourceAnalysisPlanService.profileSupported(project));
    }

    @Transactional(readOnly = true)
    public SpecializedAnalysisResponse get(UUID projectId, SpecializedAnalyzerType type, UUID runId,
            UUID actorUserId, boolean superAdmin) {
        var project = accessService.requireProjectAccess(projectId, actorUserId, superAdmin);
        SpecializedAnalysisRunView run = requireRun(projectId, type, runId);
        UUID active = contextService.snapshot(projectId).sourceRevisionId();
        var runtime = runtimeEnvironmentService.summary(projectId);
        return response(run, active, currentBuild(projectId, active, runtime), runtime.version(), project);
    }

    @Transactional(readOnly = true)
    public PageResponse<SpecializedFindingResponse> findings(UUID projectId, SpecializedAnalyzerType type, UUID runId,
            SpecializedFindingListRequest request, UUID actorUserId, boolean superAdmin) {
        var project = accessService.requireProjectAccess(projectId, actorUserId, superAdmin);
        requireRun(projectId, type, runId);
        MigrationInputSourceType sourceType = sourceType(type);
        List<SpecializedAnalysisFindingView> values = repository.listFindings(runId, request);
        Map<UUID, cn.rvport.backend.migration.NodeReportInclusionState> states = reportInclusionService.states(
                projectId, requireRun(projectId, type, runId).sourceRevisionId(), sourceType,
                values.stream().map(SpecializedAnalysisFindingView::id).toList());
        return PageResponse.of(values.stream().map(value -> SpecializedFindingResponse.from(value,
                        states.getOrDefault(value.id(), cn.rvport.backend.migration.NodeReportInclusionState.empty()))).toList(),
                request.normalizedPage(), request.normalizedPageSize(), repository.countFindings(runId, request));
    }

    @Transactional(readOnly = true)
    public SpecializedFindingResponse finding(UUID projectId, SpecializedAnalyzerType type, UUID findingId,
            UUID actorUserId, boolean superAdmin) {
        var project = accessService.requireProjectAccess(projectId, actorUserId, superAdmin);
        SpecializedAnalysisFindingView finding = repository.findFinding(findingId)
                .filter(value -> value.projectId().equals(projectId) && value.analyzerType() == type)
                .orElseThrow(() -> notFound("SPECIALIZED_FINDING_NOT_FOUND", "分析结果不存在"));
        SpecializedFindingCodeContextResponse context = null;
        String unavailableReason = null;
        try {
            context = readCodeContext(projectId, finding);
        } catch (ApiException ex) {
            unavailableReason = ex.code();
        }
        return SpecializedFindingResponse.from(finding, reportInclusionService.state(projectId,
                finding.sourceRevisionId(), sourceType(type), finding.id()), context, unavailableReason);
    }

    private SpecializedFindingCodeContextResponse readCodeContext(UUID projectId,
            SpecializedAnalysisFindingView finding) {
        if (finding.filePath() == null || finding.filePath().isBlank() || finding.startLine() == null
                || finding.startLine() < 1) {
            throw notFound("FINDING_SOURCE_LOCATION_UNAVAILABLE", "Finding 源码位置不可用");
        }
        int first = finding.startLine();
        int last = finding.endLine() == null ? first : finding.endLine();
        if (finding.analyzerType() == SpecializedAnalyzerType.MEMORY_ORDER
                && last > first && Integer.valueOf(1).equals(finding.endColumn())) {
            last--;
        }
        if (last < first) {
            throw notFound("FINDING_SOURCE_LOCATION_UNAVAILABLE", "Finding 源码范围无效");
        }
        SourceRevisionView revision = sourceRepository.findById(finding.sourceRevisionId())
                .filter(value -> value.projectId().equals(projectId))
                .orElseThrow(() -> notFound("SOURCE_REVISION_NOT_FOUND", "源码版本不存在"));
        if (revision.workingCopyPath() == null || revision.workingCopyPath().isBlank()) {
            throw notFound("FINDING_SOURCE_FILE_NOT_FOUND", "源码工作副本不可用");
        }
        try {
            Path root = Path.of(revision.workingCopyPath());
            Path candidate = ServerPathGuard.resolveRelativeUnderRoot(root, finding.filePath());
            if (!Files.exists(candidate)) {
                throw notFound("FINDING_SOURCE_FILE_NOT_FOUND", "Finding 源码文件不存在");
            }
            Path file = ServerPathGuard.resolveExistingRelativeFileUnderRoot(root, finding.filePath());
            // Bound both file reads and response size; never expose an arbitrary server path.
            byte[] bytes;
            try (var input = Files.newInputStream(file)) {
                bytes = input.readNBytes(8 * 1024 * 1024 + 1);
            }
            if (bytes.length > 8 * 1024 * 1024) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FINDING_SOURCE_FILE_TOO_LARGE",
                        "源码文件超过 8 MiB 上下文读取限制");
            }
            Object fact = finding.detail().get("fact");
            if (fact instanceof Map<?, ?> factMap
                    && factMap.get("primaryLocation") instanceof Map<?, ?> location
                    && location.get("sourceSha256") instanceof String expected) {
                String actual;
                try {
                    actual = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
                } catch (java.security.NoSuchAlgorithmException ex) {
                    throw new IllegalStateException("SHA-256 unavailable", ex);
                }
                if (!actual.equals(expected)) {
                    throw notFound("FINDING_SOURCE_DIGEST_MISMATCH", "源码内容与分析时的摘要不一致");
                }
            }
            String text = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            List<String> allLines = text.lines().toList();
            if (last > allLines.size()) {
                throw notFound("FINDING_SOURCE_LOCATION_UNAVAILABLE", "Finding 源码范围超出文件");
            }
            int start = Math.max(1, first - 20);
            int end = (int) Math.min(allLines.size(), (long) last + 20);
            int highlightLast = last;
            List<SpecializedFindingCodeContextResponse.CodeLine> lines = java.util.stream.IntStream
                    .rangeClosed(start, end).mapToObj(line -> new SpecializedFindingCodeContextResponse.CodeLine(
                            line, allLines.get(line - 1), line >= first && line <= highlightLast)).toList();
            return new SpecializedFindingCodeContextResponse(finding.id(), finding.runId(), revision.id(),
                    finding.filePath(), 20, 20, start, end, first, last, lines);
        } catch (ServerPathException | InvalidPathException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FINDING_SOURCE_PATH_INVALID", "Finding 源码路径非法");
        } catch (IOException ex) {
            throw notFound("FINDING_SOURCE_FILE_UNREADABLE", "源码文件无法作为 UTF-8 文本读取");
        }
    }

    @Transactional(readOnly = true)
    public StoredArtifactResource download(UUID projectId, SpecializedAnalyzerType type, UUID runId, String format,
            UUID actorUserId, boolean superAdmin) {
        var project = accessService.requireProjectAccess(projectId, actorUserId, superAdmin);
        SpecializedAnalysisRunView run = requireRun(projectId, type, runId);
        UUID artifactId = switch (format == null ? "" : format.toLowerCase()) {
            case "json" -> run.resultJsonArtifactId();
            case "html" -> run.resultHtmlArtifactId();
            case "audit" -> run.auditBundleArtifactId();
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "ANALYSIS_ARTIFACT_FORMAT_INVALID", "仅支持 json、html 或 audit");
        };
        if (artifactId == null) throw notFound("ANALYSIS_ARTIFACT_NOT_FOUND", "分析制品不存在");
        return artifactService.download(artifactId);
    }

    private SpecializedAnalysisRunView requireRun(UUID projectId, SpecializedAnalyzerType type, UUID runId) {
        return repository.findById(runId)
                .filter(run -> run.projectId().equals(projectId) && run.analyzerType() == type)
                .orElseThrow(() -> notFound("SPECIALIZED_ANALYSIS_NOT_FOUND", "专项分析不存在"));
    }

    private static MigrationInputSourceType sourceType(SpecializedAnalyzerType type) {
        return type == SpecializedAnalyzerType.FLOATING_POINT ? MigrationInputSourceType.FLOATING_POINT_FINDING
                : MigrationInputSourceType.MEMORY_ORDER_FINDING;
    }

    private static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
    private static ApiException notFound(String code, String message) { return new ApiException(HttpStatus.NOT_FOUND, code, message); }
}
