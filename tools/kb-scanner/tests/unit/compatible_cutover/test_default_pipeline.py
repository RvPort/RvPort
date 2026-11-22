from __future__ import annotations

import argparse
import io
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import Mock, patch

from kb_scanner import cli
from kb_scanner.core import CompletionStatus, Diagnostic, DiagnosticSeverity, KnowledgeSnapshot, PipelineResult, StageResult
from kb_scanner.pipeline.default import create_default_application, create_default_profile
from kb_scanner.pipeline.qualification import create_knowledge_detection_qualification_profile
from kb_scanner.projection import CompatibleReportProjector, VersionedReportProjector


class DefaultPipelineTests(unittest.TestCase):
    def test_default_and_qualification_share_analysis_components_only_projector_differs(self) -> None:
        default = create_default_profile()
        qualification = create_knowledge_detection_qualification_profile()
        for name in ("workspace_collector", "knowledge_provider", "frontend", "normalizer", "analyzer", "detector", "classifier", "assessor"):
            self.assertIs(type(getattr(default, name)), type(getattr(qualification, name)))
        self.assertIsInstance(default.report_projector, VersionedReportProjector)
        self.assertNotIsInstance(qualification.report_projector, CompatibleReportProjector)
        self.assertIsNotNone(create_default_application())

    def test_default_modules_do_not_import_legacy_or_yaml(self) -> None:
        root = Path(__file__).resolve().parents[3] / "kb_scanner"
        for relative in ("cli.py", "pipeline/default.py", "pipeline/qualification.py", "projection/projector.py"):
            source = (root / relative).read_text(encoding="utf-8").lower()
            self.assertNotIn("create_legacy_application", source)
            self.assertNotIn("import yaml", source)

    def test_default_entrypoint_imports_when_yaml_is_unavailable(self) -> None:
        script = """
import importlib.abc
import sys

class RejectYaml(importlib.abc.MetaPathFinder):
    def find_spec(self, fullname, path=None, target=None):
        if fullname == 'yaml' or fullname.startswith('yaml.'):
            raise ImportError('yaml is test-only')
        return None

sys.meta_path.insert(0, RejectYaml())
from kb_scanner.cli import main
from kb_scanner.pipeline.default import create_default_application
assert create_default_application() is not None
assert main(['version']) == 0
assert 'yaml' not in sys.modules
"""
        completed = subprocess.run(
            [sys.executable, "-c", script],
            cwd=Path(__file__).resolve().parents[3],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(0, completed.returncode, completed.stderr)

    def test_run_scan_delegates_to_staged_application_and_returns_exit_code(self) -> None:
        app = Mock()
        app.run.return_value = PipelineResult(False, 2, CompletionStatus.FAILED)
        args = argparse.Namespace(
            input="i", rules="r", output="o", language="c", exclude="", exclude_path=[], format="json",
            context_infer="auto", target_arch="riscv64", include_inactive_branches=False,
            no_comment_hints=False, compile_commands=None, locale="zh-CN",
        )
        with patch.object(cli, "create_default_application", return_value=app):
            self.assertEqual(2, cli.run_scan(args))
        self.assertEqual("i", app.run.call_args.args[0].input_path)

    def test_validate_and_list_use_bundle_knowledge_and_projection_policy(self) -> None:
        descriptor = Mock(rule_pack_id="pack", rule_pack_version="1")
        context = Mock(value=Mock(), diagnostics=())
        provider = Mock(); provider.load.return_value = StageResult(KnowledgeSnapshot("k", "1"))
        policy = Mock(rule_ids=("B", "A"), version=7); policy.validate_completeness.return_value = ()
        output = io.StringIO()
        with patch.object(cli, "load_bundle_descriptor", return_value=descriptor), patch.object(cli, "StagedContextFactory") as factory, patch.object(cli, "GeneratedFactsKnowledgeProvider", return_value=provider), patch.object(cli, "DEFAULT_PROJECTION_POLICY", policy), redirect_stdout(output):
            factory.return_value.build.return_value = context
            self.assertEqual(0, cli.validate_rules(Path("rules")))
            self.assertEqual(0, cli.list_rules(Path("rules")))
        self.assertIn("A\tcode-owned\tversion=7", output.getvalue())
        self.assertNotIn(".yaml", output.getvalue())

    def test_validate_failure_paths_and_version_command(self) -> None:
        output = io.StringIO()
        with patch.object(cli, "load_bundle_descriptor", side_effect=__import__("kb_scanner.bundle", fromlist=["BundleError"]).BundleError("bad")), redirect_stdout(output):
            self.assertEqual(1, cli.validate_rules(Path("bad")))
        context = Mock(value=None, diagnostics=(Diagnostic("bad", "context", DiagnosticSeverity.ERROR, "bad context"),))
        with patch.object(cli, "load_bundle_descriptor", return_value=Mock()), patch.object(cli, "StagedContextFactory") as factory, redirect_stdout(io.StringIO()):
            factory.return_value.build.return_value = context
            self.assertEqual(1, cli.validate_rules(Path("bad")))
        provider = Mock(); provider.load.return_value = StageResult(None, CompletionStatus.UNAVAILABLE)
        with patch.object(cli, "load_bundle_descriptor", return_value=Mock(rule_pack_id="p", rule_pack_version="1")), patch.object(cli, "StagedContextFactory") as factory, patch.object(cli, "GeneratedFactsKnowledgeProvider", return_value=provider), redirect_stdout(io.StringIO()):
            factory.return_value.build.return_value = Mock(value=Mock(), diagnostics=())
            self.assertEqual(1, cli.validate_rules(Path("bad")))
        with redirect_stdout(output): self.assertEqual(0, cli.main(["version"]))

    def test_list_rules_stops_when_validation_fails(self) -> None:
        output = io.StringIO()
        with patch.object(cli, "validate_rules", return_value=1) as validate, redirect_stdout(output):
            self.assertEqual(1, cli.list_rules(Path("broken")))
        validate.assert_called_once_with(Path("broken"))
        self.assertEqual("", output.getvalue())

    def test_main_dispatches_each_command(self) -> None:
        with patch.object(cli, "run_scan", return_value=4) as scan:
            self.assertEqual(4, cli.main(["scan", "--input", "i", "--rules", "r", "--output", "o"]))
            scan.assert_called_once()
        with patch.object(cli, "validate_rules", return_value=5) as validate:
            self.assertEqual(5, cli.main(["validate-rules", "--rules", "r"]))
            validate.assert_called_once_with(Path("r"))
        with patch.object(cli, "list_rules", return_value=6) as listing:
            self.assertEqual(6, cli.main(["list-rules", "--rules", "r"]))
            listing.assert_called_once_with(Path("r"))

    def test_validate_prints_diagnostics_and_rejects_incomplete_policy(self) -> None:
        note = Diagnostic("knowledge.note", "knowledge", DiagnosticSeverity.WARNING, "knowledge warning")
        provider = Mock(); provider.load.return_value = StageResult(KnowledgeSnapshot("k", "1"), CompletionStatus.PARTIAL, (note,))
        policy = Mock(rule_ids=(), version=1); policy.validate_completeness.return_value = ("MISSING",)
        output = io.StringIO()
        with patch.object(cli, "load_bundle_descriptor", return_value=Mock(rule_pack_id="p", rule_pack_version="1")), patch.object(cli, "StagedContextFactory") as factory, patch.object(cli, "GeneratedFactsKnowledgeProvider", return_value=provider), patch.object(cli, "DEFAULT_PROJECTION_POLICY", policy), redirect_stdout(output):
            factory.return_value.build.return_value = Mock(value=Mock(), diagnostics=())
            self.assertEqual(1, cli.validate_rules(Path("rules")))
        self.assertIn("knowledge warning", output.getvalue())
        self.assertIn("MISSING", output.getvalue())


    def test_cli_version_and_memory_budget_branches(self) -> None:
        """CLI 分支补充：version 子命令、内存闸门 disabled/unapplied 注记。"""
        import contextlib
        import io

        with contextlib.redirect_stdout(io.StringIO()) as output:
            exit_code = cli.main(["version"])
        self.assertEqual(0, exit_code)
        self.assertTrue(output.getvalue().strip())

        # 闸门关闭（MIB=0）：run_scan 附加 memory-budget-disabled 警告不改退出码。
        import os
        from types import SimpleNamespace
        import unittest.mock as mock

        original = os.environ.pop("KB_SCANNER_MEMORY_LIMIT_MIB", None)
        try:
            os.environ["KB_SCANNER_MEMORY_LIMIT_MIB"] = "0"
            fake_result = PipelineResult(success=True, exit_code=0, status=CompletionStatus.COMPLETE)
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary) / "in"
                out = Path(temporary) / "out"
                root.mkdir()
                (root / "a.c").write_text("int a;\n", encoding="utf-8")
                with mock.patch.object(cli, "create_default_application") as factory:
                    factory.return_value.run.return_value = fake_result
                    code = cli.run_scan(SimpleNamespace(
                        input=str(root), rules=str(RULES_ROOT) if "RULES_ROOT" in dir() else "rules",
                        output=str(out), format="json", language="c,cpp",
                        source_arch="x86", target_arch="riscv64", schema_version="2.0",
                        compile_commands=None, exclude=".git,build", exclude_path=[],
                        context_infer="off", locale="zh-CN", include_inactive_branches=False,
                        no_comment_hints=False))
        finally:
            if original is not None:
                os.environ["KB_SCANNER_MEMORY_LIMIT_MIB"] = original
            else:
                os.environ.pop("KB_SCANNER_MEMORY_LIMIT_MIB", None)
        self.assertEqual(0, code)

    def test_scan_context_rejects_invalid_schema_and_arch(self) -> None:
        """context/request 校验双分支：非法 schema_version 与非法 source_arch 各自抛错。"""
        from kb_scanner.core import ScanRequest

        with self.assertRaises(ValueError):
            ScanRequest(".", ".", ".", schema_version="3.0")
        with self.assertRaises(ValueError):
            ScanRequest(".", ".", ".", source_arch="sparc")


if __name__ == "__main__":
    unittest.main()
