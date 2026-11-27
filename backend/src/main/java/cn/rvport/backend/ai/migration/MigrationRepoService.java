package cn.rvport.backend.ai.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 迁移运行的任务级 git 仓库操作：init、结构化提交、marker 强制留痕、reflog 历史读取。
 *
 * <p>提交 message 采用白名单化四元组（对标上游 rvport-web codemig 的 Save 语义）：
 * {@code Save type:{type} stage:{stage} src:{src} reason:{reason}}——把「谁在何时做了什么」
 * 变成可解析的数据，代码改动与运行事件（启动/结束/成功/失败/取消/回滚）混排在同一条历史上。
 *
 * <p>提交统一由 worker 侧执行（执行器容器内不跑 git），git 身份固定为框架标识
 * （提交身份 ≠ 操作者，操作者走审计日志）。命令经 {@link GitCli} 薄封装直调 git CLI，
 * 不引入 GitPython 类依赖。
 */
@org.springframework.stereotype.Service
public final class MigrationRepoService {

    /** 提交白名单：type（谁改的：人工基线 / agent / 事件 marker）。 */
    static final Set<String> TYPE_WHITELIST = Set.of("manual", "ai", "marker");
    /** 提交白名单：stage（生命周期位置）。 */
    static final Set<String> STAGE_WHITELIST = Set.of("init", "before", "after", "start", "end", "marker");
    /** 提交白名单：src（触发来源模块）。 */
    static final Set<String> SRC_WHITELIST = Set.of("rvport-agent", "rvport-init", "rvport-boundary");
    /**
     * 提交白名单：reason（具体动作语义）。migration-rollback 不在常规集合内，
     * 由回滚 API 专用（marker/marker/rvport-boundary/migration-rollback）。
     */
    static final Set<String> REASON_WHITELIST = Set.of(
            "first-init", "agent-start", "agent-finish",
            "intermediate-user-stop", "intermediate-error",
            "stage-end-success", "stage-end-error", "stage-end-cancel");

    /** 结构化 message 首行正则（解析与校验共用）。 */
    static final Pattern MESSAGE_PATTERN = Pattern.compile(
            "^Save type:(?<type>manual|ai|marker)\\s+"
                    + "stage:(?<stage>init|before|after|start|end|marker)\\s+"
                    + "src:(?<src>[a-z][a-z0-9-]+)\\s+"
                    + "reason:(?<reason>[A-Za-z0-9_-]{1,64})$");

    static final Duration GIT_TIMEOUT = Duration.ofMinutes(2);
    /** 提交身份：框架标识，不代表操作者（操作者走审计日志）。 */
    static final String COMMIT_USER_NAME = "rvport-worker";
    static final String COMMIT_USER_EMAIL = "rvport-worker@rvport.local";

    private final GitCli git;

    MigrationRepoService(GitCli git) {
        this.git = git;
    }

    /** Spring 构造：生产 git CLI（进程直调，no-pager、超时、退出码异常语义）。 */
    @org.springframework.beans.factory.annotation.Autowired
    public MigrationRepoService() {
        this(new ProcessGitCli());
    }

    /** 历史条目：reflog 提交点的解析结果。 */
    record HistoryEntry(String hash, String shortHash, String message, CommitLabel label,
            String time, boolean current) {
    }

    /** 四元组解析出的展示标签；unclassified 为无法解析的历史遗留提交兜底。 */
    record CommitLabel(String type, String stage, String src, String reason, String display, boolean structured) {
        static final CommitLabel UNCLASSIFIED =
                new CommitLabel(null, null, null, null, "未分类提交", false);
    }

    /** 工作区临时物：清理时删除，仓库与输入保留（设计 D7）。 */
    static final List<String> CLEANABLE_PATTERNS = List.of(".migrate_verify", ".prompt.txt");

    /** 仓库是否已初始化（.git 存在且 HEAD 可读）。 */
    boolean isInitialized(Path repo) {
        return Files.isDirectory(repo.resolve(".git")) && headHash(repo).isPresent();
    }

    /** 初始化仓库并做基线提交。幂等：已初始化的仓库直接返回。 */
    void initialize(Path repo) throws IOException, InterruptedException {
        if (isInitialized(repo)) {
            return;
        }
        Files.createDirectories(repo);
        git.run(repo, GIT_TIMEOUT, "init", "-q");
        configureIdentity(repo);
        writeGitignore(repo);
    }

    /**
     * 任务仓库根级 .gitignore：运行期文件（agent 提示词、结果输出、验证中间产物）不进提交，
     * 使 diff/patch series 只承载真实源码改动。锚定仓库根（前导 /），不影响源码树内同名目录。
     */
    private void writeGitignore(Path repo) throws IOException {
        Files.writeString(repo.resolve(".gitignore"), """
                /.prompt.txt
                /output/
                /.migrate_verify/
                """, java.nio.charset.StandardCharsets.UTF_8);
    }

    private void configureIdentity(Path repo) throws IOException, InterruptedException {
        git.run(repo, GIT_TIMEOUT, "config", "user.name", COMMIT_USER_NAME);
        git.run(repo, GIT_TIMEOUT, "config", "user.email", COMMIT_USER_EMAIL);
        // reflog 是历史查询的数据源，确保持久文件而非压缩链（默认已是文件形态，显式声明意图）。
        git.run(repo, GIT_TIMEOUT, "config", "core.logAllRefUpdates", "true");
        git.run(repo, GIT_TIMEOUT, "config", "gc.auto", "0");
    }

    /**
     * 结构化提交。force=true 时允许空提交（marker 语义：零改动也留事件锚点）。
     * 白名单外组合拒绝提交并以 {@code MIGRATION_REPO_COMMIT_REJECTED} 失败。
     */
    String commit(Path repo, String type, String stage, String src, String reason, boolean force)
            throws IOException, InterruptedException {
        String validation = validate(type, stage, src, reason);
        if (validation != null) {
            throw new IOException("MIGRATION_REPO_COMMIT_REJECTED: " + validation);
        }
        git.run(repo, GIT_TIMEOUT, "add", "-A");
        String message = "Save type:%s stage:%s src:%s reason:%s".formatted(type, stage, src, reason);
        if (force) {
            git.run(repo, GIT_TIMEOUT, "commit", "-q", "--allow-empty", "-m", message);
        } else {
            // 无改动时 git commit 失败（nothing to commit）——非 marker 路径视为成功跳过。
            String status = git.read(repo, "status", "--porcelain");
            if (status != null && !status.isBlank()) {
                git.run(repo, GIT_TIMEOUT, "commit", "-q", "-m", message);
            } else {
                return headHash(repo).orElseThrow(() -> new IOException("MIGRATION_REPO_COMMIT_NO_HEAD"));
            }
        }
        return headHash(repo).orElseThrow(() -> new IOException("MIGRATION_REPO_COMMIT_NO_HEAD"));
    }

    private String validate(String type, String stage, String src, String reason) {
        if (!TYPE_WHITELIST.contains(type)) {
            return "type '%s' not in whitelist".formatted(type);
        }
        if (!STAGE_WHITELIST.contains(stage)) {
            return "stage '%s' not in whitelist".formatted(stage);
        }
        if (!SRC_WHITELIST.contains(src)) {
            return "src '%s' not in whitelist".formatted(src);
        }
        if (!"rvport-boundary".equals(src) || !"migration-rollback".equals(reason)) {
            if (!REASON_WHITELIST.contains(reason)) {
                return "reason '%s' not in whitelist".formatted(reason);
            }
        }
        return null;
    }

    /**
     * 基线提交（first-init）：物化完成后打，包含 baseline/source 全量。
     * 幂等：已有 first-init 提交时直接返回该提交（不再打空提交）。
     */
    String commitFirstInit(Path repo) throws IOException, InterruptedException {
        String existing = firstInitHash(repo);
        if (existing != null) {
            return existing;
        }
        return commit(repo, "manual", "init", "rvport-init", "first-init", true);
    }

    /** 找到历史上首个 first-init 提交的 hash；无则 null。 */
    String firstInitHash(Path repo) {
        try {
            String hash = git.read(repo, "log", "--grep=^Save\\ type:manual\\ stage:init\\ src:rvport-init\\ reason:first-init$",
                    "--format=%H").trim();
            return hash.isBlank() ? null : hash;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** agent 启动前 marker（before/start 语义合并为 start）。 */
    String commitAgentStart(Path repo) throws IOException, InterruptedException {
        return commit(repo, "ai", "start", "rvport-agent", "agent-start", true);
    }

    /** agent 正常结束：含改动的实体提交（无改动时跳过，stage-end marker 兜底）。 */
    String commitAgentFinish(Path repo) throws IOException, InterruptedException {
        return commit(repo, "ai", "end", "rvport-agent", "agent-finish", false);
    }

    /** 终态 marker：成功/失败/取消，强制留痕（--allow-empty）。 */
    String commitStageEnd(Path repo, String reason) throws IOException, InterruptedException {
        return commit(repo, "marker", "marker", "rvport-boundary", reason, true);
    }

    /**
     * 重试前的中断标记：把上次半成品先入库再记中断。
     * force=true：上次改动即使已由 agent-finish 入库（工作区干净），重试这一事件本身也必须留痕。
     */
    String commitIntermediate(Path repo, String reason) throws IOException, InterruptedException {
        return commit(repo, "marker", "before", "rvport-boundary", reason, true);
    }

    /** 回滚 marker（专用 reason，绕过常规 reason 白名单）。 */
    String commitRollback(Path repo) throws IOException, InterruptedException {
        return commit(repo, "marker", "marker", "rvport-boundary", "migration-rollback", true);
    }

    Optional<String> headHash(Path repo) {
        try {
            // --verify -q：无提交的空仓库静默退出非零（输出空），视为未初始化而非异常。
            String hash = git.read(repo, "rev-parse", "--verify", "-q", "HEAD").trim();
            return hash.isBlank() ? Optional.empty() : Optional.of(hash);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    /**
     * 基线提交对象改写检测：基线 first-init 提交必须在 HEAD 祖先链上且其树未被改写。
     * 以「基线提交仍可达 + 基线树哈希不变」判定（commit 对象本身带树哈希，改写即失配）。
     */
    void verifyBaselineUntouched(Path repo, String baselineHash) throws IOException, InterruptedException {
        if (baselineHash == null || baselineHash.isBlank()) {
            throw new IOException("AI_MIGRATION_BASELINE_MODIFIED");
        }
        // 基线提交仍可达（被 reset --hard 弃掉的祖先会不可达）。
        try {
            String merged = git.read(repo, "merge-base", "--is-ancestor", baselineHash, "HEAD").trim();
            if (!merged.isBlank()) {
                throw new IOException("AI_MIGRATION_BASELINE_MODIFIED");
            }
        } catch (RuntimeException ex) {
            // merge-base --is-ancestor 以退出码表达结论（0=是祖先）；GitCli 把非零退出当异常。
            // 走到这里说明命令本身失败或基线不可达 —— is-ancestor 为真时输出为空且退出码 0。
            throw new IOException("AI_MIGRATION_BASELINE_MODIFIED");
        }
    }

    /** reflog 历史（时间倒序，最新在前；含被 reset 丢弃但未 gc 的提交）。 */
    List<HistoryEntry> history(Path repo, int maxCount) {
        // git reflog 输出格式：<hash> HEAD@{n}: <动作>: <message 首行>
        String raw;
        try {
            raw = git.read(repo, "reflog", "show", "HEAD", "--date=iso",
                    "--format=%H%x09%gd%x09%gs%x09%ad");
        } catch (RuntimeException ex) {
            return List.of();
        }
        String current = headHash(repo).orElse("");
        // reflog 中同一提交可能有多条（原始 commit 条目 + reset/checkout 条目）；
        // 按去重保留信息最全的一条：优先「message 可解析为结构化四元组」的条目，
        // 其次带 message 的，最后动作条目（如 reset: moving to）。
        record Candidate(String hash, String message, String time, boolean structured) {
        }
        Map<String, Candidate> best = new LinkedHashMap<>();
        for (String line : raw.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t", 4);
            if (parts.length < 4) {
                continue;
            }
            String hash = parts[0].trim();
            String message = messageOfReflogSubject(parts[2].trim());
            String time = parts[3].trim();
            boolean structured = parseLabel(message).structured();
            best.merge(hash, new Candidate(hash, message, time, structured), (a, b) ->
                    a.structured() && !b.structured() || a.structured() == b.structured()
                            ? a : b);
        }
        List<HistoryEntry> entries = new java.util.ArrayList<>();
        int rank = 0;
        for (Candidate candidate : best.values()) {
            if (rank++ >= maxCount) {
                break;
            }
            entries.add(new HistoryEntry(candidate.hash(),
                    candidate.hash().substring(0, Math.min(8, candidate.hash().length())),
                    candidate.message(), parseLabel(candidate.message()), candidate.time(),
                    candidate.hash().equals(current)));
        }
        return entries;
    }

    /** reflog %gs（如 "commit (initial): Save type:..."）提取冒号后的纯 message。 */
    static String messageOfReflogSubject(String subject) {
        if (subject == null) {
            return "";
        }
        int colon = subject.indexOf(": ");
        return colon >= 0 ? subject.substring(colon + 2).trim() : subject.trim();
    }

    /** 解析提交 message 首行为四元组标签；非结构化消息归为未分类。 */
    static CommitLabel parseLabel(String message) {
        if (message == null) {
            return CommitLabel.UNCLASSIFIED;
        }
        // 容忍 reflog 动作前缀（"commit: " / "commit (initial): " 等）——显示与解析以纯 message 为准
        String normalized = messageOfReflogSubject(message.trim());
        Matcher matcher = MESSAGE_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            return CommitLabel.UNCLASSIFIED;
        }
        String type = matcher.group("type");
        String stage = matcher.group("stage");
        String src = matcher.group("src");
        String reason = matcher.group("reason");
        return new CommitLabel(type, stage, src, reason, reasonDisplay(reason), true);
    }

    private static String reasonDisplay(String reason) {
        return switch (reason) {
            case "first-init" -> "基线初始化";
            case "agent-start" -> "agent 启动";
            case "agent-finish" -> "agent 完成";
            case "intermediate-user-stop" -> "用户中断";
            case "intermediate-error" -> "运行中断";
            case "stage-end-success" -> "运行成功";
            case "stage-end-error" -> "运行失败";
            case "stage-end-cancel" -> "运行取消";
            case "migration-rollback" -> "结果回滚";
            default -> reason;
        };
    }

    /**
     * 工作区清理（白名单式）：删除可再生临时物，保留 .git/source/baseline/input。
     */
    void cleanup(Path repo) throws IOException {
        for (String pattern : CLEANABLE_PATTERNS) {
            Path target = repo.resolve(pattern);
            if (Files.exists(target)) {
                deleteRecursively(target);
            }
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            List<Path> toDelete = paths.sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path p : toDelete) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** 运行时直读（供 diff 派生等命令输出消费方使用）。 */
    String gitRead(Path repo, String... args) {
        return git.read(repo, args);
    }

    /** 生产实现：进程直调 git CLI。 */
    static final class ProcessGitCli implements GitCli {
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

        private String execute(Path workingDir, Duration timeout, String... args)
                throws IOException, InterruptedException {
            String gitBin = System.getProperty("rvport.test.git.bin", "git");
            List<String> command = new ArrayList<>(List.of(gitBin));
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
                    // 进程被强制结束时流关闭，属正常路径
                }
            }, "migration-repo-git-drain");
            drain.setDaemon(true);
            drain.start();
            if (!process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                drain.join(1000);
                throw new IllegalStateException("GIT_TIMEOUT: " + command);
            }
            drain.join(1000);
            String output = buffered.toString(StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                String clipped = output.length() > 2000 ? output.substring(0, 2000) : output;
                throw new IllegalStateException("GIT_FAILED(" + process.exitValue() + "): " + clipped);
            }
            return output;
        }
    }

    /** git CLI 接缝（与 SkillRepository.GitCli 同语义；独立声明避免跨类耦合）。 */
    interface GitCli {
        void run(Path workingDir, Duration timeout, String... args) throws IOException, InterruptedException;

        String read(Path workingDir, String... args);
    }

    static String sha256FileMarker(UUID runId) {
        return "runs/" + runId + "/.git";
    }
}
