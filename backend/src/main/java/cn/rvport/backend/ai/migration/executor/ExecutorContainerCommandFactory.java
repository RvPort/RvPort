package cn.rvport.backend.ai.migration.executor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 执行器容器 docker run 命令的纯拼装逻辑（可单测，不触碰 docker）。
 *
 * <p>契约见 add-ai-migration-executor design E2：一次性容器（--rm）、任务唯一命名、
 * workspace bind mount 到 /app、技能 volume 只读挂载、凭据经 -e 显式传值、
 * 代理环境置空（wxai 直连、QEMU/编译不涉外网）。
 */
public final class ExecutorContainerCommandFactory {

    static final String SKILL_MOUNT_TARGET = "/root/.pi/agent/skills";
    static final String WORKSPACE_MOUNT_TARGET = "/app";

    private ExecutorContainerCommandFactory() {
    }

    public static List<String> runCommand(
            String image,
            String containerName,
            Path workspace,
            String skillsVolume,
            Map<String, String> environment,
            Map<String, String> proxyVariables) {
        return runCommand(image, containerName, workspace, workspace, skillsVolume, environment, proxyVariables);
    }

    /**
     * @param workspace       worker 容器内的 workspace 路径（用于命名/日志）
     * @param hostWorkspace   docker daemon 可见的宿主路径（bind mount 左值）；worker 直接跑在宿主时与 workspace 相同
     */
    public static List<String> runCommand(
            String image,
            String containerName,
            Path workspace,
            Path hostWorkspace,
            String skillsVolume,
            Map<String, String> environment,
            Map<String, String> proxyVariables) {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.add("run");
        command.add("--rm");
        command.add("--network");
        command.add("host");
        command.add("--name");
        command.add(containerName);
        command.add("-v");
        command.add(hostWorkspace.toAbsolutePath().normalize() + ":" + WORKSPACE_MOUNT_TARGET);
        if (skillsVolume != null && !skillsVolume.isBlank()) {
            command.add("-v");
            command.add(skillsVolume + ":" + SKILL_MOUNT_TARGET + ":ro");
        }
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            command.add("-e");
            command.add(entry.getKey() + "=" + (entry.getValue() == null ? "" : entry.getValue()));
        }
        // 代理置空：覆盖 daemon 注入的 http_proxy/https_proxy（内网直连语义）。
        for (String proxyKey : List.of("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY", "no_proxy",
                "NO_PROXY")) {
            command.add("-e");
            command.add(proxyKey + "=" + (proxyVariables == null ? "" : proxyVariables.getOrDefault(proxyKey, "")));
        }
        command.add(image);
        return List.copyOf(command);
    }

    public static List<String> killCommand(String containerName) {
        return List.of("docker", "kill", containerName);
    }

    public static List<String> removeCommand(String containerName) {
        return List.of("docker", "rm", "-f", containerName);
    }

    public static String containerName(java.util.UUID taskId) {
        return "rvport-executor-" + taskId;
    }
}
