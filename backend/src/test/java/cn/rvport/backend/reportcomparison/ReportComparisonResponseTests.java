package cn.rvport.backend.reportcomparison;

import static org.assertj.core.api.Assertions.assertThat;

import cn.rvport.backend.report.ReportType;
import cn.rvport.backend.report.ReportView;
import cn.rvport.backend.task.TaskStatus;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReportComparisonResponseTests {

    @Test
    void mapsRunReportsAndDiffItemsWithoutDroppingFields() {
        OffsetDateTime now = OffsetDateTime.now();
        UUID projectId = UUID.randomUUID();
        UUID comparisonId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID baseId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        ReportComparisonRunView run = new ReportComparisonRunView(
                comparisonId, projectId, taskId, baseId, targetId, TaskStatus.SUCCEEDED,
                Map.of("fixed", 2), null, null, now, now, now, now, ReportComparisonBasis.CONFIRMED_FIX_LIST, UUID.randomUUID(), UUID.randomUUID());
        ReportView base = report(baseId, projectId, "base", now);
        ReportView target = report(targetId, projectId, "target", now);
        ReportComparisonDiffSummaryResponse diffSummary = new ReportComparisonDiffSummaryResponse(
                1, Map.of("FIXED", 1L), Map.of("KB", 1L), Map.of("HIGH", 1L), Map.of("HIGH", 1L));

        ReportComparisonResponse response = ReportComparisonResponse.from(run, base, target, diffSummary);
        ReportComparisonSummaryResponse summary = ReportComparisonSummaryResponse.from(run);

        assertThat(response.id()).isEqualTo(comparisonId);
        assertThat(response.basis()).isEqualTo(ReportComparisonBasis.CONFIRMED_FIX_LIST);
        assertThat(response.baseConfirmationId()).isEqualTo(run.baseConfirmationId());
        assertThat(summary.targetConfirmationId()).isEqualTo(run.targetConfirmationId());
        assertThat(response.baseReport().title()).isEqualTo("base");
        assertThat(response.targetReport().title()).isEqualTo("target");
        assertThat(response.diffSummary()).isSameAs(diffSummary);
        assertThat(summary.taskId()).isEqualTo(taskId);
        assertThat(summary.summary()).containsEntry("fixed", 2);

        ReportComparisonDiffItemView item = new ReportComparisonDiffItemView(
                UUID.randomUUID(), comparisonId, projectId, "KB", UUID.randomUUID(), UUID.randomUUID(),
                "FIXED", "HIGH", "unsafe call", "src/a.c:12", "replace", "CONFIRMED",
                "fingerprint", "HIGH", "same symbol", Map.of("old", 1), Map.of("new", 2), now);
        ReportComparisonDiffItemResponse itemResponse = ReportComparisonDiffItemResponse.from(item);
        ReportComparisonDiffItemDetailResponse detailResponse = ReportComparisonDiffItemDetailResponse.from(item);

        assertThat(itemResponse.title()).isEqualTo("unsafe call");
        assertThat(itemResponse.matchReason()).isEqualTo("same symbol");
        assertThat(detailResponse.oldItem()).containsEntry("old", 1);
        assertThat(detailResponse.newItem()).containsEntry("new", 2);
    }

    @Test
    void handlesAbsentReportsRunAndDiffSummary() {
        OffsetDateTime now = OffsetDateTime.now();
        ReportComparisonRunView run = new ReportComparisonRunView(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                TaskStatus.RUNNING, Map.of(), null, null, now, null, now, now, cn.rvport.backend.reportcomparison.ReportComparisonBasis.REPORT_FACTS, null, null);

        ReportComparisonResponse response = ReportComparisonResponse.from(run, null, null, null);

        assertThat(response.baseReport()).isNull();
        assertThat(response.targetReport()).isNull();
        assertThat(response.diffSummary()).isEqualTo(ReportComparisonDiffSummaryResponse.empty());
        assertThat(ReportComparisonSummaryResponse.from(null)).isNull();
        assertThat(ReportComparisonReportSummaryResponse.from(null)).isNull();
    }

    private ReportView report(UUID id, UUID projectId, String title, OffsetDateTime now) {
        return new ReportView(id, projectId, UUID.randomUUID(), UUID.randomUUID(), ReportType.EVALUATION,
                TaskStatus.SUCCEEDED, title, Map.of(), "HIGH", UUID.randomUUID(), now, now);
    }
}
