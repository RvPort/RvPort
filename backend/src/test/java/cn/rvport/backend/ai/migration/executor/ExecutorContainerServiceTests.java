package cn.rvport.backend.ai.migration.executor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ExecutorContainerServiceTests {

    private static final UUID TASK = UUID.randomUUID();

    /** 记录调用并按剧本回放的 docker stub。 */
    static class StubCli implements ExecutorContainerService.DockerCli {
        final List<List<String>> commands = new ArrayList<>();
        final AtomicInteger exitCode = new AtomicInteger(0);
        volatile boolean neverFinish = false;

        @Override
        public ProcessHandle start(List<String> command) {
            commands.add(command);
            return new ProcessHandle() {
                @Override
                public boolean waitFor(Duration timeout, StringBuilder stdout, StringBuilder stderr)
                        throws InterruptedException {
                    if (neverFinish) {
                        Thread.sleep(timeout.toMillis() + 1000);
                        return false;
                    }
                    stdout.append("DONE\n");
                    stderr.append("");
                    return true;
                }

                @Override
                public int exitValue() {
                    return exitCode.get();
                }
            };
        }

        @Override
        public void runQuiet(List<String> command) {
            commands.add(command);
        }

        List<String> firstRunCommand() {
            return commands.stream()
                    .filter(c -> c.contains("run"))
                    .findFirst().orElseThrow();
        }
    }

    @Test
    void 正常执行返回退出码与输出且先清孤儿() throws Exception {
        StubCli cli = new StubCli();
        var service = new ExecutorContainerService(new ExecutorContainerProperties(null, null, Duration.ofMinutes(1)), cli);

        var result = service.execute(TASK, Path.of("/tmp/ws"), Map.of("WXAI_API_KEY", "k"),
                ExecutorContainerService.CancellationToken.none());

        assertThat(result.succeeded()).isTrue();
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("DONE");
        // 孤儿清理先于 run 执行（按记录顺序定位而非索引）
        int rmIndex = cli.commands.stream()
                .mapToInt(cli.commands::indexOf)
                .filter(i -> cli.commands.get(i).size() == 4 && "rm".equals(cli.commands.get(i).get(1)))
                .findFirst().orElse(-1);
        int runIndex = cli.commands.stream()
                .mapToInt(cli.commands::indexOf)
                .filter(i -> "run".equals(cli.commands.get(i).get(1)))
                .findFirst().orElse(-1);
        assertThat(rmIndex).isGreaterThanOrEqualTo(0);
        assertThat(runIndex).isGreaterThan(rmIndex);
        assertThat(cli.commands.get(rmIndex)).containsExactly("docker", "rm", "-f",
                ExecutorContainerCommandFactory.containerName(TASK));
        assertThat(cli.commands.get(runIndex)).anyMatch(e -> e.startsWith("rvport-executor-"));
    }

    @Test
    void 非零退出码透传() throws Exception {
        StubCli cli = new StubCli();
        cli.exitCode.set(1);
        var service = new ExecutorContainerService(new ExecutorContainerProperties(null, null, Duration.ofMinutes(1)), cli);

        var result = service.execute(TASK, Path.of("/tmp/ws"), Map.of(),
                ExecutorContainerService.CancellationToken.none());

        assertThat(result.succeeded()).isFalse();
        assertThat(result.exitCode()).isEqualTo(1);
    }

    @Test
    void 超时触发kill容器并标记timedOut() throws Exception {
        StubCli cli = new StubCli();
        cli.neverFinish = true;
        var service = new ExecutorContainerService(
                new ExecutorContainerProperties(null, null, Duration.ofMillis(100)), cli);

        long start = System.currentTimeMillis();
        var result = service.execute(TASK, Path.of("/tmp/ws"), Map.of(),
                ExecutorContainerService.CancellationToken.none());

        assertThat(result.timedOut()).isTrue();
        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(cli.commands.stream().anyMatch(c -> c.size() == 3 && "kill".equals(c.get(1)))).isTrue();
        assertThat(System.currentTimeMillis() - start).isLessThan(10_000);
    }

    @Test
    void 取消信号触发kill() throws Exception {
        StubCli cli = new StubCli();
        cli.neverFinish = true;
        CountDownLatch cancelSignalled = new CountDownLatch(1);
        // 取消信号：先证明被等待（countDown），短暂阻塞后返回 = 取消事件到达，
        // killer 应随即下发 docker kill。
        ExecutorContainerService.CancellationToken cancel = () -> {
            cancelSignalled.countDown();
            Thread.sleep(300);
        };
        var service = new ExecutorContainerService(
                new ExecutorContainerProperties(null, null, Duration.ofMinutes(5)), cli);

        Thread execution = new Thread(() -> {
            try {
                service.execute(TASK, Path.of("/tmp/ws"), Map.of(), cancel);
            } catch (Exception ignored) {
                // 主线程 interrupt 后的退出路径
            }
        });
        execution.setDaemon(true);
        execution.start();

        // 等取消信号被 killer 线程拿到，然后轮询 kill 命令出现
        assertThat(cancelSignalled.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline
                && cli.commands.stream().noneMatch(c -> c.size() == 3 && "kill".equals(c.get(1)))) {
            Thread.sleep(50);
        }
        execution.interrupt();
        execution.join(5_000);
        assertThat(cli.commands.stream().anyMatch(c -> c.size() == 3 && "kill".equals(c.get(1)))).isTrue();
    }

    @Test
    void 无取消令牌走非killer路径且merge组合() throws Exception {
        StubCli cli = new StubCli() {
            @Override
            public ProcessHandle start(List<String> command) {
                commands.add(command);
                return new ProcessHandle() {
                    @Override
                    public boolean waitFor(Duration timeout, StringBuilder stdout, StringBuilder stderr) {
                        stdout.append("only-out");
                        stderr.append("only-err");
                        return true;
                    }
                    @Override
                    public int exitValue() { return 0; }
                };
            }
        };
        var service = new ExecutorContainerService(new ExecutorContainerProperties(null, null, Duration.ofMinutes(1)), cli);
        var result = service.execute(TASK, Path.of("/tmp/ws"), Map.of(), null);
        assertThat(result.mergedOutput()).isEqualTo("only-out\nonly-err");

        // 单侧输出组合
        StubCli cli2 = new StubCli() {
            @Override
            public ProcessHandle start(List<String> command) {
                commands.add(command);
                return new ProcessHandle() {
                    @Override
                    public boolean waitFor(Duration timeout, StringBuilder stdout, StringBuilder stderr) {
                        stderr.append("err-only");
                        return true;
                    }
                    @Override
                    public int exitValue() { return 0; }
                };
            }
        };
        var service2 = new ExecutorContainerService(new ExecutorContainerProperties(null, null, Duration.ofMinutes(1)), cli2);
        assertThat(service2.execute(TASK, Path.of("/tmp/ws"), Map.of(), null).mergedOutput()).isEqualTo("err-only");
    }

    @Test
    void 取消与超时竞争下kill只发一次() throws Exception {
        // 取消先到（标记 killed），随后的超时路径不得二次 kill
        StubCli cli = new StubCli();
        cli.neverFinish = true;
        ExecutorContainerService.CancellationToken cancel = () -> {
            // 立即返回 = 取消事件即刻到达
        };
        var service = new ExecutorContainerService(
                new ExecutorContainerProperties(null, null, Duration.ofMillis(200)), cli);

        Thread execution = new Thread(() -> {
            try {
                service.execute(TASK, Path.of("/tmp/ws"), Map.of(), cancel);
            } catch (Exception ignored) {
            }
        });
        execution.setDaemon(true);
        execution.start();
        execution.join(10_000);
        long killCount = cli.commands.stream()
                .filter(c -> c.size() == 3 && "kill".equals(c.get(1))).count();
        assertThat(killCount).isEqualTo(1);
    }

    @Test
    void 生产cli的quiet容错与重定向() throws Exception {
        var cli = new ExecutorContainerService.ProcessDockerCli();
        // 不存在的 docker 命令也不抛（容错契约）
        cli.runQuiet(java.util.List.of("docker", "rm", "-f", "no-such-container"));
    }

    @Test
    void 生产cli真实进程路径采集输出与退出码() throws Exception {
        var cli = new ExecutorContainerService.ProcessDockerCli();
        var handle = cli.start(java.util.List.of("sh", "-c", "echo HELLO-OUT; echo HELLO-ERR >&2; exit 7"));
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        boolean finished = handle.waitFor(Duration.ofSeconds(10), out, err);
        org.assertj.core.api.Assertions.assertThat(finished).isTrue();
        org.assertj.core.api.Assertions.assertThat(handle.exitValue()).isEqualTo(7);
        org.assertj.core.api.Assertions.assertThat(out.toString()).contains("HELLO-OUT");
        org.assertj.core.api.Assertions.assertThat(err.toString()).contains("HELLO-ERR");
    }

    @Test
    void 生产cli读取线程的输出上限截断() throws Exception {
        var cli = new ExecutorContainerService.ProcessDockerCli();
        var handle = cli.start(java.util.List.of("sh", "-c",
                "awk 'BEGIN{for(i=0;i<400000;i++) print \"line-\" i}'"));
        StringBuilder out = new StringBuilder();
        boolean finished = handle.waitFor(Duration.ofSeconds(30), out, new StringBuilder());
        org.assertj.core.api.Assertions.assertThat(finished).isTrue();
        // 采集线程自身缓冲上限 4*64K，最终 clip 后不超过 64K
        org.assertj.core.api.Assertions.assertThat(out.length()).isLessThanOrEqualTo(4 * 64 * 1024 + 1024);
    }

    @Test
    void 生产cli的quiet超时强杀() throws Exception {
        var cli = new ExecutorContainerService.ProcessDockerCli();
        long start = System.currentTimeMillis();
        // sleep 40s 超过 quiet 30s 上限 → destroyForcibly 分支
        cli.runQuiet(java.util.List.of("sh", "-c", "sleep 40"));
        org.assertj.core.api.Assertions.assertThat(System.currentTimeMillis() - start).isLessThan(35_000);
    }

    @Test
    void 生产cli超短超时路径返回未完成() throws Exception {
        var cli = new ExecutorContainerService.ProcessDockerCli();
        var handle = cli.start(java.util.List.of("sleep", "5"));
        boolean finished = handle.waitFor(Duration.ofMillis(200), new StringBuilder(), new StringBuilder());
        org.assertj.core.api.Assertions.assertThat(finished).isFalse();
    }

    @Test
    void 输出超限裁剪() throws Exception {
        StubCli cli = new StubCli() {
            @Override
            public ProcessHandle start(List<String> command) {
                commands.add(command);
                return new ProcessHandle() {
                    @Override
                    public boolean waitFor(Duration timeout, StringBuilder stdout, StringBuilder stderr) {
                        stdout.append("x".repeat(200_000));
                        return true;
                    }

                    @Override
                    public int exitValue() {
                        return 0;
                    }
                };
            }
        };
        var service = new ExecutorContainerService(new ExecutorContainerProperties(null, null, Duration.ofMinutes(1)), cli);

        var result = service.execute(TASK, Path.of("/tmp/ws"), Map.of(),
                ExecutorContainerService.CancellationToken.none());

        assertThat(result.stdout().length()).isLessThanOrEqualTo(64 * 1024);
    }
}
