from __future__ import annotations

from dataclasses import dataclass
from types import MappingProxyType

from ..core import Classification, Observation
from ..detectors.definitions import DEFAULT_DEFINITIONS
from ..classifiers.risk import present_risk


@dataclass(frozen=True, slots=True)
class Presentation:
    rule_id: str
    title: str
    title_en: str
    reason: str
    reason_en: str
    suggestion: str
    suggestion_en: str
    source_kind: str = "code"


_RULE_IDS = MappingProxyType({
    "C_CPP.X86.HEADER": "C_CPP.X86.HEADER",
    "C_CPP.X86.INTRINSIC": "C_CPP.X86.INTRINSIC_CALL",
    "C_CPP.X86.MACRO_REPLACEMENT_INTRINSIC": "C_CPP.X86.MACRO_EXPANDS_TO_INTRINSIC",
    "C_CPP.X86.MACRO_REPLACEMENT_BUILTIN": "C_CPP.X86.BUILTIN_CALL",
    "C_CPP.X86.MACRO_ARGUMENT_INTRINSIC": "C_CPP.X86.MACRO_EXPANDS_TO_INTRINSIC",
    "C_CPP.X86.MACRO_ARGUMENT_BUILTIN": "C_CPP.X86.BUILTIN_CALL",
    "C_CPP.X86.BUILTIN": "C_CPP.X86.BUILTIN_CALL",
    "C_CPP.X86.VECTOR_TYPE": "C_CPP.X86.VECTOR_TYPE",
    "C_CPP.X86.PLATFORM_MACRO": "C_CPP.X86.PLATFORM_MACRO",
    "C_CPP.X86.PLATFORM_CONDITION": "C_CPP.X86.PLATFORM_MACRO",
    "C_CPP.X86.COMPILER_FLAG": "C_CPP.X86.COMPILER_FLAG",
    "C_CPP.X86.CPU_PLATFORM_API": "C_CPP.X86.API_CALL",
    "C_CPP.X86.ASM_INSTRUCTION": "ASM.X86.INSTRUCTION",
    "C_CPP.X86.ASM_REGISTER": "C_CPP.X86.REGISTER_NAME",
    "C_CPP.X86.TARGET_FEATURE": "C_CPP.X86.TARGET_ATTRIBUTE",
    "C_CPP.MIGRATION.FIXED_VECTOR_WIDTH": "C_CPP.GCC.VECTOR_SIZE",
    "C_CPP.X86.ARCH_ALIAS_ATTRIBUTE": "C_CPP.X86.TARGET_ATTRIBUTE",
    "C_CPP.X86.COMMENT_HINT": "COMMENT.ARCH.HINT",
    "C_CPP.X86.INLINE_ASM_CANDIDATE": "C_CPP.X86.INLINE_ASM",
    "C_CPP.X86.ATTRIBUTE_CANDIDATE": "C_CPP.X86.TARGET_ATTRIBUTE",
    "C_CPP.X86.ARCH_MACRO_CANDIDATE": "C_CPP.X86.PLATFORM_MACRO",
    "C_CPP.X86.ARCH_CONDITION_CANDIDATE": "C_CPP.X86.PLATFORM_MACRO",
    "C_CPP.X86.ARCH_ENUM_MEMBER_CANDIDATE": "C_CPP.X86.ARCH_ENUM_CANDIDATE",
    "C_CPP.X86.ARCH_ENUM_REFERENCE_CANDIDATE": "C_CPP.X86.ARCH_ENUM_CANDIDATE",
    "BUILD.SELECTION": "BUILD.X86.SELECTION",
    "TEST.SELECTION": "TEST.X86.SELECTION",
    "function-multiversion": "C_CPP.X86.FUNCTION_MULTIVERSION",
})


class ProjectionPolicy:
    __slots__ = ("policy_id", "version", "_rule_ids")

    def __init__(self, policy_id: str = "kb-scanner.compatible-projection", version: int = 1) -> None:
        if not policy_id or version < 1:
            raise ValueError("projection policy identity is invalid")
        self.policy_id = policy_id
        self.version = version
        self._rule_ids = _RULE_IDS

    def present(self, observation: Observation, classification: Classification) -> Presentation:
        try:
            rule_id = self._rule_ids[observation.detector_id]
        except KeyError as exc:
            raise ValueError(f"unmapped detector: {observation.detector_id}") from exc
        if observation.detector_id == "BUILD.SELECTION" and observation.kind == "build_selected_x86_dependency" and observation.knowledge_ids:
            rule_id = "C_CPP.X86.COMPILER_FLAG"
        if observation.kind == "architecture_enum_candidate" and observation.metadata.get("selectionContext") == "if_constexpr":
            rule_id = "CPP.X86.CONSTEXPR_ARCH_BRANCH"
        title = _title(observation.kind)
        if observation.detector_id == "function-multiversion":
            from .v2.suggestions import select_suggestion
            suggestion = select_suggestion(classification.impact,
                str(classification.metadata.get("completeness", "unknown")), observation.kind)
            gap = observation.kind == "function_multiversion_fallback_gap"
            return Presentation(rule_id,
                "函数多版本 default 缺口" if gap else "函数多版本证据待补齐",
                "Function multiversion default gap" if gap else "Function multiversion evidence incomplete",
                suggestion.zh, suggestion.en, suggestion.zh, suggestion.en)
        return Presentation(
            rule_id,
            title,
            title,
            classification.reason,
            classification.reason,
            "请根据证据链补充 RISC-V 或通用实现，并验证相关构建与测试路径。",
            "Provide a RISC-V or portable implementation using the evidence trail, then validate its build and test path.",
            _source_kind(observation.kind),
        )

    def fields(self, observation: Observation, classification: Classification) -> dict[str, object]:
        impact = classification.impact
        risk = present_risk(classification, observation)
        gap = observation.kind in {"riscv_test_coverage_gap", "missing_riscv_selection_path", "missing_riscv_family_member"} or classification.metadata.get("portabilityGap") is True
        return {
            "severity": risk.severity,
            "finding_type": "hint" if impact == "hint" else "guarded" if impact == "guarded_inactive" else "portability_gap" if gap else "issue",
            "is_actionable": risk.actionable,
            "is_blocking": risk.blocking,
            "arch_reachability": "selected" if impact == "blocking_issue" else "inactive" if impact == "guarded_inactive" else "unknown",
        }

    def validate_completeness(self) -> tuple[str, ...]:
        expected = {item.detector_id for item in DEFAULT_DEFINITIONS.values()} | {"BUILD.SELECTION", "TEST.SELECTION", "function-multiversion"}
        return tuple(sorted(expected - set(self._rule_ids)))

    @property
    def rule_ids(self) -> tuple[str, ...]:
        return tuple(sorted(set(self._rule_ids.values())))


def _title(kind: str) -> str:
    return kind.replace("_", " ").strip().title()


def _source_kind(kind: str) -> str:
    if kind.startswith("build_") or kind in {"missing_riscv_selection_path", "missing_riscv_family_member"}:
        return "build"
    if "test" in kind:
        return "test"
    if "comment" in kind:
        return "comment"
    return "code"


DEFAULT_PROJECTION_POLICY = ProjectionPolicy()
