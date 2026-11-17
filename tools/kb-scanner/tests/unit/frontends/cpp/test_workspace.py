from __future__ import annotations

import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from kb_scanner.core import CompletionStatus
from kb_scanner.frontends.cpp.workspace import CppWorkspaceCollector, language_candidates_for_path
from tests.unit.frontends.cpp.support import make_context


class WorkspaceTests(unittest.TestCase):
    def test_language_candidates_are_suffix_case_and_language_aware(self) -> None:
        cases = {
            "a.c": ("c",), "a.C": ("c",), "a.cc": ("cpp",), "a.CPP": ("cpp",),
            "a.hxx": ("c", "cpp"), "a.inc": ("c", "cpp"), "a.rs": (),
        }
        for path, expected in cases.items():
            with self.subTest(path=path):
                self.assertEqual(language_candidates_for_path(path), expected)
        self.assertEqual(language_candidates_for_path("a.h", ("cpp",)), ("cpp",))
        self.assertEqual(language_candidates_for_path("a.c", ("cpp",)), ())

    def test_empty_excludes_prefixes_duplicates_and_sorting(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            for path in ("z.c", "src/a.cpp", "build/x.c", "src/building/ok.c", "exact/y.c"):
                target = root / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text("", encoding="utf-8")
            result = CppWorkspaceCollector().collect(make_context(root, exclude_paths=("exact",)))
            self.assertEqual(result.status, CompletionStatus.COMPLETE)
            self.assertEqual(result.value.files, ("src/a.cpp", "src/building/ok.c", "z.c"))

    def test_missing_unsupported_single_file_and_directory_discovery_error(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            missing = CppWorkspaceCollector().collect(make_context(root / "missing"))
            self.assertEqual(missing.status, CompletionStatus.FAILED)
            unsupported_path = root / "README"
            unsupported_path.write_text("x", encoding="utf-8")
            unsupported = CppWorkspaceCollector().collect(make_context(unsupported_path))
            self.assertEqual(unsupported.status, CompletionStatus.UNAVAILABLE)
            source = root / "main.c"
            source.write_text("", encoding="utf-8")
            single = CppWorkspaceCollector().collect(make_context(source))
            self.assertEqual(single.value.files, ("main.c",))
            with patch("kb_scanner.frontends.cpp.workspace.Path.rglob", side_effect=OSError("denied")):
                failed = CppWorkspaceCollector().collect(make_context(root))
            self.assertEqual(failed.status, CompletionStatus.FAILED)
            self.assertEqual(failed.diagnostics[0].code, "workspace.discovery-failed")
            self.assertNotIn(raw, failed.diagnostics[0].message)

    def test_unreadable_candidate_is_partial_and_other_valid_files_are_retained(self) -> None:
        class UnreadableCandidate:
            name = "broken.c"

            @staticmethod
            def is_file(): return True

            @staticmethod
            def as_posix(): return "0-broken.c"

            @staticmethod
            def relative_to(_source): return Path("broken.c")

            @staticmethod
            def resolve(): raise OSError("metadata denied")

        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            valid = root / "valid.c"
            valid.write_text("", encoding="utf-8")
            with patch("kb_scanner.frontends.cpp.workspace.Path.rglob", return_value=[UnreadableCandidate(), valid]):
                result = CppWorkspaceCollector().collect(make_context(root))
            self.assertEqual(result.status, CompletionStatus.PARTIAL)
            self.assertEqual(result.value.files, ("valid.c",))
            self.assertEqual(result.diagnostics[0].code, "workspace.path-unreadable")
            self.assertNotIn(raw, result.diagnostics[0].message)

    @unittest.skipUnless(hasattr(os, "symlink"), "symlink is unavailable")
    def test_file_symlink_escape_is_partial_and_internal_symlink_is_kept(self) -> None:
        with tempfile.TemporaryDirectory() as raw, tempfile.TemporaryDirectory() as outside_raw:
            root, outside = Path(raw), Path(outside_raw)
            (root / "real.c").write_text("", encoding="utf-8")
            (outside / "outside.c").write_text("", encoding="utf-8")
            try:
                (root / "inside.c").symlink_to(root / "real.c")
                (root / "escape.c").symlink_to(outside / "outside.c")
            except OSError as exc:
                self.skipTest(str(exc))
            result = CppWorkspaceCollector().collect(make_context(root))
            self.assertEqual(result.status, CompletionStatus.PARTIAL)
            self.assertIn("inside.c", result.value.files)
            self.assertNotIn("escape.c", result.value.files)
            self.assertEqual(result.diagnostics[0].code, "workspace.path-escape")

    def test_suffix_named_directory_is_not_a_file(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            (root / "fake.c").mkdir()
            result = CppWorkspaceCollector().collect(make_context(root))
            self.assertEqual(result.value.files, ())


    def test_language_domain_suffix_gates(self) -> None:
        """asm/rust/go/java/python 后缀：语言开→命中；语言关→空（opt-in 语义）。"""
        for suffix, language in ((".S", "asm"), (".rs", "rust"), (".go", "go"), (".java", "java"), (".py", "python"), (".pyx", "python")):
            with self.subTest(suffix=suffix):
                self.assertEqual((language,), language_candidates_for_path(f"a{suffix}", (language,)))
                self.assertEqual((), language_candidates_for_path(f"a{suffix}", ("c", "cpp")))

    def test_header_intersects_enabled_languages(self) -> None:
        """头文件候选与启用语言取交集（c 关→仅 cpp）。"""
        self.assertEqual(("cpp",), language_candidates_for_path("a.h", ("cpp",)))
        self.assertEqual(("c", "cpp"), language_candidates_for_path("a.h", ("cpp", "c")))
        self.assertEqual((), language_candidates_for_path("a.h", ("rust",)))

    def test_directory_with_disabled_language_files_skipped(self) -> None:
        """目录发现：语言未启用的文件被跳过（asm/rust 文件在 c,cpp 扫描下不进清单）。"""
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            (root / "main.c").write_text("int main(void) { return 0; }\n", encoding="utf-8")
            (root / "impl.S").write_text("nop\n", encoding="utf-8")
            (root / "lib.rs").write_text("fn f() {}\n", encoding="utf-8")
            result = CppWorkspaceCollector().collect(make_context(root))
        self.assertEqual(("main.c",), result.value.files)


if __name__ == "__main__":
    unittest.main()
