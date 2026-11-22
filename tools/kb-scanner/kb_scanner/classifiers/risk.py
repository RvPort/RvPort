"""Shared risk priority; certainty and blocking are independent dimensions."""
from dataclasses import dataclass


DEPENDENCIES = frozenset({"x86_header_reference", "x86_intrinsic_reference", "x86_builtin_reference",
    "x86_vector_type_reference", "x86_compiler_flag", "x86_cpu_platform_api", "x86_assembly_instruction",
    "x86_register_reference", "x86_target_feature", "build_selected_x86_dependency", "build_selection_unknown",
    "arm64_header_reference", "arm64_intrinsic_reference", "arm64_builtin_reference",
    "arm64_vector_type_reference", "arm64_compiler_flag", "arm64_cpu_platform_api",
    "arm64_assembly_instruction", "arm64_register_reference", "arm64_target_feature"})


def confirmed_dependency(observation):
    return (observation is not None and observation.status == "confirmed_local"
            and observation.kind in DEPENDENCIES and bool(observation.knowledge_ids)
            and not unproven_compiler_flag(observation)
            and not observation.metadata.get("rpm", {}).get("softDependency"))


def unproven_compiler_flag(observation):
    # Presence in an x86 compiler manual is not proof of x86-specific semantics.
    # These option names also accept RISC-V/other target values or host defaults.
    value = str(observation.metadata.get("compilerFlagValue", ""))
    return value.partition("=")[0] in {"-march", "-mtune", "-mcpu", "-mabi", "-target", "--target"}


@dataclass(frozen=True)
class Risk:
    severity: str
    status: str
    actionable: bool
    blocking: bool


def present_risk(classification, observation=None):
    impact = classification.impact
    defaults = {"blocking_issue":"high", "actionable_non_blocking":"medium", "guarded_inactive":"low",
                "hint":"info", "unknown":"medium"}
    if impact not in defaults:
        raise ValueError("classification-risk-unknown-impact")
    status = classification.metadata.get("completeness", "complete")
    if observation is not None and observation.status in {"partial", "unknown", "ambiguous"}:
        status = observation.status
    if impact == "guarded_inactive":
        return Risk("low", "inactive", False, False)
    if impact == "unknown" and status == "complete":
        status = "unknown"
    local = confirmed_dependency(observation) or classification.metadata.get("confirmedExecutionDependency") is True
    if local and impact in {"actionable_non_blocking", "unknown"}:
        return Risk("high", status if status != "complete" else "partial", True, False)
    blocking = impact == "blocking_issue" and status == "complete"
    return Risk(defaults[impact], status, blocking or impact == "actionable_non_blocking" and status == "complete", blocking)
