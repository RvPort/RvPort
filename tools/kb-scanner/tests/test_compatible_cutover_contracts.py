from __future__ import annotations

import hashlib
import json
import unittest
from pathlib import Path

from kb_scanner.compatibility.cleanup import validate_cleanup_inventory
from kb_scanner.compatibility.differential import validate_ledger
from kb_scanner.projection.contract import FINDING_FIELDS, SUMMARY_FIELDS, TOP_LEVEL_FIELDS


ROOT = Path(__file__).resolve().parents[1]
RULES = ROOT / "rulesets/riscv-porting"
FIXTURES = ROOT / "tests/fixtures/compatible-cutover"


class CompatibleCutoverContractTests(unittest.TestCase):
    def test_output_contract_and_bundle_descriptor_are_locked(self) -> None:
        baseline = json.loads((FIXTURES / "output-contract-baseline.json").read_text())
        self.assertEqual(list(TOP_LEVEL_FIELDS), baseline["topLevelFields"])
        self.assertEqual(set(FINDING_FIELDS), set(baseline["findingFields"]))
        self.assertEqual(set(SUMMARY_FIELDS), set(baseline["summaryFields"]))
        scanner = json.loads((RULES / "scanner-manifest.json").read_text())
        knowledge = (RULES / scanner["knowledgeManifest"]["path"]).read_bytes()
        self.assertEqual(hashlib.sha256(knowledge).hexdigest(), scanner["knowledgeManifest"]["sha256"])
        self.assertEqual("kb-scanner.compatible-projection", scanner["projectionPolicy"]["id"])

    def test_original_compiler_facts_and_legacy_yaml_inventory_are_unchanged(self) -> None:
        baseline = json.loads((FIXTURES / "fact-asset-baseline.json").read_text())
        current = json.loads((ROOT / "tests/fixtures/open-source-regression-baseline.json").read_text())
        baseline["facts"] = {item["path"].removeprefix("rulesets/riscv-porting/"): item
                             for item in current["compilerFactFiles"]}
        for relative, expected in baseline["facts"].items():
            content = (RULES / relative).read_bytes()
            payload = json.loads(content)
            self.assertEqual(expected["bytes"], len(content))
            self.assertEqual(expected["sha256"], hashlib.sha256(content).hexdigest())
            # legacy 词表是顶层列表字段（计 len）；collected 语料是 facts 数组（按 factKind 计数）。
            if "facts" in payload and isinstance(payload["facts"], list):
                actual_counts = {}
                for item in payload["facts"]:
                    actual_counts[item["factKind"]] = actual_counts.get(item["factKind"], 0) + 1
            else:
                actual_counts = {name: len(payload[name]) for name in payload
                                 if isinstance(payload.get(name), list)}
            self.assertEqual(expected["counts"], {name: actual_counts[name] for name in expected["counts"]})
        yaml_files = tuple((RULES / "rules").rglob("*.yaml"))
        self.assertEqual(baseline["legacyYaml"]["files"], len(yaml_files))
        self.assertEqual(baseline["legacyYaml"]["generatedSetReferences"], sum(path.read_text().count("generatedSet:") for path in yaml_files))

    def test_ledgers_inventories_and_import_boundaries_are_valid(self) -> None:
        self.assertEqual((), validate_ledger(json.loads((FIXTURES / "differential-ledger.json").read_text())))
        self.assertEqual((), validate_cleanup_inventory(json.loads((FIXTURES / "cleanup-inventory.json").read_text())))
        package = ROOT / "kb_scanner"
        for relative in ("cli.py", "pipeline/default.py", "pipeline/qualification.py", "projection/projector.py"):
            source = (package / relative).read_text().lower()
            for forbidden in ("adapters.legacy", "import yaml", "kb_scanner.rules", "kb_scanner.report", "kb_scanner.technology"):
                self.assertNotIn(forbidden, source, relative)

    def test_inventory_assigns_unit_and_higher_layer_coverage_to_every_changed_module(self) -> None:
        inventory = json.loads((ROOT / "tests/compatible-cutover-test-inventory.json").read_text())
        self.assertEqual({"linePercent": 90.0, "branchPercent": 90.0}, inventory["coverageThresholds"])
        for module, mapping in inventory["modules"].items():
            self.assertTrue((ROOT / "kb_scanner" / module).is_file(), module)
            self.assertTrue((ROOT / mapping["unit"]).is_file(), mapping["unit"])
            self.assertTrue(all((ROOT / path).is_file() for path in mapping["higherLayers"]), module)


if __name__ == "__main__":
    unittest.main()
