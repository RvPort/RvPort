package cn.rvport.backend.report.review;

import cn.rvport.backend.common.web.ApiResponse;
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
@RequestMapping("/api/reports/{reportId}")
public class ReviewWorkspaceController {
    private final ReviewWorkspaceService service;
    public ReviewWorkspaceController(ReviewWorkspaceService service) { this.service=service; }
    public record ConfirmationRequest(Long expectedVersion) { }
    @GetMapping("/review-items")
    public ResponseEntity<ApiResponse<Map<String,Object>>> list(@PathVariable UUID reportId,@ModelAttribute ReviewItemQuery query,
            @AuthenticationPrincipal RvPortUserPrincipal user,HttpServletRequest request) {
        return response(service.list(reportId,query,user.id(),user.superAdmin()),request);
    }
    @GetMapping("/review-items/{itemId}")
    public ResponseEntity<ApiResponse<Map<String,Object>>> detail(@PathVariable UUID reportId,@PathVariable UUID itemId,
            @RequestParam(required=false) String include,@RequestParam(defaultValue="1") int historyPage,@RequestParam(defaultValue="50") int historyPageSize,
            @AuthenticationPrincipal RvPortUserPrincipal user,HttpServletRequest request) {
        return response(service.detail(reportId,itemId,user.id(),user.superAdmin(),include,historyPage,historyPageSize),request);
    }
    @PatchMapping("/review-items")
    public ResponseEntity<ApiResponse<Map<String,Object>>> patch(@PathVariable UUID reportId,@RequestBody ReviewPatchRequest body,
            @RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal RvPortUserPrincipal user,HttpServletRequest request) {
        return response(service.patch(reportId,body,key,user.id(),user.superAdmin()),request);
    }
    @PostMapping("/review-items")
    public ResponseEntity<ApiResponse<Map<String,Object>>> add(@PathVariable UUID reportId,@RequestBody ManualReviewItemRequest body,
            @RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal RvPortUserPrincipal user,HttpServletRequest request) {
        return response(service.add(reportId,body,key,user.id(),user.superAdmin()),request);
    }
    @PostMapping("/fix-list-confirmations")
    public ResponseEntity<ApiResponse<Map<String,Object>>> confirm(@PathVariable UUID reportId,@RequestBody ConfirmationRequest body,
            @RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal RvPortUserPrincipal user,HttpServletRequest request) {
        return response(service.confirm(reportId,body.expectedVersion(),key,user.id(),user.superAdmin()),request);
    }
    @GetMapping("/fix-list-confirmations")
    public ResponseEntity<ApiResponse<cn.rvport.backend.common.web.PageResponse<Map<String,Object>>>> confirmations(@PathVariable UUID reportId,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int pageSize,
            @AuthenticationPrincipal RvPortUserPrincipal user,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(service.confirmations(reportId,user.id(),user.superAdmin(),page,pageSize),TraceIdFilter.currentTraceId(request)));
    }
    @GetMapping("/fix-list-confirmations/{confirmationId}")
    public ResponseEntity<ApiResponse<Map<String,Object>>> confirmation(@PathVariable UUID reportId,@PathVariable UUID confirmationId,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int pageSize,@RequestParam(defaultValue="ALL") String scope,
            @AuthenticationPrincipal RvPortUserPrincipal user,HttpServletRequest request) {
        return response(service.confirmation(reportId,confirmationId,user.id(),user.superAdmin(),page,pageSize,scope),request);
    }
    private ResponseEntity<ApiResponse<Map<String,Object>>> response(Map<String,Object> value,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(value,TraceIdFilter.currentTraceId(request)));
    }
}
