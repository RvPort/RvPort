package cn.rvport.backend.report.review;

import cn.rvport.backend.artifact.StoredArtifactService;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.report.ReportFileContextView;
import cn.rvport.backend.report.ReportOrganizationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewWorkspaceService {
    private final ReviewWorkspaceRepository repository;
    private final ProjectAccessService access;
    private final ReportInputSelectionPolicy selection;
    private final ReportOrganizationRepository files;
    private final StoredArtifactService artifacts;
    private final ObjectMapper mapper;
    public ReviewWorkspaceService(ReviewWorkspaceRepository repository,ProjectAccessService access,
            ReportInputSelectionPolicy selection,ReportOrganizationRepository files,StoredArtifactService artifacts,ObjectMapper mapper) {
        this.repository=repository; this.access=access; this.selection=selection; this.files=files; this.artifacts=artifacts; this.mapper=mapper;
    }

    @Transactional
    public Map<String,Object> patch(UUID report,ReviewPatchRequest body,String key,UUID actor,boolean admin) {
        var draft=lock(report,actor,admin);
        String hash=requestHash(report,body,key);
        var replay=replay(draft,actor,"PATCH",key,hash);
        if(replay!=null) return replay;
        writable(draft,body==null?null:body.expectedVersion());
        if(body.items()==null || body.items().isEmpty() || body.items().size()>500) throw bad("REPORT_REVIEW_ACTION_INVALID");
        var ids=new java.util.HashSet<UUID>();
        List<Map<String,Object>> previous=new ArrayList<>();
        List<ReviewDecision> decisions=new ArrayList<>();
        for(var item:body.items()) {
            if(item==null || item.id()==null || !ids.add(item.id())) throw bad("REPORT_REVIEW_ACTION_INVALID");
            Map<String,Object> old=repository.item(report,item.id()).orElseThrow(()->missing("REPORT_REVIEW_ITEM_NOT_FOUND"));
            ReviewDecision decision=decision(old).apply(item.action(),item.reason());
            optionalText(item.reasonEn(),4000);
            previous.add(old); decisions.add(decision);
        }
        boolean changed=false;
        List<Map<String,Object>> changedItems=new ArrayList<>();
        for(int i=0;i<body.items().size();i++) {
            var item=body.items().get(i); var old=previous.get(i); var decision=decisions.get(i);
            String reasonEn=decision.state()==ReviewState.EXCLUDED?item.reasonEn():null;
            boolean explicitInclude = item.action() == ReviewAction.INCLUDE && !Boolean.TRUE.equals(old.get("hasManualDecision"));
            if(!explicitInclude && decision.equals(decision(old)) && Objects.equals(reasonEn,old.get("exclusionReasonEn"))) continue;
            changed=true;
            repository.updateDecision(report,item.id(),decision,reasonEn);
            var after=new LinkedHashMap<>(repository.item(report,item.id()).orElseThrow());
            after.put("hasManualDecision",true);
            repository.event(report,item.id(),actor,item.action().name(),draft.version()+1,old,after);
            changedItems.add(after);
        }
        if(changed && !repository.advanceDraft(report,draft.version())) throw conflict("REPORT_DRAFT_CONFLICT");
        Map<String,Object> response=summary(repository.draft(report).orElseThrow());
        response.put("changedItems",changedItems);
        repository.remember(actor,draft.projectId(),"PATCH",key,hash,response);
        return response;
    }

    @Transactional
    public Map<String,Object> add(UUID report,ManualReviewItemRequest body,String key,UUID actor,boolean admin) {
        var draft=lock(report,actor,admin);
        String hash=requestHash(report,body,key);
        var replay=replay(draft,actor,"ADD",key,hash);
        if(replay!=null) return replay;
        writable(draft,body==null?null:body.expectedVersion());
        optionalText(body.title(),200); requiredText(body.description(),4000); requiredText(body.repairReason(),4000);
        optionalText(body.titleEn(),200); optionalText(body.descriptionEn(),4000); optionalText(body.repairReasonEn(),4000);
        var file=files.findFileContext(report,body.fileKey()).orElseThrow(()->missing("REPORT_SOURCE_FILE_NOT_FOUND"));
        String content=content(file);
        var selected=SourceSelection.extract(content,file.contentHash(),body.sourceContentHash(),body.range());
        UUID id=UUID.randomUUID();
        Map<String,Object> details=new LinkedHashMap<>();
        details.put("description",body.description()); details.put("descriptionEn",body.descriptionEn()==null?"User description (original): "+body.description():body.descriptionEn());
        details.put("repairReason",body.repairReason()); details.put("repairReasonEn",body.repairReasonEn()==null?"Reason for repair (original): "+body.repairReason():body.repairReasonEn());
        details.put("snippet",selected.snippet()); details.put("snippetHash",selected.snippetHash());
        details.put("originalTextLanguage","USER_PROVIDED");
        details.put("range",body.range()); details.put("translationStatus",(body.title()==null || body.titleEn()!=null) && body.descriptionEn()!=null
                && body.repairReasonEn()!=null?"PROVIDED":"ORIGINAL_ONLY");
        repository.insertManual(draft,id,actor,body,file,selected,details);
        Map<String,Object> item=repository.item(report,id).orElseThrow();
        repository.event(report,id,actor,"ADD",draft.version()+1,null,item);
        if(!repository.advanceDraft(report,draft.version())) throw conflict("REPORT_DRAFT_CONFLICT");
        Map<String,Object> response=summary(repository.draft(report).orElseThrow()); response.put("item",item);
        repository.remember(actor,draft.projectId(),"ADD",key,hash,response);
        return response;
    }

    @Transactional
    public Map<String,Object> confirm(UUID report,Long version,String key,UUID actor,boolean admin) {
        var draft=lock(report,actor,admin);
        String hash=requestHash(report,version,key);
        var replay=replay(draft,actor,"CONFIRM",key,hash);
        if(replay!=null) return replay;
        writable(draft,version);
        var counts=repository.summary(report);
        if(counts.get("pendingReviewCount")!=0) throw new ApiException(HttpStatus.CONFLICT,"REPORT_PENDING_REVIEW",
                "请先处理全部待复核条目",Map.of("pendingReviewCount",counts.get("pendingReviewCount")));
        var existing=repository.confirmationForVersion(report,version);
        UUID id=existing.map(value->UUID.fromString(value.get("id").toString())).orElseGet(()->{
            UUID created=repository.confirm(draft,actor,counts.get("total"),counts.get("toFixCount"));
            freezeDocuments(draft,created);
            repository.event(report,null,actor,"CONFIRM",draft.version(),null,Map.of("confirmationId",created));
            return created;
        });
        Map<String,Object> response=new LinkedHashMap<>(repository.confirmation(report,id).orElseThrow());
        response.put("confirmationId",id); response.put("availableForCurrentWorkflow",true); response.put("confirmationState",counts.get("toFixCount")==0?"CONFIRMED_EMPTY":"CONFIRMED_WITH_ITEMS");
        repository.remember(actor,draft.projectId(),"CONFIRM",key,hash,response);
        return response;
    }

    @Transactional(readOnly=true)
    public Map<String,Object> detail(UUID report,UUID item,UUID actor,boolean admin) {
        var draft=authorized(report,actor,admin);
        var response=new LinkedHashMap<>(repository.item(report,item).orElseThrow(()->missing("REPORT_REVIEW_ITEM_NOT_FOUND")));
        response.putAll(displayFields(response));
        response.put("draftVersion",draft.version()); response.putAll(availability(draft)); return response;
    }

    @Transactional(readOnly=true)
    public Map<String,Object> list(UUID report,ReviewItemQuery query,UUID actor,boolean admin) {
        var draft=authorized(report,actor,admin);
        if(query.draftVersion()!=null && query.draftVersion()!=draft.version()) throw conflict("REPORT_DRAFT_CONFLICT");
        var filtered=query.filteredPage(repository.items(report));
        var page=filtered.page();
        Map<String,Object> response=summary(draft);
        response.put("filteredSummary",filtered.summary());
        response.put("items",page.items().stream().map(row->{var result=new LinkedHashMap<>(row); result.putAll(displayFields(row)); return result;}).toList()); response.put("total",page.total()); response.put("page",page.page());
        response.put("pageSize",page.pageSize()); response.put("hasMore",page.hasMore()); response.put("draftVersion",draft.version());
        return response;
    }

    @Transactional(readOnly=true)
    public Map<String,Object> confirmation(UUID report,UUID confirmation,UUID actor,boolean admin,int page,int pageSize,String scope) {
        var draft=authorized(report,actor,admin);
        if(!List.of("ALL","TO_FIX").contains(scope)) throw bad("REPORT_REQUEST_INVALID");
        Map<String,Object> response=new LinkedHashMap<>(repository.confirmation(report,confirmation)
                .orElseThrow(()->missing("REPORT_CONFIRMATION_NOT_FOUND")));
        response.put("confirmationId",confirmation);
        response.put("availableForCurrentWorkflow",confirmation.equals(draft.currentConfirmationId()) && isWritable(draft));
        var query=new ReviewItemQuery(page,pageSize,null,scope.equals("TO_FIX")?"TO_FIX":null,null,null,null,null,null,null,null,null);
        var items=query.apply(repository.confirmationItems(report,confirmation));
        response.put("items",items.items()); response.put("total",items.total()); response.put("page",page);
        response.put("pageSize",pageSize); response.put("hasMore",items.hasMore()); return response;
    }

    private void freezeDocuments(ReviewWorkspaceRepository.Draft draft,UUID id) {
        var rows=repository.confirmationItems(draft.reportId(),id);
        Map<String,Object> document=new LinkedHashMap<>();
        document.put("schemaVersion","confirmed-fix-list.v1"); document.put("reportId",draft.reportId());
        document.put("sourceRevisionId",draft.sourceRevisionId()); document.put("confirmationId",id);
        document.put("draftVersion",draft.version()); document.put("decisions",rows);
        document.put("fixItems",rows.stream().filter(row->"TO_FIX".equals(row.get("reviewState"))).toList());
        var renderer=new ReportReviewRenderer(mapper);
        UUID json=store(draft,id,"JSON","application/json",renderer.json(document));
        UUID html=store(draft,id,"HTML","text/html",renderer.html(document).getBytes(StandardCharsets.UTF_8));
        repository.linkDocuments(draft.reportId(),id,html,json);
    }
    private UUID store(ReviewWorkspaceRepository.Draft draft,UUID id,String type,String mime,byte[] content) {
        return artifacts.store(new cn.rvport.backend.artifact.StoredArtifactCreateCommand(draft.projectId(),null,
                cn.rvport.backend.artifact.StoredArtifactOwnerType.MIGRATION_REPORT,draft.reportId(),"FIX_LIST_"+id+"_"+type,mime,content,
                Map.of("confirmationId",id,"draftVersion",draft.version()),type.toLowerCase(java.util.Locale.ROOT))).id();
    }
    @Transactional(readOnly=true)
    public cn.rvport.backend.report.ReportService.DownloadableReport download(UUID report,UUID confirmation,String format,UUID actor,boolean admin) {
        authorized(report,actor,admin);
        var saved=repository.confirmation(report,confirmation).orElseThrow(()->missing("REPORT_CONFIRMATION_NOT_FOUND"));
        cn.rvport.backend.report.ReportDownloadFormat parsed;
        try { parsed=cn.rvport.backend.report.ReportDownloadFormat.parse(format); } catch(IllegalArgumentException ex) { throw bad("REPORT_REQUEST_INVALID"); }
        Object reference=saved.get(parsed==cn.rvport.backend.report.ReportDownloadFormat.HTML?"htmlArtifactId":"jsonArtifactId");
        if(reference==null) throw missing("REPORT_CONFIRMATION_ARTIFACT_NOT_FOUND");
        var file=artifacts.download(UUID.fromString(reference.toString()));
        return new cn.rvport.backend.report.ReportService.DownloadableReport(file.resource(),"fix-list-"+confirmation+"."+parsed.extension(),parsed.contentType());
    }
    @Transactional(readOnly=true)
    public cn.rvport.backend.common.web.PageResponse<Map<String,Object>> confirmations(UUID report,UUID actor,boolean admin,int page,int size) {
        authorized(report,actor,admin); validatePage(page,size); return repository.confirmations(report,page,size);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> detail(UUID report,UUID item,UUID actor,boolean admin,String include,int page,int size) {
        var response=detail(report,item,actor,admin);
        if(include!=null) {
            if(!include.equals("history")) throw bad("REPORT_REQUEST_INVALID");
            validatePage(page,size); response.put("history",repository.events(report,item,page,size));
        }
        return response;
    }
    private Map<String,Object> displayFields(Map<String,Object> row) {
        Map<?,?> original=row.get("originalSnapshot") instanceof Map<?,?> snapshot ? snapshot : Map.of();
        Map<String,Object> fields=new LinkedHashMap<>();
        fields.put("originalEvidence",original);
        fields.put("riskLevel",original.get("riskLevel"));
        fields.put("findingKind",original.get("findingKind"));
        fields.put("groupKey",original.get("groupKey"));
        Object locations=original.get("locations");
        if("MANUAL".equals(row.get("origin"))) locations=List.of(Map.of("fileKey",row.get("fileKey"),
                "path",row.get("relativePath"),"startLine",row.get("startLine"),"endLine",row.get("endLine")));
        fields.put("locations",locations==null ? List.of() : locations);
        return fields;
    }
    private void validatePage(int page,int size) {
        if(page<1 || size<1 || size>200) throw bad("REPORT_REQUEST_INVALID");
    }

    /** 调用方已按报告检查项目权限；历史正文与人工处置事实不从活动扫描重建。 */
    @Transactional(readOnly=true)
    public Map<String,Object> metadata(UUID report) {
        Map<String,Object> result=new LinkedHashMap<>(repository.runSummary(report).orElseThrow());
        repository.draft(report).ifPresentOrElse(draft->{
            result.putAll(summary(draft));
        },()->result.put("writable",false));
        return result;
    }

    Map<String,Object> summary(ReviewWorkspaceRepository.Draft draft) {
        var response=new LinkedHashMap<String,Object>();
        var counts=new LinkedHashMap<String,Object>(repository.summary(draft.reportId())); counts.put("version",draft.version());
        response.putAll(availability(draft));
        response.put("draftSummary",counts); response.put("currentConfirmationId",draft.currentConfirmationId());
        response.put("previousConfirmationId",draft.previousConfirmationId());
        response.put("confirmationState",draft.currentConfirmationId()!=null
                ? ((Long)counts.get("toFixCount")==0?"CONFIRMED_EMPTY":"CONFIRMED_WITH_ITEMS")
                :draft.previousConfirmationId()==null?"UNCONFIRMED":"NEEDS_CONFIRMATION");
        return response;
    }

    ReviewWorkspaceRepository.Draft authorized(UUID report,UUID actor,boolean admin) {
        var draft=repository.draft(report).orElseThrow(()->missing("REPORT_NOT_FOUND"));
        access.requireProjectAccess(draft.projectId(),actor,admin); return draft;
    }
    private ReviewWorkspaceRepository.Draft lock(UUID report,UUID actor,boolean admin) {
        var draft=authorized(report,actor,admin); repository.lockProject(draft.projectId());
        return repository.draft(report).orElseThrow(()->missing("REPORT_NOT_FOUND"));
    }
    private Map<String,Object> availability(ReviewWorkspaceRepository.Draft draft) {
        boolean valid=Objects.equals(selection.current(draft.projectId()).contextHash(),draft.contextHash());
        boolean writable=valid && draft.writable() && "CURRENT".equals(draft.publishState());
        var result=new LinkedHashMap<String,Object>();
        result.put("contextValid",valid);
        result.put("writable",writable);
        result.put("readOnlyReason",writable ? null : valid ? "REPORT_SUPERSEDED" : "REPORT_CONTEXT_CHANGED");
        return result;
    }
    private boolean isWritable(ReviewWorkspaceRepository.Draft draft) {
        return Boolean.TRUE.equals(availability(draft).get("writable"));
    }
    private void writable(ReviewWorkspaceRepository.Draft draft,Long version) {
        if(!isWritable(draft)) throw conflict("REPORT_READ_ONLY");
        if(version==null || version<0) throw bad("REPORT_REQUEST_INVALID");
        if(draft.version()!=version) throw new ApiException(HttpStatus.CONFLICT,"REPORT_DRAFT_CONFLICT","复核草稿已经变化",
                Map.of("currentVersion",draft.version()));
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> replay(ReviewWorkspaceRepository.Draft draft,UUID actor,String op,String key,String hash) {
        var prior=repository.replay(actor,draft.projectId(),op,key);
        if(prior.isEmpty()) return null;
        if(!hash.equals(prior.get().get("requestHash"))) throw conflict("IDEMPOTENCY_KEY_REUSED");
        return (Map<String,Object>)prior.get().get("response");
    }
    private String requestHash(UUID report,Object body,String key) {
        requiredText(key,200);
        try { return SourceSelection.hash(mapper.writeValueAsBytes(java.util.Arrays.asList(report,body))); }
        catch(java.io.IOException ex) { throw bad("REPORT_REQUEST_INVALID"); }
    }
    private String content(ReportFileContextView file) {
        if(!file.contextAvailable() || file.artifactId()==null) throw conflict("SOURCE_CONTEXT_UNAVAILABLE");
        try(var stream=artifacts.download(file.artifactId()).resource().getInputStream()) {
            byte[] bytes=stream.readAllBytes();
            if(!SourceSelection.hash(bytes).equals(file.contentHash())) throw conflict("SOURCE_CONTENT_CHANGED");
            return new String(bytes,StandardCharsets.UTF_8);
        } catch(java.io.IOException ex) { throw conflict("SOURCE_CONTEXT_UNAVAILABLE"); }
    }
    private ReviewDecision decision(Map<String,Object> value) {
        return new ReviewDecision(ReviewState.valueOf(value.get("reviewState").toString()),
                value.get("restoreState")==null?null:ReviewState.valueOf(value.get("restoreState").toString()),(String)value.get("exclusionReason"));
    }
    private void requiredText(String value,int max) { if(value==null || value.isBlank() || value.length()>max) throw bad("REPORT_REQUEST_INVALID"); }
    private void optionalText(String value,int max) { if(value!=null) requiredText(value,max); }
    private static ApiException bad(String code) { return new ApiException(HttpStatus.BAD_REQUEST,code,"请求参数不正确"); }
    private static ApiException conflict(String code) { return new ApiException(HttpStatus.CONFLICT,code,"报告状态已变化，请刷新后重试"); }
    private static ApiException missing(String code) { return new ApiException(HttpStatus.NOT_FOUND,code,"报告资源不存在"); }
}
