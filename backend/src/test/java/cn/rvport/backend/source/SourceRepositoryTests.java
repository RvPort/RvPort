package cn.rvport.backend.source;

import static cn.rvport.backend.jooq.tables.SourceImports.SOURCE_IMPORTS;
import static cn.rvport.backend.jooq.tables.SourceRevisions.SOURCE_REVISIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.rvport.backend.jooq.tables.records.SourceImportsRecord;
import cn.rvport.backend.jooq.tables.records.SourceRevisionsRecord;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.testfixture.JooqMockDatabase;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

class SourceRepositoryTests {

    private static final UUID PROJECT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID REVISION_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID IMPORT_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID TASK_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-11T10:00:00Z");

    @Test
    void countsListsAndFindsRevisionsWithDynamicFilters() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            SourceRepository repository = new SourceRepository(database.dsl());
            SourceRevisionsRecord git = revision(SourceRevisionType.GIT, true);
            database.enqueueResult(database.result(new Field<?>[]{DSL.count()}, new Object[]{1L}))
                    .enqueueRecord(git).enqueueRecord(git).enqueueRecord(git).enqueueRecord(git);

            assertThat(repository.count(PROJECT_ID, SourceRevisionType.GIT, true)).isEqualTo(1);
            assertThat(repository.list(PROJECT_ID, SourceRevisionType.GIT, true, 2, 10)).hasSize(1);
            assertThat(repository.findById(REVISION_ID)).hasValueSatisfying(view -> assertThat(view.gitUrl()).isEqualTo("https://example/repo.git"));
            assertThat(repository.findActive(PROJECT_ID)).isPresent();
            assertThat(repository.findPrevious(PROJECT_ID, UUID.randomUUID(), NOW)).isPresent();

            assertThat(database.executions().getFirst().normalizedSql()).contains("revision_type = ?", "is_active = ?");
            assertThat(database.executions().get(1).normalizedSql()).contains("offset ? rows");
            assertThat(database.executions().get(4).normalizedSql()).contains("created_at <= cast(?", "fetch next ? rows only");
            database.verifyComplete();
        }
    }

    @Test
    void findsPreviousWithoutTimestampAndHandlesMissingRows() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            SourceRepository repository = new SourceRepository(database.dsl());
            database.enqueueResult(database.result(SOURCE_REVISIONS.fields()))
                    .enqueueResult(database.result(SOURCE_REVISIONS.fields()))
                    .enqueueResult(database.result(SOURCE_REVISIONS.fields()));

            assertThat(repository.findById(REVISION_ID)).isEmpty();
            assertThat(repository.findActive(PROJECT_ID)).isEmpty();
            assertThat(repository.findPrevious(PROJECT_ID, REVISION_ID, null)).isEmpty();
            assertThat(database.lastExecution().normalizedSql()).doesNotContain("created_at <= ?");
            database.verifyComplete();
        }
    }

    @Test
    void createsArchiveAndGitRevisions() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            SourceRepository repository = new SourceRepository(database.dsl());
            SourceRevisionsRecord archive = revision(SourceRevisionType.ARCHIVE, false);
            SourceRevisionsRecord git = revision(SourceRevisionType.GIT, true);
            database.enqueueRecord(archive).enqueueRecord(git);

            SourceRevisionView archiveView = repository.createArchive(
                    REVISION_ID, PROJECT_ID, "  ", "upload.tar.gz", "/archive/file", "/work/tree", " note ", false, USER_ID);
            SourceRevisionView gitView = repository.createGit(
                    REVISION_ID, PROJECT_ID, " v2 ", "https://example/repo.git", " ", " abc123 ", "/work/tree", null, true, USER_ID);

            assertThat(archiveView.revisionType()).isEqualTo(SourceRevisionType.ARCHIVE);
            assertThat(gitView.revisionType()).isEqualTo(SourceRevisionType.GIT);
            assertThat(database.executions().get(0).bindings()).containsNull();
            assertThat(database.executions().get(1).bindings()).contains("v2", "abc123").containsNull();
            database.verifyComplete();
        }
    }

    @Test
    void clearsAndActivatesRevisionIncludingLostUpdateRace() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            SourceRepository repository = new SourceRepository(database.dsl());
            database.enqueueRows(2).enqueueRecord(revision(SourceRevisionType.GIT, true))
                    .enqueueResult(database.result(SOURCE_REVISIONS.fields()));

            repository.clearActive(PROJECT_ID);
            assertThat(repository.activate(PROJECT_ID, REVISION_ID)).isNotNull();
            assertThat(repository.activate(PROJECT_ID, UUID.randomUUID())).isNull();
            assertThat(database.executions().getFirst().normalizedSql()).contains("is_active = true");
            database.verifyComplete();
        }
    }

    @Test
    void advancesImportLifecycleAndTruncatesErrors() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            SourceRepository repository = new SourceRepository(database.dsl());
            SourceImportsRecord pending = sourceImport(null, TaskStatus.PENDING, null);
            SourceImportsRecord queued = sourceImport(TASK_ID, TaskStatus.QUEUED, null);
            SourceImportsRecord succeeded = sourceImport(TASK_ID, TaskStatus.SUCCEEDED, null);
            SourceImportsRecord failed = sourceImport(TASK_ID, TaskStatus.FAILED, "x".repeat(500));
            database.enqueueRecord(pending).enqueueRecord(queued).enqueueRecord(succeeded).enqueueRecord(failed);

            assertThat(repository.createImport(PROJECT_ID, null, SourceRevisionType.ARCHIVE, TaskStatus.PENDING).status())
                    .isEqualTo(TaskStatus.PENDING);
            assertThat(repository.attachTask(IMPORT_ID, TASK_ID, TaskStatus.QUEUED).taskId()).isEqualTo(TASK_ID);
            assertThat(repository.completeImport(IMPORT_ID, REVISION_ID).status()).isEqualTo(TaskStatus.SUCCEEDED);
            assertThat(repository.failImport(IMPORT_ID, "  " + "x".repeat(510) + "  ").errorMessage()).hasSize(500);

            Object[] failureBindings = database.lastExecution().bindings();
            assertThat(failureBindings).anySatisfy(value -> assertThat(value).isEqualTo("x".repeat(500)));
            database.verifyComplete();
        }
    }

    @Test
    void preservesNullFailureMessageAndPropagatesDatabaseFailure() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            SourceRepository repository = new SourceRepository(database.dsl());
            database.enqueueRecord(sourceImport(TASK_ID, TaskStatus.FAILED, null))
                    .enqueueFailure(new SQLException("database unavailable"));

            assertThat(repository.failImport(IMPORT_ID, null).errorMessage()).isNull();
            assertThatThrownBy(() -> repository.findById(REVISION_ID))
                    .isInstanceOf(org.jooq.exception.DataAccessException.class)
                    .hasMessageContaining("database unavailable");
            database.verifyComplete();
        }
    }

    @Test
    void supportsUnfilteredRevisionQueriesAndPreservesShortFailureMessage() throws Exception {
        try (JooqMockDatabase database = new JooqMockDatabase()) {
            SourceRepository repository = new SourceRepository(database.dsl());
            database.enqueueResult(database.result(new Field<?>[]{DSL.count()}, new Object[]{0L}))
                    .enqueueResult(database.result(SOURCE_REVISIONS.fields()))
                    .enqueueRecord(sourceImport(TASK_ID, TaskStatus.FAILED, "short"));
            assertThat(repository.count(PROJECT_ID, null, null)).isZero();
            assertThat(repository.list(PROJECT_ID, null, null, 1, 20)).isEmpty();
            assertThat(database.executions().getFirst().normalizedSql()).doesNotContain("revision_type =", "is_active =");
            assertThat(repository.failImport(IMPORT_ID, " short ").errorMessage()).isEqualTo("short");
            assertThat(database.lastExecution().bindings()).contains("short");
            database.verifyComplete();
        }
    }

    private static SourceRevisionsRecord revision(SourceRevisionType type, boolean active) {
        return new SourceRevisionsRecord(
                REVISION_ID, PROJECT_ID, type.name(), "v1", "https://example/repo.git", "https://example/repo.git",
                "main", "abc123", "/archive/file", "/work/tree", "note", active, USER_ID, NOW);
    }

    private static SourceImportsRecord sourceImport(UUID taskId, TaskStatus status, String error) {
        return new SourceImportsRecord(
                IMPORT_ID, PROJECT_ID, REVISION_ID, taskId, SourceRevisionType.ARCHIVE.name(), status.name(), error, NOW);
    }
}
