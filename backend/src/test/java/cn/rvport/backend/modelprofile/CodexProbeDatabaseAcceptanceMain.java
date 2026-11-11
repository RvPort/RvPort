package cn.rvport.backend.modelprofile;

import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

/** 仅本地隔离数据库验收；事务回滚所有夹具，不连接远程环境。 */
public final class CodexProbeDatabaseAcceptanceMain {
    public static void main(String[] args) throws Exception {
        try (var connection = DriverManager.getConnection(System.getenv("RVPORT_DB_URL"),
                System.getenv("RVPORT_DB_USER"), System.getenv("RVPORT_DB_PASSWORD"))) {
            connection.setAutoCommit(false);
            try {
                var dsl = DSL.using(connection, SQLDialect.POSTGRES);
                UUID configurationUserId = dsl.fetchOne("SELECT id FROM users WHERE username='admin'").get("id", UUID.class);
                var repository = new CodexProbeJobRepository(dsl);
                var id = UUID.randomUUID();
                var owner = UUID.randomUUID();
                var profile = UUID.randomUUID();
                var runtime = UUID.randomUUID();
                var future = OffsetDateTime.now().plusSeconds(60);
                dsl.execute("INSERT INTO model_profiles(id,name,model_provider_type,wire_api,owner_user_id) VALUES (?,?,'OPENAI_COMPATIBLE','CHAT',?)",
                        profile, "acceptance-" + profile, configurationUserId);
                repository.create(id, null, profile, runtime, configurationUserId, "encrypted-fixture", future);
                require(repository.claim(id, owner).isPresent(), "claim");
                require(repository.claim(id, UUID.randomUUID()).isEmpty(), "duplicate claim");
                require(!repository.finish(id, UUID.randomUUID(), "{}"), "wrong owner");
                require(repository.finish(id, owner, "{}"), "finish");
                require(!repository.finish(id, owner, "{}"), "late duplicate result");
                repository.cancel(id);
                require(repository.find(id).orElseThrow().status().equals("DONE"), "terminal immutable");
                require(repository.find(id).orElseThrow().payload() == null, "completed secret erased");

                var expired = UUID.randomUUID();
                repository.create(expired, null, profile, runtime, configurationUserId, "encrypted-fixture", OffsetDateTime.now().minusSeconds(60));
                require(repository.claim(expired, owner).isEmpty(), "expired claim");
                repository.expire();
                require(repository.find(expired).orElseThrow().status().equals("TIMED_OUT"), "expiry");
                require(repository.find(expired).orElseThrow().payload() == null, "expired secret erased");

                var queued = UUID.randomUUID();
                repository.create(queued, null, profile, runtime, configurationUserId, "encrypted-fixture", future);
                for (int attempt = 0; attempt < 3; attempt++) {
                    dsl.execute("UPDATE codex_probe_jobs SET last_published_at=CURRENT_TIMESTAMP-INTERVAL '10 seconds' WHERE id=?", queued);
                    require(repository.publications().contains(queued), "bounded publication " + attempt);
                }
                dsl.execute("UPDATE codex_probe_jobs SET last_published_at=CURRENT_TIMESTAMP-INTERVAL '10 seconds' WHERE id=?", queued);
                require(!repository.publications().contains(queued), "publication exhausted");
                repository.cancel(queued);
                require(repository.find(queued).orElseThrow().status().equals("CANCELLED"), "cancelled");
                require(repository.find(queued).orElseThrow().payload() == null, "cancelled secret erased");

                var run = UUID.randomUUID();
                dsl.execute("INSERT INTO model_profile_agent_probe_runs(id,model_profile_id,owner_user_id) VALUES (?,?,?)", run, profile, configurationUserId);
                var first = repository.create(UUID.randomUUID(), run, profile, runtime, configurationUserId, null, future);
                var second = repository.create(UUID.randomUUID(), run, profile, runtime, configurationUserId, null, future);
                require(first.id().equals(second.id()), "run/runtime unique");

                // 验证真实 PostgreSQL 时间比较，迟到恢复结果不得覆盖较新摘要。
                var profiles = new ModelProfileRepository(dsl);
                var runtimes = new cn.rvport.backend.agentruntime.AgentRuntimeRepository(dsl);
                var selectedProfile = profiles.findById(profile, configurationUserId).orElseThrow();
                var selectedRuntime = runtimes.list().getFirst();
                var newer = OffsetDateTime.now().plusSeconds(10);
                var older = newer.minusSeconds(1);
                var details = org.jooq.JSONB.valueOf("{}");
                profiles.updateTestResult(selectedProfile.getId(), "SUCCESS", "newer", newer, "CODEX",
                        true, true, details, selectedProfile.getCreatedBy());
                profiles.updateTestResult(selectedProfile.getId(), "FAILED", "older", older, "CODEX",
                        false, false, details, selectedProfile.getCreatedBy());
                require("newer".equals(profiles.findById(org.mockito.ArgumentMatchers.eq(selectedProfile.getId()), org.mockito.ArgumentMatchers.any()).orElseThrow().getLastTestMessage()),
                        "profile summary rejects stale recovery");
                runtimes.updateProbeResult(selectedRuntime.getId(), "SUCCESS", "newer", details, newer);
                runtimes.updateProbeResult(selectedRuntime.getId(), "FAILED", "older", details, older);
                require("newer".equals(runtimes.findById(selectedRuntime.getId()).orElseThrow().getLastProbeMessage()),
                        "runtime summary rejects stale recovery");
                var storedRun = dsl.fetchOne("SELECT id FROM model_profile_agent_probe_runs WHERE finished_at IS NOT NULL LIMIT 1")
                        .get("id", UUID.class);
                var before = profiles.findProbeRun(org.mockito.ArgumentMatchers.eq(storedRun), org.mockito.ArgumentMatchers.any()).orElseThrow();
                require(profiles.updateProbeRunStatus(storedRun, AgentProbeRunStatus.RUNNING, "late", older, null) == null,
                        "terminal run cannot restart");
                require(profiles.updateProbeRunStatus(storedRun, AgentProbeRunStatus.FAILED, "late", null, newer) == null,
                        "late run completion rejected");
                require(before.equals(profiles.findProbeRun(org.mockito.ArgumentMatchers.eq(storedRun), org.mockito.ArgumentMatchers.any()).orElseThrow()), "terminal run immutable");
                System.out.println("CODEX_PROBE_DATABASE_ACCEPTANCE_PASSED");
            } finally {
                connection.rollback();
            }
        }
    }

    private static void require(boolean value, String assertion) {
        if (!value) { throw new AssertionError(assertion); }
    }
}
