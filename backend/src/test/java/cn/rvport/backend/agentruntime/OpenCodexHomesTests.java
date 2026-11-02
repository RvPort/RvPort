package cn.rvport.backend.agentruntime;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenCodexHomesTests {
    @TempDir Path root;
    @Test void privateHomesAreIsolatedAndRemovedAfterUse() throws Exception {
        var homes=new OpenCodexHomes(root);
        Path first;
        try (var a=homes.create(); var b=homes.create()) {
            first=a.path();
            assertThat(first).isNotEqualTo(b.path());
            assertThat(Files.getPosixFilePermissions(first)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
            Files.writeString(first.resolve("secret"),"credential");
            new OpenCodexHomes(root).recover();
            assertThat(first.resolve("secret")).exists();
            assertThat(b.path()).exists();
        }
        assertThat(first).doesNotExist();
        try(var paths=Files.list(root)) { assertThat(paths.toList()).isEmpty(); }
    }
    @Test void abandonedOwnedDirectoryIsRemovedWithoutFollowingLinks() throws Exception {
        var homes=new OpenCodexHomes(root);
        var abandoned=homes.create();
        abandoned.lock().release(); abandoned.channel().close();
        Path outside=Files.createDirectory(root.resolve("external"));
        Files.writeString(outside.resolve("keep"),"unrelated");
        Files.createSymbolicLink(abandoned.path().resolve("link"),outside);
        homes.recover();
        assertThat(abandoned.path()).doesNotExist();
        assertThat(outside.resolve("keep")).exists();
    }
    @Test void foreignUnmarkedAndSymlinkDirectoriesAreUntouched() throws Exception {
        Path foreign=Files.createDirectory(root.resolve("rvport-opencodex-foreign"));
        Path unmarked=Files.createDirectory(root.resolve("rvport-opencodex-unmarked"));
        Files.writeString(foreign.resolve(".owner"),"unrelated");
        Files.createSymbolicLink(root.resolve("rvport-opencodex-link"),foreign);
        new OpenCodexHomes(root).recover();
        assertThat(foreign).exists(); assertThat(unmarked).exists();
        Files.writeString(foreign.resolve(".owner"),"x".repeat("rvport-opencodex-owned-v1\n".length()));
        new OpenCodexHomes(root).recover();
        assertThat(foreign).exists();
    }
}
