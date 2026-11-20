from __future__ import annotations

import unittest

from kb_scanner.core import KnowledgeEntry, KnowledgeSnapshot, KnowledgeSource, NormalizedFact, stable_id
from kb_scanner.detectors.definitions import DetectorDefinition
from kb_scanner.detectors.matchers import KnowledgeIndex, match_fact


def knowledge() -> KnowledgeSnapshot:
    source = KnowledgeSource(stable_id("source"), "compiler", "generated-json", "facts.json", "a" * 64)
    entries = tuple(
        KnowledgeEntry(stable_id("entry", kind, value), kind, "x86", value, (source.id,))
        for kind, value in (
            ("headers", "immintrin.h"), ("intrinsics", "_mm_add_ps"), ("builtins", "__builtin_ia32_pause"),
            ("vectorTypes", "__m128"), ("platformMacros", "__AVX__"), ("compilerFlags", "-mavx2"),
            ("assemblyInstructions", "pause"), ("registerNames", "rax"), ("cpuPlatformApis", "__cpuid"),
            ("targetFeatures", "avx2"), ("architectureAliases", "i386"),
            ("vectorWidthMarkers", "vector_size(16)"),
        )
    )
    return KnowledgeSnapshot(stable_id("snapshot"), "1", (source,), entries)


def fact(kind: str, value: str) -> NormalizedFact:
    return NormalizedFact(stable_id("fact", kind, value), kind, stable_id("subject"), value, "scope", (stable_id("evidence", kind, value),))


class LocalMatcherTests(unittest.TestCase):
    def setUp(self) -> None:
        self.index = KnowledgeIndex(knowledge())

    def test_exact_token_and_flag_matchers_use_generated_facts(self) -> None:
        cases = (
            (DetectorDefinition("h", "include", ("headers",), "o", "exact"), fact("include", "immintrin.h")),
            (DetectorDefinition("i", "symbol", ("intrinsics",), "o", "exact"), fact("symbol", "_mm_add_ps")),
            (DetectorDefinition("b", "symbol", ("builtins",), "o", "exact"), fact("symbol", "__builtin_ia32_pause")),
            (DetectorDefinition("v", "type", ("vectorTypes",), "o", "token"), fact("type", "const __m128 *")),
            (DetectorDefinition("m", "preprocessor", ("platformMacros",), "o", "token"), fact("preprocessor", "defined(__AVX__)")),
            (DetectorDefinition("f", "compiler_argument", ("compilerFlags",), "o", "flag"), fact("compiler_argument", "-mavx2=yes")),
        )
        for definition, item in cases:
            with self.subTest(definition=definition.detector_id):
                result = match_fact(definition, item, self.index)
                self.assertEqual(result.status, "confirmed_local")
                self.assertEqual(len(result.knowledge_ids), 1)

    def test_token_boundaries_wrong_kind_and_near_misses_do_not_match(self) -> None:
        token = DetectorDefinition("v", "type", ("vectorTypes",), "o", "token")
        self.assertIsNone(match_fact(token, fact("type", "my__m128suffix"), self.index))
        self.assertIsNone(match_fact(token, fact("symbol", "__m128"), self.index))
        exact = DetectorDefinition("h", "include", ("headers",), "o", "exact")
        self.assertIsNone(match_fact(exact, fact("include", "other.h"), self.index))

    def test_structural_comment_and_architecture_candidate_have_no_fake_knowledge(self) -> None:
        cases = (
            (DetectorDefinition("s", "inline_asm", (), "o", "structural", "candidate"), fact("inline_asm", "asm(pause)"), "candidate"),
            (DetectorDefinition("c", "comment", (), "o", "comment_hint", "hint"), fact("comment", "AVX optimized"), "hint"),
            (DetectorDefinition("a", "macro", (), "o", "architecture_candidate", "candidate"), fact("macro", "LOCAL_X86_PATH"), "candidate"),
        )
        for definition, item, status in cases:
            result = match_fact(definition, item, self.index)
            self.assertEqual(result.status, status)
            self.assertEqual(result.knowledge_ids, ())
        self.assertIsNone(match_fact(cases[1][0], fact("comment", "portable implementation"), self.index))
        self.assertIsNone(match_fact(cases[2][0], fact("macro", "LOCAL_PORTABLE_PATH"), self.index))

    def test_new_knowledge_matchers_observe_boundaries_case_and_vector_spacing(self) -> None:
        cases = (
            (DetectorDefinition("asm", "inline_asm", ("assemblyInstructions",), "o", "token"), fact("inline_asm", 'asm("PAUSE %RAX")')),
            (DetectorDefinition("reg", "inline_asm", ("registerNames",), "o", "token"), fact("inline_asm", 'asm("pause %RAX")')),
            (DetectorDefinition("api", "symbol", ("cpuPlatformApis",), "o", "exact"), fact("symbol", "__cpuid")),
            (DetectorDefinition("target", "declaration_attribute", ("targetFeatures",), "o", "token"), fact("declaration_attribute", 'target("AVX2")')),
            (
                DetectorDefinition("alias", "declaration_attribute", ("architectureAliases",), "o", "token"),
                fact("declaration_attribute", 'target("arch=I386")'),
            ),
            (
                DetectorDefinition("width", "declaration_attribute", ("vectorWidthMarkers",), "o", "token", "candidate"),
                fact("declaration_attribute", "__attribute__((vector_size ( 16 )))"),
            ),
        )
        for definition, item in cases:
            with self.subTest(definition=definition.detector_id):
                self.assertIsNotNone(match_fact(definition, item, self.index))
        api = cases[2][0]
        width = cases[-1][0]
        self.assertIsNone(match_fact(api, fact("symbol", "__cpuid_helper"), self.index))
        self.assertIsNone(match_fact(width, fact("declaration_attribute", "aligned(16)"), self.index))

    def test_arm64_family_match_is_isolated_from_x86_entries(self) -> None:
        """KnowledgeIndex 按族过滤：arm64 匹配只命中 arm64 条目，x86 词表不串扰。"""
        source = KnowledgeSource(stable_id("source"), "compiler", "collected-json", "arm64.json", "b" * 64)
        arm64_entries = tuple(
            KnowledgeEntry(stable_id("arm64", kind, value), kind, "arm64", value, (source.id,))
            for kind, value in (
                ("headers", "arm_neon.h"), ("platformMacros", "__ARM_NEON"), ("builtins", "__builtin_aarch64_get_fpcr"),
                ("assemblyInstructions", "mrs"),
            )
        )
        mixed = KnowledgeSnapshot(stable_id("mixed"), "1", (source,), arm64_entries + knowledge().entries)
        index = KnowledgeIndex(mixed)

        header = DetectorDefinition("h64", "include", ("headers",), "arm64_header_reference", "exact")
        result = match_fact(header, fact("include", "arm_neon.h"), index, "arm64")
        self.assertIsNotNone(result)
        self.assertEqual("confirmed_local", result.status)
        # arm64 匹配不命中 x86 头文件（即使快照里同时有 x86 词条）。
        self.assertIsNone(match_fact(header, fact("include", "immintrin.h"), index, "arm64"))
        # 反向：x86 扫描不命中 arm64 头文件/宏/指令。
        self.assertIsNone(match_fact(header, fact("include", "arm_neon.h"), index, "x86"))
        macro = DetectorDefinition("m64", "macro", ("platformMacros",), "arm64_platform_macro_reference", "token")
        self.assertIsNotNone(match_fact(macro, fact("macro", "__ARM_NEON"), index, "arm64"))
        self.assertIsNone(match_fact(macro, fact("macro", "__ARM_NEON"), index, "x86"))
        asm = DetectorDefinition("a64", "inline_asm", ("assemblyInstructions",), "arm64_assembly_instruction", "token")
        asm_match = match_fact(asm, fact("inline_asm", "mrs x0, fpcr"), index, "arm64")
        self.assertIsNotNone(asm_match)
        # 大小写不敏感（指令 canonical 已转小写）。
        self.assertIsNone(match_fact(asm, fact("inline_asm", "MRS x0, FPCR"), index, "x86"))

    def test_arm64_comment_hint_tokens_are_family_selected(self) -> None:
        """注释线索/架构形候选按族选择词元正则。"""
        comment = DetectorDefinition("c64", "comment", (), "arm64_comment_hint", "comment_hint", "hint")
        candidate = DetectorDefinition("k64", "macro", (), "architecture_identifier_candidate", "architecture_candidate", "candidate")
        self.assertIsNotNone(match_fact(comment, fact("comment", "NEON fast path for aarch64"), KnowledgeIndex(knowledge()), "arm64"))
        self.assertIsNone(match_fact(comment, fact("comment", "no architecture hints here"), KnowledgeIndex(knowledge()), "arm64"))
        self.assertIsNotNone(match_fact(candidate, fact("macro", "USE_SVE2_PATH"), KnowledgeIndex(knowledge()), "arm64"))
        # x86 词元（avx）在 arm64 扫描中不构成注释线索。
        self.assertIsNone(match_fact(comment, fact("comment", "uses avx internally"), KnowledgeIndex(knowledge()), "arm64"))


if __name__ == "__main__":
    unittest.main()
