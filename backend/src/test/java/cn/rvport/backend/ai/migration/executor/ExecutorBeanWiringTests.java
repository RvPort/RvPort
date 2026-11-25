package cn.rvport.backend.ai.migration.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import cn.rvport.backend.audit.AuditLogService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Spring 装配冒烟：这些 bean 有多个构造器（测试便捷重载），必须由 @Autowired 明确标注注入入口；
 * 构造参数必须是容器内可解析的类型（统一走 AiMigrationExecutorProperties）。
 * 单元测试手动 new 发现不了装配错误——本测试在单测阶段兜住"启动即崩"。
 *
 * <p>迁移技能随执行器镜像内置，不再有技能仓库/技能管理相关的 bean。
 */
class ExecutorBeanWiringTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(AiMigrationExecutorProperties.class,
                    () -> AiMigrationExecutorProperties.forMode("process", null, null))
            .withBean(AuditLogService.class, () -> mock(AuditLogService.class))
            .withUserConfiguration(ExecutorContainerService.class);

    @Test
    void 执行器服务可被容器装配() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ExecutorContainerService.class);
        });
    }

    @Test
    void 执行器服务读取统一配置且不依赖技能卷配置() {
        new ApplicationContextRunner()
                .withBean(AiMigrationExecutorProperties.class, () -> new AiMigrationExecutorProperties(
                        "container", "my-img:1", null, null, null, null, null, null, null, "/w", "/hw"))
                .withBean(AuditLogService.class, () -> mock(AuditLogService.class))
                .withUserConfiguration(ExecutorContainerService.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // 仅给出镜像与 workspace 配置（无任何技能配置）即可构造：技能来自镜像，不来自配置
                    assertThat(context.getBean(ExecutorContainerService.class)).isNotNull();
                });
    }
}
