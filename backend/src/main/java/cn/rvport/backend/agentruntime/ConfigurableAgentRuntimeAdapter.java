package cn.rvport.backend.agentruntime;

import cn.rvport.backend.codexprovider.CodexProcessResult;
import cn.rvport.backend.codexprovider.CodexProcessRunner;
import cn.rvport.backend.codexprovider.ExecutionBudget;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jooq.JSONB;

abstract class ConfigurableAgentRuntimeAdapter implements AgentRuntimeAdapter {

    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {
    };

    private final CodexProcessRunner processRunner;
    private final ObjectMapper objectMapper;

    protected ConfigurableAgentRuntimeAdapter(CodexProcessRunner processRunner, ObjectMapper objectMapper) {
        this.processRunner = processRunner;
        this.objectMapper = objectMapper;
    }

    @Override
    public CodexProcessResult run(AgentRuntimeExecutionRequest request) throws IOException, InterruptedException {
        ExecutionBudget budget = new ExecutionBudget(request.timeout());
        List<String> command = new ArrayList<>();
        command.add(request.runtime().getExecutablePath());
        List<String> args = configuredArgs(request.runtime().getDefaultArgs());
        if (args.isEmpty()) {
            args = fallbackArgs(request);
        }
        command.addAll(args);
        command.add(request.prompt());
        Map<String, String> environment = environment(request);
        environment.putAll(additionalEnvironment(request));
        if (budget.expired()) { return new CodexProcessResult(-1, true, "", ""); }
        return processRunner.run(command, environment, null, request.workspace(), budget.remaining());
    }

    protected abstract List<String> fallbackArgs(AgentRuntimeExecutionRequest request) throws IOException;

    protected Map<String, String> additionalEnvironment(AgentRuntimeExecutionRequest request) throws IOException {
        return Map.of();
    }

    private List<String> configuredArgs(JSONB value) {
        return configuredArgs(value == null ? null : value.data());
    }

    private List<String> configuredArgs(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, LIST_TYPE);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private Map<String, String> environment(AgentRuntimeExecutionRequest request) {
        Map<String, String> environment = new HashMap<>();
        environment.put("OPENAI_API_KEY", request.model().apiKey());
        environment.put("OPENAI_BASE_URL", request.model().baseUrl());
        environment.put("OPENAI_MODEL", request.model().model());
        environment.put("RVPORT_AGENT_API_KEY", request.model().apiKey());
        environment.put("RVPORT_AGENT_BASE_URL", request.model().baseUrl());
        environment.put("RVPORT_AGENT_MODEL", request.model().model());
        environment.put("DEEPSEEK_API_KEY", request.model().apiKey());
        return environment;
    }
}
