package cn.rvport.backend.ai.migration.executor;

import java.time.Duration;

/**
 * 执行器容器配置（rvport.ai-migration.executor.* 绑定由 RvPortProperties 聚合）。
 *
 * <p>迁移技能随执行器镜像内置，容器启动时不挂载技能卷，因此这里没有技能卷配置。
 */
public record ExecutorContainerProperties(
        String image,
        Duration dockerTimeout) {

    public ExecutorContainerProperties {
        image = image == null || image.isBlank() ? "rvport-ai-executor:local" : image;
        dockerTimeout = dockerTimeout == null || dockerTimeout.isZero() || dockerTimeout.isNegative()
                ? Duration.ofHours(2)
                : dockerTimeout;
    }
}
