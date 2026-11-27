package cn.rvport.backend.report.review;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.rvport.backend.common.error.ApiException;
import java.util.*;
import org.junit.jupiter.api.Test;

class CurrentFixListResolverTests {
    final UUID project=UUID.randomUUID(),report=UUID.randomUUID(),source=UUID.randomUUID(),confirmation=UUID.randomUUID();
    final ReviewWorkspaceRepository reviews=mock(ReviewWorkspaceRepository.class);
    final ReportInputSelectionPolicy policy=mock(ReportInputSelectionPolicy.class);
    final CurrentFixListResolver resolver=new CurrentFixListResolver(reviews,policy);
    void draft(boolean writable,String state,String context,UUID current,UUID prior) {
        when(reviews.currentReport(project)).thenReturn(Optional.of(report));
        when(policy.current(project)).thenReturn(new ReportInputSelectionPolicy.Selection(project,source,"valid",List.of()));
        when(reviews.draft(report)).thenReturn(Optional.of(new ReviewWorkspaceRepository.Draft(report,project,source,context,state,2,2,writable,current,prior)));
    }
    @Test void reportsSpecificReasonsForUnusableLists() {
        assertThat(resolver.current(project).reasonCode()).isEqualTo("REPORT_NOT_GENERATED");
        when(reviews.currentReport(project)).thenReturn(Optional.of(report));
        assertThat(resolver.current(project).reasonCode()).isEqualTo("REPORT_REVIEW_UNAVAILABLE");
        draft(true,"CURRENT","stale",confirmation,null);
        assertThat(resolver.current(project).reasonCode()).isEqualTo("REPORT_CONTEXT_CHANGED");
        for(boolean writable:List.of(true,false)) {
            draft(writable,"HISTORICAL_SUPERSEDED","valid",confirmation,null);
            assertThat(resolver.current(project).reasonCode()).isEqualTo("REPORT_READ_ONLY");
        }
        draft(true,"CURRENT","valid",null,null);assertThat(resolver.current(project).reasonCode()).isEqualTo("UNCONFIRMED");
        draft(true,"CURRENT","valid",null,confirmation);assertThat(resolver.current(project).reasonCode()).isEqualTo("NEEDS_CONFIRMATION");
        draft(true,"CURRENT","valid",confirmation,null);
        assertThat(resolver.current(project).reasonCode()).isEqualTo("REPORT_REVIEW_UNAVAILABLE");
        when(reviews.confirmation(report,confirmation)).thenReturn(Optional.of(Map.of("draftVersion",1L,"itemCount",1)));
        assertThatThrownBy(()->resolver.require(project,confirmation)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->resolver.items(resolver.current(project))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void checksExactVersionAndReturnsOnlySelectedItemsIncludingValidEmptyList() {
        draft(true,"CURRENT","valid",confirmation,null);
        when(reviews.confirmation(report,confirmation)).thenReturn(Optional.of(Map.of("draftVersion",2L,"itemCount",0)));
        assertThat(resolver.require(project,confirmation).reasonCode()).isEqualTo("CONFIRMED_EMPTY");
        assertThatThrownBy(()->resolver.require(project,UUID.randomUUID())).isInstanceOf(ApiException.class);
        when(reviews.confirmation(report,confirmation)).thenReturn(Optional.of(Map.of("draftVersion",2L,"itemCount",1)));
        var selected=Map.<String,Object>of("reviewState","TO_FIX","itemId",UUID.randomUUID());
        when(reviews.confirmationItems(report,confirmation)).thenReturn(List.of(selected,Map.of("reviewState","EXCLUDED")));
        var current=resolver.require(project,null);
        assertThat(current.available()).isTrue();assertThat(current.reasonCode()).isEqualTo("CONFIRMED_WITH_ITEMS");
        assertThat(resolver.items(current)).containsExactly(selected);
    }
}
