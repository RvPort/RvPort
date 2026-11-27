package cn.rvport.backend.report.review;

import cn.rvport.backend.common.error.ApiException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 所有下游复用同一资格判断；空的已确认清单也是可用结果。 */
@Service
public class CurrentFixListResolver {
    public record Current(boolean available,UUID reportId,UUID confirmationId,Long draftVersion,long itemCount,String reasonCode) { }
    private final ReviewWorkspaceRepository reviews;
    private final ReportInputSelectionPolicy policy;
    public CurrentFixListResolver(ReviewWorkspaceRepository reviews,ReportInputSelectionPolicy policy) { this.reviews=reviews; this.policy=policy; }
    public Current current(UUID project) {
        return current(project, () -> policy.current(project).contextHash());
    }
    Current current(UUID project, String contextHash) {
        return current(project, () -> contextHash);
    }
    private Current current(UUID project, java.util.function.Supplier<String> contextHash) {
        UUID report=reviews.currentReport(project).orElse(null);
        if(report==null) return new Current(false,null,null,null,0,"REPORT_NOT_GENERATED");
        var draft=reviews.draft(report).orElse(null);
        if(draft==null) return new Current(false,report,null,null,0,"REPORT_REVIEW_UNAVAILABLE");
        if(!draft.contextHash().equals(contextHash.get())) return new Current(false,report,null,draft.version(),0,"REPORT_CONTEXT_CHANGED");
        if(!draft.writable() || !"CURRENT".equals(draft.publishState())) return new Current(false,report,null,draft.version(),0,"REPORT_READ_ONLY");
        if(draft.currentConfirmationId()==null) return new Current(false,report,null,draft.version(),0,
                draft.previousConfirmationId()==null?"UNCONFIRMED":"NEEDS_CONFIRMATION");
        var confirmation=reviews.confirmation(report,draft.currentConfirmationId()).orElse(null);
        if(confirmation==null) return new Current(false,report,null,draft.version(),0,"REPORT_REVIEW_UNAVAILABLE");
        if(((Number)confirmation.get("draftVersion")).longValue()!=draft.version()) return new Current(false,report,null,draft.version(),0,"NEEDS_CONFIRMATION");
        long count=((Number)confirmation.get("itemCount")).longValue();
        return new Current(true,report,draft.currentConfirmationId(),draft.version(),count,count==0?"CONFIRMED_EMPTY":"CONFIRMED_WITH_ITEMS");
    }
    public Current require(UUID project,UUID expectedConfirmation) {
        Current current=current(project);
        if(!current.available() || expectedConfirmation!=null && !expectedConfirmation.equals(current.confirmationId()))
            throw new ApiException(HttpStatus.CONFLICT,"CURRENT_FIX_LIST_UNAVAILABLE","请确认当前版本的待修复清单",
                    Map.of("reasonCode",current.reasonCode()));
        return current;
    }
    public List<Map<String,Object>> items(Current current) {
        if(!current.available()) throw new IllegalArgumentException("Current confirmation is required");
        return reviews.confirmationItems(current.reportId(),current.confirmationId()).stream()
                .filter(item->"TO_FIX".equals(item.get("reviewState"))).toList();
    }
}
