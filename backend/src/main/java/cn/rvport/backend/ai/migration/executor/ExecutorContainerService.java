package cn.rvport.backend.ai.migration.executor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 执行器容器生命周期：一次性 docker run（--rm）+ 取消 kill + 超时 + 同名孤儿清理。
 *
 * <p>直接驱动 docker CLI 进程（supervisor 模式不适用：docker run 本身就是生命周期管理器，
 * kill 容器用独立 docker kill 调用）。输出上限与任务日志脱敏由调用方负责。
 */
@Service
public class ExecutorContainerService {

    private static final Logger log = LoggerFactory.getLogger(ExecutorContainerService.class);
    private static final int OUTPUT_LIMIT_CHARS = 64 * 1024;

    private final ExecutorContainerProperties properties;
    private final DockerCli cli;

    @org.springframework.beans.factory.annotation.Autowired
    public ExecutorContainerService(AiMigrationExecutorProperties executorProperties) {
        this(new ExecutorContainerProperties(executorProperties.image(), executorProperties.dockerTimeout()),
                new ProcessDockerCli());
    }

    ExecutorContainerService(ExecutorContainerProperties properties) {
        this(properties, new ProcessDockerCli());
    }

    ExecutorContainerService(ExecutorContainerProperties properties, DockerCli cli) {
        this.properties = properties;
        this.cli = cli;
    }

    /** 单次执行：清理同名残留 → 启动 → 等待（超时/取消）→ 返回退出码与输出。 */
    public ExecutorContainerResult execute(
            UUID taskId,
            Path workspace,
            Map<String, String> environment,
            CancellationToken cancellation) throws IOException, InterruptedException {
        return execute(taskId, workspace, workspace, environment, cancellation);
    }

    /** 同上，hostWorkspace 为 docker daemon 可见的宿主路径（worker 自身运行在容器内时必传）。 */
    public ExecutorContainerResult execute(
            UUID taskId,
            Path workspace,
            Path hostWorkspace,
            Map<String, String> environment,
            CancellationToken cancellation) throws IOException, InterruptedException {
        return execute(taskId, workspace, hostWorkspace, environment, cancellation, null);
    }

    /**
     * 同上，并把"终止本次执行器容器"登记为任务的可取消资源。
     *
     * <p>用户取消任务时，任务框架先对各可取消资源调用 cancel，再中断 worker 线程；只中断线程
     * 不会结束 docker run 子进程，容器会带着模型密钥继续运行。register 为 null 时不登记（测试/一次性调用）。
     */
    public ExecutorContainerResult execute(
            UUID taskId,
            Path workspace,
            Path hostWorkspace,
            Map<String, String> environment,
            CancellationToken cancellation,
            java.util.function.Consumer<Runnable> registerCancellation) throws IOException, InterruptedException {
        String containerName = ExecutorContainerCommandFactory.containerName(taskId);
        // 重试场景的孤儿容器：--rm 容器异常残留（如 daemon 重启窗口）时先移除。
        cli.runQuiet(ExecutorContainerCommandFactory.removeCommand(containerName));

        List<String> command = ExecutorContainerCommandFactory.runCommand(
                properties.image(), containerName, workspace, hostWorkspace, environment, Map.of());
        log.info("executor container starting name={} image={}", containerName, properties.image());

        DockerCli.ProcessHandle process = cli.start(command);
        AtomicBoolean killed = new AtomicBoolean(false);
        Runnable killOnce = () -> {
            if (killed.compareAndSet(false, true)) {
                cli.runQuiet(ExecutorContainerCommandFactory.killCommand(containerName));
                log.info("executor container killed name={}", containerName);
            }
        };
        if (registerCancellation != null) {
            // 登记失败（任务已失去归属）时 execute 会抛出；此时容器刚启动，必须先终止它。
            try {
                registerCancellation.accept(killOnce);
            } catch (RuntimeException registrationFailed) {
                killOnce.run();
                throw registrationFailed;
            }
        }
        Thread killer = null;
        if (cancellation != null) {
            killer = new Thread(() -> {
                try {
                    cancellation.await();
                    killOnce.run();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }, "executor-cancel-" + taskId);
            killer.setDaemon(true);
            killer.start();
        }
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        boolean timedOut;
        try {
            timedOut = !process.waitFor(properties.dockerTimeout(), stdout, stderr);
            if (timedOut) {
                killOnce.run();
                log.warn("executor container timed out name={} timeout={}s", containerName,
                        properties.dockerTimeout().toSeconds());
            }
        } finally {
            if (killer != null) {
                killer.interrupt();
            }
        }
        String out = clip(stdout.toString());
        String err = clip(stderr.toString());
        int exitCode = timedOut ? -1 : process.exitValue();
        log.info("executor container finished name={} exit={} timedOut={}", containerName, exitCode, timedOut);
        return new ExecutorContainerResult(exitCode, timedOut, out, err, merge(out, err));
    }

    /** 调用方传入的均为 StringBuilder#toString()，不为 null；超长输出截断到上限，防止刷屏撑爆任务日志。 */
    private static String clip(String text) {
        return text.length() <= OUTPUT_LIMIT_CHARS ? text : text.substring(0, OUTPUT_LIMIT_CHARS);
    }

    private static String merge(String out, String err) {
        if (out.isBlank()) {
            return err;
        }
        if (err.isBlank()) {
            return out;
        }
        return out + "\n" + err;
    }

    /** 取消信号：await 阻塞直到取消或线程被中断（超时路径由 waitFor 兜底）。 */
    public interface CancellationToken {
        void await() throws InterruptedException;

        /**
         * 表示"本次执行不提供取消信号"。await 必须一直阻塞到线程被中断：
         * 若立即返回，killer 线程会在 docker run 启动后立刻下发 docker kill（与容器创建竞态），
         * 并把 killed 置为 true，使随后真正的超时 kill 被跳过。
         */
        static CancellationToken none() {
            return () -> {
                final Object gate = new Object();
                // 循环只为吸收 wait 的虚假唤醒；唯一正常出口是被 interrupt（抛 InterruptedException）。
                synchronized (gate) {
                    while (true) {
                        gate.wait();
                    }
                }
            };
        }
    }

    /** docker CLI 抽象（生产=进程，测试=stub）。 */
    interface DockerCli {
        ProcessHandle start(java.util.List<String> command) throws IOException;

        void runQuiet(java.util.List<String> command);

        interface ProcessHandle {
            boolean waitFor(Duration timeout, StringBuilder stdout, StringBuilder stderr)
                    throws IOException, InterruptedException;

            int exitValue();
        }
    }

    static final class ProcessDockerCli implements DockerCli {
        @Override
        public ProcessHandle start(java.util.List<String> command) throws IOException {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(false);
            Process process = builder.start();
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            Thread outReader = reader(process.getInputStream(), stdout);
            Thread errReader = reader(process.getErrorStream(), stderr);
            return new ProcessHandle() {
                @Override
                public boolean waitFor(Duration timeout, StringBuilder out, StringBuilder err2)
                        throws IOException, InterruptedException {
                    boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                    try {
                        outReader.join(2000);
                        errReader.join(2000);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    out.append(stdout);
                    err2.append(stderr);
                    return finished;
                }

                @Override
                public int exitValue() {
                    return process.exitValue();
                }
            };
        }

        @Override
        public void runQuiet(List<String> command) {
            try {
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                builder.redirectError(ProcessBuilder.Redirect.DISCARD);
                Process process = builder.start();
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (Exception ex) {
                log.debug("docker quiet command failed (tolerated): {}", command, ex);
            }
        }

        private static Thread reader(java.io.InputStream stream, StringBuilder sink) {
            Thread thread = new Thread(() -> {
                try (var reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (sink) {
                            if (sink.length() < 4 * OUTPUT_LIMIT_CHARS) {
                                sink.append(line).append('\n');
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // 进程终止时流关闭属正常路径
                }
            });
            thread.setDaemon(true);
            thread.start();
            return thread;
        }
    }
}
