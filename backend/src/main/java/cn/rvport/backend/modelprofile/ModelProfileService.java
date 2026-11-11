package cn.rvport.backend.modelprofile;

import cn.rvport.backend.agentruntime.AgentModelRuntimeConfig;
import cn.rvport.backend.agentruntime.AgentRuntimeRepository;
import cn.rvport.backend.agentruntime.AgentRuntimeResponse;
import cn.rvport.backend.agentruntime.AgentRuntimeService;
import cn.rvport.backend.codexprovider.AgentType;
import cn.rvport.backend.codexprovider.CodexProviderResolvedConfig;
import cn.rvport.backend.codexprovider.CodexProviderTestResult;
import cn.rvport.backend.codexprovider.CodexProviderProbeService;
import cn.rvport.backend.codexprovider.CodexProviderType;
import cn.rvport.backend.codexprovider.CodexProviderWireApi;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.common.web.PageResponse;
import cn.rvport.backend.jooq.tables.records.ModelProfilesRecord;
import cn.rvport.backend.jooq.tables.records.AgentRuntimesRecord;
import cn.rvport.backend.jooq.tables.records.ModelProfileAgentProbeRunsRecord;
import cn.rvport.backend.security.CredentialCipher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.jooq.JSONB;

@Service
public class ModelProfileService {

    private static final String DEFAULT_EXECUTABLE_PATH = "codex";
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_MAX_RUNTIME_SECONDS = 1800;
    private static final int MIN_TIMEOUT_SECONDS = 1;
    private static final int MAX_TIMEOUT_SECONDS = 600;
    private static final int MIN_RUNTIME_SECONDS = 10;
    private static final int MAX_RUNTIME_SECONDS_BOUND = 24 * 60 * 60;

    private final ModelProfileRepository repository;
    private final CredentialCipher credentialCipher;
    private final ObjectMapper objectMapper;
    private final CodexProviderProbeService probeService;
    private final AgentRuntimeRepository agentRuntimeRepository;
    private final AgentRuntimeService agentRuntimeService;
    private final ExecutorService agentProbeExecutor;
    private CodexProbeDispatcher codexProbeDispatcher;
    private ModelConfigurationAccess configurationAccess;

    @Autowired
    void setConfigurationAccess(ModelConfigurationAccess access) { this.configurationAccess = access; }

    @Autowired
    void setCodexProbeDispatcher(CodexProbeDispatcher dispatcher) {
        this.codexProbeDispatcher = dispatcher;
    }

    @Autowired
    public ModelProfileService(
            ModelProfileRepository repository,
            CredentialCipher credentialCipher,
            ObjectMapper objectMapper,
            CodexProviderProbeService probeService,
            AgentRuntimeRepository agentRuntimeRepository,
            AgentRuntimeService agentRuntimeService
    ) {
        this.repository = repository;
        this.credentialCipher = credentialCipher;
        this.objectMapper = objectMapper;
        this.probeService = probeService;
        this.agentRuntimeRepository = agentRuntimeRepository;
        this.agentRuntimeService = agentRuntimeService;
        this.agentProbeExecutor = Executors.newFixedThreadPool(2);
    }

    ModelProfileService(
            ModelProfileRepository repository,
            CredentialCipher credentialCipher,
            ObjectMapper objectMapper,
            CodexProviderProbeService probeService,
            AgentRuntimeRepository agentRuntimeRepository,
            AgentRuntimeService agentRuntimeService,
            ExecutorService agentProbeExecutor
    ) {
        this.repository = repository;
        this.credentialCipher = credentialCipher;
        this.objectMapper = objectMapper;
        this.probeService = probeService;
        this.agentRuntimeRepository = agentRuntimeRepository;
        this.agentRuntimeService = agentRuntimeService;
        this.agentProbeExecutor = agentProbeExecutor;
    }

    public PageResponse<ModelProfileResponse> list(ModelProfileListRequest request, UUID actorUserId) {
        configurationAccess.requireActive(actorUserId);
        validateListRequest(request);
        List<ModelProfileResponse> items = repository.list(request, actorUserId).stream()
                .map(this::response)
                .toList();
        return PageResponse.of(items, request.normalizedPage(), request.normalizedPageSize(), repository.count(request, actorUserId));
    }

    public ModelProfileResponse get(UUID profileId, UUID actorUserId) {
        return response(require(profileId, actorUserId));
    }

    @Transactional
    public ModelProfileResponse create(ModelProfileRequest request, UUID actorUserId) {
        configurationAccess.requireActive(actorUserId);
        rejectExecutableOverride(request);
        Normalized normalized = normalizeForCreate(request);
        if (repository.nameExists(normalized.name(), null, actorUserId)) {
            throw validation("模型 profile 名称已存在");
        }
        ModelProfilesRecord record = repository.create(
                UUID.randomUUID(),
                normalized.name(),
                normalized.enabled(),
                normalized.executablePath(),
                normalized.modelProviderType(),
                normalized.baseUrl(),
                normalized.model(),
                normalized.wireApi(),
                encryptedApiKey(normalized.apiKey(), normalized.apiKeyTouched()),
                normalized.timeoutSeconds(),
                normalized.maxRuntimeSeconds(),
                actorUserId
        );
        return ModelProfileResponse.from(record);
    }

    @Transactional
    public ModelProfileResponse update(UUID profileId, ModelProfileRequest request, UUID actorUserId) {
        ModelProfilesRecord current = require(profileId, actorUserId);
        rejectExecutableOverride(request);
        Normalized normalized = normalizeForUpdate(current, request);
        if (normalized.name() != null && repository.nameExists(normalized.name(), profileId, actorUserId)) {
            throw validation("模型 profile 名称已存在");
        }
        ModelProfilesRecord record = repository.update(
                current,
                normalized.name(),
                normalized.enabled(),
                normalized.executablePath(),
                normalized.modelProviderType(),
                normalized.baseUrl(),
                normalized.model(),
                normalized.wireApi(),
                encryptedApiKey(normalized.apiKey(), normalized.apiKeyTouched()),
                normalized.apiKeyTouched(),
                normalized.timeoutSeconds(),
                normalized.maxRuntimeSeconds(),
                actorUserId
        );
        return ModelProfileResponse.from(record);
    }

    @Transactional
    public void delete(UUID profileId, UUID actorUserId) {
        require(profileId, actorUserId);
        if (repository.isAssigned(profileId, actorUserId)) {
            throw new ApiException(HttpStatus.CONFLICT, "MODEL_PROFILE_ASSIGNED", "模型 profile 已被阶段绑定，不能删除");
        }
        repository.delete(profileId, actorUserId);
    }

    @Transactional
    public CodexProviderTestResult test(UUID profileId, ModelProfileRequest overrideRequest, UUID actorUserId) {
        ModelProfilesRecord current = require(profileId, actorUserId);
        AgentRuntimesRecord runtime = resolveRuntimeForTest(overrideRequest);
        CodexProviderResolvedConfig config = resolveForTest(current, overrideRequest, runtime.getExecutablePath());
        configurationAccess.requireActive(actorUserId);
        CodexProviderTestResult result = probe(null, profileId, runtime, config, overrideRequest != null, actorUserId);
        result = new ModelExecutionSelection(actorUserId, profileId, runtime.getId())
                .auditProbe(result, new AgentModelRuntimeConfig(runtime, current, config));
        repository.updateTestResult(
                profileId,
                result.status(),
                result.message(),
                OffsetDateTime.ofInstant(result.testedAt(), ZoneOffset.UTC),
                result.agentType(),
                result.basicAvailable(),
                result.agentCompatible(),
                toJsonb(result.details()),
                actorUserId
        );
        return result;
    }

    @Transactional
    public ModelProfileConnectivityTestResult testConnectivity(UUID profileId, ModelProfileRequest overrideRequest, UUID actorUserId) {
        ModelProfilesRecord current = require(profileId, actorUserId);
        CodexProviderResolvedConfig config = resolveForTest(current, overrideRequest, current.getExecutablePath());
        ModelProfileConnectivityTestResult result = probeService.testConnectivity(config);
        repository.updateConnectivityResult(
                profileId,
                result.status(),
                result.message(),
                toJsonb(result.details()),
                OffsetDateTime.ofInstant(result.testedAt(), ZoneOffset.UTC),
                result.durationMs(),
                actorUserId
        );
        return result;
    }

    public ModelProfileAgentProbeRunResponse startAgentTests(UUID profileId, ModelProfileAgentProbeRequest request, UUID actorUserId) {
        ModelProfilesRecord profile = require(profileId, actorUserId);
        if (!Boolean.TRUE.equals(profile.getEnabled())) { throw validation("模型 profile 未启用 / Model profile disabled"); }
        List<AgentRuntimesRecord> runtimes = requireEnabledRuntimes(request);
        UUID runId = newRunId();
        ModelProfileAgentProbeRunsRecord run = repository.createProbeRun(
                runId,
                profileId,
                toJsonb(runtimes.stream().map(AgentRuntimesRecord::getId).toList()),
                toJsonb(runtimes.stream().map(AgentRuntimeSummaryResponse::from).toList()),
                actorUserId
        );
        agentProbeExecutor.submit(() -> executeAgentProbeRun(runId, profile.getId(), runtimes, actorUserId));
        return ModelProfileAgentProbeRunResponse.from(run, List.of());
    }

    public ModelProfileAgentProbeRunResponse getAgentTestRun(UUID runId, UUID actorUserId) {
        configurationAccess.requireActive(actorUserId);
        ModelProfileAgentProbeRunsRecord run = repository.findProbeRun(runId, actorUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MODEL_PROFILE_AGENT_PROBE_RUN_NOT_FOUND", "模型 Agent 测试任务不存在"));
        return ModelProfileAgentProbeRunResponse.from(run, repository.listProbeResultsByRun(runId, actorUserId).stream()
                .map(ModelProfileAgentProbeResultResponse::from)
                .toList());
    }

    public List<ModelProfileAgentProbeResultResponse> listAgentProbeResults(UUID profileId, UUID actorUserId) {
        require(profileId, actorUserId);
        return repository.listLatestProbeResults(profileId, actorUserId).stream()
                .map(ModelProfileAgentProbeResultResponse::from)
                .toList();
    }

    public List<ModelProfileAssignmentResponse> listAssignments(UUID actorUserId) {
        configurationAccess.requireActive(actorUserId);
        List<ModelProfileRepository.AssignmentView> views = repository.listAssignments(actorUserId);
        List<ModelProfileAssignmentResponse> responses = new ArrayList<>();
        for (ModelProfileStage stage : ModelProfileStage.values()) {
            ModelProfileRepository.AssignmentView view = views.stream()
                    .filter(candidate -> stage.name().equals(candidate.stage()))
                    .findFirst()
                    .orElse(null);
            responses.add(view == null
                    ? ModelProfileAssignmentResponse.empty(stage)
                    : new ModelProfileAssignmentResponse(
                    stage.name(),
                    view.modelProfileId(),
                    view.profile() == null ? null : ModelProfileResponse.from(view.profile()),
                    view.agentRuntimeId(),
                    view.agentRuntime() == null ? null : AgentRuntimeResponse.from(view.agentRuntime()),
                    view.updatedAt()));
        }
        return responses;
    }

    @Transactional
    public List<ModelProfileAssignmentResponse> saveAssignments(ModelStageAssignmentRequest request, UUID actorUserId) {
        configurationAccess.requireActive(actorUserId);
        if (request == null || request.assignments() == null || request.assignments().isEmpty()) {
            throw validation("assignments 不能为空");
        }
        EnumSet<ModelProfileStage> seen = EnumSet.noneOf(ModelProfileStage.class);
        for (ModelStageAssignmentRequest.Entry entry : request.assignments()) {
            ModelProfileStage stage = parseStage(entry.stage());
            if (!seen.add(stage)) {
                throw validation("阶段重复: " + stage.name());
            }
            repository.findEnabledById(entry.modelProfileId(), actorUserId)
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "MODEL_PROFILE_NOT_AVAILABLE", "模型 profile 不存在或未启用"));
            agentRuntimeService.requireEnabled(entry.agentRuntimeId());
        }
        for (ModelStageAssignmentRequest.Entry entry : request.assignments()) {
            repository.upsertAssignment(parseStage(entry.stage()), entry.modelProfileId(), entry.agentRuntimeId(), actorUserId);
        }
        return listAssignments(actorUserId);
    }

    public CodexProviderResolvedConfig resolveForStage(ModelProfileStage stage, UUID actorUserId) {
        AgentModelRuntimeConfig resolved = resolveAgentForStage(stage, actorUserId);
        return resolved.model();
    }

    public AgentModelRuntimeConfig resolveAgentForStage(ModelProfileStage stage, UUID actorUserId) {
        configurationAccess.requireActive(actorUserId);
        ModelProfileRepository.AssignmentView assignment = repository.findAssignment(stage, actorUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "MODEL_ASSIGNMENT_MISSING", stageLabel(stage) + "未绑定模型 profile"));
        ModelProfilesRecord profile = assignment.profile();
        if (profile == null || !Boolean.TRUE.equals(profile.getEnabled())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_ASSIGNMENT_PROFILE_DISABLED", stageLabel(stage) + "绑定的模型 profile 不可用");
        }
        AgentRuntimesRecord runtime = assignment.agentRuntime();
        if (runtime == null || !Boolean.TRUE.equals(runtime.getEnabled())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_ASSIGNMENT_AGENT_DISABLED", stageLabel(stage) + "绑定的 Agent runtime 不可用");
        }
        return new AgentModelRuntimeConfig(runtime, profile, resolved(profile, null, runtime.getExecutablePath()));
    }

    public AgentModelRuntimeConfig resolveExecution(UUID ownerUserId, UUID projectId, UUID profileId, UUID runtimeId) {
        configurationAccess.requireProject(ownerUserId, projectId);
        ModelProfilesRecord profile = repository.findEnabledById(profileId, ownerUserId)
                .orElseThrow(() -> validation("执行模型不存在、归属不符或已禁用 / Execution profile unavailable"));
        AgentRuntimesRecord runtime = agentRuntimeService.requireEnabled(runtimeId);
        return new AgentModelRuntimeConfig(runtime, profile, resolved(profile, null, runtime.getExecutablePath()));
    }

    private void rejectExecutableOverride(ModelProfileRequest request) {
        if (request != null && request.executablePath() != null) {
            throw validation("不允许覆盖平台 Agent 命令 / Managed Agent executable cannot be overridden");
        }
    }
    private String stageLabel(ModelProfileStage stage) {
        return switch (stage) {
            case TEST_LOG_COMPARE -> "测试结果比较阶段";
            case KB_AI_EXTENSION -> "知识库 AI 扩展阶段";
        };
    }

    private ModelProfilesRecord require(UUID profileId, UUID actorUserId) {
        configurationAccess.requireActive(actorUserId);
        return repository.findById(profileId, actorUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MODEL_PROFILE_NOT_FOUND", "模型 profile 不存在"));
    }

    UUID newRunId() {
        return UUID.randomUUID();
    }

    private ModelProfileResponse response(ModelProfilesRecord record) {
        return ModelProfileResponse.from(
                record,
                repository.listLatestProbeResults(record.getId(), record.getOwnerUserId()).stream()
                        .map(ModelProfileAgentProbeResultResponse::from)
                        .toList(),
                repository.findActiveProbeRun(record.getId(), record.getOwnerUserId())
                        .map(ActiveAgentProbeRunResponse::from)
                        .orElse(null)
        );
    }

    private CodexProviderResolvedConfig resolveForTest(ModelProfilesRecord current, ModelProfileRequest overrideRequest, String executablePath) {
        rejectExecutableOverride(overrideRequest);
        Normalized normalized = normalizeForTestOverride(current, overrideRequest);
        return new CodexProviderResolvedConfig(
                normalized.enabled(),
                executablePath,
                CodexProviderType.valueOf(normalized.modelProviderType()),
                normalized.baseUrl(),
                normalized.model(),
                CodexProviderWireApi.valueOf(normalized.wireApi()),
                normalized.apiKeyTouched() ? blankToNull(normalized.apiKey()) : decryptedApiKey(current.getApiKeyEncrypted()),
                normalized.timeoutSeconds(),
                normalized.maxRuntimeSeconds()
        );
    }

    private AgentRuntimesRecord resolveRuntimeForTest(ModelProfileRequest overrideRequest) {
        if (overrideRequest != null && overrideRequest.agentRuntimeId() != null) {
            return agentRuntimeService.requireEnabled(overrideRequest.agentRuntimeId());
        }
        return agentRuntimeRepository.findByType(AgentType.CODEX)
                .filter(runtime -> Boolean.TRUE.equals(runtime.getEnabled()))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "AGENT_RUNTIME_DEFAULT_MISSING", "默认 Codex Agent runtime 不存在"));
    }

    private CodexProviderResolvedConfig resolved(ModelProfilesRecord profile, String overrideApiKey, String executablePath) {
        return new CodexProviderResolvedConfig(
                Boolean.TRUE.equals(profile.getEnabled()),
                executablePath,
                CodexProviderType.valueOf(profile.getModelProviderType()),
                profile.getBaseUrl(),
                profile.getModel(),
                CodexProviderWireApi.valueOf(profile.getWireApi()),
                overrideApiKey != null ? overrideApiKey : decryptedApiKey(profile.getApiKeyEncrypted()),
                profile.getTimeoutSeconds(),
                profile.getMaxRuntimeSeconds()
        );
    }

    private List<AgentRuntimesRecord> requireEnabledRuntimes(ModelProfileAgentProbeRequest request) {
        if (request == null || request.agentRuntimeIds() == null || request.agentRuntimeIds().isEmpty()) {
            throw validation("agentRuntimeIds 不能为空");
        }
        List<AgentRuntimesRecord> runtimes = new ArrayList<>();
        List<UUID> seen = new ArrayList<>();
        for (UUID runtimeId : request.agentRuntimeIds()) {
            if (runtimeId == null || seen.contains(runtimeId)) {
                throw validation("agentRuntimeIds 不能包含空值或重复值");
            }
            seen.add(runtimeId);
            runtimes.add(agentRuntimeService.requireEnabled(runtimeId));
        }
        if (runtimes.size() > 10) {
            throw validation("agentRuntimeIds 不能超过 10 个");
        }
        return List.copyOf(runtimes);
    }

    private void executeAgentProbeRun(UUID runId, UUID profileId, List<AgentRuntimesRecord> runtimes, UUID actorUserId) {
        OffsetDateTime startedAt = OffsetDateTime.now(ZoneOffset.UTC);
        repository.updateProbeRunStatus(runId, AgentProbeRunStatus.RUNNING, "模型 Agent 兼容性测试运行中", startedAt, null);
        int success = 0;
        int failed = 0;
        for (AgentRuntimesRecord runtime : runtimes) {
            if (executeSingleAgentProbe(runId, profileId, runtime, actorUserId)) {
                success++;
            } else {
                failed++;
            }
        }
        AgentProbeRunStatus status;
        if (success > 0 && failed == 0) {
            status = AgentProbeRunStatus.SUCCESS;
        } else if (success > 0) {
            status = AgentProbeRunStatus.PARTIAL_SUCCESS;
        } else {
            status = AgentProbeRunStatus.FAILED;
        }
        String message = "模型 Agent 兼容性测试完成，成功 " + success + " 个，失败 " + failed + " 个";
        repository.updateProbeRunStatus(runId, status, message, null, OffsetDateTime.now(ZoneOffset.UTC));
    }

    private boolean executeSingleAgentProbe(UUID runId, UUID profileId, AgentRuntimesRecord runtime, UUID actorUserId) {
        OffsetDateTime startedAt = OffsetDateTime.now(ZoneOffset.UTC);
        long startedNanos = System.nanoTime();
        try {
            ModelProfilesRecord profile = require(profileId, actorUserId);
            if (!Boolean.TRUE.equals(profile.getEnabled())) { throw validation("模型 profile 未启用 / Model profile disabled"); }
            configurationAccess.requireActive(actorUserId);
            runtime = agentRuntimeService.requireEnabled(runtime.getId());
            CodexProviderResolvedConfig config = resolved(profile, null, runtime.getExecutablePath());
            CodexProviderTestResult result = probe(runId, profileId, runtime, config, false, actorUserId);
            result = new ModelExecutionSelection(actorUserId, profileId, runtime.getId())
                    .auditProbe(result, new AgentModelRuntimeConfig(runtime, profile, config));
            OffsetDateTime finishedAt = OffsetDateTime.now(ZoneOffset.UTC);
            long durationMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
            String errorCode = result.success() ? null : firstFailureCode(result);
            repository.createProbeResult(
                    probeResultId(runId, runtime.getId()),
                    runId,
                    profileId,
                    runtime.getId(),
                    result.agentType(),
                    result.status(),
                    result.basicAvailable(),
                    result.agentCompatible(),
                    toJsonb(result.checks()),
                    toJsonb(result.details()),
                    errorCode,
                    result.message(),
                    startedAt,
                    finishedAt,
                    durationMs
            );
            OffsetDateTime testedAt = OffsetDateTime.ofInstant(result.testedAt(), ZoneOffset.UTC);
                repository.updateTestResult(
                    profileId,
                    result.status(),
                    result.message(),
                    testedAt,
                    result.agentType(),
                    result.basicAvailable(),
                    result.agentCompatible(),
                    toJsonb(result.details()),
                    actorUserId
            );
            return result.success();
        } catch (Exception ex) {
            OffsetDateTime finishedAt = OffsetDateTime.now(ZoneOffset.UTC);
            repository.createProbeResult(
                    probeResultId(runId, runtime.getId()),
                    runId,
                    profileId,
                    runtime.getId(),
                    runtime.getAgentType(),
                    "FAILED",
                    false,
                    false,
                    toJsonb(List.of()),
                    toJsonb(Map.of("agentType", runtime.getAgentType(), "agentRuntimeId", runtime.getId())),
                    "AGENT_PROBE_FAILED",
                    sanitizeFailure(ex.getMessage()),
                    startedAt,
                    finishedAt,
                    Duration.ofNanos(System.nanoTime() - startedNanos).toMillis()
            );
            return false;
        }
    }

    private String firstFailureCode(CodexProviderTestResult result) {
        return result.checks().stream()
                .filter(check -> !check.succeeded())
                .findFirst()
                .map(cn.rvport.backend.codexprovider.CodexProviderProbeCheckResult::errorCode)
                .orElse((String) result.details().get("errorCode"));
    }

    static UUID probeResultId(UUID runId, UUID runtimeId) {
        return UUID.nameUUIDFromBytes((runId + ":" + runtimeId).getBytes(StandardCharsets.UTF_8));
    }

    private CodexProviderTestResult probe(UUID runId, UUID profileId, AgentRuntimesRecord runtime,
            CodexProviderResolvedConfig config, boolean override, UUID actorUserId) {
        if ("CODEX".equals(runtime.getAgentType()) && config.wireApi() == CodexProviderWireApi.CHAT) {
            if (codexProbeDispatcher == null) {
                return CodexProbeDispatcher.failure(runtime.getId(), "AGENT_PROBE_DISPATCH_UNAVAILABLE");
            }
            return codexProbeDispatcher.execute(runId, profileId, runtime.getId(), actorUserId,
                    override ? config : null, config.timeoutSeconds());
        }
        return probeService.test(config, runtime);
    }

    private String sanitizeFailure(String message) {
        if (message == null || message.isBlank()) {
            return "模型 Agent 兼容性测试失败";
        }
        String sanitized = message.replace('\\', '/')
                .replaceAll("(?i)authorization:\\s*bearer\\s+[^\\s]+", "Authorization: Bearer [REDACTED]")
                .replaceAll("(?i)(api[-_ ]?key[=:]\\s*)[^\\s]+", "$1[REDACTED]");
        return sanitized.length() > 1000 ? sanitized.substring(0, 1000) : sanitized;
    }

    @PreDestroy
    void shutdownAgentProbeExecutor() {
        agentProbeExecutor.shutdownNow();
    }

    private Normalized normalizeForCreate(ModelProfileRequest request) {
        if (request == null) {
            throw validation("请求体不能为空");
        }
        String name = requiredName(request.name());
        String providerType = normalizeProviderType(defaultValue(request.modelProviderType(), CodexProviderType.OPENAI_COMPATIBLE.name()));
        String wireApi = normalizeWireApi(defaultValue(request.wireApi(), CodexProviderWireApi.RESPONSES.name())).name();
        return new Normalized(
                name,
                request.enabled() == null || request.enabled(),
                normalizeExecutablePath(defaultValue(request.executablePath(), DEFAULT_EXECUTABLE_PATH)),
                providerType,
                normalizeBaseUrl(request.baseUrl()),
                blankToNull(request.model()),
                wireApi,
                request.apiKey(),
                request.apiKeyPresent(),
                normalizeTimeout(defaultValue(request.timeoutSeconds(), DEFAULT_TIMEOUT_SECONDS)),
                normalizeMaxRuntime(defaultValue(request.maxRuntimeSeconds(), DEFAULT_MAX_RUNTIME_SECONDS))
        );
    }

    private Normalized normalizeForUpdate(ModelProfilesRecord current, ModelProfileRequest request) {
        if (request == null) {
            throw validation("请求体不能为空");
        }
        return new Normalized(
                request.hasField("name") ? requiredName(request.name()) : null,
                request.hasField("enabled") ? request.enabled() : null,
                request.hasField("executablePath") ? normalizeExecutablePath(defaultValue(request.executablePath(), DEFAULT_EXECUTABLE_PATH)) : null,
                request.hasField("modelProviderType") ? normalizeProviderType(request.modelProviderType()) : null,
                request.hasField("baseUrl") ? normalizeBaseUrl(request.baseUrl()) : current.getBaseUrl(),
                request.hasField("model") ? blankToNull(request.model()) : current.getModel(),
                request.hasField("wireApi") ? normalizeWireApi(request.wireApi()).name() : null,
                request.apiKey(),
                request.apiKeyPresent(),
                request.hasField("timeoutSeconds") ? normalizeTimeout(request.timeoutSeconds()) : null,
                request.hasField("maxRuntimeSeconds") ? normalizeMaxRuntime(request.maxRuntimeSeconds()) : null
        );
    }

    private Normalized normalizeForTestOverride(ModelProfilesRecord current, ModelProfileRequest request) {
        if (request == null) {
            return new Normalized(
                    current.getName(),
                    Boolean.TRUE.equals(current.getEnabled()),
                    current.getExecutablePath(),
                    current.getModelProviderType(),
                    current.getBaseUrl(),
                    current.getModel(),
                    current.getWireApi(),
                    null,
                    false,
                    current.getTimeoutSeconds(),
                    current.getMaxRuntimeSeconds()
            );
        }
        return new Normalized(
                current.getName(),
                request.enabled() == null ? Boolean.TRUE.equals(current.getEnabled()) : request.enabled(),
                request.executablePath() == null ? current.getExecutablePath() : normalizeExecutablePath(request.executablePath()),
                request.modelProviderType() == null ? current.getModelProviderType() : normalizeProviderType(request.modelProviderType()),
                request.baseUrl() == null ? current.getBaseUrl() : normalizeBaseUrl(request.baseUrl()),
                request.model() == null ? current.getModel() : blankToNull(request.model()),
                request.wireApi() == null ? current.getWireApi() : normalizeWireApi(request.wireApi()).name(),
                request.apiKey(),
                request.apiKeyPresent(),
                request.timeoutSeconds() == null ? current.getTimeoutSeconds() : normalizeTimeout(request.timeoutSeconds()),
                request.maxRuntimeSeconds() == null ? current.getMaxRuntimeSeconds() : normalizeMaxRuntime(request.maxRuntimeSeconds())
        );
    }

    private void validateListRequest(ModelProfileListRequest request) {
        if (notBlank(request.modelProviderType())) {
            normalizeProviderType(request.modelProviderType());
        }
        if (notBlank(request.lastTestStatus())) {
            String status = request.lastTestStatus().trim();
            if (!"SUCCESS".equals(status) && !"FAILED".equals(status)) {
                throw validation("lastTestStatus 只允许 SUCCESS 或 FAILED");
            }
        }
        if (notBlank(request.sortBy())
                && !List.of("name", "createdAt", "updatedAt", "lastTestedAt").contains(request.sortBy().trim())) {
            throw validation("sortBy 不支持");
        }
    }

    private String encryptedApiKey(String apiKey, boolean touched) {
        if (!touched || apiKey == null || apiKey.isBlank()) {
            return null;
        }
        try {
            CredentialCipher.EncryptedSecret encrypted = credentialCipher.encrypt(apiKey.getBytes(StandardCharsets.UTF_8));
            return objectMapper.writeValueAsString(encrypted);
        } catch (JsonProcessingException | IllegalStateException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "MODEL_PROFILE_SECRET_ENCRYPT_FAILED", "模型 profile API key 加密失败");
        }
    }

    private JSONB toJsonb(Object value) {
        try {
            return JSONB.valueOf(objectMapper.writeValueAsString(value == null ? java.util.Map.of() : value));
        } catch (JsonProcessingException ex) {
            return JSONB.valueOf("{}");
        }
    }

    private String decryptedApiKey(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        try {
            CredentialCipher.EncryptedSecret encrypted = objectMapper.readValue(stored, CredentialCipher.EncryptedSecret.class);
            return new String(credentialCipher.decrypt(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "MODEL_PROFILE_SECRET_DECRYPT_FAILED", "模型 profile API key 解密失败");
        }
    }

    private ModelProfileStage parseStage(String value) {
        try {
            return ModelProfileStage.valueOf(value == null ? "" : value.trim());
        } catch (IllegalArgumentException ex) {
            throw validation("stage 只允许 TEST_LOG_COMPARE 或 KB_AI_EXTENSION");
        }
    }

    private String requiredName(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            throw validation("name 不能为空");
        }
        if (normalized.length() > 128) {
            throw validation("name 不能超过 128 个字符");
        }
        return normalized;
    }

    private String normalizeProviderType(String value) {
        String trimmed = blankToNull(value);
        if (!CodexProviderType.OPENAI_COMPATIBLE.name().equals(trimmed)) {
            throw validation("modelProviderType 只允许 OPENAI_COMPATIBLE");
        }
        return trimmed;
    }

    private String normalizeBaseUrl(String value) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            URI uri = new URI(trimmed);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw validation("baseUrl 只允许 http 或 https");
            }
            if (uri.getHost() == null) {
                throw validation("baseUrl 格式不正确");
            }
            return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        } catch (URISyntaxException ex) {
            throw validation("baseUrl 格式不正确");
        }
    }

    private CodexProviderWireApi normalizeWireApi(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            throw validation("wireApi 不能为空");
        }
        try {
            return CodexProviderWireApi.valueOf(normalized.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw validation("wireApi 只允许 RESPONSES 或 CHAT");
        }
    }

    private String normalizeExecutablePath(String value) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            return DEFAULT_EXECUTABLE_PATH;
        }
        if (trimmed.contains("\n") || trimmed.contains("\r") || trimmed.contains("\u0000")) {
            throw validation("executablePath 格式不正确");
        }
        return trimmed;
    }

    private int normalizeTimeout(Integer value) {
        if (value == null || value < MIN_TIMEOUT_SECONDS || value > MAX_TIMEOUT_SECONDS) {
            throw validation("timeoutSeconds 超出范围");
        }
        return value;
    }

    private int normalizeMaxRuntime(Integer value) {
        if (value == null || value < MIN_RUNTIME_SECONDS || value > MAX_RUNTIME_SECONDS_BOUND) {
            throw validation("maxRuntimeSeconds 超出范围");
        }
        return value;
    }

    private <T> T defaultValue(T value, T fallback) {
        return value == null ? fallback : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private ApiException validation(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private record Normalized(
            String name,
            Boolean enabled,
            String executablePath,
            String modelProviderType,
            String baseUrl,
            String model,
            String wireApi,
            String apiKey,
            boolean apiKeyTouched,
            Integer timeoutSeconds,
            Integer maxRuntimeSeconds
    ) {
    }
}
