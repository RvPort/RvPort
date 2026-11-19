package cn.rvport.backend.reportcomparison;

import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.common.web.PageResponse;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.report.ReportRepository;
import cn.rvport.backend.report.ReportView;
import cn.rvport.backend.task.RvPortTaskTypes;
import cn.rvport.backend.task.TaskCreateCommand;
import cn.rvport.backend.task.TaskRepository;
import cn.rvport.backend.task.TaskResponse;
import cn.rvport.backend.task.TaskService;
import cn.rvport.backend.task.TaskStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportComparisonService {

    private final ReportRepository reportRepository;
    private final ReportComparisonRepository comparisonRepository;
    private final ProjectAccessService projectAccessService;
    private final TaskService taskService;
    private final TaskRepository taskRepository;

    public ReportComparisonService(
            ReportRepository reportRepository,
            ReportComparisonRepository comparisonRepository,
            ProjectAccessService projectAccessService,
            TaskService taskService,
            TaskRepository taskRepository
    ) {
        this.reportRepository = reportRepository;
        this.comparisonRepository = comparisonRepository;
        this.projectAccessService = projectAccessService;
        this.taskService = taskService;
        this.taskRepository = taskRepository;
    }

    @Transactional
    public TaskResponse create(
            UUID targetReportId,
            ReportComparisonCreateRequest request,
            UUID actorUserId,
            boolean superAdmin,
            HttpServletRequest httpRequest
    ) {
        ReportView target = requireReport(targetReportId, actorUserId, superAdmin);
        if (target.status() != TaskStatus.SUCCEEDED) {
            throw badRequest("REPORT_COMPARISON_TARGET_NOT_READY", "目标报告未生成完成");
        }
        ReportView base = resolveBaseReport(target, request == null ? null : request.baseReportId(), actorUserId, superAdmin);
        validateComparable(base, target);
        var basis=request==null || request.basis()==null?ReportComparisonBasis.REPORT_FACTS:request.basis();
        UUID left=request==null?null:request.baseConfirmationId(),right=request==null?null:request.targetConfirmationId();
        boolean confirmed=basis==ReportComparisonBasis.CONFIRMED_FIX_LIST;
        if(confirmed && (left==null || right==null) || !confirmed && (left!=null || right!=null))
            throw badRequest("REPORT_COMPARISON_BASIS_INVALID","请指定与对比依据一致的确认版本");
        if(confirmed && (!comparisonRepository.hasConfirmation(base.id(),left) || !comparisonRepository.hasConfirmation(target.id(),right)))
            throw notFound("REPORT_CONFIRMATION_NOT_FOUND","报告确认版本不存在");
        comparisonRepository.lockComparison(target.projectId(),base.id(),target.id(),basis,left,right);
        var reusable=(confirmed?comparisonRepository.findReusable(target.projectId(),base.id(),target.id(),basis,left,right)
                :comparisonRepository.findReusable(target.projectId(),base.id(),target.id())).orElse(null);
        if (reusable != null) {
            return taskRepository.findById(reusable.taskId())
                    .map(TaskResponse::from)
                    .orElseThrow(() -> notFound("TASK_NOT_FOUND", "任务不存在"));
        }
        UUID comparisonRunId = UUID.randomUUID();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("basis",basis.name()); if(confirmed) { input.put("baseConfirmationId",left); input.put("targetConfirmationId",right); }
        input.put("projectId", target.projectId());
        input.put("comparisonRunId", comparisonRunId);
        input.put("baseReportId", base.id());
        input.put("targetReportId", target.id());
        TaskResponse task = taskService.create(new TaskCreateCommand(
                target.projectId(),
                RvPortTaskTypes.REPORT_COMPARE,
                input,
                actorUserId,
                null,
                "报告对比任务已创建"
        ));
        if(confirmed) comparisonRepository.createRun(comparisonRunId,target.projectId(),task.id(),base.id(),target.id(),TaskStatus.QUEUED,basis,left,right);
        else comparisonRepository.createRun(comparisonRunId, target.projectId(), task.id(), base.id(), target.id(), TaskStatus.QUEUED);
        return task;
    }

    @Transactional(readOnly = true)
    public ReportComparisonResponse get(UUID comparisonRunId, UUID actorUserId, boolean superAdmin) {
        ReportComparisonRunView run = requireRun(comparisonRunId, actorUserId, superAdmin);
        return response(run);
    }

    @Transactional(readOnly = true)
    public PageResponse<ReportComparisonDiffItemResponse> diffItems(
            UUID comparisonRunId,
            ReportComparisonDiffItemListRequest request,
            UUID actorUserId,
            boolean superAdmin
    ) {
        ReportComparisonRunView run = requireRun(comparisonRunId, actorUserId, superAdmin);
        int page = request.normalizedPage();
        int pageSize = request.normalizedPageSize();
        try {
            long total = comparisonRepository.countDiffItems(run.projectId(), run.id(), request);
            return PageResponse.of(
                    comparisonRepository.listDiffItems(run.projectId(), run.id(), request)
                            .stream()
                            .map(ReportComparisonDiffItemResponse::from)
                            .toList(),
                    page,
                    pageSize,
                    total
            );
        } catch (IllegalArgumentException ex) {
            throw badRequest("REPORT_COMPARISON_DIFF_QUERY_INVALID", "报告对比差异查询参数不正确");
        }
    }

    @Transactional(readOnly = true)
    public ReportComparisonDiffItemDetailResponse diffItem(UUID comparisonRunId, UUID diffItemId, UUID actorUserId, boolean superAdmin) {
        ReportComparisonRunView run = requireRun(comparisonRunId, actorUserId, superAdmin);
        return comparisonRepository.findDiffItem(run.projectId(), run.id(), diffItemId)
                .map(ReportComparisonDiffItemDetailResponse::from)
                .orElseThrow(() -> notFound("REPORT_COMPARISON_DIFF_ITEM_NOT_FOUND", "报告对比差异项不存在"));
    }

    private ReportComparisonResponse response(ReportComparisonRunView run) {
        ReportView base = reportRepository.findById(run.baseReportId()).orElse(null);
        ReportView target = reportRepository.findById(run.targetReportId()).orElse(null);
        ReportComparisonDiffSummaryResponse summary = run.status() == TaskStatus.SUCCEEDED
                ? comparisonRepository.diffSummary(run.projectId(), run.id())
                : ReportComparisonDiffSummaryResponse.empty();
        return ReportComparisonResponse.from(run, base, target, summary);
    }

    private ReportView resolveBaseReport(UUID projectId, UUID baseReportId, UUID actorUserId, boolean superAdmin) {
        ReportView base = requireReport(baseReportId, actorUserId, superAdmin);
        if (!Objects.equals(base.projectId(), projectId)) {
            throw notFound("REPORT_NOT_FOUND", "报告不存在");
        }
        return base;
    }

    private ReportView resolveBaseReport(ReportView target, UUID baseReportId, UUID actorUserId, boolean superAdmin) {
        if (baseReportId != null) {
            return resolveBaseReport(target.projectId(), baseReportId, actorUserId, superAdmin);
        }
        return comparisonRepository.latestComparableBase(target)
                .orElseThrow(() -> badRequest("REPORT_COMPARISON_BASE_NOT_FOUND", "没有可比较的上一份报告"));
    }

    private void validateComparable(ReportView base, ReportView target) {
        if (Objects.equals(base.id(), target.id())) {
            throw badRequest("REPORT_COMPARISON_SELF", "base 和 target 报告不能相同");
        }
        if (!Objects.equals(base.projectId(), target.projectId())) {
            throw notFound("REPORT_NOT_FOUND", "报告不存在");
        }
        if (base.reportType() != target.reportType()) {
            throw badRequest("REPORT_COMPARISON_TYPE_MISMATCH", "只能对比同类型报告");
        }
        if (base.status() != TaskStatus.SUCCEEDED) {
            throw badRequest("REPORT_COMPARISON_BASE_NOT_READY", "base 报告未生成完成");
        }
    }

    private ReportView requireReport(UUID reportId, UUID actorUserId, boolean superAdmin) {
        ReportView report = reportRepository.findById(reportId)
                .orElseThrow(() -> notFound("REPORT_NOT_FOUND", "报告不存在"));
        projectAccessService.requireProjectAccess(report.projectId(), actorUserId, superAdmin);
        return report;
    }

    private ReportComparisonRunView requireRun(UUID comparisonRunId, UUID actorUserId, boolean superAdmin) {
        ReportComparisonRunView run = comparisonRepository.findRun(comparisonRunId)
                .orElseThrow(() -> notFound("REPORT_COMPARISON_NOT_FOUND", "报告对比记录不存在"));
        projectAccessService.requireProjectAccess(run.projectId(), actorUserId, superAdmin);
        return run;
    }

    private ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    private ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
