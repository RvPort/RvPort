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


class PythonFrontendEdgeTests(unittest.TestCase):
    """90/90 门禁补充：状态机边界、pyx 形态与门控。"""

    def _scan(self, files, languages=("python",)):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name, content in files.items():
                (root / name).write_text(content)
            result = PythonFrontend().analyze(context(languages), WorkspaceSnapshot(root, tuple(files)))
        return result

    def test_oversize_input_degrades_partial(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "a.py").write_text("# pad\n" * 100)
            result = PythonFrontend(max_bytes=8).analyze(context(), WorkspaceSnapshot(root, ("a.py",)))
        self.assertEqual(CompletionStatus.PARTIAL, result.status)
        self.assertTrue(any(d.code == "python-frontend.input-partial" for d in result.diagnostics))

    def test_import_alias_cffi_module(self):
        """import cffi as cf：模块名归类命中（cffi 状态置位，dlopen 走实例分支）。"""
        result = self._scan({"a.py": 'import cffi as cf\n_ = cf.FFI().dlopen("x.so")\n'})
        loads = [e for e in result.value.evidence if e.metadata.get("nodeType") == "python_dynamic_load"]
        self.assertEqual({"cffi_instance"}, {e.metadata["loadForm"] for e in loads})

    def test_from_import_unrelated_module_ignored(self):
        result = self._scan({"a.py": "from os import path\n"})
        self.assertEqual((), result.value.evidence)

    def test_ffi_assignment_with_module_prefix(self):
        """ffi = cffi.FFI() 形态同样收集实例。"""
        result = self._scan({"a.py": 'import cffi\nf = cffi.FFI()\nf.dlopen("y.so")\n'})
        loads = [e for e in result.value.evidence if e.metadata.get("nodeType") == "python_dynamic_load"]
        self.assertEqual({"cffi_instance"}, {e.metadata["loadForm"] for e in loads})

    def test_set_source_single_string_treated_as_c(self):
        """set_source 只有一个字符串（无模块名实参的畸形）：整串按 C 处理。"""
        result = self._scan({"a.py": 'from cffi import FFI\nffi = FFI()\nffi.set_source("#include <x.h>")\n'})
        includes = [e for e in result.value.evidence if e.kind == "include"]
        self.assertEqual(1, len(includes))

    def test_cdef_non_string_argument_no_embedded_c(self):
        """cdef 的实参非字符串字面量：不产内嵌 C 证据。"""
        result = self._scan({"a.py": 'from cffi import FFI\nffi = FFI()\nffi.cdef(DECLS)\n'})
        embedded = [e for e in result.value.evidence if e.metadata.get("nodeType") == "python_cffi_cdef"]
        self.assertEqual([], embedded)

    def test_cdef_empty_string_no_evidence(self):
        result = self._scan({"a.py": 'from cffi import FFI\nffi = FFI()\nffi.cdef("")\n'})
        embedded = [e for e in result.value.evidence if "cffi" in str(e.metadata.get("nodeType", ""))]
        self.assertEqual([], embedded)

    def test_verify_call_produces_selection(self):
        result = self._scan({"a.py": 'from cffi import FFI\nffi = FFI()\nffi.verify("x")\n'})
        selections = [e for e in result.value.evidence if e.kind == "selection_condition"]
        self.assertEqual(1, len(selections))

    def test_pyx_call_without_string_library(self):
        """pyx 行命中调用形态但无字符串库名：产 call 不产库名证。"""
        result = self._scan({"m.pyx": "lib = ctypes.CDLL(name)\n"})
        calls = [e for e in result.value.evidence if e.metadata.get("nodeType") == "cython_ctypes_call"]
        libs = [e for e in result.value.evidence if e.metadata.get("nodeType") == "cython_native_library"]
        self.assertEqual(1, len(calls))
        self.assertEqual([], libs)

    def test_pyx_blank_lines_skipped(self):
        result = self._scan({"m.pyx": "\n\n   \n"})
        self.assertEqual((), result.value.evidence)

    def test_load_form_unqualified_call_without_import(self):
        """无 import 状态时 CDLL() 裸调用不产证。"""
        result = self._scan({"a.py": 'x = CDLL("a.so")\n'})
        loads = [e for e in result.value.evidence if e.metadata.get("nodeType") == "python_dynamic_load"]
        self.assertEqual([], loads)


class VocabularyUnitTests(unittest.TestCase):
    def test_negative_suffix(self):
        self.assertFalse(is_python_path("a.pyw"))
        self.assertFalse(is_python_path("a.pyx.txt"))
    def test_suffix_gates(self):
        for path, expected in (("a.py", True), ("a.pyx", True), ("d/a.py", True),
                               ("a.pyi", False), ("a.java", False), ("py", False)):
            with self.subTest(path=path):
                self.assertEqual(expected, is_python_path(path))


class GoreleaserParserTests(unittest.TestCase):
    def _records(self, yaml_text):
        return parse_goreleaser(yaml_text, ".goreleaser.yml", "riscv64").records

    def test_unclosed_block_flushes_at_eof(self):
        """EOF 兜底：goarch 块未闭合时同样检查缺失。"""
        records = self._records("goarch:\n  - amd64\n")
        self.assertEqual(1, len(records))

    def test_empty_goarch_block_no_record(self):
        """空块（无列表项）不产记录。"""
        records = self._records("goarch:\nother: 1\n")
        self.assertEqual(0, len(records))

    def test_consecutive_blocks(self):
        """两个 goarch 块连续（块结束行即下一锚点）：第二块独立判定。"""
        yaml_text = "goarch:\n  - amd64\ngoarch:\n  - arm64\n"
        records = self._records(yaml_text)
        self.assertEqual(2, len(records), "两个缺 riscv 的块各产一条")

    def test_non_item_line_flushes_block(self):
        """非列表行（任意缩进）触发块 flush；块结束行同时是新锚点则重开。"""
        yaml_text = "goarch:\n  - amd64\narchives: []\n"
        records = self._records(yaml_text)
        self.assertEqual(1, len(records), "flush 时 [amd64] 缺 riscv → 1 条")

    def test_item_indent_not_deeper_ignored(self):
        """缩进不深于锚点的行不是列表项（作为块结束处理）。"""
        yaml_text = "goarch:\n- amd64\n"
        records = self._records(yaml_text)
        # "- amd64" 缩进 0 不深于锚点 0 → 块立即结束且列表空 → 无记录
        self.assertEqual(0, len(records))

    def test_producer_name(self):
        from kb_scanner.frontends.build.goreleaser import goreleaser_producer
        self.assertEqual("goreleaser-indent-parser", goreleaser_producer())
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
