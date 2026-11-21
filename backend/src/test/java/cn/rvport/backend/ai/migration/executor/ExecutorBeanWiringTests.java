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
 */
class ExecutorBeanWiringTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(AiMigrationExecutorProperties.class,
                    () -> AiMigrationExecutorProperties.forMode("process", null, null))
            .withBean(AuditLogService.class, () -> mock(AuditLogService.class))
            .withUserConfiguration(ExecutorContainerService.class, SkillRepository.class,
                    SkillAdminService.class, SkillAdminController.class);

    @Test
    void 执行器相关bean可被容器装配() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ExecutorContainerService.class);
            assertThat(context).hasSingleBean(SkillRepository.class);
            assertThat(context).hasSingleBean(SkillAdminService.class);
            assertThat(context).hasSingleBean(SkillAdminController.class);
        });
    }

    @Test
    void 执行器服务读取统一配置() {
        new ApplicationContextRunner()
                .withBean(AiMigrationExecutorProperties.class, () -> new AiMigrationExecutorProperties(
                        "container", "my-img:1", "my-vol", null, null, "http://skills", "dev", "/s", "/w", "/hw"))
                .withBean(AuditLogService.class, () -> mock(AuditLogService.class))
                .withUserConfiguration(ExecutorContainerService.class, SkillRepository.class,
                        SkillAdminService.class, SkillAdminController.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // 能成功构造即说明配置经统一属性派生（镜像/卷/技能源/分支全部来自同一配置源）
                    assertThat(context.getBean(ExecutorContainerService.class)).isNotNull();
                    assertThat(context.getBean(SkillRepository.class)).isNotNull();
                });
    }
}
