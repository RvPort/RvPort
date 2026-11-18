package cn.rvport.backend.report.review;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** 先限定执行上下文，再选最新完整成功；不使用 active-first 排序。 */
@Repository
public class ReportSourceRepository {
    private final DSLContext dsl;
    private final ReviewWorkspaceRepository json;
    public ReportSourceRepository(DSLContext dsl, ReviewWorkspaceRepository json) { this.dsl=dsl; this.json=json; }

    public Optional<Map<String,Object>> kb(UUID project, UUID source, UUID environment, long version, UUID build) {
        return dsl.fetchOptional("""
                select id,status,finished_at,kb_version,source_environment_id,runtime_environment_version,build_run_id,
                    summary->>'schemaVersion' as result_schema_version from kb_scan_runs where project_id=? and source_revision_id=?
                  and source_environment_id=? and runtime_environment_version=? and build_run_id=?
                order by (status='SUCCEEDED') desc,finished_at desc nulls last,id desc limit 1
                """,project,source,environment,version,build).map(json::map);
    }

    public Optional<Map<String,Object>> dependency(UUID project, UUID source, UUID target, long version, UUID build,
            UUID sourceSnapshot, UUID targetSnapshot) {
        return dsl.fetchOptional("""
                select id,status,finished_at,target_environment_id,environment_config_version,source_build_run_id,
                    summary->>'sourceSnapshotId' as source_snapshot_id,summary->>'targetSnapshotId' as target_snapshot_id,
                    'dependency-analysis.v1' as result_schema_version from dependency_analysis_runs where project_id=? and source_revision_id=?
                  and target_environment_id=? and environment_config_version=? and source_build_run_id=?
                  and summary->>'sourceSnapshotId'=? and summary->>'targetSnapshotId'=?
                order by (status='SUCCEEDED') desc,finished_at desc nulls last,created_at desc,id desc limit 1
                """,project,source,target,version,build,sourceSnapshot.toString(),targetSnapshot.toString()).map(json::map);
    }

    public Optional<Map<String,Object>> specialized(UUID project, UUID source, String analyzer, UUID build,
            Long environmentVersion, String mode, String artifactSet) {
        return dsl.fetchOptional("""
                select id,status,finished_at,analysis_mode,artifact_set_id,build_run_id,environment_config_version,result_schema_version,
                    (select count(*) from specialized_analysis_findings f where f.run_id=r.id and f.finding_kind='DISCHARGED') as discharged_count
                from specialized_analysis_runs r where project_id=? and source_revision_id=? and analyzer_type=?
                  and build_run_id=? and environment_config_version is not distinct from ?::bigint
                  and analysis_mode=? and artifact_set_id is not distinct from ?::text
                order by (status in ('COMPLETE','COMPLETE_WITH_RISK')) desc,finished_at desc nulls last,created_at desc,id desc limit 1
                """,project,source,analyzer,build,environmentVersion,mode,artifactSet).map(json::map);
    }

    public List<Map<String,Object>> findings(String sourceType, UUID runId) {
        String sql=switch(sourceType) {
            case "KB_FINDING" -> "select * from findings where scan_run_id=? order by id";
            case "DEPENDENCY_RESULT" -> "select * from dependency_analysis_result_items where dependency_analysis_run_id=? order by id";
            case "FLOATING_POINT_FINDING" -> "select * from specialized_analysis_findings where run_id=? order by id";
            case "MEMORY_ORDER_FINDING" -> "select * from specialized_analysis_findings where run_id=? and finding_kind in ('FINDING','SOURCE_CANDIDATE','INCONCLUSIVE') order by id";
            default -> throw new IllegalArgumentException("Unknown report source type");
        };
        return dsl.fetch(sql,runId).map(json::map);
    }
}
