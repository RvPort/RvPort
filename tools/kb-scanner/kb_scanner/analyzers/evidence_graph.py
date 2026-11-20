"""证据图分析器：把规范化快照物化为实体-关系图并解析证明路径。

analyzer 阶段的核心模块。三步走：
1. GraphEntityIndexer 把 Evidence 归一成图实体（宏定义/调用点/枚举/分支等）；
2. RelationBuilder 建立关系（defines/includes/calls/invokes_macro/expands_to/
   guarded_by/alternative_to/selects 等），全部带证据溯源与五态状态；
3. ProofPathResolver 在图上做有界搜索产出 ProofPath，并派生 reachable_from 关系。
所有实体/关系 ID 都是 stable_id（SHA-256），输出按 ID 排序保证确定性。
"""
from __future__ import annotations
from bisect import bisect_left, bisect_right
from .span_lookup import SpanLookup

import re
from collections import defaultdict
from dataclasses import dataclass, replace
from pathlib import PurePosixPath
from typing import Callable

from ..core import (
    AnalysisSnapshot,
    CompletionStatus,
    Diagnostic,
    DiagnosticSeverity,
    Entity,
    Evidence,
    KnowledgeSnapshot,
    NormalizedSnapshot,
    Relation,
    ScanContext,
    SourceSpan,
    StageResult,
    stable_id,
)
from ..graph import GraphStore, NetworkXGraphStore
from .macro_resolution import MacroDefinitionResolver
from .proof import ProofBudgets, ProofPathResolver
from .relation_diagnostics import relation_diagnostic
from .architecture_guards import foreign_guard_proof, required_foreign


_IDENTIFIER = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
# 分支角色判定用的词表正则：riscv/generic/portable/scalar（x86 走知识库+专用正则）。
_ROLE_PATTERNS = {
    "riscv": re.compile(r"(?i)(?<![A-Za-z0-9])riscv(?:32|64)?(?![A-Za-z0-9])"),
    "generic": re.compile(r"(?i)(?<![A-Za-z0-9])(generic|default)(?![A-Za-z0-9])"),
    "portable": re.compile(r"(?i)(?<![A-Za-z0-9])portable(?![A-Za-z0-9])"),
    "scalar": re.compile(r"(?i)(?<![A-Za-z0-9])scalar(?![A-Za-z0-9])"),
}


@dataclass(frozen=True, slots=True)
class BranchRecord:
    """一条预处理器分支记录（frozen 不可变）。

    四元组 (group, branch, implementation, evidence) 加角色与知识条目 ID：
    group 是条件组实体、branch 是具体分支、implementation 是该分支对应的
    实现实体、role 是架构角色（x86/riscv/generic/fallback/foreign/unknown）。
    """

    group: Entity
    branch: Entity
    implementation: Entity
    evidence: Evidence
    role: str
    knowledge_ids: tuple[str, ...]


@dataclass(slots=True)
class GraphIndex:
    """GraphEntityIndexer 产出的图索引（构建后只读）。

    聚合了各类查询表：文件/实现/宏定义/调用符号/分支等，供 RelationBuilder
    与 IncludeResolver 复用，避免反复线性扫描。
    """

    entities: dict[str, Entity]
    project: Entity
    files_by_id: dict[str, Entity]
    files_by_path: dict[str, Entity]
    implementations_by_file: dict[str, Entity]
    evidence_entities: dict[str, tuple[Entity, ...]]
    definitions_by_name: dict[str, tuple[Entity, ...]]
    definition_by_evidence: dict[str, Entity]
    definition_file: dict[str, str]
    invocation_entities: tuple[Entity, ...]
    symbol_entities: tuple[Entity, ...]
    branches: tuple[BranchRecord, ...]


class GraphEntityIndexer:
    """把规范化快照归一为图实体：项目、实现、宏、调用点、枚举与分支。

    不变量：不修改输入快照；产出实体全部经 stable_id 生成（同类同名同位置 =>
    同 ID）；分支角色判定只依赖词表与知识库，不做编译器推理。
    """

    __slots__ = ()

    def index(
        self,
        context: ScanContext,
        snapshot: NormalizedSnapshot,
        knowledge: KnowledgeSnapshot,
    ) -> GraphIndex:
        """构建 GraphIndex。

        参数：context 提供规则包 ID；snapshot 提供实体/证据；knowledge 用于
        分支角色判定。副作用：新建的实体（project/implementation/图实体/分支）
        写入 index.entities。返回的索引为后续关系构建的唯一数据源。
        """
        entities = {item.id: item for item in snapshot.entities}
        files_by_id = {item.id: item for item in snapshot.entities if item.kind == "source_file"}
        files_by_path = {item.name: item for item in files_by_id.values()}
        project = Entity(
            stable_id("graph_entity", 1, "project", tuple(sorted(files_by_path)), context.rule_pack_id),
            "project",
            ".",
            properties={"identityVersion": 1, "rulePackId": context.rule_pack_id},
        )
        entities[project.id] = project
        implementations_by_file: dict[str, Entity] = {}
        # 每个源文件配一个 implementation 实体（角色初始 unknown，分支实现再细分）。
        for file_entity in files_by_id.values():
            implementation = Entity(
                stable_id("graph_entity", 1, "implementation", file_entity.name),
                "implementation",
                file_entity.name,
                properties={"identityVersion": 1, "sourceFileId": file_entity.id, "role": "unknown"},
            )
            entities[implementation.id] = implementation
            implementations_by_file[file_entity.id] = implementation

        evidence_entities: dict[str, list[Entity]] = defaultdict(list)
        definitions_by_name: dict[str, list[Entity]] = defaultdict(list)
        definition_by_evidence: dict[str, Entity] = {}
        definition_file: dict[str, str] = {}
        symbol_by_name: dict[str, Entity] = {}
        invocation_entities: list[Entity] = []

        for evidence in sorted(snapshot.evidence, key=lambda item: item.id):
            if evidence.subject_id not in files_by_id:
                # 输入损坏：证据引用了不存在的源文件实体，直接拒绝整个分析。
                raise ValueError("Evidence references an unknown source file Entity")
            if not isinstance(evidence.value, str):
                # 非字符串值的证据（数值等）不参与图实体化。
                continue
            # 证据种类 -> 图实体种类的映射；不在表中的证据种类跳过。
            kind = {
                "macro_definition": "macro_definition",
                "macro_expansion": "macro_invocation",
                "call_expression": "call_site",
                "include": "include_site",
                "enum_declaration": "enum_type",
                "enum_member_declaration": "enum_member",
                "enum_member_reference": "enum_member_reference",
                "constexpr_condition": "selection_predicate",
                "selection_condition": "selection_predicate",
                "inline_asm": "asm_site",
                "declaration_attribute": "attribute_site",
            }.get(evidence.kind)
            if kind is None:
                continue
            # 同一证据可挂多个实体（如调用点 + 符号）；ID 由种类/名称/位置/配置决定。
            entity = _entity_from_evidence(kind, evidence.value, evidence)
            entities[entity.id] = entity
            evidence_entities[evidence.id].append(entity)
            if kind == "macro_definition":
                definitions_by_name[evidence.value].append(entity)
                definition_by_evidence[evidence.id] = entity
                definition_file[entity.id] = evidence.subject_id
            elif kind == "macro_invocation":
                invocation_entities.append(entity)
            elif kind == "call_site":
                symbol = symbol_by_name.setdefault(evidence.value, _symbol_entity(evidence.value))
                entities[symbol.id] = symbol
                evidence_entities[evidence.id].append(symbol)

        for evidence in sorted(snapshot.evidence, key=lambda item: item.id):
            # 第二遍：调用表达式若名字与某个宏定义同名，补挂宏调用实体，
            # 使"函数调用即宏调用"的路径可以被搜索到。
            if evidence.kind != "call_expression" or not isinstance(evidence.value, str):
                continue
            if evidence.value in definitions_by_name:
                invocation = _entity_from_evidence("macro_invocation", evidence.value, evidence)
                entities[invocation.id] = invocation
                evidence_entities[evidence.id].append(invocation)
                invocation_entities.append(invocation)

        branches = _index_branches(snapshot.evidence, entities, evidence_entities, knowledge, getattr(context, "source_arch", "x86"))
        # 输出统一按 ID 排序去重，保证相同快照产出相同索引。
        return GraphIndex(
            entities=entities,
            project=project,
            files_by_id=files_by_id,
            files_by_path=files_by_path,
            implementations_by_file=implementations_by_file,
            evidence_entities={key: tuple(sorted(value, key=lambda item: item.id)) for key, value in evidence_entities.items()},
            definitions_by_name={key: tuple(sorted(value, key=lambda item: item.id)) for key, value in definitions_by_name.items()},
            definition_by_evidence=definition_by_evidence,
            definition_file=definition_file,
            invocation_entities=tuple(sorted({item.id: item for item in invocation_entities}.values(), key=lambda item: item.id)),
            symbol_entities=tuple(sorted(symbol_by_name.values(), key=lambda item: item.id)),
            branches=branches,
        )


class IncludeResolver:
    """把 #include 值解析到授权文件清单内的实体（或外部头实体）。

    解析顺序：相对包含文件的父目录 -> 全路径精确匹配 -> 后缀唯一匹配 ->
    按公共目录前缀择优。歧义时返回全部候选并标 partial（ambiguous-include），
    解析不到则创建 external_header 实体并标 unknown。
    """

    __slots__ = ("_index",)

    def __init__(self, index: GraphIndex) -> None:
        self._index = index

    def resolve(self, including_file: Entity, include_value: str) -> tuple[tuple[Entity, ...], str, str | None]:
        """解析一条 include 值。

        返回 (候选实体元组, 状态, 原因)：状态为 complete/partial/unknown，
        原因在非 complete 时给出（unsafe-include-path/ambiguous-include/
        unresolved-include）。副作用：外部头实体写入 index.entities。
        """
        normalized = include_value.replace("\\", "/").strip('<>"')
        include_path = PurePosixPath(normalized)
        if include_path.is_absolute() or ".." in include_path.parts:
            # 绝对路径或含 .. 的 include 不参与项目内解析（安全边界）。
            return (self._external(normalized, "unsafe"),), "unknown", "unsafe-include-path"
        # 先试相对包含文件的父目录，再试按全路径直接命中。
        parent_candidate = (PurePosixPath(including_file.name).parent / include_path).as_posix()
        direct = self._index.files_by_path.get(parent_candidate) or self._index.files_by_path.get(include_path.as_posix())
        if direct is not None:
            return (direct,), "complete", None
        suffix = f"/{include_path.as_posix()}"
        candidates = tuple(
            sorted(
                (item for path, item in self._index.files_by_path.items() if path == include_path.as_posix() or path.endswith(suffix)),
                key=lambda item: item.id,
            )
        )
        if len(candidates) == 1:
            # 后缀匹配唯一命中：视为完整解析。
            return candidates, "complete", None
        if candidates:
            # 多候选：按公共目录前缀打分，唯最近的才算 complete，否则 partial。
            scored = tuple((self._common_prefix(including_file.name, item.name), item) for item in candidates)
            best = max(score for score, _ in scored)
            nearest = tuple(item for score, item in scored if score == best)
            if best > 0 and len(nearest) == 1:
                return nearest, "complete", None
            return candidates, "partial", "ambiguous-include"
        return (self._external(normalized, "external"),), "unknown", "unresolved-include"

    @staticmethod
    def _common_prefix(including: str, candidate: str) -> int:
        """两个路径的公共父目录段数（用于歧义 include 的就近打分）。"""
        left = PurePosixPath(including).parent.parts
        right = PurePosixPath(candidate).parent.parts
        score = 0
        for first, second in zip(left, right):
            if first != second:
                break
            score += 1
        return score

    def _external(self, name: str, resolution: str) -> Entity:
        """创建（或复用）外部头实体；resolution 为 external 或 unsafe。"""
        entity = Entity(
            stable_id("graph_entity", 1, "external_header", name, resolution),
            "external_header",
            name,
            properties={"identityVersion": 1, "resolution": resolution},
        )
        self._index.entities[entity.id] = entity
        return entity


class RelationBuilder:
    """在 GraphIndex 之上构建全部图关系。

    构建顺序（build()）：文件/宏定义 -> include -> 调用与宏调用 -> 宏展开 ->
    分支关系 -> 枚举关系。每个关系都经 _add() 统一去重合并（同键关系并证据），
    部分关系附 Diagnostic。构建完成后只读。
    """

    __slots__ = ("_context", "_snapshot", "_knowledge", "_index", "_relations", "_diagnostics",
                 "_evidence_by_id", "_first_evidence", "_relation_buckets", "_branches_by_path",
                 "_knowledge_patterns", "_evidence_by_subject", "_predicate_evidence", "_enum_conditions", "_enum_dependencies", "_enum_dependency_lines")

    def __init__(
        self,
        context: ScanContext,
        snapshot: NormalizedSnapshot,
        knowledge: KnowledgeSnapshot,
        index: GraphIndex,
    ) -> None:
        """初始化各类查询索引（证据/谓词/分支/枚举依赖行号等），不产生关系。"""
        self._context = context
        self._snapshot = snapshot
        self._knowledge = knowledge
        self._index = index
        self._relations: dict[str, Relation] = {}
        self._diagnostics: list[Diagnostic] = []
        self._evidence_by_id = {item.id: item for item in snapshot.evidence}
        self._first_evidence = {}
        for item in snapshot.evidence:
            self._first_evidence.setdefault(item.id, item)
        self._relation_buckets: dict[tuple, list[str]] = defaultdict(list)
        self._knowledge_patterns = {}
        self._evidence_by_subject: dict[str, list[Evidence]] = defaultdict(list)
        for item in snapshot.evidence:
            self._evidence_by_subject[item.subject_id].append(item)
        self._predicate_evidence: dict[str, list[tuple[Entity, Evidence]]] = defaultdict(list)
        self._enum_conditions = SpanLookup(item for item in snapshot.evidence
            if item.kind in {"constexpr_condition", "selection_condition"})
        self._enum_dependencies = defaultdict(list)
        for item in snapshot.evidence:
            if item.location is not None and item.kind in {"call_expression", "type_reference", "include", "inline_asm", "declaration_attribute"}:
                self._enum_dependencies[item.subject_id].append(item)
        self._enum_dependency_lines = {}
        for subject, items in self._enum_dependencies.items():
            items.sort(key=lambda item: item.location.start.line)
            self._enum_dependency_lines[subject] = [item.location.start.line for item in items]
        for item in snapshot.evidence:
            for entity in index.evidence_entities.get(item.id, ()):
                if entity.kind == "selection_predicate":
                    self._predicate_evidence[entity.id].append((entity, item))
        self._branches_by_path: dict[str, list[BranchRecord]] = defaultdict(list)
        for record in index.branches:
            if record.evidence.location is not None:
                self._branches_by_path[record.evidence.location.start.path].append(record)

    def build(self) -> tuple[tuple[Relation, ...], tuple[Diagnostic, ...]]:
        """构建全部关系；返回 (按 _relation_key 排序的关系元组, 诊断元组)。"""
        self._define_files_and_implementations()
        include_targets = self._includes()
        self._calls_and_macro_invocations(include_targets)
        self._macro_replacements()
        self._branch_relations()
        self._enum_relations()
        return tuple(sorted(self._relations.values(), key=_relation_key)), tuple(self._diagnostics)

    def _define_files_and_implementations(self) -> None:
        for file_id, implementation in self._index.implementations_by_file.items():
            self._add("defines", file_id, implementation.id, (), "complete")
        for evidence_id, definition in self._index.definition_by_evidence.items():
            self._add("defines", self._index.definition_file[definition.id], definition.id, (evidence_id,), "complete")

    def _includes(self) -> dict[str, set[str]]:
        """解析全部 include 证据并建立 includes 关系；返回 文件ID -> 可见文件ID 集合。

        可见集合供后续宏定义解析使用（宏只有经 include 链可见才可解析）。
        歧义/未解析的 include 记录诊断。
        """
        resolver = IncludeResolver(self._index)
        targets: dict[str, set[str]] = defaultdict(set)
        for evidence in self._snapshot.evidence:
            if evidence.kind != "include" or not isinstance(evidence.value, str):
                continue
            including = self._index.files_by_id[evidence.subject_id]
            resolved, status, reason = resolver.resolve(including, evidence.value)
            for target in resolved:
                targets[including.id].add(target.id)
                self._add("includes", including.id, target.id, (evidence.id,), status, reason)
            if reason:
                self._diagnostics.append(relation_diagnostic("graph.include-" + reason, evidence, reason, "includes"))
        return targets

    def _calls_and_macro_invocations(self, include_targets: dict[str, set[str]]) -> None:
        """建立 calls 与 invokes_macro 关系。

        调用点 -> 符号 是无条件的；宏调用 -> 宏定义 须经 _visible_definitions
        判定"该位置可见的宏定义"（同名定义可能有多个，只有经 include 可见的才算）。
        找不到定义的宏调用记 unresolved-macro 诊断。
        """
        for evidence in self._snapshot.evidence:
            if evidence.kind not in {"call_expression", "macro_expansion"} or not isinstance(evidence.value, str):
                continue
            attached = self._index.evidence_entities.get(evidence.id, ())
            call_sites = [item for item in attached if item.kind == "call_site"]
            invocations = [item for item in attached if item.kind == "macro_invocation"]
            symbols = [item for item in attached if item.kind == "symbol"]
            for call_site in call_sites:
                for symbol in symbols:
                    self._add("calls", call_site.id, symbol.id, (evidence.id,), "complete")
            resolution = self._visible_definitions(evidence, include_targets)
            definitions = resolution.definitions
            if definitions and not invocations:
                invocation = _entity_from_evidence("macro_invocation", evidence.value, evidence)
                self._index.entities[invocation.id] = invocation
                invocations = [invocation]
            status = resolution.status
            reason = resolution.reason
            for invocation in invocations:
                for definition in definitions:
                    self._add("invokes_macro", invocation.id, definition.id, (evidence.id,), status, reason)
            if invocations and not definitions:
                self._diagnostics.append(relation_diagnostic("graph.unresolved-macro", evidence, "unresolved-macro", "invokes_macro"))

    def _visible_definitions(self, evidence: Evidence, include_targets: dict[str, set[str]]):
        """解析证据位置可见的宏定义（委托 MacroDefinitionResolver）。

        可见文件集合 = 所在文件 ∪ 直接 include 的文件；同名定义多于一个时
        由 resolver 决定状态（partial + ambiguous-macro-definition）。
        """
        definitions = self._index.definitions_by_name.get(str(evidence.value), ())
        visible_files = {evidence.subject_id, *include_targets.get(evidence.subject_id, set())}
        resolver = MacroDefinitionResolver(
            self._index.definition_file,
            self._evidence_by_id,
        )
        return resolver.resolve(evidence, definitions, visible_files)

    def _macro_replacements(self) -> None:
        """建立宏替换 token 的 expands_to（及必要时 calls）关系。

        只处理 tokenRole=call_candidate 的替换 token：定义 -> 目标定义（同名宏）
        或定义 -> 符号（展开后是普通标识符）。同名多定义或带不支持预处理器语义
        时降为 partial 并记原因。
        """
        for evidence in self._snapshot.evidence:
            if evidence.kind != "macro_replacement_token" or not isinstance(evidence.value, str):
                continue
            if evidence.metadata.get("tokenRole") != "call_candidate":
                continue
            definition_id = evidence.metadata.get("macroDefinitionEvidenceId")
            definition = self._index.definition_by_evidence.get(str(definition_id))
            if definition is None:
                raise ValueError("replacement token references an unknown macro definition")
            unsupported = tuple(evidence.metadata.get("unsupportedSemantics", ()))
            unsupported_reason = "unsupported-preprocessor-semantics" if unsupported else None
            targets = self._index.definitions_by_name.get(evidence.value, ())
            if targets:
                status = "complete" if len(targets) == 1 and not unsupported else "partial"
                reasons = tuple(
                    item
                    for item in ("ambiguous-macro-definition" if len(targets) != 1 else None, unsupported_reason)
                    if item
                )
                reason = ",".join(reasons) or None
                for target in targets:
                    self._add("expands_to", definition.id, target.id, (evidence.id,), status, reason)
                continue
            symbol = _symbol_entity(evidence.value)
            self._index.entities[symbol.id] = symbol
            status = "partial" if unsupported else "complete"
            self._add("expands_to", definition.id, symbol.id, (evidence.id,), status, unsupported_reason)
            self._add("calls", definition.id, symbol.id, (evidence.id,), status, unsupported_reason)

    def _branch_relations(self) -> None:
        """建立分支相关的三类关系：成员/定义/守卫 + 嵌套守卫 + x86 替代关系。

        has_branch 把分支挂到条件组；guarded_by 把实现与图实体挂到所在分支
        （嵌套时取最内层再补一层分支->分支守卫）；alternative_to 在同组的
        x86 分支与 riscv/generic/portable/scalar/fallback 分支之间建立。
        """
        by_group: dict[str, list[BranchRecord]] = defaultdict(list)
        for record in self._index.branches:
            # 组->分支 成员关系 + 文件->分支实现 定义关系 + 实现->分支 守卫关系。
            by_group[record.group.id].append(record)
            self._add("has_branch", record.group.id, record.branch.id, (record.evidence.id,), "complete")
            self._add("defines", record.evidence.subject_id, record.implementation.id, (record.evidence.id,), "complete")
            self._add("guarded_by", record.implementation.id, record.branch.id, tuple(sorted({record.evidence.id, *record.implementation.properties.get("architectureEvidenceIds", ())})), "complete")
            local = self._branches_by_path.get(record.evidence.location.start.path, ()) if record.evidence.location else ()
            # 嵌套守卫：找包含本分支的其他组分支，取跨度最小者补 分支->分支 边。
            parents = [parent for parent in local if parent.group.id != record.group.id
                       and parent.evidence.location != record.evidence.location
                       and _contains(parent.evidence.location, record.evidence.location)]
            if parents:
                parent = min(parents, key=lambda item: (_span_size(item.evidence.location), item.branch.id))
                self._add("guarded_by", record.branch.id, parent.branch.id,
                          (record.evidence.id, parent.evidence.id), "complete", metadata={"nestedGuard": True})

        for evidence_id, attached in self._index.evidence_entities.items():
            # 非分支证据：挂到包含它的最内层分支（guarded_by），foreign 分支特殊标注。
            evidence = self._first_evidence.get(evidence_id)
            if evidence is None or evidence.kind in {"preprocessor_condition", "preprocessor_branch"}:
                continue
            local_branches = self._branches_by_path.get(evidence.location.start.path, ()) if evidence.location else ()
            branch = _innermost_branch(evidence, local_branches)
            if branch is None:
                continue
            for entity in attached:
                if entity.kind in {"macro_definition", "macro_invocation", "call_site", "include_site", "asm_site", "attribute_site"}:
                    self._add("guarded_by", entity.id, branch.branch.id, tuple(sorted({evidence.id, branch.evidence.id, *branch.implementation.properties.get("architectureEvidenceIds", ())})), "complete", metadata={"architectureDisposition": "foreign" if branch.role == "foreign" else "unknown"})

        alternatives = {"riscv", "generic", "portable", "scalar", "fallback"}
        # 同组内源架构分支 vs 各替代分支：分支级与实现级各建一条 alternative_to。
        source_family = getattr(self._context, "source_arch", "x86")
        for records in by_group.values():
            x86 = [item for item in records if item.role == source_family]
            siblings = [item for item in records if item.role in alternatives]
            for source in x86:
                for target in siblings:
                    self._add(
                        "alternative_to",
                        source.branch.id,
                        target.branch.id,
                        (source.evidence.id, target.evidence.id),
                        "complete",
                        metadata={
                            "sourceRole": source.role,
                            "targetRole": target.role,
                            "knowledgeIds": tuple(sorted(set(source.knowledge_ids + target.knowledge_ids))),
                        },
                    )
                    self._add(
                        "alternative_to",
                        source.implementation.id,
                        target.implementation.id,
                        (source.evidence.id, target.evidence.id),
                        "complete",
                        metadata={
                            "sourceRole": source.role,
                            "targetRole": target.role,
                            "knowledgeIds": tuple(sorted(set(source.knowledge_ids + target.knowledge_ids))),
                        },
                    )

    def _enum_relations(self) -> None:
        for evidence in self._snapshot.evidence:
            attached = self._index.evidence_entities.get(evidence.id, ())
            if evidence.kind == "enum_declaration":
                for entity in attached:
                    if entity.kind == "enum_type":
                        self._add("defines", evidence.subject_id, entity.id, (evidence.id,), _binding_status(evidence))
            elif evidence.kind == "enum_member_declaration":
                member = next((item for item in attached if item.kind == "enum_member"), None)
                declaration_id = evidence.metadata.get("enumDeclarationEvidenceId")
                enum_type = next((item for item in self._index.evidence_entities.get(str(declaration_id), ()) if item.kind == "enum_type"), None)
                if member is not None:
                    self._add("defines", evidence.subject_id, member.id, (evidence.id,), _binding_status(evidence))
                if enum_type is not None and member is not None:
                    self._add("contains", enum_type.id, member.id, (str(declaration_id), evidence.id), _binding_status(evidence))
            elif evidence.kind == "enum_member_reference":
                reference = next((item for item in attached if item.kind == "enum_member_reference"), None)
                if reference is None:
                    continue
                member_ids = tuple(value for value in evidence.metadata.get("enumMemberDeclarationEvidenceIds", ()) if isinstance(value, str))
                status = _binding_status(evidence)
                for member_id in member_ids:
                    member = next((item for item in self._index.evidence_entities.get(member_id, ()) if item.kind == "enum_member"), None)
                    if member is not None:
                        self._add("contains", member.id, reference.id, (member_id, evidence.id), status)
                predicates = [
                    item
                    for candidate in self._enum_conditions.containing(evidence)
                    for item in self._index.evidence_entities.get(candidate.id, ())
                    if item.kind == "selection_predicate"
                ]
                for predicate in predicates:
                    for member_id in member_ids:
                        member = next((item for item in self._index.evidence_entities.get(member_id, ()) if item.kind == "enum_member"), None)
                        if member is not None:
                            condition = next(item for entity, item in self._predicate_evidence[predicate.id] if entity == predicate)
                            relation_status = "unknown" if condition.metadata.get("templateDependent") else status
                            self._add("selects", predicate.id, member.id, (condition.id, evidence.id, member_id), relation_status, metadata={"selectionContext": condition.metadata.get("selectionContext", "unknown"), "templateDependent": bool(condition.metadata.get("templateDependent"))})
                            self._guard_enum_downstream(predicate, condition, evidence, member_id, relation_status)

    def _guard_enum_downstream(self, predicate: Entity, condition: Evidence, reference: Evidence, member_id: str, status: str) -> None:
        end_line = condition.metadata.get("guardEndLine")
        if not isinstance(end_line, int) or condition.location is None:
            return
        start_line = condition.location.end.line
        items = self._enum_dependencies.get(condition.subject_id, ())
        lines = self._enum_dependency_lines.get(condition.subject_id, ())
        for candidate in items[bisect_left(lines, start_line):bisect_right(lines, end_line)]:
            dependency = _family_knowledge_ids(candidate, self._knowledge, self._knowledge_patterns, getattr(self._context, "source_arch", "x86"))
            for entity in self._index.evidence_entities.get(candidate.id, ()):
                if entity.kind not in {"call_site", "symbol", "include_site", "asm_site", "attribute_site"}:
                    continue
                details = {
                    "enumDependency": bool(dependency),
                    "itemKind": "source_x86_dependency" if dependency else "source_dependency",
                    "knowledgeIds": dependency,
                    "selectionContext": condition.metadata.get("selectionContext", "unknown"),
                }
                self._add("guarded_by", entity.id, predicate.id, (member_id, reference.id, condition.id, candidate.id), status, metadata=details)

    def _add(
        self,
        kind: str,
        source_id: str,
        target_id: str,
        evidence_ids: tuple[str, ...],
        status: str,
        reason: str | None = None,
        metadata: dict[str, object] | None = None,
    ) -> None:
        details = dict(metadata or {})
        reasons = set(filter(None, reason.split(",") if reason else ()))
        referenced_evidence = {identifier: self._evidence_by_id[identifier]
                               for identifier in evidence_ids if identifier in self._evidence_by_id}
        if referenced_evidence and any(
            item.confidence != "high" and not (
                kind == "guarded_by" and details.get("architectureDisposition") == "foreign"
                and item.metadata.get("structureComplete") is True
            ) for item in referenced_evidence.values()
        ):
            status = "partial"
            reasons.add("partial-evidence")
        if reasons:
            details["reason"] = ",".join(sorted(reasons))
        provenance = set(evidence_ids)
        bucket_key = (kind, source_id, target_id, status)
        bucket = self._relation_buckets[bucket_key]
        for existing_id in tuple(bucket):
            existing = self._relations[existing_id]
            if (
                existing.kind == kind
                and existing.source_id == source_id
                and existing.target_id == target_id
                and existing.status == status
                and dict(existing.metadata) == details
            ):
                provenance.update(existing.evidence_ids)
                del self._relations[existing.id]
                bucket.remove(existing.id)
        stable_evidence_ids = tuple(sorted(provenance))
        relation_id = stable_id("relation", 1, kind, source_id, target_id, stable_evidence_ids, status, details)
        relation = Relation(
            relation_id,
            kind,
            source_id,
            target_id,
            stable_evidence_ids,
            "high" if status == "complete" else "medium",
            status,
            details,
        )
        self._relations[relation.id] = relation
        if relation.id not in bucket:
            bucket.append(relation.id)


class EvidenceGraphAnalyzer:
    __slots__ = ("_indexer", "_store_factory", "_budgets", "_demand_selector")

    def __init__(
        self,
        indexer: GraphEntityIndexer | None = None,
        store_factory: Callable[[], GraphStore] = NetworkXGraphStore,
        budgets: ProofBudgets = ProofBudgets(),
        demand_selector: Callable | None = None,
    ) -> None:
        self._indexer = indexer or GraphEntityIndexer()
        self._store_factory = store_factory
        self._budgets = budgets
        self._demand_selector = demand_selector

    def analyze(
        self,
        context: ScanContext,
        facts: NormalizedSnapshot,
        knowledge: KnowledgeSnapshot,
    ) -> StageResult[AnalysisSnapshot]:
        try:
            _validate_snapshot(facts)
            index = self._indexer.index(context, facts, knowledge)
            relations, diagnostics = RelationBuilder(context, facts, knowledge, index).build()
            _validate_relations(index, facts, relations)
            store = self._store_factory()
            for entity in sorted(index.entities.values(), key=lambda item: item.id):
                store.add_entity(entity)
            for relation in relations:
                store.add_relation(relation)
            terminal_ids = tuple(
                item.id
                for item in index.entities.values()
                if item.kind == "symbol"
            )
            proof_source_ids = tuple(
                item.id
                for item in index.entities.values()
                if item.kind in {"source_file", "macro_invocation"}
            )
            demand = None if self._demand_selector is None else self._demand_selector(facts, knowledge)
            proofs = ProofPathResolver(store, self._budgets).resolve(
                proof_source_ids, terminal_ids, relations, required_evidence_ids=demand,
            )
            derived = list(relations)
            relations_by_id = {item.id: item for item in relations}
            for proof in proofs:
                evidence_ids = tuple(
                    sorted(
                        {
                            evidence_id
                            for relation_id in proof.relation_ids
                            for relation in (relations_by_id.get(relation_id),)
                            if relation is not None
                            for evidence_id in relation.evidence_ids
                        }
                    )
                )
                derived.append(
                    Relation(
                        stable_id("relation", 1, "reachable_from", proof.source_id, proof.target_id, proof.id),
                        "reachable_from",
                        proof.source_id,
                        proof.target_id,
                        evidence_ids,
                        "medium",
                        "unknown",
                        {"reason": "build-selection-deferred", "proofPathId": proof.id},
                    )
                )
            all_relations = tuple(sorted({item.id: item for item in derived}.values(), key=_relation_key))
            snapshot = AnalysisSnapshot(
                relations=all_relations,
                entities=tuple(sorted(index.entities.values(), key=lambda item: item.id)),
                proof_paths=proofs,
            )
            partial = diagnostics or any(item.status != "complete" for item in all_relations) or any(
                item.status != "complete" for item in proofs
            )
            return StageResult(
                value=snapshot,
                status=CompletionStatus.PARTIAL if partial else CompletionStatus.COMPLETE,
                diagnostics=diagnostics,
            )
        except (TypeError, ValueError, KeyError) as exc:
            diagnostic = Diagnostic(
                code="graph.invalid-analysis-input",
                stage="analyzer.evidence-graph",
                severity=DiagnosticSeverity.ERROR,
                message=f"Evidence Graph rejected invalid input: {type(exc).__name__}",
                producer="evidence-graph-analyzer",
                details={"reason": str(exc)[:256]},
                affects_exit_code=True,
            )
            return StageResult(value=None, status=CompletionStatus.FAILED, diagnostics=(diagnostic,))


def _entity_from_evidence(kind: str, name: str, evidence: Evidence) -> Entity:
    location = evidence.location
    entity_id = stable_id("graph_entity", 1, kind, name, location, evidence.metadata.get("compilationContextId"))
    return Entity(
        entity_id,
        kind,
        name,
        location,
        {"identityVersion": 1, "sourceEvidenceId": evidence.id},
    )


def _symbol_entity(name: str) -> Entity:
    return Entity(stable_id("graph_entity", 1, "symbol", name), "symbol", name, properties={"identityVersion": 1})


def _relation_key(relation: Relation) -> tuple[str, str, str, str]:
    return relation.kind, relation.source_id, relation.target_id, relation.id


def _contains(outer: SourceSpan | None, inner: SourceSpan | None) -> bool:
    if outer is None or inner is None or outer.start.path != inner.start.path:
        return False
    outer_start = (outer.start.line, outer.start.column or 1)
    outer_end = (outer.end.line, outer.end.column or 1)
    inner_start = (inner.start.line, inner.start.column or 1)
    inner_end = (inner.end.line, inner.end.column or 1)
    return outer_start <= inner_start and inner_end <= outer_end


def _span_size(span: SourceSpan | None) -> tuple[int, int]:
    if span is None:
        return (10**9, 10**9)
    return span.end.line - span.start.line, (span.end.column or 1) - (span.start.column or 1)


def _index_branches(
    evidence_items: tuple[Evidence, ...],
    entities: dict[str, Entity],
    evidence_entities: dict[str, list[Entity]],
    knowledge: KnowledgeSnapshot,
    architecture_family: str = "x86",
) -> tuple[BranchRecord, ...]:
    family = architecture_family.lower()
    # Query indexes live only for this snapshot; preserve order within each bucket.
    by_subject: dict[str, list[Evidence]] = defaultdict(list)
    groups_by_path: dict[str, list[tuple[Evidence, Entity]]] = defaultdict(list)
    group_counts: dict[str, int] = defaultdict(int)
    for evidence in evidence_items:
        by_subject[evidence.subject_id].append(evidence)
    # 源族分支知识 + 目标族（riscv）平台宏：目标族供 _branch_role 的
    # 目标族预检消费（"已有 RISC-V 分支"判定），源族按 source_arch 过滤。
    branch_knowledge = replace(knowledge, entries=tuple(
        entry for entry in knowledge.entries
        if (entry.architecture_family.lower() == architecture_family
            and entry.fact_kind in {"architectureAliases", "platformMacros", "targetFeatures"})
        or (entry.architecture_family.lower() == "riscv" and entry.fact_kind == "platformMacros")
    ))
    roots = [
        item
        for item in evidence_items
        if item.kind == "preprocessor_condition"
        and item.metadata.get("nodeType") in {"preproc_if", "preproc_ifdef", "preproc_ifndef"}
    ]
    records: list[BranchRecord] = []
    for root in roots:
        group = _entity_from_evidence("condition_group", str(root.value), root)
        branch = _entity_from_evidence("branch", f"if:{root.value}", root)
        entities[group.id] = group
        entities[branch.id] = branch
        evidence_entities[root.id].extend((group, branch))
        role, knowledge_ids = _branch_role(
            str(root.value),
            branch_knowledge,
            fallback=False,
            negated=root.metadata.get("nodeType") == "preproc_ifndef" or bool(root.metadata.get("conditionNegated")) or bool(re.match(r"\s*!", str(root.value))),
            architecture_family=architecture_family,
        )
        foreign_proof = foreign_guard_proof(root, by_subject[root.subject_id])
        if foreign_proof and role != family:
            role = "foreign"
        implementation = _branch_implementation(entities[root.subject_id], branch, role, knowledge_ids, foreign_proof)
        entities[implementation.id] = implementation
        records.append(BranchRecord(group, branch, implementation, root, role, knowledge_ids))
        if root.location is not None:
            groups_by_path[root.location.start.path].append((root, group))
        group_counts[group.id] += 1

    branch_evidence = [
        item
        for item in evidence_items
        if item.kind == "preprocessor_branch"
        or (
            item.kind == "preprocessor_condition"
            and item.metadata.get("nodeType") == "preproc_elif"
        )
    ]
    for item in branch_evidence:
        local_groups = groups_by_path.get(item.location.start.path, ()) if item.location else ()
        containing = [(root, group) for root, group in local_groups if _contains(root.location, item.location)]
        if not containing:
            continue
        root, group = min(containing, key=lambda value: _span_size(value[0].location))
        ordinal = 1 + group_counts[group.id]
        fallback = item.kind == "preprocessor_branch" and str(item.value) == "else"
        role, knowledge_ids = _branch_role(str(item.value), branch_knowledge, fallback=fallback, architecture_family=architecture_family)
        branch = _entity_from_evidence("branch", f"{ordinal}:{item.value}", item)
        foreign_proof = () if fallback else foreign_guard_proof(item, by_subject[item.subject_id])
        if foreign_proof and role != family:
            role = "foreign"
        implementation = _branch_implementation(entities[item.subject_id], branch, role, knowledge_ids, foreign_proof)
        entities[branch.id] = branch
        entities[implementation.id] = implementation
        evidence_entities[item.id].append(branch)
        records.append(BranchRecord(group, branch, implementation, item, role, knowledge_ids))
        group_counts[group.id] += 1
    return tuple(sorted(records, key=lambda item: (item.group.id, item.evidence.location.start.line if item.evidence.location else 0, item.branch.id)))


def _branch_role(
    value: str,
    knowledge: KnowledgeSnapshot,
    *,
    fallback: bool,
    negated: bool = False,
    architecture_family: str = "x86",
) -> tuple[str, tuple[str, ...]]:
    family = architecture_family.lower()
    if fallback:
        return "fallback", ()
    condition = re.sub(r"/\*.*?\*/|//[^\r\n]*", " ", value, flags=re.DOTALL)
    # 目标族预检：条件串命中 riscv 目标宏词表（__riscv*）即判为 riscv 分支并携带
    # 知识条目 ID。必须先于 _ROLE_PATTERNS 词形正则（"__riscv_xlen" 内的 "riscv"
    # 子串会被词形路径截胡且不带知识 ID）；negated 时返回 unknown（"未定义
    # __riscv"不构成"已移植"证据，五态纪律）。
    target_role, target_ids = _target_family_role(condition, knowledge)
    if target_role is not None:
        return ("unknown", ()) if negated else (target_role, target_ids)
    for role, pattern in _ROLE_PATTERNS.items():
        if pattern.search(condition):
            return ("unknown", ()) if negated else (role, ())
    matched = []
    lowered = condition.lower()
    for entry in knowledge.entries:
        if entry.architecture_family.lower() != family:
            continue
        if entry.fact_kind not in {"architectureAliases", "platformMacros", "targetFeatures"}:
            continue
        token = entry.canonical_value.lower()
        if token and re.search(rf"(?<![A-Za-z0-9_]){re.escape(token)}(?![A-Za-z0-9_])", lowered):
            matched.append(entry.id)
    family_hint = _family_hint_pattern(family)
    if matched or family_hint.search(condition):
        tokens = {entry.canonical_value.casefold() for entry in knowledge.entries if entry.id in matched}
        necessary = required_foreign(condition, lambda name: (name,) if name.casefold() in tokens or family_hint.search(name) else ())
        return ("unknown" if negated or not necessary else family), tuple(sorted(set(matched)))
    return "unknown", ()


def _target_family_role(
    condition: str,
    knowledge: KnowledgeSnapshot,
) -> tuple[str | None, tuple[str, ...]]:
    """目标族（riscv）角色预检：命中目标宏词表返回 ("riscv", 知识条目 ID)，否则 (None, ())。

    只消费 architecture_family=="riscv" 的 platformMacros 条目（collected-v1
    目标语料）；词边界正则与源族匹配一致。命中即"已有 RISC-V 分支"，
    不做 required_foreign 复核——目标宏（如 __riscv_xlen）不会被同一条件内
    的其他 token 削弱。
    """
    matched: list[str] = []
    lowered = condition.lower()
    for entry in knowledge.entries:
        if entry.architecture_family.lower() != "riscv":
            continue
        if entry.fact_kind != "platformMacros":
            continue
        token = entry.canonical_value.lower()
        if token and re.search(rf"(?<![A-Za-z0-9_]){re.escape(token)}(?![A-Za-z0-9_])", lowered):
            matched.append(entry.id)
    if not matched:
        return None, ()
    return "riscv", tuple(sorted(set(matched)))


# 源架构族的启发式词元正则：无知识库命中时兜底判定分支属于源架构。
_FAMILY_HINT_PATTERNS = {
    "x86": re.compile(r"(?i)(?<![A-Za-z0-9])(?:x86(?:_64)?|i[3-6]86|sse|avx)(?![A-Za-z0-9])"),
    "arm64": re.compile(r"(?i)(?<![A-Za-z0-9])(?:aarch64|arm64|armv[4-9][a-z]?|__arm(?:__[a-z0-9_]*)?)(?![A-Za-z0-9])"),
}


def _family_hint_pattern(family: str) -> re.Pattern[str]:
    return _FAMILY_HINT_PATTERNS.get(family, _FAMILY_HINT_PATTERNS["x86"])


def _branch_implementation(
    source_file: Entity,
    branch: Entity,
    role: str,
    knowledge_ids: tuple[str, ...],
    architecture_evidence_ids: tuple[str, ...] = (),
) -> Entity:
    return Entity(
        stable_id("graph_entity", 1, "branch_implementation", source_file.id, branch.id),
        "implementation",
        f"{source_file.name}#{branch.name}",
        branch.location,
        {
            "identityVersion": 1,
            "sourceFileId": source_file.id,
            "branchId": branch.id,
            "role": role,
            "knowledgeIds": knowledge_ids,
            "architectureEvidenceIds": architecture_evidence_ids,
        },
    )


def _innermost_branch(evidence: Evidence, branches: tuple[BranchRecord, ...]) -> BranchRecord | None:
    candidates = [item for item in branches if _contains(item.evidence.location, evidence.location)]
    return min(candidates, key=lambda item: _span_size(item.evidence.location), default=None)


def _binding_status(evidence: Evidence) -> str:
    value = evidence.metadata.get("bindingStatus", "unknown")
    return value if value in {"complete", "partial", "ambiguous", "unknown"} else "unknown"


def _family_knowledge_ids(
    evidence: Evidence,
    knowledge: KnowledgeSnapshot,
    patterns: dict | None = None,
    architecture_family: str = "x86",
) -> tuple[str, ...]:
    if not isinstance(evidence.value, str):
        return ()
    kinds = {
        "call_expression": {"intrinsics", "builtins", "cpuPlatformApis"},
        "type_reference": {"vectorTypes"},
        "include": {"headers"},
        "inline_asm": {"assemblyInstructions", "registerNames"},
        "declaration_attribute": {"targetFeatures", "architectureAliases"},
    }.get(evidence.kind, set())
    lowered = evidence.value.casefold()
    family = architecture_family.casefold()
    cache = patterns if patterns is not None else {}
    cache_key = f"{family}:{evidence.kind}"
    if cache_key not in cache:
        cache[cache_key] = tuple(
            (entry.id, entry.canonical_value.casefold(),
             re.compile(rf"(?<![A-Za-z0-9_]){re.escape(entry.canonical_value.casefold())}(?![A-Za-z0-9_])"))
            for entry in knowledge.entries
            if entry.architecture_family.casefold() == family and entry.fact_kind in kinds
        )
    matches = []
    for identifier, token, pattern in cache[cache_key]:
        if lowered == token or pattern.search(lowered):
            matches.append(identifier)
    return tuple(sorted(set(matches)))


def _validate_snapshot(snapshot: NormalizedSnapshot) -> None:
    entity_ids = {item.id for item in snapshot.entities}
    evidence_ids = {item.id for item in snapshot.evidence}
    for evidence in snapshot.evidence:
        if evidence.subject_id not in entity_ids:
            raise ValueError("Evidence references an unknown Entity")
    for fact in snapshot.facts:
        if fact.subject_id not in entity_ids or not set(fact.evidence_ids).issubset(evidence_ids):
            raise ValueError("NormalizedFact references unknown Entity or Evidence")


def _validate_relations(index: GraphIndex, snapshot: NormalizedSnapshot, relations: tuple[Relation, ...]) -> None:
    entity_ids = set(index.entities)
    evidence_ids = {item.id for item in snapshot.evidence}
    for relation in relations:
        if relation.source_id not in entity_ids or relation.target_id not in entity_ids:
            raise ValueError("Relation references an unknown Entity")
        if not set(relation.evidence_ids).issubset(evidence_ids):
            raise ValueError("Relation references unknown Evidence")
