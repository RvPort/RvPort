package cn.rvport.backend.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.artifact.StoredArtifactOwnerType;
import cn.rvport.backend.artifact.StoredArtifactRepository;
import cn.rvport.backend.artifact.StoredArtifactResource;
import cn.rvport.backend.artifact.StoredArtifactService;
import cn.rvport.backend.artifact.StoredArtifactView;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.reportcomparison.ReportComparisonRepository;
import cn.rvport.backend.task.TaskStatus;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;

@ExtendWith(MockitoExtension.class)
class ReportServiceTests {

    @Mock private ReportRepository reportRepository;

    @Mock private ProjectAccessService projectAccessService;

    @Mock private StoredArtifactRepository storedArtifactRepository;

    @Mock private StoredArtifactService storedArtifactService;

    @Mock private ReportComparisonRepository reportComparisonRepository;

    @Mock private cn.rvport.backend.report.review.ReviewWorkspaceService reviews;

    @Test
    void reportIdEntrypointsRequireProjectAccessAndDownloadThroughObjectStorage() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();
        ReportView report = report(reportId, projectId, UUID.randomUUID(), taskId);
        StoredArtifactView artifact = artifact(artifactId, projectId, taskId, reportId, ReportDownloadFormat.HTML.artifactType(), "text/html");
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(storedArtifactRepository.findByOwner(StoredArtifactOwnerType.MIGRATION_REPORT, reportId, ReportDownloadFormat.HTML.artifactType()))
                .thenReturn(Optional.of(artifact));
        when(storedArtifactService.download(artifactId)).thenReturn(new StoredArtifactResource(artifact,
                new ByteArrayResource("<html>ok</html>".getBytes(StandardCharsets.UTF_8))));

        ReportService.DownloadableReport download = service().download(reportId, "HTML", actorId, false);

        assertThat(download.filename()).endsWith(".html");
        assertThat(download.contentType()).isEqualTo("text/html");
        verify(projectAccessService).requireProjectAccess(projectId, actorId, false);
    }

    @Test
    void rejectsInvalidQueriesAndMissingArtifactsSafely() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        when(reportRepository.count(eq(projectId), any())).thenThrow(new IllegalArgumentException("bad sort"));
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report(reportId, projectId, UUID.randomUUID(), UUID.randomUUID())));

        assertThatThrownBy(() -> service().list(projectId, new ReportListRequest(1, 20, null, null, null, null, null, "serverPath", null), actorId, false))
                .isInstanceOf(ApiException.class)
                .hasMessage("报告查询参数不正确")
                .extracting("code")
                .isEqualTo("REPORT_QUERY_INVALID");

        assertThatThrownBy(() -> service().download(reportId, "XML", actorId, false))
                .isInstanceOf(ApiException.class)
                .hasMessage("报告下载格式不正确")
                .extracting("code")
                .isEqualTo("REPORT_FORMAT_INVALID");

        assertThatThrownBy(() -> service().download(reportId, "JSON", actorId, false))
                .isInstanceOf(ApiException.class)
                .hasMessage("报告制品不存在")
                .extracting("code")
                .isEqualTo("REPORT_ARTIFACT_NOT_FOUND");
    }

    @Test
    void reportDetailExposesOnlyDownloadableReportArtifacts() {
        UUID projectId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        ReportView report = report(reportId, projectId, sourceRevisionId, UUID.randomUUID());
        StoredArtifactView html = artifact(UUID.randomUUID(), projectId, report.taskId(), reportId,
                "MIGRATION_REPORT_HTML", "text/html");
        StoredArtifactView json = artifact(UUID.randomUUID(), projectId, report.taskId(), reportId,
                "MIGRATION_REPORT_JSON", "application/json");
        StoredArtifactView sourceContext = artifact(UUID.randomUUID(), projectId, report.taskId(), reportId,
                "SOURCE_CONTEXT_file-key", "text/plain");
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(storedArtifactRepository.listByOwner(StoredArtifactOwnerType.MIGRATION_REPORT, reportId))
                .thenReturn(List.of(html, sourceContext, json));
        when(reportComparisonRepository.latestComparableBase(report)).thenReturn(Optional.empty());
        when(reportComparisonRepository.latestForTargetReport(reportId)).thenReturn(Optional.empty());

        ReportResponse response = service().get(reportId, actorId, false);

        assertThat(response.artifacts()).extracting(ReportArtifactSummaryResponse::artifactType)
                .containsExactly("MIGRATION_REPORT_HTML", "MIGRATION_REPORT_JSON");
    }

    private ReportService service() { return new ReportService(reportRepository, projectAccessService, storedArtifactRepository, storedArtifactService, reportComparisonRepository, reviews); }

    private ReportView report(UUID reportId, UUID projectId, UUID sourceRevisionId, UUID taskId) {
        return new ReportView(reportId, projectId, taskId, sourceRevisionId, ReportType.EVALUATION, TaskStatus.SUCCEEDED,
                "迁移评估报告", Map.of("confirmedInputCount", 1), "HIGH", UUID.randomUUID(), OffsetDateTime.now(), OffsetDateTime.now());
    }

    private StoredArtifactView artifact(UUID artifactId, UUID projectId, UUID taskId, UUID reportId, String artifactType, String contentType) {
        return new StoredArtifactView(artifactId, projectId, taskId, StoredArtifactOwnerType.MIGRATION_REPORT, reportId,
                artifactType, "rvport-artifacts", "projects/test/report.html", contentType, 10, "0".repeat(64), Map.of(), OffsetDateTime.now());
    }

}
