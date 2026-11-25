package cn.rvport.backend.analysis;

import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.project.ProjectView;
import cn.rvport.backend.report.review.ReportSourceSnapshotService;
import cn.rvport.backend.report.review.ReportSourceMaterializer;
import cn.rvport.backend.source.SourceRevisionView;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 从归档或固定 Git 提交捕获输入；不把可变工作目录当作源码身份。 */
@Service
public class SourceAnalysisPlanService {
    private final ReportSourceSnapshotService snapshots;
    private final ReportSourceMaterializer materializer;

    public SourceAnalysisPlanService(ReportSourceSnapshotService snapshots, ReportSourceMaterializer materializer) {
        this.snapshots = snapshots;
        this.materializer = materializer;
    }

    public ReportSourceMaterializer.Materialized materialize(AnalysisInputPlan plan, SourceRevisionView source)
            throws java.io.IOException, InterruptedException {
        if (!source.projectId().equals(plan.projectId()) || !source.id().equals(plan.sourceRevisionId()))
            throw invalid("ANALYSIS_SOURCE_IDENTITY_MISMATCH");
        var fixed = materializer.prepare(source);
        if (!plan.sourceDigest().equals(fixed.sourceDigest())) {
            fixed.close();
            throw invalid("ANALYSIS_SOURCE_DIGEST_MISMATCH");
        }
        return fixed;
    }

    public AnalysisInputPlan create(ProjectView project, SourceRevisionView source, SpecializedAnalyzerType type) {
        if (!project.id().equals(source.projectId())) throw invalid("ANALYSIS_SOURCE_IDENTITY_MISMATCH");
        if (!profileSupported(project)) throw invalid("ANALYSIS_PROFILE_UNSUPPORTED");
        var manifest = snapshots.prepare(source);
        if (!source.id().equals(manifest.sourceRevisionId()) || manifest.sourceDigest() == null
                || manifest.reasonCode() != null) throw invalid("ANALYSIS_SOURCE_SNAPSHOT_UNAVAILABLE");
        var identity = AnalysisInputPlan.Identity.of("c,cpp", project.sourceArch(), project.targetArch(),
                Map.of("mode", "ALL"), List.of(), "source-discovery@1");
        return new AnalysisInputPlan(1, project.id(), source.id(), manifest.sourceDigest(), type.name(),
                type == SpecializedAnalyzerType.FLOATING_POINT ? "0.6.0" : "0.1.0",
                type == SpecializedAnalyzerType.FLOATING_POINT ? "x86_64-linux-gnu-clang21@1.0:riscv64-linux-gnu-lp64d-clang21@1.0"
                        : "bounded-default-v1:source-preserving@1",
                identity, AnalysisInputPlan.InputOrigin.SOURCE_DISCOVERY, null, List.of(),
                List.of("DEFAULT_ASSUMPTION", "C17_CPP17", "OPEN_SCOPE"),
                List.of("COMPILE_DATABASE_UNAVAILABLE", "BITCODE_UNAVAILABLE"), null);
    }

    public static boolean profileSupported(ProjectView project) {
        if (project.targetArch() == null) return false;
        var identity = AnalysisInputPlan.Identity.of("c,cpp", project.sourceArch(), project.targetArch(), Map.of(), List.of(), "profile@1");
        return !identity.architectureAssumed() && "x86".equals(identity.effectiveSourceArch())
                && List.of("riscv64", "riscv", "risc-v", "risc-v64").contains(project.targetArch().toLowerCase(java.util.Locale.ROOT));
    }

    public AnalysisInputPlan createKb(SourceRevisionView source, AnalysisInputPlan.Identity identity) {
        var manifest = snapshots.prepare(source);
        if (!source.id().equals(manifest.sourceRevisionId()) || manifest.sourceDigest() == null
                || manifest.reasonCode() != null) throw invalid("ANALYSIS_SOURCE_SNAPSHOT_UNAVAILABLE");
        return new AnalysisInputPlan(1, source.projectId(), source.id(), manifest.sourceDigest(), "KB", "0.1.0",
                "kb-source@1", identity, AnalysisInputPlan.InputOrigin.SOURCE_DISCOVERY, null, List.of(),
                identity.architectureAssumed() ? List.of("SOURCE_ARCH_DEFAULTED_TO_X86") : List.of(),
                List.of("COMPILE_DATABASE_UNAVAILABLE", "BUILD_SELECTION_UNVERIFIED"), null);
    }

    /** 仅采用创建时已选定的当前构建；缺少证明时保留源码计划，不搜索旧成功记录。 */
    public AnalysisInputPlan enhance(AnalysisInputPlan source, cn.rvport.backend.buildvalidation.BuildValidationRunView build,
            List<cn.rvport.backend.artifact.StoredArtifactView> artifacts) {
        if (build == null || artifacts.isEmpty()) return source;
        var database = artifacts.getFirst();
        var facts = database.summary();
        if (build.status() != cn.rvport.backend.task.TaskStatus.SUCCEEDED
                || build.buildScope() != cn.rvport.backend.buildvalidation.BuildValidationScope.SOURCE_ONLY
                || !source.projectId().equals(build.projectId()) || !source.sourceRevisionId().equals(build.sourceRevisionId())
                || source.identity().architectureAssumed()
                || !source.sourceDigest().equals(facts.get("sourceDigest"))
                || !source.identity().effectiveSourceArch().equals(facts.get("sourceArch"))
                || !cn.rvport.backend.buildvalidation.BuildValidationArtifacts.COMPILE_COMMANDS_JSON.equals(database.artifactType())
                || artifacts.stream().anyMatch(a -> !source.projectId().equals(a.projectId()) || !build.id().equals(a.ownerId())
                    || a.ownerType() != cn.rvport.backend.artifact.StoredArtifactOwnerType.BUILD_VALIDATION
                    || a.sha256() == null || !a.sha256().matches("[0-9a-f]{64}")))
            return unavailableEnhancement(source);
        var binding = new AnalysisInputPlan.BuildBinding(build.id(), build.projectId(), build.sourceRevisionId(),
                build.environmentId(), build.environmentConfigVersion(), source.identity().effectiveSourceArch(),
                "rvport-compile-database@1", source.identity().effectiveSourceArch(), "NOT_RECORDED");
        return new AnalysisInputPlan(1, source.projectId(), source.sourceRevisionId(), source.sourceDigest(), source.analyzer(),
                source.toolVersion(), source.profileVersion(), source.identity(), AnalysisInputPlan.InputOrigin.BUILD_ARTIFACTS,
                binding, artifacts.stream().map(a -> new AnalysisInputPlan.Artifact(a.id(), a.ownerId(), a.artifactType(), a.sha256())).toList(),
                source.assumptions().stream().filter(a -> !List.of("DEFAULT_ASSUMPTION", "C17_CPP17").contains(a)).toList(),
                artifacts.size() > 1 || "KB".equals(source.analyzer()) ? List.of() : List.of("BITCODE_UNAVAILABLE"), null);
    }

    private AnalysisInputPlan unavailableEnhancement(AnalysisInputPlan source) {
        var fallback = source.sourceFallback("ANALYSIS_ARTIFACT_IDENTITY_MISMATCH");
        // 创建时已经拒绝增强：入队的是最终请求，不能伪造一次执行时降级。
        return new AnalysisInputPlan(fallback.planVersion(), fallback.projectId(), fallback.sourceRevisionId(), fallback.sourceDigest(),
                fallback.analyzer(), fallback.toolVersion(), fallback.profileVersion(), fallback.identity(), fallback.inputOrigin(),
                null, List.of(), fallback.assumptions(), fallback.missingEvidence(), null);
    }

    private ApiException invalid(String code) {
        return new ApiException(HttpStatus.CONFLICT, code,
                "无法固定分析源码输入 / Cannot capture immutable analysis source input");
    }
}
