package cn.rvport.backend.source.deploy;

import cn.rvport.backend.credential.CredentialService;
import cn.rvport.backend.environment.EnvironmentRepository;
import cn.rvport.backend.environment.EnvironmentView;
import cn.rvport.backend.environment.runtime.SshClientAdapter;
import cn.rvport.backend.environment.runtime.SshCommandRequest;
import cn.rvport.backend.environment.runtime.SshCommandResult;
import cn.rvport.backend.environment.runtime.SshConnectionTarget;
import cn.rvport.backend.environment.runtime.SshRuntimeCredential;
import cn.rvport.backend.environment.runtime.SshRuntimeException;
import cn.rvport.backend.source.SourceRepository;
import cn.rvport.backend.source.SourceRevisionView;
import cn.rvport.backend.task.TaskErrorCode;
import cn.rvport.backend.task.TaskExecutionException;
import cn.rvport.backend.task.TaskLogType;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.task.ToolRunRepository;
import cn.rvport.backend.worker.TaskCancelledException;
import cn.rvport.backend.worker.TaskExecutionContext;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.springframework.stereotype.Component;

@Component
public class SourceDeployExecutor {

    private static final String TOOL_NAME = "rvport-source-deploy";
    private static final String TOOL_VERSION = "1";

    private final SourceDeployRepository sourceDeployRepository;
    private final SourceRepository sourceRepository;
    private final EnvironmentRepository environmentRepository;
    private final CredentialService credentialService;
    private final SshClientAdapter sshClientAdapter;
    private final ToolRunRepository toolRunRepository;

    public SourceDeployExecutor(
            SourceDeployRepository sourceDeployRepository,
            SourceRepository sourceRepository,
            EnvironmentRepository environmentRepository,
            CredentialService credentialService,
            SshClientAdapter sshClientAdapter,
            ToolRunRepository toolRunRepository
    ) {
        this.sourceDeployRepository = sourceDeployRepository;
        this.sourceRepository = sourceRepository;
        this.environmentRepository = environmentRepository;
        this.credentialService = credentialService;
        this.sshClientAdapter = sshClientAdapter;
        this.toolRunRepository = toolRunRepository;
    }

    public SourceDeployRunView deployExisting(SourceDeployTaskInput input, TaskExecutionContext context) {
        SourceDeployRunView run = sourceDeployRepository.findById(input.sourceDeploymentId())
                .orElseThrow(() -> new TaskExecutionException(TaskErrorCode.RESOURCE_NOT_FOUND, "源码部署记录不存在", null));
        SourceDeployExecutionRequest request = new SourceDeployExecutionRequest(
                input.projectId(),
                context.task().id(),
                input.sourceDeploymentId(),
                input.sourceRevisionId(),
                input.environmentId(),
                input.environmentConfigVersion(),
                run.remotePath(),
                input.force(),
                input.timeoutSeconds()
        );
        validateReuse(run, request, context, true);
        return deployRun(run, request, context);
    }

    public SourceDeployRunView ensureDeployed(SourceDeployExecutionRequest request, TaskExecutionContext context) {
        validateTask(request, context);
        // 同一任务重试时 task_id 唯一键会与上次（含 FAILED）的 deploy run 冲突——先按任务查回并复用，
        // 重新部署到同一 remote_path 是幂等操作。
        if (!request.force()) {
            var byTask = sourceDeployRepository.findByTaskId(request.taskId());
            if (byTask.isPresent()) {
                validateReuse(byTask.get(), request, context, true);
                if (byTask.get().status() == TaskStatus.SUCCEEDED) return byTask.get();
                context.log(TaskLogType.TOOL, "复用本任务既有部署记录（重试）：" + byTask.get().id());
                return deployRun(byTask.get(), request, context);
            }
        }
        if (request.sourceDeploymentId() != null) {
            SourceDeployRunView run = sourceDeployRepository.findById(request.sourceDeploymentId())
                    .orElseThrow(() -> new TaskExecutionException(TaskErrorCode.RESOURCE_NOT_FOUND, "源码部署记录不存在", null));
            validateReuse(run, request, context, run.status() != TaskStatus.SUCCEEDED);
            if (!request.force() && run.status() == TaskStatus.SUCCEEDED) {
                context.log(TaskLogType.TOOL, "复用源码部署记录：" + run.id());
                return run;
            }
        }
        if (!request.force()) {
            var reusable = sourceDeployRepository.findLatestSucceeded(
                    request.projectId(),
                    request.sourceRevisionId(),
                    request.environmentId(),
                    request.environmentConfigVersionValue()
            );
            if (reusable.isPresent()) {
                validateReuse(reusable.get(), request, context, false);
                context.log(TaskLogType.TOOL, "复用源码部署记录：" + reusable.get().id());
                return reusable.get();
            }
        }

        SourceRevisionView sourceRevision = requireSourceRevision(request);
        EnvironmentView environment = requireEnvironment(request);
        String remotePath = effectiveRemotePath(request, environment, sourceRevision.id());
        SourceDeployRunView run = context.publish(() -> sourceDeployRepository.createPending(
                UUID.randomUUID(),
                request.projectId(),
                request.taskId(),
                sourceRevision.id(),
                environment.id(),
                request.environmentConfigVersionValue(),
                remotePath,
                request.force()
        ));
        return deployRun(run, new SourceDeployExecutionRequest(request.projectId(), request.taskId(), run.id(),
                request.sourceRevisionId(), request.environmentId(), request.environmentConfigVersion(),
                remotePath, request.force(), request.timeoutSeconds()), context);
    }

    private SourceDeployRunView deployRun(SourceDeployRunView run, SourceDeployExecutionRequest request, TaskExecutionContext context) {
        // 在标记运行、创建工具记录、打包或 SSH 前校验；错误引用不能改写另一条记录。
        validateReuse(run, request, context, true);
        UUID toolRunId = context.publish(() -> {
        sourceDeployRepository.markRunning(run.id());
        return toolRunRepository.create(
                context.task().projectId(),
                context.task().id(),
                TOOL_NAME,
                TOOL_VERSION,
                "源码部署",
                TaskStatus.RUNNING,
                OffsetDateTime.now()
        );
        });
        Path archive = null;
        try {
            context.progress(10, "开始源码部署");
            context.throwIfCancellationRequested();
            SourceRevisionView sourceRevision = requireSourceRevision(request);
            EnvironmentView environment = requireEnvironment(request);
            EnvironmentView jumpHost = requireJumpHost(environment);
            Path sourceRoot = validateWorkingCopy(sourceRevision);

            context.progress(25, "正在打包源码");
            SourceArchive archiveInfo = createArchive(sourceRoot);
            archive = archiveInfo.path();
            context.log(TaskLogType.TOOL, "源码打包完成，文件数：" + archiveInfo.fileCount() + "，字节数：" + archiveInfo.totalBytes());

            context.progress(50, "正在传输源码到远端环境");
            SshCommandResult result;
            try (InputStream inputStream = Files.newInputStream(archive)) {
                result = sshClientAdapter.execute(new SshCommandRequest(
                        target(environment),
                        jumpHost == null ? null : target(jumpHost),
                        deployCommand(run.remotePath()),
                        null,
                        inputStream,
                        false,
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(effectiveTimeout(request.timeoutSeconds())),
                        context
                ));
            }
            context.log(TaskLogType.STDOUT, result.stdout());
            context.log(TaskLogType.STDERR, result.stderr());
            if (result.exitCode() != 0) {
                String code = containsTarMissing(result) ? "SOURCE_DEPLOY_REMOTE_TAR_REQUIRED" : TaskErrorCode.REMOTE_COMMAND_FAILED;
                fail(context, run.id(), toolRunId, result.exitCode(), code, "源码部署远端命令执行失败", archiveInfo, result);
            }
            Map<String, Object> summary = summary(archiveInfo, result, run.remotePath());
            context.publish(() -> {
                toolRunRepository.finish(toolRunId, TaskStatus.SUCCEEDED, result.exitCode(), summary);
                sourceDeployRepository.markFinished(run.id(), TaskStatus.SUCCEEDED, archiveInfo.fileCount(), archiveInfo.totalBytes(), result.exitCode(), summary);
                return null;
            });
            context.progress(60, "源码部署完成");
            return sourceDeployRepository.findById(run.id()).orElse(run);
        } catch (TaskCancelledException ex) {
            finishFailed(context, run.id(), toolRunId, TaskStatus.CANCELLED, Map.of("error", "任务已取消"));
            throw ex;
        } catch (TaskExecutionException ex) {
            finishFailed(context, run.id(), toolRunId, TaskStatus.FAILED, Map.of("error", ex.safeMessage(), "errorCode", ex.errorCode()));
            throw ex;
        } catch (SshRuntimeException ex) {
            finishFailed(context, run.id(), toolRunId, TaskStatus.FAILED, Map.of("error", "源码部署 SSH 执行失败"));
            throw new TaskExecutionException(ex.errorCode() == null ? TaskErrorCode.REMOTE_COMMAND_FAILED : ex.errorCode(), "源码部署 SSH 执行失败", ex);
        } catch (IOException ex) {
            finishFailed(context, run.id(), toolRunId, TaskStatus.FAILED, Map.of("error", "源码部署本机文件处理失败"));
            throw new TaskExecutionException(TaskErrorCode.HANDLER_FAILED, "源码部署本机文件处理失败", ex);
        } finally {
            deleteIfExists(archive);
        }
    }

    private void validateRunMatches(SourceDeployRunView run, SourceDeployExecutionRequest request) {
        if (!Objects.equals(run.projectId(), request.projectId())
                || !Objects.equals(run.sourceRevisionId(), request.sourceRevisionId())
                || !Objects.equals(run.environmentId(), request.environmentId())
                || !Objects.equals(run.environmentConfigVersion(), request.environmentConfigVersionValue())
                || (request.sourceDeploymentId() != null && !request.sourceDeploymentId().equals(run.id()))) {
            throw new TaskExecutionException(TaskErrorCode.PROJECT_MISMATCH, "源码部署记录与当前任务不一致", null);
        }
    }

    private void validateTask(SourceDeployExecutionRequest request, TaskExecutionContext context) {
        var task = context.task();
        if (task == null || !Objects.equals(task.id(), request.taskId())
                || !Objects.equals(task.projectId(), request.projectId())) {
            throw new TaskExecutionException(TaskErrorCode.PROJECT_MISMATCH,
                    "源码部署请求与当前执行任务不一致 / Deployment request does not match the executing task", null);
        }
    }

    private void validateReuse(SourceDeployRunView run, SourceDeployExecutionRequest request,
            TaskExecutionContext context, boolean sameTask) {
        validateTask(request, context);
        validateRunMatches(run, request);
        if (sameTask && !Objects.equals(run.taskId(), request.taskId())) {
            throw new TaskExecutionException(TaskErrorCode.PROJECT_MISMATCH,
                    "源码部署任务关联不一致 / Deployment task association does not match", null);
        }
        SourceRevisionView source = requireSourceRevision(request);
        EnvironmentView environment = requireEnvironment(request);
        String expected = effectiveRemotePath(request, environment, source.id());
        if (!Objects.equals(run.remotePath(), expected)) {
            throw new TaskExecutionException("SOURCE_DEPLOY_PATH_MISMATCH",
                    "源码部署路径不符合当前输入 / Deployment path does not match the current input", null);
        }
        // 复用成功缓存也需经过现有租约守卫；不把外部操作放入 publish 事务。
        context.publish(() -> null);
    }

    private SourceRevisionView requireSourceRevision(SourceDeployExecutionRequest request) {
        SourceRevisionView revision = sourceRepository.findById(request.sourceRevisionId())
                .orElseThrow(() -> new TaskExecutionException(TaskErrorCode.RESOURCE_NOT_FOUND, "源码版本不存在", null));
        if (!revision.projectId().equals(request.projectId()) || !revision.id().equals(request.sourceRevisionId())) {
            throw new TaskExecutionException(TaskErrorCode.PROJECT_MISMATCH, "源码版本不属于当前项目", null);
        }
        return revision;
    }

    private EnvironmentView requireEnvironment(SourceDeployExecutionRequest request) {
        EnvironmentView environment = environmentRepository.findById(request.environmentId())
                .orElseThrow(() -> new TaskExecutionException(TaskErrorCode.RESOURCE_NOT_FOUND, "环境不存在", null));
        if (!environment.projectId().equals(request.projectId()) || !environment.id().equals(request.environmentId())) {
            throw new TaskExecutionException(TaskErrorCode.PROJECT_MISMATCH, "环境不属于当前项目", null);
        }
        if (!environment.enabled()) {
            throw new TaskExecutionException(TaskErrorCode.RESOURCE_DISABLED, "环境已禁用", null);
        }
        if (environment.workingDirectory() == null || environment.workingDirectory().isBlank()) {
            throw new TaskExecutionException("ENVIRONMENT_WORKING_DIRECTORY_REQUIRED", "环境工作目录不能为空", null);
        }
        return environment;
    }

    private EnvironmentView requireJumpHost(EnvironmentView environment) {
        if (environment.jumpHostEnvironmentId() == null) {
            return null;
        }
        EnvironmentView jumpHost = environmentRepository.findById(environment.jumpHostEnvironmentId())
                .orElseThrow(() -> new TaskExecutionException(TaskErrorCode.RESOURCE_NOT_FOUND, "跳板机环境不存在", null));
        if (!jumpHost.projectId().equals(environment.projectId())) {
            throw new TaskExecutionException(TaskErrorCode.PROJECT_MISMATCH, "跳板机不属于当前项目", null);
        }
        if (!jumpHost.enabled()) {
            throw new TaskExecutionException(TaskErrorCode.RESOURCE_DISABLED, "跳板机已禁用", null);
        }
        if (jumpHost.jumpHostEnvironmentId() != null) {
            throw new TaskExecutionException(TaskErrorCode.UNSUPPORTED_OPERATION, "不支持多级跳板机", null);
        }
        return jumpHost;
    }

    private Path validateWorkingCopy(SourceRevisionView sourceRevision) {
        if (sourceRevision.workingCopyPath() == null || sourceRevision.workingCopyPath().isBlank()) {
            throw new TaskExecutionException(TaskErrorCode.RESOURCE_NOT_FOUND, "源码工作副本不存在", null);
        }
        Path path = Path.of(sourceRevision.workingCopyPath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            throw new TaskExecutionException(TaskErrorCode.RESOURCE_NOT_FOUND, "源码工作副本不存在", null);
        }
        return path;
    }

    private String effectiveRemotePath(SourceDeployExecutionRequest request, EnvironmentView environment, UUID sourceRevisionId) {
        String root = environment.workingDirectory().replace('\\', '/').replaceAll("/+$", "");
        if ((!root.isEmpty() && !root.startsWith("/")) || root.contains(":")
                || root.chars().anyMatch(Character::isISOControl)
                || java.util.Arrays.stream(root.split("/", -1)).skip(1)
                    .anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))) {
            throw new TaskExecutionException("SOURCE_DEPLOY_PATH_INVALID",
                    "环境部署根目录无效 / Invalid environment deployment root", null);
        }
        String expected = root + "/rvport-sources/" + sourceRevisionId;
        if (request.remotePath() != null && !request.remotePath().isBlank()
                && !request.remotePath().equals(expected)) {
            throw new TaskExecutionException("SOURCE_DEPLOY_PATH_MISMATCH",
                    "源码部署路径不符合当前输入 / Deployment path does not match the current input", null);
        }
        return expected;
    }

    private SourceArchive createArchive(Path sourceRoot) throws IOException {
        Path archive = Files.createTempFile("rvport-source-deploy-", ".tar.gz");
        AtomicInteger fileCount = new AtomicInteger();
        AtomicLong totalBytes = new AtomicLong();
        try (OutputStream fileOut = new BufferedOutputStream(Files.newOutputStream(archive));
             GzipCompressorOutputStream gzipOut = new GzipCompressorOutputStream(fileOut);
             TarArchiveOutputStream tarOut = new TarArchiveOutputStream(gzipOut);
             var stream = Files.walk(sourceRoot)) {
            tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            stream.filter(path -> !path.equals(sourceRoot))
                    .forEach(path -> addEntry(sourceRoot, path, tarOut, fileCount, totalBytes));
        } catch (RuntimeException ex) {
            deleteIfExists(archive);
            if (ex.getCause() instanceof IOException io) {
                throw io;
            }
            throw ex;
        }
        return new SourceArchive(archive, fileCount.get(), totalBytes.get());
    }

    private void addEntry(Path sourceRoot, Path path, TarArchiveOutputStream tarOut, AtomicInteger fileCount, AtomicLong totalBytes) {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            String entryName = sourceRoot.relativize(path).toString().replace('\\', '/');
            TarArchiveEntry entry = new TarArchiveEntry(path.toFile(), entryName);
            tarOut.putArchiveEntry(entry);
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.copy(path, tarOut);
                fileCount.incrementAndGet();
                totalBytes.addAndGet(Files.size(path));
            }
            tarOut.closeArchiveEntry();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private SshConnectionTarget target(EnvironmentView environment) {
        if (environment.credentialId() == null) {
            throw new TaskExecutionException(TaskErrorCode.INPUT_INVALID, "SSH 凭据引用不存在", null);
        }
        CredentialService.SshRuntimeCredential credential = credentialService.sshRuntimeCredential(environment.credentialId(), environment.projectId());
        return new SshConnectionTarget(
                environment.id(),
                environment.host(),
                environment.sshPort(),
                environment.username(),
                environment.workingDirectory(),
                new SshRuntimeCredential(
                        credential.kind(),
                        credential.username(),
                        credential.password(),
                        credential.privateKey(),
                        credential.passphrase()
                )
        );
    }

    private String deployCommand(String remotePath) {
        String deployPath = shellQuote(remotePath);
        return """
                DEPLOY_PATH=__DEPLOY_PATH__
                TMP_PATH="${DEPLOY_PATH}.tmp.$$"
                cleanup() {
                  rm -rf "$TMP_PATH"
                }
                trap cleanup EXIT INT TERM
                if ! command -v tar >/dev/null 2>&1; then
                  echo "SOURCE_DEPLOY_REMOTE_TAR_REQUIRED" >&2
                  exit 127
                fi
                rm -rf "$TMP_PATH"
                mkdir -p "$TMP_PATH"
                tar -xzf - -C "$TMP_PATH"
                rm -rf "$DEPLOY_PATH"
                mv "$TMP_PATH" "$DEPLOY_PATH"
                trap - EXIT INT TERM
                printf 'SOURCE_DEPLOY=ok\\n'
                """.replace("__DEPLOY_PATH__", deployPath);
    }

    private void fail(
            TaskExecutionContext context,
            UUID runId,
            UUID toolRunId,
            int exitCode,
            String errorCode,
            String message,
            SourceArchive archiveInfo,
            SshCommandResult result
    ) {
        Map<String, Object> summary = summary(archiveInfo, result, null);
        summary.put("error", message);
        summary.put("errorCode", errorCode);
        context.publish(() -> {
            toolRunRepository.finish(toolRunId, TaskStatus.FAILED, exitCode, summary);
            sourceDeployRepository.markFinished(runId, TaskStatus.FAILED, archiveInfo.fileCount(), archiveInfo.totalBytes(), exitCode, summary);
            return null;
        });
        throw new TaskExecutionException(errorCode, message, null);
    }

    private void finishFailed(TaskExecutionContext context, UUID runId, UUID toolId, TaskStatus status, Map<String, Object> summary) {
        context.publish(() -> {
            sourceDeployRepository.markFinished(runId, status, null, null, null, summary);
            toolRunRepository.finish(toolId, status, null, summary);
            return null;
        });
    }

    private Map<String, Object> summary(SourceArchive archiveInfo, SshCommandResult result, String remotePath) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("deployStrategy", SourceDeployStrategy.TAR_OVER_SSH.name());
        summary.put("fileCount", archiveInfo.fileCount());
        summary.put("totalBytes", archiveInfo.totalBytes());
        summary.put("durationMs", result.duration().toMillis());
        summary.put("exitCode", result.exitCode());
        if (remotePath != null) {
            summary.put("remotePath", remotePath);
        }
        return summary;
    }

    private boolean containsTarMissing(SshCommandResult result) {
        return (result.stderr() != null && result.stderr().contains("SOURCE_DEPLOY_REMOTE_TAR_REQUIRED"))
                || (result.stdout() != null && result.stdout().contains("SOURCE_DEPLOY_REMOTE_TAR_REQUIRED"));
    }

    private int effectiveTimeout(Integer timeoutSeconds) {
        return timeoutSeconds == null ? 3600 : Math.max(10, timeoutSeconds);
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private void deleteIfExists(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件清理失败不覆盖主要任务结果。
        }
    }

    private record SourceArchive(Path path, int fileCount, long totalBytes) {
    }
}
