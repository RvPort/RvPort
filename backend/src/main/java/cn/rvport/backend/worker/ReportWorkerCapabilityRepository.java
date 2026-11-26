package cn.rvport.backend.worker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

/** 复用既有可空 metadata，旧 Worker 无观测自然为 UNKNOWN。 */
@Repository
public class ReportWorkerCapabilityRepository {
    private final DSLContext db; private final ObjectMapper mapper;
    public ReportWorkerCapabilityRepository(DSLContext db,ObjectMapper mapper) { this.db=db;this.mapper=mapper; }
    public void publish(String worker,String queue,Map<String,Object> observation) {
        try {
            db.execute("update worker_instances set metadata=coalesce(metadata,'{}'::jsonb) || jsonb_build_object('taskQueue',?::text,'reportAi',?::jsonb) where instance_id=?",
                    queue,mapper.writeValueAsString(observation),worker);
        } catch(java.io.IOException ex) { throw new IllegalArgumentException("WORKER_CAPABILITY_INVALID",ex); }
    }
    public List<Map<String,Object>> observations(String queue,OffsetDateTime since) {
        return db.fetch("""
                select metadata->'reportAi' as observation from worker_instances
                where status='ONLINE' and concurrency_limit>0 and active_concurrency>0 and last_heartbeat_at>=?::timestamptz
                  and (metadata->>'taskQueue'=? or metadata->>'taskQueue' is null)
                """,since,queue).map(row->{
                    JSONB json=row.get("observation",JSONB.class);
                    if(json==null) return Map.<String,Object>of();
                    try {
                        Map<String,Object> value=mapper.readValue(json.data(),new TypeReference<Map<String,Object>>(){});
                        return value==null?Map.<String,Object>of():value;
                    }
                    catch(java.io.IOException ex) { return Map.<String,Object>of(); }
                });
    }
}
