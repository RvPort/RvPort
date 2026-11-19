package cn.rvport.backend.report.review;

import static org.assertj.core.api.Assertions.*;
import cn.rvport.backend.common.error.ApiException;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReviewItemQueryTests {
    @Test void paginatesDeterministicallyAndKeepsUnlocatedItemsInGlobalList() {
        var located=Map.<String,Object>of("id","a","relativePath","src/a.c","startLine",5,"reviewState","TO_FIX","title","Alpha");
        var unlocated=Map.<String,Object>of("id","b","reviewState","PENDING_REVIEW","originalSnapshot",Map.of("riskLevel","HIGH"));
        var values=List.of(unlocated,located);
        var page=query(1,1,null,null,null).apply(values);
        assertThat(page.total()).isEqualTo(2); assertThat(page.hasMore()).isTrue(); assertThat(page.items()).containsExactly(located);
        assertThat(query(2,1,null,null,null).apply(values).items()).containsExactly(unlocated);
        assertThat(query(Integer.MAX_VALUE,200,null,null,null).apply(values).items()).isEmpty();
        assertThat(query(null,null,"path","desc",null).apply(values).items()).containsExactly(located,unlocated);
        assertThat(query(null,null,"line","asc",null).apply(values).items()).containsExactly(located,unlocated);
        assertThat(query(null,null,"riskLevel","asc",null).apply(values).items()).containsExactly(unlocated,located);
        assertThat(query(null,null,"title","asc","ALPHA").apply(values).items()).containsExactly(located);
    }
    @Test void filteredCountsIncludeEveryMatchingPage() {
        var toFix = Map.<String,Object>of("id","a","relativePath","a.c","reviewState","TO_FIX","origin","MANUAL");
        var pending = Map.<String,Object>of("id","b","relativePath","a.c","reviewState","PENDING_REVIEW");
        var excluded = Map.<String,Object>of("id","c","relativePath","b.c","reviewState","EXCLUDED");
        var result = query(1,1,null,null,"a.c").filteredPage(List.of(toFix,pending,excluded));
        assertThat(result.page().items()).containsExactly(toFix);
        assertThat(result.summary()).containsExactlyInAnyOrderEntriesOf(Map.of("total",2L,"toFixCount",1L,
                "pendingReviewCount",1L,"excludedCount",0L,"manualCount",1L));
    }

    @Test void combinesEveryFilterAndHandlesAllRiskRanks() {
        var rows=new ArrayList<Map<String,Object>>();
        for(String risk:List.of("COMMENT","LOW","CRITICAL","INFO","HIGH","MEDIUM","UNKNOWN")) rows.add(Map.of("id",risk,"riskLevel",risk));
        assertThat(query(null,null,"riskLevel",null,null).apply(rows).items()).extracting(v->v.get("riskLevel"))
                .containsExactly("CRITICAL","HIGH","MEDIUM","LOW","INFO","COMMENT","UNKNOWN");
        var item=Map.<String,Object>of("id","a","reviewState","TO_FIX","origin","SCANNER","sourceType","KB_FINDING","findingKind","FINDING","fileKey","file","riskLevel","HIGH","titleEn","test");
        var q=new ReviewItemQuery(1,50,null,"TO_FIX","SCANNER","KB_FINDING","FINDING","HIGH","file","test","reviewState","asc");
        assertThat(q.apply(List.of(item,Map.of("id","b"))).items()).containsExactly(item);
        assertThat(query(1,50,null,null," ").apply(List.of(item)).total()).isEqualTo(1);
    }
    @Test void rejectsInvalidPageSizeAndSortRatherThanConcatenatingSql() {
        for(var query:List.of(query(0,50,null,null,null),query(1,0,null,null,null),query(1,201,null,null,null),
                query(1,50,"DROP TABLE",null,null),query(1,50,null,"sideways",null))) {
            assertThatThrownBy(()->query.apply(List.of())).isInstanceOf(ApiException.class);
        }
    }
    private ReviewItemQuery query(Integer page,Integer size,String sort,String order,String keyword) {
        return new ReviewItemQuery(page,size,null,null,null,null,null,null,null,keyword,sort,order);
    }
}
