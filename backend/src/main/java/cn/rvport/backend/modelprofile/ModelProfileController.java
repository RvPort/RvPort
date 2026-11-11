package cn.rvport.backend.modelprofile;

import cn.rvport.backend.codexprovider.CodexProviderTestResult;
import cn.rvport.backend.common.web.ApiResponse;
import cn.rvport.backend.common.web.PageResponse;
import cn.rvport.backend.common.web.TraceIdFilter;
import cn.rvport.backend.security.RvPortUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
public class ModelProfileController {

    private final ModelProfileService service;
    private final ModelProfileRequestParser profileParser;
    private final ModelProfileAgentProbeRequestParser agentProbeParser;
    private final ModelStageAssignmentRequestParser assignmentParser;

    public ModelProfileController(
            ModelProfileService service,
            ModelProfileRequestParser profileParser,
            ModelProfileAgentProbeRequestParser agentProbeParser,
            ModelStageAssignmentRequestParser assignmentParser
    ) {
        this.service = service;
        this.profileParser = profileParser;
        this.agentProbeParser = agentProbeParser;
        this.assignmentParser = assignmentParser;
    }

    @GetMapping("/api/me/model-profiles")
    public ApiResponse<PageResponse<ModelProfileResponse>> list(
            @AuthenticationPrincipal RvPortUserPrincipal principal,
            @Valid @ModelAttribute ModelProfileListRequest request,
            HttpServletRequest servletRequest
    ) {
        return ApiResponse.ok(service.list(request, principal.id()), TraceIdFilter.currentTraceId(servletRequest));
    }

    @PostMapping("/api/me/model-profiles")
    public ApiResponse<ModelProfileResponse> create(
            @AuthenticationPrincipal RvPortUserPrincipal principal,
            @RequestBody JsonNode body,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(service.create(profileParser.parse(body), principal.id()), TraceIdFilter.currentTraceId(request));
    }

    @GetMapping("/api/me/model-profiles/{profileId}")
    public ApiResponse<ModelProfileResponse> get(@AuthenticationPrincipal RvPortUserPrincipal principal, @PathVariable UUID profileId, HttpServletRequest request) {
        return ApiResponse.ok(service.get(profileId, principal.id()), TraceIdFilter.currentTraceId(request));
    }

    @PatchMapping("/api/me/model-profiles/{profileId}")
    public ApiResponse<ModelProfileResponse> update(
            @AuthenticationPrincipal RvPortUserPrincipal principal,
            @PathVariable UUID profileId,
            @RequestBody JsonNode body,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(service.update(profileId, profileParser.parse(body), principal.id()), TraceIdFilter.currentTraceId(request));
    }

    @DeleteMapping("/api/me/model-profiles/{profileId}")
    public ApiResponse<Map<String, Object>> delete(@AuthenticationPrincipal RvPortUserPrincipal principal, @PathVariable UUID profileId, HttpServletRequest request) {
        service.delete(profileId, principal.id());
        return ApiResponse.ok(Map.of("deleted", true), TraceIdFilter.currentTraceId(request));
    }

    @PostMapping("/api/me/model-profiles/{profileId}:test")
    public ApiResponse<CodexProviderTestResult> test(
            @AuthenticationPrincipal RvPortUserPrincipal principal,
            @PathVariable UUID profileId,
            @RequestBody(required = false) JsonNode body,
            HttpServletRequest request
    ) {
        ModelProfileRequest parsed = body == null ? null : profileParser.parse(body);
        return ApiResponse.ok(service.test(profileId, parsed, principal.id()), TraceIdFilter.currentTraceId(request));
    }

    @PostMapping("/api/me/model-profiles/{profileId}:test-connectivity")
    public ApiResponse<ModelProfileConnectivityTestResult> testConnectivity(
            @AuthenticationPrincipal RvPortUserPrincipal principal,
            @PathVariable UUID profileId,
            @RequestBody(required = false) JsonNode body,
            HttpServletRequest request
    ) {
        ModelProfileRequest parsed = body == null ? null : profileParser.parse(body);
        return ApiResponse.ok(service.testConnectivity(profileId, parsed, principal.id()), TraceIdFilter.currentTraceId(request));
    }

    @PostMapping("/api/me/model-profiles/{profileId}:test-agents")
    public ApiResponse<ModelProfileAgentProbeRunResponse> testAgents(
            @AuthenticationPrincipal RvPortUserPrincipal principal,
            @PathVariable UUID profileId,
            @RequestBody JsonNode body,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(
                service.startAgentTests(profileId, agentProbeParser.parse(body), principal.id()),
                TraceIdFilter.currentTraceId(request)
        );
    }

    @GetMapping("/api/me/model-profile-agent-test-runs/{runId}")
    public ApiResponse<ModelProfileAgentProbeRunResponse> getAgentTestRun(
            @AuthenticationPrincipal RvPortUserPrincipal principal,
            @PathVariable UUID runId,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(service.getAgentTestRun(runId, principal.id()), TraceIdFilter.currentTraceId(request));
    }

    @GetMapping("/api/me/model-profiles/{profileId}/agent-probe-results")
    public ApiResponse<List<ModelProfileAgentProbeResultResponse>> listAgentProbeResults(
            @AuthenticationPrincipal RvPortUserPrincipal principal,
            @PathVariable UUID profileId,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(service.listAgentProbeResults(profileId, principal.id()), TraceIdFilter.currentTraceId(request));
    }

    @GetMapping("/api/me/model-stage-assignments")
    public ApiResponse<List<ModelProfileAssignmentResponse>> listAssignments(@AuthenticationPrincipal RvPortUserPrincipal principal, HttpServletRequest request) {
        return ApiResponse.ok(service.listAssignments(principal.id()), TraceIdFilter.currentTraceId(request));
    }

    @PatchMapping("/api/me/model-stage-assignments")
    public ApiResponse<List<ModelProfileAssignmentResponse>> saveAssignments(
            @AuthenticationPrincipal RvPortUserPrincipal principal,
            @RequestBody JsonNode body,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(service.saveAssignments(assignmentParser.parse(body), principal.id()), TraceIdFilter.currentTraceId(request));
    }
}
