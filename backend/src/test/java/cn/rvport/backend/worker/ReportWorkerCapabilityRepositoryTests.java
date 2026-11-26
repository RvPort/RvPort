package cn.rvport.backend.worker;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.rvport.backend.testfixture.JooqMockDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.time.OffsetDateTime;
import java.util.*;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

class ReportWorkerCapabilityRepositoryTests {
    @Test void updatesOnlyOwnMetadataAndReadsEligibleQueueWithoutBusyCountFilter() throws Exception {
        try(var db=new JooqMockDatabase()) {
            var repo=new ReportWorkerCapabilityRepository(db.dsl(),new ObjectMapper());
            db.enqueueRows(1);repo.publish("worker","queue",Map.of("status","AVAILABLE"));
            assertThat(db.lastExecution().bindings()).containsExactly("queue","{\"status\":\"AVAILABLE\"}","worker");
            var fields=new Field<?>[]{DSL.field("observation",JSONB.class)};
            var now=OffsetDateTime.now();
            for(JSONB observation:Arrays.asList(null,JSONB.valueOf("null"),JSONB.valueOf("[]"),JSONB.valueOf("{bad"),JSONB.valueOf("{\"status\":\"AVAILABLE\"}"))) {
                db.enqueueResult(db.result(fields,new Object[]{observation}));
                var result=repo.observations("queue",now);
                assertThat(result).hasSize(1);
                if(observation!=null && observation.data().contains("AVAILABLE")) assertThat(result.get(0)).containsEntry("status","AVAILABLE");
                else assertThat(result.get(0)).isEmpty();
                assertThat(db.lastExecution().bindings()[1]).isEqualTo("queue");
                assertThat(db.lastExecution().bindings()[0].toString()).startsWith(now.toLocalDate().toString());
                assertThat(db.lastExecution().sql()).contains("status='ONLINE'","active_concurrency>0","last_heartbeat_at>=?::timestamptz").doesNotContain("running_task_count");
            }
            var broken=mock(ObjectMapper.class);when(broken.writeValueAsString(any())).thenThrow(new JsonProcessingException("bad"){});
            assertThatThrownBy(()->new ReportWorkerCapabilityRepository(db.dsl(),broken).publish("w","q",Map.of())).hasMessage("WORKER_CAPABILITY_INVALID");
            db.verifyComplete();
        }
    }
}
