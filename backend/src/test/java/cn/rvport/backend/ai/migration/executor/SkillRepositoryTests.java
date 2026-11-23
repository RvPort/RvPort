package cn.rvport.backend.ai.migration.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillRepositoryTests {

    @TempDir
    Path temp;

    /** 可编程 git stub：按 (命令关键字) 返回预置输出。 */
    static class StubGit implements SkillRepository.GitCli {
        final List<String> runLog = new java.util.ArrayList<>();
        final Map<String, String> readResults = new LinkedHashMap<>();
        final Deque<Map<String, String>> readPhases = new ArrayDeque<>();
        RuntimeException failure;
        /** 仓库当前记录的远端地址；默认与各用例使用的地址一致，使已有用例不触发换源。 */
        String recordedRemote = "https://example/skills.git";

        @Override
        public void run(Path dir, Duration timeout, String... args) {
            if (failure != null) {
                throw failure;
            }
            runLog.add(String.join(" ", args));
            if (args.length >= 4 && args[0].equals("remote") && args[1].equals("set-url")) {
                recordedRemote = args[3];
            }
        }

        @Override
        public String read(Path dir, String... args) {
            if (failure != null) {
                throw failure;
            }
            String key = String.join(" ", args);
            if (key.equals("remote get-url origin")) {
                return recordedRemote + "\n";
            }
            Map<String, String> phase = readPhases.isEmpty() ? readResults : readPhases.peek();
            return phase.getOrDefault(key, "");
        }

        StubGit phase(Map<String, String> results) {
            readPhases.add(results);
            return this;
        }
    }

    private Path repoDir(boolean initialized) throws IOException {
        Path root = temp.resolve("ai-skills");
        if (initialized) {
            Files.createDirectories(root.resolve(".git"));
            Files.writeString(root.resolve(".git").resolve("HEAD"), "ref: refs/heads/main\n");
            Files.createDirectories(root.resolve("riscv-migrate"));
            Files.writeString(root.resolve("riscv-migrate").resolve("SKILL.md"), "# skill\n");
        }
        return root;
    }

    private SkillRepository repository(Path root, StubGit git) {
        return new SkillRepository(new SkillRepositoryProperties(root, "https://example/skills.git", "main", null), git);
    }

    @Test
    void 空目录触发克隆且幂等() throws IOException {
        Path root = temp.resolve("ai-skills");
        StubGit git = new StubGit();

        repository(root, git).ensureCloned();
        assertThat(git.runLog).singleElement()
                .satisfies(cmd -> assertThat(cmd).startsWith("clone"));
        // 第二次调用不再克隆
        git.runLog.clear();
        repository(repoDir(true), git).ensureCloned();
        assertThat(git.runLog).isEmpty();
    }

    @Test
    void 克隆失败抛出原始异常() {
        StubGit git = new StubGit();
        git.failure = new IllegalStateException("GIT_FAILED(128): network unreachable");
        // git 进程失败（非 IO）直接透传，保留退出码上下文
        assertThatThrownBy(() -> repository(temp.resolve("s2"), git).ensureCloned())
                .hasMessageContaining("GIT_FAILED(128)");
    }

    @Test
    void 配置代理时仅联网命令携带代理且原样透传给git() throws IOException {
        StubGit git = new StubGit();
        git.phase(Map.of(
                "rev-parse HEAD", "a",
                "rev-parse refs/remotes/origin/main", "b",
                "rev-list --count HEAD..refs/remotes/origin/main", "1",
                "--no-pager diff --stat HEAD..refs/remotes/origin/main", " f | 1 +\n"));
        Path root = temp.resolve("proxied");
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git").resolve("HEAD"), "ref: refs/heads/main\n");
        var repository = new SkillRepository(
                new SkillRepositoryProperties(root, "https://example/skills.git", "main", "http://proxy:8080"), git);

        repository.status();
        repository.update();

        // 联网命令（fetch）：共两次，每次都必须带代理且值与配置一致
        List<String> fetches = git.runLog.stream().filter(c -> c.contains(" fetch ")).toList();
        assertThat(fetches).hasSize(2);
        assertThat(fetches).allSatisfy(c -> assertThat(c)
                .isEqualTo("-c http.proxy=http://proxy:8080 fetch --force origin main:refs/remotes/origin/main"));
        // 本地命令（版本比较、对齐）：不得带代理
        List<String> localCalls = git.runLog.stream().filter(c -> !c.contains(" fetch ")).toList();
        assertThat(localCalls).isNotEmpty();
        assertThat(localCalls).noneMatch(c -> c.contains("proxy"));
        assertThat(localCalls).anySatisfy(c -> assertThat(c).isEqualTo("reset --hard refs/remotes/origin/main"));
    }

    @Test
    void 仓库记录的远端与配置不一致时先换源再拉取() throws IOException {
        StubGit git = new StubGit();
        git.recordedRemote = "file:///opt/old-remote";
        git.phase(Map.of(
                "rev-parse HEAD", "a",
                "rev-parse refs/remotes/origin/main", "b",
                "rev-list --count HEAD..refs/remotes/origin/main", "1",
                "--no-pager diff --stat HEAD..refs/remotes/origin/main", " f | 1 +\n"));
        Path root = temp.resolve("realigned");
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git").resolve("HEAD"), "ref: refs/heads/main\n");
        var repository = new SkillRepository(
                new SkillRepositoryProperties(root, "https://example/skills.git", "main", null), git);

        repository.status();

        List<String> calls = git.runLog;
        int setUrl = calls.indexOf("remote set-url origin https://example/skills.git");
        int fetch = calls.indexOf("fetch --force origin main:refs/remotes/origin/main");
        assertThat(setUrl).isGreaterThanOrEqualTo(0);
        assertThat(fetch).isGreaterThan(setUrl);
        assertThat(git.recordedRemote).isEqualTo("https://example/skills.git");
    }

    @Test
    void 远端已与配置一致时不重复换源() throws IOException {
        StubGit git = new StubGit();
        git.phase(Map.of(
                "rev-parse HEAD", "a",
                "rev-parse refs/remotes/origin/main", "a",
                "rev-list --count HEAD..refs/remotes/origin/main", "0"));
        Path root = temp.resolve("aligned");
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git").resolve("HEAD"), "ref: refs/heads/main\n");
        var repository = new SkillRepository(
                new SkillRepositoryProperties(root, "https://example/skills.git", "main", null), git);

        var status = repository.status();

        assertThat(status.commitsBehind()).isZero();
        assertThat(git.runLog).noneMatch(c -> c.startsWith("remote set-url"));
    }

    @Test
    void 未配置代理时联网命令不带代理() throws IOException {
        StubGit git = new StubGit();
        var repository = new SkillRepository(
                new SkillRepositoryProperties(temp.resolve("plain"), "https://example/skills.git", "main", "  "), git);

        repository.ensureCloned();

        assertThat(git.runLog).hasSize(1);
        assertThat(git.runLog.get(0)).isEqualTo("clone --branch main https://example/skills.git plain");
    }

    @Test
    void 状态查询报告远端领先与差异摘要() throws IOException {
        StubGit git = new StubGit();
        git.phase(Map.of(
                "rev-parse HEAD", "aaa111",
                "rev-parse refs/remotes/origin/main", "bbb222",
                "rev-list --count HEAD..refs/remotes/origin/main", "3",
                "--no-pager diff --stat HEAD..refs/remotes/origin/main", " SKILL.md | 30 +++--\n refs/x.md | 12 +-\n"));
        var status = repository(repoDir(true), git).status();

        assertThat(status.currentRevision()).isEqualTo("aaa111");
        assertThat(status.remoteRevision()).isEqualTo("bbb222");
        assertThat(status.commitsBehind()).isEqualTo(3);
        assertThat(status.diffStatSummary()).contains("SKILL.md");
        assertThat(status.skills()).containsExactly("riscv-migrate");
        assertThat(git.runLog).anySatisfy(cmd -> assertThat(cmd).startsWith("fetch"));
    }

    @Test
    void 更新前进到远端并统计文件数() throws IOException {
        StubGit git = new StubGit();
        git.phase(Map.of(
                "rev-parse HEAD", "old000",
                "rev-parse refs/remotes/origin/main", "new999",
                "rev-list --count HEAD..refs/remotes/origin/main", "2",
                "--no-pager diff --stat HEAD..refs/remotes/origin/main", " a.md | 1 +\n b.md | 2 -\n",
                "diff --name-only HEAD..refs/remotes/origin/main", "a.md\nb.md\n"));
        var result = repository(repoDir(true), git).update();

        assertThat(result.beforeRevision()).isEqualTo("old000");
        assertThat(result.afterRevision()).isEqualTo("new999");
        assertThat(result.commitsChanged()).isEqualTo(2);
        assertThat(result.filesChanged()).isEqualTo(2);
        assertThat(result.diffStatLines()).hasSize(2);
        assertThat(git.runLog).anySatisfy(cmd -> assertThat(cmd).startsWith("reset").contains("--hard"));
    }

    @Test
    void 技能仓库配置默认值与显式值() {
        var defaults = new SkillRepositoryProperties(null, null, " ", null);
        assertThat(defaults.skillsRoot()).isEqualTo(java.nio.file.Path.of("/var/lib/rvport/ai-skills"));
        assertThat(defaults.remoteUrl()).contains("rvport-skills");
        assertThat(defaults.branch()).isEqualTo("master");

        var explicit = new SkillRepositoryProperties(java.nio.file.Path.of("/x"), "http://r", "dev", "http://p:1");
        assertThat(explicit.skillsRoot()).isEqualTo(java.nio.file.Path.of("/x"));
        assertThat(explicit.remoteUrl()).isEqualTo("http://r");
        assertThat(explicit.branch()).isEqualTo("dev");
        assertThat(explicit.proxy()).isEqualTo("http://p:1");
    }

    @Test
    void 进程git客户端的超时与失败路径() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.util.Arrays.stream(System.getenv("PATH").split(":"))
                        .anyMatch(p -> java.nio.file.Files.isRegularFile(java.nio.file.Path.of(p, "git"))),
                "unit-test 镜像无 git");
        // 先造一个仓库（真实链路测试同款），进程客户端在仓库内验证
        Path remote = temp.resolve("cli-remote.git");
        Path work = temp.resolve("cli-seed");
        Files.createDirectories(work);
        runGit(work, "init", "-b", "main");
        Files.writeString(work.resolve("x.txt"), "x");
        runGit(work, "add", "-A");
        runGit(work, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "init");
        runGit(work, "clone", "--bare", ".", remote.toString());
        runGit(work, "remote", "add", "origin", remote.toString());
        var cli = new SkillRepository.ProcessGitCli();
        String out = cli.read(work, "status", "--porcelain");
        assertThat(out).isNotNull();
        // 失败：非法子命令 → GIT_FAILED（短输出不裁剪）
        assertThatThrownBy(() -> cli.read(work, "no-such-subcommand"))
                .hasMessageContaining("GIT_FAILED");
        // 超时：1ms 预算下任何真实 git 调用都超时
        assertThatThrownBy(() -> cli.run(work, java.time.Duration.ofMillis(0), "status"))
                .hasMessageContaining("GIT_TIMEOUT");
    }

    @Test
    void 差异摘要超长裁剪() throws IOException {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            big.append(" file").append(i).append(" | 2 +-\n");
        }
        StubGit git = new StubGit();
        git.phase(Map.of(
                "rev-parse HEAD", "a",
                "rev-parse refs/remotes/origin/main", "b",
                "rev-list --count HEAD..refs/remotes/origin/main", "5",
                "--no-pager diff --stat HEAD..refs/remotes/origin/main", big.toString()));
        var status = repository(repoDir(true), git).status();
        assertThat(status.diffStatSummary()).hasSizeLessThanOrEqualTo(2003).endsWith("...");
    }

    @Test
    void 更新统计行解析与空diff防御() throws IOException {
        StubGit git = new StubGit();
        git.phase(Map.of(
                "rev-parse HEAD", "a",
                "rev-parse refs/remotes/origin/main", "b",
                "rev-list --count HEAD..refs/remotes/origin/main", "7",
                "--no-pager diff --stat HEAD..refs/remotes/origin/main", "",
                "diff --name-only HEAD..refs/remotes/origin/main", "\n\n"));
        var result = repository(repoDir(true), git).update();
        assertThat(result.commitsChanged()).isEqualTo(7);
        assertThat(result.filesChanged()).isZero();
        assertThat(result.diffStatLines()).isEmpty();
    }

    @Test
    void 目录不存在时技能列表为空() {
        assertThat(repository(temp.resolve("nope"), new StubGit()).listSkills()).isEmpty();
    }

    @Test
    void 差异摘要与统计行为null输入防御() throws IOException {
        // read 返回 null（stub 未命中场景下 phase 返回 null）
        StubGit git = new StubGit() {
            @Override
            public String read(Path dir, String... args) {
                String key = String.join(" ", args);
                if (key.startsWith("rev-list")) {
                    return "1"; // behind=1 触发 diff --stat 读取
                }
                if (key.startsWith("rev-parse") || key.contains("diff --stat") || key.contains("diff --name-only")) {
                    return null; // null 输入路径
                }
                return super.read(dir, args);
            }
        };
        var status = repository(repoDir(true), git).status();
        assertThat(status.diffStatSummary()).isEmpty();
        assertThat(status.commitsBehind()).isEqualTo(1);
    }

    @Test
    void 零落后时差异摘要为空字符串() throws IOException {
        StubGit git = new StubGit();
        git.phase(Map.of(
                "rev-parse HEAD", "a",
                "rev-parse refs/remotes/origin/main", "a",
                "rev-list --count HEAD..refs/remotes/origin/main", "0"));
        var status = repository(repoDir(true), git).status();
        assertThat(status.diffStatSummary()).isEmpty();
    }

    @Test
    void 更新统计行超50行截断() throws IOException {
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            many.append(" f").append(i).append(" | 1 +\n");
        }
        StubGit git = new StubGit();
        git.phase(Map.of(
                "rev-parse HEAD", "a",
                "rev-parse refs/remotes/origin/main", "b",
                "rev-list --count HEAD..refs/remotes/origin/main", "9",
                "--no-pager diff --stat HEAD..refs/remotes/origin/main", many.toString(),
                "diff --name-only HEAD..refs/remotes/origin/main", "x.md\n"));
        var result = repository(repoDir(true), git).update();
        assertThat(result.diffStatLines()).hasSize(50);
    }

    @Test
    void 属性null与空白组合全兜底() {
        var allNull = new SkillRepositoryProperties(null, null, null, null);
        assertThat(allNull.remoteUrl()).isEqualTo("https://github.com/zte-riscv/rvport-skills.git");
        assertThat(allNull.branch()).isEqualTo("master");
        assertThat(allNull.proxy()).isEmpty();
        var blankUrl = new SkillRepositoryProperties(null, "   ", null, "  ");
        assertThat(blankUrl.remoteUrl()).contains("rvport-skills");
        assertThat(blankUrl.proxy()).isEmpty();
    }

    @Test
    void 未初始化目录状态查询先克隆() throws IOException {
        StubGit git = new StubGit();
        git.phase(Map.of(
                "rev-parse HEAD", "aaa",
                "rev-parse refs/remotes/origin/main", "aaa",
                "rev-list --count HEAD..refs/remotes/origin/main", "0"));
        // 空目录（无 .git）→ status 触发 ensureCloned 的 clone 分支
        var status = repository(temp.resolve("fresh-skills"), git).status();
        assertThat(git.runLog.stream().anyMatch(c -> c.startsWith("clone"))).isTrue();
        assertThat(status.commitsBehind()).isZero();
    }

    @Test
    void 克隆的中断路径与父目录已存在() throws IOException {
        StubGit git = new StubGit() {
            @Override
            public void run(Path dir, Duration timeout, String... args) {
                if (String.join(" ", args).startsWith("clone")) {
                    throw new RuntimeException(new InterruptedException("stop"));
                }
                super.run(dir, timeout, args);
            }
        };
        // 父目录已存在（temp 本身）；clone 被 mock 为中断（RuntimeException 包装）时直接透传
        assertThatThrownBy(() -> repository(temp.resolve("ai-skills"), git).ensureCloned())
                .hasMessageContaining("InterruptedException");
    }

    @Test
    void read时传入noPager参数不重复注入() throws IOException {
        // ProcessGitCli.execute 对含 --no-pager 的 args 不二次添加（防御分支）
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.util.Arrays.stream(System.getenv("PATH").split(":"))
                        .anyMatch(pn -> java.nio.file.Files.isRegularFile(java.nio.file.Path.of(pn, "git"))),
                "unit-test 镜像无 git");
        Path work = temp.resolve("pager-seed");
        Files.createDirectories(work);
        runGit(work, "init", "-b", "main");
        var cli = new SkillRepository.ProcessGitCli();
        runGit(work, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "--allow-empty", "-m", "x");
        // 显式带 --no-pager 的 read 调用应正常工作
        String out = cli.read(work, "--no-pager", "log", "--oneline", "-1");
        assertThat(out).contains("x");
    }

    @Test
    void 生产构造器使用进程git() {
        var repository = new SkillRepository(new SkillRepositoryProperties(temp.resolve("prod"), null, null, null));
        assertThat(repository).isNotNull();
    }

    @Test
    void 父目录无法创建时克隆包装为SKILL_CLONE_FAILED() throws IOException {
        // 父路径是普通文件 → createDirectories 抛 IOException → SKILL_CLONE_FAILED 包装
        Path blocker = temp.resolve("blocker");
        Files.writeString(blocker, "x");
        Path root = blocker.resolve("nested").resolve("ai-skills");
        assertThatThrownBy(() -> repository(root, new StubGit()).ensureCloned())
                .hasMessageContaining("SKILL_CLONE_FAILED");
    }

    @Test
    void 非目录路径技能列表吞异常为空() throws IOException {
        Path notDir = temp.resolve("file-not-dir");
        Files.writeString(notDir, "x");
        assertThat(repository(notDir, new StubGit()).listSkills()).isEmpty();
    }

    @Test
    void 真实git链路从本地远端克隆并查询状态() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.util.Arrays.stream(System.getenv("PATH").split(":"))
                        .anyMatch(p -> java.nio.file.Files.isRegularFile(java.nio.file.Path.of(p, "git"))),
                "unit-test 镜像无 git，真实链路由运行时镜像/冒烟覆盖");
        // 远端 = 测试内自建的裸仓库（不依赖网络）
        Path remote = temp.resolve("skills-remote.git");
        Path work = temp.resolve("seed");
        Files.createDirectories(work.resolve("riscv-migrate"));
        Files.writeString(work.resolve("riscv-migrate").resolve("SKILL.md"), "# riscv-migrate\n");
        runGit(work, "init", "-b", "main");
        runGit(work, "add", "-A");
        runGit(work, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "init");
        runGit(work, "clone", "--bare", ".", remote.toString());
        runGit(work, "remote", "add", "origin", remote.toUri().toString());

        Path root = temp.resolve("skills");
        SkillRepository repository = new SkillRepository(
                new SkillRepositoryProperties(root, remote.toUri().toString(), "main", null),
                new SkillRepository.ProcessGitCli());

        repository.ensureCloned();
        assertThat(repository.listSkills()).containsExactly("riscv-migrate");
        // 增加远端新提交后 status 应报告落后 1、update 前进
        Files.writeString(work.resolve("SKILL2.md"), "# new");
        runGit(work, "add", "-A");
        runGit(work, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "second");
        runGit(work, "push", "origin", "main");
        var status = repository.status();
        assertThat(status.commitsBehind()).isEqualTo(1);
        assertThat(status.currentRevision()).isNotEqualTo(status.remoteRevision());
        var update = repository.update();
        assertThat(update.commitsChanged()).isEqualTo(1);
        assertThat(repository.status().commitsBehind()).isZero();
    }

    private static void runGit(Path dir, String... args) {
        try {
            List<String> command = new java.util.ArrayList<>();
            command.add("git");
            command.addAll(java.util.Arrays.asList(args));
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(dir.toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String out = new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IllegalStateException("seed git failed: " + String.join(" ", args) + " -> " + out);
            }
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void 无更新时零变更且不reset() throws IOException {
        StubGit git = new StubGit();
        git.phase(Map.of(
                "rev-parse HEAD", "same1",
                "rev-parse refs/remotes/origin/main", "same1",
                "rev-list --count HEAD..refs/remotes/origin/main", "0"));
        var result = repository(repoDir(true), git).update();
        assertThat(result.commitsChanged()).isZero();
        assertThat(git.runLog).noneSatisfy(cmd -> assertThat(cmd).startsWith("reset"));
    }
}
