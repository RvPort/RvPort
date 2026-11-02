package cn.rvport.backend.agentruntime;

import cn.rvport.backend.codexprovider.AgentType;
import cn.rvport.backend.codexprovider.CodexProcessRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DeepSeekHarnessAgentRuntimeAdapter extends ConfigurableAgentRuntimeAdapter {

    public DeepSeekHarnessAgentRuntimeAdapter(CodexProcessRunner processRunner, ObjectMapper objectMapper) {
        super(processRunner, objectMapper);
    }

    @Override
    public AgentType type() {
        return AgentType.DEEPSEEK_HARNESS;
    }

    @Override
    protected List<String> fallbackArgs(AgentRuntimeExecutionRequest request) throws IOException {
        Path patchPath = request.workspace().toAbsolutePath().normalize().resolve("dsh-rvport-provider.patch.yml");
        Files.writeString(patchPath, patch(request), StandardCharsets.UTF_8);
        return List.of(
                "--profile", "headless",
                "--patch", patchPath.toString()
        );
    }

    private String patch(AgentRuntimeExecutionRequest request) {
        String provider = "rvport-openai-compatible";
        String model = yaml(request.model().model());
        return """
                - id: hmr
                  disabled: false
                  config:
                    root: []
                - id: llm-pi-ai
                  name: '@deepseek-ai/dsh-llm-pi-ai'
                  config:
                    providers:
                      %s:
                        displayName: RvPort OpenAI Compatible
                        apiKeyEnv: RVPORT_AGENT_API_KEY
                        api: openai-completions
                        baseURL: %s
                        models:
                          - id: %s
                            name: %s
                            contextWindow: 131072
                            maxTokens: 8192
                - id: agent-default-model
                  name: '@deepseek-ai/dsh-agent-default-model'
                  config:
                    provider: %s
                    model: %s
                - id: approval
                  name: '@deepseek-ai/dsh-user-approval'
                  config:
                    policy: never
                - id: sandbox-policy
                  name: '@deepseek-ai/dsh-sandbox-policy'
                  config:
                    mode: danger-full-access
                    workspaceRoot: !!js process.cwd()
                """.formatted(provider, yaml(request.model().baseUrl()), model, model, provider, model);
    }

    private String yaml(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
