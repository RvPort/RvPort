package cn.rvport.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import cn.rvport.backend.buildvalidation.bitcode.BitcodeManifest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompileEntryClassifierTests {

    @TempDir Path tempDir;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void relativeEntryUsesItsOwnCaseSensitiveBuildDirectory() throws Exception {
        Path database = Files.writeString(tempDir.resolve("relative.json"), """
                [{"file":"main.c","directory":"/remote/Src/lib"},
                 {"file":"main.c","directory":"/remote/other"},
                 {"file":"./other.c","directory":"/remote/Src/lib/"},
                 {"file":"../escape.c","directory":"/remote/Src/lib"}]
                """);
        var result = new CompileEntryClassifier(mapper).classify(database, tempDir, "/remote/Src");
        assertThat(result.projectUnits()).extracting(CompileEntryClassification.ProjectUnit::path)
                .containsExactly("lib/main.c", "lib/other.c");
        assertThat(result.excludedReasons()).containsEntry("OUTSIDE_SOURCE_ROOT", 2);
    }

    @Test void pinnedBuildRootDoesNotUseRandomMaterializationDirectoryName() throws Exception {
        Path database = Files.writeString(tempDir.resolve("pinned.json"), """
                [{"file":"/remote/fixed/main.c"},{"file":"/other/main.c"},{"file":"relative.c"},
                 {"file":"../escape.c"},{"file":"C:/other/main.c"}]
                """);
        var result = new CompileEntryClassifier(mapper).classify(database, tempDir, "/remote/fixed/");
        assertThat(result.projectUnits()).extracting(CompileEntryClassification.ProjectUnit::path).containsExactly("main.c", "relative.c");
        assertThat(result.excludedReasons()).containsEntry("OUTSIDE_SOURCE_ROOT", 3);
    }

    @Test
    void separatesRawCandidatesProjectUnitsDuplicatesAndBuildProbes() throws Exception {
        Path source = Files.createDirectories(tempDir.resolve("revision-1"));
        Path database = tempDir.resolve("compile_commands.json");
        Files.writeString(database, """
                [
                  {"file":"/remote/revision-1/src/main.cpp","arguments":["c++","-std=c++17","-c","src/main.cpp"]},
                  {"file":"/remote/revision-1/src/main.cpp","arguments":["c++","-std=c++17","-c","src/main.cpp"]},
                  {"file":"/remote/revision-1/build/CMakeFiles/CompilerIdCXX/CMakeCXXCompilerId.cpp","arguments":["c++","-c"]},
                  {"file":"/usr/share/cmake/Modules/CMakeCXXCompilerABI.cpp","directory":"/remote/revision-1/build/CMakeFiles/CMakeScratch/TryCompile-x","arguments":["c++","-c"]},
                  {"file":"NULL_SOURCE_FOR_LD","arguments":["ld"]},
                  {"file":"/remote/revision-1/generated/model.c","arguments":["cc","-c"]}
                ]
                """);

        CompileEntryClassification result = new CompileEntryClassifier(mapper).classify(database, source);

        assertThat(result.rawCommandCount()).isEqualTo(6);
        assertThat(result.cCppCandidateCount()).isEqualTo(5);
        assertThat(result.projectTranslationUnitCount()).isEqualTo(2);
        assertThat(result.projectUnits()).extracting(CompileEntryClassification.ProjectUnit::path)
                .containsExactly("generated/model.c", "src/main.cpp");
        assertThat(result.excludedReasons())
                .containsEntry("DUPLICATE_CONTEXT", 1)
                .containsEntry("BUILD_SYSTEM_PROBE", 2)
                .containsEntry("NON_C_CPP_COMMAND", 1);
    }

    @Test
    void invalidDocumentIsAuditedInsteadOfInventingUnits() throws Exception {
        Path source = Files.createDirectories(tempDir.resolve("revision-2"));
        Path database = Files.writeString(tempDir.resolve("invalid.json"), "{}");
        CompileEntryClassification result = new CompileEntryClassifier(mapper).classify(database, source);
        assertThat(result.projectTranslationUnitCount()).isZero();
        assertThat(result.excludedReasons()).containsEntry("INVALID_DOCUMENT", 1);
    }

    @Test
    void handlesRelativeWindowsOutsideAndDistinctCommandContexts() throws Exception {
        Path source = Files.createDirectories(tempDir.resolve("revision-3"));
        Path database = tempDir.resolve("compile-contexts.json");
        Files.writeString(database, """
                [
                  {"file":"./src/local.CPP","arguments":["c++","-std=c++17","-Wall"]},
                  {"file":"src/local.CPP","arguments":["c++","-std=c++20","-DDEBUG","-Iinc","-mavx","-ffast-math"]},
                  {"file":"C:\\\\remote\\\\revision-3\\\\src\\\\win.CXX","command":"c++ -std=c++20 -c src/win.CXX"},
                  {"file":"/other/out.cp","arguments":["c++"]},
                  {"file":"build/CMakeFiles/CMakeCCompilerABI.c","arguments":["cc"]},
                  {"file":"build/CMakeFiles/CompilerIdC/a.c","arguments":["cc"]},
                  {"file":"src/try.cc","directory":"C:\\\\remote\\\\revision-3\\\\build\\\\CMakeFiles\\\\CMakeScratch\\\\TryCompile-a","arguments":["c++"]},
                  {"file":"include/not-a-tu.hpp","arguments":["c++"]}
                ]
                """);

        CompileEntryClassification result = new CompileEntryClassifier(mapper).classify(database, source);

        assertThat(result.rawCommandCount()).isEqualTo(8);
        assertThat(result.cCppCandidateCount()).isEqualTo(7);
        assertThat(result.projectTranslationUnitCount()).isEqualTo(3);
        assertThat(result.projectUnits()).extracting(CompileEntryClassification.ProjectUnit::path)
                .containsExactly("src/local.CPP", "src/local.CPP", "src/win.CXX");
        assertThat(result.projectUnits()).extracting(CompileEntryClassification.ProjectUnit::contextKey)
                .contains("-std=c++17", "-std=c++20\u0000-DDEBUG\u0000-Iinc\u0000-mavx\u0000-ffast-math",
                        "c++ -std=c++20 -c src/win.CXX");
        assertThat(result.excludedReasons())
                .containsEntry("OUTSIDE_SOURCE_ROOT", 1)
                .containsEntry("BUILD_SYSTEM_PROBE", 3)
                .containsEntry("NON_C_CPP_COMMAND", 1);
    }

    @Test
    void coverageUsesOnlyUniqueProjectRecordsAndReportsMissingUnits() {
        CompileEntryClassification classification = new CompileEntryClassification(
                3, 3, 3,
                List.of(
                        new CompileEntryClassification.ProjectUnit("src/a.c", ""),
                        new CompileEntryClassification.ProjectUnit("src/b.cpp", ""),
                        new CompileEntryClassification.ProjectUnit("src/c.cxx", "")),
                Map.of());
        BitcodeManifest manifest = new BitcodeManifest(null, null, null, null, null, null, null, null,
                List.of(
                        record("src/a.c", "produced"),
                        record("src/a.c", "failed"),
                        record("src/b.cpp", "failed"),
                        record("outside/system.c", "produced")),
                List.of(), null, null);

        CompileEntryClassifier.Coverage coverage = new CompileEntryClassifier(mapper)
                .coverage(classification, manifest);

        assertThat(coverage).isEqualTo(new CompileEntryClassifier.Coverage(3, 1, 1, 1));
    }

    private BitcodeManifest.ArtifactRecord record(String path, String status) {
        return new BitcodeManifest.ArtifactRecord(
                null, null, null, null, null, status, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                new BitcodeManifest.Source(path, null), null, Map.of(), null, List.of(), List.of(), null);
    }
}
