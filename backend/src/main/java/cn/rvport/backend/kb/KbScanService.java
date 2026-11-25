package cn.rvport.backend.kb;

import cn.rvport.backend.artifact.StoredArtifactOwnerType;

import cn.rvport.backend.audit.AuditLogService;
import cn.rvport.backend.artifact.StoredArtifactResource;
import cn.rvport.backend.artifact.StoredArtifactService;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.common.path.ServerPathException;
import cn.rvport.backend.common.path.ServerPathGuard;
import cn.rvport.backend.common.web.PageResponse;
import cn.rvport.backend.config.RvPortProperties;
import cn.rvport.backend.migration.MigrationInputSourceType;
import cn.rvport.backend.migration.NodeReportInclusionService;
import cn.rvport.backend.migration.NodeReportInclusionState;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.source.SourceRepository;
import cn.rvport.backend.source.SourceRevisionView;
import cn.rvport.backend.task.RvPortTaskTypes;
import cn.rvport.backend.task.TaskCreateCommand;
import cn.rvport.backend.task.TaskRepository;
import cn.rvport.backend.task.TaskResponse;
import cn.rvport.backend.task.TaskService;
import cn.rvport.backend.workflow.DomainLatestExecutionResponse;
import cn.rvport.backend.workflow.DomainLatestFreshness;
import cn.rvport.backend.workflow.DomainLatestResponse;
import cn.rvport.backend.workflow.ProjectContextService;
import cn.rvport.backend.workflow.ProjectContextSnapshot;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KbScanService {

    private static final String DEFAULT_SCOPE_MODE = "ALL";
    private static final List<String> DEFAULT_EXCLUDE_PATHS = List.of(".git", "build");

    private final KbScanRepository kbScanRepository;
    private final SourceRepository sourceRepository;
    private final ProjectAccessService projectAccessService;
    private final TaskService taskService;
    private final TaskRepository taskRepository;
    private final ProjectContextService projectContextService;
    private final AuditLogService auditLogService;
    private final RvPortProperties properties;
    private final StoredArtifactService storedArtifactService;
    private final NodeReportInclusionService nodeReportInclusionService;
    @Autowired
    private KbScanContextService scanContextService;
    @Autowired
    private cn.rvport.backend.analysis.SourceAnalysisPlanService sourcePlans;

    @Autowired
    public KbScanService(
            KbScanRepository kbScanRepository,
            SourceRepository sourceRepository,
            ProjectAccessService projectAccessService,
            TaskService taskService,
            TaskRepository taskRepository,
            ProjectContextService projectContextService,
            AuditLogService auditLogService,
            RvPortProperties properties,
            StoredArtifactService storedArtifactService,
            NodeReportInclusionService nodeReportInclusionService
    ) {
        this.kbScanRepository = kbScanRepository;
        this.sourceRepository = sourceRepository;
        this.projectAccessService = projectAccessService;
        this.taskService = taskService;
        this.taskRepository = taskRepository;
        this.projectContextService = projectContextService;
        this.auditLogService = auditLogService;
        this.properties = properties;
        this.storedArtifactService = storedArtifactService;
        this.nodeReportInclusionService = nodeReportInclusionService;
    }

    KbScanService(
            KbScanRepository kbScanRepository,
            SourceRepository sourceRepository,
            ProjectAccessService projectAccessService,
            TaskService taskService,
            AuditLogService auditLogService,
            RvPortProperties properties,
            StoredArtifactService storedArtifactService,
            NodeReportInclusionService nodeReportInclusionService
    ) {
        this(kbScanRepository, sourceRepository, projectAccessService, taskService, null, null, auditLogService,
                properties, storedArtifactService, nodeReportInclusionService);
    }

    @Transactional
    public TaskResponse create(UUID projectId, KbScanRequest request, UUID actorUserId, boolean superAdmin, HttpServletRequest httpRequest) {
        projectAccessService.requireProjectAccess(projectId, actorUserId, superAdmin);
        scanContextService.lock(projectId);
        SourceRevisionView sourceRevision = resolveSourceRevision(projectId, request == null ? null : request.sourceRevisionId());
        validateWorkingCopy(sourceRevision);
        KbScanContextService.Selection selection = scanContextService.resolve(projectId, sourceRevision.id());

        String language = defaultText(request == null ? null : request.language(), defaultLanguage());
        String targetArch = defaultText(properties.kbScan() == null ? null : properties.kbScan().targetArch(), "riscv64");
        String sourceArch = normalizeSourceArch(defaultText(request == null ? null : request.sourceArch(), defaultSourceArch()));
        String ruleVersion = blankToNull(request == null ? null : request.ruleVersion());
        Map<String, Object> scanScope = scanScope(request == null ? null : request.scanScope());
        List<String> excludePaths = excludePaths(request == null ? null : request.excludePaths());
        var identity = cn.rvport.backend.analysis.AnalysisInputPlan.Identity.of(language,
                defaultText(request == null ? null : request.sourceArch(), defaultSourceArch()), targetArch,
                scanScope, excludePaths, rulesDigest());
        var plan = sourcePlans.createKb(sourceRevision, identity);
        if (selection.ready()) {
            var artifact = storedArtifactService.findByOwner(cn.rvport.backend.artifact.StoredArtifactOwnerType.BUILD_VALIDATION,
                    selection.build().id(), cn.rvport.backend.buildvalidation.BuildValidationArtifacts.COMPILE_COMMANDS_JSON);
            if (artifact.isPresent() && storedArtifactService.objectExists(artifact.get()))
                plan = sourcePlans.enhance(plan, selection.build(), List.of(artifact.get()));
        }
        KbExecutionContext executionContext = plan.inputOrigin() == cn.rvport.backend.analysis.AnalysisInputPlan.InputOrigin.SOURCE_DISCOVERY
                ? new KbExecutionContext(null, null, null, plan)
                : new KbExecutionContext(selection.sourceEnvironmentId(), selection.runtimeEnvironmentVersion(), selection.build().id(), plan);
        language = String.join(",", identity.effectiveLanguages());
        sourceArch = identity.effectiveSourceArch();

        Map<String, Object> inputSnapshot = new LinkedHashMap<>();
        inputSnapshot.put("projectId", projectId);
        inputSnapshot.put("sourceRevisionId", sourceRevision.id());
        inputSnapshot.put("ruleVersion", ruleVersion);
        inputSnapshot.put("language", language);
        inputSnapshot.put("sourceArch", sourceArch);
        inputSnapshot.put("targetArch", targetArch);
        inputSnapshot.put("scanScope", scanScope);
        inputSnapshot.put("excludePaths", excludePaths);
        inputSnapshot.put("executionContext", executionContext);

        TaskResponse task = taskService.create(new TaskCreateCommand(
                projectId,
                RvPortTaskTypes.KB_SCAN,
                inputSnapshot,
                actorUserId,
                timeoutSeconds(),
                "KB 扫描任务已创建"
        ));
        KbScanInput runInput = new KbScanInput(projectId, null, sourceRevision.id(), ruleVersion, language, sourceArch, targetArch, scanScope, excludePaths, executionContext);
        UUID runId = kbScanRepository.createRun(runInput, task.id());
        auditLogService.record(projectId, actorUserId, "kb.scan.create", "kb_scan_run", runId,
                Map.of("taskId", task.id(), "sourceRevisionId", sourceRevision.id()), httpRequest);
        return task;
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResponse<KbScanResponse> list(UUID projectId, KbScanListRequest request, UUID actorUserId, boolean superAdmin) {
        projectAccessService.requireProjectAccess(projectId, actorUserId, superAdmin);
        int page = request.normalizedPage();
        int pageSize = request.normalizedPageSize();
        try {
            KbScanContextService.Selection selection = scanContextService.current(projectId);
            if (request.effectiveCurrentOnly() && (selection.sourceRevisionId() == null
                    || (request.sourceRevisionId() != null && !Objects.equals(request.sourceRevisionId(), selection.sourceRevisionId())))) {
                return PageResponse.of(List.of(), page, pageSize, 0);
            }
            KbScanListRequest effectiveRequest = request.effectiveCurrentOnly()
                    ? request.withContext(selection.sourceRevisionId(), selection.executionContext()) : request;
            long total = kbScanRepository.countRuns(projectId, effectiveRequest);
            return PageResponse.of(kbScanRepository.listRuns(projectId, effectiveRequest).stream()
                    .map(run -> response(run, selection))
                    .toList(), effectiveRequest.normalizedPage(), effectiveRequest.normalizedPageSize(), total);
        } catch (IllegalArgumentException ex) {
            throw badRequest("KB_SCAN_QUERY_INVALID", "KB 扫描查询参数不正确");
        }
    }

    private String rulesDigest() {
        String configured = properties.kbScan() == null ? null : properties.kbScan().rulesetPath();
        Path rules = Path.of(defaultText(configured, "rulesets/riscv-porting"));
        if (!rules.isAbsolute()) rules = Path.of(defaultText(properties.kbScan() == null ? null : properties.kbScan().scannerRoot(),
                "./tools/kb-scanner")).resolve(rules);
        if (!Files.exists(rules) && !rules.isAbsolute() && Files.exists(Path.of("..").resolve(rules))) rules = Path.of("..").resolve(rules);
        try { return cn.rvport.backend.analysis.AnalysisDirectoryFingerprint.digest(rules.toAbsolutePath().normalize()); }
        catch (java.io.IOException ex) { throw new ApiException(HttpStatus.CONFLICT, "ANALYSIS_RULES_UNAVAILABLE", "扫描规则不可用 / Scan rules are unavailable"); }
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public KbScanResponse get(UUID scanId, UUID actorUserId, boolean superAdmin) {
        KbScanView run = requireAccessibleRun(scanId, actorUserId, superAdmin);
        return response(run, scanContextService.current(run.projectId()));
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public DomainLatestResponse<KbScanResponse> latest(UUID projectId, UUID actorUserId, boolean superAdmin) {
        projectAccessService.requireProjectAccess(projectId, actorUserId, superAdmin);
        KbScanContextService.Selection selection = scanContextService.current(projectId);
        ProjectContextSnapshot context = selection.snapshot();
        KbScanResponse published = kbScanRepository.findLatestPublishedByContext(projectId, selection.sourceRevisionId(), selection.executionContext())
                .filter(run -> selection.staleReason(run) == null)
                .map(run -> response(run, selection)).orElse(null);
        var latest = kbScanRepository.findLatestHistoricalRun(projectId)
                .map(run -> new DomainLatestResponse<>(
                        context,
                        selection.staleReason(run) == null ? DomainLatestFreshness.CURRENT : DomainLatestFreshness.STALE,
                        DomainLatestExecutionResponse.from(run.id(), taskRepository.findById(run.taskId()).orElse(null)),
                        response(run, selection)))
                .orElseGet(() -> DomainLatestResponse.empty(context));
        return new DomainLatestResponse<>(latest.context(), latest.freshness(), latest.execution(), latest.result(), published);
    }

    private KbScanResponse response(KbScanView run, KbScanContextService.Selection selection) {
        boolean enhancement = false;
        if (run.sourceOnly() && selection.ready() && selection.staleReason(run) == null) {
            var artifact = storedArtifactService.findByOwner(StoredArtifactOwnerType.BUILD_VALIDATION, selection.build().id(),
                    cn.rvport.backend.buildvalidation.BuildValidationArtifacts.COMPILE_COMMANDS_JSON).orElse(null);
            if (artifact != null && storedArtifactService.objectExists(artifact)) {
                var plan = run.effectiveInputPlan() == null ? run.executionContext().inputPlan() : run.effectiveInputPlan();
                enhancement = sourcePlans.enhance(plan, selection.build(), List.of(artifact)).inputOrigin()
                        == cn.rvport.backend.analysis.AnalysisInputPlan.InputOrigin.BUILD_ARTIFACTS;
            }
        }
        return KbScanResponse.from(run, selection, enhancement);
    }

    @Transactional(readOnly = true)
    public PageResponse<FindingResponse> findings(UUID scanId, FindingListRequest request, UUID actorUserId, boolean superAdmin) {
        KbScanView run = requireAccessibleRun(scanId, actorUserId, superAdmin);
        int page = request.normalizedPage();
        int pageSize = request.normalizedPageSize();
        try {
            long total = kbScanRepository.countFindings(run.id(), request);
            List<FindingView> rawViews = kbScanRepository.listFindingViews(run.id(), request);
            List<UUID> findingIds = rawViews.stream().map(FindingView::id).toList();
            Map<UUID, NodeReportInclusionState> states = nodeReportInclusionService.states(
                    run.projectId(),
                    run.sourceRevisionId(),
                    MigrationInputSourceType.KB_FINDING,
                    findingIds
            );
            Map<UUID, NodeReportInclusionState> inclusionStates = states == null ? Map.of() : states;
            List<FindingResponse> items = rawViews.stream()
                    .map(view -> FindingResponse.from(view, inclusionStates.get(view.id())))
                    .toList();
            return PageResponse.of(items, page, pageSize, total);
        } catch (IllegalArgumentException ex) {
            throw badRequest("FINDING_QUERY_INVALID", "Finding 查询参数不正确");
        }
    }

    @Transactional(readOnly = true)
    public FindingResponse finding(UUID findingId, UUID actorUserId, boolean superAdmin) {
        FindingView finding = requireAccessibleFinding(findingId, actorUserId, superAdmin);
        NodeReportInclusionState state = nodeReportInclusionService.state(
                finding.projectId(),
                finding.sourceRevisionId(),
                MigrationInputSourceType.KB_FINDING,
                finding.id()
        );
        return FindingResponse.from(finding, state);
    }

    @Transactional(readOnly = true)
    public PageResponse<KbScanArtifactSummaryResponse> artifacts(UUID scanId, KbScanArtifactListRequest request, UUID actorUserId, boolean superAdmin) {
        KbScanView run = requireAccessibleRun(scanId, actorUserId, superAdmin);
        int page = request.normalizedPage();
        int pageSize = request.normalizedPageSize();
        try {
            long total = kbScanRepository.countArtifacts(run.id(), request);
            return PageResponse.of(kbScanRepository.listArtifacts(run.id(), request), page, pageSize, total);
        } catch (IllegalArgumentException ex) {
            throw badRequest("KB_SCAN_ARTIFACT_QUERY_INVALID", "KB 扫描 artifact 查询参数不正确");
        }
    }

    @Transactional(readOnly = true)
    public DownloadableKbScanArtifact downloadArtifact(UUID scanId, UUID artifactId, UUID actorUserId, boolean superAdmin) {
        KbScanView run = requireAccessibleRun(scanId, actorUserId, superAdmin);
        KbScanArtifactDownload artifact = kbScanRepository.findArtifact(run.id(), artifactId)
                .orElseThrow(() -> notFound("KB_SCAN_ARTIFACT_NOT_FOUND", "KB 扫描 artifact 不存在"));
        return downloadableArtifact(artifact);
    }

    @Transactional(readOnly = true)
    public DownloadableKbScanArtifact downloadArtifactByFormat(UUID scanId, String format, UUID actorUserId, boolean superAdmin) {
        KbScanArtifactType artifactType;
        try {
            artifactType = KbScanArtifactType.fromDownloadFormat(format);
        } catch (IllegalArgumentException ex) {
            throw badRequest("KB_SCAN_ARTIFACT_FORMAT_INVALID", "KB 扫描 artifact 下载格式不正确");
        }
        KbScanView run = requireAccessibleRun(scanId, actorUserId, superAdmin);
        KbScanArtifactDownload artifact = kbScanRepository.findArtifactByType(run.id(), artifactType)
                .orElseThrow(() -> notFound("KB_SCAN_ARTIFACT_NOT_FOUND", "KB 扫描 artifact 不存在"));
        return downloadableArtifact(artifact);
    }

    @Transactional(readOnly = true)
    public FindingCodeContextResponse codeContext(UUID findingId, UUID actorUserId, boolean superAdmin) {
        FindingView finding = requireAccessibleFinding(findingId, actorUserId, superAdmin);
        if (finding.filePath() == null || finding.filePath().isBlank() || finding.lineStart() == null) {
            throw notFound("FINDING_SOURCE_LOCATION_UNAVAILABLE", "Finding 源码位置不可用");
        }
        SourceRevisionView revision = sourceRepository.findById(finding.sourceRevisionId())
                .orElseThrow(() -> notFound("SOURCE_REVISION_NOT_FOUND", "源码版本不存在"));
        Path root = Path.of(revision.workingCopyPath()).toAbsolutePath().normalize();
        Path file;
        try {
            file = ServerPathGuard.resolveExistingRelativeFileUnderRoot(root, finding.filePath());
            List<String> allLines = Files.readAllLines(file);
            int contextLines = contextLines();
            int highlightStart = finding.lineStart();
            int highlightEnd = finding.lineEnd() == null ? highlightStart : finding.lineEnd();
            int start = Math.max(1, highlightStart - contextLines);
            int end = Math.min(allLines.size(), highlightEnd + contextLines);
            List<FindingCodeContextResponse.CodeLine> lines = java.util.stream.IntStream.rangeClosed(start, end)
                    .mapToObj(lineNumber -> new FindingCodeContextResponse.CodeLine(
                            lineNumber,
                            allLines.get(lineNumber - 1),
                            lineNumber >= highlightStart && lineNumber <= highlightEnd
                    ))
                    .toList();
            return new FindingCodeContextResponse(
                    finding.id(),
                    finding.scanRunId(),
                    finding.filePath(),
                    contextLines,
                    contextLines,
                    start,
                    end,
                    highlightStart,
                    highlightEnd,
                    lines
            );
        } catch (ApiException ex) {
            throw ex;
        } catch (ServerPathException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("exist")) {
                throw notFound("FINDING_SOURCE_FILE_NOT_FOUND", "Finding 源码文件不存在");
            }
            throw badRequest("FINDING_SOURCE_PATH_INVALID", "Finding 源码路径非法");
        } catch (Exception ex) {
            throw notFound("FINDING_SOURCE_FILE_NOT_FOUND", "Finding 源码文件不存在");
        }
    }

    private DownloadableKbScanArtifact downloadableArtifact(KbScanArtifactDownload artifact) {
        if (artifact.storedArtifactId() != null) {
            StoredArtifactResource storedArtifact = storedArtifactService.download(artifact.storedArtifactId());
            return new DownloadableKbScanArtifact(
                    storedArtifact.resource(),
                    artifactFilename(artifact),
                    storedArtifact.artifact().contentType()
            );
        }
        try {
            Path path = ServerPathGuard.resolveExistingFileUnderRoot(outputRoot(), artifact.contentPath());
            Resource resource = new UrlResource(path.toUri());
            return new DownloadableKbScanArtifact(resource, artifactFilename(artifact), contentType(artifact.artifactType()));
        } catch (ServerPathException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("outside")) {
                throw badRequest("KB_SCAN_ARTIFACT_PATH_INVALID", "KB 扫描 artifact 路径非法");
            }
            throw notFound("KB_SCAN_ARTIFACT_FILE_NOT_FOUND", "KB 扫描 artifact 文件不存在");
        } catch (Exception ex) {
            throw notFound("KB_SCAN_ARTIFACT_FILE_NOT_FOUND", "KB 扫描 artifact 文件不存在");
        }
    }

    private FindingView requireAccessibleFinding(UUID findingId, UUID actorUserId, boolean superAdmin) {
        FindingView finding = kbScanRepository.findFinding(findingId)
                .orElseThrow(() -> notFound("FINDING_NOT_FOUND", "Finding 不存在"));
        projectAccessService.requireProjectAccess(finding.projectId(), actorUserId, superAdmin);
        return finding;
    }

    private KbScanView requireAccessibleRun(UUID scanId, UUID actorUserId, boolean superAdmin) {
        KbScanView run = kbScanRepository.findById(scanId)
                .orElseThrow(() -> notFound("KB_SCAN_NOT_FOUND", "KB 扫描不存在"));
        projectAccessService.requireProjectAccess(run.projectId(), actorUserId, superAdmin);
        return run;
    }

    private SourceRevisionView resolveSourceRevision(UUID projectId, UUID sourceRevisionId) {
        SourceRevisionView sourceRevision = sourceRevisionId == null
                ? sourceRepository.findActive(projectId).orElseThrow(() -> badRequest("SOURCE_REVISION_ACTIVE_NOT_FOUND", "项目没有可用的 active source revision"))
                : sourceRepository.findById(sourceRevisionId).orElseThrow(() -> notFound("SOURCE_REVISION_NOT_FOUND", "源码版本不存在"));
        if (!Objects.equals(sourceRevision.projectId(), projectId)) {
            throw notFound("SOURCE_REVISION_NOT_FOUND", "源码版本不存在");
        }
        return sourceRevision;
    }

    private void validateWorkingCopy(SourceRevisionView sourceRevision) {
        if (sourceRevision.workingCopyPath() == null || sourceRevision.workingCopyPath().isBlank()) {
            throw badRequest("SOURCE_WORKING_COPY_NOT_FOUND", "源码工作副本不存在");
        }
        if (!Files.isDirectory(Path.of(sourceRevision.workingCopyPath()))) {
            throw badRequest("SOURCE_WORKING_COPY_NOT_FOUND", "源码工作副本不存在");
        }
    }

    private Map<String, Object> scanScope(KbScanScope scope) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", defaultText(scope == null ? null : scope.mode(), DEFAULT_SCOPE_MODE));
        result.put("paths", scope == null || scope.paths() == null ? List.of() : scope.paths().stream().filter(item -> item != null && !item.isBlank()).toList());
        return result;
    }

    private List<String> excludePaths(List<String> excludePaths) {
        if (excludePaths == null || excludePaths.isEmpty()) {
            return DEFAULT_EXCLUDE_PATHS;
        }
        return excludePaths.stream().filter(item -> item != null && !item.isBlank()).map(String::trim).toList();
    }

    private int timeoutSeconds() {
        Integer configured = properties.kbScan() == null ? null : properties.kbScan().timeoutSeconds();
        return configured == null ? 1800 : configured;
    }

    private int contextLines() {
        Integer configured = properties.kbScan() == null ? null : properties.kbScan().contextLines();
        return configured == null ? 20 : Math.max(0, configured);
    }

    private Path outputRoot() {
        String configured = properties.kbScan() == null ? null : properties.kbScan().outputDir();
        return resolvePath(defaultText(configured, "./backend/runtime/kb-scans"));
    }

    private Path resolvePath(String value) {
        Path configured = Path.of(value);
        Path path = configured.toAbsolutePath().normalize();
        if (configured.isAbsolute()) {
            return path;
        }
        if (Files.exists(path)) {
            return path;
        }
        Path parentRelative = Path.of("..", value).toAbsolutePath().normalize();
        return Files.exists(parentRelative) ? parentRelative : path;
    }

    private String defaultLanguage() {
        return defaultText(properties.kbScan() == null ? null : properties.kbScan().language(), "c,cpp");
    }

    private String defaultSourceArch() {
        return defaultText(properties.kbScan() == null ? null : properties.kbScan().sourceArch(), "x86");
    }

    private String normalizeSourceArch(String value) {
        // 项目侧架构名（x86_64/amd64/aarch64/arm64）归一化为扫描器的源架构族（x86/arm64）。
        if (value == null) {
            return "x86";
        }
        return switch (value.trim().toLowerCase()) {
            case "x86", "x86_64", "amd64", "i386", "i686", "x64" -> "x86";
            case "arm64", "aarch64", "arm" -> "arm64";
            default -> "x86";
        };
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    private ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private String artifactFilename(KbScanArtifactDownload artifact) {
        return switch (artifact.artifactType()) {
            case "SCAN_REPORT_HTML" -> "scan-report-" + artifact.scanRunId() + ".html";
            case "SCAN_RESULT_JSON" -> "scan-result-" + artifact.scanRunId() + ".json";
            case "SCAN_LOG" -> "scan-log-" + artifact.scanRunId() + ".log";
            default -> "kb-scan-artifact-" + artifact.id();
        };
    }

    private String contentType(String artifactType) {
        return switch (artifactType) {
            case "SCAN_REPORT_HTML" -> "text/html";
            case "SCAN_RESULT_JSON" -> "application/json";
            case "SCAN_LOG" -> "text/plain";
            default -> "application/octet-stream";
        };
    }

    public record DownloadableKbScanArtifact(Resource resource, String filename, String contentType) {
    }
}
