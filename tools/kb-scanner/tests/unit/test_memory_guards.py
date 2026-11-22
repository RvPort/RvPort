"""M1 内存防护的隔离单测：C/C++ 前端字节预算与 CLI 闸门语义。"""
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest

from kb_scanner.core import CompletionStatus, WorkspaceSnapshot
from kb_scanner.frontends.cpp.frontend import MAX_TOTAL_SOURCE_BYTES
from kb_scanner.frontends.cpp.tree_sitter_adapter import MAX_FILE_BYTES


class CppByteBudgetTests(unittest.TestCase):
    def test_budget_constants_aligned_with_other_frontends(self):
        """预算常量与 asm/rust/go/java/python 前端对齐（4MB 单文件 / 64MB 累计）。"""
        self.assertEqual(4 * 1024 * 1024, MAX_FILE_BYTES)
        self.assertEqual(64 * 1024 * 1024, MAX_TOTAL_SOURCE_BYTES)

    def test_oversize_c_file_degrades_partial(self):
        """超大 C 文件（> 4MB）降级 PARTIAL 并记 tree-sitter.file-too-large。"""
        from unittest.mock import patch
        from kb_scanner.frontends.cpp.frontend import CppFrontend

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            big = root / "generated.c"
            big.write_bytes(b"// pad\n" * (MAX_FILE_BYTES // 6 + 8))
            with patch("subprocess.run", side_effect=AssertionError("forbidden")):
                result = CppFrontend().analyze(
                    SimpleNamespace(languages=("c",), scanner_version="t",
                                    compile_contexts=(), include_inactive_branches=False,
                                    include_comment_hints=True, locale="zh-CN"),
                    WorkspaceSnapshot(root, ("generated.c",)))
        self.assertEqual(CompletionStatus.PARTIAL, result.status)
        self.assertTrue(any(d.code == "tree-sitter.file-too-large" for d in result.diagnostics),
                        [d.code for d in result.diagnostics])
        self.assertEqual((), result.value.evidence)

    def test_total_budget_stops_remaining_files(self):
        """累计预算超限：跳过剩余文件并记 tree-sitter.total-bytes-budget。"""
        from unittest.mock import patch
        from kb_scanner.frontends.cpp.frontend import CppFrontend
        import kb_scanner.frontends.cpp.frontend as frontend_module

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "a.c").write_text("int a;\n")
            (root / "b.c").write_text("int b;\n")
            original = frontend_module.MAX_TOTAL_SOURCE_BYTES
            frontend_module.MAX_TOTAL_SOURCE_BYTES = 4
            try:
                with patch("subprocess.run", side_effect=AssertionError("forbidden")):
                    result = CppFrontend().analyze(
                        SimpleNamespace(languages=("c",), scanner_version="t",
                                        compile_contexts=(), include_inactive_branches=False,
                                        include_comment_hints=True, locale="zh-CN"),
                        WorkspaceSnapshot(root, ("a.c", "b.c")))
            finally:
                frontend_module.MAX_TOTAL_SOURCE_BYTES = original
        self.assertEqual(CompletionStatus.PARTIAL, result.status)
        self.assertTrue(any(d.code == "tree-sitter.total-bytes-budget" for d in result.diagnostics),
                        [d.code for d in result.diagnostics])


class MemoryBudgetGateTests(unittest.TestCase):
    def test_disabled_budget_returns_note(self):
        """KB_SCANNER_MEMORY_LIMIT_MIB=0：关闭闸门（返回 disabled 注记）。"""
        import os
        from kb_scanner.cli import _apply_memory_budget

        original = os.environ.pop("KB_SCANNER_MEMORY_LIMIT_MIB", None)
        try:
            os.environ["KB_SCANNER_MEMORY_LIMIT_MIB"] = "0"
            notes = _apply_memory_budget()
            self.assertEqual(("memory-budget-disabled",), notes)
        finally:
            if original is not None:
                os.environ["KB_SCANNER_MEMORY_LIMIT_MIB"] = original
            else:
                os.environ.pop("KB_SCANNER_MEMORY_LIMIT_MIB", None)

    def test_budget_applied_and_restored(self):
        """设置后 RLIMIT_AS 生效，且测试结束恢复原值（不污染后续测试）。"""
        import os
        import resource
        from kb_scanner.cli import _apply_memory_budget

        original = resource.getrlimit(resource.RLIMIT_AS)
        try:
            os.environ["KB_SCANNER_MEMORY_LIMIT_MIB"] = "4096"
            notes = _apply_memory_budget()
            self.assertEqual("memory-budget-applied", notes[0])
            soft, _hard = resource.getrlimit(resource.RLIMIT_AS)
            self.assertEqual(4096 * 1024 * 1024, soft)
        finally:
            resource.setrlimit(resource.RLIMIT_AS, original)
            os.environ.pop("KB_SCANNER_MEMORY_LIMIT_MIB", None)

    def test_memory_error_produces_fatal_diagnostic(self):
        """orchestrator 的 MemoryError 边界：产 pipeline.memory-budget-exceeded FATAL。"""
        from unittest.mock import patch
        from kb_scanner.pipeline.orchestrator import StagedPipelineRunner
        from kb_scanner.core import CompletionStatus

        runner = StagedPipelineRunner.__new__(StagedPipelineRunner)
        with patch.object(StagedPipelineRunner, "_run", side_effect=MemoryError("synthetic")):
            result = runner.run(SimpleNamespace(source_arch="x86"))
        codes = [d.code for d in result.diagnostics]
        self.assertIn("pipeline.memory-budget-exceeded", codes)
        self.assertEqual(CompletionStatus.FAILED, result.status)


if __name__ == "__main__":
    unittest.main()
