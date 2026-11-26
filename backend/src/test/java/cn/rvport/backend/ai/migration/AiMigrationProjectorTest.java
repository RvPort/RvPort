package cn.rvport.backend.ai.migration;

import static org.assertj.core.api.Assertions.assertThat;

import cn.rvport.backend.kb.FindingView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AiMigrationProjectorTest {

    private static final UUID SCAN_RUN = UUID.randomUUID();

    @Test
    void 投影基础映射与条目身份双轨() {
        FindingView finding = finding("f1", evidence(Map.of(
                "scannerFindingId", "finding:abc",
                "factKind", "intrinsic_call",
                "symbol", "_mm256_add_epi32",
                "matchedText", "_mm256_add_epi32",
                "actionable", true,
                "blocking", true)), "src/sse.c", 56, "high");

        AiMigrationProjector.Projection projection = AiMigrationProjector.project(SCAN_RUN, List.of(finding));

        assertThat(projection.schemaVersion()).isEqualTo("ai-migration.v1");
        assertThat(projection.scanRunId()).isEqualTo(SCAN_RUN);
        assertThat(projection.suggestionClass()).hasSize(1);
        AiMigrationProjector.Item item = projection.suggestionClass().get(0);
        assertThat(item.id()).isEqualTo("finding:abc");
        assertThat(item.sourceFindingIds()).containsExactly(finding.id());
        assertThat(item.id()).isNotEqualTo(finding.id().toString());
        assertThat(item.status()).isEqualTo("TODO");
        assertThat(item.solverType()).isEqualTo(1);
        assertThat(item.file()).isEqualTo("src/sse.c");
        assertThat(item.startLine()).isEqualTo(56);
        assertThat(item.symbol()).isEqualTo("_mm256_add_epi32");
        assertThat(item.matchedText()).isEqualTo("_mm256_add_epi32");
        assertThat(item.severity()).isEqualTo("high");
        assertThat(item.blocking()).isTrue();
        assertThat(item.uncertain()).isFalse();
        assertThat(item.description()).isEqualTo("标题：原因");
        assertThat(item.migrationSuggestion()).isEqualTo("建议");
        assertThat(item.evidence()).containsExactly(new AiMigrationProjector.EvidenceRef("src/sse.c", 56, "sym-f1"));
    }

    @Test
    void 白名单判定的分支覆盖() {
        assertThat(AiMigrationProjector.UNCERTAIN_FINDING_TYPES).contains("x86_intrinsic_reference",
                "x86_builtin_reference", "x86_vector_type_reference", "x86_header_reference",
                "x86_assembly_instruction", "x86_register_reference", "x86_cpu_platform_api",
                "x86_compiler_flag", "x86_platform_macro_reference", "x86_target_feature",
                "x86_architecture_alias");
        assertThat(AiMigrationProjector.UNCERTAIN_FINDING_TYPES).doesNotContain("x86_comment_hint",
                "hint", "some_other_type", "inline_asm_candidate");
    }

    @Test
    void 架构相关不确定类放行并标注uncertain() {
        FindingView uncertainIntrinsic = finding("u1", evidence(Map.of(
                "scannerFindingId", "finding:ui",
                "factKind", "intrinsic_call",
                "findingType", "x86_intrinsic_reference",
                "actionable", false,
                "archReachability", "unknown",
                "conclusionStatus", "unknown")), "src/u.c", 1, "high");
        FindingView targetAttr = finding("u2", evidence(Map.of(
                "scannerFindingId", "finding:ta",
                "factKind", "architecture_probe",
                "findingType", "x86_target_feature",
                "actionable", false,
                "archReachability", "not_selected")), "src/t.c", 2, "medium");

        AiMigrationProjector.Projection projection = AiMigrationProjector.project(SCAN_RUN, List.of(uncertainIntrinsic, targetAttr));

        assertThat(projection.suggestionClass()).hasSize(2);
        assertThat(projection.suggestionClass()).allSatisfy(item -> assertThat(item.uncertain()).isTrue());
    }

    @Test
    void 注释提示与非架构类仍被过滤() {
        FindingView commentHint = finding("h1", evidence(Map.of(
                "scannerFindingId", "finding:ch",
                "factKind", "macro_invocation",
                "findingType", "x86_comment_hint",
                "actionable", false)), "src/c.c", 1, "low");
        FindingView unknownKind = finding("h2", evidence(Map.of(
                "scannerFindingId", "finding:uk",
                "factKind", "macro_invocation",
                "actionable", false)), "src/k.c", 1, "low");

        AiMigrationProjector.Projection projection = AiMigrationProjector.project(SCAN_RUN, List.of(commentHint, unknownKind));

        assertThat(projection.suggestionClass()).isEmpty();
    }

    @Test
    void 过滤不可迁移条目() {
        FindingView notActionable = finding("f1", evidence(Map.of(
                "scannerFindingId", "finding:na",
                "factKind", "intrinsic_call",
                "actionable", false)), "src/a.c", 1, "high");
        FindingView inactive = finding("f2", evidence(Map.of(
                "scannerFindingId", "finding:in",
                "factKind", "macro_invocation",
                "actionable", true,
                "conclusionStatus", "inactive")), "src/b.h", 2, "medium");
        FindingView hint = finding("f3", evidence(Map.of(
                "scannerFindingId", "finding:hint",
                "factKind", "macro_invocation")), "src/c.c", 3, "info");
        FindingView testAsset = finding("f4", evidence(Map.of(
                "scannerFindingId", "finding:ta",
                "factKind", "test_asset",
                "actionable", true)), "tests/d.py", 4, "low");

        AiMigrationProjector.Projection projection = AiMigrationProjector.project(SCAN_RUN,
                List.of(notActionable, inactive, hint, testAsset));

        assertThat(projection.suggestionClass()).isEmpty();
    }

    @Test
    void 同scannerFindingId去重并合并sourceFindingIds() {
        FindingView first = finding("f1", evidence(Map.of(
                "scannerFindingId", "finding:dup",
                "factKind", "macro_invocation",
                "actionable", true)), "src/x.h", 10, "medium");
        FindingView second = finding("f2", evidence(Map.of(
                "scannerFindingId", "finding:dup",
                "factKind", "macro_invocation",
                "actionable", true)), "src/x.h", 10, "medium");

        AiMigrationProjector.Projection projection = AiMigrationProjector.project(SCAN_RUN, List.of(first, second));

        assertThat(projection.suggestionClass()).hasSize(1);
        assertThat(projection.suggestionClass().get(0).sourceFindingIds())
                .containsExactlyInAnyOrder(first.id(), second.id());
    }

    @Test
    void solverType按技能协议输出整数() {
        // 1 内联函数 / 3 宏 / 4 内联汇编 / 5 汇编文件 / 0 Makefile / 10 构建脚本
        assertThat(solverFor("vector_type", "src/v.c", "high")).isEqualTo(1);
        assertThat(solverFor("intrinsic_call", "src/i.c", "high")).isEqualTo(1);
        assertThat(solverFor("macro_replacement", "src/m.h", "medium")).isEqualTo(3);
        // 头文件包含只动预处理行，归宏类（技能协议没有独立的头文件类型）
        assertThat(solverFor("header_include", "src/h.h", "low")).isEqualTo(14);
        assertThat(solverFor("compiler_flag", "CMakeLists.txt", "medium")).isEqualTo(10);
        assertThat(solverFor("compiler_flag", "cmake/toolchain.cmake", "medium")).isEqualTo(10);
        assertThat(solverFor("build_argument", "Makefile", "medium")).isEqualTo(0);
        assertThat(solverFor("build_argument", "GNUmakefile", "medium")).isEqualTo(0);
        // 汇编文件后缀（含大写 .S）
        assertThat(solverFor("architecture_probe", "src/kern.S", "high", "x86_assembly_instruction")).isEqualTo(5);
        assertThat(solverFor("architecture_probe", "src/kern.asm", "high", "x86_assembly_instruction")).isEqualTo(5);
        // 未识别的类型不套用最重的汇编流程
        assertThat(solverFor("totally_unknown_kind", "src/u.c", "high")).isEqualTo(4);
        assertThat(solverFor(null, "src/u.c", "high")).isEqualTo(4);
    }

    @Test
    void 探测类的汇编文件按后缀分流() {
        assertThat(solverFor("architecture_probe", "src/kern.S", "high")).isEqualTo(5);
        assertThat(solverFor("architecture_probe", "src/kern.asm", "high")).isEqualTo(5);
        // 非汇编后缀的探测类当前归内联汇编（factKind 粒度，findingType 细分待技能协议确认后跟进）
        assertThat(solverFor("architecture_probe", "src/inline.c", "high")).isEqualTo(4);
    }

    @Test
    void solver名称与说明跟整数一致且未知值回落到宏() {
        assertThat(AiMigrationProjector.Solver.values()).anyMatch(e -> e.type == 0 && e.solverName.equals("MakefileSolver"));
        assertThat(AiMigrationProjector.Solver.values()).anyMatch(e -> e.type == 1 && e.solverName.equals("IntrinsicsSolver"));
        assertThat(AiMigrationProjector.Solver.values()).anyMatch(e -> e.type == 3 && e.solverName.equals("MacrosSolver"));
        assertThat(AiMigrationProjector.Solver.values()).anyMatch(e -> e.type == 4 && e.solverName.equals("InlineAsmSolver"));
        assertThat(AiMigrationProjector.Solver.values()).anyMatch(e -> e.type == 5 && e.solverName.equals("AsmFileSolver"));
        assertThat(AiMigrationProjector.Solver.values()).anyMatch(e -> e.type == 10 && e.solverName.equals("CMakeSolver"));
        assertThat(AiMigrationProjector.Solver.values()).anyMatch(e -> e.type == 0 && e.description.equals("Makefile 架构编译参数"));
        assertThat(AiMigrationProjector.Solver.values()).anyMatch(e -> e.type == 1 && e.description.equals("内联函数"));
        assertThat(AiMigrationProjector.Solver.values()).anyMatch(e -> e.type == 3 && e.description.equals("宏定义"));
        assertThat(AiMigrationProjector.Solver.values()).anyMatch(e -> e.type == 4 && e.description.equals("内联汇编"));
        assertThat(AiMigrationProjector.Solver.values()).anyMatch(e -> e.type == 5 && e.description.equals("汇编文件"));
        assertThat(AiMigrationProjector.Solver.values()).anyMatch(e -> e.type == 10 && e.description.equals("CMake 构建系统架构分支"));

    }

    @Test
    void 缺失scannerFindingId时合成确定性id() {
        FindingView finding = finding("f1", evidence(Map.of(
                "factKind", "intrinsic_call",
                "actionable", true)), "src/a.c", 7, "high");

        AiMigrationProjector.Projection first = AiMigrationProjector.project(SCAN_RUN, List.of(finding));
        AiMigrationProjector.Projection second = AiMigrationProjector.project(SCAN_RUN, List.of(finding));

        assertThat(first.suggestionClass()).hasSize(1);
        assertThat(first.suggestionClass().get(0).id()).startsWith("finding:").doesNotContain("|");
        assertThat(first.suggestionClass().get(0).id()).isEqualTo(second.suggestionClass().get(0).id());
    }

    @Test
    void 投影条目数与去重后actionable一致且id不重复() {
        List<FindingView> findings = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            findings.add(finding("f" + i, evidence(Map.of(
                    "scannerFindingId", "finding:n" + i,
                    "factKind", "intrinsic_call",
                    "actionable", true)), "src/f" + i + ".c", i, "high"));
        }
        findings.add(finding("fdup", evidence(Map.of(
                "scannerFindingId", "finding:n0",
                "factKind", "intrinsic_call",
                "actionable", true)), "src/f0.c", 0, "high"));
        findings.add(finding("fskip", evidence(Map.of(
                "scannerFindingId", "finding:skip",
                "factKind", "intrinsic_call",
                "actionable", false)), "src/skip.c", 1, "high"));

        AiMigrationProjector.Projection projection = AiMigrationProjector.project(SCAN_RUN, findings);

        assertThat(projection.suggestionClass()).hasSize(5);
        assertThat(projection.suggestionClass()).allSatisfy(item -> assertThat(item.status()).isEqualTo("TODO"));
        List<String> ids = projection.suggestionClass().stream().map(AiMigrationProjector.Item::id).toList();
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void 边界分支覆盖() {
        Map<String, Object> evidence = new HashMap<>();
        evidence.put("scannerFindingId", "   ");
        evidence.put("factKind", "compiler_flag");
        evidence.put("actionable", true);
        evidence.put("blocking", false);
        FindingView nullDescription = new FindingView(
                UUID.randomUUID(), UUID.randomUUID(), SCAN_RUN, UUID.randomUUID(),
                "r1", "c1", "info", "只有标题", null, null, null, null, null,
                evidence, null, "fp1", "DETECTED", null);

        Map<String, Object> noSymbol = new HashMap<>();
        noSymbol.put("scannerFindingId", "finding:nosym");
        noSymbol.put("factKind", "architecture_probe");
        noSymbol.put("actionable", true);
        FindingView symbolFallback = new FindingView(
                UUID.randomUUID(), UUID.randomUUID(), SCAN_RUN, UUID.randomUUID(),
                "r2", "c2", "low", "t", "d", "src/none.c", 1, 1, "fallback-sym",
                noSymbol, null, "fp2", "DETECTED", null);

        AiMigrationProjector.Projection projection = AiMigrationProjector.project(SCAN_RUN,
                List.of(nullDescription, symbolFallback));

        assertThat(projection.suggestionClass()).hasSize(2);
        var first = projection.suggestionClass().stream()
                .filter(i -> "info".equals(i.severity())).findFirst().orElseThrow();
        assertThat(first.solverType()).isEqualTo(0);
        assertThat(first.description()).isEqualTo("只有标题");
        assertThat(first.migrationSuggestion()).isNull();
        assertThat(first.blocking()).isFalse();
        assertThat(first.symbol()).isNull();
        assertThat(first.id()).startsWith("finding:").isNotEqualTo("   ");
        var second = projection.suggestionClass().stream()
                .filter(i -> i.solverType() == 4).findFirst().orElseThrow();
        assertThat(second.symbol()).isEqualTo("fallback-sym");
    }

    @Test
    void null证据与空白描述分支() {
        FindingView nullEvidence = new FindingView(
                UUID.randomUUID(), UUID.randomUUID(), SCAN_RUN, UUID.randomUUID(),
                "r3", "c3", "high", "无证据", "  ", "src/x.c", 9, 9, "sym-x",
                null, null, "fp3", "DETECTED", null);
        Map<String, Object> oddTypes = new HashMap<>();
        oddTypes.put("scannerFindingId", "finding:odd");
        oddTypes.put("factKind", "header_include");
        oddTypes.put("findingType", "x86_header_reference");
        oddTypes.put("actionable", true);
        oddTypes.put("blocking", 7);
        FindingView odd = new FindingView(
                UUID.randomUUID(), UUID.randomUUID(), SCAN_RUN, UUID.randomUUID(),
                "r4", "c4", "medium", "t4", "d4", "src/y.h", 4, 4, "sym-y",
                oddTypes, null, "fp4", "DETECTED", null);

        AiMigrationProjector.Projection projection = AiMigrationProjector.project(SCAN_RUN, List.of(nullEvidence, odd));

        // null 证据不触发任何过滤分支（actionable 缺失、非 info 级），两个条目都保留
        assertThat(projection.suggestionClass()).hasSize(2);
        var only = projection.suggestionClass().stream()
                .filter(i -> "finding:odd".equals(i.id())).findFirst().orElseThrow();
        assertThat(only.solverType()).isEqualTo(14);
        assertThat(only.blocking()).isNull();
        assertThat(only.description()).isEqualTo("t4：d4");
    }

    private int solverFor(String factKind, String file, String severity) {
        return solverFor(factKind, file, severity, null);
    }

    private int solverFor(String factKind, String file, String severity, String findingType) {
        Map<String, Object> evidence = new HashMap<>();
        if (factKind != null) {
            evidence.put("factKind", factKind);
        }
        if (findingType != null) {
            evidence.put("findingType", findingType);
        }
        evidence.put("actionable", true);
        return AiMigrationProjector.solver(finding("fx", evidence(evidence), file, 1, severity)).type;
    }

    private Map<String, Object> evidence(Map<String, Object> values) {
        return values;
    }

    private FindingView finding(String suffix, Map<String, Object> evidence, String file, int line, String severity) {
        return new FindingView(
                UUID.nameUUIDFromBytes((SCAN_RUN + ":" + suffix).getBytes()),
                UUID.randomUUID(),
                SCAN_RUN,
                UUID.randomUUID(),
                "rule-" + suffix,
                "category-" + suffix,
                severity,
                "标题",
                "原因",
                file,
                line,
                line,
                "sym-" + suffix,
                evidence,
                "建议",
                "fp-" + suffix,
                "DETECTED",
                null);
    }
}
