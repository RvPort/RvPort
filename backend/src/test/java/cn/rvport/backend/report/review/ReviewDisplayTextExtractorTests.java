package cn.rvport.backend.report.review;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReviewDisplayTextExtractorTests {
    ReviewItemQuery query(String keyword,int page) {
        return new ReviewItemQuery(page,1,null,null,null,null,null,null,null,keyword,null,null);
    }
    @Test void matchesEachPublicBodyAndCountsBeforePagination() {
        var rows=new ArrayList<Map<String,Object>>();
        for(String field:List.of("originalSnapshot","aiReview","manualDetails","displayEvidence"))
            rows.add(Map.of("id",field,"reviewState","TO_FIX",field,Map.of("reasonEn","Evidence-Only 内容")));
        var result=query("  EVIDENCE-only  ",2).filteredPage(rows);
        assertThat(result.page().total()).isEqualTo(4);
        assertThat(result.page().items()).hasSize(1);
        assertThat(result.summary()).containsEntry("toFixCount",4L);
        assertThat(query("内容",1).apply(rows).total()).isEqualTo(4);
        assertThat(query(" ",1).apply(rows).total()).isEqualTo(4);
    }
    @Test void ignoresPrivateIdentifiersUnknownFieldsAndNonText() {
        var item=new HashMap<String,Object>();
        item.put("originalSnapshot",Map.of("id","secret","hash","secret","unknown","secret","description",3,
                "evidence",List.of(Map.of("text","public text","actorId","secret"),42)));
        item.put("manualDetails",null);item.put("aiReview",false);
        assertThat(query("secret",1).apply(List.of(item)).total()).isZero();
        assertThat(query("public",1).apply(List.of(item)).total()).isEqualTo(1);
        assertThatThrownBy(()->query("x".repeat(257),1).apply(List.of())).isInstanceOf(cn.rvport.backend.common.error.ApiException.class);
    }
    @Test void supportsTextEvidenceAndBoundsNesting() {
        var root=new HashMap<String,Object>();Map<String,Object> next=root;
        for(int i=0;i<12;i++){var nested=new HashMap<String,Object>();next.put("evidence",nested);next=nested;}
        next.put("text","deep secret");
        assertThat(ReviewDisplayTextExtractor.text(Map.of("originalSnapshot",root))).isEmpty();
        assertThat(ReviewDisplayTextExtractor.text(Map.of("originalSnapshot",Map.of("evidence","source evidence"),
                "exclusionReasonEn","manual reason"))).containsExactly("manual reason","source evidence");
    }
    @Test void coversDependencyAndSpecializedAnalysisDisplayKeys() {
        for(String field:List.of("recommendation","recommendationEn","testSuggestion","testSuggestionEn"))
            assertThat(query("needle",1).apply(List.of(Map.of("originalSnapshot",Map.of(field,"needle")))).total()).isEqualTo(1);
        for(String container:List.of("detail","analysisEvidence"))
            assertThat(query("needle",1).apply(List.of(Map.of("originalSnapshot",Map.of(container,Map.of("messageEn","needle"))))).total()).isEqualTo(1);
    }
}
