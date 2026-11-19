package cn.rvport.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import cn.rvport.backend.report.review.ReviewWorkspaceRepository;
import cn.rvport.backend.report.review.ReportInputSelectionPolicy;
import java.util.*;
import org.junit.jupiter.api.Test;

class NodeReportInclusionServiceTests {
    private final ReviewWorkspaceRepository reviews=mock();
    private final ReportInputSelectionPolicy policy=mock();
    private final NodeReportInclusionService service=new NodeReportInclusionService(reviews,policy);
    private final UUID project=UUID.randomUUID(),source=UUID.randomUUID(),ref=UUID.randomUUID(),report=UUID.randomUUID();
    private final MigrationInputSourceType type=MigrationInputSourceType.KB_FINDING;

    @Test void emptyRequestsOrForeignRevisionsDoNotReadReportItems() {
        assertThat(service.states(project,source,type,null)).isEmpty();
        assertThat(service.states(project,source,type,List.of())).isEmpty();
        verifyNoInteractions(policy);
        when(policy.current(project)).thenReturn(new ReportInputSelectionPolicy.Selection(project,UUID.randomUUID(),"ctx",List.of()));
        assertThat(service.state(project,source,type,ref)).isEqualTo(NodeReportInclusionState.empty());
        verifyNoInteractions(reviews);
    }

    @Test void inclusionIsScopedToCurrentContextAndOriginalSourceRef() {
        when(policy.current(project)).thenReturn(new ReportInputSelectionPolicy.Selection(project,source,"ctx",List.of()));
        var included=new NodeReportInclusionState(true,report);
        when(reviews.includedSourceRefs(project,source,"ctx",type.name(),List.of(ref))).thenReturn(Map.of(ref,included));
        assertThat(service.state(project,source,type,ref)).isEqualTo(included);
        assertThat(service.state(project,source,type,UUID.randomUUID())).isEqualTo(NodeReportInclusionState.empty());
    }
}
