package cn.rvport.backend.analysis;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SpecializedAnalysisResponseTests {
    @Test void actualDependenciesAndPublicationAreIndependentOfSourceOnlyMissingEvidence() {
        UUID source=UUID.randomUUID(), build=UUID.randomUUID();
        for (var status:SpecializedAnalysisStatus.values()) {
            for (boolean sourceOnly:List.of(true,false)) {
                var run=new SpecializedAnalysisRunView(UUID.randomUUID(),UUID.randomUUID(),null,SpecializedAnalyzerType.FLOATING_POINT,
                        source,build,null,1L,"1","1","2",null,status,null,0,
                        Map.of("inputOrigin",sourceOnly?"SOURCE_DISCOVERY":"BUILD_ARTIFACTS","analysisComplete",true),
                        null,UUID.randomUUID(),null,null,0,0,0,null,null,null,null,null);
                var valid=SpecializedAnalysisResponse.from(run,source,build,1L,true);
                boolean published=List.of(SpecializedAnalysisStatus.COMPLETE,SpecializedAnalysisStatus.COMPLETE_WITH_RISK,SpecializedAnalysisStatus.PARTIAL).contains(status);
                assertThat(valid.resultAvailable()).isEqualTo(published);
                assertThat(valid.analysisComplete()).isEqualTo(published);
                assertThat(valid.inputOrigin()).isEqualTo(sourceOnly?"SOURCE_DISCOVERY":"BUILD_ARTIFACTS");
                assertThat(valid.enhancementAvailable()).isTrue();
                assertThat(valid.resultApplicability().get("applicable")).isEqualTo(published);
                var changed=SpecializedAnalysisResponse.from(run,source,UUID.randomUUID(),2L,false);
                var changedProfile=SpecializedAnalysisResponse.from(run,source,build,1L,true,false);
                assertThat(changedProfile.staleReasons()).containsExactly("ANALYSIS_PROFILE_CHANGED");
                assertThat(changedProfile.resultApplicability().get("applicable")).isEqualTo(false);
                assertThat(changedProfile.enhancementAvailable()).isFalse();
                assertThat(changed.staleReasons()).hasSize(sourceOnly?0:2);
                assertThat(changed.enhancementAvailable()).isFalse();
                var stale=SpecializedAnalysisResponse.from(run,UUID.randomUUID(),null,1L,true);
                assertThat(stale.staleReasons()).contains("SOURCE_REVISION_CHANGED");
                assertThat(stale.enhancementAvailable()).isFalse();
                assertThat(stale.resultApplicability().get("applicable")).isEqualTo(false);
                assertThat(SpecializedAnalysisResponse.from(run,source).currentSourceRevision()).isTrue();
            }
        }
        var noOutput=new SpecializedAnalysisRunView(UUID.randomUUID(),UUID.randomUUID(),null,SpecializedAnalyzerType.FLOATING_POINT,
                source,null,null,null,"1","1","2",null,SpecializedAnalysisStatus.COMPLETE,null,0,Map.of(),
                null,null,null,null,0,0,0,null,null,null,null,null);
        assertThat(SpecializedAnalysisResponse.from(noOutput,source).resultAvailable()).isFalse();
        assertThat(SpecializedAnalysisResponse.from(noOutput,source).analysisComplete()).isFalse();
    }
}
