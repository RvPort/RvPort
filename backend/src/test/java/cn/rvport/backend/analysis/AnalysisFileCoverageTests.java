package cn.rvport.backend.analysis;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AnalysisFileCoverageTests {
    @Test void groupsScopeCountsWithoutInventingExecutionOrDuplicatingOneFileWithinLanguage() {
        var groups=AnalysisFileCoverage.byLanguage(List.of(
                Map.of("languages",List.of("c","cpp","c"),"status","PLANNED"),
                Map.of("language","c","status","FAILED"),
                Map.of("language","rust","status","NOT_SELECTED"),
                Map.of("languages",List.of(),"status","UNSUPPORTED"), Map.of()));
        assertThat(groups).hasSize(4);
        assertThat(groups.get(0)).isEqualTo(Map.of("language","c","knownFileCount",2,
                "manifestFileCounts",Map.of("PLANNED",1,"FAILED",1),"executionKnown",false));
        assertThat(groups.get(3)).containsEntry("manifestFileCounts",Map.of("UNSUPPORTED",1,"UNKNOWN",1));
        assertThat(groups).allSatisfy(group->assertThat(group).doesNotContainKeys("analyzedFileCount","failedTuCount"));
        assertThat(AnalysisFileCoverage.byLanguage(List.of())).isEmpty();
    }
}
