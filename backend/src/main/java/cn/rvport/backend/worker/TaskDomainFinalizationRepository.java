package cn.rvport.backend.worker;

import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.task.TaskView;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
public class TaskDomainFinalizationRepository {
    // Explicit schema mapping: no user-controlled table/column names or guessed latest records.
    static final List<Domain> DOMAINS = List.of(
            new Domain("SOURCE_GIT_SYNC", "source_imports", null, null, "error_message", null),
            new Domain("SOURCE_DEPLOY", "source_deploy_runs", "finished_at", "summary", null, null),
            new Domain("ENV_TEST_CONNECTION", "environment_snapshots", null, "raw_snapshot", null, null),
            new Domain("ENV_COLLECT", "environment_snapshots", null, "raw_snapshot", null, null),
            new Domain("DEPENDENCY_ANALYSIS", "dependency_analysis_runs", "finished_at", "summary", null, null),
            new Domain("DEPENDENCY_AUTO_FIX", "dependency_auto_fix_runs", "finished_at", "summary", "error_summary", null),
            new Domain("KB_SCAN", "kb_scan_runs", "finished_at", "summary", null, null),
            new Domain("AI_MIGRATION", "ai_migration_runs", "finished_at", "summary", null, null),
            new Domain("FLOATING_POINT_ANALYSIS", "specialized_analysis_runs", "finished_at", "summary", null, null),
            new Domain("MEMORY_ORDER_ANALYSIS", "specialized_analysis_runs", "finished_at", "summary", null, null),
            new Domain("BUILD_VALIDATION", "build_runs", "finished_at", "build_facts", null, "failure_category"),
            new Domain("AUTOMATED_TEST", "test_runs", "finished_at", null, "output_summary", null),
            new Domain("TEST_LOG_COMPARE", "test_comparison_runs", "finished_at", null, "error_message", "error_code"),
            new Domain("REPORT_GENERATE", "reports", null, "summary", null, null),
            new Domain("REPORT_COMPARE", "report_comparison_runs", "finished_at", "summary", "error_message", "error_code"),
            new Domain("RECHECK_DIFF", "recheck_runs", null, "summary", null, null),
            new Domain("ARCHIVE_PROJECT", "archives", null, "summary", null, null));
    static final Domain TOOLS = new Domain(null, "tool_runs", "finished_at", "summary", null, null);
    private final DSLContext dsl;

    public TaskDomainFinalizationRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public int finish(TaskView task) {
        if (!List.of(TaskStatus.FAILED, TaskStatus.CANCELLED, TaskStatus.TIMEOUT).contains(task.status())) {
            return 0;
        }
        int count = 0;
        for (Domain domain : DOMAINS.stream().collect(java.util.stream.Collectors.toMap(
                Domain::table, value -> value, (first, second) -> first, java.util.LinkedHashMap::new)).values()) {
            count += finish(domain, task);
        }
        count += finish(TOOLS, task);
        if ("REPORT_GENERATE".equals(task.type())) {
            count += dsl.execute("""
                    update report_review_runs r set active=false,ai_summary=jsonb_build_object('execution','FAILED','taskStatus',?::text)
                    from reports p where p.id=r.report_id and p.task_id=? and r.active and r.publish_state='NOT_PUBLISHED'
                    """,task.status().name(),task.id());
        }
        if ("DEPENDENCY_AUTO_FIX".equals(task.type())) {
            // Item status has no TIMEOUT value; task/run retain TIMEOUT and the item records failure.
            count += dsl.update(DSL.table("dependency_auto_fix_items"))
                    .set(DSL.field("status", String.class), task.status() == TaskStatus.CANCELLED ? "CANCELLED" : "FAILED")
                    .set(DSL.field("finished_at", OffsetDateTime.class), end(task))
                    .set(DSL.field("error_summary", String.class), message(task))
                    .where(DSL.field("dependency_auto_fix_run_id", UUID.class).in(
                            dsl.select(DSL.field("id", UUID.class)).from("dependency_auto_fix_runs")
                                    .where(DSL.field("task_id", UUID.class).eq(task.id()))))
                    .and(active("status")).execute();
        }
        return count;
    }

    private int finish(Domain domain, TaskView task) {
        var update = dsl.update(DSL.table(domain.table())).set(DSL.field("status", String.class), task.status().name());
        if (domain.finished() != null) {
            update.set(DSL.field(domain.finished(), OffsetDateTime.class), end(task));
        }
        if (domain.summary() != null) {
            Field<JSONB> summary = DSL.field(domain.summary(), JSONB.class);
            update.set(summary, DSL.field(
                    "coalesce({0}, {4}) || jsonb_build_object('errorCode', cast({1} as text), 'error', cast({2} as text), 'finishedAt', cast({3} as text), 'finalizationSource', 'TASK_TERMINAL_RECONCILIATION')",
                    JSONB.class, summary, DSL.val(code(task)), DSL.val(message(task)), DSL.val(end(task).toString()), DSL.val(JSONB.valueOf("{}"))));
        }
        if (domain.error() != null) {
            Field<String> error = DSL.field(domain.error(), String.class);
            update.set(error, DSL.field("concat_ws(E'\\n', nullif({0}, ''), {1})", String.class,
                    error, DSL.val(code(task) + ": " + message(task))));
        }
        if (domain.errorCode() != null) {
            update.set(DSL.field(domain.errorCode(), String.class), code(task));
        }
        return update.where(DSL.field("task_id", UUID.class).eq(task.id())).and(active("status")).execute();
    }

    public List<UUID> candidates(UUID after, int limit) {
        Condition pending = DSL.falseCondition();
        for (Domain domain : DOMAINS) {
            pending = pending.or(DSL.exists(dsl.selectOne().from(DSL.table(domain.table()).as("r"))
                    .where(DSL.field("r.task_id", UUID.class).eq(DSL.field("t.id", UUID.class)))
                    .and(active("r.status"))));
        }
        pending = pending.or(DSL.exists(dsl.selectOne().from("tool_runs r")
                .where(DSL.field("r.task_id", UUID.class).eq(DSL.field("t.id", UUID.class))).and(active("r.status"))));
        pending = pending.or(DSL.exists(dsl.selectOne().from("dependency_auto_fix_items i")
                .join("dependency_auto_fix_runs r")
                .on(DSL.field("i.dependency_auto_fix_run_id", UUID.class).eq(DSL.field("r.id", UUID.class)))
                .where(DSL.field("r.task_id", UUID.class).eq(DSL.field("t.id", UUID.class)))
                .and(active("i.status"))));
        Field<UUID> id = DSL.field("t.id", UUID.class);
        return dsl.select(id).from("tasks t")
                .where(DSL.field("t.status", String.class).in("FAILED", "CANCELLED", "TIMEOUT", "SUCCEEDED"))
                .and(after == null ? DSL.trueCondition() : id.gt(after)).and(pending)
                .orderBy(id.asc()).limit(limit).fetch(id);
    }

    private static Condition active(String column) {
        return DSL.field(column, String.class).in("PENDING", "QUEUED", "RUNNING");
    }

    private static OffsetDateTime end(TaskView task) {
        return task.finishedAt() == null ? OffsetDateTime.now() : task.finishedAt();
    }

    private static String code(TaskView task) {
        String code = task.errorCode();
        return code != null && code.matches("[A-Z][A-Z0-9_]{0,79}") ? code : "TASK_" + task.status().name();
    }

    private static String message(TaskView task) {
        return "WORKER_LOST".equals(task.errorCode()) ? WorkerCleanupItemService.WORKER_LOST_MESSAGE
                : "任务已结束：" + task.status().name();
    }

    record Domain(String type, String table, String finished, String summary, String error, String errorCode) { }
}
