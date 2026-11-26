package cn.rvport.backend.report.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

@Repository
public class ReportAiCheckpointRepository {
    private final DSLContext dsl;
    private final ObjectMapper mapper;
    public ReportAiCheckpointRepository(DSLContext dsl, ObjectMapper mapper) { this.dsl = dsl; this.mapper = mapper; }

    public Optional<List<AiReviewValidator.Result>> find(UUID task, String plan, String config, int batch) {
        return dsl.fetchOptional("select results from report_ai_review_checkpoints where task_id=? and plan_digest=? and configuration_digest=? and batch_index=?",
                task, plan, config, batch).map(row -> {
            try { return mapper.readValue(row.get("results", JSONB.class).data(),
                    mapper.getTypeFactory().constructCollectionType(List.class, AiReviewValidator.Result.class)); }
            catch (java.io.IOException ex) { throw new IllegalStateException("REPORT_AI_CHECKPOINT_INVALID", ex); }
        });
    }

    public void save(UUID task, String plan, String config, int batch, List<AiReviewValidator.Result> results) {
        String json;
        try { json = mapper.writeValueAsString(results); }
        catch (java.io.IOException ex) { throw new IllegalStateException("REPORT_AI_CHECKPOINT_INVALID", ex); }
        int changed = dsl.execute("""
                insert into report_ai_review_checkpoints(task_id,plan_digest,configuration_digest,batch_index,results)
                values(?,?,?,?,?::jsonb) on conflict(task_id,plan_digest,configuration_digest,batch_index) do nothing
                """, task, plan, config, batch, json);
        if (changed == 0 && !find(task, plan, config, batch).orElseThrow().equals(results))
            throw new IllegalStateException("REPORT_AI_CHECKPOINT_CONFLICT");
    }
}
