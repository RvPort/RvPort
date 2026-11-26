package cn.rvport.backend.report.review;

import cn.rvport.backend.archive.ArchiveCandidateRegistrar;
import cn.rvport.backend.artifact.*;
import cn.rvport.backend.report.*;
import cn.rvport.backend.task.TaskLogType;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.worker.TaskAttemptLostException;
import cn.rvport.backend.worker.TaskCancelledException;
import cn.rvport.backend.worker.TaskExecutionContext;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ReportReviewRuntimeService {
    private final ReportRepository reports;
    private final ReviewWorkspaceRepository reviews;
    private final ReportAiReviewRuntime ai;
    private final ReportReviewRenderer renderer;
    private final StoredArtifactService artifacts;
    private final ReportReviewPublication publication;
    private final ArchiveCandidateRegistrar archives;
    public ReportReviewRuntimeService(ReportRepository reports,ReviewWorkspaceRepository reviews,
            ReportAiReviewRuntime ai,ReportReviewRenderer renderer,StoredArtifactService artifacts,ReportReviewPublication publication,
            ArchiveCandidateRegistrar archives) {
        this.reports=reports; this.reviews=reviews; this.ai=ai; this.renderer=renderer; this.artifacts=artifacts;
        this.publication=publication; this.archives=archives;
    }
    public void generate(ReportGenerateInput input,TaskExecutionContext context) {
        if(input==null || input.reportId()==null || !Objects.equals(input.projectId(),context.task().projectId()))
            throw new IllegalArgumentException("REPORT_TASK_INPUT_INVALID");
        var report=reports.findById(input.reportId()).orElseThrow();
        var manifest=reviews.manifest(report.id());
        if(!report.taskId().equals(context.task().id()) || !report.projectId().equals(input.projectId())
                || !report.sourceRevisionId().equals(input.sourceRevisionId()) || !report.generatedBy().equals(context.task().createdBy())
                || !manifest.projectId().equals(report.projectId()) || !manifest.sourceRevisionId().equals(report.sourceRevisionId()))
            throw new IllegalArgumentException("REPORT_TASK_IDENTITY_MISMATCH");
        if(!report.generatedBy().equals(input.configurationUserId())
                || !reviews.matchesExecution(report.id(),input.configurationUserId(),input.modelExecution()))
            throw cn.rvport.backend.modelprofile.ModelExecutionSelection.missing();
        if(report.status()==TaskStatus.SUCCEEDED && reviews.draft(report.id()).isPresent()) return;
        UUID attempt=UUID.randomUUID();
        context.publish(()->reports.updateStatus(report.id(),TaskStatus.RUNNING,Map.of("phase","REVIEW")));
        boolean completed=false;
        try {
            context.progress(10,"读取固定分析输入 / Reading fixed analysis inputs");
            var reviewed=ai.run(manifest,input.modelExecution(),context);
            context.progress(65,"生成固定报告制品 / Rendering immutable report artifacts");
            var summary=renderer.summary(manifest);
            var document=renderer.document(report.id(),manifest,reviewed);
            var json=store(report,ReportDownloadFormat.JSON,renderer.json(document),summary,context);
            var html=store(report,ReportDownloadFormat.HTML,renderer.html(document).getBytes(StandardCharsets.UTF_8),summary,context);
            context.throwIfCancellationRequested();
            var published=context.publish(()->publication.publish(report.id(),manifest,reviewed,summary,renderer.overallRisk(manifest),attempt));
            completed=true;
            try { context.publish(()->{ archives.registerMigrationReport(published,summary,List.of(json,html)); return null; }); }
            catch(TaskAttemptLostException ex) { throw ex; }
            catch(RuntimeException ex) { context.log(TaskLogType.STDERR,"报告已生成，归档候选登记失败 / Report generated; archive registration failed"); }
            context.progress(95,"结果报告生成完成 / Result report generated");
        } catch(TaskAttemptLostException ex) { throw ex; }
        catch(Exception ex) {
            if(completed) return;
            if(ex instanceof InterruptedException) Thread.currentThread().interrupt();
            String code=ex instanceof cn.rvport.backend.common.error.ApiException api?api.code():safeCode(ex.getMessage());
            TaskStatus status=ex instanceof TaskCancelledException?TaskStatus.CANCELLED:TaskStatus.FAILED;
            context.publish(()->{
                reports.updateStatus(report.id(),status,Map.of("errorCode",code,"attemptId",attempt.toString()));
                reviews.finish(report.id(),"NOT_PUBLISHED",Map.of("execution","FAILED","errorCode",code)); return null;
            });
            if(ex instanceof TaskCancelledException cancelled) throw cancelled;
            if("REPORT_AI_SANDBOX_UNAVAILABLE".equals(code))
                throw new cn.rvport.backend.task.TaskExecutionException(code,
                        "报告 AI 执行沙箱不可用 / The report AI execution sandbox is unavailable",ex);
            throw new IllegalStateException(code,ex);
        }
    }
    private StoredArtifactView store(ReportView report,ReportDownloadFormat format,byte[] bytes,Map<String,Object> summary,TaskExecutionContext context) {
        return artifacts.store(new StoredArtifactCreateCommand(report.projectId(),report.taskId(),StoredArtifactOwnerType.MIGRATION_REPORT,
                report.id(),format.artifactType(),format.contentType(),bytes,summary,format.extension()),context);
    }
    private String safeCode(String value) {
        return value!=null && value.matches("(?:REPORT|SOURCE)_[A-Z_]+")?value:"REPORT_GENERATION_FAILED";
    }
}
