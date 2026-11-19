package cn.rvport.backend.worker;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.rvport.backend.config.WorkerCleanupProperties;
import cn.rvport.backend.config.WorkerProperties;
import cn.rvport.backend.config.RvPortAppMode;
import cn.rvport.backend.task.*;
import cn.rvport.backend.testfixture.JooqMockDatabase;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

class TaskDomainFinalizationTests {
    private TaskView task(TaskStatus status, String type, String code, boolean finished) {
        var now = OffsetDateTime.now();
        return new TaskView(UUID.randomUUID(), UUID.randomUUID(), type, status, 20, "sensitive /server/path",
                UUID.randomUUID(), now, now, now, finished ? now : null, 3600, code, "password=secret", 0);
    }

    @Test
    void updatesOnlyTaskBoundNonterminalRecordsAndPreservesMetadata() throws Exception {
        try (var db = new JooqMockDatabase()) {
            var repository = new TaskDomainFinalizationRepository(db.dsl());
            var task = task(TaskStatus.FAILED, "DEPENDENCY_ANALYSIS", "WORKER_LOST", true);
            for (int i = 0; i < 16; i++) db.enqueueRows(1);
            assertThat(repository.finish(task)).isEqualTo(16);
            for (var execution : db.executions()) {
                assertThat(execution.normalizedSql()).contains("task_id = cast(? as uuid)", "status in")
                        .doesNotContain("sbom");
                assertThat(execution.bindings()).contains(task.id(), "PENDING", "QUEUED", "RUNNING")
                        .doesNotContain("sensitive /server/path", "password=secret");
            }
            assertThat(db.executions().stream().map(e -> e.normalizedSql()))
                    .anyMatch(sql -> sql.contains("jsonb_build_object") && sql.contains("coalesce"));
            var testRunUpdate = db.executions().stream().filter(e -> e.normalizedSql().startsWith("update test_runs ")).findFirst().orElseThrow();
            assertThat(testRunUpdate.normalizedSql()).contains("concat_ws", "output_summary").doesNotContain("jsonb_build_object");
            assertThat(testRunUpdate.bindings()).contains("WORKER_LOST: " + WorkerCleanupItemService.WORKER_LOST_MESSAGE);
            db.verifyComplete();
        }
    }

    @Test
    void rejectsNonFailureTerminalsAndHandlesAllFailureMappings() throws Exception {
        try (var db = new JooqMockDatabase()) {
            var repository = new TaskDomainFinalizationRepository(db.dsl());
            for (var status : List.of(TaskStatus.SUCCEEDED, TaskStatus.RUNNING, TaskStatus.QUEUED, TaskStatus.PENDING)) {
                assertThat(repository.finish(task(status, "KB_SCAN", null, false))).isZero();
            }
            assertThat(db.executions()).isEmpty();
            for (var status : List.of(TaskStatus.CANCELLED, TaskStatus.TIMEOUT)) {
                for (int i = 0; i < 17; i++) db.enqueueRows(0);
                assertThat(repository.finish(task(status, "DEPENDENCY_AUTO_FIX", "invalid /code", false))).isZero();
                assertThat(db.lastExecution().bindings()).contains(status == TaskStatus.CANCELLED ? "CANCELLED" : "FAILED");
            }
            db.verifyComplete();
        }
    }

    @Test void reportFailureReleasesOnlyItsUnpublishedGeneration() throws Exception {
        try(var db=new JooqMockDatabase()) {
            for(int i=0;i<17;i++) db.enqueueRows(1);
            var failed=task(TaskStatus.TIMEOUT,"REPORT_GENERATE","WORKER_LOST",true);
            assertThat(new TaskDomainFinalizationRepository(db.dsl()).finish(failed)).isEqualTo(17);
            assertThat(db.lastExecution().sql()).contains("report_review_runs","task_id","r.active","NOT_PUBLISHED");
            assertThat(db.lastExecution().bindings()).contains(failed.id());
            db.verifyComplete();
        }
    }
    @Test
    void candidateQueryUsesStableCursorWithoutWorkerJoin() throws Exception {
        try (var db = new JooqMockDatabase()) {
            var repository = new TaskDomainFinalizationRepository(db.dsl());
            UUID id = UUID.randomUUID();
            Field<UUID> field = DSL.field("id", UUID.class);
            db.enqueueResult(db.result(new Field<?>[]{field}, new Object[]{id}));
            assertThat(repository.candidates(null, 10)).containsExactly(id);
            db.enqueueResult(db.result(new Field<?>[]{field}));
            assertThat(repository.candidates(id, 10)).isEmpty();
            assertThat(db.lastExecution().normalizedSql()).contains("t.id > cast(? as uuid)", "order by t.id asc")
                    .doesNotContain("worker_instances");
        }
    }

    @Test
    void locksTaskAndPublishesOnlyChangedDomains() {
        var repository = mock(TaskDomainFinalizationRepository.class);
        var tasks = mock(TaskRepository.class);
        var events = mock(TaskEventService.class);
        var service = new TaskDomainFinalizationService(repository, tasks, events);
        var task = task(TaskStatus.FAILED, "KB_SCAN", null, true);
        assertThat(service.reconcile(task.id())).isZero();
        var succeeded = task(TaskStatus.SUCCEEDED, "KB_SCAN", null, true);
        when(tasks.lockById(succeeded.id())).thenReturn(succeeded);
        assertThat(service.reconcile(succeeded.id())).isZero();
        verify(repository, never()).finish(succeeded);
        when(tasks.lockById(task.id())).thenReturn(task);
        assertThat(service.reconcile(task.id())).isZero();
        verifyNoInteractions(events);
        when(repository.finish(task)).thenReturn(2);
        assertThat(service.reconcile(task.id())).isEqualTo(2);
        verify(events).appendProjectContextChanged(task.projectId(), task.stageId(), task.resource(),
                List.of("status", "finishedAt"), "任务关联运行状态已收敛");
        when(repository.finish(task)).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(() -> service.reconcile(task.id())).isInstanceOf(IllegalStateException.class);
        verifyNoMoreInteractions(events);
    }

    @Test
    void schedulerIsBoundedAdvancesPastFailuresAndResetsCursor() {
        var repository = mock(TaskDomainFinalizationRepository.class);
        var service = mock(TaskDomainFinalizationService.class);
        var workerProperties = new WorkerProperties(true, "test", RvPortAppMode.API, 1, 8, 10, 60);
        var disabled = new TaskDomainReconciliationScheduler(new WorkerCleanupProperties(false, 100, 300, 24, 1), repository, service, workerProperties);
        disabled.reconcile();
        verifyNoInteractions(repository, service);
        var scheduler = new TaskDomainReconciliationScheduler(new WorkerCleanupProperties(true, 100, 300, 24, 1), repository, service, workerProperties);
        var workerScheduler = new TaskDomainReconciliationScheduler(new WorkerCleanupProperties(true, 100, 300, 24, 1),
                repository, service, new WorkerProperties(true, "test", RvPortAppMode.WORKER, 1, 8, 10, 60));
        workerScheduler.reconcile();
        verifyNoInteractions(repository, service);
        UUID id = UUID.randomUUID();
        when(repository.candidates(null, 1)).thenReturn(List.of(id));
        when(repository.candidates(id, 1)).thenReturn(List.of());
        when(service.reconcile(id)).thenThrow(new IllegalStateException("secret"));
        scheduler.reconcile();
        scheduler.reconcile();
        doReturn(2).when(service).reconcile(id);
        scheduler.reconcile();
        verify(repository, times(2)).candidates(null, 1);
        verify(repository).candidates(id, 1);
    }
}
