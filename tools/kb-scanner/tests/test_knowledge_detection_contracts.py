from __future__ import annotations

import hashlib
import json
import unittest
from pathlib import Path

from kb_scanner.core import CompletionStatus
from kb_scanner.knowledge import GeneratedFactsKnowledgeProvider
from tests.unit.knowledge_detection.support import make_context


ROOT = Path(__file__).resolve().parents[1]
RULES = ROOT / "rulesets" / "riscv-porting"


class KnowledgeDetectionContractTests(unittest.TestCase):
    def test_real_manifest_is_explicit_v2_and_preserves_frozen_legacy_hashes(self) -> None:
        payload = json.loads((RULES / "knowledge-manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(set(payload), {"schemaVersion", "catalogId", "version", "sources"})
        self.assertEqual(payload["schemaVersion"], "2.0")
        self.assertNotIn("curatedSets", payload)
        self.assertNotIn("detectors", payload)
        expected = {
            "generated/clang-x86-entries.json": "947e2ad0797248f8179f2bc29f4e7e0591bd7a14801ba819c9e56d31926e9a8c",
            "generated/gcc-x86-entries.json": "22d68c2ab3c8c40c8606451af2e5f493877661d031b6b2f379eff7886c23d191",
            "generated/arm64-collected-entries.json": "4301bb10dd4ae3b035b9eda39caff01be7f52f4daaee55c33efae6917a7554f4",
            "generated/riscv-collected-entries.json": "ec18d767685bfe6956d3c36f848cea7cfcdf7358a4a460bfb22f43f884a75853",
        }
        declared = {item["path"]: item["sha256"] for item in payload["sources"]}
        self.assertEqual({path: declared[path] for path in expected}, expected)
        for path, digest in expected.items():
            self.assertEqual(hashlib.sha256((RULES / path).read_bytes()).hexdigest(), digest)
        self.assertEqual({item["kind"] for item in payload["sources"]}, {"legacy-generated", "generated-v2", "curated-v1", "collected-v1"})
        self.assertNotIn("audit/candidate-decisions.json", json.dumps(payload))

    def test_real_provider_loads_legacy_and_new_kinds_with_provenance(self) -> None:
        result = GeneratedFactsKnowledgeProvider().load(make_context(ROOT, rule_dir=RULES))
        # 全部 manifest 源（含 clang-x86-entries）冻结哈希与仓库内实际字节一致，装载完整。
        self.assertEqual(result.status, CompletionStatus.COMPLETE)
        self.assertTrue({
            "headers", "intrinsics", "builtins", "vectorTypes", "platformMacros", "compilerFlags",
            "assemblyInstructions", "registerNames", "cpuPlatformApis", "targetFeatures", "architectureAliases", "vectorWidthMarkers",
        } <= {item.fact_kind for item in result.value.entries})
        self.assertEqual(len(result.value.sources), 11)
        legacy = [item for item in result.value.sources if item.source_id in {"clang-x86-entries", "gcc-x86-entries"}]
        self.assertTrue(all(item.kind == "generated-json" and "originalSource" in item.metadata for item in legacy))
        add = next(item for item in result.value.entries
                   if item.fact_kind == "assemblyInstructions" and item.canonical_value == "add"
                   and item.architecture_family == "x86")
        # x86 的 add 指令来自 binutils/llvm/clang 多源，全部可溯源。
        self.assertGreaterEqual(len(add.source_ids), 2)

    def test_real_provider_loads_arm64_collected_facts(self) -> None:
        """arm64 语料源以 collected-v1 装载：单源单族、条目带 arm64 族标记。"""
        result = GeneratedFactsKnowledgeProvider().load(make_context(ROOT, rule_dir=RULES))
        self.assertIn("collected-arm64-entries", {item.source_id for item in result.value.sources})
        collected = next(item for item in result.value.sources if item.source_id == "collected-arm64-entries")
        self.assertEqual(collected.kind, "collected-json")
        arm64_entries = [item for item in result.value.entries if item.architecture_family == "arm64"]
        self.assertTrue(arm64_entries)
        # 语义范围只写在新版事实上（与 v1 legacy 种类的既有约定一致）。
        self.assertTrue(all(item.metadata.get("semanticScope") == "arm64-only" for item in arm64_entries
                            if item.fact_kind not in {"headers", "intrinsics", "builtins", "vectorTypes", "platformMacros", "compilerFlags"}))
        # 语料四类映射齐备：指令/宏/头文件/builtin。
        kinds = {item.fact_kind for item in arm64_entries}
        self.assertTrue({"assemblyInstructions", "platformMacros", "headers", "builtins"} <= kinds)
        # 与 x86 条目（如 llvm 指令 add）按族隔离：同规范值不同条目 ID。
        x86_add = next(item for item in result.value.entries
                       if item.architecture_family == "x86" and item.fact_kind == "assemblyInstructions" and item.canonical_value == "add")
        arm64_add = next(item for item in arm64_entries if item.fact_kind == "assemblyInstructions" and item.canonical_value == "add")
        self.assertNotEqual(x86_add.id, arm64_add.id)

    def test_real_provider_loads_riscv_target_macros(self) -> None:
        """riscv 目标族语料以 collected-v1 装载：单源单族、全部 platformMacros、riscv-only 范围。"""
        result = GeneratedFactsKnowledgeProvider().load(make_context(ROOT, rule_dir=RULES))
        self.assertIn("collected-riscv-entries", {item.source_id for item in result.value.sources})
        riscv_entries = [item for item in result.value.entries if item.architecture_family == "riscv"]
        # platformMacros 来自 riscv.macro 语料；intrinsics 来自 rust 语料（riscv 目标族扩展）。
        self.assertEqual({item.fact_kind for item in riscv_entries}, {"platformMacros", "intrinsics"})
        # platformMacros 属 legacy 六类：semanticScope 不进运行时 metadata（与 arm64 语料同约定）。
        self.assertTrue(all("semanticScope" not in item.metadata for item in riscv_entries))
        self.assertGreaterEqual(len(riscv_entries), 200)
        self.assertIn("__riscv_xlen", {item.canonical_value for item in riscv_entries})
        # 检测器注册表不含 riscv 键（目标族不进源族检测链）。
        from kb_scanner.detectors.definitions import DEFINITIONS_BY_FAMILY
        self.assertEqual(set(DEFINITIONS_BY_FAMILY), {"x86", "arm64"})

    def test_behavior_matrix_is_requirement_driven_and_records_curated_gap(self) -> None:
        payload = json.loads((RULES / "behavior-acceptance.json").read_text(encoding="utf-8"))
        allowed = {
            "generated-fact-local", "structural-candidate", "needs-curated-knowledge", "needs-graph",
            "needs-build", "needs-packaging", "needs-classification", "generated-knowledge-local",
            "curated-knowledge-local", "knowledge-backed-candidate",
            "graph-backed", "classification-backed", "external-validation-deferred",
        }
        capabilities = payload["capabilities"]
        self.assertEqual(len({item["id"] for item in capabilities}), len(capabilities))
        self.assertTrue(all(item["status"] in allowed and item["reason"] for item in capabilities))
        self.assertFalse(any(item["status"] == "needs-curated-knowledge" for item in capabilities))
        self.assertTrue(any(item["status"] == "curated-knowledge-local" for item in capabilities))
        self.assertNotIn("legacyRuleId", json.dumps(payload))
        plan = (ROOT / "docs" / "knowledge.md").read_text(encoding="utf-8")
        self.assertIn("curated/x86-migration-semantics.json", plan)
        self.assertIn("accepted entries only", plan)

    def test_new_provider_and_detector_sources_do_not_import_legacy_or_yaml(self) -> None:
        files = [
            ROOT / "kb_scanner" / "knowledge" / "provider.py",
            ROOT / "kb_scanner" / "detectors" / "definitions.py",
            ROOT / "kb_scanner" / "detectors" / "matchers.py",
            ROOT / "kb_scanner" / "detectors" / "local.py",
        ]
        combined = "\n".join(path.read_text(encoding="utf-8") for path in files)
        for forbidden in ("import yaml", "from yaml", "kb_scanner.rules", "..rules", "Legacy", "Finding", "Assessment("):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, combined)

    def test_core_contracts_do_not_depend_on_parsers_validation_or_legacy_models(self) -> None:
        files = [
            ROOT / "kb_scanner" / "core" / "knowledge.py",
            ROOT / "kb_scanner" / "core" / "facts.py",
            ROOT / "kb_scanner" / "core" / "results.py",
            ROOT / "kb_scanner" / "core" / "observations.py",
            ROOT / "kb_scanner" / "core" / "protocols.py",
        ]
        combined = "\n".join(path.read_text(encoding="utf-8") for path in files)
        for forbidden in (
            "pydantic",
            "tree_sitter",
            "import yaml",
            "from yaml",
            "kb_scanner.models",
            "Legacy",
        ):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, combined)


if __name__ == "__main__":
    unittest.main()
