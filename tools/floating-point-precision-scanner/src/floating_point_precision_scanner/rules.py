"""版本化核心浮点迁移规则及纯数据 assessment pipeline。"""

from __future__ import annotations

import math
import struct
from collections import Counter
from collections.abc import Iterable, Iterator, Mapping, Sequence
from dataclasses import dataclass, replace
from decimal import Decimal, InvalidOperation
from typing import TYPE_CHECKING, Any, Literal

from . import RULE_REGISTRY_VERSION
from .candidates import construct, local_evidence
from .canonical import semantic_digest, stable_id
from .diagnostics import Diagnostic, diagnostic
from .reduction_candidates import ReductionCandidate, bound_evidence

if TYPE_CHECKING:
    from .build_contexts import BuildContext
    from .profiles import TargetProfile

AssessmentStatus = Literal["matched", "not_matched", "unknown", "skipped", "failed"]
IrCapability = Literal["none", "source-preserving", "build-faithful", "either"]
EvidenceMode = Literal["deterministic", "heuristic-capable"]


@dataclass(frozen=True, slots=True)
class RuleDescriptor:
    code: str
    version: str
    category: str
    source_fact_kinds: tuple[str, ...]
    ir_fact_kinds: tuple[str, ...]
    profile_fields: tuple[str, ...]
    build_context_fields: tuple[str, ...]
    ir_capability: IrCapability
    assessment_scope: str
    evidence_mode: EvidenceMode
    default_enabled: bool
    budget_key: str

    @property
    def requires_build_faithful_ir(self) -> bool:
        return self.ir_capability == "build-faithful"

    def to_dict(self) -> dict[str, Any]:
        return {
            "code": self.code,
            "version": self.version,
            "category": self.category,
            "source_fact_kinds": list(self.source_fact_kinds),
            "ir_fact_kinds": list(self.ir_fact_kinds),
            "profile_fields": list(self.profile_fields),
            "build_context_fields": list(self.build_context_fields),
            "ir_capability": self.ir_capability,
            "assessment_scope": self.assessment_scope,
            "evidence_mode": self.evidence_mode,
            "default_enabled": self.default_enabled,
            "budget_key": self.budget_key,
        }


RULES: tuple[RuleDescriptor, ...] = (
    RuleDescriptor(
        "fma-contraction",
        "1.2",
        "rounding-semantics",
        ("arithmetic",),
        ("call", "arithmetic"),
        ("fma",),
        (),
        "build-faithful",
        "expression",
        "deterministic",
        True,
        "max_rule_candidates",
    ),
    RuleDescriptor(
        "long-double-abi",
        "1.2",
        "abi",
        ("type",),
        (),
        ("floating_types",),
        (),
        "none",
        "declaration",
        "deterministic",
        True,
        "max_rule_candidates",
    ),
    RuleDescriptor(
        "unsafe-fp-optimization",
        "1.2",
        "optimization-semantics",
        (),
        ("arithmetic",),
        (),
        (),
        "either",
        "configuration-member",
        "deterministic",
        True,
        "max_rule_candidates",
    ),
    RuleDescriptor(
        "x87-excess-precision",
        "1.2",
        "evaluation-semantics",
        ("arithmetic", "conversion"),
        ("conversion",),
        ("evaluation",),
        (),
        "build-faithful",
        "evaluation-chain",
        "deterministic",
        True,
        "max_rule_candidates",
    ),
    RuleDescriptor(
        "floating-environment",
        "1.2",
        "environment-semantics",
        ("environment", "function_summary"),
        ("call", "constrained"),
        ("environment",),
        (),
        "either",
        "state-region",
        "deterministic",
        True,
        "max_rule_candidates",
    ),
    RuleDescriptor(
        "mixed-precision-conversion",
        "1.2",
        "precision-conversion",
        ("conversion",),
        ("conversion",),
        ("floating_types",),
        (),
        "none",
        "expression",
        "deterministic",
        True,
        "max_rule_candidates",
    ),
    RuleDescriptor(
        "fragile-fp-comparison",
        "1.2",
        "comparison-semantics",
        ("comparison",),
        ("comparison",),
        (),
        (),
        "either",
        "expression",
        "heuristic-capable",
        True,
        "max_rule_candidates",
    ),
    RuleDescriptor(
        "reduction-reassociation",
        "1.2",
        "reduction-semantics",
        ("reduction",),
        ("reduction", "arithmetic"),
        ("evaluation",),
        (),
        "either",
        "loop",
        "heuristic-capable",
        True,
        "max_rule_candidates",
    ),
    RuleDescriptor(
        "math-library-variance",
        "1.2",
        "library-semantics",
        ("call",),
        ("call",),
        (),
        ("libm",),
        "either",
        "call",
        "deterministic",
        True,
        "max_rule_candidates",
    ),
    RuleDescriptor(
        "architecture-specific-fp-api",
        "1.2",
        "portability",
        ("api", "call"),
        ("call",),
        ("target_features",),
        ("target_features",),
        "none",
        "call",
        "deterministic",
        True,
        "max_rule_candidates",
    ),
)
RULE_BY_CODE = {item.code: item for item in RULES}
DEFAULT_RULES = tuple(item.code for item in RULES if item.default_enabled)
LEGACY_DEFAULT_RULES = (
    "fma-contraction",
    "long-double-abi",
    "unsafe-fp-optimization",
    "x87-excess-precision",
)
RULE_REGISTRY_DIGEST = semantic_digest(
    {"version": RULE_REGISTRY_VERSION, "rules": [item.to_dict() for item in RULES]}
)


@dataclass(frozen=True, slots=True)
class EvidenceRef:
    kind: str
    ref: str

    def to_dict(self) -> dict[str, str]:
        return {"kind": self.kind, "ref": self.ref}


@dataclass(frozen=True, slots=True)
class RuleContext:
    source_profile: TargetProfile
    target_profile: TargetProfile
    source_bundle: Mapping[str, Any]
    ir_bundles: tuple[Mapping[str, Any], ...]
    evidence_bundles: tuple[Mapping[str, Any], ...]
    source_build_context: BuildContext | None = None
    target_build_context: BuildContext | None = None
    candidate_scope: str | None = None

    @property
    def scope(self) -> str:
        return self.candidate_scope or str(self.source_bundle.get("translation_unit_id", "unknown"))


@dataclass(frozen=True, slots=True)
class RuleAssessment:
    assessment_id: str
    rule_code: str
    rule_version: str
    scope: str
    status: AssessmentStatus
    reason_code: str
    evidence: tuple[EvidenceRef, ...]
    frontier: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "assessment_id": self.assessment_id,
            "rule_code": self.rule_code,
            "rule_version": self.rule_version,
            "scope": self.scope,
            "status": self.status,
            "reason_code": self.reason_code,
            "evidence": [item.to_dict() for item in self.evidence],
            "frontier": self.frontier,
        }


@dataclass(frozen=True, slots=True)
class RuleRun:
    assessments: tuple[RuleAssessment, ...]
    findings: tuple[Mapping[str, Any], ...]
    diagnostics: tuple[Diagnostic, ...]
    candidate_findings: tuple[Mapping[str, Any], ...] = ()

    @property
    def counts(self) -> dict[str, int]:
        counter = Counter(item.status for item in self.assessments)
        return {
            "planned": len(self.assessments),
            "completed": counter["matched"] + counter["not_matched"],
            "matched": counter["matched"],
            "not_matched": counter["not_matched"],
            "unknown": counter["unknown"],
            "skipped": counter["skipped"],
            "failed": counter["failed"],
        }


def normalize_rule_selection(
    rules: Mapping[str, Any] | None,
    *,
    schema_version: str = "1.4",
) -> tuple[tuple[str, ...], dict[str, str], list[Diagnostic]]:
    """Return stable rule codes, version constraints, and validation diagnostics."""
    defaults = LEGACY_DEFAULT_RULES if schema_version in {"1.0", "1.1", "1.2"} else DEFAULT_RULES
    if rules is None:
        return defaults, {}, []
    enabled_raw = rules.get("enabled")
    disabled_raw = rules.get("disabled", ())
    enabled = defaults if enabled_raw is None else tuple(str(item) for item in enabled_raw)
    disabled = tuple(str(item) for item in disabled_raw)
    versions_raw = rules.get("versions", {})
    versions = (
        {str(key): str(value) for key, value in versions_raw.items()}
        if isinstance(versions_raw, Mapping)
        else {}
    )
    errors: list[Diagnostic] = []
    duplicates = {item for item, count in Counter(enabled).items() if count > 1}
    overlap = set(enabled) & set(disabled)
    unknown = (set(enabled) | set(disabled) | set(versions)) - set(RULE_BY_CODE)
    bad_versions = {
        code
        for code, version in versions.items()
        if code in RULE_BY_CODE and version != RULE_BY_CODE[code].version
    }
    if duplicates or overlap:
        errors.append(
            diagnostic(
                "rule_configuration_conflict",
                "request_validation",
                "规则配置包含重复或启用/禁用冲突。",
                "Rule configuration contains duplicates or enabled/disabled conflicts.",
                suggestion=", ".join(sorted(duplicates | overlap)),
                suggestion_en=", ".join(sorted(duplicates | overlap)),
            )
        )
    if unknown or bad_versions:
        unavailable = sorted(unknown | bad_versions)
        errors.append(
            diagnostic(
                "rules_not_available",
                "request_validation",
                "请求包含未实现或版本不兼容的浮点规则。",
                "The request contains unavailable or version-incompatible floating-point rules.",
                suggestion=f"可用规则：{', '.join(DEFAULT_RULES)}。",
                suggestion_en=(
                    f"Available rules: {', '.join(DEFAULT_RULES)}. "
                    f"Invalid: {', '.join(unavailable)}."
                ),
            )
        )
    selected = (
        tuple(sorted(set(enabled) - set(disabled), key=DEFAULT_RULES.index)) if not unknown else ()
    )
    return selected, versions, errors


def _refs(values: Iterable[EvidenceRef]) -> tuple[EvidenceRef, ...]:
    return tuple(sorted(set(values), key=lambda item: (item.kind, item.ref)))


def _assessment(
    descriptor: RuleDescriptor,
    scope: str,
    status: AssessmentStatus,
    reason_code: str,
    evidence: Iterable[EvidenceRef] = (),
    frontier: str | None = None,
) -> RuleAssessment:
    ordered = _refs(evidence)
    identity = {
        "rule_code": descriptor.code,
        "rule_version": descriptor.version,
        "scope": scope,
        "status": status,
        "reason_code": reason_code,
        "evidence": [item.to_dict() for item in ordered],
        "frontier": frontier,
    }
    return RuleAssessment(
        stable_id("rule_assessment", identity),
        descriptor.code,
        descriptor.version,
        scope,
        status,
        reason_code,
        ordered,
        frontier,
    )


def _location_sort_key(value: Any) -> tuple[str, int, int]:
    if not isinstance(value, Mapping):
        return ("", 0, 0)
    point = value.get("expansion") or value.get("spelling") or value
    if not isinstance(point, Mapping):
        return ("", 0, 0)
    return (str(point.get("path", "")), int(point.get("line", 0)), int(point.get("column", 0)))


def _primary_location(facts: Sequence[Mapping[str, Any]]) -> Mapping[str, Any] | None:
    locations: list[Mapping[str, Any]] = []
    for item in facts:
        location = item.get("location")
        if isinstance(location, Mapping):
            locations.append(location)
    if not locations:
        return None

    return dict(sorted(locations, key=_location_sort_key)[0])


_MESSAGES: dict[str, dict[str, str]] = {
    "long-double-abi": {
        "title": "long double 跨架构 ABI 差异",
        "title_en": "Cross-architecture long double ABI difference",
        "message": "long double 穿越公开或持久化边界，源端与目标端表示或 ABI 不同。",
        "message_en": (
            "long double crosses a public or persistent boundary with different source "
            "and target representation or ABI."
        ),
        "impact": "参数、返回值、变参或二进制布局可能被错误解释。",
        "impact_en": (
            "Parameters, return values, variadic arguments, or binary layouts may be "
            "interpreted incorrectly."
        ),
        "suggestion": "隔离 ABI 边界并改用固定格式，或提供显式转换层。",
        "suggestion_en": (
            "Isolate the ABI boundary and use a fixed format or an explicit conversion layer."
        ),
        "test": "增加 x86/RISC-V ABI、变参与序列化往返测试。",
        "test_en": "Add x86/RISC-V ABI, variadic, and serialization round-trip tests.",
    },
    "x87-excess-precision": {
        "title": "x87 扩展中间精度依赖",
        "title_en": "Dependency on x87 excess intermediate precision",
        "message": "源端可能保留扩展中间精度，而目标端按声明类型舍入。",
        "message_en": (
            "The source may retain excess intermediate precision while the target rounds "
            "to the declared type."
        ),
        "impact": "存储边界和二次舍入可能改变比较、分支或最终数值。",
        "impact_en": (
            "Storage boundaries and double rounding may change comparisons, branches, "
            "or final values."
        ),
        "suggestion": "显式固定中间类型和存储边界，避免依赖扩展寄存器精度。",
        "suggestion_en": (
            "Fix intermediate types and storage boundaries explicitly instead of relying "
            "on extended register precision."
        ),
        "test": "使用接近舍入边界的输入执行双平台差分测试。",
        "test_en": "Run cross-platform differential tests with inputs near rounding boundaries.",
    },
    "fma-contraction": {
        "title": "FMA 融合策略差异",
        "title_en": "FMA contraction policy difference",
        "message": "乘加表达式可能在一个环境融合为一次舍入，在另一环境保持两次舍入。",
        "message_en": (
            "A multiply-add expression may use one fused rounding in one environment and "
            "two roundings in another."
        ),
        "impact": "结果的末位、阈值比较和可复现性可能变化。",
        "impact_en": "Low-order bits, threshold comparisons, and reproducibility may change.",
        "suggestion": "显式选择 fma 或关闭 contraction，并统一两端编译策略。",
        "suggestion_en": (
            "Select fma explicitly or disable contraction and align compiler policies on "
            "both targets."
        ),
        "test": "增加可区分融合与非融合结果的边界输入。",
        "test_en": "Add boundary inputs that distinguish fused from unfused results.",
    },
    "unsafe-fp-optimization": {
        "title": "不安全浮点优化语义已生效",
        "title_en": "Unsafe floating-point optimization semantics are active",
        "message": "有效编译选项或 IR flags 放宽了 IEEE 浮点语义。",
        "message_en": "Effective compiler options or IR flags relax IEEE floating-point semantics.",
        "impact": "NaN、Inf、±0、重结合、近似运算、errno 或异常行为可能变化。",
        "impact_en": (
            "NaN, Inf, signed zero, reassociation, approximation, errno, or exception "
            "behavior may change."
        ),
        "suggestion": "只启用经过验证的子选项，并在迁移构建中保持相同有效语义。",
        "suggestion_en": (
            "Enable only validated sub-options and keep effective semantics aligned in "
            "migration builds."
        ),
        "test": "覆盖 NaN、Inf、±0、极值和重结合敏感输入。",
        "test_en": (
            "Cover NaN, Inf, signed zero, extreme values, and reassociation-sensitive inputs."
        ),
    },
    "floating-environment": {
        "title": "浮点环境依赖可能改变结果",
        "title_en": "Floating-environment dependency may change results",
        "message": "动态舍入、异常标志或环境状态与有效编译语义存在迁移风险。",
        "message_en": (
            "Dynamic rounding, exception flags, or environment state conflicts with "
            "effective compilation semantics."
        ),
        "impact": "计算结果、异常观察和调用边界行为可能不同。",
        "impact_en": "Results, observed exceptions, and call-boundary behavior may differ.",
        "suggestion": "显式声明 fenv 访问，限制状态作用域并在所有出口恢复环境。",
        "suggestion_en": (
            "Declare fenv access, bound state scope, and restore the environment on every exit."
        ),
        "test": "覆盖各舍入模式、异常标志、特殊值和优化级别。",
        "test_en": (
            "Cover rounding modes, exception flags, special values, and optimization levels."
        ),
    },
    "mixed-precision-conversion": {
        "title": "隐式混合精度转换可能丢失信息",
        "title_en": "Implicit mixed-precision conversion may lose information",
        "message": "隐式转换的目标精度或范围小于源类型。",
        "message_en": "An implicit conversion has less precision or range than its source type.",
        "impact": "舍入、溢出或阈值比较结果可能变化。",
        "impact_en": "Rounding, overflow, or threshold comparisons may change.",
        "suggestion": "显式选择存储类型并验证值域；不要依赖隐式窄化。",
        "suggestion_en": (
            "Choose the storage type explicitly and validate the value range instead "
            "of relying on implicit narrowing."
        ),
        "test": "覆盖精确可表示边界、相邻不可表示值和范围极值。",
        "test_en": (
            "Cover exact-representability boundaries, adjacent unrepresentable values, "
            "and range extremes."
        ),
    },
    "fragile-fp-comparison": {
        "title": "浮点比较对舍入或特殊值敏感",
        "title_en": "Floating-point comparison is sensitive to rounding or special values",
        "message": "比较模式可能依赖精确舍入、错误 NaN 语义或未声明的领域容差。",
        "message_en": (
            "The comparison may depend on exact rounding, incorrect NaN semantics, "
            "or an unspecified domain tolerance."
        ),
        "impact": "控制流和终止条件可能在不同架构上分叉。",
        "impact_en": "Control flow and termination conditions may diverge across architectures.",
        "suggestion": "由领域策略选择绝对、相对或 ULP 容差，并显式处理 NaN/Inf。",
        "suggestion_en": (
            "Use a domain-approved absolute, relative, or ULP tolerance and handle "
            "NaN/Inf explicitly."
        ),
        "test": "覆盖近零、阈值两侧、NaN、Inf 和舍入边界。",
        "test_en": (
            "Cover near-zero values, both sides of thresholds, NaN, Inf, and rounding boundaries."
        ),
    },
    "reduction-reassociation": {
        "title": "浮点归约顺序可能改变",
        "title_en": "Floating-point reduction order may change",
        "message": "累加、点积或向量归约可能因重结合或向量化采用不同顺序。",
        "message_en": (
            "An accumulation, dot product, or vector reduction may use a different "
            "order after reassociation or vectorization."
        ),
        "impact": "病态输入、抵消和长归约的误差可能扩大。",
        "impact_en": (
            "Error may grow for ill-conditioned inputs, cancellation, and long reductions."
        ),
        "suggestion": "固定所需顺序或采用经过验证的补偿/成对归约算法。",
        "suggestion_en": (
            "Fix the required order or use a validated compensated or pairwise reduction."
        ),
        "test": "使用病态顺序、正负抵消和不同向量宽度重复测试。",
        "test_en": "Repeat with ill-conditioned orders, cancellation, and different vector widths.",
    },
    "math-library-variance": {
        "title": "数学库语义存在构建环境差异",
        "title_en": "Math-library semantics differ between build contexts",
        "message": "调用依赖的 libm、errno、异常或特殊值行为在两端不同。",
        "message_en": (
            "The call depends on libm, errno, exception, or special-value behavior "
            "that differs between contexts."
        ),
        "impact": "边界、极值和特殊值结果或副作用可能变化。",
        "impact_en": "Boundary, extreme, and special-value results or side effects may change.",
        "suggestion": "锁定数学库语义，并对依赖行为建立跨平台验证。",
        "suggestion_en": (
            "Pin math-library semantics and validate dependent behavior across platforms."
        ),
        "test": "覆盖特殊值、定义域边界、极值、errno、异常和 ULP。",
        "test_en": (
            "Cover special values, domain boundaries, extremes, errno, exceptions, and ULP error."
        ),
    },
    "architecture-specific-fp-api": {
        "title": "使用了架构专用浮点 API",
        "title_en": "Architecture-specific floating-point API is used",
        "message": "已解析调用依赖 x86 intrinsic、头文件或目标特性。",
        "message_en": "The resolved call depends on an x86 intrinsic, header, or target feature.",
        "impact": "RISC-V 构建可能缺少入口，或替代实现具有不同舍入和特殊值语义。",
        "impact_en": (
            "The RISC-V build may lack the entry point or an alternative may have "
            "different rounding and special-value semantics."
        ),
        "suggestion": "在受验证的目标分支中选择替代实现，并逐项比较语义。",
        "suggestion_en": (
            "Select an alternative in a validated target branch and compare semantics explicitly."
        ),
        "test": "比较 lane、舍入、NaN、Inf、±0 和异常行为。",
        "test_en": "Compare lane behavior, rounding, NaN, Inf, signed zero, and exceptions.",
    },
}


_VALIDATION_PLANS: dict[str, dict[str, Any]] = {
    "floating-environment": {
        "input_classes": ["rounding-boundary", "nan", "infinity", "subnormal"],
        "error_metrics": ["classification", "exceptions"],
        "threshold_source": "standard",
        "repeat_strategy": "source/target × rounding modes × optimization levels",
    },
    "mixed-precision-conversion": {
        "input_classes": ["exact-boundary", "adjacent-unrepresentable", "range-extreme"],
        "error_metrics": ["absolute", "relative", "ulp", "classification"],
        "threshold_source": "profile",
        "repeat_strategy": "source/target and storage boundaries",
    },
    "fragile-fp-comparison": {
        "input_classes": ["near-zero", "threshold-neighbors", "nan", "infinity"],
        "error_metrics": ["absolute", "relative", "ulp", "classification"],
        "threshold_source": "unknown",
        "repeat_strategy": "source/target across relevant optimization modes",
    },
    "reduction-reassociation": {
        "input_classes": ["cancellation", "ill-conditioned-order", "long-sequence"],
        "error_metrics": ["absolute", "relative", "ulp"],
        "threshold_source": "project-policy",
        "repeat_strategy": "repeat across order, vector width, and thread count",
    },
    "math-library-variance": {
        "input_classes": ["domain-boundary", "range-extreme", "nan", "infinity", "subnormal"],
        "error_metrics": ["ulp", "classification", "errno", "exceptions"],
        "threshold_source": "profile",
        "repeat_strategy": "source/target build contexts",
    },
    "architecture-specific-fp-api": {
        "input_classes": ["lane-boundary", "nan", "infinity", "signed-zero"],
        "error_metrics": ["ulp", "classification", "exceptions"],
        "threshold_source": "project-policy",
        "repeat_strategy": "x86 implementation versus each RISC-V alternative",
    },
}


def _finding(
    descriptor: RuleDescriptor,
    assessment: RuleAssessment,
    facts: Sequence[Mapping[str, Any]],
    context: RuleContext,
    semantic: Mapping[str, Any],
    *,
    severity: str = "medium",
    confidence: str = "high",
    requires_human_confirmation: bool = False,
    aggregation_key: str | None = None,
    evidence_origin: str = "deterministic",
    validation_plan: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    location = _primary_location(facts)
    text = _MESSAGES[descriptor.code]
    identity = {
        "rule_code": descriptor.code,
        "rule_version": descriptor.version,
        "scope": assessment.scope,
        "location": location,
        "evidence": [item.to_dict() for item in assessment.evidence],
        "source_profile": context.source_profile.fingerprint,
        "target_profile": context.target_profile.fingerprint,
        "source_build_context": (
            context.source_build_context.fingerprint if context.source_build_context else None
        ),
        "target_build_context": (
            context.target_build_context.fingerprint if context.target_build_context else None
        ),
        "evidence_origin": evidence_origin,
        "semantic": semantic,
    }
    requires_confirmation = (
        requires_human_confirmation
        or evidence_origin != "deterministic"
        or severity in {"critical", "high"}
    )
    return {
        "finding_id": stable_id("finding", identity),
        "finding_code": descriptor.code,
        "rule_version": descriptor.version,
        "assessment_id": assessment.assessment_id,
        "category": descriptor.category,
        "severity": severity,
        "confidence": confidence,
        "evidence_origin": evidence_origin,
        "requires_human_confirmation": requires_confirmation,
        "location": location,
        "related_locations": [dict(item["location"]) for item in facts
                              if isinstance(item.get("location"), Mapping)
                              and item["location"] != location],
        "affected_count": 1,
        "evidence": [item.to_dict() for item in assessment.evidence],
        "source_profile_fingerprint": context.source_profile.fingerprint,
        "target_profile_fingerprint": context.target_profile.fingerprint,
        "source_build_context_fingerprint": (
            context.source_build_context.fingerprint if context.source_build_context else None
        ),
        "target_build_context_fingerprint": (
            context.target_build_context.fingerprint if context.target_build_context else None
        ),
        "aggregation_key": aggregation_key,
        "title": text["title"],
        "title_en": text["title_en"],
        "message": text["message"],
        "message_en": text["message_en"],
        "impact": text["impact"],
        "impact_en": text["impact_en"],
        "suggestion": text["suggestion"],
        "suggestion_en": text["suggestion_en"],
        "test_suggestion": text["test"],
        "test_suggestion_en": text["test_en"],
        "validation_plan": dict(
            validation_plan
            or _VALIDATION_PLANS.get(
                descriptor.code,
                {
                    "input_classes": ["rounding-boundary"],
                    "error_metrics": ["ulp"],
                    "threshold_source": "profile",
                    "repeat_strategy": "source/target",
                },
            )
        ),
        "semantic": dict(semantic),
    }


def _facts(context: RuleContext) -> list[Mapping[str, Any]]:
    value = context.source_bundle.get("facts", [])
    return [item for item in value if isinstance(item, Mapping)] if isinstance(value, list) else []


def _profile_value(profile: TargetProfile, field: str) -> Mapping[str, Any] | None:
    value = profile.document.get(field)
    if not isinstance(value, Mapping) or value.get("status") != "known":
        return None
    payload = value.get("value")
    return payload if isinstance(payload, Mapping) else None


def _long_double(context: RuleContext) -> tuple[RuleAssessment, dict[str, Any] | None]:
    return _long_double_evaluations(context)[0]


def _long_double_evaluations(
    context: RuleContext,
) -> list[tuple[RuleAssessment, dict[str, Any] | None]]:
    descriptor = RULE_BY_CODE["long-double-abi"]
    boundary_kinds = {
        "parameter", "variadic_parameter", "return", "external_record_field",
        "persistent_field", "global",
    }
    unique: dict[str, Mapping[str, Any]] = {}
    for fact in _facts(context):
        if not isinstance(fact.get("payload"), Mapping):
            raise TypeError("invalid_type_fact_payload")
        payload = _payload(fact)
        location = fact.get("location")
        if (
            fact.get("fact_kind") == "type"
            and (
                payload.get("is_long_double") is True
                or "long double" in str(fact.get("type", ""))
            )
            and payload.get("origin", "project-physical") == "project-physical"
            and str(payload.get("boundary_kind")) in boundary_kinds
        ):
            key = semantic_digest(
                {
                    "kind": payload.get("declaration_kind"),
                    "name": payload.get("name"),
                    "boundary": payload.get("boundary_kind"),
                    "location": location,
                }
            )
            unique.setdefault(key, fact)
    candidates = [unique[key] for key in sorted(unique)]
    if not candidates:
        return [(_assessment(descriptor, context.scope, "not_matched", "no_abi_boundary"), None)]
    source_type = context.source_profile.document.get("floating_types", {}).get("long_double")
    target_type = context.target_profile.document.get("floating_types", {}).get("long_double")
    keys = (
        "format",
        "storage_bits",
        "precision_bits",
        "alignment_bits",
        "abi_class",
        "calling_convention",
        "variadic",
    )
    if (
        not isinstance(source_type, Mapping)
        or not isinstance(target_type, Mapping)
        or any(
            source_type.get(key) in (None, "unknown") or target_type.get(key) in (None, "unknown")
            for key in keys
        )
    ):
        return [
            (
                _assessment(
                    descriptor,
                    stable_id("rule_candidate_scope", {
                        "rule": descriptor.code, "tu": context.scope, "fact": item["fact_id"]
                    }),
                    "unknown",
                    "profile_field_unknown",
                    _fact_refs([item]) + _profile_refs(context),
                ),
                None,
            )
            for item in candidates
        ]
    differences = {
        key: [source_type[key], target_type[key]]
        for key in keys
        if source_type[key] != target_type[key]
    }
    if not differences:
        return [
            (
                _assessment(
                    descriptor,
                    stable_id("rule_candidate_scope", {
                        "rule": descriptor.code, "tu": context.scope, "fact": item["fact_id"]
                    }),
                    "not_matched",
                    "abi_equal",
                    _fact_refs([item]) + _profile_refs(context),
                ),
                None,
            )
            for item in candidates
        ]
    result: list[tuple[RuleAssessment, dict[str, Any] | None]] = []
    for item in candidates:
        scope = stable_id("rule_candidate_scope", {
            "rule": descriptor.code, "tu": context.scope, "fact": item["fact_id"]
        })
        assessment = _assessment(
            descriptor, scope, "matched", "abi_difference",
            _fact_refs([item]) + _profile_refs(context),
        )
        result.append((assessment, _finding(
            descriptor,
            assessment,
            [item],
            context,
            {"differences": differences, "translation_unit_id": context.scope},
            severity="high",
            requires_human_confirmation=True,
        )))
    return result


def _context_options(context: RuleContext) -> Mapping[str, Any]:
    value = context.source_bundle.get("effective_fp_options")
    return value if isinstance(value, Mapping) else {}


def _has_ir_evidence(context: RuleContext) -> bool:
    return bool(context.ir_bundles and context.evidence_bundles)


def _x87(context: RuleContext) -> tuple[RuleAssessment, dict[str, Any] | None]:
    descriptor = RULE_BY_CODE["x87-excess-precision"]
    candidates = [
        fact
        for fact in _facts(context)
        if fact.get("fact_kind") == "arithmetic"
        and int(fact.get("payload", {}).get("operation_chain_length", 1)) >= 2
        and fact["fact_id"] in context.source_bundle.get("candidate_fact_ids", [fact["fact_id"]])
    ]
    if not candidates:
        return _assessment(descriptor, context.scope, "not_matched", "no_operation_chain"), None
    source_eval = _profile_value(context.source_profile, "evaluation")
    target_eval = _profile_value(context.target_profile, "evaluation")
    refs: list[EvidenceRef] = [
        EvidenceRef("source_fact", str(item["fact_id"])) for item in _facts(context)
    ]
    refs.extend(
        (
            EvidenceRef("profile", context.source_profile.fingerprint),
            EvidenceRef("profile", context.target_profile.fingerprint),
        )
    )
    if source_eval is None or target_eval is None:
        return _assessment(
            descriptor, context.scope, "unknown", "profile_field_unknown", refs
        ), None
    options = _context_options(context)
    excess = str(options.get("excess_precision", "standard"))
    x87_enabled = (
        bool(options.get("x87_enabled")) or int(source_eval.get("flt_eval_method", 0)) == 2
    )
    target_extended = int(target_eval.get("flt_eval_method", 0)) == 2
    if not x87_enabled or excess == "standard" and not bool(options.get("x87_enabled")):
        return _assessment(
            descriptor, context.scope, "not_matched", "standard_precision", refs
        ), None
    if target_extended:
        return _assessment(descriptor, context.scope, "not_matched", "evaluation_equal", refs), None
    if any(bundle.get("ambiguities") for bundle in context.evidence_bundles):
        return _assessment(descriptor, context.scope, "unknown", "binding_ambiguous", refs), None
    if not _has_ir_evidence(context) or not _ir_facts(context):
        return _assessment(descriptor, context.scope, "unknown", "ir_evidence_missing", refs), None
    for bundle in context.ir_bundles:
        for fact in bundle.get("facts", []):
            if isinstance(fact, Mapping):
                refs.append(EvidenceRef("ir_fact", str(fact.get("fact_id", ""))))
    refs.extend(EvidenceRef("binding", str(binding["binding_id"]))
                for bundle in context.evidence_bundles for binding in bundle.get("bindings", []))
    assessment = _assessment(
        descriptor, context.scope, "matched", "excess_precision_difference", refs
    )
    return assessment, _finding(
        descriptor,
        assessment,
        candidates,
        context,
        {"source_evaluation": dict(source_eval), "target_evaluation": dict(target_eval)},
        requires_human_confirmation=True,
    )


def _ir_facts(context: RuleContext) -> list[Mapping[str, Any]]:
    result: list[Mapping[str, Any]] = []
    for bundle in context.ir_bundles:
        value = bundle.get("facts", [])
        if isinstance(value, list):
            result.extend(item for item in value if isinstance(item, Mapping))
    return result


def _fma(context: RuleContext) -> tuple[RuleAssessment, dict[str, Any] | None]:
    descriptor = RULE_BY_CODE["fma-contraction"]
    candidates = [
        fact
        for fact in _facts(context)
        if bool(fact.get("payload", {}).get("fma_candidate"))
        and not bool(fact.get("payload", {}).get("explicit_fma"))
        and fact["fact_id"] in context.source_bundle.get("candidate_fact_ids", [fact["fact_id"]])
    ]
    if not candidates:
        return _assessment(
            descriptor, context.scope, "not_matched", "no_implicit_fma_candidate"
        ), None
    refs: list[EvidenceRef] = [
        EvidenceRef("source_fact", str(item["fact_id"])) for item in _facts(context)
    ]
    ambiguous_source_ids = {
        str(source_id)
        for bundle in context.evidence_bundles
        for item in bundle.get("ambiguities", [])
        if isinstance(item, Mapping)
        for source_id in item.get("source_fact_ids", [])
    }
    if any(str(item["fact_id"]) in ambiguous_source_ids for item in candidates):
        return _assessment(descriptor, context.scope, "unknown", "binding_ambiguous", refs), None
    build_bundles = [
        item
        for item in context.ir_bundles
        if item.get("artifact", {}).get("profile") == "build-faithful"
    ]
    if not build_bundles:
        return _assessment(
            descriptor, context.scope, "unknown", "build_faithful_ir_missing", refs
        ), None
    ir_facts = _ir_facts(context)
    if not ir_facts:
        return _assessment(descriptor, context.scope, "unknown", "ir_evidence_missing", refs), None
    for fact in ir_facts:
        refs.append(EvidenceRef("ir_fact", str(fact.get("fact_id", ""))))
    for bundle in context.evidence_bundles:
        for binding in bundle.get("bindings", []):
            if isinstance(binding, Mapping):
                refs.append(EvidenceRef("binding", str(binding.get("binding_id", ""))))
    explicit_fused = [
        fact
        for fact in ir_facts
        if fact.get("opcode") == "fma"
        or "llvm.fma" in str(fact.get("callee", ""))
        or "llvm.fmuladd" in str(fact.get("callee", ""))
    ]
    contract_multiply = [
        fact
        for fact in ir_facts
        if fact.get("opcode") == "fmul"
        and set(str(flag) for flag in fact.get("fast_math_flags", [])) & {"contract", "fast"}
    ]
    contract_add = [
        fact
        for fact in ir_facts
        if fact.get("opcode") in {"fadd", "fsub"}
        and set(str(flag) for flag in fact.get("fast_math_flags", [])) & {"contract", "fast"}
    ]
    fused = explicit_fused + contract_multiply + contract_add
    source_fma = _profile_value(context.source_profile, "fma")
    target_fma = _profile_value(context.target_profile, "fma")
    if source_fma is None or target_fma is None:
        return _assessment(
            descriptor, context.scope, "unknown", "profile_field_unknown", refs
        ), None
    options = _context_options(context)
    policies_differ = (
        source_fma.get("default_contraction") != target_fma.get("default_contraction")
        or options.get("fp_contract") == "fast"
    )
    multiply_ids = {str(item["fact_id"]) for item in contract_multiply}
    has_contract_pair = any(
        multiply_ids.intersection(item.get("operand_fact_ids", [])) for item in contract_add
    )
    if not explicit_fused and not has_contract_pair or not policies_differ:
        return _assessment(
            descriptor, context.scope, "not_matched", "lowering_consistent", refs
        ), None
    assessment = _assessment(descriptor, context.scope, "matched", "contraction_difference", refs)
    return assessment, _finding(
        descriptor,
        assessment,
        candidates,
        context,
        {
            "fused_ir_fact_ids": sorted(str(item.get("fact_id", "")) for item in fused),
            "lowering": "explicit-fma" if explicit_fused else "contractable-mul-add",
            "source_policy": dict(source_fma),
            "target_policy": dict(target_fma),
        },
    )


_IR_FLAG_TO_OPTION = {
    "reassoc": "reassociation",
    "nnan": "no_nans",
    "ninf": "no_infs",
    "nsz": "no_signed_zeros",
    "arcp": "reciprocal_math",
    "afn": "approx_functions",
    "contract": "fp_contract",
}
_HAZARDS = {
    "reassociation",
    "no_nans",
    "no_infs",
    "no_signed_zeros",
    "reciprocal_math",
    "approx_functions",
    "finite_math_only",
}


def _unsafe(context: RuleContext) -> tuple[RuleAssessment, dict[str, Any] | None]:
    descriptor = RULE_BY_CODE["unsafe-fp-optimization"]
    options = _context_options(context)
    active = {key for key in _HAZARDS if options.get(key) is True}
    if options.get("math_errno") is False:
        active.add("no_math_errno")
    if options.get("trapping_math") is False:
        active.add("no_trapping_math")
    ir_active: set[str] = set()
    refs: list[EvidenceRef] = []
    for fact in _ir_facts(context):
        flags = fact.get("fast_math_flags", [])
        if isinstance(flags, list) and flags:
            refs.append(EvidenceRef("ir_fact", str(fact.get("fact_id", ""))))
            for flag in flags:
                if str(flag) == "fast":
                    ir_active.update(_HAZARDS)
                elif str(flag) in _IR_FLAG_TO_OPTION:
                    ir_active.add(_IR_FLAG_TO_OPTION[str(flag)])
    if ir_active and not active:
        return _assessment(descriptor, context.scope, "unknown", "option_ir_conflict", refs), None
    active.update(ir_active)
    if not active:
        return _assessment(descriptor, context.scope, "not_matched", "strict_fp_semantics"), None
    option_digest = semantic_digest({key: options.get(key) for key in sorted(options)})
    refs.append(EvidenceRef("compile_option", option_digest))
    assessment = _assessment(descriptor, context.scope, "matched", "unsafe_semantics_active", refs)
    facts = sorted(_facts(context), key=lambda item: _location_sort_key(item.get("location")))[:1]
    compile_context = context.source_bundle.get("compile_context", {})
    configuration_identity = {
        "rule_version": descriptor.version, "active": sorted(active), "options": dict(options),
        "compile_context": {key: value for key, value in compile_context.items()
                            if key not in {"translation_unit_id", "physical_tu_id", "context_id",
                                           "file", "semantic_digest"}},
        "source_profile": context.source_profile.fingerprint,
        "target_profile": context.target_profile.fingerprint,
        "build_contexts": [ref.ref for ref in _build_context_refs(context)],
        "evidence_mode": descriptor.evidence_mode,
    }
    return assessment, _finding(
        descriptor,
        assessment,
        facts,
        context,
        {"active_semantics": sorted(active)},
        aggregation_key=f"unsafe:{semantic_digest(configuration_identity)}",
    )


def _payload(fact: Mapping[str, Any]) -> Mapping[str, Any]:
    value = fact.get("payload")
    return value if isinstance(value, Mapping) else {}


def _fact_refs(facts: Sequence[Mapping[str, Any]]) -> list[EvidenceRef]:
    return [EvidenceRef("source_fact", str(item["fact_id"])) for item in facts]


def _profile_refs(context: RuleContext) -> list[EvidenceRef]:
    return [
        EvidenceRef("profile", context.source_profile.fingerprint),
        EvidenceRef("profile", context.target_profile.fingerprint),
    ]


def _build_context_refs(context: RuleContext) -> list[EvidenceRef]:
    return [
        EvidenceRef("build_context", item.fingerprint)
        for item in (context.source_build_context, context.target_build_context)
        if item is not None
    ]


def _floating_environment(context: RuleContext) -> tuple[RuleAssessment, dict[str, Any] | None]:
    descriptor = RULE_BY_CODE["floating-environment"]
    candidates = [fact for fact in _facts(context) if fact.get("fact_kind") == "environment"]
    summaries = [fact for fact in _facts(context) if fact.get("fact_kind") == "function_summary"]
    if not candidates:
        return _assessment(descriptor, context.scope, "not_matched", "no_environment_access"), None
    refs = _fact_refs([*candidates, *summaries]) + _profile_refs(context)
    source_environment = _profile_value(context.source_profile, "environment")
    target_environment = _profile_value(context.target_profile, "environment")
    if source_environment is None or target_environment is None:
        return _assessment(
            descriptor, context.scope, "unknown", "profile_field_unknown", refs
        ), None
    calls = [fact for fact in _facts(context) if fact.get("fact_kind") == "call"]
    known_symbols = {fact.get("function_symbol") for fact in summaries}
    if (any(bool(_payload(item).get("unknown_effect")) for item in summaries)
            or any(_payload(item).get("callee_symbol") not in known_symbols for item in calls)
            or any(_payload(item).get("environment_region_unknown") for item in candidates)):
        return _assessment(
            descriptor, context.scope, "unknown", "call_boundary_unknown", refs
        ), None
    actions = sorted(
        {
            str(_payload(item).get("fenv_action"))
            for item in candidates
            if _payload(item).get("fenv_action")
        }
    )
    options = _context_options(context)
    rounding_conflict = "changes_rounding" in actions and not bool(
        options.get("rounding_math", False)
    )
    exception_conflict = (
        bool({"tests_exceptions", "raises_exceptions"} & set(actions))
        and options.get("trapping_math") is False
    )
    restored = "restores_environment" in actions or any(
        bool(_payload(item).get("restores_environment")) for item in summaries
    )
    if restored and not rounding_conflict and not exception_conflict:
        return _assessment(
            descriptor, context.scope, "not_matched", "environment_restored", refs
        ), None
    if not rounding_conflict and not exception_conflict:
        return _assessment(
            descriptor, context.scope, "not_matched", "environment_semantics_compatible", refs
        ), None
    assessment = _assessment(
        descriptor, context.scope, "matched", "environment_option_conflict", refs
    )
    return assessment, _finding(
        descriptor,
        assessment,
        candidates,
        context,
        {
            "actions": actions,
            "rounding_conflict": rounding_conflict,
            "exception_conflict": exception_conflict,
            "source_environment": dict(source_environment),
            "target_environment": dict(target_environment),
        },
    )


_TYPE_PRECISION = {
    "_Float16": 11,
    "__fp16": 11,
    "half": 11,
    "float": 24,
    "double": 53,
}


def _precision_bits(context: RuleContext, type_name: str, *, source: bool) -> int | None:
    normalized = type_name.replace("const", "").replace("volatile", "").strip()
    if normalized in _TYPE_PRECISION:
        return _TYPE_PRECISION[normalized]
    if normalized == "long double":
        profile = context.source_profile if source else context.target_profile
        value = profile.document.get("floating_types", {}).get("long_double", {})
        precision = value.get("precision_bits") if isinstance(value, Mapping) else None
        return int(precision) if isinstance(precision, int) else None
    return None


def _integer_width(type_name: str, payload: Mapping[str, Any]) -> int | None:
    explicit = payload.get("integer_width")
    if isinstance(explicit, int):
        return explicit
    value = type_name.replace("unsigned", "").strip()
    return {
        "char": 8,
        "short": 16,
        "int": 32,
        "long": 64,
        "long int": 64,
        "long long": 64,
        "long long int": 64,
    }.get(value)


def _integer_constant_exact(value: str, precision: int) -> bool | None:
    try:
        integer = int(value, 0)
    except ValueError:
        try:
            decimal = Decimal(value)
        except InvalidOperation:
            return None
        if decimal != decimal.to_integral_value():
            return None
        integer = int(decimal)
    if integer == 0:
        return True
    absolute = abs(integer)
    while absolute % 2 == 0:
        absolute //= 2
    return absolute.bit_length() <= precision


def _floating_constant_exact(value: str, precision: int) -> bool | None:
    normalized = value.strip().lower().removesuffix("f").removesuffix("l")
    if normalized in {"nan", "+nan", "-nan", "inf", "+inf", "-inf", "infinity"}:
        return True
    try:
        parsed = float.fromhex(normalized) if "0x" in normalized else float(normalized)
    except ValueError:
        return None
    if not math.isfinite(parsed):
        return False
    if precision == 24:
        try:
            rounded = struct.unpack("!f", struct.pack("!f", parsed))[0]
        except OverflowError:
            return False
    elif precision == 53:
        rounded = parsed
    else:
        return None
    if "0x" in normalized:
        return bool(rounded == parsed)
    try:
        return bool(Decimal(normalized) == Decimal.from_float(rounded))
    except InvalidOperation:
        return None


def _mixed_precision(context: RuleContext) -> tuple[RuleAssessment, dict[str, Any] | None]:
    return _mixed_precision_evaluations(context)[0]


def _mixed_precision_evaluations(
    context: RuleContext,
) -> list[tuple[RuleAssessment, dict[str, Any] | None]]:
    descriptor = RULE_BY_CODE["mixed-precision-conversion"]
    candidates = [fact for fact in _facts(context) if fact.get("fact_kind") == "conversion"]
    if not candidates:
        return [(_assessment(descriptor, context.scope, "not_matched", "no_conversion"), None)]
    results: list[tuple[RuleAssessment, dict[str, Any] | None]] = []
    for fact in candidates:
        scope = stable_id("rule_candidate_scope", {
            "rule": descriptor.code, "tu": context.scope, "fact": fact["fact_id"]
        })
        refs = _fact_refs([fact])
        payload = _payload(fact)
        if not bool(payload.get("implicit")):
            results.append((_assessment(
                descriptor, scope, "not_matched", "conversion_explicit", refs
            ), None))
            continue
        source_type = payload.get("source_type")
        target_type = payload.get("destination_type") or fact.get("type")
        if payload.get("endpoint_status") == "unknown" or not isinstance(source_type, str) \
                or not isinstance(target_type, str):
            results.append((_assessment(
                descriptor, scope, "unknown", "conversion_type_unknown", refs
            ), None))
            continue
        target_precision = _precision_bits(context, target_type, source=False)
        if target_precision is None:
            results.append((_assessment(
                descriptor, scope, "unknown", "conversion_type_unknown", refs
            ), None))
            continue
        source_precision = _precision_bits(context, source_type, source=True)
        if source_precision is not None:
            constant = payload.get("constant_value")
            if (
                constant is not None
                and _floating_constant_exact(str(constant), target_precision) is True
            ):
                results.append((_assessment(
                    descriptor, scope, "not_matched", "conversion_exact", refs
                ), None))
                continue
            if source_precision > target_precision:
                loss = {
                    "fact_id": fact["fact_id"], "source_type": source_type,
                    "target_type": target_type, "kind": "precision",
                    "source_precision": source_precision,
                    "target_precision": target_precision,
                }
                assessment = _assessment(
                    descriptor, scope, "matched", "implicit_precision_loss", refs
                )
                results.append((assessment, _finding(
                    descriptor, assessment, [fact], context,
                    {"conversions": [loss], "translation_unit_id": context.scope},
                )))
            else:
                results.append((_assessment(
                    descriptor, scope, "not_matched", "conversion_exact", refs
                ), None))
            continue
        width = _integer_width(source_type, payload)
        if width is None:
            results.append((_assessment(
                descriptor, scope, "unknown", "conversion_type_unknown", refs
            ), None))
            continue
        constant = payload.get("constant_value")
        exact = (
            _integer_constant_exact(str(constant), target_precision)
            if constant is not None
            else None
        )
        if exact is True:
            results.append((_assessment(
                descriptor, scope, "not_matched", "conversion_exact", refs
            ), None))
            continue
        if width > target_precision or exact is False:
            loss = {
                "fact_id": fact["fact_id"], "source_type": source_type,
                "target_type": target_type, "kind": "integer-precision",
                "integer_width": width, "target_precision": target_precision,
                "constant_exact": exact,
            }
            assessment = _assessment(
                descriptor, scope, "matched", "implicit_precision_loss", refs
            )
            results.append((assessment, _finding(
                descriptor, assessment, [fact], context,
                {"conversions": [loss], "translation_unit_id": context.scope},
            )))
        else:
            results.append((_assessment(
                descriptor, scope, "not_matched", "conversion_exact", refs
            ), None))
    return results


def _fragile_comparison(context: RuleContext) -> tuple[RuleAssessment, dict[str, Any] | None]:
    descriptor = RULE_BY_CODE["fragile-fp-comparison"]
    candidates = [fact for fact in _facts(context) if fact.get("fact_kind") == "comparison"]
    if not candidates:
        return _assessment(descriptor, context.scope, "not_matched", "no_float_comparison"), None
    refs = _fact_refs(candidates)
    nan_items = [item for item in candidates if bool(_payload(item).get("nan_comparison"))]
    if nan_items:
        assessment = _assessment(
            descriptor, context.scope, "matched", "nan_equality_semantics", refs
        )
        return assessment, _finding(
            descriptor,
            assessment,
            nan_items,
            context,
            {"rationale": "nan_equality_semantics"},
        )
    fragile = [
        item
        for item in candidates
        if _payload(item).get("opcode") in {"==", "!="}
        and not bool(_payload(item).get("zero_comparison"))
        and not bool(_payload(item).get("same_origin"))
        and not bool(_payload(item).get("nan_guard"))
    ]
    fragile.extend(
        item
        for item in candidates
        if bool(_payload(item).get("epsilon_pattern")) and item not in fragile
    )
    if not fragile:
        return _assessment(
            descriptor, context.scope, "not_matched", "comparison_guarded_or_stable", refs
        ), None
    if any(not _payload(item).get("use_context") for item in fragile):
        return _assessment(
            descriptor, context.scope, "unknown", "comparison_use_unknown", refs
        ), None
    assessment = _assessment(
        descriptor, context.scope, "matched", "rounding_sensitive_comparison", refs
    )
    return assessment, _finding(
        descriptor,
        assessment,
        fragile,
        context,
        {"rationale": "rounding_sensitive_comparison", "business_tolerance": "unknown"},
        confidence="medium",
        evidence_origin="heuristic",
    )


def _reduction_candidate(
    context: RuleContext,
    candidate: ReductionCandidate,
    scope: str,
) -> tuple[RuleAssessment, dict[str, Any] | None]:
    descriptor = RULE_BY_CODE["reduction-reassociation"]
    facts = candidate.facts
    refs = _fact_refs(facts)
    payloads = [_payload(fact) for fact in facts]
    if any(payload.get("ambiguous") for payload in payloads) or len(
        {(payload.get("operation"), payload.get("reduction_kind")) for payload in payloads}
    ) > 1:
        return _assessment(
            descriptor, scope, "unknown", "reduction_recurrence_ambiguous", refs
        ), None
    if any(
        not fact.get("function_id")
        or not (payload.get("loop_id") or payload.get("iteration_count") is not None)
        or ("accumulator_id" in payload and not payload["accumulator_id"])
        for fact, payload in zip(facts, payloads, strict=True)
    ):
        return _assessment(descriptor, scope, "unknown", "reduction_identity_unknown", refs), None
    ir, bindings, ambiguous = bound_evidence(
        candidate, context.ir_bundles, context.evidence_bundles
    )
    if ambiguous:
        return _assessment(descriptor, scope, "unknown", "binding_ambiguous", refs), None
    refs.extend(EvidenceRef("ir_fact", str(item["fact_id"])) for item in ir)
    refs.extend(EvidenceRef("binding", str(item["binding_id"])) for item in bindings)
    reordered = [item for item in ir if set(item.get("fast_math_flags", [])) & {"reassoc", "fast"}]
    risky = [
        fact
        for fact, payload in zip(facts, payloads, strict=True)
        if (not payload.get("compensation") or reordered)
        and (payload.get("iteration_count") is None or int(payload["iteration_count"]) > 1)
        and not payload.get("fixed_order")
    ]
    if not risky:
        return _assessment(descriptor, scope, "not_matched", "compensated_or_fixed", refs), None
    # 不把 x87 可用性等无关 profile 差异解释成归约顺序差异。
    source_policy = _profile_value(context.source_profile, "evaluation") or {}
    target_policy = _profile_value(context.target_profile, "evaluation") or {}
    build_ids = {
        str(fact["fact_id"])
        for bundle in context.ir_bundles
        if bundle.get("artifact", {}).get("profile") == "build-faithful"
        for fact in bundle.get("facts", [])
    }
    verified = (
        source_policy.get("reassociation") is True
        and target_policy.get("reassociation") is False
        and any(str(item["fact_id"]) in build_ids for item in reordered)
    )
    if verified:
        refs.extend(_profile_refs(context))
    assessment = _assessment(
        descriptor,
        scope,
        "matched",
        "verified_reassociation" if verified else "order_sensitive_reduction",
        refs,
    )
    finding = _finding(
        descriptor,
        assessment,
        risky,
        context,
        {
            "rationale": assessment.reason_code,
            "translation_unit_id": context.scope,
            "candidate_id": candidate.identity,
            "ir_fact_ids": sorted(str(item["fact_id"]) for item in ir),
            "compensation_invalidated": bool(reordered)
            and any(p.get("compensation") for p in payloads),
        },
        confidence="high" if verified else "medium",
        evidence_origin="deterministic" if verified else "heuristic",
    )
    finding["affected_count"] = 1
    return assessment, finding


def _math_library(context: RuleContext) -> tuple[RuleAssessment, dict[str, Any] | None]:
    descriptor = RULE_BY_CODE["math-library-variance"]
    candidates = [
        fact
        for fact in _facts(context)
        if fact.get("fact_kind") == "call" and _payload(fact).get("math_family")
    ]
    if not candidates:
        return _assessment(descriptor, context.scope, "not_matched", "no_math_call"), None
    refs = _fact_refs(candidates) + _build_context_refs(context)
    if context.source_build_context is None or context.target_build_context is None:
        return _assessment(descriptor, context.scope, "unknown", "libm_context_missing", refs), None
    source_libm = context.source_build_context.capability("libm")
    target_libm = context.target_build_context.capability("libm")
    if source_libm is None or target_libm is None:
        return _assessment(
            descriptor, context.scope, "unknown", "libm_identity_unknown", refs
        ), None
    semantic_fields = ("errno", "exceptions", "special_values", "rounding")
    if any(field not in source_libm or field not in target_libm for field in semantic_fields[:3]):
        return _assessment(
            descriptor, context.scope, "unknown", "libm_capability_unknown", refs
        ), None
    differences = {
        field: {"source": source_libm.get(field), "target": target_libm.get(field)}
        for field in semantic_fields
        if field in source_libm
        and field in target_libm
        and source_libm.get(field) != target_libm.get(field)
    }
    family = str(_payload(candidates[0]).get("math_family"))
    if "functions" in source_libm or "functions" in target_libm:
        left, right = source_libm.get("functions"), target_libm.get("functions")
        if (not isinstance(left, Mapping) or not isinstance(right, Mapping)
                or family not in left or family not in right):
            return _assessment(descriptor, context.scope, "unknown", "libm_capability_unknown",
                               refs), None
        if left[family] != right[family]:
            differences["functions"] = {"source": left[family], "target": right[family]}
    if not differences:
        return _assessment(
            descriptor, context.scope, "not_matched", "libm_semantics_equal", refs
        ), None
    assessment = _assessment(descriptor, context.scope, "matched", "libm_semantics_differ", refs)
    return assessment, _finding(
        descriptor,
        assessment,
        candidates,
        context,
        {
            "source_libm": dict(source_libm),
            "target_libm": dict(target_libm),
            "semantic_differences": differences,
            "argument_domains": sorted(
                {str(_payload(item).get("argument_domain", "unknown")) for item in candidates}
            ),
        },
    )


def _architecture_api(context: RuleContext) -> tuple[RuleAssessment, dict[str, Any] | None]:
    descriptor = RULE_BY_CODE["architecture-specific-fp-api"]
    candidates = [fact for fact in _facts(context) if fact.get("fact_kind") == "api"]
    if not candidates:
        return _assessment(descriptor, context.scope, "not_matched", "no_architecture_api"), None
    refs = _fact_refs(candidates) + _profile_refs(context) + _build_context_refs(context)
    unresolved = [item for item in candidates if not bool(_payload(item).get("resolved"))]
    ambiguous = [item for item in candidates if bool(_payload(item).get("provenance_ambiguous"))]
    if unresolved or ambiguous:
        return _assessment(
            descriptor, context.scope, "unknown", "api_declaration_unknown", refs
        ), None
    unguarded = [
        item
        for item in candidates
        if not (
            _payload(item).get("active_target_guard")
            and bool(_payload(item).get("portable_target_implementation"))
        )
    ]
    if not unguarded:
        return _assessment(
            descriptor, context.scope, "not_matched", "portable_wrapper_guarded", refs
        ), None
    if "riscv" not in context.target_profile.target_triple.lower():
        return _assessment(
            descriptor, context.scope, "not_matched", "target_supports_source_api", refs
        ), None
    assessment = _assessment(descriptor, context.scope, "matched", "x86_api_on_riscv_target", refs)
    return assessment, _finding(
        descriptor,
        assessment,
        unguarded,
        context,
        {
            "apis": sorted(str(_payload(item).get("callee")) for item in unguarded),
            "required_target_features": sorted(
                {
                    str(feature)
                    for item in unguarded
                    for feature in _payload(item).get("required_target_features", [])
                }
            ),
        },
    )


_EVALUATORS = {
    "long-double-abi": _long_double,
    "x87-excess-precision": _x87,
    "fma-contraction": _fma,
    "unsafe-fp-optimization": _unsafe,
    "floating-environment": _floating_environment,
    "mixed-precision-conversion": _mixed_precision,
    "fragile-fp-comparison": _fragile_comparison,
    "math-library-variance": _math_library,
    "architecture-specific-fp-api": _architecture_api,
}


def _candidate_evaluations(
    code: str, context: RuleContext, limit: int, max_evidence_refs: int,
) -> Iterator[tuple[RuleAssessment, dict[str, Any] | None]]:
    descriptor = RULE_BY_CODE[code]
    candidates = construct(code, _facts(context), str(context.source_bundle.get("context_id")))
    if not candidates:
        if code == "reduction-reassociation":
            yield _assessment(descriptor, context.scope, "not_matched", "no_reduction"), None
        else:
            yield _EVALUATORS[code](replace(context, source_bundle={**context.source_bundle,
                                                                  "facts": []}))
        return
    for index, candidate in enumerate(candidates):
        scope = stable_id("rule_candidate_scope", {
            "rule": code, "version": descriptor.version, "candidate": candidate.identity,
        })
        if index >= limit:
            yield _assessment(descriptor, scope, "unknown", "budget_exceeded",
                              frontier=candidate.identity), None
            return
        try:
            if code != "unsafe-fp-optimization" and (
                len(candidate.facts) + len(candidate.dependencies) > max_evidence_refs
            ):
                yield _assessment(descriptor, scope, "unknown", "provenance_budget_exceeded",
                                  frontier=candidate.identity), None
                continue
            if any(not isinstance(fact.get("payload"), Mapping) for fact in candidate.facts):
                raise TypeError("invalid_candidate_payload")
            for fact in candidate.facts:
                data = _payload(fact)
                identity_fields = ("expression_id", "evaluation_chain_id", "environment_region_id")
                if any(data.get(key) is not None and not isinstance(data[key], str)
                       for key in identity_fields):
                    raise TypeError("invalid_candidate_relation")
                if any(key in data and not isinstance(data[key], list)
                       for key in ("dependency_expression_ids", "fma_operand_expression_ids")):
                    raise TypeError("invalid_candidate_dependencies")
            if code != "unsafe-fp-optimization" and any(
                _payload(fact).get("expression_identity_unknown")
                and not (code == "long-double-abi" and _payload(fact).get("name"))
                for fact in candidate.facts
            ):
                yield _assessment(descriptor, scope, "unknown", "candidate_identity_unknown",
                                  _fact_refs(candidate.facts)), None
                continue
            if code == "reduction-reassociation":
                yield _reduction_candidate(
                    context, ReductionCandidate(candidate.identity, candidate.facts), scope,
                )
                continue
            ir, evidence = (context.ir_bundles, context.evidence_bundles)
            if code in {"fma-contraction", "x87-excess-precision"}:
                ir = tuple(b for b in ir
                           if b.get("artifact", {}).get("profile") == "build-faithful")
                evidence = tuple(b for b in evidence
                                 if b.get("artifact", {}).get("profile") == "build-faithful")
            if code in {"fma-contraction", "x87-excess-precision"}:
                ir, evidence = local_evidence(candidate, ir, evidence)
            elif code != "unsafe-fp-optimization":
                ir, evidence = (), ()
            local = replace(context, candidate_scope=scope, ir_bundles=ir,
                            evidence_bundles=evidence,
                            source_bundle={**context.source_bundle,
                                           "facts": [*candidate.facts, *candidate.dependencies],
                                           "candidate_fact_ids": [f["fact_id"]
                                                                  for f in candidate.facts]})
            assessment, finding = _EVALUATORS[code](local)
            if finding is not None:
                finding["semantic"] = {**finding["semantic"], "candidate_id": candidate.identity,
                                       "translation_unit_id": context.scope}
            yield assessment, finding
        except (AttributeError, KeyError, TypeError, ValueError):
            yield _assessment(descriptor, scope, "failed", "invalid_rule_input",
                              _fact_refs(candidate.facts)), None


def _unknown_diagnostic(assessment: RuleAssessment, tu: str | None = None) -> Diagnostic:
    return diagnostic(
        f"{assessment.rule_code.replace('-', '_')}_{assessment.reason_code}",
        "rule_evaluation",
        f"规则 {assessment.rule_code} 因证据不足或冲突无法完成判断。",
        (
            f"Rule {assessment.rule_code} could not complete because evidence is missing "
            "or conflicting."
        ),
        severity="warning",
        suggestion=f"补齐证据：{assessment.reason_code}。",
        suggestion_en=f"Provide or correct evidence: {assessment.reason_code}.",
        translation_unit_id=tu or assessment.scope,
    )


def _aggregate_findings(
    findings: Sequence[Mapping[str, Any]], max_related_locations: int
) -> tuple[Mapping[str, Any], ...]:
    groups: dict[str, list[Mapping[str, Any]]] = {}
    for finding in findings:
        key = str(finding.get("aggregation_key") or finding["finding_id"])
        groups.setdefault(key, []).append(finding)
    result: list[Mapping[str, Any]] = []
    for key in sorted(groups):
        values = sorted(groups[key], key=lambda item: str(item["finding_id"]))
        combined_refs = _refs(
            EvidenceRef(str(ref["kind"]), str(ref["ref"]))
            for value in values for ref in [*value.get("evidence", []),
                *([{"kind": "assessment", "ref": value["assessment_id"]}]
                  if value.get("assessment_id") else [])]
        )
        if len(values) > 1 and len(combined_refs) > 128:
            # 不通过截断丢弃成员证据；预算不允许合并时保留完整独立成员。
            result.extend(_aggregate_findings(
                [{**value, "aggregation_key": None} for value in values], max_related_locations,
            ))
            continue
        primary = dict(values[0])
        locations = [location for item in values
                     for location in [item.get("location"), *item.get("related_locations", [])]
                     if isinstance(location, Mapping)]
        ordered_locations = sorted(
            {semantic_digest(item): item for item in locations}.values(),
            key=lambda location: (*_location_sort_key(location), semantic_digest(location)),
        )
        primary["location"] = ordered_locations[0] if ordered_locations else None
        primary["related_locations"] = ordered_locations[1 : max_related_locations + 1]
        if len(ordered_locations) > max_related_locations + 1:
            primary["semantic"] = {**primary.get("semantic", {}),
                                   "location_budget_exceeded": True,
                                   "total_locations": len(ordered_locations)}
        primary["affected_count"] = sum(int(item.get("affected_count", 1)) for item in values)
        if len(values) > 1:
            primary["evidence"] = [item.to_dict() for item in combined_refs]
            primary["semantic"] = {**primary.get("semantic", {}), "members": [
                {"finding_id": item["finding_id"], "assessment_id": item["assessment_id"],
                 "location": item.get("location"), "evidence": item.get("evidence", [])}
                for item in values
            ]}
            primary["finding_id"] = stable_id(
                "finding_aggregate",
                {
                    "aggregation_key": key,
                    "members": sorted(str(item["finding_id"]) for item in values),
                },
            )
        result.append(primary)
    return tuple(
        sorted(result, key=lambda item: (str(item["finding_code"]), str(item["finding_id"])))
    )


def evaluate_rules(
    enabled_rules: Sequence[str],
    source_profile: TargetProfile,
    target_profile: TargetProfile,
    source_bundles: Sequence[Mapping[str, Any]],
    ir_bundles: Sequence[Mapping[str, Any]],
    evidence_bundles: Sequence[Mapping[str, Any]],
    budgets: Mapping[str, int | float],
    source_build_context: BuildContext | None = None,
    target_build_context: BuildContext | None = None,
    *,
    aggregate: bool = True,
) -> RuleRun:
    max_assessments = int(budgets.get("max_rule_assessments", 100_000))
    max_candidates = int(budgets.get("max_rule_candidates", 100_000))
    # Public result v2 caps every provenance/reference list at 128 entries.
    max_evidence_refs = min(128, int(budgets.get("max_rule_evidence_refs", 128)))
    max_related = int(budgets.get("max_related_locations", 100))
    assessments: list[RuleAssessment] = []
    findings: list[Mapping[str, Any]] = []
    diagnostics: list[Diagnostic] = []
    candidate_counts: Counter[str] = Counter()
    ordered_sources = sorted(source_bundles, key=lambda item: str(item.get("context_id", "")))
    for source_bundle in ordered_sources:
        context_id = str(source_bundle.get("context_id", ""))
        context = RuleContext(
            source_profile,
            target_profile,
            source_bundle,
            tuple(
                item
                for item in ir_bundles
                if str(item.get("artifact", {}).get("context_id", "")) == context_id
            ),
            tuple(
                item
                for item in evidence_bundles
                if str(item.get("artifact", {}).get("context_id", "")) == context_id
            ),
            source_build_context,
            target_build_context,
        )
        for code in enabled_rules:
            descriptor = RULE_BY_CODE[code]
            if len(assessments) >= max_assessments:
                item = _assessment(
                    descriptor,
                    context.scope,
                    "unknown",
                    "budget_exceeded",
                    frontier=assessments[-1].assessment_id if assessments else None,
                )
                assessments.append(item)
                diagnostics.append(_unknown_diagnostic(item, context.scope))
                return RuleRun(
                    tuple(
                        sorted(
                            assessments,
                            key=lambda value: (
                                value.rule_code,
                                value.scope,
                                value.assessment_id,
                            ),
                        )
                    ),
                    _aggregate_findings(findings, max_related) if aggregate else tuple(findings),
                    tuple(diagnostics),
                    tuple(sorted(findings, key=lambda value: str(value["finding_id"]))),
                )
            if candidate_counts[code] >= max_candidates:
                item = _assessment(
                    descriptor,
                    context.scope,
                    "unknown",
                    "budget_exceeded",
                    frontier=assessments[-1].assessment_id if assessments else None,
                )
                assessments.append(item)
                diagnostics.append(_unknown_diagnostic(item, context.scope))
                continue
            try:
                evaluations: Iterable[tuple[RuleAssessment, dict[str, Any] | None]]
                evaluations = _candidate_evaluations(
                    code, context, min(max_candidates - candidate_counts[code],
                                       max_assessments - len(assessments)),
                    max_evidence_refs,
                )
            except (AttributeError, KeyError, TypeError, ValueError) as exc:
                evaluations = [(_assessment(
                    descriptor, context.scope, "failed", "invalid_rule_input"
                ), None)]
                diagnostics.append(
                    diagnostic(
                        "rule_evaluation_failed",
                        "rule_evaluation",
                        f"规则 {code} 遇到可隔离的无效输入。",
                        f"Rule {code} encountered isolated invalid input.",
                        suggestion=type(exc).__name__,
                        suggestion_en=type(exc).__name__,
                        translation_unit_id=context.scope,
                    )
                )
            for assessment, finding in evaluations:
                if candidate_counts[code] >= max_candidates or len(assessments) >= max_assessments:
                    budget_item = assessment if (
                        assessment.reason_code == "budget_exceeded"
                    ) else _assessment(
                        descriptor, context.scope, "unknown", "budget_exceeded",
                        frontier=assessments[-1].assessment_id if assessments else None,
                    )
                    assessments.append(budget_item)
                    diagnostics.append(_unknown_diagnostic(budget_item, context.scope))
                    break
                candidate_counts[code] += 1
                assessments.append(assessment)
                if assessment.status == "unknown":
                    diagnostics.append(_unknown_diagnostic(assessment, context.scope))
                if assessment.status == "failed":
                    diagnostics.append(diagnostic(
                        "rule_evaluation_failed", "rule_evaluation",
                        "当前候选包含无效输入，其他候选继续评估。",
                        "This candidate has invalid input; other candidates continue.",
                        translation_unit_id=context.scope,
                        suggestion=f"检查候选：{assessment.scope}。",
                        suggestion_en=f"Check candidate: {assessment.scope}.",
                    ))
                if len(assessment.evidence) > max_evidence_refs:
                    assessment = _assessment(
                        descriptor,
                        assessment.scope,
                        "unknown",
                        "provenance_budget_exceeded",
                        frontier=assessment.assessment_id,
                    )
                    assessments[-1] = assessment
                    diagnostics.append(_unknown_diagnostic(assessment, context.scope))
                    finding = None
                if finding is not None:
                    findings.append(finding)
    return RuleRun(
        tuple(
            sorted(assessments, key=lambda item: (item.rule_code, item.scope, item.assessment_id))
        ),
        _aggregate_findings(findings, max_related) if aggregate else tuple(findings),
        tuple(diagnostics),
        tuple(sorted(findings, key=lambda value: str(value["finding_id"]))),
    )
