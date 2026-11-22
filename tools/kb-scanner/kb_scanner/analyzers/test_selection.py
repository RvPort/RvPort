from __future__ import annotations

import re
from dataclasses import dataclass, replace

from ..core import (
    AnalysisSnapshot,
    CompletionStatus,
    Diagnostic,
    DiagnosticSeverity,
    Entity,
    KnowledgeSnapshot,
    NormalizedFact,
    NormalizedSnapshot,
    ProofPath,
    Relation,
    ScanContext,
    StageResult,
    candidate_test_path,
    explicit_test_target,
    stable_id,
)


_X86 = re.compile(r"(?i)(?:-m(?:no-)?(?:avx|sse)\w*|(?:^|[/_.-])(?:x86(?:_64)?|amd64|i[3-6]86|avx\w*|sse\w*)(?:$|[/_.-]))")
_KNOWN_SELECTIONS = {"selected", "inactive", "not_selected"}


@dataclass(frozen=True, slots=True)
class TestProofBudgets:
    max_assets: int = 4096
    max_relations: int = 10000
    max_paths: int = 4096
    max_depth: int = 8
    max_visited: int = 20000

    def __post_init__(self) -> None:
        if min(self.max_assets, self.max_relations, self.max_paths, self.max_depth, self.max_visited) < 1:
            raise ValueError("test proof budgets must be positive")


class TestAssetAnalyzer:
    """Recover test roles from already-normalized facts; never reads project files."""

    __slots__ = ("_budgets",)

    def __init__(self, budgets: TestProofBudgets = TestProofBudgets()) -> None:
        self._budgets = budgets

    def analyze(
        self,
        context: ScanContext,
        facts: NormalizedSnapshot,
        knowledge: KnowledgeSnapshot,
        base: AnalysisSnapshot = AnalysisSnapshot(),
    ) -> StageResult[AnalysisSnapshot]:
        del knowledge
        invalid = _validate_inputs(facts, base)
        if invalid is not None:
            return invalid
        if not any(
            item.kind in {"build_target", "build_source", "build_package_section"}
            for item in facts.facts
        ):
            return StageResult(base)
        entities = {item.id: item for item in (*facts.entities, *base.entities)}
        relations = {item.id: item for item in base.relations}
        proofs = {item.id: item for item in base.proof_paths}
        budgets = replace(self._budgets, max_relations=len(relations) + self._budgets.max_relations)
        selector = _selector(context, entities)
        diagnostics: list[Diagnostic] = []
        assets = 0

        confirmed_targets: dict[tuple[str, str], Entity] = {}
        target_facts = [item for item in facts.facts if item.kind == "build_target"]
        for fact in target_facts:
            input_kind = _text(fact, "inputKind")
            name = _text(fact, "target", fact.canonical_value)
            configuration = _text(fact, "configuration", "default")
            # Existing explicit build-test scope is not an executable entry proof.
            # TestContentAnalyzer keeps registration/entry applicability separate.
            confirmed = _text(fact, "testRole") == "confirmed" or explicit_test_target(name, input_kind)
            candidate = confirmed or candidate_test_path(name)
            if not candidate:
                continue
            if assets + 2 > self._budgets.max_assets:
                diagnostics.append(_diagnostic("test-selection.max-assets", "Test asset budget exceeded", "max-assets"))
                break
            strength = "confirmed" if confirmed else "candidate"
            status = _text(fact, "selectionStatus", "unknown") if confirmed else "unknown"
            basis = "ctest-metadata" if input_kind == "ctest" else "explicit-test-metadata" if _text(fact, "testRole") == "confirmed" else "explicit-build-target" if confirmed else "name-pattern"
            suite = _test_entity("test_suite", fact.subject_id, configuration, basis, strength, status)
            target = _test_entity("test_target" if confirmed else "test_candidate", name, configuration, basis, strength, status)
            entities[suite.id] = suite
            entities[target.id] = target
            assets += 2
            _add_relation(relations, "contains_test", suite, target, fact, status, basis, budgets)
            _add_relation(relations, "selects_test", selector, target, fact, status, basis, budgets)
            if confirmed:
                confirmed_targets[(name, configuration)] = target
            else:
                diagnostics.append(_diagnostic("test-selection.candidate-role", "Test-like target is only a candidate", basis))

        for fact in facts.facts:
            if fact.kind == "build_package_section" and _text(fact, "section", fact.canonical_value).lower() == "check":
                configuration = _text(fact, "configuration", "default")
                status = _text(fact, "selectionStatus", "unknown")
                suite = _test_entity("test_suite", fact.subject_id, configuration, "rpm-check-section", "confirmed", status)
                target = _test_entity("test_target", "%check", configuration, "rpm-check-section", "confirmed", status)
                entities[suite.id] = suite
                entities[target.id] = target
                confirmed_targets[("%check", configuration)] = target
                _add_relation(relations, "contains_test", suite, target, fact, status, "rpm-check-section", budgets)
                _add_relation(relations, "selects_test", selector, target, fact, status, "rpm-check-section", budgets)

        source_by_name = {item.name: item for item in entities.values() if item.kind == "source_file"}
        for fact in (item for item in facts.facts if item.kind == "build_source"):
            name = fact.canonical_value.removeprefix("./")
            configuration = _text(fact, "configuration", "default")
            target_name = _text(fact, "target")
            target = confirmed_targets.get((target_name, configuration))
            confirmed = target is not None
            if not confirmed and not candidate_test_path(name):
                continue
            source = source_by_name.get(name)
            if not confirmed:
                source = _test_entity(
                    "test_candidate",
                    name,
                    configuration,
                    "path-pattern",
                    "candidate",
                    "unknown",
                )
                entities[source.id] = source
                _add_relation(relations, "selects_test", selector, source, fact, "unknown", "path-pattern", budgets)
            elif source is None:
                source = _test_entity(
                    "test_source",
                    name,
                    configuration,
                    "explicit-source-to-test-target",
                    "confirmed",
                    _text(fact, "selectionStatus", "unknown"),
                )
                entities[source.id] = source
            if target is not None:
                _add_relation(
                    relations,
                    "contains_test",
                    target,
                    source,
                    fact,
                    _text(fact, "selectionStatus", "unknown"),
                    "explicit-source-to-test-target",
                    budgets,
                )
            else:
                diagnostics.append(_diagnostic("test-selection.candidate-source", "Test-like source has no explicit test target", "path-pattern"))

        snapshot = _snapshot(entities, relations, proofs)
        return StageResult(snapshot, CompletionStatus.PARTIAL if diagnostics else CompletionStatus.COMPLETE, tuple(diagnostics))


class TestSelectionAnalyzer:
    """Connect confirmed test assets to existing build/source entities and bounded proofs."""

    __slots__ = ("_budgets",)

    def __init__(self, budgets: TestProofBudgets = TestProofBudgets()) -> None:
        self._budgets = budgets

    def analyze(
        self,
        context: ScanContext,
        facts: NormalizedSnapshot,
        knowledge: KnowledgeSnapshot,
        base: AnalysisSnapshot = AnalysisSnapshot(),
    ) -> StageResult[AnalysisSnapshot]:
        invalid = _validate_inputs(facts, base)
        if invalid is not None:
            return invalid
        entities = {item.id: item for item in (*facts.entities, *base.entities)}
        relations = {item.id: item for item in base.relations}
        proofs = {item.id: item for item in base.proof_paths}
        budgets = replace(self._budgets, max_relations=len(relations) + self._budgets.max_relations,
                          max_paths=len(proofs) + self._budgets.max_paths)
        selector = _selector(context, entities)
        diagnostics: list[Diagnostic] = []
        targets = [item for item in entities.values() if item.kind == "test_target"]
        relation_visits = 0
        knowledge_values = tuple(
            (item.canonical_value.lower(), item.id)
            for item in knowledge.entries
            if item.architecture_family.lower() == context.source_arch
        )
        build_endpoint = {
            str(item.metadata.get("factId")): entities.get(item.target_id)
            for item in base.relations
            if item.metadata.get("factId") and item.target_id in entities
        }
        for target in sorted(targets, key=lambda item: item.id):
            configuration = str(target.properties.get("configuration", "default"))
            name = target.name
            selections = [
                item for item in relations.values()
                if item.kind == "selects_test" and item.target_id == target.id
            ]
            statuses = {item.status for item in selections}
            target_status = next(iter(statuses)) if len(statuses) == 1 else "unknown"
            if len(statuses) > 1:
                diagnostics.append(_diagnostic("test-selection.configuration-conflict", "Test target has conflicting selection states", "configuration-conflict"))
            for fact in facts.facts:
                if fact.kind not in {"build_source", "build_argument", "build_dependency", "build_arch_constraint"}:
                    continue
                fact_target = _text(fact, "target")
                fact_section = _text(fact, "section")
                if not (fact_target == name or (name == "%check" and fact_section.lower() == "check")):
                    continue
                if _text(fact, "configuration", "default") != configuration:
                    continue
                relation_visits += 1
                if relation_visits > self._budgets.max_visited:
                    diagnostics.append(_diagnostic("test-selection.max-visited", "Test relation visit budget exceeded", "max-visited"))
                    break
                endpoint = build_endpoint.get(fact.id)
                if endpoint is None:
                    endpoint = _item_entity(fact, entities)
                    entities[endpoint.id] = endpoint
                fact_status = _text(fact, "selectionStatus", "unknown")
                status = target_status if target_status == fact_status else "unknown"
                value = fact.canonical_value.lower()
                knowledge_ids = tuple(sorted(item_id for token, item_id in knowledge_values if value == token or value.startswith(token + "=")))
                x86_candidate = bool(knowledge_ids) or bool(_X86.search(value))
                metadata: dict[str, object] = {
                    "basis": "explicit-test-target-member",
                    "configuration": configuration,
                    "selectionStatus": status,
                    "itemKind": fact.kind,
                    "factId": fact.id,
                    "x86Dependency": bool(knowledge_ids),
                    "x86Candidate": x86_candidate,
                    "knowledgeIds": knowledge_ids,
                }
                relation = _make_relation("exercises", target.id, endpoint.id, fact.evidence_ids, status, metadata)
                if len(relations) < budgets.max_relations:
                    relations[relation.id] = relation
                else:
                    diagnostics.append(_diagnostic("test-selection.max-relations", "Test relation budget exceeded", "max-relations"))
                    break
                selection = next((item for item in selections if item.status == target_status), selections[0] if selections else None)
                path_ids = (() if selection is None else (selection.id,)) + (relation.id,)
                proof_status = "complete" if status in _KNOWN_SELECTIONS and len(path_ids) <= self._budgets.max_depth else "unknown"
                truncated = len(path_ids) > self._budgets.max_depth
                reason = None if proof_status == "complete" else "max-depth" if truncated else "selection-evidence-incomplete"
                proof = ProofPath(
                    stable_id("proof_path", 1, selector.id, endpoint.id, path_ids, proof_status, reason),
                    selector.id,
                    endpoint.id,
                    path_ids,
                    proof_status,
                    reason,
                    len(path_ids) + 1,
                    truncated,
                )
                if len(proofs) < budgets.max_paths:
                    proofs[proof.id] = proof
                else:
                    diagnostics.append(_diagnostic("test-selection.max-paths", "Test proof path budget exceeded", "max-paths"))
                    break

                # Source-file membership is a compile association, not a test
                # execution proof. Local detection owns these original facts;
                # TestContentAnalyzer adds their test context without duplicates.

                explicit_coverage = _text(fact, "coverageTarget") or _text(fact, "testedSource") or _text(fact, "callTarget")
                if explicit_coverage:
                    implementation = next((item for item in entities.values() if item.name == explicit_coverage), None)
                    if implementation is not None:
                        coverage = _make_relation(
                            "covers_candidate",
                            target.id,
                            implementation.id,
                            fact.evidence_ids,
                            status,
                            {"basis": "explicit-target-source-call-metadata", "configuration": configuration},
                        )
                        relations[coverage.id] = coverage
                alternative_name = _text(fact, "alternativeTestFor")
                if alternative_name:
                    alternative = next((item for item in entities.values() if item.name == alternative_name and item.kind == "test_target"), None)
                    if alternative is not None:
                        relation = _make_relation(
                            "alternative_test_for",
                            target.id,
                            alternative.id,
                            fact.evidence_ids,
                            status,
                            {"basis": "explicit-test-alternative-metadata", "configuration": configuration},
                        )
                        relations[relation.id] = relation

        if any(item.kind == "test_candidate" for item in entities.values()):
            diagnostics.append(_diagnostic("test-selection.candidate-input", "Candidate test roles remain unresolved", "candidate-role"))
        if any(item.kind == "selects_test" and item.status == "unknown" for item in relations.values()):
            diagnostics.append(_diagnostic("test-selection.partial", "Some test selections remain unknown", "selection-evidence-incomplete"))
        snapshot = _snapshot(entities, relations, proofs)
        return StageResult(snapshot, CompletionStatus.PARTIAL if diagnostics else CompletionStatus.COMPLETE, tuple(_dedupe_diagnostics(diagnostics)))


def _selector(context: ScanContext, entities: dict[str, Entity]) -> Entity:
    existing = next((item for item in entities.values() if item.kind == "architecture_selector" and item.name == context.target_arch), None)
    if existing is not None:
        return existing
    selector = Entity(
        stable_id("build_entity", 1, "architecture_selector", context.target_arch),
        "architecture_selector",
        context.target_arch,
        properties={"identityVersion": 1, "targetArchitecture": context.target_arch},
    )
    entities[selector.id] = selector
    return selector


def _test_entity(kind: str, name: str, configuration: str, basis: str, strength: str, status: str) -> Entity:
    return Entity(
        stable_id("test_entity", 1, kind, name, configuration, basis, strength),
        kind,
        name,
        properties={
            "identityVersion": 1,
            "configuration": configuration,
            "roleBasis": basis,
            "roleStrength": strength,
            "selectionStatus": status,
        },
    )


def _item_entity(fact: NormalizedFact, entities: dict[str, Entity]) -> Entity:
    name = fact.canonical_value.removeprefix("./")
    if fact.kind == "build_source":
        existing = next((item for item in entities.values() if item.kind == "source_file" and item.name == name), None)
        if existing is not None:
            return existing
    kind = {"build_source": "source_file", "build_argument": "build_argument", "build_dependency": "build_dependency", "build_arch_constraint": "architecture_constraint"}[fact.kind]
    return Entity(
        stable_id("test_member_entity", 1, kind, fact.subject_id, name, fact.scope),
        kind,
        name,
        properties={"identityVersion": 1, "factId": fact.id, "subjectId": fact.subject_id},
    )


def _add_relation(
    relations: dict[str, Relation],
    kind: str,
    source: Entity,
    target: Entity,
    fact: NormalizedFact,
    status: str,
    basis: str,
    budgets: TestProofBudgets,
) -> None:
    if len(relations) >= budgets.max_relations:
        return
    relation = _make_relation(
        kind,
        source.id,
        target.id,
        fact.evidence_ids,
        status,
        {
            "basis": basis,
            "configuration": _text(fact, "configuration", "default"),
            "selectionStatus": status,
            "factId": fact.id,
        },
    )
    relations[relation.id] = relation


def _make_relation(kind: str, source_id: str, target_id: str, evidence_ids: tuple[str, ...], status: str, metadata: dict[str, object]) -> Relation:
    clean_status = status if status in {*_KNOWN_SELECTIONS, "unknown"} else "unknown"
    return Relation(
        stable_id("relation", 1, kind, source_id, target_id, tuple(sorted(evidence_ids)), clean_status, metadata),
        kind,
        source_id,
        target_id,
        tuple(sorted(evidence_ids)),
        "high" if clean_status in _KNOWN_SELECTIONS else "medium",
        clean_status,
        metadata,
    )


def _validate_inputs(facts: NormalizedSnapshot, base: AnalysisSnapshot) -> StageResult[AnalysisSnapshot] | None:
    entity_ids = {item.id for item in (*facts.entities, *base.entities)}
    evidence_ids = {item.id for item in facts.evidence}
    relation_ids = {item.id for item in base.relations}
    for fact in facts.facts:
        if fact.subject_id not in entity_ids or not set(fact.evidence_ids).issubset(evidence_ids):
            return _failed("test-selection.invalid-fact-reference", "Test analysis fact references unknown Entity or Evidence")
    for relation in base.relations:
        if relation.source_id not in entity_ids or relation.target_id not in entity_ids or not set(relation.evidence_ids).issubset(evidence_ids):
            return _failed("test-selection.invalid-relation-reference", "Test analysis relation references unknown Entity or Evidence")
    for proof in base.proof_paths:
        if proof.source_id not in entity_ids or proof.target_id not in entity_ids or not set(proof.relation_ids).issubset(relation_ids):
            return _failed("test-selection.invalid-proof-reference", "Test analysis proof references unknown Entity or Relation")
    return None


def _text(fact: NormalizedFact, field: str, default: str = "") -> str:
    value = fact.metadata.get(field, default)
    return value if isinstance(value, str) else default


def _snapshot(entities: dict[str, Entity], relations: dict[str, Relation], proofs: dict[str, ProofPath]) -> AnalysisSnapshot:
    return AnalysisSnapshot(
        tuple(sorted(relations.values(), key=lambda item: (item.kind, item.source_id, item.target_id, item.id))),
        tuple(sorted(entities.values(), key=lambda item: (item.kind, item.name, item.id))),
        tuple(sorted(proofs.values(), key=lambda item: item.id)),
    )


def _diagnostic(code: str, message: str, reason: str) -> Diagnostic:
    return Diagnostic(code, "analyzer.test-selection", DiagnosticSeverity.WARNING, message, producer="test-selection-analyzer", details={"reason": reason})


def _failed(code: str, message: str) -> StageResult[AnalysisSnapshot]:
    diagnostic = Diagnostic(code, "analyzer.test-selection", DiagnosticSeverity.ERROR, message, producer="test-selection-analyzer", affects_exit_code=True)
    return StageResult(None, CompletionStatus.FAILED, (diagnostic,))


def _dedupe_diagnostics(items: list[Diagnostic]) -> tuple[Diagnostic, ...]:
    return tuple({(item.code, item.message, str(item.details)): item for item in items}.values())
