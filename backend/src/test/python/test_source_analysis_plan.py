import importlib.util
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).parents[2] / "main/resources/analysis/source_analysis_plan.py"
SPEC = importlib.util.spec_from_file_location("source_analysis_plan", SCRIPT)
PLAN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PLAN)


class SourceAnalysisPlanTests(unittest.TestCase):
    def test_language_canonicalization_and_invalid_values(self):
        self.assertEqual(PLAN.languages(" CPP,c,c "), ["c", "cpp"])
        self.assertEqual(PLAN.languages(None), ["c", "cpp"])
        for value in ("unknown", "c,", " ", ","):
            with self.assertRaisesRegex(ValueError, "LANGUAGE_UNSUPPORTED"):
                PLAN.languages(value)

    def test_paths_must_be_relative_and_canonical(self):
        self.assertEqual(PLAN.relative_path("src\\sub"), "src/sub")
        for value in ("", "/etc", "C:/x", "../a", "a/../b", "a//b", "./a", "a\nb", "a\x7fb"):
            with self.assertRaisesRegex(ValueError, "SOURCE_INPUT_INVALID"):
                PLAN.relative_path(value)

    def test_unsafe_real_filenames_are_rejected_before_manifest_publication(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for name in ("bad:name.c", "bad\nname.c", "bad\x7fname.c"):
                path = root / name
                path.write_text("int a;")
                with self.assertRaisesRegex(ValueError, "SOURCE_INPUT_INVALID"):
                    PLAN.discover(root)
                path.unlink()

    def test_multilanguage_budget_scope_and_semantic_digest(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for name in ("a.c", "b.C", "c.rs", "d.py", "e.h", "f.cpp"):
                (root / name).write_text("sample", encoding="utf-8")
            (root / "vendor").mkdir()
            (root / "vendor/g.c").write_text("int ignored;")
            plan = PLAN.discover(root, "c,cpp,rust", excludes=("vendor",), max_files=2)
            self.assertEqual([f["status"] for f in plan["files"]],
                             ["PLANNED", "PLANNED", "OMITTED", "NOT_SELECTED", "UNSUPPORTED", "OMITTED", "EXCLUDED"])
            self.assertEqual(len(plan["translationUnits"]), 2)
            self.assertIn("-std=c++17", plan["translationUnits"][1]["arguments"])
            self.assertFalse(plan["denominatorKnown"])
            same = PLAN.discover(root, "rust,CPP,c,c", excludes=("vendor", "vendor"), max_files=2)
            self.assertEqual(plan["inputDigest"], same["inputDigest"])
            (root / ".rvport-analysis-cache").mkdir()
            (root / ".rvport-analysis-cache/temp.c").write_text("temporary output")
            self.assertEqual(plan["inputDigest"], PLAN.discover(root, "c,cpp,rust", excludes=("vendor",), max_files=2)["inputDigest"])
            self.assertNotEqual(plan["inputDigest"], PLAN.discover(root)["inputDigest"])
            (root / "a.c").write_text("changed")
            self.assertNotEqual(plan["inputDigest"], PLAN.discover(root, "c,cpp,rust", excludes=("vendor",), max_files=2)["inputDigest"])

    def test_no_fake_units_for_non_c_and_empty_source(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.assertEqual(PLAN.discover(root)["translationUnits"], [])
            (root / "a.rs").write_text("fn main() {}")
            plan = PLAN.discover(root, "rust")
            self.assertEqual(plan["files"][0]["status"], "PLANNED")
            self.assertEqual(plan["translationUnits"], [])

    def test_root_symlink_and_file_symlink_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            with self.assertRaises(ValueError):
                PLAN.discover(root / "missing")
            with self.assertRaises(ValueError):
                PLAN.discover(root, max_files=0)
            (root / "source.c").write_text("int x;")
            (root / "alias.c").symlink_to(root / "source.c")
            with self.assertRaises(ValueError):
                PLAN.discover(root)

    def test_manifest_and_content_budgets_and_special_files_are_bounded(self):
        import os
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "a.c").write_text("int a;")
            with self.assertRaisesRegex(ValueError, "MANIFEST_LIMIT"):
                PLAN.discover(root, max_entries=0)
            with self.assertRaisesRegex(ValueError, "BYTE_LIMIT"):
                PLAN.discover(root, max_bytes=2)
            (root / "link").symlink_to(root, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, "SOURCE_INPUT_INVALID"):
                PLAN.discover(root)
            (root / "link").unlink()
            os.mkfifo(root / "pipe")
            with self.assertRaisesRegex(ValueError, "SOURCE_INPUT_INVALID"):
                PLAN.discover(root)
