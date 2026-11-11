package cn.rvport.backend.kb.ai;

import cn.rvport.backend.agentruntime.AgentModelRuntimeConfig;
import cn.rvport.backend.agentruntime.AgentRuntimeAdapter;
import cn.rvport.backend.agentruntime.AgentRuntimeAdapterRegistry;
import cn.rvport.backend.agentruntime.AgentRuntimeExecutionRequest;
import cn.rvport.backend.codexprovider.CodexProviderResolvedConfig;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.kb.FindingView;
import cn.rvport.backend.modelprofile.ModelProfileService;
import cn.rvport.backend.modelprofile.ModelProfileStage;
import cn.rvport.backend.modelprofile.ModelExecutionSelection;
import java.util.UUID;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class KbAiExtensionAgentRuntime {

    private static final String RESULT_FILE_NAME = "output/results-0001.jsonl";
    private static final Set<String> SKIP_DIRS = Set.of(".git", "build", "target", ".idea", ".vscode", "node_modules");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ModelProfileService modelProfileService;
    private final AgentRuntimeAdapterRegistry adapterRegistry;
    private final ObjectMapper objectMapper;

    public KbAiExtensionAgentRuntime(
            ModelProfileService modelProfileService,
            AgentRuntimeAdapterRegistry adapterRegistry,
            ObjectMapper objectMapper
    ) {
        this.modelProfileService = modelProfileService;
        this.adapterRegistry = adapterRegistry;
        this.objectMapper = objectMapper;
    }

    public AgentModelRuntimeConfig resolveRunnableConfig(UUID actorUserId) {
        AgentModelRuntimeConfig resolved = modelProfileService.resolveAgentForStage(ModelProfileStage.KB_AI_EXTENSION, actorUserId);
        return validate(resolved);
    }

    private AgentModelRuntimeConfig validate(AgentModelRuntimeConfig resolved) {
        CodexProviderResolvedConfig config = resolved.model();
        if (!config.enabled() || isBlank(config.apiKey()) || isBlank(config.baseUrl()) || isBlank(config.model())
                || resolved.runtime() == null || isBlank(resolved.runtime().getExecutablePath())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "KB_AI_EXTENSION_MODEL_NOT_CONFIGURED", "知识库 AI 扩展模型配置不完整");
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
    public KbAiExtensionAgentResult run(Path sourceRoot, List<FindingView> findings, List<Map<String, Object>> exportRows, AgentModelRuntimeConfig resolved)
            throws IOException, InterruptedException {
        Path workspace = Files.createTempDirectory("rvport-kb-ai-extension-");
        try {
            CodexProviderResolvedConfig config = resolved.model();
            AgentRuntimeAdapter adapter = adapterRegistry.get(resolved.runtime().getAgentType());
            Path inputDir = Files.createDirectories(workspace.resolve("input"));
            Path outputDir = Files.createDirectories(workspace.resolve("output"));
            copySource(sourceRoot, workspace.resolve("source"));

            Path findingsFile = inputDir.resolve("findings-0001.jsonl");
            writeJsonl(findingsFile, exportRows);
            Path manifestFile = inputDir.resolve("manifest.json");
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("schemaVersion", "kb-ai-extension-input.v2");
            manifest.put("findingCount", findings.size());
            manifest.put("findingsFile", "input/findings-0001.jsonl");
            manifest.put("sourceRoot", "source");
            Files.writeString(manifestFile, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest), StandardCharsets.UTF_8);

            var result = adapter.run(new AgentRuntimeExecutionRequest(
                    resolved.runtime(),
                    config,
                    workspace,
                    prompt(),
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

            if (result.timedOut()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "KB_AI_EXTENSION_AGENT_TIMEOUT", "知识库 AI 扩展 agent 执行超时", agentOutput);
            }
            if (result.exitCode() != 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "KB_AI_EXTENSION_AGENT_FAILED", "知识库 AI 扩展 agent 执行失败", agentOutput);
            }
            Path outputFile = outputDir.resolve("results-0001.jsonl");
            List<Map<String, Object>> rawRows = readJsonl(outputFile);
            return new KbAiExtensionAgentResult(workspace, findingsFile, outputFile, rawRows, agentOutput);
        } catch (IOException | InterruptedException | RuntimeException ex) {
            deleteQuietly(workspace);
            throw ex;
        }
    }

    private String prompt() {
        return """
                你是 RvPort 知识库扫描 AI 扩展 agent。当前目录是本次任务的专用 workspace。
                读取 input/manifest.json 和 input/findings-0001.jsonl，为每条 schema v2 finding 生成补充建议。
                每条输入包含 fact、conclusion、occurrences、evidenceChain、后端源码上下文和当前人工状态。
                如果 finding 自带的信息不足以判断，可以读取 source/ 下的项目源码；只允许读取，不允许修改任何文件。
                允许部分条目无法判断，但每条输出必须能通过 findingId 追溯到输入。
                最终结果必须写入 output/results-0001.jsonl，每行一个 JSON 对象，不能输出 markdown。
                每行 JSON 结构如下：
                {"findingId":"uuid","aiSummary":"一句话摘要","aiSuggestion":"具体建议","confidence":0.0,"actionType":"REVIEW","notes":"可选说明"}
                confidence 必须在 0 到 1 之间。actionType 建议使用 REVIEW、CHANGE_CODE、CHANGE_CONFIG、NO_ACTION 或 UNKNOWN。
                """;
    }

    private void writeJsonl(Path file, List<Map<String, Object>> rows) throws IOException {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            lines.add(objectMapper.writeValueAsString(row));
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    private List<Map<String, Object>> readJsonl(Path file) throws IOException {
        if (!Files.exists(file)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "KB_AI_EXTENSION_OUTPUT_MISSING", "知识库 AI 扩展结果文件不存在");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    rows.add(objectMapper.readValue(line, MAP_TYPE));
                }
            }
        }
        return rows;
    }

    private void copySource(Path sourceRoot, Path targetRoot) throws IOException {
        Files.createDirectories(targetRoot);
        if (sourceRoot == null || !Files.isDirectory(sourceRoot)) {
            return;
        }
        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!sourceRoot.equals(dir) && SKIP_DIRS.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(targetRoot.resolve(sourceRoot.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = sourceRoot.relativize(file);
                Files.copy(file, targetRoot.resolve(relative));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
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
                    // 临时目录清理失败不影响业务错误。
                }
            });
        } catch (IOException ignored) {
            // ignore
        }
    }
}
