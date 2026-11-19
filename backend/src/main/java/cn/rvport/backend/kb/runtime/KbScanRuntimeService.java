package cn.rvport.backend.kb.runtime;

import cn.rvport.backend.artifact.StoredArtifactOwnerType;
import cn.rvport.backend.artifact.StoredArtifactResource;
import cn.rvport.backend.artifact.StoredArtifactService;
import cn.rvport.backend.artifact.StoredArtifactView;
import cn.rvport.backend.archive.ArchiveCandidateRegistrar;
import cn.rvport.backend.buildvalidation.BuildValidationArtifacts;
import cn.rvport.backend.buildvalidation.BuildValidationRepository;
import cn.rvport.backend.buildvalidation.BuildValidationRunView;
import cn.rvport.backend.config.RvPortProperties;
import cn.rvport.backend.common.path.ServerPathGuard;
import cn.rvport.backend.kb.KbFindingInput;
import cn.rvport.backend.kb.KbScanArtifact;
import cn.rvport.backend.kb.KbScanArtifactType;
import cn.rvport.backend.kb.KbScanInput;
import cn.rvport.backend.kb.KbScanRepository;
import cn.rvport.backend.kb.KbScanView;
import cn.rvport.backend.kb.KbScannerResult;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentRepository;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentView;
import cn.rvport.backend.source.SourceRepository;
import cn.rvport.backend.source.SourceRevisionView;
import cn.rvport.backend.task.TaskErrorCode;
import cn.rvport.backend.task.TaskExecutionException;
import cn.rvport.backend.task.TaskLogType;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.task.ToolRunRepository;
import cn.rvport.backend.worker.TaskCancelledException;
import cn.rvport.backend.worker.TaskExecutionContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

@Service
public class KbScanRuntimeService {

    private static final String TOOL_NAME = "kb-scanner";
    private static final String DEFAULT_SCHEMA_VERSION = "2.0";
    private static final int LOG_LIMIT = 100_000;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final KbScanRepository kbScanRepository;
    private final SourceRepository sourceRepository;
    private final ToolRunRepository toolRunRepository;
    private final RvPortProperties properties;
    private final ObjectMapper objectMapper;
    private final StoredArtifactService storedArtifactService;
    private final ArchiveCandidateRegistrar archiveCandidateRegistrar;
    private final BuildValidationRepository buildValidationRepository;
    private final ProjectRuntimeEnvironmentRepository runtimeEnvironmentRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private cn.rvport.backend.kb.KbScanContextService scanContextService;

    public KbScanRuntimeService(
            KbScanRepository kbScanRepository,
            SourceRepository sourceRepository,
            ToolRunRepository toolRunRepository,
            RvPortProperties properties,
            ObjectMapper objectMapper,
            StoredArtifactService storedArtifactService,
            ArchiveCandidateRegistrar archiveCandidateRegistrar,
            BuildValidationRepository buildValidationRepository,
            ProjectRuntimeEnvironmentRepository runtimeEnvironmentRepository
    ) {
        this.kbScanRepository = kbScanRepository;
        this.sourceRepository = sourceRepository;
        this.toolRunRepository = toolRunRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.storedArtifactService = storedArtifactService;
        this.archiveCandidateRegistrar = archiveCandidateRegistrar;
        this.buildValidationRepository = buildValidationRepository;
        this.runtimeEnvironmentRepository = runtimeEnvironmentRepository;
    }

    public void scan(KbScanInput input, TaskExecutionContext context) {
        validate(input, context);
        KbScanView run = kbScanRepository.findByTaskId(context.task().id())
                .orElseThrow(() -> new TaskExecutionException(TaskErrorCode.HANDLER_FAILED, "KB 扫描运行记录不存在", null));
        SourceRevisionView sourceRevision = sourceRepository.findById(run.sourceRevisionId())
                .orElseThrow(() -> new TaskExecutionException(TaskErrorCode.HANDLER_FAILED, "源码版本不存在", null));
        UUID toolRunId = null;
        String phase = "PREPARE";
        Path outputDir = outputDir(run);
        try {
            context.throwIfCancellationRequested();
            BuildValidationRunView boundBuild;
            try {
                boundBuild = scanContextService.requireExecution(run, input);
            } catch (cn.rvport.backend.workflow.PrerequisiteNotSatisfiedException ex) {
                finishFailed(context, run.id(), null, TaskStatus.FAILED,
                        Map.of("phase", phase, "reasonCode", "KB_SCAN_CONTEXT_INVALID",
                                "message", "KB 扫描执行上下文已失效，请重新构建并扫描",
                                "message_en", "KB scan context is stale; rebuild and scan again"));
                throw new TaskExecutionException(TaskErrorCode.INPUT_INVALID,
                        "KB 扫描执行上下文已失效: " + ex.details().get("prerequisite"), null);
            }
            context.publish(() -> { kbScanRepository.markRunning(run.id()); return null; });
            context.progress(5, "准备 KB 扫描");
            Files.createDirectories(outputDir);
            Path sourceRoot = sourceRoot(sourceRevision);
            if (!Files.isDirectory(sourceRoot)) {
                fail(context, run.id(), null, phase, null, "源码工作副本不存在", null);
            }

            toolRunId = context.publish(() -> toolRunRepository.create(
                    run.projectId(),
                    run.taskId(),
                    TOOL_NAME,
                    null,
                    "KB 源码扫描",
                    TaskStatus.RUNNING,
                    OffsetDateTime.now()
            ));

            phase = "SCAN";
            context.progress(15, "执行 KB Scanner");
            CompileCommandsContext compileCommands = resolveCompileCommands(boundBuild, run, sourceRoot, outputDir, context);
            ProcessResult rawProcess;
            try {
                rawProcess = runScanner(input, sourceRoot, outputDir, compileCommands.path(), context);
            } finally {
                deleteQuietly(compileCommands.path());
            }
            ProcessResult process = sanitize(rawProcess, sourceRoot, outputDir);
            context.log(TaskLogType.STDOUT, truncate(process.stdout(), LOG_LIMIT));
            context.log(TaskLogType.STDERR, truncate(process.stderr(), LOG_LIMIT));
            if (process.exitCode() != 0) {
                fail(context, run.id(), toolRunId, phase, process.exitCode(), "KB Scanner 执行失败", process);
            }
            context.throwIfCancellationRequested();

            phase = "IMPORT_RESULT";
            context.progress(75, "导入 KB 扫描结果");
            Path resultFile = outputDir.resolve("scan-result.json");
            Path reportFile = outputDir.resolve("scan-report.html");
            Path logFile = outputDir.resolve("scan.log");
            if (!Files.exists(resultFile)) {
                fail(context, run.id(), toolRunId, phase, process.exitCode(), "KB Scanner 未生成 scan-result.json", process);
            }
            if (Files.size(resultFile) > maxOutputBytes()) {
                fail(context, run.id(), toolRunId, phase, process.exitCode(), "KB Scanner JSON 输出超过限制", process);
            }
            KbScannerResult result = objectMapper.readValue(resultFile.toFile(), KbScannerResult.class);
            if (!DEFAULT_SCHEMA_VERSION.equals(result.schemaVersion())) {
                fail(context, run.id(), toolRunId, phase, process.exitCode(), "KB Scanner 结果版本不兼容", process);
            }
            sanitizeResultArtifact(sourceRoot, outputRoot(), resultFile);
            sanitizeTextArtifact(sourceRoot, outputRoot(), reportFile);
            sanitizeTextArtifact(sourceRoot, outputRoot(), logFile);
            List<KbFindingInput> findings = mapFindings(run, sourceRoot, result);
            context.publish(() -> {
                kbScanRepository.replaceFindings(run.id(), findings);
                return null;
            });
            Map<String, Object> summary = sanitizeMap(
                    safeSummary(result, findings.size(), compileCommands.summary()), sourceRoot, outputRoot());


            List<StoredArtifactView> artifacts = new ArrayList<>();
            artifacts.add(saveArtifact(context, run, KbScanArtifactType.SCAN_RESULT_JSON, resultFile, Map.of("findingCount", findings.size(), "schemaVersion", result.schemaVersion())));
            if (Files.exists(reportFile)) {
                artifacts.add(saveArtifact(context, run, KbScanArtifactType.SCAN_REPORT_HTML, reportFile, Map.of("format", "html")));
            }
            if (Files.exists(logFile)) {
                artifacts.add(saveArtifact(context, run, KbScanArtifactType.SCAN_LOG, logFile, Map.of("format", "text")));
            }

            try {
                context.publish(() -> { archiveCandidateRegistrar.registerKbScan(run, summary, artifacts); return null; });
                summary.put("archiveCandidate", Map.of("registered", true));
            } catch (cn.rvport.backend.worker.TaskAttemptLostException ex) {
                throw ex;
            } catch (Exception ex) {
                summary.put("archiveCandidate", Map.of("registered", false, "warning", "ARCHIVE_CANDIDATE_REGISTRATION_FAILED"));
                context.log(TaskLogType.STDERR, "KB 扫描结果已导入，但归档候选登记失败");
            }
            UUID completedToolId = toolRunId;
            context.publish(() -> {
            kbScanRepository.markSucceeded(run.id(), summary);
            toolRunRepository.finish(completedToolId, TaskStatus.SUCCEEDED, process.exitCode(), sanitizeMap(Map.of(
                    "durationMs", process.duration().toMillis(),
                    "summary", summary
            ), sourceRoot, outputDir));
            return null;
            });
            context.progress(95, "KB 扫描结果已导入");
        } catch (cn.rvport.backend.worker.TaskAttemptLostException ex) {
            throw ex;
        } catch (TaskCancelledException ex) {
            finishFailed(context, run.id(), toolRunId, TaskStatus.CANCELLED, phaseSummary(phase, "KB 扫描任务已取消"));
            throw ex;
        } catch (TaskExecutionException ex) {
            throw ex;
        } catch (Exception ex) {
            finishFailed(context, run.id(), toolRunId, TaskStatus.FAILED, phaseSummary(phase, "KB 扫描失败"));
            throw new TaskExecutionException(TaskErrorCode.HANDLER_FAILED, "KB 扫描失败", ex);
        }
    }

    private ProcessResult runScanner(KbScanInput input, Path sourceRoot, Path outputDir, Path compileCommandsPath, TaskExecutionContext context) throws IOException, InterruptedException {
        List<String> command = command(input, sourceRoot, outputDir, compileCommandsPath);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(scannerRoot().toFile());
        long started = System.nanoTime();
        Process process = builder.start();
        context.registerLocalProcess(process, Duration.ofSeconds(5));
        CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
        CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
        boolean finished = process.waitFor(timeoutSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new ProcessResult(124, stdout.join(), append(stderr.join(), "KB Scanner timeout"), Duration.ofNanos(System.nanoTime() - started));
        }
        return new ProcessResult(process.exitValue(), stdout.join(), stderr.join(), Duration.ofNanos(System.nanoTime() - started));
    }

    private List<String> command(KbScanInput input, Path sourceRoot, Path outputDir, Path compileCommandsPath) {
        List<String> command = new ArrayList<>(splitCommand(pythonCommand()));
        command.add("-m");
        command.add("kb_scanner");
        command.add("scan");
        command.add("--input");
        command.add(sourceRoot.toString());
        command.add("--rules");
        command.add(rulesetPath().toString());
        command.add("--output");
        command.add(outputDir.toString());
        command.add("--format");
        command.add("json,html");
        command.add("--schema-version");
        command.add(DEFAULT_SCHEMA_VERSION);
        command.add("--language");
        command.add(defaultText(input.language(), defaultLanguage()));
        command.add("--target-arch");
        command.add(defaultText(input.targetArch(), defaultTargetArch()));
        command.add("--exclude");
        command.add(String.join(",", safeList(input.excludePaths())));
        if (compileCommandsPath != null) {
            command.add("--compile-commands");
            command.add(compileCommandsPath.toString());
        }
        return command;
    }

    private CompileCommandsContext resolveCompileCommands(BuildValidationRunView buildRun, KbScanView run, Path sourceRoot, Path outputDir, TaskExecutionContext context) {
        Map<String, Object> compileDatabase = mapValue(buildRun.buildFacts().get("compileDatabase"));
        if (!Boolean.TRUE.equals(compileDatabase.get("available"))) {
            return withBuild(CompileCommandsContext.notAvailable("COMPILE_DATABASE_NOT_AVAILABLE"), buildRun);
        }
        UUID artifactId = uuidValue(compileDatabase.get("storedArtifactId"));
        if (artifactId == null) {
            return withBuild(CompileCommandsContext.fallback("ARTIFACT_ID_MISSING"), buildRun);
        }
        try {
            StoredArtifactResource resource = storedArtifactService.download(artifactId);
            if (resource.artifact().ownerType() != StoredArtifactOwnerType.BUILD_VALIDATION
                    || !resource.artifact().ownerId().equals(buildRun.id())
                    || !resource.artifact().projectId().equals(run.projectId())
                    || !BuildValidationArtifacts.COMPILE_COMMANDS_JSON.equals(resource.artifact().artifactType())) {
                return withBuild(CompileCommandsContext.fallback("ARTIFACT_OWNER_MISMATCH"), buildRun);
            }
            Path compileCommandsPath = outputDir.resolve("compile_commands.input.json").toAbsolutePath().normalize();
            boolean pathRewritten = writeLocalCompileCommands(
                    compileCommandsPath,
                    resource.resource().getContentAsByteArray(),
                    buildRun.workingDirectory(),
                    sourceRoot
            );
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("mode", "USED");
            summary.put("source", "BOUND_SOURCE_BUILD");
            summary.put("buildRunId", buildRun.id());
            summary.put("storedArtifactId", artifactId);
            summary.put("rewritten", pathRewritten);
            putIfNotNull(summary, "sha256", compileDatabase.get("sha256"));
            putIfNotNull(summary, "sizeBytes", compileDatabase.get("sizeBytes"));
            putIfNotNull(summary, "commandCount", compileDatabase.get("commandCount"));
            summary.put("buildScope", buildRun.buildScope().name());
            context.log(TaskLogType.STDOUT, "RVPORT_OBSERVATION kbCompileCommands mode=USED" + System.lineSeparator());
            return new CompileCommandsContext(compileCommandsPath, summary);
        } catch (Exception ex) {
            context.log(TaskLogType.STDERR, "RVPORT_OBSERVATION_WARNING kbCompileCommandsUnavailable reason=ARTIFACT_DOWNLOAD_FAILED" + System.lineSeparator());
            return withBuild(CompileCommandsContext.fallback("ARTIFACT_DOWNLOAD_FAILED"), buildRun);
        }
    }

    private CompileCommandsContext withBuild(CompileCommandsContext commands, BuildValidationRunView build) {
        Map<String, Object> summary = new LinkedHashMap<>(commands.summary());
        summary.put("buildRunId", build.id());
        summary.put("source", "BOUND_SOURCE_BUILD");
        return new CompileCommandsContext(commands.path(), summary);
    }

    private boolean writeLocalCompileCommands(
            Path outputPath,
            byte[] content,
            String remoteSourceRoot,
            Path localSourceRoot
    ) throws IOException {
        JsonNode database = objectMapper.readTree(content);
        if (database == null || !database.isArray()) throw new IOException("Invalid compile database");
        for (JsonNode entry : database) {
            if (!entry.isObject() || !entry.path("file").isTextual() || entry.path("file").asText().isBlank()
                    || !(entry.path("command").isTextual() && !entry.path("command").asText().isBlank()
                        || entry.path("arguments").isArray() && !entry.path("arguments").isEmpty())) {
                throw new IOException("Invalid compile database entry");
            }
        }
        if (blankToNull(remoteSourceRoot) == null) {
            Files.write(outputPath, content);
            return false;
        }
        String remoteRoot = normalizeRemoteRoot(remoteSourceRoot);
        String localRoot = localSourceRoot.toAbsolutePath().normalize().toString();
        Object payload = objectMapper.readValue(content, Object.class);
        boolean rewritten = rewriteCompileCommandPaths(payload, remoteRoot, localRoot);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), payload);
        return rewritten;
    }

    @SuppressWarnings("unchecked")
    private boolean rewriteCompileCommandPaths(Object value, String remoteRoot, String localRoot) {
        if (value instanceof List<?> list) {
            boolean rewritten = false;
            for (Object item : list) {
                rewritten |= rewriteCompileCommandPaths(item, remoteRoot, localRoot);
            }
            return rewritten;
        }
        if (!(value instanceof Map<?, ?> rawMap)) {
            return false;
        }
        Map<String, Object> map = (Map<String, Object>) rawMap;
        boolean rewritten = false;
        rewritten |= rewriteStringField(map, "directory", remoteRoot, localRoot);
        rewritten |= rewriteStringField(map, "file", remoteRoot, localRoot);
        rewritten |= rewriteStringField(map, "command", remoteRoot, localRoot);
        Object arguments = map.get("arguments");
        if (arguments instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                Object item = list.get(index);
                if (item instanceof String text) {
                    String mapped = mapRemotePath(text, remoteRoot, localRoot);
                    if (!mapped.equals(text)) {
                        ((List<Object>) list).set(index, mapped);
                        rewritten = true;
                    }
                }
            }
        }
        return rewritten;
    }

    private boolean rewriteStringField(Map<String, Object> map, String field, String remoteRoot, String localRoot) {
        Object value = map.get(field);
        if (!(value instanceof String text)) {
            return false;
        }
        String mapped = mapRemotePath(text, remoteRoot, localRoot);
        if (mapped.equals(text)) {
            return false;
        }
        map.put(field, mapped);
        return true;
    }

    private String mapRemotePath(String text, String remoteRoot, String localRoot) {
        if (text.equals(remoteRoot)) {
            return localRoot;
        }
        String prefix = remoteRoot + "/";
        if (!text.startsWith(prefix)) {
            return text;
        }
        Path mapped = Path.of(localRoot).resolve(text.substring(prefix.length())).normalize();
        return mapped.toString();
    }

    private String normalizeRemoteRoot(String remoteSourceRoot) {
        String normalized = remoteSourceRoot.trim().replace('\\', '/');
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private List<KbFindingInput> mapFindings(KbScanView run, Path sourceRoot, KbScannerResult result) {
        return safeList(result.findings()).stream()
                .filter(Objects::nonNull)
                .map(finding -> {
                    KbScannerResult.Fact fact = finding.fact();
                    KbScannerResult.Conclusion conclusion = finding.conclusion();
                    KbScannerResult.Location location = primaryLocation(finding);
                    String filePath = relativePath(sourceRoot, location == null ? null : location.file());
                    Integer lineStart = line(location == null || location.span() == null ? null : location.span().start());
                    Integer lineEnd = line(location == null || location.span() == null ? null : location.span().end());
                    if (lineStart != null && lineEnd != null && lineEnd < lineStart) {
                        lineEnd = lineStart;
                    }
                    String category = defaultText(conclusion == null ? null : conclusion.findingType(), defaultText(fact == null ? null : fact.kind(), "issue"));
                    Map<String, Object> evidence = evidence(finding, sourceRoot);
                    String ruleId = defaultText(finding.id(), "UNKNOWN");
                    return new KbFindingInput(
                            run.projectId(),
                            run.id(),
                            run.sourceRevisionId(),
                            ruleId,
                            category,
                            normalizeSeverity(conclusion == null ? null : conclusion.severity()),
                            defaultText(conclusion == null ? null : conclusion.title(), "KB Scanner Finding"),
                            conclusion == null ? null : conclusion.reason(),
                            filePath,
                            lineStart,
                            lineEnd,
                            blankToNull(fact == null ? null : fact.symbol()),
                            evidence,
                            conclusion == null ? null : conclusion.suggestion(),
                            fingerprint(run.id(), ruleId, filePath, lineStart == null ? 0 : lineStart, fact == null ? null : fact.symbol())
                    );
                })
                .toList();
    }

    private Map<String, Object> evidence(KbScannerResult.Finding finding, Path sourceRoot) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schemaVersion", DEFAULT_SCHEMA_VERSION);
        evidence.put("finding", objectMapper.convertValue(finding, MAP_TYPE));
        putIfNotNull(evidence, "scannerFindingId", finding.id());
        KbScannerResult.Fact fact = finding.fact();
        KbScannerResult.Conclusion conclusion = finding.conclusion();
        if (fact != null) {
            putIfNotNull(evidence, "factKind", fact.kind());
            putIfNotNull(evidence, "symbol", fact.symbol());
            putIfNotNull(evidence, "matchedText", fact.matchedText());
        }
        if (conclusion != null) {
            putIfNotNull(evidence, "confidence", conclusion.confidence());
            putIfNotNull(evidence, "findingType", conclusion.findingType());
            putIfNotNull(evidence, "actionable", conclusion.actionable());
            putIfNotNull(evidence, "blocking", conclusion.blocking());
            putIfNotNull(evidence, "archReachability", conclusion.archReachability());
            putIfNotNull(evidence, "conclusionStatus", conclusion.status());
        }
        putIfNotNull(evidence, "primaryOccurrenceId", finding.primaryOccurrenceId());
        putIfNotNull(evidence, "occurrenceCount", finding.occurrences() == null ? null : finding.occurrences().size());
        return sanitizeEvidence(evidence, sourceRoot);
    }

    private KbScannerResult.Location primaryLocation(KbScannerResult.Finding finding) {
        if (finding == null) {
            return null;
        }
        if (finding.fact() != null && finding.fact().primaryLocation() != null) {
            return finding.fact().primaryLocation();
        }
        for (KbScannerResult.Occurrence occurrence : safeList(finding.occurrences())) {
            if (occurrence != null && occurrence.location() != null) {
                return occurrence.location();
            }
        }
        return null;
    }

    private Integer line(KbScannerResult.Position position) {
        if (position == null || position.line() == null) {
            return null;
        }
        return Math.max(1, position.line());
    }

    private Map<String, Object> sanitizeEvidence(Map<String, Object> evidence, Path sourceRoot) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : evidence.entrySet()) {
            sanitized.put(entry.getKey(), sanitizeValue(entry.getValue(), entry.getKey(), sourceRoot, outputRoot()));
        }
        return sanitized;
    }

    @SuppressWarnings("unchecked")
    private Object sanitizeValue(Object value, String fieldName, Path sourceRoot, Path outputRoot) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String childField = String.valueOf(entry.getKey());
                sanitized.put(childField, sanitizeValue(entry.getValue(), childField, sourceRoot, outputRoot));
            }
            return sanitized;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> sanitizeValue(item, fieldName, sourceRoot, outputRoot)).toList();
        }
        if (value instanceof String text) {
            return isPathField(fieldName)
                    ? sanitizeStructuredPath(text, sourceRoot, outputRoot)
                    : sanitizeKnownRootText(text, sourceRoot, outputRoot);
        }
        return value;
    }

    private boolean isPathField(String fieldName) {
        if (fieldName == null) {
            return false;
        }
        String normalized = fieldName.replaceAll("[^A-Za-z0-9]", "").toLowerCase();
        return normalized.equals("file") || normalized.equals("directory") || normalized.equals("cwd")
                || normalized.endsWith("path") || normalized.endsWith("paths") || normalized.endsWith("root");
    }

    private String sanitizeStructuredPath(String text, Path sourceRoot, Path outputRoot) {
        String sanitized = sanitizeKnownRootText(text, sourceRoot, outputRoot);
        String normalized = sanitized.replace('\\', '/');
        if (containsParentSegment(normalized)) {
            return "<server-path-redacted>";
        }
        return ServerPathGuard.redactPathText(sanitized, List.of(), "<server-path-redacted>");
    }

    private boolean containsParentSegment(String value) {
        for (String segment : value.split("/", -1)) {
            if ("..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private String sanitizeKnownRootText(String text, Path sourceRoot, Path outputRoot) {
        String sanitized = ServerPathGuard.redactKnownPathRoots(text, List.of(sourceRoot), "<source-root>");
        return ServerPathGuard.redactKnownPathRoots(sanitized, List.of(outputRoot), "<kb-output-root>");
    }

    private String sanitizePathText(String text, Path sourceRoot, Path outputRoot) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String sanitized = ServerPathGuard.redactPathText(text, sourceRoot, "<source-root>");
        return ServerPathGuard.redactPathText(sanitized, outputRoot, "<kb-output-root>");
    }

    private ProcessResult sanitize(ProcessResult process, Path sourceRoot, Path outputDir) {
        return new ProcessResult(
                process.exitCode(),
                sanitizePathText(process.stdout(), sourceRoot, outputDir),
                sanitizePathText(process.stderr(), sourceRoot, outputDir),
                process.duration()
        );
    }

    private Map<String, Object> sanitizeMap(Map<String, Object> value, Path sourceRoot, Path outputRoot) {
        @SuppressWarnings("unchecked")
        Map<String, Object> sanitized = (Map<String, Object>) sanitizeValue(value, null, sourceRoot, outputRoot);
        return sanitized;
    }

    private void sanitizeResultArtifact(Path sourceRoot, Path outputDir, Path resultFile) throws IOException {
        JsonNode root = objectMapper.readTree(resultFile.toFile());
        JsonNode sanitized = sanitizeJsonNode(root, null, sourceRoot, outputDir);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(resultFile.toFile(), sanitized);
        if (objectMapper.readTree(resultFile.toFile()) == null) {
            throw new IOException("脱敏后的 KB Scanner JSON 为空");
        }
    }

    private JsonNode sanitizeJsonNode(JsonNode node, String fieldName, Path sourceRoot, Path outputDir) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode sanitized = objectMapper.createObjectNode();
            node.fields().forEachRemaining(entry -> sanitized.set(
                    entry.getKey(), sanitizeJsonNode(entry.getValue(), entry.getKey(), sourceRoot, outputDir)));
            return sanitized;
        }
        if (node.isArray()) {
            ArrayNode sanitized = objectMapper.createArrayNode();
            node.forEach(item -> sanitized.add(sanitizeJsonNode(item, fieldName, sourceRoot, outputDir)));
            return sanitized;
        }
        if (node.isTextual()) {
            String text = node.textValue();
            return TextNode.valueOf(isPathField(fieldName)
                    ? sanitizeStructuredPath(text, sourceRoot, outputDir)
                    : sanitizeKnownRootText(text, sourceRoot, outputDir));
        }
        return node.deepCopy();
    }

    private void sanitizeTextArtifact(Path sourceRoot, Path outputDir, Path file) throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        String content = Files.readString(file, StandardCharsets.UTF_8);
        Files.writeString(file, sanitizeKnownRootText(content, sourceRoot, outputDir), StandardCharsets.UTF_8);
    }

    private Map<String, Object> safeSummary(KbScannerResult result, int findingCount, Map<String, Object> compileCommands) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("schemaVersion", result.schemaVersion());
        summary.put("tool", result.scan() == null || result.scan().tool() == null ? Map.of() : result.scan().tool());
        summary.put("scan", result.scan() == null ? Map.of() : objectMapper.convertValue(result.scan(), MAP_TYPE));
        summary.put("findingCount", findingCount);
        summary.put("summary", result.summary() == null ? Map.of() : result.summary());
        summary.put("diagnosticCount", result.diagnostics() == null ? 0 : result.diagnostics().size());
        summary.put("diagnostics", result.diagnostics() == null ? List.of() : result.diagnostics());
        summary.put("compileCommands", compileCommands == null ? CompileCommandsContext.notAvailable("NOT_RESOLVED").summary() : compileCommands);
        return summary;
    }

    private StoredArtifactView saveArtifact(TaskExecutionContext context, KbScanView run, KbScanArtifactType type, Path path, Map<String, Object> summary) throws IOException {
        StoredArtifactView storedArtifact = storedArtifactService.storeFile(
                run.projectId(),
                run.taskId(),
                StoredArtifactOwnerType.KB_SCAN,
                run.id(),
                type.name(),
                path,
                contentType(type),
                summary,
                extension(type), context
        );
        KbScanArtifact artifact = new KbScanArtifact(
                run.projectId(),
                run.taskId(),
                run.id(),
                type,
                DEFAULT_SCHEMA_VERSION,
                sha256(Files.readAllBytes(path)),
                Files.size(path),
                path.toAbsolutePath().normalize().toString(),
                storedArtifact.id(),
                summary
        );
        context.publish(() -> { kbScanRepository.upsertArtifact(artifact); return null; });
        return storedArtifact;
    }

    private String contentType(KbScanArtifactType type) {
        return switch (type) {
            case SCAN_RESULT_JSON -> "application/json";
            case SCAN_REPORT_HTML -> "text/html";
            case SCAN_LOG -> "text/plain";
        };
    }

    private String extension(KbScanArtifactType type) {
        return switch (type) {
            case SCAN_RESULT_JSON -> "json";
            case SCAN_REPORT_HTML -> "html";
            case SCAN_LOG -> "log";
        };
    }

    private void fail(TaskExecutionContext context, UUID runId, UUID toolRunId, String phase, Integer exitCode, String message, ProcessResult process) {
        Map<String, Object> summary = phaseSummary(phase, message);
        context.publish(() -> {
        kbScanRepository.markFailed(runId, summary);
        if (toolRunId != null) {
            Map<String, Object> toolSummary = new LinkedHashMap<>(summary);
            if (process != null) {
                toolSummary.put("durationMs", process.duration().toMillis());
            }
            toolRunRepository.finish(toolRunId, TaskStatus.FAILED, exitCode, toolSummary);
        }
        return null;
        });
        throw new TaskExecutionException(TaskErrorCode.HANDLER_FAILED, message, null);
    }

    private void finishFailed(TaskExecutionContext context, UUID runId, UUID toolId, TaskStatus status, Map<String, Object> summary) {
        context.publish(() -> {
            if (status == TaskStatus.CANCELLED) kbScanRepository.markCancelled(runId, summary);
            else kbScanRepository.markFailed(runId, summary);
            if (toolId != null) toolRunRepository.finish(toolId, status, null, summary);
            return null;
        });
    }

    private void validate(KbScanInput input, TaskExecutionContext context) {
        if (input == null || input.projectId() == null || !input.projectId().equals(context.task().projectId())) {
            throw new TaskExecutionException(TaskErrorCode.INPUT_INVALID, "KB 扫描任务输入非法", null);
        }
    }

    private Path sourceRoot(SourceRevisionView sourceRevision) {
        if (sourceRevision.workingCopyPath() == null || sourceRevision.workingCopyPath().isBlank()) {
            throw new TaskExecutionException(TaskErrorCode.HANDLER_FAILED, "源码工作副本不存在", null);
        }
        return Path.of(sourceRevision.workingCopyPath()).toAbsolutePath().normalize();
    }

    private Path outputDir(KbScanView run) {
        return outputRoot()
                .resolve(run.projectId().toString())
                .resolve(run.taskId().toString())
                .resolve(run.id().toString())
                .toAbsolutePath()
                .normalize();
    }

    private Path outputRoot() {
        String configured = properties.kbScan() == null ? null : properties.kbScan().outputDir();
        return resolvePath(defaultText(configured, "./backend/runtime/kb-scans"));
    }

    private Path scannerRoot() {
        String configured = properties.kbScan() == null ? null : properties.kbScan().scannerRoot();
        return resolvePath(defaultText(configured, "./tools/kb-scanner"));
    }

    private Path rulesetPath() {
        String configured = properties.kbScan() == null ? null : properties.kbScan().rulesetPath();
        Path path = Path.of(defaultText(configured, "rulesets/riscv-porting"));
        return path.isAbsolute() ? path.normalize() : path.normalize();
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

    private String pythonCommand() {
        return properties.kbScan() == null ? "python" : defaultText(properties.kbScan().pythonCommand(), "python");
    }

    private String defaultLanguage() {
        return properties.kbScan() == null ? "c,cpp" : defaultText(properties.kbScan().language(), "c,cpp");
    }

    private String defaultTargetArch() {
        return properties.kbScan() == null ? "riscv64" : defaultText(properties.kbScan().targetArch(), "riscv64");
    }

    private int timeoutSeconds() {
        Integer configured = properties.kbScan() == null ? null : properties.kbScan().timeoutSeconds();
        return configured == null ? 1800 : configured;
    }

    private long maxOutputBytes() {
        Long configured = properties.kbScan() == null ? null : properties.kbScan().maxOutputBytes();
        return configured == null ? 10_485_760L : configured;
    }

    private String relativePath(Path sourceRoot, String file) {
        if (file == null || file.isBlank()) {
            return null;
        }
        try {
            Path raw = Path.of(file);
            Path normalized = raw.isAbsolute() ? raw.normalize() : sourceRoot.resolve(raw).normalize();
            if (normalized.startsWith(sourceRoot)) {
                return sourceRoot.relativize(normalized).toString().replace('\\', '/');
            }
            return raw.getFileName() == null ? null : raw.getFileName().toString();
        } catch (Exception ex) {
            return null;
        }
    }

    private List<String> splitCommand(String command) {
        if (command == null || command.isBlank()) {
            return List.of("python");
        }
        return List.of(command.trim().split("\\s+"));
    }

    private String read(java.io.InputStream inputStream) {
        try (inputStream) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }

    private String append(String left, String right) {
        return (left == null || left.isBlank()) ? right : left + System.lineSeparator() + right;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength) + "\n...日志已截断...";
    }

    private Map<String, Object> phaseSummary(String phase, String message) {
        return Map.of("phase", phase, "error", message);
    }

    private String fingerprint(UUID runId, String ruleId, String filePath, int line, String symbol) {
        return sha256((runId + "|" + defaultText(ruleId, "") + "|" + defaultText(filePath, "") + "|" + line + "|" + defaultText(symbol, ""))
                .getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private <T> List<T> safeList(List<T> value) {
        return value == null ? List.of() : value;
    }

    private void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
            return result;
        }
        return Map.of();
    }

    private UUID uuidValue(Object value) {
        if (value instanceof UUID id) {
            return id;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时 compile database 清理失败不影响扫描结果，输出目录清理由运行时维护流程兜底。
        }
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String normalizeSeverity(String value) {
        return defaultText(value, "info").trim().toLowerCase();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record ProcessResult(int exitCode, String stdout, String stderr, Duration duration) {
    }

    private record CompileCommandsContext(Path path, Map<String, Object> summary) {
        static CompileCommandsContext notAvailable(String reason) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("mode", "NOT_AVAILABLE");
            summary.put("reason", reason);
            return new CompileCommandsContext(null, summary);
        }

        static CompileCommandsContext fallback(String reason) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("mode", "FALLBACK");
            summary.put("reason", reason);
            return new CompileCommandsContext(null, summary);
        }
    }
}
