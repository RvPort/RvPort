package cn.rvport.backend.ai.migration;

import cn.rvport.backend.kb.FindingView;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 将一次 KB 扫描的 findings 投影为 riscv-migrate 技能可消费的 {@code input/scan_result.json}。
 *
 * <p>条目 schema 以技能协议为权威（{@code riscv-scan/references/project_scan.md}）：顶层
 * {@code target / suggestion_class_count / missing_class_count / suggestion_class[] / missing_class[]}，
 * 条目字段为 {@code class_type / file_name / file_path / solver_name / solver_type(整数) / solver_description /
 * start_line / end_line / migration_suggestion / status / marking}。平台专有字段（{@code id} 等）以附加字段
 * 保留；已经由协议字段表达的旧字段不再以原名重复出现。
 *
 * <p>条目身份双轨：{@code id} 使用 evidence 中的 {@code scannerFindingId}（扫描器 stable_view_id，
 * 跨扫描运行稳定），{@code sourceFindingIds} 携带当前运行 findings 的数据库 UUID 仅作回填句柄。
 * findings 主键每运行全删重插、fingerprint 公式含 runId，两者跨运行均不稳定，禁止作为条目身份。
 *
 * <p>{@code missing_class} 一期不投影（设计基线 § 待定项），固定输出空数组以保持协议形状稳定。
 */
public final class AiMigrationProjector {

    public static final String SCHEMA_VERSION = "ai-migration.v1";
    static final String STATUS_TODO = "TODO";
    static final String CLASS_TYPE_SUGGESTION = "suggestion_class";
    static final String DEFAULT_SOURCE_ROOT = "/app/source";

    /**
     * 可放行 {@code actionable=false} 条目的 x86 专有引用类型。
     *
     * <p>这些类型在 RISC-V 上必然要替换；扫描器给出不可行动只是"未能确认参与编译"，
     * 条目仍需交给技能做必要性分析。{@code x86_dependency}/{@code x86_branch} 语义过宽、
     * {@code x86_comment_hint} 不是代码、{@code fixed_vector_width_candidate} 非 x86 专有，均不放行。
     */
    static final Set<String> UNCERTAIN_FINDING_TYPES = Set.of(
            "x86_intrinsic_reference",
            "x86_builtin_reference",
            "x86_vector_type_reference",
            "x86_header_reference",
            "x86_assembly_instruction",
            "x86_register_reference",
            "x86_cpu_platform_api",
            "x86_compiler_flag",
            "x86_platform_macro_reference",
            "x86_target_feature",
            "x86_architecture_alias");

    private AiMigrationProjector() {
    }

    /** solver 类型：技能协议要求整数枚举，分流规则见 project_scan.md。 */
    enum Solver {
        INTRINSICS(1, "IntrinsicsSolver", "内联函数"),
        BUILTIN(2, "BuiltinSolver", "编译器内置函数"),
        MACROS(3, "MacrosSolver", "宏定义"),
        INLINE_ASM(4, "InlineAsmSolver", "内联汇编"),
        ASM_FILE(5, "AsmFileSolver", "汇编文件"),
        MAKEFILE(0, "MakefileSolver", "Makefile 架构编译参数"),
        CMAKE(10, "CMakeSolver", "CMake 构建系统架构分支"),
        /** 技能协议枚举未覆盖头文件引用；取未占用值，技能侧按 solver_name 回退源码特征分流。 */
        HEADERS(14, "HeadersSolver", "架构头文件引用");

        final int type;
        final String solverName;
        final String description;

        Solver(int type, String solverName, String description) {
            this.type = type;
            this.solverName = solverName;
            this.description = description;
        }
    }

    @JsonPropertyOrder({"target", "suggestion_class_count", "missing_class_count", "suggestion_class",
            "missing_class", "schemaVersion", "scanRunId"})
    public record Projection(
            String target,
            @JsonProperty("suggestion_class_count") int suggestionClassCount,
            @JsonProperty("missing_class_count") int missingClassCount,
            @JsonProperty("suggestion_class") List<Item> suggestionClass,
            @JsonProperty("missing_class") List<Map<String, Object>> missingClass,
            String schemaVersion,
            UUID scanRunId) {

        public Projection {
            suggestionClass = List.copyOf(suggestionClass);
            missingClass = List.copyOf(missingClass);
        }

        /** 换一个源码根重新定位条目的绝对路径；原投影保持不变。 */
        public Projection withSourceRoot(Path root) {
            String sourceRoot = normalizeRoot(root);
            List<Item> relocated = suggestionClass.stream().map(item -> item.withSourceRoot(sourceRoot)).toList();
            return new Projection(sourceRoot, suggestionClassCount, missingClassCount, relocated, missingClass,
                    schemaVersion, scanRunId);
        }
    }

    @JsonPropertyOrder({"class_type", "file_name", "file_path", "solver_name", "solver_type",
            "solver_description", "start_line", "end_line", "migration_suggestion", "status", "marking",
            "id", "sourceFindingIds", "symbol", "matched_text", "severity", "blocking", "uncertain",
            "description", "evidence"})
    public record Item(
            @JsonProperty("class_type") String classType,
            @JsonProperty("file_name") String fileName,
            @JsonProperty("file_path") String filePath,
            @JsonProperty("solver_name") String solverName,
            @JsonProperty("solver_type") int solverType,
            @JsonProperty("solver_description") String solverDescription,
            @JsonProperty("start_line") Integer startLine,
            @JsonProperty("end_line") Integer endLine,
            @JsonProperty("migration_suggestion") String migrationSuggestion,
            String status,
            String marking,
            String id,
            List<UUID> sourceFindingIds,
            String symbol,
            @JsonProperty("matched_text") String matchedText,
            String severity,
            Boolean blocking,
            boolean uncertain,
            String description,
            List<EvidenceRef> evidence,
            /** 相对源码根的文件路径（投影内部字段，不写入条目文件）。 */
            @JsonIgnore String file,
            /** 条目文件里 file_path 的根，默认容器内 /app/source。 */
            @JsonIgnore String sourceRoot) {

        public Item {
            // 行区间缺失或倒置时退化为单行，保证 start_line <= end_line
            if (endLine == null || (startLine != null && endLine < startLine)) {
                endLine = startLine;
            }
            sourceFindingIds = sourceFindingIds == null ? List.of() : List.copyOf(sourceFindingIds);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            sourceRoot = normalizeRoot(sourceRoot);
            if (filePath == null && file != null) {
                fileName = AiMigrationProjector.fileNameOf(file);
                filePath = AiMigrationProjector.filePath(sourceRoot, file);
            }
        }

        public Item withSourceRoot(String root) {
            String normalized = normalizeRoot(root);
            return new Item(classType, fileName, file == null ? null : AiMigrationProjector.filePath(normalized, file), solverName,
                    solverType, solverDescription, startLine, endLine, migrationSuggestion, status, marking, id,
                    sourceFindingIds, symbol, matchedText, severity, blocking, uncertain, description, evidence,
                    file, normalized);
        }
    }

    public record EvidenceRef(String file, Integer line, String symbol) {
    }

    public static Projection project(UUID scanRunId, List<FindingView> findings) {
        return project(scanRunId, findings, null);
    }

    public static Projection project(UUID scanRunId, List<FindingView> findings, Path sourceRoot) {
        Map<String, Item> items = new LinkedHashMap<>();
        Map<String, List<UUID>> findingIds = new LinkedHashMap<>();
        for (FindingView finding : findings) {
            if (skipped(finding)) {
                continue;
            }
            String itemId = itemId(finding);
            findingIds.computeIfAbsent(itemId, key -> new ArrayList<>()).add(finding.id());
            items.computeIfAbsent(itemId, key -> toItem(finding, sourceRoot));
        }
        List<Item> merged = items.entrySet().stream()
                .map(entry -> withFindingIds(entry.getValue(), findingIds.get(entry.getKey())))
                .toList();
        return new Projection(normalizeRoot(sourceRoot), merged.size(), 0, merged, List.of(), SCHEMA_VERSION,
                scanRunId);
    }

    private static boolean skipped(FindingView finding) {
        Map<String, Object> evidence = finding.evidence() == null ? Map.of() : finding.evidence();
        if ("test_asset".equals(textValue(evidence.get("factKind")))) {
            return true;
        }
        if (Boolean.FALSE.equals(boolValue(evidence.get("actionable")))) {
            // 仅当属于 x86 专有引用且确未参与编译时放行，标记为 uncertain 交给技能做必要性分析
            return !uncertain(finding);
        }
        if ("inactive".equals(textValue(evidence.get("conclusionStatus")))) {
            return true;
        }
        return "info".equals(finding.severity()) && !Boolean.TRUE.equals(boolValue(evidence.get("actionable")));
    }

    static boolean uncertain(FindingView finding) {
        Map<String, Object> evidence = finding.evidence() == null ? Map.of() : finding.evidence();
        Object findingType = evidence.get("findingType");
        if (findingType == null || !UNCERTAIN_FINDING_TYPES.contains(findingType.toString())) {
            return false;
        }
        String reachability = textValue(evidence.get("archReachability"));
        if (!"unknown".equals(reachability) && !"not_selected".equals(reachability)) {
            return false;
        }
        String conclusionStatus = textValue(evidence.get("conclusionStatus"));
        if (conclusionStatus != null && !Set.of("unknown", "partial", "ambiguous").contains(conclusionStatus)) {
            return false;
        }
        return !"info".equals(finding.severity());
    }

    private static Item toItem(FindingView finding, Path sourceRoot) {
        Map<String, Object> evidence = finding.evidence() == null ? Map.of() : finding.evidence();
        Solver solver = solver(finding);
        String file = finding.filePath();
        return new Item(
                CLASS_TYPE_SUGGESTION,
                fileNameOf(file),
                file == null ? null : filePath(normalizeRoot(sourceRoot), file),
                solver.solverName,
                solver.type,
                solver.description,
                finding.lineStart(),
                endLine(finding),
                finding.suggestion(),
                STATUS_TODO,
                "",
                itemId(finding),
                List.of(finding.id()),
                textValue(evidence.get("symbol")) == null ? finding.symbol() : textValue(evidence.get("symbol")),
                textValue(evidence.get("matchedText")),
                finding.severity(),
                boolValue(evidence.get("blocking")),
                uncertain(finding),
                description(finding),
                List.of(new EvidenceRef(file, finding.lineStart(), finding.symbol())),
                file,
                normalizeRoot(sourceRoot));
    }

    private static Item withFindingIds(Item item, List<UUID> ids) {
        return new Item(item.classType(), item.fileName(), item.filePath(), item.solverName(), item.solverType(),
                item.solverDescription(), item.startLine(), item.endLine(), item.migrationSuggestion(),
                item.status(), item.marking(), item.id(), List.copyOf(ids), item.symbol(), item.matchedText(),
                item.severity(), item.blocking(), item.uncertain(), item.description(), item.evidence(),
                item.file(), item.sourceRoot());
    }

    /** 结束行：缺失或早于起始行时退化为起始行（协议要求 end_line 含在区间内且不小于 start_line）。 */
    static Integer endLine(FindingView finding) {
        Integer start = finding.lineStart();
        Integer end = finding.lineEnd();
        if (start == null) {
            return end;
        }
        return end == null || end < start ? start : end;
    }

    /** 协议要求文件名；Windows 分隔符按路径语义归一。 */
    static String fileNameOf(String file) {
        if (file == null || file.isBlank()) {
            return null;
        }
        String normalized = file.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = normalized.substring(slash + 1);
        return name.isBlank() ? null : name;
    }

    /** 拼出条目文件里的绝对路径；已带根或以 / 开头的输入不重复拼接。 */
    static String filePath(String sourceRoot, String file) {
        String normalized = file.replace('\\', '/');
        if (normalized.equals(sourceRoot) || normalized.startsWith(sourceRoot + "/")) {
            return normalized;
        }
        return sourceRoot + (normalized.startsWith("/") ? normalized : "/" + normalized);
    }

    static String normalizeRoot(Path root) {
        return root == null ? DEFAULT_SOURCE_ROOT : normalizeRoot(root.toString());
    }

    static String normalizeRoot(String root) {
        if (root == null || root.isBlank()) {
            return DEFAULT_SOURCE_ROOT;
        }
        String normalized = root.replace('\\', '/');
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /** fact.kind → solver；注意不得使用 findings.category（conclusion.findingType，语义不同）。 */
    static Solver solver(FindingView finding) {
        Map<String, Object> evidence = finding.evidence() == null ? Map.of() : finding.evidence();
        String factKind = textValue(evidence.get("factKind"));
        String file = finding.filePath() == null ? "" : finding.filePath();
        String lower = file.toLowerCase();
        return switch (factKind == null ? "" : factKind) {
            case "intrinsic_call", "vector_type" -> Solver.INTRINSICS;
            case "macro_invocation", "macro_replacement" -> Solver.MACROS;
            case "architecture_probe" -> lower.endsWith(".s") || lower.endsWith(".asm") ? Solver.ASM_FILE
                    : Solver.INLINE_ASM;
            case "compiler_flag", "build_argument" -> isCmakeFile(file) ? Solver.CMAKE : Solver.MAKEFILE;
            case "header_include" -> Solver.HEADERS;
            default -> Solver.INLINE_ASM;
        };
    }

    /** solver 名称（技能协议字符串形态）。 */
    static String solverName(FindingView finding) {
        return solver(finding).solverName;
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
