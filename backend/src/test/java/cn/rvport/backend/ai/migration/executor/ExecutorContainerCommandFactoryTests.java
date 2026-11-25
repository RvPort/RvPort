package cn.rvport.backend.ai.migration.executor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExecutorContainerCommandFactoryTests {

    private static final UUID TASK = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String SKILL_DIR = "/root/.pi/agent/skills";

    @Test
    void run命令包含全部契约要素() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("WXAI_API_KEY", "secret-value");
        env.put("WXAI_MODEL", "rch-eco-tool-glm");
        env.put("AI_MIGRATION_PROMPT_FILE", "/app/.prompt.txt");

        List<String> command = ExecutorContainerCommandFactory.runCommand(
                "rvport-ai-executor:local",
                ExecutorContainerCommandFactory.containerName(TASK),
                Path.of("/tmp/ws"),
                env,
                Map.of());

        assertThat(command).startsWith("docker", "run", "--rm", "--network", "host");
        assertThat(String.join(" ", command))
                .contains("--name rvport-executor-11111111-2222-3333-4444-555555555555")
                .contains("/tmp/ws:/app")
                .contains("-e WXAI_API_KEY=secret-value")
                .contains("-e WXAI_MODEL=rch-eco-tool-glm")
                .contains("-e http_proxy=")
                .contains("-e https_proxy=")
                .endsWith("rvport-ai-executor:local");
        // 镜像名必须是最后一个元素（容器命令无额外参数）
        assertThat(command.get(command.size() - 1)).isEqualTo("rvport-ai-executor:local");
    }

    @Test
    void 迁移技能随镜像内置因而不挂载任何技能卷() {
        // 技能不再经 named volume 装载：命令里不得出现技能目录的 bind mount，
        // 否则会用卷里的（可能过期的）技能盖住镜像内置的技能。
        Map<String, String> env = Map.of("K", "v");
        List<String> direct = ExecutorContainerCommandFactory.runCommand("img", "c1", Path.of("/w"), env, Map.of());
        List<String> withHost = ExecutorContainerCommandFactory.runCommand("img", "c1", Path.of("/w"), Path.of("/host/w"),
                env, Map.of());

        assertThat(direct).noneMatch(e -> e.contains(SKILL_DIR));
        assertThat(withHost).noneMatch(e -> e.contains(SKILL_DIR));
        // 唯一的 -v 挂载就是 workspace
        assertThat(withHost.stream().filter("-v"::equals).count()).isEqualTo(1L);
        assertThat(withHost).contains("/host/w:/app");
    }

    @Test
    void 代理键全部显式置空() {
        List<String> command = ExecutorContainerCommandFactory.runCommand(
                "img", "c1", Path.of("/w"), Map.of(), null);
        String joined = String.join(" ", command);
        for (String key : List.of("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY", "no_proxy", "NO_PROXY")) {
            assertThat(joined).contains("-e " + key + "=");
        }
    }

    @Test
    void kill与remove命令形态() {
        assertThat(ExecutorContainerCommandFactory.killCommand("c1"))
                .containsExactly("docker", "kill", "c1");
        assertThat(ExecutorContainerCommandFactory.removeCommand("c1"))
                .containsExactly("docker", "rm", "-f", "c1");
    }

    @Test
    void 默认配置兜底与非法值防御() {
        var props = new ExecutorContainerProperties(null, null);
        assertThat(props.image()).isEqualTo("rvport-ai-executor:local");
        assertThat(props.dockerTimeout()).isEqualTo(Duration.ofHours(2));

        var negative = new ExecutorContainerProperties("", Duration.ZERO);
        assertThat(negative.image()).isEqualTo("rvport-ai-executor:local");
        assertThat(negative.dockerTimeout()).isEqualTo(Duration.ofHours(2));

        var negativeTimeout = new ExecutorContainerProperties(null, Duration.ofSeconds(-5));
        assertThat(negativeTimeout.dockerTimeout()).isEqualTo(Duration.ofHours(2));

        var explicit = new ExecutorContainerProperties("custom:1", Duration.ofMinutes(30));
        assertThat(explicit.image()).isEqualTo("custom:1");
        assertThat(explicit.dockerTimeout()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void null值环境变量与代理变量分支() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("EMPTY_VAL", null);
        List<String> command = ExecutorContainerCommandFactory.runCommand(
                "img", "c", Path.of("/w"), env, Map.of("no_proxy", "internal"));
        String joined = String.join(" ", command);
        assertThat(joined).contains("-e EMPTY_VAL=");
        assertThat(joined).contains("-e no_proxy=internal");
    }

    @Test
    void 结果语义() {
        var ok = new ExecutorContainerResult(0, false, "o", "e", "o\ne");
        assertThat(ok.succeeded()).isTrue();
        var fail = new ExecutorContainerResult(1, false, "", "", "");
        assertThat(fail.succeeded()).isFalse();
        var timeout = new ExecutorContainerResult(-1, true, "", "", "");
        assertThat(timeout.succeeded()).isFalse();
    }
}
