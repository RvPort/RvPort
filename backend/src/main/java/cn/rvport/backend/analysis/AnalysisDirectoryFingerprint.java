package cn.rvport.backend.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 规则目录的确定性身份，拒绝链接和无界输入，不含服务器绝对路径。 */
public final class AnalysisDirectoryFingerprint {
    private AnalysisDirectoryFingerprint() { }
    public static String digest(Path root) throws IOException {
        return digest(root, 100_000, 256L * 1024 * 1024);
    }
    static String digest(Path root, int maximumEntries, long maximumBytes) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("ANALYSIS_RULES_UNAVAILABLE");
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            long bytes = 0;
            try (var walk = Files.walk(root)) {
                var paths = walk.limit((long) maximumEntries + 2).sorted().toList();
                if (paths.size() > maximumEntries + 1) throw new IOException("ANALYSIS_RULES_LIMIT_EXCEEDED");
                for (Path path : paths) {
                    if (Files.isSymbolicLink(path)) throw new IOException("ANALYSIS_RULES_PATH_UNSAFE");
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("ANALYSIS_RULES_PATH_UNSAFE");
                    digest.update(root.relativize(path).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    var content = MessageDigest.getInstance("SHA-256");
                    try (var stream = Files.newInputStream(path)) {
                        byte[] buffer = new byte[65536]; int read;
                        while ((read = stream.read(buffer)) != -1) {
                            bytes += read;
                            if (bytes > maximumBytes) throw new IOException("ANALYSIS_RULES_LIMIT_EXCEEDED");
                            content.update(buffer, 0, read);
                        }
                    }
                    digest.update(content.digest());
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
