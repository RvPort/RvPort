"""Go frontend 隔离单测：build tag 三值矩阵、检测点产证、门控与确定性。"""
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest

from kb_scanner.core import CompletionStatus, WorkspaceSnapshot
from kb_scanner.frontends.go.build_tag import evaluate_build_tag
from kb_scanner.frontends.go.frontend import GoFrontend
from kb_scanner.frontends.go.vocabulary import is_go_path


def context(languages=("go",), scanner_version="test"):
    return SimpleNamespace(languages=languages, scanner_version=scanner_version)


class BuildTagTests(unittest.TestCase):
    def test_three_value_matrix(self):
        """三值：riscv→True；已知非 riscv 架构→False；非架构 tag→None。"""
        cases = (
            ("riscv64", True),
            ("amd64", False),
            ("386", False),
            ("arm64", False),
            ("loong64", False),
            ("linux", None),
            ("cgo", None),
            ("unix", None),
            # ! 对 default 取反。
            ("!amd64", True),
            ("!linux", None),
            # && / || Kleene 折叠。
            ("linux && amd64", False),
            ("linux && riscv64", None),
            ("amd64 || riscv64", True),
            ("amd64 || arm64", False),
            ("linux || amd64", None),
            # 优先级与括号。
            ("linux && (amd64 || arm64)", False),
            ("(linux && amd64) || riscv64", True),
            ("!linux && amd64", False),
        )
        for expression, expected in cases:
            with self.subTest(expression=expression):
                self.assertEqual(expected, evaluate_build_tag(expression))

    def test_malformed_returns_unknown(self):
        for bad in ("", "&&", "(linux", "linux)", "a b"):
            with self.subTest(bad=bad):
                self.assertIsNone(evaluate_build_tag(bad))


class GoFrontendUnitTests(unittest.TestCase):
    def _scan(self, text, languages=("go",)):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "main.go").write_text(text)
            result = GoFrontend().analyze(context(languages), WorkspaceSnapshot(root, ("main.go",)))
        return result

    def test_language_gate_off(self):
        result = self._scan("package main\n", languages=("c", "cpp"))
        self.assertEqual((), result.value.entities)
        self.assertEqual((), result.value.evidence)

    def test_build_tag_evidence_with_evaluation(self):
        result = self._scan("//go:build linux && amd64\n\npackage main\n")
        conditions = [e for e in result.value.evidence if e.kind == "preprocessor_condition"]
        self.assertEqual(1, len(conditions))
        self.assertEqual("false", conditions[0].metadata["tagValue"])
        self.assertEqual("linux && amd64", conditions[0].value)

    def test_goarch_compare_evidence(self):
        result = self._scan('package main\nfunc f() bool { return runtime.GOARCH == "amd64" }\n')
        selections = [e for e in result.value.evidence if e.kind == "selection_condition"]
        self.assertEqual(1, len(selections))
        self.assertEqual("amd64", selections[0].metadata["goarchValue"])
        self.assertEqual("false", selections[0].metadata["supportsRiscv"])

    def test_goarch_not_equal_flips(self):
        result = self._scan('package main\nfunc f() bool { return runtime.GOARCH != "riscv64" }\n')
        selections = [e for e in result.value.evidence if e.kind == "selection_condition"]
        self.assertEqual(1, len(selections))
        self.assertEqual("false", selections[0].metadata["supportsRiscv"], '!= riscv64 表示不支持')

    def test_cgo_block_evidence(self):
        text = ('package main\n\n// #include <immintrin.h>\n'
                '// __m256i zero(void) { return _mm256_setzero_si256(); }\nimport "C"\n')
        result = self._scan(text)
        kinds = {(e.kind, e.metadata.get("nodeType")) for e in result.value.evidence}
        self.assertIn(("include", "go_cgo_include"), kinds)
        self.assertIn(("inline_asm", "go_cgo_code"), kinds)

    def test_plain_go_file_minimal_evidence(self):
        """无架构特征的 Go 代码零证据。"""
        result = self._scan("package main\n\nfunc main() { println(\"hi\") }\n")
        self.assertEqual((), result.value.evidence)

    def test_riscv_build_tag_true(self):
        result = self._scan("//go:build riscv64\n\npackage main\n")
        conditions = [e for e in result.value.evidence if e.kind == "preprocessor_condition"]
        self.assertEqual("true", conditions[0].metadata["tagValue"])

    def test_determinism_and_budget_guard(self):
        text = "//go:build amd64\n\npackage main\n"
        first = self._scan(text)
        second = self._scan(text)
        self.assertEqual([e.id for e in first.value.evidence], [e.id for e in second.value.evidence])
        with self.assertRaises(ValueError):
            GoFrontend(max_bytes=0)

    def test_partial_parse_degrades(self):
        result = self._scan("package main\nfunc broken( {\n")
        self.assertEqual(CompletionStatus.PARTIAL, result.status)


class GoFrontendEdgeTests(unittest.TestCase):
    """90/90 门禁补充：降级路径与求值器边界。"""

    def _scan(self, text, max_bytes=None, languages=("go",)):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "main.go").write_text(text)
            frontend = GoFrontend(max_bytes=max_bytes) if max_bytes else GoFrontend()
            result = frontend.analyze(context(languages), WorkspaceSnapshot(root, ("main.go",)))
        return result

    def test_oversize_input_degrades_partial(self):
        result = self._scan("// p\n" * 100, max_bytes=8)
        self.assertEqual(CompletionStatus.PARTIAL, result.status)
        self.assertTrue(any(d.code == "go-frontend.input-partial" for d in result.diagnostics))

    def test_goarch_node_without_match_ignored(self):
        """binary_expression 不含 GOARCH 比较 → 不产证。"""
        result = self._scan("package main\nfunc f() bool { return 1 + 2 == 3 }\n")
        selections = [e for e in result.value.evidence if e.kind == "selection_condition"]
        self.assertEqual([], selections)

    def test_switch_case_form(self):
        """switch { case runtime.GOARCH == ...: } 形态经 switch 块与 case 节点各产证。"""
        result = self._scan('package main\nfunc f() { switch {\ncase runtime.GOARCH == "arm64":\n}\n}\n')
        selections = [e for e in result.value.evidence if e.kind == "selection_condition"]
        self.assertGreaterEqual(len(selections), 1)
        self.assertTrue(all(s.metadata["supportsRiscv"] == "false" for s in selections))

    def test_non_c_import_c_no_evidence(self):
        """import 非 "C"（如 runtime）不触发 cgo 块。"""
        result = self._scan('package main\n// #include <x.h>\nimport "runtime"\n')
        includes = [e for e in result.value.evidence if e.kind == "include"]
        self.assertEqual([], includes)


class BuildTagEdgeTests(unittest.TestCase):
    def test_precedence_without_parens(self):
        """&& 优先于 ||（无括号）：linux&&amd64=False，再 || riscv64=True → True。"""
        self.assertTrue(evaluate_build_tag("linux && amd64 || riscv64"))
        # riscv64=True || (linux&&amd64=False) → True（短路）。
        self.assertTrue(evaluate_build_tag("riscv64 || linux && amd64"))
        # linux&&mytag：False&&None → False（&& 对 False 短路）。
        self.assertFalse(evaluate_build_tag("amd64 && mytag"))

    def test_nested_not(self):
        self.assertIsNone(evaluate_build_tag("!!linux"))
        self.assertTrue(evaluate_build_tag("!amd64"))

    def test_unknown_arch_word_not_arch(self):
        """非闭集词（自定义 tag）→ unknown 而非 False。"""
        self.assertIsNone(evaluate_build_tag("mytag"))

    def test_malformed_variants(self):
        for bad in ("()", "! ", "&& amd64", "amd64 ||", "(amd64"):
            with self.subTest(bad=bad):
                self.assertIsNone(evaluate_build_tag(bad))


class VocabularyUnitTests(unittest.TestCase):
    def test_negative_suffix(self):
        """is_go_path 的 False 分支（覆盖词法分支的负例）。"""
        self.assertFalse(is_go_path("a.go.txt"))
        self.assertFalse(is_go_path("a.java"))
        self.assertFalse(is_go_path("go.mod"))

    def test_suffix_gates(self):
        for path, expected in (("a.go", True), ("d/b.go", True), ("a.go.txt", False),
                               ("a.rs", False), ("a.c", False), ("go", False)):
            with self.subTest(path=path):
                self.assertEqual(expected, is_go_path(path))


if __name__ == "__main__":
    unittest.main()
