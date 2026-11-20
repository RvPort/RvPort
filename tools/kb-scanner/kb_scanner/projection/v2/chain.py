from __future__ import annotations

from collections.abc import Iterable

from ...core import Classification, KnowledgeSnapshot
from ...core.metadata import to_json_compatible
from .context import SourceContextProjector
from .index import SnapshotIndex
from .source_chain import Query, SourceChainIndex, primary_path
from .models import Break, ChainBudget, Edge, EvidenceChain, KnowledgeProjection, Step, TypedRef, stable_view_id
from .evidence_kinds import intrinsic_symbols, is_intrinsic, proven_macro_terminals
from .macro_effects import MacroEffectsProjection


_STEP_KIND = {
    "architecture_probe": "build_selector", "build_argument": "compiler_flag",
    "compiler_argument": "compiler_flag", "include": "source_evidence",
    "call_expression": "source_evidence", "macro_expansion": "macro_invocation",
    "macro_definition": "macro_definition", "macro_replacement_token": "macro_replacement",
    "type_reference": "vector_type", "test_asset": "test_asset",
    "enum_declaration": "source_evidence", "enum_member_declaration": "source_evidence",
    "enum_member_reference": "source_evidence", "constexpr_condition": "source_evidence",
    "selection_condition": "source_evidence",
}
class EvidenceChainBuilder:
    def __init__(self, index: SnapshotIndex, knowledge: KnowledgeSnapshot, source_context: SourceContextProjector, *, max_depth: int = 16, max_visited: int = 256, max_paths: int = 32, architecture_family: str = "x86") -> None:
        self._index = index
        self._source_index = SourceChainIndex(index)
        self._knowledge = knowledge
        self._source_context = source_context
        self._max_depth = max_depth
        self._max_visited = max_visited
        self._max_paths = max_paths
        self._intrinsic_symbols = intrinsic_symbols(index.knowledge.values(), architecture_family)
        self._macro_terminals = proven_macro_terminals(index.relations.values())
        self._macro_effects = MacroEffectsProjection(index.relations.values())
        self._step_cache, self._edge_cache, self._incoming_cache = {}, {}, {}

    def build(self, classification: Classification, *, anchor_id: str | None = None, occurrence_ids: tuple[str, ...] = ()) -> EvidenceChain:
        missing = []
        cited = set(classification.evidence_ids)
        relation_ids = set(classification.relation_ids)
        for oid in classification.observation_ids:
            observation = self._index.observations.get(oid)
            if observation is None:
                missing.append(oid)
            else:
                cited.update(observation.evidence_ids)
                relation_ids.update(observation.relation_ids)
        for pid in classification.proof_path_ids:
            proof = self._index.proofs.get(pid)
            if proof is None:
                missing.append(pid)
            else:
                relation_ids.update(proof.relation_ids)
        missing.extend(rid for rid in relation_ids if rid not in self._index.relations)
        missing.extend(eid for eid in cited if eid not in self._index.evidence)
        located = [self._index.evidence[eid] for eid in cited if eid in self._index.evidence and self._index.evidence[eid].location]
        if anchor_id is None:
            anchor_id = min(located, key=_evidence_key).id if located else None
        if anchor_id is None:
            raise ValueError("classification has no projectable evidence chain")
        anchor = self._index.evidence[anchor_id]
        configuration = anchor.metadata.get("configuration") or anchor.metadata.get("compilationContextId")
        configurations = self._macro_effects.configurations(anchor_id)
        if configurations:
            configuration = configurations[0]
        query = self._source_index.query(anchor_id, configuration=configuration,
            max_depth=self._max_depth, max_visited=self._max_visited, max_paths=self._max_paths)
        primary_arcs = query.arcs
        occurrence_gap = not query.arcs
        additional = [(anchor_id,cfg) for cfg in configurations[1:]]
        additional.extend((eid,None) for eid in sorted(set(occurrence_ids) - {anchor_id}))
        for occurrence_id, occurrence_configuration in additional:
            item = self._index.evidence.get(occurrence_id)
            if item is None or item.location is None:
                missing.append(occurrence_id)
                continue
            if query.visited >= self._max_visited or query.paths >= self._max_paths:
                query = Query(query.evidence_ids, query.arcs, tuple(sorted({*query.reasons, "reference_budget_exceeded"})), query.visited, query.paths)
                break
            other = self._source_index.query(occurrence_id,
                configuration=occurrence_configuration or item.metadata.get("configuration") or item.metadata.get("compilationContextId"),
                max_depth=self._max_depth, max_visited=self._max_visited - query.visited, max_paths=self._max_paths - query.paths)
            occurrence_gap |= not other.arcs
            query = Query(tuple(sorted({*query.evidence_ids, *other.evidence_ids})),
                tuple(sorted(set((*query.arcs, *other.arcs)), key=lambda arc: (arc.source, arc.target, arc.kind, arc.relation_ids))),
                tuple(sorted({*query.reasons, *other.reasons})), query.visited + other.visited, query.paths + other.paths)
        evidence_ids = set(query.evidence_ids)
        # Referenced occurrences remain visible, but membership alone is not an edge.
        overflow = False
        for item in sorted(located, key=_evidence_key):
            if item.id in evidence_ids:
                continue
            if len(evidence_ids) >= self._max_visited:
                overflow = True
                break
            evidence_ids.add(item.id)
        evidence_steps = {eid: self._evidence_step(self._index.evidence[eid]) for eid in sorted(evidence_ids)}
        knowledge_steps = self._knowledge_steps(classification.knowledge_ids, missing)
        available = max(0, self._max_visited - len(evidence_steps))
        overflow |= len(knowledge_steps) > available
        knowledge_steps = knowledge_steps[:available]
        steps = [*evidence_steps.values(), *knowledge_steps]
        edges = []
        for arc in query.arcs:
            left, right = evidence_steps[arc.source], evidence_steps[arc.target]
            relations = [self._index.relations[rid] for rid in arc.relation_ids]
            states = {_relation_completion(r.status) for r in relations}
            state = "unknown" if "unknown" in states else "ambiguous" if "ambiguous" in states else "partial" if "partial" in states else "inactive" if "inactive" in states else "complete"
            reasons = () if state == "complete" else ("inactive_branch",) if state == "inactive" else ("multiple_candidates",) if state == "ambiguous" else ("missing_input",)
            if arc not in self._edge_cache:
                self._edge_cache[arc] = Edge(id=stable_view_id("edge", arc.source, arc.target, arc.kind, *arc.relation_ids),
                    fromStepId=left.id, toStepId=right.id, kind=arc.kind, status=state, reasonCodes=reasons,
                    provenance=tuple(TypedRef(kind="relation", id=r.id, producer="analysis") for r in relations))
            edges.append(self._edge_cache[arc])
            details = [{"id": r.id, "kind": r.kind, "sourceEntityId": r.source_id,
                        "targetEntityId": r.target_id, "direction": "reversed" if r.kind == "guarded_by" else "forward",
                        **({'configuration':r.metadata.get('configuration'),'macroEffect':to_json_compatible(r.metadata['macroEffect'])} if r.metadata.get('macroEffect') else {})}
                       for r in relations]
            existing = evidence_steps[arc.target]
            incoming_key = id(existing), arc  # 仅扫描内对象缓存；不进入稳定 ID 或输出。
            if incoming_key in self._incoming_cache:
                evidence_steps[arc.target] = self._incoming_cache[incoming_key]
                continue
            extensions = dict(existing.extensions or {})
            source_chain = dict(extensions.get("org.rvport.kb-scanner.source-chain", {}))
            source_chain["incoming"] = [*source_chain.get("incoming", []), {"fromEvidenceId": arc.source, "relations": details}]
            extensions["org.rvport.kb-scanner.source-chain"] = source_chain
            evidence_steps[arc.target] = existing.model_copy(update={"extensions": extensions})
            self._incoming_cache[incoming_key] = evidence_steps[arc.target]
        for knowledge_step in knowledge_steps:
            for eid, step in evidence_steps.items():
                if step.symbol != knowledge_step.symbol:
                    continue
                entry = self._index.knowledge[knowledge_step.knowledge.entryId]
                evidence = self._index.evidence[eid]
                applicable = is_intrinsic(evidence, self._intrinsic_symbols) or evidence.kind == 'macro_argument_token' or (evidence.kind == "macro_replacement_token" and evidence.metadata.get("tokenRole") == "call_candidate")
                if entry.fact_kind in {"intrinsics", "builtins"} and not applicable:
                    continue
                edges.append(Edge(id=stable_view_id("edge", step.id, knowledge_step.id, "matches_knowledge"),
                    fromStepId=step.id, toStepId=knowledge_step.id, kind="matches_knowledge", status="complete",
                    provenance=(*step.provenance, *knowledge_step.provenance)))
        path = primary_path(anchor_id, primary_arcs if configurations else query.arcs, max_depth=self._max_depth, max_paths=self._max_paths)
        reasons = set(query.reasons)
        status = _completion(classification)
        path_pairs = {(evidence_steps[a].id, evidence_steps[b].id) for a, b in zip(path, path[1:])}
        if any(edge.status == "inactive" and (edge.fromStepId, edge.toStepId) in path_pairs for edge in edges):
            status = "inactive"
        if missing:
            reasons.add("missing_reference")
        if overflow:
            reasons.add("reference_budget_exceeded")
        if any(step.source.context.status != "complete" for step in evidence_steps.values()):
            reasons.add("context_unavailable")
        if occurrence_gap:
            reasons.add("missing_input")
        bound_conditions = {arc.target for arc in query.arcs for rid in arc.relation_ids
                            if self._index.relations[rid].metadata.get("sourceChain", {}).get("binding")}
        if _completion(classification) != "inactive" and any(
            self._index.evidence[eid].kind == "preprocessor_condition" and eid not in bound_conditions
            for eid in evidence_ids
        ):
            reasons.add("missing_input")
        if any(edge.status in {"unknown", "partial"} for edge in edges):
            reasons.add("missing_input")
        if any(edge.status == "ambiguous" for edge in edges):
            reasons.add("multiple_candidates")
        configs = {self._index.relations[rid].metadata.get("configuration")
                   for arc in query.arcs for rid in arc.relation_ids}
        configs.discard(None)
        configs.discard("default")
        if len(configs) > 1:
            reasons.add("configuration_conflict")
        if reasons:
            status = "ambiguous" if reasons <= {"multiple_candidates", "configuration_conflict"} else "partial"
        if _completion(classification) == "unknown":
            status = "unknown"
        if status in {"partial", "ambiguous", "unknown"} and not reasons:
            reasons.add("multiple_candidates" if status == "ambiguous" else "missing_input")
        if status == "inactive":
            reasons.add("inactive_branch")
        breaks = tuple(Break(id=stable_view_id("break", classification.id, reason), status=status if status in {"ambiguous", "unknown"} else "partial",
            reasonCode=reason, stage="source-upstream" if reason == "missing_input" else "source-reference",
            fromStepId=evidence_steps[anchor_id].id,
            missingId=sorted(missing)[0] if reason == "missing_reference" else None)
            for reason in sorted(reasons) if reason != "inactive_branch")
        steps = [*evidence_steps.values(), *knowledge_steps]
        return EvidenceChain(status=status, reasonCodes=tuple(sorted(reasons)), rootStepId=evidence_steps[path[0]].id,
            primaryPath=tuple(evidence_steps[eid].id for eid in path),
            steps=tuple(sorted(steps, key=lambda s: s.id)), edges=tuple(sorted(edges, key=lambda e: e.id)),
            breaks=tuple(sorted(breaks, key=lambda b: b.id)), impactStepIds=(),
            budget=ChainBudget(maxDepth=self._max_depth, maxVisited=self._max_visited, maxPaths=self._max_paths,
                visited=max(query.visited, len(steps)), paths=query.paths, truncated="reference_budget_exceeded" in reasons))

    def _evidence_step(self, item) -> Step:
        if item.id in self._step_cache:
            return self._step_cache[item.id]
        kind = "intrinsic_call" if is_intrinsic(item, self._intrinsic_symbols) else _STEP_KIND.get(item.kind, "source_evidence")
        if item.id in self._macro_terminals and item.value in self._intrinsic_symbols:
            kind = 'intrinsic_call'
        metadata = to_json_compatible(item.metadata)
        selection = metadata.get("selectionStatus", "unknown")
        selection = selection if selection in {"selected", "inactive", "not_selected", "unknown"} else "unknown"
        status = "inactive" if selection in {"inactive", "not_selected"} else "complete"
        reasons = ("inactive_branch",) if status == "inactive" else ()
        enum_extension = None
        if item.kind.startswith("enum_") or item.kind in {"constexpr_condition", "selection_condition"}:
            enum_extension = {"org.rvport.kb-scanner.architecture-enum": {**metadata, "evidenceKind": item.kind}}
        if "rpm" in metadata:
            enum_extension = {"org.rvport.kb-scanner.rpm": {**metadata["rpm"], "evidenceKind":item.kind}}
        if "autoconf" in metadata:
            enum_extension = {"org.rvport.kb-scanner.autoconf": metadata["autoconf"]}
        step = Step(
            id=stable_view_id("step", "evidence", item.id), kind=kind,
            title=f"{kind.replace('_', ' ').title()}: {_value(item.value)}", status=status,
            reason=f"Observed by {item.producer}.", reasonCodes=reasons,
            symbol=_value(item.value), configuration=metadata.get("configuration") or metadata.get("compilationContextId"),
            selectionStatus=selection, source=self._source_context.project(item.location),
            provenance=(TypedRef(kind="evidence", id=item.id, producer=item.producer),),
            extensions=enum_extension,
        )
        self._step_cache[item.id] = step
        return step

    def _knowledge_steps(self, ids: Iterable[str], missing: list[str]) -> list[Step]:
        sources = {item.id: item for item in self._knowledge.sources}
        result = []
        for entry_id in sorted(ids):
            entry = self._index.knowledge.get(entry_id)
            if entry is None:
                missing.append(entry_id)
                continue
            source = next((sources[item] for item in entry.source_ids if item in sources), None)
            if source is None:
                missing.append(entry.source_ids[0])
                continue
            provenance = (TypedRef(kind="knowledge", id=entry.id, producer=source.source_id),)
            result.append(Step(
                id=stable_view_id("step", "knowledge", entry.id), kind="knowledge_match",
                title=f"Knowledge matches {entry.canonical_value}", status="complete",
                reason="The normalized value matches the loaded knowledge snapshot.", reasonCodes=(),
                symbol=entry.canonical_value,
                knowledge=KnowledgeProjection(entryId=entry.id, category=entry.fact_kind, symbol=entry.canonical_value, producer=source.source_id, sourceId=source.source_id, sourceSha256=source.sha256, provenance=provenance),
                provenance=provenance,
            ))
        return result


def _value(value: object) -> str:
    compatible = to_json_compatible(value)
    return compatible if isinstance(compatible, str) else str(compatible)


def _evidence_key(item) -> tuple[object, ...]:
    start = item.location.start
    return (start.path, start.line, start.column or 1, item.id)


def _relation_completion(status: str) -> str:
    if status in {"inactive", "not_selected"}:
        return "inactive"
    if status == "selected":
        return "complete"
    return status if status in {"complete", "partial", "ambiguous", "unknown"} else "unknown"


def _completion(classification) -> str:
    selection = classification.metadata.get("selectionStatus")
    if classification.impact == "guarded_inactive" or selection in {"inactive", "not_selected"}:
        return "inactive"
    if classification.metadata.get("confirmedExecutionDependency") is True and classification.impact == "actionable_non_blocking":
        # Impact completeness is not source-chain completeness. Actual missing
        # source edges, contexts and references are checked by build().
        return "complete"
    completeness = classification.metadata.get("completeness")
    return completeness if completeness in {"complete", "partial", "ambiguous", "unknown"} else "complete"


def _reason_codes(classification) -> tuple[str, ...]:
    if _completion(classification) == "inactive":
        return ("inactive_branch",)
    return ()
