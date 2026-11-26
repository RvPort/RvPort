package cn.rvport.backend.ai.migration;

import cn.rvport.backend.report.review.ReviewWorkspaceRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 迁移的当前报告条目读取（change add-migration-report-loop 任务 1.2/1.3 支撑）。
 *
 * <p>报告是迁移的唯一权威输入源（spec：无报告时迁移创建必须被拒绝）。本服务封装
 * current 报告定位与全部条目读取，供创建资格判断与执行时条目投影复用；并承载
 * 报告上下文（contextHash）复检，STALE 判定与报告自身 READINESS 语义一致
 * （已决事项 3：contextHash 变化即拒）。
 */
@Service
public class AiMigrationReportSource {

    private final ReviewWorkspaceRepository reviews;
    private final cn.rvport.backend.report.review.ReportInputSelectionPolicy policy;

    public AiMigrationReportSource(ReviewWorkspaceRepository reviews,
            cn.rvport.backend.report.review.ReportInputSelectionPolicy policy) {
        this.reviews = reviews;
        this.policy = policy;
    }

    /** 当前报告定位结果：报告 id 与创建时冻结的 contextHash（供执行时复检）。 */
    public record CurrentReport(UUID reportId, String contextHash, long projectableCount) {
    }

    /** current 报告 + 全部条目（SCANNER 与 MANUAL 均返回，适配层区分处理）。 */
    public record ReportItems(UUID reportId, String contextHash, List<Map<String, Object>> items) {
    }

    /** 项目当前报告及其全部条目；无 current 报告为空。 */
    public Optional<ReportItems> currentItems(UUID projectId) {
        Optional<UUID> reportId = reviews.currentReport(projectId);
        if (reportId.isEmpty()) {
            return Optional.empty();
        }
        String contextHash = policy.current(projectId).contextHash();
        return Optional.of(new ReportItems(reportId.get(), contextHash, reviews.items(reportId.get())));
    }

    /** 创建资格：current 报告存在且可投影条目（SCANNER 且带 scannerFindingId）大于零。 */
    public Optional<CurrentReport> eligible(UUID projectId) {
        return currentItems(projectId).map(source -> {
            AiMigrationReportItems.Adapted adapted = AiMigrationReportItems.adapt(source.items());
            return new CurrentReport(source.reportId(), source.contextHash(), adapted.findings().size());
        });
    }

    /**
     * 执行时复检：报告上下文未变化且条目仍可投影。
     * contextHash 不一致 → {@code AI_MIGRATION_REPORT_STALE}（不可重试，已决事项 3）。
     */
    public ReportItems requireUnchanged(UUID projectId, UUID expectedReportId, String expectedContextHash) {
        ReportItems current = currentItems(projectId).orElseThrow(
                () -> stale("报告已不存在 / Report no longer current"));
        if (!current.reportId().equals(expectedReportId)) {
            throw stale("报告已被重新生成 / Report has been regenerated");
        }
        if (expectedContextHash != null && !expectedContextHash.equals(current.contextHash())) {
            throw stale("报告上下文已变化 / Report context changed");
        }
        return current;
    }

    private cn.rvport.backend.common.error.ApiException stale(String message) {
        return new cn.rvport.backend.common.error.ApiException(
                org.springframework.http.HttpStatus.CONFLICT, "AI_MIGRATION_REPORT_STALE",
                message + "，请重新生成报告后再发起迁移 / Regenerate the report and retry");
    }
}
