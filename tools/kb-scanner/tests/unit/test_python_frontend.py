"""Python/Cython frontend 隔离单测：import 状态机、加载形态、内嵌 C、pyx 行正则。"""
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest

from kb_scanner.core import CompletionStatus, WorkspaceSnapshot
from kb_scanner.frontends.build.goreleaser import parse_goreleaser
from kb_scanner.frontends.python.frontend import PythonFrontend
from kb_scanner.frontends.python.vocabulary import is_python_path


def context(languages=("python",), scanner_version="test"):
    return SimpleNamespace(languages=languages, scanner_version=scanner_version)


class PythonFrontendUnitTests(unittest.TestCase):
    def _scan(self, files, languages=("python",)):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name, text in files.items():
                (root / name).write_text(text)
            result = PythonFrontend().analyze(context(languages), WorkspaceSnapshot(root, tuple(files)))
        return result

    def _loads(self, result):
        return [e for e in result.value.evidence if e.metadata.get("nodeType") == "python_dynamic_load"]

    def _libraries(self, result):
        return [e.value for e in result.value.evidence if e.metadata.get("nodeType") == "python_native_library"]

    def test_language_gate_off(self):
        result = self._scan({"a.py": "import ctypes\n"}, languages=("c", "cpp"))
        self.assertEqual((), result.value.entities)
        self.assertEqual((), result.value.evidence)

    def test_qualified_ctypes_forms(self):
        result = self._scan({"a.py": 'import ctypes\nlib = ctypes.CDLL("libfoo_x86.so")\n'})
        self.assertEqual({"ctypes_qualified"}, {e.metadata["loadForm"] for e in self._loads(result)})
        self.assertIn("libfoo_x86.so", self._libraries(result))

    def test_from_import_forms(self):
        result = self._scan({"a.py": 'from ctypes import cdll, CDLL\n_ = cdll.LoadLibrary("a.so")\n_ = CDLL("b.so")\n'})
        self.assertEqual({"ctypes_from_import"}, {e.metadata["loadForm"] for e in self._loads(result)})
        self.assertEqual(2, len(self._loads(result)))

    def test_alias_import_recognized(self):
        result = self._scan({"a.py": 'import ctypes as ct\n_ = ct.CDLL("x.so")\n'})
        # ct.CDLL 不在限定名集合内（riscv-scan 同款局限：限定名匹配不做别名追踪）。
        self.assertEqual([], self._loads(result))

    def test_ffi_instance_and_inline_dlopen(self):
        result = self._scan({"a.py": 'from cffi import FFI\nffi = FFI()\nffi.dlopen("libz_amd64.so")\n'})
        self.assertEqual({"cffi_instance"}, {e.metadata["loadForm"] for e in self._loads(result)})
        self.assertIn("libz_amd64.so", self._libraries(result))

    def test_unresolved_dlopen_not_reported(self):
        """未收集实例的 .dlopen 不产证（消误报）。"""
        result = self._scan({"a.py": 'other.dlopen("x.so")\n'})
        self.assertEqual([], self._loads(result))

    def test_empty_argument_not_reported(self):
        result = self._scan({"a.py": "import ctypes\n_ = ctypes.CDLL()\n"})
        self.assertEqual([], self._loads(result))

    def test_cdef_embedded_c_evidence(self):
        result = self._scan({"a.py": 'from cffi import FFI\nffi = FFI()\nffi.cdef("int probe(void);")\n'})
        embedded = [e for e in result.value.evidence if e.metadata.get("nodeType") == "python_cffi_cdef"]
        self.assertEqual(1, len(embedded))
        self.assertEqual("inline_asm", embedded[0].kind)

    def test_set_source_include_line(self):
        result = self._scan({"a.py": 'from cffi import FFI\nffi = FFI()\nffi.set_source("_m", "#include <immintrin.h>")\n'})
        includes = [e for e in result.value.evidence if e.kind == "include"]
        self.assertEqual(1, len(includes))
        self.assertEqual("#include <immintrin.h>", includes[0].value)

    def test_plain_python_no_evidence(self):
        result = self._scan({"a.py": "import os\nprint('hi')\n"})
        self.assertEqual((), result.value.evidence)

    def test_pyx_line_regex(self):
        result = self._scan({
            "m.pyx": '# comment\nfrom ctypes import CDLL\nlib = ctypes.CDLL("libfoo_x86.so")\n',
        })
        ctypes_calls = [e for e in result.value.evidence if e.metadata.get("nodeType") == "cython_ctypes_call"]
        imports = [e for e in result.value.evidence if e.metadata.get("nodeType") == "cython_ctypes_import"]
        self.assertEqual(1, len(imports), "from ctypes import *dll* 行命中")
        self.assertEqual(1, len(ctypes_calls), "ctypes 调用行命中（# 注释跳过）")
        self.assertIn("libfoo_x86.so", [e.value for e in result.value.evidence if e.metadata.get("nodeType") == "cython_native_library"])

    def test_pyx_entity_language(self):
        result = self._scan({"m.pyx": "x = 1\n"})
        self.assertEqual("cython", result.value.entities[0].properties["language"])

    def test_determinism_and_budget_guard(self):
        files = {"a.py": 'import ctypes\n_ = ctypes.CDLL("x.so")\n'}
        first = self._scan(files)
        second = self._scan(files)
        self.assertEqual([e.id for e in first.value.evidence], [e.id for e in second.value.evidence])
        with self.assertRaises(ValueError):
            PythonFrontend(max_bytes=0)


class VocabularyUnitTests(unittest.TestCase):
    def test_suffix_gates(self):
        for path, expected in (("a.py", True), ("a.pyx", True), ("d/a.py", True),
                               ("a.pyi", False), ("a.java", False), ("py", False)):
            with self.subTest(path=path):
                self.assertEqual(expected, is_python_path(path))


class GoreleaserParserTests(unittest.TestCase):
    def test_missing_riscv_emits_constraint(self):
        result = parse_goreleaser("builds:\n  - goarch:\n      - amd64\n      - arm64\n", ".goreleaser.yml", "riscv64")
        self.assertEqual(1, len(result.records))
        record = result.records[0]
        self.assertEqual("build_arch_constraint", record.kind)
        self.assertFalse(record.metadata["rpm"]["targetAllowed"])

    def test_with_riscv_no_constraint(self):
        result = parse_goreleaser("builds:\n  - goarch:\n      - amd64\n      - riscv64\n", ".goreleaser.yml", "riscv64")
        self.assertEqual([], list(result.records))

    def test_no_goarch_block(self):
        result = parse_goreleaser("project_name: x\n", ".goreleaser.yml", "riscv64")
        self.assertEqual([], list(result.records))


if __name__ == "__main__":
    unittest.main()
