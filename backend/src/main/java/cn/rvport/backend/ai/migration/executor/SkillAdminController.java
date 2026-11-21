package cn.rvport.backend.ai.migration.executor;

import cn.rvport.backend.common.web.ApiResponse;
import cn.rvport.backend.common.web.TraceIdFilter;
import cn.rvport.backend.security.RvPortUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SkillAdminController {

    private final SkillAdminService skillAdminService;

    public SkillAdminController(SkillAdminService skillAdminService) {
        this.skillAdminService = skillAdminService;
    }

    @GetMapping("/api/admin/ai-migration/skills")
    public ApiResponse<SkillRepository.SkillStatus> status(HttpServletRequest request) {
        return ApiResponse.ok(skillAdminService.status(), TraceIdFilter.currentTraceId(request));
    }

    @PostMapping("/api/admin/ai-migration/skills:update")
    public ApiResponse<SkillRepository.SkillUpdateResult> update(
            @org.springframework.security.core.annotation.AuthenticationPrincipal RvPortUserPrincipal principal,
            HttpServletRequest request) {
        return ApiResponse.ok(skillAdminService.update(principal.id(), request),
                TraceIdFilter.currentTraceId(request));
    }
}
