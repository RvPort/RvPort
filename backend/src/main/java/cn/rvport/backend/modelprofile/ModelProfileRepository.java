package cn.rvport.backend.modelprofile;

import static cn.rvport.backend.jooq.tables.ModelProfiles.MODEL_PROFILES;
import static cn.rvport.backend.jooq.tables.ModelProfileAgentProbeResults.MODEL_PROFILE_AGENT_PROBE_RESULTS;
import static cn.rvport.backend.jooq.tables.ModelProfileAgentProbeRuns.MODEL_PROFILE_AGENT_PROBE_RUNS;
import static cn.rvport.backend.jooq.tables.ModelStageAssignments.MODEL_STAGE_ASSIGNMENTS;
import static cn.rvport.backend.jooq.tables.AgentRuntimes.AGENT_RUNTIMES;

import cn.rvport.backend.jooq.tables.records.AgentRuntimesRecord;
import cn.rvport.backend.jooq.tables.records.ModelProfileAgentProbeResultsRecord;
import cn.rvport.backend.jooq.tables.records.ModelProfileAgentProbeRunsRecord;
import cn.rvport.backend.jooq.tables.records.ModelProfilesRecord;
import cn.rvport.backend.jooq.tables.records.ModelStageAssignmentsRecord;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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
public class ModelProfileRepository {

    private final DSLContext dsl;

    public ModelProfileRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public ModelProfilesRecord create(
            UUID id,
            String name,
            boolean enabled,
            String executablePath,
            String providerType,
            String baseUrl,
            String model,
            String wireApi,
            String apiKeyEncrypted,
            int timeoutSeconds,
            int maxRuntimeSeconds,
            UUID actorUserId
    ) {
        return dsl.insertInto(MODEL_PROFILES)
                .set(MODEL_PROFILES.ID, id)
                .set(MODEL_PROFILES.NAME, name)
                .set(MODEL_PROFILES.ENABLED, enabled)
                .set(MODEL_PROFILES.EXECUTABLE_PATH, executablePath)
                .set(MODEL_PROFILES.MODEL_PROVIDER_TYPE, providerType)
                .set(MODEL_PROFILES.BASE_URL, baseUrl)
                .set(MODEL_PROFILES.MODEL, model)
                .set(MODEL_PROFILES.WIRE_API, wireApi)
                .set(MODEL_PROFILES.API_KEY_ENCRYPTED, apiKeyEncrypted)
                .set(MODEL_PROFILES.TIMEOUT_SECONDS, timeoutSeconds)
                .set(MODEL_PROFILES.MAX_RUNTIME_SECONDS, maxRuntimeSeconds)
                .set(MODEL_PROFILES.OWNER_USER_ID, actorUserId)
                .set(MODEL_PROFILES.CREATED_BY, actorUserId)
                .set(MODEL_PROFILES.UPDATED_BY, actorUserId)
                .returning()
                .fetchOne();
    }

    public Optional<ModelProfilesRecord> findById(UUID profileId, UUID ownerUserId) {
        return dsl.selectFrom(MODEL_PROFILES)
                .where(MODEL_PROFILES.ID.eq(profileId).and(MODEL_PROFILES.OWNER_USER_ID.eq(ownerUserId)))
                .fetchOptional();
    }

    public Optional<ModelProfilesRecord> findEnabledById(UUID profileId, UUID ownerUserId) {
        return dsl.selectFrom(MODEL_PROFILES)
                .where(MODEL_PROFILES.ID.eq(profileId).and(MODEL_PROFILES.OWNER_USER_ID.eq(ownerUserId)).and(MODEL_PROFILES.ENABLED.isTrue()))
                .fetchOptional();
    }

    public boolean nameExists(String name, UUID exceptId, UUID ownerUserId) {
        Condition condition = MODEL_PROFILES.OWNER_USER_ID.eq(ownerUserId).and(DSL.lower(MODEL_PROFILES.NAME).eq(name.toLowerCase(java.util.Locale.ROOT)));
        if (exceptId != null) {
            condition = condition.and(MODEL_PROFILES.ID.ne(exceptId));
        }
        return dsl.fetchExists(MODEL_PROFILES, condition);
    }

    public List<ModelProfilesRecord> list(ModelProfileListRequest request, UUID ownerUserId) {
        int page = request.normalizedPage();
        int pageSize = request.normalizedPageSize();
        return dsl.selectFrom(MODEL_PROFILES)
                .where(filters(request)).and(MODEL_PROFILES.OWNER_USER_ID.eq(ownerUserId))
                .orderBy(sort(request), MODEL_PROFILES.ID.asc())
                .limit(pageSize)
                .offset((page - 1) * pageSize)
                .fetch();
    }

    public long count(ModelProfileListRequest request, UUID ownerUserId) {
        return dsl.selectCount()
                .from(MODEL_PROFILES)
                .where(filters(request)).and(MODEL_PROFILES.OWNER_USER_ID.eq(ownerUserId))
                .fetchOne(0, long.class);
    }

    public ModelProfilesRecord update(
            ModelProfilesRecord current,
            String name,
            Boolean enabled,
            String executablePath,
            String providerType,
            String baseUrl,
            String model,
            String wireApi,
            String apiKeyEncrypted,
            boolean apiKeyTouched,
            Integer timeoutSeconds,
            Integer maxRuntimeSeconds,
            UUID actorUserId
    ) {
        var update = dsl.update(MODEL_PROFILES)
                .set(MODEL_PROFILES.UPDATED_BY, actorUserId)
                .set(MODEL_PROFILES.UPDATED_AT, OffsetDateTime.now());
        if (name != null) {
            update.set(MODEL_PROFILES.NAME, name);
        }
        if (enabled != null) {
            update.set(MODEL_PROFILES.ENABLED, enabled);
        }
        if (executablePath != null) {
            update.set(MODEL_PROFILES.EXECUTABLE_PATH, executablePath);
        }
        if (providerType != null) {
            update.set(MODEL_PROFILES.MODEL_PROVIDER_TYPE, providerType);
        }
        if (baseUrl != null || current.getBaseUrl() != null) {
            update.set(MODEL_PROFILES.BASE_URL, baseUrl);
        }
        if (model != null || current.getModel() != null) {
            update.set(MODEL_PROFILES.MODEL, model);
        }
        if (wireApi != null) {
            update.set(MODEL_PROFILES.WIRE_API, wireApi);
        }
        if (apiKeyTouched) {
            update.set(MODEL_PROFILES.API_KEY_ENCRYPTED, apiKeyEncrypted);
        }
        if (timeoutSeconds != null) {
            update.set(MODEL_PROFILES.TIMEOUT_SECONDS, timeoutSeconds);
        }
        if (maxRuntimeSeconds != null) {
            update.set(MODEL_PROFILES.MAX_RUNTIME_SECONDS, maxRuntimeSeconds);
        }
        return update.where(MODEL_PROFILES.ID.eq(current.getId()).and(MODEL_PROFILES.OWNER_USER_ID.eq(actorUserId)))
                .returning()
                .fetchOne();
    }

    public ModelProfilesRecord updateTestResult(
            UUID profileId,
            String status,
            String message,
            OffsetDateTime testedAt,
            String agentType,
            Boolean basicAvailable,
            Boolean agentCompatible,
            JSONB details,
            UUID actorUserId
    ) {
        return dsl.update(MODEL_PROFILES)
                .set(MODEL_PROFILES.LAST_TEST_STATUS, status)
                .set(MODEL_PROFILES.LAST_TEST_MESSAGE, message)
                .set(MODEL_PROFILES.LAST_TESTED_AT, testedAt)
                .set(MODEL_PROFILES.LAST_TEST_AGENT_TYPE, agentType)
                .set(MODEL_PROFILES.LAST_TEST_BASIC_AVAILABLE, basicAvailable)
                .set(MODEL_PROFILES.LAST_TEST_AGENT_COMPATIBLE, agentCompatible)
                .set(MODEL_PROFILES.LAST_TEST_DETAILS, details)
                .set(MODEL_PROFILES.UPDATED_BY, actorUserId)
                .set(MODEL_PROFILES.UPDATED_AT, OffsetDateTime.now())
                .where(MODEL_PROFILES.ID.eq(profileId).and(MODEL_PROFILES.OWNER_USER_ID.eq(actorUserId)))
                .and(MODEL_PROFILES.LAST_TESTED_AT.isNull().or(MODEL_PROFILES.LAST_TESTED_AT.le(testedAt)))
                .returning()
                .fetchOne();
    }

    public ModelProfilesRecord updateConnectivityResult(
            UUID profileId,
            String status,
            String message,
            JSONB details,
            OffsetDateTime testedAt,
            long durationMs,
            UUID actorUserId
    ) {
        return dsl.update(MODEL_PROFILES)
                .set(MODEL_PROFILES.LAST_CONNECTIVITY_STATUS, status)
                .set(MODEL_PROFILES.LAST_CONNECTIVITY_MESSAGE, message)
                .set(MODEL_PROFILES.LAST_CONNECTIVITY_DETAILS, details)
                .set(MODEL_PROFILES.LAST_CONNECTIVITY_TESTED_AT, testedAt)
                .set(MODEL_PROFILES.LAST_CONNECTIVITY_DURATION_MS, durationMs)
                .set(MODEL_PROFILES.UPDATED_BY, actorUserId)
                .set(MODEL_PROFILES.UPDATED_AT, OffsetDateTime.now())
                .where(MODEL_PROFILES.ID.eq(profileId).and(MODEL_PROFILES.OWNER_USER_ID.eq(actorUserId)))
                .returning()
                .fetchOne();
    }

    public ModelProfileAgentProbeRunsRecord createProbeRun(
            UUID runId,
            UUID profileId,
            JSONB selectedRuntimeIds,
            JSONB selectedRuntimeSummaries,
            UUID actorUserId
    ) {
        return dsl.insertInto(MODEL_PROFILE_AGENT_PROBE_RUNS)
                .set(MODEL_PROFILE_AGENT_PROBE_RUNS.ID, runId)
                .set(MODEL_PROFILE_AGENT_PROBE_RUNS.MODEL_PROFILE_ID, profileId)
                .set(MODEL_PROFILE_AGENT_PROBE_RUNS.STATUS, AgentProbeRunStatus.PENDING.name())
                .set(MODEL_PROFILE_AGENT_PROBE_RUNS.SELECTED_AGENT_RUNTIME_IDS, selectedRuntimeIds)
                .set(MODEL_PROFILE_AGENT_PROBE_RUNS.SELECTED_AGENT_RUNTIMES, selectedRuntimeSummaries)
                .set(MODEL_PROFILE_AGENT_PROBE_RUNS.OWNER_USER_ID, actorUserId)
                .set(MODEL_PROFILE_AGENT_PROBE_RUNS.CREATED_BY, actorUserId)
                .returning()
                .fetchOne();
    }

    public Optional<ModelProfileAgentProbeRunsRecord> findProbeRun(UUID runId, UUID ownerUserId) {
        return dsl.selectFrom(MODEL_PROFILE_AGENT_PROBE_RUNS)
                .where(MODEL_PROFILE_AGENT_PROBE_RUNS.ID.eq(runId).and(MODEL_PROFILE_AGENT_PROBE_RUNS.OWNER_USER_ID.eq(ownerUserId)))
                .fetchOptional();
    }

    public Optional<ModelProfileAgentProbeRunsRecord> findActiveProbeRun(UUID profileId, UUID ownerUserId) {
        return dsl.selectFrom(MODEL_PROFILE_AGENT_PROBE_RUNS)
                .where(MODEL_PROFILE_AGENT_PROBE_RUNS.MODEL_PROFILE_ID.eq(profileId)
                        .and(MODEL_PROFILE_AGENT_PROBE_RUNS.OWNER_USER_ID.eq(ownerUserId))
                        .and(MODEL_PROFILE_AGENT_PROBE_RUNS.STATUS.in(
                                AgentProbeRunStatus.PENDING.name(),
                                AgentProbeRunStatus.RUNNING.name())))
                .orderBy(MODEL_PROFILE_AGENT_PROBE_RUNS.CREATED_AT.desc(), MODEL_PROFILE_AGENT_PROBE_RUNS.ID.asc())
                .limit(1)
                .fetchOptional();
    }

    public ModelProfileAgentProbeRunsRecord updateProbeRunStatus(
            UUID runId,
            AgentProbeRunStatus status,
            String message,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt
    ) {
        var update = dsl.update(MODEL_PROFILE_AGENT_PROBE_RUNS)
                .set(MODEL_PROFILE_AGENT_PROBE_RUNS.STATUS, status.name())
                .set(MODEL_PROFILE_AGENT_PROBE_RUNS.MESSAGE, message)
                .set(MODEL_PROFILE_AGENT_PROBE_RUNS.UPDATED_AT, OffsetDateTime.now());
        if (startedAt != null) {
            update.set(MODEL_PROFILE_AGENT_PROBE_RUNS.STARTED_AT, startedAt);
        }
        if (finishedAt != null) {
            update.set(MODEL_PROFILE_AGENT_PROBE_RUNS.FINISHED_AT, finishedAt);
        }
        return update.where(MODEL_PROFILE_AGENT_PROBE_RUNS.ID.eq(runId)
                        .and(MODEL_PROFILE_AGENT_PROBE_RUNS.STATUS.in("PENDING", "RUNNING")))
                .returning()
                .fetchOne();
    }

    public ModelProfileAgentProbeResultsRecord createProbeResult(
            UUID id,
            UUID runId,
            UUID profileId,
            UUID runtimeId,
            String agentType,
            String status,
            Boolean basicAvailable,
            Boolean agentCompatible,
            JSONB checks,
            JSONB details,
            String errorCode,
            String message,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            Long durationMs
    ) {
        return dsl.insertInto(MODEL_PROFILE_AGENT_PROBE_RESULTS)
                .set(MODEL_PROFILE_AGENT_PROBE_RESULTS.ID, id)
                .set(MODEL_PROFILE_AGENT_PROBE_RESULTS.RUN_ID, runId)
                .set(MODEL_PROFILE_AGENT_PROBE_RESULTS.MODEL_PROFILE_ID, profileId)
                .set(MODEL_PROFILE_AGENT_PROBE_RESULTS.AGENT_RUNTIME_ID, runtimeId)
                .set(MODEL_PROFILE_AGENT_PROBE_RESULTS.AGENT_TYPE, agentType)
                .set(MODEL_PROFILE_AGENT_PROBE_RESULTS.STATUS, status)
                .set(MODEL_PROFILE_AGENT_PROBE_RESULTS.BASIC_AVAILABLE, basicAvailable)
                .set(MODEL_PROFILE_AGENT_PROBE_RESULTS.AGENT_COMPATIBLE, agentCompatible)
                .set(MODEL_PROFILE_AGENT_PROBE_RESULTS.CHECKS, checks)
                .set(MODEL_PROFILE_AGENT_PROBE_RESULTS.DETAILS, details)
                .set(MODEL_PROFILE_AGENT_PROBE_RESULTS.ERROR_CODE, errorCode)
                .set(MODEL_PROFILE_AGENT_PROBE_RESULTS.MESSAGE, message)
                .set(MODEL_PROFILE_AGENT_PROBE_RESULTS.STARTED_AT, startedAt)
                .set(MODEL_PROFILE_AGENT_PROBE_RESULTS.FINISHED_AT, finishedAt)
                .set(MODEL_PROFILE_AGENT_PROBE_RESULTS.DURATION_MS, durationMs)
                .onConflict(MODEL_PROFILE_AGENT_PROBE_RESULTS.ID).doNothing()
                .returning()
                .fetchOne();
    }

    public List<ModelProfileAgentProbeResultsRecord> listProbeResultsByRun(UUID runId, UUID ownerUserId) {
        return dsl.selectFrom(MODEL_PROFILE_AGENT_PROBE_RESULTS)
                .where(MODEL_PROFILE_AGENT_PROBE_RESULTS.RUN_ID.eq(runId))
                .andExists(dsl.selectOne().from(MODEL_PROFILE_AGENT_PROBE_RUNS)
                        .where(MODEL_PROFILE_AGENT_PROBE_RUNS.ID.eq(runId)
                                .and(MODEL_PROFILE_AGENT_PROBE_RUNS.OWNER_USER_ID.eq(ownerUserId))))
                .orderBy(MODEL_PROFILE_AGENT_PROBE_RESULTS.CREATED_AT.asc(), MODEL_PROFILE_AGENT_PROBE_RESULTS.ID.asc())
                .fetch();
    }

    public List<ModelProfileAgentProbeResultsRecord> listLatestProbeResults(UUID profileId, UUID ownerUserId) {
        List<ModelProfileAgentProbeResultsRecord> ordered = dsl.selectFrom(MODEL_PROFILE_AGENT_PROBE_RESULTS)
                .where(MODEL_PROFILE_AGENT_PROBE_RESULTS.MODEL_PROFILE_ID.eq(profileId))
                .andExists(dsl.selectOne().from(MODEL_PROFILES)
                        .where(MODEL_PROFILES.ID.eq(profileId).and(MODEL_PROFILES.OWNER_USER_ID.eq(ownerUserId))))
                .orderBy(MODEL_PROFILE_AGENT_PROBE_RESULTS.AGENT_RUNTIME_ID.asc(),
                        MODEL_PROFILE_AGENT_PROBE_RESULTS.CREATED_AT.desc())
                .fetch();
        List<ModelProfileAgentProbeResultsRecord> latest = new ArrayList<>();
        List<UUID> seen = new ArrayList<>();
        for (ModelProfileAgentProbeResultsRecord record : ordered) {
            if (!seen.contains(record.getAgentRuntimeId())) {
                seen.add(record.getAgentRuntimeId());
                latest.add(record);
            }
        }
        return latest;
    }

    public boolean isAssigned(UUID profileId, UUID ownerUserId) {
        return dsl.fetchExists(MODEL_STAGE_ASSIGNMENTS, MODEL_STAGE_ASSIGNMENTS.MODEL_PROFILE_ID.eq(profileId)
                .and(MODEL_STAGE_ASSIGNMENTS.OWNER_USER_ID.eq(ownerUserId)));
    }

    public void delete(UUID profileId, UUID ownerUserId) {
        dsl.deleteFrom(MODEL_PROFILES)
                .where(MODEL_PROFILES.ID.eq(profileId).and(MODEL_PROFILES.OWNER_USER_ID.eq(ownerUserId)))
                .execute();
    }

    public List<AssignmentView> listAssignments(UUID ownerUserId) {
        return dsl.select()
                .from(MODEL_STAGE_ASSIGNMENTS)
                .leftJoin(MODEL_PROFILES).on(MODEL_STAGE_ASSIGNMENTS.MODEL_PROFILE_ID.eq(MODEL_PROFILES.ID))
                .leftJoin(AGENT_RUNTIMES).on(MODEL_STAGE_ASSIGNMENTS.AGENT_RUNTIME_ID.eq(AGENT_RUNTIMES.ID))
                .where(MODEL_STAGE_ASSIGNMENTS.OWNER_USER_ID.eq(ownerUserId))
                .fetch(this::mapAssignment);
    }

    public Optional<AssignmentView> findAssignment(ModelProfileStage stage, UUID ownerUserId) {
        return dsl.select()
                .from(MODEL_STAGE_ASSIGNMENTS)
                .join(MODEL_PROFILES).on(MODEL_STAGE_ASSIGNMENTS.MODEL_PROFILE_ID.eq(MODEL_PROFILES.ID))
                .join(AGENT_RUNTIMES).on(MODEL_STAGE_ASSIGNMENTS.AGENT_RUNTIME_ID.eq(AGENT_RUNTIMES.ID))
                .where(MODEL_STAGE_ASSIGNMENTS.STAGE.eq(stage.name()).and(MODEL_STAGE_ASSIGNMENTS.OWNER_USER_ID.eq(ownerUserId)))
                .fetchOptional(this::mapAssignment);
    }

    public void upsertAssignment(ModelProfileStage stage, UUID profileId, UUID agentRuntimeId, UUID actorUserId) {
        dsl.insertInto(MODEL_STAGE_ASSIGNMENTS)
                .set(MODEL_STAGE_ASSIGNMENTS.OWNER_USER_ID, actorUserId)
                .set(MODEL_STAGE_ASSIGNMENTS.STAGE, stage.name())
                .set(MODEL_STAGE_ASSIGNMENTS.MODEL_PROFILE_ID, profileId)
                .set(MODEL_STAGE_ASSIGNMENTS.AGENT_RUNTIME_ID, agentRuntimeId)
                .set(MODEL_STAGE_ASSIGNMENTS.UPDATED_BY, actorUserId)
                .onConflict(MODEL_STAGE_ASSIGNMENTS.OWNER_USER_ID, MODEL_STAGE_ASSIGNMENTS.STAGE)
                .doUpdate()
                .set(MODEL_STAGE_ASSIGNMENTS.MODEL_PROFILE_ID, profileId)
                .set(MODEL_STAGE_ASSIGNMENTS.AGENT_RUNTIME_ID, agentRuntimeId)
                .set(MODEL_STAGE_ASSIGNMENTS.UPDATED_BY, actorUserId)
                .set(MODEL_STAGE_ASSIGNMENTS.UPDATED_AT, OffsetDateTime.now())
                .execute();
    }

    private List<Condition> filters(ModelProfileListRequest request) {
        List<Condition> conditions = new ArrayList<>();
        if (request.enabled() != null) {
            conditions.add(MODEL_PROFILES.ENABLED.eq(request.enabled()));
        }
        if (notBlank(request.modelProviderType())) {
            conditions.add(MODEL_PROFILES.MODEL_PROVIDER_TYPE.eq(request.modelProviderType().trim()));
        }
        if (notBlank(request.lastTestStatus())) {
            conditions.add(MODEL_PROFILES.LAST_TEST_STATUS.eq(request.lastTestStatus().trim()));
        }
        if (notBlank(request.keyword())) {
            String keyword = "%" + request.keyword().trim().toLowerCase() + "%";
            conditions.add(DSL.lower(MODEL_PROFILES.NAME).like(keyword)
                    .or(DSL.lower(MODEL_PROFILES.MODEL).like(keyword)));
        }
        return conditions;
    }

    private SortField<?> sort(ModelProfileListRequest request) {
        Field<?> field = switch (blankToDefault(request.sortBy(), "createdAt")) {
            case "name" -> MODEL_PROFILES.NAME;
            case "updatedAt" -> MODEL_PROFILES.UPDATED_AT;
            case "lastTestedAt" -> MODEL_PROFILES.LAST_TESTED_AT;
            case "createdAt" -> MODEL_PROFILES.CREATED_AT;
            default -> MODEL_PROFILES.CREATED_AT;
        };
        return "asc".equalsIgnoreCase(request.sortDirection()) ? field.asc().nullsLast() : field.desc().nullsLast();
    }

    private AssignmentView mapAssignment(Record record) {
        ModelStageAssignmentsRecord assignment = record.into(MODEL_STAGE_ASSIGNMENTS);
        ModelProfilesRecord profile = record.into(MODEL_PROFILES);
        if (profile.getId() == null) {
            profile = null;
        }
        AgentRuntimesRecord runtime = record.into(AGENT_RUNTIMES);
        if (runtime.getId() == null) {
            runtime = null;
        }
        return new AssignmentView(assignment.getStage(), assignment.getModelProfileId(), profile,
                assignment.getAgentRuntimeId(), runtime, assignment.getUpdatedAt());
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    public record AssignmentView(
            String stage,
            UUID modelProfileId,
            ModelProfilesRecord profile,
            UUID agentRuntimeId,
            AgentRuntimesRecord agentRuntime,
            OffsetDateTime updatedAt
    ) {
    }
}
