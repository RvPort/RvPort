package cn.rvport.backend.workflow;

import cn.rvport.backend.analysis.SpecializedAnalysisRepository;
import cn.rvport.backend.analysis.SpecializedAnalysisToolHealthService;
import cn.rvport.backend.analysis.SpecializedBitcodeCapabilityResolver;
import cn.rvport.backend.analysis.SpecializedAnalyzerType;
import cn.rvport.backend.archive.ArchiveCandidateService;
import cn.rvport.backend.automatedtest.AutomatedTestRepository;
import cn.rvport.backend.buildvalidation.BuildValidationRepository;
import cn.rvport.backend.buildvalidation.BuildValidationRunView;
import cn.rvport.backend.buildvalidation.BuildValidationScope;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.dependency.DependencyAnalysisRepository;
import cn.rvport.backend.kb.KbScanRepository;
import cn.rvport.backend.report.review.ReportInputSelectionPolicy;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentResponse;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentService;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentView;
import cn.rvport.backend.report.ReportRepository;
import cn.rvport.backend.source.SourceRepository;
import cn.rvport.backend.source.SourceRevisionView;
import cn.rvport.backend.source.deploy.SourceDeployRepository;
import cn.rvport.backend.task.TaskStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StageReadinessService {
    @Autowired
    private cn.rvport.backend.kb.KbScanContextService scanContextService;

    private final ProjectAccessService projectAccessService;
    private final ProjectContextService contextService;
    private final SourceRepository sourceRepository;
    private final ProjectRuntimeEnvironmentService runtimeEnvironmentService;
    private final SourceDeployRepository sourceDeployRepository;
    private final BuildValidationRepository buildValidationRepository;
    private final KbScanRepository kbScanRepository;
    private final DependencyAnalysisRepository dependencyAnalysisRepository;
    private final ReportRepository reportRepository;
    private final ReportInputSelectionPolicy reportInputs;
    private final ArchiveCandidateService archiveCandidateService;
    private final AutomatedTestRepository automatedTestRepository;
    private final SpecializedAnalysisRepository specializedAnalysisRepository;
    private final SpecializedAnalysisToolHealthService specializedToolHealthService;
    private final SpecializedBitcodeCapabilityResolver specializedBitcodeCapabilityResolver;

    @Autowired
    public StageReadinessService(
            ProjectAccessService projectAccessService,
            ProjectContextService contextService,
            SourceRepository sourceRepository,
            ProjectRuntimeEnvironmentService runtimeEnvironmentService,
            SourceDeployRepository sourceDeployRepository,
            BuildValidationRepository buildValidationRepository,
            KbScanRepository kbScanRepository,
            DependencyAnalysisRepository dependencyAnalysisRepository,
            ReportRepository reportRepository,
            ReportInputSelectionPolicy reportInputs,
            ArchiveCandidateService archiveCandidateService,
            AutomatedTestRepository automatedTestRepository,
            SpecializedAnalysisRepository specializedAnalysisRepository,
            SpecializedAnalysisToolHealthService specializedToolHealthService,
            SpecializedBitcodeCapabilityResolver specializedBitcodeCapabilityResolver
    ) {
        this.projectAccessService = projectAccessService;
        this.contextService = contextService;
        this.sourceRepository = sourceRepository;
        this.runtimeEnvironmentService = runtimeEnvironmentService;
        this.sourceDeployRepository = sourceDeployRepository;
        this.buildValidationRepository = buildValidationRepository;
        this.kbScanRepository = kbScanRepository;
        this.dependencyAnalysisRepository = dependencyAnalysisRepository;
        this.reportRepository = reportRepository;
        this.reportInputs = reportInputs;
        this.archiveCandidateService = archiveCandidateService;
        this.automatedTestRepository = automatedTestRepository;
        this.specializedAnalysisRepository = specializedAnalysisRepository;
        this.specializedToolHealthService = specializedToolHealthService;
        this.specializedBitcodeCapabilityResolver = specializedBitcodeCapabilityResolver;
    }

    StageReadinessService(
            ProjectAccessService projectAccessService,
            ProjectContextService contextService,
            SourceRepository sourceRepository,
            ProjectRuntimeEnvironmentService runtimeEnvironmentService,
            SourceDeployRepository sourceDeployRepository,
            BuildValidationRepository buildValidationRepository,
            KbScanRepository kbScanRepository,
            DependencyAnalysisRepository dependencyAnalysisRepository,
            ReportRepository reportRepository,
            ReportInputSelectionPolicy reportInputs,
            ArchiveCandidateService archiveCandidateService,
            AutomatedTestRepository automatedTestRepository
    ) {
        this(projectAccessService, contextService, sourceRepository, runtimeEnvironmentService, sourceDeployRepository,
                buildValidationRepository, kbScanRepository, dependencyAnalysisRepository, reportRepository,
                reportInputs, archiveCandidateService, automatedTestRepository, null, null, null);
    }

    StageReadinessService(
            ProjectAccessService projectAccessService, ProjectContextService contextService,
            SourceRepository sourceRepository, ProjectRuntimeEnvironmentService runtimeEnvironmentService,
            SourceDeployRepository sourceDeployRepository, BuildValidationRepository buildValidationRepository,
            KbScanRepository kbScanRepository, DependencyAnalysisRepository dependencyAnalysisRepository,
            ReportRepository reportRepository, ReportInputSelectionPolicy reportInputs,
            ArchiveCandidateService archiveCandidateService, AutomatedTestRepository automatedTestRepository,
            SpecializedAnalysisRepository specializedAnalysisRepository,
            SpecializedAnalysisToolHealthService specializedToolHealthService
    ) {
        this(projectAccessService, contextService, sourceRepository, runtimeEnvironmentService, sourceDeployRepository,
                buildValidationRepository, kbScanRepository, dependencyAnalysisRepository, reportRepository,
                reportInputs, archiveCandidateService, automatedTestRepository, specializedAnalysisRepository,
                specializedToolHealthService, null);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public StageReadinessResponse readiness(UUID projectId, String stageId, UUID actorUserId, boolean superAdmin) {
        projectAccessService.requireProjectAccess(projectId, actorUserId, superAdmin);
        String normalizedStageId = normalizeStageId(stageId);
        ProjectContextSnapshot context = contextService.snapshot(projectId);
        ProjectRuntimeEnvironmentView runtimeView = currentRuntime(projectId);
        List<StagePrerequisiteResponse> prerequisites = switch (normalizedStageId) {
            case StageIds.KB -> analysis(projectId, context, runtimeView);
            case StageIds.X86_BUILD -> x86Build(projectId, context, runtimeView);
            case StageIds.RV_DEPENDENCY_ANALYSIS -> dependency(projectId, context, runtimeView);
            case StageIds.REPORT -> report(projectId, context);
            case StageIds.BUILD -> build(projectId, runtimeView);
            case StageIds.TEST -> test(projectId, context);
            case StageIds.OPTIMIZE -> optimize(projectId, context);
            case StageIds.ARCHIVE -> archive(projectId, context);
            case StageIds.FLOATING_POINT_ANALYSIS -> specializedAnalysis(projectId, context, runtimeView,
                    SpecializedAnalyzerType.FLOATING_POINT);
            case StageIds.MEMORY_ORDER_ANALYSIS -> specializedAnalysis(projectId, context, runtimeView,
                    SpecializedAnalyzerType.MEMORY_ORDER);
            case StageIds.AI_MIGRATION -> aiMigration(projectId, context);
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "STAGE_ID_INVALID", "阶段标识不正确");
        };
        return new StageReadinessResponse(normalizedStageId, context, prerequisites);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public void requireSatisfied(UUID projectId, String stageId, List<String> requiredKeys, UUID actorUserId, boolean superAdmin) {
        StageReadinessResponse response = readiness(projectId, stageId, actorUserId, superAdmin);
        for (String key : requiredKeys) {
            StagePrerequisiteResponse prerequisite = response.prerequisites().stream()
                    .filter(item -> item.key().equals(key))
                    .findFirst()
                    .orElse(StagePrerequisiteResponse.of(key, StagePrerequisiteStatus.MISSING, null, key + "_MISSING"));
            if (prerequisite.status() != StagePrerequisiteStatus.SATISFIED) {
                throw new PrerequisiteNotSatisfiedException(response.stageId(), prerequisite);
            }
        }
    }

    public StagePrerequisiteResponse activeSource(UUID projectId) {
        return sourceRepository.findActive(projectId)
                .map(source -> StagePrerequisiteResponse.of(
                        StagePrerequisiteKeys.ACTIVE_SOURCE,
                        StagePrerequisiteStatus.SATISFIED,
                        source.id(),
                        "ACTIVE_SOURCE_READY"))
                .orElseGet(() -> StagePrerequisiteResponse.of(
                        StagePrerequisiteKeys.ACTIVE_SOURCE,
                        StagePrerequisiteStatus.MISSING,
                        null,
                        "ACTIVE_SOURCE_MISSING"));
    }

    private List<StagePrerequisiteResponse> x86Build(UUID projectId, ProjectContextSnapshot context, ProjectRuntimeEnvironmentView runtime) {
        List<StagePrerequisiteResponse> prerequisites = new ArrayList<>();
        prerequisites.add(activeSource(projectId));
        prerequisites.add(sourceEnvironment(projectId, runtime));
        if (context.sourceRevisionId() != null && runtime.sourceEnvironmentId() != null) {
            sourceDeployRepository.findLatestSucceeded(projectId, context.sourceRevisionId(), runtime.sourceEnvironmentId(), runtime.version())
                    .ifPresent(deployment -> prerequisites.add(StagePrerequisiteResponse.of(
                            StagePrerequisiteKeys.SOURCE_DEPLOYMENT,
                            StagePrerequisiteStatus.SATISFIED,
                            deployment.id(),
                            "SOURCE_DEPLOYMENT_READY")));
        }
        return prerequisites;
    }

    private List<StagePrerequisiteResponse> dependency(UUID projectId, ProjectContextSnapshot context, ProjectRuntimeEnvironmentView runtime) {
        return List.of(
                x86BuildRun(projectId, context, runtime),
                targetEnvironment(projectId, runtime)
        );
    }

    private List<StagePrerequisiteResponse> analysis(UUID projectId, ProjectContextSnapshot context, ProjectRuntimeEnvironmentView runtime) {
        return List.of(
                activeSource(projectId),
                scanContextService.resolve(projectId, context.sourceRevisionId()).prerequisite()
        );
    }

    private List<StagePrerequisiteResponse> specializedAnalysis(
            UUID projectId,
            ProjectContextSnapshot context,
            ProjectRuntimeEnvironmentView runtime,
            SpecializedAnalyzerType type
    ) {
        List<StagePrerequisiteResponse> prerequisites = new ArrayList<>();
        prerequisites.add(activeSource(projectId));
        prerequisites.add(x86BuildRun(projectId, context, runtime));
        if (specializedBitcodeCapabilityResolver != null && context.sourceRevisionId() != null && runtime != null) {
            buildValidationRepository.findLatestSucceededSourceBuildWithCompileDatabase(projectId,
                    context.sourceRevisionId(), runtime.sourceEnvironmentId(), runtime.version()).ifPresent(build -> {
                var capability = specializedBitcodeCapabilityResolver.resolve(build.id());
                prerequisites.add(StagePrerequisiteResponse.of(
                        StagePrerequisiteKeys.SPECIALIZED_ANALYSIS_EVIDENCE,
                        StagePrerequisiteStatus.SATISFIED,
                        build.id(),
                        capability.mode().name(),
                        Map.of("analysisMode", capability.mode().name(), "bcStatus", capability.status(),
                                "plannedCount", capability.plannedCount(), "usableCount", capability.usableCount(),
                                "failedCount", capability.failedCount(), "astOnlyCount", capability.astOnlyCount(),
                                "degradationReason", Objects.toString(capability.degradationReason(), ""))
                ));
            });
        }
        if (specializedToolHealthService != null) {
            var health = specializedToolHealthService.health(type);
            prerequisites.add(StagePrerequisiteResponse.of(
                    StagePrerequisiteKeys.SPECIALIZED_ANALYZER,
                    health.available() ? StagePrerequisiteStatus.SATISFIED : StagePrerequisiteStatus.UNAVAILABLE,
                    null,
                    health.available() ? "SPECIALIZED_ANALYZER_AVAILABLE" : "SPECIALIZED_ANALYZER_UNAVAILABLE",
                    Map.of("analyzerType", type.name(), "toolStatus", health.status(), "reasons", health.reasons(),
                            "versions", health.versions())
            ));
        }
        return prerequisites;
    }

    private List<StagePrerequisiteResponse> report(UUID projectId, ProjectContextSnapshot context) {
        if(context.sourceRevisionId()==null) return List.of(StagePrerequisiteResponse.of(
                StagePrerequisiteKeys.INCLUDED_FINDINGS,StagePrerequisiteStatus.MISSING,null,"ACTIVE_SOURCE_MISSING"));
        var selected=reportInputs.current(projectId);
        var result=new java.util.ArrayList<StagePrerequisiteResponse>();
        for(var source:selected.sources()) {
            String key=switch(source.type()) {
                case "KB_FINDING" -> StagePrerequisiteKeys.KB_SCAN;
                case "DEPENDENCY_RESULT" -> StagePrerequisiteKeys.DEPENDENCY_ANALYSIS;
                default -> source.type();
            };
            result.add(StagePrerequisiteResponse.of(key,source.available()?StagePrerequisiteStatus.SATISFIED:StagePrerequisiteStatus.MISSING,
                    source.runId(),source.available()?"ANALYSIS_READY":source.reasonCode(),Map.of("count",source.candidates().size())));
        }
        result.add(StagePrerequisiteResponse.of(StagePrerequisiteKeys.INCLUDED_FINDINGS,
                selected.canGenerate()?StagePrerequisiteStatus.SATISFIED:StagePrerequisiteStatus.MISSING,null,
                selected.canGenerate()?"INCLUDED_FINDINGS_READY":"REPORT_ANALYSIS_NOT_READY",Map.of("count",(long)selected.candidates().size())));
        return List.copyOf(result);
    }

    /** 迁移前置（loop change）：报告已生成（REPORT 就绪 = 存在 current 报告且输入可聚合）。 */
    private List<StagePrerequisiteResponse> aiMigration(UUID projectId, ProjectContextSnapshot context) {
        if(context.sourceRevisionId()==null) return List.of(StagePrerequisiteResponse.of(
                StagePrerequisiteKeys.REPORT,StagePrerequisiteStatus.MISSING,null,"ACTIVE_SOURCE_MISSING"));
        var selected=reportInputs.current(projectId);
        boolean reportReady=selected.canGenerate();
        return List.of(StagePrerequisiteResponse.of(StagePrerequisiteKeys.REPORT,
                reportReady?StagePrerequisiteStatus.SATISFIED:StagePrerequisiteStatus.MISSING,null,
                reportReady?"REPORT_READY":"REPORT_NOT_GENERATED",
                Map.of("sourceCount",(long)selected.sources().stream().filter(cn.rvport.backend.report.review.ReportInputSelectionPolicy.Source::available).count())));
    }

    private List<StagePrerequisiteResponse> build(UUID projectId, ProjectRuntimeEnvironmentView runtime) {
        return List.of(activeSource(projectId), sourceEnvironment(projectId, runtime), targetEnvironment(projectId, runtime));
    }

    private List<StagePrerequisiteResponse> test(UUID projectId, ProjectContextSnapshot context) {
        Optional<RunRef> latest = automatedTestRepository.findLatest(projectId, context.sourceRevisionId(), context.runtimeEnvironmentVersion())
                .map(run -> new RunRef(run.id(), run.status()));
        if (latest.isPresent()) {
            RunRef run = latest.get();
            return List.of(StagePrerequisiteResponse.of(StagePrerequisiteKeys.BUILD_VALIDATION, status(run.status()), run.id(), reason("BUILD_VALIDATION", run.status())));
        }
        ProjectRuntimeEnvironmentView runtime = currentRuntime(projectId);
        Optional<BuildValidationRunView> source = buildValidationRepository.findLatest(projectId, context.sourceRevisionId(), runtime.sourceEnvironmentId(), runtime.version(), BuildValidationScope.BOTH);
        Optional<BuildValidationRunView> target = buildValidationRepository.findLatest(projectId, context.sourceRevisionId(), runtime.targetEnvironmentId(), runtime.version(), BuildValidationScope.BOTH);
        if (source.isPresent() && target.isPresent() && source.get().status() == TaskStatus.SUCCEEDED && target.get().status() == TaskStatus.SUCCEEDED) {
            return List.of(StagePrerequisiteResponse.of(StagePrerequisiteKeys.BUILD_VALIDATION, StagePrerequisiteStatus.SATISFIED, source.get().id(), "BUILD_VALIDATION_READY",
                    Map.of("sourceBuildRunId", source.get().id(), "targetBuildRunId", target.get().id())));
        }
        Optional<BuildValidationRunView> latestBuild = source.isPresent() && target.isPresent()
                ? (latest(source.get(), target.get()) == source.get() ? source : target)
                : source.or(() -> target);
        return List.of(latestBuild
                .map(run -> StagePrerequisiteResponse.of(StagePrerequisiteKeys.BUILD_VALIDATION, status(run.status()), run.id(), reason("BUILD_VALIDATION", run.status())))
                .orElseGet(() -> StagePrerequisiteResponse.of(StagePrerequisiteKeys.BUILD_VALIDATION, StagePrerequisiteStatus.MISSING, null, "BUILD_VALIDATION_MISSING")));
    }

    private List<StagePrerequisiteResponse> optimize(UUID projectId, ProjectContextSnapshot context) {
        return List.of(activeSource(projectId));
    }

    private List<StagePrerequisiteResponse> archive(UUID projectId, ProjectContextSnapshot context) {
        if (context.sourceRevisionId() == null) {
            return List.of(StagePrerequisiteResponse.of(StagePrerequisiteKeys.ARCHIVE_CANDIDATES, StagePrerequisiteStatus.MISSING, null, "ACTIVE_SOURCE_MISSING"));
        }
        List<?> candidates = archiveCandidateService.listActive(projectId, context.sourceRevisionId());
        if (!candidates.isEmpty()) {
            return List.of(StagePrerequisiteResponse.of(StagePrerequisiteKeys.ARCHIVE_CANDIDATES, StagePrerequisiteStatus.SATISFIED, null, "ARCHIVE_CANDIDATES_READY", Map.of("count", candidates.size())));
        }
        return List.of(StagePrerequisiteResponse.of(StagePrerequisiteKeys.ARCHIVE_CANDIDATES, StagePrerequisiteStatus.MISSING, null, "ARCHIVE_CANDIDATES_MISSING", Map.of("count", 0)));
    }

    private StagePrerequisiteResponse x86BuildRun(UUID projectId, ProjectContextSnapshot context, ProjectRuntimeEnvironmentView runtime) {
        if (context.sourceRevisionId() == null) {
            return StagePrerequisiteResponse.of(StagePrerequisiteKeys.X86_BUILD, StagePrerequisiteStatus.MISSING, null, "ACTIVE_SOURCE_MISSING");
        }
        if (runtime.sourceEnvironmentId() == null) {
            return StagePrerequisiteResponse.of(StagePrerequisiteKeys.X86_BUILD, StagePrerequisiteStatus.MISSING, null, "SOURCE_ENVIRONMENT_MISSING");
        }
        return buildValidationRepository.findLatest(projectId, context.sourceRevisionId(), runtime.sourceEnvironmentId(), runtime.version(), BuildValidationScope.SOURCE_ONLY)
                .map(run -> StagePrerequisiteResponse.of(StagePrerequisiteKeys.X86_BUILD, status(run.status()), run.id(), reason("X86_BUILD", run.status())))
                .orElseGet(() -> StagePrerequisiteResponse.of(StagePrerequisiteKeys.X86_BUILD, StagePrerequisiteStatus.MISSING, null, "X86_BUILD_MISSING"));
    }

    private StagePrerequisiteResponse sourceEnvironment(UUID projectId, ProjectRuntimeEnvironmentView runtime) {
        ProjectRuntimeEnvironmentResponse summary = runtimeEnvironmentService.summary(projectId);
        if (runtime.sourceEnvironmentId() == null || summary.sourceEnvironment() == null) {
            return StagePrerequisiteResponse.of(StagePrerequisiteKeys.SOURCE_ENVIRONMENT, StagePrerequisiteStatus.MISSING, null, "SOURCE_ENVIRONMENT_MISSING");
        }
        return !summary.sourceEnvironment().enabled()
                ? StagePrerequisiteResponse.of(StagePrerequisiteKeys.SOURCE_ENVIRONMENT, StagePrerequisiteStatus.UNAVAILABLE, runtime.sourceEnvironmentId(), "SOURCE_ENVIRONMENT_UNAVAILABLE")
                : StagePrerequisiteResponse.of(StagePrerequisiteKeys.SOURCE_ENVIRONMENT, StagePrerequisiteStatus.SATISFIED, runtime.sourceEnvironmentId(), "SOURCE_ENVIRONMENT_READY");
    }

    private StagePrerequisiteResponse targetEnvironment(UUID projectId, ProjectRuntimeEnvironmentView runtime) {
        ProjectRuntimeEnvironmentResponse summary = runtimeEnvironmentService.summary(projectId);
        if (runtime.targetEnvironmentId() == null || summary.targetEnvironment() == null) {
            return StagePrerequisiteResponse.of(StagePrerequisiteKeys.TARGET_ENVIRONMENT, StagePrerequisiteStatus.MISSING, null, "TARGET_ENVIRONMENT_MISSING");
        }
        return !summary.targetEnvironment().enabled()
                ? StagePrerequisiteResponse.of(StagePrerequisiteKeys.TARGET_ENVIRONMENT, StagePrerequisiteStatus.UNAVAILABLE, runtime.targetEnvironmentId(), "TARGET_ENVIRONMENT_UNAVAILABLE")
                : StagePrerequisiteResponse.of(StagePrerequisiteKeys.TARGET_ENVIRONMENT, StagePrerequisiteStatus.SATISFIED, runtime.targetEnvironmentId(), "TARGET_ENVIRONMENT_READY");
    }

    private StagePrerequisiteResponse runStatus(String key, String reasonPrefix, Optional<RunRef> latest) {
        return latest
                .map(run -> StagePrerequisiteResponse.of(key, status(run.status()), run.id(), reason(reasonPrefix, run.status())))
                .orElseGet(() -> StagePrerequisiteResponse.of(key, StagePrerequisiteStatus.MISSING, null, reasonPrefix + "_MISSING"));
    }

    private StagePrerequisiteStatus status(TaskStatus status) {
        if (status == TaskStatus.SUCCEEDED) {
            return StagePrerequisiteStatus.SATISFIED;
        }
        if (status == TaskStatus.PENDING || status == TaskStatus.QUEUED || status == TaskStatus.RUNNING) {
            return StagePrerequisiteStatus.RUNNING;
        }
        return StagePrerequisiteStatus.FAILED;
    }

    private String reason(String prefix, TaskStatus status) {
        return switch (status) {
            case SUCCEEDED -> prefix + "_READY";
            case PENDING, QUEUED, RUNNING -> prefix + "_RUNNING";
            case CANCELLED -> prefix + "_CANCELLED";
            case TIMEOUT -> prefix + "_TIMEOUT";
            case FAILED -> prefix + "_FAILED";
        };
    }

    private ProjectRuntimeEnvironmentView currentRuntime(UUID projectId) {
        ProjectRuntimeEnvironmentResponse summary = runtimeEnvironmentService.summary(projectId);
        return summary == null
                ? ProjectRuntimeEnvironmentView.empty(projectId)
                : new ProjectRuntimeEnvironmentView(
                        projectId,
                        summary.sourceEnvironment() == null ? null : summary.sourceEnvironment().id(),
                        summary.targetEnvironment() == null ? null : summary.targetEnvironment().id(),
                        summary.version(),
                        summary.updatedBy(),
                        null,
                        summary.updatedAt()
                );
    }

    private BuildValidationRunView latest(BuildValidationRunView left, BuildValidationRunView right) {
        if (left.createdAt() == null) {
            return right;
        }
        if (right.createdAt() == null) {
            return left;
        }
        return left.createdAt().isAfter(right.createdAt()) ? left : right;
    }

    private String normalizeStageId(String stageId) {
        if (stageId == null || stageId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "STAGE_ID_REQUIRED", "阶段标识不能为空");
        }
        return stageId.trim();
    }

    private record RunRef(UUID id, TaskStatus status) {
    }
}
