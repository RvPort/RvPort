package cn.rvport.backend.worker;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.rvport.backend.agentruntime.AgentRuntimeRepository;
import cn.rvport.backend.config.*;
import cn.rvport.backend.jooq.tables.records.AgentRuntimesRecord;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReportWorkerCapabilityServiceTests {
    final AgentRuntimesRecord runtime=new AgentRuntimesRecord();
    final OffsetDateTime now=OffsetDateTime.now();
    ReportWorkerCapabilityServiceTests() { runtime.setId(UUID.randomUUID());runtime.setAgentType("CODEX");runtime.setEnabled(true);runtime.setExecutablePath("codex"); }
    Map<String,Object> observation(String status) {
        return new HashMap<>(Map.of("status",status,"policyVersion",ReportWorkerCapabilityService.POLICY,
                "runtimes",Map.of(runtime.getId().toString(),ReportWorkerCapabilityService.identity(runtime)),
                "observedAt",now.minusSeconds(1).toString(),"expiresAt",now.plusSeconds(80).toString()));
    }
    @Test void aggregatesOnlyCurrentMatchingObservations() {
        for(String status:List.of("AVAILABLE","UNAVAILABLE","UNKNOWN"))
            assertThat(ReportWorkerCapabilityService.aggregate(List.of(observation(status)),runtime,now)).containsEntry("status",status);
        assertThat(ReportWorkerCapabilityService.aggregate(List.of(),runtime,now)).containsEntry("status","UNKNOWN");
        assertThat(ReportWorkerCapabilityService.aggregate(List.of(Map.of()),runtime,now)).containsEntry("reasonCode","WORKER_CAPABILITY_UNKNOWN");
        assertThat(ReportWorkerCapabilityService.aggregate(List.of(observation("AVAILABLE"),observation("UNAVAILABLE")),runtime,now))
                .containsEntry("reasonCode","WORKER_CAPABILITY_MIXED");
        var changed=observation("AVAILABLE");changed.put("runtimes",Map.of());
        assertThat(ReportWorkerCapabilityService.aggregate(List.of(changed),runtime,now)).containsEntry("status","UNKNOWN");
        changed.put("runtimes",false);
        assertThat(ReportWorkerCapabilityService.aggregate(List.of(changed),runtime,now)).containsEntry("status","UNKNOWN");
        changed=observation("AVAILABLE");changed.put("policyVersion","old");
        assertThat(ReportWorkerCapabilityService.aggregate(List.of(changed),runtime,now)).containsEntry("status","UNKNOWN");
    }
    @Test void rejectsInvalidOrStaleTimesAndFindsOldestValidObservation() {
        for(String expiry:List.of(now.toString(),now.minusSeconds(10).toString(),now.plusSeconds(100).toString(),"invalid")) {
            var row=observation("AVAILABLE");row.put("expiresAt",expiry);
            assertThat(ReportWorkerCapabilityService.aggregate(List.of(row),runtime,now)).containsEntry("status","UNKNOWN");
        }
        var future=observation("AVAILABLE");future.put("observedAt",now.plusSeconds(1).toString());
        assertThat(ReportWorkerCapabilityService.aggregate(List.of(future),runtime,now)).containsEntry("reasonCode","WORKER_CAPABILITY_STALE");
        var older=observation("AVAILABLE");older.put("observedAt",now.minusSeconds(20).toString());older.put("expiresAt",now.plusSeconds(60).toString());
        var result=ReportWorkerCapabilityService.aggregate(List.of(observation("AVAILABLE"),older,observation("AVAILABLE")),runtime,now);
        assertThat(result).containsEntry("observedAt",now.minusSeconds(20)).containsEntry("expiresAt",now.plusSeconds(60));
    }
    @Test void heartbeatCachesActualProbeButDoesNotExtendObservationAndGetNeverProbes() {
        var repository=mock(ReportWorkerCapabilityRepository.class);var runtimes=mock(AgentRuntimeRepository.class);var probe=mock(ReportSandboxProbe.class);
        var queue=new TaskQueueProperties("x","q","r",null,null,null,3);
        var workers=new WorkerProperties(true,"worker",RvPortAppMode.WORKER,1,8,10,60);
        var service=new ReportWorkerCapabilityService(repository,runtimes,probe,queue,workers);
        var disabled=new AgentRuntimesRecord();disabled.setEnabled(false);
        when(runtimes.list()).thenReturn(List.of(runtime,disabled));when(probe.probe()).thenReturn("AVAILABLE");
        service.heartbeat("worker");service.heartbeat("worker");
        var values=org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(repository,times(2)).publish(eq("worker"),eq("q"),values.capture());
        assertThat(values.getAllValues().get(0)).isSameAs(values.getAllValues().get(1));
        verify(probe).probe();
        clearInvocations(probe);
        assertThat(service.current(runtime,false)).containsEntry("status","NOT_REQUIRED");
        assertThat(service.current(null,true)).containsEntry("status","UNKNOWN");
        when(repository.observations(eq("q"),any())).thenReturn(List.of(observation("AVAILABLE")));
        assertThat(service.current(runtime,true)).containsEntry("status","AVAILABLE");
        verifyNoInteractions(probe);
    }
}
