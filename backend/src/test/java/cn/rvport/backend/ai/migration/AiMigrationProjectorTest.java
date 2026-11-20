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
        assertThat(projection.items()).hasSize(1);
        AiMigrationProjector.Item item = projection.items().get(0);
        assertThat(item.id()).isEqualTo("finding:abc");
        assertThat(item.sourceFindingIds()).containsExactly(finding.id());
        assertThat(item.id()).isNotEqualTo(finding.id().toString());
        assertThat(item.status()).isEqualTo("TODO");
        assertThat(item.solverType()).isEqualTo("intrinsics");
        assertThat(item.file()).isEqualTo("src/sse.c");
        assertThat(item.line()).isEqualTo(56);
        assertThat(item.symbol()).isEqualTo("_mm256_add_epi32");
        assertThat(item.matchedText()).isEqualTo("_mm256_add_epi32");
        assertThat(item.severity()).isEqualTo("high");
        assertThat(item.blocking()).isTrue();
        assertThat(item.description()).isEqualTo("标题：原因");
        assertThat(item.suggestion()).isEqualTo("建议");
        assertThat(item.evidence()).containsExactly(new AiMigrationProjector.EvidenceRef("src/sse.c", 56, "sym-f1"));
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

        assertThat(projection.items()).isEmpty();
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

        assertThat(projection.items()).hasSize(1);
        assertThat(projection.items().get(0).sourceFindingIds())
                .containsExactlyInAnyOrder(first.id(), second.id());
    }

    @Test
    void solverType映射含汇编文件与构建文件分流() {
        assertThat(solverFor("architecture_probe", "src/kern.S", "high")).isEqualTo("asm_file");
        assertThat(solverFor("architecture_probe", "src/kern.asm", "high")).isEqualTo("asm_file");
        assertThat(solverFor("architecture_probe", "src/inline.c", "high")).isEqualTo("inline_asm");
        assertThat(solverFor("vector_type", "src/v.c", "high")).isEqualTo("intrinsics");
        assertThat(solverFor("macro_replacement", "src/m.h", "medium")).isEqualTo("macros");
        assertThat(solverFor("compiler_flag", "CMakeLists.txt", "medium")).isEqualTo("build_cmake");
        assertThat(solverFor("compiler_flag", "cmake/toolchain.cmake", "medium")).isEqualTo("build_cmake");
        assertThat(solverFor("build_argument", "Makefile", "medium")).isEqualTo("build_makefile");
        assertThat(solverFor("build_argument", "GNUmakefile", "medium")).isEqualTo("build_makefile");
        assertThat(solverFor("header_include", "src/h.h", "low")).isEqualTo("headers");
        assertThat(solverFor("totally_unknown_kind", "src/u.c", "high")).isEqualTo("inline_asm");
        assertThat(solverFor(null, "src/u.c", "high")).isEqualTo("inline_asm");
    }

    @Test
    void 缺失scannerFindingId时合成确定性id() {
        FindingView finding = finding("f1", evidence(Map.of(
                "factKind", "intrinsic_call",
                "actionable", true)), "src/a.c", 7, "high");

        AiMigrationProjector.Projection first = AiMigrationProjector.project(SCAN_RUN, List.of(finding));
        AiMigrationProjector.Projection second = AiMigrationProjector.project(SCAN_RUN, List.of(finding));

        assertThat(first.items()).hasSize(1);
        assertThat(first.items().get(0).id()).startsWith("finding:").doesNotContain("|");
        assertThat(first.items().get(0).id()).isEqualTo(second.items().get(0).id());
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

        assertThat(projection.items()).hasSize(5);
        assertThat(projection.items()).allSatisfy(item -> assertThat(item.status()).isEqualTo("TODO"));
        List<String> ids = projection.items().stream().map(AiMigrationProjector.Item::id).toList();
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void 边界分支覆盖() {
        Map<String, Object> evidence = new HashMap<>();
        evidence.put("scannerFindingId", "   ");
        evidence.put("factKind", "compiler_flag");
        evidence.put("actionable", "true");
        evidence.put("blocking", "false");
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

        assertThat(projection.items()).hasSize(2);
        var first = projection.items().stream()
                .filter(i -> "info".equals(i.severity())).findFirst().orElseThrow();
        assertThat(first.solverType()).isEqualTo("build_makefile");
        assertThat(first.description()).isEqualTo("只有标题");
        assertThat(first.suggestion()).isNull();
        assertThat(first.blocking()).isFalse();
        assertThat(first.symbol()).isNull();
        assertThat(first.id()).startsWith("finding:").isNotEqualTo("   ");
        var second = projection.items().stream()
                .filter(i -> i.solverType().equals("inline_asm")).findFirst().orElseThrow();
        assertThat(second.symbol()).isEqualTo("fallback-sym");
    }

    @Test
    void null证据与空白描述分支() {
        FindingView nullEvidence = new FindingView(
                UUID.randomUUID(), UUID.randomUUID(), SCAN_RUN, UUID.randomUUID(),
                "r3", "c3", "high", "无证据", "  ", "src/x.c", 9, 9, "sym-x",
                null, "sug", "fp3", "DETECTED", null);
        Map<String, Object> oddTypes = new HashMap<>();
        oddTypes.put("scannerFindingId", "finding:odd");
        oddTypes.put("factKind", "header_include");
        oddTypes.put("actionable", true);
        oddTypes.put("blocking", 7);
        FindingView odd = new FindingView(
                UUID.randomUUID(), UUID.randomUUID(), SCAN_RUN, UUID.randomUUID(),
                "r4", "c4", "medium", "t4", "d4", "src/y.h", 4, 4, "sym-y",
                oddTypes, null, "fp4", "DETECTED", null);

        AiMigrationProjector.Projection projection = AiMigrationProjector.project(SCAN_RUN, List.of(nullEvidence, odd));

        assertThat(projection.items()).hasSize(2);
        var first = projection.items().stream().filter(i -> "sym-x".equals(i.symbol())).findFirst().orElseThrow();
        assertThat(first.solverType()).isEqualTo("inline_asm");
        assertThat(first.description()).isEqualTo("无证据");
        assertThat(first.evidence()).containsExactly(new AiMigrationProjector.EvidenceRef("src/x.c", 9, "sym-x"));
        var second = projection.items().stream().filter(i -> "sym-y".equals(i.symbol())).findFirst().orElseThrow();
        assertThat(second.blocking()).isNull();
        assertThat(second.solverType()).isEqualTo("headers");
    }

    private String solverFor(String factKind, String file, String severity) {
        Map<String, Object> evidence = new HashMap<>();
        if (factKind != null) {
            evidence.put("factKind", factKind);
        }
        evidence.put("actionable", true);
        return AiMigrationProjector.solverType(finding("fx", evidence(evidence), file, 1, severity));
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
