"""知识库装载器：扫描流水线第二阶段（knowledge）的入口。

读取规则包内的知识清单（v1/v2），按声明逐个装载并校验生成事实与策展事实 JSON
（SHA-256 逐文件核对），把所有事实规范化合并为一个不可变的 KnowledgeSnapshot
供后续 detector 阶段匹配使用。
"""

from __future__ import annotations

import json
from pathlib import Path

from pydantic import ValidationError

from ..core import (
    CompletionStatus, Diagnostic, DiagnosticSeverity, KnowledgeEntry, KnowledgeSnapshot, KnowledgeSource, ScanContext, StageResult,
)
from .identity import canonicalize_fact_value, content_sha256, knowledge_entry_id, knowledge_snapshot_id, knowledge_source_id
from .production import lint_curated, lint_generated
from .schema import (
    CollectedFactsModel, CollectedManifestSourceModel, CuratedFactsModel, CuratedManifestSourceModel, FACT_KINDS,
    GeneratedFactsModel, GeneratedFactsV2Model, GeneratedSourceModel, KnowledgeManifestModel, KnowledgeManifestV2Model,
    ManifestSourceModel, ReferenceRegistryModel, SourceDescriptorModel,
)


class GeneratedFactsKnowledgeProvider:
    """把规则包中的知识清单 + 事实 JSON 装载为 KnowledgeSnapshot 的供应器。

    流水线位置：knowledge 阶段。不变量：所有来源必须位于规则包目录内且摘要与清单一致；
    全部来源失败时返回 FAILED，部分失败时快照状态为 PARTIAL（五态中的 complete/partial）。
    """

    __slots__ = ("_manifest_name",)

    def __init__(self, manifest_name: str | None = None) -> None:
        """manifest_name 允许覆盖扫描上下文中的默认清单文件名（主要用于测试）。"""
        self._manifest_name = manifest_name

    def load(self, context: ScanContext) -> StageResult[KnowledgeSnapshot]:
        """装载知识库：解析清单→逐来源装载事实→合并去重→构造快照。

        返回 StageResult：成功为 COMPLETE；任一来源有告警为 PARTIAL；
        清单不可用或无任何有效来源为 FAILED（携带 ERROR 级诊断，影响退出码）。
        """
        root = context.rule_dir.resolve()
        manifest_path = (root / (self._manifest_name or context.knowledge_manifest_name)).resolve()
        if not manifest_path.is_relative_to(root):
            return _failed("knowledge.manifest-path-escape", "Knowledge manifest resolves outside the rule pack")
        try:
            content = manifest_path.read_bytes()
            payload = json.loads(content)
            if not isinstance(payload, dict):
                raise ValueError("manifest root must be an object")
            if payload.get("schemaVersion") == "1.0":
                manifest: KnowledgeManifestModel | KnowledgeManifestV2Model = KnowledgeManifestModel.model_validate_json(content)
            elif payload.get("schemaVersion") == "2.0":
                manifest = KnowledgeManifestV2Model.model_validate_json(content)
            else:
                raise ValueError("unsupported manifest version")
        except (OSError, json.JSONDecodeError, ValidationError, ValueError) as exc:
            return _failed("knowledge.invalid-manifest", f"Knowledge manifest is unavailable or invalid: {type(exc).__name__}")

        sources: list[KnowledgeSource] = []
        entries: dict[str, KnowledgeEntry] = {}
        diagnostics: list[Diagnostic] = []
        declarations = manifest.generatedSources if isinstance(manifest, KnowledgeManifestModel) else manifest.sources
        for declaration in declarations:
            loaded = self._load_source(root, declaration)
            if isinstance(loaded, Diagnostic):
                diagnostics.append(loaded)
                continue
            source, family, facts = loaded
            sources.append(source)
            self._merge_entries(entries, source, facts, architecture_family=family)

        if not sources:
            return _failed("knowledge.no-valid-sources", "No knowledge source could be loaded", diagnostics=tuple(diagnostics))
        ordered_sources = tuple(sorted(sources, key=lambda item: (item.source_id, item.id)))
        ordered_entries = tuple(sorted(entries.values(), key=lambda item: (item.fact_kind, item.canonical_value, item.id)))
        snapshot = KnowledgeSnapshot(
            identity=knowledge_snapshot_id(
                manifest.catalogId, manifest.version, tuple(item.id for item in ordered_sources), tuple(item.id for item in ordered_entries),
            ),
            version=manifest.version,
            sources=ordered_sources,
            entries=ordered_entries,
        )
        status = CompletionStatus.PARTIAL if diagnostics else CompletionStatus.COMPLETE
        return StageResult(value=snapshot, status=status, diagnostics=tuple(diagnostics))

    @staticmethod
    def _load_source(
        root: Path,
        model: GeneratedSourceModel | ManifestSourceModel | CuratedManifestSourceModel | CollectedManifestSourceModel,
    ) -> tuple[KnowledgeSource, str, tuple[tuple[str, str, str, tuple[str, ...]], ...]] | Diagnostic:
        """装载单个知识来源，返回 (KnowledgeSource, 架构族, 事实四元组) 或失败 Diagnostic。

        事实四元组为 (fact_kind, canonical_value, semantic_scope, reference_ids)。
        按清单条目 kind 分四路：curated-v1 需额外装载参考登记表并跑策展 lint；
        generated-v2 走 v2 模型并检查来源身份与规范排序；collected-v1 走语料
        收集模型（单源单族，族直接取事实载荷）；其余按 v1 生成事实装载
        （v1 无 scope 信息，默认标记为 x86-only，族固定 x86）。
        """
        content_or_error = _read_declared_json(root, model, model.path, model.sha256)
        if isinstance(content_or_error, Diagnostic):
            return content_or_error
        content = content_or_error
        try:
            if isinstance(model, CuratedManifestSourceModel):
                reference_content = _read_declared_json(root, model, model.referencePath, model.referenceSha256)
                if isinstance(reference_content, Diagnostic):
                    return reference_content
                registry = ReferenceRegistryModel.model_validate_json(reference_content)
                curated = CuratedFactsModel.model_validate_json(content)
                if curated.sourceId != model.sourceId:
                    raise ValueError("curated source identity mismatch")
                lint = lint_curated(curated, registry)
                if lint:
                    raise ValueError(lint[0])
                facts = tuple((item.factKind, item.canonicalValue, item.semanticScope, item.referenceIds) for item in curated.entries)
                metadata = {
                    "reviewPolicy": curated.reviewPolicy,
                    "reviewPolicyVersion": curated.reviewPolicyVersion,
                    "referenceIds": sorted({reference for item in curated.entries for reference in item.referenceIds}),
                }
                kind = "curated-json"
                family = "x86"
            elif isinstance(model, CollectedManifestSourceModel):
                collected = CollectedFactsModel.model_validate_json(content)
                if collected.source.sourceId != model.sourceId or lint_generated(_collected_as_v2(collected)):
                    raise ValueError("collected source identity or canonical order is invalid")
                facts = tuple((item.factKind, item.canonicalValue, item.semanticScope, ()) for item in collected.facts)
                metadata = {"upstreamSource": collected.source.model_dump(mode="json")}
                kind = "collected-json"
                family = collected.facts[0].architectureFamily
            elif isinstance(model, ManifestSourceModel) and model.kind == "generated-v2":
                generated = GeneratedFactsV2Model.model_validate_json(content)
                if generated.source.sourceId != model.sourceId or lint_generated(generated):
                    raise ValueError("generated source identity or canonical order is invalid")
                facts = tuple((item.factKind, item.canonicalValue, item.semanticScope, ()) for item in generated.facts)
                metadata = {"upstreamSource": generated.source.model_dump(mode="json")}
                kind = "generated-json-v2"
                family = "x86"
            else:
                legacy = GeneratedFactsModel.model_validate_json(content)
                payload = legacy.model_dump()
                facts = tuple((fact_kind, value, "x86-only", ()) for fact_kind in FACT_KINDS[:6] for value in payload[fact_kind])
                metadata = {"originalSource": legacy.source}
                kind = "generated-json"
                family = "x86"
        except (ValidationError, ValueError) as exc:
            return _source_diagnostic(model, "knowledge.source-invalid", f"Knowledge source shape is invalid: {type(exc).__name__}")
        source = KnowledgeSource(
            id=knowledge_source_id(model.sourceId, model.path, model.sha256),
            source_id=model.sourceId,
            kind=kind,
            logical_path=model.path,
            sha256=model.sha256,
            metadata=metadata,
        )
        return source, family, facts

    @staticmethod
    def _merge_entries(
        entries: dict[str, KnowledgeEntry], source: KnowledgeSource,
        facts: tuple[tuple[str, str, str, tuple[str, ...]], ...],
        architecture_family: str = "x86",
    ) -> None:
        """把一个来源的事实合并进全局 entries 字典（就地修改，key 为稳定 entry_id）。

        同一事实（fact_kind + 架构族 + 规范值）出现在多个来源时合并 source_ids 并对
        referenceIds 去重取并集；semanticScope 只在新版事实上写入。架构族由来源
        装载结果给定（v1 legacy 固定 x86；v2/curated 目前均为 x86 词表；
        collected-v1 按事实载荷推导，如 arm64）。
        """
        for fact_kind, raw_value, scope, reference_ids in facts:
            canonical = canonicalize_fact_value(fact_kind, raw_value)
            entry_id = knowledge_entry_id(fact_kind, architecture_family, canonical)
            current = entries.get(entry_id)
            source_ids = (source.id,) if current is None else (*current.source_ids, source.id)
            metadata = {"identityVersion": 1} if current is None else dict(current.metadata or {})
            if fact_kind not in FACT_KINDS[:6]:
                metadata["semanticScope"] = scope
            if reference_ids:
                metadata["referenceIds"] = sorted(set(metadata.get("referenceIds", [])) | set(reference_ids))
            entries[entry_id] = KnowledgeEntry(
                id=entry_id,
                fact_kind=fact_kind,
                architecture_family=architecture_family,
                canonical_value=canonical,
                source_ids=source_ids,
                metadata=metadata,
            )


def _collected_as_v2(collected: CollectedFactsModel) -> GeneratedFactsV2Model:
    """把 collected 载荷适配为 v2 模型以复用 lint_generated（排序/规范化校验）。

    仅借用工厂的校验逻辑；v2 的 SourceDescriptorModel 字段以占位值填充，
    不进入任何输出——调用方只消费 lint 结果。
    """
    return GeneratedFactsV2Model(
        schemaVersion="2.0",
        source=SourceDescriptorModel(
            sourceId=collected.source.sourceId,
            officialUrl=collected.source.officialUrl,
            release="collected",
            revision="0" * 40,
            license=collected.source.license,
            collectedAt=collected.source.collectedAt,
            inputPaths=collected.source.inputFiles,
            inputSha256=collected.source.inputSha256,
        ),
        facts=collected.facts,
    )


def _read_declared_json(
    root: Path, model: GeneratedSourceModel, logical_path: str, expected_hash: str,
) -> bytes | Diagnostic:
    """读取规则包内声明的 JSON 文件并核对 SHA-256；路径越界/不可读/摘要不符均返回 WARNING 诊断。"""
    path = (root / logical_path).resolve()
    if not path.is_relative_to(root):
        return _source_diagnostic(model, "knowledge.source-path-escape", "Knowledge source resolves outside the rule pack")
    try:
        content = path.read_bytes()
    except OSError as exc:
        return _source_diagnostic(model, "knowledge.source-unreadable", f"Knowledge source is unreadable: {type(exc).__name__}")
    if content_sha256(content) != expected_hash:
        return _source_diagnostic(model, "knowledge.source-hash-mismatch", "Knowledge source digest does not match manifest")
    return content


def _source_diagnostic(model: GeneratedSourceModel, code: str, message: str) -> Diagnostic:
    """构造针对某个知识来源的 WARNING 级诊断（不影响整体退出码，只降级为 PARTIAL）。"""
    return Diagnostic(
        code=code,
        stage="knowledge",
        severity=DiagnosticSeverity.WARNING,
        message=f"{model.sourceId}: {message}",
        producer="generated-facts-knowledge-provider",
        details={"sourceId": model.sourceId, "logicalPath": model.path},
    )


def _failed(code: str, message: str, *, diagnostics: tuple[Diagnostic, ...] = ()) -> StageResult[KnowledgeSnapshot]:
    """构造 FAILED 的 StageResult：附加 ERROR 级诊断并置 affects_exit_code=True。"""
    fatal = Diagnostic(
        code=code, stage="knowledge", severity=DiagnosticSeverity.ERROR, message=message,
        producer="generated-facts-knowledge-provider", affects_exit_code=True,
    )
    return StageResult(value=None, status=CompletionStatus.FAILED, diagnostics=(*diagnostics, fatal))
