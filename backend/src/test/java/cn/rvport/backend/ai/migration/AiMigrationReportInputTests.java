package cn.rvport.backend.ai.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.report.review.ReviewWorkspaceRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 输入侧（change add-migration-report-loop 任务 1.4）：
 * 报告条目 → 投影的适配语义（全量/身份对齐/状态不过滤/MANUAL 跳过）
 * 与报告源（无报告/STALE 复检）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiMigrationReportInputTests {

    @Mock
    private ReviewWorkspaceRepository reviews;

    @Mock
    private cn.rvport.backend.report.review.ReportInputSelectionPolicy policy;

    @InjectMocks
    private AiMigrationReportSource source;

    private final UUID project = UUID.randomUUID();
    private final UUID reportId = UUID.randomUUID();
    private final UUID scanRunId = UUID.randomUUID();
    private final UUID revisionId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        org.mockito.Mockito.when(reviews.currentReport(project)).thenReturn(Optional.of(reportId));
        org.mockito.Mockito.when(policy.current(project)).thenReturn(
                new cn.rvport.backend.report.review.ReportInputSelectionPolicy.Selection(
                        project, revisionId, "ctx-hash-1", List.of()));
        org.mockito.Mockito.when(reviews.items(reportId)).thenReturn(List.of(
                item("SCANNER", "KB_FINDING", "finding:a", "src/a.c", 3, "high"),
                item("SCANNER", "KB_FINDING", "finding:b", "src/b.h", 7, "low"),
                // TO_FIX 状态的条目：全量投影必须包含（spec：不以复核状态过滤）
                toItemFix("finding:c", "src/c.c", 9, "medium"),
                // MANUAL：无 scannerFindingId，跳过
                manual("人工补充项")));
    }

    @Test
    void 全量投影含TO_FIX并跳过MANUAL() {
        var items = source.currentItems(project).orElseThrow();
        var adapted = AiMigrationReportItems.adapt(items.items());

        // 3 条 SCANNER（含 TO_FIX）全投影；MANUAL 1 条跳过
        assertThat(adapted.findings()).hasSize(3);
        assertThat(adapted.skippedManual()).containsExactly("人工补充项");

        // 身份对齐：条目 id 仍是 scannerFindingId（投影器消费 evidence）
        var projection = AiMigrationProjector.project(scanRunId, adapted.findings());
        List<String> ids = projection.suggestionClass().stream()
                .map(AiMigrationProjector.Item::id).toList();
        assertThat(ids).contains("finding:a", "finding:b", "finding:c");

        // TO_FIX 条目（finding:c）确实在投影集合中
        assertThat(ids).contains("finding:c");
    }

    @Test
    void 资格判断取可投影数() {
        var eligible = source.eligible(project).orElseThrow();
        assertThat(eligible.projectableCount()).isEqualTo(3);
        assertThat(eligible.reportId()).isEqualTo(reportId);
        assertThat(eligible.contextHash()).isEqualTo("ctx-hash-1");
    }

    @Test
    void 无报告时为空() {
        org.mockito.Mockito.when(reviews.currentReport(project)).thenReturn(Optional.empty());
        assertThat(source.currentItems(project)).isEmpty();
        assertThat(source.eligible(project)).isEmpty();
    }

    @Test
    void contextHash变化复检拒绝() {
        var current = source.eligible(project).orElseThrow();
        // 复检时 contextHash 已变化 → STALE（已决事项 3）
        org.mockito.Mockito.when(policy.current(project))
                .thenReturn(new cn.rvport.backend.report.review.ReportInputSelectionPolicy.Selection(
                        project, revisionId, "ctx-hash-CHANGED", List.of()));
        assertThatThrownBy(() -> source.requireUnchanged(project, current.reportId(), current.contextHash()))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("AI_MIGRATION_REPORT_STALE");
    }

    @Test
    void 报告被重新生成复检拒绝() {
        var current = source.eligible(project).orElseThrow();
        UUID regenerated = UUID.randomUUID();
        org.mockito.Mockito.when(reviews.currentReport(project)).thenReturn(Optional.of(regenerated));
        assertThatThrownBy(() -> source.requireUnchanged(project, current.reportId(), current.contextHash()))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("AI_MIGRATION_REPORT_STALE");
    }

    @Test
    void 上下文一致时复检通过() {
        var current = source.eligible(project).orElseThrow();
        var items = source.requireUnchanged(project, current.reportId(), current.contextHash());
        assertThat(items.reportId()).isEqualTo(reportId);
    }

    // ---- 夹具 ----

    private Map<String, Object> item(String origin, String sourceType, String scannerFindingId,
            String filePath, int line, String severity) {
        Map<String, Object> item = baseItem(origin, sourceType);
        Map<String, Object> original = (Map<String, Object>) item.get("originalSnapshot");
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("scannerFindingId", scannerFindingId);
        evidence.put("factKind", "macro_invocation");
        evidence.put("actionable", true);
        evidence.put("symbol", "sym-" + scannerFindingId);
        original.put("evidence", evidence);
        original.put("filePath", filePath);
        original.put("lineStart", line);
        original.put("lineEnd", line);
        original.put("severity", severity);
        original.put("scanRunId", scanRunId.toString());
        return item;
    }

    /** TO_FIX 复核状态条目：evidence 原样携带 reviewState（透传 agent，不作为过滤条件）。 */
    private Map<String, Object> toItemFix(String scannerFindingId, String filePath, int line, String severity) {
        Map<String, Object> item = item("SCANNER", "KB_FINDING", scannerFindingId, filePath, line, severity);
        item.put("reviewState", "TO_FIX");
        return item;
    }

    private Map<String, Object> manual(String title) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("origin", "MANUAL");
        item.put("title", title);
        item.put("originalSnapshot", new LinkedHashMap<>());
        return item;
    }

    private Map<String, Object> baseItem(String origin, String sourceType) {
        UUID refId = UUID.randomUUID();
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("ruleId", "rule-1");
        original.put("category", "cat");
        original.put("title", "标题 " + refId);
        original.put("description", "原因");
        original.put("suggestion", "建议");
        original.put("fingerprint", "fp-" + refId);
        original.put("status", "DETECTED");
        original.put("sourceRevisionId", revisionId.toString());
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("origin", origin);
        item.put("sourceType", sourceType);
        item.put("sourceRefId", refId.toString());
        item.put("projectId", project.toString());
        item.put("originalSnapshot", original);
        return item;
    }

    @Test
    void 非数值行号与缺失字段容错() {
        UUID ref = UUID.randomUUID();
        var original = new java.util.LinkedHashMap<String, Object>();
        original.put("scanRunId", UUID.randomUUID().toString());
        original.put("sourceRevisionId", UUID.randomUUID().toString());
        original.put("ruleId", "rule"); original.put("category", "c"); original.put("severity", "HIGH");
        original.put("title", "t"); original.put("description", "d"); original.put("filePath", "src/a.c");
        original.put("lineStart", "12"); original.put("lineEnd", "14"); original.put("suggestion", "s");
        original.put("fingerprint", "fp"); original.put("status", "OPEN");
        original.put("evidence", Map.of("scannerFindingId", ref.toString()));
        var candidate = new java.util.LinkedHashMap<String, Object>();
        candidate.put("origin", "SCANNER"); candidate.put("title", "夹具");
        candidate.put("sourceRefId", ref.toString()); candidate.put("projectId", UUID.randomUUID().toString());
        candidate.put("originalSnapshot", original);
        var adapted = AiMigrationReportItems.adapt(List.of(candidate));
        assertThat(adapted.findings()).hasSize(1);
        assertThat(adapted.findings().getFirst().lineStart()).isNull();
        assertThat(adapted.findings().getFirst().lineEnd()).isNull();
    }

    @Test
    void 非常规条目形态防御性跳过() {
        var adapted = AiMigrationReportItems.adapt(java.util.List.of(
                java.util.Map.of("title", "无来源"),
                java.util.Map.of("origin", "SCANNER", "title", "坏快照", "originalSnapshot", "not-a-map")));
        assertThat(adapted.findings()).isEmpty();
        assertThat(adapted.skippedManual()).containsExactly("无来源", "坏快照");
    }
}
