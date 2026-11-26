package cn.rvport.backend.ai.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.rvport.backend.ai.migration.MigrationRepoService.GitCli;
import cn.rvport.backend.ai.migration.executor.AiMigrationExecutorProperties;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.project.ProjectAccessService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 历史/文件/回滚三端点的服务层行为：访问校验复用、路径安全、ref 可达性、
 * 三原语与回滚 marker。真实 git 分支（无 git 镜像跳过）。
 */
class AiMigrationHistoryServiceTests {

    final AiMigrationRepository repository = mock(AiMigrationRepository.class);
    final ProjectAccessService access = mock(ProjectAccessService.class);
    final MigrationRepoService repo = new MigrationRepoService(new Cli());
    final MigrationStatusWriter statusWriter = mock(MigrationStatusWriter.class);
    final AiMigrationHistoryService service = new AiMigrationHistoryService(repository, repo, access, statusWriter);

    final UUID runId = UUID.randomUUID();
    final UUID project = UUID.randomUUID();
    final UUID actor = UUID.randomUUID();

    @TempDir
    Path workspaceRoot;

    @TempDir
    Path temp;

    private Path runRepo;

    @BeforeEach
    void setup() throws Exception {
        when(repository.findRun(runId)).thenReturn(java.util.Optional.of(runView()));
        runRepo = workspaceRoot.resolve("runs").resolve(runId.toString());
        Files.createDirectories(runRepo.resolve("source/src"));
        Files.writeString(runRepo.resolve("source/src/a.c"), "int a;\n");
        service.initWorkspaceRootForTest(AiMigrationExecutorProperties.forMode("process",
                workspaceRoot.toString(), workspaceRoot.toString()));
    }

    private AiMigrationRunView runView() {
        return new AiMigrationRunView(runId, project, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "SUCCEEDED", Map.of(), 2, 2, 0, 0, UUID.randomUUID(), UUID.randomUUID(), null, null, null);
    }

    private void seedRepo() throws Exception {
        repo.initialize(runRepo);
        repo.commitFirstInit(runRepo);
        Files.writeString(runRepo.resolve("source/src/a.c"), "v2 __riscv;\n");
        repo.commitAgentFinish(runRepo);
    }

    private boolean realGitAvailable() {
        return java.util.Arrays.stream(System.getenv("PATH").split(":"))
                .anyMatch(p -> Files.isRegularFile(Path.of(p, "git")));
    }

    @Test
    void 不存在的运行返回NOT_FOUND() {
        when(repository.findRun(runId)).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> service.history(runId, actor, false))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("AI_MIGRATION_RUN_NOT_FOUND");
    }

    @Test
    void 无仓库的旧运行返回冲突() {
        assertThatThrownBy(() -> service.history(runId, actor, false))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("AI_MIGRATION_REPO_UNAVAILABLE");
    }

    @Test
    void 历史返回解析后的标签与当前位() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(realGitAvailable(), "unit-test 镜像无 git");
        seedRepo();
        var history = service.history(runId, actor, false);
        assertThat(history).hasSize(2);
        var head = history.get(0);
        assertThat(head.current()).isTrue();
        assertThat(head.reason()).isEqualTo("agent-finish");
        assertThat(head.label()).isEqualTo("agent 完成");
        assertThat(history.get(1).reason()).isEqualTo("first-init");
        // 访问校验已执行（同 detail 语义）
        org.mockito.Mockito.verify(access).requireProjectAccess(project, actor, false);
    }

    @Test
    void 仓库外路径与非法ref拒绝() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(realGitAvailable(), "unit-test 镜像无 git");
        seedRepo();
        assertThatThrownBy(() -> service.repoFile(runId, "../outside.txt", null, actor, false))
                .extracting("code").isEqualTo("AI_MIGRATION_FILE_PATH_UNSAFE");
        assertThatThrownBy(() -> service.repoFile(runId, "input/scan_result.json", null, actor, false))
                .extracting("code").isEqualTo("AI_MIGRATION_FILE_PATH_UNSAFE");
        assertThatThrownBy(() -> service.repoFile(runId, "/abs/path.c", null, actor, false))
                .extracting("code").isEqualTo("AI_MIGRATION_FILE_PATH_UNSAFE");
        // 仓库外提交（未知 hash）拒绝
        assertThatThrownBy(() -> service.repoFile(runId, "source/src/a.c",
                "0000000000000000000000000000000000000001", actor, false))
                .extracting("code").isEqualTo("AI_MIGRATION_REF_NOT_FOUND");
    }

    @Test
    void 按提交读文件返回历史内容() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(realGitAvailable(), "unit-test 镜像无 git");
        seedRepo();
        String base = repo.firstInitHash(runRepo);
        // HEAD 读到 v2；基线读到 v1
        assertThat(service.repoFile(runId, "source/src/a.c", null, actor, false)).contains("__riscv");
        assertThat(service.repoFile(runId, "source/src/a.c", base, actor, false)).isEqualTo("int a;\n");
    }

    @Test
    void 回滚三原语与marker() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(realGitAvailable(), "unit-test 镜像无 git");
        seedRepo();
        String base = repo.firstInitHash(runRepo);

        // working-tree：制造未提交改动再丢弃
        Files.writeString(runRepo.resolve("source/src/a.c"), "uncommitted\n");
        var result = service.rollback(runId, new AiMigrationHistoryService.RollbackRequest("working-tree", null),
                actor, false);
        assertThat(result.head()).isNotBlank();
        assertThat(Files.readString(runRepo.resolve("source/src/a.c"))).contains("__riscv");

        // hard：回退到基线
        service.rollback(runId, new AiMigrationHistoryService.RollbackRequest("hard", base), actor, false);
        assertThat(Files.readString(runRepo.resolve("source/src/a.c"))).isEqualTo("int a;\n");

        // 回滚 marker 留痕
        List<String> reasons = repo.history(runRepo, 20).stream()
                .map(e -> e.label().structured() ? e.label().reason() : "?").toList();
        assertThat(reasons.stream().filter("migration-rollback"::equals).count()).isEqualTo(2);

        // commit（soft）不带 hash 拒绝
        assertThatThrownBy(() -> service.rollback(runId,
                new AiMigrationHistoryService.RollbackRequest("commit", null), actor, false))
                .extracting("code").isEqualTo("AI_MIGRATION_ROLLBACK_COMMIT_REQUIRED");
        // 非法 target 拒绝
        assertThatThrownBy(() -> service.rollback(runId,
                new AiMigrationHistoryService.RollbackRequest("destroy", null), actor, false))
                .extracting("code").isEqualTo("AI_MIGRATION_ROLLBACK_TARGET_INVALID");
    }

    @Test
    void 回滚后历史去重保留结构化条目() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(realGitAvailable(), "unit-test 镜像无 git");
        seedRepo();
        String base = repo.firstInitHash(runRepo);
        service.rollback(runId, new AiMigrationHistoryService.RollbackRequest("hard", base), actor, false);
        // first-init 提交在 reflog 有两条（原始 commit + reset moving to）→ 只列一次且标签正确
        var history = service.history(runId, actor, false);
        var firsts = history.stream().filter(e -> "first-init".equals(e.reason())).toList();
        assertThat(firsts).hasSize(1);
        assertThat(firsts.get(0).structured()).isTrue();
        // 回滚 marker 在最前
        assertThat(history.get(0).reason()).isEqualTo("migration-rollback");
    }

    /** 与 MigrationRepoServiceTests.Cli 同实现（真实 git）。 */
    static final class Cli implements GitCli {
        @Override
        public void run(Path workingDir, Duration timeout, String... args)
                throws IOException, InterruptedException {
            execute(workingDir, timeout, args);
        }

        @Override
        public String read(Path workingDir, String... args) {
            try {
                return execute(workingDir, Duration.ofMinutes(1), args);
            } catch (IOException | InterruptedException ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("GIT_ERROR: " + ex.getMessage(), ex);
            }
        }

        private String execute(Path workingDir, Duration timeout, String... args)
                throws IOException, InterruptedException {
            List<String> command = new ArrayList<>(List.of("git"));
            if (!List.of(args).contains("--no-pager")) {
                command.add("--no-pager");
            }
            command.addAll(List.of(args));
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workingDir.toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            java.io.ByteArrayOutputStream buffered = new java.io.ByteArrayOutputStream();
            Thread drain = new Thread(() -> {
                try (java.io.InputStream in = process.getInputStream()) {
                    buffered.write(in.readAllBytes());
                } catch (IOException ignored) {
                    // 强制结束路径
                }
            }, "history-test-git-drain");
            drain.setDaemon(true);
            drain.start();
            if (!process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                drain.join(1000);
                throw new IllegalStateException("GIT_TIMEOUT: " + command);
            }
            drain.join(1000);
            String output = buffered.toString(java.nio.charset.StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                String clipped = output.length() > 2000 ? output.substring(0, 2000) : output;
                throw new IllegalStateException("GIT_FAILED(" + process.exitValue() + "): " + clipped);
            }
            return output;
        }
    }

    @Test
    void 仓库文件读取的参数与安全分支() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(realGitAvailable(), "unit-test 镜像无 git");
        seedRepo();
        // 空/缺失路径必填
        assertThatThrownBy(() -> service.repoFile(runId, " ", null, actor, false))
                .extracting("code").isEqualTo("AI_MIGRATION_FILE_PATH_REQUIRED");
        assertThatThrownBy(() -> service.repoFile(runId, null, null, actor, false))
                .extracting("code").isEqualTo("AI_MIGRATION_FILE_PATH_REQUIRED");
        // Windows 反斜杠归一后正常读取
        assertThat(service.repoFile(runId, "source\\src\\a.c", null, actor, false)).contains("__riscv");
        // 空路径段拒绝
        assertThatThrownBy(() -> service.repoFile(runId, "source//a.c", null, actor, false))
                .extracting("code").isEqualTo("AI_MIGRATION_FILE_PATH_UNSAFE");
        // 版本中不存在的文件 → NOT_FOUND
        assertThatThrownBy(() -> service.repoFile(runId, "source/src/missing.c", null, actor, false))
                .extracting("code").isEqualTo("AI_MIGRATION_FILE_NOT_FOUND");
        // 请求体缺失 → 非法 target
        assertThatThrownBy(() -> service.rollback(runId, null, actor, false))
                .extracting("code").isEqualTo("AI_MIGRATION_ROLLBACK_TARGET_INVALID");
    }

    @Test
    void 回滚联动重算未变更文件的条目状态() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(realGitAvailable(), "unit-test 镜像无 git");
        seedRepo();
        UUID reportId = UUID.randomUUID();
        when(repository.reportIdOf(runId)).thenReturn(java.util.Optional.of(reportId));
        when(repository.reportItemsFor(reportId)).thenReturn(List.of(
                Map.of("originalSnapshot", Map.of("filePath", "src/a.c", "evidence", Map.of("scannerFindingId", "f1"))),
                Map.of("originalSnapshot", Map.of("filePath", "src/b.c", "evidence", Map.of("scannerFindingId", "f2"))),
                Map.of("originalSnapshot", Map.of("filePath", "src/b.c")),                     // 无 scannerId → 跳过
                Map.of("originalSnapshot", Map.of("evidence", Map.of("scannerFindingId", "f3"))), // 无文件 → 按 "" 处理
                Map.of()));                                                                     // 无 originalSnapshot → 跳过
        // a.c 在基线→HEAD 间已变更（seedRepo 改写），不动；其余重算
        service.rollback(runId, new AiMigrationHistoryService.RollbackRequest("commit", repo.headHash(runRepo).orElseThrow()), actor, false);
        org.mockito.Mockito.verify(statusWriter).resetToBaseline(reportId, List.of("f2", "f3"));
        // 运行无报告输入：不触发重算
        UUID runB = UUID.randomUUID();
        when(repository.findRun(runB)).thenReturn(java.util.Optional.of(new AiMigrationRunView(runB, project,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "SUCCEEDED", Map.of(), 2, 2, 0, 0,
                UUID.randomUUID(), UUID.randomUUID(), null, null, null)));
        when(repository.reportIdOf(runB)).thenReturn(java.util.Optional.empty());
        // runB 需要自己的仓库（定位按运行 id 解析 runs/{runId}/）
        Path runBRepo = workspaceRoot.resolve("runs").resolve(runB.toString());
        Files.createDirectories(runBRepo.resolve("source"));
        Files.writeString(runBRepo.resolve("source/a.c"), "int a;\n");
        repo.initialize(runBRepo);
        repo.commitFirstInit(runBRepo);
        String base = repo.firstInitHash(runBRepo);
        service.rollback(runB, new AiMigrationHistoryService.RollbackRequest("hard", base), actor, false);
        // 无报告绑定 → 不新增重算调用（仍只有第一次的 1 次）
        org.mockito.Mockito.verify(statusWriter, org.mockito.Mockito.times(1)).resetToBaseline(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        // 运行非 SUCCEEDED：不触发重算
        UUID runC = UUID.randomUUID();
        var failed = new AiMigrationRunView(runC, project, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "FAILED", Map.of(), 2, 2, 0, 0, UUID.randomUUID(), UUID.randomUUID(), null, null, null);
        when(repository.findRun(runC)).thenReturn(java.util.Optional.of(failed));
        when(repository.reportIdOf(runC)).thenReturn(java.util.Optional.of(reportId));
        Path runCRepo = workspaceRoot.resolve("runs").resolve(runC.toString());
        Files.createDirectories(runCRepo.resolve("source"));
        Files.writeString(runCRepo.resolve("source/a.c"), "int a;\n");
        repo.initialize(runCRepo);
        repo.commitFirstInit(runCRepo);
        String baseC = repo.firstInitHash(runCRepo);
        service.rollback(runC, new AiMigrationHistoryService.RollbackRequest("hard", baseC), actor, false);
        // 非 SUCCEEDED 运行 → 仍只有第一次的 1 次
        org.mockito.Mockito.verify(statusWriter, org.mockito.Mockito.times(1)).resetToBaseline(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 回滚参数与基线读取的剩余边界() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(realGitAvailable(), "unit-test 镜像无 git");
        seedRepo();
        // baseline/ 前缀放行（读取不存在文件 → NOT_FOUND 而非 UNSAFE）
        assertThatThrownBy(() -> service.repoFile(runId, "baseline/src/a.c", null, actor, false))
                .extracting("code").isEqualTo("AI_MIGRATION_FILE_NOT_FOUND");
        // 空白 ref → HEAD
        assertThat(service.repoFile(runId, "source/src/a.c", "  ", actor, false)).contains("__riscv");
        // target null → 非法 target；空白 commitHash → 必填
        assertThatThrownBy(() -> service.rollback(runId, new AiMigrationHistoryService.RollbackRequest(null, null), actor, false))
                .extracting("code").isEqualTo("AI_MIGRATION_ROLLBACK_TARGET_INVALID");
        assertThatThrownBy(() -> service.rollback(runId, new AiMigrationHistoryService.RollbackRequest("commit", "  "), actor, false))
                .extracting("code").isEqualTo("AI_MIGRATION_ROLLBACK_COMMIT_REQUIRED");
        // 全部条目文件都已变更 → 重算集合为空 → 不调用 statusWriter
        UUID reportId = UUID.randomUUID();
        when(repository.reportIdOf(runId)).thenReturn(java.util.Optional.of(reportId));
        when(repository.reportItemsFor(reportId)).thenReturn(List.of(
                Map.of("originalSnapshot", Map.of("filePath", "src/a.c", "evidence", Map.of("scannerFindingId", "f9")))));
        service.rollback(runId, new AiMigrationHistoryService.RollbackRequest("commit", repo.headHash(runRepo).orElseThrow()), actor, false);
        org.mockito.Mockito.verify(statusWriter, org.mockito.Mockito.never()).resetToBaseline(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 仓库文件名归一静态边界() {
        assertThat(AiMigrationHistoryService.safeRepoFileName(null)).isEmpty();
        assertThat(AiMigrationHistoryService.safeRepoFileName("src\\a.c")).isEqualTo("src/a.c");
    }

    @Test
    void 空diff回滚重算全部条目() throws Exception {
        // HEAD 树回到基线（空 diff）→ 该报告全部条目重算
        UUID runD = UUID.randomUUID();
        when(repository.findRun(runD)).thenReturn(java.util.Optional.of(new AiMigrationRunView(runD, project,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "SUCCEEDED", Map.of(), 2, 2, 0, 0,
                UUID.randomUUID(), UUID.randomUUID(), null, null, null)));
        UUID reportIdD = UUID.randomUUID();
        when(repository.reportIdOf(runD)).thenReturn(java.util.Optional.of(reportIdD));
        when(repository.reportItemsFor(reportIdD)).thenReturn(List.of(
                Map.of("originalSnapshot", Map.of("filePath", "src/a.c", "evidence", Map.of("scannerFindingId", "f5")))));
        Path runDRepo = workspaceRoot.resolve("runs").resolve(runD.toString());
        Files.createDirectories(runDRepo.resolve("source"));
        Files.writeString(runDRepo.resolve("source/a.c"), "int a;\n");
        repo.initialize(runDRepo);
        repo.commitFirstInit(runDRepo);
        String baseD = repo.firstInitHash(runDRepo);
        service.rollback(runD, new AiMigrationHistoryService.RollbackRequest("hard", baseD), actor, false);
        org.mockito.Mockito.verify(statusWriter).resetToBaseline(reportIdD, List.of("f5"));
    }
}
