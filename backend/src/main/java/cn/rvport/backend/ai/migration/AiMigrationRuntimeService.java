package cn.rvport.backend.ai.migration;

import cn.rvport.backend.archive.ArchiveCandidateRegistrar;
import cn.rvport.backend.artifact.StoredArtifactOwnerType;
import cn.rvport.backend.artifact.StoredArtifactService;
import cn.rvport.backend.artifact.StoredArtifactView;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.kb.FindingView;
import cn.rvport.backend.kb.KbScanRepository;
import cn.rvport.backend.source.SourceRepository;
import cn.rvport.backend.source.SourceRevisionView;
import cn.rvport.backend.task.TaskErrorCode;
import cn.rvport.backend.task.TaskExecutionException;
import cn.rvport.backend.worker.TaskExecutionContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** AI_MIGRATION 执行编排：投影 → agent 执行 → 校验 → 产物入库 → 计数回写。 */
@Service
public class AiMigrationRuntimeService {

    static final String ARTIFACT_TYPE_DIFF = "MIGRATION_DIFF";
    static final String ARTIFACT_TYPE_RESULT = "MIGRATION_RESULT";

    private final AiMigrationRepository repository;
    private final KbScanRepository kbScanRepository;
    private final SourceRepository sourceRepository;
    private final AiMigrationAgentRuntime agentRuntime;
    private final StoredArtifactService storedArtifactService;
    private final ArchiveCandidateRegistrar archiveCandidateRegistrar;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public AiMigrationRuntimeService(AiMigrationRepository repository, KbScanRepository kbScanRepository,
            SourceRepository sourceRepository, AiMigrationAgentRuntime agentRuntime,
            StoredArtifactService storedArtifactService, ArchiveCandidateRegistrar archiveCandidateRegistrar,
            com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.repository = repository;
        this.kbScanRepository = kbScanRepository;
        this.sourceRepository = sourceRepository;
        this.agentRuntime = agentRuntime;
        this.storedArtifactService = storedArtifactService;
        this.archiveCandidateRegistrar = archiveCandidateRegistrar;
        this.mapper = mapper;
    }

    public void migrate(AiMigrationInput input, TaskExecutionContext context) {
        validate(input, context);
        AiMigrationRunView run = repository.findByTaskId(context.task().id())
                .orElseThrow(() -> new TaskExecutionException(TaskErrorCode.RESOURCE_NOT_FOUND, "迁移运行不存在", null));
        if (!Objects.equals(run.taskId(), context.task().id())) {
            throw new TaskExecutionException(TaskErrorCode.PROJECT_MISMATCH, "迁移运行与任务不匹配", null);
        }
        context.publish(() -> {
            repository.markRunning(run.id());
            return null;
        });
        List<FindingView> findings = kbScanRepository.listAllFindingViews(input.scanRunId());
        AiMigrationProjector.Projection projection = AiMigrationProjector.project(input.scanRunId(), findings);
        if (projection.suggestionClass().isEmpty()) {
            throw new TaskExecutionException(TaskErrorCode.AI_MIGRATION_NO_ENTRIES,
                    "当前扫描没有可迁移条目 / No projectable entries", null);
        }
        SourceRevisionView sourceRevision = sourceRepository.findById(input.sourceRevisionId())
                .orElseThrow(() -> new TaskExecutionException(TaskErrorCode.RESOURCE_NOT_FOUND, "源码版本不存在", null));
        Path sourceRoot = workingCopy(sourceRevision);
        AiMigrationAgentRuntime.Executed executed;
        try {
            executed = agentRuntime.run(projection, sourceRoot, input.selection(), context);
        } catch (TaskExecutionException ex) {
            throw ex;
        } catch (ApiException ex) {
            throw new TaskExecutionException(TaskErrorCode.AI_MIGRATION_MODEL_NOT_CONFIGURED, ex.getMessage(), null);
        } catch (IOException ex) {
            context.log(cn.rvport.backend.task.TaskLogType.STDERR,
                    "AGENT_IO_FAILURE " + ex.getClass().getName() + ": " + ex.getMessage());
            throw translate(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TaskExecutionException(TaskErrorCode.HANDLER_FAILED, "迁移执行被中断", null);
        }
        List<StoredArtifactView> artifacts = storeArtifacts(run, executed, context);
        Map<String, Object> summary = summary(executed);
        context.publish(() -> {
            repository.finish(run.id(), "SUCCEEDED", executed.result().expectedCount(),
                    executed.result().doneCount(), executed.result().noWorkNeededCount(),
                    executed.result().pendingCount(), summary);
            return null;
        });
        archiveCandidateRegistrar.registerAiMigration(run, summary, artifacts);
    }

    private void validate(AiMigrationInput input, TaskExecutionContext context) {
        if (input == null || input.projectId() == null || !input.projectId().equals(context.task().projectId())) {
            throw new TaskExecutionException(TaskErrorCode.INPUT_INVALID, "AI 迁移任务输入非法", null);
        }
    }

    private Path workingCopy(SourceRevisionView sourceRevision) {
        if (sourceRevision.workingCopyPath() == null || sourceRevision.workingCopyPath().isBlank()) {
            throw new TaskExecutionException(TaskErrorCode.HANDLER_FAILED, "源码工作副本不存在", null);
        }
        return Path.of(sourceRevision.workingCopyPath()).toAbsolutePath().normalize();
    }

    private List<StoredArtifactView> storeArtifacts(AiMigrationRunView run, AiMigrationAgentRuntime.Executed executed,
            TaskExecutionContext context) {
        List<StoredArtifactView> artifacts = new ArrayList<>();
        try {
            Path diffFile = Files.createTempFile("ai-migration-diff-", ".patch");
            try {
                Files.write(diffFile, executed.diffPatch());
                artifacts.add(store(run, ARTIFACT_TYPE_DIFF, diffFile, "text/x-diff", ".patch", executed, context));
            } finally {
                Files.deleteIfExists(diffFile);
            }
            Path resultFile = Files.createTempFile("ai-migration-result-", ".json");
            try {
                Files.writeString(resultFile, resultJson(executed), StandardCharsets.UTF_8);
                artifacts.add(store(run, ARTIFACT_TYPE_RESULT, resultFile, "application/json", ".json", executed, context));
            } finally {
                Files.deleteIfExists(resultFile);
            }
        } catch (IOException ex) {
            throw new TaskExecutionException(TaskErrorCode.HANDLER_FAILED, "迁移产物写入失败", null);
        }
        return List.copyOf(artifacts);
    }

    private StoredArtifactView store(AiMigrationRunView run, String artifactType, Path file, String contentType,
            String extension, AiMigrationAgentRuntime.Executed executed, TaskExecutionContext context) throws IOException {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("itemCount", executed.result().expectedCount());
        summary.put("doneCount", executed.result().doneCount());
        summary.put("noWorkNeededCount", executed.result().noWorkNeededCount());
        summary.put("pendingCount", executed.result().pendingCount());
        return storedArtifactService.storeFile(run.projectId(), context.task().id(),
                StoredArtifactOwnerType.AI_MIGRATION, run.id(), artifactType, file, contentType, summary, extension,
                context);
    }

    private String resultJson(AiMigrationAgentRuntime.Executed executed) {
        var root = mapper.createObjectNode();
        root.put("schemaVersion", AiMigrationResultValidator.SCHEMA_VERSION);
        root.put("expectedCount", executed.result().expectedCount());
        root.put("missingCount", executed.result().missingCount());
        root.set("unknownIds", mapper.valueToTree(executed.result().unknownIds()));
        root.set("duplicateIds", mapper.valueToTree(executed.result().duplicateIds()));
        var entries = root.putArray("entries");
        for (AiMigrationResultValidator.Entry entry : executed.result().entries()) {
            var node = entries.addObject();
            node.put("id", entry.id());
            node.put("status", entry.status());
            if (entry.changeSummary() != null) {
                node.put("changeSummary", entry.changeSummary());
            }
            node.set("files", mapper.valueToTree(entry.files()));
            if (entry.reason() != null) {
                node.put("reason", entry.reason());
            }
        }
        return root.toString();
    }

    private Map<String, Object> summary(AiMigrationAgentRuntime.Executed executed) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("itemCount", executed.result().expectedCount());
        summary.put("doneCount", executed.result().doneCount());
        summary.put("noWorkNeededCount", executed.result().noWorkNeededCount());
        summary.put("pendingCount", executed.result().pendingCount());
        summary.put("missingCount", executed.result().missingCount());
        summary.put("unknownIds", executed.result().unknownIds());
        summary.put("duplicateIds", executed.result().duplicateIds());
        return summary;
    }

    /**
     * 把运行时的失败翻译为任务错误。只对"重试必然复现"的失败使用不可重试码；
     * 技能克隆失败、代理超时与代理异常退出可能是偶发的，保留为可重试的 HANDLER_FAILED。
     */
    private TaskExecutionException translate(IOException ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage();
        return switch (message) {
            case "AI_MIGRATION_NO_ENTRIES" -> new TaskExecutionException(TaskErrorCode.AI_MIGRATION_NO_ENTRIES,
                    "当前扫描没有可迁移条目 / No projectable entries", null);
            case "AGENT_OUTPUT_INVALID" -> new TaskExecutionException(TaskErrorCode.AI_MIGRATION_OUTPUT_INVALID,
                    "迁移结果非法 / Invalid agent output", null);
            case "AI_MIGRATION_BASELINE_MODIFIED" -> nonRetryable(
                    "迁移基准在工作区中被改动，补丁不可信 / Migration baseline was modified in the workspace");
            case "AI_MIGRATION_INPUT_MODIFIED" -> nonRetryable(
                    "迁移输入在工作区中被改动 / Migration input was modified in the workspace");
            case "AI_MIGRATION_EXECUTOR_MODE_INVALID" -> nonRetryable(
                    "迁移执行器模式配置无效 / Invalid migration executor mode");
            case "AI_MIGRATION_EXECUTOR_UNAVAILABLE" -> nonRetryable(
                    "迁移执行器未配置 / Migration executor is not configured");
            case "AI_MIGRATION_SOURCE_PATH_UNSAFE" -> nonRetryable(
                    "迁移条目指向了源码目录之外的路径 / Migration entry points outside the source tree");
            case "AI_MIGRATION_SOURCE_SYMLINK" -> nonRetryable(
                    "迁移结果包含符号链接，补丁不可信 / Migration result contains symbolic links");
            case "AI_MIGRATION_DIFF_FAILED" -> nonRetryable(
                    "迁移补丁生成失败 / Failed to generate the migration patch");
            default -> new TaskExecutionException(TaskErrorCode.HANDLER_FAILED,
                    "迁移执行失败 / Migration execution failed: " + message, null);
        };
    }

    private static TaskExecutionException nonRetryable(String message) {
        return new TaskExecutionException(TaskErrorCode.AI_MIGRATION_NOT_RETRYABLE, message, null);
    }
}
