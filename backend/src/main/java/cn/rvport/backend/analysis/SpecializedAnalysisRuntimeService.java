package cn.rvport.backend.analysis;

import cn.rvport.backend.artifact.StoredArtifactOwnerType;
import cn.rvport.backend.artifact.StoredArtifactResource;
import cn.rvport.backend.artifact.StoredArtifactService;
import cn.rvport.backend.artifact.StoredArtifactView;
import cn.rvport.backend.config.RvPortProperties;
import cn.rvport.backend.common.path.ServerPathGuard;
import cn.rvport.backend.buildvalidation.bitcode.BitcodeManifest;
import cn.rvport.backend.buildvalidation.bitcode.BitcodeReasonCode;
import cn.rvport.backend.buildvalidation.bitcode.BitcodeRuntimeException;
import cn.rvport.backend.buildvalidation.bitcode.BitcodeVerificationFailure;
import cn.rvport.backend.buildvalidation.bitcode.BitcodeVerificationStage;
import cn.rvport.backend.project.ProjectRepository;
import cn.rvport.backend.project.ProjectView;
import cn.rvport.backend.source.SourceRepository;
import cn.rvport.backend.source.SourceRevisionView;
import cn.rvport.backend.task.TaskErrorCode;
import cn.rvport.backend.worker.TaskCancelledException;
import cn.rvport.backend.worker.TaskAttemptLostException;
import cn.rvport.backend.worker.TaskExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class SpecializedAnalysisRuntimeService {

    private static final String REQUEST_ARTIFACT = "ANALYSIS_REQUEST_JSON";
    private static final String RESULT_JSON_ARTIFACT = "ANALYSIS_RESULT_JSON";
    private static final String RESULT_HTML_ARTIFACT = "ANALYSIS_RESULT_HTML";
    private static final String AUDIT_BUNDLE_ARTIFACT = "ANALYSIS_AUDIT_BUNDLE_TGZ";
    private static final Map<String, String> LANGUAGE_STANDARD_ALIASES = Map.ofEntries(
            Map.entry("c89", "c89"), Map.entry("c90", "c89"), Map.entry("iso9899:1990", "c89"),
            Map.entry("iso9899:199409", "c89"), Map.entry("gnu89", "c89"), Map.entry("gnu90", "c89"),
            Map.entry("c99", "c99"), Map.entry("c9x", "c99"), Map.entry("gnu99", "c99"),
            Map.entry("gnu9x", "c99"), Map.entry("c11", "c11"), Map.entry("c1x", "c11"),
            Map.entry("gnu11", "c11"), Map.entry("gnu1x", "c11"), Map.entry("c17", "c17"),
            Map.entry("c18", "c17"), Map.entry("gnu17", "c17"), Map.entry("gnu18", "c17"),
            Map.entry("c23", "c23"), Map.entry("c2x", "c23"), Map.entry("gnu23", "c23"),
            Map.entry("gnu2x", "c23"), Map.entry("c++98", "c++98"), Map.entry("gnu++98", "c++98"),
            Map.entry("c++03", "c++03"), Map.entry("gnu++03", "c++03"), Map.entry("c++11", "c++11"),
            Map.entry("c++0x", "c++11"), Map.entry("gnu++11", "c++11"), Map.entry("gnu++0x", "c++11"),
            Map.entry("c++14", "c++14"), Map.entry("c++1y", "c++14"), Map.entry("gnu++14", "c++14"),
            Map.entry("gnu++1y", "c++14"), Map.entry("c++17", "c++17"), Map.entry("c++1z", "c++17"),
            Map.entry("gnu++17", "c++17"), Map.entry("gnu++1z", "c++17"), Map.entry("c++20", "c++20"),
            Map.entry("c++2a", "c++20"), Map.entry("gnu++20", "c++20"), Map.entry("gnu++2a", "c++20"),
            Map.entry("c++23", "c++23"), Map.entry("c++2b", "c++23"), Map.entry("gnu++23", "c++23"),
            Map.entry("gnu++2b", "c++23"));
    private final SpecializedAnalysisRepository repository;
    private final SpecializedAnalysisCompletionService completionService;
    private final StoredArtifactService artifactService;
    private final SourceRepository sourceRepository;
    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper;
    private final SpecializedAnalysisProcessRunner processRunner;
    private final SharedBitcodeArtifactVerifier bitcodeArtifactVerifier;
    private final SourceAnalysisPlanService sourcePlans;
    private final RvPortProperties.SpecializedAnalysis properties;

    public SpecializedAnalysisRuntimeService(
            SpecializedAnalysisRepository repository, SpecializedAnalysisCompletionService completionService,
            StoredArtifactService artifactService,
            SourceRepository sourceRepository, ProjectRepository projectRepository,
            ObjectMapper objectMapper, RvPortProperties RvPortProperties,
            SpecializedAnalysisProcessRunner processRunner,
            SharedBitcodeArtifactVerifier bitcodeArtifactVerifier, SourceAnalysisPlanService sourcePlans
    ) {
        this.repository = repository;
        this.completionService = completionService;
        this.artifactService = artifactService;
        this.sourceRepository = sourceRepository;
        this.projectRepository = projectRepository;
        this.objectMapper = objectMapper;
        this.properties = RvPortProperties.specializedAnalysis();
        this.processRunner = processRunner;
        this.bitcodeArtifactVerifier = bitcodeArtifactVerifier;
        this.sourcePlans = sourcePlans;
    }

    public void runTask(SpecializedAnalysisTaskInput input, TaskExecutionContext context) {
        if (input.inputPlan() == null) {
            context.publish(() -> {
                repository.fail(input.runId(), SpecializedAnalysisStatus.FAILED, Map.of("reasonCode", "ANALYSIS_INPUT_PLAN_REQUIRED",
                        "resultAvailable", false, "message", "旧任务没有固定输入计划，请重新发起分析。",
                        "message_en", "This legacy task has no immutable input plan; start a new analysis."), null, null, 0);
                return null;
            });
            throw new cn.rvport.backend.task.TaskExecutionException("ANALYSIS_INPUT_PLAN_REQUIRED",
                    "旧任务没有固定输入计划，请重新发起 / Start a new analysis with an immutable input plan", null);
        }
        run(input, context);
    }

    public void run(SpecializedAnalysisTaskInput input, TaskExecutionContext context) {
        Path workspace = null;
        Path sourceLocalCache = null;
        Path resultJson = null;
        JsonNode resultDocument = null;
        Integer processExitCode = null;
        PreparedInput prepared = null;
        cn.rvport.backend.report.review.ReportSourceMaterializer.Materialized fixedSource = null;
        try {
            context.publish(() -> { repository.markRunning(input.runId(), toolVersion(input.analyzerType())); return null; });
            context.progress(5, "正在解析专项分析输入制品");
            workspace = createWorkspace(context.task().id());
            if (input.inputPlan() != null) {
                SourceRevisionView source = sourceRepository.findById(input.sourceRevisionId()).orElseThrow();
                fixedSource = sourcePlans.materialize(input.inputPlan(), source);
            }
            SpecializedAnalysisTaskInput executionInput = resumeInput(input);
            prepared = materialize(executionInput, workspace, fixedSource == null ? null : fixedSource.sourceRoot());
            SpecializedBitcodeCapability capability = prepared.capability();
            AnalysisInputPlan effectivePlan = executionInput.inputPlan();
            if (effectivePlan != null && effectivePlan.inputOrigin() == AnalysisInputPlan.InputOrigin.BUILD_ARTIFACTS) {
                if (prepared.compileDatabase() == null) effectivePlan = effectivePlan.sourceFallback("OPTIONAL_ARTIFACT_UNAVAILABLE");
                else if (!capability.hasUsableBitcode()) effectivePlan = effectivePlan.withoutBitcode("BITCODE_UNAVAILABLE");
            }
            AnalysisInputPlan actualPlan = effectivePlan;
            if (actualPlan != null) context.publish(() -> {
                repository.storeEffectivePlan(input.runId(), input.inputPlan(), actualPlan);
                return null;
            });
            context.publish(() -> { repository.updateEvidenceMode(input.runId(), capability); return null; });
            context.throwIfCancellationRequested();
            context.progress(20, "正在生成分析器请求");
            PreparedRequest preparedRequest = prepareRequest(executionInput, prepared, workspace, context);
            Path request = preparedRequest.request();
            sourceLocalCache = preparedRequest.sourceLocalCache();
            validateRequest(input.analyzerType(), request, workspace, context);
            context.progress(30, "正在执行" + input.analyzerType().displayName());
            SpecializedAnalysisProcessRunner.ProcessResult result = executeAnalysis(
                    input.analyzerType(), request, workspace, context);
            processExitCode = result.exitCode();
            context.throwIfCancellationRequested();
            resultJson = locateResult(input.analyzerType(), workspace);
            resultDocument = readSafeResult(resultJson, prepared.sourceRoot(), workspace);
            objectMapper.writeValue(resultJson.toFile(), resultDocument);
            validateResult(input.analyzerType(), resultDocument, result.exitCode());
            JsonNode document = resultDocument;
            context.progress(80, "正在导入专项分析结果");
            List<SpecializedFindingImport> findings = parseFindings(input.analyzerType(), document);
            Map<String, Object> summary = summary(input.analyzerType(), document, prepared, findings);
            if (actualPlan != null) summary.putAll(AnalysisEvidenceSummary.from(actualPlan));
            Path manifest = (sourceLocalCache == null ? workspace.resolve("cache") : sourceLocalCache)
                    .resolve("source-analysis-plan.json");
            JsonNode inventory = null;
            if (Files.isRegularFile(manifest, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                if (Files.size(manifest) > 32L * 1024 * 1024)
                    throw new SpecializedAnalysisRuntimeException("ANALYSIS_SOURCE_MANIFEST_LIMIT_EXCEEDED");
                inventory = objectMapper.readTree(manifest.toFile());
            }
            summary.putAll(AnalysisEvidenceSummary.execution(input.analyzerType(), document, inventory));
            Path html = renderHtml(input.analyzerType(), input.runId(), summary, findings, workspace);
            Path audit = input.analyzerType() == SpecializedAnalyzerType.MEMORY_ORDER
                    ? archiveDirectory(workspace.resolve("output"), workspace.resolve("memory-order-audit.tgz")) : null;
            StoredArtifactView requestArtifact = artifactService.storeFile(input.projectId(), context.task().id(),
                    StoredArtifactOwnerType.SPECIALIZED_ANALYSIS, input.runId(), REQUEST_ARTIFACT, request,
                    "application/json", Map.of("analyzerType", input.analyzerType().name()), "json", context);
            StoredArtifactView jsonArtifact = artifactService.storeLargeFile(input.projectId(), context.task().id(),
                    StoredArtifactOwnerType.SPECIALIZED_ANALYSIS, input.runId(), RESULT_JSON_ARTIFACT, resultJson,
                    "application/json", summary, "json", context);
            StoredArtifactView htmlArtifact = artifactService.storeFile(input.projectId(), context.task().id(),
                    StoredArtifactOwnerType.SPECIALIZED_ANALYSIS, input.runId(), RESULT_HTML_ARTIFACT, html,
                    "text/html;charset=UTF-8", summary, "html", context);
            StoredArtifactView auditArtifact = audit == null ? null : artifactService.storeLargeFile(input.projectId(),
                    context.task().id(), StoredArtifactOwnerType.SPECIALIZED_ANALYSIS, input.runId(), AUDIT_BUNDLE_ARTIFACT,
                    audit, "application/gzip", Map.of("analyzerType", input.analyzerType().name()), "tgz", context);
            StatusMapping mapped = mapStatus(input.analyzerType(), document, result.exitCode());
            context.publish(() -> {
                completionService.complete(input, mapped.status(), mapped.verdict(), result.exitCode(), summary,
                        requestArtifact, jsonArtifact, htmlArtifact, auditArtifact, findings);
                return null;
            });
            context.progress(100, input.analyzerType().displayName() + "完成");
        } catch (TaskAttemptLostException ex) {
            throw ex;
        } catch (TaskCancelledException ex) {
            context.publish(() -> { repository.fail(input.runId(), SpecializedAnalysisStatus.CANCELLED, ex.getMessage()); return null; });
            throw ex;
        } catch (Exception ex) {
            FailureAudit audit = persistFailureResult(input, context, resultJson, resultDocument,
                    processExitCode, prepared, workspace, ex);
            Integer failedExitCode = processExitCode;
            context.publish(() -> {
                repository.fail(input.runId(), SpecializedAnalysisStatus.FAILED, audit.summary(), failedExitCode,
                        audit.resultArtifactId(), audit.diagnosticCount());
                return null;
            });
            if (ex instanceof SpecializedAnalysisRuntimeException runtime) throw runtime;
            throw new SpecializedAnalysisRuntimeException(input.analyzerType().displayName() + "失败: " + safeMessage(ex), ex);
        } finally {
            deleteTree(workspace);
            deleteTree(sourceLocalCache);
            deleteEmptyDirectory(sourceLocalCache == null ? null : sourceLocalCache.getParent());
            if (fixedSource != null) {
                try { fixedSource.close(); }
                catch (IOException ignored) { /* 临时源码清理失败不能把已发布分析改为失败。 */ }
            }
        }
    }

    SpecializedAnalysisTaskInput resumeInput(SpecializedAnalysisTaskInput input) {
        if (input.inputPlan() == null) return input;
        AnalysisInputPlan actual = repository.effectivePlan(input.runId()).orElse(input.inputPlan());
        if (!actual.originalInputDigest().equals(input.inputPlan().effectiveInputDigest()))
            throw new SpecializedAnalysisRuntimeException("ANALYSIS_INPUT_PLAN_MISMATCH");
        if (actual.effectiveInputDigest().equals(input.inputPlan().effectiveInputDigest())) return input;
        boolean sourceOnly = actual.inputOrigin() == AnalysisInputPlan.InputOrigin.SOURCE_DISCOVERY;
        return new SpecializedAnalysisTaskInput(input.projectId(), input.runId(), input.sourceRevisionId(),
                sourceOnly ? null : input.buildRunId(), sourceOnly ? null : input.compileDatabaseArtifactId(),
                null, null, null, SpecializedAnalysisMode.AST_ONLY, "MISSING", 0, 0, 0,
                sourceOnly ? "OPTIONAL_ARTIFACT_UNAVAILABLE" : "BITCODE_UNAVAILABLE",
                sourceOnly ? null : input.environmentConfigVersion(), input.analyzerType(), actual);
    }

    private PreparedInput materialize(SpecializedAnalysisTaskInput input, Path workspace) throws IOException {
        return materialize(input, workspace, null);
    }

    private PreparedInput materialize(SpecializedAnalysisTaskInput input, Path workspace, Path fixedSourceRoot) throws IOException {
        SourceRevisionView source = sourceRepository.findById(input.sourceRevisionId())
                .filter(value -> value.projectId().equals(input.projectId()))
                .orElseThrow(() -> new SpecializedAnalysisRuntimeException("源码版本不存在"));
        Path sourceRoot = fixedSourceRoot == null ? Path.of(source.workingCopyPath()).toAbsolutePath().normalize() : fixedSourceRoot;
        if (!Files.isDirectory(sourceRoot) || Files.isSymbolicLink(sourceRoot)) {
            throw new SpecializedAnalysisRuntimeException("源码工作副本不可用");
        }
        if (input.compileDatabaseArtifactId() == null) {
            AnalysisInputPlan plan = input.inputPlan();
            if (plan == null || plan.inputOrigin() != AnalysisInputPlan.InputOrigin.SOURCE_DISCOVERY
                    || input.buildRunId() != null || input.bitcodeManifestArtifactId() != null
                    || input.bitcodeBundleArtifactId() != null || input.artifactSetId() != null
                    || !plan.projectId().equals(input.projectId()) || !plan.sourceRevisionId().equals(input.sourceRevisionId())) {
                throw new SpecializedAnalysisRuntimeException("ANALYSIS_PLAN_INVALID");
            }
            var capability = new SpecializedBitcodeCapability(SpecializedAnalysisMode.AST_ONLY, "MISSING", 0, 0, 0,
                    "SOURCE_DISCOVERY", null, null, null, Map.of("inputPlan", plan.summary()));
            return new PreparedInput(sourceRoot, null, null, null, capability);
        }
        Path inputDir = workspace.resolve("input");
        Path artifacts = inputDir.resolve("shared-bitcode");
        Files.createDirectories(artifacts);
        Path compileDatabase = inputDir.resolve("compile_commands.json");
        Path platformManifest = artifacts.resolve("platform-manifest.json");
        Path bundle = inputDir.resolve("shared-bitcode.tgz");
        String remoteRoot = null;
        CompileEntryClassifier entryClassifier = new CompileEntryClassifier(objectMapper);
        CompileEntryClassification classification;
        try {
            writeBoundArtifact(input, input.compileDatabaseArtifactId(), "COMPILE_COMMANDS_JSON", compileDatabase, properties.maxOutputBytes());
            if (input.inputPlan() != null) {
                Object root = artifactService.download(input.compileDatabaseArtifactId()).artifact().summary().get("remoteSourceRoot");
                if (!(root instanceof String path) || !path.startsWith("/") || path.equals("/")
                        || path.chars().anyMatch(Character::isISOControl))
                    throw new SpecializedAnalysisRuntimeException("ANALYSIS_ARTIFACT_IDENTITY_MISMATCH");
                remoteRoot = path;
            }
            classification = entryClassifier.classify(compileDatabase, sourceRoot, remoteRoot);
            if (input.inputPlan() != null && classification.projectTranslationUnitCount() == 0)
                throw new SpecializedAnalysisRuntimeException("ANALYSIS_COMPILE_CONTEXT_UNAVAILABLE");
        } catch (Exception unavailable) {
            if (input.inputPlan() == null) throw unavailable;
            return new PreparedInput(sourceRoot, null, null, null,
                    new SpecializedBitcodeCapability(SpecializedAnalysisMode.AST_ONLY, "MISSING", 0, 0, 0,
                            "OPTIONAL_ARTIFACT_UNAVAILABLE", null, null, null));
        }
        SpecializedBitcodeCapability capability = withClassification(inputCapability(input), classification);
        if (capability.hasUsableBitcode()) {
            try {
                writeBoundArtifact(input, input.bitcodeManifestArtifactId(), "LLVM_BITCODE_MANIFEST_JSON", platformManifest, 8L * 1024L * 1024L);
                writeBoundArtifact(input, input.bitcodeBundleArtifactId(), "LLVM_BITCODE_BUNDLE_TGZ", bundle, properties.maxExtractedBytes());
                verifySha(input.bitcodeManifestArtifactId(), platformManifest);
                verifySha(input.bitcodeBundleArtifactId(), bundle);
                BitcodeManifest verifiedManifest = bitcodeArtifactVerifier.verify(platformManifest, bundle,
                        properties.maxFiles(), properties.maxExtractedBytes(), properties.maxOutputBytes());
                if (input.inputPlan() != null && verifiedManifest != null)
                    SharedBitcodeArtifactVerifier.requireCompatible(verifiedManifest, input.inputPlan(), classification);
                extractBundle(bundle, artifacts);
                JsonNode manifest = objectMapper.readTree(platformManifest.toFile());
                String schema = manifest.path("schema_version").asText();
                if (!"SharedLlvmBitcodeArtifactSet".equals(manifest.path("kind").asText())
                        || !("2.0".equals(schema) || "2.1".equals(schema))
                        || !input.artifactSetId().equals(manifest.path("artifact_set_id").asText())) {
                    throw new SpecializedAnalysisRuntimeException("Shared LLVM BC manifest 身份不匹配");
                }
                if (verifiedManifest != null) {
                    capability = verifiedCapability(capability, classification,
                            entryClassifier.coverage(classification, verifiedManifest));
                }
            } catch (BitcodeRuntimeException ex) {
                deleteTree(artifacts);
                Files.createDirectories(artifacts);
                Map<String, Object> audit = new LinkedHashMap<>();
                audit.put("translationUnits", classification.safeSummary());
                if (ex.failure() != null) audit.put("validationFailure", ex.failure().safeSummary());
                capability = new SpecializedBitcodeCapability(SpecializedAnalysisMode.AST_ONLY, "INVALID",
                        classification.projectTranslationUnitCount(), 0, 0,
                        ex.reasonCode().name(), null, null, null, audit);
                platformManifest = null;
                artifacts = null;
            } catch (Exception ex) {
                deleteTree(artifacts);
                Files.createDirectories(artifacts);
                BitcodeVerificationFailure failure = new BitcodeVerificationFailure(
                        BitcodeVerificationStage.MATERIALIZATION,
                        BitcodeReasonCode.MATERIALIZATION_FAILED,
                        null, null, capability.artifactSetId(), null, null, false,
                        "共享 BC 物化失败。", "Shared BC materialization failed.",
                        "检查制品存储与解包预算后重试。",
                        "Check artifact storage and extraction budgets, then retry.");
                capability = new SpecializedBitcodeCapability(SpecializedAnalysisMode.AST_ONLY, "INVALID",
                        classification.projectTranslationUnitCount(), 0, 0,
                        failure.reasonCode().name(), null, null, null,
                        Map.of("translationUnits", classification.safeSummary(),
                                "validationFailure", failure.safeSummary()));
                platformManifest = null;
                artifacts = null;
            }
        }
        return new PreparedInput(sourceRoot, compileDatabase,
                capability.hasUsableBitcode() ? platformManifest : null,
                capability.hasUsableBitcode() ? artifacts : null, capability, remoteRoot);
    }

    private SpecializedBitcodeCapability inputCapability(SpecializedAnalysisTaskInput input) {
        return new SpecializedBitcodeCapability(input.analysisMode(), input.bcStatus(), input.bcPlannedCount(),
                input.bcUsableCount(), input.bcFailedCount(), input.bcDegradationReason(),
                null, null,
                input.artifactSetId());
    }

    private SpecializedBitcodeCapability withClassification(
            SpecializedBitcodeCapability value, CompileEntryClassification classification) {
        int planned = classification.projectTranslationUnitCount();
        return new SpecializedBitcodeCapability(value.mode(), value.status(), planned,
                Math.min(planned, value.usableCount()), Math.min(planned, value.failedCount()),
                value.degradationReason(), value.manifest(), value.bundle(), value.artifactSetId(),
                Map.of("translationUnits", classification.safeSummary()));
    }

    private SpecializedBitcodeCapability verifiedCapability(
            SpecializedBitcodeCapability value,
            CompileEntryClassification classification,
            CompileEntryClassifier.Coverage coverage) {
        SpecializedAnalysisMode mode = coverage.produced() == 0
                ? SpecializedAnalysisMode.AST_ONLY
                : coverage.produced() >= coverage.planned()
                ? SpecializedAnalysisMode.AST_BC_COMPLETE : SpecializedAnalysisMode.AST_BC_MIXED;
        String status = mode == SpecializedAnalysisMode.AST_BC_COMPLETE ? "READY"
                : mode == SpecializedAnalysisMode.AST_BC_MIXED ? "PARTIAL" : "INVALID";
        String reason = mode == SpecializedAnalysisMode.AST_BC_COMPLETE ? null
                : mode == SpecializedAnalysisMode.AST_BC_MIXED
                ? "BITCODE_PARTIAL_PROJECT_COVERAGE" : "BITCODE_HAS_NO_USABLE_PROJECT_RECORDS";
        Map<String, Object> tu = new LinkedHashMap<>(classification.safeSummary());
        tu.put("producedCount", coverage.produced());
        tu.put("failedCount", coverage.failed());
        tu.put("missingCount", coverage.missing());
        return new SpecializedBitcodeCapability(mode, status, coverage.planned(), coverage.produced(),
                coverage.failed() + coverage.missing(), reason, value.manifest(), value.bundle(),
                value.artifactSetId(), Map.of("translationUnits", tu));
    }

    private PreparedRequest prepareRequest(SpecializedAnalysisTaskInput input, PreparedInput prepared, Path workspace,
            TaskExecutionContext context) throws IOException {
        Path script = workspace.resolve("prepare_specialized_analysis.py");
        try (InputStream stream = new ClassPathResource("analysis/prepare_specialized_analysis.py").getInputStream()) {
            Files.copy(stream, script, StandardCopyOption.REPLACE_EXISTING);
        }
        try (InputStream stream = new ClassPathResource("analysis/source_analysis_plan.py").getInputStream()) {
            Files.copy(stream, workspace.resolve("source_analysis_plan.py"), StandardCopyOption.REPLACE_EXISTING);
        }
        Path request = workspace.resolve("request.json");
        Path sourceLocalCache = input.analyzerType() == SpecializedAnalyzerType.FLOATING_POINT
                ? prepared.sourceRoot().resolve(".rvport-analysis-cache").resolve(input.runId().toString()).normalize()
                : null;
        Path cache = sourceLocalCache == null ? workspace.resolve("cache") : sourceLocalCache;
        List<String> command = new ArrayList<>();
        command.add(input.analyzerType() == SpecializedAnalyzerType.FLOATING_POINT
                ? properties.floatingPython() : properties.memoryOrderPython());
        command.add(script.toString());
        command.add(input.analyzerType() == SpecializedAnalyzerType.FLOATING_POINT ? "floating" : "memory");
        if (prepared.platformManifest() != null) {
            add(command, "--manifest", prepared.platformManifest(), "--artifact-root", prepared.artifactRoot());
        }
        if (prepared.compileDatabase() != null) add(command, "--compile-database", prepared.compileDatabase());
        if (prepared.remoteSourceRoot() != null) add(command, "--remote-source-root", prepared.remoteSourceRoot());
        if (prepared.compileDatabase() != null && input.buildRunId() != null) add(command, "--build-run", input.buildRunId());
        add(command, "--source-root", prepared.sourceRoot(),
                "--output", request, "--run-id", input.runId(), "--source-revision", input.sourceRevisionId(),
                "--cache", cache, "--timeout", properties.timeoutSeconds(),
                "--max-frontend-output-bytes", properties.floatingFrontendOutputBytes());
        if (input.analyzerType() == SpecializedAnalyzerType.MEMORY_ORDER) {
            ProjectView project = projectRepository.findById(input.projectId()).orElseThrow();
            Path cancel = workspace.resolve("cancel.requested").toAbsolutePath();
            add(command, "--external-root", workspace.resolve("external-bundle"), "--workspace", workspace,
                    "--cancel-file", cancel, "--project-id", input.projectId(), "--project-name", project.name());
            context.registerCancellableResource(() -> {
                try {
                    Files.writeString(cancel, "cancelled\n", StandardCharsets.UTF_8);
                } catch (IOException ex) {
                    throw new SpecializedAnalysisRuntimeException("无法写入内存序分析取消标记", ex);
                }
            });
        }
        SpecializedAnalysisProcessRunner.ProcessResult result = processRunner.run(command, workspace, context,
                Duration.ofSeconds(120));
        if (result.exitCode() != 0 || !Files.isRegularFile(request)) {
            throw new SpecializedAnalysisRuntimeException("分析请求适配失败，exitCode=" + result.exitCode());
        }
        return new PreparedRequest(request, sourceLocalCache);
    }

    private void validateRequest(SpecializedAnalyzerType type, Path request, Path workspace, TaskExecutionContext context) {
        if (type != SpecializedAnalyzerType.FLOATING_POINT) return;
        SpecializedAnalysisProcessRunner.ProcessResult result = processRunner.run(List.of(properties.floatingScanner(), "validate-request", "--request", request.toString()),
                workspace, context, Duration.ofSeconds(120));
        if (result.exitCode() != 0) throw new SpecializedAnalysisRuntimeException("浮点分析请求校验失败");
    }

    private SpecializedAnalysisProcessRunner.ProcessResult executeAnalysis(SpecializedAnalyzerType type, Path request, Path workspace,
            TaskExecutionContext context) {
        Path output = workspace.resolve("output");
        if (type == SpecializedAnalyzerType.FLOATING_POINT) {
            try {
                Files.createDirectories(output);
            } catch (IOException ex) {
                throw new SpecializedAnalysisRuntimeException("无法创建输出目录", ex);
            }
        }
        List<String> command = type == SpecializedAnalyzerType.FLOATING_POINT
                ? List.of(properties.floatingScanner(), "scan", "--request", request.toString(),
                        "--output", output.resolve("result.json").toString(), "--clang", properties.floatingClang(),
                        "--ir-worker", properties.floatingIrWorker())
                : List.of(properties.memoryOrderPython(), "-m", "memory_order_analyzer", "analyze-project",
                        "--request", request.toString(), "--output", output.toString(),
                        "--artifact-store", workspace.resolve("cache").toString(),
                        "--ir-worker", properties.memoryOrderIrWorker(), "--source-worker", properties.memoryOrderSourceWorker());
        return processRunner.run(command, workspace, context, Duration.ofSeconds(properties.timeoutSeconds()));
    }

    Path locateResult(SpecializedAnalyzerType type, Path workspace) {
        Path result = type == SpecializedAnalyzerType.FLOATING_POINT
                ? workspace.resolve("output/result.json") : workspace.resolve("output/report.json");
        if (!Files.isRegularFile(result)) throw new SpecializedAnalysisRuntimeException("分析器未原子发布结果文件");
        try {
            if (Files.size(result) > properties.maxOutputBytes()) throw new SpecializedAnalysisRuntimeException("分析结果超过大小预算");
        } catch (IOException ex) { throw new SpecializedAnalysisRuntimeException("无法读取分析结果", ex); }
        return result;
    }

    JsonNode readSafeResult(Path result, Path sourceRoot, Path workspace) throws IOException {
        JsonNode document = objectMapper.readTree(result.toFile());
        if (document == null || !document.isObject()) {
            throw new SpecializedAnalysisRuntimeException("分析结果不是 JSON 对象");
        }
        return sanitizeJson(document, List.of(sourceRoot, workspace));
    }

    private void validateResult(SpecializedAnalyzerType type, JsonNode document, int exitCode) {
        if (type == SpecializedAnalyzerType.FLOATING_POINT) {
            String status = document.path("scan").path("status").asText();
            boolean statusMatchesExit = exitCode == 0 && "complete".equals(status)
                    || exitCode == 4 && "partial".equals(status);
            if (!"2.0".equals(document.path("schemaVersion").asText()) || !statusMatchesExit) {
                JsonNode diagnostic = primaryDiagnostic(document.path("diagnostics"));
                boolean retryable = diagnostic.path("retryable").asBoolean(false);
                String code = diagnostic.path("code").asText("unknown");
                String message = diagnostic.path("message").asText("浮点分析未提供中文诊断");
                String messageEn = diagnostic.path("messageEn").asText("Floating-point analysis did not provide an English diagnostic.");
                throw new SpecializedAnalysisRuntimeException(
                        retryable ? TaskErrorCode.ANALYSIS_RETRYABLE_RESOURCE : TaskErrorCode.ANALYSIS_NON_RETRYABLE,
                        "浮点分析结果 schema 或退出码不符合协议: exitCode=" + exitCode + ", status=" + status
                                + ", diagnostic=" + code + ", message=" + message + " / " + messageEn);
            }
        } else {
            String status = document.path("status").asText();
            String verdict = document.path("verdict").asText();
            if (exitCode == 2 && "cancelled".equals(status)) throw new TaskCancelledException("内存序分析已取消 / Memory-order analysis cancelled");
            boolean statusMatchesExit = exitCode == 0
                    && "complete".equals(status) && "no-finding-within-supported-model".equals(verdict)
                    || exitCode == 3 && "complete".equals(status) && "risk".equals(verdict)
                    || exitCode == 2 && "partial".equals(status)
                    && "inconclusive".equals(verdict);
            if (!"UnifiedMemoryOrderAnalysisReport".equals(document.path("kind").asText())
                    || document.path("schema_version").asInt() != 1 || !List.of(0, 2, 3).contains(exitCode)
                    || document.path("exit_code").asInt(-1) != exitCode || !statusMatchesExit) {
                throw new SpecializedAnalysisRuntimeException("内存序分析结果 schema、状态或退出码不符合协议");
            }
            MemoryOrderResultPolicy.validateVersion(document);
        }
    }

    private FailureAudit persistFailureResult(SpecializedAnalysisTaskInput input, TaskExecutionContext context,
            Path resultJson, JsonNode document, Integer exitCode, PreparedInput prepared, Path workspace, Exception ex) {
        Map<String, Object> summary = failureSummary(document, exitCode, ex);
        List<Path> roots = new ArrayList<>();
        if (prepared != null) roots.add(prepared.sourceRoot());
        if (workspace != null) roots.add(workspace);
        if (input.analyzerType() == SpecializedAnalyzerType.MEMORY_ORDER) {
            summary.putAll(document == null ? MemoryOrderResultPolicy.historicalSummary(Map.of())
                    : MemoryOrderResultPolicy.diagnostics(sanitizeJson(document, roots)));
        }
        if (resultJson == null || document == null || !Files.isRegularFile(resultJson)) {
            return new FailureAudit(summary, null, number(summary, "diagnosticCount"));
        }
        try {
            JsonNode sanitized = sanitizeJson(document, roots);
            objectMapper.writeValue(resultJson.toFile(), sanitized);
            StoredArtifactView artifact = artifactService.storeLargeFile(input.projectId(), context.task().id(),
                    StoredArtifactOwnerType.SPECIALIZED_ANALYSIS, input.runId(), RESULT_JSON_ARTIFACT, resultJson,
                    "application/json", summary, "json", context);
            return new FailureAudit(summary, artifact.id(), number(summary, "diagnosticCount"));
        } catch (TaskAttemptLostException lost) {
            throw lost;
        } catch (Exception auditFailure) {
            Map<String, Object> minimal = new LinkedHashMap<>(summary);
            minimal.put("resultArtifactStored", false);
            minimal.put("artifactError", "失败结果未通过安全持久化校验");
            return new FailureAudit(minimal, null, number(summary, "diagnosticCount"));
        }
    }

    private Map<String, Object> failureSummary(JsonNode document, Integer exitCode, Exception ex) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("error", safeMessage(ex));
        if (exitCode != null) summary.put("exitCode", exitCode);
        JsonNode diagnostics = document == null ? objectMapper.createArrayNode() : document.path("diagnostics");
        int count = diagnostics.isArray() ? diagnostics.size() : 0;
        summary.put("diagnosticCount", count);
        JsonNode primary = primaryDiagnostic(diagnostics);
        if (!primary.isMissingNode()) {
            summary.put("primaryDiagnosticCode", primary.path("code").asText("unknown"));
            summary.put("primaryDiagnosticStage", primary.path("stage").asText("unknown"));
            summary.put("primaryDiagnosticMessage", primary.path("message").asText("分析失败"));
            summary.put("primaryDiagnosticMessageEn", primary.path("messageEn").asText("Analysis failed."));
            summary.put("retryable", primary.path("retryable").asBoolean(false));
        }
        return summary;
    }

    JsonNode primaryDiagnostic(JsonNode diagnostics) {
        if (!(diagnostics instanceof ArrayNode array) || array.isEmpty()) return objectMapper.missingNode();
        JsonNode selected = array.get(0);
        int selectedPriority = diagnosticPriority(selected.path("stage").asText());
        for (JsonNode candidate : array) {
            int priority = diagnosticPriority(candidate.path("stage").asText());
            if (priority < selectedPriority) {
                selected = candidate;
                selectedPriority = priority;
            }
        }
        return selected;
    }

    int diagnosticPriority(String stage) {
        return switch (stage == null ? "" : stage.toLowerCase(Locale.ROOT)) {
            case "request", "input", "compilation", "frontend", "budget", "resource", "bitcode_artifact" -> 0;
            case "ir", "ir_worker" -> 1;
            case "source", "source_facts", "evidence", "binding", "rule_evaluation" -> 2;
            case "result_validation", "result" -> 3;
            default -> 4;
        };
    }

    JsonNode sanitizeJson(JsonNode node, List<Path> roots) {
        if (node == null || node.isNull()) return node;
        if (node.isTextual()) {
            return objectMapper.getNodeFactory().textNode(
                    ServerPathGuard.redactKnownPathRoots(node.asText(), roots, "<server-path-redacted>"));
        }
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            node.fields().forEachRemaining(entry -> result.set(entry.getKey(), sanitizeJson(entry.getValue(), roots)));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(value -> result.add(sanitizeJson(value, roots)));
            return result;
        }
        return node.deepCopy();
    }

    private List<SpecializedFindingImport> parseFindings(SpecializedAnalyzerType type, JsonNode document) {
        List<SpecializedFindingImport> result = new ArrayList<>();
        parseFindingArray(type, document.path("findings"), "FINDING", result);
        if (type == SpecializedAnalyzerType.MEMORY_ORDER) {
            parseFindingArray(type, document.path("source_candidates"), "SOURCE_CANDIDATE", result);
            parseFindingArray(type, document.path("discharged_assessments"), "DISCHARGED", result);
        }
        return result;
    }

    private void parseFindingArray(SpecializedAnalyzerType type, JsonNode values, String kind, List<SpecializedFindingImport> target) {
        if (!(values instanceof ArrayNode array)) return;
        AtomicInteger index = new AtomicInteger();
        array.forEach(value -> {
            JsonNode conclusion = value.path("conclusion");
            JsonNode location = type == SpecializedAnalyzerType.FLOATING_POINT
                    ? value.path("fact").path("primaryLocation") : value.path("fact").path("primaryLocation");
            if (type == SpecializedAnalyzerType.MEMORY_ORDER && location.has("span")) {
                JsonNode span = location.path("span");
                target.add(imported(value, conclusion, kind, index.incrementAndGet(), location.path("file").asText(null),
                        intOrNull(span.path("start").path("line")), intOrNull(span.path("start").path("column")),
                        intOrNull(span.path("end").path("line")), intOrNull(span.path("end").path("column")), type));
            } else {
                target.add(imported(value, conclusion, kind, index.incrementAndGet(), location.path("file").asText(null),
                        intOrNull(location.path("line")), intOrNull(location.path("column")),
                        intOrNull(location.path("line")), intOrNull(location.path("column")), type));
            }
        });
    }

    @SuppressWarnings("unchecked")
    private SpecializedFindingImport imported(JsonNode value, JsonNode conclusion, String kind, int index,
            String file, Integer startLine, Integer startColumn, Integer endLine, Integer endColumn,
            SpecializedAnalyzerType type) {
        String id = text(value, "id", type.name().toLowerCase(Locale.ROOT) + "-" + index);
        if (type == SpecializedAnalyzerType.MEMORY_ORDER) kind = MemoryOrderResultPolicy.kind(kind, value);
        boolean unproven = type == SpecializedAnalyzerType.MEMORY_ORDER && MemoryOrderResultPolicy.pending(kind);
        String code = type == SpecializedAnalyzerType.FLOATING_POINT ? text(conclusion, "findingCode", "floating-point-risk")
                : text(conclusion, "findingType", text(value.path("fact"), "kind", "memory-order-risk"));
        String severity = text(conclusion, "severity", "info").toUpperCase(Locale.ROOT);
        String confidence = conclusion.path("confidence").isNumber()
                ? String.valueOf(conclusion.path("confidence").asDouble()) : text(conclusion, "confidence", null);
        if (unproven) { severity = "INFO"; confidence = null; }
        String status = text(conclusion, "status", "complete").toUpperCase(Locale.ROOT);
        String message = type == SpecializedAnalyzerType.FLOATING_POINT
                ? text(conclusion, "message", text(conclusion, "impact", "浮点迁移风险"))
                : text(conclusion, "detail", text(conclusion, "reason", "内存序迁移风险"));
        String messageEn = type == SpecializedAnalyzerType.FLOATING_POINT
                ? text(conclusion, "messageEn", text(conclusion, "impactEn", null))
                : text(conclusion, "detail_en", text(conclusion, "reason_en", null));
        Map<String, Object> detail = objectMapper.convertValue(value, Map.class);
        return new SpecializedFindingImport(id, kind, code, text(conclusion, "category", code), severity, confidence,
                status, text(conclusion, "title", code), text(conclusion, "titleEn", text(conclusion, "title_en", null)),
                message, messageEn, text(conclusion, "suggestion", null),
                text(conclusion, "suggestionEn", text(conclusion, "suggestion_en", null)),
                text(conclusion, "testSuggestion", null), text(conclusion, "testSuggestionEn", null),
                file, startLine, startColumn, endLine, endColumn,
                unproven || conclusion.path("requiresHumanConfirmation").asBoolean(kind.equals("SOURCE_CANDIDATE")),
                text(value, "semanticDigest", text(value, "semantic_digest", null)), detail);
    }

    private Map<String, Object> summary(SpecializedAnalyzerType type, JsonNode document, PreparedInput prepared,
            List<SpecializedFindingImport> findings) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("analyzerType", type.name());
        summary.put("findingCount", findings.stream().filter(item -> "FINDING".equals(item.findingKind())).count());
        summary.put("candidateCount", findings.stream().filter(item -> "SOURCE_CANDIDATE".equals(item.findingKind())).count());
        int diagnosticCount = document.path("diagnostics").isArray() ? document.path("diagnostics").size() : 0;
        summary.put("diagnosticCount", diagnosticCount);
        if (type == SpecializedAnalyzerType.MEMORY_ORDER) {
            summary.putAll(MemoryOrderResultPolicy.diagnostics(document));
            summary.put("inconclusiveCount", findings.stream().filter(item -> "INCONCLUSIVE".equals(item.findingKind())).count());
            summary.put("dischargedCount", findings.stream().filter(item -> "DISCHARGED".equals(item.findingKind())).count());
            summary.put("findingSchemaVersion", document.path("finding_schema_version").asText());
        }
        summary.put("status", type == SpecializedAnalyzerType.FLOATING_POINT
                ? document.path("scan").path("status").asText() : document.path("status").asText());
        summary.put("verdict", type == SpecializedAnalyzerType.MEMORY_ORDER ? document.path("verdict").asText() : "analyzed");
        summary.put("sourceRootDigest", digestText(prepared.sourceRoot().toString()));
        summary.put("analysisMode", prepared.capability().mode().name());
        summary.put("bcStatus", prepared.capability().status());
        summary.put("bcPlannedCount", prepared.capability().plannedCount());
        summary.put("bcUsableCount", prepared.capability().usableCount());
        summary.put("bcFailedCount", prepared.capability().failedCount());
        summary.put("astOnlyCount", prepared.capability().astOnlyCount());
        summary.put("bcDegradationReason", prepared.capability().degradationReason());
        if (!prepared.capability().audit().isEmpty()) {
            summary.put("bcAudit", prepared.capability().audit());
        }
        summary.put("languageContexts", languageContexts(prepared.compileDatabase()));
        return summary;
    }

    List<Map<String, Object>> languageContexts(Path compileDatabase) {
        if (compileDatabase == null) return List.of(
                Map.of("language", "c", "standard", "c17", "standardSource", "DEFAULT_ASSUMPTION"),
                Map.of("language", "c++", "standard", "c++17", "standardSource", "DEFAULT_ASSUMPTION"));
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        try {
            JsonNode records = objectMapper.readTree(compileDatabase.toFile());
            if (!records.isArray()) return List.of();
            for (JsonNode record : records) {
                String file = record.path("file").asText("").toLowerCase(Locale.ROOT);
                String language = List.of(".cc", ".cp", ".cpp", ".cxx", ".c++").stream()
                        .anyMatch(file::endsWith) ? "c++" : "c";
                List<String> arguments = new ArrayList<>();
                if (record.path("arguments").isArray()) {
                    record.path("arguments").forEach(value -> arguments.add(value.asText()));
                } else {
                    String command = record.path("command").asText("").trim();
                    if (!command.isEmpty()) arguments.addAll(List.of(command.split("\\s+")));
                }
                String rawStandard = arguments.stream().filter(value -> value.startsWith("-std="))
                        .reduce((first, second) -> second).map(value -> value.substring(5).toLowerCase(Locale.ROOT))
                        .orElse(null);
                String compiler = arguments.isEmpty() ? "unknown"
                        : Path.of(arguments.getFirst().replace('\\', '/')).getFileName().toString();
                String standard = rawStandard == null ? "compiler-default"
                        : LANGUAGE_STANDARD_ALIASES.getOrDefault(rawStandard, "unsupported");
                if (("c++".equals(language)) != standard.startsWith("c++")
                        && !List.of("compiler-default", "unsupported").contains(standard)) {
                    standard = "unsupported";
                }
                Map<String, Object> context = new LinkedHashMap<>();
                context.put("language", language);
                context.put("standard", standard);
                context.put("dialect", rawStandard == null ? "compiler-default"
                        : rawStandard.startsWith("gnu") ? "gnu" : "iso");
                context.put("standardSource", rawStandard == null ? "compiler-default" : "explicit");
                context.put("standardOption", rawStandard == null ? null : "-std=" + rawStandard);
                context.put("compiler", compiler);
                unique.putIfAbsent(context.toString(), context);
            }
        } catch (IOException | RuntimeException ignored) {
            return List.of();
        }
        return List.copyOf(unique.values());
    }

    private StatusMapping mapStatus(SpecializedAnalyzerType type, JsonNode document, int exitCode) {
        if (type == SpecializedAnalyzerType.FLOATING_POINT) {
            return new StatusMapping(exitCode == 4 ? SpecializedAnalysisStatus.PARTIAL : SpecializedAnalysisStatus.COMPLETE,
                    document.path("scan").path("status").asText("complete"));
        }
        String verdict = document.path("verdict").asText("inconclusive");
        return new StatusMapping(exitCode == 2 ? SpecializedAnalysisStatus.PARTIAL
                : exitCode == 3 ? SpecializedAnalysisStatus.COMPLETE_WITH_RISK : SpecializedAnalysisStatus.COMPLETE, verdict);
    }

    private Path renderHtml(SpecializedAnalyzerType type, UUID runId, Map<String, Object> summary,
            List<SpecializedFindingImport> findings, Path workspace) throws IOException {
        StringBuilder rows = new StringBuilder();
        for (SpecializedFindingImport finding : findings) {
            String label = type == SpecializedAnalyzerType.MEMORY_ORDER && MemoryOrderResultPolicy.pending(finding.findingKind())
                    ? "DISCHARGED".equals(finding.findingKind()) ? "受限模型内消解" : "待核实（证据不足）" : finding.severity();
            rows.append("<tr><td>").append(escape(label)).append("</td><td>")
                    .append(escape(finding.findingCode())).append("</td><td>").append(escape(finding.title()))
                    .append("</td><td>").append(escape(finding.filePath())).append(":")
                    .append(finding.startLine() == null ? "" : finding.startLine()).append("</td></tr>");
        }
        String html = "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><title>" + type.displayName()
                + "</title><style>body{font-family:system-ui;margin:24px;color:#222}table{border-collapse:collapse;width:100%}td,th{border-bottom:1px solid #ddd;padding:8px;text-align:left}</style>"
                + "<h1>" + type.displayName() + "</h1><p>运行 ID: " + runId + "</p><pre>"
                + escape(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary))
                + "</pre><table><thead><tr><th>风险</th><th>类型</th><th>问题</th><th>位置</th></tr></thead><tbody>"
                + rows + "</tbody></table></html>";
        Path output = workspace.resolve("result.html");
        Files.writeString(output, html, StandardCharsets.UTF_8);
        return output;
    }

    private void writeArtifact(UUID artifactId, Path target, long maximumBytes) throws IOException {
        StoredArtifactResource resource = artifactService.download(artifactId);
        if (resource.artifact().sizeBytes() > maximumBytes) throw new SpecializedAnalysisRuntimeException("输入制品超过大小预算");
        Files.createDirectories(target.getParent());
        try (InputStream input = resource.resource().getInputStream(); OutputStream output = Files.newOutputStream(target)) {
            long copied = 0;
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                copied += read;
                if (copied > maximumBytes) throw new SpecializedAnalysisRuntimeException("输入制品超过大小预算");
                output.write(buffer, 0, read);
            }
        }
    }

    private void writeBoundArtifact(SpecializedAnalysisTaskInput input, UUID id, String type, Path path, long maximumBytes) throws IOException {
        if (input.inputPlan() != null) {
            var expected = input.inputPlan().artifacts().stream().filter(a -> a.id().equals(id) && a.type().equals(type))
                    .findFirst().orElseThrow(() -> new SpecializedAnalysisRuntimeException("ANALYSIS_ARTIFACT_IDENTITY_MISMATCH"));
            var actual = artifactService.download(id).artifact();
            if (!input.projectId().equals(actual.projectId()) || !expected.ownerId().equals(actual.ownerId())
                    || actual.ownerType() != StoredArtifactOwnerType.BUILD_VALIDATION || !type.equals(actual.artifactType())
                    || !expected.sha256().equals(actual.sha256()))
                throw new SpecializedAnalysisRuntimeException("ANALYSIS_ARTIFACT_IDENTITY_MISMATCH");
            writeArtifact(id, path, maximumBytes);
            if (!expected.sha256().equals(sha256(path))) throw new SpecializedAnalysisRuntimeException("ANALYSIS_ARTIFACT_HASH_MISMATCH");
        } else writeArtifact(id, path, maximumBytes);
    }

    private void verifySha(UUID artifactId, Path target) {
        StoredArtifactResource resource = artifactService.download(artifactId);
        if (!resource.artifact().sha256().equals(sha256(target))) throw new SpecializedAnalysisRuntimeException("输入制品 SHA-256 不匹配");
    }

    private void extractBundle(Path bundle, Path target) throws IOException {
        AtomicInteger files = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        Set<String> entries = new HashSet<>();
        try (InputStream input = Files.newInputStream(bundle);
             GzipCompressorInputStream gzip = new GzipCompressorInputStream(input);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                String entryName = entry.getName();
                if (!entries.add(entryName)) {
                    throw new SpecializedAnalysisRuntimeException("BC bundle 包含重复条目");
                }
                if (entry.isSymbolicLink() || entry.isLink() || entry.isCharacterDevice() || entry.isBlockDevice() || entry.isFIFO())
                    throw new SpecializedAnalysisRuntimeException("BC bundle 包含不安全条目");
                Path destination = target.resolve(entryName).normalize();
                if (!destination.startsWith(target) || entryName.contains("\\"))
                    throw new SpecializedAnalysisRuntimeException("BC bundle 路径逃逸");
                if (entry.isDirectory()) { Files.createDirectories(destination); continue; }
                if (files.incrementAndGet() > properties.maxFiles()) throw new SpecializedAnalysisRuntimeException("BC bundle 文件数超过预算");
                if (bytes.addAndGet(entry.getSize()) > properties.maxExtractedBytes()) throw new SpecializedAnalysisRuntimeException("BC bundle 解压大小超过预算");
                Files.createDirectories(destination.getParent());
                Files.copy(tar, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private Path archiveDirectory(Path directory, Path target) throws IOException {
        try (OutputStream file = Files.newOutputStream(target);
             GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(file);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    String name = directory.relativize(path).toString().replace('\\', '/');
                    TarArchiveEntry entry = new TarArchiveEntry(path.toFile(), name);
                    tar.putArchiveEntry(entry);
                    Files.copy(path, tar);
                    tar.closeArchiveEntry();
                }
            }
            tar.finish();
        }
        return target;
    }

    private Path createWorkspace(UUID taskId) throws IOException {
        Path root = Path.of(properties.workDir()).toAbsolutePath().normalize();
        Files.createDirectories(root);
        return Files.createTempDirectory(root, "analysis-" + taskId + "-");
    }

    private void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) { }
    }

    private void deleteEmptyDirectory(Path directory) {
        if (directory == null) return;
        try (var children = Files.list(directory)) {
            if (children.findAny().isEmpty()) Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            // 并发分析仍在使用该缓存根目录时，由最后一个任务负责清理。
        }
    }

    private String toolVersion(SpecializedAnalyzerType type) { return type == SpecializedAnalyzerType.FLOATING_POINT ? "0.6.0" : "0.1.0"; }
    private String safeMessage(Exception ex) { return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(); }
    private String sha256(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024]; int read;
            while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception ex) { throw new SpecializedAnalysisRuntimeException("无法计算制品摘要", ex); }
    }
    private String digestText(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    private static int number(Map<String, Object> value, String key) {
        Object found = value == null ? null : value.get(key);
        return found instanceof Number number ? number.intValue() : 0;
    }
    private static Integer intOrNull(JsonNode node) { return node == null || !node.isInt() ? null : node.asInt(); }
    private static String text(JsonNode node, String name, String fallback) {
        JsonNode value = node == null ? null : node.get(name);
        return value == null || value.isNull() || value.asText().isBlank() ? fallback : value.asText();
    }
    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
    private static void add(List<String> command, Object... values) { for (Object value : values) command.add(String.valueOf(value)); }

    private record PreparedInput(Path sourceRoot, Path compileDatabase, Path platformManifest, Path artifactRoot,
                                 SpecializedBitcodeCapability capability, String remoteSourceRoot) {
        private PreparedInput(Path sourceRoot, Path compileDatabase, Path platformManifest, Path artifactRoot,
                SpecializedBitcodeCapability capability) {
            this(sourceRoot, compileDatabase, platformManifest, artifactRoot, capability, null);
        }
    }

    private record PreparedRequest(Path request, Path sourceLocalCache) { }
    private record StatusMapping(SpecializedAnalysisStatus status, String verdict) { }
    private record FailureAudit(Map<String, Object> summary, UUID resultArtifactId, int diagnosticCount) { }
}
