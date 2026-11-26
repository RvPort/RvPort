package cn.rvport.backend.report.review;

import cn.rvport.backend.agentruntime.AgentModelRuntimeConfig;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.modelprofile.ModelExecutionSelection;
import cn.rvport.backend.modelprofile.ModelProfileService;
import cn.rvport.backend.modelprofile.ModelProfileStage;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.report.ReportGenerateRequest;
import cn.rvport.backend.report.ReportRepository;
import cn.rvport.backend.source.SourceRepository;
import cn.rvport.backend.task.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReportReviewGenerationService {
    private final ProjectAccessService access;
    private final ReportInputSelectionPolicy policy;
    private final ReviewWorkspaceRepository reviews;
    private final SourceRepository sources;
    private final ReportSourceSnapshotService sourceSnapshots;
    private final ReviewSnapshotFactory snapshots;
    private final ModelProfileService models;
    private final TaskService tasks;
    private final ReportRepository reports;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final cn.rvport.backend.worker.ReportWorkerCapabilityService capabilities;
    public ReportReviewGenerationService(ProjectAccessService access,ReportInputSelectionPolicy policy,ReviewWorkspaceRepository reviews,
            SourceRepository sources,ReportSourceSnapshotService sourceSnapshots,ReviewSnapshotFactory snapshots,ModelProfileService models,
            TaskService tasks,ReportRepository reports,ObjectMapper mapper,PlatformTransactionManager transactionManager,
            cn.rvport.backend.worker.ReportWorkerCapabilityService capabilities) {
        this.access=access; this.policy=policy; this.reviews=reviews; this.sources=sources; this.sourceSnapshots=sourceSnapshots;
        this.snapshots=snapshots; this.models=models; this.tasks=tasks; this.reports=reports; this.mapper=mapper;
        this.transaction=new TransactionTemplate(transactionManager);
        this.capabilities=capabilities;
    }
    public Map<String,Object> create(UUID project,ReportGenerateRequest request,String key,UUID actor,boolean admin) {
        access.requireProjectAccess(project,actor,admin);
        if(request==null || request.mode()==null || request.selectionToken()==null || request.selectionToken().isBlank()
                || key==null || key.isBlank() || key.length()>200) throw bad("REPORT_REQUEST_INVALID");
        String hash=hash(request);
        var replay=replay(project,actor,key,hash); if(replay!=null) return replay;
        var selected=policy.current(project); validate(selected,request);
        var revision=sources.findById(selected.sourceRevisionId()).orElseThrow(()->conflict("REPORT_SOURCE_REQUIRED"));
        // 源码 IO 在事务外完成；事务内重新校验选择令牌和草稿基线。
        var source=sourceSnapshots.prepare(revision);
        return transaction.execute(status->{
            reviews.lockProject(project);
            var repeated=replay(project,actor,key,hash); if(repeated!=null) return repeated;
            var current=policy.current(project); validate(current,request);
            if(reviews.activeGeneration(project)) throw conflict("REPORT_GENERATION_IN_PROGRESS");
            var base=regenerationBase(project,current);
            UUID baseId=base==null?null:base.reportId();
            if(!Objects.equals(baseId,request.baseReportId())) throw conflict("REPORT_REVIEW_BASE_CHANGED");
            if(base!=null && !Objects.equals(request.expectedDraftVersion(),base.version())) throw conflict("REPORT_DRAFT_CONFLICT");
            if(base==null && request.expectedDraftVersion()!=null) throw bad("REPORT_REQUEST_INVALID");
            AgentModelRuntimeConfig configured=request.mode()==ReportMode.AI_ENHANCED?ai(actor):null;
            ModelExecutionSelection model=configured==null?null:ModelExecutionSelection.from(actor,configured);
            List<Map<String,Object>> previous=base==null?List.of():reviews.items(baseId);
            var items=snapshots.create(current,source,actor,base,previous);
            boolean executionRequired=items.stream().anyMatch(item->"SCANNER".equals(item.origin()) && item.decision().state()!=ReviewState.EXCLUDED);
            if(configured!=null && "UNAVAILABLE".equals(capabilities.current(configured.runtime(),executionRequired).get("status")))
                throw new ApiException(HttpStatus.CONFLICT,"REPORT_AI_SANDBOX_UNAVAILABLE","报告 AI 执行沙箱不可用",
                        Map.of("messageEn","The report AI execution sandbox is unavailable","recoveryAction","CHECK_WORKER_ENVIRONMENT"));
            var manifest=new ReportReviewManifest(project,current.sourceRevisionId(),current.contextHash(),request.mode(),current,source,
                    items,baseId,base==null?null:base.reviewRevision(),
                    cn.rvport.backend.report.SourceVersionMetadata.of(revision.versionLabel(),revision.gitCommit(),"SNAPSHOT"),
                    ReasonDescriptionResolver.snapshot(items,current));
            UUID reportId=UUID.randomUUID();
            Map<String,Object> input=new LinkedHashMap<>(); input.put("projectId",project); input.put("sourceRevisionId",current.sourceRevisionId());
            input.put("reportId",reportId); input.put("modelExecution",model);
            input.put("configurationUserId",actor);
            var task=tasks.create(new TaskCreateCommand(project,RvPortTaskTypes.REPORT_GENERATE,input,actor,null,"结果报告任务已创建"));
            reports.createEvaluation(reportId,project,current.sourceRevisionId(),task.id(),"结果评估报告 / Result assessment report",actor);
            reports.captureSourceMetadata(reportId,manifest.sourceMetadata());
            reviews.createRun(reportId,manifest,actor,model);
            Map<String,Object> response=mapper.convertValue(task,new TypeReference<LinkedHashMap<String,Object>>(){});
            response.put("reportId",reportId);
            reviews.remember(actor,project,"GENERATE",key,hash,response);
            return response;
        });
    }
    @org.springframework.transaction.annotation.Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> readiness(UUID project,UUID requestedSource,UUID actor,boolean admin) {
        access.requireProjectAccess(project,actor,admin);
        var selected=policy.current(project);
        if(requestedSource!=null && !Objects.equals(requestedSource,selected.sourceRevisionId())) throw conflict("REPORT_CONTEXT_CHANGED");
        boolean aiReady=true; List<Map<String,Object>> blockers=new ArrayList<>();
        AgentModelRuntimeConfig configured=null;
        try { configured=ai(actor); } catch(ApiException ex) {
            aiReady=false; blockers.add(Map.of("category","CONFIGURATION","code",ex.code(),"message","请配置个人报告复核模型",
                    "messageEn","Configure your personal report review model","recoveryAction","OPEN_PERSONAL_MODEL_CONFIGURATION"));
        }
        var preview=previewRows(selected,actor);
        boolean executionRequired=preview.stream().anyMatch(item->!Boolean.TRUE.equals(item.get("inheritedExcluded")));
        var capability=capabilities.current(configured==null?null:configured.runtime(),executionRequired);
        boolean executionBlocked="UNAVAILABLE".equals(capability.get("status"));
        if(executionBlocked) blockers.add(Map.of("category","EXECUTION","code","REPORT_AI_SANDBOX_UNAVAILABLE",
                "message","报告 AI 执行沙箱不可用","messageEn","The report AI execution sandbox is unavailable","recoveryAction","CHECK_WORKER_ENVIRONMENT"));
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("sourceRevisionId",selected.sourceRevisionId()); result.put("selectionToken",selected.contextHash());
        result.put("canGenerate",selected.canGenerate()); result.put("canGenerateStandard",selected.canGenerate());
        result.put("aiConfigurationReady",aiReady);result.put("aiExecutionCapability",capability);
        result.put("aiWarnings","UNKNOWN".equals(capability.get("status"))?List.of(capability):List.of());
        result.put("canGenerateAi",selected.canGenerate() && aiReady && !executionBlocked);
        result.put("recommendedMode",selected.canGenerate() && aiReady && !executionBlocked?"AI_ENHANCED":"STANDARD");
        result.put("aiBlockers",blockers); result.put("generationInProgress",reviews.activeGeneration(project));
        result.put("sources",selected.sources().stream().map(source->{
            Map<String,Object> value=new LinkedHashMap<>(); value.put("type",source.type()); value.put("available",source.available());
            value.put("runId",source.runId()); value.put("status",source.status()); value.put("resultVersion",source.resultVersion());
            value.put("executionContext",source.execution()); value.put("finishedAt",source.execution().get("finishedAt"));
            value.put("reasonCode",source.reasonCode()); value.put("rawFindingCount",source.findings().size());
            long count=preview.stream().filter(i->source.type().equals(i.get("sourceType"))).count();
            long skipped=preview.stream().filter(i->source.type().equals(i.get("sourceType")) && Boolean.TRUE.equals(i.get("inheritedExcluded"))).count();
            if("MEMORY_ORDER_FINDING".equals(source.type())) value.put("dischargedCount",source.execution().getOrDefault("dischargedCount",0));
            value.put("candidateCount",count); value.put("inheritedExcludedCount",skipped); value.put("aiInputCount",count-skipped); return value;
        }).toList());
        result.putAll(reportFacts(project, selected));
        return result;
    }

    /** 阅读报告不解析查看者的模型；只有生成资格才需要个人绑定。 */
    @org.springframework.transaction.annotation.Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> latest(UUID project, UUID requestedSource, UUID actor, boolean admin) {
        access.requireProjectAccess(project,actor,admin);
        var selected=policy.current(project);
        if(requestedSource!=null && !requestedSource.equals(selected.sourceRevisionId())) throw conflict("REPORT_CONTEXT_CHANGED");
        var result=reportFacts(project,selected);
        result.put("sourceRevisionId",selected.sourceRevisionId());
        return result;
    }
    private Map<String,Object> reportFacts(UUID project, ReportInputSelectionPolicy.Selection selected) {
        Map<String,Object> result=new LinkedHashMap<>();
        var base = regenerationBase(project,selected);
        UUID current = base!=null && base.contextHash().equals(selected.contextHash()) ? base.reportId() : null;
        result.put("regenerationBaseReportId",base==null?null:base.reportId());
        result.put("regenerationBaseDraftVersion",base==null?null:base.version());
        var latest=reviews.latestGeneration(project);
        result.put("currentReportId",current); result.put("latestGenerationId",latest.orElse(null));
        result.put("latestGeneration",latest.flatMap(reviews::runSummary).orElse(null));
        result.put("currentReport",current==null?null:reviews.runSummary(current).orElse(null));
        return result;
    }
    private ReviewWorkspaceRepository.Draft regenerationBase(UUID project, ReportInputSelectionPolicy.Selection selected) {
        return reviews.currentReport(project).flatMap(reviews::draft)
                .filter(base -> Objects.equals(base.sourceRevisionId(),selected.sourceRevisionId())).orElse(null);
    }

    public cn.rvport.backend.common.web.PageResponse<Map<String,Object>> preview(UUID project,UUID source,ReviewItemQuery query,UUID actor,boolean admin) {
        access.requireProjectAccess(project,actor,admin);
        var selection=policy.current(project);
        if(source!=null && !source.equals(selection.sourceRevisionId())) throw conflict("REPORT_CONTEXT_CHANGED");
        return query.apply(previewRows(selection,actor));
    }
    private List<Map<String,Object>> previewRows(ReportInputSelectionPolicy.Selection selected,UUID actor) {
        var base=regenerationBase(selected.projectId(),selected);
        var fixed=base==null?new ReportSourceSnapshotService.Manifest(selected.sourceRevisionId(),null,List.of(),null):
                sourceSnapshots.existing(selected.sourceRevisionId());
        var items=snapshots.create(selected,fixed,actor,base,base==null?List.of():reviews.items(base.reportId()));
        return items.stream().filter(item->"SCANNER".equals(item.origin())).map(item->{
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("id",item.sourceRefId()); row.put("sourceType",item.sourceType()); row.put("sourceRunId",item.sourceRunId());
            row.put("title",item.title()); row.put("titleEn",item.titleEn()); row.put("riskLevel",item.originalSnapshot().get("riskLevel"));
            row.put("originalSnapshot",item.originalSnapshot());
            row.put("relativePath",item.relativePath()); row.put("startLine",item.startLine()); row.put("fileKey",item.fileKey());
            boolean include=item.decision().state()!=ReviewState.EXCLUDED;
            row.put("inheritedExcluded",!include); row.put("aiIncluded",include);
            row.put("aiReason",include?"REVIEW_REQUIRED":"INHERITED_EXCLUSION");
            return row;
        }).toList();
    }
    private AgentModelRuntimeConfig ai(UUID actor) {
        AgentModelRuntimeConfig config;
        try { config=models.resolveAgentForStage(ModelProfileStage.REPORT_AI_REVIEW,actor); }
        catch(ApiException ex) {
            if(ex.code().startsWith("MODEL_ASSIGNMENT_")) throw conflict("REPORT_AI_MODEL_NOT_CONFIGURED");
            if(ex.code().equals("MODEL_CONFIGURATION_USER_UNAVAILABLE")) throw conflict(ex.code());
            throw ex;
        }
        if(config==null || !config.model().enabled() || blank(config.model().apiKey()) || blank(config.model().model())
                || blank(config.model().baseUrl()) || config.runtime()==null || blank(config.runtime().getExecutablePath()))
            throw conflict("REPORT_AI_MODEL_NOT_CONFIGURED");
        return config;
    }
    private boolean blank(String value) { return value==null || value.isBlank(); }
    private void validate(ReportInputSelectionPolicy.Selection selected,ReportGenerateRequest request) {
        if(selected.sourceRevisionId()==null) throw conflict("REPORT_SOURCE_REQUIRED");
        if(request.sourceRevisionId()!=null && !request.sourceRevisionId().equals(selected.sourceRevisionId())) throw conflict("REPORT_CONTEXT_CHANGED");
        if(!request.selectionToken().equals(selected.contextHash())) throw conflict("REPORT_SELECTION_CHANGED");
        if(!selected.canGenerate()) throw conflict("REPORT_ANALYSIS_NOT_READY");
    }
    @SuppressWarnings("unchecked") private Map<String,Object> replay(UUID project,UUID actor,String key,String hash) {
        var saved=reviews.replay(actor,project,"GENERATE",key);
        if(saved.isEmpty()) return null;
        if(!hash.equals(saved.get().get("requestHash"))) throw conflict("IDEMPOTENCY_KEY_REUSED");
        return (Map<String,Object>)saved.get().get("response");
    }
    private String hash(Object request) {
        try { return SourceSelection.hash(mapper.writeValueAsBytes(request)); }
        catch(java.io.IOException ex) { throw bad("REPORT_REQUEST_INVALID"); }
    }
    private static ApiException bad(String code) { return new ApiException(HttpStatus.BAD_REQUEST,code,"报告请求参数不正确",
            Map.of("messageEn","Invalid report request parameters")); }
    private static ApiException conflict(String code) { return new ApiException(HttpStatus.CONFLICT,code,"报告生成条件已变化，请刷新后重试"); }
}
