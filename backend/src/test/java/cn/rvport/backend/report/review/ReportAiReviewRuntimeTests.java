package cn.rvport.backend.report.review;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.rvport.backend.agentruntime.*;
import cn.rvport.backend.artifact.*;
import cn.rvport.backend.codexprovider.*;
import cn.rvport.backend.jooq.tables.records.*;
import cn.rvport.backend.modelprofile.*;
import cn.rvport.backend.task.TaskView;
import cn.rvport.backend.worker.TaskExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

class ReportAiReviewRuntimeTests {
    final UUID project=UUID.randomUUID(), revision=UUID.randomUUID(), actor=UUID.randomUUID(),
            profile=UUID.randomUUID(),runtime=UUID.randomUUID(), artifact=UUID.randomUUID(),id=UUID.randomUUID();
    final ModelProfileService models=mock(ModelProfileService.class);
    final AgentRuntimeAdapterRegistry registry=mock(AgentRuntimeAdapterRegistry.class);
    final AgentRuntimeAdapter adapter=mock(AgentRuntimeAdapter.class);
    final StoredArtifactService artifacts=mock(StoredArtifactService.class);
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final ReportAiReviewRuntime service=new ReportAiReviewRuntime(models,registry,artifacts,json);
    final ModelExecutionSelection selection=new ModelExecutionSelection(actor,profile,runtime);
    final TaskExecutionContext context=mock(TaskExecutionContext.class);
    final byte[] bytes="int x;".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    final String hash=SourceSelection.hash(bytes);
    @BeforeEach void setup() {
        var managed=new AgentRuntimesRecord(); managed.setId(runtime); managed.setAgentType("OPENCODE");
        var model=new ModelProfilesRecord(); model.setId(profile); model.setOwnerUserId(actor);
        var config=new AgentModelRuntimeConfig(managed,model,new CodexProviderResolvedConfig(true,"opencode",CodexProviderType.OPENAI_COMPATIBLE,"http://mock.invalid","fixture",CodexProviderWireApi.CHAT,"fake",10,15));
        when(models.resolveExecution(actor,project,profile,runtime)).thenReturn(config);
        var task=new TaskView(UUID.randomUUID(),project,"REPORT_GENERATE",cn.rvport.backend.task.TaskStatus.RUNNING,0,null,actor,null,null,null,null,60,null,null,0);
        when(context.task()).thenReturn(task); when(registry.get("OPENCODE")).thenReturn(adapter);
        when(artifacts.download(artifact)).thenReturn(new StoredArtifactResource(null,new ByteArrayResource(bytes)));
    }
    ReportReviewManifest manifest(ReportMode mode,String path,int start,ReviewState state) {
        var file=new ReportSourceSnapshotService.File("key","src/a.c",hash,1,artifact,true,null);
        var item=new ReviewItemSnapshot(id,"SCANNER","KB_FINDING",UUID.randomUUID(),UUID.randomUUID(),"i","e",Map.of("riskLevel","LOW"),
                "结果","Finding","key",path,start,1,start,1,hash,null,null,new ReviewDecision(state,null,null),null,Map.of(),actor);
        return new ReportReviewManifest(project,revision,"ctx",mode,null,new ReportSourceSnapshotService.Manifest(revision,"digest",List.of(file),null),List.of(item),null,null);
    }
    @Test void standardNeverResolvesModelAndExcludedOnlyStillChecksIdentityWithoutCallingCli() throws Exception {
        var standard=service.run(manifest(ReportMode.STANDARD,"src/a.c",1,ReviewState.PENDING_REVIEW),null,context);
        assertThat(standard.summary()).containsEntry("execution","STANDARD"); verifyNoInteractions(models,registry);
        var skipped=service.run(manifest(ReportMode.AI_ENHANCED,"src/a.c",1,ReviewState.EXCLUDED),selection,context);
        assertThat(skipped.summary()).containsEntry("execution","NOT_REQUIRED").containsEntry("skippedInheritedExcluded",1);
        verify(models).resolveExecution(actor,project,profile,runtime); verifyNoInteractions(registry,artifacts);
    }
    @Test void executesSelectedAdapterWithFrozenSourceAndValidatesItsEvidence() throws Exception {
        java.util.concurrent.atomic.AtomicReference<Path> path=new java.util.concurrent.atomic.AtomicReference<>();
        when(adapter.run(any())).thenAnswer(invocation->{
            AgentRuntimeExecutionRequest request=invocation.getArgument(0); path.set(request.workspace());
            assertThat(request.readOnlyInputs()).isTrue(); assertThat(request.model().model()).isEqualTo("fixture");
            assertThat(Files.readAllBytes(path.get().resolve("source/src/a.c"))).isEqualTo(bytes);
            var row=json.readTree(Files.readString(path.get().resolve("input/findings.jsonl")));
            assertThat(row.path("inputId").asText()).isEqualTo(id.toString());
            Files.writeString(path.get().resolve("output/results.jsonl"),json.writeValueAsString(Map.of("inputId",id,
                    "verdict","CONFIRMED_ISSUE","reason","目标缺少对应实现","reasonEn","The target implementation is missing","evidenceRefs",row.get("evidenceRefs"))));
            return new CodexProcessResult(0,false,"","");
        });
        var result=service.run(manifest(ReportMode.AI_ENHANCED,"src/a.c",1,ReviewState.PENDING_REVIEW),selection,context);
        assertThat(result.results()).singleElement().satisfies(r->assertThat(r.inputId()).isEqualTo(id));
        assertThat(result.summary()).containsEntry("valid",1); assertThat(path.get()).doesNotExist();
        verify(models,never()).resolveAgentForStage(any(),any());
    }
    @Test void timeoutNonzeroMissingOutputAndTamperedSourceNeverPublishAReview() throws Exception {
        var manifest=manifest(ReportMode.AI_ENHANCED,"src/a.c",1,ReviewState.TO_FIX);
        when(adapter.run(any())).thenReturn(new CodexProcessResult(0,true,"",""),new CodexProcessResult(126,false,"","REPORT_AI_SANDBOX_UNAVAILABLE"),
                new CodexProcessResult(126,false,"","RVPORT_OCX_READINESS_FAILED"),
                new CodexProcessResult(1,false,"",""),new CodexProcessResult(0,false,"",""));
        for(String code:List.of("REPORT_AI_TIMEOUT","REPORT_AI_SANDBOX_UNAVAILABLE","REPORT_AI_EXECUTION_FAILED","REPORT_AI_EXECUTION_FAILED","REPORT_AI_OUTPUT_INVALID"))
            assertThatThrownBy(()->service.run(manifest,selection,context)).isInstanceOf(IOException.class).hasMessage(code);
        when(artifacts.download(artifact)).thenReturn(new StoredArtifactResource(null,new ByteArrayResource(new byte[]{1})));
        assertThatThrownBy(()->service.run(manifest,selection,context)).hasMessage("SOURCE_CONTENT_CHANGED");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"{} {}", "{\"inputId\":1,\"inputId\":2}", "{broken"})
    void rejectsMalformedJsonLinesWithoutPublishingOrEchoingModelOutput(String line) throws Exception {
        when(adapter.run(any())).thenAnswer(call->{
            AgentRuntimeExecutionRequest request=call.getArgument(0);
            Files.writeString(request.workspace().resolve("output/results.jsonl"),line);
            return new CodexProcessResult(0,false,"","");
        });
        assertThatThrownBy(()->service.run(manifest(ReportMode.AI_ENHANCED,"src/a.c",1,ReviewState.PENDING_REVIEW),selection,context))
                .isInstanceOf(IOException.class).hasMessage("REPORT_AI_OUTPUT_INVALID");
    }
    @Test void absenceOfSourceCannotSubstantiateAFirmConclusion() throws Exception {
        when(adapter.run(any())).thenAnswer(invocation->{
            AgentRuntimeExecutionRequest request=invocation.getArgument(0);
            Files.writeString(request.workspace().resolve("output/results.jsonl"),json.writeValueAsString(Map.of("inputId",id,
                    "verdict","SUSPECTED_FALSE_POSITIVE","reason","未找到源码","reasonEn","Missing source","evidenceRefs",List.of("analysis:"+id))));
            return new CodexProcessResult(0,false,"","");
        });
        assertThatThrownBy(()->service.run(manifest(ReportMode.AI_ENHANCED,"unknown.c",1,ReviewState.PENDING_REVIEW),selection,context))
                .hasMessage("REPORT_AI_EVIDENCE_INVALID");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"input/findings.jsonl","input/manifest.json","source/src/a.c","missing-source"})
    void modifiedOrRemovedEvidenceCannotLeadToAnAutomaticFixItem(String file) throws Exception {
        when(adapter.run(any())).thenAnswer(invocation -> {
            AgentRuntimeExecutionRequest request = invocation.getArgument(0);
            if (file.equals("missing-source")) Files.delete(request.workspace().resolve("source/src/a.c"));
            else Files.writeString(request.workspace().resolve(file),"changed");
            return new CodexProcessResult(0,false,"","");
        });
        assertThatThrownBy(() -> service.run(manifest(ReportMode.AI_ENHANCED,"src/a.c",1,ReviewState.PENDING_REVIEW),selection,context))
                .hasMessage("REPORT_AI_INPUT_MODIFIED");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={0,2})
    void unavailableLineIsValidOnlyWithAnUncertainVerdict(int line) throws Exception {
        when(adapter.run(any())).thenAnswer(invocation -> {
            AgentRuntimeExecutionRequest request = invocation.getArgument(0);
            var row = json.readTree(Files.readString(request.workspace().resolve("input/findings.jsonl")));
            assertThat(row.path("sourceAvailable").asBoolean()).isFalse();
            var result = Map.of("inputId",id,"verdict","INSUFFICIENT_EVIDENCE","reason","行号超出范围",
                    "reasonEn","Source line unavailable","evidenceRefs",row.get("evidenceRefs"));
            Files.writeString(request.workspace().resolve("output/results.jsonl"),"\n" + json.writeValueAsString(result));
            return new CodexProcessResult(0,false,"","");
        });
        var result = service.run(manifest(ReportMode.AI_ENHANCED,"src/a.c",line,ReviewState.PENDING_REVIEW),selection,context);
        assertThat(result.results().getFirst().verdict()).isEqualTo(AiReviewValidator.Verdict.INSUFFICIENT_EVIDENCE);
    }
}
