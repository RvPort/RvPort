from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from kb_scanner.core import CompletionStatus
from kb_scanner.knowledge import GeneratedFactsKnowledgeProvider
from kb_scanner.knowledge.identity import curated_entry_id
from tests.unit.knowledge_detection.support import make_context


def fact_payload(*, header: str = "x.h", source: object | None = None) -> dict:
    return {
        "source": source if source is not None else {"name": "compiler"},
        "headers": [header], "intrinsics": ["_mm_x"], "builtins": ["__builtin_x"],
        "vectorTypes": ["__m128"], "platformMacros": ["__X86__"], "compilerFlags": ["-mavx"],
    }


def write_source(root: Path, name: str, payload: object) -> tuple[str, str]:
    path = root / name
    path.parent.mkdir(parents=True, exist_ok=True)
    content = json.dumps(payload, sort_keys=True).encode()
    path.write_bytes(content)
    return name.replace("\\", "/"), hashlib.sha256(content).hexdigest()


def write_manifest(root: Path, sources: list[dict], **extra: object) -> None:
    payload = {"schemaVersion": "1.0", "catalogId": "facts", "version": "1", "generatedSources": sources} | extra
    (root / "knowledge-manifest.json").write_text(json.dumps(payload), encoding="utf-8")


class KnowledgeProviderTests(unittest.TestCase):
    def test_loads_merges_sources_and_is_stable_across_roots(self) -> None:
        identities = []
        for _ in range(2):
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                clang_path, clang_hash = write_source(root, "generated/clang.json", fact_payload(source={"path": "C:/machine/clang"}))
                gcc_path, gcc_hash = write_source(root, "generated/gcc.json", fact_payload(source={"path": "/other/gcc"}))
                write_manifest(root, [
                    {"sourceId": "gcc", "path": gcc_path, "sha256": gcc_hash},
                    {"sourceId": "clang", "path": clang_path, "sha256": clang_hash},
                ])
                result = GeneratedFactsKnowledgeProvider().load(make_context(root, rule_dir=root))
                identities.append(result.value.identity)
                header = next(item for item in result.value.entries if item.fact_kind == "headers")
                self.assertEqual(len(header.source_ids), 2)
                self.assertEqual([item.source_id for item in result.value.sources], ["clang", "gcc"])
                self.assertNotIn(str(root), result.value.identity)
                self.assertEqual(result.status, CompletionStatus.COMPLETE)
        self.assertEqual(identities[0], identities[1])

    def test_bad_source_is_partial_while_valid_source_is_retained(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            path, digest = write_source(root, "good.json", fact_payload())
            write_manifest(root, [
                {"sourceId": "good", "path": path, "sha256": digest},
                {"sourceId": "bad", "path": "missing.json", "sha256": "b" * 64},
            ])
            result = GeneratedFactsKnowledgeProvider().load(make_context(root, rule_dir=root))
        self.assertEqual(result.status, CompletionStatus.PARTIAL)
        self.assertTrue(result.value.entries)
        self.assertEqual(result.diagnostics[0].code, "knowledge.source-unreadable")
        self.assertNotIn(str(root), result.diagnostics[0].message)

    def test_invalid_manifest_path_manifest_and_all_sources_fail(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            context = make_context(root, rule_dir=root)
            escaped = GeneratedFactsKnowledgeProvider("../manifest.json").load(context)
            missing = GeneratedFactsKnowledgeProvider().load(context)
            write_manifest(root, [{"sourceId": "bad", "path": "missing.json", "sha256": "b" * 64}])
            no_sources = GeneratedFactsKnowledgeProvider().load(context)
            (root / "knowledge-manifest.json").write_text("{}", encoding="utf-8")
            invalid = GeneratedFactsKnowledgeProvider().load(context)
        self.assertEqual(escaped.diagnostics[-1].code, "knowledge.manifest-path-escape")
        self.assertEqual(missing.diagnostics[-1].code, "knowledge.invalid-manifest")
        self.assertEqual(no_sources.diagnostics[-1].code, "knowledge.no-valid-sources")
        self.assertEqual(invalid.status, CompletionStatus.FAILED)

    def test_hash_mismatch_and_invalid_source_shape_are_diagnostics(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            path, digest = write_source(root, "bad.json", {"source": {}})
            write_manifest(root, [{"sourceId": "bad", "path": path, "sha256": "b" * 64}])
            mismatch = GeneratedFactsKnowledgeProvider().load(make_context(root, rule_dir=root))
            write_manifest(root, [{"sourceId": "bad", "path": path, "sha256": digest}])
            invalid = GeneratedFactsKnowledgeProvider().load(make_context(root, rule_dir=root))
        self.assertEqual(mismatch.diagnostics[0].code, "knowledge.source-hash-mismatch")
        self.assertEqual(invalid.diagnostics[0].code, "knowledge.source-invalid")

    def test_non_object_manifest_is_rejected_without_a_snapshot(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for payload in ([], None, "manifest"):
                with self.subTest(payload=payload):
                    (root / "knowledge-manifest.json").write_text(json.dumps(payload), encoding="utf-8")
                    result = GeneratedFactsKnowledgeProvider().load(make_context(root, rule_dir=root))
                    self.assertEqual(result.status, CompletionStatus.FAILED)
                    self.assertIsNone(result.value)
                    self.assertEqual(result.diagnostics[-1].code, "knowledge.invalid-manifest")

    def test_source_symlink_cannot_escape_the_rule_pack(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            root = base / "rules"
            root.mkdir()
            _, digest = write_source(base, "outside.json", fact_payload())
            (root / "linked.json").symlink_to(base / "outside.json")
            write_manifest(root, [{"sourceId": "linked", "path": "linked.json", "sha256": digest}])
            result = GeneratedFactsKnowledgeProvider().load(make_context(root, rule_dir=root))
            self.assertEqual(result.status, CompletionStatus.FAILED)
            self.assertIsNone(result.value)
            self.assertEqual([item.code for item in result.diagnostics], [
                "knowledge.source-path-escape", "knowledge.no-valid-sources",
            ])
            self.assertNotIn(str(base), result.diagnostics[0].message)

    def test_invalid_curated_references_identity_and_lint_preserve_valid_sources(self) -> None:
        for failure in ("missing-reference", "reference-digest", "source-identity", "unknown-reference"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                good_path, good_hash = write_source(root, "legacy.json", fact_payload())
                reference_path, reference_hash = write_source(root, "references.json", {
                    "schemaVersion": "1.0", "references": [{
                        "referenceId": "ref", "officialUrl": "https://example.test/ref", "release": "1",
                        "revision": "a" * 40, "logicalLocation": "cpuid.h", "license": "MIT",
                        "collectedAt": "2026-09-07", "sha256": "c" * 64,
                    }],
                })
                curated_path, curated_hash = write_source(root, "curated.json", {
                    "schemaVersion": "1.0", "sourceId": "wrong" if failure == "source-identity" else "curated",
                    "reviewPolicy": "reviewed", "reviewPolicyVersion": "1", "entries": [{
                        "entryId": curated_entry_id("cpuPlatformApis", "x86", "__cpuid"),
                        "factKind": "cpuPlatformApis", "architectureFamily": "x86",
                        "canonicalValue": "__cpuid", "semanticScope": "x86-only",
                        "referenceIds": ["unknown" if failure == "unknown-reference" else "ref"],
                        "reviewStatus": "accepted",
                    }],
                })
                manifest = {
                    "schemaVersion": "2.0", "catalogId": "facts", "version": "2", "sources": [
                        {"sourceId": "legacy", "kind": "legacy-generated", "path": good_path, "sha256": good_hash},
                        {"sourceId": "curated", "kind": "curated-v1", "path": curated_path, "sha256": curated_hash,
                         "referencePath": "missing.json" if failure == "missing-reference" else reference_path,
                         "referenceSha256": "0" * 64 if failure == "reference-digest" else reference_hash},
                    ],
                }
                (root / "knowledge-manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
                result = GeneratedFactsKnowledgeProvider().load(make_context(root, rule_dir=root))
                self.assertEqual(result.status, CompletionStatus.PARTIAL)
                self.assertEqual([source.source_id for source in result.value.sources], ["legacy"])
                self.assertTrue(result.value.entries)
                self.assertFalse(any(entry.canonical_value == "__cpuid" for entry in result.value.entries))
                expected = {"missing-reference": "knowledge.source-unreadable", "reference-digest": "knowledge.source-hash-mismatch"}
                self.assertEqual([item.code for item in result.diagnostics], [expected.get(failure, "knowledge.source-invalid")])
                self.assertNotIn(str(root), result.diagnostics[0].message)

    def test_v2_loads_legacy_generated_new_generated_and_curated_then_merges_provenance(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            legacy_path, legacy_hash = write_source(root, "generated/legacy.json", fact_payload())
            generated_payload = {
                "schemaVersion": "2.0",
                "source": {
                    "sourceId": "generated", "officialUrl": "https://example.test/upstream", "release": "1",
                    "revision": "a" * 40, "license": "MIT", "collectedAt": "2026-09-07",
                    "inputPaths": ["X86.td"], "inputSha256": ["b" * 64],
                },
                "facts": [{
                    "factKind": "cpuPlatformApis", "architectureFamily": "x86", "canonicalValue": "__cpuid",
                    "semanticScope": "x86-only",
                }],
            }
            generated_path, generated_hash = write_source(root, "generated/new.json", generated_payload)
            references_payload = {
                "schemaVersion": "1.0", "references": [{
                    "referenceId": "ref", "officialUrl": "https://example.test/ref", "release": "1",
                    "revision": "a" * 40, "logicalLocation": "cpuid.h", "license": "MIT",
                    "collectedAt": "2026-09-07", "sha256": "c" * 64,
                }],
            }
            reference_path, reference_hash = write_source(root, "curated/references.json", references_payload)
            curated_payload = {
                "schemaVersion": "1.0", "sourceId": "curated", "reviewPolicy": "reviewed", "reviewPolicyVersion": "1",
                "entries": [{
                    "entryId": curated_entry_id("cpuPlatformApis", "x86", "__cpuid"), "factKind": "cpuPlatformApis",
                    "architectureFamily": "x86", "canonicalValue": "__cpuid", "semanticScope": "x86-only",
                    "referenceIds": ["ref"], "reviewStatus": "accepted",
                }],
            }
            curated_path, curated_hash = write_source(root, "curated/data.json", curated_payload)
            manifest_payload = {
                "schemaVersion": "2.0", "catalogId": "facts", "version": "2", "sources": [
                    {"sourceId": "legacy", "kind": "legacy-generated", "path": legacy_path, "sha256": legacy_hash},
                    {"sourceId": "generated", "kind": "generated-v2", "path": generated_path, "sha256": generated_hash},
                    {
                        "sourceId": "curated", "kind": "curated-v1", "path": curated_path, "sha256": curated_hash,
                        "referencePath": reference_path, "referenceSha256": reference_hash,
                    },
                ],
            }
            (root / "knowledge-manifest.json").write_text(json.dumps(manifest_payload), encoding="utf-8")
            result = GeneratedFactsKnowledgeProvider().load(make_context(root, rule_dir=root))
        self.assertEqual(result.status, CompletionStatus.COMPLETE)
        api = next(item for item in result.value.entries if item.fact_kind == "cpuPlatformApis")
        self.assertEqual(len(api.source_ids), 2)
        self.assertEqual(api.metadata["referenceIds"], ("ref",))
        self.assertEqual({item.kind for item in result.value.sources}, {"generated-json", "generated-json-v2", "curated-json"})

    def test_v2_bad_reference_identity_and_generated_order_are_partial_and_sanitized(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            good_path, good_hash = write_source(root, "legacy.json", fact_payload())
            bad_path, bad_hash = write_source(root, "new.json", {
                "schemaVersion": "2.0",
                "source": {
                    "sourceId": "wrong", "officialUrl": "https://example.test", "release": "1", "revision": "a" * 40,
                    "license": "MIT", "collectedAt": "2026-09-07", "inputPaths": ["x"], "inputSha256": ["b" * 64],
                },
                "facts": [
                    {"factKind": "targetFeatures", "architectureFamily": "x86", "canonicalValue": "sse2", "semanticScope": "x86-toolchain"},
                    {"factKind": "targetFeatures", "architectureFamily": "x86", "canonicalValue": "avx", "semanticScope": "x86-toolchain"},
                ],
            })
            manifest = {
                "schemaVersion": "2.0", "catalogId": "facts", "version": "2", "sources": [
                    {"sourceId": "legacy", "kind": "legacy-generated", "path": good_path, "sha256": good_hash},
                    {"sourceId": "new", "kind": "generated-v2", "path": bad_path, "sha256": bad_hash},
                ],
            }
            (root / "knowledge-manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            result = GeneratedFactsKnowledgeProvider().load(make_context(root, rule_dir=root))
        self.assertEqual(result.status, CompletionStatus.PARTIAL)
        self.assertEqual(result.diagnostics[0].code, "knowledge.source-invalid")
        self.assertNotIn(str(root), result.diagnostics[0].message)

    def test_collected_arm64_source_loads_with_family_and_bad_order_degrades(self) -> None:
        """collected-v1 装载：arm64 族透传到条目；乱序/身份不符降级 PARTIAL。"""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            good_payload = {
                "schemaVersion": "1.0",
                "source": {
                    "sourceId": "collected-arm64", "officialUrl": "https://developer.arm.com/documentation/ddi0487/latest",
                    "license": "Arm ARM", "collectedAt": "2026-10-28",
                    "inputFiles": ["collected/arm64/aarch64.opcode"], "inputSha256": ["b" * 64],
                },
                "facts": [
                    {"factKind": "headers", "architectureFamily": "arm64", "canonicalValue": "arm_neon.h",
                     "semanticScope": "arm64-only"},
                    {"factKind": "platformMacros", "architectureFamily": "arm64", "canonicalValue": "__ARM_NEON",
                     "semanticScope": "arm64-only"},
                ],
            }
            good_path, good_hash = write_source(root, "generated/arm64.json", good_payload)
            manifest_payload = {
                "schemaVersion": "2.0", "catalogId": "facts", "version": "2", "sources": [
                    {"sourceId": "collected-arm64", "kind": "collected-v1", "path": good_path,
                     "sha256": good_hash, "collectedFiles": ["collected/arm64/aarch64.opcode"]},
                ],
            }
            (root / "knowledge-manifest.json").write_text(json.dumps(manifest_payload), encoding="utf-8")
            result = GeneratedFactsKnowledgeProvider().load(make_context(root, rule_dir=root))
        self.assertEqual(result.status, CompletionStatus.COMPLETE)
        source = result.value.sources[0]
        self.assertEqual("collected-json", source.kind)
        self.assertIn("officialUrl", source.metadata["upstreamSource"])
        arm64 = [item for item in result.value.entries if item.architecture_family == "arm64"]
        self.assertEqual({"headers", "platformMacros"}, {item.fact_kind for item in arm64})
        # 乱序装载：facts 不按 (factKind, family, value) 排序 → source-invalid。
        # 该坏源是清单里唯一来源，装载整体 FAILED（无有效来源）。
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bad_payload = dict(good_payload)
            bad_payload["facts"] = list(reversed(good_payload["facts"]))
            bad_path, bad_hash = write_source(root, "generated/arm64-bad.json", bad_payload)
            manifest_payload["sources"][0]["path"] = bad_path
            manifest_payload["sources"][0]["sha256"] = bad_hash
            (root / "knowledge-manifest.json").write_text(json.dumps(manifest_payload), encoding="utf-8")
            bad_result = GeneratedFactsKnowledgeProvider().load(make_context(root, rule_dir=root))
        self.assertEqual(bad_result.status, CompletionStatus.FAILED)
        self.assertEqual(bad_result.diagnostics[0].code, "knowledge.source-invalid")


if __name__ == "__main__":
    unittest.main()
