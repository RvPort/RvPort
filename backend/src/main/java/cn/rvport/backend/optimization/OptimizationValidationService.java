package cn.rvport.backend.optimization;

import cn.rvport.backend.buildvalidation.BuildValidationRepository;
import cn.rvport.backend.buildvalidation.BuildValidationRunView;
import cn.rvport.backend.buildvalidation.BuildValidationScope;
import cn.rvport.backend.optimization.OptimizationCycleSummaryResponse.StageRunSummary;
import cn.rvport.backend.optimization.OptimizationCycleSummaryResponse.TestComparisonSummary;
import cn.rvport.backend.optimization.OptimizationEvidenceRepository.TestEvidence;
import cn.rvport.backend.project.ProjectOverviewResponse.WorkflowStageStatus;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentResponse;
import cn.rvport.backend.workflow.ProjectContextSnapshot;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class OptimizationValidationService {
    private final BuildValidationRepository builds;
    private final OptimizationEvidenceRepository evidence;

    public OptimizationValidationService(BuildValidationRepository builds, OptimizationEvidenceRepository evidence) {
        this.builds = builds;
        this.evidence = evidence;
    }

    public OptimizationValidation resolve(UUID project, ProjectContextSnapshot context, ProjectRuntimeEnvironmentResponse runtime) {
        if (context.sourceRevisionId() == null) {
            return OptimizationValidation.unavailable("ACTIVE_SOURCE_MISSING", "当前源码缺失", "Active source is missing");
        }
        if (runtime == null || runtime.sourceEnvironment() == null || runtime.targetEnvironment() == null
                || context.runtimeEnvironmentVersion() == null || runtime.version() != context.runtimeEnvironmentVersion()) {
            return OptimizationValidation.unavailable("OPTIMIZATION_ENVIRONMENT_MISSING", "当前运行环境未准备", "Current runtime environments are missing");
        }
        var source = builds.findLatest(project, context.sourceRevisionId(), runtime.sourceEnvironment().id(),
                context.runtimeEnvironmentVersion(), BuildValidationScope.BOTH).orElse(null);
        var target = builds.findLatest(project, context.sourceRevisionId(), runtime.targetEnvironment().id(),
                context.runtimeEnvironmentVersion(), BuildValidationScope.BOTH).orElse(null);
        var build = pair(run(source), run(target));
        var empty = StageRunSummary.empty();
        if (!"SUCCEEDED".equals(build.latestStatus())) {
            return result(build, empty, null, source, target, null, null, null, "BUILD", "构建验证", "Build validation", "build");
        }
        if (!cn.rvport.backend.buildvalidation.BuildValidationPair.succeeded(source, target)) {
            return OptimizationValidation.unavailable("BUILD_VALIDATION_PAIR_INCOMPLETE",
                    "双端构建不属于同一有效验证批次", "Builds do not belong to the same valid validation batch");
        }
        var candidate = evidence.candidate(project, context.sourceRevisionId(), context.runtimeEnvironmentVersion(),
                runtime.sourceEnvironment().id(), runtime.targetEnvironment().id()).orElse(null);
        if (candidate == null) {
            return result(build, empty, null, source, target, null, null, null, "TEST_MISSING", "缺少双端测试", "Paired tests are missing", "test");
        }
        UUID batch = candidate.run().getTestBatchId();
        if (batch == null || candidate.createdAt() == null
                || !List.of("SOURCE", "TARGET").contains(Objects.toString(candidate.run().getTestBatchRole(), ""))) {
            return result(build, empty, null, source, target, batch, null, null, "TEST_STALE", "测试关联信息缺失", "Test evidence is incomplete", "test");
        }
        var left = evidence.side(project, context.sourceRevisionId(), context.runtimeEnvironmentVersion(),
                runtime.sourceEnvironment().id(), runtime.targetEnvironment().id(), batch, "SOURCE").orElse(null);
        var right = evidence.side(project, context.sourceRevisionId(), context.runtimeEnvironmentVersion(),
                runtime.sourceEnvironment().id(), runtime.targetEnvironment().id(), batch, "TARGET").orElse(null);
        if (left == null && valid(right, target)) {
            return result(build, pair(null, run(right)), null, source, target, batch, null, right,
                    "TEST", "自动化测试", "Automated tests", "test");
        }
        if (right == null && valid(left, source)) {
            return result(build, pair(run(left), null), null, source, target, batch, left, null,
                    "TEST", "自动化测试", "Automated tests", "test");
        }
        if (!valid(left, source) || !valid(right, target)) {
            return result(build, empty, null, source, target, batch, left, right, "TEST_STALE", "测试缺失或不属于当前构建", "Tests are missing or do not match current builds", "test");
        }
        var test = pair(run(left), run(right));
        if (!"SUCCEEDED".equals(test.latestStatus())) {
            return result(build, test, null, source, target, batch, left, right, "TEST", "自动化测试", "Automated tests", "test");
        }
        var comparison = evidence.comparison(project, batch, left.run().getId(), right.run().getId()).orElse(null);
        if (comparison == null) {
            return result(build, test, null, source, target, batch, left, right, "COMPARISON_MISSING", "缺少双端结果比较", "Test comparison is missing", "test");
        }
        // 当前兼容摘要不能让失败执行遗留的成功结论被旧客户端误读。
        var summary = new TestComparisonSummary(comparison.getId(), comparison.getTaskId(), batch, comparison.getStatus(),
                "SUCCEEDED".equals(comparison.getStatus()) ? comparison.getOverallStatus() : null,
                comparison.getRiskLevel(), comparison.getSummary(), comparison.getRecommendation(),
                comparison.getCreatedAt(), comparison.getFinishedAt());
        return result(build, test, summary, source, target, batch, left, right, "COMPARISON", "双端结果比较", "Test comparison", "test");
    }

    private boolean valid(TestEvidence test, BuildValidationRunView build) {
        if (test == null || test.createdAt() == null) return false;
        var run = test.run();
        return Objects.equals(run.getBuildRunId(), build.id()) && Objects.equals(run.getEnvironmentId(), build.environmentId())
                && Objects.equals(run.getSourceRevisionId(), build.sourceRevisionId())
                && Objects.equals(run.getProjectId(), build.projectId())
                && Objects.equals(run.getEnvironmentConfigVersion(), build.environmentConfigVersion());
    }

    private OptimizationValidation result(StageRunSummary build, StageRunSummary test, TestComparisonSummary comparison,
            BuildValidationRunView source, BuildValidationRunView target, UUID batch, TestEvidence left, TestEvidence right,
            String reason, String zh, String en, String action) {
        var status = WorkflowStageStatus.PENDING;
        if (comparison != null) {
            if (active(comparison.status())) {
                status = WorkflowStageStatus.RUNNING;
                reason += "_RUNNING";
            } else if ("SUCCEEDED".equals(comparison.status()) && "EQUIVALENT".equals(comparison.overallStatus())) {
                status = WorkflowStageStatus.DONE;
                reason = "VALIDATION_READY";
                zh = "当前双端验证已通过";
                en = "Current paired validation passed";
                action = "archive";
            } else {
                status = WorkflowStageStatus.BLOCKED;
                reason += "_NOT_PASSED";
            }
        } else if (reason.equals("BUILD") || reason.equals("TEST")) {
            String selected = reason.equals("BUILD") ? build.latestStatus() : test.latestStatus();
            if (active(selected)) reason += "_RUNNING";
            else if (selected == null) reason += "_MISSING";
            else { status = WorkflowStageStatus.BLOCKED; reason += "_FAILED"; }
        }
        return new OptimizationValidation(status, "OPTIMIZATION_" + reason, zh, en,
                source == null ? null : source.id(), target == null ? null : target.id(), batch,
                left == null ? null : left.run().getId(), right == null ? null : right.run().getId(), build, test, comparison,
                new OptimizationValidation.NextAction(action, zh, en));
    }

    record Run(UUID id, String status, OffsetDateTime createdAt, OffsetDateTime startedAt, OffsetDateTime finishedAt) { }

    private Run run(BuildValidationRunView build) {
        return build == null ? null : new Run(build.id(), build.status().name(), build.createdAt(), build.startedAt(), build.finishedAt());
    }

    private Run run(TestEvidence test) {
        return new Run(test.run().getId(), test.run().getStatus(), test.createdAt(), test.run().getStartedAt(), test.run().getFinishedAt());
    }

    static boolean active(String status) {
        return "PENDING".equals(status) || "QUEUED".equals(status) || "RUNNING".equals(status);
    }

    static StageRunSummary pair(Run left, Run right) {
        List<Run> runs = new ArrayList<>();
        if (left != null) runs.add(left);
        if (right != null) runs.add(right);
        runs.sort(Comparator.comparingInt((Run r) -> active(r.status()) ? 0 : "SUCCEEDED".equals(r.status()) ? 2 : 1)
                .thenComparing(Run::createdAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Run::id, Comparator.reverseOrder()));
        Map<String, Long> counts = new LinkedHashMap<>();
        runs.forEach(r -> counts.merge(r.status(), 1L, Long::sum));
        if (runs.isEmpty()) return StageRunSummary.empty();
        var representative = runs.getFirst();
        if (runs.size() < 2 && "SUCCEEDED".equals(representative.status())) {
            return new StageRunSummary(runs.size(), null, null, null, null, counts);
        }
        return new StageRunSummary(runs.size(), representative.status(), representative.id(), representative.startedAt(), representative.finishedAt(), counts);
    }
}
