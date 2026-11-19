package cn.rvport.backend.kb.migration;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 校验 agent 产出的 output/migration_result.json：条目身份必须属于投影集合、不得重复、
 * 状态枚举合法；unknown/duplicate 计入摘要不静默丢弃（照 AiReviewValidator 模式）。
 */
public final class KbMigrationResultValidator {

    public static final String SCHEMA_VERSION = "kb-migration-result.v1";
    static final Set<String> STATUSES = Set.of("DONE", "NO_WORK_NEEDED", "PENDING");

    public record Entry(String id, String status, String changeSummary, List<String> files, String reason) {
    }

    public record Result(
            int expectedCount,
            List<Entry> entries,
            List<String> unknownIds,
            List<String> duplicateIds,
            int missingCount,
            int doneCount,
            int noWorkNeededCount,
            int pendingCount) {

        public boolean invalid() {
            return !unknownIds.isEmpty() || !duplicateIds.isEmpty();
        }

        public boolean allMissing() {
            return entries.isEmpty() && expectedCount > 0;
        }
    }

    private KbMigrationResultValidator() {
    }

    public static Result validate(Collection<String> expectedIds, JsonNode root) {
        if (root == null || !root.path("entries").isArray()) {
            throw new IllegalArgumentException("entries 必须为数组");
        }
        Set<String> expected = new HashSet<>(expectedIds);
        List<Entry> entries = new ArrayList<>();
        List<String> unknownIds = new ArrayList<>();
        List<String> duplicateIds = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int done = 0;
        int noWork = 0;
        int pending = 0;
        for (JsonNode node : root.path("entries")) {
            String id = node.path("id").asText("").trim();
            String status = node.path("status").asText("").trim();
            if (id.isEmpty() || !STATUSES.contains(status)) {
                throw new IllegalArgumentException("条目 id 为空或 status 非法: " + status);
            }
            if (!expected.contains(id)) {
                if (!unknownIds.contains(id)) {
                    unknownIds.add(id);
                }
                continue;
            }
            if (!seen.add(id)) {
                if (!duplicateIds.contains(id)) {
                    duplicateIds.add(id);
                }
                continue;
            }
            entries.add(new Entry(
                    id,
                    status,
                    node.path("changeSummary").asText(null),
                    texts(node.path("files")),
                    node.path("reason").asText(null)));
            switch (status) {
                case "DONE" -> done++;
                case "NO_WORK_NEEDED" -> noWork++;
                default -> pending++;
            }
        }
        int missing = expected.size() - entries.size();
        return new Result(expected.size(), List.copyOf(entries), List.copyOf(unknownIds), List.copyOf(duplicateIds),
                missing, done, noWork, pending);
    }

    private static List<String> texts(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String text = item.asText("").trim();
            if (!text.isEmpty()) {
                values.add(text);
            }
        }
        return List.copyOf(values);
    }
}
