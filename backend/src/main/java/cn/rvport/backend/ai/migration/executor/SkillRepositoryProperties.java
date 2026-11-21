package cn.rvport.backend.ai.migration.executor;

import java.nio.file.Path;

/** 技能仓库配置。 */
public record SkillRepositoryProperties(
        Path skillsRoot,
        String remoteUrl,
        String branch) {

    public SkillRepositoryProperties {
        skillsRoot = skillsRoot == null ? Path.of("/var/lib/rvport/ai-skills") : skillsRoot;
        remoteUrl = remoteUrl == null || remoteUrl.isBlank()
                ? "https://github.com/zte-riscv/rvport-skills.git"
                : remoteUrl;
        branch = branch == null || branch.isBlank() ? "master" : branch;
    }
}
