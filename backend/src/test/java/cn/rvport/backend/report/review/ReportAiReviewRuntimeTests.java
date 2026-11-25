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
    @Test void resumesOnlyValidatedBatchesAndDoesNotPublishIncompleteSecondBatch() throws Exception {
        var initial=manifest(ReportMode.AI_ENHANCED,"src/a.c",1,ReviewState.PENDING_REVIEW);
        var items=new ArrayList<ReviewItemSnapshot>();
        for(int index=1;index<=33;index++) items.add(new ReviewItemSnapshot(new UUID(0,index),"SCANNER","KB_FINDING",
                revision,new UUID(1,index),"identity"+index,"evidence",Map.of("riskLevel","LOW"),"候选","Candidate",
                "key","src/a.c",1,1,1,1,hash,null,null,new ReviewDecision(ReviewState.PENDING_REVIEW,null,null),null,Map.of(),actor));
        var manifest=new ReportReviewManifest(project,revision,"ctx",ReportMode.AI_ENHANCED,null,initial.source(),items,null,null);
        var checkpoints=mock(ReportAiCheckpointRepository.class);
        Map<Integer,List<AiReviewValidator.Result>> saved=new HashMap<>();
        when(checkpoints.find(any(),anyString(),anyString(),anyInt())).thenAnswer(call->Optional.ofNullable(saved.get(call.getArgument(3))));
        doAnswer(call->{saved.put(call.getArgument(3),call.getArgument(4));return null;})
                .when(checkpoints).save(any(),anyString(),anyString(),anyInt(),anyList());
        when(context.publish(any())).thenAnswer(call->((java.util.function.Supplier<?>)call.getArgument(0)).get());
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(adapter.run(any())).thenAnswer(call->{
            AgentRuntimeExecutionRequest request=call.getArgument(0);
            if(calls.incrementAndGet()==2) return new CodexProcessResult(1,false,"","");
            List<String> output=new ArrayList<>();
            for(String line:Files.readAllLines(request.workspace().resolve("input/findings.jsonl"))) {
                var item=json.readTree(line);
                output.add(json.writeValueAsString(Map.of("inputId",item.path("inputId").asText(),"verdict","CONFIRMED_ISSUE",
                        "reason","源码确认","reasonEn","Confirmed against source","evidenceRefs",item.path("evidenceRefs"))));
            }
            Files.write(request.workspace().resolve("output/results.jsonl"),output);
            return new CodexProcessResult(0,false,"","");
        });
        var runtime=new ReportAiReviewRuntime(models,registry,artifacts,json,checkpoints);
        assertThatThrownBy(()->runtime.run(manifest,selection,context)).hasMessage("REPORT_AI_EXECUTION_FAILED");
        assertThat(saved).containsOnlyKeys(0); assertThat(saved.get(0)).hasSize(32);
        var result=runtime.run(manifest,selection,context);
        assertThat(calls.get()).isEqualTo(3);
        assertThat(result.results()).hasSize(33).extracting(AiReviewValidator.Result::inputId)
                .containsExactlyElementsOf(items.stream().map(ReviewItemSnapshot::id).toList());
        assertThat(result.summary()).containsEntry("validatedItems",33).containsEntry("validatedBatches",2);
        verify(checkpoints,times(2)).save(any(),anyString(),anyString(),anyInt(),anyList());
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

    @Test void checkpointRecoveryRevalidatesSourceAndNeverReexecutesAValidatedBatch() throws Exception {
        var store = mock(ReportAiCheckpointRepository.class);
        var batched = new ReportAiReviewRuntime(models, registry, artifacts, json, store);
        var manifest = manifest(ReportMode.AI_ENHANCED,"src/a.c",1,ReviewState.PENDING_REVIEW);
        var cache = new java.util.concurrent.atomic.AtomicReference<List<AiReviewValidator.Result>>();
        when(context.publish(any())).thenAnswer(call -> ((java.util.function.Supplier<?>)call.getArgument(0)).get());
        when(store.find(any(),anyString(),anyString(),eq(0))).thenAnswer(call -> Optional.ofNullable(cache.get()));
        doAnswer(call -> { cache.set(call.getArgument(4)); return null; }).when(store).save(any(),anyString(),anyString(),eq(0),anyList());
        when(adapter.run(any())).thenAnswer(call -> {
            AgentRuntimeExecutionRequest request = call.getArgument(0);
            var row=json.readTree(Files.readString(request.workspace().resolve("input/findings.jsonl")));
            Files.writeString(request.workspace().resolve("output/results.jsonl"),json.writeValueAsString(Map.of(
                    "inputId",id,"verdict","CONFIRMED_ISSUE","reason","需要修复","reasonEn","Fix required","evidenceRefs",row.get("evidenceRefs"))));
            return new CodexProcessResult(0,false,"","");
        });
        var first = batched.run(manifest, selection, context);
        var recovered = batched.run(manifest, selection, context);
        assertThat(recovered.results()).isEqualTo(first.results());
        assertThat(recovered.summary()).containsEntry("totalItems",1).containsEntry("validatedItems",1).containsEntry("validatedBatches",1);
        verify(adapter, times(1)).run(any()); verify(store,times(1)).save(any(),anyString(),anyString(),eq(0),anyList());
        when(artifacts.download(artifact)).thenReturn(new StoredArtifactResource(null,new ByteArrayResource(new byte[]{1})));
        assertThatThrownBy(() -> batched.run(manifest,selection,context)).hasMessage("SOURCE_CONTENT_CHANGED");
    }

    @Test void configurationChangesBeforeBatchCannotMixWithCheckpointResults() throws Exception {
        var original = models.resolveExecution(actor,project,profile,runtime);
        var changedModel = new CodexProviderResolvedConfig(true,"opencode",CodexProviderType.OPENAI_COMPATIBLE,
                "http://mock.invalid","different-model",CodexProviderWireApi.CHAT,"fake",10,15);
        var changed = new AgentModelRuntimeConfig(original.runtime(), original.modelProfile(), changedModel);
        when(models.resolveExecution(actor,project,profile,runtime)).thenReturn(original, changed);
        assertThatThrownBy(() -> service.run(manifest(ReportMode.AI_ENHANCED,"src/a.c",1,ReviewState.TO_FIX),selection,context))
                .hasMessage("REPORT_AI_CONFIGURATION_CHANGED");
        verifyNoInteractions(adapter);
    }
}
