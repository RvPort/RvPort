"""汇编 frontend 的隔离单测：纯词法扫描逻辑，无项目/命令/源码读取。"""
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest

from kb_scanner.core import CompletionStatus, WorkspaceSnapshot, stable_id
from kb_scanner.frontends.asm.frontend import AsmFrontend, _scan_lines
from kb_scanner.frontends.asm.vocabulary import is_assembly_path, make_asm_file_entity, make_asm_evidence

SUBJECT = stable_id("file", "a.S")


def context(languages=("asm",), scanner_version="test"):
    return SimpleNamespace(languages=languages, scanner_version=scanner_version)


class FakeReader:
    """可注入 reader：按路径返回预设文本（绕开文件系统）。"""

    def __init__(self, texts):
        self.texts = texts

    def __call__(self, path):
        return self.texts[path.name]


class AsmFrontendUnitTests(unittest.TestCase):
    def _workspace(self, names):
        return WorkspaceSnapshot(Path("/tmp"), names)

    def test_language_gate_off_produces_nothing(self):
        """language 不含 asm：零实体零证据（向后兼容）。"""
        frontend = AsmFrontend(FakeReader({}))
        result = frontend.analyze(context(languages=("c", "cpp")), self._workspace(("a.S",)))
        self.assertEqual((), result.value.entities)
        self.assertEqual((), result.value.evidence)
        self.assertEqual(CompletionStatus.COMPLETE, result.status)

    def test_entities_and_evidence_per_statement(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "a.S").write_text("label:\n  movaps xmm0, xmm1\n")
            frontend = AsmFrontend(FakeReader({"a.S": "label:\n  movaps xmm0, xmm1\n"}))
            # 真实文件提供 stat/resolve；reader 注入避免重复读盘。
            result = frontend.analyze(context(), WorkspaceSnapshot(root, ("a.S",)))
        self.assertEqual(1, len(result.value.entities))
        self.assertEqual("source_file", result.value.entities[0].kind)
        self.assertEqual("asm", result.value.entities[0].properties["language"])
        self.assertEqual(1, len(result.value.evidence))
        evidence = result.value.evidence[0]
        self.assertEqual("inline_asm", evidence.kind)
        self.assertEqual("movaps xmm0, xmm1", evidence.value)
        self.assertEqual("lexical-line", evidence.parse_mode)
        self.assertIn("asmByteOffset", evidence.metadata)

    def test_non_asm_paths_ignored(self):
        frontend = AsmFrontend(FakeReader({}))
        workspace = WorkspaceSnapshot(Path("/tmp"), ("a.c", "Makefile", "b.S"))
        result = frontend.analyze(context(), workspace)
        self.assertEqual(1, len(result.value.entities), "只有 .S 进清单")

    def test_missing_input_degrades_partial(self):
        def boom(path):
            raise OSError("gone")

        frontend = AsmFrontend(boom)
        workspace = WorkspaceSnapshot(Path("/tmp"), ("a.S",))
        result = frontend.analyze(context(), workspace)
        self.assertEqual(CompletionStatus.PARTIAL, result.status)
        self.assertTrue(any(d.code == "asm-frontend.input-partial" for d in result.diagnostics))

    def test_oversize_input_degrades_partial(self):
        frontend = AsmFrontend(FakeReader({"a.S": "x" * 100}), max_bytes=10)
        workspace = WorkspaceSnapshot(Path("/tmp"), ("a.S",))
        result = frontend.analyze(context(), workspace)
        self.assertEqual(CompletionStatus.PARTIAL, result.status)
        self.assertEqual((), result.value.evidence)

    def test_invalid_max_bytes_rejected(self):
        with self.assertRaises(ValueError):
            AsmFrontend(FakeReader({}), max_bytes=0)


class ScanLinesUnitTests(unittest.TestCase):
    def test_preprocessor_lines_skipped(self):
        items = list(_scan_lines("a.S", SUBJECT, ["#include <x>", "#if FOO", "#endif", "real:"]))
        self.assertEqual([], items)

    def test_comment_forms_stripped(self):
        items = list(_scan_lines("a.S", SUBJECT, ["a ; c1", "b // c2"]))
        self.assertEqual(["a", "b"], [i.value for i in items])

    def test_block_comment_spans_lines(self):
        items = list(_scan_lines("a.S", SUBJECT, ["/* open", "still comment", "end */ insn1", "insn2"]))
        self.assertEqual(["insn1", "insn2"], [i.value for i in items])

    def test_label_prefix_and_standalone(self):
        items = list(_scan_lines("a.S", SUBJECT, ["foo: insn9", "lonely:", "insn8"]))
        self.assertEqual(["insn9", "insn8"], [i.value for i in items])

    def test_byte_offsets_cumulative(self):
        items = list(_scan_lines("a.S", SUBJECT, ["#skip me", "insn1", "insn2"]))
        self.assertEqual(9, items[0].metadata["asmByteOffset"], "首语句偏移含被跳过的 # 行")
        self.assertGreater(items[1].metadata["asmByteOffset"], items[0].metadata["asmByteOffset"])

    def test_stable_ids_deterministic(self):
        first = list(_scan_lines("a.S", SUBJECT, ["insn1"]))
        second = list(_scan_lines("a.S", SUBJECT, ["insn1"]))
        self.assertEqual(first[0].id, second[0].id)


class AsmScanEdgeTests(unittest.TestCase):
    """90/90 门禁补充：标签循环与词法边界。"""

    def test_label_loop_stops_on_space_head(self):
        """含空格的冒号头（'foo bar:'）不是 GNU 标签：整行作为语句产出。"""
        items = list(_scan_lines("a.S", SUBJECT, ["foo bar: insn1"]))
        self.assertEqual(["foo bar: insn1"], [i.value for i in items])

    def test_multiple_label_prefixes_stripped(self):
        """嵌套标签（foo: bar: insn）逐层剥离。"""
        items = list(_scan_lines("a.S", SUBJECT, ["foo: bar: insn2"]))
        self.assertEqual(["insn2"], [i.value for i in items])

    def test_indented_label_standalone_skipped(self):
        items = list(_scan_lines("a.S", SUBJECT, ["  lonely:  "]))
        self.assertEqual([], items)

    def test_semicolon_only_line_skipped(self):
        items = list(_scan_lines("a.S", SUBJECT, [" ; just a comment"]))
        self.assertEqual([], items)

    def test_non_asm_files_ignored_in_workspace(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "a.c").write_text("int x;\n")
            (root / "Makefile").write_text("all:\n")
            frontend = AsmFrontend(FakeReader({}))
            result = frontend.analyze(context(), WorkspaceSnapshot(root, ("a.c", "Makefile")))
        self.assertEqual((), result.value.entities)


class VocabularyUnitTests(unittest.TestCase):
    def test_suffix_gates(self):
        for path, expected in (("a.S", True), ("a.s", True), ("a.asm", True), ("d/b.s", True),
                               ("a.c", False), ("a.cpp", False), ("a.inc", False), ("asm", False), ("a.SX", False)):
            with self.subTest(path=path):
                self.assertEqual(expected, is_assembly_path(path))

    def test_entity_id_is_path_stable(self):
        self.assertEqual(make_asm_file_entity("a.S", "v1").id, make_asm_file_entity("a.S", "v2").id)

    def test_evidence_confidence_default(self):
        evidence = make_asm_evidence(subject_id=SUBJECT, path="a.S", line=1,
                                     start_column=1, end_column=6, value="insn1", byte_offset=0)
        self.assertEqual("medium", evidence.confidence)
        self.assertEqual("asm-frontend", evidence.producer)


if __name__ == "__main__":
    unittest.main()
