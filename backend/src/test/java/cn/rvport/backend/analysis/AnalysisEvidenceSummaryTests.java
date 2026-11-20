package cn.rvport.backend.analysis;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnalysisEvidenceSummaryTests {
    final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @Test void sourceOnlyCompletionIsIndependentOfMissingBuildEvidence() throws Exception {
        var document = mapper.readTree("""
                {"scan":{"status":"complete"},"project":{"filesAnalyzed":2,
                  "translationUnits":{"planned":2,"completed":2,"failed":0,"unknown":0}},
                 "summary":{"layers":{"ir":{"planned":0,"completed":0}}}}
                """);
        var manifest = mapper.readTree("""
                {"inputDigest":"fixed-manifest","files":[{"path":"a.c","status":"PLANNED"},
                 {"path":"b.c","status":"PLANNED"},{"path":"a.h","status":"UNSUPPORTED"},
                 {"path":"main.go","status":"NOT_SELECTED"}, {"path":"unknown"}]}
                """);
        var result = AnalysisEvidenceSummary.execution(SpecializedAnalyzerType.FLOATING_POINT, document, manifest);
        assertThat(result).containsEntry("analysisComplete", true).containsEntry("resultAvailable", true)
                .containsEntry("analysisReasonCodes", List.of());
        assertThat((Map<String,Object>)result.get("coverage")).containsAllEntriesOf(Map.of("denominatorKnown",false,
                "plannedTuCount",2L,"analyzedTuCount",2L,"failedTuCount",0L,"unknownTuCount",0L,
                "knownSourceFileCount",5,"manifestDigest","fixed-manifest"));
        assertThat(result.get("analysisLayers").toString()).contains("planned=0");
    }

    @Test void preservesActualPartialReasonsAndDoesNotInventMissingCounters() throws Exception {
        var doc = mapper.readTree("""
                {"scan":{"status":"partial"},"project":{"reasonCodes":["source_failed",4,"result_budget_truncated"],
                 "filesAnalyzed":-1,"translationUnits":{"planned":3,"completed":1,"failed":1,"unknown":1}}}
                """);
        var result = AnalysisEvidenceSummary.execution(SpecializedAnalyzerType.FLOATING_POINT, doc, null);
        assertThat(result).containsEntry("analysisComplete",false)
                .containsEntry("analysisReasonCodes",List.of("source_failed","result_budget_truncated"));
        assertThat((Map<String,Object>)result.get("coverage")).doesNotContainKey("analyzedFileCount");
        var withMissingGenerated = AnalysisEvidenceSummary.execution(SpecializedAnalyzerType.FLOATING_POINT, doc,
                mapper.readTree("{\"compileContextCoverage\":{\"unavailableSourceCount\":1,\"localizedCommandCount\":2}}"));
        assertThat(((Map<?,?>)withMissingGenerated.get("coverage")).get("compileContextCoverage"))
                .isEqualTo(Map.of("unavailableSourceCount",1,"localizedCommandCount",2));
        var moa = AnalysisEvidenceSummary.execution(SpecializedAnalyzerType.MEMORY_ORDER, mapper.readTree("""
                {"status":"partial","summary":{"translation_units":2},"reason_codes":["open-scope"]}
                """), mapper.readTree("{}"));
        assertThat(moa).containsEntry("analysisComplete",false).containsEntry("analysisReasonCodes",List.of("open-scope"));
        assertThat((Map<String,Object>)moa.get("coverage")).containsAllEntriesOf(Map.of("plannedTuCount",2L))
                .doesNotContainKeys("analyzedTuCount","failedTuCount");
        assertThat(AnalysisEvidenceSummary.execution(SpecializedAnalyzerType.FLOATING_POINT,
                mapper.readTree("{\"project\":{\"filesAnalyzed\":9999999999999999999999999}}"), null)
                .get("coverage")).isEqualTo(Map.of("denominatorKnown",false));
    }
    @Test void preservesGapsAndDoesNotInventMeasuredCoverageOrValidation() {
        var plan = new AnalysisInputPlan(1, UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), "KB", "0.1.0", "p1",
                AnalysisInputPlan.Identity.of("c", "x86", "riscv64", Map.of(), List.of(), "r1"),
                AnalysisInputPlan.InputOrigin.SOURCE_DISCOVERY, null, List.of(), List.of("DEFAULT_ASSUMPTION"),
                List.of("COMPILE_DATABASE_UNAVAILABLE"), null);
        var summary = AnalysisEvidenceSummary.from(plan);
        assertThat(summary).containsEntry("inputOrigin", "SOURCE_DISCOVERY")
                .containsEntry("effectiveInputDigest", plan.effectiveInputDigest())
                .containsEntry("missingEvidence", plan.missingEvidence())
                .containsEntry("coverage", Map.of("denominatorKnown", false, "plannedFiles", List.of()));
        assertThat(summary.toString()).contains("message_en", "COMPILE_DATABASE_UNAVAILABLE")
                .doesNotContain("buildSucceeded", "testsPassed", "formalRiskCount");
    }
}
