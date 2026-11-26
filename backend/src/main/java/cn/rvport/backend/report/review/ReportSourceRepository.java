package cn.rvport.backend.report.review;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** 先限定实际输入，再选最新合法发布结果；技术失败不能发布半截输出。 */
@Repository
public class ReportSourceRepository {
    private final DSLContext dsl;
    private final ReviewWorkspaceRepository json;
    public ReportSourceRepository(DSLContext dsl, ReviewWorkspaceRepository json) { this.dsl=dsl; this.json=json; }

    /** 最新成功迁移运行（loop change：AI_MIGRATION_RESULT 来源；sourceRevision 经 kb_scan_runs join 取）。 */
    public Optional<Map<String,Object>> migration(UUID project, UUID source) {
        return dsl.fetchOptional("""
                select r.id,r.status,r.finished_at,r.scan_run_id,r.item_count,r.done_count,r.pending_count,
                    r.summary,'ai-migration-result.v1' as result_schema_version
                  from ai_migration_runs r join kb_scan_runs k on k.id=r.scan_run_id
                  where r.project_id=? and k.source_revision_id=? and r.status='SUCCEEDED'
                  order by r.finished_at desc nulls last,r.id desc limit 1
                """,project,source).map(json::map);
    }

    public Optional<Map<String,Object>> kb(UUID project, UUID source, UUID environment, long version, UUID build) {
        return dsl.fetchOptional("""
                select id,status,finished_at,kb_version,source_environment_id,runtime_environment_version,build_run_id,
                    summary, input_plan, effective_input_plan,
                    (select jsonb_build_object('id',e.id,'status',e.status,'reasonCode',e.summary->>'reasonCode')
                       from kb_scan_runs e where e.project_id=r.project_id and e.source_revision_id=r.source_revision_id
                       order by (e.status in ('PENDING','QUEUED','RUNNING')) desc,
                         (select t.created_at from tasks t where t.id=e.task_id) desc nulls last,e.id desc limit 1) as latest_execution,
                    exists(select 1 from kb_scan_artifacts a where a.scan_run_id=r.id
                      and a.artifact_type='SCAN_RESULT_JSON' and a.stored_artifact_id is not null) as result_available,
                    summary->>'schemaVersion' as result_schema_version from kb_scan_runs r where project_id=? and source_revision_id=?
                  and ((source_environment_id=? and runtime_environment_version=? and build_run_id=?)
                    or coalesce(effective_input_plan,input_plan)->>'inputOrigin'='SOURCE_DISCOVERY')
                order by (status='SUCCEEDED' and exists(select 1 from kb_scan_artifacts a where a.scan_run_id=r.id
                    and a.artifact_type='SCAN_RESULT_JSON' and a.stored_artifact_id is not null)) desc,
                    (select t.created_at from tasks t where t.id=r.task_id) desc nulls last,id desc limit 1
                """,project,source,environment,version,build).map(json::map);
    }

    public Optional<Map<String,Object>> dependency(UUID project, UUID source, UUID target, long version, UUID build,
            UUID sourceSnapshot, UUID targetSnapshot) {
        return dsl.fetchOptional("""
                select id,status,finished_at,target_environment_id,environment_config_version,source_build_run_id,
                    summary->'environmentRefresh'->>'sourceSnapshotId' as source_snapshot_id,
                    summary->'environmentRefresh'->>'targetSnapshotId' as target_snapshot_id,
                    'dependency-analysis.v1' as result_schema_version from dependency_analysis_runs where project_id=? and source_revision_id=?
                  and target_environment_id=? and environment_config_version=? and source_build_run_id=?
                  and summary->'environmentRefresh'->>'sourceSnapshotId'=?
                  and summary->'environmentRefresh'->>'targetSnapshotId'=?
                order by (status='SUCCEEDED') desc,finished_at desc nulls last,created_at desc,id desc limit 1
                """,project,source,target,version,build,sourceSnapshot.toString(),targetSnapshot.toString()).map(json::map);
    }

    public Optional<Map<String,Object>> specialized(UUID project, UUID source, String analyzer, UUID build,
            Long environmentVersion, String mode, String artifactSet) {
        return dsl.fetchOptional("""
                select id,status,finished_at,analysis_mode,artifact_set_id,build_run_id,environment_config_version,result_schema_version,
                    summary,input_plan,effective_input_plan,result_json_artifact_id,
                    (select jsonb_build_object('id',e.id,'status',e.status,'reasonCode',e.summary->>'reasonCode')
                       from specialized_analysis_runs e where e.project_id=r.project_id and e.source_revision_id=r.source_revision_id
                         and e.analyzer_type=r.analyzer_type
                       order by (e.status in ('PENDING','QUEUED','RUNNING')) desc,e.created_at desc,e.id desc limit 1) as latest_execution,
                    (result_json_artifact_id is not null) as result_available,
                    (select count(*) from specialized_analysis_findings f where f.run_id=r.id and f.finding_kind='DISCHARGED') as discharged_count
                from specialized_analysis_runs r where project_id=? and source_revision_id=? and analyzer_type=?
                  and exists(select 1 from projects p where p.id=r.project_id
                    and lower(trim(p.source_arch)) in ('x86','x86_64','amd64','i386','i686','x64')
                    and lower(trim(p.target_arch)) in ('riscv64','riscv','risc-v','risc-v64'))
                  and ((build_run_id=? and environment_config_version is not distinct from ?::bigint
                  and (input_plan is not null or (analysis_mode=? and artifact_set_id is not distinct from ?::text)))
                  or coalesce(effective_input_plan,input_plan)->>'inputOrigin'='SOURCE_DISCOVERY')
                order by (status in ('COMPLETE','COMPLETE_WITH_RISK','PARTIAL') and result_json_artifact_id is not null) desc,
                    created_at desc,id desc limit 1
                """,project,source,analyzer,build,environmentVersion,mode,artifactSet).map(json::map);
    }

    public List<Map<String,Object>> findings(String sourceType, UUID runId) {
        String sql=switch(sourceType) {
            case "KB_FINDING" -> "select * from findings where scan_run_id=? order by id";
            case "DEPENDENCY_RESULT" -> "select * from dependency_analysis_result_items where dependency_analysis_run_id=? order by id";
            case "FLOATING_POINT_FINDING" -> "select * from specialized_analysis_findings where run_id=? order by id";
            case "MEMORY_ORDER_FINDING" -> "select * from specialized_analysis_findings where run_id=? and finding_kind in ('FINDING','SOURCE_CANDIDATE','INCONCLUSIVE') order by id";
            case "AI_MIGRATION_RESULT" -> "select * from ai_migration_runs where id=?";
            default -> throw new IllegalArgumentException("Unknown report source type");
        };
        return dsl.fetch(sql,runId).map(json::map);
    }
}
