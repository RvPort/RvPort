package cn.rvport.backend.report.review;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import cn.rvport.backend.artifact.StoredArtifactResource;
import cn.rvport.backend.artifact.StoredArtifactService;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.report.ReportFileContextView;
import cn.rvport.backend.report.ReportOrganizationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;

class ReviewWorkspaceServiceTests {
    final UUID report=UUID.randomUUID(),project=UUID.randomUUID(),source=UUID.randomUUID(),actor=UUID.randomUUID(),item=UUID.randomUUID();
    final ReviewWorkspaceRepository repository=mock(ReviewWorkspaceRepository.class);
    final ProjectAccessService access=mock(ProjectAccessService.class);
    final ReportInputSelectionPolicy selection=mock(ReportInputSelectionPolicy.class);
    final ReportOrganizationRepository files=mock(ReportOrganizationRepository.class);
    final StoredArtifactService artifacts=mock(StoredArtifactService.class);
    final ObjectMapper mapper=new ObjectMapper();
    final ReviewWorkspaceService service=new ReviewWorkspaceService(repository,access,selection,files,artifacts,mapper);
    ReviewWorkspaceRepository.Draft draft;
    @BeforeEach void setup() {
        draft=new ReviewWorkspaceRepository.Draft(report,project,source,"ctx","CURRENT",2,2,true,null,null);
        when(repository.draft(report)).thenReturn(Optional.of(draft));
        when(selection.current(project)).thenReturn(new ReportInputSelectionPolicy.Selection(project,source,"ctx",List.of()));
        when(repository.item(report,item)).thenReturn(Optional.of(Map.of("id",item,"reviewState","PENDING_REVIEW")));
        when(repository.advanceDraft(report,2)).thenReturn(true);
        when(repository.summary(report)).thenReturn(counts(1,1,0,0));
    }
    @Test void validatesEntireBatchBeforeAnyWriteAndRejectsDuplicates() {
        UUID missing=UUID.randomUUID();
        var first=new ReviewPatchRequest.Item(item,ReviewAction.INCLUDE,null,null);
        code("REPORT_REVIEW_ITEM_NOT_FOUND",()->service.patch(report,new ReviewPatchRequest(2L,List.of(first,
                new ReviewPatchRequest.Item(missing,ReviewAction.INCLUDE,null,null))),"key",actor,false));
        code("REPORT_REVIEW_ACTION_INVALID",()->service.patch(report,new ReviewPatchRequest(2L,List.of(first,first)),"key",actor,false));
        code("REPORT_REVIEW_ACTION_INVALID",()->service.patch(report,new ReviewPatchRequest(2L,List.of(
                new ReviewPatchRequest.Item(item,ReviewAction.EXCLUDE," ",null))),"key",actor,false));
        verify(repository,never()).updateDecision(any(),any(),any(),any());
        verify(repository,never()).advanceDraft(any(),anyLong());
        verify(repository,never()).remember(any(),any(),any(),any(),any(),any());
    }
    @Test void writesOneRevisionAndAuditForBatchAndClearsConfirmation() {
        UUID previous=UUID.randomUUID();
        var updated=new ReviewWorkspaceRepository.Draft(report,project,source,"ctx","CURRENT",3,3,true,null,previous);
        when(repository.draft(report)).thenReturn(Optional.of(draft),Optional.of(draft),Optional.of(updated));
        var response=service.patch(report,new ReviewPatchRequest(2L,List.of(new ReviewPatchRequest.Item(item,ReviewAction.EXCLUDE,"不适用","Not applicable"))),"key",actor,false);
        verify(repository).updateDecision(report,item,new ReviewDecision(ReviewState.EXCLUDED,ReviewState.PENDING_REVIEW,"不适用"),"Not applicable");
        verify(repository).advanceDraft(report,2);
        verify(repository).event(eq(report),eq(item),eq(actor),eq("EXCLUDE"),eq(3L),anyMap(),anyMap());
        assertThat(response).containsEntry("confirmationState","NEEDS_CONFIRMATION").containsEntry("previousConfirmationId",previous);
    }
    @Test void preservesVersionOnNoopAndFailsCompareAndSetConflict() {
        var request=new ReviewPatchRequest(2L,List.of(new ReviewPatchRequest.Item(item,ReviewAction.RESTORE,null,null)));
        assertThat(service.patch(report,request,"key",actor,false).get("changedItems")).isEqualTo(List.of());
        verify(repository,never()).advanceDraft(any(),anyLong());
        when(repository.advanceDraft(report,2)).thenReturn(false);
        code("REPORT_DRAFT_CONFLICT",()->service.patch(report,new ReviewPatchRequest(2L,List.of(new ReviewPatchRequest.Item(item,ReviewAction.INCLUDE,null,null))),"key2",actor,false));
    }
    @Test void explicitlyKeepingAnAiDefaultRecordsAHumanDecisionOnlyOnce() {
        var automatic = Map.<String,Object>of("id",item,"reviewState","TO_FIX","hasManualDecision",false);
        var manual = Map.<String,Object>of("id",item,"reviewState","TO_FIX","hasManualDecision",true);
        when(repository.item(report,item)).thenReturn(Optional.of(automatic),Optional.of(automatic),Optional.of(manual));
        var request = new ReviewPatchRequest(2L,List.of(new ReviewPatchRequest.Item(item,ReviewAction.INCLUDE,null,null)));
        service.patch(report,request,"first",actor,false);
        assertThat(service.patch(report,request,"repeat",actor,false).get("changedItems")).isEqualTo(List.of());
        verify(repository).event(report,item,actor,"INCLUDE",3L,automatic,manual);
        verify(repository).advanceDraft(report,2);
    }
    @Test void protectsPermissionsBeforeIdempotencyAndMutation() {
        doThrow(new ApiException(HttpStatus.NOT_FOUND,"PROJECT_NOT_FOUND","项目不存在")).when(access).requireProjectAccess(project,actor,false);
        code("PROJECT_NOT_FOUND",()->service.confirm(report,2L,"key",actor,false));
        verify(repository,never()).lockProject(any()); verify(repository,never()).replay(any(),any(),any(),any());
        when(repository.draft(report)).thenReturn(Optional.empty());
        code("REPORT_NOT_FOUND",()->service.detail(report,item,actor,false));
    }
    @Test void returnsAuthorizedReplayWithoutRepeatingWritesAndRejectsKeyReuse() throws Exception {
        String hash=SourceSelection.hash(mapper.writeValueAsBytes(Arrays.asList(report,2L)));
        Map<String,Object> original=Map.of("confirmationId",UUID.randomUUID());
        when(repository.replay(actor,project,"CONFIRM","key")).thenReturn(Optional.of(Map.of("requestHash",hash,"response",original)));
        assertThat(service.confirm(report,2L,"key",actor,false)).isEqualTo(original);
        code("IDEMPOTENCY_KEY_REUSED",()->service.confirm(report,3L,"key",actor,false));
        verifyNoInteractions(selection); verify(repository,never()).confirm(any(),any(),anyLong(),anyLong());
    }
    @Test void rejectsHistoricalContextAndDraftVersionChanges() {
        when(selection.current(project)).thenReturn(new ReportInputSelectionPolicy.Selection(project,source,"changed",List.of()));
        code("REPORT_READ_ONLY",()->service.confirm(report,2L,"key",actor,false));
        when(selection.current(project)).thenReturn(new ReportInputSelectionPolicy.Selection(project,source,"ctx",List.of()));
        code("REPORT_DRAFT_CONFLICT",()->service.confirm(report,1L,"key",actor,false));
        code("REPORT_REQUEST_INVALID",()->service.confirm(report,null,"key",actor,false));
        code("REPORT_REQUEST_INVALID",()->service.confirm(report,-1L,"key",actor,false));
        for(var historical:List.of(new ReviewWorkspaceRepository.Draft(report,project,source,"ctx","CURRENT",2,2,false,null,null),
                new ReviewWorkspaceRepository.Draft(report,project,source,"ctx","HISTORICAL_SUPERSEDED",2,2,true,null,null))) {
            when(repository.draft(report)).thenReturn(Optional.of(historical));
            code("REPORT_READ_ONLY",()->service.confirm(report,2L,"key",actor,false));
        }
    }
    @Test void blocksGlobalPendingEvenIfVisiblePageHasNoneAndAllowsEmptyConfirmation() {
        code("REPORT_PENDING_REVIEW",()->service.confirm(report,2L,"key",actor,false));
        verify(repository,never()).confirm(any(),any(),anyLong(),anyLong());
        when(repository.summary(report)).thenReturn(counts(2,0,0,2));
        UUID confirmation=UUID.randomUUID();
        var stored=new cn.rvport.backend.artifact.StoredArtifactView(UUID.randomUUID(),project,null,null,report,"json",null,null,null,0,null,Map.of(),null);
        when(artifacts.store(any(cn.rvport.backend.artifact.StoredArtifactCreateCommand.class))).thenReturn(stored);
        when(repository.confirm(draft,actor,2,0)).thenReturn(confirmation);
        when(repository.confirmation(report,confirmation)).thenReturn(Optional.of(Map.of("id",confirmation,"itemCount",0)));
        var result=service.confirm(report,2L,"key",actor,false);
        assertThat(result).containsEntry("confirmationState","CONFIRMED_EMPTY").containsEntry("confirmationId",confirmation);
        verify(repository).event(report,null,actor,"CONFIRM",2L,null,Map.of("confirmationId",confirmation));
    }
    @Test void reusesSameDraftConfirmationAndSupportsNonEmpty() {
        UUID confirmation=UUID.randomUUID();
        when(repository.summary(report)).thenReturn(counts(1,0,1,0));
        when(repository.confirmationForVersion(report,2)).thenReturn(Optional.of(Map.of("id",confirmation)));
        when(repository.confirmation(report,confirmation)).thenReturn(Optional.of(Map.of("id",confirmation)));
        assertThat(service.confirm(report,2L,"new-key",actor,true)).containsEntry("confirmationState","CONFIRMED_WITH_ITEMS");
        verify(repository,never()).confirm(any(),any(),anyLong(),anyLong());
        verify(access).requireProjectAccess(project,actor,true);
    }
    @Test void distinguishesSupersededDraftFromChangedSourceAndRetainsEvidence() {
        var origin=Map.of("riskLevel","LOW","findingKind","SOURCE_CANDIDATE","locations",List.of());
        when(repository.item(report,item)).thenReturn(Optional.of(Map.of("originalSnapshot",origin)));
        when(repository.draft(report)).thenReturn(Optional.of(new ReviewWorkspaceRepository.Draft(
                report,project,source,"ctx","HISTORICAL_SUPERSEDED",2,2,false,null,null)));
        assertThat(service.detail(report,item,actor,false)).containsEntry("contextValid",true)
                .containsEntry("writable",false).containsEntry("readOnlyReason","REPORT_SUPERSEDED")
                .containsEntry("originalEvidence",origin).containsEntry("findingKind","SOURCE_CANDIDATE").containsEntry("riskLevel","LOW");
        when(selection.current(project)).thenReturn(new ReportInputSelectionPolicy.Selection(project,source,"next",List.of()));
        assertThat(service.detail(report,item,actor,false)).containsEntry("contextValid",false)
                .containsEntry("readOnlyReason","REPORT_CONTEXT_CHANGED");
        when(repository.item(report,item)).thenReturn(Optional.of(Map.of("origin","MANUAL","fileKey","known","relativePath","a.c","startLine",2,"endLine",3)));
        assertThat(service.detail(report,item,actor,false).get("locations")).isEqualTo(
                List.of(Map.of("fileKey","known","path","a.c","startLine",2,"endLine",3)));
    }
    @Test void draftWithOnlyAiConfirmedItemsIsReadableBeforeManualSubmission() {
        when(repository.summary(report)).thenReturn(counts(22,0,22,0));
        when(repository.runSummary(report)).thenReturn(Optional.of(Map.of("mode","AI_ENHANCED")));
        assertThat(service.metadata(report)).containsEntry("confirmationState","UNCONFIRMED")
                .containsEntry("currentConfirmationId",null).containsEntry("writable",true);
        var row = Map.<String,Object>of("id",item,"reviewState","TO_FIX","hasManualDecision",false);
        when(repository.item(report,item)).thenReturn(Optional.of(row));
        assertThat(service.detail(report,item,actor,false,null,1,20)).containsAllEntriesOf(row).doesNotContainKey("history");
        service.detail(report,item,actor,false,"history",1,20);
        verify(repository).events(report,item,1,20);
        code("REPORT_REQUEST_INVALID",()->service.detail(report,item,actor,false,"invalid",1,20));
        for (int[] page : List.of(new int[]{0,1}, new int[]{1,0}, new int[]{1,201}))
            code("REPORT_REQUEST_INVALID",()->service.confirmations(report,actor,false,page[0],page[1]));
        service.confirmations(report,actor,false,1,200);
        verify(repository).confirmations(report,1,200);
        when(repository.item(report,item)).thenReturn(Optional.empty());
        code("REPORT_REVIEW_ITEM_NOT_FOUND",()->service.detail(report,item,actor,false));
        when(repository.draft(report)).thenReturn(Optional.empty());
        assertThat(service.metadata(report)).containsEntry("writable",false).doesNotContainKey("draftSummary");
        verify(repository,never()).confirm(any(),any(),anyLong(),anyLong());
    }
    @Test void readsOnlySubmittedDispositionsFromTheRequestedConfirmation() {
        var confirmation = UUID.randomUUID();
        var kept = Map.<String,Object>of("id",item,"reviewState","TO_FIX");
        var excluded = Map.<String,Object>of("id",UUID.randomUUID(),"reviewState","EXCLUDED");
        code("REPORT_CONFIRMATION_NOT_FOUND",()->service.confirmation(report,confirmation,actor,false,1,10,"ALL"));
        when(repository.confirmation(report,confirmation)).thenReturn(Optional.of(Map.of("id",confirmation)));
        when(repository.confirmationItems(report,confirmation)).thenReturn(List.of(kept,excluded));
        assertThat(service.confirmation(report,confirmation,actor,false,1,10,"TO_FIX")).containsEntry("items",List.of(kept)).containsEntry("availableForCurrentWorkflow",false);
        assertThat(service.confirmation(report,confirmation,actor,false,1,10,"ALL")).containsEntry("total",2L);
        code("REPORT_REQUEST_INVALID",()->service.confirmation(report,confirmation,actor,false,1,10,"PENDING_REVIEW"));
        for (long size : new long[]{0,1}) {
            when(repository.summary(report)).thenReturn(counts(1,0,(int)size,(int)(1-size)));
            var submitted = new ReviewWorkspaceRepository.Draft(report,project,source,"ctx","CURRENT",2,3,true,confirmation,null);
            assertThat(service.summary(submitted)).containsEntry("confirmationState",size==0?"CONFIRMED_EMPTY":"CONFIRMED_WITH_ITEMS");
        }
    }
    @Test void rejectsIncompleteUserDecisionsAndReplaysAlreadyCommittedRequests() throws Exception {
        code("REPORT_REQUEST_INVALID",()->service.patch(report,null,"key",actor,false));
        code("REPORT_REQUEST_INVALID",()->service.add(report,null,"key",actor,false));
        for (var invalid : Arrays.asList(null,new ReviewPatchRequest.Item(null,ReviewAction.INCLUDE,null,null))) {
            var body = new ReviewPatchRequest(2L,Collections.singletonList(invalid));
            code("REPORT_REVIEW_ACTION_INVALID",()->service.patch(report,body,"key",actor,false));
        }
        var patch = new ReviewPatchRequest(2L,List.of(new ReviewPatchRequest.Item(item,ReviewAction.INCLUDE,null,null)));
        var add = new ManualReviewItemRequest(2L,"key","hash",new SourceSelection.Range(1,1,1,2),null,null,"描述",null,"原因",null);
        var response = Map.of("draftVersion",3);
        for (var entry : Map.of("PATCH",(Object)patch,"ADD",add).entrySet()) {
            var hash=SourceSelection.hash(mapper.writeValueAsBytes(Arrays.asList(report,entry.getValue())));
            when(repository.replay(actor,project,entry.getKey(),"key")).thenReturn(Optional.of(Map.of("requestHash",hash,"response",response)));
        }
        assertThat(service.patch(report,patch,"key",actor,false)).isEqualTo(response);
        assertThat(service.add(report,add,"key",actor,false)).isEqualTo(response);
        verify(repository,never()).advanceDraft(any(),anyLong());
    }
    @Test void downloadsRequireAnExplicitVersionAndItsOwnStoredArtifact() {
        var confirmation = UUID.randomUUID();
        var json = UUID.randomUUID();
        var html = UUID.randomUUID();
        code("REPORT_CONFIRMATION_NOT_FOUND",()->service.download(report,confirmation,"JSON",actor,false));
        when(repository.confirmation(report,confirmation)).thenReturn(Optional.of(Map.of("id",confirmation)));
        code("REPORT_CONFIRMATION_ARTIFACT_NOT_FOUND",()->service.download(report,confirmation,"HTML",actor,false));
        code("REPORT_REQUEST_INVALID",()->service.download(report,confirmation,"txt",actor,false));
        when(repository.confirmation(report,confirmation)).thenReturn(Optional.of(Map.of("htmlArtifactId",html,"jsonArtifactId",json)));
        for (var format : List.of("json","html")) {
            var resource = new ByteArrayResource(format.getBytes(StandardCharsets.UTF_8));
            when(artifacts.download(format.equals("html")?html:json)).thenReturn(new StoredArtifactResource(null,resource));
            var saved = service.download(report,confirmation,format,actor,false);
            assertThat(saved.resource()).isSameAs(resource);
            assertThat(saved.filename()).isEqualTo("fix-list-"+confirmation+"."+format);
        }
    }
    @Test void extractsManualContentFromStoredVersionAndValidatesHash() {
        byte[] bytes="before\nint unsafe();\nafter".getBytes(StandardCharsets.UTF_8); String hash=SourceSelection.hash(bytes);
        UUID artifact=UUID.randomUUID();
        var file=new ReportFileContextView(UUID.randomUUID(),report,source,"file","src/a.c",hash,3,artifact,true,null,null);
        when(files.findFileContext(report,"file")).thenReturn(Optional.of(file));
        when(artifacts.download(artifact)).thenReturn(new StoredArtifactResource(null,new ByteArrayResource(bytes)));
        when(repository.item(eq(report),any(UUID.class))).thenAnswer(call->Optional.of(Map.<String,Object>of("id",call.getArgument(1),"reviewState","TO_FIX")));
        var body=new ManualReviewItemRequest(2L,"file",hash,new SourceSelection.Range(2,1,2,14),"修复",null,"调用不兼容",null,"替换",null);
        var response=service.add(report,body,"key",actor,false);
        assertThat(response).containsKey("item");
        verify(repository).insertManual(eq(draft),any(),eq(actor),eq(body),eq(file),argThat(s->s.snippet().equals("int unsafe();")),
                argThat(d->d.get("translationStatus").equals("ORIGINAL_ONLY") && d.get("repairReasonEn").toString().startsWith("Reason for repair (original):")));
        when(artifacts.download(artifact)).thenReturn(new StoredArtifactResource(null,new ByteArrayResource("changed".getBytes(StandardCharsets.UTF_8))));
        code("SOURCE_CONTENT_CHANGED",()->service.add(report,body,"key2",actor,false));
    }
    @Test void manualSelectionNeverAcceptsAClientSuppliedSnippetOrAuthor() {
        for(String field:List.of("snippet","snippetHash","createdBy","ownerId")) {
            assertThatThrownBy(() -> mapper.readValue("{\""+field+"\":\"forged\"}",ManualReviewItemRequest.class))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void rejectsUnavailableManualSourcesAndInvalidText() {
        var body=new ManualReviewItemRequest(2L,"file","h",new SourceSelection.Range(1,1,1,2),"title",null,"description",null,"repair",null);
        code("REPORT_SOURCE_FILE_NOT_FOUND",()->service.add(report,body,"key",actor,false));
        when(files.findFileContext(report,"file")).thenReturn(Optional.of(new ReportFileContextView(UUID.randomUUID(),report,source,"file","a",null,0,null,false,"MISSING",null)));
        code("SOURCE_CONTEXT_UNAVAILABLE",()->service.add(report,body,"key",actor,false));
        code("REPORT_REQUEST_INVALID",()->service.add(report,new ManualReviewItemRequest(2L,"file","h",null,"",null,"d",null,"r",null),"key",actor,false));
        code("REPORT_REQUEST_INVALID",()->service.patch(report,new ReviewPatchRequest(2L,List.of())," ",actor,false));
        code("REPORT_REVIEW_ACTION_INVALID",()->service.patch(report,new ReviewPatchRequest(2L,List.of()),"key",actor,false));
        code("REPORT_REVIEW_ACTION_INVALID",()->service.patch(report,new ReviewPatchRequest(2L,null),"key",actor,false));
        code("REPORT_REVIEW_ACTION_INVALID",()->service.patch(report,new ReviewPatchRequest(2L,Collections.nCopies(501,new ReviewPatchRequest.Item(item,ReviewAction.INCLUDE,null,null))),"key",actor,false));
    }
    @Test void listsWithGlobalSummaryAndRejectsPageVersionDrift() {
        when(repository.items(report)).thenReturn(List.of(Map.of("id",item,"reviewState","PENDING_REVIEW")));
        var query=new ReviewItemQuery(1,50,2L,null,null,null,null,null,null,null,null,null);
        assertThat(service.list(report,query,actor,false)).containsEntry("total",1L).containsEntry("draftVersion",2L).containsEntry("writable",true);
        code("REPORT_DRAFT_CONFLICT",()->service.list(report,new ReviewItemQuery(1,50,1L,null,null,null,null,null,null,null,null,null),actor,false));
        assertThat(service.detail(report,item,actor,false)).containsEntry("draftVersion",2L);
    }
    private Map<String,Long> counts(long total,long pending,long tofix,long excluded) { return Map.of("total",total,"pendingReviewCount",pending,"toFixCount",tofix,"excludedCount",excluded,"manualCount",0L); }
    private void code(String code,Runnable action) { assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo(code)); }
}
