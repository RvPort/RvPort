package cn.rvport.backend.ai.migration.executor;

import cn.rvport.backend.audit.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 技能管理面：查询（版本/远端差异）与更新，管理员操作记审计。 */
@Service
public class SkillAdminService {

    private final SkillRepository skillRepository;
    private final AuditLogService auditLogService;

    public SkillAdminService(SkillRepository skillRepository, AuditLogService auditLogService) {
        this.skillRepository = skillRepository;
        this.auditLogService = auditLogService;
    }

    public SkillRepository.SkillStatus status() {
        return skillRepository.status();
    }

    public SkillRepository.SkillUpdateResult update(java.util.UUID actorUserId, HttpServletRequest httpRequest) {
        SkillRepository.SkillUpdateResult result = skillRepository.update();
        auditLogService.record(null, actorUserId, "ai.migration.skill.update", "skill_repository",
                null, Map.of(
                        "before", result.beforeRevision(),
                        "after", result.afterRevision(),
                        "commits", result.commitsChanged(),
                        "files", result.filesChanged()), httpRequest);
        return result;
    }

    public List<String> skills() {
        return skillRepository.listSkills();
    }
}
