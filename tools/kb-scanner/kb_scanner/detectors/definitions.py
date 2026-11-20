from __future__ import annotations

from dataclasses import dataclass
from types import MappingProxyType
from collections.abc import Iterable, Mapping


NORMALIZED_FACT_KINDS = frozenset(
    {
        "include",
        "symbol",
        "type",
        "macro",
        "macro_parameter",
        "macro_argument",
        "macro_replacement",
        "preprocessor",
        "compiler_argument",
        "inline_asm",
        "comment",
        "declaration_attribute",
        "enum_declaration",
        "enum_member",
        "enum_reference",
        "selection_condition",
        "template_declaration",
    }
)
MATCHERS = frozenset({"exact", "token", "flag", "comment_hint", "architecture_candidate", "enum_architecture_candidate", "structural"})


@dataclass(frozen=True, slots=True)
class DetectorDefinition:
    detector_id: str
    input_kind: str
    knowledge_kinds: tuple[str, ...]
    observation_kind: str
    matcher: str
    status: str = "confirmed_local"

    def __post_init__(self) -> None:
        if not all((self.detector_id, self.input_kind, self.observation_kind, self.matcher, self.status)):
            raise ValueError("detector definition fields must not be empty")
        if self.input_kind not in NORMALIZED_FACT_KINDS:
            raise ValueError(f"unsupported normalized fact kind: {self.input_kind}")
        if self.matcher not in MATCHERS:
            raise ValueError(f"unknown matcher binding: {self.matcher}")
        object.__setattr__(self, "knowledge_kinds", tuple(sorted(set(self.knowledge_kinds))))
        if self.matcher in {"exact", "token", "flag", "enum_architecture_candidate"} and not self.knowledge_kinds:
            raise ValueError("fact-backed matcher requires knowledge kinds")
        if self.matcher in {"comment_hint", "architecture_candidate", "structural"} and self.knowledge_kinds:
            raise ValueError("structural matcher must not embed a knowledge vocabulary")


def build_registry(definitions: Iterable[DetectorDefinition]) -> Mapping[str, DetectorDefinition]:
    ordered = sorted(definitions, key=lambda item: item.detector_id)
    result: dict[str, DetectorDefinition] = {}
    for definition in ordered:
        if definition.detector_id in result:
            raise ValueError(f"duplicate detector id: {definition.detector_id}")
        result[definition.detector_id] = definition
    return MappingProxyType(result)


DEFAULT_DEFINITIONS = build_registry(
    (
        DetectorDefinition("C_CPP.X86.HEADER", "include", ("headers",), "x86_header_reference", "exact"),
        DetectorDefinition("C_CPP.X86.INTRINSIC", "symbol", ("intrinsics",), "x86_intrinsic_reference", "exact"),
        DetectorDefinition("C_CPP.X86.MACRO_ARGUMENT_INTRINSIC", "macro_argument", ("intrinsics",), "x86_intrinsic_reference", "exact", "candidate"),
        DetectorDefinition("C_CPP.X86.MACRO_ARGUMENT_BUILTIN", "macro_argument", ("builtins",), "x86_builtin_reference", "exact", "candidate"),
        DetectorDefinition(
            "C_CPP.X86.MACRO_REPLACEMENT_INTRINSIC",
            "macro_replacement",
            ("intrinsics",),
            "x86_intrinsic_reference",
            "exact",
        ),
        DetectorDefinition(
            "C_CPP.X86.MACRO_REPLACEMENT_BUILTIN",
            "macro_replacement",
            ("builtins",),
            "x86_builtin_reference",
            "exact",
        ),
        DetectorDefinition("C_CPP.X86.BUILTIN", "symbol", ("builtins",), "x86_builtin_reference", "exact"),
        DetectorDefinition("C_CPP.X86.VECTOR_TYPE", "type", ("vectorTypes",), "x86_vector_type_reference", "token"),
        DetectorDefinition("C_CPP.X86.PLATFORM_MACRO", "macro", ("platformMacros",), "x86_platform_macro_reference", "token"),
        DetectorDefinition("C_CPP.X86.PLATFORM_CONDITION", "preprocessor", ("platformMacros",), "x86_platform_macro_reference", "token"),
        DetectorDefinition("C_CPP.X86.COMPILER_FLAG", "compiler_argument", ("compilerFlags",), "x86_compiler_flag", "flag"),
        DetectorDefinition("C_CPP.X86.CPU_PLATFORM_API", "symbol", ("cpuPlatformApis",), "x86_cpu_platform_api", "exact"),
        DetectorDefinition("C_CPP.X86.ASM_INSTRUCTION", "inline_asm", ("assemblyInstructions",), "x86_assembly_instruction", "token"),
        DetectorDefinition("C_CPP.X86.ASM_REGISTER", "inline_asm", ("registerNames",), "x86_register_reference", "token"),
        DetectorDefinition("C_CPP.X86.TARGET_FEATURE", "declaration_attribute", ("targetFeatures",), "x86_target_feature", "token"),
        DetectorDefinition(
            "C_CPP.MIGRATION.FIXED_VECTOR_WIDTH", "declaration_attribute", ("vectorWidthMarkers",),
            "fixed_vector_width_candidate", "token", "candidate",
        ),
        DetectorDefinition(
            "C_CPP.X86.ARCH_ALIAS_ATTRIBUTE", "declaration_attribute", ("architectureAliases",),
            "x86_architecture_alias", "token",
        ),
        DetectorDefinition("C_CPP.X86.COMMENT_HINT", "comment", (), "x86_comment_hint", "comment_hint", "hint"),
        DetectorDefinition("C_CPP.X86.INLINE_ASM_CANDIDATE", "inline_asm", (), "inline_asm_candidate", "structural", "candidate"),
        DetectorDefinition("C_CPP.X86.ATTRIBUTE_CANDIDATE", "declaration_attribute", (), "target_attribute_candidate", "structural", "candidate"),
        DetectorDefinition("C_CPP.X86.ARCH_MACRO_CANDIDATE", "macro", (), "architecture_identifier_candidate", "architecture_candidate", "candidate"),
        DetectorDefinition("C_CPP.X86.ARCH_CONDITION_CANDIDATE", "preprocessor", (), "architecture_identifier_candidate", "architecture_candidate", "candidate"),
        DetectorDefinition(
            "C_CPP.X86.ARCH_ENUM_MEMBER_CANDIDATE", "enum_member",
            ("architectureAliases", "platformMacros", "targetFeatures"),
            "architecture_enum_candidate", "enum_architecture_candidate", "candidate",
        ),
        DetectorDefinition(
            "C_CPP.X86.ARCH_ENUM_REFERENCE_CANDIDATE", "enum_reference",
            ("architectureAliases", "platformMacros", "targetFeatures"),
            "architecture_enum_candidate", "enum_architecture_candidate", "candidate",
        ),
    )
)


# arm64 检测器定义：与 x86 定义同构，detector_id/observation_kind 换为 ARM64 命名，
# 匹配时经 KnowledgeIndex.by_kind_and_family 只命中 arm64 族知识条目。
# 语料暂无 intrinsics 函数名/vectorTypes/registers/compilerFlags 词表，对应定义
# 保留（空集合不产生误报），待词表扩充后自动生效。
ARM64_DEFINITIONS = build_registry(
    (
        DetectorDefinition("C_CPP.ARM64.HEADER", "include", ("headers",), "arm64_header_reference", "exact"),
        DetectorDefinition("C_CPP.ARM64.INTRINSIC", "symbol", ("intrinsics",), "arm64_intrinsic_reference", "exact"),
        DetectorDefinition("C_CPP.ARM64.MACRO_ARGUMENT_INTRINSIC", "macro_argument", ("intrinsics",), "arm64_intrinsic_reference", "exact", "candidate"),
        DetectorDefinition("C_CPP.ARM64.MACRO_ARGUMENT_BUILTIN", "macro_argument", ("builtins",), "arm64_builtin_reference", "exact", "candidate"),
        DetectorDefinition(
            "C_CPP.ARM64.MACRO_REPLACEMENT_INTRINSIC",
            "macro_replacement",
            ("intrinsics",),
            "arm64_intrinsic_reference",
            "exact",
        ),
        DetectorDefinition(
            "C_CPP.ARM64.MACRO_REPLACEMENT_BUILTIN",
            "macro_replacement",
            ("builtins",),
            "arm64_builtin_reference",
            "exact",
        ),
        DetectorDefinition("C_CPP.ARM64.BUILTIN", "symbol", ("builtins",), "arm64_builtin_reference", "exact"),
        DetectorDefinition("C_CPP.ARM64.VECTOR_TYPE", "type", ("vectorTypes",), "arm64_vector_type_reference", "token"),
        DetectorDefinition("C_CPP.ARM64.PLATFORM_MACRO", "macro", ("platformMacros",), "arm64_platform_macro_reference", "token"),
        DetectorDefinition("C_CPP.ARM64.PLATFORM_CONDITION", "preprocessor", ("platformMacros",), "arm64_platform_macro_reference", "token"),
        DetectorDefinition("C_CPP.ARM64.COMPILER_FLAG", "compiler_argument", ("compilerFlags",), "arm64_compiler_flag", "flag"),
        DetectorDefinition("C_CPP.ARM64.CPU_PLATFORM_API", "symbol", ("cpuPlatformApis",), "arm64_cpu_platform_api", "exact"),
        DetectorDefinition("C_CPP.ARM64.ASM_INSTRUCTION", "inline_asm", ("assemblyInstructions",), "arm64_assembly_instruction", "token"),
        DetectorDefinition("C_CPP.ARM64.ASM_REGISTER", "inline_asm", ("registerNames",), "arm64_register_reference", "token"),
        DetectorDefinition("C_CPP.ARM64.TARGET_FEATURE", "declaration_attribute", ("targetFeatures",), "arm64_target_feature", "token"),
        DetectorDefinition(
            "C_CPP.MIGRATION.FIXED_VECTOR_WIDTH", "declaration_attribute", ("vectorWidthMarkers",),
            "fixed_vector_width_candidate", "token", "candidate",
        ),
        DetectorDefinition(
            "C_CPP.ARM64.ARCH_ALIAS_ATTRIBUTE", "declaration_attribute", ("architectureAliases",),
            "arm64_architecture_alias", "token",
        ),
        DetectorDefinition("C_CPP.ARM64.COMMENT_HINT", "comment", (), "arm64_comment_hint", "comment_hint", "hint"),
        DetectorDefinition("C_CPP.ARM64.INLINE_ASM_CANDIDATE", "inline_asm", (), "inline_asm_candidate", "structural", "candidate"),
        DetectorDefinition("C_CPP.ARM64.ATTRIBUTE_CANDIDATE", "declaration_attribute", (), "target_attribute_candidate", "structural", "candidate"),
        DetectorDefinition("C_CPP.ARM64.ARCH_MACRO_CANDIDATE", "macro", (), "architecture_identifier_candidate", "architecture_candidate", "candidate"),
        DetectorDefinition("C_CPP.ARM64.ARCH_CONDITION_CANDIDATE", "preprocessor", (), "architecture_identifier_candidate", "architecture_candidate", "candidate"),
        DetectorDefinition(
            "C_CPP.ARM64.ARCH_ENUM_MEMBER_CANDIDATE", "enum_member",
            ("architectureAliases", "platformMacros", "targetFeatures"),
            "architecture_enum_candidate", "enum_architecture_candidate", "candidate",
        ),
        DetectorDefinition(
            "C_CPP.ARM64.ARCH_ENUM_REFERENCE_CANDIDATE", "enum_reference",
            ("architectureAliases", "platformMacros", "targetFeatures"),
            "architecture_enum_candidate", "enum_architecture_candidate", "candidate",
        ),
    )
)


# 源架构族 → 检测器定义注册表；local 检测器按 context.source_arch 选择。
DEFINITIONS_BY_FAMILY = MappingProxyType({
    "x86": DEFAULT_DEFINITIONS,
    "arm64": ARM64_DEFINITIONS,
})


DEFAULT_DEFERRED_CAPABILITIES = MappingProxyType(
    {
        "architecture-alias-amd64-x64": "needs-fixed-normative-source",
    }
)
