package cn.rvport.backend.analysis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 展示输入事实和证据缺口，不从 AI 结论反推编译或验证已完成。 */
public final class AnalysisEvidenceSummary {
    private AnalysisEvidenceSummary() { }
    public static Map<String, Object> from(AnalysisInputPlan plan) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inputOrigin", plan.inputOrigin().name());
        result.put("inputPlan", plan.summary());
        result.put("sourceDigest", plan.sourceDigest());
        result.put("requestedInputDigest", plan.originalInputDigest());
        result.put("effectiveInputDigest", plan.effectiveInputDigest());
        result.put("assumptions", plan.assumptions());
        result.put("missingEvidence", plan.missingEvidence());
        result.put("limitations", plan.missingEvidence().stream().map(code -> Map.of(
                "reasonCode", code,
                "message", "此分析缺少对应构建证据，候选仍需结合源码及后续构建测试复核。",
                "message_en", "Corresponding build evidence is unavailable; review candidates against source and subsequent build and test results."
        )).toList());
        result.put("coverage", Map.of("denominatorKnown", false, "plannedFiles", List.of()));
        return result;
    }

    /** 完成度取自合法发布的工具结果，构建证据缺失不参与状态推断。 */
    public static Map<String, Object> execution(SpecializedAnalyzerType type, JsonNode document,
            JsonNode manifest) {
        Map<String, Object> result = new LinkedHashMap<>();
        String status = type == SpecializedAnalyzerType.FLOATING_POINT
                ? document.path("scan").path("status").asText() : document.path("status").asText();
        result.put("analysisComplete", "complete".equals(status));
        result.put("resultAvailable", true);
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("denominatorKnown", false);
        if (manifest != null && manifest.path("compileContextCoverage").isObject())
            coverage.put("compileContextCoverage", object(manifest.path("compileContextCoverage")));
        if (manifest != null && manifest.path("files").isArray()) {
            JsonNode files = manifest.path("files");
            coverage.put("knownSourceFileCount", files.size());
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (JsonNode file : files) counts.merge(file.path("status").asText("UNKNOWN"), 1, Integer::sum);
            coverage.put("manifestFileCounts", counts);
            List<Map<String,Object>> fileList = new ObjectMapper().convertValue(files,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String,Object>>>() { });
            coverage.put("files", fileList);
            coverage.put("byLanguage", AnalysisFileCoverage.byLanguage(fileList));
            coverage.put("manifestDigest", manifest.path("inputDigest").asText());
        }
        if (type == SpecializedAnalyzerType.FLOATING_POINT) {
            JsonNode units = document.path("project").path("translationUnits");
            copyCount(units, "planned", coverage, "plannedTuCount");
            copyCount(units, "completed", coverage, "analyzedTuCount");
            copyCount(units, "failed", coverage, "failedTuCount");
            copyCount(units, "unknown", coverage, "unknownTuCount");
            copyCount(document.path("project"), "filesAnalyzed", coverage, "analyzedFileCount");
            result.put("analysisReasonCodes", codes(document.path("project").path("reasonCodes")));
            result.put("analysisLayers", object(document.path("summary").path("layers")));
        } else {
            copyCount(document.path("summary"), "translation_units", coverage, "plannedTuCount");
            // 内存序未提供完成 TU 的计数，不从 scopes 或 findings 猜测。
            result.put("analysisReasonCodes", codes(document.path("reason_codes")));
        }
        result.put("coverage", coverage);
        return result;
    }

    private static void copyCount(JsonNode source, String key, Map<String, Object> target, String name) {
        JsonNode value = source.path(key);
        if (value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0)
            target.put(name, value.longValue());
    }

    private static List<String> codes(JsonNode value) {
        if (!value.isArray()) return List.of();
        List<String> result = new java.util.ArrayList<>();
        for (JsonNode item : value) if (item.isTextual()) result.add(item.textValue());
        return List.copyOf(result);
    }

    private static Map<?, ?> object(JsonNode value) {
        return value.isObject() ? new ObjectMapper().convertValue(value, Map.class) : Map.of();
    }
}
