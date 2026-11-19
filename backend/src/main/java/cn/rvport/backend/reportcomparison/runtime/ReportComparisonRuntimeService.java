package cn.rvport.backend.reportcomparison.runtime;

import cn.rvport.backend.archive.ArchiveCandidateRegistrar;
import cn.rvport.backend.report.ReportRepository;
import cn.rvport.backend.report.ReportView;
import cn.rvport.backend.reportcomparison.ReportComparisonRepository;
import cn.rvport.backend.reportcomparison.ReportComparisonTaskInput;
import cn.rvport.backend.task.TaskErrorCode;
import cn.rvport.backend.task.TaskExecutionException;
import cn.rvport.backend.worker.TaskExecutionContext;
import cn.rvport.backend.worker.TaskAttemptLostException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class ReportComparisonRuntimeService {

    private static final String ERROR_CODE = "REPORT_COMPARISON_FAILED";

    private final ReportRepository reportRepository;
    private final ReportComparisonRepository comparisonRepository;
    private final ArchiveCandidateRegistrar archiveCandidateRegistrar;

    public ReportComparisonRuntimeService(
            ReportRepository reportRepository,
            ReportComparisonRepository comparisonRepository,
            ArchiveCandidateRegistrar archiveCandidateRegistrar
    ) {
        this.reportRepository = reportRepository;
        this.comparisonRepository = comparisonRepository;
        this.archiveCandidateRegistrar = archiveCandidateRegistrar;
    }

    public void run(ReportComparisonTaskInput input, TaskExecutionContext context) {
        validate(input);
        context.publish(() -> { comparisonRepository.markRunning(input.comparisonRunId()); return null; });
        try {
            context.progress(10, "正在读取报告输入快照");
            ReportView baseReport = requireReport(input.baseReportId());
            ReportView targetReport = requireReport(input.targetReportId());
            if (!Objects.equals(baseReport.projectId(), input.projectId())
                    || !Objects.equals(targetReport.projectId(), input.projectId())) {
                throw new TaskExecutionException(TaskErrorCode.PROJECT_MISMATCH, "报告不属于当前项目", null);
            }

            boolean confirmed=input.basis()==cn.rvport.backend.reportcomparison.ReportComparisonBasis.CONFIRMED_FIX_LIST;
            if(confirmed && (!comparisonRepository.hasConfirmation(baseReport.id(),input.baseConfirmationId())
                    || !comparisonRepository.hasConfirmation(targetReport.id(),input.targetConfirmationId())))
                throw new TaskExecutionException(TaskErrorCode.INPUT_INVALID,"报告确认版本不匹配",null);
            var base = confirmed ? comparisonRepository.confirmationInputs(baseReport.id(), input.baseConfirmationId())
                    : comparisonRepository.listAllInputs(baseReport.id());
            var target = confirmed ? comparisonRepository.confirmationInputs(targetReport.id(), input.targetConfirmationId())
                    : comparisonRepository.listAllInputs(targetReport.id());

            context.progress(35, "正在匹配报告差异");
            var diffItems = new SnapshotComparison().compare(base, target, confirmed, confirmed ? "NO_LONGER_SELECTED" : "NOT_OBSERVED");
            Map<String, Object> summary = summary(baseReport, targetReport, base.size(), target.size(), diffItems);
            summary.put("basis",input.basis().name()); if(confirmed) {
                summary.put("baseConfirmationId",input.baseConfirmationId()); summary.put("targetConfirmationId",input.targetConfirmationId());
            }
            context.progress(80, "正在写入报告对比结果");
            context.publish(() -> {
            comparisonRepository.replaceDiffItems(input.projectId(), input.comparisonRunId(), diffItems);
            comparisonRepository.markSucceeded(input.comparisonRunId(), summary);
            comparisonRepository.findRun(input.comparisonRunId())
                    .ifPresent(run -> archiveCandidateRegistrar.registerReportComparison(run, targetReport.sourceRevisionId(), summary));
            return null;
            });
            context.progress(100, "报告对比完成");
        } catch (TaskAttemptLostException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            context.publish(() -> {
            comparisonRepository.markFailed(input.comparisonRunId(), errorCode(ex), safeMessage(ex), Map.of(
                    "error", errorCode(ex),
                    "message", safeMessage(ex)
            ));
            return null;
            });
            if (ex instanceof TaskExecutionException taskExecutionException) {
                throw taskExecutionException;
            }
            throw new TaskExecutionException(ERROR_CODE, safeMessage(ex), ex);
        }
    }

    private void validate(ReportComparisonTaskInput input) {
        if (input == null
                || input.projectId() == null
                || input.comparisonRunId() == null
                || input.baseReportId() == null
                || input.targetReportId() == null) {
            throw new TaskExecutionException(TaskErrorCode.INPUT_INVALID, "报告对比任务输入非法", null);
        }
    }

    private ReportView requireReport(UUID reportId) {
        return reportRepository.findById(reportId)
                .orElseThrow(() -> new TaskExecutionException(TaskErrorCode.RESOURCE_NOT_FOUND, "报告不存在", null));
    }

    private Map<String, Object> summary(
            ReportView baseReport,
            ReportView targetReport,
            int baseCount,
            int targetCount,
            List<ReportComparisonRepository.DiffItemCreateCommand> items
    ) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("baseReportId", baseReport.id());
        summary.put("targetReportId", targetReport.id());
        summary.put("baseSourceRevisionId", baseReport.sourceRevisionId());
        summary.put("targetSourceRevisionId", targetReport.sourceRevisionId());
        summary.put("baseInputCount", baseCount);
        summary.put("targetInputCount", targetCount);
        summary.put("total", items.size());
        summary.put("byStatus", grouped(items, ReportComparisonRepository.DiffItemCreateCommand::diffStatus));
        summary.put("bySourceType", grouped(items, ReportComparisonRepository.DiffItemCreateCommand::sourceType));
        summary.put("bySeverity", grouped(items, ReportComparisonRepository.DiffItemCreateCommand::severity));
        summary.put("byMatchConfidence", grouped(items, item -> item.matchConfidence() == null ? "UNMATCHED" : item.matchConfidence()));
        return summary;
    }

    private Map<String, Long> grouped(List<ReportComparisonRepository.DiffItemCreateCommand> items, Function<ReportComparisonRepository.DiffItemCreateCommand, String> classifier) {
        Map<String, Long> grouped = new LinkedHashMap<>();
        items.stream()
                .collect(Collectors.groupingBy(item -> firstNonBlank(classifier.apply(item), "UNKNOWN"), LinkedHashMap::new, Collectors.counting()))
                .forEach(grouped::put);
        return grouped;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String safeMessage(Throwable ex) {
        if (ex instanceof TaskExecutionException taskExecutionException) {
            return taskExecutionException.safeMessage();
        }
        return ex.getMessage() == null || ex.getMessage().isBlank() ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private String errorCode(Throwable ex) {
        if (ex instanceof TaskExecutionException taskExecutionException) {
            return taskExecutionException.errorCode();
        }
        return ERROR_CODE;
    }

}
