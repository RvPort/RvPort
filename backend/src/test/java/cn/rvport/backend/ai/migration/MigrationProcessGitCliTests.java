package cn.rvport.backend.ai.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 真实 git 进程封装（生产路径）：成功读/写、非零退出、超时强杀与中断标记。
 * 超时用例经 rvport.test.git.bin 钩子注入慢脚本（与进程封装的生产实现零耦合）。
 */
class MigrationProcessGitCliTests {

    @TempDir
    Path repo;

    private MigrationRepoService.ProcessGitCli cli;

    @BeforeEach
    void setup() throws Exception {
        Assumptions.assumeTrue(gitAvailable(), "unit-test 镜像无 git");
        cli = new MigrationRepoService.ProcessGitCli();
        cli.run(repo, Duration.ofMinutes(1), "init", "-q");
        cli.run(repo, Duration.ofMinutes(1), "config", "user.email", "t@t");
        cli.run(repo, Duration.ofMinutes(1), "config", "user.name", "t");
        Files.writeString(repo.resolve("a.c"), "int a;\n");
        cli.run(repo, Duration.ofMinutes(1), "add", ".");
        cli.run(repo, Duration.ofMinutes(1), "commit", "-q", "-m", "init");
    }

    private boolean gitAvailable() {
        return java.util.Arrays.stream(System.getenv("PATH").split(":"))
                .anyMatch(p -> Files.isRegularFile(Path.of(p, "git")));
    }

    @Test
    void read返回命令输出且run执行成功路径() throws Exception {
        String log = cli.read(repo, "log", "--format=%s");
        assertThat(log.trim()).isEqualTo("init");
        // run 对成功命令静默完成（exit 0 不抛）
        cli.run(repo, Duration.ofSeconds(30), "status");
        // read 对非零退出抛 GIT_FAILED 并带截断输出
        assertThatThrownBy(() -> cli.read(repo, "show", "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GIT_FAILED");
        // run 对非零退出同样抛出
        assertThatThrownBy(() -> cli.run(repo, Duration.ofSeconds(30), "show", "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GIT_FAILED");
        // 无效工作目录 → 进程启动失败抛 GIT_ERROR
        assertThatThrownBy(() -> cli.read(repo.resolve("no-such-dir"), "log"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GIT_ERROR");
    }

    @Test
    void 超时强杀并保留中断语义() throws Exception {
        Path slow = Files.createTempFile("rvport-slow-git", ".sh");
        Files.writeString(slow, "#!/bin/sh\nsleep 30\n");
        Runtime.getRuntime().exec(new String[]{"chmod", "+x", slow.toString()}).waitFor();
        String oldBin = System.getProperty("rvport.test.git.bin");
        System.setProperty("rvport.test.git.bin", slow.toString());
        try {
            assertThatThrownBy(() -> cli.run(repo, Duration.ofMillis(300), "log"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("GIT_TIMEOUT");
        } finally {
            if (oldBin == null) {
                System.clearProperty("rvport.test.git.bin");
            } else {
                System.setProperty("rvport.test.git.bin", oldBin);
            }
            Files.deleteIfExists(slow);
        }
    }

    @Test
    void 输出UTF8与多行完整回传() throws Exception {
        Files.writeString(repo.resolve("中文.c"), "内容\n", StandardCharsets.UTF_8);
        cli.run(repo, Duration.ofSeconds(30), "add", ".");
        cli.run(repo, Duration.ofSeconds(30), "commit", "-q", "-m", "中文提交");
        String names = cli.read(repo, "-c", "core.quotepath=false", "ls-files");
        assertThat(names).contains("中文.c");
        String log = cli.read(repo, "log", "--format=%s");
        assertThat(log).contains("中文提交");
    }
}
