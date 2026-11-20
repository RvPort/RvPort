"""知识库（knowledge）阶段的 pydantic 数据契约。

kb-scanner 八阶段流水线（workspace→knowledge→frontend→normalizer→analyzer→detector→classifier→assessor→projector）
中，本模块定义 knowledge 阶段产出的各类 JSON 载荷的严格校验模型：清单（manifest）、生成事实、
人工策展事实、参考源登记与候选决策台账。所有模型均 frozen/strict/extra=forbid，禁止未知字段，
保证知识库内容一旦生成即不可变、可被 SHA-256 精确追溯。
"""

from __future__ import annotations

import re
from typing import Annotated, Any, Literal, Union

from pydantic import BaseModel, ConfigDict, Field, HttpUrl, field_validator, model_validator


# 旧版六类事实种类（v1 生成事实使用）
LEGACY_FACT_KINDS = ("headers", "intrinsics", "builtins", "vectorTypes", "platformMacros", "compilerFlags")
# 新版六类事实种类（v2 生成事实与策展事实使用，按架构敏感维度拆分）
NEW_FACT_KINDS = (
    "assemblyInstructions", "registerNames", "cpuPlatformApis", "targetFeatures", "architectureAliases", "vectorWidthMarkers",
)
FACT_KINDS = (*LEGACY_FACT_KINDS, *NEW_FACT_KINDS)
# v2/collected 事实条目允许的事实种类：新版六类 + collected 语料映射所需的旧版六类
# （detector 的 knowledge_kinds 本就同时消费两类；旧版种类进入 v2 载荷时按族登记）
FACT_KIND_LITERALS = Literal[
    "headers", "intrinsics", "builtins", "vectorTypes", "platformMacros", "compilerFlags",
    "assemblyInstructions", "registerNames", "cpuPlatformApis", "targetFeatures", "architectureAliases", "vectorWidthMarkers",
]
# 知识库支持的知识架构族：事实按族登记，检测按族过滤。
# "riscv" 是**目标族**——条目出现表示代码已有 RISC-V 分支/实现（与源族
# "出现即迁移障碍"方向相反），只被消费端显式 family="riscv" 查询命中，
# 不进入 source_arch 取值集与检测器注册表。
ARCHITECTURE_FAMILIES = ("x86", "arm64", "riscv")
# 语义范围：<族>-only（仅该架构存在）/ <族>-toolchain（该架构工具链语义）/ migration-sensitive（迁移敏感）
SEMANTIC_SCOPES = (
    "x86-only", "x86-toolchain", "arm64-only", "arm64-toolchain", "riscv-only", "migration-sensitive",
)
ArchitectureFamilyLiteral = Literal["x86", "arm64", "riscv"]
SemanticScopeLiteral = Literal["x86-only", "x86-toolchain", "arm64-only", "arm64-toolchain", "riscv-only", "migration-sensitive"]
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_REVISION = re.compile(r"^[0-9a-f]{40}$")
_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


class _StrictModel(BaseModel):
    """所有知识库模型的公共基类：frozen（不可变）、strict（不做隐式类型转换）、extra=forbid。"""

    model_config = ConfigDict(extra="forbid", frozen=True, strict=True)


def _relative_json_path(value: str) -> str:
    """校验值是项目相对路径且以 .json 结尾，返回统一为正斜杠的路径。"""
    normalized = value.replace("\\", "/")
    if normalized.startswith("/") or re.match(r"^[A-Za-z]:/", normalized) or ".." in normalized.split("/"):
        raise ValueError("path must be project relative")
    if not normalized.endswith(".json"):
        raise ValueError("path must name a JSON file")
    return normalized


def _relative_source_path(value: str) -> str:
    """校验值是项目相对路径（允许任意扩展名），返回统一为正斜杠的路径。"""
    normalized = value.replace("\\", "/")
    if not normalized or normalized.startswith("/") or re.match(r"^[A-Za-z]:/", normalized) or ".." in normalized.split("/"):
        raise ValueError("source path must be relative")
    return normalized


def _complete_digest(value: str) -> str:
    """校验值是完整的小写 SHA-256 十六进制摘要（64 位十六进制字符）。"""
    if not _SHA256.fullmatch(value):
        raise ValueError("sha256 must be a complete lowercase digest")
    return value


class GeneratedSourceModel(_StrictModel):
    """清单中单个生成来源：sourceId + 项目相对 JSON 路径 + 内容 SHA-256。"""

    sourceId: str = Field(min_length=1)
    path: str = Field(min_length=1)
    sha256: str

    @field_validator("path")
    @classmethod
    def validate_path(cls, value: str) -> str:
        return _relative_json_path(value)

    @field_validator("sha256")
    @classmethod
    def validate_sha256(cls, value: str) -> str:
        return _complete_digest(value)


class KnowledgeManifestModel(_StrictModel):
    """Frozen v1 contract; curatedSets remains invalid.

    v1 知识库清单：schemaVersion 固定为 1.0，列出全部生成来源；不允许出现策展集合（curatedSets）。
    不变量：sourceId 与 path 均必须唯一。
    """

    schemaVersion: str = Field(pattern=r"^1\.0$")
    catalogId: str = Field(min_length=1)
    version: str = Field(min_length=1)
    generatedSources: tuple[GeneratedSourceModel, ...] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_unique_sources(self) -> "KnowledgeManifestModel":
        _require_unique(self.generatedSources, "sourceId", "path")
        return self


class ManifestSourceModel(GeneratedSourceModel):
    """v2 清单中的生成来源条目（kind 取 legacy-generated 或 generated-v2）。"""

    kind: Literal["legacy-generated", "generated-v2"]


class CuratedManifestSourceModel(GeneratedSourceModel):
    """v2 清单中的策展来源条目：除运行时 JSON 外还引用一份只读参考副本（referencePath/Sha256）。"""

    kind: Literal["curated-v1"]
    referencePath: str
    referenceSha256: str

    @field_validator("referencePath")
    @classmethod
    def validate_reference_path(cls, value: str) -> str:
        return _relative_json_path(value)

    @field_validator("referenceSha256")
    @classmethod
    def validate_reference_sha256(cls, value: str) -> str:
        return _complete_digest(value)


class CollectedManifestSourceModel(GeneratedSourceModel):
    """v2 清单中的语料收集来源条目（collected-v1）。

    语料提取自官方手册/文档而非 git 仓库，无法满足 40 位精确 commit 溯源；
    以语料原件（collectedFiles）的 SHA-256 + 官方 URL + 采集日期登记出处。
    不变量：collectedFiles 文件名唯一，与运行时 JSON 路径互不冲突。
    """

    kind: Literal["collected-v1"]
    collectedFiles: tuple[str, ...] = Field(min_length=1)

    @field_validator("collectedFiles")
    @classmethod
    def validate_collected_files(cls, values: tuple[str, ...]) -> tuple[str, ...]:
        normalized = tuple(_relative_source_path(value) for value in values)
        if len(normalized) != len(set(normalized)):
            raise ValueError("collected file names must be unique")
        return normalized


RuntimeManifestSourceModel = Annotated[
    Union[ManifestSourceModel, CuratedManifestSourceModel, CollectedManifestSourceModel], Field(discriminator="kind")
]


class KnowledgeManifestV2Model(_StrictModel):
    """v2 知识库清单：按 kind 判别联合装载生成来源与策展来源。

    不变量：sourceId/path 唯一，且运行时路径与参考路径互不重叠、参考路径彼此不重复
    （否则同一 JSON 会被两种装载方式重复消费）。
    """

    schemaVersion: str = Field(pattern=r"^2\.0$")
    catalogId: str = Field(min_length=1)
    version: str = Field(min_length=1)
    sources: tuple[RuntimeManifestSourceModel, ...] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_unique_sources(self) -> "KnowledgeManifestV2Model":
        _require_unique(self.sources, "sourceId", "path")
        runtime_paths = [item.path for item in self.sources]
        reference_paths = [item.referencePath for item in self.sources if isinstance(item, CuratedManifestSourceModel)]
        if set(runtime_paths) & set(reference_paths) or len(reference_paths) != len(set(reference_paths)):
            raise ValueError("runtime and reference paths must be unique")
        return self


class GeneratedFactsModel(_StrictModel):
    """v1 生成事实载荷：六个事实种类各为一个字符串元组，source 为 JSON 安全的元数据字典。"""

    source: dict[str, Any]
    headers: tuple[str, ...]
    intrinsics: tuple[str, ...]
    builtins: tuple[str, ...]
    vectorTypes: tuple[str, ...]
    platformMacros: tuple[str, ...]
    compilerFlags: tuple[str, ...]

    @field_validator(*LEGACY_FACT_KINDS)
    @classmethod
    def validate_entries(cls, values: tuple[str, ...]) -> tuple[str, ...]:
        if any(not value.strip() for value in values):
            raise ValueError("generated fact values must be non-empty strings")
        return values

    @field_validator("source")
    @classmethod
    def validate_source(cls, value: dict[str, Any]) -> dict[str, Any]:
        _validate_json_value(value)
        return value


class SourceDescriptorModel(_StrictModel):
    """v2 生成事实的来源描述：官方 URL、精确 commit、许可与采集日期、输入文件路径及摘要。

    不变量：revision 必须是 40 位小写十六进制 commit（禁止 main/master/trunk 这类浮动引用），
    inputPaths 与 inputSha256 一一对应且路径唯一。
    """

    sourceId: str = Field(min_length=1)
    officialUrl: HttpUrl
    release: str = Field(min_length=1)
    revision: str
    license: str = Field(min_length=1)
    collectedAt: str
    inputPaths: tuple[str, ...] = Field(min_length=1)
    inputSha256: tuple[str, ...] = Field(min_length=1)

    @field_validator("revision")
    @classmethod
    def validate_revision(cls, value: str) -> str:
        if value in {"main", "master", "trunk"} or not _REVISION.fullmatch(value):
            raise ValueError("revision must be an exact lowercase 40-character commit")
        return value

    @field_validator("collectedAt")
    @classmethod
    def validate_date(cls, value: str) -> str:
        if not _DATE.fullmatch(value):
            raise ValueError("collectedAt must use YYYY-MM-DD")
        return value

    @field_validator("inputPaths")
    @classmethod
    def validate_input_paths(cls, values: tuple[str, ...]) -> tuple[str, ...]:
        return tuple(_relative_source_path(value) for value in values)

    @field_validator("inputSha256")
    @classmethod
    def validate_input_hashes(cls, values: tuple[str, ...]) -> tuple[str, ...]:
        return tuple(_complete_digest(value) for value in values)

    @model_validator(mode="after")
    def validate_inputs(self) -> "SourceDescriptorModel":
        if len(self.inputPaths) != len(self.inputSha256):
            raise ValueError("each input path requires one digest")
        if len(self.inputPaths) != len(set(self.inputPaths)):
            raise ValueError("input paths must be unique")
        return self


class GeneratedFactV2Model(_StrictModel):
    """单条 v2 生成事实：事实种类 + 架构族（x86/arm64 源族、riscv 目标族）+ 规范值 + 语义范围。"""

    factKind: FACT_KIND_LITERALS
    architectureFamily: ArchitectureFamilyLiteral
    canonicalValue: str = Field(min_length=1)
    semanticScope: SemanticScopeLiteral

class GeneratedFactsV2Model(_StrictModel):
    """v2 生成事实载荷：一条来源描述 + 一组事实，事实三元组（种类/架构/规范值）必须唯一。"""

    schemaVersion: str = Field(pattern=r"^2\.0$")
    source: SourceDescriptorModel
    facts: tuple[GeneratedFactV2Model, ...] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_unique_facts(self) -> "GeneratedFactsV2Model":
        keys = [(item.factKind, item.architectureFamily, item.canonicalValue) for item in self.facts]
        if len(keys) != len(set(keys)):
            raise ValueError("generated facts must be unique")
        return self


class CollectedSourceDescriptorModel(_StrictModel):
    """语料收集来源描述：官方手册 URL、许可与采集日期、语料原件文件名及摘要。

    与 SourceDescriptorModel 的区别：无 revision（手册语料无精确 commit 可引用），
    inputFiles 的 sha256 登记语料原件字节，保证转换产物可追溯到采集输入。
    """

    sourceId: str = Field(min_length=1)
    officialUrl: HttpUrl
    license: str = Field(min_length=1)
    collectedAt: str
    inputFiles: tuple[str, ...] = Field(min_length=1)
    inputSha256: tuple[str, ...] = Field(min_length=1)

    @field_validator("collectedAt")
    @classmethod
    def validate_date(cls, value: str) -> str:
        return SourceDescriptorModel.validate_date(value)

    @field_validator("inputFiles")
    @classmethod
    def validate_input_files(cls, values: tuple[str, ...]) -> tuple[str, ...]:
        return tuple(_relative_source_path(value) for value in values)

    @field_validator("inputSha256")
    @classmethod
    def validate_input_hashes(cls, values: tuple[str, ...]) -> tuple[str, ...]:
        return tuple(_complete_digest(value) for value in values)

    @model_validator(mode="after")
    def validate_inputs(self) -> "CollectedSourceDescriptorModel":
        if len(self.inputFiles) != len(self.inputSha256):
            raise ValueError("each collected file requires one digest")
        if len(self.inputFiles) != len(set(self.inputFiles)):
            raise ValueError("collected file names must be unique")
        return self


class CollectedFactsModel(_StrictModel):
    """语料收集事实载荷（collected-v1）：来源描述 + 一组 v2 事实条目。

    不变量：事实三元组唯一；同一来源的事实必须全部属于同一架构族
    （单源单族，避免装载端按来源推导族时产生歧义）。
    """

    schemaVersion: str = Field(pattern=r"^1\.0$")
    source: CollectedSourceDescriptorModel
    facts: tuple[GeneratedFactV2Model, ...] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_unique_facts(self) -> "CollectedFactsModel":
        keys = [(item.factKind, item.architectureFamily, item.canonicalValue) for item in self.facts]
        if len(keys) != len(set(keys)):
            raise ValueError("collected facts must be unique")
        families = {item.architectureFamily for item in self.facts}
        if len(families) != 1:
            raise ValueError("collected facts must share a single architecture family")
        return self


class SourceReferenceModel(_StrictModel):
    """策展事实的证据参考：指向官方仓库中某个逻辑位置（logicalLocation）在精确 commit 上的快照。"""

    referenceId: str = Field(min_length=1)
    officialUrl: HttpUrl
    release: str = Field(min_length=1)
    revision: str
    logicalLocation: str = Field(min_length=1)
    license: str = Field(min_length=1)
    collectedAt: str
    sha256: str

    @field_validator("revision")
    @classmethod
    def validate_revision(cls, value: str) -> str:
        return SourceDescriptorModel.validate_revision(value)

    @field_validator("collectedAt")
    @classmethod
    def validate_date(cls, value: str) -> str:
        return SourceDescriptorModel.validate_date(value)

    @field_validator("sha256")
    @classmethod
    def validate_sha256(cls, value: str) -> str:
        return _complete_digest(value)


class ReferenceRegistryModel(_StrictModel):
    """参考源登记表：全部 SourceReferenceModel 的集合，referenceId 必须唯一。"""

    schemaVersion: str = Field(pattern=r"^1\.0$")
    references: tuple[SourceReferenceModel, ...] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_unique_references(self) -> "ReferenceRegistryModel":
        _require_unique(self.references, "referenceId")
        return self


class CuratedEntryModel(_StrictModel):
    """单条人工策展事实：reviewStatus 固定为 accepted，必须挂接至少一个参考证据。"""

    entryId: str = Field(min_length=1)
    factKind: Literal["assemblyInstructions", "registerNames", "cpuPlatformApis", "targetFeatures", "architectureAliases", "vectorWidthMarkers"]
    architectureFamily: ArchitectureFamilyLiteral
    canonicalValue: str = Field(min_length=1)
    semanticScope: SemanticScopeLiteral
    referenceIds: tuple[str, ...] = Field(min_length=1)
    reviewStatus: Literal["accepted"]

    @field_validator("referenceIds")
    @classmethod
    def validate_reference_ids(cls, values: tuple[str, ...]) -> tuple[str, ...]:
        if any(not value for value in values) or len(values) != len(set(values)):
            raise ValueError("reference ids must be non-empty and unique")
        return values


class CuratedFactsModel(_StrictModel):
    """策展事实载荷：记录评审策略版本，entryId 与事实三元组均必须唯一。"""

    schemaVersion: str = Field(pattern=r"^1\.0$")
    sourceId: str = Field(min_length=1)
    reviewPolicy: str = Field(min_length=1)
    reviewPolicyVersion: str = Field(min_length=1)
    entries: tuple[CuratedEntryModel, ...] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_unique_entries(self) -> "CuratedFactsModel":
        _require_unique(self.entries, "entryId")
        keys = [(item.factKind, item.architectureFamily, item.canonicalValue) for item in self.entries]
        if len(keys) != len(set(keys)):
            raise ValueError("curated facts must be unique")
        return self


class CandidateDecisionModel(_StrictModel):
    """候选决策：对某个生成候选值的处置（generated/accepted/rejected/deferred）。

    不变量：进入运行时（generated/accepted）的决策必须携带参考证据与 runtimeFactKind；
    被拒绝或搁置的决策不得声明 runtimeFactKind。
    """

    decisionId: str = Field(min_length=1)
    category: Literal[
        "assembly-instruction", "register", "cpu-platform-api", "target-feature", "architecture-alias", "vector-width", "linker-source",
    ]
    candidateValue: str = Field(min_length=1)
    status: Literal["generated", "accepted", "rejected", "deferred"]
    referenceIds: tuple[str, ...]
    rationale: str = Field(min_length=1)
    runtimeFactKind: Literal[
        "assemblyInstructions", "registerNames", "cpuPlatformApis", "targetFeatures", "architectureAliases", "vectorWidthMarkers",
    ] | None = None

    @model_validator(mode="after")
    def validate_runtime_decision(self) -> "CandidateDecisionModel":
        if self.status in {"generated", "accepted"} and (not self.referenceIds or self.runtimeFactKind is None):
            raise ValueError("runtime decisions require references and a runtime fact kind")
        if self.status in {"rejected", "deferred"} and self.runtimeFactKind is not None:
            raise ValueError("non-runtime decisions must not declare a runtime fact kind")
        if len(self.referenceIds) != len(set(self.referenceIds)):
            raise ValueError("decision references must be unique")
        return self


class DecisionLedgerModel(_StrictModel):
    """决策台账：scope 固定为 C/C++ x86→RISC-V 静态分析，decisionId 必须唯一。"""

    schemaVersion: str = Field(pattern=r"^1\.0$")
    scope: Literal["c-cpp-x86-to-riscv-static-analysis"]
    decisions: tuple[CandidateDecisionModel, ...] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_unique_decisions(self) -> "DecisionLedgerModel":
        _require_unique(self.decisions, "decisionId")
        return self


def _require_unique(items: tuple[Any, ...], *fields: str) -> None:
    """校验 items 中每个指定字段的取值各自唯一，重复即抛 ValueError。"""
    for field in fields:
        values = [getattr(item, field) for item in items]
        if len(values) != len(set(values)):
            raise ValueError(f"{field} values must be unique")


def _validate_json_value(value: Any) -> None:
    """递归校验 value 只含 JSON 安全类型（None/bool/int/float/str 及其数组、字符串键字典）。"""
    if value is None or isinstance(value, (bool, int, float, str)):
        return
    if isinstance(value, list):
        for item in value:
            _validate_json_value(item)
        return
    if isinstance(value, dict) and all(isinstance(key, str) for key in value):
        for item in value.values():
            _validate_json_value(item)
        return
    raise ValueError("source metadata must be JSON-safe")
