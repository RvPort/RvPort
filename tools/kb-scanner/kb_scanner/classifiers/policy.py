from __future__ import annotations

from dataclasses import dataclass

from ..core import Observation
from ..core.candidate_contract import invalid_candidate
from .risk import confirmed_dependency, unproven_compiler_flag


@dataclass(frozen=True, slots=True)
class ClassificationDecision:
    impact: str
    reason: str
    requires_complete_proof: bool = False


@dataclass(frozen=True, slots=True)
class ClassificationPolicy:
    policy_id: str
    version: int

    def __post_init__(self) -> None:
        if not self.policy_id or self.version < 1:
            raise ValueError("classification policy requires an identity and positive version")

    def decide(
        self,
        observation: Observation,
        *,
        proof_complete: bool,
        relation_unknown: bool,
        selected_reachable: bool = False,
        inactive_reachable: bool = False,
    ) -> ClassificationDecision:
        kind = observation.kind
        multiversion = observation.metadata.get("functionMultiversion")
        if multiversion is not None:
            if multiversion.get("sourceExcluded") is True or (inactive_reachable and proof_complete and not relation_unknown):
                return ClassificationDecision("guarded_inactive", "multiversion-source-statically-excluded", True)
            gap = (kind == "function_multiversion_fallback_gap" and multiversion.get("defaultGap") is True
                   and multiversion.get("groupCompleteness") == "complete" and multiversion.get("ruleStatus") == "applicable")
            return ClassificationDecision("actionable_non_blocking" if gap else "unknown",
                str(multiversion.get("reason") or "multiversion-binding-ambiguous"))
        if invalid_candidate(observation):
            raise ValueError("candidate.invalid-admission")
        rpm = observation.metadata.get("rpm")
        autoconf = observation.metadata.get("autoconf")
        cmake = observation.metadata.get("cmake")
        if cmake is not None and cmake.get("rejection"):
            rejected = cmake.get("required") and proof_complete and not relation_unknown and observation.metadata.get("selectionStatus") == "selected"
            return ClassificationDecision("blocking_issue" if rejected else "unknown",
                                          "cmake-target-configuration-rejected" if rejected else "cmake-rejection-proof-incomplete", True)
        if autoconf is not None:
            rejected = kind == "missing_riscv_selection_path" and proof_complete and not relation_unknown
            return ClassificationDecision("actionable_non_blocking" if rejected else "unknown",
                                          str(autoconf.get("reason") or "autoconf-proof-incomplete"), True)
        if rpm is not None and rpm.get("softDependency") and kind == "build_selected_x86_dependency":
            return ClassificationDecision("actionable_non_blocking" if proof_complete and not relation_unknown else "unknown",
                                          "rpm-soft-dependency" if proof_complete and not relation_unknown else "rpm-soft-dependency-proof-incomplete", True)
        if kind == "architecture_enum_candidate":
            binding = observation.metadata.get("bindingStatus", "unknown")
            if binding != "complete" or observation.metadata.get("templateDependent") is True:
                return ClassificationDecision("unknown", "enum-selection-binding-incomplete", True)
            if observation.metadata.get("enumSelectionStatus") == "selected":
                return ClassificationDecision("blocking_issue", "selected-x86-enum-dependency", True)
            if observation.metadata.get("enumSelectionStatus") in {"inactive", "not_selected"}:
                return ClassificationDecision("guarded_inactive", "x86-enum-path-statically-excluded", True)
            if observation.metadata.get("enumDependencyComplete") is True:
                return ClassificationDecision("actionable_non_blocking", "guarded-x86-enum-dependency-target-unknown", True)
            return ClassificationDecision("unknown", "isolated-enum-candidate", True)
        if observation.metadata.get("branchRole") == "x86" and proof_complete and not relation_unknown:
            return ClassificationDecision("guarded_inactive", "x86-branch-statically-excluded-for-riscv", True)
        if unproven_compiler_flag(observation):
            return ClassificationDecision("unknown", "compiler-option-target-specificity-unproven")
        if selected_reachable and proof_complete and observation.status == "confirmed_local":
            return ClassificationDecision("blocking_issue", "selected-x86-dependency", True)
        if inactive_reachable and proof_complete and observation.status == "confirmed_local":
            return ClassificationDecision("guarded_inactive", "x86-path-statically-excluded", True)
        if confirmed_dependency(observation) and kind not in {"build_selected_x86_dependency", "riscv_test_blocker"} and not selected_reachable and not inactive_reachable:
            return ClassificationDecision("actionable_non_blocking", "confirmed-local-x86-dependency-target-unproven")
        if kind in {"x86_platform_macro_reference", "arm64_platform_macro_reference"} and not selected_reachable and not inactive_reachable:
            return ClassificationDecision("unknown", "architecture-macro-use-unproven")
        if kind.endswith("unknown") or relation_unknown:
            return ClassificationDecision("unknown", "input-or-relation-unknown")
        if kind in {"riscv_test_blocker", "build_selected_x86_dependency"}:
            if not proof_complete:
                return ClassificationDecision("unknown", "selected-x86-proof-incomplete", True)
            return ClassificationDecision("blocking_issue", "selected-x86-dependency", True)
        if kind in {"x86_test_guarded", "build_inactive_x86_path"}:
            if not proof_complete:
                return ClassificationDecision("unknown", "inactive-proof-incomplete", True)
            return ClassificationDecision("guarded_inactive", "x86-path-statically-excluded", True)
        if kind in {"riscv_test_coverage_gap", "missing_riscv_selection_path", "missing_riscv_family_member"}:
            return ClassificationDecision("actionable_non_blocking", "migration-or-test-gap")
        if observation.status == "hint" or str(observation.metadata.get("basis", "")).startswith("comment"):
            return ClassificationDecision("hint", "comment-only-hint")
        if observation.status == "confirmed_local":
            return ClassificationDecision("actionable_non_blocking", "confirmed-local-x86-dependency")
        return ClassificationDecision("unknown", "candidate-evidence-not-sufficient")


DEFAULT_CLASSIFICATION_POLICY = ClassificationPolicy("kb-scanner.migration-classification", 1)
