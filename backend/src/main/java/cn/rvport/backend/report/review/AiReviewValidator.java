package cn.rvport.backend.report.review;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 对完整的一次尝试作原子校验；部分结果不能成为成功的增强报告。 */
public final class AiReviewValidator {
    public enum Verdict { CONFIRMED_ISSUE, SUSPECTED_FALSE_POSITIVE, INSUFFICIENT_EVIDENCE }
    public record Evidence(String id, String fileKey, String contentHash, Integer startLine, Integer endLine) { }
    public record Result(UUID inputId, Verdict verdict, String reason, String reasonEn,
            String suggestion, String suggestionEn, List<String> evidenceRefs) { }

    public List<Result> validate(List<UUID> expectedIds, Map<UUID, Set<String>> allowedEvidence, JsonNode output) {
        if (output == null || !output.isObject() || !output.path("results").isArray()) throw invalid();
        Set<UUID> expected = new LinkedHashSet<>(expectedIds);
        if (expected.size() != expectedIds.size()) throw invalid();
        Map<UUID, Result> valid = new LinkedHashMap<>();
        for (JsonNode row : output.path("results")) {
            if (!row.isObject()) throw invalid();
            UUID id;
            Verdict verdict;
            try {
                id = UUID.fromString(text(row, "inputId", true));
                verdict = Verdict.valueOf(text(row, "verdict", true));
            } catch (IllegalArgumentException ex) {
                throw invalid();
            }
            if (!expected.contains(id) || valid.containsKey(id)) throw invalid();
            String reason = text(row, "reason", true);
            String reasonEn = text(row, "reasonEn", true);
            String suggestion = text(row, "suggestion", false);
            String suggestionEn = text(row, "suggestionEn", false);
            if ((suggestion == null) != (suggestionEn == null)) throw invalid();
            JsonNode references = row.path("evidenceRefs");
            if (!references.isArray()) throw invalid();
            Set<String> refs = new LinkedHashSet<>();
            for (JsonNode reference : references) {
                if (!reference.isTextual() || !allowedEvidence.getOrDefault(id, Set.of()).contains(reference.textValue())
                        || !refs.add(reference.textValue())) throw invalid();
            }
            if (verdict != Verdict.INSUFFICIENT_EVIDENCE && refs.isEmpty()) throw invalid();
            valid.put(id, new Result(id, verdict, reason, reasonEn, suggestion, suggestionEn, List.copyOf(refs)));
        }
        if (valid.size() != expected.size()) throw invalid();
        List<Result> ordered = new ArrayList<>();
        expectedIds.forEach(id -> ordered.add(valid.get(id)));
        return List.copyOf(ordered);
    }

    private String text(JsonNode node, String key, boolean required) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            if (required) throw invalid();
            return null;
        }
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 16000) throw invalid();
        return value.textValue().trim();
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException("REPORT_AI_OUTPUT_INVALID");
    }
}
