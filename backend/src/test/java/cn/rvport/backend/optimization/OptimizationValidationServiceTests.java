package cn.rvport.backend.optimization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.rvport.backend.buildvalidation.*;
import cn.rvport.backend.jooq.tables.records.*;
import cn.rvport.backend.optimization.OptimizationEvidenceRepository.TestEvidence;
import cn.rvport.backend.project.ProjectOverviewResponse.WorkflowStageStatus;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentResponse;
import cn.rvport.backend.source.deploy.SourceDeployEnvironmentSummary;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.testcomparison.TestComparisonStatus;
import cn.rvport.backend.testcomparison.TestComparisonOverallStatus;
import cn.rvport.backend.workflow.ProjectContextSnapshot;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OptimizationValidationServiceTests {
    final UUID project = UUID.randomUUID(), source = UUID.randomUUID(), sourceEnv = UUID.randomUUID(), targetEnv = UUID.randomUUID(), batch = UUID.randomUUID();
    final OffsetDateTime now = OffsetDateTime.parse("2026-10-29T00:00:00Z");
    final BuildValidationRepository builds = mock(BuildValidationRepository.class);
    final OptimizationEvidenceRepository evidence = mock(OptimizationEvidenceRepository.class);
    final OptimizationValidationService service = new OptimizationValidationService(builds, evidence);
    final ProjectContextSnapshot context = new ProjectContextSnapshot(source, 1L);
    final BuildValidationRunView leftBuild = build(sourceEnv, TaskStatus.SUCCEEDED), rightBuild = build(targetEnv, TaskStatus.SUCCEEDED);
    final TestEvidence left = test(leftBuild, "SOURCE"), right = test(rightBuild, "TARGET");

    ProjectRuntimeEnvironmentResponse runtime() {
        var a = new SourceDeployEnvironmentSummary(sourceEnv, "x86", cn.rvport.backend.environment.EnvironmentRole.SOURCE_X86,
                "x86_64", cn.rvport.backend.environment.EnvironmentStatus.AVAILABLE, true);
        var b = new SourceDeployEnvironmentSummary(targetEnv, "riscv", cn.rvport.backend.environment.EnvironmentRole.TARGET_RISCV,
                "riscv64", cn.rvport.backend.environment.EnvironmentStatus.AVAILABLE, true);
        return new ProjectRuntimeEnvironmentResponse(project, a, b, 1, true, List.of(), null, now);
    }

    void buildsReady() {
        when(builds.findLatest(project, source, sourceEnv, 1L, BuildValidationScope.BOTH)).thenReturn(Optional.of(leftBuild));
        when(builds.findLatest(project, source, targetEnv, 1L, BuildValidationScope.BOTH)).thenReturn(Optional.of(rightBuild));
    }

    void testsReady() {
        buildsReady();
        when(evidence.candidate(project, source, 1L, sourceEnv, targetEnv)).thenReturn(Optional.of(left));
        when(evidence.side(project, source, 1L, sourceEnv, targetEnv, batch, "SOURCE")).thenReturn(Optional.of(left));
        when(evidence.side(project, source, 1L, sourceEnv, targetEnv, batch, "TARGET")).thenReturn(Optional.of(right));
    }

    TestComparisonRunsRecord comparison(String status, String overall) {
        var c = new TestComparisonRunsRecord(); c.setId(UUID.randomUUID()); c.setProjectId(project); c.setTestBatchId(batch);
        c.setSourceTestRunId(left.run().getId()); c.setTargetTestRunId(right.run().getId()); c.setStatus(status); c.setOverallStatus(overall);
        c.setCreatedAt(now); c.setFinishedAt(now); c.setSummary("比较摘要"); c.setRecommendation("检查差异");
        when(evidence.comparison(project, batch, left.run().getId(), right.run().getId())).thenReturn(Optional.of(c));
        return c;
    }

    @Test void missingContextNeverPerformsUnfilteredEvidenceQueries() {
        assertThat(service.resolve(project, new ProjectContextSnapshot(null, 1L), null).reasonCode()).isEqualTo("ACTIVE_SOURCE_MISSING");
        var r = runtime();
        for (var invalid : Arrays.asList(null, new ProjectRuntimeEnvironmentResponse(project, null, r.targetEnvironment(), 1, false, List.of(), null, now),
                new ProjectRuntimeEnvironmentResponse(project, r.sourceEnvironment(), null, 1, false, List.of(), null, now),
                new ProjectRuntimeEnvironmentResponse(project, r.sourceEnvironment(), r.targetEnvironment(), 2, true, List.of(), null, now))) {
            assertThat(service.resolve(project, context, invalid).status()).isEqualTo(WorkflowStageStatus.PENDING);
        }
        assertThat(service.resolve(project, new ProjectContextSnapshot(source, null), r).nextAction()).isNull();
        verifyNoInteractions(builds, evidence);
    }

    @ParameterizedTest @EnumSource(TaskStatus.class)
    void buildStatesCannotFallBackToPreviousSuccess(TaskStatus status) {
        var selected = build(sourceEnv, status);
        when(builds.findLatest(project, source, sourceEnv, 1L, BuildValidationScope.BOTH)).thenReturn(Optional.of(selected));
        var result = service.resolve(project, context, runtime());
        assertThat(result.status()).isEqualTo(switch(status) {
            case FAILED, CANCELLED, TIMEOUT -> WorkflowStageStatus.BLOCKED;
            default -> WorkflowStageStatus.PENDING;
        });
        assertThat(result.build().latestStatus()).isEqualTo(status == TaskStatus.SUCCEEDED ? null : status.name());
        assertThat(result.nextAction().stageId()).isEqualTo("build");
        verifyNoInteractions(evidence);
    }

    @Test void newCycleWithoutBuildIsPendingAndTestsAreQueriedOnlyAfterBothBuildsSucceed() {
        var missing = service.resolve(project, context, runtime());
        assertThat(missing.reasonCode()).isEqualTo("OPTIMIZATION_BUILD_MISSING");
        assertThat(missing.build().total()).isZero();
        buildsReady();
        var result = service.resolve(project, context, runtime());
        assertThat(result.reasonCode()).isEqualTo("OPTIMIZATION_TEST_MISSING");
        assertThat(result.build().latestStatus()).isEqualTo("SUCCEEDED");
        assertThat(result.test().latestRunId()).isNull();
        assertThat(result.nextAction().stageId()).isEqualTo("test");
    }

    @Test void incompleteLegacyCandidateNeverFallsBack() {
        buildsReady();
        when(evidence.candidate(project, source, 1L, sourceEnv, targetEnv)).thenReturn(Optional.of(left));
        left.run().setTestBatchId(null);
        assertThat(service.resolve(project, context, runtime()).reasonCode()).isEqualTo("OPTIMIZATION_TEST_STALE");
        left.run().setTestBatchId(batch);
        when(evidence.candidate(project, source, 1L, sourceEnv, targetEnv)).thenReturn(Optional.of(new TestEvidence(left.run(), null)));
        assertThat(service.resolve(project, context, runtime()).test().total()).isZero();
        when(evidence.candidate(project, source, 1L, sourceEnv, targetEnv)).thenReturn(Optional.of(left));
        left.run().setTestBatchRole(null);
        assertThat(service.resolve(project, context, runtime()).reasonCode()).isEqualTo("OPTIMIZATION_TEST_STALE");
        verify(evidence, never()).side(any(), any(), anyLong(), any(), any(), any(), any());
    }

    @Test void newIncompleteBatchAndEveryBrokenBindingInvalidatesDownstream() {
        testsReady();
        when(evidence.side(project, source, 1L, sourceEnv, targetEnv, batch, "SOURCE")).thenReturn(Optional.empty());
        assertThat(service.resolve(project, context, runtime()).reasonCode()).isEqualTo("OPTIMIZATION_TEST_MISSING");
        when(evidence.side(project, source, 1L, sourceEnv, targetEnv, batch, "SOURCE")).thenReturn(Optional.of(left));
        when(evidence.side(project, source, 1L, sourceEnv, targetEnv, batch, "TARGET")).thenReturn(Optional.empty());
        assertThat(service.resolve(project, context, runtime()).test().latestStatus()).isNull();
        when(evidence.side(project, source, 1L, sourceEnv, targetEnv, batch, "TARGET")).thenReturn(Optional.of(new TestEvidence(right.run(), null)));
        assertThat(service.resolve(project, context, runtime()).status()).isEqualTo(WorkflowStageStatus.PENDING);
        when(evidence.side(project, source, 1L, sourceEnv, targetEnv, batch, "TARGET")).thenReturn(Optional.of(right));
        var mutations = List.<java.util.function.Consumer<TestRunsRecord>>of(
                r -> r.setBuildRunId(UUID.randomUUID()), r -> r.setEnvironmentId(UUID.randomUUID()),
                r -> r.setSourceRevisionId(UUID.randomUUID()), r -> r.setProjectId(UUID.randomUUID()), r -> r.setEnvironmentConfigVersion(2L));
        for (var mutation : mutations) {
            var record = test(rightBuild, "TARGET"); mutation.accept(record.run());
            when(evidence.side(project, source, 1L, sourceEnv, targetEnv, batch, "TARGET")).thenReturn(Optional.of(record));
            var result = service.resolve(project, context, runtime());
            assertThat(result.reasonCode()).isEqualTo("OPTIMIZATION_TEST_STALE");
            assertThat(result.testComparison()).isNull();
        }
        verify(evidence, never()).comparison(any(), any(), any(), any());
    }

    @Test void failedSideIsStillBlockedWhenPeerIsMissing() {
        testsReady();
        right.run().setStatus("FAILED");
        when(evidence.side(project, source, 1L, sourceEnv, targetEnv, batch, "SOURCE")).thenReturn(Optional.empty());
        assertThat(service.resolve(project, context, runtime()).status()).isEqualTo(WorkflowStageStatus.BLOCKED);
        when(evidence.side(project, source, 1L, sourceEnv, targetEnv, batch, "TARGET")).thenReturn(Optional.empty());
        assertThat(service.resolve(project, context, runtime()).reasonCode()).isEqualTo("OPTIMIZATION_TEST_STALE");
    }

    @ParameterizedTest @EnumSource(TaskStatus.class)
    void pairedTestStatesAndMissingComparison(TaskStatus status) {
        testsReady(); right.run().setStatus(status.name());
        var result = service.resolve(project, context, runtime());
        assertThat(result.status()).isEqualTo(switch(status) {
            case FAILED, CANCELLED, TIMEOUT -> WorkflowStageStatus.BLOCKED;
            default -> WorkflowStageStatus.PENDING;
        });
        assertThat(result.test().latestStatus()).isEqualTo(status.name());
        assertThat(result.nextAction().stageId()).isEqualTo("test");
        if (status == TaskStatus.SUCCEEDED) assertThat(result.reasonCode()).isEqualTo("OPTIMIZATION_COMPARISON_MISSING");
    }

    @ParameterizedTest @EnumSource(TestComparisonStatus.class)
    void comparisonExecutionMustSucceedEvenIfOldOverallStatusSaysEquivalent(TestComparisonStatus status) {
        testsReady(); var original = comparison(status.name(), "EQUIVALENT");
        var result = service.resolve(project, context, runtime());
        assertThat(result.status()).isEqualTo(switch(status) {
            case SUCCEEDED -> WorkflowStageStatus.DONE;
            case PENDING, QUEUED, RUNNING -> WorkflowStageStatus.RUNNING;
            default -> WorkflowStageStatus.BLOCKED;
        });
        assertThat(result.testComparison().overallStatus()).isEqualTo(status == TestComparisonStatus.SUCCEEDED ? "EQUIVALENT" : null);
        assertThat(original.getOverallStatus()).isEqualTo("EQUIVALENT");
        assertClientContract(result);
    }

    @ParameterizedTest @EnumSource(TestComparisonOverallStatus.class)
    void onlyEquivalentCompletesTheCurrentChain(TestComparisonOverallStatus overall) {
        testsReady(); comparison("SUCCEEDED", overall.name());
        var result = service.resolve(project, context, runtime());
        assertThat(result.status()).isEqualTo(overall == TestComparisonOverallStatus.EQUIVALENT ? WorkflowStageStatus.DONE : WorkflowStageStatus.BLOCKED);
        assertThat(result.sourceBuildRunId()).isEqualTo(leftBuild.id());
        assertThat(result.targetBuildRunId()).isEqualTo(rightBuild.id());
        assertThat(result.testBatchId()).isEqualTo(batch);
        assertClientContract(result);
    }

    @Test void absentConclusionCannotPassAndDtoContainsBilingualSafeFacts() throws Exception {
        testsReady(); comparison("SUCCEEDED", null);
        var result = service.resolve(project, context, runtime());
        assertThat(result.status()).isEqualTo(WorkflowStageStatus.BLOCKED);
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(result);
        assertThat(json).contains("reason_en", "description_en", "sourceBuildRunId").doesNotContain("command", "workingDirectory", "password");
    }

    @Test void pairSelectsRealRepresentativeAndKeepsMissingSidesEmpty() {
        var a = new OptimizationValidationService.Run(UUID.randomUUID(), "SUCCEEDED", null, null, null);
        var b = new OptimizationValidationService.Run(UUID.randomUUID(), "SUCCEEDED", now, now, now);
        assertThat(OptimizationValidationService.pair(null, a).latestRunId()).isNull();
        var result = OptimizationValidationService.pair(a, b);
        assertThat(result.latestRunId()).isEqualTo(b.id());
        assertThat(result.byStatus()).containsEntry("SUCCEEDED", 2L);
        var queued = new OptimizationValidationService.Run(UUID.randomUUID(), "QUEUED", now.minusDays(1), null, null);
        assertThat(OptimizationValidationService.pair(b, queued).latestRunId()).isEqualTo(queued.id());
    }

    void assertClientContract(OptimizationValidation result) {
        var summary = result.applyTo(OptimizationCycleSummaryResponse.empty());
        boolean clientPassed = "SUCCEEDED".equals(summary.build().latestStatus()) && "SUCCEEDED".equals(summary.test().latestStatus())
                && summary.testComparison() != null && "EQUIVALENT".equals(summary.testComparison().overallStatus());
        assertThat(clientPassed).isEqualTo(result.status() == WorkflowStageStatus.DONE);
        assertThat(result.nextAction().stageId()).isEqualTo(clientPassed ? "archive" : "test");
    }

    BuildValidationRunView build(UUID env, TaskStatus status) {
        var r = new BuildRunsRecord(); r.setId(UUID.randomUUID()); r.setProjectId(project); r.setSourceRevisionId(source);
        r.setEnvironmentId(env); r.setEnvironmentConfigVersion(1L); r.setStatus(status.name()); r.setCreatedAt(now);
        r.setScriptSha256("test-script-hash");
        return BuildValidationRunView.from(r, Map.of(), BuildValidationScope.BOTH, Map.of("buildBatchId", batch.toString()));
    }

    TestEvidence test(BuildValidationRunView build, String role) {
        var r = new TestRunsRecord(); r.setId(UUID.randomUUID()); r.setProjectId(project); r.setSourceRevisionId(source);
        r.setBuildRunId(build.id()); r.setEnvironmentId(build.environmentId()); r.setEnvironmentConfigVersion(1L);
        r.setTestBatchId(batch); r.setTestBatchRole(role); r.setStatus("SUCCEEDED");
        return new TestEvidence(r, now);
    }
}
