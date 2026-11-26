package cn.rvport.backend.ai.migration;

import cn.rvport.backend.kb.FindingView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 报告条目 → 迁移投影的输入适配（change add-migration-report-loop 任务 1.1）。
 *
 * <p>迁移输入源从 KB 扫描 findings 切换为当前评估报告的全部条目。报告条目经
 * {@code ReviewWorkspaceRepository.items(reportId)} 读出为 {@code Map}（camelCase 键），
 * 本适配器将其还原为投影器消费的 {@link FindingView} 形态——身份经
 * {@code originalSnapshot.evidence.scannerFindingId} 对齐（双轨同源，投影合同零改动）。
 *
 * <p>语义（spec：迁移输入必须取自当前评估报告的全部条目）：
 * <ul>
 *   <li>全部 SCANNER 条目投影，MUST NOT 以 reviewState（TO_FIX 与否）过滤；</li>
 *   <li>MANUAL 条目（无 scannerFindingId）跳过并计入 missingFiles 说明；</li>
 *   <li>报告条目的复核结论（reviewState）经 evidence 原样透传给 agent，不损失信息。</li>
 * </ul>
 */
final class AiMigrationReportItems {

    private AiMigrationReportItems() {
    }

    /** 适配结果：可投影 findings + 跳过的 MANUAL 条目（记 manifest missingFiles）。 */
    record Adapted(List<FindingView> findings, List<String> skippedManual) {
    }

    static Adapted adapt(List<Map<String, Object>> reportItems) {
        List<FindingView> findings = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Map<String, Object> item : reportItems) {
            if (!"SCANNER".equals(text(item.get("origin")))) {
                // 人工条目（MANUAL）：无 scannerFindingId，迁移域只处理扫描发现的事实
                skipped.add(text(item.get("title")));
                continue;
            }
            Map<String, Object> original = map(item.get("originalSnapshot"));
            Map<String, Object> evidence = map(original.get("evidence"));
            if (evidence.get("scannerFindingId") == null) {
                // SCANNER 条目必带 scannerFindingId（快照工厂契约）；防御性跳过
                skipped.add(text(item.get("title")));
                continue;
            }
            findings.add(toFinding(item, original, evidence));
        }
        return new Adapted(List.copyOf(findings), List.copyOf(skipped));
    }

    private static FindingView toFinding(Map<String, Object> item, Map<String, Object> original,
            Map<String, Object> evidence) {
        UUID id = uuid(item.get("sourceRefId"));
        return new FindingView(
                id,
                uuid(item.get("projectId")),
                uuid(original.get("scanRunId")),
                uuid(original.get("sourceRevisionId")),
                text(original.get("ruleId")),
                text(original.get("category")),
                text(original.get("severity")),
                text(original.get("title")),
                text(original.get("description")),
                text(original.get("filePath")),
                integer(original.get("lineStart")),
                integer(original.get("lineEnd")),
                text(evidence.get("symbol")) != null ? text(evidence.get("symbol")) : text(original.get("symbol")),
                evidence,
                text(original.get("suggestion")),
                text(original.get("fingerprint")),
                text(original.get("status")),
                null);
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static UUID uuid(Object value) {
        return value == null ? null : UUID.fromString(value.toString());
    }

    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw
                ? (Map<String, Object>) raw
                : Map.of();
    }
}
