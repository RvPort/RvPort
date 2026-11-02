package cn.rvport.backend.codexprovider;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class DefaultCodexProcessRunner implements CodexProcessRunner {

    private static final int OUTPUT_LIMIT_BYTES = 64 * 1024;
    // 管道读取会阻塞；独立虚拟线程避免占满 common pool 后标准输入无法写入。
    private static final Executor PROCESS_IO_EXECUTOR = Thread::startVirtualThread;

    @Override
    public CodexProcessResult run(
            List<String> command,
            Map<String, String> environment,
            String standardInput,
            Path workingDirectory,
            Duration timeout
    ) throws IOException, InterruptedException {
        ExecutionBudget execution = new ExecutionBudget(timeout);
        Path supervisor = java.nio.file.Files.createTempFile("rvport-agent-supervisor-", ".py");
        try {
            try (var resource = DefaultCodexProcessRunner.class.getResourceAsStream("/agentruntime/agent_process_supervisor.py")) {
                if (resource == null) { throw new IOException("Agent supervisor resource unavailable"); }
                java.nio.file.Files.copy(resource, supervisor, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            List<String> supervised = new ArrayList<>(List.of("python3", supervisor.toString()));
            supervised.addAll(command);
            return runSupervised(supervised, environment, standardInput, workingDirectory, timeout, execution);
        } finally { java.nio.file.Files.deleteIfExists(supervisor); }
    }

    private CodexProcessResult runSupervised(List<String> command, Map<String, String> environment, String standardInput,
            Path workingDirectory, Duration timeout, ExecutionBudget execution) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.environment().putAll(environment);
        Process process = builder.start();
        OwnedProcessTree tree = new OwnedProcessTree(process.toHandle());
        OutputCollector collector = new OutputCollector(OUTPUT_LIMIT_BYTES);
        CompletableFuture<Void> stdoutFuture = readAsync(process.getInputStream(), "STDOUT", collector);
        CompletableFuture<Void> stderrFuture = readAsync(process.getErrorStream(), "STDERR", collector);
        AtomicReference<IOException> stdinFailure = new AtomicReference<>();
        CompletableFuture<Void> stdinFuture = writeAsync(process.getOutputStream(), standardInput, stdinFailure);
        boolean finished = false;
        long executionMs;
        ExecutionBudget cleanup;
        boolean interrupted = false;
        try {
            while (!execution.expired()) {
                tree.capture();
                if (process.waitFor(Math.min(execution.remaining().toNanos(), 20_000_000), TimeUnit.NANOSECONDS)) {
                    finished = true;
                    break;
                }
            }
        } catch (InterruptedException ex) {
            interrupted = true;
            throw ex;
        } finally {
            executionMs = TimeUnit.NANOSECONDS.toMillis(execution.elapsedNanos());
            cleanup = new ExecutionBudget(Duration.ofSeconds(2));
            try {
                if (process.isAlive()) { process.destroy(); }
                while (tree.alive() && !cleanup.expired()) {
                    tree.capture();
                    TimeUnit.NANOSECONDS.sleep(Math.min(cleanup.remaining().toNanos(), 10_000_000));
                }
                join(stdinFuture, cleanup);
                join(stdoutFuture, cleanup);
                join(stderrFuture, cleanup);
            } catch (InterruptedException ex) {
                interrupted = true;
                throw ex;
            } finally {
                if (tree.alive()) { tree.terminate(cleanup); }
                process.getOutputStream().close();
                process.getInputStream().close();
                process.getErrorStream().close();
                stdinFuture.cancel(true);
                stdoutFuture.cancel(true);
                stderrFuture.cancel(true);
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        ProcessExecutionDiagnostics diagnostics = new ProcessExecutionDiagnostics(timeout.toMillis(), executionMs,
                TimeUnit.NANOSECONDS.toMillis(cleanup.elapsedNanos()),
                TimeUnit.NANOSECONDS.toMillis(execution.elapsedNanos()), !tree.alive()
                        && !(finished && process.exitValue() == 125));
        if (!finished) {
            return new CodexProcessResult(-1, true, collector.stdout(), collector.stderr(), collector.mergedOutput(), diagnostics);
        }
        if (stdinFailure.get() != null) {
            collector.appendSystemError("Agent stdin write failed");
            return new CodexProcessResult(-1, false, collector.stdout(), collector.stderr(), collector.mergedOutput(), diagnostics);
        }
        return new CodexProcessResult(process.exitValue(), false, collector.stdout(), collector.stderr(), collector.mergedOutput(), diagnostics);
    }

    private CompletableFuture<Void> writeAsync(OutputStream stream, String standardInput, AtomicReference<IOException> failure) {
        return CompletableFuture.runAsync(() -> {
            try (stream) {
                if (standardInput != null) {
                    stream.write(standardInput.getBytes(StandardCharsets.UTF_8));
                    stream.flush();
                }
            } catch (IOException ex) {
                failure.set(ex);
            }
        }, PROCESS_IO_EXECUTOR);
    }

    private CompletableFuture<Void> readAsync(java.io.InputStream stream, String label, OutputCollector collector) {
        return CompletableFuture.runAsync(() -> {
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                char[] buffer = new char[1024];
                Character pendingHighSurrogate = null;
                int read;
                while ((read = reader.read(buffer)) >= 0) {
                    StringBuilder text = new StringBuilder(read + 1);
                    if (pendingHighSurrogate != null) {
                        text.append(pendingHighSurrogate);
                        pendingHighSurrogate = null;
                    }
                    text.append(buffer, 0, read);
                    if (!text.isEmpty() && Character.isHighSurrogate(text.charAt(text.length() - 1))) {
                        pendingHighSurrogate = text.charAt(text.length() - 1);
                        text.setLength(text.length() - 1);
                    }
                    collector.append(label, text.toString());
                }
                if (pendingHighSurrogate != null) {
                    collector.append(label, String.valueOf(pendingHighSurrogate));
                }
            } catch (IOException ex) {
                // 读取进程输出失败时保留已有输出，由调用方根据退出码处理。
            }
        }, PROCESS_IO_EXECUTOR);
    }

    private void join(CompletableFuture<Void> future, ExecutionBudget cleanup) throws InterruptedException {
        try {
            future.get(cleanup.remaining().toNanos(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException | java.util.concurrent.TimeoutException ex) {
            // 超时或读取异常不阻断进程结果返回。
        }
    }

    private static final class OutputCollector {
        private final int limit;
        private final AtomicInteger total = new AtomicInteger();
        private final StringBuilder stdout = new StringBuilder();
        private final StringBuilder stderr = new StringBuilder();
        private final List<String> merged = new ArrayList<>();

        private OutputCollector(int limit) {
            this.limit = limit;
        }

        private synchronized void append(String label, String value) {
            int current = total.get();
            if (current >= limit || value.isEmpty()) {
                return;
            }
            StringBuilder accepted = new StringBuilder(value.length());
            int acceptedBytes = 0;
            for (int offset = 0; offset < value.length();) {
                int codePoint = value.codePointAt(offset);
                String character = new String(Character.toChars(codePoint));
                int characterBytes = character.getBytes(StandardCharsets.UTF_8).length;
                if (current + acceptedBytes + characterBytes > limit) {
                    break;
                }
                accepted.append(character);
                acceptedBytes += characterBytes;
                offset += Character.charCount(codePoint);
            }
            if (acceptedBytes == 0) {
                return;
            }
            total.addAndGet(acceptedBytes);
            String text = accepted.toString();
            if ("STDOUT".equals(label)) {
                stdout.append(text);
            } else {
                stderr.append(text);
            }
            merged.add("[" + label + "] " + text);
        }

        private synchronized void appendSystemError(String message) {
            stderr.append(message);
            merged.add("[STDERR] " + message);
        }

        private synchronized String stdout() {
            return stdout.toString();
        }

        private synchronized String stderr() {
            return stderr.toString();
        }

        private synchronized String mergedOutput() {
            return String.join("", merged);
        }
    }
}
