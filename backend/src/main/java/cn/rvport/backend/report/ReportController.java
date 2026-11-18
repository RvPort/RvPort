package cn.rvport.backend.report;

import cn.rvport.backend.common.web.ApiResponse;
import cn.rvport.backend.common.web.PageResponse;
import cn.rvport.backend.common.web.TraceIdFilter;
import cn.rvport.backend.reportcomparison.ReportComparisonCreateRequest;
import cn.rvport.backend.reportcomparison.ReportComparisonDiffItemDetailResponse;
import cn.rvport.backend.reportcomparison.ReportComparisonDiffItemListRequest;
import cn.rvport.backend.reportcomparison.ReportComparisonDiffItemResponse;
import cn.rvport.backend.reportcomparison.ReportComparisonResponse;
import cn.rvport.backend.reportcomparison.ReportComparisonService;
import cn.rvport.backend.security.RvPortUserPrincipal;
import cn.rvport.backend.task.TaskResponse;
import cn.rvport.backend.workflow.DomainLatestResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class ReportController {

    private final ReportService reportService;
    private final ReportComparisonService reportComparisonService;
    private final ReportOrganizationService reportOrganizationService;

    private final cn.rvport.backend.report.review.ReviewWorkspaceService reviews;

    public ReportController(
            ReportService reportService,
            ReportComparisonService reportComparisonService,
            ReportOrganizationService reportOrganizationService,
            cn.rvport.backend.report.review.ReviewWorkspaceService reviews
    ) {
        this.reportService = reportService;
        this.reportComparisonService = reportComparisonService;
        this.reportOrganizationService = reportOrganizationService;
        this.reviews=reviews;
    }

    @GetMapping("/api/projects/{projectId}/reports/evaluation")
    public ApiResponse<PageResponse<ReportResponse>> listEvaluation(
            @AuthenticationPrincipal RvPortUserPrincipal principal,
            @PathVariable UUID projectId,
            @Valid @ModelAttribute ReportListRequest query,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(
                reportService.list(projectId, query, principal.id(), principal.superAdmin()),
                TraceIdFilter.currentTraceId(request)
        );
    }

    @GetMapping("/api/reports/{reportId}")
    public ApiResponse<ReportResponse> get(
            @AuthenticationPrincipal RvPortUserPrincipal principal,
            @PathVariable UUID reportId,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(
                reportService.get(reportId, principal.id(), principal.superAdmin()),
                TraceIdFilter.currentTraceId(request)
        );
    }

    @GetMapping("/api/reports/{reportId}/inputs")
    public ApiResponse<PageResponse<ReportInputResponse>> inputs(
            @AuthenticationPrincipal RvPortUserPrincipal principal,
            @PathVariable UUID reportId,
            @Valid @ModelAttribute ReportInputListRequest query,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(
                reportService.inputs(reportId, query, principal.id(), principal.superAdmin()),
                TraceIdFilter.currentTraceId(request)
        );
    }

    @GetMapping("/api/reports/{reportId}/download")
    public ResponseEntity<Resource> download(
            @AuthenticationPrincipal RvPortUserPrincipal principal,
            @PathVariable UUID reportId,
            @RequestParam String format,@RequestParam(required=false) UUID confirmationId
    ) {
        ReportService.DownloadableReport report = confirmationId!=null?reviews.download(reportId,confirmationId,format,principal.id(),principal.superAdmin()):reportService.download(reportId, format, principal.id(), principal.superAdmin());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(report.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(report.filename())
                        .build()
                        .toString())
                .body(report.resource());
    }

    @GetMapping("/api/reports/{reportId}/code-modules")
    public ApiResponse<PageResponse<ReportCodeModuleResponse>> codeModules(
            @AuthenticationPrincipal RvPortUserPrincipal principal,
            @PathVariable UUID reportId,
            @Valid @ModelAttribute ReportOrganizationListRequest query,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(reportOrganizationService.modules(reportId, query, principal.id(), principal.superAdmin()),
                TraceIdFilter.currentTraceId(request));
    }

    @GetMapping("/api/reports/{reportId}/code-files")
    public ApiResponse<PageResponse<ReportCodeFileResponse>> codeFiles(
            @AuthenticationPrincipal RvPortUserPrincipal principal,
            @PathVariable UUID reportId,
            @RequestParam String moduleKey,
            @Valid @ModelAttribute ReportOrganizationListRequest query,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(reportOrganizationService.files(reportId, moduleKey, query, principal.id(), principal.superAdmin()),
                TraceIdFilter.currentTraceId(request));
    }

    @GetMapping("/api/reports/{reportId}/code-finding-types")
    public ApiResponse<PageResponse<ReportFindingTypeResponse>> codeFindingTypes(
            @AuthenticationPrincipal RvPortUserPrincipal principal,
            @PathVariable UUID reportId,
            @RequestParam String moduleKey,
            @RequestParam String fileKey,
            @Valid @ModelAttribute ReportOrganizationListRequest query,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(reportOrganizationService.findingTypes(reportId, moduleKey, fileKey, query,
                        principal.id(), principal.superAdmin()), TraceIdFilter.currentTraceId(request));
    }

    @GetMapping("/api/reports/{reportId}/code-findings")
    public ApiResponse<PageResponse<ReportCodeFindingResponse>> codeFindings(
            @AuthenticationPrincipal RvPortUserPrincipal principal,
            @PathVariable UUID reportId,
            @RequestParam String moduleKey,
            @RequestParam String fileKey,
            @RequestParam(required = false) String findingType,
            @Valid @ModelAttribute ReportOrganizationListRequest query,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(reportOrganizationService.findings(reportId, moduleKey, fileKey, findingType, query,
                        principal.id(), principal.superAdmin()), TraceIdFilter.currentTraceId(request));
    }

    @GetMapping("/api/reports/{reportId}/code-files/{fileKey}/context")
    public ApiResponse<ReportFileContextResponse> codeFileContext(
            @AuthenticationPrincipal RvPortUserPrincipal principal,
            @PathVariable UUID reportId,
            @PathVariable String fileKey,
            @Valid @ModelAttribute ReportFileContextRequest query,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(reportOrganizationService.context(reportId, fileKey, query, principal.id(), principal.superAdmin()),
                TraceIdFilter.currentTraceId(request));
    }

    @PostMapping("/api/reports/{reportId}/comparisons")
    public ApiResponse<TaskResponse> createComparison(
            @AuthenticationPrincipal RvPortUserPrincipal principal,
            @PathVariable UUID reportId,
            @RequestBody(required = false) ReportComparisonCreateRequest body,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(
                reportComparisonService.create(reportId, body, principal.id(), principal.superAdmin(), request),
                TraceIdFilter.currentTraceId(request)
        );
    }

    @GetMapping("/api/report-comparisons/{comparisonRunId}")
    public ApiResponse<ReportComparisonResponse> getComparison(
            @AuthenticationPrincipal RvPortUserPrincipal principal,
            @PathVariable UUID comparisonRunId,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(
                reportComparisonService.get(comparisonRunId, principal.id(), principal.superAdmin()),
                TraceIdFilter.currentTraceId(request)
        );
    }

    @GetMapping("/api/report-comparisons/{comparisonRunId}/diff-items")
    public ApiResponse<PageResponse<ReportComparisonDiffItemResponse>> comparisonDiffItems(
            @AuthenticationPrincipal RvPortUserPrincipal principal,
            @PathVariable UUID comparisonRunId,
            @Valid @ModelAttribute ReportComparisonDiffItemListRequest query,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(
                reportComparisonService.diffItems(comparisonRunId, query, principal.id(), principal.superAdmin()),
                TraceIdFilter.currentTraceId(request)
        );
    }

    @GetMapping("/api/report-comparisons/{comparisonRunId}/diff-items/{diffItemId}")
    public ApiResponse<ReportComparisonDiffItemDetailResponse> comparisonDiffItem(
            @AuthenticationPrincipal RvPortUserPrincipal principal,
            @PathVariable UUID comparisonRunId,
            @PathVariable UUID diffItemId,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(
                reportComparisonService.diffItem(comparisonRunId, diffItemId, principal.id(), principal.superAdmin()),
                TraceIdFilter.currentTraceId(request)
        );
    }
}
