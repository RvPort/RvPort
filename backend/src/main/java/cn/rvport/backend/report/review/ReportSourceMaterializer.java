package cn.rvport.backend.report.review;

import cn.rvport.backend.source.SourceRevisionType;
import cn.rvport.backend.source.SourceRevisionView;
import cn.rvport.backend.source.archive.SourceArchiveExtractor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 从保存的上传包或固定 Git 对象提取；从不使用工作区文件或跟随远端分支。 */
@Component
public class ReportSourceMaterializer {
    private final SourceArchiveExtractor extractor;
    private final GitArchive git;
    @Autowired public ReportSourceMaterializer(SourceArchiveExtractor extractor) { this(extractor,ReportSourceMaterializer::archive); }
    ReportSourceMaterializer(SourceArchiveExtractor extractor,GitArchive git) { this.extractor=extractor; this.git=git; }
    interface GitArchive { void write(Path repository,String commit,Path target) throws IOException,InterruptedException; }
    public record Materialized(Path temporaryRoot,Path sourceRoot,String sourceDigest) implements AutoCloseable {
        @Override public void close() throws IOException { remove(temporaryRoot); }
    }
    public Materialized prepare(SourceRevisionView revision) throws IOException,InterruptedException {
        Path temporary=Files.createTempDirectory("rvport-report-source-");
        try {
            Path archive; String filename; String digest;
            if(revision.revisionType()==SourceRevisionType.ARCHIVE) {
                if(revision.archivePath()==null) throw new IOException("SOURCE_ARCHIVE_UNAVAILABLE");
                Path original=Path.of(revision.archivePath());
                if(!Files.isRegularFile(original,java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new IOException("SOURCE_ARCHIVE_UNAVAILABLE");
                filename=original.getFileName().toString(); archive=temporary.resolve(filename);
                Files.copy(original,archive);
                digest=hashFile(archive);
            } else {
                if(revision.gitCommit()==null || !revision.gitCommit().matches("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")
                        || revision.workingCopyPath()==null) throw new IOException("SOURCE_COMMIT_UNAVAILABLE");
                filename="source.tar.gz"; archive=temporary.resolve(filename);
                git.write(Path.of(revision.workingCopyPath()),revision.gitCommit(),archive);
                digest="git:"+revision.gitCommit().toLowerCase(java.util.Locale.ROOT);
            }
            Path source=extractor.extract(archive,filename,temporary.resolve("source")).workingCopyPath();
            return new Materialized(temporary,source,digest);
        } catch(IOException|InterruptedException|RuntimeException ex) {
            try { remove(temporary); } catch(IOException cleanup) { ex.addSuppressed(cleanup); }
            throw ex;
        }
    }
    static String hashFile(Path path) throws IOException {
        try(var stream=Files.newInputStream(path)) {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer=new byte[65536]; int read;
            while((read=stream.read(buffer))!=-1) digest.update(buffer,0,read);
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static void archive(Path repository,String commit,Path target) throws IOException,InterruptedException {
        ProcessBuilder builder=new ProcessBuilder(List.of("git","-C",repository.toAbsolutePath().toString(),
                "-c","core.attributesFile=/dev/null","archive","--format=tar.gz","--output="+target.toAbsolutePath(),commit));
        builder.environment().put("GIT_TERMINAL_PROMPT","0");
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process=builder.start();
        try {
            if(!process.waitFor(Duration.ofSeconds(60).toMillis(),TimeUnit.MILLISECONDS)) throw new IOException("SOURCE_ARCHIVE_TIMEOUT");
            if(process.exitValue()!=0) throw new IOException("SOURCE_COMMIT_UNAVAILABLE");
        } finally { if(process.isAlive()) process.destroyForcibly(); }
    }
    static void remove(Path root) throws IOException {
        if(!Files.exists(root,java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try(var paths=Files.walk(root)) {
            for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
