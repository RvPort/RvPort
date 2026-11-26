package cn.rvport.backend.project;

import cn.rvport.backend.analysis.SpecializedAnalysisRepository;
import cn.rvport.backend.analysis.SpecializedAnalysisStatus;
import cn.rvport.backend.analysis.SpecializedAnalyzerType;
import cn.rvport.backend.archive.ProjectArchiveRepository;
import cn.rvport.backend.automatedtest.AutomatedTestRepository;
import cn.rvport.backend.buildvalidation.BuildValidationRepository;
import cn.rvport.backend.buildvalidation.BuildValidationRunView;
import cn.rvport.backend.buildvalidation.BuildValidationScope;
import cn.rvport.backend.dependency.DependencyAnalysisRepository;
import cn.rvport.backend.kb.KbScanRepository;
import cn.rvport.backend.optimization.OptimizationCycleRepository;
import cn.rvport.backend.project.ProjectOverviewResponse.WorkflowResponse;
import cn.rvport.backend.project.ProjectOverviewResponse.WorkflowStageResponse;
import cn.rvport.backend.project.ProjectOverviewResponse.WorkflowStageStatus;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentResponse;
import cn.rvport.backend.report.ReportRepository;
import cn.rvport.backend.source.SourceRevisionResponse;
import cn.rvport.backend.task.TaskRepository;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.workflow.ProjectContextSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class ProjectWorkflowStatusService {

    public static final String PROJECT = "project";
    public static final String ENV = "env";
    public static final String ASSETS = "assets";
    public static final String KB = "kb";
    public static final String AI_MIGRATION = "ai-migration";
    public static final String X86_BUILD = "x86-build";
    public static final String DEPENDENCY = "rv-dependency-analysis";
    public static final String FLOATING_POINT = "floating-point-analysis";
    public static final String MEMORY_ORDER = "memory-order-analysis";
    public static final String REPORT = "report";
    public static final String MANUAL = "manual";
    public static final String BUILD = "build";
    public static final String TEST = "test";
    public static final String OPTIMIZE = "optimize";
    public static final String ARCHIVE = "archive";
    private static final String ENVIRONMENT_TASK_STAGE = "environment";

    private final TaskRepository taskRepository;
    private final KbScanRepository kbScanRepository;
    private final cn.rvport.backend.ai.migration.AiMigrationRepository aiMigrationRepository;
    private final BuildValidationRepository buildValidationRepository;
    private final DependencyAnalysisRepository dependencyAnalysisRepository;
    private final ReportRepository reportRepository;
    @Autowired private cn.rvport.backend.report.review.CurrentFixListResolver fixLists;
    private final AutomatedTestRepository automatedTestRepository;
    private final OptimizationCycleRepository optimizationCycleRepository;
    private final ProjectArchiveRepository archiveRepository;
    private final SpecializedAnalysisRepository specializedAnalysisRepository;
    @Autowired
    private cn.rvport.backend.kb.KbScanContextService scanContextService;
    @Autowired
    private cn.rvport.backend.optimization.OptimizationValidationService optimizationValidationService;
    @Autowired private cn.rvport.backend.workflow.StageReadinessService readiness;

    @Autowired
    public ProjectWorkflowStatusService(
            TaskRepository taskRepository,
            KbScanRepository kbScanRepository,
            BuildValidationRepository buildValidationRepository,
            DependencyAnalysisRepository dependencyAnalysisRepository,
            ReportRepository reportRepository,
            AutomatedTestRepository automatedTestRepository,
            OptimizationCycleRepository optimizationCycleRepository,
            ProjectArchiveRepository archiveRepository,
            SpecializedAnalysisRepository specializedAnalysisRepository,
            cn.rvport.backend.ai.migration.AiMigrationRepository aiMigrationRepository
    ) {
        this.taskRepository = taskRepository;
        this.kbScanRepository = kbScanRepository;
        this.aiMigrationRepository = aiMigrationRepository;
        this.buildValidationRepository = buildValidationRepository;
        this.dependencyAnalysisRepository = dependencyAnalysisRepository;
        this.reportRepository = reportRepository;
        this.automatedTestRepository = automatedTestRepository;
        this.optimizationCycleRepository = optimizationCycleRepository;
        this.archiveRepository = archiveRepository;
        this.specializedAnalysisRepository = specializedAnalysisRepository;
    }

    ProjectWorkflowStatusService(
            TaskRepository taskRepository,
            KbScanRepository kbScanRepository,
            BuildValidationRepository buildValidationRepository,
            DependencyAnalysisRepository dependencyAnalysisRepository,
            ReportRepository reportRepository,
            AutomatedTestRepository automatedTestRepository,
            OptimizationCycleRepository optimizationCycleRepository,
            ProjectArchiveRepository archiveRepository
    ) {
        this(taskRepository, kbScanRepository, buildValidationRepository, dependencyAnalysisRepository,
                reportRepository, automatedTestRepository, optimizationCycleRepository, archiveRepository, null, null);
    }

    public WorkflowResponse aggregate(
            UUID projectId,
            SourceRevisionResponse activeSource,
            ProjectRuntimeEnvironmentResponse runtime,
            ProjectContextSnapshot context
    ) {
        List<WorkflowStageResponse> stages = new ArrayList<>();
        stages.add(done(PROJECT, "PROJECT_READY"));
        stages.add(environment(projectId, runtime));
        stages.add(assets(projectId, activeSource));
        stages.add(x86Build(projectId, runtime, context));
        stages.add(kb(projectId, context));
        stages.add(dependency(projectId, context));
        stages.add(specialized(projectId, context, runtime, SpecializedAnalyzerType.FLOATING_POINT, FLOATING_POINT));
        stages.add(specialized(projectId, context, runtime, SpecializedAnalyzerType.MEMORY_ORDER, MEMORY_ORDER));
        var currentList=fixLists.current(projectId);
        WorkflowStageResponse report = report(projectId, context,currentList);
        stages.add(report);
        // AI 迁移与人工修复并列：都是读完评估报告后处理代码的方式，顺序与前端流水线拓扑一致。
        stages.add(aiMigration(projectId, context));
        stages.add(manual(report, activeSource,currentList));
        stages.add(build(projectId, runtime, context));
        stages.add(test(projectId, context));
        stages.add(optimize(projectId, context, runtime));
        stages.add(archive(projectId, context));
        if (readiness != null) {
            stages = stages.stream().map(stage -> withReadiness(projectId, stage)).toList();
        }
        return new WorkflowResponse(recommended(stages), stages,currentList);
    }

    private WorkflowStageResponse withReadiness(UUID project, WorkflowStageResponse stage) {
        if (List.of(PROJECT, ENV, ASSETS, MANUAL).contains(stage.id())) return stage;
        if (stage.status() == WorkflowStageStatus.RUNNING) return new WorkflowStageResponse(stage.id(), stage.status(),
                stage.reasonCode(), stage.analysisStatus(), stage.analysisComplete(), stage.message(), stage.messageEn(),
                X86_BUILD.equals(stage.id()), false);
        var prerequisites = readiness.resolve(project, stage.id()).prerequisites();
        if (List.of(FLOATING_POINT, MEMORY_ORDER).contains(stage.id()) && prerequisites.stream().anyMatch(p ->
                "SPECIALIZED_ANALYZER".equals(p.key()) && p.status() != cn.rvport.backend.workflow.StagePrerequisiteStatus.SATISFIED))
            stage = pending(stage.id(), "ANALYSIS_PROFILE_OR_TOOL_UNAVAILABLE");
        boolean canStart = prerequisites
                .stream().allMatch(p -> !p.required() || p.status() == cn.rvport.backend.workflow.StagePrerequisiteStatus.SATISFIED);
        return new WorkflowStageResponse(stage.id(), stage.status(), stage.reasonCode(), stage.analysisStatus(),
                stage.analysisComplete(), stage.message(), stage.messageEn(), X86_BUILD.equals(stage.id()), canStart);
    }

    private WorkflowStageResponse environment(UUID projectId, ProjectRuntimeEnvironmentResponse runtime) {
        Map<UUID, Long> environmentVersions = new LinkedHashMap<>();
        if (runtime.sourceEnvironment() != null) {
            environmentVersions.put(runtime.sourceEnvironment().id(), runtime.sourceEnvironment().configVersion());
        }
        if (runtime.targetEnvironment() != null) {
            environmentVersions.put(runtime.targetEnvironment().id(), runtime.targetEnvironment().configVersion());
        }
        Optional<TaskStatus> latest = taskRepository.findLatestByStageAndResourceVersions(
                        projectId, ENVIRONMENT_TASK_STAGE, environmentVersions)
                .map(task -> task.status());
        if (latest.filter(this::active).isPresent()) {
            return running(ENV, "ENVIRONMENT_TEST_RUNNING");
        }
        if (!runtime.ready()) {
            if (runtime.sourceEnvironment() == null || runtime.targetEnvironment() == null) {
                return pending(ENV, "RUNTIME_ENVIRONMENTS_INCOMPLETE");
            }
            if (latest.isPresent() && terminalFailure(latest.get())) {
                return blocked(ENV, "ENVIRONMENT_TEST_FAILED");
            }
            return pending(ENV, "ENVIRONMENT_TEST_REQUIRED");
        }
        return done(ENV, "ENVIRONMENT_READY");
    }

    private WorkflowStageResponse assets(UUID projectId, SourceRevisionResponse activeSource) {
        Optional<TaskStatus> latest = taskRepository.findLatestByStage(projectId, "source").map(task -> task.status());
        if (latest.filter(this::active).isPresent()) {
            return running(ASSETS, "SOURCE_IMPORT_RUNNING");
        }
        if (activeSource != null) {
            return done(ASSETS, "ACTIVE_SOURCE_READY");
        }
        if (latest.isPresent() && terminalFailure(latest.get())) {
            return blocked(ASSETS, "SOURCE_IMPORT_FAILED");
        }
        return pending(ASSETS, "ACTIVE_SOURCE_MISSING");
    }

    private WorkflowStageResponse kb(UUID projectId, ProjectContextSnapshot context) {
        if (context.sourceRevisionId() == null) {
            return pending(KB, kbScanRepository.findLatestHistoricalRun(projectId).isPresent()
                    ? "KB_SCAN_STALE" : "ACTIVE_SOURCE_MISSING");
        }
        return scanContextService.currentRun(projectId, context.sourceRevisionId())
                .map(run -> fromStatus(KB, run.status(), "KB_SCAN"))
                .orElseGet(() -> pending(KB, kbScanRepository.findLatestHistoricalRun(projectId).isPresent()
                        ? "KB_SCAN_STALE" : "KB_SCAN_MISSING"));
    }

    private WorkflowStageResponse aiMigration(UUID projectId, ProjectContextSnapshot context) {
        if (context.sourceRevisionId() == null) {
            return pending(AI_MIGRATION, "ACTIVE_SOURCE_MISSING");
        }
        var latest = aiMigrationRepository.listRuns(projectId, 0, 1);
        if (latest.isEmpty()) {
            boolean scanReady = scanContextService.currentRun(projectId, context.sourceRevisionId()).isPresent();
            return pending(AI_MIGRATION, scanReady ? "AI_MIGRATION_MISSING" : "KB_SCAN_MISSING");
        }
        var run = latest.get(0);
        return fromStatus(AI_MIGRATION, TaskStatus.valueOf(run.status()), "AI_MIGRATION");
    }

    private WorkflowStageResponse x86Build(
            UUID projectId,
            ProjectRuntimeEnvironmentResponse runtime,
            ProjectContextSnapshot context
    ) {
        if (context.sourceRevisionId() == null || runtime.sourceEnvironment() == null) {
            return pending(X86_BUILD, "X86_BUILD_PREREQUISITE_MISSING");
        }
        return buildValidationRepository.findLatest(projectId, context.sourceRevisionId(), runtime.sourceEnvironment().id(),
                        context.runtimeEnvironmentVersion(), BuildValidationScope.SOURCE_ONLY)
                .map(run -> fromStatus(X86_BUILD, run.status(), "X86_BUILD"))
                .orElseGet(() -> pending(X86_BUILD, "X86_BUILD_MISSING"));
    }

    private WorkflowStageResponse dependency(UUID projectId, ProjectContextSnapshot context) {
        if (context.sourceRevisionId() == null) {
            return pending(DEPENDENCY, "ACTIVE_SOURCE_MISSING");
        }
        return dependencyAnalysisRepository.findLatestRun(
                        projectId, context.sourceRevisionId(), context.runtimeEnvironmentVersion())
                .map(run -> fromStatus(DEPENDENCY, run.status(), "DEPENDENCY_ANALYSIS"))
                .orElseGet(() -> pending(DEPENDENCY, "DEPENDENCY_ANALYSIS_MISSING"));
    }

    private WorkflowStageResponse specialized(
            UUID projectId,
            ProjectContextSnapshot context,
            ProjectRuntimeEnvironmentResponse runtime,
            SpecializedAnalyzerType analyzerType,
            String stageId
    ) {
        if (context.sourceRevisionId() == null || specializedAnalysisRepository == null) {
            return pending(stageId, context.sourceRevisionId() == null ? "ACTIVE_SOURCE_MISSING" : "SPECIALIZED_ANALYSIS_MISSING");
        }
        return specializedAnalysisRepository.latest(projectId, analyzerType)
                .filter(run -> context.sourceRevisionId().equals(run.sourceRevisionId()))
                .filter(run -> {
                    if ("SOURCE_DISCOVERY".equals(run.summary().get("inputOrigin"))) return true;
                    if (runtime.sourceEnvironment() == null) return false;
                    return buildValidationRepository.findLatest(projectId, context.sourceRevisionId(),
                            runtime.sourceEnvironment().id(), runtime.version(), BuildValidationScope.SOURCE_ONLY)
                            .filter(build -> build.status() == TaskStatus.SUCCEEDED
                                    && build.id().equals(run.buildRunId())
                                    && java.util.Objects.equals(runtime.version(), run.environmentConfigVersion()))
                            .isPresent();
                })
                .map(run -> fromSpecializedStatus(stageId, run.status(), analyzerType.name()))
                .orElseGet(() -> pending(stageId, analyzerType.name() + "_MISSING"));
    }

    private WorkflowStageResponse fromSpecializedStatus(String stageId, SpecializedAnalysisStatus status, String prefix) {
        if (status == SpecializedAnalysisStatus.PARTIAL) {
            return new WorkflowStageResponse(stageId, WorkflowStageStatus.DONE, prefix + "_PARTIAL",
                    "PARTIAL", false, "部分分析完成", "Analysis partially completed");
        }
        return switch (status) {
            case PENDING, QUEUED, RUNNING -> running(stageId, prefix + "_RUNNING");
            case COMPLETE, COMPLETE_WITH_RISK, PARTIAL -> done(stageId, prefix + "_READY");
            case FAILED, CANCELLED, TIMEOUT -> blocked(stageId, prefix + "_" + status.name());
        };
    }

    private WorkflowStageResponse report(UUID projectId, ProjectContextSnapshot context,cn.rvport.backend.report.review.CurrentFixListResolver.Current list) {
        if (context.sourceRevisionId() == null) {
            return pending(REPORT, "ACTIVE_SOURCE_MISSING");
        }
        if(list.reportId()!=null && !List.of("REPORT_READ_ONLY","REPORT_CONTEXT_CHANGED").contains(list.reasonCode())) return done(REPORT,"REPORT_READY");
        return reportRepository.latestEvaluation(projectId,context.sourceRevisionId())
                .map(value->value.status()==TaskStatus.SUCCEEDED?pending(REPORT,"REPORT_CONTEXT_CHANGED"):fromStatus(REPORT,value.status(),"REPORT"))
                .orElseGet(()->pending(REPORT,"REPORT_MISSING"));
    }

    private WorkflowStageResponse manual(WorkflowStageResponse report, SourceRevisionResponse activeSource,cn.rvport.backend.report.review.CurrentFixListResolver.Current list) {
        if (activeSource == null) {
            return pending(MANUAL, "ACTIVE_SOURCE_MISSING");
        }
        if (report.status() == WorkflowStageStatus.RUNNING) {
            return pending(MANUAL, "REPORT_RUNNING");
        }
        if (report.status() == WorkflowStageStatus.BLOCKED) {
            return blocked(MANUAL, "REPORT_FAILED");
        }
        return report.status() == WorkflowStageStatus.DONE
                ? pending(MANUAL,list.available()?(list.itemCount()==0?"CONFIRMED_EMPTY":"MANUAL_FIX_AVAILABLE"):list.reasonCode())
                : pending(MANUAL, "REPORT_REQUIRED");
    }

    private WorkflowStageResponse build(
            UUID projectId,
            ProjectRuntimeEnvironmentResponse runtime,
            ProjectContextSnapshot context
    ) {
        if (context.sourceRevisionId() == null || runtime.sourceEnvironment() == null || runtime.targetEnvironment() == null) {
            return pending(BUILD, "BUILD_PREREQUISITE_MISSING");
        }
        Optional<BuildValidationRunView> source = buildValidationRepository.findLatest(
                projectId, context.sourceRevisionId(), runtime.sourceEnvironment().id(),
                context.runtimeEnvironmentVersion(), BuildValidationScope.BOTH);
        Optional<BuildValidationRunView> target = buildValidationRepository.findLatest(
                projectId, context.sourceRevisionId(), runtime.targetEnvironment().id(),
                context.runtimeEnvironmentVersion(), BuildValidationScope.BOTH);
        if (source.map(BuildValidationRunView::status).filter(this::active).isPresent()
                || target.map(BuildValidationRunView::status).filter(this::active).isPresent()) {
            return running(BUILD, "BUILD_VALIDATION_RUNNING");
        }
        if (source.isPresent() && target.isPresent()
                && source.get().status() == TaskStatus.SUCCEEDED
                && target.get().status() == TaskStatus.SUCCEEDED) {
            return done(BUILD, "BUILD_VALIDATION_READY");
        }
        if (source.map(BuildValidationRunView::status).filter(this::terminalFailure).isPresent()
                || target.map(BuildValidationRunView::status).filter(this::terminalFailure).isPresent()) {
            return blocked(BUILD, "BUILD_VALIDATION_FAILED");
        }
        return pending(BUILD, "BUILD_VALIDATION_MISSING");
    }

    private WorkflowStageResponse test(UUID projectId, ProjectContextSnapshot context) {
        if (context.sourceRevisionId() == null) {
            return pending(TEST, "ACTIVE_SOURCE_MISSING");
        }
        return automatedTestRepository.findLatest(projectId, context.sourceRevisionId(), context.runtimeEnvironmentVersion())
                .map(run -> fromStatus(TEST, run.status(), "AUTOMATED_TEST"))
                .orElseGet(() -> pending(TEST, "AUTOMATED_TEST_MISSING"));
    }

    private WorkflowStageResponse optimize(UUID projectId, ProjectContextSnapshot context, ProjectRuntimeEnvironmentResponse runtime) {
        if (context.sourceRevisionId() == null) {
            return pending(OPTIMIZE, "ACTIVE_SOURCE_MISSING");
        }
        if (optimizationCycleRepository.findBySourceRevision(projectId, context.sourceRevisionId()).isEmpty()) {
            return pending(OPTIMIZE, "OPTIMIZATION_CYCLE_MISSING");
        }
        var validation = optimizationValidationService.resolve(projectId, context, runtime);
        return new WorkflowStageResponse(OPTIMIZE, validation.status(), validation.reasonCode(),
                null, null, validation.reason(), validation.reasonEn());
    }

    private WorkflowStageResponse archive(UUID projectId, ProjectContextSnapshot context) {
        if (context.sourceRevisionId() == null) {
            return pending(ARCHIVE, "ACTIVE_SOURCE_MISSING");
        }
        return archiveRepository.latest(projectId, context.sourceRevisionId())
                .map(value -> fromStatus(ARCHIVE, value.status(), "ARCHIVE"))
                .orElseGet(() -> pending(ARCHIVE, "ARCHIVE_MISSING"));
    }

    private WorkflowStageResponse fromStatus(String id, TaskStatus status, String prefix) {
        if (active(status)) {
            return running(id, prefix + "_RUNNING");
        }
        if (status == TaskStatus.SUCCEEDED) {
            return done(id, prefix + "_READY");
        }
        return blocked(id, prefix + switch (status) {
            case FAILED -> "_FAILED";
            case CANCELLED -> "_CANCELLED";
            case TIMEOUT -> "_TIMEOUT";
            default -> "_BLOCKED";
        });
    }

    private boolean active(TaskStatus status) {
        return status == TaskStatus.PENDING || status == TaskStatus.QUEUED || status == TaskStatus.RUNNING;
    }

    private boolean terminalFailure(TaskStatus status) {
        return status == TaskStatus.FAILED || status == TaskStatus.CANCELLED || status == TaskStatus.TIMEOUT;
    }

    private String recommended(List<WorkflowStageResponse> stages) {
        return stages.stream().filter(stage -> stage.status() == WorkflowStageStatus.RUNNING).findFirst()
                .or(() -> stages.stream().filter(stage -> !stage.optional() && Boolean.TRUE.equals(stage.canStart())
                        && stage.status() != WorkflowStageStatus.DONE && !OPTIMIZE.equals(stage.id()) && !ARCHIVE.equals(stage.id())).findFirst())
                .or(() -> stages.stream().filter(stage -> stage.status() == WorkflowStageStatus.BLOCKED).findFirst())
                .or(() -> stages.stream().filter(stage -> stage.status() == WorkflowStageStatus.PENDING).findFirst())
                .map(WorkflowStageResponse::id)
                .orElse(ARCHIVE);
    }

    private WorkflowStageResponse pending(String id, String reason) {
        return new WorkflowStageResponse(id, WorkflowStageStatus.PENDING, reason);
    }

    private WorkflowStageResponse running(String id, String reason) {
        return new WorkflowStageResponse(id, WorkflowStageStatus.RUNNING, reason);
    }

    private WorkflowStageResponse done(String id, String reason) {
        return new WorkflowStageResponse(id, WorkflowStageStatus.DONE, reason);
    }

    private WorkflowStageResponse blocked(String id, String reason) {
        return new WorkflowStageResponse(id, WorkflowStageStatus.BLOCKED, reason);
    }
}
