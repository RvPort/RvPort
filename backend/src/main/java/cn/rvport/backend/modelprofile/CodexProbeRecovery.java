package cn.rvport.backend.modelprofile;

import cn.rvport.backend.codexprovider.CodexProviderTestResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.jooq.JSONB;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 在 API 重启后仍归并已落盘的 worker 终态；不重跑模型调用。 */
@Service
public class CodexProbeRecovery {
    private final CodexProbeJobRepository jobs;
    private final ModelProfileRepository profiles;
    private final ObjectMapper json;
    private final cn.rvport.backend.agentruntime.AgentRuntimeRepository runtimes;

    public CodexProbeRecovery(CodexProbeJobRepository jobs, ModelProfileRepository profiles, ObjectMapper json,
            cn.rvport.backend.agentruntime.AgentRuntimeRepository runtimes) {
        this.jobs = jobs;
        this.profiles = profiles;
        this.json = json;
        this.runtimes = runtimes;
    }

    @Scheduled(fixedDelay = 1000)
    @org.springframework.transaction.annotation.Transactional
    public void recover() {
        for (var job : jobs.recoverable()) {
            var run = profiles.findProbeRun(job.runId(), job.configurationUserId());
            if (run.isEmpty() || !job.profileId().equals(run.get().getModelProfileId())) { continue; }
            if (profiles.listProbeResultsByRun(job.runId(), job.configurationUserId()).stream()
                    .anyMatch(result -> job.runtimeId().equals(result.getAgentRuntimeId()))) { continue; }
            CodexProviderTestResult result;
            // 恢复的是既有终态；不能以恢复时刻把历史失败伪装为最新测试。
            var testedAt = (job.finishedAt() == null ? job.deadline() : job.finishedAt()).toInstant();
            try {
                if ("DONE".equals(job.status())) {
                    result = json.readValue(job.result(), CodexProviderTestResult.class);
                    testedAt = result.testedAt();
                } else {
                    result = CodexProbeDispatcher.failure(job.runtimeId(), "AGENT_PROBE_WORKER_" + job.status());
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                result = CodexProbeDispatcher.failure(job.runtimeId(), "AGENT_PROBE_RESULT_INVALID");
            }
            var started = run.get().getCreatedAt();
            var finished = OffsetDateTime.now();
            try {
                profiles.createProbeResult(ModelProfileService.probeResultId(job.runId(), job.runtimeId()),
                        job.runId(), job.profileId(), job.runtimeId(), "CODEX", result.status(),
                        result.basicAvailable(), result.agentCompatible(), JSONB.valueOf(json.writeValueAsString(result.checks())),
                        JSONB.valueOf(json.writeValueAsString(result.details())),
                        result.success() ? null : "AGENT_PROBE_WORKER_FAILED", result.message(), started, finished,
                        Duration.between(started, finished).toMillis());
                var tested = OffsetDateTime.ofInstant(testedAt, java.time.ZoneOffset.UTC);
                var details = JSONB.valueOf(json.writeValueAsString(result.details()));
                profiles.updateTestResult(job.profileId(), result.status(), result.message(), tested, "CODEX",
                        result.basicAvailable(), result.agentCompatible(), details, job.configurationUserId());
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw new IllegalStateException("PROBE_RESULT_SERIALIZATION_FAILED", ex);
            }
        }
        jobs.reconcileRuns();
    }
}
