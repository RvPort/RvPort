package cn.rvport.backend.archive;

import cn.rvport.backend.analysis.SpecializedAnalysisRunView;
import cn.rvport.backend.analysis.SpecializedAnalyzerType;
import cn.rvport.backend.artifact.StoredArtifactView;
import cn.rvport.backend.automatedtest.AutomatedTestRunView;
import cn.rvport.backend.buildvalidation.BuildValidationRunView;
import cn.rvport.backend.dependency.DependencyAnalysisView;
import cn.rvport.backend.kb.KbScanView;
import cn.rvport.backend.report.ReportView;
import cn.rvport.backend.reportcomparison.ReportComparisonRunView;
import cn.rvport.backend.source.RecheckRunView;
import cn.rvport.backend.source.SourceRepository;
import cn.rvport.backend.testcomparison.TestComparisonRunView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ArchiveCandidateRegistrar {

    private final ArchiveCandidateService candidateService;
    private final SourceRepository sourceRepository;

    public ArchiveCandidateRegistrar(ArchiveCandidateService candidateService, SourceRepository sourceRepository) {
        this.candidateService = candidateService;
        this.sourceRepository = sourceRepository;
    }

    public void registerMigrationReport(ReportView report, Map<String, Object> summary, List<StoredArtifactView> artifacts) {
        candidateService.upsert(new ArchiveCandidateUpsertCommand(
                report.projectId(),
                report.sourceRevisionId(),
                ArchiveNodeType.MIGRATION_REPORT,
                ArchiveCandidateType.REPORT,
                "migration-report",
                "MIGRATION_REPORT",
                report.id(),
                report.title(),
                migrationReportArchiveSummary(report, summary, artifacts),
                artifactRefs("migration-report", artifacts)
        ));
    }

    public void registerReportComparison(ReportComparisonRunView run, UUID sourceRevisionId, Map<String, Object> summary) {
        candidateService.upsert(new ArchiveCandidateUpsertCommand(
                run.projectId(),
                sourceRevisionId,
                ArchiveNodeType.REPORT_COMPARISON,
                ArchiveCandidateType.COMPARISON_REPORT,
                "report-comparison",
                "REPORT_COMPARE",
                run.id(),
                "评估报告对比",
                merge(summary, Map.of(
                        "comparisonRunId", run.id().toString(),
                        "baseReportId", run.baseReportId().toString(),
                        "targetReportId", run.targetReportId().toString(),
                        "status", run.status().name()
                )),
                List.of()
        ));
    }

    public void registerKbScan(KbScanView run, Map<String, Object> summary, List<StoredArtifactView> artifacts) {
        candidateService.upsert(new ArchiveCandidateUpsertCommand(
                run.projectId(),
                run.sourceRevisionId(),
                ArchiveNodeType.KB_SCAN,
                ArchiveCandidateType.REPORT,
                "kb-scan",
                "KB_SCAN",
                run.id(),
                "知识库扫描",
                merge(summary, Map.of("kbScanRunId", run.id().toString(), "kbVersion", safe(run.kbVersion()))),
                artifactRefs("kb-scan", artifacts)
        ));
    }

    public void registerKbMigration(cn.rvport.backend.kb.migration.KbMigrationRunView run, Map<String, Object> summary,
            List<StoredArtifactView> artifacts) {
        candidateService.upsert(new ArchiveCandidateUpsertCommand(
                run.projectId(),
                run.sourceRevisionId(),
                ArchiveNodeType.KB_MIGRATION,
                ArchiveCandidateType.REPORT,
                "kb-migration",
                "KB_MIGRATION",
                run.id(),
                "KB 迁移",
                merge(summary, Map.of("kbMigrationRunId", run.id().toString(), "status", run.status())),
                artifactRefs("kb-migration", artifacts)
        ));
    }

    public void registerDependencyAnalysis(DependencyAnalysisView run, Map<String, Object> summary, List<StoredArtifactView> artifacts) {
        candidateService.upsert(new ArchiveCandidateUpsertCommand(
                run.projectId(),
                run.sourceRevisionId(),
                ArchiveNodeType.DEPENDENCY_ANALYSIS,
                ArchiveCandidateType.REPORT,
                "dependency-analysis",
                "DEPENDENCY_ANALYSIS",
                run.id(),
                "依赖分析",
                merge(summary, Map.of(
                        "dependencyAnalysisRunId", run.id().toString(),
                        "status", run.status().name()
                )),
                artifactRefs("dependency-analysis", artifacts)
        ));
    }

    public void registerRecheck(RecheckRunView run, Map<String, Object> summary) {
        candidateService.upsert(new ArchiveCandidateUpsertCommand(
                run.projectId(),
                run.targetSourceRevisionId(),
                ArchiveNodeType.RECHECK,
                ArchiveCandidateType.DIFF_REPORT,
                "recheck-diff",
                "RECHECK_DIFF",
                run.id(),
                "复检差异",
                merge(summary, Map.of(
                        "recheckRunId", run.id().toString(),
                        "baseSourceRevisionId", run.baseSourceRevisionId().toString(),
                        "targetSourceRevisionId", run.targetSourceRevisionId().toString()
                )),
                List.of()
        ));
    }

    public void registerBuildValidation(BuildValidationRunView run, Map<String, Object> summary) {
        registerBuildValidation(run, summary, List.of());
    }

    public void registerBuildValidation(
            BuildValidationRunView run,
            Map<String, Object> summary,
            List<StoredArtifactView> artifacts
    ) {
        candidateService.upsert(new ArchiveCandidateUpsertCommand(
                run.projectId(),
                run.sourceRevisionId(),
                ArchiveNodeType.BUILD_VALIDATION,
                ArchiveCandidateType.BUILD_SUMMARY,
                "build-validation-" + run.environmentId(),
                "BUILD_VALIDATION",
                run.id(),
                "构建验证 - " + run.environmentId(),
                merge(summary, Map.of("buildRunId", run.id().toString(), "status", run.status().name())),
                artifactRefs("build-validation", artifacts)
        ));
    }

    public void registerAutomatedTest(AutomatedTestRunView run, Map<String, Object> summary, List<StoredArtifactView> artifacts) {
        candidateService.upsert(new ArchiveCandidateUpsertCommand(
                run.projectId(),
                run.sourceRevisionId(),
                ArchiveNodeType.AUTOMATED_TEST,
                ArchiveCandidateType.TEST_SUMMARY,
                "automated-test-" + run.environmentId(),
                "AUTOMATED_TEST",
                run.id(),
                "自动化测试 - " + run.environmentId(),
                merge(summary, Map.of("testRunId", run.id().toString(), "status", run.status().name())),
                artifactRefs("automated-test", artifacts)
        ));
    }

    public void registerTestComparison(TestComparisonRunView run, UUID sourceRevisionId, Map<String, Object> summary, StoredArtifactView artifact) {
        candidateService.upsert(new ArchiveCandidateUpsertCommand(
                run.projectId(),
                sourceRevisionId,
                ArchiveNodeType.TEST_COMPARISON,
                ArchiveCandidateType.COMPARISON_REPORT,
                "test-comparison",
                "TEST_COMPARISON",
                run.id(),
                "测试结果比较",
                merge(summary, Map.of(
                        "comparisonRunId", run.id().toString(),
                        "status", run.status().name(),
                        "riskLevel", run.riskLevel() == null ? "" : run.riskLevel().name()
                )),
                artifact == null ? List.of() : artifactRefs("test-comparison", List.of(artifact))
        ));
    }

    public void registerSpecializedAnalysis(
            SpecializedAnalysisRunView run,
            Map<String, Object> summary,
            List<StoredArtifactView> artifacts
    ) {
        boolean floatingPoint = run.analyzerType() == SpecializedAnalyzerType.FLOATING_POINT;
        candidateService.upsert(new ArchiveCandidateUpsertCommand(
                run.projectId(),
                run.sourceRevisionId(),
                floatingPoint ? ArchiveNodeType.FLOATING_POINT_ANALYSIS : ArchiveNodeType.MEMORY_ORDER_ANALYSIS,
                ArchiveCandidateType.REPORT,
                floatingPoint ? "floating-point-analysis" : "memory-order-analysis",
                floatingPoint ? "FLOATING_POINT_ANALYSIS" : "MEMORY_ORDER_ANALYSIS",
                run.id(),
                floatingPoint ? "浮点精度分析" : "强弱内存序分析",
                merge(summary, Map.of(
                        "analysisRunId", run.id().toString(),
                        "analyzerType", run.analyzerType().name(),
                        "status", run.status().name()
                )),
                artifactRefs(floatingPoint ? "floating-point-analysis" : "memory-order-analysis", artifacts)
        ));
    }

    private List<ArchiveArtifactRef> artifactRefs(String prefix, List<StoredArtifactView> artifacts) {
        return artifacts == null ? List.of() : artifacts.stream()
                .map(artifact -> new ArchiveArtifactRef(
                        format(artifact),
                        artifact.id(),
                        artifact.artifactType(),
                        artifact.contentType(),
                        prefix + "-" + artifact.artifactType().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-") + "." + extension(artifact)
                ))
                .toList();
    }

    private String format(StoredArtifactView artifact) {
        String type = artifact.artifactType() == null ? "" : artifact.artifactType().toUpperCase(Locale.ROOT);
        if (type.contains("HTML")) {
            return "HTML";
        }
        if (type.contains("LOG")) {
            return "LOG";
        }
        if (type.contains("BUNDLE") || type.contains("TGZ")) {
            return "BINARY";
        }
        return "JSON";
    }

    private String extension(StoredArtifactView artifact) {
        String contentType = artifact.contentType() == null ? "" : artifact.contentType().toLowerCase(Locale.ROOT);
        if (contentType.contains("html")) {
            return "html";
        }
        if (contentType.contains("plain")) {
            return "log";
        }
        if (contentType.contains("gzip") || contentType.contains("tar")) {
            return "tgz";
        }
        return "json";
    }

    private Map<String, Object> merge(Map<String, Object> base, Map<String, Object> extra) {
        Map<String, Object> value = new LinkedHashMap<>();
        if (base != null) {
            value.putAll(base);
        }
        value.putAll(extra);
        return value;
    }

    private Map<String, Object> migrationReportArchiveSummary(
            ReportView report,
            Map<String, Object> summary,
            List<StoredArtifactView> artifacts
    ) {
        Map<String, Object> safeSummary = new LinkedHashMap<>();
        if (summary != null) {
            copyIfPresent(summary, safeSummary, "schemaVersion");
            copyIfPresent(summary, safeSummary, "confirmedInputCount");
            copyIfPresent(summary, safeSummary, "highRiskIssueCount");
            copyIfPresent(summary, safeSummary, "estimatedWorkload");
            copyIfPresent(summary, safeSummary, "sourceTypeCounts");
            copyIfPresent(summary, safeSummary, "detectedLevelCounts");
            copyIfPresent(summary, safeSummary, "dependencyAnalysisInputCount");
            copyIfPresent(summary, safeSummary, "dependencyRiskCounts");
            copyIfPresent(summary, safeSummary, "dependencyTargetMatchStatusCounts");
            copyIfPresent(summary, safeSummary, "generatedAt");
        }
        safeSummary.put("riskLevel", safe(report.riskLevel()));
        safeSummary.put("reportId", report.id().toString());
        safeSummary.put("artifactCount", artifacts == null ? 0 : artifacts.size());
        safeSummary.put("artifactFormats", artifacts == null ? List.of() : artifacts.stream()
                .map(this::format)
                .distinct()
                .toList());
        return safeSummary;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
