package cn.rvport.backend.worker;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.rvport.backend.task.*;
import cn.rvport.backend.queue.TaskQueueMessage;
import cn.rvport.backend.report.*;
import cn.rvport.backend.report.review.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 仅使用显式开启的独立验收库，真实行锁验证并发收尾。 */
@EnabledIfEnvironmentVariable(named="RVPORT_REPORT_REVIEW_DB_TEST",matches="true")
class TaskDeliveryRecoveryDatabaseTests {
    Connection connection() throws SQLException {
        return DriverManager.getConnection(System.getenv("RVPORT_MIGRATION_TEST_JDBC_URL"),
                System.getenv("RVPORT_MIGRATION_TEST_USERNAME"),System.getenv("RVPORT_MIGRATION_TEST_PASSWORD"));
    }
    TaskDeliveryRecoveryService recovery(Connection conn) {
        var db=DSL.using(conn,SQLDialect.POSTGRES);var mapper=new ObjectMapper().findAndRegisterModules();
        var tasks=new TaskRepository(db);
        var events=new TaskEventService(new TaskEventRepository(db,mapper),mock(TaskSseBroker.class),mock(TaskSseEventBroadcaster.class));
        return new TaskDeliveryRecoveryService(tasks,new TaskRuntimeHandleRepository(db),
                new TaskDomainFinalizationService(new TaskDomainFinalizationRepository(db),tasks,events),events,
                new TaskSecretPayloadService(new TaskSecretPayloadRepository(db),mock(cn.rvport.backend.security.CredentialCipher.class),mapper));
    }
    @Test void capabilityTimestampBindingExecutesAgainstPostgres() throws Exception {
        try(var conn=connection()) {
            var repository=new ReportWorkerCapabilityRepository(DSL.using(conn,SQLDialect.POSTGRES),new ObjectMapper());
            assertThat(repository.observations("handoff-no-matching-queue",java.time.OffsetDateTime.now().plusDays(1))).isEmpty();
        }
    }
    @Test void rollbackAndCompetingConsumersProduceExactlyOneTerminalEventAndReleaseReportRun() throws Exception {
        UUID project=UUID.randomUUID(),source=UUID.randomUUID(),task=UUID.randomUUID(),report=UUID.randomUUID();
        var message=new TaskQueueMessage(task,"REPORT_GENERATE",project,null,3);
        try(var seed=connection()) {
            var db=DSL.using(seed,SQLDialect.POSTGRES);var mapper=new ObjectMapper().findAndRegisterModules();
            UUID actor=db.fetchOne("select id from users where username='admin'").get(0,UUID.class);
            try {
                db.execute("insert into projects(id,name,stage,status,created_by) values (?,'handoff recovery unit','PROJECT_CREATED','ACTIVE',?)",project,actor);
                db.execute("insert into source_revisions(id,project_id,revision_type,is_active,created_by) values (?,?,'ARCHIVE',true,?)",source,project,actor);
                db.execute("insert into tasks(id,project_id,type,status,input_snapshot,created_by) values (?,?,'REPORT_GENERATE','QUEUED','{}',?)",task,project,actor);
                new ReportRepository(db,mapper).createEvaluation(report,project,source,task,"fixture",actor);
                var selection=new ReportInputSelectionPolicy.Selection(project,source,"ctx",List.of());
                new ReviewWorkspaceRepository(db,mapper).createRun(report,new ReportReviewManifest(project,source,"ctx",ReportMode.STANDARD,
                        selection,new ReportSourceSnapshotService.Manifest(source,"hash",List.of(),null),List.of(),null,null),actor,null);
                try(var interrupted=connection()) {
                    interrupted.setAutoCommit(false);
                    assertThat(recovery(interrupted).reconcile(message)).isTrue();
                    interrupted.rollback();
                }
                assertThat(db.fetchOne("select status from tasks where id=?",task).get(0,String.class)).isEqualTo("QUEUED");
                var start=new CountDownLatch(1);
                try(var pool=Executors.newFixedThreadPool(2)) {
                    Callable<Boolean> consumer=()->{
                        try(var conn=connection()) {
                            conn.setAutoCommit(false);start.await();
                            boolean value=recovery(conn).reconcile(message);conn.commit();return value;
                        }
                    };
                    var first=pool.submit(consumer);var second=pool.submit(consumer);start.countDown();
                    assertThat(first.get(15,TimeUnit.SECONDS)).isTrue();assertThat(second.get(15,TimeUnit.SECONDS)).isTrue();
                }
                assertThat(db.fetchOne("select status,error_code from tasks where id=?",task).intoList())
                        .containsExactly("FAILED","TASK_DELIVERY_EXHAUSTED");
                assertThat(db.fetchOne("select status from reports where id=?",report).get(0,String.class)).isEqualTo("FAILED");
                assertThat(db.fetchOne("select active from report_review_runs where report_id=?",report).get(0,Boolean.class)).isFalse();
                assertThat(db.fetchOne("select count(*) from task_events where task_id=? and event_type=?",task,TaskEventType.FAILED.value()).get(0,Long.class)).isEqualTo(1L);
            } finally {
                db.execute("delete from report_review_runs where report_id=?",report);
                db.execute("delete from reports where id=?",report);
                db.execute("delete from task_events where project_id=?",project);
                db.execute("delete from tasks where id=?",task);
                db.execute("delete from source_revisions where id=?",source);
                db.execute("delete from projects where id=?",project);
            }
        }
    }
}
