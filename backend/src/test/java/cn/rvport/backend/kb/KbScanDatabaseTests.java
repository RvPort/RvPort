package cn.rvport.backend.kb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import cn.rvport.backend.audit.AuditLogService;
import cn.rvport.backend.buildvalidation.*;
import cn.rvport.backend.config.RvPortProperties;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentRepository;
import cn.rvport.backend.source.SourceRepository;
import cn.rvport.backend.task.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import liquibase.Liquibase;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.database.core.PostgresDatabase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.jooq.impl.DSL;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** 仅连接显式提供的隔离测试库；每次创建独立 schema，验证真实迁移和事务。 */
@EnabledIfEnvironmentVariable(named = "RVPORT_MIGRATION_TEST_ENABLED", matches = "true")
class KbScanDatabaseTests {
    @TempDir Path sourcePath;
    UUID project = UUID.randomUUID(), source = UUID.randomUUID(), environment = UUID.randomUUID(), actor;
    JdbcTemplate jdbc;

    @Test void upgradesLegacyRowsEnforcesConstraintsOrdersRunsAndSerializesCreation() throws Exception {
        String schema = "kb_test_" + UUID.randomUUID().toString().replace("-", "");
        String baseUrl = System.getenv("RVPORT_MIGRATION_TEST_JDBC_URL");
        String user = System.getenv("RVPORT_MIGRATION_TEST_USERNAME");
        String password = System.getenv("RVPORT_MIGRATION_TEST_PASSWORD");
        var admin = new DriverManagerDataSource(baseUrl, user, password);
        new JdbcTemplate(admin).execute("create schema " + schema);
        var dataSource = new DriverManagerDataSource(baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public", user, password);
        jdbc = new JdbcTemplate(dataSource);
        try {
            try (var connection = dataSource.getConnection()) {
                var database = new PostgresDatabase();
                database.setConnection(new JdbcConnection(connection));
                database.setDefaultSchemaName(schema);
                try (var liquibase = new Liquibase("db/changelog/db.changelog-master.yaml", new ClassLoaderResourceAccessor(), database)) {
                    var changes = liquibase.getDatabaseChangeLog().getChangeSets();
                    int baseline = (int) changes.stream().takeWhile(change -> !change.getFilePath().contains("/0128-")).count();
                    int upgrade = (int) changes.stream().filter(change -> change.getFilePath().contains("/0128-")).count();
                    assertThat(upgrade).isPositive();
                    liquibase.update(baseline, new Contexts(), new LabelExpression());
                    actor = jdbc.queryForObject("select id from users where username='admin'", UUID.class);
                    jdbc.update("insert into projects(id,name,stage,status,created_by) values (?, 'kb migration test','PROJECT_CREATED','ACTIVE',?)", project, actor);
                    jdbc.update("insert into source_revisions(id,project_id,revision_type,is_active,working_copy_path,created_by) values (?,?,'GIT',true,?,?)", source, project, sourcePath.toString(), actor);
                    UUID legacy = scan(task("2026-01-01T00:00:00Z"), "SUCCEEDED", null);
                    liquibase.update(upgrade, new Contexts(), new LabelExpression());
                    assertThat(jdbc.queryForMap("select source_environment_id,runtime_environment_version,build_run_id from kb_scan_runs where id=?", legacy).values())
                            .containsOnlyNulls();
                    assertThat(jdbc.queryForObject("select status from kb_scan_runs where id=?", String.class, legacy)).isEqualTo("SUCCEEDED");
                }
            }
            UUID credential = UUID.randomUUID();
            jdbc.update("insert into credentials(id,project_id,owner_user_id,kind,name,encrypted_payload,kms_key_version,status) values (?,?,?,'SSH_PASSWORD','test',?,'test','ACTIVE')",
                    credential, project, actor, new byte[]{1});
            jdbc.update("insert into environments(id,project_id,name,role,arch,host,username,credential_id,status,created_by) values (?,?,'test','SOURCE_X86','x86_64','unreachable.invalid','test',?,'UNKNOWN',?)", environment, project, credential, actor);
            jdbc.update("insert into project_runtime_environments(project_id,source_environment_id,version) values (?,?,7)", project, environment);
            var dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES,
                    new org.jooq.conf.Settings().withRenderSchema(false));
            var mapper = new ObjectMapper();
            var repository = new KbScanRepository(dsl, mapper);
            var builds = new BuildValidationRepository(dsl, mapper);
            var sources = new SourceRepository(dsl);
            var context = new KbScanContextService(sources, new ProjectRuntimeEnvironmentRepository(dsl), builds, repository);
            UUID success = build("SUCCEEDED", "SOURCE_ONLY", "2026-01-02T00:00:00Z");
            UUID failure = build("FAILED", "SOURCE_ONLY", "2026-01-03T00:00:00Z");
            build("SUCCEEDED", "BOTH", "2026-01-05T00:00:00Z");
            assertThat(context.current(project).build().id()).isEqualTo(failure);
            assertThat(context.current(project).ready()).isFalse();
            UUID active = build("RUNNING", "SOURCE_ONLY", "2026-01-01T00:00:00Z");
            assertThat(context.current(project).build().id()).isEqualTo(active);
            jdbc.update("update build_runs set status='FAILED' where id=?", active);
            jdbc.update("update build_runs set status='SUCCEEDED', created_at='2026-01-03T00:00:00Z' where id=?", success);
            // PostgreSQL UUID ordering is unsigned lexical ordering.
            UUID tied = success.toString().compareTo(failure.toString()) > 0 ? success : failure;
            assertThat(context.current(project).build().id()).isEqualTo(tied);
            jdbc.update("update build_runs set created_at='2026-01-06T00:00:00Z' where id=?", success);
            assertThat(context.current(project).requireReady().buildRunId()).isEqualTo(success);
            var binding = new KbExecutionContext(environment, 7L, success);
            UUID scanSuccess = scan(task("2026-01-02T00:00:00Z"), "SUCCEEDED", binding);
            UUID scanFailure = scan(task("2026-01-03T00:00:00Z"), "FAILED", binding);
            assertThat(repository.findLatestRunByContext(project, source, binding).orElseThrow().id()).isEqualTo(scanFailure);
            UUID scanActive = scan(task("2026-01-01T00:00:00Z"), "RUNNING", binding);
            assertThat(repository.findLatestRunByContext(project, source, binding).orElseThrow().id()).isEqualTo(scanActive);
            assertThat(repository.findLatestHistoricalRun(project).orElseThrow().id()).isEqualTo(scanFailure);
            for (String update : List.of("source_environment_id=null", "runtime_environment_version=null", "build_run_id=null", "runtime_environment_version=-1",
                    "source_environment_id='" + UUID.randomUUID() + "'", "build_run_id='" + UUID.randomUUID() + "'")) {
                assertThatThrownBy(() -> jdbc.update("update kb_scan_runs set " + update + " where id=?", scanSuccess))
                        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            }
            var manager = new DataSourceTransactionManager(dataSource);
            var transaction = new TransactionTemplate(manager);
            UUID insertTask = task("2026-01-07T00:00:00Z");
            try (var executor = Executors.newSingleThreadExecutor()) {
                for (String mutation : List.of(
                        "update source_revisions set version_note='changed' where id='" + source + "'",
                        "update project_runtime_environments set version=8 where project_id='" + project + "'",
                        "update build_runs set status='RUNNING' where id='" + success + "'",
                        "insert into build_runs(project_id,task_id,source_revision_id,environment_id,command,status,build_scope,environment_config_version) values ('" + project + "','" + insertTask + "','" + source + "','" + environment + "','make','PENDING','SOURCE_ONLY',7)")) {
                    transaction.executeWithoutResult(status -> {
                        repository.lockContext(project);
                        var blocked = executor.submit(() -> {
                            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                                statement.execute("set lock_timeout='250ms'");
                                statement.execute(mutation);
                                return "unexpected success";
                            } catch (SQLException ex) { return ex.getSQLState(); }
                        });
                        try { assertThat(blocked.get(5, TimeUnit.SECONDS)).isEqualTo("55P03"); }
                        catch (Exception ex) { throw new AssertionError(ex); }
                        assertThat(context.current(project).requireReady()).isEqualTo(binding);
                    });
                }
            }
            verifiesRollbackAndCommit(manager, dsl, repository, sources, context, mapper);
            jdbc.update("update project_runtime_environments set version=8 where project_id=?", project);
            assertThat(context.currentRun(project, source)).isEmpty();
            assertThat(repository.findById(scanSuccess).orElseThrow().status()).isEqualTo(TaskStatus.SUCCEEDED);
        } finally {
            // schema name is generated locally, never derived from credentials or a caller-supplied path.
            new JdbcTemplate(admin).execute("drop schema " + schema + " cascade");
        }
    }

    void verifiesRollbackAndCommit(DataSourceTransactionManager manager, org.jooq.DSLContext dsl,
            KbScanRepository repository, SourceRepository sources, KbScanContextService context, ObjectMapper mapper) {
        var access = mock(ProjectAccessService.class);
        var dispatch = mock(TaskDispatchService.class);
        var broker = mock(TaskSseBroker.class);
        var broadcaster = mock(TaskSseEventBroadcaster.class);
        var tasks = new TaskRepository(dsl);
        var taskService = new TaskService(tasks, new TaskEventService(new TaskEventRepository(dsl, mapper), broker, broadcaster),
                dispatch, mapper, access, null, null, null, new TaskMetadataResolver());
        var audit = mock(AuditLogService.class);
        var target = new KbScanService(repository, sources, access, taskService, tasks, null, audit,
                new RvPortProperties(null, null, null), null, null);
        ReflectionTestUtils.setField(target, "scanContextService", context);
        var factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        var service = (KbScanService) factory.getProxy();
        Map<String, Long> before = counts();
        doThrow(new IllegalStateException("forced write failure")).when(audit).record(any(), any(), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> service.create(project, null, actor, true, new MockHttpServletRequest()))
                .isInstanceOf(IllegalStateException.class).hasMessage("forced write failure");
        assertThat(counts()).isEqualTo(before);
        verifyNoInteractions(dispatch, broker, broadcaster);
        reset(audit);
        var created = service.create(project, null, actor, true, new MockHttpServletRequest());
        assertThat(repository.findByTaskId(created.id()).orElseThrow().executionContext()).isEqualTo(context.current(project).executionContext());
        verify(dispatch).publishAndMarkQueued(any());
        verify(broker).publish(any());
        verify(broadcaster).publishTaskEvent(any());

        var concurrentSources = spy(sources);
        var changed = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call -> {
            var result = call.callRealMethod();
            if (changed.compareAndSet(false, true)) {
                try (var executor = Executors.newSingleThreadExecutor()) {
                    executor.submit(() -> jdbc.update("update project_runtime_environments set version=8 where project_id=?", project)).get(5, TimeUnit.SECONDS);
                }
            }
            return result;
        }).when(concurrentSources).findActive(project);
        ReflectionTestUtils.setField(target, "scanContextService", new KbScanContextService(concurrentSources,
                new ProjectRuntimeEnvironmentRepository(dsl), new BuildValidationRepository(dsl, mapper), repository));
        var snapshot = service.latest(project, actor, true);
        assertThat(snapshot.freshness()).isEqualTo(cn.rvport.backend.workflow.DomainLatestFreshness.CURRENT);
        assertThat(snapshot.context().runtimeEnvironmentVersion()).isEqualTo(7L);
        assertThat(snapshot.result().current()).isTrue();
        assertThat(service.latest(project, actor, true).freshness()).isEqualTo(cn.rvport.backend.workflow.DomainLatestFreshness.STALE);
    }
    Map<String, Long> counts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : List.of("tasks", "task_events", "kb_scan_runs"))
            counts.put(table, jdbc.queryForObject("select count(*) from " + table + " where project_id=?", Long.class, project));
        return counts;
    }
    UUID task(String created) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into tasks(id,project_id,type,status,input_snapshot,created_by,created_at) values (?,?,'KB_SCAN','SUCCEEDED','{}',?,?::timestamptz)", id, project, actor, created);
        return id;
    }
    UUID build(String status, String scope, String created) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into build_runs(id,project_id,task_id,source_revision_id,environment_id,command,status,build_scope,environment_config_version,created_at) values (?,?,?,?,?,'make',?,?,7,?::timestamptz)",
                id, project, task(created), source, environment, status, scope, created);
        return id;
    }
    UUID scan(UUID task, String status, KbExecutionContext binding) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into kb_scan_runs(id,project_id,task_id,source_revision_id,kb_version,scope,status) values (?,?,?,?,'rules','{}',?)", id, project, task, source, status);
        if (binding != null) jdbc.update("update kb_scan_runs set source_environment_id=?,runtime_environment_version=?,build_run_id=? where id=?",
                binding.sourceEnvironmentId(), binding.runtimeEnvironmentVersion(), binding.buildRunId(), id);
        return id;
    }
}
