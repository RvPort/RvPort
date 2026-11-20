from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tests.tree_sitter_native_support import (
    GrammarRuntime,
    TreeSitterRuntime,
    classify_process,
    inspect_runtime,
    load_project_fixtures,
    run_scanner_process,
    runtime_payload,
    validate_runtime,
)


class TreeSitterNativeSupportTests(unittest.TestCase):
    def runtime(self, abi: int = 14) -> TreeSitterRuntime:
        return TreeSitterRuntime("0.26.0", 13, 15, (GrammarRuntime("grammar", "1", abi),))

    def test_runtime_metadata_and_validation(self) -> None:
        runtime = inspect_runtime()
        self.assertEqual("0.26.0", runtime.binding_version)
        self.assertEqual((), validate_runtime(runtime))
        payload = runtime_payload(runtime)
        self.assertEqual([13, 15], payload["supportedAbi"])
        self.assertEqual({"tree-sitter-c", "tree-sitter-cpp", "tree-sitter-go",
                          "tree-sitter-java", "tree-sitter-rust", "tree-sitter-python"},
                         {item["package"] for item in payload["grammars"]})
        self.assertTrue(validate_runtime(self.runtime(12)))
        self.assertTrue(validate_runtime(TreeSitterRuntime("x", 16, 15, ())))

    def test_process_classification_distinguishes_native_failures(self) -> None:
        self.assertEqual("normal", classify_process(0))
        self.assertEqual("exit-139", classify_process(139))
        self.assertEqual("signal", classify_process(-11))
        self.assertEqual("nonzero", classify_process(2))
        self.assertEqual("nonzero", classify_process(None))
        self.assertEqual("timeout", classify_process(None, timed_out=True))

    def test_fixture_loader_validates_contract_path_and_digest(self) -> None:
        fixture_root = Path(__file__).resolve().parents[2] / "fixtures" / "tree-sitter-native-crash"
        fixtures = load_project_fixtures(fixture_root)
        self.assertEqual({"cjson", "zlib", "libarchive"}, {item.project_id for item in fixtures})
        self.assertTrue(all(item.fixture_path.is_file() and len(item.commit) == 40 for item in fixtures))
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            (root / "PROVENANCE.json").write_text(json.dumps({"schemaVersion": "bad"}), encoding="utf-8")
            with self.assertRaises(ValueError):
                load_project_fixtures(root)

    def test_fixture_loader_rejects_unsafe_missing_and_corrupt_inputs(self) -> None:
        fixture_root = Path(__file__).resolve().parents[2] / "fixtures" / "tree-sitter-native-crash"
        original = json.loads((fixture_root / "PROVENANCE.json").read_text())
        mutations = (
            (lambda p: p.update(networkRequired=True), "offline"),
            (lambda p: p.update(projects=[]), "cover cjson"),
            (lambda p: p["projects"][0].update(fixturePath="../escape.c"), "escapes"),
            (lambda p: p["projects"][0].update(fixturePath="missing.c"), "does not exist"),
            (lambda p: p["projects"][0].update(fixturePath="bad.c"), "digest mismatch"),
        )
        for mutate, message in mutations:
            with self.subTest(message=message), tempfile.TemporaryDirectory() as raw:
                root = Path(raw)
                payload = json.loads(json.dumps(original))
                mutate(payload)
                (root / "bad.c").write_text("corrupt")
                (root / "PROVENANCE.json").write_text(json.dumps(payload))
                with self.assertRaisesRegex(ValueError, message):
                    load_project_fixtures(root)

    def test_process_runner_builds_scanner_command_and_captures_result(self) -> None:
        completed = subprocess.CompletedProcess(["python"], 0, "out", "err")
        with patch("tests.tree_sitter_native_support.subprocess.run", return_value=completed) as run:
            outcome = run_scanner_process(Path("project"), Path("rules"), Path("output"), schema_version="1.0")
        self.assertEqual(("normal", 0, "out", "err"), (outcome.status, outcome.returncode, outcome.stdout, outcome.stderr))
        command = run.call_args.args[0]
        self.assertIn("--schema-version", command)
        self.assertIn("1.0", command)
        self.assertFalse(run.call_args.kwargs["check"])

    def test_process_runner_converts_timeout_bytes(self) -> None:
        timeout = subprocess.TimeoutExpired(["python"], 1, output=b"partial", stderr=None)
        with patch("tests.tree_sitter_native_support.subprocess.run", side_effect=timeout):
            outcome = run_scanner_process(Path("project"), Path("rules"), Path("output"))
        self.assertEqual("timeout", outcome.status)
        self.assertIsNone(outcome.returncode)
        self.assertEqual("partial", outcome.stdout)
        self.assertEqual("", outcome.stderr)


if __name__ == "__main__":
    unittest.main()
