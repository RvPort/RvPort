package cn.rvport.backend.kb.runtime;

import static org.assertj.core.api.Assertions.*;
import cn.rvport.backend.analysis.AnalysisInputPlan;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KbSourceInventoryTests {
    @TempDir Path root;

    private AnalysisInputPlan.Identity identity(String language, Map<String, Object> scope, List<String> excludes) {
        return AnalysisInputPlan.Identity.of(language, "x86", "riscv64", scope, excludes, "rules@1");
    }

    @Test void languageCountsAreFilesAndDoNotInventAnalyzedTranslationUnits() throws Exception {
        for (String name : List.of("a.c", "b.cc", "c.cpp", "d.cxx", "a.h", "b.hh", "c.hpp", "d.hxx", "e.inc",
                "a.rs", "a.go", "a.java", "a.py", "a.pyx", "a.s", "b.S", "c.asm", "README", "a.txt"))
            Files.writeString(root.resolve(name), "content");
        var found = KbSourceInventory.inspect(root,
                identity("c,cpp,rust,go,java,python,asm", Map.of("mode", "ALL"), List.of()), 100);
        assertThat(found.files()).hasSize(19);
        assertThat(found.coverage()).containsEntry("denominatorKnown", false)
                .containsEntry("manifestFileCounts", Map.of("PLANNED", 17, "UNSUPPORTED", 2))
                .containsEntry("plannedFilesByLanguage", Map.of("c", 6, "cpp", 8, "rust", 1, "go", 1, "java", 1, "python", 2, "asm", 3))
                .doesNotContainKeys("analyzedTuCount", "analyzedFileCount");
        assertThat(found.excludedPaths()).isEmpty();
    }

    @Test void scopeComplementAndExactExclusionsMatchRootRelativePaths() throws Exception {
        Files.createDirectories(root.resolve("src/nested"));
        for (String name : List.of("src/a.c", "src/nested/b.c", "src/c.py", "outside.c", "single.c"))
            Files.writeString(root.resolve(name), "content");
        var found = KbSourceInventory.inspect(root, identity("c", Map.of("mode", "PATHS", "paths", List.of("src", "single.c")),
                List.of("src/nested")), 100);
        assertThat(found.coverage()).containsEntry("manifestFileCounts", Map.of("PLANNED", 2, "NOT_SELECTED", 2, "EXCLUDED", 1));
        assertThat(found.excludedPaths()).containsExactly("outside.c", "src/nested");
        assertThat(found.files().stream().filter(file -> "PLANNED".equals(file.get("status")))).extracting(file -> file.get("path"))
                .containsExactly("single.c", "src/a.c");
        var empty = KbSourceInventory.inspect(Files.createDirectory(root.resolve("empty")), identity("c", Map.of("mode", "ALL"), List.of()), 1);
        assertThat(empty.coverage()).containsEntry("knownSourceFileCount", 0);
    }

    @Test void rejectsUnboundedUnsafeAndSpecialInputs() throws Exception {
        var id = identity("c", Map.of("mode", "ALL"), List.of());
        assertThatThrownBy(() -> KbSourceInventory.inspect(root.resolve("absent"), id, 1)).hasMessageContaining("INVALID");
        assertThatThrownBy(() -> KbSourceInventory.inspect(root, id, 0)).hasMessageContaining("INVALID");
        Files.writeString(root.resolve("a.c"), "x");
        Files.writeString(root.resolve("b.c"), "x");
        assertThatThrownBy(() -> KbSourceInventory.inspect(root, id, 1)).hasMessageContaining("LIMIT_EXCEEDED");
        Path link = Files.createSymbolicLink(root.resolve("link"), root.resolve("a.c"));
        assertThatThrownBy(() -> KbSourceInventory.inspect(root, id, 100)).hasMessageContaining("INVALID");
        assertThatThrownBy(() -> KbSourceInventory.inspect(link, id, 100)).hasMessageContaining("INVALID");
        Files.delete(link);
        for (String invalid : List.of("bad:name.c", "bad\nname.c")) {
            Path file = Files.writeString(root.resolve(invalid), "x");
            assertThatThrownBy(() -> KbSourceInventory.inspect(root, id, 100)).hasMessageContaining("INVALID");
            Files.delete(file);
        }
        Path fifo = root.resolve("pipe");
        assertThat(new ProcessBuilder("mkfifo", fifo.toString()).start().waitFor()).isZero();
        assertThatThrownBy(() -> KbSourceInventory.inspect(root, id, 100)).hasMessageContaining("INVALID");
    }
}
