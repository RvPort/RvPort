package cn.rvport.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalysisFailureDiagnosticsTests {
    @TempDir Path root;
    @Test void onlyExplicitFatalDiagnosticProvesMemoryFailureAndNeverExportsRawContent() throws Exception {
        Path result = root.resolve("result.json");
        Files.writeString(result, "{\"diagnostics\":[{\"code\":\"pipeline.memory-budget-exceeded\",\"severity\":\"FATAL\",\"message\":\"secret/path/key\"}],\"findings\":[{}]}");
        var summary = AnalysisFailureDiagnostics.kb(result, 1, 2048);
        assertThat(summary).containsEntry("reasonCode", "ANALYSIS_MEMORY_BUDGET_EXCEEDED").containsEntry("memoryLimitMib", 2048)
                .containsEntry("resultAvailable", false).containsEntry("exitCode", 1);
        assertThat(summary.toString()).doesNotContain("secret", "findings").contains("message_en");
        Files.writeString(result, "{\"diagnostics\":[{\"code\":\"other\",\"severity\":\"fatal\"},{\"code\":\"pipeline.memory-budget-exceeded\",\"severity\":\"warning\"}]}");
        assertThat(AnalysisFailureDiagnostics.kb(result, 137, 0)).containsEntry("reasonCode", "ANALYSIS_PROCESS_TERMINATED");
    }
    @Test void missingCorruptOversizeAndLinkedOutputRemainUnknownAndBounded() throws Exception {
        Path file = root.resolve("result.json");
        assertThat(AnalysisFailureDiagnostics.kb(file, null, 0)).doesNotContainKey("exitCode");
        Files.writeString(file, "{");
        assertThat(AnalysisFailureDiagnostics.kb(file, 1, 0)).containsEntry("diagnosticStatus", "UNREADABLE_OR_TRUNCATED");
        Files.writeString(file, " ");
        assertThat(AnalysisFailureDiagnostics.kb(file, 1, 0)).containsEntry("reasonCode", "ANALYSIS_PROCESS_FAILED");
        Files.writeString(file, "x".repeat(256 * 1024 + 1));
        assertThat(AnalysisFailureDiagnostics.kb(file, 137, 0)).containsEntry("diagnosticStatus", "SIZE_LIMIT_EXCEEDED");
        Path link = root.resolve("linked.json"); Files.createSymbolicLink(link, file);
        assertThat(AnalysisFailureDiagnostics.kb(link, 137, 0)).doesNotContainKey("toolDiagnosticCode");
        assertThat(AnalysisFailureDiagnostics.kb(root, 1, 0)).containsEntry("reasonCode", "ANALYSIS_PROCESS_FAILED");
    }
}
