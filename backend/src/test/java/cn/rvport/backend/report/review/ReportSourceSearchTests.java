package cn.rvport.backend.report.review;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReportSourceSearchTests {
    @SuppressWarnings("unchecked") List<Map<String,Object>> matches(Map<String,Object> result) { return (List<Map<String,Object>>) result.get("matches"); }
    @Test void searchesUnloadedLinesAndKeepsOriginalUtf16Coordinates() {
        String text="blank\r\n".repeat(500)+"😀İx NEEDLE needle";
        var result=ReportSourceSearch.find(text,"needle",false,10);
        assertThat(matches(result)).hasSize(2);
        assertThat(matches(result).getFirst()).containsEntry("lineNumber",501).containsEntry("startColumn",6).containsEntry("endColumn",12);
        assertThat(ReportSourceSearch.find(text,"NEEDLE",true,10)).containsEntry("returnedCount",1);
        assertThat(ReportSourceSearch.find("x\ry\nz","z",false,10)).containsEntry("returnedCount",1);
        assertThat(ReportSourceSearch.find("😀","😀",true,10)).containsEntry("returnedCount",1);
    }
    @Test void reportsAccurateLimitsAndNonOverlappingMatches() {
        assertThat(ReportSourceSearch.find("aaaa","aa",false,2)).containsEntry("returnedCount",2).containsEntry("truncated",false);
        assertThat(ReportSourceSearch.find("aaaaaa","aa",false,2)).containsEntry("returnedCount",2).containsEntry("truncated",true);
        assertThat(ReportSourceSearch.find("","x",false,1)).containsEntry("returnedCount",0).containsEntry("truncated",false);
        assertThat(ReportSourceSearch.find("ab","longer",true,1)).containsEntry("returnedCount",0);
        assertThat(ReportSourceSearch.find("a.b axb","a.b",false,10)).containsEntry("returnedCount",1);
    }
    @Test void previewsAreBoundedWithoutSplittingSurrogates() {
        String text="a".repeat(600)+"😀"+"b".repeat(127)+"needle"+"c".repeat(376)+"😀"+"d".repeat(20);
        var row=matches(ReportSourceSearch.find(text,"needle",false,1)).getFirst();
        String preview=(String) row.get("preview");
        assertThat(preview.length()).isLessThanOrEqualTo(512);
        assertThat(Character.isLowSurrogate(preview.charAt(0))).isFalse();
        assertThat(Character.isHighSurrogate(preview.charAt(preview.length()-1))).isFalse();
        assertThat(row).containsEntry("previewTruncated",true).containsEntry("startColumn",730);
    }
    @Test void rejectsInvalidQueriesAndLimits() {
        for(String keyword:Arrays.asList(null,""," ","x\ny","x\ry","x".repeat(257),"\ud800","\udc00","\ud800x"))
            assertThatThrownBy(()->ReportSourceSearch.validate(keyword,100)).isInstanceOf(cn.rvport.backend.common.error.ApiException.class);
        for(int limit:new int[]{0,201}) assertThatThrownBy(()->ReportSourceSearch.validate("x",limit)).isInstanceOf(cn.rvport.backend.common.error.ApiException.class);
    }
}
