"""Rust frontend 隔离单测：cfg 三值矩阵、检测点产证、预算与确定性。"""
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest

from kb_scanner.core import CompletionStatus, WorkspaceSnapshot
from kb_scanner.frontends.rust.cfg_evaluator import evaluate_cfg
from kb_scanner.frontends.rust.frontend import RustFrontend, _extract_macro_body, _asm_statements
from kb_scanner.frontends.rust.vocabulary import is_rust_path, make_rust_file_entity


def context(languages=("rust",), scanner_version="test"):
    return SimpleNamespace(languages=languages, scanner_version=scanner_version)


class CfgEvaluatorTests(unittest.TestCase):
    def test_three_value_matrix(self):
        """三值矩阵：riscv→True；其他架构字面量→False；未知键/裸标识符→None。"""
        cases = (
            ('target_arch = "riscv64"', True, "riscv64"),
            ('target_arch = "riscv32"', True, "riscv32"),
            ('target_arch = "x86_64"', False, "x86_64"),
            ('target_arch = "aarch64"', False, "aarch64"),
            ('target_feature = "sse4.1"', None, None),
            ("unix", None, None),
            # not 对 default 取反：not(unknown) 仍是 unknown（Kleene）。
            ('not(target_arch = "x86_64")', True, "x86_64"),
            ('not(unix)', None, None),
            # any 子项缺省 False：无确凿 riscv → unknown 不抬高。
            ('any(target_arch = "riscv64", unix)', True, "riscv64"),
            ('any(target_arch = "x86_64", unix)', None, None),
            ('any(target_arch = "x86_64")', False, None),
            # all 子项缺省 True：x86 确凿 False → False。
            ('all(target_arch = "x86_64", unix)', False, "x86_64"),
            ('all(target_arch = "riscv64", unix)', None, None),
        )
        for expression, expected, arch in cases:
            with self.subTest(expression=expression):
                result = evaluate_cfg(expression)
                self.assertEqual(expected, result.value)
                self.assertEqual(arch, result.target_arch)

    def test_malformed_returns_unknown(self):
        """未知形态不抛异常：返回 unknown（五态兜底）。"""
        for bad in ("", "= =", "any(", 'foo =', "a b c"):
            with self.subTest(bad=bad):
                result = evaluate_cfg(bad)
                self.assertIsNone(result.value)


class RustFrontendUnitTests(unittest.TestCase):
    def _scan(self, text, languages=("rust",)):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "lib.rs").write_text(text)
            result = RustFrontend().analyze(context(languages), WorkspaceSnapshot(root, ("lib.rs",)))
        return result

    def test_language_gate_off(self):
        result = self._scan("fn f() {}\n", languages=("c", "cpp"))
        self.assertEqual((), result.value.entities)
        self.assertEqual((), result.value.evidence)

    def test_cfg_attribute_produces_condition_with_evaluation(self):
        result = self._scan('#[cfg(target_arch = "x86_64")]\nfn f() {}\n')
        self.assertEqual(1, len(result.value.evidence))
        evidence = result.value.evidence[0]
        self.assertEqual("preprocessor_condition", evidence.kind)
        self.assertEqual("false", evidence.metadata["cfgValue"])
        self.assertEqual("x86_64", evidence.metadata["cfgTargetArch"])

    def test_asm_macro_extracts_statements(self):
        result = self._scan('unsafe fn r() { global_asm!("movaps %xmm0, %xmm1"); }\n')
        values = [e.value for e in result.value.evidence if e.kind == "inline_asm"]
        self.assertEqual(["movaps %xmm0, %xmm1"], values)

    def test_call_and_type_evidence(self):
        result = self._scan("fn f() { let v: __m128i = _mm_add_epi32(1, 2); }\n")
        kinds = {(e.kind, e.value) for e in result.value.evidence}
        self.assertIn(("call_expression", "_mm_add_epi32"), kinds)
        self.assertIn(("type_reference", "__m128i"), kinds)

    def test_arch_use_evidence(self):
        result = self._scan("use core::arch::x86_64::*;\n")
        includes = [e for e in result.value.evidence if e.kind == "include"]
        self.assertEqual(1, len(includes))
        self.assertEqual("std::arch::x86_64", includes[0].value)
        self.assertEqual("x86_64", includes[0].metadata["archModule"])

    def test_plain_rust_file_no_evidence(self):
        """无架构特征的 Rust 代码零证据（fn/main/println 不是检测点）。"""
        result = self._scan("fn main() { println!(\"hi\"); let x = 1 + 2; }\n")
        self.assertEqual((), result.value.evidence)

    def test_budget_and_determinism(self):
        text = "#[cfg(unix)]\nfn f() {}\n"
        first = self._scan(text)
        second = self._scan(text)
        self.assertEqual([e.id for e in first.value.evidence], [e.id for e in second.value.evidence])
        with self.assertRaises(ValueError):
            RustFrontend(max_bytes=0)

    def test_partial_parse_degrades_not_fails(self):
        result = self._scan("fn broken( {\n")
        self.assertEqual(CompletionStatus.PARTIAL, result.status)
        self.assertTrue(any(d.code == "rust-frontend.partial-parse" for d in result.diagnostics))


class VocabularyUnitTests(unittest.TestCase):
    def test_suffix_gates(self):
        for path, expected in (("a.rs", True), ("d/b.rs", True), ("a.rs.txt", False),
                               ("a.c", False), ("a.go", False), ("rs", False)):
            with self.subTest(path=path):
                self.assertEqual(expected, is_rust_path(path))

    def test_entity_id_path_stable(self):
        self.assertEqual(make_rust_file_entity("a.rs", "v1").id, make_rust_file_entity("a.rs", "v2").id)


class MacroHelpersUnitTests(unittest.TestCase):
    def test_extract_macro_body(self):
        self.assertEqual("mov %rax, %rbx", _extract_macro_body('asm!("mov %rax, %rbx")'))
        self.assertEqual("a\nb", _extract_macro_body('asm!("a\\nb")'))
        self.assertEqual("", _extract_macro_body("global_asm!(SYMBOL)"))

    def test_asm_statements(self):
        self.assertEqual(["a", "b"], _asm_statements("a ; b"))
        self.assertEqual([], _asm_statements("  "))


if __name__ == "__main__":
    unittest.main()
