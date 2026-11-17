"""Java frontend 隔离单测：JNI 形态矩阵、变量收集消误报、os.arch、门控与确定性。"""
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest

from kb_scanner.core import CompletionStatus, WorkspaceSnapshot
from kb_scanner.frontends.java.frontend import JavaFrontend
from kb_scanner.frontends.java.vocabulary import is_java_path


def context(languages=("java",), scanner_version="test"):
    return SimpleNamespace(languages=languages, scanner_version=scanner_version)


class JavaFrontendUnitTests(unittest.TestCase):
    def _scan(self, text, languages=("java",)):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "Loader.java").write_text(text)
            result = JavaFrontend().analyze(context(languages), WorkspaceSnapshot(root, ("Loader.java",)))
        return result

    def _jni(self, result):
        return [e for e in result.value.evidence if e.kind == "call_expression"]

    def _libraries(self, result):
        return [e.value for e in result.value.evidence if e.kind == "preprocessor_condition"]

    def test_language_gate_off(self):
        result = self._scan("class A {}\n", languages=("c", "cpp"))
        self.assertEqual((), result.value.entities)
        self.assertEqual((), result.value.evidence)

    def test_standard_load_forms(self):
        result = self._scan('class A { void f() { System.loadLibrary("foo_x86"); System.load("/opt/lib.so"); } }\n')
        forms = {e.metadata["jniForm"] for e in self._jni(result)}
        self.assertEqual({"standard"}, forms)
        self.assertIn("foo_x86", self._libraries(result))

    def test_native_receiver_form(self):
        result = self._scan('class A { void f() { Native.loadLibrary("foo"); } }\n')
        self.assertEqual({"native_jna"}, {e.metadata["jniForm"] for e in self._jni(result)})

    def test_runtime_chain_form(self):
        result = self._scan('class A { void f() { Runtime.getRuntime().loadLibrary("bar"); } }\n')
        self.assertEqual({"runtime_chain"}, {e.metadata["jniForm"] for e in self._jni(result)})
        self.assertIn("bar", self._libraries(result))

    def test_runtime_var_collection_and_false_positive_exclusion(self):
        """变量链式：已收集的 rt 产证；未收集的 mystery 不产证（消误报）。"""
        result = self._scan(
            'class A { static Runtime rt = Runtime.getRuntime();\n'
            '  void f() { rt.load("libx_amd64.so"); mystery.loadLibrary("nope"); } }\n')
        forms = {e.metadata["jniForm"] for e in self._jni(result)}
        self.assertEqual({"runtime_var"}, forms, "只有已收集的 rt 出证")
        self.assertIn("libx_amd64.so", self._libraries(result))
        self.assertNotIn("nope", self._libraries(result))

    def test_os_arch_compare(self):
        result = self._scan('class A { boolean x() { return "amd64".equals(System.getProperty("os.arch")); } }\n')
        selections = [e for e in result.value.evidence if e.kind == "selection_condition"]
        self.assertEqual(1, len(selections))
        self.assertEqual("amd64", selections[0].metadata["archValue"])
        self.assertEqual("false", selections[0].metadata["supportsRiscv"])

    def test_os_arch_riscv_supported(self):
        result = self._scan('class A { boolean x() { return "riscv64".equals(System.getProperty("os.arch")); } }\n')
        selections = [e for e in result.value.evidence if e.kind == "selection_condition"]
        self.assertEqual("true", selections[0].metadata["supportsRiscv"])

    def test_plain_java_no_evidence(self):
        result = self._scan("class A { int f() { return 1 + 2; } }\n")
        self.assertEqual((), result.value.evidence)

    def test_unrelated_load_not_reported(self):
        """普通对象的 load 方法（非 JNI 接收者）不产证。"""
        result = self._scan('class A { void f() { config.load("x"); loader.loadLibrary("y"); } }\n')
        self.assertEqual([], self._jni(result))

    def test_determinism_and_budget_guard(self):
        text = 'class A { void f() { System.loadLibrary("foo"); } }\n'
        first = self._scan(text)
        second = self._scan(text)
        self.assertEqual([e.id for e in first.value.evidence], [e.id for e in second.value.evidence])
        with self.assertRaises(ValueError):
            JavaFrontend(max_bytes=0)

    def test_partial_parse_degrades(self):
        result = self._scan("class broken {\n")
        self.assertEqual(CompletionStatus.PARTIAL, result.status)


class VocabularyUnitTests(unittest.TestCase):
    def test_suffix_gates(self):
        for path, expected in (("A.java", True), ("d/A.java", True), ("a.jav", False),
                               ("a.rs", False), ("a.py", False), ("java", False)):
            with self.subTest(path=path):
                self.assertEqual(expected, is_java_path(path))


if __name__ == "__main__":
    unittest.main()
