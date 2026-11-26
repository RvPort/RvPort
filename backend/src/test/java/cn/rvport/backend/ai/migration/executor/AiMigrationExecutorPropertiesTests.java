package cn.rvport.backend.ai.migration.executor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 执行器配置的兜底规则。技能随镜像内置，本配置不再含技能仓库/技能卷相关项。 */
class AiMigrationExecutorPropertiesTests {

    @Test
    void 全部缺省兜底() {
        var props = new AiMigrationExecutorProperties(null, null, null, null, null, null, null, null, null, null, null);
        assertThat(props.executionMode()).isEqualTo("container");
        assertThat(props.image()).isEqualTo("rvport-ai-executor:local");
        assertThat(props.dockerTimeout()).isEqualTo(Duration.ofHours(2));
        assertThat(props.maxConcurrent()).isEqualTo(1);
        assertThat(props.workspaceRoot()).isEqualTo("/var/lib/rvport/ai-workspaces");
        // 未配置宿主根 → 与 workspaceRoot 相同
        assertThat(props.workspaceHostRoot()).isEqualTo(props.workspaceRoot());
        assertThat(props.containerMode()).isTrue();
        assertThat(props.validMode()).isTrue();
    }

    @Test
    void 空白与非法值兜底() {
        var props = new AiMigrationExecutorProperties("  ", "  ", null, Duration.ZERO, 0, null, null, null, null, " ", "");
        assertThat(props.executionMode()).isEqualTo("container");
        assertThat(props.image()).isEqualTo("rvport-ai-executor:local");
        assertThat(props.dockerTimeout()).isEqualTo(Duration.ofHours(2));
        assertThat(props.maxConcurrent()).isEqualTo(1);
        assertThat(props.workspaceRoot()).isEqualTo("/var/lib/rvport/ai-workspaces");
    }

    @Test
    void 负超时与负并发兜底() {
        var negative = new AiMigrationExecutorProperties("process", "img:1", null, Duration.ofSeconds(-3), -5, null, null, null, null, "/ws", null);
        assertThat(negative.dockerTimeout()).isEqualTo(Duration.ofHours(2));
        assertThat(negative.maxConcurrent()).isEqualTo(1);
        assertThat(negative.containerMode()).isFalse();
        assertThat(negative.validMode()).isTrue();
    }

    @Test
    void 显式值全部保留且宿主根独立() {
        var explicit = new AiMigrationExecutorProperties("Container ", "my-image:2", null, Duration.ofMinutes(45), 3, null, null, null, null, "/w", "/host/w");
        // 模式大小写不敏感且去除首尾空白
        assertThat(explicit.executionMode()).isEqualTo("Container");
        assertThat(explicit.containerMode()).isTrue();
        assertThat(explicit.image()).isEqualTo("my-image:2");
        assertThat(explicit.dockerTimeout()).isEqualTo(Duration.ofMinutes(45));
        assertThat(explicit.maxConcurrent()).isEqualTo(3);
        assertThat(explicit.workspaceRoot()).isEqualTo("/w");
        assertThat(explicit.workspaceHostRoot()).isEqualTo("/host/w");
    }

    @Test
    void 空白宿主根回落到workspaceRoot() {
        var props = new AiMigrationExecutorProperties("process", null, null, null, null, null, null, null, null, "/w", "  ");
        assertThat(props.workspaceHostRoot()).isEqualTo("/w");
    }

    @Test
    void 非法模式被识别() {
        var invalid = new AiMigrationExecutorProperties("weird", null, null, null, null, null, null, null, null, null, null);
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

    @Test
    void 技能仓库与代理配置显式保留且空白回退() {
        var blank = new AiMigrationExecutorProperties("process", "img", "vol", Duration.ofSeconds(30), 2,
                " ", "", "  ", " ", "/ws", null);
        assertThat(blank.skillRepoUrl()).isEqualTo("https://github.com/zte-riscv/rvport-skills.git");
        assertThat(blank.skillBranch()).isEqualTo("master");
        assertThat(blank.skillsRoot()).isEqualTo("/var/lib/rvport/ai-skills");
        assertThat(blank.skillsProxy()).isEmpty();
        assertThat(blank.workspaceHostRoot()).isEqualTo("/ws");

        var explicit = new AiMigrationExecutorProperties("process", "img", "vol", Duration.ofSeconds(30), 2,
                "https://example.com/rvport-skills.git", "release-1", "/opt/skills", "  http://p  ", "/ws", "/host/ws");
        assertThat(explicit.skillRepoUrl()).isEqualTo("https://example.com/rvport-skills.git");
        assertThat(explicit.skillBranch()).isEqualTo("release-1");
        assertThat(explicit.skillsRoot()).isEqualTo("/opt/skills");
        assertThat(explicit.skillsProxy()).isEqualTo("http://p");
        assertThat(explicit.workspaceHostRoot()).isEqualTo("/host/ws");

        // null 代理与 null 宿主根路径：代理空串、宿主根回落 workspace 根
        var nulls = new AiMigrationExecutorProperties("process", "img", "vol", Duration.ofSeconds(30), 2,
                "u", "b", "/r", null, "/ws", null);
        assertThat(nulls.skillsProxy()).isEmpty();
        assertThat(nulls.workspaceHostRoot()).isEqualTo("/ws");
    }
}
