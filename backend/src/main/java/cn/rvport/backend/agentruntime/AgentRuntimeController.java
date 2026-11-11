package cn.rvport.backend.agentruntime;

import cn.rvport.backend.common.web.ApiResponse;
import cn.rvport.backend.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AgentRuntimeController {

    private final AgentRuntimeService service;

    public AgentRuntimeController(AgentRuntimeService service) {
        this.service = service;
    }

    @GetMapping("/api/me/agent-runtimes")
    public ApiResponse<List<AgentRuntimeResponse>> list(HttpServletRequest request) {
        return ApiResponse.ok(service.list(), TraceIdFilter.currentTraceId(request));
    }

    @GetMapping("/api/me/agent-runtimes/{runtimeId}")
    public ApiResponse<AgentRuntimeResponse> get(@PathVariable UUID runtimeId, HttpServletRequest request) {
        return ApiResponse.ok(service.get(runtimeId), TraceIdFilter.currentTraceId(request));
    }
}
