from __future__ import annotations

import re

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


_X86_PATH = re.compile(r"(?i)(?:^|[/_.-])(?:x86(?:_64)?|amd64|i[3-6]86|avx\w*|sse\w*)(?:$|[/_.-])")
_RELEVANT_KINDS = {"build_source", "build_argument", "build_dependency", "build_arch_constraint"}


class BuildSelectionDetector:
    __slots__ = ()

    def detect(
        self,
        context: ScanContext,
        facts: NormalizedSnapshot,
        analysis: AnalysisSnapshot,
        knowledge: KnowledgeSnapshot,
    ) -> StageResult[DetectionSnapshot]:
        evidence_ids = {item.id for item in facts.evidence}
        evidence_by_id = {item.id: item for item in facts.evidence}
        entity_ids = {item.id for item in analysis.entities}
        observations: dict[str, Observation] = {}
        flag_knowledge = {
            entry.canonical_value.lower(): entry
            for entry in knowledge.entries
            if entry.architecture_family.lower() == context.source_arch and entry.fact_kind == "compilerFlags"
        }
        for relation in analysis.relations:
            if relation.source_id not in entity_ids or relation.target_id not in entity_ids or not set(relation.evidence_ids).issubset(evidence_ids):
                return _failed("build-detector.invalid-relation-reference", "Build relation references unknown Entity or Evidence")
            issue = relation.metadata.get("issue")
            item_kind = relation.metadata.get("itemKind")
            value = str(relation.metadata.get("value", ""))
            rpm = relation.metadata.get("rpm")
            cmake = relation.metadata.get("cmake")
            autoconf = relation.metadata.get("autoconf")
            if autoconf is not None:
                outcome = autoconf.get("outcome")
                if issue == "missing_riscv_selection_path":
                    _add_observation(observations, "missing_riscv_selection_path", relation, (), "candidate", "high", relation.target_id)
                elif outcome == "unknown" or outcome == "rejected" and (not autoconf.get("bound") or relation.status == "unknown"):
                    _add_observation(observations, "build_selection_unknown", relation, (), "candidate", "medium", relation.target_id)
                continue
            if issue == "missing_riscv_selection_path":
                _add_observation(observations, "missing_riscv_selection_path", relation, (), "candidate", "high", relation.target_id)
                continue
            if issue == "missing_riscv_family_member":
                # 架构家族升格条目（architecture_family/absence）：构建已选中源架构
                # 成员且家族内无 riscv/generic 成员。kind 同名、级别同 selection_gap。
                _add_observation(observations, "missing_riscv_family_member", relation, (), "candidate", "high", relation.target_id)
                continue
            if item_kind not in _RELEVANT_KINDS:
                continue
            matches = tuple(
                sorted(
                    entry.id
                    for flag, entry in flag_knowledge.items()
                    if value.lower() == flag or value.lower().startswith(flag + "=")
                )
            )
            x86_shaped = bool(matches) or bool(_X86_PATH.search(value))
            subjects = sorted({evidence_by_id[item].subject_id for item in relation.evidence_ids})
            semantic_root = subjects[0] if subjects else relation.target_id
            if rpm is not None:
                if item_kind == 'build_arch_constraint' and relation.status != 'unknown' and rpm.get('targetAllowed') is True:
                    continue
                # Raw names and architecture macros are facts, not confirmed x86 code.
                if rpm.get("targetAdaptive") and relation.status != "unknown":
                    continue
                related = x86_shaped or rpm.get("architectureRelated") or rpm.get("x86Confirmed")
                if not related:
                    continue
                confirmed = bool(matches) or rpm.get("x86Confirmed")
                kind = "build_selection_unknown"
                if relation.status in {"inactive","not_selected"}:
                    kind = "build_inactive_x86_path"
                elif relation.status == "selected" and confirmed:
                    kind = "build_selected_x86_dependency"
                _add_observation(observations,kind,relation,matches,"confirmed_local" if confirmed else "candidate",
                                 "high" if confirmed else "medium",relation.target_id)
                continue
            if cmake and x86_shaped and not matches:
                # A selected filename is not proof that its contents require x86.
                # The source dependency analyzer supplies knowledge-backed edges.
                _add_observation(observations, "build_selection_unknown", relation, (), "candidate", "medium", semantic_root)
                continue
            if x86_shaped and relation.status == "unknown":
                _add_observation(observations, "build_selection_unknown", relation, matches,
                                 "confirmed_local" if cmake and matches else "candidate", "medium", semantic_root)
            elif x86_shaped and relation.status == "selected":
                _add_observation(observations, "build_selected_x86_dependency", relation, matches, "confirmed_local" if matches else "candidate", "high" if matches else "medium", semantic_root)
            elif x86_shaped and relation.status in {"inactive", "not_selected"}:
                _add_observation(observations, "build_inactive_x86_path", relation, matches, "candidate", "high", semantic_root)
        return StageResult(DetectionSnapshot(tuple(sorted(observations.values(), key=lambda item: (item.kind, item.id)))))


def _add_observation(
    output: dict[str, Observation],
    kind: str,
    relation,
    knowledge_ids: tuple[str, ...],
    status: str,
    confidence: str,
    semantic_root: str,
) -> None:
    observation_id = stable_id("observation", 1, "BUILD.SELECTION", kind, relation.id, knowledge_ids, status)
    output[observation_id] = Observation(
        observation_id,
        "BUILD.SELECTION",
        kind,
        relation.evidence_ids,
        (relation.id,),
        confidence,
        status,
        {
            "basis": "build-selection-proof",
            **({"compilerFlagValue": str(relation.metadata.get("value", ""))}
               if relation.metadata.get("itemKind") == "build_argument" else {}),
            "selectionStatus": relation.status,
            "reason": relation.metadata.get("reason", ""),
            "configuration": relation.metadata.get("configuration", "default"),
            "rootCauseId": semantic_root,
            **({"cmake": dict(relation.metadata["cmake"]),
                "matchedEvidenceIds": tuple(relation.metadata.get("matchedEvidenceIds", ()))} if "cmake" in relation.metadata else {}),
            **({"autoconf": dict(relation.metadata["autoconf"]),
                "matchedEvidenceIds": tuple(relation.metadata.get("matchedEvidenceIds", ()))} if "autoconf" in relation.metadata else {}),
            **({"rpm":dict(relation.metadata["rpm"]),
                "matchedEvidenceIds":tuple(relation.metadata.get("matchedEvidenceIds",()))} if "rpm" in relation.metadata else {}),
        },
        knowledge_ids,
    )


def _failed(code: str, message: str) -> StageResult[DetectionSnapshot]:
    diagnostic = Diagnostic(code, "detector.build-selection", DiagnosticSeverity.ERROR, message, producer="build-selection-detector", affects_exit_code=True)
    return StageResult(None, CompletionStatus.FAILED, (diagnostic,))
