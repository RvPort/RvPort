package cn.rvport.backend.report.review;

import cn.rvport.backend.artifact.StoredArtifactCreateCommand;
import cn.rvport.backend.artifact.StoredArtifactOwnerType;
import cn.rvport.backend.artifact.StoredArtifactService;
import cn.rvport.backend.source.SourceRevisionView;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ReportSourceSnapshotService {
    public record File(String fileKey,String path,String contentHash,Integer totalLines,UUID artifactId,boolean contextAvailable,String reasonCode) { }
    public record Manifest(UUID sourceRevisionId,String sourceDigest,List<File> files,String reasonCode) { }
    private final SourceManifestRepository repository;
    private final ReportSourceMaterializer materializer;
    private final StoredArtifactService artifacts;
    public ReportSourceSnapshotService(SourceManifestRepository repository,ReportSourceMaterializer materializer,StoredArtifactService artifacts) {
        this.repository=repository; this.materializer=materializer; this.artifacts=artifacts;
    }
    public Manifest existing(UUID revision) {
        return revision==null?new Manifest(null,null,List.of(),"SOURCE_CONTEXT_UNAVAILABLE"):
                repository.find(revision).orElseGet(()->new Manifest(revision,null,List.of(),"SOURCE_NOT_CAPTURED"));
    }
    public Manifest prepare(SourceRevisionView revision) {
        var existing=repository.find(revision.id());
        if(existing.isPresent()) return existing.get();
        try(var source=materializer.prepare(revision)) {
            List<File> files=new ArrayList<>();
            UUID captureId=UUID.randomUUID();
            try(var entries=Files.walk(source.sourceRoot())) {
                for(Path path:entries.filter(p->!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)).sorted().toList()) {
                    String relative=source.sourceRoot().relativize(path).toString().replace('\\','/');
                    String key=SourceSelection.hash(relative.getBytes(StandardCharsets.UTF_8));
                    if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) { files.add(unavailable(key,relative,"PATH_UNSAFE")); continue; }
                    if(Files.size(path)>10L*1024*1024) { files.add(unavailable(key,relative,"FILE_TOO_LARGE")); continue; }
                    byte[] bytes=Files.readAllBytes(path);
                    String content;
                    try { content=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
                    catch(java.nio.charset.CharacterCodingException ex) { files.add(unavailable(key,relative,"INVALID_UTF8")); continue; }
                    if(content.indexOf('\0')>=0) { files.add(unavailable(key,relative,"BINARY_FILE")); continue; }
                    String hash=SourceSelection.hash(bytes);
                    int lines=content.isEmpty()?0:content.split("\\r\\n|\\r|\\n",-1).length;
                    var artifact=artifacts.store(new StoredArtifactCreateCommand(revision.projectId(),null,StoredArtifactOwnerType.SOURCE_REVISION,
                            captureId,"SOURCE_"+key,"text/plain; charset=utf-8",bytes,Map.of("sourceRevisionId",revision.id(),"fileKey",key,"contentHash",hash),"txt"));
                    files.add(new File(key,relative,hash,lines,artifact.id(),true,null));
                }
            }
            return repository.save(revision.projectId(),new Manifest(revision.id(),source.sourceDigest(),List.copyOf(files),null));
        } catch(InterruptedException ex) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("SOURCE_SNAPSHOT_INTERRUPTED",ex);
        } catch(IOException|cn.rvport.backend.source.archive.SourceArchiveException ex) {
            // 不固定失败为空清单；来源恢复后可重新提取。当前报告明确记录本次不可用事实。
            return new Manifest(revision.id(),null,List.of(),"SOURCE_CONTEXT_UNAVAILABLE");
        }
    }
    private File unavailable(String key,String path,String reason) { return new File(key,path,null,null,null,false,reason); }
}
