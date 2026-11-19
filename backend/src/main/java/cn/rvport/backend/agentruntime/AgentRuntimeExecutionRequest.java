package cn.rvport.backend.agentruntime;

import cn.rvport.backend.codexprovider.CodexProviderResolvedConfig;
import cn.rvport.backend.jooq.tables.records.AgentRuntimesRecord;
import java.nio.file.Path;
import java.time.Duration;

public record AgentRuntimeExecutionRequest(
        AgentRuntimesRecord runtime,
        CodexProviderResolvedConfig model,
        Path workspace,
        String prompt,
        Duration timeout,
        boolean readOnlyInputs
) {
    public AgentRuntimeExecutionRequest(AgentRuntimesRecord runtime,CodexProviderResolvedConfig model,Path workspace,String prompt,Duration timeout) {
        this(runtime,model,workspace,prompt,timeout,false);
    }
    public java.util.Map<String,String> executionEnvironment(java.util.Map<String,String> environment) {
        var result=new java.util.HashMap<>(environment);
        if(readOnlyInputs) result.put("RVPORT_REPORT_REVIEW_SANDBOX",workspace.toAbsolutePath().toString());
        return result;
    }
}
