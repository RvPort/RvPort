package cn.rvport.backend.report.review;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReasonDescriptionResolverTests {
    @Test void describesKnownCodesWithinTheirNamespaceAndPreservesUnknownCodes() {
        assertThat(ReasonDescriptionResolver.describe("KB_FINDING","input-or-relation-unknown"))
                .containsEntry("message","输入信息或关联关系尚无法确定").containsKey("messageEn");
        assertThat(ReasonDescriptionResolver.describe("OTHER","input-or-relation-unknown"))
                .containsEntry("message","未提供该原因码的解释");
        assertThat(ReasonDescriptionResolver.describe("KB_FINDING","future-code"))
                .containsEntry("code","future-code").containsEntry("catalogVersion",ReasonDescriptionResolver.VERSION);
        assertThat(ReasonDescriptionResolver.describe("ANY","SOURCE_CONTENT_CHANGED")).containsEntry("message","源码制品内容与固定摘要不一致");
    }
    @Test void extractsOnlyReasonFieldsWithoutChangingOriginalEvidence() {
        var evidence=Map.of("id","hidden","conclusion",Map.of("reason","input-or-relation-unknown"),
                "summary",Map.of("analysisReasonCodes",List.of("FUTURE","FUTURE"),"description","not a code"),
                "reason", "ordinary reason sentence", "unknown",Map.of("reason","hidden"));
        var result=ReasonDescriptionResolver.evidence("KB_FINDING",evidence);
        assertThat(result).extracting(row->row.get("code")).containsExactly("input-or-relation-unknown","FUTURE");
        assertThat(evidence).doesNotContainKey("displayEvidence");
        assertThat(ReasonDescriptionResolver.evidence("KB_FINDING",null)).isEmpty();
        assertThat(ReasonDescriptionResolver.evidence("KB_FINDING",42)).isEmpty();
        var root=new HashMap<String,Object>();var next=root;
        for(int i=0;i<15;i++){var child=new HashMap<String,Object>();next.put("conclusion",child);next=child;}
        next.put("reasonCode","hidden");assertThat(ReasonDescriptionResolver.evidence("KB_FINDING",root)).isEmpty();
    }
    @Test void fixedSnapshotCapturesSourceReasonsAndDoesNotInventItems() {
        var selection=new ReportInputSelectionPolicy.Selection(UUID.randomUUID(),UUID.randomUUID(),"ctx",List.of(
                new ReportInputSelectionPolicy.Source("KB_FINDING",null,"FAILED",null,"COMPILE_DATABASE_UNAVAILABLE",Map.of(),List.of(),List.of())));
        var snapshot=ReasonDescriptionResolver.snapshot(List.of(),selection);
        assertThat(snapshot).containsEntry("items",Map.of()).containsKey("sources");
    }
}
