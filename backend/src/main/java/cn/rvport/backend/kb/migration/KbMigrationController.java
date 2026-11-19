package cn.rvport.backend.kb.migration;

import cn.rvport.backend.common.web.ApiResponse;
import cn.rvport.backend.common.web.PageResponse;
import cn.rvport.backend.common.web.TraceIdFilter;
import cn.rvport.backend.security.RvPortUserPrincipal;
import cn.rvport.backend.task.TaskResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class KbMigrationController {

    private final KbMigrationService kbMigrationService;

    public KbMigrationController(KbMigrationService kbMigrationService) {
        this.kbMigrationService = kbMigrationService;
    }

    @PostMapping("/api/projects/{projectId}/kb-migrations")
    public ApiResponse<TaskResponse> create(
            @AuthenticationPrincipal RvPortUserPrincipal principal,
            @PathVariable UUID projectId,
            @RequestBody(required = false) KbMigrationRequest body,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(
                kbMigrationService.create(projectId, body, principal.id(), principal.superAdmin(), request),
                TraceIdFilter.currentTraceId(request));
    }

    @GetMapping("/api/projects/{projectId}/kb-migrations")
    public ApiResponse<PageResponse<KbMigrationResponse>> list(
            @AuthenticationPrincipal RvPortUserPrincipal principal,
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(
                kbMigrationService.list(projectId, page, pageSize, principal.id(), principal.superAdmin()),
                TraceIdFilter.currentTraceId(request));
    }

    @GetMapping("/api/kb-migrations/{runId}")
    public ApiResponse<KbMigrationResponse> detail(
            @AuthenticationPrincipal RvPortUserPrincipal principal,
            @PathVariable UUID runId,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(
                kbMigrationService.detail(runId, principal.id(), principal.superAdmin()),
                TraceIdFilter.currentTraceId(request));
    }
}
