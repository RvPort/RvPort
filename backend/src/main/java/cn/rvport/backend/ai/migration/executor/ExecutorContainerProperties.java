package cn.rvport.backend.ai.migration.executor;

import java.time.Duration;

/** 执行器容器配置（rvport.ai-migration.executor.* 绑定由 RvPortProperties 聚合）。 */
public record ExecutorContainerProperties(
        String image,
        String skillsVolume,
        Duration dockerTimeout) {

    public ExecutorContainerProperties {
        image = image == null || image.isBlank() ? "rvport-ai-executor:local" : image;
        skillsVolume = skillsVolume == null || skillsVolume.isBlank() ? "rvport-ai-skills" : skillsVolume;
        dockerTimeout = dockerTimeout == null || dockerTimeout.isZero() || dockerTimeout.isNegative()
                ? Duration.ofHours(2)
                : dockerTimeout;
    }
}
