from __future__ import annotations

import unittest
from dataclasses import replace
from pathlib import Path

from kb_scanner.analyzers import BuildProofBudgets, BuildSelectionAnalyzer
from kb_scanner.core import (
    AnalysisSnapshot,
    CompilationUnitContext,
    CompletionStatus,
    Entity,
    Evidence,
    FrontendSnapshot,
    NormalizedFact,
    NormalizedSnapshot,
    Relation,
    SourceLocation,
    SourceSpan,
    stable_id,
)
from kb_scanner.detectors import BuildSelectionDetector
from kb_scanner.frontends.build import BuildFrontend, ProjectWorkspaceCollector
from kb_scanner.normalization import BuildEvidenceNormalizer
from tests.unit.build_selection.support import context, flag_knowledge


class AnalysisTests(unittest.TestCase):
    def setUp(self) -> None:
        self.context = context()
        workspace = ProjectWorkspaceCollector().collect(self.context).value
        self.frontend = BuildFrontend().analyze(self.context, workspace).value
        self.normalized = BuildEvidenceNormalizer().normalize(self.context, self.frontend).value

    def test_normalizer_preserves_build_provenance_and_stable_facts(self) -> None:
        result = BuildEvidenceNormalizer().normalize(self.context, self.frontend)
        self.assertIn(result.status, {CompletionStatus.COMPLETE, CompletionStatus.PARTIAL})
        self.assertTrue(result.value.facts)
        fact = next(item for item in result.value.facts if item.kind == "build_argument" and item.canonical_value == "-mavx")
        self.assertEqual(fact.metadata["selectionStatus"], "selected")
        self.assertTrue(fact.evidence_ids and fact.producers)
        again = BuildEvidenceNormalizer().normalize(self.context, FrontendSnapshot(tuple(reversed(self.frontend.entities)), tuple(reversed(self.frontend.evidence)))).value
        self.assertEqual({item.id for item in result.value.facts}, {item.id for item in again.facts})

    def test_normalizer_preserves_explicit_test_and_coverage_metadata(self) -> None:
        entity = self.frontend.entities[0]
        metadata = {
            "inputKind": "meson", "selectionStatus": "selected", "target": "arbitrary",
            "configuration": "cfg", "testRole": "confirmed", "testSuite": "unit",
            "coverageTarget": "src/a.c", "testedSource": "src/b.c", "callTarget": "api", "alternativeTestFor": "other",
        }
        evidence = Evidence(stable_id("test-metadata"), "build_target", entity.id, "arbitrary", "unit", "static-build", "high", metadata=metadata)
        result = BuildEvidenceNormalizer().normalize(self.context, FrontendSnapshot((entity,), (evidence,)))
        fact = result.value.facts[0]
        for key, value in metadata.items():
            self.assertEqual(fact.metadata[key], value)

    def test_normalizer_rejects_unknown_subject_and_non_text_is_partial(self) -> None:
        entity = self.frontend.entities[0]
        location = SourceSpan(SourceLocation(entity.name, 1, 1), SourceLocation(entity.name, 1, 2))
        bad = Evidence(stable_id("bad-evidence"), "build_target", stable_id("missing"), "x", "unit", "unit", "high", location)
        result = BuildEvidenceNormalizer().normalize(self.context, FrontendSnapshot((entity,), (bad,)))
        self.assertEqual(result.status, CompletionStatus.FAILED)
        non_text = Evidence(stable_id("non-text"), "build_target", entity.id, {"x": 1}, "unit", "unit", "high", location)
        result = BuildEvidenceNormalizer().normalize(self.context, FrontendSnapshot((entity,), (non_text,)))
        self.assertEqual(result.status, CompletionStatus.PARTIAL)

    def test_normalizer_bridges_compile_contexts_with_target_provenance(self) -> None:
        source = self.context.input_root / "main.c"
        source_entity = Entity(stable_id("source-file", "main.c"), "source_file", "main.c")
        combined_frontend = FrontendSnapshot((*self.frontend.entities, source_entity), self.frontend.evidence)
        scan_context = replace(
            self.context,
            compile_contexts=(
                CompilationUnitContext(source, tokens=("clang", "--target=riscv64", "-mavx", "main.c"), entry_index=0),
                CompilationUnitContext(source, tokens=("clang", "--target=x86_64", "-msse2"), entry_index=1),
                CompilationUnitContext(source, tokens=("clang", "-mavx2"), entry_index=2),
                CompilationUnitContext(self.context.input_root / "missing.c", tokens=("clang", "-mavx"), entry_index=3),
                CompilationUnitContext(Path(self.context.input_root.parent) / "outside.c", tokens=("clang", "-mavx"), entry_index=4),
            ),
        )
        result = BuildEvidenceNormalizer().normalize(scan_context, combined_frontend)
        self.assertEqual(result.status, CompletionStatus.PARTIAL)
        bridged = [item for item in result.value.evidence if item.producer == "compilation-database-build-bridge"]
        self.assertEqual({item.metadata["selectionStatus"] for item in bridged}, {"selected", "inactive", "unknown"})
        self.assertTrue(all(item.location is None for item in bridged))
        self.assertTrue(any(item.scope.startswith("subject:") for item in result.value.facts if item.producers == ("compilation-database-build-bridge",)))
        self.assertTrue(any(item.code == "build-normalization.context-outside-workspace" for item in result.diagnostics))

    def test_analyzer_builds_status_relations_proofs_and_reuses_source_when_available(self) -> None:
        result = BuildSelectionAnalyzer().analyze(self.context, self.normalized, flag_knowledge("-mavx", "-mavx2"))
        self.assertEqual(result.status, CompletionStatus.PARTIAL)
        self.assertTrue(result.value.relations)
        self.assertTrue(result.value.proof_paths)
        statuses = {item.status for item in result.value.relations}
        self.assertTrue({"selected", "inactive", "unknown"} <= statuses)
        self.assertTrue(all(set(path.relation_ids) <= {item.id for item in result.value.relations} for path in result.value.proof_paths))
        self.assertTrue(any(item.kind == "selection_gap" for item in result.value.entities) is False)

    def test_analyzer_detects_missing_path_and_budget(self) -> None:
        source = next(item for item in self.frontend.entities if item.name == "Makefile")
        x86_evidence = Evidence(stable_id("x86-branch-evidence"), "build_branch", source.id, "x86_64", "unit", "unit", "high", metadata={"selectionStatus": "inactive", "role": "x86", "conditionGroup": "g"})
        fact = NormalizedFact(stable_id("x86-branch-fact"), "build_branch", source.id, "x86_64", "s", (x86_evidence.id,), metadata={"selectionStatus": "inactive", "role": "x86", "conditionGroup": "g"})
        snapshot = NormalizedSnapshot((source,), (x86_evidence,), (fact,))
        result = BuildSelectionAnalyzer().analyze(self.context, snapshot, flag_knowledge())
        self.assertTrue(any(item.properties.get("issue") == "missing_riscv_selection_path" for item in result.value.entities))
        limited = BuildSelectionAnalyzer(BuildProofBudgets(max_relations=1, max_paths=1)).analyze(self.context, self.normalized, flag_knowledge())
        self.assertEqual(limited.status, CompletionStatus.PARTIAL)
        with self.assertRaises(ValueError):
            BuildProofBudgets(max_paths=0)

    def test_detector_emits_missing_family_member_observation(self) -> None:
        """D2 升格条目：issue=missing_riscv_family_member 的关系产同名观察（graph 段与主路径段）。"""
        source = next(item for item in self.frontend.entities if item.name == "Makefile")
        evidence = Evidence(stable_id("fam-evidence"), "build_branch", source.id, "x86_64", "unit", "unit", "high")
        fact = NormalizedFact(stable_id("fam-fact"), "build_branch", source.id, "x86_64", "s", (evidence.id,))
        snapshot = NormalizedSnapshot((source,), (evidence,), (fact,))
        # graph 关系段：kind=selects 指向 gap、evidence 引用实存。
        gap = Entity(stable_id("fam-gap"), "family_gap", "Makefile:missing-riscv-family-member", properties={"issue": "missing_riscv_family_member"})
        graph_relation = Relation(stable_id("fam-relation"), "selects", source.id, gap.id, (evidence.id,), "high", "selected",
                                  {"issue": "missing_riscv_family_member", "itemKind": "build_branch", "value": "x86_64"})
        analysis = AnalysisSnapshot((graph_relation,), (source, gap), ())
        detected = BuildSelectionDetector().detect(self.context, snapshot, analysis, flag_knowledge())
        kinds = {item.kind for item in detected.value.observations}
        self.assertIn("missing_riscv_family_member", kinds)

    def test_analyzer_and_detector_reject_invalid_references(self) -> None:
        entity = self.frontend.entities[0]
        bad_fact = NormalizedFact(stable_id("bad-fact"), "build_target", stable_id("missing"), "x", "s", (stable_id("e"),))
        result = BuildSelectionAnalyzer().analyze(self.context, NormalizedSnapshot((entity,), (), (bad_fact,)), flag_knowledge())
        self.assertEqual(result.status, CompletionStatus.FAILED)
        relation = Relation(stable_id("bad-relation"), "selects", stable_id("a"), stable_id("b"), (), "high", "selected", {"itemKind": "build_argument", "value": "-mavx"})
        detected = BuildSelectionDetector().detect(self.context, self.normalized, AnalysisSnapshot((relation,), (), ()), flag_knowledge("-mavx"))
        self.assertEqual(detected.status, CompletionStatus.FAILED)

    def test_detector_emits_selected_inactive_unknown_and_gap_without_classification(self) -> None:
        analysis = BuildSelectionAnalyzer().analyze(self.context, self.normalized, flag_knowledge("-mavx", "-mavx2")).value
        selected = next(item for item in analysis.relations if item.metadata.get("value") == "-mavx")
        unknown = Relation(
            stable_id("unknown-x86-relation"), selected.kind, selected.source_id, selected.target_id,
            selected.evidence_ids, "medium", "unknown", dict(selected.metadata),
        )
        analysis = AnalysisSnapshot((*analysis.relations, unknown), analysis.entities, analysis.proof_paths)
        result = BuildSelectionDetector().detect(self.context, self.normalized, analysis, flag_knowledge("-mavx", "-mavx2"))
        kinds = {item.kind for item in result.value.observations}
        self.assertIn("build_selected_x86_dependency", kinds)
        self.assertIn("build_selection_unknown", kinds)
        for item in result.value.observations:
            self.assertNotIn(item.status, {"blocking", "ready"})
            self.assertTrue(item.relation_ids)


if __name__ == "__main__":
    unittest.main()
