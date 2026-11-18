package cn.rvport.backend.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.rvport.backend.artifact.StoredArtifactOwnerType;
import cn.rvport.backend.artifact.StoredArtifactView;
import cn.rvport.backend.source.SourceRevisionType;
import cn.rvport.backend.source.SourceRevisionView;
import cn.rvport.backend.task.TaskStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReportDtoTests {

    @Test
    void mapsComparableArtifactInputAndSourceRevisionViews() {
        OffsetDateTime now = OffsetDateTime.now();
        UUID projectId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        ReportView report = new ReportView(reportId, projectId, UUID.randomUUID(), UUID.randomUUID(),
                ReportType.EVALUATION, TaskStatus.SUCCEEDED, "title", Map.of(), "HIGH", UUID.randomUUID(), now, now);
        assertThat(LatestComparableReportResponse.from(report)).satisfies(mapped -> {
            assertThat(mapped.id()).isEqualTo(reportId);
            assertThat(mapped.status()).isEqualTo(TaskStatus.SUCCEEDED);
        });
        assertThat(LatestComparableReportResponse.from(null)).isNull();

        StoredArtifactView artifact = new StoredArtifactView(UUID.randomUUID(), projectId, UUID.randomUUID(),
                StoredArtifactOwnerType.MIGRATION_REPORT, reportId, "MIGRATION_REPORT_JSON", "bucket", "key",
                "application/json", 12, "sha", Map.of("count", 1), now);
        assertThat(ReportArtifactSummaryResponse.from(artifact)).satisfies(mapped -> {
            assertThat(mapped.id()).isEqualTo(artifact.id());
            assertThat(mapped.sizeBytes()).isEqualTo(12);
            assertThat(mapped.summary()).containsEntry("count", 1);
        });

        ReportInputView input = new ReportInputView(UUID.randomUUID(), reportId, ReportInputType.SCAN_FINDING,
                UUID.randomUUID(), Map.of("title", "issue"), now);
        assertThat(ReportInputResponse.from(input)).satisfies(mapped -> {
            assertThat(mapped.inputId()).isEqualTo(input.inputId());
            assertThat(mapped.inputSnapshot()).containsEntry("title", "issue");
        });

        SourceRevisionView revision = new SourceRevisionView(UUID.randomUUID(), projectId, SourceRevisionType.GIT,
                "v1", "git", "git", "main", "abc", null, "/runtime", null, true, UUID.randomUUID(), now);
        assertThat(ReportSourceRevisionSummaryResponse.from(revision)).satisfies(mapped -> {
            assertThat(mapped.id()).isEqualTo(revision.id());
            assertThat(mapped.gitCommit()).isEqualTo("abc");
        });
        assertThat(ReportSourceRevisionSummaryResponse.from(null)).isNull();
    }

    @Test
    void reportResponseDefaultsNullableCollectionsAndDiff() {
        OffsetDateTime now = OffsetDateTime.now();
        ReportView view = new ReportView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ReportType.EVALUATION, TaskStatus.SUCCEEDED, "title", Map.of(), "INFO", UUID.randomUUID(), now, now);
        var review=Map.<String,Object>of("publishState","HISTORICAL_SUPERSEDED","writable",false);
        ReportResponse response=ReportResponse.from(view,null,null,null,review);
        assertThat(response.artifacts()).isEmpty(); assertThat(response.review()).isEqualTo(review);
    }

    @Test
    void parsesReportFormatsAndRejectsMissingOrUnknownValues() {
        assertThat(ReportDownloadFormat.parse(" html ")).isEqualTo(ReportDownloadFormat.HTML);
        assertThat(ReportDownloadFormat.parse("JSON")).isEqualTo(ReportDownloadFormat.JSON);
        assertThat(ReportDownloadFormat.HTML.contentType()).isEqualTo("text/html");
        assertThat(ReportDownloadFormat.JSON.extension()).isEqualTo("json");
        assertThatThrownBy(() -> ReportDownloadFormat.parse(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReportDownloadFormat.parse(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReportDownloadFormat.parse("xml")).isInstanceOf(IllegalArgumentException.class);
    }
}
