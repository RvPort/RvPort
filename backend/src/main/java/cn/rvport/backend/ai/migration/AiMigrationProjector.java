package cn.rvport.backend.ai.migration;

import cn.rvport.backend.kb.FindingView;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 将一次 KB 扫描的 findings 投影为迁移 agent 可消费的条目集合。
 *
 * <p>条目身份双轨：{@code id} 使用 evidence 中的 {@code scannerFindingId}（扫描器 stable_view_id，
 * 跨扫描运行稳定），{@code sourceFindingIds} 携带当前运行 findings 的数据库 UUID 仅作回填句柄。
 * findings 主键每运行全删重插、fingerprint 公式含 runId，两者跨运行均不稳定，禁止作为条目身份。
 */
public final class AiMigrationProjector {

    public static final String SCHEMA_VERSION = "ai-migration.v1";
    static final String STATUS_TODO = "TODO";

    private AiMigrationProjector() {
    }

    public record Projection(UUID scanRunId, String schemaVersion, List<Item> items) {
    }

    public record Item(
            String id,
            List<UUID> sourceFindingIds,
            String status,
            @JsonProperty("solver_type") String solverType,
            String file,
            Integer line,
            String symbol,
            @JsonProperty("matched_text") String matchedText,
            String severity,
            Boolean blocking,
            String description,
            String suggestion,
            Boolean uncertain,
            List<EvidenceRef> evidence) {
    }

    public record EvidenceRef(String file, Integer line, String symbol) {
    }

    public static Projection project(UUID scanRunId, List<FindingView> findings) {
        Map<String, Item> items = new LinkedHashMap<>();
        Map<String, List<UUID>> findingIds = new LinkedHashMap<>();
        for (FindingView finding : findings) {
            if (skipped(finding)) {
                continue;
            }
            String itemId = itemId(finding);
            findingIds.computeIfAbsent(itemId, key -> new ArrayList<>()).add(finding.id());
            items.computeIfAbsent(itemId, key -> toItem(finding));
        }
        List<Item> merged = items.entrySet().stream()
                .map(entry -> withFindingIds(entry.getValue(), findingIds.get(entry.getKey())))
                .toList();
        return new Projection(scanRunId, SCHEMA_VERSION, merged);
    }

    private static boolean skipped(FindingView finding) {
        Map<String, Object> evidence = finding.evidence() == null ? Map.of() : finding.evidence();
        if ("test_asset".equals(textValue(evidence.get("factKind")))) {
            return true;
        }
        if ("inactive".equals(textValue(evidence.get("conclusionStatus")))) {
            return true;
        }
        if (Boolean.TRUE.equals(boolValue(evidence.get("actionable")))) {
            return false;
        }
        // 不可行动条目：仅架构相关的不确定类放行（交给 agent 必要性分析），
        // 纯提示（注释线索等）与非架构类仍然跳过。
        return !architectureUncertain(textValue(evidence.get("findingType")));
    }

    /**
     * 架构相关的不确定类白名单：x86/ARM 专有 API、intrinsic、向量类型、汇编、寄存器、
     * 目标属性、架构别名/宏/枚举候选、构建选择不确定、测试选择不确定等——扫描器未能
     * 证明参与编译，但条目本身是架构绑定信号，正适合 agent 做 NO_WORK_NEEDED 判定。
     * 纯注释提示（x86_comment_hint）与 hint 级其余类别不在此列。
     */
    static boolean architectureUncertain(String findingType) {
        if (findingType == null) {
            return false;
        }
        if (findingType.equals("x86_comment_hint") || findingType.equals("hint")) {
            return false;
        }
        return findingType.startsWith("x86_")
                || findingType.equals("target_attribute_candidate")
                || findingType.equals("inline_asm_candidate")
                || findingType.equals("architecture_candidate")
                || findingType.equals("enum_architecture_candidate")
                || findingType.equals("fixed_vector_width_candidate")
                || findingType.equals("build_selection_unknown")
                || findingType.equals("test_selection_unknown");
    }

    private static Item toItem(FindingView finding) {
        Map<String, Object> evidence = finding.evidence() == null ? Map.of() : finding.evidence();
        return new Item(
                itemId(finding),
                List.of(finding.id()),
                STATUS_TODO,
                solverType(finding),
                finding.filePath(),
                finding.lineStart(),
                textValue(evidence.get("symbol")) == null ? finding.symbol() : textValue(evidence.get("symbol")),
                textValue(evidence.get("matchedText")),
                finding.severity(),
                boolValue(evidence.get("blocking")),
                description(finding),
                finding.suggestion(),
                !Boolean.TRUE.equals(boolValue(evidence.get("actionable"))),
                List.of(new EvidenceRef(finding.filePath(), finding.lineStart(), finding.symbol())));
    }

    private static Item withFindingIds(Item item, List<UUID> ids) {
        return new Item(item.id(), List.copyOf(ids), item.status(), item.solverType(), item.file(), item.line(),
                item.symbol(), item.matchedText(), item.severity(), item.blocking(), item.description(),
                item.suggestion(), item.uncertain(), item.evidence());
    }

    /** fact.kind → solver_type；注意不得使用 findings.category（conclusion.findingType，语义不同）。 */
    static String solverType(FindingView finding) {
        Map<String, Object> evidence = finding.evidence() == null ? Map.of() : finding.evidence();
        String factKind = textValue(evidence.get("factKind"));
        String file = finding.filePath() == null ? "" : finding.filePath();
        String lower = file.toLowerCase();
        return switch (factKind == null ? "" : factKind) {
            case "intrinsic_call", "vector_type" -> "intrinsics";
            case "macro_invocation", "macro_replacement" -> "macros";
            case "architecture_probe" -> lower.endsWith(".s") || lower.endsWith(".asm") ? "asm_file" : "inline_asm";
            case "compiler_flag", "build_argument" -> isCmakeFile(file) ? "build_cmake" : "build_makefile";
            case "header_include" -> "headers";
            default -> "inline_asm";
        };
    }

    private static boolean isCmakeFile(String file) {
        String normalized = file == null ? "" : file;
        String lower = normalized.toLowerCase();
        return lower.endsWith("cmakelists.txt") || lower.endsWith(".cmake");
    }

    private static String description(FindingView finding) {
        if (finding.description() == null || finding.description().isBlank()) {
            return finding.title();
        }
        return finding.title() + "：" + finding.description();
    }

    private static String itemId(FindingView finding) {
        Map<String, Object> evidence = finding.evidence() == null ? Map.of() : finding.evidence();
        String scannerFindingId = textValue(evidence.get("scannerFindingId"));
        if (scannerFindingId != null && !scannerFindingId.isBlank()) {
            return scannerFindingId;
        }
        String basis = (nullToEmpty(finding.ruleId())) + "|" + nullToEmpty(finding.filePath()) + "|"
                + (finding.lineStart() == null ? "" : finding.lineStart()) + "|" + nullToEmpty(finding.symbol());
        return "finding:" + sha256(basis);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String textValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    private static Boolean boolValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return null;
    }
}
