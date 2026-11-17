from __future__ import annotations

import json
import unittest

from pydantic import ValidationError

from kb_scanner.knowledge.schema import (
    CandidateDecisionModel, CuratedFactsModel, DecisionLedgerModel, GeneratedFactsModel, GeneratedFactsV2Model,
    KnowledgeManifestModel, KnowledgeManifestV2Model, ReferenceRegistryModel, SourceDescriptorModel, _validate_json_value,
)
from kb_scanner.knowledge.schema import GeneratedFactV2Model


HASH = "a" * 64


def manifest() -> dict:
    return {
        "schemaVersion": "1.0",
        "catalogId": "facts",
        "version": "1",
        "generatedSources": [{"sourceId": "clang", "path": "generated/clang.json", "sha256": HASH}],
    }


def facts() -> dict:
    return {
        "source": {"name": "compiler", "links": ["https://example.test"]},
        "headers": ["x.h"], "intrinsics": ["_mm_x"], "builtins": ["__builtin_x"],
        "vectorTypes": ["__m128"], "platformMacros": ["__X86__"], "compilerFlags": ["-mavx"],
    }


class KnowledgeSchemaTests(unittest.TestCase):
    def test_valid_manifest_and_generated_facts_are_immutable(self) -> None:
        model = KnowledgeManifestModel.model_validate_json(json.dumps(manifest()))
        generated = GeneratedFactsModel.model_validate_json(json.dumps(facts()))
        self.assertEqual(model.generatedSources[0].path, "generated/clang.json")
        self.assertEqual(generated.headers, ("x.h",))
        windows = manifest()
        windows["generatedSources"][0]["path"] = "generated\\clang.json"
        self.assertEqual(KnowledgeManifestModel.model_validate_json(json.dumps(windows)).generatedSources[0].path, "generated/clang.json")
        with self.assertRaises(ValidationError):
            KnowledgeManifestModel.model_validate(manifest() | {"extra": True})

    def test_manifest_rejects_curated_recipes_missing_identity_bad_paths_hashes_and_duplicates(self) -> None:
        cases = [
            manifest() | {"curatedSets": []}, manifest() | {"detectors": []},
            {key: value for key, value in manifest().items() if key != "catalogId"},
            manifest() | {"schemaVersion": "2.0"},
            manifest() | {"generatedSources": [{"sourceId": "x", "path": "../x.json", "sha256": HASH}]},
            manifest() | {"generatedSources": [{"sourceId": "x", "path": "/x.json", "sha256": HASH}]},
            manifest() | {"generatedSources": [{"sourceId": "x", "path": "x.yaml", "sha256": HASH}]},
            manifest() | {"generatedSources": [{"sourceId": "x", "path": "x.json", "sha256": "A" * 64}]},
            manifest() | {"generatedSources": []},
            manifest() | {"generatedSources": [manifest()["generatedSources"][0]] * 2},
            manifest() | {"generatedSources": [
                manifest()["generatedSources"][0],
                {"sourceId": "other", "path": "generated/clang.json", "sha256": HASH},
            ]},
        ]
        for payload in cases:
            with self.subTest(payload=payload), self.assertRaises(ValidationError):
                KnowledgeManifestModel.model_validate_json(json.dumps(payload))

    def test_generated_facts_reject_extra_non_string_empty_and_non_json_source(self) -> None:
        json_cases = [
            facts() | {"unknown": []},
            facts() | {"headers": [1]},
            facts() | {"headers": [" "]},
        ]
        for payload in json_cases:
            with self.subTest(payload=payload), self.assertRaises(ValidationError):
                GeneratedFactsModel.model_validate_json(json.dumps(payload))
        direct = {
            key: tuple(value) if isinstance(value, list) else value
            for key, value in facts().items()
        }
        direct["source"] = {"bad": object()}
        with self.assertRaises(ValidationError):
            GeneratedFactsModel.model_validate(direct)

    def test_json_value_validator_covers_scalars_lists_dicts_and_invalid_values(self) -> None:
        for value in (None, True, 1, 1.5, "x", [], {}, [1, {"ok": True}], {"nested": ["x"]}):
            _validate_json_value(value)
        with self.assertRaises(ValueError):
            _validate_json_value({1: "bad"})

    def test_v2_manifest_accepts_explicit_runtime_kinds_and_rejects_audit_sources(self) -> None:
        payload = {
            "schemaVersion": "2.0", "catalogId": "facts", "version": "2",
            "sources": [
                {"sourceId": "old", "kind": "legacy-generated", "path": "generated/old.json", "sha256": HASH},
                {
                    "sourceId": "curated", "kind": "curated-v1", "path": "curated/data.json", "sha256": HASH,
                    "referencePath": "curated/references.json", "referenceSha256": "b" * 64,
                },
            ],
        }
        self.assertEqual(len(KnowledgeManifestV2Model.model_validate_json(json.dumps(payload)).sources), 2)
        for invalid in (
            payload | {"sources": [payload["sources"][0] | {"kind": "decision-ledger"}]},
            payload | {"sources": [payload["sources"][0]] * 2},
            payload | {"sources": [payload["sources"][1] | {"referencePath": "curated/data.json"}]},
        ):
            with self.subTest(invalid=invalid), self.assertRaises(ValidationError):
                KnowledgeManifestV2Model.model_validate_json(json.dumps(invalid))

    def test_source_descriptor_requires_fixed_revision_relative_inputs_and_complete_provenance(self) -> None:
        valid = {
            "sourceId": "llvm", "officialUrl": "https://github.com/llvm/llvm-project", "release": "18.1.8",
            "revision": "a" * 40, "license": "Apache-2.0", "collectedAt": "2026-09-07",
            "inputPaths": ["llvm/lib/Target/X86/X86.td"], "inputSha256": [HASH],
        }
        self.assertEqual(SourceDescriptorModel.model_validate_json(json.dumps(valid)).sourceId, "llvm")
        for invalid in (
            valid | {"revision": "main"}, valid | {"inputPaths": ["C:/checkout/X86.td"]},
            valid | {"inputPaths": ["../X86.td"]}, valid | {"inputSha256": []},
            valid | {"inputPaths": ["a", "b"]}, valid | {"collectedAt": "today"}, valid | {"extra": True},
        ):
            with self.subTest(invalid=invalid), self.assertRaises(ValidationError):
                SourceDescriptorModel.model_validate_json(json.dumps(invalid))

    def test_runtime_source_schemas_forbid_recipes_duplicates_unaccepted_and_missing_references(self) -> None:
        source = {
            "sourceId": "llvm", "officialUrl": "https://github.com/llvm/llvm-project", "release": "18.1.8",
            "revision": "a" * 40, "license": "Apache-2.0", "collectedAt": "2026-09-07",
            "inputPaths": ["X86.td"], "inputSha256": [HASH],
        }
        fact = {"factKind": "targetFeatures", "architectureFamily": "x86", "canonicalValue": "avx2", "semanticScope": "x86-toolchain"}
        valid_generated = {"schemaVersion": "2.0", "source": source, "facts": [fact]}
        self.assertEqual(len(GeneratedFactsV2Model.model_validate_json(json.dumps(valid_generated)).facts), 1)
        for invalid in (valid_generated | {"facts": [fact | {"regex": "avx.*"}]}, valid_generated | {"facts": [fact, fact]}):
            with self.subTest(invalid=invalid), self.assertRaises(ValidationError):
                GeneratedFactsV2Model.model_validate_json(json.dumps(invalid))

        entry = {
            "entryId": "entry", "factKind": "cpuPlatformApis", "architectureFamily": "x86", "canonicalValue": "__cpuid",
            "semanticScope": "x86-only", "referenceIds": ["ref"], "reviewStatus": "accepted",
        }
        curated_payload = {
            "schemaVersion": "1.0", "sourceId": "curated", "reviewPolicy": "reviewed", "reviewPolicyVersion": "1", "entries": [entry],
        }
        self.assertEqual(len(CuratedFactsModel.model_validate_json(json.dumps(curated_payload)).entries), 1)
        for invalid_entry in (entry | {"reviewStatus": "deferred"}, entry | {"referenceIds": []}, entry | {"severity": "high"}):
            with self.assertRaises(ValidationError):
                CuratedFactsModel.model_validate_json(json.dumps(curated_payload | {"entries": [invalid_entry]}))

    def test_reference_registry_and_decision_ledger_are_strict_and_separate(self) -> None:
        reference = {
            "referenceId": "ref", "officialUrl": "https://gcc.gnu.org/git/gcc.git", "release": "14.2.0",
            "revision": "a" * 40, "logicalLocation": "gcc/config/i386/cpuid.h", "license": "GPL-3.0",
            "collectedAt": "2026-09-07", "sha256": HASH,
        }
        self.assertEqual(len(ReferenceRegistryModel.model_validate_json(json.dumps({"schemaVersion": "1.0", "references": [reference]})).references), 1)
        with self.assertRaises(ValidationError):
            ReferenceRegistryModel.model_validate_json(json.dumps({"schemaVersion": "1.0", "references": [reference, reference]}))
        generated = {
            "decisionId": "d", "category": "target-feature", "candidateValue": "avx2", "status": "generated",
            "referenceIds": ["ref"], "rationale": "upstream definition", "runtimeFactKind": "targetFeatures",
        }
        ledger = DecisionLedgerModel.model_validate_json(json.dumps(
            {"schemaVersion": "1.0", "scope": "c-cpp-x86-to-riscv-static-analysis", "decisions": [generated]}
        ))
        self.assertEqual(ledger.decisions[0].status, "generated")
        for invalid in (
            generated | {"referenceIds": []}, generated | {"status": "rejected"},
            generated | {"referenceIds": ["ref", "ref"]},
        ):
            with self.assertRaises(ValidationError):
                CandidateDecisionModel.model_validate_json(json.dumps(invalid))

    def test_arm64_families_and_scopes_are_accepted_per_model_kind(self) -> None:
        """架构族放宽后：arm64 + arm64-only/arm64-toolchain 在 v2/curated 事实合法。"""
        fact = {"factKind": "platformMacros", "architectureFamily": "arm64",
                "canonicalValue": "__ARM_NEON", "semanticScope": "arm64-only"}
        model = GeneratedFactsV2Model.model_validate_json(json.dumps({
            "schemaVersion": "2.0",
            "source": {
                "sourceId": "s", "officialUrl": "https://example.test", "release": "r",
                "revision": "a" * 40, "license": "MIT", "collectedAt": "2026-10-28",
                "inputPaths": ["a"], "inputSha256": [HASH],
            },
            "facts": [fact, fact | {"canonicalValue": "__ARM_FEATURE_SVE", "semanticScope": "arm64-toolchain"}],
        }))
        self.assertEqual(("arm64", "arm64"), tuple(item.architectureFamily for item in model.facts))
        entry = {
            "entryId": "e", "factKind": "cpuPlatformApis", "architectureFamily": "arm64",
            "canonicalValue": "__builtin_aarch64_get_fpcr", "semanticScope": "arm64-only",
            "referenceIds": ["ref"], "reviewStatus": "accepted",
        }
        curated = CuratedFactsModel.model_validate_json(json.dumps({
            "schemaVersion": "1.0", "sourceId": "c", "reviewPolicy": "reviewed",
            "reviewPolicyVersion": "1", "entries": [entry],
        }))
        self.assertEqual("arm64", curated.entries[0].architectureFamily)
        # 未知族仍然拒绝（单条事实模型）。
        from kb_scanner.knowledge.schema import GeneratedFactV2Model
        for bad in (fact | {"architectureFamily": "sparc"}, fact | {"semanticScope": "sparc-only"}):
            with self.subTest(bad=bad), self.assertRaises(ValidationError):
                GeneratedFactV2Model.model_validate_json(json.dumps(bad))

    def test_collected_payload_requires_single_family_and_unique_files(self) -> None:
        """collected-v1 载荷：无 revision 溯源、单源单族、语料文件一一对应。"""
        from kb_scanner.knowledge.schema import CollectedFactsModel, CollectedManifestSourceModel

        valid = {
            "schemaVersion": "1.0",
            "source": {
                "sourceId": "collected-arm64", "officialUrl": "https://developer.arm.com/documentation/ddi0487/latest",
                "license": "Arm ARM", "collectedAt": "2026-10-28",
                "inputFiles": ["collected/arm64/aarch64.opcode"], "inputSha256": [HASH],
            },
            "facts": [{"factKind": "assemblyInstructions", "architectureFamily": "arm64",
                       "canonicalValue": "abs", "semanticScope": "arm64-only"}],
        }
        model = CollectedFactsModel.model_validate_json(json.dumps(valid))
        self.assertEqual(1, len(model.facts))
        for invalid in (
            # 多族混装被拒（单源单族不变量）。
            valid | {"facts": valid["facts"] + [valid["facts"][0] | {"architectureFamily": "x86"}]},
            # 事实重复被拒。
            valid | {"facts": [valid["facts"][0], valid["facts"][0]]},
            # 文件与摘要数量不匹配。
            valid | {"source": valid["source"] | {"inputSha256": []}},
            # 语料文件名重复。
            valid | {"source": valid["source"] | {"inputFiles": ["a", "a"], "inputSha256": [HASH, HASH]}},
            # 路径越界。
            valid | {"source": valid["source"] | {"inputFiles": ["../escape"]}},
        ):
            with self.subTest(invalid=invalid), self.assertRaises(ValidationError):
                CollectedFactsModel.model_validate_json(json.dumps(invalid))
        # 清单条目：collected-v1 需要 collectedFiles，至少一个。
        manifest_entry = CollectedManifestSourceModel.model_validate_json(json.dumps({
            "sourceId": "collected-arm64", "kind": "collected-v1",
            "path": "generated/arm64.json", "sha256": HASH,
            "collectedFiles": ["collected/arm64/aarch64.opcode"],
        }))
        self.assertEqual("collected-v1", manifest_entry.kind)
        with self.assertRaises(ValidationError):
            CollectedManifestSourceModel.model_validate_json(json.dumps({
                "sourceId": "collected-arm64", "kind": "collected-v1",
                "path": "generated/arm64.json", "sha256": HASH, "collectedFiles": [],
            }))

    def test_riscv_target_family_and_scope_are_accepted(self) -> None:
        """riscv 目标族：riscv + riscv-only 在 v2/curated/collected 事实合法；未知族仍拒绝。"""
        fact = {"factKind": "platformMacros", "architectureFamily": "riscv",
                "canonicalValue": "__riscv_xlen", "semanticScope": "riscv-only"}
        model = GeneratedFactsV2Model.model_validate_json(json.dumps({
            "schemaVersion": "2.0",
            "source": {
                "sourceId": "s", "officialUrl": "https://example.test", "release": "r",
                "revision": "a" * 40, "license": "MIT", "collectedAt": "2026-11-12",
                "inputPaths": ["a"], "inputSha256": [HASH],
            },
            "facts": [fact],
        }))
        self.assertEqual("riscv", model.facts[0].architectureFamily)
        from kb_scanner.knowledge.schema import CollectedFactsModel
        collected = CollectedFactsModel.model_validate_json(json.dumps({
            "schemaVersion": "1.0",
            "source": {
                "sourceId": "collected-riscv", "officialUrl": "https://github.com/riscv-non-isa/riscv-c-api-doc",
                "license": "Apache-2.0", "collectedAt": "2026-11-12",
                "inputFiles": ["collected/riscv/riscv.macro"], "inputSha256": [HASH],
            },
            "facts": [fact],
        }))
        self.assertEqual("riscv", collected.facts[0].architectureFamily)
        # 未知族/未知语义范围仍被拒（单条事实模型）。
        for bad in (fact | {"semanticScope": "sparc-only"}, fact | {"architectureFamily": "sparc"}):
            with self.subTest(bad=bad), self.assertRaises(ValidationError):
                GeneratedFactV2Model.model_validate_json(json.dumps(bad))


if __name__ == "__main__":
    unittest.main()
