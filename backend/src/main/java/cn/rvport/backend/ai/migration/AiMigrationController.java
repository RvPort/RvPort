package cn.rvport.backend.ai.migration;

import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.common.web.ApiResponse;
import cn.rvport.backend.common.web.PageResponse;
import cn.rvport.backend.common.web.TraceIdFilter;
import cn.rvport.backend.security.RvPortUserPrincipal;
import cn.rvport.backend.task.TaskResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
public class AiMigrationController {

    private final AiMigrationService aiMigrationService;
    private final AiMigrationHistoryService historyService;

    public AiMigrationController(AiMigrationService aiMigrationService, AiMigrationHistoryService historyService) {
        this.aiMigrationService = aiMigrationService;
        this.historyService = historyService;
    }

    @GetMapping("/api/ai-migrations/{runId}/history")
    public ApiResponse<java.util.List<AiMigrationHistoryService.HistoryResponse>> history(
            @AuthenticationPrincipal RvPortUserPrincipal principal,
            @PathVariable UUID runId,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(
                historyService.history(runId, principal.id(), principal.superAdmin()),
                TraceIdFilter.currentTraceId(request));
    }

    @GetMapping("/api/ai-migrations/{runId}/repo-file")
    public ApiResponse<String> repoFile(
            @AuthenticationPrincipal RvPortUserPrincipal principal,
            @PathVariable UUID runId,
            @RequestParam String path,
            @RequestParam(required = false) String ref,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(
                historyService.repoFile(runId, path, ref, principal.id(), principal.superAdmin()),
                TraceIdFilter.currentTraceId(request));
    }

    @PostMapping("/api/ai-migrations/{runId}/rollback")
    public ApiResponse<AiMigrationHistoryService.RollbackResponse> rollback(
            @AuthenticationPrincipal RvPortUserPrincipal principal,
            @PathVariable UUID runId,
            @RequestBody(required = false) AiMigrationHistoryService.RollbackRequest body,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(
                historyService.rollback(runId, body, principal.id(), principal.superAdmin()),
                TraceIdFilter.currentTraceId(request));
    }

    @PostMapping("/api/projects/{projectId}/ai-migrations")
    public ApiResponse<TaskResponse> create(
            @AuthenticationPrincipal RvPortUserPrincipal principal,
            @PathVariable UUID projectId,
            @RequestBody(required = false) AiMigrationRequest body,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(
                aiMigrationService.create(projectId, body, principal.id(), principal.superAdmin(), request),
                TraceIdFilter.currentTraceId(request));
    }

    @GetMapping("/api/projects/{projectId}/ai-migrations")
    public ApiResponse<PageResponse<AiMigrationResponse>> list(
            @AuthenticationPrincipal RvPortUserPrincipal principal,
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(
                aiMigrationService.list(projectId, page, pageSize, principal.id(), principal.superAdmin()),
                TraceIdFilter.currentTraceId(request));
    }

    @GetMapping("/api/ai-migrations/{runId}")
    public ApiResponse<AiMigrationResponse> detail(
            @AuthenticationPrincipal RvPortUserPrincipal principal,
            @PathVariable UUID runId,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(
                aiMigrationService.detail(runId, principal.id(), principal.superAdmin()),
                TraceIdFilter.currentTraceId(request));
    }

    private AiMigrationService.ArtifactKind requireArtifactKind(String kind) {
        AiMigrationService.ArtifactKind artifactKind = AiMigrationService.ArtifactKind.fromPathValue(kind);
        if (artifactKind == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AI_MIGRATION_ARTIFACT_KIND_INVALID",
                    "迁移产物类型不正确 / Invalid migration artifact kind");
        }
        return artifactKind;
    }

    /** 读取某次运行的迁移产物文本用于页面内展示；kind 为 diff 或 result。 */
    @GetMapping("/api/ai-migrations/{runId}/artifacts/{kind}")
    public ApiResponse<AiMigrationService.ArtifactContent> artifactContent(
            @AuthenticationPrincipal RvPortUserPrincipal principal,
            @PathVariable UUID runId,
            @PathVariable String kind,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(
                aiMigrationService.artifactContent(runId, requireArtifactKind(kind), principal.id(),
                        principal.superAdmin()),
                TraceIdFilter.currentTraceId(request));
    }

    /** 下载某次运行的迁移产物；kind 为 diff（补丁）或 result（逐条目结论）。 */
    @GetMapping("/api/ai-migrations/{runId}/artifacts/{kind}/download")
    public ResponseEntity<Resource> downloadArtifact(
            @AuthenticationPrincipal RvPortUserPrincipal principal,
            @PathVariable UUID runId,
            @PathVariable String kind,
            HttpServletRequest request
    ) {
        AiMigrationService.ArtifactKind artifactKind = requireArtifactKind(kind);
        AiMigrationService.DownloadableArtifact artifact = aiMigrationService.downloadArtifact(
                runId, artifactKind, principal.id(), principal.superAdmin());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(artifact.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(artifact.filename())
                        .build()
                        .toString())
                .body(artifact.resource());
    }
}
