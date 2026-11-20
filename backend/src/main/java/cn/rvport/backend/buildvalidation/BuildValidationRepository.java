package cn.rvport.backend.buildvalidation;

import static cn.rvport.backend.jooq.tables.BuildRuns.BUILD_RUNS;
import static cn.rvport.backend.jooq.tables.Tasks.TASKS;
import static cn.rvport.backend.workflow.DomainLatestOrdering.activeFirst;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.rvport.backend.jooq.tables.records.BuildRunsRecord;
import cn.rvport.backend.task.TaskStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.SortField;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
public class BuildValidationRepository {

    private static final Field<String> BUILD_SCOPE = DSL.field(DSL.name("build_scope"), String.class);
    private static final Field<JSONB> BUILD_FACTS = DSL.field(DSL.name("build_facts"), JSONB.class);

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public BuildValidationRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    public BuildValidationRunView createPending(
            UUID id,
            UUID projectId,
            UUID taskId,
            UUID sourceRevisionId,
            UUID environmentId,
            BuildValidationScope buildScope,
            UUID sourceDeploymentId,
            long environmentConfigVersion,
            UUID scriptPayloadId,
            String scriptSha256,
            String commandSummary,
            String workingDirectory,
            Integer timeoutSeconds,
            Map<String, String> environmentVariablesMasked
    ) {
        BuildRunsRecord record = dsl.insertInto(BUILD_RUNS)
                .set(BUILD_RUNS.ID, id)
                .set(BUILD_RUNS.PROJECT_ID, projectId)
                .set(BUILD_RUNS.TASK_ID, taskId)
                .set(BUILD_RUNS.SOURCE_REVISION_ID, sourceRevisionId)
                .set(BUILD_RUNS.ENVIRONMENT_ID, environmentId)
                .set(BUILD_SCOPE, buildScope == null ? BuildValidationScope.BOTH.name() : buildScope.name())
                .set(BUILD_RUNS.SOURCE_DEPLOY_RUN_ID, sourceDeploymentId)
                .set(BUILD_RUNS.ENVIRONMENT_CONFIG_VERSION, environmentConfigVersion)
                .set(BUILD_RUNS.SCRIPT_PAYLOAD_ID, scriptPayloadId)
                .set(BUILD_RUNS.SCRIPT_SHA256, scriptSha256)
                .set(BUILD_RUNS.COMMAND, commandSummary)
                .set(BUILD_RUNS.WORKING_DIRECTORY, workingDirectory)
                .set(BUILD_RUNS.TIMEOUT_SECONDS, timeoutSeconds)
                .set(BUILD_RUNS.STATUS, TaskStatus.PENDING.name())
                .set(BUILD_FACTS, JSONB.valueOf("{}"))
                .returning()
                .fetchOne();
        return BuildValidationRunView.from(record, environmentVariablesMasked, buildScope, Map.of());
    }

    public BuildValidationRunView createPending(
            UUID id,
            UUID projectId,
            UUID taskId,
            UUID sourceRevisionId,
            UUID environmentId,
            UUID sourceDeploymentId,
            long environmentConfigVersion,
            UUID scriptPayloadId,
            String scriptSha256,
            String commandSummary,
            String workingDirectory,
            Integer timeoutSeconds,
            Map<String, String> environmentVariablesMasked
    ) {
        return createPending(id, projectId, taskId, sourceRevisionId, environmentId, BuildValidationScope.BOTH,
                sourceDeploymentId, environmentConfigVersion, scriptPayloadId, scriptSha256, commandSummary,
                workingDirectory, timeoutSeconds, environmentVariablesMasked);
    }

    public BuildValidationRunView createPending(
            UUID id,
            UUID projectId,
            UUID taskId,
            UUID sourceRevisionId,
            UUID environmentId,
            UUID sourceDeploymentId,
            UUID scriptPayloadId,
            String scriptSha256,
            String commandSummary,
            String workingDirectory,
            Integer timeoutSeconds,
            Map<String, String> environmentVariablesMasked
    ) {
        return createPending(id, projectId, taskId, sourceRevisionId, environmentId, BuildValidationScope.BOTH, sourceDeploymentId, 0L,
                scriptPayloadId, scriptSha256, commandSummary, workingDirectory, timeoutSeconds, environmentVariablesMasked);
    }

    public Optional<BuildValidationRunView> findById(UUID id) {
        return dsl.select(BUILD_RUNS.fields())
                .select(TASKS.INPUT_SNAPSHOT)
                .from(BUILD_RUNS)
                .leftJoin(TASKS).on(TASKS.ID.eq(BUILD_RUNS.TASK_ID))
                .where(BUILD_RUNS.ID.eq(id))
                .fetchOptional()
                .map(this::view);
    }

    public Optional<BuildValidationRunView> findByTaskId(UUID taskId) {
        return dsl.select(BUILD_RUNS.fields())
                .select(TASKS.INPUT_SNAPSHOT)
                .from(BUILD_RUNS)
                .leftJoin(TASKS).on(TASKS.ID.eq(BUILD_RUNS.TASK_ID))
                .where(BUILD_RUNS.TASK_ID.eq(taskId))
                .fetchOptional()
                .map(this::view);
    }

    public Optional<BuildValidationRunView> findLatestSucceededSourceBuild(
            UUID projectId,
            UUID sourceRevisionId,
            UUID sourceEnvironmentId,
            Long environmentConfigVersion
    ) {
        Condition condition = BUILD_RUNS.PROJECT_ID.eq(projectId)
                .and(BUILD_RUNS.SOURCE_REVISION_ID.eq(sourceRevisionId))
                .and(BUILD_RUNS.ENVIRONMENT_ID.eq(sourceEnvironmentId))
                .and(BUILD_RUNS.STATUS.eq(TaskStatus.SUCCEEDED.name()));
        if (environmentConfigVersion != null) {
            condition = condition.and(BUILD_RUNS.ENVIRONMENT_CONFIG_VERSION.eq(environmentConfigVersion));
        }
        return dsl.select(BUILD_RUNS.fields())
                .select(TASKS.INPUT_SNAPSHOT)
                .from(BUILD_RUNS)
                .leftJoin(TASKS).on(TASKS.ID.eq(BUILD_RUNS.TASK_ID))
                .where(condition)
                .orderBy(BUILD_RUNS.FINISHED_AT.desc().nullsLast(), BUILD_RUNS.CREATED_AT.desc(), BUILD_RUNS.ID.desc())
                .limit(1)
                .fetchOptional()
                .map(this::view);
    }

    public Optional<BuildValidationRunView> findLatest(
            UUID projectId,
            UUID sourceRevisionId,
            UUID environmentId,
            Long environmentConfigVersion,
            BuildValidationScope buildScope
    ) {
        Condition condition = BUILD_RUNS.PROJECT_ID.eq(projectId);
        if (sourceRevisionId != null) {
            condition = condition.and(BUILD_RUNS.SOURCE_REVISION_ID.eq(sourceRevisionId));
        }
        if (environmentId != null) {
            condition = condition.and(BUILD_RUNS.ENVIRONMENT_ID.eq(environmentId));
        }
        if (environmentConfigVersion != null) {
            condition = condition.and(BUILD_RUNS.ENVIRONMENT_CONFIG_VERSION.eq(environmentConfigVersion));
        }
        if (buildScope != null) {
            condition = condition.and(BUILD_SCOPE.eq(buildScope.name()));
        }
        return dsl.select(BUILD_RUNS.fields())
                .select(TASKS.INPUT_SNAPSHOT)
                .from(BUILD_RUNS)
                .leftJoin(TASKS).on(TASKS.ID.eq(BUILD_RUNS.TASK_ID))
                .where(condition)
                .orderBy(activeFirst(BUILD_RUNS.STATUS), BUILD_RUNS.CREATED_AT.desc(), BUILD_RUNS.ID.desc())
                .limit(1)
                .fetchOptional()
                .map(this::view);
    }

    public Optional<BuildValidationRunView> findLatestSucceededSourceBuildWithCompileDatabase(
            UUID projectId,
            UUID sourceRevisionId,
            UUID sourceEnvironmentId,
            Long environmentConfigVersion
    ) {
        Condition condition = BUILD_RUNS.PROJECT_ID.eq(projectId)
                .and(BUILD_RUNS.SOURCE_REVISION_ID.eq(sourceRevisionId))
                .and(BUILD_RUNS.ENVIRONMENT_ID.eq(sourceEnvironmentId))
                .and(BUILD_RUNS.STATUS.eq(TaskStatus.SUCCEEDED.name()))
                .and(DSL.condition("(build_facts -> 'compileDatabase' ->> 'available')::boolean = true"));
        if (environmentConfigVersion != null) {
            condition = condition.and(BUILD_RUNS.ENVIRONMENT_CONFIG_VERSION.eq(environmentConfigVersion));
        }
        return dsl.select(BUILD_RUNS.fields())
                .select(TASKS.INPUT_SNAPSHOT)
                .from(BUILD_RUNS)
                .leftJoin(TASKS).on(TASKS.ID.eq(BUILD_RUNS.TASK_ID))
                .where(condition)
                .orderBy(
                        DSL.when(BUILD_SCOPE.eq(BuildValidationScope.SOURCE_ONLY.name()), 0).otherwise(1).asc(),
                        BUILD_RUNS.FINISHED_AT.desc().nullsLast(),
                        BUILD_RUNS.CREATED_AT.desc(),
                        BUILD_RUNS.ID.desc())
                .limit(1)
                .fetchOptional()
                .map(this::view);
    }

    public long count(UUID projectId, BuildValidationListRequest request) {
        return dsl.selectCount()
                .from(BUILD_RUNS)
                .where(filter(projectId, request))
                .fetchOne(0, long.class);
    }

    public List<BuildValidationRunView> list(UUID projectId, BuildValidationListRequest request) {
        int page = request.normalizedPage();
        int pageSize = request.normalizedPageSize();
        return dsl.select(BUILD_RUNS.fields())
                .select(TASKS.INPUT_SNAPSHOT)
                .from(BUILD_RUNS)
                .leftJoin(TASKS).on(TASKS.ID.eq(BUILD_RUNS.TASK_ID))
                .where(filter(projectId, request))
                .orderBy(sort(request), BUILD_RUNS.ID.desc())
                .limit(pageSize)
                .offset((page - 1) * pageSize)
                .fetch(this::view);
    }

    public void markRunning(UUID id) {
        dsl.update(BUILD_RUNS)
                .set(BUILD_RUNS.STATUS, TaskStatus.RUNNING.name())
                .set(BUILD_RUNS.STARTED_AT, OffsetDateTime.now())
                .where(BUILD_RUNS.ID.eq(id))
                .execute();
    }

    public void markFinished(UUID id, TaskStatus status, Integer exitCode, String failureCategory, UUID mergedLogId, UUID stdoutLogId, UUID stderrLogId) {
        dsl.update(BUILD_RUNS)
                .set(BUILD_RUNS.STATUS, status.name())
                .set(BUILD_RUNS.EXIT_CODE, exitCode)
                .set(BUILD_RUNS.FAILURE_CATEGORY, failureCategory)
                .set(BUILD_RUNS.COMBINED_LOG_ID, mergedLogId)
                .set(BUILD_RUNS.STDOUT_LOG_ID, stdoutLogId)
                .set(BUILD_RUNS.STDERR_LOG_ID, stderrLogId)
                .set(BUILD_RUNS.FINISHED_AT, OffsetDateTime.now())
                .where(BUILD_RUNS.ID.eq(id))
                .execute();
    }

    public void updateBuildFacts(UUID id, Map<String, ?> buildFacts) {
        dsl.update(BUILD_RUNS)
                .set(BUILD_FACTS, toJsonb(buildFacts))
                .where(BUILD_RUNS.ID.eq(id))
                .execute();
    }

    public void updateSourceDeployment(UUID id, UUID sourceDeploymentId, String workingDirectory) {
        dsl.update(BUILD_RUNS)
                .set(BUILD_RUNS.SOURCE_DEPLOY_RUN_ID, sourceDeploymentId)
                .set(BUILD_RUNS.WORKING_DIRECTORY, workingDirectory)
                .where(BUILD_RUNS.ID.eq(id))
                .execute();
    }

    public void syncStatusByTask(UUID taskId, TaskStatus status) {
        dsl.update(BUILD_RUNS)
                .set(BUILD_RUNS.STATUS, status.name())
                .set(BUILD_RUNS.FINISHED_AT, OffsetDateTime.now())
                .where(BUILD_RUNS.TASK_ID.eq(taskId))
                .execute();
    }

    private Condition filter(UUID projectId, BuildValidationListRequest request) {
        Condition condition = BUILD_RUNS.PROJECT_ID.eq(projectId);
        if (request.sourceRevisionId() != null) {
            condition = condition.and(BUILD_RUNS.SOURCE_REVISION_ID.eq(request.sourceRevisionId()));
        }
        if (request.environmentId() != null) {
            condition = condition.and(BUILD_RUNS.ENVIRONMENT_ID.eq(request.environmentId()));
        }
        if (request.environmentConfigVersion() != null) {
            condition = condition.and(BUILD_RUNS.ENVIRONMENT_CONFIG_VERSION.eq(request.environmentConfigVersion()));
        }
        if (request.buildScope() != null) {
            condition = condition.and(BUILD_SCOPE.eq(request.buildScope().name()));
        }
        if (request.status() != null) {
            condition = condition.and(BUILD_RUNS.STATUS.eq(request.status().name()));
        }
        if (request.createdFrom() != null) {
            condition = condition.and(BUILD_RUNS.CREATED_AT.ge(request.createdFrom()));
        }
        if (request.createdTo() != null) {
            condition = condition.and(BUILD_RUNS.CREATED_AT.le(request.createdTo()));
        }
        return condition;
    }

    private SortField<?> sort(BuildValidationListRequest request) {
        Field<?> field = switch (normalizeSortBy(request.sortBy())) {
            case "startedAt" -> BUILD_RUNS.STARTED_AT;
            case "finishedAt" -> BUILD_RUNS.FINISHED_AT;
            case "status" -> BUILD_RUNS.STATUS;
            case "environmentId" -> BUILD_RUNS.ENVIRONMENT_ID;
            default -> BUILD_RUNS.CREATED_AT;
        };
        return "asc".equalsIgnoreCase(request.sortDirection()) ? field.asc() : field.desc();
    }

    private String normalizeSortBy(String value) {
        return value == null || value.isBlank() ? "createdAt" : value.trim();
    }

    private BuildValidationRunView view(Record record) {
        String scope = record.get(BUILD_SCOPE);
        Map<String, Object> facts = new java.util.LinkedHashMap<>(jsonbToMap(record.get(BUILD_FACTS)));
        // 批次身份由服务端入队时固定，不从可变工具输出推断。
        facts.remove("buildBatchId");
        Object batch = jsonbToMap(record.get(TASKS.INPUT_SNAPSHOT)).get("buildBatchId");
        if (batch instanceof String value && value.matches("[0-9a-fA-F-]{36}")) facts.put("buildBatchId", value);
        return BuildValidationRunView.from(
                record.into(BUILD_RUNS),
                environmentVariablesMasked(record.get(TASKS.INPUT_SNAPSHOT)),
                parseScope(scope),
                facts
        );
    }

    private BuildValidationScope parseScope(String value) {
        if (value == null || value.isBlank()) {
            return BuildValidationScope.BOTH;
        }
        try {
            return BuildValidationScope.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return BuildValidationScope.BOTH;
        }
    }

    private JSONB toJsonb(Map<String, ?> value) {
        try {
            return JSONB.valueOf(objectMapper.writeValueAsString(value == null ? Map.of() : value));
        } catch (Exception ex) {
            return JSONB.valueOf("{}");
        }
    }

    private Map<String, String> environmentVariablesMasked(JSONB inputSnapshot) {
        if (inputSnapshot == null || inputSnapshot.data() == null || inputSnapshot.data().isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> snapshot = objectMapper.readValue(inputSnapshot.data(), new TypeReference<>() {
            });
            Object value = snapshot.get("environmentVariablesMasked");
            if (value == null) {
                return Map.of();
            }
            return objectMapper.convertValue(value, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private Map<String, Object> jsonbToMap(JSONB value) {
        if (value == null || value.data() == null || value.data().isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value.data(), new TypeReference<>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }
}
