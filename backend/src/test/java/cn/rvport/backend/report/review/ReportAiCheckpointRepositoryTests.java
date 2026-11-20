package cn.rvport.backend.report.review;

import static org.assertj.core.api.Assertions.*;
import cn.rvport.backend.testfixture.JooqMockDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.jooq.JSONB;
import org.junit.jupiter.api.Test;

class ReportAiCheckpointRepositoryTests {
    @Test void bindsExactIdentityAndNeverOverwritesDifferentValidatedResults() throws Exception {
        try (var db = new JooqMockDatabase()) {
            var mapper = new ObjectMapper(); var repo = new ReportAiCheckpointRepository(db.dsl(), mapper);
            UUID task = UUID.randomUUID();
            var results = List.of(new AiReviewValidator.Result(UUID.randomUUID(),AiReviewValidator.Verdict.INSUFFICIENT_EVIDENCE,
                    "不足","Insufficient",null,null,List.of()));
            db.enqueueRows(1); repo.save(task,"plan","config",0,results);
            assertThat(db.lastExecution().sql()).contains("on conflict", "do nothing");
            assertThat(db.lastExecution().bindings()).containsExactly(task,"plan","config",0,mapper.writeValueAsString(results));
            db.enqueueRows(0);
            ReviewWorkspaceRepositoryTests.enqueue(db,Map.of("results",JSONB.valueOf(mapper.writeValueAsString(results))));
            repo.save(task,"plan","config",0,results);
            db.enqueueRows(0);
            ReviewWorkspaceRepositoryTests.enqueue(db,Map.of("results",JSONB.valueOf("[]")));
            assertThatThrownBy(()->repo.save(task,"plan","config",0,results)).hasMessage("REPORT_AI_CHECKPOINT_CONFLICT");
            ReviewWorkspaceRepositoryTests.enqueue(db,Map.of("results",JSONB.valueOf("{broken")));
            assertThatThrownBy(()->repo.find(task,"plan","config",0)).hasMessage("REPORT_AI_CHECKPOINT_INVALID");
            db.enqueueResult(db.result(new org.jooq.Field<?>[]{org.jooq.impl.DSL.field("results",JSONB.class)}));
            assertThat(repo.find(task,"other-plan","other-config",1)).isEmpty();
            db.verifyComplete();
        }
    }
}
