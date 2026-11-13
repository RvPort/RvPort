package cn.rvport.backend.modelprofile;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import cn.rvport.backend.codexprovider.CodexProviderTestResult;
import cn.rvport.backend.jooq.tables.records.ModelProfileAgentProbeRunsRecord;
import cn.rvport.backend.jooq.tables.records.ModelProfileAgentProbeResultsRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CodexProbeRecoveryTests {
    private static final UUID OWNER_ID = UUID.fromString("a0000000-0000-0000-0000-000000000001");
    final CodexProbeJobRepository jobs=mock(CodexProbeJobRepository.class);
    final ModelProfileRepository profiles=mock(ModelProfileRepository.class);
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final cn.rvport.backend.agentruntime.AgentRuntimeRepository runtimes=mock(cn.rvport.backend.agentruntime.AgentRuntimeRepository.class);
    final CodexProbeRecovery recovery=new CodexProbeRecovery(jobs,profiles,json,runtimes);
    final UUID id=UUID.randomUUID(), run=UUID.randomUUID(), profile=UUID.randomUUID(), runtime=UUID.randomUUID();
    void prepare(String status,String result) {
        when(jobs.recoverable()).thenReturn(List.of(new CodexProbeJobRepository.Job(id, run, profile, runtime, null,
                status, null, OffsetDateTime.now(), result, null, OWNER_ID)));
        var record=new ModelProfileAgentProbeRunsRecord(); record.setCreatedAt(OffsetDateTime.now().minusSeconds(1));
        record.setModelProfileId(profile); record.setOwnerUserId(OWNER_ID);
        when(profiles.findProbeRun(org.mockito.ArgumentMatchers.eq(run), org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(record));
        when(profiles.listProbeResultsByRun(org.mockito.ArgumentMatchers.eq(run), org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
    }
    @Test void terminalResultReplayedWithStableIdentity() throws Exception {
        var result=new CodexProviderTestResult(true,"SUCCESS","ok",Instant.now(),"CODEX",runtime,true,true,List.of(),Map.of());
        prepare("DONE",json.writeValueAsString(result));
        recovery.recover();
        verify(profiles).createProbeResult(eq(ModelProfileService.probeResultId(run,runtime)),eq(run),eq(profile),eq(runtime),
                eq("CODEX"),eq("SUCCESS"),eq(true),eq(true),any(),any(),isNull(),eq("ok"),any(),any(),anyLong());
        verify(jobs).reconcileRuns();
        verifyNoInteractions(runtimes);
        verify(profiles).updateTestResult(eq(profile),eq("SUCCESS"),eq("ok"),any(),eq("CODEX"),eq(true),eq(true),any(),eq(OWNER_ID));
    }
    @Test void expiredAndCorruptResultsAreExplicitFailures() {
        prepare("TIMED_OUT",null); recovery.recover();
        prepare("DONE","invalid"); recovery.recover();
        verify(profiles,times(2)).createProbeResult(any(),eq(run),eq(profile),eq(runtime),eq("CODEX"),eq("FAILED"),
                eq(false),eq(false),any(),any(),eq("AGENT_PROBE_WORKER_FAILED"),anyString(),any(),any(),anyLong());
    }
    @Test void deletedOrAlreadyPersistedRunIsNotRewritten() {
        prepare("DONE","{}");
        when(profiles.findProbeRun(org.mockito.ArgumentMatchers.eq(run), org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty()); recovery.recover();
        prepare("DONE","{}");
        var result=new ModelProfileAgentProbeResultsRecord(); result.setAgentRuntimeId(runtime);
        when(profiles.listProbeResultsByRun(org.mockito.ArgumentMatchers.eq(run), org.mockito.ArgumentMatchers.any())).thenReturn(List.of(result)); recovery.recover();
        verify(profiles,never()).createProbeResult(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        verifyNoInteractions(runtimes);
    }
    @Test void foreignProfileCannotBeMergedEvenWhenRunIdWasReused() {
        prepare("DONE", "{}");
        profiles.findProbeRun(run, OWNER_ID).orElseThrow().setModelProfileId(UUID.randomUUID());
        recovery.recover();
        verify(profiles,never()).createProbeResult(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        verify(profiles,never()).updateTestResult(any(),any(),any(),any(),any(),any(),any(),any(),any());
        verifyNoInteractions(runtimes);
    }
    @Test void recoveredFailureUsesPersistedTerminalTimeRatherThanRecoveryTime() {
        prepare("CANCELLED",null);
        var terminal=OffsetDateTime.parse("2026-10-01T00:00:00Z");
        when(jobs.recoverable()).thenReturn(List.of(new CodexProbeJobRepository.Job(id, run, profile, runtime, null,
                "CANCELLED", null, terminal.plusSeconds(30), null, terminal, OWNER_ID)));
        recovery.recover();
        verifyNoInteractions(runtimes);
        verify(profiles).updateTestResult(eq(profile),eq("FAILED"),anyString(),eq(terminal),eq("CODEX"),
                eq(false),eq(false),any(),eq(OWNER_ID));
    }
    @Test void persistedDoneFailurePreservesOriginalTestTime() throws Exception {
        var terminal=OffsetDateTime.parse("2026-10-01T00:00:00Z");
        var result=new CodexProviderTestResult(false,"FAILED","bad",terminal.minusSeconds(2).toInstant(),
                "CODEX",runtime,false,false,List.of(),Map.of());
        prepare("DONE",json.writeValueAsString(result));
        when(jobs.recoverable()).thenReturn(List.of(new CodexProbeJobRepository.Job(id, run, profile, runtime, null,
                "DONE", null, terminal.plusSeconds(30), json.writeValueAsString(result), terminal, OWNER_ID)));
        recovery.recover();
        verifyNoInteractions(runtimes);
        verify(profiles).updateTestResult(eq(profile),eq("FAILED"),anyString(),eq(terminal.minusSeconds(2)),
                eq("CODEX"),eq(false),eq(false),any(),eq(OWNER_ID));
    }
}
