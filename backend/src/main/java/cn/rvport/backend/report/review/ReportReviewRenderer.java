package cn.rvport.backend.report.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class ReportReviewRenderer {
    private final ObjectMapper mapper;
    public ReportReviewRenderer(ObjectMapper mapper) { this.mapper=mapper; }
    public Map<String,Object> summary(ReportReviewManifest manifest) {
        // 已处置条目（MIGRATED / MIGRATION_NO_WORK）退出风险计数（loop change：报告反映"仍需处理"集合）
        var scanner=manifest.items().stream()
                .filter(item->item.origin().equals("SCANNER")&&!disposed(item)).toList();
        var allScanner=manifest.items().stream().filter(item->item.origin().equals("SCANNER")).toList();
        long candidates=scanner.stream().filter(this::unverified).count();
        Map<String,Object> summary=new LinkedHashMap<>();
        summary.put("rawFindingCount",manifest.selection().sources().stream().mapToInt(s->s.findings().size()).sum());
        summary.put("decisionItemCount",allScanner.size()); summary.put("formalRiskCount",scanner.size()-candidates);
        summary.put("unverifiedCandidateCount",candidates); summary.put("mode",manifest.mode().name());
        summary.put("policyVersion",ReportInputSelectionPolicy.POLICY_VERSION);
        Map<String,Long> risks=new TreeMap<>();
        scanner.stream().filter(i->!unverified(i)).forEach(i->risks.merge(normalizedLevel(i),1L,Long::sum));
        summary.put("riskCounts",risks);
        // 旧展示契约字段：评估报告页统计卡与既有前端直接读取这些键，生成时一并计算。
        var code=scanner.stream().filter(i->"KB_FINDING".equals(i.sourceType())).toList();
        summary.put("migratedCount",allScanner.stream().filter(this::disposed).count());
        summary.put("migrationStateApplied",allScanner.stream().anyMatch(this::hasMigrationState));
        summary.put("codeFindingCount",code.size());
        summary.put("codeFileCount",code.stream().map(ReviewItemSnapshot::fileKey).filter(Objects::nonNull).distinct().count());
        summary.put("codeModuleCount",code.stream().map(i->moduleKeyOf(i.relativePath())).distinct().count());
        summary.put("dependencyAnalysisInputCount",scanner.stream().filter(i->"DEPENDENCY_RESULT".equals(i.sourceType())).count());
        summary.put("highRiskIssueCount",scanner.stream().filter(i->!unverified(i))
                .filter(i->List.of("CRITICAL","HIGH").contains(normalizedLevel(i))).count());
        return summary;
    }

    /** 与发布落库的模块推导保持一致：取相对路径的目录段，无路径为 UNLOCATED，根级文件为 "."。 */
    static String moduleKeyOf(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return "UNLOCATED";
        return relativePath.contains("/") ? relativePath.substring(0, relativePath.lastIndexOf('/')) : ".";
    }

    private static String normalizedLevel(ReviewItemSnapshot item) {
        return Objects.toString(item.originalSnapshot().get("riskLevel"), "UNKNOWN").toUpperCase(Locale.ROOT);
    }
    public String overallRisk(ReportReviewManifest manifest) {
        var levels=manifest.items().stream().filter(i->i.origin().equals("SCANNER") && !unverified(i) && !disposed(i))
                .map(i->Objects.toString(i.originalSnapshot().get("riskLevel"),"UNKNOWN").toUpperCase(Locale.ROOT)).toList();
        return List.of("CRITICAL","HIGH","MEDIUM","LOW","INFO","COMMENT").stream().filter(levels::contains).findFirst().orElse("UNKNOWN");
    }
    public Map<String,Object> document(UUID report,ReportReviewManifest manifest,ReportAiReviewRuntime.Reviewed reviewed) {
        Map<String,Object> result=new LinkedHashMap<>(); result.put("schemaVersion","report-review.v1"); result.put("reportId",report);
        result.put("projectId",manifest.projectId()); result.put("sourceRevisionId",manifest.sourceRevisionId());
        result.put("contextHash",manifest.contextHash()); result.put("mode",manifest.mode()); result.put("summary",summary(manifest));
        result.put("sources",manifest.selection().sources().stream().map(source->{
            Map<String,Object> value=new LinkedHashMap<>(); value.put("type",source.type()); value.put("runId",source.runId());
            value.put("available",source.available()); value.put("status",source.status()); value.put("resultVersion",source.resultVersion());
            value.put("reasonCode",source.reasonCode()); value.put("rawFindingCount",source.findings().size());
            value.put("candidateCount",source.candidates().size());
            if("MEMORY_ORDER_FINDING".equals(source.type()))
                value.put("dischargedCount",source.execution().getOrDefault("dischargedCount",0));
            return value;
        }).toList());
        // 固定扫描事实与 AI 结论；人工工作台通过自己的版本和确认制品呈现。
        result.put("findings",manifest.items().stream().filter(item->item.origin().equals("SCANNER")).map(item->{
            Map<String,Object> value=new LinkedHashMap<>(); value.put("id",item.id()); value.put("sourceType",item.sourceType());
            value.put("sourceRunId",item.sourceRunId()); value.put("sourceRefId",item.sourceRefId()); value.put("original",item.originalSnapshot());
            value.put("title",item.title()); value.put("titleEn",item.titleEn()); value.put("fileKey",item.fileKey()); value.put("path",item.relativePath());
            value.put("startLine",item.startLine()); value.put("endLine",item.endLine()); return value;
        }).toList());
        result.put("aiSummary",reviewed.summary()); result.put("aiReviews",reviewed.results());
        // 迁移结果段（loop change）：第五来源的运行摘要（无迁移时 unavailable，段仍存在供前端稳定消费）
        manifest.selection().sources().stream().filter(src->"AI_MIGRATION_RESULT".equals(src.type())).findFirst()
                .ifPresent(src->{
                    Map<String,Object> migration=new LinkedHashMap<>();
                    migration.put("available",src.available());
                    migration.put("runId",src.runId());
                    migration.put("status",src.status());
                    migration.put("itemCount",src.execution()==null?0:src.execution().getOrDefault("itemCount",0));
                    migration.put("doneCount",src.execution()==null?0:src.execution().getOrDefault("doneCount",0));
                    migration.put("pendingCount",src.execution()==null?0:src.execution().getOrDefault("pendingCount",0));
                    result.put("migrationResult",migration);
                });
        return result;
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
    /** 条目已由迁移处置（MIGRATED / MIGRATION_NO_WORK，已决事项 1）——退出风险计数。 */
    private boolean disposed(ReviewItemSnapshot item) {
        Object state = item.originalSnapshot() == null ? null : item.originalSnapshot().get("migrationState");
        return "MIGRATED".equals(state) || "MIGRATION_NO_WORK".equals(state);
    }

    private boolean hasMigrationState(ReviewItemSnapshot item) {
        return item.originalSnapshot() != null && item.originalSnapshot().get("migrationState") != null;
    }

    private boolean unverified(ReviewItemSnapshot item) {
        return List.of("SOURCE_CANDIDATE","INCONCLUSIVE").contains(Objects.toString(item.originalSnapshot().get("findingKind"),""));
    }
    private String escape(String value) { return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;"); }
}
