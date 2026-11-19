package cn.rvport.backend.source.runtime;

import cn.rvport.backend.archive.ArchiveCandidateRegistrar;
import cn.rvport.backend.reportcomparison.ReportComparisonRepository;
import cn.rvport.backend.reportcomparison.runtime.SnapshotComparison;
import cn.rvport.backend.report.review.CurrentFixListResolver;
import cn.rvport.backend.source.RecheckTaskInput;
import cn.rvport.backend.source.SourceCompareRepository;
import cn.rvport.backend.task.TaskExecutionException;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.worker.TaskExecutionContext;
import cn.rvport.backend.worker.TaskAttemptLostException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class RecheckDiffRuntimeService {

    private static final String ERROR_CODE = "RECHECK_DIFF_FAILED";

    private final ReportComparisonRepository snapshots;
    private final CurrentFixListResolver fixLists;
    private final SourceCompareRepository sourceCompareRepository;
    private final ArchiveCandidateRegistrar archiveCandidateRegistrar;

    public RecheckDiffRuntimeService(
            ReportComparisonRepository snapshots,
            CurrentFixListResolver fixLists,
            SourceCompareRepository sourceCompareRepository,
            ArchiveCandidateRegistrar archiveCandidateRegistrar
    ) {
        this.snapshots = snapshots;
        this.fixLists = fixLists;
        this.sourceCompareRepository = sourceCompareRepository;
        this.archiveCandidateRegistrar = archiveCandidateRegistrar;
    }

    public void run(RecheckTaskInput input, TaskExecutionContext context) {
        try {
            context.publish(() -> {
                sourceCompareRepository.updateRecheckStatus(input.recheckRunId(), TaskStatus.RUNNING, Map.of("phase", "matching"));
                return null;
            });
            context.progress(10, "正在读取已确认的清单快照");
            var current = fixLists.current(input.projectId());
            if (!current.available() || !current.confirmationId().equals(input.targetConfirmationId()))
                throw confirmationRequired();
            UUID baseReport = sourceCompareRepository.confirmedReport(input.projectId(), input.baseSourceRevisionId(),
                    input.baseConfirmationId()).orElseThrow(this::confirmationRequired);
            UUID targetReport = sourceCompareRepository.confirmedReport(input.projectId(), input.targetSourceRevisionId(),
                    input.targetConfirmationId()).orElseThrow(this::confirmationRequired);
            var base = snapshots.confirmationInputs(baseReport, input.baseConfirmationId());
            var target = snapshots.confirmationInputs(targetReport, input.targetConfirmationId());
            context.progress(35, "正在匹配确认清单差异");
            var diffItems = new SnapshotComparison().compare(base, target, true, "NOT_OBSERVED").stream()
                    .map(this::diffItem).toList();
            Map<String, Object> summary = summary(fixCount(base), fixCount(target), diffItems);
            summary.put("baseConfirmationId", input.baseConfirmationId());
            summary.put("targetConfirmationId", input.targetConfirmationId());
            context.progress(80, "正在写入复检差异结果");
            context.publish(() -> {
            sourceCompareRepository.replaceDiffItems(input.projectId(), input.recheckRunId(), diffItems);
            sourceCompareRepository.updateRecheckStatus(input.recheckRunId(), TaskStatus.SUCCEEDED, summary);
            sourceCompareRepository.findRecheckRun(input.recheckRunId())
                    .ifPresent(run -> archiveCandidateRegistrar.registerRecheck(run, summary));
            return null;
            });
            context.progress(100, "复检差异生成完成");
        } catch (TaskAttemptLostException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            context.publish(() -> {
            sourceCompareRepository.updateRecheckStatus(input.recheckRunId(), TaskStatus.FAILED, Map.of(
                    "error", ERROR_CODE,
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

    private TaskExecutionException confirmationRequired() {
        return new TaskExecutionException("FIX_LIST_CONFIRMATION_REQUIRED", "所选源码版本缺少有效确认清单", null);
    }

    private SourceCompareRepository.DiffItemCreateCommand diffItem(ReportComparisonRepository.DiffItemCreateCommand item) {
        return new SourceCompareRepository.DiffItemCreateCommand(item.sourceType(), item.oldSourceId(), item.newSourceId(),
                item.diffStatus(), location(item.oldSnapshot()), location(item.newSnapshot()), item.severity(),
                item.title(), item.locationLabel(), item.recommendation(), item.confirmationStatus(), item.fingerprint(),
                item.matchConfidence(), item.matchReason(), item.oldSnapshot(), item.newSnapshot());
    }

    private Map<String,Object> location(Map<String,Object> snapshot) {
        if (snapshot == null) return null;
        Map<String,Object> location = new LinkedHashMap<>();
        location.put("identityPath", snapshot.get("identityPath"));
        location.put("startLine", snapshot.get("start_line"));
        location.put("endLine", snapshot.get("end_line"));
        return location;
    }

    private long fixCount(List<cn.rvport.backend.report.ReportInputView> inputs) {
        return inputs.stream().filter(input -> "TO_FIX".equals(input.inputSnapshot().get("confirmationStatus"))).count();
    }

    private Map<String, Object> summary(long baseCount, long targetCount, List<SourceCompareRepository.DiffItemCreateCommand> items) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("baseConfirmedCount", baseCount);
        summary.put("targetConfirmedCount", targetCount);
        summary.put("total", items.size());
        summary.put("byStatus", grouped(items, SourceCompareRepository.DiffItemCreateCommand::diffStatus));
        summary.put("bySourceType", grouped(items, SourceCompareRepository.DiffItemCreateCommand::sourceType));
        summary.put("bySeverity", grouped(items, SourceCompareRepository.DiffItemCreateCommand::severity));
        summary.put("byMatchConfidence", grouped(items, item -> item.matchConfidence() == null ? "UNMATCHED" : item.matchConfidence()));
        return summary;
    }

    private Map<String, Long> grouped(List<SourceCompareRepository.DiffItemCreateCommand> items, Function<SourceCompareRepository.DiffItemCreateCommand, String> classifier) {
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
        return ex.getMessage() == null || ex.getMessage().isBlank() ? ex.getClass().getSimpleName() : ex.getMessage();
    }

}
