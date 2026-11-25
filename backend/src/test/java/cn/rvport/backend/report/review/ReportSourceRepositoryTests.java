package cn.rvport.backend.report.review;

import static org.assertj.core.api.Assertions.*;
import cn.rvport.backend.testfixture.JooqMockDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReportSourceRepositoryTests {
    @Test void queriesOnlySuccessfulExactContextsAndBindsUntrustedValues() throws Exception {
        UUID project=UUID.randomUUID(),source=UUID.randomUUID(),env=UUID.randomUUID(),build=UUID.randomUUID(),snapshot=UUID.randomUUID();
        try(var db=new JooqMockDatabase()) {
            var repo=new ReportSourceRepository(db.dsl(),new ReviewWorkspaceRepository(db.dsl(),new ObjectMapper()));
            ReviewWorkspaceRepositoryTests.enqueue(db,Map.of("id",build));
            assertThat(repo.kb(project,source,env,7,build)).isPresent();
            assertThat(db.lastExecution().sql()).contains("status='SUCCEEDED'","build_run_id=?","runtime_environment_version=?").doesNotContain("case");
            assertThat(db.lastExecution().bindings()).containsExactly(project,source,env,7L,build);
            ReviewWorkspaceRepositoryTests.enqueue(db,Map.of("id",build));
            assertThat(repo.dependency(project,source,env,7,build,snapshot,snapshot)).isPresent();
            // 快照 id 由 DependencyAnalysisHandler 写在 summary.environmentRefresh 下，谓词必须同路径取值。
            assertThat(db.lastExecution().sql())
                    .contains("summary->'environmentRefresh'->>'sourceSnapshotId'","summary->'environmentRefresh'->>'targetSnapshotId'","source_build_run_id=?")
                    .doesNotContain("summary->>'sourceSnapshotId'");
            assertThat(db.lastExecution().bindings()).containsExactly(project,source,env,7L,build,snapshot.toString(),snapshot.toString());
            ReviewWorkspaceRepositoryTests.enqueue(db,Map.of("id",build));
            assertThat(repo.specialized(project,source,"FLOATING_POINT",build,7L,"AST_ONLY",null)).isPresent();
            assertThat(db.lastExecution().sql()).contains("status in ('COMPLETE','COMPLETE_WITH_RISK')","is not distinct from").doesNotContain("'PARTIAL'");
            for(String type:List.of("KB_FINDING","DEPENDENCY_RESULT","FLOATING_POINT_FINDING","MEMORY_ORDER_FINDING")) {
                ReviewWorkspaceRepositoryTests.enqueue(db,Map.of("id",build));
                assertThat(repo.findings(type,build)).containsExactly(Map.of("id",build));
                assertThat(db.lastExecution().bindings()).containsExactly(build);
            }
            assertThat(db.lastExecution().sql()).contains("'SOURCE_CANDIDATE'","'INCONCLUSIVE'").doesNotContain("'DISCHARGED'");
            assertThatThrownBy(()->repo.findings("unknown",build)).isInstanceOf(IllegalArgumentException.class);
            db.verifyComplete();
        }
    }
}
