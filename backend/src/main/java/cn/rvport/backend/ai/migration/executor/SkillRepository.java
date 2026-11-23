package cn.rvport.backend.ai.migration.executor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 技能仓库（git 管理的宿主目录）：空目录初始化克隆、当前版本查询、拉取更新。
 *
 * <p>目录即 named volume 的挂载点（容器内只读挂载 /root/.pi/agent/skills）。
 * 版本对比语义：fetch 后 rev-list HEAD..origin/&lt;branch&gt; 计数 = 远端领先提交数；
 * diff --stat 摘要 = 文件变更规模。更新 = reset --hard origin/&lt;branch&gt;（前进到远端）。
 */
@Service
public class SkillRepository {

    private static final Logger log = LoggerFactory.getLogger(SkillRepository.class);

    private final Path skillsRoot;
    private final String remoteUrl;
    private final String branch;
    private final String proxy;
    private final GitCli git;

    @org.springframework.beans.factory.annotation.Autowired
    public SkillRepository(AiMigrationExecutorProperties executorProperties) {
        this(new SkillRepositoryProperties(Path.of(executorProperties.skillsRoot()),
                executorProperties.skillRepoUrl(), executorProperties.skillBranch(),
                executorProperties.skillRepoProxy()), new ProcessGitCli());
    }

    SkillRepository(SkillRepositoryProperties properties) {
        this(properties, new ProcessGitCli());
    }

    SkillRepository(SkillRepositoryProperties properties, GitCli git) {
        this.skillsRoot = properties.skillsRoot();
        this.remoteUrl = properties.remoteUrl();
        this.branch = properties.branch();
        this.proxy = properties.proxy();
        this.git = git;
    }

    /** 访问远端用：配置了代理则每次命令单独指定，不影响进程内的其它网络设置。 */
    private String[] withProxy(String... args) {
        if (proxy == null || proxy.isBlank()) {
            return args;
        }
        List<String> command = new ArrayList<>(args.length + 2);
        command.add("-c");
        command.add("http.proxy=" + proxy);
        command.addAll(List.of(args));
        return command.toArray(String[]::new);
    }

    /** 确保仓库就绪：目录空则克隆；幂等。 */
    public synchronized void ensureCloned() {
        if (Files.isRegularFile(skillsRoot.resolve(".git").resolve("HEAD"))) {
            return;
        }
        try {
            Files.createDirectories(skillsRoot.getParent());
            git.run(skillsRoot.getParent(), Duration.ofMinutes(10),
                    withProxy("clone", "--branch", branch, remoteUrl, skillsRoot.getFileName().toString()));
            log.info("skills cloned from {} into {}", remoteUrl, skillsRoot);
        } catch (IOException ex) {
            throw new IllegalStateException("SKILL_CLONE_FAILED: " + ex.getMessage(), ex);
        }
    }

    public synchronized SkillStatus status() {
        ensureCloned();
        String current = git.read(skillsRoot, "rev-parse", "HEAD");
        git.run(skillsRoot, Duration.ofMinutes(2), withProxy("fetch", "--force", "origin",
                branch + ":refs/remotes/origin/" + branch));
        String remote = git.read(skillsRoot, "rev-parse", "refs/remotes/origin/" + branch);
        int behind = Integer.parseInt(git.read(skillsRoot, "rev-list", "--count",
                "HEAD..refs/remotes/origin/" + branch).trim());
        String diffStat = behind > 0
                ? git.read(skillsRoot, "--no-pager", "diff", "--stat", "HEAD..refs/remotes/origin/" + branch)
                : "";
        List<String> skills = listSkills();
        return new SkillStatus(skills, current, remote, behind, clipStat(diffStat));
    }

    public synchronized SkillUpdateResult update() {
        ensureCloned();
        String before = git.read(skillsRoot, "rev-parse", "HEAD");
        git.run(skillsRoot, Duration.ofMinutes(2), withProxy("fetch", "--force", "origin",
                branch + ":refs/remotes/origin/" + branch));
        String after = git.read(skillsRoot, "rev-parse", "refs/remotes/origin/" + branch);
        int changed = Integer.parseInt(git.read(skillsRoot, "rev-list", "--count",
                "HEAD..refs/remotes/origin/" + branch).trim());
        if (changed == 0) {
            return new SkillUpdateResult(before, after, 0, 0, List.of());
        }
        String stat = git.read(skillsRoot, "--no-pager", "diff", "--stat",
                "HEAD..refs/remotes/origin/" + branch);
        int fileCount = (int) git.read(skillsRoot, "diff", "--name-only",
                "HEAD..refs/remotes/origin/" + branch)
                .lines().filter(l -> !l.isBlank()).count();
        git.run(skillsRoot, Duration.ofMinutes(1), "reset", "--hard", "refs/remotes/origin/" + branch);
        log.info("skills updated {} -> {} ({} files)", before, after, fileCount);
        return new SkillUpdateResult(before, after, changed, fileCount, parseStatLines(stat));
    }

    /** 顶层含 SKILL.md 的子目录 = 可用技能（riscv-migrate 等）。 */
    public List<String> listSkills() {
        if (!Files.isDirectory(skillsRoot)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        try (var stream = Files.list(skillsRoot)) {
            stream.filter(Files::isDirectory)
                    .filter(dir -> Files.isRegularFile(dir.resolve("SKILL.md")))
                    .forEach(dir -> names.add(dir.getFileName().toString()));
        } catch (IOException ex) {
            log.warn("skills listing failed: {}", ex.getMessage());
            return List.of();
        }
        return List.copyOf(names);
    }

    private static String clipStat(String stat) {
        if (stat == null || stat.length() <= 2000) {
            return stat == null ? "" : stat;
        }
        return stat.substring(0, 2000) + "...";
    }

    private static List<String> parseStatLines(String stat) {
        if (stat == null || stat.isBlank()) {
            return List.of();
        }
        return stat.lines().filter(l -> !l.isBlank()).limit(50).toList();
    }

    public record SkillStatus(
            List<String> skills,
            String currentRevision,
            String remoteRevision,
            int commitsBehind,
            String diffStatSummary) {
    }

    public record SkillUpdateResult(
            String beforeRevision,
            String afterRevision,
            int commitsChanged,
            int filesChanged,
            List<String> diffStatLines) {
    }

    /** git CLI 抽象（生产=进程，测试=stub）。 */
    interface GitCli {
        void run(Path workingDir, Duration timeout, String... args);

        String read(Path workingDir, String... args);
    }

    static final class ProcessGitCli implements GitCli {
        @Override
        public void run(Path workingDir, Duration timeout, String... args) {
            execute(workingDir, timeout, args);
        }

        @Override
        public String read(Path workingDir, String... args) {
            return execute(workingDir, Duration.ofMinutes(1), args);
        }

        private String execute(Path workingDir, Duration timeout, String... args) {
            List<String> command = new ArrayList<>(List.of("git"));
            if (!List.of(args).contains("--no-pager")) {
                command.add("--no-pager");
            }
            command.addAll(List.of(args));
            try {
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.directory(workingDir.toFile());
                builder.redirectErrorStream(true);
                Process process = builder.start();
                String output = new String(process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                if (!process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    throw new IllegalStateException("GIT_TIMEOUT: " + command);
                }
                if (process.exitValue() != 0) {
                    String clipped = output.length() > 2000 ? output.substring(0, 2000) : output;
                    throw new IllegalStateException("GIT_FAILED(" + process.exitValue() + "): " + clipped);
                }
                return output;
            } catch (IOException | InterruptedException ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("GIT_ERROR: " + ex.getMessage(), ex);
            }
        }
    }
}
