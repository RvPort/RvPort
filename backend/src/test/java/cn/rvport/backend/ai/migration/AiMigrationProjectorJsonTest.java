package cn.rvport.backend.ai.migration;

import static org.assertj.core.api.Assertions.assertThat;

import cn.rvport.backend.kb.FindingView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 投影结果序列化后的 JSON 形状：必须符合 riscv-migrate 技能协议，同时保留平台专有字段。
 *
 * <p>这些断言针对的是 {@code input/scan_result.json} 的实际内容。只断言 Java 访问器不够——
 * 协议字段全部是派生 getter，只有在序列化结果里才能确认字段名、取值类型和"没有多余字段"。
 */
class AiMigrationProjectorJsonTest {

    private static final UUID SCAN_RUN = UUID.randomUUID();
    /** 与运行时写出条目文件时使用的配置一致（AiMigrationAgentRuntime.run）。 */
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void 顶层结构符合技能协议() {
        JsonNode root = mapper.valueToTree(AiMigrationProjector.project(SCAN_RUN, List.of(
                actionable("a", "vector_type", "src/a.c", 3, 3),
                actionable("b", "macro_invocation", "include/b.h", 7, 9))));

        assertThat(root.get("target").asText()).isEqualTo("/app/source");
        assertThat(root.get("suggestion_class_count").asInt()).isEqualTo(2);
        assertThat(root.get("missing_class_count").asInt()).isZero();
        assertThat(root.get("suggestion_class")).hasSize(2);
        assertThat(root.get("missing_class")).isEmpty();
        // 平台专有的顶层字段保留
        assertThat(root.get("schemaVersion").asText()).isEqualTo("ai-migration.v1");
        assertThat(root.get("scanRunId").asText()).isEqualTo(SCAN_RUN.toString());
        // 原来的条目数组不再以 items 之名出现
        assertThat(root.has("items")).isFalse();
    }

    @Test
    void 条目含全部协议字段且取值类型正确() {
        JsonNode entry = mapper.valueToTree(AiMigrationProjector.project(SCAN_RUN, List.of(
                        actionable("a", "vector_type", "src/kern/simd.c", 12, 15))))
                .get("suggestion_class").get(0);

        assertThat(entry.get("class_type").asText()).isEqualTo("suggestion_class");
        assertThat(entry.get("file_name").asText()).isEqualTo("simd.c");
        assertThat(entry.get("file_path").asText()).isEqualTo("/app/source/src/kern/simd.c");
        assertThat(entry.get("solver_type").isIntegralNumber()).isTrue();
        assertThat(entry.get("solver_type").asInt()).isEqualTo(1);
        assertThat(entry.get("solver_name").asText()).isEqualTo("IntrinsicsSolver");
        assertThat(entry.get("solver_description").asText()).isEqualTo("内联函数");
        assertThat(entry.get("start_line").asInt()).isEqualTo(12);
        assertThat(entry.get("end_line").asInt()).isEqualTo(15);
        assertThat(entry.get("migration_suggestion").asText()).isEqualTo("建议");
        assertThat(entry.get("status").asText()).isEqualTo("TODO");
        assertThat(entry.get("marking").asText()).isEmpty();
    }

    @Test
    void 平台专有字段作为附加字段保留() {
        Map<String, Object> evidence = new HashMap<>();
        evidence.put("scannerFindingId", "finding:xyz");
        evidence.put("factKind", "vector_type");
        // 不可行动的条目需要属于架构不确定白名单才会被放行，因此带上 findingType
        evidence.put("findingType", "x86_vector_type_reference");
        evidence.put("symbol", "_mm256_add_epi32");
        evidence.put("matchedText", "_mm256_add_epi32");
        evidence.put("actionable", false);
        evidence.put("archReachability", "unknown");
        evidence.put("conclusionStatus", "unknown");
        FindingView finding = new FindingView(
                UUID.randomUUID(), UUID.randomUUID(), SCAN_RUN, UUID.randomUUID(),
                "rule", "category", "high", "标题", "原因", "src/a.c", 4, 4, "_mm256_add_epi32",
                evidence, "建议", "fp", "DETECTED", null);

        JsonNode entry = mapper.valueToTree(AiMigrationProjector.project(SCAN_RUN, List.of(finding)))
                .get("suggestion_class").get(0);

        // id 是后端核对结论的依据，技能协议里没有这个字段，必须作为附加字段带上
        assertThat(entry.get("id").asText()).isEqualTo("finding:xyz");
        assertThat(entry.get("sourceFindingIds")).hasSize(1);
        assertThat(entry.get("symbol").asText()).isEqualTo("_mm256_add_epi32");
        assertThat(entry.get("matched_text").asText()).isEqualTo("_mm256_add_epi32");
        assertThat(entry.get("severity").asText()).isEqualTo("high");
        assertThat(entry.get("uncertain").asBoolean()).isTrue();
        assertThat(entry.get("description").asText()).isEqualTo("标题：原因");
        assertThat(entry.get("evidence")).hasSize(1);
    }

    @Test
    void 已由协议字段表达的原字段不再重复出现() {
        JsonNode entry = mapper.valueToTree(AiMigrationProjector.project(SCAN_RUN, List.of(
                actionable("a", "macro_invocation", "src/a.c", 1, 2)))).get("suggestion_class").get(0);

        // 这些信息已经由 file_path / start_line+end_line / migration_suggestion 表达，
        // 再以原名出现会让条目文件里有两套字段名。
        assertThat(entry.has("file")).isFalse();
        assertThat(entry.has("line")).isFalse();
        assertThat(entry.has("endLine")).isFalse();
        assertThat(entry.has("suggestion")).isFalse();
    }

    @Test
    void 协议字段齐全() {
        JsonNode entry = mapper.valueToTree(AiMigrationProjector.project(SCAN_RUN, List.of(
                actionable("a", "macro_invocation", "src/a.c", 1, 1)))).get("suggestion_class").get(0);

        for (String field : List.of("class_type", "file_name", "file_path", "solver_name", "solver_type",
                "solver_description", "start_line", "end_line", "migration_suggestion", "status", "marking")) {
            assertThat(entry.has(field)).as("协议字段 %s", field).isTrue();
        }
    }

    @Test
    void 行区间缺失或倒置时退化为单行() {
        var missing = AiMigrationProjector.project(SCAN_RUN, List.of(actionable("m", "macro_invocation", "src/a.c", 5, null)))
                .suggestionClass().get(0);
        var inverted = AiMigrationProjector.project(SCAN_RUN, List.of(actionable("i", "macro_invocation", "src/a.c", 9, 4)))
                .suggestionClass().get(0);
        var normal = AiMigrationProjector.project(SCAN_RUN, List.of(actionable("n", "macro_invocation", "src/a.c", 3, 8)))
                .suggestionClass().get(0);

        assertThat(missing.endLine()).isEqualTo(5);
        assertThat(inverted.endLine()).isEqualTo(9);
        assertThat(normal.endLine()).isEqualTo(8);
    }

    @Test
    void 文件名为空或路径分隔符异常时不抛错() {
        var noFile = item(null, 1, 1);
        var backslash = item("src\\win\\a.c", 1, 1);
        var leadingSlash = item("/src/a.c", 1, 1);

        assertThat(noFile.fileName()).isNull();
        assertThat(noFile.filePath()).isNull();
        assertThat(backslash.fileName()).isEqualTo("a.c");
        assertThat(backslash.filePath()).isEqualTo("/app/source/src/win/a.c");
        // 已是绝对路径的输入不会被拼成 /app/source//src/a.c
        assertThat(leadingSlash.filePath()).isEqualTo("/app/source/src/a.c");
    }

    @Test
    void 计数与条目数组一致且去重后仍一致() {
        List<FindingView> findings = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            findings.add(actionable("n" + i, "vector_type", "src/f" + i + ".c", i + 1, i + 1));
        }
        // 同一 scannerFindingId 的第二条会被合并进第一条
        findings.add(actionable("n0", "vector_type", "src/f0.c", 1, 1));
        // 不可行动且不属于架构不确定类的条目会被过滤
        findings.add(nonActionable("skip", "macro_invocation", "src/skip.c"));

        JsonNode root = mapper.valueToTree(AiMigrationProjector.project(SCAN_RUN, findings));

        assertThat(root.get("suggestion_class")).hasSize(4);
        assertThat(root.get("suggestion_class_count").asInt()).isEqualTo(4);
        assertThat(root.get("suggestion_class").get(0).get("sourceFindingIds")).hasSize(2);
    }

    /** 最小条目：只关心文件与行区间，其余字段留空。 */
    private AiMigrationProjector.Item item(String file, int startLine, Integer endLine) {
        return new AiMigrationProjector.Item("suggestion_class", null, null, "IntrinsicsSolver", 1, "内联函数",
                startLine, endLine, null, "TODO", "", "i", List.of(), null, null, null, null, false, null,
                List.of(), file, null);
    }

    @Test
    void 写条目文件前可把路径定位到实际的源码根() {
        // 两种执行通道看到的工作区位置不同：容器内是 /app/source，子进程模式是实际的工作目录
        var projection = AiMigrationProjector.project(SCAN_RUN, List.of(
                actionable("a", "vector_type", "src/a.c", 1, 1)));

        var relocated = projection.withSourceRoot(java.nio.file.Path.of("/tmp/task-42/source/"));

        assertThat(relocated.target()).isEqualTo("/tmp/task-42/source");
        assertThat(relocated.suggestionClass().get(0).filePath()).isEqualTo("/tmp/task-42/source/src/a.c");
        // 原投影不受影响；其余字段保持不变
        assertThat(projection.target()).isEqualTo("/app/source");
        assertThat(projection.suggestionClass().get(0).filePath()).isEqualTo("/app/source/src/a.c");
        assertThat(relocated.suggestionClass().get(0).id()).isEqualTo("finding:a");
        assertThat(relocated.suggestionClass().get(0).file()).isEqualTo("src/a.c");
        assertThat(relocated.suggestionClassCount()).isEqualTo(1);
    }

    @Test
    void 源码根为空的条目回落到容器默认值() {
        var byDefault = new AiMigrationProjector.Item("suggestion_class", null, null, "IntrinsicsSolver", 1, "内联函数",
                1, 1, null, "TODO", "", "i", List.of(), null, null, null, null, false, null,
                List.of(), "src/a.c", "  ");

        assertThat(byDefault.sourceRoot()).isEqualTo("/app/source");
        assertThat(byDefault.filePath()).isEqualTo("/app/source/src/a.c");
    }

    private FindingView actionable(String suffix, String factKind, String file, int startLine, Integer endLine) {
        Map<String, Object> evidence = new HashMap<>();
        evidence.put("scannerFindingId", "finding:" + suffix);
        evidence.put("factKind", factKind);
        evidence.put("actionable", true);
        return finding(suffix, evidence, file, startLine, endLine);
    }

    private FindingView nonActionable(String suffix, String factKind, String file) {
        Map<String, Object> evidence = new HashMap<>();
        evidence.put("scannerFindingId", "finding:" + suffix);
        evidence.put("factKind", factKind);
        evidence.put("actionable", false);
        return finding(suffix, evidence, file, 1, 1);
    }

    private FindingView finding(String suffix, Map<String, Object> evidence, String file, int startLine,
            Integer endLine) {
        return new FindingView(
                UUID.nameUUIDFromBytes((SCAN_RUN + ":" + suffix).getBytes()),
                UUID.randomUUID(),
                SCAN_RUN,
                UUID.randomUUID(),
                "rule-" + suffix,
                "category-" + suffix,
                "high",
                "标题",
                "原因",
                file,
                startLine,
                endLine,
                "sym-" + suffix,
                evidence,
                "建议",
                "fp-" + suffix,
                "DETECTED",
                null);
    }
}
