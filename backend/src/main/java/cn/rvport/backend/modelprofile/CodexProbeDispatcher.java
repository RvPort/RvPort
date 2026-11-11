package cn.rvport.backend.modelprofile;

import cn.rvport.backend.agentruntime.AgentRuntimeService;
import cn.rvport.backend.codexprovider.*;
import cn.rvport.backend.security.CredentialCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class CodexProbeDispatcher {
    public static final String QUEUE = "rvport.codex.profile-probe";
    private final CodexProbeJobRepository jobs;
    private final ModelProfileRepository profiles;
    private final AgentRuntimeService runtimes;
    private final CredentialCipher cipher;
    private final ObjectMapper json;
    private final CodexProviderProbeService probes;
    private final RabbitTemplate rabbit;
    private ModelConfigurationAccess configurationAccess;

    @org.springframework.beans.factory.annotation.Autowired
    void setConfigurationAccess(ModelConfigurationAccess access) { this.configurationAccess = access; }

    public CodexProbeDispatcher(CodexProbeJobRepository jobs, ModelProfileRepository profiles,
            AgentRuntimeService runtimes, CredentialCipher cipher, ObjectMapper json,
            CodexProviderProbeService probes, RabbitTemplate rabbit) {
        this.jobs=jobs; this.profiles=profiles; this.runtimes=runtimes; this.cipher=cipher;
        this.json=json; this.probes=probes; this.rabbit=rabbit;
    }

    public CodexProviderTestResult execute(UUID runId, UUID profileId, UUID runtimeId, UUID configurationUserId,
            CodexProviderResolvedConfig override, int timeoutSeconds) {
        CodexProbeJobRepository.Job job = null;
        try {
            String encrypted = override == null ? null : json.writeValueAsString(cipher.encrypt(json.writeValueAsBytes(override)));
            job = jobs.create(UUID.randomUUID(),runId,profileId,runtimeId,configurationUserId,encrypted,
                    OffsetDateTime.now().plusSeconds(3L*timeoutSeconds));
            publish(job.id());
            while (OffsetDateTime.now().isBefore(job.deadline())) {
                var current = jobs.find(job.id()).orElseThrow();
                if ("DONE".equals(current.status())) {
                    return json.readValue(current.result(),CodexProviderTestResult.class);
                }
                if (!List.of("QUEUED","RUNNING").contains(current.status())) {
                    return failure(runtimeId,"AGENT_PROBE_WORKER_CANCELLED");
                }
                TimeUnit.MILLISECONDS.sleep(50);
            }
            return failure(runtimeId,"AGENT_PROBE_WORKER_TIMEOUT");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return failure(runtimeId,"AGENT_PROBE_INTERRUPTED");
        } catch (Exception ex) {
            return failure(runtimeId,"AGENT_PROBE_DISPATCH_FAILED");
        } finally {
            if (job != null) { jobs.cancel(job.id()); }
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void maintain() {
        jobs.expire();
        jobs.publications().forEach(this::publish);
    }

    void publish(UUID id) {
        try { rabbit.convertAndSend("",QUEUE,id.toString()); }
        catch (org.springframework.amqp.AmqpException ignored) { /* 持久 job 由有界发布恢复接管。 */ }
    }

    public void process(UUID id) {
        UUID owner = UUID.randomUUID();
        var claimed = jobs.claim(id,owner);
        if (claimed.isEmpty()) { return; }
        var job = claimed.get();
        FutureTask<CodexProviderTestResult> task = new FutureTask<>(() -> run(job));
        Thread thread = Thread.ofVirtual().start(task);
        try {
            while (!task.isDone() && OffsetDateTime.now().isBefore(job.deadline())) {
                var current = jobs.find(id).orElseThrow();
                if (!"RUNNING".equals(current.status()) || !owner.equals(current.owner())) { return; }
                TimeUnit.MILLISECONDS.sleep(50);
            }
            if (task.isDone()) {
                jobs.finish(id,owner,json.writeValueAsString(task.get()));
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            try { jobs.finish(id,owner,json.writeValueAsString(failure(job.runtimeId(),"AGENT_PROBE_WORKER_FAILED"))); }
            catch (com.fasterxml.jackson.core.JsonProcessingException impossible) { throw new IllegalStateException(impossible); }
        } finally {
            task.cancel(true);
            thread.interrupt();
            boolean interrupted = Thread.interrupted();
            try { thread.join(2500); }
            catch (InterruptedException ex) { interrupted = true; }
            finally { if (interrupted) { Thread.currentThread().interrupt(); } }
            jobs.expire();
        }
    }

    private CodexProviderTestResult run(CodexProbeJobRepository.Job job) throws Exception {
        configurationAccess.requireActive(job.configurationUserId());
        var profile = profiles.findEnabledById(job.profileId(), job.configurationUserId()).orElseThrow();
        if (job.runId() != null) {
            var run = profiles.findProbeRun(job.runId(), job.configurationUserId()).orElseThrow();
            if (!job.profileId().equals(run.getModelProfileId())) { throw ModelExecutionSelection.missing(); }
        }
        var runtime = runtimes.requireEnabled(job.runtimeId());
        if (!"CODEX".equals(runtime.getAgentType())) { throw new IllegalArgumentException("CODEX_REQUIRED"); }
        CodexProviderResolvedConfig config;
        if (job.payload() != null) {
            config = json.readValue(cipher.decrypt(json.readValue(job.payload(),CredentialCipher.EncryptedSecret.class)),
                    CodexProviderResolvedConfig.class);
        } else {
            String key = profile.getApiKeyEncrypted() == null ? "" : new String(cipher.decrypt(
                    json.readValue(profile.getApiKeyEncrypted(),CredentialCipher.EncryptedSecret.class)),StandardCharsets.UTF_8);
            config = new CodexProviderResolvedConfig(true,runtime.getExecutablePath(),
                    CodexProviderType.valueOf(profile.getModelProviderType()),profile.getBaseUrl(),profile.getModel(),
                    CodexProviderWireApi.valueOf(profile.getWireApi()),key,profile.getTimeoutSeconds(),profile.getMaxRuntimeSeconds());
        }
        if (OffsetDateTime.now().isAfter(job.deadline())) { return failure(job.runtimeId(),"AGENT_PROBE_WORKER_TIMEOUT"); }
        return new ModelExecutionSelection(job.configurationUserId(), job.profileId(), job.runtimeId())
                .auditProbe(probes.test(config,runtime), new cn.rvport.backend.agentruntime.AgentModelRuntimeConfig(runtime, profile, config));
    }

    static CodexProviderTestResult failure(UUID runtimeId,String code) {
        return new CodexProviderTestResult(false,"FAILED","Agent worker 探针未完成："+code,Instant.now(),
                "CODEX",runtimeId,false,false,List.of(),Map.of("errorCode",code,
                "message_en","Agent worker probe did not complete: "+code));
    }
}
