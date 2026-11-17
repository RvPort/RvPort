from __future__ import annotations

from collections.abc import Mapping
from collections import defaultdict
from dataclasses import replace

from ..core import (
    AnalysisSnapshot,
    CompletionStatus,
    DetectionSnapshot,
    Diagnostic,
    DiagnosticSeverity,
    KnowledgeSnapshot,
    NormalizedSnapshot,
    Observation,
    ScanContext,
    StageResult,
    stable_id,
)
from .definitions import DEFAULT_DEFERRED_CAPABILITIES, DEFAULT_DEFINITIONS, DEFINITIONS_BY_FAMILY, DetectorDefinition
from .matchers import KnowledgeIndex, match_fact
from ..core.candidate_contract import architecture_attribute
from .candidate_coverage import covered_identifier_candidate


class LocalX86Detector:
    __slots__ = ("_definitions", "_deferred")

    def __init__(
        self,
        definitions: Mapping[str, DetectorDefinition] = DEFAULT_DEFINITIONS,
        deferred_capabilities: Mapping[str, str] = DEFAULT_DEFERRED_CAPABILITIES,
    ) -> None:
        self._definitions = definitions
        self._deferred = deferred_capabilities

    def detect(
        self,
        context: ScanContext,
        facts: NormalizedSnapshot,
        analysis: AnalysisSnapshot,
        knowledge: KnowledgeSnapshot,
    ) -> StageResult[DetectionSnapshot]:
        # context 缺省（部分单测直接传 None）时按 x86 源架构处理。
        source_family = getattr(context, "source_arch", "x86")
        # 显式注入过自定义定义时优先；默认按源架构族选择注册表（x86/arm64）。
        definitions = DEFINITIONS_BY_FAMILY.get(source_family, self._definitions) if self._definitions is DEFAULT_DEFINITIONS else self._definitions
        evidence_ids = {item.id for item in facts.evidence}
        entity_ids = {item.id for item in facts.entities}
        knowledge_ids = {item.id for item in knowledge.entries}
        for fact in facts.facts:
            if fact.subject_id not in entity_ids or not set(fact.evidence_ids).issubset(evidence_ids):
                return _failed("detector.invalid-fact-reference", "Normalized fact contains an unknown Entity or Evidence reference")

        analysis_entity_ids = {item.id for item in analysis.entities}
        relation_ids = {item.id for item in analysis.relations}
        for relation in analysis.relations:
            if (
                relation.source_id not in analysis_entity_ids
                or relation.target_id not in analysis_entity_ids
                or not set(relation.evidence_ids).issubset(evidence_ids)
            ):
                return _failed(
                    "detector.invalid-relation-reference",
                    "Analysis relation contains an unknown Entity or Evidence reference",
                )
        for proof in analysis.proof_paths:
            if (
                proof.source_id not in analysis_entity_ids
                or proof.target_id not in analysis_entity_ids
                or not set(proof.relation_ids).issubset(relation_ids)
            ):
                return _failed(
                    "detector.invalid-proof-reference",
                    "Analysis proof path contains an unknown Entity or Relation reference",
                )

        index = KnowledgeIndex(knowledge)
        entries = {item.id: item for item in knowledge.entries}
        # 用途图可有大量非执行边；每个候选只查询其 Evidence 对应的执行关系。
        by_evidence, by_proof, guarded, alternatives = (defaultdict(set), defaultdict(set), defaultdict(list), defaultdict(list))
        relations = {item.id: item for item in analysis.relations}
        entities = {item.id: item for item in analysis.entities}
        for item in analysis.relations:
            if item.metadata.get("semanticRole") == "non_executing_usage":
                continue
            for eid in item.evidence_ids:
                by_evidence[eid].add(item.id)
            if item.kind == "guarded_by":
                guarded[item.target_id].append(item)
            if item.kind == "alternative_to":
                alternatives[item.source_id].append(item)
        for item in analysis.proof_paths:
            for rid in item.relation_ids:
                by_proof[rid].add(item.id)
        branch_cache = {}
        raw_evidence = {item.id: item for item in facts.evidence}
        direct_calls = {(item.value, item.location.start) for item in facts.evidence
                        if item.kind == 'call_expression' and item.location}
        observations: dict[str, Observation] = {}
        referenced_enum_members = {
            evidence_id
            for fact in facts.facts
            if fact.kind == "enum_reference"
            for evidence_id in fact.metadata.get("enumMemberDeclarationEvidenceIds", ())
            if isinstance(evidence_id, str)
        }
        for fact in facts.facts:
            if fact.kind == 'macro_argument' and all(
                (raw_evidence[eid].value, raw_evidence[eid].location.start) in direct_calls
                for eid in fact.evidence_ids if raw_evidence[eid].location
            ):
                continue
            if fact.kind == "declaration_attribute" and not architecture_attribute(fact.canonical_value):
                continue
            matches = [(definition, match_fact(definition, fact, index, source_family)) for definition in definitions.values()]
            confirmed = any(match is not None and match.status == "confirmed_local" for _, match in matches)
            specific_attribute = fact.kind == "declaration_attribute" and any(match is not None and definition.observation_kind != "target_attribute_candidate" for definition, match in matches)
            for definition, match in matches:
                if fact.kind == "enum_member" and set(fact.evidence_ids) & referenced_enum_members:
                    continue
                if match is None:
                    continue
                if definition.observation_kind == "architecture_identifier_candidate" and covered_identifier_candidate(fact, matches, entries, source_family):
                    continue
                if (confirmed or specific_attribute) and definition.observation_kind in {"inline_asm_candidate", "target_attribute_candidate"}:
                    continue
                if not set(match.knowledge_ids).issubset(knowledge_ids):
                    return _failed("detector.invalid-knowledge-reference", "Matcher returned an unknown Knowledge reference")
                matched_relations = tuple(sorted({rid for eid in fact.evidence_ids for rid in by_evidence[eid]}))
                selected_relations = tuple(relations[rid] for rid in matched_relations)
                if fact.kind == 'macro_argument':
                    proven = any(r.metadata.get('macroEffect', {}).get('proven') is True
                                 and r.metadata['macroEffect'].get('terminalEvidenceId') in fact.evidence_ids
                                 and r.status == 'complete' for r in selected_relations)
                    if proven:
                        match = replace(match, status='confirmed_local')
                proof_paths = tuple(sorted({pid for rid in matched_relations for pid in by_proof[rid]}))
                if matched_relations not in branch_cache:
                    branch_cache[matched_relations] = _branch_metadata(entities, selected_relations, guarded, alternatives)
                branch_metadata = branch_cache[matched_relations]
                candidate_metadata = {
                    "candidateBasis": match.basis,
                    "architectureDisposition": source_family if branch_metadata.get("branchRole") == source_family else "unknown",
                } if match.status == "candidate" else {}
                if definition.observation_kind in {"inline_asm_candidate", "target_attribute_candidate"}:
                    foreign = any(
                        item.kind == "guarded_by"
                        and item.status == "complete" and item.metadata.get("architectureDisposition") == "foreign"
                        for item in selected_relations
                    )
                    if foreign:
                        continue
                    candidate_metadata = {
                        "candidateBasis": "unclassified_inline_asm" if fact.kind == "inline_asm" else "architecture_attribute",
                        "architectureDisposition": source_family if branch_metadata.get("branchRole") == source_family else "unknown",
                    }
                observation_evidence = fact.evidence_ids
                if fact.kind in {"enum_member", "enum_reference"}:
                    related = {
                        evidence_id
                        for relation in selected_relations
                        for evidence_id in relation.evidence_ids
                    }
                    observation_evidence = tuple(sorted(set(observation_evidence) | related))
                observation_id = stable_id(
                    "observation",
                    1,
                    definition.detector_id,
                    definition.observation_kind,
                    fact.id,
                    match.status,
                    match.knowledge_ids,
                    matched_relations,
                    proof_paths,
                )
                observations[observation_id] = Observation(
                    id=observation_id,
                    detector_id=definition.detector_id,
                    kind=definition.observation_kind,
                    evidence_ids=observation_evidence,
                    relation_ids=matched_relations,
                    confidence={"confirmed_local": "high", "candidate": "medium", "hint": "low"}[match.status],
                    status=match.status,
                    metadata={
                        "basis": match.basis,
                        **({"compilerFlagValue": fact.canonical_value} if definition.observation_kind in {"x86_compiler_flag", "arm64_compiler_flag"} else {}),
                        "factId": fact.id,
                        "factKind": fact.kind,
                        "subjectId": fact.subject_id,
                        "proofPathIds": proof_paths,
                        "relationCompleteness": tuple(
                            sorted(
                                {
                                    item.status
                                    for item in selected_relations
                                }
                            )
                        ),
                        **{
                            field: fact.metadata[field]
                            for field in (
                                "enumName", "memberName", "qualifiedName", "bindingStatus",
                                "referenceContext", "templateDependent", "enumMemberDeclarationEvidenceIds",
                            )
                            if field in fact.metadata
                        },
                        **branch_metadata,
                        **candidate_metadata,
                        **({"matchedEvidenceIds": fact.evidence_ids, "configuration": fact.metadata.get("compilationContextId") or fact.metadata.get("configuration", "default")} if definition.observation_kind in {"architecture_identifier_candidate", "architecture_enum_candidate"} else {}),
                    },
                    knowledge_ids=match.knowledge_ids,
                )
        diagnostics: tuple[Diagnostic, ...] = ()
        status = CompletionStatus.COMPLETE
        if self._deferred:
            diagnostics = (
                Diagnostic(
                    code="detector.deferred-capabilities",
                    stage="detector",
                    severity=DiagnosticSeverity.INFO,
                    message="Some migration capabilities are deferred to later knowledge or relation stages",
                    producer="local-x86-detector",
                    details={
                        "capabilities": [
                            {"id": capability, "reason": reason}
                            for capability, reason in sorted(self._deferred.items())
                        ]
                    },
                ),
            )
            status = CompletionStatus.PARTIAL
        snapshot = DetectionSnapshot(
            observations=tuple(sorted(observations.values(), key=lambda item: (item.detector_id, item.kind, item.id)))
        )
        return StageResult(value=snapshot, status=status, diagnostics=diagnostics)


def _failed(code: str, message: str) -> StageResult[DetectionSnapshot]:
    diagnostic = Diagnostic(
        code=code,
        stage="detector",
        severity=DiagnosticSeverity.ERROR,
        message=message,
        producer="local-x86-detector",
        affects_exit_code=True,
    )
    return StageResult(value=None, status=CompletionStatus.FAILED, diagnostics=(diagnostic,))


def _branch_metadata(entities, selected, guarded, alternative_edges) -> dict[str, object]:
    branches = {
        relation.target_id
        for relation in selected
        if relation.kind == "guarded_by" and entities.get(relation.target_id) is not None
    }
    roles: set[str] = set()
    alternatives: set[str] = set()
    expressions: set[str] = set()
    for branch_id in branches:
        branch = entities[branch_id]
        expressions.add(branch.name.split(":", 1)[-1])
        for relation in guarded[branch_id]:
            source = entities.get(relation.source_id)
            role = source.properties.get("role") if source is not None else None
            if isinstance(role, str) and role != "unknown":
                roles.add(role)
        for relation in alternative_edges[branch_id]:
            source_role = relation.metadata.get("sourceRole")
            target_role = relation.metadata.get("targetRole")
            if isinstance(source_role, str):
                roles.add(source_role)
            if isinstance(target_role, str):
                alternatives.add(target_role)
    result: dict[str, object] = {}
    enum_relations = [item for item in selected if item.metadata.get("enumDependency") is True]
    enum_contexts = {
        str(item.metadata.get("selectionContext"))
        for item in selected
        if item.metadata.get("selectionContext")
    }
    if enum_contexts:
        result["selectionContext"] = min(enum_contexts)
    if enum_relations:
        result["enumDependencyComplete"] = all(item.status == "complete" for item in enum_relations)
        knowledge = {
            value
            for item in enum_relations
            for value in item.metadata.get("knowledgeIds", ())
            if isinstance(value, str)
        }
        if knowledge:
            result["enumDependencyKnowledgeIds"] = tuple(sorted(knowledge))
    if branches:
        result["branchGroupId"] = min(branches)
    if roles:
        result["branchRole"] = min(roles)
    if alternatives:
        result["compatibleSiblingBranch"] = True
        result["compatibleBranchKind"] = min(alternatives)
    if expressions:
        result["conditionExpression"] = min(expressions)
    return result
