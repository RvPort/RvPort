package cn.rvport.backend.agentruntime;

import cn.rvport.backend.codexprovider.AgentType;
import cn.rvport.backend.codexprovider.CodexProcessResult;
import cn.rvport.backend.codexprovider.CodexProcessRunner;
import cn.rvport.backend.codexprovider.CodexProviderResolvedConfig;
import cn.rvport.backend.codexprovider.CodexProviderWireApi;
import cn.rvport.backend.codexprovider.ExecutionBudget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import cn.rvport.backend.config.WorkerProperties;
import cn.rvport.backend.config.RvPortAppMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CodexAgentRuntimeAdapter implements AgentRuntimeAdapter {

    private final CodexProcessRunner processRunner;
    private final boolean worker;
    private final String proxyExecutable;
    private final Semaphore slots;
    private final OpenCodexHomes homes = new OpenCodexHomes(Path.of("/var/tmp"));

    @Autowired
    public CodexAgentRuntimeAdapter(CodexProcessRunner processRunner, WorkerProperties properties,
            @Value("${rvport.agent.opencodex.executable:/opt/rvport/opencodex/node_modules/.bin/ocx}") String executable,
            @Value("${rvport.agent.opencodex.max-concurrency:2}") int concurrency) {
        this(processRunner, properties.enabled() && properties.mode() != RvPortAppMode.API,
                executable, Math.min(properties.concurrencyLimit(), concurrency));
    }

    public CodexAgentRuntimeAdapter(CodexProcessRunner processRunner) {
        this(processRunner, true, "/opt/rvport/opencodex/node_modules/.bin/ocx", 2);
    }

    CodexAgentRuntimeAdapter(CodexProcessRunner processRunner, boolean worker, String executable, int concurrency) {
        this.processRunner = processRunner;
        this.worker = worker;
        this.proxyExecutable = executable;
        this.slots = new Semaphore(Math.max(0, concurrency), true);
    }

    @Override
    public AgentType type() {
        return AgentType.CODEX;
    }

    @Override
    public CodexProcessResult run(AgentRuntimeExecutionRequest request) throws IOException, InterruptedException {
        ExecutionBudget budget = new ExecutionBudget(request.timeout());
        if (request.model().wireApi() == CodexProviderWireApi.CHAT) {
            return executeChat(request, budget);
        }
        return execute(request, request.model(), budget);
    }

    private CodexProcessResult executeChat(AgentRuntimeExecutionRequest request, ExecutionBudget budget)
            throws IOException, InterruptedException {
        if (!worker) { throw new IOException("OPENCODEX_WORKER_REQUIRED"); }
        if (!slots.tryAcquire(budget.remaining().toNanos(), TimeUnit.NANOSECONDS)) {
            return new CodexProcessResult(-1, true, "", "RVPORT_OCX_QUEUE_DEADLINE");
        }
        try (var lease = homes.create()) {
            if (budget.expired()) { return new CodexProcessResult(-1, true, "", "RVPORT_OCX_QUEUE_DEADLINE"); }
            Path home = lease.path();
            for (String name : List.of("opencodex_runtime.py", "opencodex_guard.py", "apply_patch.lark")) {
                try (var source = CodexAgentRuntimeAdapter.class.getResourceAsStream("/agentruntime/" + name)) {
                    if (source == null) { throw new IOException("OPENCODEX_RESOURCE_MISSING"); }
                    Files.copy(source, home.resolve(name));
                }
            }
            List<String> command = new ArrayList<>(List.of("python3", home.resolve("opencodex_runtime.py").toString()));
            command.addAll(CodexAgentCommandFactory.command(request.runtime().getExecutablePath(), request.model(), request.workspace(), true));
            var environment = new HashMap<>(CodexAgentCommandFactory.environment(request.model()));
            environment.put("RVPORT_OCX_HOME", home.toString());
            environment.put("RVPORT_OCX_EXECUTABLE", proxyExecutable);
            environment.put("RVPORT_OCX_TIMEOUT_SECONDS", Double.toString(budget.remaining().toNanos() / 1_000_000_000.0));
            CodexProcessResult result = processRunner.run(command, environment, request.prompt(), request.workspace(), budget.remaining());
            return result.exitCode() == 124 ? new CodexProcessResult(result.exitCode(), true, result.stdout(), result.stderr(),
                    result.mergedOutput(), result.diagnostics()) : result;
        } finally { slots.release(); }
    }

    private CodexProcessResult execute(AgentRuntimeExecutionRequest request, CodexProviderResolvedConfig config,
            ExecutionBudget budget) throws IOException, InterruptedException {
        if (budget.expired()) { return new CodexProcessResult(-1, true, "", ""); }
        return processRunner.run(
                CodexAgentCommandFactory.command(request.runtime().getExecutablePath(), config, request.workspace()),
                CodexAgentCommandFactory.environment(config),
                request.prompt(),
                request.workspace(),
                budget.remaining()
        );
    }
}
