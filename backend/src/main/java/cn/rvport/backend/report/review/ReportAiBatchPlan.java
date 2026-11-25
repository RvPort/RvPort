package cn.rvport.backend.report.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** 分母固定；预算不足明确失败，绝不截断输入后发布成功。 */
public record ReportAiBatchPlan(String digest, List<List<ReviewItemSnapshot>> batches, int totalItems) {
    public static ReportAiBatchPlan create(ReportReviewManifest manifest, ObjectMapper mapper) throws java.io.IOException {
        return create(manifest, mapper, 32, 256 * 1024, 16 * 1024 * 1024, 10000);
    }

    static ReportAiBatchPlan create(ReportReviewManifest manifest, ObjectMapper mapper,
            int maxItems, int maxBatchBytes, int maxTotalBytes, int maxTotalItems) throws java.io.IOException {
        var expected = manifest.items().stream().filter(item -> "SCANNER".equals(item.origin())
                && item.decision().state() != ReviewState.EXCLUDED).sorted(Comparator.comparing(item -> item.id().toString())).toList();
        if (maxItems < 1 || maxBatchBytes < 1 || maxTotalBytes < 1 || expected.size() > maxTotalItems)
            throw new java.io.IOException("REPORT_AI_REVIEW_INPUT_TOO_LARGE");
        var ids = new HashSet<java.util.UUID>();
        var batches = new ArrayList<List<ReviewItemSnapshot>>();
        var batch = new ArrayList<ReviewItemSnapshot>();
        long totalBytes = 0, batchBytes = 0;
        for (var item : expected) {
            if (!ids.add(item.id())) throw new java.io.IOException("REPORT_AI_REVIEW_INPUT_INVALID");
            int bytes = mapper.writeValueAsBytes(item).length + 1024;
            if (manifest.selection() != null) bytes += mapper.writeValueAsBytes(manifest.selection().sources().stream()
                    .filter(s -> java.util.Objects.equals(s.runId(), item.sourceRunId()))
                    .map(ReportInputSelectionPolicy.Source::execution).findFirst().orElse(java.util.Map.of())).length;
            totalBytes += bytes;
            if (bytes > maxBatchBytes || totalBytes > maxTotalBytes) throw new java.io.IOException("REPORT_AI_REVIEW_INPUT_TOO_LARGE");
            if (!batch.isEmpty() && (batch.size() >= maxItems || batchBytes + bytes > maxBatchBytes)) {
                batches.add(List.copyOf(batch)); batch.clear(); batchBytes = 0;
            }
            batch.add(item); batchBytes += bytes;
        }
        if (!batch.isEmpty()) batches.add(List.copyOf(batch));
        var canonical = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        var orderedManifest = new ReportReviewManifest(manifest.projectId(), manifest.sourceRevisionId(), manifest.contextHash(),
                manifest.mode(), manifest.selection(), manifest.source(), manifest.items().stream()
                .sorted(Comparator.comparing(item -> item.id().toString())).toList(), manifest.baseReportId(), manifest.baseReviewRevision());
        String digest = SourceSelection.hash(canonical.writeValueAsBytes(java.util.Map.of(
                "version", 1, "manifest", orderedManifest, "ids", expected.stream().map(ReviewItemSnapshot::id).toList(),
                "maxItems", maxItems, "maxBatchBytes", maxBatchBytes)));
        return new ReportAiBatchPlan(digest, List.copyOf(batches), expected.size());
    }
}
