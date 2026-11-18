package cn.rvport.backend.source;

import static cn.rvport.backend.jooq.tables.SourceImports.SOURCE_IMPORTS;
import static cn.rvport.backend.jooq.tables.SourceRevisions.SOURCE_REVISIONS;

import cn.rvport.backend.jooq.tables.records.SourceImportsRecord;
import cn.rvport.backend.jooq.tables.records.SourceRevisionsRecord;
import cn.rvport.backend.task.TaskStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
public class SourceRepository {

    private final DSLContext dsl;

    public SourceRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public long count(UUID projectId, SourceRevisionType revisionType, Boolean active) {
        return dsl.selectCount()
                .from(SOURCE_REVISIONS)
                .where(filter(projectId, revisionType, active))
                .fetchOne(0, long.class);
    }

    public List<SourceRevisionView> list(UUID projectId, SourceRevisionType revisionType, Boolean active, int page, int pageSize) {
        return dsl.selectFrom(SOURCE_REVISIONS)
                .where(filter(projectId, revisionType, active))
                .orderBy(SOURCE_REVISIONS.CREATED_AT.desc(), SOURCE_REVISIONS.ID.desc())
                .limit(pageSize)
                .offset((page - 1) * pageSize)
                .fetch(SourceRevisionView::from);
    }

    public Optional<SourceRevisionView> findById(UUID sourceRevisionId) {
        return dsl.selectFrom(SOURCE_REVISIONS)
                .where(SOURCE_REVISIONS.ID.eq(sourceRevisionId))
                .fetchOptional()
                .map(SourceRevisionView::from);
    }

    public Optional<SourceRevisionView> findActive(UUID projectId) {
        return dsl.selectFrom(SOURCE_REVISIONS)
                .where(SOURCE_REVISIONS.PROJECT_ID.eq(projectId)
                        .and(SOURCE_REVISIONS.IS_ACTIVE.isTrue()))
                .fetchOptional()
                .map(SourceRevisionView::from);
    }

    public Optional<SourceRevisionView> findPrevious(UUID projectId, UUID currentSourceRevisionId, OffsetDateTime currentCreatedAt) {
        Condition condition = SOURCE_REVISIONS.PROJECT_ID.eq(projectId)
                .and(SOURCE_REVISIONS.ID.ne(currentSourceRevisionId));
        if (currentCreatedAt != null) {
            condition = condition.and(SOURCE_REVISIONS.CREATED_AT.le(currentCreatedAt));
        }
        return dsl.selectFrom(SOURCE_REVISIONS)
                .where(condition)
                .orderBy(SOURCE_REVISIONS.CREATED_AT.desc(), SOURCE_REVISIONS.ID.desc())
                .limit(1)
                .fetchOptional()
                .map(SourceRevisionView::from);
    }

    public SourceRevisionView createArchive(
            UUID id,
            UUID projectId,
            String versionLabel,
            String sourceUri,
            String archivePath,
            String workingCopyPath,
            String versionNote,
            boolean active,
            UUID createdBy
    ) {
        SourceRevisionsRecord record = dsl.insertInto(SOURCE_REVISIONS)
                .set(SOURCE_REVISIONS.ID, id)
                .set(SOURCE_REVISIONS.PROJECT_ID, projectId)
                .set(SOURCE_REVISIONS.REVISION_TYPE, SourceRevisionType.ARCHIVE.name())
                .set(SOURCE_REVISIONS.VERSION_LABEL, blankToNull(versionLabel))
                .set(SOURCE_REVISIONS.SOURCE_URI, sourceUri)
                .set(SOURCE_REVISIONS.ARCHIVE_PATH, archivePath)
                .set(SOURCE_REVISIONS.WORKING_COPY_PATH, workingCopyPath)
                .set(SOURCE_REVISIONS.VERSION_NOTE, blankToNull(versionNote))
                .set(SOURCE_REVISIONS.IS_ACTIVE, active)
                .set(SOURCE_REVISIONS.CREATED_BY, createdBy)
                .returning()
                .fetchOne();
        return SourceRevisionView.from(record);
    }

    public SourceRevisionView createGit(
            UUID id,
            UUID projectId,
            String versionLabel,
            String gitUrl,
            String gitBranch,
            String gitCommit,
            String workingCopyPath,
            String versionNote,
            boolean active,
            UUID createdBy
    ) {
        SourceRevisionsRecord record = dsl.insertInto(SOURCE_REVISIONS)
                .set(SOURCE_REVISIONS.ID, id)
                .set(SOURCE_REVISIONS.PROJECT_ID, projectId)
                .set(SOURCE_REVISIONS.REVISION_TYPE, SourceRevisionType.GIT.name())
                .set(SOURCE_REVISIONS.VERSION_LABEL, blankToNull(versionLabel))
                .set(SOURCE_REVISIONS.SOURCE_URI, gitUrl)
                .set(SOURCE_REVISIONS.GIT_URL, gitUrl)
                .set(SOURCE_REVISIONS.GIT_BRANCH, blankToNull(gitBranch))
                .set(SOURCE_REVISIONS.GIT_COMMIT, blankToNull(gitCommit))
                .set(SOURCE_REVISIONS.WORKING_COPY_PATH, workingCopyPath)
                .set(SOURCE_REVISIONS.VERSION_NOTE, blankToNull(versionNote))
                .set(SOURCE_REVISIONS.IS_ACTIVE, active)
                .set(SOURCE_REVISIONS.CREATED_BY, createdBy)
                .returning()
                .fetchOne();
        return SourceRevisionView.from(record);
    }

    public void clearActive(UUID projectId) {
        dsl.update(SOURCE_REVISIONS)
                .set(SOURCE_REVISIONS.IS_ACTIVE, false)
                .where(SOURCE_REVISIONS.PROJECT_ID.eq(projectId)
                        .and(SOURCE_REVISIONS.IS_ACTIVE.isTrue()))
                .execute();
    }

    public SourceRevisionView activate(UUID projectId, UUID sourceRevisionId) {
        SourceRevisionsRecord record = dsl.update(SOURCE_REVISIONS)
                .set(SOURCE_REVISIONS.IS_ACTIVE, true)
                .where(SOURCE_REVISIONS.PROJECT_ID.eq(projectId)
                        .and(SOURCE_REVISIONS.ID.eq(sourceRevisionId)))
                .returning()
                .fetchOne();
        return record == null ? null : SourceRevisionView.from(record);
    }

    public SourceImportView createImport(UUID projectId, UUID sourceRevisionId, SourceRevisionType inputType, TaskStatus status) {
        SourceImportsRecord record = dsl.insertInto(SOURCE_IMPORTS)
                .set(SOURCE_IMPORTS.PROJECT_ID, projectId)
                .set(SOURCE_IMPORTS.SOURCE_REVISION_ID, sourceRevisionId)
                .set(SOURCE_IMPORTS.INPUT_TYPE, inputType.name())
                .set(SOURCE_IMPORTS.STATUS, status.name())
                .returning()
                .fetchOne();
        return SourceImportView.from(record);
    }

    public SourceImportView attachTask(UUID sourceImportId, UUID taskId, TaskStatus status) {
        SourceImportsRecord record = dsl.update(SOURCE_IMPORTS)
                .set(SOURCE_IMPORTS.TASK_ID, taskId)
                .set(SOURCE_IMPORTS.STATUS, status.name())
                .where(SOURCE_IMPORTS.ID.eq(sourceImportId))
                .returning()
                .fetchOne();
        return SourceImportView.from(record);
    }

    public SourceImportView completeImport(UUID sourceImportId, UUID sourceRevisionId) {
        SourceImportsRecord record = dsl.update(SOURCE_IMPORTS)
                .set(SOURCE_IMPORTS.SOURCE_REVISION_ID, sourceRevisionId)
                .set(SOURCE_IMPORTS.STATUS, TaskStatus.SUCCEEDED.name())
                .set(SOURCE_IMPORTS.ERROR_MESSAGE, (String) null)
                .where(SOURCE_IMPORTS.ID.eq(sourceImportId))
                .returning()
                .fetchOne();
        return SourceImportView.from(record);
    }

    public SourceImportView failImport(UUID sourceImportId, String errorMessage) {
        SourceImportsRecord record = dsl.update(SOURCE_IMPORTS)
                .set(SOURCE_IMPORTS.STATUS, TaskStatus.FAILED.name())
                .set(SOURCE_IMPORTS.ERROR_MESSAGE, truncate(errorMessage, 500))
                .where(SOURCE_IMPORTS.ID.eq(sourceImportId))
                .returning()
                .fetchOne();
        return SourceImportView.from(record);
    }

    private Condition filter(UUID projectId, SourceRevisionType revisionType, Boolean active) {
        Condition condition = SOURCE_REVISIONS.PROJECT_ID.eq(projectId);
        if (revisionType != null) {
            condition = condition.and(SOURCE_REVISIONS.REVISION_TYPE.eq(revisionType.name()));
        }
        if (active != null) {
            condition = condition.and(SOURCE_REVISIONS.IS_ACTIVE.eq(active));
        }
        return condition;
    }


    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
