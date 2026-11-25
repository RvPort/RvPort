package cn.rvport.backend.report.review;

import cn.rvport.backend.common.web.ApiResponse;
import cn.rvport.backend.common.web.PageResponse;
import cn.rvport.backend.common.web.TraceIdFilter;
import cn.rvport.backend.security.RvPortUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports/{reportId}/source-files")
public class ReportSourceWorkspaceController {
    private final ReportSourceWorkspaceService service;
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<cn.rvport.backend.common.error.ApiErrorResponse> invalidParameter(HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new cn.rvport.backend.common.error.ApiErrorResponse("REPORT_REQUEST_INVALID",
                "源码查询参数不正确",Map.of("messageEn","Invalid source query parameters"),
                TraceIdFilter.currentTraceId(request),java.time.Instant.now()));
    }
    public ReportSourceWorkspaceController(ReportSourceWorkspaceService service) { this.service=service; }
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<Map<String,Object>>>> files(@PathVariable UUID reportId,
            @RequestParam(required=false) String parentKey,@RequestParam(required=false) String keyword,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int pageSize,
            @AuthenticationPrincipal RvPortUserPrincipal user,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(
                service.files(reportId,user.id(),user.superAdmin(),parentKey,keyword,page,pageSize),TraceIdFilter.currentTraceId(request)));
    }
    @GetMapping("/{fileKey}/context")
    public ResponseEntity<ApiResponse<Map<String,Object>>> context(@PathVariable UUID reportId,@PathVariable String fileKey,
            @RequestParam(required=false) Integer startLine,@RequestParam(required=false) Integer endLine,
            @AuthenticationPrincipal RvPortUserPrincipal user,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(
                service.context(reportId,fileKey,user.id(),user.superAdmin(),startLine,endLine),TraceIdFilter.currentTraceId(request)));
    }
    @GetMapping("/{fileKey}/search")
    public ResponseEntity<ApiResponse<Map<String,Object>>> search(@PathVariable UUID reportId,@PathVariable String fileKey,
            @RequestParam(required=false) String keyword,@RequestParam(defaultValue="false") boolean caseSensitive,
            @RequestParam(defaultValue="100") int limit,@AuthenticationPrincipal RvPortUserPrincipal user,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(
                service.search(reportId,fileKey,user.id(),user.superAdmin(),keyword,caseSensitive,limit),TraceIdFilter.currentTraceId(request)));
    }
}
