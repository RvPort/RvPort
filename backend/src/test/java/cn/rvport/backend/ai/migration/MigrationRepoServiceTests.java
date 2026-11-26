package cn.rvport.backend.ai.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * MigrationRepoService 的真实 git 分支：init/白名单/marker/reflog/基线检测。
 * unit-test 镜像无 git 时以 PATH 假 git 探针覆盖解析类分支（同 SkillRepositoryTests 手法）。
 */
class MigrationRepoServiceTests {

    private final Path temp = Files.createTempDirectory("migration-repo-tests");

    MigrationRepoServiceTests() throws IOException {
    }

    private boolean realGitAvailable() {
        return java.util.Arrays.stream(System.getenv("PATH").split(":"))
                .anyMatch(p -> Files.isRegularFile(Path.of(p, "git")));
    }

    private MigrationRepoService service() {
        return new MigrationRepoService(new Cli());
    }

    @Test
    void 提交message正则与白名单解析() {
        var label = MigrationRepoService.parseLabel(
                "Save type:ai stage:end src:rvport-agent reason:agent-finish");
        assertThat(label.structured()).isTrue();
        assertThat(label.reason()).isEqualTo("agent-finish");
        assertThat(label.display()).isEqualTo("agent 完成");

        var legacy = MigrationRepoService.parseLabel("Initial commit");
        assertThat(legacy.structured()).isFalse();
        assertThat(MigrationRepoService.parseLabel(null).structured()).isFalse();

        // 非白名单 src 的结构化消息：正则捕获但 src 不在白名单集合——解析层仍结构化，提交层会拒绝
        var foreign = MigrationRepoService.parseLabel("Save type:ai stage:end src:other-src reason:agent-finish");
        assertThat(foreign.structured()).isTrue();
        assertThat(foreign.src()).isEqualTo("other-src");
    }

    @Test
    void 白名单外组合拒绝提交() {
        var svc = service();
        Path repo = temp.resolve("rejected");
        assertThatThrownBy(() -> svc.commit(repo, "robot", "init", "rvport-init", "first-init", true))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("MIGRATION_REPO_COMMIT_REJECTED")
                .hasMessageContaining("type 'robot'");
        assertThatThrownBy(() -> svc.commit(repo, "manual", "init", "rvport-init", "not-a-reason", true))
                .hasMessageContaining("reason 'not-a-reason'");
        // migration-rollback 仅允许 rvport-boundary src 专用通道
        assertThatThrownBy(() -> svc.commit(repo, "marker", "marker", "rvport-agent", "migration-rollback", true))
                .hasMessageContaining("reason 'migration-rollback'");
    }

    @Test
    void 未初始化目录isInitialized为假且history为空() {
        var svc = service();
        Path repo = temp.resolve("not-yet");
        assertThat(svc.isInitialized(repo)).isFalse();
        assertThat(svc.history(repo, 10)).isEmpty();
        assertThat(svc.headHash(repo)).isEmpty();
    }

    // ---- 以下为真实 git 分支（无 git 的镜像自动跳过） ----

    @Test
    void init与基线提交及幂等() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(realGitAvailable(), "unit-test 镜像无 git");
        var svc = service();
        Path repo = temp.resolve("repo-a");
        Files.createDirectories(repo.resolve("source"));
        Files.writeString(repo.resolve("source/a.c"), "int a;\n");

        svc.initialize(repo);
        String base = svc.commitFirstInit(repo);

        assertThat(svc.isInitialized(repo)).isTrue();
        assertThat(svc.headHash(repo)).contains(base);
        assertThat(svc.commitFirstInit(repo)).isEqualTo(base); // 幂等路径（同 HEAD）

        // 身份配置生效（提交者固定为框架标识）
        String author = new Cli().read(repo, "log", "-1", "--format=%an <%ae>");
        assertThat(author.trim()).isEqualTo("rvport-worker <rvport-worker@rvport.local>");
    }

    @Test
    void marker强制留痕与零改动提交() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(realGitAvailable(), "unit-test 镜像无 git");
        var svc = service();
        Path repo = temp.resolve("repo-b");
        Files.createDirectories(repo.resolve("source"));
        Files.writeString(repo.resolve("source/a.c"), "int a;\n");
        svc.initialize(repo);
        svc.commitFirstInit(repo);

        // 零改动：agent-finish 非强制路径跳过（返回当前 HEAD）
        String head = svc.headHash(repo).orElseThrow();
        assertThat(svc.commitAgentFinish(repo)).isEqualTo(head);

        // 零改动：stage-end marker 强制留痕（--allow-empty）
        String marker = svc.commitStageEnd(repo, "stage-end-error");
        assertThat(marker).isNotEqualTo(head);
        String messages = new Cli().read(repo, "log", "--format=%s");
        assertThat(messages).contains("Save type:marker stage:marker src:rvport-boundary reason:stage-end-error");
    }

    @Test
    void 带改动的agentFinish产生实体提交() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(realGitAvailable(), "unit-test 镜像无 git");
        var svc = service();
        Path repo = temp.resolve("repo-c");
        Files.createDirectories(repo.resolve("source"));
        Files.writeString(repo.resolve("source/a.c"), "int a;\n");
        svc.initialize(repo);
        svc.commitFirstInit(repo);
        Files.writeString(repo.resolve("source/a.c"), "int a; __riscv_vadd;\n");

        String commit = svc.commitAgentFinish(repo);
        String show = new Cli().read(repo, "show", "--stat", "--format=%s", commit);
        assertThat(show).contains("agent-finish").contains("a.c");
    }

    @Test
    void reflog历史倒序且回滚后仍可见() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(realGitAvailable(), "unit-test 镜像无 git");
        var svc = service();
        Path repo = temp.resolve("repo-d");
        Files.createDirectories(repo.resolve("source"));
        Files.writeString(repo.resolve("source/a.c"), "int a;\n");
        svc.initialize(repo);
        String base = svc.commitFirstInit(repo);
        Files.writeString(repo.resolve("source/a.c"), "v2\n");
        String second = svc.commitAgentFinish(repo);

        List<MigrationRepoService.HistoryEntry> before = svc.history(repo, 10);
        assertThat(before).extracting(MigrationRepoService.HistoryEntry::hash)
                .containsExactly(second, base);
        assertThat(before.get(0).current()).isTrue();
        assertThat(before.get(0).label().display()).isEqualTo("agent 完成");
        assertThat(before.get(1).label().display()).isEqualTo("基线初始化");

        // hard reset 到基线后：second 仍可从 reflog 列出（isCurrent 转移）
        new Cli().run(repo, Duration.ofMinutes(1), "reset", "--hard", base);
        List<MigrationRepoService.HistoryEntry> after = svc.history(repo, 10);
        assertThat(after).extracting(MigrationRepoService.HistoryEntry::hash).contains(second);
        assertThat(after.stream().filter(MigrationRepoService.HistoryEntry::current).count()).isEqualTo(1);
        assertThat(after.stream().filter(MigrationRepoService.HistoryEntry::current)
                .findFirst().orElseThrow().hash()).isEqualTo(base);
    }

    @Test
    void 基线改写检测_祖先与失配() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(realGitAvailable(), "unit-test 镜像无 git");
        var svc = service();
        Path repo = temp.resolve("repo-e");
        Files.createDirectories(repo.resolve("source"));
        Files.writeString(repo.resolve("source/a.c"), "int a;\n");
        svc.initialize(repo);
        String base = svc.commitFirstInit(repo);
        Files.writeString(repo.resolve("source/a.c"), "v2\n");
        svc.commitAgentFinish(repo);

        // 正常：基线是 HEAD 祖先
        svc.verifyBaselineUntouched(repo, base);

        // reset 到基线后重写历史（amend 制造新基线）→ 旧基线对象不可达 → 检测失败
        new Cli().run(repo, Duration.ofMinutes(1), "reset", "--hard", base);
        Files.writeString(repo.resolve("source/a.c"), "rewritten\n");
        new Cli().run(repo, Duration.ofMinutes(1), "commit", "--amend", "-m", "rewritten base", "--allow-empty");
        String rewrittenBase = svc.headHash(repo).orElseThrow();
        assertThatThrownBy(() -> svc.verifyBaselineUntouched(repo, base))
                .isInstanceOf(IOException.class)
                .hasMessage("AI_MIGRATION_BASELINE_MODIFIED");
        // 新基线自身合法
        svc.verifyBaselineUntouched(repo, rewrittenBase);
        // 空 hash 拒绝
        assertThatThrownBy(() -> svc.verifyBaselineUntouched(repo, ""))
                .hasMessage("AI_MIGRATION_BASELINE_MODIFIED");
    }

    @Test
    void 清理仅删临时物保留仓库() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(realGitAvailable(), "unit-test 镜像无 git");
        var svc = service();
        Path repo = temp.resolve("repo-f");
        Files.createDirectories(repo.resolve("source"));
        Files.writeString(repo.resolve("source/a.c"), "int a;\n");
        svc.initialize(repo);
        svc.commitFirstInit(repo);
        Files.createDirectories(repo.resolve(".migrate_verify"));
        Files.writeString(repo.resolve(".migrate_verify/main.c"), "int main(){}\n");
        Files.writeString(repo.resolve(".prompt.txt"), "prompt");
        Files.createDirectories(repo.resolve("input"));

        svc.cleanup(repo);

        assertThat(Files.exists(repo.resolve(".migrate_verify"))).isFalse();
        assertThat(Files.exists(repo.resolve(".prompt.txt"))).isFalse();
        assertThat(Files.isDirectory(repo.resolve(".git"))).isTrue();
        assertThat(Files.isDirectory(repo.resolve("source"))).isTrue();
        assertThat(Files.isDirectory(repo.resolve("input"))).isTrue();
    }

    /** 真实 CLI 包装：与生产 ProcessGitCli 同语义（no-pager 注入、超时、退出码异常）。 */
    static final class Cli implements MigrationRepoService.GitCli {
        @Override
        public void run(Path workingDir, Duration timeout, String... args) throws IOException, InterruptedException {
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

        private String execute(Path workingDir, Duration timeout, String... args) throws IOException, InterruptedException {
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
                    // 强制结束时流关闭，正常路径
                }
            }, "repo-test-git-drain");
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
}
