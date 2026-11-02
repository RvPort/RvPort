package cn.rvport.backend.agentruntime;

import cn.rvport.backend.codexprovider.CodexProviderResolvedConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class CodexAgentCommandFactory {

    private CodexAgentCommandFactory() {
    }

    static List<String> command(String executablePath, CodexProviderResolvedConfig config, Path workspace) {
        return command(executablePath, config, workspace, false);
    }

    static List<String> command(String executablePath, CodexProviderResolvedConfig config, Path workspace, boolean bridge) {
        List<String> command = new ArrayList<>();
        command.add(executablePath);
        command.add("exec");
        command.add("--skip-git-repo-check");
        command.add("--ephemeral");
        command.add("--ignore-user-config");
        command.add("--ignore-rules");
        command.add("--dangerously-bypass-approvals-and-sandbox");
        command.add("-C");
        command.add(workspace.toAbsolutePath().normalize().toString());
        command.add("-c");
        command.add("model_provider=\"rvport-openai-compatible\"");
        command.add("-c");
        command.add("model_providers.rvport-openai-compatible.name=\"RvPort OpenAI Compatible\"");
        command.add("-c");
        command.add("model_providers.rvport-openai-compatible.base_url=" + tomlQuote(config.baseUrl()));
        command.add("-c");
        command.add("model_providers.rvport-openai-compatible.env_key=\"OPENAI_API_KEY\"");
        command.add("-c");
        command.add("model_providers.rvport-openai-compatible.wire_api=\"responses\"");
        command.add("-c");
        command.add("model_providers.rvport-openai-compatible.request_max_retries=0");
        command.add("-c");
        command.add("model_providers.rvport-openai-compatible.stream_max_retries=0");
        command.add("-c");
        command.add("analytics.enabled=false");
        if (bridge) {
        command.add("-c");
        command.add("web_search=\"disabled\"");
        command.add("-c");
        command.add("features.multi_agent=false");
        command.add("-c");
        command.add("features.goals=false");
        command.add("-c");
        command.add("model_reasoning_summary=\"none\"");
        command.add("-c");
        command.add("model_reasoning_effort=\"none\"");
        command.add("-c");
        command.add("model_providers.rvport-openai-compatible.supports_websockets=false");
        command.add("-c");
        command.add("model_providers.rvport-openai-compatible.capabilities.remote_compaction=\"unsupported\"");
        }
        command.add("-m");
        command.add(config.model());
        command.add("-");
        return command;
    }

    static Map<String, String> environment(CodexProviderResolvedConfig config) {
        Map<String, String> environment = new HashMap<>();
        environment.put("OPENAI_API_KEY", config.apiKey());
        environment.put("OPENAI_BASE_URL", config.baseUrl());
        environment.put("OPENAI_MODEL", config.model());
        return environment;
    }

    private static String tomlQuote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
