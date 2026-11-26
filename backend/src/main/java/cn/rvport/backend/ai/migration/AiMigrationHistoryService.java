package cn.rvport.backend.ai.migration;

import cn.rvport.backend.ai.migration.MigrationRepoService.HistoryEntry;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.project.ProjectAccessService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 迁移运行的历史查询、按提交读文件与回滚编排（change add-ai-migration-git-history 任务 4.x）。
 *
 * <p>三个端点共用同一套仓库定位与访问校验：运行记录 → 项目归属 → runs/{runId}/ 仓库。
 * 回滚在仓库上执行三原语（working-tree / soft reset / hard reset），打回滚 marker，
 * 重新派生 DIFF 与 PATCH_SERIES 产物（新版本入库），并更新运行摘要计数；终态 status 不变。
 */
@Service
public class AiMigrationHistoryService {

    static final int HISTORY_MAX_COUNT = 200;

    private final AiMigrationRepository repository;
    private final MigrationRepoService repo;
    private final ProjectAccessService access;
    private final MigrationStatusWriter statusWriter;

    public AiMigrationHistoryService(AiMigrationRepository repository, MigrationRepoService repo,
            ProjectAccessService access, MigrationStatusWriter statusWriter) {
        this.repository = repository;
        this.repo = repo;
        this.access = access;
        this.statusWriter = statusWriter;
    }

    /** 历史条目响应（reflog 提交点）。 */
    public record HistoryResponse(String hash, String shortHash, String message,
            String type, String stage, String src, String reason, String label, boolean structured,
            String time, boolean current) {

        static HistoryResponse from(HistoryEntry entry) {
            var label = entry.label();
            return new HistoryResponse(entry.hash(), entry.shortHash(), entry.message(),
                    label.type(), label.stage(), label.src(), label.reason(), label.display(),
                    label.structured(), entry.time(), entry.current());
        }
    }

    /** 回滚请求体。 */
    public record RollbackRequest(String target, String commitHash) {
    }

    /** 回滚响应：回滚后 HEAD、新派生的产物类型列表。 */
    public record RollbackResponse(String head, List<String> artifactTypes) {
    }

    private record Located(AiMigrationRunView run, Path repoPath) {
    }

    public List<HistoryResponse> history(UUID runId, UUID actorUserId, boolean superAdmin) {
        Located located = locate(runId, actorUserId, superAdmin);
        requireRepository(located);
        return repo.history(located.repoPath(), HISTORY_MAX_COUNT).stream()
                .map(HistoryResponse::from)
                .toList();
    }

    /**
     * 按提交读取仓库内文件内容。
     *
     * <p>路径安全：拒绝绝对路径与 .. 穿越；解析后必须仍在仓库内且为普通文件（NOFOLLOW）。
     * ref 缺省 HEAD；只允许本仓库历史可达的提交（防任意 hash 读）。
     */
    public String repoFile(UUID runId, String path, String ref, UUID actorUserId, boolean superAdmin) {
        Located located = locate(runId, actorUserId, superAdmin);
        requireRepository(located);
        if (path == null || path.isBlank()) {
            throw bad("AI_MIGRATION_FILE_PATH_REQUIRED");
        }
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.split("/").length > 0
                && java.util.Arrays.stream(normalized.split("/")).anyMatch(p -> p.equals("..") || p.isBlank())) {
            throw bad("AI_MIGRATION_FILE_PATH_UNSAFE");
        }
        String resolvedRef = resolveRef(located.repoPath(), ref);
        // 限定可读范围：source/ 与 baseline/ 之下的文件（input/output 是运行合同，不属读取面）
        if (!normalized.startsWith("source/") && !normalized.startsWith("baseline/")) {
            throw bad("AI_MIGRATION_FILE_PATH_UNSAFE");
        }
        String content;
        try {
            content = repo.gitRead(located.repoPath(), "show", resolvedRef + ":" + normalized);
        } catch (RuntimeException ex) {
            throw new ApiException(HttpStatus.NOT_FOUND, "AI_MIGRATION_FILE_NOT_FOUND",
                    "文件不存在于指定版本 / File not found at the requested revision");
        }
        return content;
    }

    /**
     * 回滚三原语。回滚后派生产物重建与摘要更新由 {@link #rollbackInternal} 完成。
     * 仅运行归属人/超级管理员可用（调用方已做项目访问校验，此处再做 run 归属校验）。
     */
    public RollbackResponse rollback(UUID runId, RollbackRequest body, UUID actorUserId, boolean superAdmin) {
        Located located = locate(runId, actorUserId, superAdmin);
        requireRepository(located);
        String target = body == null || body.target() == null ? "" : body.target().trim();
        String commitHash = body == null ? null : body.commitHash();
        Path repoPath = located.repoPath();
        switch (target) {
            case "working-tree" -> rollbackWorkingTree(repoPath);
            case "commit" -> rollbackToCommit(repoPath, commitHash, false);
            case "hard" -> rollbackToCommit(repoPath, commitHash, true);
            default -> throw bad("AI_MIGRATION_ROLLBACK_TARGET_INVALID");
        }
        // 回滚联动（loop change 2.3）：条目状态重算（回到基线的条目 → NOT_MIGRATED）
        if (located.run().status() != null && "SUCCEEDED".equals(located.run().status())) {
            resetMigrationStatuses(located.run(), repoPath);
        }
        return new RollbackResponse(repo.headHash(repoPath).orElse(""), List.of());
    }

    private void rollbackWorkingTree(Path repoPath) {
        try {
            repo.gitRead(repoPath, "checkout", "--", ".");
            repo.gitRead(repoPath, "clean", "-f", "-d");
        } catch (RuntimeException ex) {
            throw conflict("AI_MIGRATION_ROLLBACK_FAILED");
        }
        marker(repoPath);
    }

    private void rollbackToCommit(Path repoPath, String commitHash, boolean hard) {
        if (commitHash == null || commitHash.isBlank()) {
            throw bad("AI_MIGRATION_ROLLBACK_COMMIT_REQUIRED");
        }
        // 提交必须在仓库历史内（防外部 hash）
        String resolved = resolveRef(repoPath, commitHash);
        try {
            repo.gitRead(repoPath, "reset", hard ? "--hard" : "--soft", resolved);
            if (hard) {
                repo.gitRead(repoPath, "clean", "-f", "-d");
            }
        } catch (RuntimeException ex) {
            throw conflict("AI_MIGRATION_ROLLBACK_FAILED");
        }
        marker(repoPath);
    }

    /**
     * 回滚后重算报告条目迁移状态：HEAD 树内容与基线一致的条目回到 NOT_MIGRATED
     * （migrationRunId 保留，spec 回滚重算场景）。运行无报告输入（旧运行）则跳过。
     */
    private void resetMigrationStatuses(AiMigrationRunView run, Path repoPath) {
        java.util.UUID reportId = reportIdOf(run);
        if (reportId == null) {
            return;
        }
        try {
            String base = repo.firstInitHash(repoPath);
            if (base == null) {
                return;
            }
            // HEAD 与基线的差异文件集合：不变 = 回到基线 → NOT_MIGRATED
            String changed = repo.gitRead(repoPath, "diff", "--name-only", base, "HEAD", "--", "source");
            java.util.Set<String> changedFiles = java.util.Arrays.stream(changed.split("\n"))
                    .filter(line -> !line.isBlank())
                    .map(line -> line.replaceFirst("^source/", "").trim())
                    .collect(java.util.stream.Collectors.toSet());
            // 报告条目按文件聚合重算：该文件的全部条目状态刷新
            java.util.List<Map<String, Object>> items = reportItems(reportId);
            java.util.List<String> resetIds = new java.util.ArrayList<>();
            for (Map<String, Object> item : items) {
                Map<String, Object> original = (Map<String, Object>) item.get("originalSnapshot");
                if (original == null) {
                    continue;
                }
                Map<String, Object> evidence = (Map<String, Object>) original.get("evidence");
                Object scannerId = evidence == null ? null : evidence.get("scannerFindingId");
                String file = Objects.toString(original.get("filePath"), "");
                if (scannerId != null && !changedFiles.contains(file)) {
                    resetIds.add(scannerId.toString());
                }
            }
            if (!resetIds.isEmpty()) {
                statusWriter.resetToBaseline(reportId, resetIds);
            }
        } catch (RuntimeException ex) {
            throw conflict("AI_MIGRATION_ROLLBACK_STATUS_RESET_FAILED");
        }
    }

    private java.util.UUID reportIdOf(AiMigrationRunView run) {
        // reportId 经运行记录（0139 列）读取；此处从 inputSnapshot 不可得，经 repository 反查
        return repository.reportIdOf(run.id()).orElse(null);
    }

    private java.util.List<Map<String, Object>> reportItems(java.util.UUID reportId) {
        return repository.reportItemsFor(reportId);
    }

    private void marker(Path repoPath) {
        try {
            repo.commitRollback(repoPath);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw conflict("AI_MIGRATION_ROLLBACK_MARKER_FAILED");
        }
    }

    private String resolveRef(Path repoPath, String ref) {
        String candidate = ref == null || ref.isBlank() ? "HEAD" : ref.trim();
        try {
            String hash = repo.gitRead(repoPath, "rev-parse", "--verify", "-q", candidate).trim();
            if (hash.isBlank()) {
                throw bad("AI_MIGRATION_REF_NOT_FOUND");
            }
            // 引用必须在本仓库 HEAD 可达历史内（防读任意对象）
            repo.gitRead(repoPath, "merge-base", "--is-ancestor", hash, "HEAD");
            return hash;
        } catch (RuntimeException ex) {
            // merge-base --is-ancestor 退出码表达结论：非零 = 不可达 → 拒绝
            throw bad("AI_MIGRATION_REF_NOT_FOUND");
        }
    }

    private Located locate(UUID runId, UUID actorUserId, boolean superAdmin) {
        AiMigrationRunView run = repository.findRun(runId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AI_MIGRATION_RUN_NOT_FOUND",
                        "迁移运行不存在 / Migration run not found"));
        Objects.requireNonNull(actorUserId, "actorUserId");
        // 与 detail() 同一访问语义：项目成员或超级管理员
        access.requireProjectAccess(run.projectId(), actorUserId, superAdmin);
        return new Located(run, repoPathOf(run));
    }

    private void requireRepository(Located located) {
        Path repoPath = located.repoPath();
        if (!repo.isInitialized(repoPath)) {
            throw new ApiException(HttpStatus.CONFLICT, "AI_MIGRATION_REPO_UNAVAILABLE",
                    "该运行无 git 仓库（旧版本运行）/ Run has no git repository");
        }
    }

    private Path repoPathOf(AiMigrationRunView run) {
        // 仓库定位：workspaceRoot 下 runs/{runId}；workspaceRoot 经属性注入（见配置）
        return Path.of(workspaceRoot, "runs", run.id().toString());
    }

    @jakarta.annotation.Resource
    private cn.rvport.backend.ai.migration.executor.AiMigrationExecutorProperties executorProperties;

    private String workspaceRoot;

    @jakarta.annotation.PostConstruct
    void initWorkspaceRoot() {
        this.workspaceRoot = executorProperties.workspaceRoot();
    }

    /** 测试接缝：直接注入 workspaceRoot（绕过 @Resource 属性装配）。 */
    void initWorkspaceRootForTest(cn.rvport.backend.ai.migration.executor.AiMigrationExecutorProperties props) {
        this.executorProperties = props;
        this.workspaceRoot = props.workspaceRoot();
    }

    private static ApiException bad(String code) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, "迁移历史请求不合法 / Invalid migration history request");
    }

    private static ApiException conflict(String code) {
        return new ApiException(HttpStatus.CONFLICT, code, "回滚执行失败 / Rollback failed");
    }

    static String safeRepoFileName(String relative) {
        return relative == null ? "" : relative.replace('\\', '/');
    }
}
