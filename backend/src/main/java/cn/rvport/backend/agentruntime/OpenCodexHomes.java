package cn.rvport.backend.agentruntime;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;

/** 文件锁标识活跃执行；重启只回收带本项目标识且已无所有者的目录。 */
final class OpenCodexHomes {
    private static final String MARKER = "rvport-opencodex-owned-v1\n";
    private final Path root;
    private boolean recovered;

    OpenCodexHomes(Path root) { this.root = root; }

    synchronized Lease create() throws IOException {
        if (!recovered) { recover(); recovered = true; }
        Path home = Files.createTempDirectory(root, "rvport-opencodex-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        try {
            Path marker = home.resolve(".owner");
            FileChannel channel = FileChannel.open(marker, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW);
            try {
                FileLock lock = channel.lock();
                channel.write(java.nio.ByteBuffer.wrap(MARKER.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                return new Lease(home, channel, lock);
            }
            catch (IOException | RuntimeException ex) { channel.close(); throw ex; }
        } catch (IOException | RuntimeException ex) { remove(home); throw ex; }
    }

    void recover() throws IOException {
        try (var paths = Files.newDirectoryStream(root, "rvport-opencodex-*")) {
            for (Path home : paths) {
                Path marker = home.resolve(".owner");
                if (!Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS)
                        || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(marker) != MARKER.length() || !MARKER.equals(Files.readString(marker))) { continue; }
                try (FileChannel channel = FileChannel.open(marker, StandardOpenOption.WRITE)) {
                    try (FileLock lock = channel.tryLock()) {
                        if (lock != null) { remove(home); }
                    } catch (OverlappingFileLockException ignored) { /* 同一 JVM 的其他活跃执行。 */ }
                }
            }
        }
    }

    private static void remove(Path home) throws IOException {
        try (var paths = Files.walk(home)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) { Files.deleteIfExists(path); }
        }
    }

    record Lease(Path path, FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override public void close() throws IOException {
            try { remove(path); }
            finally { try { lock.release(); } finally { channel.close(); } }
        }
    }
}
