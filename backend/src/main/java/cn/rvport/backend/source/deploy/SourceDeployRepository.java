package cn.rvport.backend.source.deploy;

import static cn.rvport.backend.jooq.tables.SourceDeployRuns.SOURCE_DEPLOY_RUNS;

import cn.rvport.backend.jooq.tables.records.SourceDeployRunsRecord;
import cn.rvport.backend.task.TaskStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.SortField;
import org.springframework.stereotype.Repository;

@Repository
public class SourceDeployRepository {

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public SourceDeployRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    public SourceDeployRunView createPending(
            UUID id,
            UUID projectId,
            UUID taskId,
            UUID sourceRevisionId,
            UUID environmentId,
            long environmentConfigVersion,
            String remotePath,
            boolean force
    ) {
        SourceDeployRunsRecord record = dsl.insertInto(SOURCE_DEPLOY_RUNS)
                .set(SOURCE_DEPLOY_RUNS.ID, id)
                .set(SOURCE_DEPLOY_RUNS.PROJECT_ID, projectId)
                .set(SOURCE_DEPLOY_RUNS.TASK_ID, taskId)
                .set(SOURCE_DEPLOY_RUNS.SOURCE_REVISION_ID, sourceRevisionId)
                .set(SOURCE_DEPLOY_RUNS.ENVIRONMENT_ID, environmentId)
                .set(SOURCE_DEPLOY_RUNS.ENVIRONMENT_CONFIG_VERSION, environmentConfigVersion)
                .set(SOURCE_DEPLOY_RUNS.REMOTE_PATH, remotePath)
                .set(SOURCE_DEPLOY_RUNS.DEPLOY_STRATEGY, SourceDeployStrategy.TAR_OVER_SSH.name())
                .set(SOURCE_DEPLOY_RUNS.FORCE, force)
                .set(SOURCE_DEPLOY_RUNS.STATUS, TaskStatus.PENDING.name())
                .returning()
                .fetchOne();
        return SourceDeployRunView.from(record);
    }

    public SourceDeployRunView createPending(
            UUID id,
            UUID projectId,
            UUID taskId,
            UUID sourceRevisionId,
            UUID environmentId,
            String remotePath,
            boolean force
    ) {
        return createPending(id, projectId, taskId, sourceRevisionId, environmentId, 0L, remotePath, force);
    }

    public Optional<SourceDeployRunView> findByTaskId(UUID taskId) {
        return dsl.selectFrom(SOURCE_DEPLOY_RUNS)
                .where(SOURCE_DEPLOY_RUNS.TASK_ID.eq(taskId))
                .orderBy(SOURCE_DEPLOY_RUNS.CREATED_AT.desc(), SOURCE_DEPLOY_RUNS.ID.desc())
                .limit(1)
                .fetchOptional()
                .map(SourceDeployRunView::from);
    }

    public Optional<SourceDeployRunView> findById(UUID id) {
        return dsl.selectFrom(SOURCE_DEPLOY_RUNS)
                .where(SOURCE_DEPLOY_RUNS.ID.eq(id))
                .fetchOptional()
                .map(SourceDeployRunView::from);
    }

    public Optional<SourceDeployRunView> findReusable(UUID projectId, UUID sourceRevisionId, UUID environmentId, long environmentConfigVersion) {
        return dsl.selectFrom(SOURCE_DEPLOY_RUNS)
                .where(SOURCE_DEPLOY_RUNS.PROJECT_ID.eq(projectId)
                        .and(SOURCE_DEPLOY_RUNS.SOURCE_REVISION_ID.eq(sourceRevisionId))
                        .and(SOURCE_DEPLOY_RUNS.ENVIRONMENT_ID.eq(environmentId))
                        .and(SOURCE_DEPLOY_RUNS.ENVIRONMENT_CONFIG_VERSION.eq(environmentConfigVersion))
                        .and(SOURCE_DEPLOY_RUNS.STATUS.in(reusableStatuses())))
                .orderBy(SOURCE_DEPLOY_RUNS.CREATED_AT.desc(), SOURCE_DEPLOY_RUNS.ID.desc())
                .limit(1)
                .fetchOptional()
                .map(SourceDeployRunView::from);
    }

    public Optional<SourceDeployRunView> findReusable(UUID projectId, UUID sourceRevisionId, UUID environmentId) {
        return findReusable(projectId, sourceRevisionId, environmentId, 0L);
    }

    public Optional<SourceDeployRunView> findLatestSucceeded(
            UUID projectId,
            UUID sourceRevisionId,
            UUID environmentId,
            long environmentConfigVersion
    ) {
        return dsl.selectFrom(SOURCE_DEPLOY_RUNS)
                .where(SOURCE_DEPLOY_RUNS.PROJECT_ID.eq(projectId)
                        .and(SOURCE_DEPLOY_RUNS.SOURCE_REVISION_ID.eq(sourceRevisionId))
                        .and(SOURCE_DEPLOY_RUNS.ENVIRONMENT_ID.eq(environmentId))
                        .and(SOURCE_DEPLOY_RUNS.ENVIRONMENT_CONFIG_VERSION.eq(environmentConfigVersion))
                        .and(SOURCE_DEPLOY_RUNS.STATUS.eq(TaskStatus.SUCCEEDED.name())))
                .orderBy(SOURCE_DEPLOY_RUNS.FINISHED_AT.desc().nullsLast(), SOURCE_DEPLOY_RUNS.ID.desc())
                .limit(1)
                .fetchOptional()
                .map(SourceDeployRunView::from);
    }

    public long count(UUID projectId, SourceDeployListRequest request) {
        return dsl.selectCount()
                .from(SOURCE_DEPLOY_RUNS)
                .where(filter(projectId, request))
                .fetchOne(0, long.class);
    }

    public List<SourceDeployRunView> list(UUID projectId, SourceDeployListRequest request) {
        int page = request.normalizedPage();
        int pageSize = request.normalizedPageSize();
        return dsl.selectFrom(SOURCE_DEPLOY_RUNS)
                .where(filter(projectId, request))
                .orderBy(sort(request), SOURCE_DEPLOY_RUNS.ID.desc())
                .limit(pageSize)
                .offset((page - 1) * pageSize)
                .fetch(SourceDeployRunView::from);
    }

    public void markRunning(UUID id) {
        dsl.update(SOURCE_DEPLOY_RUNS)
                .set(SOURCE_DEPLOY_RUNS.STATUS, TaskStatus.RUNNING.name())
                .set(SOURCE_DEPLOY_RUNS.STARTED_AT, OffsetDateTime.now())
                .where(SOURCE_DEPLOY_RUNS.ID.eq(id))
                .execute();
    }

    public void markFinished(
            UUID id,
            TaskStatus status,
            Integer fileCount,
            Long totalBytes,
            Integer exitCode,
            Map<String, ?> summary
    ) {
        dsl.update(SOURCE_DEPLOY_RUNS)
                .set(SOURCE_DEPLOY_RUNS.STATUS, status.name())
                .set(SOURCE_DEPLOY_RUNS.FILE_COUNT, fileCount)
                .set(SOURCE_DEPLOY_RUNS.TOTAL_BYTES, totalBytes)
                .set(SOURCE_DEPLOY_RUNS.EXIT_CODE, exitCode)
                .set(SOURCE_DEPLOY_RUNS.SUMMARY, toJsonb(summary))
                .set(SOURCE_DEPLOY_RUNS.FINISHED_AT, OffsetDateTime.now())
                .where(SOURCE_DEPLOY_RUNS.ID.eq(id))
                .execute();
    }

    public void syncStatusByTask(UUID taskId, TaskStatus status) {
        dsl.update(SOURCE_DEPLOY_RUNS)
                .set(SOURCE_DEPLOY_RUNS.STATUS, status.name())
                .set(SOURCE_DEPLOY_RUNS.FINISHED_AT, OffsetDateTime.now())
                .where(SOURCE_DEPLOY_RUNS.TASK_ID.eq(taskId))
                .execute();
    }

    private Collection<String> reusableStatuses() {
        return List.of(
                TaskStatus.PENDING.name(),
                TaskStatus.QUEUED.name(),
                TaskStatus.RUNNING.name(),
                TaskStatus.SUCCEEDED.name()
        );
    }

    private Condition filter(UUID projectId, SourceDeployListRequest request) {
        Condition condition = SOURCE_DEPLOY_RUNS.PROJECT_ID.eq(projectId);
        if (request.sourceRevisionId() != null) {
            condition = condition.and(SOURCE_DEPLOY_RUNS.SOURCE_REVISION_ID.eq(request.sourceRevisionId()));
        }
        if (request.environmentId() != null) {
            condition = condition.and(SOURCE_DEPLOY_RUNS.ENVIRONMENT_ID.eq(request.environmentId()));
        }
        if (request.status() != null) {
            condition = condition.and(SOURCE_DEPLOY_RUNS.STATUS.eq(request.status().name()));
        }
        if (request.createdFrom() != null) {
            condition = condition.and(SOURCE_DEPLOY_RUNS.CREATED_AT.ge(request.createdFrom()));
        }
        if (request.createdTo() != null) {
            condition = condition.and(SOURCE_DEPLOY_RUNS.CREATED_AT.le(request.createdTo()));
        }
        return condition;
    }

    private SortField<?> sort(SourceDeployListRequest request) {
        Field<?> field = switch (normalizeSortBy(request.sortBy())) {
            case "startedAt" -> SOURCE_DEPLOY_RUNS.STARTED_AT;
            case "finishedAt" -> SOURCE_DEPLOY_RUNS.FINISHED_AT;
            case "status" -> SOURCE_DEPLOY_RUNS.STATUS;
            default -> SOURCE_DEPLOY_RUNS.CREATED_AT;
        };
        return "asc".equalsIgnoreCase(request.sortDirection()) ? field.asc() : field.desc();
    }

    private String normalizeSortBy(String value) {
        return value == null || value.isBlank() ? "createdAt" : value.trim();
    }

    private JSONB toJsonb(Map<String, ?> value) {
        try {
            return JSONB.valueOf(objectMapper.writeValueAsString(value == null ? Map.of() : value));
        } catch (JsonProcessingException ex) {
            return JSONB.valueOf("{}");
        }
    }
}
