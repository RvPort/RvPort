package cn.rvport.backend.codexprovider;

import cn.rvport.backend.agentruntime.AgentRuntimeAdapter;
import cn.rvport.backend.agentruntime.AgentRuntimeAdapterRegistry;
import cn.rvport.backend.agentruntime.AgentRuntimeExecutionRequest;
import cn.rvport.backend.agentruntime.CodexAgentRuntimeAdapter;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.jooq.tables.records.AgentRuntimesRecord;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import cn.rvport.backend.modelprofile.ModelProfileConnectivityTestResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CodexProviderProbeService {

    private static final String PROBE_MARKER = "rvport-probe-ok";
    private static final String WORKSPACE_OUTPUT = "probe-output.json";
    private static final Pattern ANSI_CSI = Pattern.compile("\\u001B\\[[0-?]*[ -/]*[@-~]");
    private static final Pattern ANSI_OSC = Pattern.compile("\\u001B\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)");
    private static final List<String> CHANNEL_LABELS = List.of("[STDOUT]", "[STDERR]");

    private final AgentRuntimeAdapterRegistry adapterRegistry;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public CodexProviderProbeService(AgentRuntimeAdapterRegistry adapterRegistry) {
        this(adapterRegistry, null, new ObjectMapper());
    }

    public CodexProviderProbeService(CodexProcessRunner processRunner) {
        this(new AgentRuntimeAdapterRegistry(List.of(new CodexAgentRuntimeAdapter(processRunner))));
    }

    CodexProviderProbeService(AgentRuntimeAdapterRegistry adapterRegistry, HttpClient httpClient, ObjectMapper objectMapper) {
        this.adapterRegistry = adapterRegistry;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public ModelProfileConnectivityTestResult testConnectivity(CodexProviderResolvedConfig config) {
        Instant testedAt = Instant.now();
        long started = System.nanoTime();
        validateRunnable(config);
        try {
            HttpClient client = httpClient == null ? HttpClient.newHttpClient() : httpClient;
            HttpRequest request = HttpRequest.newBuilder(connectivityUri(config))
                    .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(connectivityBody(config)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return new ModelProfileConnectivityTestResult(true, CodexProviderTestStatus.SUCCESS.name(),
                        "模型连通性测试成功", testedAt, durationMs, Map.of(
                        "httpStatus", response.statusCode(),
                        "wireApi", config.wireApi().name()
                ));
            }
            return new ModelProfileConnectivityTestResult(false, CodexProviderTestStatus.FAILED.name(),
                    "模型连通性测试失败: HTTP " + response.statusCode() + "；上游正文已隐藏 [REDACTED]",
                    testedAt, durationMs, Map.of(
                    "httpStatus", response.statusCode(),
                    "wireApi", config.wireApi().name()
            ));
        } catch (java.net.http.HttpTimeoutException ex) {
            return new ModelProfileConnectivityTestResult(false, "TIMEOUT",
                    "模型连通性测试超时", testedAt,
                    Duration.ofNanos(System.nanoTime() - started).toMillis(), Map.of(
                    "wireApi", config.wireApi().name()
            ));
        } catch (IOException | UncheckedIOException ex) {
            return new ModelProfileConnectivityTestResult(false, CodexProviderTestStatus.FAILED.name(),
                    "模型连通性测试无法完成；原始错误已隐藏 [REDACTED]", testedAt,
                    Duration.ofNanos(System.nanoTime() - started).toMillis(), Map.of(
                    "wireApi", config.wireApi().name()
            ));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new ModelProfileConnectivityTestResult(false, CodexProviderTestStatus.FAILED.name(),
                    "模型连通性测试被中断", testedAt,
                    Duration.ofNanos(System.nanoTime() - started).toMillis(), Map.of(
                    "wireApi", config.wireApi().name()
            ));
        }
    }

    public CodexProviderTestResult test(CodexProviderResolvedConfig config) {
        AgentRuntimesRecord runtime = new AgentRuntimesRecord();
        runtime.setId(java.util.UUID.randomUUID());
        runtime.setAgentType(AgentType.CODEX.name());
        runtime.setName("Codex");
        runtime.setEnabled(true);
        runtime.setExecutablePath(config.executablePath());
        return test(config, runtime);
    }

    public CodexProviderTestResult test(CodexProviderResolvedConfig config, AgentRuntimesRecord runtime) {
        Instant testedAt = Instant.now();
        validateRunnable(config);
        AgentType agentType = AgentType.valueOf(runtime.getAgentType());
        AgentRuntimeAdapter adapter = adapterRegistry.get(runtime.getAgentType());
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("rvport-codex-provider-test-");
            Files.writeString(tempDir.resolve("probe-input.json"), "{\"marker\":\"" + PROBE_MARKER + "\"}",
                    StandardCharsets.UTF_8);
            List<CodexProviderProbeCheckResult> checks = List.of(
                    runCheck(adapter, runtime, config, tempDir, CodexProviderProbeName.AGENT_TEXT,
                            agentPrompt(agentType), Map.of("status", "ok", "agent", agentType.name(), "mode", "text")),
                    runCheck(adapter, runtime, config, tempDir, CodexProviderProbeName.AGENT_WORKSPACE_READ,
                            workspaceReadPrompt(agentType), Map.of("status", "ok", "agent", agentType.name(),
                                    "mode", "workspace-read", "marker", PROBE_MARKER)),
                    runFileCheck(adapter, runtime, config, tempDir, CodexProviderProbeName.AGENT_WORKSPACE_WRITE,
                            workspaceWritePrompt(), tempDir.resolve(WORKSPACE_OUTPUT), PROBE_MARKER)
            );
            boolean basicAvailable = checks.get(0).succeeded();
            boolean agentCompatible = checks.stream().allMatch(CodexProviderProbeCheckResult::succeeded);
            boolean success = agentCompatible;
            String message = success
                    ? "模型 profile agent 兼容性测试成功"
                    : firstFailureMessage(checks);
            return CodexProviderTestResult.of(success, message, testedAt, agentType, runtime.getId(),
                    basicAvailable, agentCompatible, checks);
        } catch (IOException ex) {
            return CodexProviderTestResult.failure("Agent runtime 无法启动；原始错误已隐藏 [REDACTED]",
                    testedAt, agentType, runtime.getId());
        } finally {
            deleteQuietly(tempDir);
        }
    }

    private CodexProviderProbeCheckResult runCheck(
            AgentRuntimeAdapter adapter,
            AgentRuntimesRecord runtime,
            CodexProviderResolvedConfig config,
            Path workspace,
            CodexProviderProbeName name,
            String prompt,
            Map<String, String> expectedFields
    ) {
        long started = System.nanoTime();
        try {
            if (Thread.currentThread().isInterrupted()) { throw new InterruptedException(); }
            CodexProcessResult result = adapter.run(new AgentRuntimeExecutionRequest(
                    runtime,
                    config,
                    workspace,
                    prompt,
                    Duration.ofSeconds(config.timeoutSeconds())
            ));
            long durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            Map<String, Object> diagnostics = ProbeDiagnostics.describe(result, config,
                    runtime.getAgentType(), runtime.getExecutablePath(), durationMs);
            if (result.timedOut()) {
                return CodexProviderProbeCheckResult.failure(name, "AGENT_PROBE_TIMEOUT", "agent probe 超时", durationMs)
                        .withDetails(diagnostics);
            }
            if (result.exitCode() != 0) {
                return CodexProviderProbeCheckResult.failure(name, "AGENT_PROBE_EXIT_NON_ZERO",
                        "agent probe 执行失败；原始输出已隐藏 [REDACTED]", durationMs).withDetails(diagnostics);
            }
            // Codex stderr 包含输入提示词；其中的示例 JSON 不能充当模型回答。
            JsonNode json = AgentType.CODEX.name().equals(runtime.getAgentType())
                    ? findExpectedJson(result.stdout(), expectedFields) : findExpectedJson(result, expectedFields);
            if (json == null) {
                return CodexProviderProbeCheckResult.failure(name, "AGENT_PROBE_OUTPUT_INVALID",
                        "agent probe 输出格式不正确；原始输出已隐藏 [REDACTED]", durationMs).withDetails(diagnostics);
            }
            return CodexProviderProbeCheckResult.success(name, "通过", durationMs).withDetails(diagnostics);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
            return CodexProviderProbeCheckResult.failure(name, "AGENT_PROBE_INTERRUPTED", "agent probe 被中断",
                    elapsed).withDetails(ProbeDiagnostics.failed(config, runtime.getAgentType(), runtime.getExecutablePath(), elapsed, true));
        } catch (IOException ex) {
            long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
            return CodexProviderProbeCheckResult.failure(name, "AGENT_PROBE_IO_FAILED",
                    "agent probe 无法启动；原始错误已隐藏 [REDACTED]",
                    elapsed).withDetails(ProbeDiagnostics.failed(config, runtime.getAgentType(), runtime.getExecutablePath(), elapsed, false));
        }
    }

    private CodexProviderProbeCheckResult runFileCheck(
            AgentRuntimeAdapter adapter,
            AgentRuntimesRecord runtime,
            CodexProviderResolvedConfig config,
            Path workspace,
            CodexProviderProbeName name,
            String prompt,
            Path outputFile,
            String expectedMarker
    ) {
        CodexProviderProbeCheckResult execution = runCheck(adapter, runtime, config, workspace, name, prompt,
                Map.of("status", "ok", "mode", "workspace-write"));
        if (!execution.succeeded()) {
            return execution;
        }
        try {
            if (!Files.exists(outputFile)) {
                return CodexProviderProbeCheckResult.failure(name, "AGENT_PROBE_OUTPUT_FILE_MISSING",
                        "agent probe 输出文件不存在", execution.durationMs()).withDetails(execution.details());
            }
            String output;
            try (var input = Files.newInputStream(outputFile)) {
                byte[] bytes = input.readNBytes(65537);
                if (bytes.length > 65536) {
                    return CodexProviderProbeCheckResult.failure(name, "AGENT_PROBE_OUTPUT_FILE_INVALID",
                            "agent probe 输出文件超过限制", execution.durationMs()).withDetails(execution.details());
                }
                output = new String(bytes, StandardCharsets.UTF_8);
            }
            JsonNode json = findExpectedJson(output, Map.of("marker", expectedMarker));
            if (json != null) {
                return execution;
            }
            json = findExpectedJson(output, Map.of());
            if (json == null) {
                return CodexProviderProbeCheckResult.failure(name, "AGENT_PROBE_OUTPUT_FILE_INVALID",
                        "agent probe 输出文件格式不正确；原始输出已隐藏 [REDACTED]", execution.durationMs()).withDetails(execution.details());
            }
            return CodexProviderProbeCheckResult.failure(name, "AGENT_PROBE_MARKER_MISMATCH",
                    "agent probe 输出文件 marker 不匹配", execution.durationMs()).withDetails(execution.details());
        } catch (IOException ex) {
            return CodexProviderProbeCheckResult.failure(name, "AGENT_PROBE_OUTPUT_FILE_MISSING",
                    "agent probe 输出文件读取失败；原始错误已隐藏 [REDACTED]", execution.durationMs()).withDetails(execution.details());
        }
    }

    private URI connectivityUri(CodexProviderResolvedConfig config) {
        String base = config.baseUrl().endsWith("/") ? config.baseUrl().substring(0, config.baseUrl().length() - 1) : config.baseUrl();
        String path = config.wireApi() == CodexProviderWireApi.RESPONSES ? "/responses" : "/chat/completions";
        return URI.create(base + path);
    }

    private String connectivityBody(CodexProviderResolvedConfig config) throws IOException {
        if (config.wireApi() == CodexProviderWireApi.RESPONSES) {
            return objectMapper.writeValueAsString(Map.of(
                    "model", config.model(),
                    "input", "Return exactly this JSON without markdown: {\"status\":\"ok\"}",
                    "max_output_tokens", 32
            ));
        }
        return objectMapper.writeValueAsString(Map.of(
                "model", config.model(),
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", "Return exactly this JSON without markdown: {\"status\":\"ok\"}"
                )),
                "max_tokens", 32
        ));
    }

    private void validateRunnable(CodexProviderResolvedConfig config) {
        if (!config.enabled()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CODEX_PROVIDER_DISABLED", "Codex provider 未启用");
        }
        if (isBlank(config.apiKey())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CODEX_PROVIDER_API_KEY_REQUIRED", "Codex provider API key 未配置");
        }
        if (isBlank(config.baseUrl()) || isBlank(config.model())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CODEX_PROVIDER_CONFIG_INCOMPLETE", "Codex provider 配置不完整");
        }
    }

    private String agentPrompt(AgentType agentType) {
        return """
                这是 UTF-8 中文传输校验。请严格按要求响应。
                Return exactly this JSON without markdown:
                {"status":"ok","agent":"%s","mode":"text"}
                """.formatted(agentType.name());
    }

    private String workspaceReadPrompt(AgentType agentType) {
        return """
                这是 UTF-8 中文传输校验。请读取工作区文件。
                Read ./probe-input.json, then return exactly this JSON without markdown:
                {"status":"ok","agent":"%s","mode":"workspace-read","marker":"rvport-probe-ok"}
                """.formatted(agentType.name());
    }

    private String workspaceWritePrompt() {
        return """
                这是 UTF-8 中文传输校验。请读取并写入工作区文件。
                Read ./probe-input.json and write this exact JSON object to ./probe-output.json:
                {"status":"ok","marker":"rvport-probe-ok"}
                Then return exactly this JSON without markdown:
                {"status":"ok","mode":"workspace-write"}
                """;
    }

    private JsonNode findExpectedJson(CodexProcessResult result, Map<String, String> expectedFields) {
        JsonNode stdoutResult = findExpectedJson(result.stdout(), expectedFields);
        if (stdoutResult != null) {
            return stdoutResult;
        }
        return findExpectedJson(cleanMergedOutput(result.mergedOutput()), expectedFields);
    }

    private JsonNode findExpectedJson(String output, Map<String, String> expectedFields) {
        if (isBlank(output)) {
            return null;
        }
        List<JsonNode> candidates = extractJsonObjects(output);
        for (int index = candidates.size() - 1; index >= 0; index--) {
            JsonNode candidate = candidates.get(index);
            boolean matches = expectedFields.entrySet().stream()
                    .allMatch(entry -> entry.getValue().equals(candidate.path(entry.getKey()).asText()));
            if (matches) {
                return candidate;
            }
        }
        return null;
    }

    private List<JsonNode> extractJsonObjects(String output) {
        List<JsonNode> candidates = new ArrayList<>();
        for (int start = 0; start < output.length(); start++) {
            if (output.charAt(start) != '{') {
                continue;
            }
            int end = findObjectEnd(output, start);
            if (end < 0) {
                continue;
            }
            try {
                JsonNode parsed = objectMapper.readTree(output.substring(start, end + 1));
                if (parsed.isObject()) {
                    candidates.add(parsed);
                    start = end;
                }
            } catch (IOException ignored) {
                // 当前花括号可能来自普通日志或 JSON 字符串，继续寻找下一个候选。
            }
        }
        return candidates;
    }

    private int findObjectEnd(String output, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < output.length(); index++) {
            char current = output.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private String cleanMergedOutput(String output) {
        if (isBlank(output)) {
            return output;
        }
        String withoutAnsi = ANSI_OSC.matcher(ANSI_CSI.matcher(output).replaceAll("")).replaceAll("");
        StringBuilder cleaned = new StringBuilder(withoutAnsi.length());
        int objectDepth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < withoutAnsi.length();) {
            String label = !inString ? channelLabelAt(withoutAnsi, index) : null;
            if (label != null) {
                index += label.length();
                if (index < withoutAnsi.length() && withoutAnsi.charAt(index) == ' ') {
                    index++;
                }
                continue;
            }
            char current = withoutAnsi.charAt(index++);
            cleaned.append(current);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
            } else if (current == '"' && objectDepth > 0) {
                inString = true;
            } else if (current == '{') {
                objectDepth++;
            } else if (current == '}' && objectDepth > 0) {
                objectDepth--;
            }
        }
        return cleaned.toString();
    }

    private String channelLabelAt(String output, int index) {
        for (String label : CHANNEL_LABELS) {
            if (output.startsWith(label, index)) {
                return label;
            }
        }
        return null;
    }

    private String firstFailureMessage(List<CodexProviderProbeCheckResult> checks) {
        return checks.stream()
                .filter(check -> !check.succeeded())
                .findFirst()
                .map(check -> check.name() + " 失败: " + check.message())
                .orElse("模型 profile agent 兼容性测试失败");
    }

    private void deleteQuietly(Path root) {
        if (root == null) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted((left, right) -> right.compareTo(left))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // 临时探测目录清理失败不影响配置测试结果。
                        }
                    });
        } catch (IOException ignored) {
            // ignore
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
