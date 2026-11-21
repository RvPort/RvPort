package cn.rvport.backend.ai.migration.executor;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 迁移执行器配置（rvport.ai-migration.executor.*）。
 *
 * <p>execution-mode：container（默认，一次性执行器容器）或 process（一期 worker 子进程回退）。
 * 两模式产出同一套产物合同，切换不涉及数据迁移。
 */
@ConfigurationProperties(prefix = "rvport.ai-migration.executor")
public record AiMigrationExecutorProperties(
        String executionMode,
        String image,
        String skillsVolume,
        Duration dockerTimeout,
        Integer maxConcurrent,
        String skillRepoUrl,
        String skillBranch,
        String skillsRoot,
        String workspaceRoot,
        String workspaceHostRoot) {

    public AiMigrationExecutorProperties {
        executionMode = executionMode == null || executionMode.isBlank() ? "container" : executionMode.trim();
        image = image == null || image.isBlank() ? "rvport-ai-executor:local" : image;
        skillsVolume = skillsVolume == null || skillsVolume.isBlank() ? "rvport-ai-skills" : skillsVolume;
        dockerTimeout = dockerTimeout == null || dockerTimeout.isZero() || dockerTimeout.isNegative()
                ? Duration.ofHours(2)
                : dockerTimeout;
        maxConcurrent = maxConcurrent == null || maxConcurrent < 1 ? 1 : maxConcurrent;
        skillRepoUrl = skillRepoUrl == null || skillRepoUrl.isBlank()
                ? "https://github.com/zte-riscv/rvport-skills.git"
                : skillRepoUrl;
        skillBranch = skillBranch == null || skillBranch.isBlank() ? "master" : skillBranch;
        skillsRoot = skillsRoot == null || skillsRoot.isBlank()
                ? "/var/lib/rvport/ai-skills"
                : skillsRoot;
        workspaceRoot = workspaceRoot == null || workspaceRoot.isBlank()
                ? "/var/lib/rvport/ai-workspaces"
                : workspaceRoot;
        // 宿主侧路径：worker 容器把 workspace 建在 workspaceRoot 下；docker daemon 解析 bind mount
        // 时看到的是宿主路径，二者仅在 compose 把同一宿主目录挂到相同容器路径时相等。
        workspaceHostRoot = workspaceHostRoot == null || workspaceHostRoot.isBlank()
                ? workspaceRoot
                : workspaceHostRoot;
    }

    public boolean containerMode() {
        return "container".equalsIgnoreCase(executionMode);
    }

    /** 测试与回退场景的便捷构造：仅指定模式与 workspace 根（宿主根缺省同 workspace 根）。 */
    public static AiMigrationExecutorProperties forMode(String mode, String workspaceRoot, String workspaceHostRoot) {
        return new AiMigrationExecutorProperties(mode, null, null, null, null, null, null, null,
                workspaceRoot, workspaceHostRoot);
    }

    public boolean validMode() {
        return containerMode() || "process".equalsIgnoreCase(executionMode);
    }
}
