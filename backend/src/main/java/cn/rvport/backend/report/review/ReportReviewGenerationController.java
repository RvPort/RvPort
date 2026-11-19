package cn.rvport.backend.report.review;

import cn.rvport.backend.common.web.*;
import cn.rvport.backend.report.ReportGenerateRequest;
import cn.rvport.backend.security.RvPortUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/reports/evaluation")
public class ReportReviewGenerationController {
    private final ReportReviewGenerationService generation;
    private final CurrentFixListResolver fixLists;
    public ReportReviewGenerationController(ReportReviewGenerationService generation,CurrentFixListResolver fixLists) {
        this.generation=generation; this.fixLists=fixLists;
    }
    @PostMapping
    public ResponseEntity<ApiResponse<Map<String,Object>>> create(@AuthenticationPrincipal RvPortUserPrincipal user,
            @PathVariable UUID projectId,@RequestBody ReportGenerateRequest body,@RequestHeader("Idempotency-Key") String key,
            HttpServletRequest request) {
        return response(generation.create(projectId,body,key,user.id(),user.superAdmin()),request);
    }
    @GetMapping("/readiness")
    public ResponseEntity<ApiResponse<Map<String,Object>>> readiness(@AuthenticationPrincipal RvPortUserPrincipal user,
            @PathVariable UUID projectId,@RequestParam(required=false) UUID sourceRevisionId,HttpServletRequest request) {
        var result=new LinkedHashMap<>(generation.readiness(projectId,sourceRevisionId,user.id(),user.superAdmin()));
        result.put("currentFixList",fixLists.current(projectId));
        return response(result,request);
    }
    @GetMapping("/latest")
    public ResponseEntity<ApiResponse<Map<String,Object>>> latest(@AuthenticationPrincipal RvPortUserPrincipal user,
            @PathVariable UUID projectId,@RequestParam(required=false) UUID sourceRevisionId,HttpServletRequest request) {
        var state=generation.latest(projectId,sourceRevisionId,user.id(),user.superAdmin());
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("context",Collections.singletonMap("sourceRevisionId",state.get("sourceRevisionId")));
        result.put("freshness",state.get("currentReport")!=null?"CURRENT":state.get("latestGeneration")==null?"EMPTY":"STALE");
        result.put("regenerationBaseReportId",state.get("regenerationBaseReportId"));
        result.put("regenerationBaseDraftVersion",state.get("regenerationBaseDraftVersion"));
        result.put("execution",state.get("latestGeneration")); result.put("result",state.get("currentReport"));
        result.put("currentReport",state.get("currentReport")); result.put("latestGeneration",state.get("latestGeneration"));
        result.put("currentFixList",fixLists.current(projectId)); return response(result,request);
    }
    @GetMapping("/input-preview")
    public ResponseEntity<ApiResponse<PageResponse<Map<String,Object>>>> preview(@AuthenticationPrincipal RvPortUserPrincipal user,
            @PathVariable UUID projectId,@RequestParam(required=false) UUID sourceRevisionId,@ModelAttribute ReviewItemQuery query,HttpServletRequest request) {
        return response(generation.preview(projectId,sourceRevisionId,query,user.id(),user.superAdmin()),request);
    }
    private <T> ResponseEntity<ApiResponse<T>> response(T data,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(data,TraceIdFilter.currentTraceId(request)));
    }
}
