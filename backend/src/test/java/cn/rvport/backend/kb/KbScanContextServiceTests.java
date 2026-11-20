package cn.rvport.backend.kb;

import static cn.rvport.backend.kb.KbContextFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import cn.rvport.backend.buildvalidation.*;
import cn.rvport.backend.project.runtime.*;
import cn.rvport.backend.source.*;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.workflow.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KbScanContextServiceTests {
    @Mock SourceRepository sources;
    @Mock ProjectRuntimeEnvironmentRepository runtimes;
    @Mock BuildValidationRepository builds;
    @Mock KbScanRepository scans;
    final UUID project = UUID.randomUUID();
    final UUID source = UUID.randomUUID();

    KbScanContextService service() { return new KbScanContextService(sources, runtimes, builds, scans); }
    @Test void explicitSourcePlanSurvivesUnconsumedBuildAndEnvironmentChanges() {
        var plan = new cn.rvport.backend.analysis.AnalysisInputPlan(1, project, source, "a".repeat(64), "KB", "0.1.0", "p1",
                cn.rvport.backend.analysis.AnalysisInputPlan.Identity.of("c", "x86", "riscv64", Map.of(), List.of(), "r1"),
                cn.rvport.backend.analysis.AnalysisInputPlan.InputOrigin.SOURCE_DISCOVERY, null, List.of(), List.of(), List.of(), null);
        var binding = new KbExecutionContext(null, null, null, plan);
        assertThat(binding.complete()).isTrue();
        var run = scan(project, source, TaskStatus.SUCCEEDED, binding);
        for (TaskStatus status : TaskStatus.values()) {
            var selection = new KbScanContextService.Selection(project, source, ENV, 900L,
                    build(project, source, ENV, 900L, UUID.randomUUID(), status, BuildValidationScope.SOURCE_ONLY));
            assertThat(selection.staleReason(run)).isNull();
        }
        assertThat(service().requireExecution(run, input(run, binding, project, source))).isNull();
        verifyNoInteractions(builds);
        for (var invalid : List.of(new KbExecutionContext(ENV, null, null, plan),
                new KbExecutionContext(null, 7L, null, plan), new KbExecutionContext(null, null, BUILD, plan)))
            assertThat(invalid.complete()).isFalse();
        assertThat(ready(project, UUID.randomUUID()).staleReason(run)).isEqualTo("KB_SCAN_SOURCE_CHANGED");
    }
    void runtime() {
        when(runtimes.find(project)).thenReturn(Optional.of(new ProjectRuntimeEnvironmentView(project, ENV, null, 7L, null, null, null)));
    }
    void activeSource() {
        when(sources.findActive(project)).thenReturn(Optional.of(new SourceRevisionView(source, project,
                SourceRevisionType.GIT, "main", null, null, null, null, null, "/source", null, true, null, null)));
    }
    KbScanInput input(KbScanView run, KbExecutionContext binding, UUID projectId, UUID sourceId) {
        return new KbScanInput(projectId, run.id(), sourceId, "rules", "c", "riscv64", Map.of(), List.of(), binding);
    }

    @ParameterizedTest @EnumSource(TaskStatus.class)
    void readinessUsesSelectedBuildWithoutFallingBack(TaskStatus status) {
        runtime();
        var selected = build(project, source, ENV, 7L, BUILD, status, BuildValidationScope.SOURCE_ONLY);
        when(builds.findLatest(project, source, ENV, 7L, BuildValidationScope.SOURCE_ONLY)).thenReturn(Optional.of(selected));
        var context = service().resolve(project, source);
        assertThat(context.ready()).isEqualTo(status == TaskStatus.SUCCEEDED);
        assertThat(context.prerequisite().status()).isEqualTo(switch (status) {
            case SUCCEEDED -> StagePrerequisiteStatus.SATISFIED;
            case PENDING, QUEUED, RUNNING -> StagePrerequisiteStatus.RUNNING;
            default -> StagePrerequisiteStatus.FAILED;
        });
        if (status == TaskStatus.SUCCEEDED) assertThat(context.requireReady()).isEqualTo(CONTEXT);
        else assertThatThrownBy(context::requireReady).isInstanceOf(PrerequisiteNotSatisfiedException.class);
        verify(builds).findLatest(project, source, ENV, 7L, BuildValidationScope.SOURCE_ONLY);
        verifyNoMoreInteractions(builds);
    }

    @Test void missingInputsDoNotQueryOrInventBuilds() {
        var empty = service().current(project);
        assertThat(empty.prerequisite().reasonCode()).isEqualTo("ACTIVE_SOURCE_MISSING");
        assertThat(service().resolve(project, source).prerequisite().reasonCode()).isEqualTo("SOURCE_ENVIRONMENT_MISSING");
        verifyNoInteractions(builds);
        runtime();
        assertThat(service().resolve(project, null).ready()).isFalse();
        assertThat(service().resolve(project, source).prerequisite().reasonCode()).isEqualTo("X86_BUILD_MISSING");
        assertThat(service().latest(empty)).isEmpty();
        verifyNoInteractions(scans);
    }

    @Test void validatesBuildOwnershipScopeAndVersionEvenIfRepositoryReturnsInvalidData() {
        var bad = List.of(
            build(UUID.randomUUID(), source, ENV, 7L, BUILD, TaskStatus.SUCCEEDED, BuildValidationScope.SOURCE_ONLY),
            build(project, UUID.randomUUID(), ENV, 7L, BUILD, TaskStatus.SUCCEEDED, BuildValidationScope.SOURCE_ONLY),
            build(project, source, UUID.randomUUID(), 7L, BUILD, TaskStatus.SUCCEEDED, BuildValidationScope.SOURCE_ONLY),
            build(project, source, ENV, 8L, BUILD, TaskStatus.SUCCEEDED, BuildValidationScope.SOURCE_ONLY),
            build(project, source, ENV, null, BUILD, TaskStatus.SUCCEEDED, BuildValidationScope.SOURCE_ONLY),
            build(project, source, ENV, 7L, BUILD, TaskStatus.SUCCEEDED, BuildValidationScope.BOTH));
        for (var build : bad) {
            var selection = new KbScanContextService.Selection(project, source, ENV, 7L, build);
            assertThat(selection.ready()).isFalse();
            assertThatThrownBy(selection::requireReady).isInstanceOf(PrerequisiteNotSatisfiedException.class);
        }
    }

    @Test void freshnessDistinguishesUnknownSourceEnvironmentAndBuildChanges() {
        var context = ready(project, source);
        for (KbExecutionContext binding : Arrays.asList(null, new KbExecutionContext(null, null, null),
                new KbExecutionContext(ENV, null, BUILD), new KbExecutionContext(ENV, 7L, null),
                new KbExecutionContext(ENV, -1L, BUILD))) {
            assertThat(context.staleReason(scan(project, source, TaskStatus.SUCCEEDED, binding))).isEqualTo("KB_SCAN_CONTEXT_UNKNOWN");
        }
        assertThat(new KbExecutionContext(ENV, 0L, BUILD).complete()).isTrue();
        assertThat(context.staleReason(scan(UUID.randomUUID(), source, TaskStatus.SUCCEEDED, CONTEXT))).isEqualTo("KB_SCAN_SOURCE_CHANGED");
        assertThat(context.staleReason(scan(project, UUID.randomUUID(), TaskStatus.SUCCEEDED, CONTEXT))).isEqualTo("KB_SCAN_SOURCE_CHANGED");
        for (var binding : List.of(new KbExecutionContext(UUID.randomUUID(), 7L, BUILD), new KbExecutionContext(ENV, 8L, BUILD)))
            assertThat(context.staleReason(scan(project, source, TaskStatus.SUCCEEDED, binding))).isEqualTo("KB_SCAN_ENVIRONMENT_CHANGED");
        assertThat(context.staleReason(scan(project, source, TaskStatus.SUCCEEDED, new KbExecutionContext(ENV, 7L, UUID.randomUUID()))))
                .isEqualTo("KB_SCAN_BUILD_CHANGED");
        assertThat(new KbScanContextService.Selection(project, source, ENV, 7L, null)
                .staleReason(scan(project, source, TaskStatus.SUCCEEDED, CONTEXT))).isEqualTo("KB_SCAN_BUILD_NOT_READY");
        assertThat(context.staleReason(scan(project, source, TaskStatus.FAILED, CONTEXT))).isNull();
    }

    @Test void currentSelectionRequiresActiveSourceAndPreservesCurrentFailure() {
        runtime(); activeSource();
        when(builds.findLatest(project, source, ENV, 7L, BuildValidationScope.SOURCE_ONLY)).thenReturn(Optional.of(build(project, source)));
        var failed = scan(project, source, TaskStatus.FAILED, CONTEXT);
        when(scans.findLatestRunByContext(project, source, CONTEXT)).thenReturn(Optional.of(failed));
        assertThat(service().currentRun(project, source)).contains(failed);
        assertThat(service().currentRun(project, UUID.randomUUID())).isEmpty();
        assertThat(service().current(project).snapshot()).isEqualTo(new ProjectContextSnapshot(source, 7L));
    }

    @Test void executionUsesPinnedSourceAndRejectsOldPayloadOrChangedContext() throws Exception {
        runtime();
        var build = build(project, source);
        when(builds.findLatest(project, source, ENV, 7L, BuildValidationScope.SOURCE_ONLY)).thenReturn(Optional.of(build));
        var run = scan(project, source, TaskStatus.QUEUED, CONTEXT);
        assertThat(service().requireExecution(run, input(run, CONTEXT, project, source))).isEqualTo(build);
        verifyNoInteractions(sources); // active source switching must not redirect a queued historical scan
        var legacy = new ObjectMapper().readValue("{\"projectId\":\"" + project + "\",\"sourceRevisionId\":\"" + source + "\"}", KbScanInput.class);
        assertThatThrownBy(() -> service().requireExecution(run, legacy)).isInstanceOf(PrerequisiteNotSatisfiedException.class);
        assertThatThrownBy(() -> service().requireExecution(run, input(run, CONTEXT, UUID.randomUUID(), source)))
                .isInstanceOf(PrerequisiteNotSatisfiedException.class);
        assertThatThrownBy(() -> service().requireExecution(run, input(run, CONTEXT, project, UUID.randomUUID())))
                .isInstanceOf(PrerequisiteNotSatisfiedException.class);
        var old = scan(project, source, TaskStatus.QUEUED, null);
        assertThatThrownBy(() -> service().requireExecution(old, input(old, null, project, source)))
                .isInstanceOf(PrerequisiteNotSatisfiedException.class);
        when(builds.findLatest(project, source, ENV, 7L, BuildValidationScope.SOURCE_ONLY)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().requireExecution(run, input(run, CONTEXT, project, source)))
                .isInstanceOf(PrerequisiteNotSatisfiedException.class);
        verify(scans, times(6)).lockContext(project);
    }
}
