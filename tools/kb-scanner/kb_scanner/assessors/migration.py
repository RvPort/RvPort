from __future__ import annotations

from ..core import (
    AnalysisSnapshot,
    Assessment,
    AssessmentSnapshot,
    ClassificationSnapshot,
    CompletionStatus,
    Diagnostic,
    DiagnosticSeverity,
    ScanContext,
    StageResult,
    stable_id,
)
from .vocabulary import ASSESSMENT_DIMENSIONS


class MigrationAssessor:
    __slots__ = ()

    def assess(
        self,
        context: ScanContext,
        classifications: ClassificationSnapshot,
        analysis: AnalysisSnapshot,
    ) -> StageResult[AssessmentSnapshot]:
        del context
        invalid = _validate_references(classifications, analysis)
        if invalid is not None:
            return invalid
        scope = next((item for item in analysis.entities if item.kind == "assessment_scope"), None)
        scope_properties = {} if scope is None else scope.properties
        impacts = {item.impact for item in classifications.classifications}
        kinds = {item.kind for item in classifications.observations}
        roles = {
            str(item.metadata.get("role"))
            for item in analysis.relations
            if item.status == "selected" and item.metadata.get("role") not in {None, "", "unknown"}
        }
        selected_alternative = bool(roles & {"riscv", "generic", "portable", "scalar", "fallback"})
        architecture_independent = scope_properties.get("architectureIndependent") is True
        source_complete = scope_properties.get("sourceComplete") is True or any(item.kind == "source_file" for item in analysis.entities)
        build_complete = scope_properties.get("buildComplete") is True or any(
            item.kind in {"selects", "uses_source", "depends_on", "adds_argument", "requires", "excludes"} and item.status != "unknown"
            for item in analysis.relations
        )
        has_test_assets = any(item.kind in {"test_target", "test_candidate", "test_suite", "test_source"} for item in analysis.entities)
        test_not_applicable = scope_properties.get("testNotApplicable") is True or not has_test_assets
        test_complete = scope_properties.get("testComplete") is True or (
            any(item.kind == "test_target" for item in analysis.entities)
            and not any(item.kind == "test_candidate" for item in analysis.entities)
            and not any(item.kind == "selects_test" and item.status == "unknown" for item in analysis.relations)
        )
        packaging_present = any(item.kind == "package_section" for item in analysis.entities)
        packaging_complete = scope_properties.get("packagingComplete") is True or (
            packaging_present and not any(item.kind == "excludes" and item.status == "unknown" for item in analysis.relations)
        )
        classification_complete = "unknown" not in impacts and all(
            item.metadata.get("completeness", "complete") == "complete" for item in classifications.classifications)
        alternative_incomplete = any(
            item.metadata.get("selectedReachable") is True
            and item.metadata.get("alternativeComplete") is not True
            for item in classifications.classifications
        )
        complete = (
            source_complete
            and build_complete
            and (test_complete or test_not_applicable)
            and classification_complete
            and not alternative_incomplete
        )

        blocker = "blocking_issue" in impacts
        selected_x86_dependency = any(
            item.metadata.get("selectedReachable") is True
            for item in classifications.classifications
        )
        test_issue = bool(kinds & {"riscv_test_blocker", "riscv_test_coverage_gap"})
        missing_target = bool(kinds & {"missing_riscv_selection_path", "missing_riscv_family_member"})
        conflict = scope_properties.get("configurationConflict") is True or _configuration_conflict(analysis)

        dimensions = {
            "input_completeness": "satisfied" if complete else "unknown",
            "target_reachability": "satisfied" if architecture_independent or selected_alternative or selected_x86_dependency else "issue" if missing_target else "unknown",
            "x86_dependency": "issue" if blocker else "satisfied" if classification_complete else "unknown",
            "riscv_or_generic_alternative": (
                "not_applicable"
                if architecture_independent
                else "satisfied"
                if selected_alternative
                else "unknown"
                if alternative_incomplete
                else "issue"
                if blocker
                else "unknown"
            ),
            "test_selection_coverage": "not_applicable" if test_not_applicable else "issue" if test_issue else "satisfied" if test_complete else "unknown",
            "packaging_constraint": "satisfied" if packaging_complete else "not_applicable" if not packaging_present else "unknown",
        }
        state = _project_state(
            dimensions,
            roles,
            architecture_independent=architecture_independent,
            selected_alternative=selected_alternative,
            blocker=blocker,
            test_issue=test_issue,
            conflict=conflict,
            architecture_dependency_present=any(
                item.impact in {"blocking_issue", "actionable_non_blocking", "guarded_inactive"}
                for item in classifications.classifications
            ),
            has_input=bool(analysis.entities or classifications.classifications),
        )
        all_classification_ids = tuple(item.id for item in classifications.classifications)
        all_observation_ids = tuple(item.id for item in classifications.observations)
        all_evidence_ids = tuple(sorted({value for item in classifications.classifications for value in item.evidence_ids}))
        all_relation_ids = tuple(sorted({value for item in classifications.classifications for value in item.relation_ids}))
        all_proof_ids = tuple(sorted({value for item in classifications.classifications for value in item.proof_path_ids}))
        assessments: list[Assessment] = []
        for dimension in ASSESSMENT_DIMENSIONS:
            dimension_state = dimensions[dimension]
            assessments.append(
                Assessment(
                    id=stable_id("assessment", 1, "dimension", dimension, dimension_state, all_classification_ids),
                    kind="migration_dimension",
                    state=dimension_state,
                    evidence_ids=all_evidence_ids,
                    relation_ids=all_relation_ids,
                    confidence="high" if dimension_state in {"satisfied", "issue", "not_applicable"} else "unknown",
                    metadata={"dimension": dimension, "reason": _dimension_reason(dimension, dimension_state)},
                    classification_ids=all_classification_ids,
                    observation_ids=all_observation_ids,
                    proof_path_ids=all_proof_ids,
                )
            )
        project_metadata = {
            "dimensions": dimensions,
            "staticScopeOnly": True,
            "compiled": False,
            "testsExecuted": False,
            "completeness": {
                "source": source_complete,
                "build": build_complete,
                "test": test_complete,
                "testNotApplicable": test_not_applicable,
                "packaging": packaging_complete if packaging_present else "not_applicable",
                "classification": classification_complete,
                "selectedX86Dependency": selected_x86_dependency,
                "alternativeComplete": not alternative_incomplete,
            },
            "blockingDimensions": tuple(name for name, value in dimensions.items() if value in {"issue", "unknown"}),
            "configurationConflict": conflict,
        }
        assessments.append(
            Assessment(
                id=stable_id("assessment", 1, "project_migration_state", state, dimensions, all_classification_ids),
                kind="project_migration_state",
                state=state,
                evidence_ids=all_evidence_ids,
                relation_ids=all_relation_ids,
                confidence="high" if state not in {"provisional", "unknown"} else "unknown",
                metadata=project_metadata,
                classification_ids=all_classification_ids,
                observation_ids=all_observation_ids,
                proof_path_ids=all_proof_ids,
            )
        )
        partial = state in {"provisional", "unknown"}
        diagnostics = ()
        if partial:
            diagnostics = (
                Diagnostic(
                    "assessor.provisional",
                    "assessor",
                    DiagnosticSeverity.WARNING,
                    "Project migration state is provisional because static inputs are incomplete or conflicting",
                    producer="migration-assessor",
                    details={"reason": "assessment-input-incomplete", "state": state},
                ),
            )
        return StageResult(
            AssessmentSnapshot(tuple(sorted(assessments, key=lambda item: (item.kind, str(item.metadata.get("dimension", "")), item.id)))),
            CompletionStatus.PARTIAL if partial else CompletionStatus.COMPLETE,
            diagnostics,
        )


def _project_state(
    dimensions: dict[str, str],
    roles: set[str],
    *,
    architecture_independent: bool,
    selected_alternative: bool,
    blocker: bool,
    test_issue: bool,
    conflict: bool,
    architecture_dependency_present: bool,
    has_input: bool,
) -> str:
    if not has_input:
        return "unknown"
    if conflict:
        return "provisional"
    complete = dimensions["input_completeness"] == "satisfied"
    if architecture_independent and complete and not architecture_dependency_present:
        return "architecture_independent"
    if selected_alternative:
        if blocker or test_issue:
            return "partial_riscv"
        if any(value == "unknown" for value in dimensions.values()):
            return "provisional"
        if all(value in {"satisfied", "not_applicable"} for value in dimensions.values()):
            return "riscv_ready"
    if blocker and not selected_alternative and complete:
        return "x86_only"
    if len(roles) >= 2 and not roles & {"riscv", "generic", "portable", "scalar", "fallback"} and complete:
        return "multiarch_without_riscv"
    return "provisional" if not complete or has_input else "unknown"


def _configuration_conflict(analysis: AnalysisSnapshot) -> bool:
    states: dict[tuple[str, str], set[str]] = {}
    for relation in analysis.relations:
        configuration = str(relation.metadata.get("configuration", "default"))
        key = (relation.target_id, configuration)
        states.setdefault(key, set()).add(relation.status)
    return any("selected" in values and values & {"inactive", "not_selected"} for values in states.values())


def _dimension_reason(dimension: str, state: str) -> str:
    return f"{dimension}:{state}"


def _validate_references(
    classifications: ClassificationSnapshot,
    analysis: AnalysisSnapshot,
) -> StageResult[AssessmentSnapshot] | None:
    observation_ids = {item.id for item in classifications.observations}
    relation_ids = {item.id for item in analysis.relations}
    proof_ids = {item.id for item in analysis.proof_paths}
    for item in classifications.classifications:
        if not set(item.observation_ids).issubset(observation_ids):
            return _failed("assessor.invalid-observation-reference", "Classification references an unknown Observation")
        if not set(item.relation_ids).issubset(relation_ids):
            return _failed("assessor.invalid-relation-reference", "Classification references an unknown Relation")
        if not set(item.proof_path_ids).issubset(proof_ids):
            return _failed("assessor.invalid-proof-reference", "Classification references an unknown ProofPath")
    return None


def _failed(code: str, message: str) -> StageResult[AssessmentSnapshot]:
    diagnostic = Diagnostic(code, "assessor", DiagnosticSeverity.ERROR, message, producer="migration-assessor", affects_exit_code=True)
    return StageResult(None, CompletionStatus.FAILED, (diagnostic,))
