package cn.rvport.backend.report.review;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import cn.rvport.backend.artifact.*;
import cn.rvport.backend.source.*;
import cn.rvport.backend.source.archive.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportSourceSnapshotTests {
    @TempDir Path directory;
    final UUID revisionId=UUID.randomUUID(),project=UUID.randomUUID();
    @Test void extractsSavedArchiveInsteadOfChangedWorkingCopyAndRemovesTemporaryFiles() throws Exception {
        Path archive=directory.resolve("source.zip");
        try(var zip=new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("src/a.c")); zip.write("original\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
        }
        Files.writeString(directory.resolve("a.c"),"changed");
        var service=new ReportSourceMaterializer(new SourceArchiveExtractor()); Path temporary;
        try(var prepared=service.prepare(revision(SourceRevisionType.ARCHIVE,archive,null))) {
            temporary=prepared.temporaryRoot();
            assertThat(Files.readString(prepared.sourceRoot().resolve("src/a.c"))).isEqualTo("original\n");
            assertThat(prepared.sourceDigest()).isEqualTo(ReportSourceMaterializer.hashFile(archive));
        }
        assertThat(temporary).doesNotExist(); ReportSourceMaterializer.remove(temporary);
    }
    @Test void gitUsesOnlyValidatedCommitAndDoesNotReadBranchOrWorkingFiles() throws Exception {
        SourceArchiveExtractor extractor=mock(SourceArchiveExtractor.class);
        List<String> calls=new ArrayList<>();
        var service=new ReportSourceMaterializer(extractor,(repository,commit,target)->{
            calls.add(commit); assertThat(repository).isEqualTo(directory); Files.writeString(target,"archive");
        });
        when(extractor.extract(any(),eq("source.tar.gz"),any())).thenAnswer(call->new SourceArchiveExtraction(call.getArgument(2),0));
        try(var prepared=service.prepare(revision(SourceRevisionType.GIT,null,"A".repeat(40)))) {
            assertThat(prepared.sourceDigest()).isEqualTo("git:"+"a".repeat(40));
        }
        assertThat(calls).containsExactly("A".repeat(40));
        for(String commit:new String[]{null,"main","--exec=unsafe","a".repeat(39)}) {
            assertThatThrownBy(()->service.prepare(revision(SourceRevisionType.GIT,null,commit))).isInstanceOf(IOException.class);
        }
        assertThatThrownBy(()->service.prepare(revision(SourceRevisionType.ARCHIVE,null,null))).isInstanceOf(IOException.class);
        assertThatThrownBy(()->service.prepare(revision(SourceRevisionType.ARCHIVE,directory.resolve("missing"),null))).isInstanceOf(IOException.class);
    }
    @Test void capturesZeroFindingFilesBinaryFailuresAndContentHashesOnce() throws Exception {
        Path root=Files.createDirectories(directory.resolve("source"));
        Files.writeString(root.resolve("ok.c"),"line1\r\nline2"); Files.write(root.resolve("bad.bin"),new byte[]{0});
        Files.write(root.resolve("invalid.c"),new byte[]{(byte)0xff}); Files.write(root.resolve("empty.c"),new byte[0]);
        Files.write(root.resolve("large.c"),new byte[10*1024*1024+1]);
        Files.createSymbolicLink(root.resolve("escape"),directory.resolve("outside"));
        SourceManifestRepository repo=mock(SourceManifestRepository.class);
        ReportSourceMaterializer materializer=mock(ReportSourceMaterializer.class);
        when(materializer.prepare(any())).thenReturn(new ReportSourceMaterializer.Materialized(directory,root,"digest"));
        StoredArtifactService artifacts=mock(StoredArtifactService.class);
        when(artifacts.store(any(StoredArtifactCreateCommand.class))).thenAnswer(call->{
            StoredArtifactCreateCommand command=call.getArgument(0);
            assertThat(command.ownerType()).isEqualTo(StoredArtifactOwnerType.SOURCE_REVISION);
            return new StoredArtifactView(UUID.randomUUID(),project,null,command.ownerType(),command.ownerId(),command.artifactType(),
                    "bucket","key",command.contentType(),command.content().length,SourceSelection.hash(command.content()),Map.of(),null);
        });
        when(repo.save(eq(project),any())).thenAnswer(call->call.getArgument(1));
        var service=new ReportSourceSnapshotService(repo,materializer,artifacts);
        var manifest=service.prepare(revision(SourceRevisionType.ARCHIVE,null,null));
        assertThat(manifest.files()).hasSize(6);
        assertThat(manifest.files()).filteredOn(ReportSourceSnapshotService.File::contextAvailable).hasSize(2);
        assertThat(manifest.files()).extracting(ReportSourceSnapshotService.File::reasonCode)
                .contains("BINARY_FILE","INVALID_UTF8","PATH_UNSAFE","FILE_TOO_LARGE");
        assertThat(manifest.files()).filteredOn(f->f.path().equals("ok.c")).first().satisfies(file->{
            assertThat(file.totalLines()).isEqualTo(2); assertThat(file.artifactId()).isNotNull();
        });
        when(repo.find(revisionId)).thenReturn(Optional.of(manifest));
        assertThat(service.prepare(revision(SourceRevisionType.ARCHIVE,null,null))).isSameAs(manifest);
        verify(materializer,times(1)).prepare(any());
    }
    @Test void unavailableSourceIsExplicitAndNotPersistedAsPermanentEmptyManifest() throws Exception {
        var repo=mock(SourceManifestRepository.class); var materializer=mock(ReportSourceMaterializer.class);
        var service=new ReportSourceSnapshotService(repo,materializer,mock(StoredArtifactService.class));
        when(materializer.prepare(any())).thenThrow(new IOException("missing"));
        assertThat(service.prepare(revision(SourceRevisionType.ARCHIVE,null,null)).reasonCode()).isEqualTo("SOURCE_CONTEXT_UNAVAILABLE");
        verify(repo,never()).save(any(),any());
        doThrow(new InterruptedException()).when(materializer).prepare(any());
        try {
            assertThatThrownBy(()->service.prepare(revision(SourceRevisionType.ARCHIVE,null,null))).isInstanceOf(IllegalStateException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }
    private SourceRevisionView revision(SourceRevisionType type,Path archive,String commit) {
        return new SourceRevisionView(revisionId,project,type,"v1",null,null,"main",commit,
                archive==null?null:archive.toString(),directory.toString(),null,true,UUID.randomUUID(),null);
    }
}
