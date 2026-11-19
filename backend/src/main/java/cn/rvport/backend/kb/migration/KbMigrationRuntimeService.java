package cn.rvport.backend.kb.migration;

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

/** KB_MIGRATION 执行编排：投影 → agent 执行 → 校验 → 产物入库 → 计数回写。 */
@Service
public class KbMigrationRuntimeService {

    static final String ARTIFACT_TYPE_DIFF = "MIGRATION_DIFF";
    static final String ARTIFACT_TYPE_RESULT = "MIGRATION_RESULT";

    private final KbMigrationRepository repository;
    private final KbScanRepository kbScanRepository;
    private final SourceRepository sourceRepository;
    private final KbMigrationAgentRuntime agentRuntime;
    private final StoredArtifactService storedArtifactService;
    private final ArchiveCandidateRegistrar archiveCandidateRegistrar;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public KbMigrationRuntimeService(KbMigrationRepository repository, KbScanRepository kbScanRepository,
            SourceRepository sourceRepository, KbMigrationAgentRuntime agentRuntime,
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

    public void migrate(KbMigrationInput input, TaskExecutionContext context) {
        validate(input, context);
        KbMigrationRunView run = repository.findRun(input.migrationRunId())
                .orElseThrow(() -> new TaskExecutionException(TaskErrorCode.RESOURCE_NOT_FOUND, "迁移运行不存在", null));
        if (!Objects.equals(run.taskId(), context.task().id())) {
            throw new TaskExecutionException(TaskErrorCode.PROJECT_MISMATCH, "迁移运行与任务不匹配", null);
        }
        context.publish(() -> {
            repository.markRunning(run.id());
            return null;
        });
        List<FindingView> findings = kbScanRepository.listAllFindingViews(input.scanRunId());
        KbMigrationProjector.Projection projection = KbMigrationProjector.project(input.scanRunId(), findings);
        if (projection.items().isEmpty()) {
            throw new TaskExecutionException(TaskErrorCode.KB_MIGRATION_NO_ENTRIES,
                    "当前扫描没有可迁移条目 / No projectable entries", null);
        }
        SourceRevisionView sourceRevision = sourceRepository.findById(input.sourceRevisionId())
                .orElseThrow(() -> new TaskExecutionException(TaskErrorCode.RESOURCE_NOT_FOUND, "源码版本不存在", null));
        Path sourceRoot = workingCopy(sourceRevision);
        KbMigrationAgentRuntime.Executed executed;
        try {
            executed = agentRuntime.run(projection, sourceRoot, input.selection(), context);
        } catch (TaskExecutionException ex) {
            throw ex;
        } catch (ApiException ex) {
            throw new TaskExecutionException(TaskErrorCode.KB_MIGRATION_MODEL_NOT_CONFIGURED, ex.getMessage(), null);
        } catch (IOException ex) {
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
        archiveCandidateRegistrar.registerKbMigration(run, summary, artifacts);
    }

    private void validate(KbMigrationInput input, TaskExecutionContext context) {
        if (input == null || input.projectId() == null || !input.projectId().equals(context.task().projectId())) {
            throw new TaskExecutionException(TaskErrorCode.INPUT_INVALID, "KB 迁移任务输入非法", null);
        }
    }

    private Path workingCopy(SourceRevisionView sourceRevision) {
        if (sourceRevision.workingCopyPath() == null || sourceRevision.workingCopyPath().isBlank()) {
            throw new TaskExecutionException(TaskErrorCode.HANDLER_FAILED, "源码工作副本不存在", null);
        }
        return Path.of(sourceRevision.workingCopyPath()).toAbsolutePath().normalize();
    }

    private List<StoredArtifactView> storeArtifacts(KbMigrationRunView run, KbMigrationAgentRuntime.Executed executed,
            TaskExecutionContext context) {
        List<StoredArtifactView> artifacts = new ArrayList<>();
        try {
            Path diffFile = Files.createTempFile("kb-migration-diff-", ".patch");
            try {
                Files.write(diffFile, executed.diffPatch());
                artifacts.add(store(run, ARTIFACT_TYPE_DIFF, diffFile, "text/x-diff", ".patch", executed, context));
            } finally {
                Files.deleteIfExists(diffFile);
            }
            Path resultFile = Files.createTempFile("kb-migration-result-", ".json");
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

    private StoredArtifactView store(KbMigrationRunView run, String artifactType, Path file, String contentType,
            String extension, KbMigrationAgentRuntime.Executed executed, TaskExecutionContext context) throws IOException {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("itemCount", executed.result().expectedCount());
        summary.put("doneCount", executed.result().doneCount());
        summary.put("noWorkNeededCount", executed.result().noWorkNeededCount());
        summary.put("pendingCount", executed.result().pendingCount());
        return storedArtifactService.storeFile(run.projectId(), context.task().id(),
                StoredArtifactOwnerType.KB_MIGRATION, run.id(), artifactType, file, contentType, summary, extension,
                context);
    }

    private String resultJson(KbMigrationAgentRuntime.Executed executed) {
        var root = mapper.createObjectNode();
        root.put("schemaVersion", KbMigrationResultValidator.SCHEMA_VERSION);
        root.put("expectedCount", executed.result().expectedCount());
        root.put("missingCount", executed.result().missingCount());
        root.set("unknownIds", mapper.valueToTree(executed.result().unknownIds()));
        root.set("duplicateIds", mapper.valueToTree(executed.result().duplicateIds()));
        var entries = root.putArray("entries");
        for (KbMigrationResultValidator.Entry entry : executed.result().entries()) {
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

    private Map<String, Object> summary(KbMigrationAgentRuntime.Executed executed) {
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

    private TaskExecutionException translate(IOException ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage();
        return switch (message) {
            case "KB_MIGRATION_NO_ENTRIES" -> new TaskExecutionException(TaskErrorCode.KB_MIGRATION_NO_ENTRIES,
                    "当前扫描没有可迁移条目 / No projectable entries", null);
            case "AGENT_OUTPUT_INVALID" -> new TaskExecutionException(TaskErrorCode.KB_MIGRATION_OUTPUT_INVALID,
                    "迁移结果非法 / Invalid agent output", null);
            default -> new TaskExecutionException(TaskErrorCode.HANDLER_FAILED,
                    "迁移执行失败 / Migration execution failed: " + message, null);
        };
    }
}
