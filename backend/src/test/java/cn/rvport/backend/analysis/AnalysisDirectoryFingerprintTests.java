package cn.rvport.backend.analysis;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalysisDirectoryFingerprintTests {
    @TempDir Path temporary;
    @Test void hashesRelativeNamesAndContentsIndependentlyOfLocation() throws Exception {
        Path a = Files.createDirectory(temporary.resolve("a")), b = Files.createDirectory(temporary.resolve("b"));
        for (Path root : new Path[]{a, b}) {
            Files.createDirectory(root.resolve("sub"));
            Files.writeString(root.resolve("sub/rules.json"), "{}\n");
        }
        String original = AnalysisDirectoryFingerprint.digest(a);
        assertThat(original).isEqualTo(AnalysisDirectoryFingerprint.digest(b));
        Files.writeString(b.resolve("sub/rules.json"), "{\"v\":1}");
        assertThat(original).isNotEqualTo(AnalysisDirectoryFingerprint.digest(b));
        Files.move(a.resolve("sub/rules.json"), a.resolve("sub/renamed.json"));
        assertThat(original).isNotEqualTo(AnalysisDirectoryFingerprint.digest(a));
    }
    @Test void rejectsMissingRootSymlinksSpecialFilesAndResourceOverflow() throws Exception {
        assertThatThrownBy(() -> AnalysisDirectoryFingerprint.digest(temporary.resolve("missing"))).hasMessage("ANALYSIS_RULES_UNAVAILABLE");
        Path root = Files.createDirectory(temporary.resolve("rules"));
        Path file = Files.writeString(root.resolve("a"), "content");
        assertThatThrownBy(() -> AnalysisDirectoryFingerprint.digest(file)).hasMessage("ANALYSIS_RULES_UNAVAILABLE");
        assertThatThrownBy(() -> AnalysisDirectoryFingerprint.digest(root, 1, 3)).hasMessage("ANALYSIS_RULES_LIMIT_EXCEEDED");
        Files.writeString(root.resolve("b"), "other");
        assertThatThrownBy(() -> AnalysisDirectoryFingerprint.digest(root, 1, 100)).hasMessage("ANALYSIS_RULES_LIMIT_EXCEEDED");
        Files.createSymbolicLink(root.resolve("link"), file);
        assertThatThrownBy(() -> AnalysisDirectoryFingerprint.digest(root)).hasMessage("ANALYSIS_RULES_PATH_UNSAFE");
        Files.delete(root.resolve("link"));
        Path pipe = root.resolve("pipe");
        assertThat(new ProcessBuilder("mkfifo", pipe.toString()).start().waitFor()).isZero();
        assertThatThrownBy(() -> AnalysisDirectoryFingerprint.digest(root)).hasMessage("ANALYSIS_RULES_PATH_UNSAFE");
    }
}
