package cn.rvport.backend.testcomparison.runtime;

import cn.rvport.backend.agentruntime.AgentModelRuntimeConfig;
import cn.rvport.backend.agentruntime.AgentRuntimeAdapter;
import cn.rvport.backend.agentruntime.AgentRuntimeAdapterRegistry;
import cn.rvport.backend.agentruntime.AgentRuntimeExecutionRequest;
import cn.rvport.backend.codexprovider.CodexProviderResolvedConfig;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.modelprofile.ModelProfileService;
import cn.rvport.backend.modelprofile.ModelProfileStage;
import cn.rvport.backend.modelprofile.ModelExecutionSelection;
import java.util.UUID;
import cn.rvport.backend.testcomparison.TestComparisonOverallStatus;
import cn.rvport.backend.testcomparison.TestComparisonParsedFinding;
import cn.rvport.backend.testcomparison.TestComparisonRiskLevel;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class TestComparisonAgentRuntime {

    static final String RESULT_FILE_NAME = "comparison-result.json";
    private static final int MAX_RESULT_FILE_BYTES = 1024 * 1024;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Set<String> TEST_STATUSES = Set.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMEOUT", "UNKNOWN");
    private static final Set<String> DIFFERENCE_TYPES = Set.of("EXIT_CODE", "STDERR", "STDOUT", "REPORT", "MISSING_REPORT", "OTHER");
    private static final Pattern FENCED_BLOCK = Pattern.compile("(?s)```(?:json|JSON)?\\s*(.*?)\\s*```");

    private final ModelProfileService modelProfileService;
    private final AgentRuntimeAdapterRegistry adapterRegistry;
    private final ObjectMapper objectMapper;

    public TestComparisonAgentRuntime(
            ModelProfileService modelProfileService,
            AgentRuntimeAdapterRegistry adapterRegistry,
            ObjectMapper objectMapper
    ) {
        this.modelProfileService = modelProfileService;
        this.adapterRegistry = adapterRegistry;
        this.objectMapper = objectMapper;
    }

    public AgentModelRuntimeConfig resolveRunnableConfig(UUID actorUserId) {
        AgentModelRuntimeConfig resolved = modelProfileService.resolveAgentForStage(ModelProfileStage.TEST_LOG_COMPARE, actorUserId);
        return validate(resolved);
    }

    private AgentModelRuntimeConfig validate(AgentModelRuntimeConfig resolved) {
        CodexProviderResolvedConfig config = resolved.model();
        if (!config.enabled() || isBlank(config.apiKey()) || isBlank(config.baseUrl()) || isBlank(config.model())
                || resolved.runtime() == null || isBlank(resolved.runtime().getExecutablePath())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TEST_COMPARE_MODEL_NOT_CONFIGURED", "测试结果比较模型配置不完整");
        }
        return resolved;
    }

    public AgentModelRuntimeConfig resolveExecution(ModelExecutionSelection selection, UUID actor, UUID projectId) {
        if (selection == null) { throw ModelExecutionSelection.missing(); }
        selection.requireActor(actor);
        AgentModelRuntimeConfig resolved = modelProfileService.resolveExecution(actor, projectId,
                selection.modelProfileId(), selection.agentRuntimeId());
        return validate(resolved);
    }
    public TestComparisonAgentResult run(TestComparisonInputBundle input, AgentModelRuntimeConfig resolved) {
        CodexProviderResolvedConfig config = resolved.model();
        AgentRuntimeAdapter adapter = adapterRegistry.get(resolved.runtime().getAgentType());
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("rvport-test-comparison-");
            Path inputFile = tempDir.resolve("comparison-input.json");
            Files.writeString(inputFile, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(input.payload()), StandardCharsets.UTF_8);
            var result = adapter.run(new AgentRuntimeExecutionRequest(
                    resolved.runtime(),
                    config,
                    tempDir,
                    prompt(inputFile.getFileName().toString()),
                    Duration.ofSeconds(config.maxRuntimeSeconds())
            ));
            Map<String, Object> agentOutput = new LinkedHashMap<>();
            agentOutput.put("stdout", result.stdout() == null ? "" : result.stdout());
            agentOutput.put("stderr", result.stderr() == null ? "" : result.stderr());
            agentOutput.put("mergedOutput", result.mergedOutput() == null ? "" : result.mergedOutput());
            agentOutput.put("exitCode", result.exitCode());
            agentOutput.put("timedOut", result.timedOut());
            agentOutput.put("agentType", resolved.runtime().getAgentType());
            agentOutput.put("agentRuntimeId", resolved.runtime().getId());
            JsonSource jsonSource = resolveJsonSource(tempDir, result.stdout());
            agentOutput.put("resultFilePresent", jsonSource.resultFilePresent());
            agentOutput.put("resultFileContent", jsonSource.content());
            agentOutput.put("parseSource", jsonSource.parseSource());
            if (result.timedOut()) {
                throw invalid("TEST_COMPARE_AGENT_TIMEOUT", "测试结果比较 agent 执行超时", agentOutput);
            }
            if (result.exitCode() != 0) {
                throw invalid("TEST_COMPARE_AGENT_FAILED", sanitize(firstNonBlank(result.stderr(), result.stdout(), result.mergedOutput()), config), agentOutput);
            }
            return parse(jsonSource.content(), agentOutput);
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "TEST_COMPARE_AGENT_IO_FAILED", "测试结果比较 agent 输入输出失败");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "TEST_COMPARE_AGENT_INTERRUPTED", "测试结果比较 agent 被中断");
        } finally {
            deleteQuietly(tempDir);
        }
    }

    private String prompt(String inputFileName) {
        return """
                你是测试结果比较 agent。当前目录是本次任务的专用 workspace。
                读取当前目录下的文件 %s，对 source 和 target 的测试 mergedLog 和报告进行对比。stdout/stderr 只作为 mergedLog 缺失时的降级材料。
                不要为特定测试工具编造解析规则；你需要从日志和报告内容中归纳测试数量、失败项和失败原因。
                如果无法可靠判断测试数量，相关数字返回 null，并降低 extractionConfidence。
                必须把最终结构化结果写入当前目录下的 %s。
                stdout 可以输出简短说明，但不要把 JSON 只写到 stdout。不要输出 markdown。
                枚举字段必须写单个合法字符串，不允许写组合枚举，不允许写 ... 占位符。
                JSON 结构如下，示例值仅表示类型，不能照抄：
                {
                  "overallStatus": "INCONCLUSIVE",
                  "riskLevel": "INFO",
                  "confidence": 0.0,
                  "summary": "一句话总结 source 和 target 的测试差异",
                  "recommendation": "下一步建议",
                  "sourceStats": {
                    "status": "SUCCEEDED",
                    "exitCode": 0,
                    "totalTests": 0,
                    "executedTests": 0,
                    "passedTests": 0,
                    "failedTests": 0,
                    "skippedTests": 0,
                    "failedItems": [{"name":"case name","reason":"failure reason","evidence":"short evidence"}],
                    "extractionConfidence": 0.0,
                    "testCountBasis": "说明测试数量的判断依据"
                  },
                  "targetStats": {
                    "status": "FAILED",
                    "exitCode": 0,
                    "totalTests": 0,
                    "executedTests": 0,
                    "passedTests": 0,
                    "failedTests": 0,
                    "skippedTests": 0,
                    "failedItems": [{"name":"case name","reason":"failure reason","evidence":"short evidence"}],
                    "extractionConfidence": 0.0,
                    "testCountBasis": "说明测试数量的判断依据"
                  },
                  "findings": [
                    {
                      "differenceType": "EXIT_CODE",
                      "severity": "HIGH",
                      "confidence": 0.0,
                      "title": "差异标题",
                      "description": "差异描述",
                      "sourceSummary": "source 摘要",
                      "targetSummary": "target 摘要",
                      "recommendation": "处理建议"
                    }
                  ]
                }
                合法 overallStatus：EQUIVALENT、DIFFERENT_ACCEPTABLE、DIFFERENT_BLOCKING、INCONCLUSIVE。
                合法 riskLevel/severity：CRITICAL、HIGH、MEDIUM、LOW、INFO。
                合法 sourceStats.status/targetStats.status：SUCCEEDED、FAILED、CANCELLED、TIMEOUT、UNKNOWN。
                合法 differenceType：EXIT_CODE、STDERR、STDOUT、REPORT、MISSING_REPORT、OTHER。
                """.formatted(inputFileName, RESULT_FILE_NAME);
    }

    private TestComparisonAgentResult parse(String raw, Map<String, Object> agentOutput) {
        try {
            Map<String, Object> root = strictReadMap(raw);
            TestComparisonOverallStatus overall = enumValue(TestComparisonOverallStatus.class, root.get("overallStatus"), "overallStatus");
            TestComparisonRiskLevel risk = enumValue(TestComparisonRiskLevel.class, root.get("riskLevel"), "riskLevel");
            BigDecimal confidence = decimalInRange(root.get("confidence"), "confidence", false);
            String summary = requiredText(root.get("summary"), "summary");
            String recommendation = requiredText(root.get("recommendation"), "recommendation");
            Map<String, Object> sourceStats = stats(root.get("sourceStats"));
            Map<String, Object> targetStats = stats(root.get("targetStats"));
            List<TestComparisonParsedFinding> findings = findings(root.get("findings"));
            return new TestComparisonAgentResult(overall, risk, confidence, summary, recommendation, sourceStats, targetStats, findings, raw, agentOutput);
        } catch (Exception ex) {
            throw invalid("TEST_COMPARE_AGENT_OUTPUT_INVALID", "测试结果比较 agent 输出不是有效 JSON 或未通过语义校验", agentOutput);
        }
    }

    private List<TestComparisonParsedFinding> findings(Object value) {
        if (!(value instanceof List<?> list)) {
            throw invalidOutput("findings 必须是数组");
        }
        List<TestComparisonParsedFinding> findings = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> rawMap)) {
                throw invalidOutput("findings 条目必须是对象");
            }
            Map<String, Object> map = new LinkedHashMap<>();
            rawMap.forEach((key, rawValue) -> map.put(String.valueOf(key), rawValue));
            String differenceType = requiredEnumString(map.get("differenceType"), DIFFERENCE_TYPES, "findings.differenceType");
            TestComparisonRiskLevel severity = enumValue(TestComparisonRiskLevel.class, map.get("severity"), "findings.severity");
            BigDecimal confidence = decimalInRange(map.get("confidence"), "findings.confidence", false);
            String title = requiredText(map.get("title"), "findings.title");
            String description = requiredText(map.get("description"), "findings.description");
            String sourceSummary = optionalText(map.get("sourceSummary"), "findings.sourceSummary");
            String targetSummary = optionalText(map.get("targetSummary"), "findings.targetSummary");
            String recommendation = requiredText(map.get("recommendation"), "findings.recommendation");
            findings.add(new TestComparisonParsedFinding(
                    differenceType,
                    severity,
                    confidence,
                    title,
                    description,
                    sourceSummary,
                    targetSummary,
                    recommendation,
                    map
            ));
        }
        return findings;
    }

    private Map<String, Object> stats(Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            throw invalidOutput("sourceStats/targetStats 必须是对象");
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        rawMap.forEach((key, rawValue) -> {
            String name = String.valueOf(key);
            if (List.of("totalTests", "executedTests", "passedTests", "failedTests", "skippedTests", "exitCode").contains(name)) {
                stats.put(name, nonNegativeIntegerOrNull(rawValue, name));
            } else if ("extractionConfidence".equals(name)) {
                stats.put(name, decimalInRange(rawValue, name, false));
            } else if ("failedItems".equals(name)) {
                stats.put(name, failedItems(rawValue));
            } else {
                stats.put(name, rawValue == null ? null : optionalText(rawValue, name));
            }
        });
        stats.put("status", requiredEnumString(stats.get("status"), TEST_STATUSES, "stats.status"));
        stats.putIfAbsent("failedItems", List.of());
        if (!stats.containsKey("extractionConfidence")) {
            stats.put("extractionConfidence", null);
        }
        if (!stats.containsKey("testCountBasis")) {
            stats.put("testCountBasis", null);
        }
        return stats;
    }

    private List<Map<String, Object>> failedItems(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw invalidOutput("failedItems 必须是数组");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> rawMap)) {
                throw invalidOutput("failedItems 条目必须是对象");
            }
            Map<String, Object> map = new LinkedHashMap<>();
            rawMap.forEach((key, rawValue) -> map.put(String.valueOf(key), rawValue == null ? null : optionalText(rawValue, "failedItems." + key)));
            items.add(map);
        }
        return items;
    }

    private Integer nonNegativeIntegerOrNull(Object value, String field) {
        if (value == null) {
            return null;
        }
        try {
            Integer parsed = Integer.valueOf(String.valueOf(value));
            if (parsed < 0) {
                throw invalidOutput(field + " 不能为负数");
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw invalidOutput(field + " 必须是数字或 null");
        }
    }

    private JsonSource resolveJsonSource(Path tempDir, String stdout) throws IOException {
        Path resultFile = tempDir.resolve(RESULT_FILE_NAME);
        if (Files.exists(resultFile)) {
            long size = Files.size(resultFile);
            if (size > MAX_RESULT_FILE_BYTES) {
                return new JsonSource(true, "", "file-too-large");
            }
            return new JsonSource(true, Files.readString(resultFile, StandardCharsets.UTF_8), "comparison-result.json");
        }
        String fallback = extractJsonFromStdout(stdout);
        return new JsonSource(false, fallback, fallback.isBlank() ? "missing" : "stdout");
    }

    private String extractJsonFromStdout(String stdout) {
        String text = stdout == null ? "" : stdout;
        String fenced = "";
        var matcher = FENCED_BLOCK.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group(1).trim();
            if (candidate.startsWith("{") && candidate.endsWith("}") && canParseMap(candidate)) {
                fenced = candidate;
            }
        }
        if (!fenced.isBlank()) {
            return fenced;
        }
        String last = "";
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '{') {
                continue;
            }
            String candidate = jsonObjectStartingAt(text, i);
            if (!candidate.isBlank() && canParseMap(candidate)) {
                last = candidate;
            }
        }
        return last;
    }

    private String jsonObjectStartingAt(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return "";
    }

    private boolean canParseMap(String value) {
        try {
            strictReadMap(value);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private Map<String, Object> strictReadMap(String value) throws IOException {
        if (value == null || value.isBlank()) {
            throw invalidOutput("JSON 内容为空");
        }
        ObjectMapper strict = objectMapper.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        return strict.readValue(value, MAP_TYPE);
    }

    private BigDecimal decimalInRange(Object value, String field, boolean required) {
        if (value == null) {
            if (required) {
                throw invalidOutput(field + " 不能为空");
            }
            return null;
        }
        try {
            BigDecimal parsed = new BigDecimal(String.valueOf(value));
            if (parsed.compareTo(BigDecimal.ZERO) < 0 || parsed.compareTo(BigDecimal.ONE) > 0) {
                throw invalidOutput(field + " 必须在 0 到 1 之间");
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw invalidOutput(field + " 必须是数字或 null");
        }
    }

    private <T extends Enum<T>> T enumValue(Class<T> type, Object value, String field) {
        if (value == null) {
            throw invalidOutput(field + " 不能为空");
        }
        try {
            return Enum.valueOf(type, String.valueOf(value).trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw invalidOutput(field + " 不是合法枚举值");
        }
    }

    private String requiredEnumString(Object value, Set<String> allowed, String field) {
        String text = requiredText(value, field).toUpperCase();
        if (text.contains("|") || !allowed.contains(text)) {
            throw invalidOutput(field + " 不是合法单值枚举");
        }
        return text;
    }

    private String requiredText(Object value, String field) {
        String text = optionalText(value, field);
        if (text == null || text.isBlank()) {
            throw invalidOutput(field + " 不能为空");
        }
        return text;
    }

    private String optionalText(Object value, String field) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if ("...".equals(text) || text.contains("SUCCEEDED|FAILED") || text.contains("CRITICAL|HIGH") || text.contains("EXIT_CODE|STDERR")) {
            throw invalidOutput(field + " 不能是 schema 占位符");
        }
        return text;
    }

    private TestComparisonAgentOutputException invalid(String code, String message, Map<String, Object> agentOutput) {
        return new TestComparisonAgentOutputException(code, message, agentOutput);
    }

    private IllegalArgumentException invalidOutput(String message) {
        return new IllegalArgumentException(message);
    }

    private String sanitize(String text, CodexProviderResolvedConfig config) {
        String sanitized = text == null ? "测试结果比较 agent 执行失败" : text;
        if (!isBlank(config.apiKey())) {
            sanitized = sanitized.replace(config.apiKey(), "[REDACTED]");
        }
        return sanitized.length() > 1000 ? sanitized.substring(0, 1000) : sanitized;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record JsonSource(boolean resultFilePresent, String content, String parseSource) {
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
}
