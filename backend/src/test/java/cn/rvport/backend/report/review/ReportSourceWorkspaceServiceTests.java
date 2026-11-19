package cn.rvport.backend.report.review;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.rvport.backend.artifact.*;
import cn.rvport.backend.common.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;

class ReportSourceWorkspaceServiceTests {
    final ReviewWorkspaceService access=mock(ReviewWorkspaceService.class);
    final ReviewWorkspaceRepository reviews=mock(ReviewWorkspaceRepository.class);
    final StoredArtifactService artifacts=mock(StoredArtifactService.class);
    final ReportSourceWorkspaceService service=new ReportSourceWorkspaceService(access,reviews,artifacts);
    final UUID report=UUID.randomUUID(),actor=UUID.randomUUID(),revision=UUID.randomUUID(),artifact=UUID.randomUUID();
    final String hash=SourceSelection.hash("first\r\n尾行".getBytes(StandardCharsets.UTF_8));
    void manifest(ReportSourceSnapshotService.File... files) {
        when(reviews.manifest(report)).thenReturn(new ReportReviewManifest(UUID.randomUUID(),revision,"c",ReportMode.STANDARD,null,
                new ReportSourceSnapshotService.Manifest(revision,"digest",List.of(files),null),List.of(),null,null));
    }
    ReportSourceSnapshotService.File fixture() {return new ReportSourceSnapshotService.File("key","src/a.c",hash,2,artifact,true,null);}
    @Test void onlyManifestFilesAreListedIncludingFilesWithoutFindings() {
        manifest(fixture(),new ReportSourceSnapshotService.File("unused","src/empty.c","hash",0,artifact,true,null));
        when(reviews.items(report)).thenReturn(List.of(Map.of("fileKey","key","relativePath","src/a.c","reviewState","TO_FIX"),
                Map.of("relativePath","src/unknown.c","reviewState","EXCLUDED")));
        var roots=service.files(report,actor,false,null,null,1,50);
        assertThat(roots.items()).singleElement().satisfies(dir->assertThat(dir).containsEntry("kind","DIRECTORY").containsEntry("findingCount",1));
        String parent=(String)roots.items().getFirst().get("fileKey");
        var page=service.files(report,actor,false,parent,"",2,1);assertThat(page.total()).isEqualTo(2);
        assertThat(page.items().getFirst()).containsEntry("fileKey","unused").containsEntry("findingCount",0);
        assertThat(service.files(report,actor,false,null,"A.C",1,20).items()).singleElement().satisfies(f->assertThat(f).containsEntry("toFixCount",1L));
        assertThatThrownBy(()->service.files(report,actor,false,"key",null,1,1)).isInstanceOf(ApiException.class);
        for(int size:new int[]{0,201})assertThatThrownBy(()->service.files(report,actor,false,null,null,1,size)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.files(report,actor,false,null,null,0,20)).isInstanceOf(ApiException.class);
        verifyNoInteractions(artifacts);
    }
    @Test void returnsExactLinesOrSafeReasonForMissingOrChangedBytes() {
        manifest(fixture());
        when(artifacts.download(artifact)).thenReturn(new StoredArtifactResource(null,new ByteArrayResource("first\r\n尾行".getBytes(StandardCharsets.UTF_8))));
        assertThat(service.context(report,"key",actor,false,2,null)).containsEntry("sourceRevisionId",revision)
                .containsEntry("lines",List.of(Map.of("lineNumber",2,"text","尾行")));
        assertThat(service.context(report,"key",actor,false,null,1)).containsEntry("endLine",1);
        for(int[] range:List.of(new int[]{0,1},new int[]{2,1},new int[]{3,3},new int[]{1,501},new int[]{1,3}))
            assertThatThrownBy(()->service.context(report,"key",actor,false,range[0],range[1])).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.context(report,"unknown",actor,false,null,null)).isInstanceOf(ApiException.class);
        when(artifacts.download(artifact)).thenReturn(new StoredArtifactResource(null,new ByteArrayResource(new byte[]{3})));
        assertThat(service.context(report,"key",actor,false,null,null)).containsEntry("reasonCode","SOURCE_CONTENT_CHANGED");
        when(artifacts.download(artifact)).thenThrow(new ApiException(HttpStatus.NOT_FOUND,"ARTIFACT_OBJECT_NOT_FOUND","test"));
        assertThat(service.context(report,"key",actor,false,null,null)).containsEntry("reasonCode","SOURCE_CONTEXT_UNAVAILABLE")
                .containsEntry("contextAvailable",false);
    }
    @Test void emptyAndUnavailableSourcesDoNotPretendToHaveLines() {
        manifest(new ReportSourceSnapshotService.File("bin","bin",null,null,null,false,"BINARY_FILE"),
                new ReportSourceSnapshotService.File("empty","empty",SourceSelection.hash(new byte[0]),0,artifact,true,null));
        assertThat(service.context(report,"bin",actor,false,null,null)).containsEntry("reasonCode","BINARY_FILE");
        when(artifacts.download(artifact)).thenReturn(new StoredArtifactResource(null,new ByteArrayResource(new byte[0])));
        assertThat(service.context(report,"empty",actor,false,null,null)).containsEntry("contextAvailable",true).containsEntry("lines",List.of());
        assertThatThrownBy(()->service.context(report,"empty",actor,false,2,null)).isInstanceOf(ApiException.class);
        doThrow(new ApiException(HttpStatus.NOT_FOUND,"REPORT_NOT_FOUND","test")).when(access).authorized(report,actor,false);
        assertThatThrownBy(()->service.files(report,actor,false,null,null,1,20)).isInstanceOf(ApiException.class);
    }
}
