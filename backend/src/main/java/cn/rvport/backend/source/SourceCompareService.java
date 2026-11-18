package cn.rvport.backend.source;

import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.common.web.PageResponse;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.report.review.CurrentFixListResolver;
import cn.rvport.backend.report.review.ReviewWorkspaceRepository;
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
public class SourceCompareService {

    private static final int DEFAULT_DIFF_PAGE = 1;
    private static final int DEFAULT_DIFF_PAGE_SIZE = 20;

    private final SourceRepository sourceRepository;
    private final SourceCompareRepository sourceCompareRepository;
    private final ProjectAccessService projectAccessService;
    private final TaskService taskService;
    private final TaskRepository taskRepository;
    private final CurrentFixListResolver fixLists;
    private final ReviewWorkspaceRepository reviews;

    public SourceCompareService(
            SourceRepository sourceRepository,
            SourceCompareRepository sourceCompareRepository,
            ProjectAccessService projectAccessService,
            TaskService taskService,
            TaskRepository taskRepository, CurrentFixListResolver fixLists, ReviewWorkspaceRepository reviews
    ) {
        this.sourceRepository = sourceRepository;
        this.sourceCompareRepository = sourceCompareRepository;
        this.projectAccessService = projectAccessService;
        this.taskService = taskService;
        this.taskRepository = taskRepository;
        this.fixLists = fixLists;
        this.reviews = reviews;
    }

    @Transactional
    public TaskResponse create(UUID projectId, RecheckCreateRequest request, UUID actorUserId, boolean superAdmin, HttpServletRequest httpRequest) {
        projectAccessService.requireProjectAccess(projectId, actorUserId, superAdmin);
        SourceRevisionView base = sourceRepository.findById(request.baseSourceRevisionId())
                .orElseThrow(() -> notFound("SOURCE_REVISION_NOT_FOUND", "源码版本不存在"));
        SourceRevisionView target = sourceRepository.findById(request.targetSourceRevisionId())
                .orElseThrow(() -> notFound("SOURCE_REVISION_NOT_FOUND", "源码版本不存在"));
        return createForRevisions(projectId, base, target, request.baseConfirmationId(), request.targetConfirmationId(), actorUserId);
    }

    @Transactional
    public TaskResponse createLatest(UUID projectId, UUID actorUserId, boolean superAdmin, HttpServletRequest httpRequest) {
        projectAccessService.requireProjectAccess(projectId, actorUserId, superAdmin);
        reviews.lockProject(projectId);
        SourceRevisionView target = sourceRepository.findActive(projectId)
                .orElseThrow(() -> badRequest("SOURCE_REVISION_ACTIVE_NOT_FOUND", "项目没有可用的 active source revision"));
        SourceRevisionView base = sourceRepository.findPrevious(projectId, target.id(), target.createdAt())
                .orElseThrow(() -> badRequest("SOURCE_REVISION_PREVIOUS_NOT_FOUND", "项目没有可比较的上一源码版本"));
        return createForRevisions(projectId, base, target, sourceCompareRepository.latestConfirmation(projectId, base.id())
                .orElseThrow(this::confirmationRequired), fixLists.current(projectId).confirmationId(), actorUserId);
    }

    @Transactional(readOnly = true)
    public LatestSourceCompareResponse latest(UUID projectId, UUID actorUserId, boolean superAdmin) {
        projectAccessService.requireProjectAccess(projectId, actorUserId, superAdmin);
        SourceRevisionView current = sourceRepository.findActive(projectId)
                .orElseThrow(() -> badRequest("SOURCE_REVISION_ACTIVE_NOT_FOUND", "项目没有可用的 active source revision"));
        SourceRevisionView previous = sourceRepository.findPrevious(projectId, current.id(), current.createdAt()).orElse(null);
        requireSameProject(projectId, current);
        if (previous == null) {
            return new LatestSourceCompareResponse(
                    LatestSourceCompareStatus.NOT_STARTED,
                    LatestSourceCompareEmptyReason.NO_PREVIOUS_REVISION,
                    SourceRevisionResponse.from(current),
                    null,
                    null,
                    DiffSummaryResponse.empty(),
                    null
            );
        }
        requireSameProject(projectId, previous);
        var targetList = fixLists.current(projectId);
        var pairRun = sourceCompareRepository.latestConfirmation(projectId, previous.id())
                .filter(ignored -> targetList.available())
                .flatMap(id -> sourceCompareRepository.latestRecheckRun(projectId, id, targetList.confirmationId()));
        return pairRun
                .map(recheckRun -> withRecheckRun(projectId, current, previous, recheckRun))
                .orElseGet(() -> new LatestSourceCompareResponse(
                        LatestSourceCompareStatus.NOT_STARTED,
                        null,
                        SourceRevisionResponse.from(current),
                        SourceRevisionResponse.from(previous),
                        null,
                        DiffSummaryResponse.empty(),
                        null
                ));
    }

    @Transactional(readOnly = true)
    public PageResponse<RecheckDiffItemResponse> diffItems(
            UUID recheckRunId,
            RecheckDiffItemListRequest request,
            UUID actorUserId,
            boolean superAdmin
    ) {
        RecheckRunView recheckRun = sourceCompareRepository.findRecheckRun(recheckRunId)
                .orElseThrow(() -> notFound("RECHECK_RUN_NOT_FOUND", "复检记录不存在"));
        projectAccessService.requireProjectAccess(recheckRun.projectId(), actorUserId, superAdmin);
        int page = request.normalizedPage();
        int pageSize = request.normalizedPageSize();
        try {
            long total = sourceCompareRepository.countDiffItems(recheckRun.projectId(), recheckRun.id(), request);
            return PageResponse.of(
                    sourceCompareRepository.listDiffItems(recheckRun.projectId(), recheckRun.id(), request)
                            .stream()
                            .map(RecheckDiffItemResponse::from)
                            .toList(),
                    page,
                    pageSize,
                    total
            );
        } catch (IllegalArgumentException ex) {
            throw badRequest("DIFF_ITEM_QUERY_INVALID", "差异项查询参数不正确");
        }
    }

    @Transactional(readOnly = true)
    public RecheckDiffItemDetailResponse diffItem(UUID recheckRunId, UUID diffItemId, UUID actorUserId, boolean superAdmin) {
        RecheckRunView recheckRun = sourceCompareRepository.findRecheckRun(recheckRunId)
                .orElseThrow(() -> notFound("RECHECK_RUN_NOT_FOUND", "复检记录不存在"));
        projectAccessService.requireProjectAccess(recheckRun.projectId(), actorUserId, superAdmin);
        return sourceCompareRepository.findDiffItem(recheckRun.projectId(), recheckRun.id(), diffItemId)
                .map(RecheckDiffItemDetailResponse::from)
                .orElseThrow(() -> notFound("DIFF_ITEM_NOT_FOUND", "差异项不存在"));
    }

    private TaskResponse createForRevisions(UUID projectId, SourceRevisionView base, SourceRevisionView target,
            UUID baseConfirmation, UUID targetConfirmation, UUID actorUserId) {
        requireSameProject(projectId, base);
        requireSameProject(projectId, target);
        if (Objects.equals(base.id(), target.id())) {
            throw badRequest("SOURCE_REVISION_COMPARE_SELF", "base 和 target 源码版本不能相同");
        }
        reviews.lockProject(projectId);
        var current = fixLists.current(projectId);
        if (!current.available() || !current.confirmationId().equals(targetConfirmation)) throw confirmationRequired();
        UUID baseReport = sourceCompareRepository.confirmedReport(projectId, base.id(), baseConfirmation)
                .orElseThrow(this::confirmationRequired);
        UUID targetReport = sourceCompareRepository.confirmedReport(projectId, target.id(), targetConfirmation)
                .orElseThrow(this::confirmationRequired);
        RecheckRunView reusable = sourceCompareRepository.findReusableRecheckRun(projectId, baseConfirmation, targetConfirmation).orElse(null);
        if (reusable != null) {
            return taskRepository.findById(reusable.taskId())
                    .map(TaskResponse::from)
                    .orElseThrow(() -> notFound("TASK_NOT_FOUND", "任务不存在"));
        }
        UUID recheckRunId = UUID.randomUUID();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("projectId", projectId);
        input.put("recheckRunId", recheckRunId);
        input.put("baseSourceRevisionId", base.id());
        input.put("targetSourceRevisionId", target.id());
        input.put("baseConfirmationId", baseConfirmation);
        input.put("targetConfirmationId", targetConfirmation);
        TaskResponse task = taskService.create(new TaskCreateCommand(
                projectId,
                RvPortTaskTypes.RECHECK_DIFF,
                input,
                actorUserId,
                null,
                "复检差异任务已创建"
        ));
        sourceCompareRepository.createRecheckRun(recheckRunId, projectId, task.id(), base.id(), target.id(),
                baseReport, targetReport, baseConfirmation, targetConfirmation, TaskStatus.QUEUED);
        return task;
    }

    private LatestSourceCompareResponse withRecheckRun(
            UUID projectId,
            SourceRevisionView current,
            SourceRevisionView previous,
            RecheckRunView recheckRun
    ) {
        if (!Objects.equals(recheckRun.projectId(), projectId)
                || !Objects.equals(recheckRun.baseSourceRevisionId(), previous.id())
                || !Objects.equals(recheckRun.targetSourceRevisionId(), current.id())) {
            throw notFound("RECHECK_RUN_NOT_FOUND", "复检记录不存在");
        }
        DiffSummaryResponse summary = recheckRun.status() == TaskStatus.SUCCEEDED
                ? sourceCompareRepository.diffSummary(projectId, recheckRun.id())
                : DiffSummaryResponse.empty();
        DiffItemsPageLinkResponse diffItems = recheckRun.status() == TaskStatus.SUCCEEDED
                ? new DiffItemsPageLinkResponse(
                        "/api/rechecks/%s/diff-items?page=%d&pageSize=%d".formatted(
                                recheckRun.id(),
                                DEFAULT_DIFF_PAGE,
                                DEFAULT_DIFF_PAGE_SIZE
                        ),
                        DEFAULT_DIFF_PAGE,
                        DEFAULT_DIFF_PAGE_SIZE,
                        summary.total()
                )
                : null;
        return new LatestSourceCompareResponse(
                publicStatus(recheckRun.status()),
                null,
                SourceRevisionResponse.from(current),
                SourceRevisionResponse.from(previous),
                RecheckRunResponse.from(recheckRun),
                summary,
                diffItems
        );
    }

    private LatestSourceCompareStatus publicStatus(TaskStatus status) {
        return switch (status) {
            case SUCCEEDED -> LatestSourceCompareStatus.SUCCEEDED;
            case FAILED, CANCELLED, TIMEOUT -> LatestSourceCompareStatus.FAILED;
            case PENDING, QUEUED, RUNNING -> LatestSourceCompareStatus.RUNNING;
        };
    }

    private void requireSameProject(UUID projectId, SourceRevisionView revision) {
        if (!Objects.equals(revision.projectId(), projectId)) {
            throw notFound("SOURCE_REVISION_NOT_FOUND", "源码版本不存在");
        }
    }

    private ApiException confirmationRequired() {
        return new ApiException(HttpStatus.CONFLICT, "FIX_LIST_CONFIRMATION_REQUIRED", "请先确认所选源码版本的待修复清单");
    }

    private ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    private ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
