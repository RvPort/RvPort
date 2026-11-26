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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ExecutorContainerServiceTests {

    private static final UUID TASK = UUID.randomUUID();

    /** 记录调用并按剧本回放的 docker stub。 */
    static class StubCli implements ExecutorContainerService.DockerCli {
        final List<List<String>> commands = new ArrayList<>();
        /** 每条 kill 命令被下发时的时间戳（纳秒），用于断言 kill 相对超时的先后。 */
        final List<Long> killTimesNanos = new ArrayList<>();
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
            if (command.size() == 3 && "kill".equals(command.get(1))) {
                killTimesNanos.add(System.nanoTime());
            }
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
        var service = new ExecutorContainerService(new ExecutorContainerProperties(null, Duration.ofMinutes(1)), cli);

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
        var service = new ExecutorContainerService(new ExecutorContainerProperties(null, Duration.ofMinutes(1)), cli);

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
                new ExecutorContainerProperties(null, Duration.ofMillis(100)), cli);

        long start = System.currentTimeMillis();
        var result = service.execute(TASK, Path.of("/tmp/ws"), Map.of(),
                ExecutorContainerService.CancellationToken.none());

        assertThat(result.timedOut()).isTrue();
        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(cli.commands.stream().anyMatch(c -> c.size() == 3 && "kill".equals(c.get(1)))).isTrue();
        assertThat(System.currentTimeMillis() - start).isLessThan(10_000);
    }

    @Test
    void 不提供取消信号时启动后不得发出kill且超时仍会kill() throws Exception {
        // 回归：none() 曾是立即返回的空实现，killer 线程会在 docker run 启动后立刻 docker kill
        // （与容器创建竞态），并把 killed 置 true 导致真正的超时 kill 被跳过。
        StubCli cli = new StubCli();
        cli.neverFinish = true;
        long timeoutMs = 1500;
        var service = new ExecutorContainerService(
                new ExecutorContainerProperties(null, Duration.ofMillis(timeoutMs)), cli);

        long started = System.nanoTime();
        var result = service.execute(TASK, Path.of("/tmp/ws"), Map.of(),
                ExecutorContainerService.CancellationToken.none());

        assertThat(result.timedOut()).isTrue();
        // 恰好一次 kill，且发生在超时之后：说明它来自超时路径，而不是启动竞态。
        assertThat(cli.killTimesNanos).hasSize(1);
        long elapsedMs = (cli.killTimesNanos.get(0) - started) / 1_000_000;
        assertThat(elapsedMs).isGreaterThanOrEqualTo(timeoutMs - 100);
    }

    @Test
    void none令牌在被中断前始终阻塞() throws Exception {
        // none() 的 await 不得自行返回；只有 interrupt 才让它退出并保留中断标记。
        ExecutorContainerService.CancellationToken none = ExecutorContainerService.CancellationToken.none();
        CountDownLatch released = new CountDownLatch(1);
        AtomicBoolean returnedBeforeInterrupt = new AtomicBoolean(false);
        Thread waiter = new Thread(() -> {
            try {
                none.await();
                returnedBeforeInterrupt.set(true);
            } catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
            } finally {
                released.countDown();
            }
        });
        waiter.setDaemon(true);
        waiter.start();

        Thread.sleep(800);
        assertThat(returnedBeforeInterrupt).isFalse();
        assertThat(released.getCount()).isEqualTo(1);

        waiter.interrupt();
        assertThat(released.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(waiter.isInterrupted()).isTrue();
    }

    @Test
    void 登记取消资源后取消才杀容器且只杀一次() throws Exception {
        // 用户取消任务时，任务框架对登记的资源调用 cancel：必须真正 docker kill，且与超时路径合计只杀一次。
        StubCli cli = new StubCli();
        cli.neverFinish = true;
        List<Runnable> registered = new ArrayList<>();
        var service = new ExecutorContainerService(
                new ExecutorContainerProperties(null, Duration.ofSeconds(30)), cli);

        Thread execution = new Thread(() -> {
            try {
                service.execute(TASK, Path.of("/tmp/ws"), Path.of("/tmp/ws"), Map.of(),
                        ExecutorContainerService.CancellationToken.none(), registered::add);
            } catch (Exception ignored) {
                // 取消后 worker 线程被中断的退出路径
            }
        });
        execution.setDaemon(true);
        execution.start();

        // 等容器启动并完成登记
        long deadline = System.currentTimeMillis() + 5_000;
        while (registered.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(registered).hasSize(1);
        // 登记本身不得触发 kill（登记发生在容器刚启动时，此时任务仍在运行）
        assertThat(cli.killTimesNanos).isEmpty();

        // 任务框架：先 cancel 资源，再中断 worker 线程
        registered.get(0).run();
        execution.interrupt();
        execution.join(5_000);

        assertThat(cli.killTimesNanos).hasSize(1);
        assertThat(cli.commands.stream().anyMatch(c -> c.size() == 3 && "kill".equals(c.get(1))
                && ExecutorContainerCommandFactory.containerName(TASK).equals(c.get(2)))).isTrue();
    }

    @Test
    void 登记失败时先杀掉刚启动的容器再抛出() throws Exception {
        // 任务在容器启动后、登记前被取消：registerCancellableResource 会抛 TaskAttemptLostException。
        // 此时容器已经起来了，execute 必须先终止它，不能让孤儿容器带着密钥继续跑。
        StubCli cli = new StubCli();
        var service = new ExecutorContainerService(
                new ExecutorContainerProperties(null, Duration.ofSeconds(30)), cli);
        RuntimeException registrationFailure = new IllegalStateException("attempt lost");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.execute(TASK, Path.of("/tmp/ws"),
                Path.of("/tmp/ws"), Map.of(), ExecutorContainerService.CancellationToken.none(),
                resource -> {
                    throw registrationFailure;
                }))
                .isSameAs(registrationFailure);

        assertThat(cli.killTimesNanos).hasSize(1);
    }

    @Test
    void 不登记取消资源时不影响正常执行() throws Exception {
        StubCli cli = new StubCli();
        var service = new ExecutorContainerService(
                new ExecutorContainerProperties(null, Duration.ofMinutes(1)), cli);

        var result = service.execute(TASK, Path.of("/tmp/ws"), Path.of("/tmp/ws"), Map.of(),
                ExecutorContainerService.CancellationToken.none(), null);

        assertThat(result.succeeded()).isTrue();
        assertThat(cli.killTimesNanos).isEmpty();
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
                new ExecutorContainerProperties(null, Duration.ofMinutes(5)), cli);

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
        var service = new ExecutorContainerService(new ExecutorContainerProperties(null, Duration.ofMinutes(1)), cli);
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
        var service2 = new ExecutorContainerService(new ExecutorContainerProperties(null, Duration.ofMinutes(1)), cli2);
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
                new ExecutorContainerProperties(null, Duration.ofMillis(200)), cli);

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
    void null输出组合与超时先行killer落空路径() throws Exception {
        // stdout=null → clip 的 null 分支；超时先 kill（CAS true），killer 后到 CAS false
        StubCli cli = new StubCli() {
            @Override
            public ProcessHandle start(List<String> command) {
                commands.add(command);
                return new ProcessHandle() {
                    @Override
                    public boolean waitFor(Duration timeout, StringBuilder stdout, StringBuilder stderr)
                            throws InterruptedException {
                        Thread.sleep(timeout.toMillis() + 300);
                        return false;
                    }
                    @Override
                    public int exitValue() { return -1; }
                };
            }
        };
        // 取消令牌在超时之后才放行（await 阻塞 500ms > timeout 200ms）
        ExecutorContainerService.CancellationToken late = () -> Thread.sleep(600);
        var service = new ExecutorContainerService(
                new ExecutorContainerProperties(null, Duration.ofMillis(200)), cli);
        var result = service.execute(TASK, Path.of("/tmp/ws"), java.util.Map.of(), late);
        assertThat(result.timedOut()).isTrue();
        long killCount = cli.commands.stream()
                .filter(c -> c.size() == 3 && "kill".equals(c.get(1))).count();
        assertThat(killCount).isEqualTo(1);
    }

    @Test
    void waitFor抛中断时收尾killer线程() throws Exception {
        StubCli cli = new StubCli();
        CountDownLatch entered = new CountDownLatch(1);
        cli.neverFinish = true;
        ExecutorContainerService.CancellationToken cancel = () -> entered.await();
        var service = new ExecutorContainerService(
                new ExecutorContainerProperties(null, Duration.ofMinutes(5)), cli);
        Thread execution = new Thread(() -> {
            try {
                service.execute(TASK, Path.of("/tmp/ws"), Map.of(), cancel);
            } catch (Exception ignored) {
            }
        });
        execution.setDaemon(true);
        execution.start();
        entered.await(java.util.concurrent.TimeUnit.SECONDS.toMillis(5), java.util.concurrent.TimeUnit.MILLISECONDS);
        // 中断 execution 线程 → waitFor 抛 InterruptedException → finally 中断 killer
        execution.interrupt();
        execution.join(5_000);
        org.assertj.core.api.Assertions.assertThat(execution.isAlive()).isFalse();
    }

    @Test
    void 生产构造器使用进程cli() {
        var service = new ExecutorContainerService(new ExecutorContainerProperties(null, null));
        assertThat(service).isNotNull();
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
        var service = new ExecutorContainerService(new ExecutorContainerProperties(null, Duration.ofMinutes(1)), cli);

        var result = service.execute(TASK, Path.of("/tmp/ws"), Map.of(),
                ExecutorContainerService.CancellationToken.none());

        assertThat(result.stdout().length()).isLessThanOrEqualTo(64 * 1024);
    }

    @Test
    void 等待采集线程时被中断则保留中断标记() throws Exception {
        // waitFor 的收尾阶段（进程已退出、正在 join 采集线程）被中断：catch 块必须恢复中断标记，
        // 供上层识别为任务取消。让子进程退出后仍有后代进程握着 stdout 不放，使 join 处于等待状态。
        // 轮询 exitValue 确认子进程已退出（waiter 已越过 process.waitFor、正停在 join 阶段）再中断，
        // 消除"定时猜测"竞态；极端负载下后代进程可能 fork 失败令 join 阶段提前结束，此时整体重试。
        var cli = new ExecutorContainerService.ProcessDockerCli();
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        var threw = new java.util.concurrent.atomic.AtomicBoolean();
        for (int attempt = 0; attempt < 3 && !(interrupted.get() && !threw.get()); attempt++) {
            interrupted.set(false);
            threw.set(false);
            var handle = cli.start(java.util.List.of("sh", "-c",
                    "echo PARENT-DONE; ( sleep 8 ) & exit 0"));
            Thread waiter = new Thread(() -> {
                try {
                    handle.waitFor(Duration.ofSeconds(60), new StringBuilder(), new StringBuilder());
                } catch (Exception ex) {
                    threw.set(true);
                } finally {
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
            });
            waiter.setDaemon(true);
            waiter.start();
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (true) {
                try {
                    handle.exitValue();
                    break;
                } catch (IllegalThreadStateException stillRunning) {
                    if (System.nanoTime() > deadline) {
                        throw new java.util.concurrent.TimeoutException("子进程未在 5s 内退出");
                    }
                    Thread.sleep(10);
                }
            }
            waiter.interrupt();
            waiter.join(10_000);
            assertThat(waiter.isAlive()).isFalse();
        }
        // 必须是"被 catch 后正常返回"而不是从 process.waitFor 抛出：
        // 后者由 JVM 保留中断标记，同样会让下面的断言成立，却并未覆盖 catch 分支。
        assertThat(threw.get()).as("中断应落在 join 阶段并被 catch，而不是从 waitFor 抛出").isFalse();
        // 中断标记被保留：catch 块里恢复过
        assertThat(interrupted.get()).isTrue();
    }

    @Test
    void 进程退出后已采集的输出不丢失() throws Exception {
        // 真实采集线程：进程写出内容后退出，waitFor 返回时输出已完整交给调用方。
        // （采集线程的 catch (IOException) 分支需要流在读取中途异常关闭，无法用普通子进程稳定构造，此处不作覆盖。）
        var cli = new ExecutorContainerService.ProcessDockerCli();
        var handle = cli.start(java.util.List.of("sh", "-c",
                "echo FIRST-LINE; echo SECOND-LINE; echo LAST-LINE; exit 0"));
        StringBuilder out = new StringBuilder();
        boolean finished = handle.waitFor(Duration.ofSeconds(20), out, new StringBuilder());

        assertThat(finished).isTrue();
        assertThat(out.toString()).contains("FIRST-LINE").contains("LAST-LINE");
    }

    @Test
    void 输出恰为空时返回空串() throws Exception {
        // 边界：容器无任何输出时 stdout/stderr 为空串，下游拼接与日志不出现 "null" 字样
        StubCli cli = new StubCli() {
            @Override
            public ProcessHandle start(List<String> command) {
                commands.add(command);
                return new ProcessHandle() {
                    @Override
                    public boolean waitFor(Duration timeout, StringBuilder stdout, StringBuilder stderr) {
                        return true;
                    }

                    @Override
                    public int exitValue() {
                        return 0;
                    }
                };
            }
        };
        var service = new ExecutorContainerService(new ExecutorContainerProperties(null, Duration.ofMinutes(1)), cli);

        var result = service.execute(TASK, Path.of("/tmp/ws"), Map.of(),
                ExecutorContainerService.CancellationToken.none());

        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).isEmpty();
        assertThat(result.mergedOutput()).isEmpty();
        assertThat(result.succeeded()).isTrue();
    }
}
