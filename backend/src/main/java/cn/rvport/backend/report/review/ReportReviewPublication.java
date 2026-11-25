package cn.rvport.backend.report.review;

import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.report.*;
import java.util.*;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportReviewPublication {
    private final ReviewWorkspaceRepository reviews;
    private final ReportRepository reports;
    private final ReportOrganizationRepository files;
    private final DSLContext dsl;
    private final ReportInputSelectionPolicy policy;
    public ReportReviewPublication(ReviewWorkspaceRepository reviews,ReportRepository reports,ReportOrganizationRepository files,DSLContext dsl,ReportInputSelectionPolicy policy) {
        this.reviews=reviews; this.reports=reports; this.files=files; this.dsl=dsl; this.policy=policy;
    }
    @Transactional
    public ReportView publish(UUID report,ReportReviewManifest manifest,ReportAiReviewRuntime.Reviewed reviewed,
            Map<String,Object> summary,String risk,UUID attempt) {
        reviews.lockProject(manifest.projectId());
        if(!reviews.latestGeneration(manifest.projectId()).filter(report::equals).isPresent()) throw conflict("REPORT_GENERATION_SUPERSEDED");
        if(manifest.baseReportId()!=null) {
            var base=reviews.draft(manifest.baseReportId()).orElseThrow(()->conflict("REPORT_REVIEW_BASE_CHANGED"));
            if(base.reviewRevision()!=manifest.baseReviewRevision()) throw conflict("REPORT_REVIEW_BASE_CHANGED");
        }
        boolean current=manifest.contextHash().equals(policy.current(manifest.projectId()).contextHash());
        if(current) reviews.supersede(manifest.projectId());
        reviews.initializeDraft(report,current,manifest.items(),reviewed.results());
        for(var file:manifest.source().files()) files.upsertFileContext(new ReportFileContextView(UUID.randomUUID(),report,manifest.sourceRevisionId(),
                file.fileKey(),file.path(),file.contentHash(),file.totalLines(),file.artifactId(),file.contextAvailable(),file.reasonCode(),null));
        for(var item:manifest.items()) {
            if(!item.origin().equals("SCANNER")) continue;
            Map<String,Object> snapshot=new LinkedHashMap<>(item.originalSnapshot());
            snapshot.put("id",item.id()); snapshot.put("issueTitle",item.title()); snapshot.put("sourceType",item.sourceType());
            snapshot.put("sourceRefId",item.sourceRefId()); snapshot.put("sourceDetail",item.originalSnapshot());
            snapshot.put("stableFingerprint",item.identityHash()); snapshot.put("semanticDigest",item.evidenceHash());
            snapshot.put("identityPath",item.relativePath()); snapshot.put("detectedLevel",item.originalSnapshot().get("riskLevel"));
            String module=item.relativePath()==null?"UNLOCATED":item.relativePath().contains("/")
                    ?item.relativePath().substring(0,item.relativePath().lastIndexOf('/')):".";
            dsl.execute("""
                    insert into report_inputs(report_id,input_type,input_id,input_snapshot,source_type,risk_level,module_key,module_name,
                      module_basis,module_confidence,related_module_keys,file_key,relative_path,finding_type,line_start,line_end,column_start,
                      column_end,symbol,source_content_hash,location_available)
                    values(?,'SCAN_FINDING',?,?::jsonb,?,?,?,?,'SOURCE_PATH','EXACT',?::jsonb,?,?,?,?,?,?,?,?,?,?)
                    """,report,item.id(),reviews.json(snapshot),item.sourceType(),item.originalSnapshot().get("riskLevel"),module,module,
                    "{\"items\":[]}",item.fileKey(),item.relativePath(),Objects.toString(item.originalSnapshot().get("category"),"UNCLASSIFIED"),
                    item.startLine(),item.endLine(),item.startColumn(),item.endColumn(),item.originalSnapshot().get("symbol"),item.sourceContentHash(),
                    item.fileKey()!=null && item.startLine()!=null);
        }
        for(var result:reviewed.results()) dsl.execute("insert into report_ai_review_results(report_id,attempt_id,input_id,verdict,result) values(?,?,?,?,?::jsonb)",
                report,attempt,result.inputId(),result.verdict().name(),reviews.json(result));
        reviews.finish(report,current?"CURRENT":"HISTORICAL_CONTEXT_CHANGED",reviewed.summary());
        var saved=new LinkedHashMap<>(summary); saved.put("aiSummary",reviewed.summary()); saved.put("organizationAvailable",true);
        if(manifest.sourceMetadata()!=null) saved.put("sourceMetadata",manifest.sourceMetadata());
        saved.put("schemaVersion","report-review.v2"); saved.put("publishState",current?"CURRENT":"HISTORICAL_CONTEXT_CHANGED");
        return reports.markSucceeded(report,saved,risk);
    }
    private static ApiException conflict(String code) { return new ApiException(HttpStatus.CONFLICT,code,"生成期间复核基线已变化，请重新生成"); }
}
