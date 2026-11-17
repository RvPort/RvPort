"""Paired presentation text; never infers or changes a migration conclusion."""
from dataclasses import dataclass
from types import MappingProxyType


@dataclass(frozen=True, slots=True)
class Suggestion:
    zh: str
    en: str


SUGGESTIONS = MappingProxyType({
    "multiversion_unknown": Suggestion(
        "请核查函数版本绑定、default 声明与定义，以及源编译器版本和目标规则。当前证据不足以确认 fallback 缺口；default 不等于 RISC-V 或通用实现。",
        "Review function version binding, default declarations and definitions, and source compiler version and target rules. Evidence is insufficient to confirm a fallback gap; default is not a RISC-V or portable implementation.",
    ),
    "multiversion_gap": Suggestion(
        "已在限定范围内发现适用规则要求的 default 版本缺口，请补齐对应声明或实现。此项不代表全项目缺少 RISC-V 实现，也不代表已执行编译验证。",
        "A required default version is absent within the bounded scope under an applicable rule. Supply its declaration or implementation; this does not establish a project-wide RISC-V gap or an executed compilation failure.",
    ),
    "configuration_rejected": Suggestion(
        "静态证据表明必需的 CMake 配置路径拒绝目标架构。请修正该条件并补齐 RISC-V 或通用目标路径；尚未执行真实配置、编译或测试。",
        "Static evidence shows that a required CMake configuration path rejects the target architecture. Revise the condition and provide a RISC-V or generic target path; configuration, compilation and tests have not been executed.",
    ),
    "unknown": Suggestion(
        "当前证据不足以确定该事实的目标架构适用性或迁移影响。请保留该事实，补齐缺失或有歧义的证据后，再判断是否需要替换实现。",
        "Target-architecture applicability or migration impact cannot be determined from the current evidence. Preserve this fact and resolve missing or ambiguous evidence before deciding whether to replace an implementation.",
    ),
    "inactive": Suggestion(
        "当前静态证据表明该路径未被目标配置选中。请保留相关条件保护，并在配置变化时重新核查；这不代表项目整体已完成迁移。",
        "Current static evidence shows that this path is excluded by the target configuration. Preserve its guards and reassess after configuration changes; this does not establish migration readiness for the whole project.",
    ),
    "hint": Suggestion(
        "请将此项作为待核实的架构线索，结合实际源码和选择路径确认其是否对应真实依赖，不要仅凭名称或注释决定替换实现。",
        "Treat this item as an architecture hint. Check actual source and selection paths for a real dependency; do not decide on replacement from names or comments alone.",
    ),
    "blocking": Suggestion(
        "请根据证据链处理目标配置已选中的 x86 依赖，补充 RISC-V 或通用实现，并核查相应静态选择路径；真实编译和测试需在后续阶段验证。",
        "Use the evidence chain to address the x86 dependency selected by the target configuration. Provide a RISC-V or portable implementation and review the static selection path; actual compilation and tests require later validation.",
    ),
    "local": Suggestion(
        "请核查该依赖对目标配置的适用性，按需补充 RISC-V 或通用实现及选择路径；本项不代表已确认的构建阻断，真实编译和测试需在后续阶段验证。",
        "Check this dependency's applicability to the target configuration and add a RISC-V or portable implementation and selection path where needed. This item is not a confirmed build blocker; actual compilation and tests require later validation.",
    ),
    "selection_gap": Suggestion(
        "请核查目标架构的构建选择分支，按证据补齐 RISC-V 或通用选择路径；不能仅凭分支名称判断实现可用，真实构建需在后续阶段验证。",
        "Review the build-selection branches for the target architecture and use the evidence to add a RISC-V or generic selection path. Branch names alone do not establish implementation suitability; actual builds require later validation.",
    ),
    "family_gap": Suggestion(
        "构建已选中源架构家族成员且范围内未观察到 RISC-V 或通用实现，请按推导的目标路径补齐对应成员或确认其不需要；本结论限于授权文件清单，不代表全项目缺失，真实构建需在后续阶段验证。",
        "The build selects a source-architecture family member while no RISC-V or portable member is observed in scope. Add the derived target member or confirm it is unnecessary. This conclusion is limited to the authorized file list and does not claim a project-wide absence; actual builds require later validation.",
    ),
    "test_gap": Suggestion(
        "请核查 RISC-V 目标对应的测试资产、选择路径及其与实现的关联，补齐有证据支持的测试覆盖缺口；当前静态扫描不代表测试已经运行或通过。",
        "Review test assets, selection paths and their links to implementations for the RISC-V target, and address evidence-backed coverage gaps. This static scan does not mean tests have run or passed.",
    ),
})


# Presentation coverage only: an unfamiliar type never implies a new conclusion.
_DEPENDENCY_TYPES = frozenset({
    "x86_header_reference", "x86_intrinsic_reference", "x86_builtin_reference",
    "x86_vector_type_reference", "x86_platform_macro_reference", "x86_compiler_flag",
    "x86_cpu_platform_api", "x86_assembly_instruction", "x86_register_reference",
    "x86_target_feature", "x86_architecture_alias", "architecture_enum_candidate",
    "architecture_identifier_candidate", "inline_asm_candidate", "target_attribute_candidate",
    "build_selected_x86_dependency", "riscv_test_blocker",
    "arm64_header_reference", "arm64_intrinsic_reference", "arm64_builtin_reference",
    "arm64_vector_type_reference", "arm64_platform_macro_reference", "arm64_compiler_flag",
    "arm64_cpu_platform_api", "arm64_assembly_instruction", "arm64_register_reference",
    "arm64_target_feature", "arm64_architecture_alias",
})


def select_suggestion(impact: str, status: str, finding_type: str, *, confirmed_local=False, reason="") -> Suggestion:
    if finding_type in {"function_multiversion_fallback_gap", "function_multiversion_unknown"}:
        if status == "inactive" or impact == "guarded_inactive":
            return SUGGESTIONS["inactive"]
        return SUGGESTIONS["multiversion_gap" if impact == "actionable_non_blocking" and status == "complete" and finding_type == "function_multiversion_fallback_gap" else "multiversion_unknown"]
    if reason == "cmake-target-configuration-rejected" and impact == "blocking_issue" and status == "complete":
        return SUGGESTIONS["configuration_rejected"]
    if confirmed_local and status in {"unknown", "partial", "ambiguous"}:
        return SUGGESTIONS["local"]
    if status in {"unknown", "partial", "ambiguous"} or impact == "unknown":
        return SUGGESTIONS["unknown"]
    if status == "inactive" or impact == "guarded_inactive":
        return SUGGESTIONS["inactive"]
    if impact == "hint":
        return SUGGESTIONS["hint"]
    if status != "complete":
        return SUGGESTIONS["unknown"]
    if finding_type not in _DEPENDENCY_TYPES | {"missing_riscv_selection_path", "missing_riscv_family_member", "riscv_test_coverage_gap"}:
        return SUGGESTIONS["unknown"]
    if impact == "blocking_issue":
        return SUGGESTIONS["blocking"]
    if impact == "actionable_non_blocking":
        key = {"missing_riscv_selection_path": "selection_gap",
               "missing_riscv_family_member": "family_gap",
               "riscv_test_coverage_gap": "test_gap"}.get(finding_type, "local")
        return SUGGESTIONS[key]
    return SUGGESTIONS["unknown"]
