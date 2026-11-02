package cn.rvport.backend.modelprofile;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CodexProbeJobRepository {
    private final DSLContext dsl;
    public CodexProbeJobRepository(DSLContext dsl) { this.dsl = dsl; }

    public record Job(UUID id, UUID runId, UUID profileId, UUID runtimeId, String payload,
                      String status, UUID owner, OffsetDateTime deadline, String result, OffsetDateTime finishedAt) { }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Job create(UUID id, UUID runId, UUID profileId, UUID runtimeId, String encrypted, OffsetDateTime deadline) {
        dsl.execute("""
                INSERT INTO codex_probe_jobs(id,run_id,profile_id,runtime_id,payload_encrypted,deadline)
                VALUES (?,?,?,?,?,?::timestamptz) ON CONFLICT DO NOTHING
                """, id, runId, profileId, runtimeId, encrypted, deadline);
        return runId == null ? find(id).orElseThrow() : map(dsl.fetchOne(
                "SELECT * FROM codex_probe_jobs WHERE run_id=? AND runtime_id=?", runId, runtimeId));
    }

    public Optional<Job> find(UUID id) {
        return Optional.ofNullable(dsl.fetchOne("SELECT * FROM codex_probe_jobs WHERE id=?", id)).map(this::map);
    }

    public Optional<Job> claim(UUID id, UUID owner) {
        return Optional.ofNullable(dsl.fetchOne("""
                UPDATE codex_probe_jobs SET status='RUNNING',owner_id=?
                WHERE id=? AND status='QUEUED' AND deadline>CURRENT_TIMESTAMP RETURNING *
                """, owner, id)).map(this::map);
    }

    public boolean finish(UUID id, UUID owner, String result) {
        return dsl.execute("""
                UPDATE codex_probe_jobs SET status='DONE',result_json=?::jsonb,payload_encrypted=NULL,finished_at=CURRENT_TIMESTAMP
                WHERE id=? AND owner_id=? AND status='RUNNING' AND deadline>CURRENT_TIMESTAMP
                """, result, id, owner) == 1;
    }

    public void cancel(UUID id) {
        dsl.execute("""
                UPDATE codex_probe_jobs SET status='CANCELLED',payload_encrypted=NULL,finished_at=CURRENT_TIMESTAMP
                WHERE id=? AND status IN ('QUEUED','RUNNING')
                """, id);
    }

    public List<UUID> publications() {
        return dsl.fetch("""
                UPDATE codex_probe_jobs SET publish_attempts=publish_attempts+1,last_published_at=CURRENT_TIMESTAMP
                WHERE id IN (SELECT id FROM codex_probe_jobs WHERE status='QUEUED'
                  AND deadline>CURRENT_TIMESTAMP AND publish_attempts<3
                  AND (last_published_at IS NULL OR last_published_at<CURRENT_TIMESTAMP-INTERVAL '5 seconds')
                  ORDER BY created_at LIMIT 100 FOR UPDATE SKIP LOCKED) RETURNING id
                """).getValues("id", UUID.class);
    }

    public void expire() {
        dsl.execute("""
                UPDATE codex_probe_jobs SET status='TIMED_OUT',payload_encrypted=NULL,finished_at=CURRENT_TIMESTAMP
                WHERE status IN ('QUEUED','RUNNING') AND deadline<=CURRENT_TIMESTAMP
                """);
        dsl.execute("DELETE FROM codex_probe_jobs WHERE finished_at<CURRENT_TIMESTAMP-INTERVAL '1 day'");
    }

    public List<Job> recoverable() {
        return dsl.fetch("""
                SELECT j.* FROM codex_probe_jobs j
                JOIN model_profile_agent_probe_runs r ON r.id=j.run_id
                WHERE r.status IN ('PENDING','RUNNING')
                AND j.status IN ('DONE','TIMED_OUT','CANCELLED')
                AND EXISTS (SELECT 1 FROM agent_runtimes a WHERE a.id=j.runtime_id)
                ORDER BY j.created_at LIMIT 100
                """).map(this::map);
    }

    public void reconcileRuns() {
        // 每个 runtime 最多三项 600 秒 probe，加有限清理宽限；只处理本机制创建过 job 的 run。
        dsl.execute("""
                UPDATE model_profile_agent_probe_runs r SET
                  status=CASE
                    WHEN (SELECT count(DISTINCT p.agent_runtime_id) FROM model_profile_agent_probe_results p
                          WHERE p.run_id=r.id)<jsonb_array_length(r.selected_agent_runtime_ids) THEN 'TIMEOUT'
                    WHEN NOT EXISTS (SELECT 1 FROM model_profile_agent_probe_results p WHERE p.run_id=r.id AND p.status<>'SUCCESS') THEN 'SUCCESS'
                    WHEN EXISTS (SELECT 1 FROM model_profile_agent_probe_results p WHERE p.run_id=r.id AND p.status='SUCCESS') THEN 'PARTIAL_SUCCESS'
                    ELSE 'FAILED' END,
                  message='模型 Agent 测试已归并；未完成分项超过恢复期限时按超时结束 / Agent probe results reconciled; unfinished items expire at the recovery deadline',
                  finished_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP
                WHERE r.status IN ('PENDING','RUNNING')
                  AND EXISTS (SELECT 1 FROM codex_probe_jobs j WHERE j.run_id=r.id)
                  AND ((SELECT count(DISTINCT p.agent_runtime_id) FROM model_profile_agent_probe_results p
                        WHERE p.run_id=r.id)>=jsonb_array_length(r.selected_agent_runtime_ids)
                       OR r.created_at+(jsonb_array_length(r.selected_agent_runtime_ids)*1805)*INTERVAL '1 second'<CURRENT_TIMESTAMP)
                """);
    }

    private Job map(Record record) {
        JSONB result = record.get("result_json", JSONB.class);
        return new Job(record.get("id",UUID.class), record.get("run_id",UUID.class),
                record.get("profile_id",UUID.class), record.get("runtime_id",UUID.class),
                record.get("payload_encrypted",String.class), record.get("status",String.class),
                record.get("owner_id",UUID.class), record.get("deadline",OffsetDateTime.class),
                result == null ? null : result.data(), record.get("finished_at", OffsetDateTime.class));
    }
}
