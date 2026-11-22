from __future__ import annotations

from ...core import ClassificationSnapshot
from ...core.metadata import to_json_compatible
from .chain import EvidenceChainBuilder
from .context import SourceContextProjector
from .index import SnapshotIndex
from .models import Conclusion, Fact, Finding, Occurrence, TypedRef, confidence_value, stable_view_id
from .evidence_kinds import intrinsic_symbols, is_intrinsic, proven_macro_terminals
from .suggestions import select_suggestion
from ...classifiers.risk import present_risk
from .families import FamilyProjection
from .multiversion import MultiversionProjection
from .candidate_usage import CandidateUsageProjection
from .macro_effects import MacroEffectsProjection
from .test_content import TestContentProjection


_FACT_KIND = {
    "include": "header_include", "call_expression": "intrinsic_call", "macro_expansion": "macro_invocation",
    "macro_replacement_token": "macro_replacement", "type_reference": "vector_type",
    "macro_argument_token": "macro_replacement",
    "compiler_argument": "compiler_flag", "build_argument": "build_argument", "architecture_probe": "architecture_probe",
    "test_asset": "test_asset",
}


class V2FindingMapper:
    def __init__(self, index: SnapshotIndex, chain_builder: EvidenceChainBuilder, source_context: SourceContextProjector, usage=None, *, architecture_family: str = "x86") -> None:
        self._index = index
        self._chain_builder = chain_builder
        self._source_context = source_context
        self._intrinsic_symbols = intrinsic_symbols(index.knowledge.values(), architecture_family)
        self._macro_terminals = proven_macro_terminals(index.relations.values())
        self._macro_effects = MacroEffectsProjection(index.relations.values())
        self._families = FamilyProjection(index.entities.values())
        self._multiversion = MultiversionProjection(index.entities.values(), index.observations.values(), source_context)
        self._usage = usage or CandidateUsageProjection(index, source_context)
        self._test_content = TestContentProjection(index, source_context)

    def map(self, snapshot: ClassificationSnapshot) -> tuple[Finding, ...]:
        findings = [self._finding(item) for item in sorted(snapshot.classifications, key=lambda value: value.id)]
        return tuple(sorted((item for item in findings if item is not None), key=lambda item: item.id))

    def _finding(self, classification):
        observations = [self._index.observations[item] for item in classification.observation_ids if item in self._index.observations]
        evidence_ids = set(classification.evidence_ids)
        for observation in observations:
            evidence_ids.update(observation.evidence_ids)
        evidence = [self._index.evidence[item] for item in evidence_ids if item in self._index.evidence and self._index.evidence[item].location is not None]
        if not evidence:
            return None
        enum_candidate = any(item.kind == "architecture_enum_candidate" for item in observations)
        matched = {eid for item in observations for eid in item.metadata.get("matchedEvidenceIds", ())}
        evidence.sort(key=lambda item: (bool(matched) and item.id not in matched, _evidence_key(item, enum=enum_candidate)))
        primary = evidence[0]
        observation = min(observations, key=lambda item: (item.detector_id, item.kind, item.id)) if observations else None
        occurrences = tuple(sorted((self._occurrence(item, cfg) for item in evidence
            for cfg in (self._macro_effects.configurations(item.id)[:32] or (None,))), key=_occurrence_key))
        risk = present_risk(classification, observation)
        status = risk.status
        impact = classification.impact
        blocking, actionable = risk.blocking, risk.actionable
        selection = "inactive" if status == "inactive" else _selection(primary)
        symbol = _value(primary.value)
        source = self._source_context.project(primary.location)
        producer = primary.producer
        provenance = (TypedRef(kind="evidence", id=primary.id, producer=producer),)
        fact_kind = "intrinsic_call" if is_intrinsic(primary, self._intrinsic_symbols) else _FACT_KIND.get(primary.kind, "architecture_probe")
        if primary.id in self._macro_terminals and primary.value in self._intrinsic_symbols:
            fact_kind = 'intrinsic_call'
        if primary.kind == "call_expression" and not is_intrinsic(primary, self._intrinsic_symbols):
            fact_kind = "architecture_probe"
        finding_type = observation.kind if observation else "unknown"
        suggestion = select_suggestion(impact, status, finding_type, confirmed_local=classification.metadata.get("confirmedExecutionDependency") is True and impact == "actionable_non_blocking",
                                       reason=classification.reason)
        return Finding(
            id=stable_view_id("finding", classification.id, finding_type, status, _configuration(primary)),
            fact=Fact(
                kind=fact_kind, symbol=symbol, matchedText=symbol, primaryLocation=source, provenance=provenance,
                extensions={**((_enum_extension(primary) if enum_candidate else _autoconf_extension(primary) or _rpm_extension(primary)) or {}),
                            **self._families.finding({item.subject_id for item in evidence}),
                            **self._multiversion.finding(evidence_ids),
                            **self._usage.finding(observations), **self._macro_effects.extension(primary.id),
                            **self._test_content.finding(evidence_ids)} or None,
            ),
            conclusion=Conclusion(
                title="函数多版本 default 缺口" if finding_type == "function_multiversion_fallback_gap" else "函数多版本待核查" if finding_type == "function_multiversion_unknown" else finding_type.replace("_", " ").title(), severity=risk.severity,
                confidence=confidence_value(classification.confidence), findingType=finding_type,
                actionable=actionable, blocking=blocking, archReachability=selection, status=status,
                reason=classification.reason,
                suggestion=suggestion.zh, suggestion_en=suggestion.en,
                extensions={
                    "org.rvport.kb-scanner.classification": {
                        "portabilityGap": bool(classification.metadata.get("portabilityGap")),
                    }
                },
            ),
            occurrences=occurrences,
            primaryOccurrenceId=next(o.id for o in occurrences if any(p.id==primary.id for p in o.provenance)),
            evidenceChain=self._chain_builder.build(classification, anchor_id=primary.id, occurrence_ids=tuple(sorted(matched))),
        )

    def _occurrence(self, item, configuration=None) -> Occurrence:
        use = self._macro_effects.uses.get(item.id, {}).get(configuration, {})
        selection = use.get('selectionStatus', _selection(item))
        configuration = configuration or _configuration(item)
        status = "inactive" if selection in {"inactive", "not_selected"} else "complete"
        return Occurrence(
            id=stable_view_id("occurrence", item.id, configuration, selection), kind=item.kind,
            location=self._source_context.project(item.location), configuration=configuration,
            selectionStatus=selection, status=status,
            reasonCodes=("inactive_branch",) if status == "inactive" else (),
            provenance=(TypedRef(kind="evidence", id=item.id, producer=item.producer),),
            extensions=_enum_extension(item) if item.kind.startswith("enum_") or item.kind in {"constexpr_condition", "selection_condition"} else _autoconf_extension(item) or _rpm_extension(item),
        )


def _value(value: object) -> str:
    compatible = to_json_compatible(value)
    return compatible if isinstance(compatible, str) else str(compatible)


def _configuration(item) -> str:
    return str(item.metadata.get("configuration") or item.metadata.get("compilationContextId") or "default")


def _selection(item) -> str:
    value = item.metadata.get("selectionStatus", "unknown")
    return value if value in {"selected", "inactive", "not_selected", "unknown"} else "unknown"


def _status(classification, observation) -> str:
    if classification.impact == "guarded_inactive":
        return "inactive"
    value = classification.metadata.get("completeness")
    if value in {"partial", "ambiguous", "unknown"}:
        return value
    if observation is not None and observation.status in {"partial", "ambiguous", "unknown"}:
        return observation.status
    return "unknown" if classification.impact == "unknown" else "complete"


def _severity(impact: str) -> str:
    return {"blocking_issue": "high", "actionable_non_blocking": "medium", "guarded_inactive": "low", "hint": "info", "unknown": "medium"}[impact]


def _evidence_key(item, *, enum: bool = False):
    start = item.location.start
    priority = {"enum_member_reference": 0, "constexpr_condition": 1, "selection_condition": 1, "enum_member_declaration": 2, "enum_declaration": 3}.get(item.kind, 4) if enum else 0
    return priority, start.path, start.line, start.column or 1, item.id


def _occurrence_key(item):
    start = item.location.span.start
    return item.location.file, start.line, start.column, item.id


def _enum_extension(item) -> dict[str, dict[str, object]]:
    fields = {
        key: to_json_compatible(item.metadata[key])
        for key in (
            "enumName", "memberName", "qualifiedName", "bindingStatus", "referenceContext",
            "selectionContext", "templateDependent", "enumStyle", "initializerPresent",
        )
        if key in item.metadata
    }
    fields["evidenceKind"] = item.kind
    return {"org.rvport.kb-scanner.architecture-enum": fields}


def _rpm_extension(item):
    if "rpm" not in item.metadata:
        return None
    return {"org.rvport.kb-scanner.rpm": {**to_json_compatible(item.metadata["rpm"]), "evidenceKind":item.kind}}


def _autoconf_extension(item):
    if "autoconf" not in item.metadata:
        return None
    return {"org.rvport.kb-scanner.autoconf": to_json_compatible(item.metadata["autoconf"])}
