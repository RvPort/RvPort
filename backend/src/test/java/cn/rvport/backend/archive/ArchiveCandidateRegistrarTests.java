package cn.rvport.backend.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.analysis.SpecializedAnalysisRunView;
import cn.rvport.backend.analysis.SpecializedAnalysisStatus;
import cn.rvport.backend.analysis.SpecializedAnalyzerType;
import cn.rvport.backend.artifact.StoredArtifactOwnerType;
import cn.rvport.backend.artifact.StoredArtifactView;
import cn.rvport.backend.automatedtest.AutomatedTestRunView;
import cn.rvport.backend.buildvalidation.BuildValidationRunView;
import cn.rvport.backend.buildvalidation.BuildValidationScope;
import cn.rvport.backend.dependency.DependencyAnalysisView;
import cn.rvport.backend.kb.KbScanView;
import cn.rvport.backend.report.ReportType;
import cn.rvport.backend.report.ReportView;
import cn.rvport.backend.reportcomparison.ReportComparisonRunView;
import cn.rvport.backend.source.RecheckRunView;
import cn.rvport.backend.source.SourceRepository;
import cn.rvport.backend.source.SourceRevisionType;
import cn.rvport.backend.source.SourceRevisionView;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.testcomparison.TestComparisonOverallStatus;
import cn.rvport.backend.testcomparison.TestComparisonRiskLevel;
import cn.rvport.backend.testcomparison.TestComparisonRunView;
import cn.rvport.backend.testcomparison.TestComparisonStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ArchiveCandidateRegistrarTests {

    @Mock
    private ArchiveCandidateService candidateService;

    @Mock
    private SourceRepository sourceRepository;

    @Test
    void migrationReportCandidateUsesSafeAggregateSummaryOnly() {
        UUID projectId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        ReportView report = new ReportView(
                reportId,
                projectId,
                UUID.randomUUID(),
                sourceRevisionId,
                ReportType.EVALUATION,
                TaskStatus.SUCCEEDED,
                "迁移报告",
                Map.of(),
                "HIGH",
                UUID.randomUUID(),
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
        Map<String, Object> fullSummary = Map.of(
                "schemaVersion", "migration-report/v1",
                "confirmedInputCount", 2,
                "highRiskIssueCount", 1,
                "sourceTypeCounts", Map.of("KB_FINDING", 1),
                "detectedLevelCounts", Map.of("HIGH", 1),
                "priorityIssues", List.of(Map.of("locationLabel", "include:../include")),
                "dependencySubstitutionNotes", List.of(Map.of("evidence", "raw evidence"))
        );
        StoredArtifactView artifact = new StoredArtifactView(
                UUID.randomUUID(),
                projectId,
                report.taskId(),
                StoredArtifactOwnerType.MIGRATION_REPORT,
                reportId,
                "MIGRATION_REPORT_HTML",
                "bucket",
                "object-key",
                "text/html",
                100,
                "sha256",
                Map.of(),
                OffsetDateTime.now()
        );
        when(candidateService.upsert(any())).thenAnswer(invocation -> null);

        new ArchiveCandidateRegistrar(candidateService, sourceRepository)
                .registerMigrationReport(report, fullSummary, List.of(artifact));

        ArgumentCaptor<ArchiveCandidateUpsertCommand> captor = ArgumentCaptor.forClass(ArchiveCandidateUpsertCommand.class);
        verify(candidateService).upsert(captor.capture());
        Map<String, ?> summary = captor.getValue().summary();
        assertThat(summary.get("reportId")).isEqualTo(reportId.toString());
        assertThat(summary.get("riskLevel")).isEqualTo("HIGH");
        assertThat(summary.get("confirmedInputCount")).isEqualTo(2);
        assertThat(summary.get("highRiskIssueCount")).isEqualTo(1);
        assertThat(summary.get("artifactCount")).isEqualTo(1);
        assertThat(summary).doesNotContainKeys("priorityIssues", "dependencySubstitutionNotes");
        assertThat(summary.toString()).doesNotContain("../include").doesNotContain("raw evidence").doesNotContain("object-key").doesNotContain("bucket");
    }

    @Test
    void registersCoreNodeCandidatesWithStableKeysAndMergedSummaries() {
        UUID projectId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        ArchiveCandidateRegistrar registrar = new ArchiveCandidateRegistrar(candidateService, sourceRepository);
        when(candidateService.upsert(any())).thenReturn(null);

        ReportComparisonRunView comparison = new ReportComparisonRunView(
                UUID.randomUUID(), projectId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                TaskStatus.SUCCEEDED, Map.of(), null, null, now, now, now, now, cn.rvport.backend.reportcomparison.ReportComparisonBasis.REPORT_FACTS, null, null);
        registrar.registerReportComparison(comparison, revisionId, Map.of("changed", 2));

        KbScanView kb = new KbScanView(
                UUID.randomUUID(), projectId, UUID.randomUUID(), revisionId, null, Map.of(), TaskStatus.SUCCEEDED,
                Map.of(), now, now);
        registrar.registerKbScan(kb, null, null);

        DependencyAnalysisView dependency = new DependencyAnalysisView(
                UUID.randomUUID(), projectId, UUID.randomUUID(), revisionId, UUID.randomUUID(), UUID.randomUUID(),
                1L, TaskStatus.SUCCEEDED, Map.of(), List.of(), now, now, now);
        registrar.registerDependencyAnalysis(dependency, Map.of("total", 3), List.of(artifact(projectId, "DEPENDENCY_REPORT_HTML", "text/html")));

        RecheckRunView recheck = new RecheckRunView(
                UUID.randomUUID(), projectId, UUID.randomUUID(), UUID.randomUUID(), revisionId, UUID.randomUUID(),
                UUID.randomUUID(), Map.of(), TaskStatus.SUCCEEDED, now);
        registrar.registerRecheck(recheck, Map.of("fixed", 1));

        BuildValidationRunView build = new BuildValidationRunView(
                UUID.randomUUID(), projectId, UUID.randomUUID(), revisionId, UUID.randomUUID(), BuildValidationScope.BOTH,
                UUID.randomUUID(), 1L, UUID.randomUUID(), "sha", "make", "/work", 60, 0,
                TaskStatus.SUCCEEDED, null, null, null, null, now, now, now, Map.of(), Map.of());
        registrar.registerBuildValidation(build, Map.of("exitCode", 0));

        ArgumentCaptor<ArchiveCandidateUpsertCommand> captor = ArgumentCaptor.forClass(ArchiveCandidateUpsertCommand.class);
        verify(candidateService, org.mockito.Mockito.times(5)).upsert(captor.capture());
        assertThat(captor.getAllValues()).extracting(ArchiveCandidateUpsertCommand::candidateKey)
                .containsExactly("report-comparison", "kb-scan", "dependency-analysis", "recheck-diff",
                        "build-validation-" + build.environmentId());
        assertThat(captor.getAllValues().get(1).summary().get("kbVersion")).isEqualTo("");
        assertThat(captor.getAllValues().get(2).artifactRefs()).singleElement().satisfies(ref -> {
            assertThat(ref.format()).isEqualTo("HTML");
            assertThat(ref.filename()).endsWith(".html");
        });
    }

    @Test
    void artifactReferenceDefaultsUnknownTypesToJsonAndNormalizesFilename() {
        UUID projectId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        KbScanView kb = new KbScanView(
                UUID.randomUUID(), projectId, UUID.randomUUID(), UUID.randomUUID(), "v2", Map.of(),
                TaskStatus.SUCCEEDED, Map.of(), now, now);
        StoredArtifactView artifact = new StoredArtifactView(
                UUID.randomUUID(), projectId, kb.taskId(), StoredArtifactOwnerType.KB_SCAN, kb.id(),
                "BINARY OUTPUT", "bucket", "key", null, 1, "sha", Map.of(), now);
        when(candidateService.upsert(any())).thenReturn(null);

        new ArchiveCandidateRegistrar(candidateService, sourceRepository).registerKbScan(kb, Map.of(), List.of(artifact));

        ArgumentCaptor<ArchiveCandidateUpsertCommand> captor = ArgumentCaptor.forClass(ArchiveCandidateUpsertCommand.class);
        verify(candidateService).upsert(captor.capture());
        assertThat(captor.getValue().artifactRefs()).singleElement().satisfies(ref -> {
            assertThat(ref.format()).isEqualTo("JSON");
            assertThat(ref.filename()).isEqualTo("kb-scan-binary-output.json");
        });
    }

    @Test
    void registersOptionalTestComparisonRiskAndArtifactStates() {
        UUID projectId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        TestComparisonRunView withoutRisk = comparison(projectId, null);
        TestComparisonRunView withRisk = comparison(projectId, TestComparisonRiskLevel.HIGH);
        ArchiveCandidateRegistrar registrar = new ArchiveCandidateRegistrar(candidateService, sourceRepository);

        registrar.registerTestComparison(withoutRisk, revisionId, Map.of(), null);
        registrar.registerTestComparison(withRisk, revisionId, Map.of(), artifact(projectId, "RESULT_JSON", "application/json"));

        ArgumentCaptor<ArchiveCandidateUpsertCommand> captor = ArgumentCaptor.forClass(ArchiveCandidateUpsertCommand.class);
        verify(candidateService, org.mockito.Mockito.times(2)).upsert(captor.capture());
        assertThat(captor.getAllValues().get(0).summary().get("riskLevel")).isEqualTo("");
        assertThat(captor.getAllValues().get(0).artifactRefs()).isEmpty();
        assertThat(captor.getAllValues().get(1).summary().get("riskLevel")).isEqualTo("HIGH");
        assertThat(captor.getAllValues().get(1).artifactRefs()).hasSize(1);
    }

    @Test
    void migrationReportAllowsMissingSummaryArtifactsAndRisk() {
        UUID projectId = UUID.randomUUID();
        ReportView report = new ReportView(UUID.randomUUID(), projectId, UUID.randomUUID(), UUID.randomUUID(),
                ReportType.EVALUATION, TaskStatus.SUCCEEDED, "Report", Map.of(), null, null,
                OffsetDateTime.now(), OffsetDateTime.now());

        new ArchiveCandidateRegistrar(candidateService, sourceRepository)
                .registerMigrationReport(report, null, null);

        ArgumentCaptor<ArchiveCandidateUpsertCommand> captor = ArgumentCaptor.forClass(ArchiveCandidateUpsertCommand.class);
        verify(candidateService).upsert(captor.capture());
        assertThat(captor.getValue().summary().get("riskLevel")).isEqualTo("");
        assertThat(captor.getValue().summary().get("artifactCount")).isEqualTo(0);
        assertThat(captor.getValue().summary().get("artifactFormats")).isEqualTo(List.of());
        assertThat(captor.getValue().artifactRefs()).isEmpty();
    }

    @Test
    void formatTreatsMissingArtifactTypeAsJson() {
        StoredArtifactView artifact = artifact(UUID.randomUUID(), null, "application/json");
        String format = ReflectionTestUtils.invokeMethod(
                new ArchiveCandidateRegistrar(candidateService, sourceRepository), "format", artifact);
        assertThat(format).isEqualTo("JSON");
    }

    @Test
    void bitcodeBundlesUseBinaryTgzArchiveMetadata() {
        ArchiveCandidateRegistrar registrar = new ArchiveCandidateRegistrar(candidateService, sourceRepository);
        StoredArtifactView bundle = artifact(UUID.randomUUID(), "LLVM_BITCODE_BUNDLE_TGZ", "application/gzip");
        StoredArtifactView tar = artifact(UUID.randomUUID(), "LLVM_BITCODE_BUNDLE", "application/x-tar");

        assertThat((String) ReflectionTestUtils.invokeMethod(registrar, "format", bundle)).isEqualTo("BINARY");
        assertThat((String) ReflectionTestUtils.invokeMethod(registrar, "format", tar)).isEqualTo("BINARY");
        assertThat((String) ReflectionTestUtils.invokeMethod(registrar, "extension", bundle)).isEqualTo("tgz");
        assertThat((String) ReflectionTestUtils.invokeMethod(registrar, "extension", tar)).isEqualTo("tgz");
    }

    @Test
    void registersAutomatedTestWithArtifacts() {
        UUID projectId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        AutomatedTestRunView testRun = new AutomatedTestRunView(
                UUID.randomUUID(), projectId, UUID.randomUUID(), UUID.randomUUID(), revisionId, environmentId,
                1L, UUID.randomUUID(), null, "environment", "make test", null, 60, 0,
                1, 0, 0, TaskStatus.SUCCEEDED, null, null, null, null, List.of(), null, null);
        OffsetDateTime now = OffsetDateTime.now();
        ArchiveCandidateRegistrar registrar = new ArchiveCandidateRegistrar(candidateService, sourceRepository);

        registrar.registerAutomatedTest(testRun, null,
                List.of(artifact(projectId, "TEST_LOG", "text/plain")));

        ArgumentCaptor<ArchiveCandidateUpsertCommand> captor = ArgumentCaptor.forClass(ArchiveCandidateUpsertCommand.class);
        verify(candidateService).upsert(captor.capture());
        assertThat(captor.getAllValues().get(0).candidateKey()).isEqualTo("automated-test-" + environmentId);
        assertThat(captor.getAllValues().get(0).artifactRefs()).singleElement()
                .satisfies(ref -> assertThat(ref.filename()).endsWith(".log"));

    }

    @Test
    void specializedAnalysisUsesAnalyzerSpecificArchiveMetadata() {
        UUID projectId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        SpecializedAnalysisRunView floating = specializedRun(
                projectId, revisionId, SpecializedAnalyzerType.FLOATING_POINT);
        SpecializedAnalysisRunView memory = specializedRun(
                projectId, revisionId, SpecializedAnalyzerType.MEMORY_ORDER);
        ArchiveCandidateRegistrar registrar = new ArchiveCandidateRegistrar(candidateService, sourceRepository);

        registrar.registerSpecializedAnalysis(floating, null,
                List.of(artifact(projectId, "FLOATING_REPORT_HTML", "text/html")));
        registrar.registerSpecializedAnalysis(memory, Map.of("findingCount", 3), List.of());

        ArgumentCaptor<ArchiveCandidateUpsertCommand> captor = ArgumentCaptor.forClass(ArchiveCandidateUpsertCommand.class);
        verify(candidateService, org.mockito.Mockito.times(2)).upsert(captor.capture());
        assertThat(captor.getAllValues()).extracting(
                        ArchiveCandidateUpsertCommand::nodeType,
                        ArchiveCandidateUpsertCommand::candidateKey,
                        ArchiveCandidateUpsertCommand::resourceType)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(ArchiveNodeType.FLOATING_POINT_ANALYSIS,
                                "floating-point-analysis", "FLOATING_POINT_ANALYSIS"),
                        org.assertj.core.groups.Tuple.tuple(ArchiveNodeType.MEMORY_ORDER_ANALYSIS,
                                "memory-order-analysis", "MEMORY_ORDER_ANALYSIS"));
        assertThat(captor.getAllValues().get(0).artifactRefs()).singleElement()
                .satisfies(ref -> assertThat(ref.filename()).endsWith(".html"));
        assertThat(captor.getAllValues().get(1).summary().get("findingCount")).isEqualTo(3);
    }

    private SpecializedAnalysisRunView specializedRun(
            UUID projectId,
            UUID revisionId,
            SpecializedAnalyzerType type
    ) {
        OffsetDateTime now = OffsetDateTime.now();
        return new SpecializedAnalysisRunView(
                UUID.randomUUID(), projectId, UUID.randomUUID(), type, revisionId, UUID.randomUUID(),
                "artifact-set", 1L, "tool", "request", "result", "policy",
                SpecializedAnalysisStatus.COMPLETE, "PASS", 0, Map.of(), null, null, null, null,
                0, 0, 0, UUID.randomUUID(), now, now, now, now);
    }

    private TestComparisonRunView comparison(UUID projectId, TestComparisonRiskLevel risk) {
        OffsetDateTime now = OffsetDateTime.now();
        return new TestComparisonRunView(UUID.randomUUID(), projectId, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), TestComparisonStatus.SUCCEEDED,
                TestComparisonOverallStatus.EQUIVALENT, risk, BigDecimal.ONE, "summary", "recommendation",
                null, null, null, null, false, Map.of(), Map.of(), Map.of(), null, now, now, now, now);
    }

    private StoredArtifactView artifact(UUID projectId, String type, String contentType) {
        return new StoredArtifactView(
                UUID.randomUUID(), projectId, UUID.randomUUID(), StoredArtifactOwnerType.DEPENDENCY_ANALYSIS,
                UUID.randomUUID(), type, "bucket", "key", contentType, 1, "sha", Map.of(), OffsetDateTime.now());
    }
}
