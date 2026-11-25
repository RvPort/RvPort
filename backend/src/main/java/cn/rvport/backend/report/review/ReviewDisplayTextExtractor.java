package cn.rvport.backend.report.review;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** 仅提取协议中的公开正文；未知 JSON 字段、标识和哈希不参与全文匹配。 */
final class ReviewDisplayTextExtractor {
    private static final List<String> TEXT=List.of("title","titleEn","description","descriptionEn","description_en",
            "reason","reasonEn","reason_en","suggestion","suggestionEn","suggestion_en","message","messageEn",
            "explanation","explanationEn","repairReason","repairReasonEn","text","textEn","summary","summaryEn",
            "recommendation","recommendationEn","testSuggestion","testSuggestionEn");
    private static final List<String> CONTAINERS=List.of("evidence","evidences","detail","details","limitations","missingEvidence",
            "assumptions","analysisReasonCodes","analysisEvidence","conclusion","evidenceChain","rawFinding","sourceDetail","breaks");
    private ReviewDisplayTextExtractor() { }

    static Stream<String> text(Map<String,Object> item) {
        return Stream.concat(Stream.of("title","titleEn","relativePath","exclusionReason","exclusionReasonEn")
                .flatMap(key->string(item.get(key))),
                Stream.of("originalSnapshot","aiReview","manualDetails","displayEvidence")
                        .flatMap(key->body(item.get(key),0)));
    }

    private static Stream<String> body(Object value,int depth) {
        if(depth>8) return Stream.empty();
        if(value instanceof String text) return Stream.of(text);
        if(value instanceof List<?> values) return values.stream().flatMap(v->body(v,depth+1));
        if(!(value instanceof Map<?,?> map)) return Stream.empty();
        return Stream.concat(TEXT.stream().flatMap(key->string(map.get(key))),
                CONTAINERS.stream().flatMap(key->body(map.get(key),depth+1)));
    }

    private static Stream<String> string(Object value) {
        return value instanceof String text?Stream.of(text):Stream.empty();
    }
}
