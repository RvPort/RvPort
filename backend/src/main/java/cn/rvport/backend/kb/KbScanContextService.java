package cn.rvport.backend.kb;

import cn.rvport.backend.buildvalidation.BuildValidationRepository;
import cn.rvport.backend.buildvalidation.BuildValidationRunView;
import cn.rvport.backend.buildvalidation.BuildValidationScope;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentRepository;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentView;
import cn.rvport.backend.source.SourceRepository;
import cn.rvport.backend.source.SourceRevisionView;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.workflow.PrerequisiteNotSatisfiedException;
import cn.rvport.backend.workflow.ProjectContextSnapshot;
import cn.rvport.backend.workflow.StagePrerequisiteResponse;
import cn.rvport.backend.workflow.StagePrerequisiteStatus;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KbScanContextService {
    private final SourceRepository sources;
    private final ProjectRuntimeEnvironmentRepository runtimes;
    private final BuildValidationRepository builds;
    private final KbScanRepository scans;

    public KbScanContextService(SourceRepository sources, ProjectRuntimeEnvironmentRepository runtimes,
            BuildValidationRepository builds, KbScanRepository scans) {
        this.sources = sources;
        this.runtimes = runtimes;
        this.builds = builds;
        this.scans = scans;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Selection current(UUID projectId) {
        return resolve(projectId, sources.findActive(projectId).map(SourceRevisionView::id).orElse(null));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Selection resolve(UUID projectId, UUID sourceRevisionId) {
        ProjectRuntimeEnvironmentView runtime = runtimes.find(projectId)
                .orElseGet(() -> ProjectRuntimeEnvironmentView.empty(projectId));
        BuildValidationRunView build = sourceRevisionId == null || runtime.sourceEnvironmentId() == null ? null
                : builds.findLatest(projectId, sourceRevisionId, runtime.sourceEnvironmentId(), runtime.version(),
                        BuildValidationScope.SOURCE_ONLY).orElse(null);
        return new Selection(projectId, sourceRevisionId, runtime.sourceEnvironmentId(), runtime.version(), build);
    }

    public void lock(UUID projectId) {
        scans.lockContext(projectId);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<KbScanView> currentRun(UUID projectId, UUID sourceRevisionId) {
        Selection selection = current(projectId);
        return Objects.equals(selection.sourceRevisionId(), sourceRevisionId) ? latest(selection) : Optional.empty();
    }

    public Optional<KbScanView> latest(Selection selection) {
        return selection.sourceRevisionId() != null ? scans.findLatestRunByContext(selection.projectId(), selection.sourceRevisionId(),
                selection.executionContext()) : Optional.empty();
    }

    @Transactional
    public BuildValidationRunView requireExecution(KbScanView run, KbScanInput input) {
        lock(run.projectId());
        Selection selected = resolve(run.projectId(), run.sourceRevisionId());
        String reason = selected.staleReason(run);
        if (reason != null || !Objects.equals(run.executionContext(), input.executionContext())
                || !Objects.equals(run.sourceRevisionId(), input.sourceRevisionId())
                || !Objects.equals(run.projectId(), input.projectId())) {
            throw new PrerequisiteNotSatisfiedException("kb", StagePrerequisiteResponse.of("X86_BUILD",
                    StagePrerequisiteStatus.STALE, run.executionContext() == null ? null : run.executionContext().buildRunId(),
                    reason == null ? "KB_SCAN_INPUT_CONTEXT_MISMATCH" : reason));
        }
        return run.sourceOnly() ? null : selected.build();
    }

    public record Selection(UUID projectId, UUID sourceRevisionId, UUID sourceEnvironmentId,
            long runtimeEnvironmentVersion, BuildValidationRunView build) {
        public ProjectContextSnapshot snapshot() {
            return new ProjectContextSnapshot(sourceRevisionId, runtimeEnvironmentVersion);
        }

        public boolean ready() {
            return sourceRevisionId != null && sourceEnvironmentId != null && build != null
                    && build.status() == TaskStatus.SUCCEEDED && build.buildScope() == BuildValidationScope.SOURCE_ONLY
                    && Objects.equals(projectId, build.projectId())
                    && Objects.equals(sourceRevisionId, build.sourceRevisionId())
                    && Objects.equals(sourceEnvironmentId, build.environmentId())
                    && Objects.equals(runtimeEnvironmentVersion, build.environmentConfigVersion());
        }

        public KbExecutionContext executionContext() {
            return new KbExecutionContext(sourceEnvironmentId, runtimeEnvironmentVersion, build == null ? null : build.id());
        }

        public KbExecutionContext requireReady() {
            if (!ready()) throw new PrerequisiteNotSatisfiedException("kb", prerequisite());
            return executionContext();
        }

        public StagePrerequisiteResponse prerequisite() {
            if (sourceRevisionId == null) return missing("ACTIVE_SOURCE_MISSING");
            if (sourceEnvironmentId == null) return missing("SOURCE_ENVIRONMENT_MISSING");
            if (build == null) return missing("X86_BUILD_MISSING");
            StagePrerequisiteStatus status = ready() ? StagePrerequisiteStatus.SATISFIED : switch (build.status()) {
                case PENDING, QUEUED, RUNNING -> StagePrerequisiteStatus.RUNNING;
                default -> StagePrerequisiteStatus.FAILED;
            };
            String suffix = status == StagePrerequisiteStatus.SATISFIED ? "READY"
                    : status == StagePrerequisiteStatus.RUNNING ? "RUNNING" : "FAILED";
            return StagePrerequisiteResponse.of("X86_BUILD", status, build.id(), "X86_BUILD_" + suffix);
        }

        private StagePrerequisiteResponse missing(String reason) {
            return StagePrerequisiteResponse.of("X86_BUILD", StagePrerequisiteStatus.MISSING, null, reason);
        }

        public String staleReason(KbScanView run) {
            if (run.executionContext() == null || !run.executionContext().complete()) return "KB_SCAN_CONTEXT_UNKNOWN";
            if (!Objects.equals(projectId, run.projectId()) || !Objects.equals(sourceRevisionId, run.sourceRevisionId()))
                return "KB_SCAN_SOURCE_CHANGED";
            KbExecutionContext recorded = run.executionContext();
            if (run.sourceOnly()) {
                var actual = run.effectiveInputPlan() == null ? recorded.inputPlan() : run.effectiveInputPlan();
                return Objects.equals(projectId, actual.projectId()) && Objects.equals(sourceRevisionId, actual.sourceRevisionId())
                        ? null : "KB_SCAN_INPUT_CONTEXT_MISMATCH";
            }
            if (!Objects.equals(sourceEnvironmentId, recorded.sourceEnvironmentId())
                    || !Objects.equals(runtimeEnvironmentVersion, recorded.runtimeEnvironmentVersion()))
                return "KB_SCAN_ENVIRONMENT_CHANGED";
            if (!ready()) return "KB_SCAN_BUILD_NOT_READY";
            return Objects.equals(build.id(), recorded.buildRunId()) ? null : "KB_SCAN_BUILD_CHANGED";
        }
    }
}
