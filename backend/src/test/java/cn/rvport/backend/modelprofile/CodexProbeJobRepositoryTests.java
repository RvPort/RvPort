package cn.rvport.backend.modelprofile;

import static org.assertj.core.api.Assertions.*;
import cn.rvport.backend.testfixture.JooqMockDatabase;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

class CodexProbeJobRepositoryTests {
    private static final UUID OWNER_ID = UUID.fromString("a0000000-0000-0000-0000-000000000001");
    final UUID id=UUID.randomUUID(), run=UUID.randomUUID(), profile=UUID.randomUUID(), runtime=UUID.randomUUID(), owner=UUID.randomUUID();
    final OffsetDateTime deadline=OffsetDateTime.now().plusSeconds(10);
    final Field<?>[] fields={DSL.field("id",UUID.class),DSL.field("run_id",UUID.class),
            DSL.field("profile_id",UUID.class),DSL.field("runtime_id",UUID.class),DSL.field("payload_encrypted",String.class),
            DSL.field("status",String.class),DSL.field("owner_id",UUID.class),DSL.field("deadline",OffsetDateTime.class),DSL.field("result_json",JSONB.class),DSL.field("finished_at",OffsetDateTime.class), DSL.field("configuration_user_id",UUID.class)};

    @Test void recordsMapAndCreateIsIdempotent() throws Exception {
        try(var db=new JooqMockDatabase()) {
            var repo=new CodexProbeJobRepository(db.dsl());
            var rows=db.result(fields,new Object[]{id,run,profile,runtime,"cipher","DONE",owner,deadline,JSONB.valueOf("{}"),deadline,OWNER_ID});
            db.enqueueRows(1).enqueueResult(rows).enqueueRows(0).enqueueResult(rows).enqueueResult(db.result(fields));
            var created=repo.create(id, null, profile, runtime, OWNER_ID, "cipher", deadline);
            assertThat(created.result()).isEqualTo("{}");
            assertThat(created.finishedAt()).isEqualTo(deadline);
            assertThat(repo.create(id, run, profile, runtime, OWNER_ID, "cipher", deadline).runId()).isEqualTo(run);
            assertThat(repo.find(id)).isEmpty();
            assertThat(db.executions().getFirst().normalizedSql().toLowerCase(java.util.Locale.ROOT))
                    .contains("?::timestamptz", "on conflict do nothing");
            db.verifyComplete();
        }
    }
    @Test void ownershipDeadlineAndSecretRemovalAreEnforcedInMutations() throws Exception {
        try(var db=new JooqMockDatabase()) {
            var repo=new CodexProbeJobRepository(db.dsl());
            db.enqueueResult(db.result(fields,new Object[]{id,run,profile,runtime,"cipher","RUNNING",owner,deadline,null,null,OWNER_ID}));
            db.enqueueResult(db.result(fields)).enqueueRows(1).enqueueRows(0).enqueueRows(1).enqueueRows(1).enqueueRows(1);
            assertThat(repo.claim(id,owner).orElseThrow().result()).isNull();
            assertThat(repo.claim(id,owner)).isEmpty();
            assertThat(repo.finish(id,owner,"{}")).isTrue();
            assertThat(repo.finish(id,owner,"{}")).isFalse();
            repo.cancel(id); repo.expire();
            assertThat(db.executions().getFirst().normalizedSql().toLowerCase(java.util.Locale.ROOT)).contains("status='queued'", "deadline>current_timestamp");
            assertThat(db.executions().get(2).normalizedSql().toLowerCase(java.util.Locale.ROOT)).contains("owner_id=?", "status='running'", "payload_encrypted=null");
            assertThat(db.executions().get(4).normalizedSql().toLowerCase(java.util.Locale.ROOT)).contains("status in ('queued','running')");
            db.verifyComplete();
        }
    }
    @Test void recoveryIsBoundedAndDoesNotRerunModels() throws Exception {
        try(var db=new JooqMockDatabase()) {
            var repo=new CodexProbeJobRepository(db.dsl());
            db.enqueueResult(db.result(new Field<?>[]{DSL.field("id",UUID.class)},new Object[]{id}));
            db.enqueueResult(db.result(fields,new Object[]{id,run,profile,runtime,null,"DONE",owner,deadline,JSONB.valueOf("{}"),deadline,OWNER_ID}));
            db.enqueueRows(1);
            assertThat(repo.publications()).containsExactly(id);
            assertThat(repo.recoverable()).hasSize(1);
            repo.reconcileRuns();
            assertThat(db.executions().getFirst().normalizedSql().toLowerCase(java.util.Locale.ROOT)).contains("publish_attempts<3", "limit 100", "skip locked", "status='queued'");
            assertThat(db.lastExecution().normalizedSql().toLowerCase(java.util.Locale.ROOT)).contains("partial_success", "timeout", "1805", "r.status in ('pending','running')");
            db.verifyComplete();
        }
    }
}
