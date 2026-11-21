package cn.rvport.backend.ai.migration.executor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AiMigrationExecutorPropertiesTests {

    @Test
    void 全部缺省兜底() {
        var props = new AiMigrationExecutorProperties(null, null, null, null, null, null, null, null, null, null);
        assertThat(props.executionMode()).isEqualTo("container");
        assertThat(props.image()).isEqualTo("rvport-ai-executor:local");
        assertThat(props.skillsVolume()).isEqualTo("rvport-ai-skills");
        assertThat(props.dockerTimeout()).isEqualTo(Duration.ofHours(2));
        assertThat(props.maxConcurrent()).isEqualTo(1);
        assertThat(props.skillRepoUrl()).contains("rvport-skills");
        assertThat(props.skillBranch()).isEqualTo("master");
        assertThat(props.skillsRoot()).isEqualTo("/var/lib/rvport/ai-skills");
        assertThat(props.workspaceRoot()).isEqualTo("/var/lib/rvport/ai-workspaces");
        // 未配置宿主根 → 与 workspaceRoot 相同
        assertThat(props.workspaceHostRoot()).isEqualTo(props.workspaceRoot());
        assertThat(props.containerMode()).isTrue();
        assertThat(props.validMode()).isTrue();
    }

    @Test
    void 空白与非法值兜底() {
        var props = new AiMigrationExecutorProperties("  ", "  ", "\t", Duration.ZERO, 0, " ", "", " ", "", null);
        assertThat(props.executionMode()).isEqualTo("container");
        assertThat(props.image()).isEqualTo("rvport-ai-executor:local");
        assertThat(props.skillsVolume()).isEqualTo("rvport-ai-skills");
        assertThat(props.dockerTimeout()).isEqualTo(Duration.ofHours(2));
        assertThat(props.maxConcurrent()).isEqualTo(1);
        assertThat(props.skillRepoUrl()).contains("rvport-skills");
        assertThat(props.skillBranch()).isEqualTo("master");
        assertThat(props.skillsRoot()).isEqualTo("/var/lib/rvport/ai-skills");
        assertThat(props.workspaceRoot()).isEqualTo("/var/lib/rvport/ai-workspaces");
    }

    @Test
    void 负超时与负并发兜底() {
        var negative = new AiMigrationExecutorProperties("process", "img:1", "vol", Duration.ofSeconds(-3), -5,
                "http://r", "dev", "/skills", "/ws", null);
        assertThat(negative.dockerTimeout()).isEqualTo(Duration.ofHours(2));
        assertThat(negative.maxConcurrent()).isEqualTo(1);
        assertThat(negative.containerMode()).isFalse();
        assertThat(negative.validMode()).isTrue();
    }

    @Test
    void 显式值全部保留且宿主根独立() {
        var explicit = new AiMigrationExecutorProperties("Container ", "my-image:2", "my-vol", Duration.ofMinutes(45),
                3, "http://skills", "release", "/s", "/w", "/host/w");
        // 模式大小写不敏感且去除首尾空白
        assertThat(executionModeTrimmed(explicit)).isEqualTo("Container");
        assertThat(explicit.containerMode()).isTrue();
        assertThat(explicit.image()).isEqualTo("my-image:2");
        assertThat(explicit.skillsVolume()).isEqualTo("my-vol");
        assertThat(explicit.dockerTimeout()).isEqualTo(Duration.ofMinutes(45));
        assertThat(explicit.maxConcurrent()).isEqualTo(3);
        assertThat(explicit.skillRepoUrl()).isEqualTo("http://skills");
        assertThat(explicit.skillBranch()).isEqualTo("release");
        assertThat(explicit.skillsRoot()).isEqualTo("/s");
        assertThat(explicit.workspaceRoot()).isEqualTo("/w");
        assertThat(explicit.workspaceHostRoot()).isEqualTo("/host/w");
    }

    @Test
    void 空白宿主根回落到workspaceRoot() {
        var props = new AiMigrationExecutorProperties("process", null, null, null, null, null, null, null, "/w", "  ");
        assertThat(props.workspaceHostRoot()).isEqualTo("/w");
    }

    @Test
    void 非法模式被识别() {
        var invalid = new AiMigrationExecutorProperties("weird", null, null, null, null, null, null, null, null, null);
        assertThat(invalid.validMode()).isFalse();
        assertThat(invalid.containerMode()).isFalse();
    }

    @Test
    void 工厂方法构造() {
        var byFactory = AiMigrationExecutorProperties.forMode("process", "/w", null);
        assertThat(byFactory.executionMode()).isEqualTo("process");
        assertThat(byFactory.workspaceRoot()).isEqualTo("/w");
        assertThat(byFactory.workspaceHostRoot()).isEqualTo("/w");
    }

    private static String executionModeTrimmed(AiMigrationExecutorProperties props) {
        return props.executionMode();
    }
}
