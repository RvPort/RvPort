from __future__ import annotations

import hashlib
import json
import unittest
from collections import Counter
from pathlib import Path

from kb_scanner.knowledge.production import lint_curated, lint_generated, render_model
from kb_scanner.knowledge.schema import CollectedFactsModel, CuratedFactsModel, DecisionLedgerModel, GeneratedFactsV2Model, ReferenceRegistryModel


ROOT = Path(__file__).resolve().parents[1]
RULES = ROOT / "rulesets" / "riscv-porting"
GENERATED = (
    "generated/binutils-2.42-x86.json",
    "generated/gcc-14.2.0-x86-target.json",
    "generated/llvm-18.1.8-x86.json",
)
LEGACY_HASHES = {
    "generated/clang-x86-entries.json": "947e2ad0797248f8179f2bc29f4e7e0591bd7a14801ba819c9e56d31926e9a8c",
    "generated/gcc-x86-entries.json": "22d68c2ab3c8c40c8606451af2e5f493877661d031b6b2f379eff7886c23d191",
}
COLLECTED_PATH = "generated/arm64-collected-entries.json"
COLLECTED_SHA256 = "4301bb10dd4ae3b035b9eda39caff01be7f52f4daaee55c33efae6917a7554f4"
RISCV_COLLECTED_PATH = "generated/riscv-collected-entries.json"
RISCV_COLLECTED_SHA256 = "ec18d767685bfe6956d3c36f848cea7cfcdf7358a4a460bfb22f43f884a75853"
# 储备数据（不进 manifest、不进运行时）：仅钉哈希防漂移，启用路径待 Rust frontend 立项。
RESERVE_SHA256 = {
    "collected/reserve/rust_arch_functions.json": "7838018318ad9b7c81041af5c3bd35a64e9d55d4a1e4888aa547b50aaa7f8eea",
    "collected/reserve/rust_arch_struct.json": "06ec9843517ba5e47c4015056df5971df00d9561e47bbf3ceda3440526002a84",
}


class KnowledgeProductionContractTests(unittest.TestCase):
    def test_generated_sources_are_strict_canonical_deterministic_and_pinned(self) -> None:
        releases = set()
        counts: Counter[str] = Counter()
        for logical_path in GENERATED:
            path = RULES / logical_path
            model = GeneratedFactsV2Model.model_validate_json(path.read_bytes())
            self.assertEqual(render_model(model), path.read_bytes())
            self.assertEqual(lint_generated(model), ())
            self.assertEqual(len(model.source.revision), 40)
            self.assertNotIn(model.source.revision, {"main", "master", "trunk"})
            releases.add(model.source.release)
            counts.update(item.factKind for item in model.facts)
        self.assertEqual(releases, {"binutils-2_42", "llvmorg-18.1.8", "releases/gcc-14.2.0"})
        self.assertEqual(counts["assemblyInstructions"], 1494)
        self.assertEqual(counts["registerNames"], 612)
        self.assertEqual(counts["targetFeatures"], 284)
        self.assertEqual(counts["architectureAliases"], 171)

    def test_curated_runtime_entries_have_closed_references_and_only_accepted_status(self) -> None:
        registry_path = RULES / "curated" / "source-references.json"
        curated_path = RULES / "curated" / "x86-migration-semantics.json"
        registry = ReferenceRegistryModel.model_validate_json(registry_path.read_bytes())
        curated = CuratedFactsModel.model_validate_json(curated_path.read_bytes())
        self.assertEqual(render_model(registry), registry_path.read_bytes())
        self.assertEqual(render_model(curated), curated_path.read_bytes())
        self.assertEqual(lint_curated(curated, registry), ())
        self.assertTrue(all(item.reviewStatus == "accepted" for item in curated.entries))
        self.assertEqual(Counter(item.factKind for item in curated.entries), {"cpuPlatformApis": 6, "vectorWidthMarkers": 3})

    def test_decision_ledger_is_non_runtime_and_keeps_rejected_deferred_reasons(self) -> None:
        path = RULES / "audit" / "candidate-decisions.json"
        ledger = DecisionLedgerModel.model_validate_json(path.read_bytes())
        self.assertEqual(render_model(ledger), path.read_bytes())
        statuses = Counter(item.status for item in ledger.decisions)
        self.assertEqual(statuses, {"accepted": 2, "generated": 1, "rejected": 2, "deferred": 1})
        self.assertTrue(all(item.rationale for item in ledger.decisions))
        manifest_text = (RULES / "knowledge-manifest.json").read_text(encoding="utf-8")
        self.assertNotIn("candidate-decisions", manifest_text)
        source_ids = {item["sourceId"] for item in json.loads(manifest_text)["sources"]}
        reference_ids = {
            item.referenceId
            for item in ReferenceRegistryModel.model_validate_json(
                (RULES / "curated" / "source-references.json").read_bytes()
            ).references
        }
        self.assertTrue(all(set(item.referenceIds) <= source_ids | reference_ids for item in ledger.decisions))

    def test_candidate_inventory_is_non_authoritative_and_excludes_os_domains(self) -> None:
        payload = json.loads((RULES / "audit" / "candidate-inventory.json").read_text(encoding="utf-8"))
        self.assertEqual(payload["authority"], "candidate-discovery-only")
        self.assertEqual(len(payload["categories"]), 6)
        self.assertTrue({"operating-system", "kernel", "system-call", "driver", "board"} <= set(payload["excludedDomains"]))

    def test_legacy_compiler_facts_remain_byte_identical(self) -> None:
        for logical_path, digest in LEGACY_HASHES.items():
            with self.subTest(path=logical_path):
                self.assertEqual(hashlib.sha256((RULES / logical_path).read_bytes()).hexdigest(), digest)

    def test_collected_arm64_source_is_canonical_sorted_and_reproducible(self) -> None:
        """arm64 语料产物：collected-v1 契约、确定性渲染、单源单族、计数与转换脚本一致。"""
        path = RULES / COLLECTED_PATH
        self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), COLLECTED_SHA256)
        model = CollectedFactsModel.model_validate_json(path.read_bytes())
        self.assertEqual(render_model(model), path.read_bytes())
        # 复用 v2 lint：事实按 (factKind, family, value) 排序且值已规范化。
        from kb_scanner.knowledge.production import lint_generated as lint_v2
        from kb_scanner.knowledge.provider import _collected_as_v2
        self.assertEqual(lint_v2(_collected_as_v2(model)), ())
        self.assertEqual({item.architectureFamily for item in model.facts}, {"arm64"})
        counts = Counter(item.factKind for item in model.facts)
        self.assertEqual(counts["assemblyInstructions"], 2161)
        self.assertEqual(counts["platformMacros"], 121)
        self.assertEqual(counts["headers"], 16)
        self.assertEqual(counts["builtins"], 8)
        # 语料原件登记齐备且哈希与规则包内文件一致。
        self.assertEqual(len(model.source.inputFiles), len(model.source.inputSha256))
        for name, digest in zip(model.source.inputFiles, model.source.inputSha256):
            self.assertEqual(hashlib.sha256((RULES / name).read_bytes()).hexdigest(), digest)
        # 转换脚本可再生成：collect() 与落盘产物逐字节一致。
        from tools.collect_aarch64_entries import collect
        regenerated, _ = collect(RULES)
        self.assertEqual(render_model(regenerated), path.read_bytes())

    def test_collected_riscv_source_is_canonical_sorted_and_reproducible(self) -> None:
        """riscv 目标族语料产物：collected-v1 契约、确定性渲染、单源单族=riscv、目标侧语义范围。"""
        path = RULES / RISCV_COLLECTED_PATH
        self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), RISCV_COLLECTED_SHA256)
        model = CollectedFactsModel.model_validate_json(path.read_bytes())
        self.assertEqual(render_model(model), path.read_bytes())
        from kb_scanner.knowledge.production import lint_generated as lint_v2
        from kb_scanner.knowledge.provider import _collected_as_v2
        self.assertEqual(lint_v2(_collected_as_v2(model)), ())
        # 单源单族：全部条目为 riscv 目标族、riscv-only 语义范围。
        self.assertEqual({item.architectureFamily for item in model.facts}, {"riscv"})
        self.assertTrue(all(item.semanticScope == "riscv-only" for item in model.facts))
        counts = Counter(item.factKind for item in model.facts)
        self.assertEqual(counts["platformMacros"], 203)
        self.assertEqual(len(counts), 1)
        for name, digest in zip(model.source.inputFiles, model.source.inputSha256):
            self.assertEqual(hashlib.sha256((RULES / name).read_bytes()).hexdigest(), digest)
        # 转换脚本可再生成：collect() 与落盘产物逐字节一致。
        from tools.collect_riscv_entries import collect
        regenerated, _ = collect(RULES)
        self.assertEqual(render_model(regenerated), path.read_bytes())

    def test_reserve_data_is_pinned_and_not_in_runtime_manifest(self) -> None:
        """储备语料：哈希钉死防漂移；不进 knowledge-manifest（不装载、不投影）。"""
        manifest_text = (RULES / "knowledge-manifest.json").read_text(encoding="utf-8")
        self.assertNotIn("reserve", manifest_text)
        for name, digest in RESERVE_SHA256.items():
            self.assertEqual(hashlib.sha256((RULES / name).read_bytes()).hexdigest(), digest, name)

    def test_runtime_data_contains_no_execution_or_classification_recipe(self) -> None:
        forbidden = {"regex", "operator", "pattern", "matcher", "severity", "blocking", "reason", "suggestion", "message", "readiness"}
        for logical_path in (*GENERATED, "curated/x86-migration-semantics.json"):
            payload = json.loads((RULES / logical_path).read_text(encoding="utf-8"))
            keys: set[str] = set()

            def visit(value: object) -> None:
                if isinstance(value, dict):
                    keys.update(value)
                    for item in value.values():
                        visit(item)
                elif isinstance(value, list):
                    for item in value:
                        visit(item)

            visit(payload)
            self.assertFalse(keys & forbidden, logical_path)


if __name__ == "__main__":
    unittest.main()
