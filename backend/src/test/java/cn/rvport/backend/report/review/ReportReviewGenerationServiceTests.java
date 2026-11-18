package cn.rvport.backend.report.review;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import cn.rvport.backend.agentruntime.AgentModelRuntimeConfig;
import cn.rvport.backend.codexprovider.*;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.jooq.tables.records.*;
import cn.rvport.backend.modelprofile.*;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.report.*;
import cn.rvport.backend.source.*;
import cn.rvport.backend.task.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class ReportReviewGenerationServiceTests {
    final UUID project=UUID.randomUUID(),source=UUID.randomUUID(),actor=UUID.randomUUID(),task=UUID.randomUUID();
    final ProjectAccessService access=mock(ProjectAccessService.class);
    final ReportInputSelectionPolicy policy=mock(ReportInputSelectionPolicy.class);
    final ReviewWorkspaceRepository reviews=mock(ReviewWorkspaceRepository.class);
    final SourceRepository sources=mock(SourceRepository.class);
    final ReportSourceSnapshotService sourceSnapshots=mock(ReportSourceSnapshotService.class);
    final ModelProfileService models=mock(ModelProfileService.class);
    final TaskService tasks=mock(TaskService.class);
    final ReportRepository reports=mock(ReportRepository.class);
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    final PlatformTransactionManager transaction=mock(PlatformTransactionManager.class);
    final ReportReviewGenerationService service=new ReportReviewGenerationService(access,policy,reviews,sources,sourceSnapshots,
            new ReviewSnapshotFactory(mapper),models,tasks,reports,mapper,transaction);
    final ReportInputSelectionPolicy.Selection selection=new ReportInputSelectionPolicy.Selection(project,source,"ctx",List.of(
            new ReportInputSelectionPolicy.Source("KB_FINDING",UUID.randomUUID(),"SUCCEEDED",null,null,Map.of(),List.of(),List.of())));
    @BeforeEach void setup() {
        when(policy.current(project)).thenReturn(selection);
        when(transaction.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(sources.findById(source)).thenReturn(Optional.of(new SourceRevisionView(source,project,SourceRevisionType.ARCHIVE,
                "v1",null,null,null,null,null,null,null,true,actor,null)));
        when(sourceSnapshots.prepare(any())).thenReturn(new ReportSourceSnapshotService.Manifest(source,"digest",List.of(),null));
        when(tasks.create(any())).thenReturn(new TaskResponse(task,project,"REPORT_GENERATE",TaskStatus.PENDING,0,null,null,null,null,null,null,null,Map.of()));
    }
    @Test void standardZeroFindingsDoesNotRequireModelAndPersistsTriggerIdentityAndManifest() {
        var response=service.create(project,request(ReportMode.STANDARD),"key",actor,false);
        assertThat(response).containsEntry("id",task.toString()).containsKey("reportId");
        var command=ArgumentCaptor.forClass(TaskCreateCommand.class); verify(tasks).create(command.capture());
        assertThat(command.getValue().createdBy()).isEqualTo(actor);
        assertThat(command.getValue().inputSnapshot().get("configurationUserId")).isEqualTo(actor);
        assertThat(command.getValue().inputSnapshot().get("modelExecution")).isNull();
        verify(reviews).createRun(any(),argThat(m->m.mode()==ReportMode.STANDARD && m.items().isEmpty() && m.source().sourceDigest().equals("digest")),eq(actor),isNull());
        verifyNoInteractions(models); verify(transaction).commit(any());
    }
    @Test void aiUsesOnlyTriggersPersonalAgentAndModelEvenForAdmin() {
        var config=config(); when(models.resolveAgentForStage(ModelProfileStage.REPORT_AI_REVIEW,actor)).thenReturn(config);
        service.create(project,request(ReportMode.AI_ENHANCED),"key",actor,true);
        verify(access).requireProjectAccess(project,actor,true);
        verify(models).resolveAgentForStage(ModelProfileStage.REPORT_AI_REVIEW,actor);
        var fixed=ModelExecutionSelection.from(actor,config);
        verify(reviews).createRun(any(),any(),eq(actor),eq(fixed));
        var command=ArgumentCaptor.forClass(TaskCreateCommand.class);
        verify(tasks).create(command.capture());
        var workerInput=mapper.convertValue(command.getValue().inputSnapshot(),ReportGenerateInput.class);
        assertThat(workerInput.configurationUserId()).isEqualTo(actor);
        assertThat(workerInput.modelExecution()).isEqualTo(fixed);
    }
    @Test void unavailableAiNeverFallsBackAndInvalidRequestsNeverQueue() {
        code("REPORT_AI_MODEL_NOT_CONFIGURED",()->service.create(project,request(ReportMode.AI_ENHANCED),"key",actor,false));
        verify(tasks,never()).create(any());
        for(var body:Arrays.asList(null,new ReportGenerateRequest(source),new ReportGenerateRequest(source,ReportMode.STANDARD,"",null,null)))
            code("REPORT_REQUEST_INVALID",()->service.create(project,body,"key",actor,false));
        code("REPORT_REQUEST_INVALID",()->service.create(project,request(ReportMode.STANDARD)," ",actor,false));
    }
    @Test void callerCannotOverrideThePersonalBindingEvenWithLenientMapper() {
        var httpMapper=mapper.copy().disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        for(String forbidden:List.of("configurationUserId","ownerUserId","modelExecution","agentRuntimeId","modelProfileId","apiKey","baseUrl")) {
            assertThatThrownBy(()->httpMapper.readValue("{\"mode\":\"AI_ENHANCED\",\""+forbidden+"\":\"override\"}",ReportGenerateRequest.class))
                    .hasRootCauseMessage("REPORT_REQUEST_INVALID");
        }
    }

    @Test void authorizationRunsBeforeReplayAndDoesNotExposeSavedResponses() {
        doThrow(new ApiException(HttpStatus.NOT_FOUND,"PROJECT_NOT_FOUND","项目不存在")).when(access).requireProjectAccess(project,actor,false);
        code("PROJECT_NOT_FOUND",()->service.create(project,request(ReportMode.STANDARD),"key",actor,false));
        verifyNoInteractions(reviews,policy,sourceSnapshots,models,tasks);
    }
    @Test void idempotencyReplaysOriginalTaskAndRejectsDifferentPayload() throws Exception {
        var body=request(ReportMode.STANDARD);
        String hash=SourceSelection.hash(mapper.writeValueAsBytes(body));
        Map<String,Object> original=Map.of("id",task,"reportId",UUID.randomUUID());
        when(reviews.replay(actor,project,"GENERATE","key")).thenReturn(Optional.of(Map.of("requestHash",hash,"response",original)));
        assertThat(service.create(project,body,"key",actor,false)).isEqualTo(original);
        code("IDEMPOTENCY_KEY_REUSED",()->service.create(project,request(ReportMode.AI_ENHANCED),"key",actor,false));
        verifyNoInteractions(policy,sourceSnapshots,tasks);
    }
    @Test void rejectsSourceTokenNoAnalysisAndPreQueueContextChanges() {
        when(policy.current(project)).thenReturn(new ReportInputSelectionPolicy.Selection(project,null,"ctx",List.of()));
        code("REPORT_SOURCE_REQUIRED",()->service.create(project,request(ReportMode.STANDARD),"key",actor,false));
        when(policy.current(project)).thenReturn(selection);
        code("REPORT_CONTEXT_CHANGED",()->service.create(project,new ReportGenerateRequest(UUID.randomUUID(),ReportMode.STANDARD,"ctx",null,null),"key",actor,false));
        code("REPORT_SELECTION_CHANGED",()->service.create(project,new ReportGenerateRequest(source,ReportMode.STANDARD,"old",null,null),"key",actor,false));
        var empty=new ReportInputSelectionPolicy.Selection(project,source,"ctx",List.of());
        when(policy.current(project)).thenReturn(empty);
        code("REPORT_ANALYSIS_NOT_READY",()->service.create(project,request(ReportMode.STANDARD),"key",actor,false));
        when(policy.current(project)).thenReturn(selection,new ReportInputSelectionPolicy.Selection(project,source,"changed",selection.sources()));
        code("REPORT_SELECTION_CHANGED",()->service.create(project,request(ReportMode.STANDARD),"key",actor,false));
        verify(tasks,never()).create(any());
    }
    @Test void malformedGenerationRequestsCannotReplaceTheDraft() {
        for (String key : Arrays.asList(null,"x".repeat(201)))
            code("REPORT_REQUEST_INVALID",()->service.create(project,request(ReportMode.STANDARD),key,actor,false));
        code("REPORT_REQUEST_INVALID",()->service.create(project,new ReportGenerateRequest(source,ReportMode.AI_ENHANCED,null,null,null),"key",actor,false));
        code("REPORT_REQUEST_INVALID",()->service.create(project,new ReportGenerateRequest(source,ReportMode.STANDARD,"ctx",null,0L),"key",actor,false));
        verifyNoInteractions(tasks);
    }
    @Test void replayAppearingUnderTheProjectLockReturnsTheFirstGeneration() throws Exception {
        var body = request(ReportMode.STANDARD);
        String hash = SourceSelection.hash(mapper.writeValueAsBytes(body));
        var result = Map.of("id",task);
        when(reviews.replay(actor,project,"GENERATE","key")).thenReturn(Optional.empty(),Optional.of(Map.of("requestHash",hash,"response",result)));
        assertThat(service.create(project,body,"key",actor,false)).isEqualTo(result);
        verify(reviews).lockProject(project);
        verifyNoInteractions(tasks);
    }
    @Test void regenerationCapturesReviewRevisionIncludingConfirmationAndDetectsBaseConflicts() {
        UUID base=UUID.randomUUID();
        when(reviews.currentReport(project)).thenReturn(Optional.of(base));
        var draft=new ReviewWorkspaceRepository.Draft(base,project,source,"ctx","CURRENT",5,8,true,UUID.randomUUID(),null);
        when(reviews.draft(base)).thenReturn(Optional.of(draft));
        code("REPORT_REVIEW_BASE_CHANGED",()->service.create(project,request(ReportMode.STANDARD),"key",actor,false));
        code("REPORT_DRAFT_CONFLICT",()->service.create(project,new ReportGenerateRequest(source,ReportMode.STANDARD,"ctx",base,4L),"key",actor,false));
        service.create(project,new ReportGenerateRequest(source,ReportMode.STANDARD,"ctx",base,5L),"key",actor,false);
        verify(reviews).createRun(any(),argThat(m->m.baseReportId().equals(base) && m.baseReviewRevision()==8L),eq(actor),isNull());
    }
    @Test void projectAllowsOnlyOneActiveGenerationAndReadinessSeparatesModeEligibility() {
        when(reviews.activeGeneration(project)).thenReturn(true);
        code("REPORT_GENERATION_IN_PROGRESS",()->service.create(project,request(ReportMode.STANDARD),"key",actor,false));
        var ready=service.readiness(project,null,actor,false);
        assertThat(ready).containsEntry("canGenerate",true).containsEntry("canGenerateAi",false).containsEntry("recommendedMode","STANDARD");
        when(models.resolveAgentForStage(ModelProfileStage.REPORT_AI_REVIEW,actor)).thenReturn(config());
        assertThat(service.readiness(project,source,actor,false)).containsEntry("recommendedMode","AI_ENHANCED").containsEntry("canGenerateAi",true);
        code("REPORT_CONTEXT_CHANGED",()->service.readiness(project,UUID.randomUUID(),actor,false));
    }
    @Test void aDifferentRevisionStartsWithoutBorrowingThePreviousConfirmation() {
        UUID old = UUID.randomUUID();
        when(reviews.currentReport(project)).thenReturn(Optional.of(old));
        when(reviews.draft(old)).thenReturn(Optional.of(new ReviewWorkspaceRepository.Draft(
                old,project,UUID.randomUUID(),"old","CURRENT",5,8,true,UUID.randomUUID(),null)));
        assertThat(service.latest(project,source,actor,false)).containsEntry("regenerationBaseReportId",null)
                .containsEntry("currentReportId",null);
        assertThat(service.create(project,request(ReportMode.STANDARD),"new-revision",actor,false)).containsKey("reportId");
        verify(reviews,never()).items(old);
    }

    @Test void regenerationPreviewSkipsOnlyReliablyInheritedHumanExclusions() {
        var base = UUID.randomUUID();
        var run = UUID.randomUUID();
        var first = Map.<String,Object>of("sourceType","KB_FINDING","sourceRunId",run,"sourceRefId",UUID.randomUUID(),"fingerprint","left");
        var second = Map.<String,Object>of("sourceType","KB_FINDING","sourceRunId",run,"sourceRefId",UUID.randomUUID(),"fingerprint","right");
        var facts = List.of(first,second);
        var selected = new ReportInputSelectionPolicy.Selection(project,source,"ctx",List.of(
                new ReportInputSelectionPolicy.Source("KB_FINDING",run,"SUCCEEDED","v1",null,Map.of(),facts,facts)));
        var fixed = new ReportSourceSnapshotService.Manifest(source,"digest",List.of(),null);
        var snapshots = new ReviewSnapshotFactory(mapper).create(selected,fixed,actor,null,List.of());
        var previous = snapshots.stream().map(i -> {
            boolean excluded = i.sourceRefId().equals(first.get("sourceRefId"));
            return Map.<String,Object>of("id",i.id(),"origin","SCANNER","identityHash",i.identityHash(),"evidenceHash",i.evidenceHash(),
                    "hasManualDecision",excluded,"reviewState",excluded?"EXCLUDED":"TO_FIX");
        }).toList();
        when(policy.current(project)).thenReturn(selected);
        when(reviews.currentReport(project)).thenReturn(Optional.of(base));
        when(reviews.draft(base)).thenReturn(Optional.of(new ReviewWorkspaceRepository.Draft(base,project,source,"ctx","CURRENT",1,1,true,null,null)));
        when(sourceSnapshots.existing(source)).thenReturn(fixed);
        when(reviews.items(base)).thenReturn(previous);
        var query = new ReviewItemQuery(1,10,null,null,null,null,null,null,null,null,null,null);
        var preview = service.preview(project,source,query,actor,false).items();
        assertThat(preview).hasSize(2);
        assertThat(preview).filteredOn(row->row.get("id").equals(first.get("sourceRefId"))).singleElement()
                .satisfies(row->assertThat(row).containsEntry("aiIncluded",false).containsEntry("inheritedExcluded",true));
        assertThat(preview).filteredOn(row->row.get("id").equals(second.get("sourceRefId"))).singleElement()
                .satisfies(row->assertThat(row).containsEntry("aiIncluded",true).containsEntry("inheritedExcluded",false));
        var ready = service.readiness(project,source,actor,false);
        var sources = (List<?>)ready.get("sources");
        assertThat(((Map<?,?>)sources.getFirst()).get("aiInputCount")).isEqualTo(1L);
        code("REPORT_CONTEXT_CHANGED",()->service.preview(project,UUID.randomUUID(),query,actor,false));
    }
    @Test void publishedReportCanBeReadWithoutPersonalStageOrModelAccess() {
        UUID reportId=UUID.randomUUID(), failedId=UUID.randomUUID(), viewer=UUID.randomUUID();
        when(reviews.currentReport(project)).thenReturn(Optional.of(reportId));
        when(reviews.draft(reportId)).thenReturn(Optional.of(new ReviewWorkspaceRepository.Draft(
                reportId,project,source,"ctx","CURRENT",5,8,true,null,null)));
        when(reviews.latestGeneration(project)).thenReturn(Optional.of(failedId));
        when(reviews.runSummary(reportId)).thenReturn(Optional.of(Map.of("status","SUCCEEDED")));
        when(reviews.runSummary(failedId)).thenReturn(Optional.of(Map.of("status","FAILED")));
        assertThat(service.latest(project,source,viewer,false))
                .containsEntry("currentReport",Map.of("status","SUCCEEDED"))
                .containsEntry("latestGeneration",Map.of("status","FAILED"));
        verify(access).requireProjectAccess(project,viewer,false);
        code("REPORT_CONTEXT_CHANGED",()->service.latest(project,UUID.randomUUID(),viewer,false));
        verifyNoInteractions(models,sourceSnapshots,tasks);
    }

    AgentModelRuntimeConfig config() {
        var runtime=new AgentRuntimesRecord(); runtime.setId(UUID.randomUUID()); runtime.setExecutablePath("agent"); runtime.setEnabled(true); runtime.setAgentType("CODEX");
        var profile=new ModelProfilesRecord(); profile.setId(UUID.randomUUID()); profile.setOwnerUserId(actor); profile.setEnabled(true);
        return new AgentModelRuntimeConfig(runtime,profile,new CodexProviderResolvedConfig(true,"agent",CodexProviderType.OPENAI_COMPATIBLE,
                "http://fake.invalid","test",CodexProviderWireApi.CHAT,"fake",60,60));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"disabled","missing-key","missing-model","missing-url","missing-runtime","missing-executable"})
    void unusablePersonalBindingsCannotGenerateAutomaticDispositions(String missing) {
        var original = config();
        var model = original.model();
        var invalid = new CodexProviderResolvedConfig(!missing.equals("disabled"),model.executablePath(),model.modelProviderType(),
                missing.equals("missing-url")?null:model.baseUrl(),missing.equals("missing-model")?" ":model.model(),
                model.wireApi(),missing.equals("missing-key")?null:model.apiKey(),60,60);
        var runtime = missing.equals("missing-runtime")?null:original.runtime();
        if (missing.equals("missing-executable")) runtime.setExecutablePath("");
        when(models.resolveAgentForStage(ModelProfileStage.REPORT_AI_REVIEW,actor))
                .thenReturn(new AgentModelRuntimeConfig(runtime,original.modelProfile(),invalid));
        code("REPORT_AI_MODEL_NOT_CONFIGURED",()->service.create(project,request(ReportMode.AI_ENHANCED),"key",actor,false));
        verifyNoInteractions(tasks);
    }
    ReportGenerateRequest request(ReportMode mode) { return new ReportGenerateRequest(source,mode,"ctx",null,null); }
    void code(String code,Runnable action) { assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo(code)); }
}
