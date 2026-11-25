package cn.rvport.backend.report.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ReportReviewRenderer {
    private final ObjectMapper mapper;
    public ReportReviewRenderer(ObjectMapper mapper) { this.mapper=mapper; }
    public Map<String,Object> summary(ReportReviewManifest manifest) {
        var scanner=manifest.items().stream().filter(item->item.origin().equals("SCANNER")).toList();
        long candidates=scanner.stream().filter(this::unverified).count();
        Map<String,Object> summary=new LinkedHashMap<>();
        summary.put("rawFindingCount",manifest.selection().sources().stream().mapToInt(s->s.findings().size()).sum());
        summary.put("decisionItemCount",scanner.size()); summary.put("formalRiskCount",scanner.size()-candidates);
        summary.put("unverifiedCandidateCount",candidates); summary.put("mode",manifest.mode().name());
        summary.put("policyVersion",ReportInputSelectionPolicy.POLICY_VERSION);
        Map<String,Long> risks=new TreeMap<>();
        scanner.stream().filter(i->!unverified(i)).forEach(i->risks.merge(Objects.toString(i.originalSnapshot().get("riskLevel"),"UNKNOWN"),1L,Long::sum));
        summary.put("riskCounts",risks);
        return summary;
    }
    public String overallRisk(ReportReviewManifest manifest) {
        var levels=manifest.items().stream().filter(i->i.origin().equals("SCANNER") && !unverified(i))
                .map(i->Objects.toString(i.originalSnapshot().get("riskLevel"),"UNKNOWN")).toList();
        return List.of("CRITICAL","HIGH","MEDIUM","LOW","INFO","COMMENT").stream().filter(levels::contains).findFirst().orElse("UNKNOWN");
    }
    public Map<String,Object> document(UUID report,ReportReviewManifest manifest,ReportAiReviewRuntime.Reviewed reviewed) {
        Map<String,Object> result=new LinkedHashMap<>(); result.put("schemaVersion","report-review.v2"); result.put("reportId",report);
        result.put("projectId",manifest.projectId()); result.put("sourceRevisionId",manifest.sourceRevisionId());
        result.put("contextHash",manifest.contextHash()); result.put("mode",manifest.mode()); result.put("summary",summary(manifest));
        result.put("sources",sources(manifest));
        // 固定扫描事实与 AI 结论；人工工作台通过自己的版本和确认制品呈现。
        result.put("findings",manifest.items().stream().filter(item->item.origin().equals("SCANNER")).map(item->{
            Map<String,Object> value=new LinkedHashMap<>(); value.put("id",item.id()); value.put("sourceType",item.sourceType());
            value.put("sourceRunId",item.sourceRunId()); value.put("sourceRefId",item.sourceRefId()); value.put("original",item.originalSnapshot());
            value.put("title",item.title()); value.put("titleEn",item.titleEn()); value.put("fileKey",item.fileKey()); value.put("path",item.relativePath());
            value.put("startLine",item.startLine()); value.put("endLine",item.endLine()); return value;
        }).toList());
        result.put("aiSummary",reviewed.summary()); result.put("aiReviews",reviewed.results()); return result;
    }
    public List<Map<String,Object>> sources(ReportReviewManifest manifest) {
        return manifest.selection().sources().stream().map(source->{
            Map<String,Object> value=new LinkedHashMap<>(); value.put("type",source.type()); value.put("runId",source.runId());
            value.put("available",source.available()); value.put("status",source.status()); value.put("resultVersion",source.resultVersion());
            value.put("reasonCode",source.reasonCode()); value.put("rawFindingCount",source.findings().size());
            value.put("candidateCount",source.candidates().size());
            value.put("resultAvailable",source.available());
            value.put("analysisStatus",source.status());
            if(source.execution().get("latestExecution") instanceof Map<?,?> execution) {
                Map<String,Object> latest=new LinkedHashMap<>();
                for(String key:List.of("id","status","reasonCode")) if(execution.containsKey(key)) latest.put(key,execution.get(key));
                value.put("latestExecution",latest);
            }
            if(source.execution().get("summary") instanceof Map<?,?> evidence) {
                for(String field:List.of("inputOrigin","analysisMode","analysisComplete","coverage","assumptions",
                        "missingEvidence","limitations","analysisReasonCodes","analysisLayers",
                        "requestedInputDigest","effectiveInputDigest","sourceDigest","inputPlan"))
                    if(evidence.containsKey(field)) value.put(field,evidence.get(field));
            }
            if("MEMORY_ORDER_FINDING".equals(source.type()))
                value.put("dischargedCount",source.execution().getOrDefault("dischargedCount",0));
            return value;
        }).toList();
    }
    public byte[] json(Object document) {
        try { return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(document); }
        catch(java.io.IOException ex) { throw new IllegalArgumentException("REPORT_DOCUMENT_INVALID",ex); }
    }
    public String html(Map<String,Object> document) {
        String title=document.containsKey("confirmationId")?"已确认待修复清单 / Confirmed fix list":"结果评估报告 / Result assessment report";
        return "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\">"
                +"<title>"+title+"</title><style>body{font-family:sans-serif;margin:2rem}pre{white-space:pre-wrap;overflow-wrap:anywhere}</style>"
                +"<h1>"+title+"</h1><p>原始扫描与 AI 复核结果保持固定；人工确认清单独立版本化。 / Original findings and AI review are immutable; confirmed fix lists are versioned separately.</p><pre>"
                +escape(new String(json(document),java.nio.charset.StandardCharsets.UTF_8))+"</pre></html>";
    }
    private boolean unverified(ReviewItemSnapshot item) {
        return List.of("SOURCE_CANDIDATE","INCONCLUSIVE").contains(Objects.toString(item.originalSnapshot().get("findingKind"),""));
    }
    private String escape(String value) { return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;"); }
}
