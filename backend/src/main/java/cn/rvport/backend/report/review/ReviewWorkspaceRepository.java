package cn.rvport.backend.report.review;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/** 所有写操作由服务层项目锁和同一事务串行化；SQL 中的用户值一律参数绑定。 */
@Repository
public class ReviewWorkspaceRepository {
    private final DSLContext dsl;
    private final ObjectMapper mapper;
    public ReviewWorkspaceRepository(DSLContext dsl, ObjectMapper mapper) { this.dsl = dsl; this.mapper = mapper; }

    public record Draft(UUID reportId, UUID projectId, UUID sourceRevisionId, String contextHash,
            String publishState, long version, long reviewRevision, boolean writable,
            UUID currentConfirmationId, UUID previousConfirmationId) { }

    public void lockProject(UUID projectId) {
        dsl.fetch("select pg_advisory_xact_lock(hashtextextended('rvport-kb-context:' || cast(? as text),0))",projectId);
        dsl.fetch("select id from projects where id = ? for update", projectId);
    }

    public Map<UUID,cn.rvport.backend.migration.NodeReportInclusionState> includedSourceRefs(UUID project, UUID source,
            String contextHash, String sourceType, List<UUID> ids) {
        var matches = dsl.fetch("""
                select i.source_ref_id,r.report_id from report_review_runs r join report_review_items i using(report_id)
                where r.project_id=? and r.source_revision_id=? and r.context_hash=? and r.publish_state='CURRENT'
                and i.source_type=? and i.source_ref_id=any(?::uuid[])
                """, project, source, contextHash, sourceType, ids.toArray(UUID[]::new));
        return matches.intoMap(row -> row.get("source_ref_id", UUID.class), row ->
                new cn.rvport.backend.migration.NodeReportInclusionState(true, row.get("report_id", UUID.class)));
    }

    public Optional<UUID> currentReport(UUID projectId) {
        return dsl.fetchOptional("select report_id from report_review_runs where project_id=? and publish_state='CURRENT'",projectId)
                .map(row->row.get("report_id",UUID.class));
    }
    public Optional<UUID> latestGeneration(UUID projectId) {
        return dsl.fetchOptional("select report_id from report_review_runs where project_id=? order by generation_sequence desc limit 1",projectId)
                .map(row->row.get("report_id",UUID.class));
    }
    public Optional<Map<String,Object>> runSummary(UUID report) {
        return dsl.fetchOptional("""
                select r.report_id,r.source_revision_id,r.generation_sequence,r.publish_state,r.mode,r.ai_summary,
                  p.task_id,p.status,p.title,p.created_at,d.version as draft_version,d.review_revision
                from report_review_runs r join reports p on p.id=r.report_id
                left join report_review_drafts d on d.report_id=r.report_id where r.report_id=?
                """,report).map(this::map);
    }
    public boolean activeGeneration(UUID projectId) {
        return Boolean.TRUE.equals(dsl.fetchOne("select exists(select 1 from report_review_runs where project_id=? and active) as present",projectId).get("present",Boolean.class));
    }
    public void createRun(UUID reportId,ReportReviewManifest manifest,UUID actor,cn.rvport.backend.modelprofile.ModelExecutionSelection model) {
        dsl.execute("""
                insert into report_review_runs(report_id,project_id,source_revision_id,mode,context_hash,manifest,
                  configuration_user_id,model_profile_id,agent_runtime_id,base_report_id,base_review_revision,generation_sequence)
                select ?,?,?,?,?,?::jsonb,?,?,?,?,?,coalesce(max(generation_sequence),0)+1 from report_review_runs where project_id=?
                """,reportId,manifest.projectId(),manifest.sourceRevisionId(),manifest.mode().name(),manifest.contextHash(),json(manifest),
                actor,model==null?null:model.modelProfileId(),model==null?null:model.agentRuntimeId(),manifest.baseReportId(),
                manifest.baseReviewRevision(),manifest.projectId());
    }
    public boolean matchesExecution(UUID report, UUID actor, cn.rvport.backend.modelprofile.ModelExecutionSelection model) {
        if(actor==null || model!=null && !actor.equals(model.configurationUserId())) return false;
        return Boolean.TRUE.equals(dsl.fetchOne("""
                select exists(select 1 from report_review_runs where report_id=? and configuration_user_id=?
                    and model_profile_id is not distinct from ?::uuid and agent_runtime_id is not distinct from ?::uuid) as matches
                """,report,actor,model==null?null:model.modelProfileId(),model==null?null:model.agentRuntimeId()).get("matches",Boolean.class));
    }

    public ReportReviewManifest manifest(UUID reportId) {
        var row=dsl.fetchOptional("select manifest from report_review_runs where report_id=?",reportId).orElseThrow();
        try { return mapper.readValue(row.get("manifest",JSONB.class).data(),ReportReviewManifest.class); }
        catch(java.io.IOException ex) { throw new IllegalStateException("REPORT_MANIFEST_INVALID",ex); }
    }
    public void finish(UUID reportId,String publishState,Map<String,?> aiSummary) {
        dsl.execute("update report_review_runs set active=false,publish_state=?,ai_summary=?::jsonb where report_id=?",publishState,json(aiSummary),reportId);
    }
    public void supersede(UUID projectId) {
        dsl.execute("""
                update report_review_drafts set writable=false where report_id in
                (select report_id from report_review_runs where project_id=? and publish_state='CURRENT')
                """,projectId);
        dsl.execute("update report_review_runs set publish_state='HISTORICAL_SUPERSEDED' where project_id=? and publish_state='CURRENT'",projectId);
    }
    public void initializeDraft(UUID reportId,boolean writable,List<ReviewItemSnapshot> items,List<AiReviewValidator.Result> results) {
        dsl.execute("insert into report_review_drafts(report_id,writable) values(?,?)",reportId,writable);
        Map<UUID,AiReviewValidator.Result> ai=new java.util.HashMap<>(); results.forEach(result->ai.put(result.inputId(),result));
        for(var item:items) {
            var result = ai.get(item.id());
            var state = item.decision().state();
            if (state == ReviewState.PENDING_REVIEW && result != null
                    && result.verdict() == AiReviewValidator.Verdict.CONFIRMED_ISSUE) {
                state = ReviewState.TO_FIX;
            }
            dsl.execute("""
                    insert into report_review_items(id,report_id,origin,source_type,source_run_id,source_ref_id,identity_hash,evidence_hash,
                      original_snapshot,ai_review,review_state,restore_state,exclusion_reason,exclusion_reason_en,title,title_en,
                      file_key,relative_path,start_line,start_column,end_line,end_column,source_content_hash,snippet_hash,
                      manual_details,inheritance,created_by)
                    values(?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?)
                    """,item.id(),reportId,item.origin(),item.sourceType(),item.sourceRunId(),item.sourceRefId(),item.identityHash(),item.evidenceHash(),
                    json(item.originalSnapshot()),result == null ? null : json(result),state.name(),
                    item.decision().restoreState()==null?null:item.decision().restoreState().name(),item.decision().exclusionReason(),item.exclusionReasonEn(),
                    item.title(),item.titleEn(),item.fileKey(),item.relativePath(),item.startLine(),item.startColumn(),item.endLine(),item.endColumn(),
                    item.sourceContentHash(),item.snippetHash(),item.manualDetails()==null?null:json(item.manualDetails()),json(item.inheritance()),item.createdBy());
        }
    }

    public Optional<Draft> draft(UUID reportId) {
        return dsl.fetchOptional("""
                select r.report_id, r.project_id, r.source_revision_id, r.context_hash, r.publish_state,
                       d.version, d.review_revision, d.writable, d.current_confirmation_id, d.previous_confirmation_id
                from report_review_runs r join report_review_drafts d on d.report_id=r.report_id
                where r.report_id=?
                """, reportId).map(r -> new Draft(r.get("report_id",UUID.class),r.get("project_id",UUID.class),
                    r.get("source_revision_id",UUID.class),r.get("context_hash",String.class),r.get("publish_state",String.class),
                    r.get("version",Long.class),r.get("review_revision",Long.class),r.get("writable",Boolean.class),
                    r.get("current_confirmation_id",UUID.class),r.get("previous_confirmation_id",UUID.class)));
    }

    // 自动分流不产生人工事件。人工处置、显式确认或上次可靠继承才可作为下一版的人工决定。
    private static final String SELECT_ITEMS = """
            select i.*, (i.origin='MANUAL' or i.inheritance @> '{"manualDecision":true}'::jsonb
                or exists (select 1 from report_review_events e where e.report_id=i.report_id and e.item_id=i.id)
                or exists (select 1 from report_fix_list_items f where f.report_id=i.report_id and f.item_id=i.id
                           and f.review_state=i.review_state)) as has_manual_decision
            from report_review_items i where i.report_id=?
            """;

    public Optional<Map<String,Object>> item(UUID reportId, UUID itemId) {
        return dsl.fetchOptional(SELECT_ITEMS + " and i.id=?",reportId,itemId).map(this::map);
    }

    public void insertManual(Draft draft, UUID id, UUID actor, ManualReviewItemRequest request,
            cn.rvport.backend.report.ReportFileContextView file, SourceSelection.Selected selected, Map<String,Object> details) {
        dsl.execute("""
                insert into report_review_items(id,report_id,origin,identity_hash,evidence_hash,original_snapshot,
                  review_state,title,title_en,file_key,relative_path,start_line,start_column,end_line,end_column,
                  source_content_hash,snippet_hash,manual_details,created_by)
                values(?,?,'MANUAL',?,?,?::jsonb,'TO_FIX',?,?,?,?,?,?,?,?,?,?,?::jsonb,?)
                """,id,draft.reportId(),id.toString(),selected.snippetHash(),json(details),request.title()==null?"人工补充："+file.relativePath()+":"+request.range().startLine():request.title(),
                request.titleEn()==null?(request.title()==null?"Manual finding: "+file.relativePath()+":"+request.range().startLine():"Manual finding (original): "+request.title()):request.titleEn(),file.fileKey(),file.relativePath(),request.range().startLine(),
                request.range().startColumn(),request.range().endLine(),request.range().endColumn(),file.contentHash(),
                selected.snippetHash(),json(details),actor);
    }

    public List<Map<String,Object>> items(UUID reportId) {
        return dsl.fetch(SELECT_ITEMS + " order by i.created_at,i.id",reportId).map(this::map);
    }

    public Map<String,Long> summary(UUID reportId) {
        Record row = dsl.fetchOne("""
                select count(*) as total,
                    count(*) filter(where review_state='PENDING_REVIEW') as pending,
                    count(*) filter(where review_state='TO_FIX') as to_fix,
                    count(*) filter(where review_state='EXCLUDED') as excluded,
                    count(*) filter(where origin='MANUAL') as manual
                from report_review_items where report_id=?
                """,reportId);
        return Map.of("total",row.get("total",Long.class),"pendingReviewCount",row.get("pending",Long.class),
                "toFixCount",row.get("to_fix",Long.class),"excludedCount",row.get("excluded",Long.class),
                "manualCount",row.get("manual",Long.class));
    }

    public void updateDecision(UUID reportId, UUID itemId, ReviewDecision decision, String reasonEn) {
        dsl.execute("""
                update report_review_items set review_state=?,restore_state=?,exclusion_reason=?,exclusion_reason_en=?
                where report_id=? and id=?
                """, decision.state().name(),decision.restoreState()==null?null:decision.restoreState().name(),
                decision.exclusionReason(),reasonEn,reportId,itemId);
    }

    public boolean advanceDraft(UUID reportId, long expectedVersion) {
        return dsl.execute("""
                update report_review_drafts set version=version+1,review_revision=review_revision+1,
                    previous_confirmation_id=coalesce(current_confirmation_id,previous_confirmation_id),
                    current_confirmation_id=null,updated_at=now()
                where report_id=? and version=? and writable
                """,reportId,expectedVersion)==1;
    }

    public void event(UUID reportId, UUID itemId, UUID actorId, String action, long version,
            Map<String,?> before, Map<String,?> after) {
        dsl.execute("""
                insert into report_review_events(report_id,item_id,actor_id,action,draft_version,before_value,after_value)
                values(?,?,?,?,?,?::jsonb,?::jsonb)
                """,reportId,itemId,actorId,action,version,before==null?null:json(before),json(after));
    }

    public Optional<Map<String,Object>> replay(UUID actorId, UUID projectId, String operation, String key) {
        return dsl.fetchOptional("""
                select request_hash,response from report_review_operations
                where actor_id=? and project_id=? and operation=? and idempotency_key=?
                """,actorId,projectId,operation,key).map(this::map);
    }

    public void remember(UUID actorId, UUID projectId, String operation, String key, String requestHash, Map<String,?> response) {
        dsl.execute("""
                insert into report_review_operations(actor_id,project_id,operation,idempotency_key,request_hash,response)
                values(?,?,?,?,?,?::jsonb)
                """,actorId,projectId,operation,key,requestHash,json(response));
    }

    public cn.rvport.backend.common.web.PageResponse<Map<String,Object>> confirmations(UUID report,int page,int size) {
        long total=dsl.fetchOne("select count(*) from report_fix_list_confirmations where report_id=?",report).get(0,Long.class);
        var rows=dsl.fetch("select id,draft_version,item_count,reviewed_item_count,confirmed_by,confirmed_at from report_fix_list_confirmations where report_id=? order by confirmed_at desc,id desc limit ? offset ?",
                report,size,(long)(page-1)*size).map(this::map);
        return cn.rvport.backend.common.web.PageResponse.of(rows,page,size,total);
    }

    public cn.rvport.backend.common.web.PageResponse<Map<String,Object>> events(UUID report,UUID item,int page,int size) {
        long total=dsl.fetchOne("select count(*) from report_review_events where report_id=? and item_id=?",report,item).get(0,Long.class);
        var rows=dsl.fetch("select id,actor_id,action,draft_version,before_value,after_value,created_at from report_review_events where report_id=? and item_id=? order by created_at desc,id desc limit ? offset ?",
                report,item,size,(long)(page-1)*size).map(this::map);
        return cn.rvport.backend.common.web.PageResponse.of(rows,page,size,total);
    }

    public void linkDocuments(UUID report,UUID confirmation,UUID html,UUID json) {
        if(dsl.execute("update report_fix_list_confirmations set html_artifact_id=?,json_artifact_id=? where report_id=? and id=? and html_artifact_id is null and json_artifact_id is null",
                html,json,report,confirmation)!=1) throw new IllegalStateException("REPORT_CONFIRMATION_IMMUTABLE");
    }

    public Optional<Map<String,Object>> confirmationForVersion(UUID reportId, long version) {
        return dsl.fetchOptional("select * from report_fix_list_confirmations where report_id=? and draft_version=?",reportId,version).map(this::map);
    }

    public UUID confirm(Draft draft, UUID actorId, long total, long toFix) {
        UUID id=UUID.randomUUID();
        dsl.execute("""
                insert into report_fix_list_confirmations(id,report_id,draft_version,item_count,reviewed_item_count,confirmed_by)
                values(?,?,?,?,?,?)
                """,id,draft.reportId(),draft.version(),toFix,total,actorId);
        dsl.execute("""
                insert into report_fix_list_items(confirmation_id,report_id,item_id,review_state,snapshot)
                select ?,report_id,id,review_state,to_jsonb(report_review_items)
                from report_review_items where report_id=?
                """,id,draft.reportId());
        dsl.execute("""
                update report_review_drafts set current_confirmation_id=?,review_revision=review_revision+1,updated_at=now()
                where report_id=?
                """,id,draft.reportId());
        return id;
    }

    public Optional<Map<String,Object>> confirmation(UUID reportId, UUID confirmationId) {
        return dsl.fetchOptional("select c.*,c.id as confirmation_id,reviewed_item_count-item_count as excluded_count,0 as pending_review_count,case when item_count=0 then 'CONFIRMED_EMPTY' else 'CONFIRMED_WITH_ITEMS' end as confirmation_state from report_fix_list_confirmations c where report_id=? and id=?",reportId,confirmationId).map(this::map);
    }

    public List<Map<String,Object>> confirmationItems(UUID reportId, UUID confirmationId) {
        return dsl.fetch("""
                select item_id,review_state,snapshot from report_fix_list_items
                where report_id=? and confirmation_id=? order by item_id
                """,reportId,confirmationId).map(this::map);
    }

    JSONB json(Object value) {
        try { return JSONB.valueOf(mapper.writeValueAsString(value)); }
        catch (java.io.IOException ex) { throw new IllegalArgumentException("REPORT_REVIEW_JSON_INVALID",ex); }
    }

    Map<String,Object> map(Record row) {
        Map<String,Object> result=new LinkedHashMap<>();
        row.intoMap().forEach((key,value) -> result.put(camel(key),decode(value)));
        return result;
    }

    private Object decode(Object value) {
        if (!(value instanceof JSONB json)) return value;
        try { return mapper.readValue(json.data(),new TypeReference<Object>(){}); }
        catch(java.io.IOException ex) { throw new IllegalStateException("REPORT_REVIEW_STORED_JSON_INVALID",ex); }
    }

    private String camel(String key) {
        StringBuilder result=new StringBuilder(); boolean upper=false;
        for(char c:key.toCharArray()) {
            if(c=='_') { upper=true; continue; }
            result.append(upper?Character.toUpperCase(c):c); upper=false;
        }
        return result.toString();
    }
}
