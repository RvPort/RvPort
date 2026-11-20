package cn.rvport.backend.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** 失败输出只提取白名单诊断，不发布半截 findings 或把信号退出推断为 OOM。 */
public final class AnalysisFailureDiagnostics {
    private static final long MAX_BYTES = 256 * 1024;
    private AnalysisFailureDiagnostics() { }

    public static Map<String, Object> kb(Path result, Integer exitCode, int memoryLimitMib) {
        Map<String, Object> diagnostic = new LinkedHashMap<>();
        diagnostic.put("reasonCode", exitCode != null && exitCode >= 128 ? "ANALYSIS_PROCESS_TERMINATED" : "ANALYSIS_PROCESS_FAILED");
        diagnostic.put("message", "扫描执行失败，本次结果未发布。");
        diagnostic.put("message_en", "The scanner failed; this attempt did not publish a result.");
        diagnostic.put("resultAvailable", false);
        diagnostic.put("memoryLimitMib", memoryLimitMib);
        if (exitCode != null) diagnostic.put("exitCode", exitCode);
        try {
            if (!Files.isRegularFile(result) || Files.isSymbolicLink(result)) return diagnostic;
            if (Files.size(result) > MAX_BYTES) {
                diagnostic.put("diagnosticStatus", "SIZE_LIMIT_EXCEEDED");
                return diagnostic;
            }
            var document = new ObjectMapper().readTree(result.toFile());
            if (document == null) { diagnostic.put("diagnosticStatus", "INVALID"); return diagnostic; }
            for (var entry : document.path("diagnostics")) {
                if ("pipeline.memory-budget-exceeded".equals(entry.path("code").asText())
                        && "fatal".equalsIgnoreCase(entry.path("severity").asText())) {
                    diagnostic.put("toolDiagnosticCode", "pipeline.memory-budget-exceeded");
                    diagnostic.put("reasonCode", "ANALYSIS_MEMORY_BUDGET_EXCEEDED");
                    diagnostic.put("message", "扫描进程报告内存预算超限，本次结果未发布。");
                    diagnostic.put("message_en", "The scanner reported an exceeded memory budget; this attempt did not publish a result.");
                    break;
                }
            }
        } catch (java.io.IOException | RuntimeException ex) {
            diagnostic.put("diagnosticStatus", "UNREADABLE_OR_TRUNCATED");
        }
        return diagnostic;
    }
}
