"""构建选择分析（analyzer 阶段）：把 build_* 事实转成"架构选择器→构建项"的证据图。

支持 Make/CMake/Autoconf/RPM 等构建方言，按方言分流到对应的 selection 模块；
通用路径负责建实体/关系/证明路径、推导 RISC-V 选择缺口（selection_gap），
并通过 Make 模式匹配把源码成员可达性解析为 x86 知识依赖。
所有 ID 由 stable_id（SHA-256）派生；预算耗尽时降级为 PARTIAL 而非失败。
"""
from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, replace
from pathlib import PurePosixPath
import re

from ..core import (
    AnalysisSnapshot,
    CompletionStatus,
    Diagnostic,
    DiagnosticSeverity,
    Entity,
    KnowledgeSnapshot,
    NormalizedFact,
    NormalizedSnapshot,
    ProofPath,
    Relation,
    ScanContext,
    StageResult,
    stable_id,
)


@dataclass(frozen=True, slots=True)
class BuildProofBudgets:
    """构建选择分析的证明预算（frozen 不可变）。

    max_relations: 本阶段允许新增的关系数上限。
    max_paths: 本阶段允许新增的证明路径数上限。
    预算是"本阶段新增量"而非全图总量；不足 1 视为配置错误。
    """
    max_relations: int = 10000
    max_paths: int = 4096

    def __post_init__(self) -> None:
        if min(self.max_relations, self.max_paths) < 1:
            raise ValueError("build proof budgets must be positive")


_ITEM_KIND = {
    # build_* 事实种类到实体 kind 的映射（build_branch → build_branch 等）。
    "build_assignment": "build_variable",
    "build_condition": "build_condition",
    "build_branch": "build_branch",
    "build_include": "build_file_reference",
    "build_target": "build_target",
    "build_source": "source_file",
    "build_directory": "build_directory",
    "build_argument": "build_argument",
    "build_dependency": "build_dependency",
    "build_artifact": "build_artifact",
    "build_arch_probe": "architecture_probe",
    "build_configuration": "build_configuration",
    "build_package_section": "package_section",
    "build_arch_constraint": "architecture_constraint",
    "build_unsupported": "unsupported_build_construct",
}


_RELATION_KIND = {
    # build_* 事实种类到关系 kind 的映射（定义/依赖/选择/包含等语义）。
    "build_assignment": "defines",
    "build_condition": "depends_on",
    "build_branch": "selects",
    "build_include": "includes",
    "build_target": "selects",
    "build_source": "selects",
    "build_directory": "selects",
    "build_argument": "adds_argument",
    "build_dependency": "requires",
    "build_artifact": "produces",
    "build_arch_probe": "depends_on",
    "build_configuration": "selects",
    "build_package_section": "selects",
    "build_arch_constraint": "excludes",
    "build_unsupported": "depends_on",
}


class BuildSelectionAnalyzer:
    """构建选择分析器：analyzer 阶段的构建系统入口。

    职责：校验事实引用完整性；按方言（cmake/autoconf/rpm）分流处理；
    通用路径生成 architecture_selector → build_branch → 构建项的选择链；
    最后统一调用 bind_build_macros 绑定宏效果。
    不变式：输出快照中的实体/关系按稳定次序排序；预算只限制本阶段新增。
    """
    __slots__ = ("_budgets",)

    def __init__(self, budgets: BuildProofBudgets = BuildProofBudgets()) -> None:
        self._budgets = budgets

    def analyze(
        self, context, facts, knowledge, base=AnalysisSnapshot(),
    ):
        """分析入口：先跑通用/方言语义，再统一绑定宏作用。

        返回 StageResult；宏绑定达到预算时追加 WARNING 并降级为 PARTIAL。
        """
        result = self._analyze(context, facts, knowledge, base)
        if result.value is None:
            return result
        from .build_macro_binding import bind_build_macros
        entities = {item.id: item for item in result.value.entities}
        relations = {item.id: item for item in result.value.relations}
        limited = bind_build_macros(facts, entities, relations, max_relations=100000,
                                    demand_symbols={entry.canonical_value for entry in knowledge.entries})
        diagnostics = result.diagnostics
        if limited:
            diagnostics += (_warning('build-selection.macro-budget',
                '宏作用证明达到预算，保留未知 / Macro effect proof budget exceeded', 'max-relations'),)
        snapshot = replace(result.value, entities=tuple(sorted(entities.values(), key=lambda item: (item.kind, item.name, item.id))),
                           relations=tuple(sorted(relations.values(), key=lambda item: (item.kind, item.source_id, item.target_id, item.id))))
        return StageResult(snapshot, CompletionStatus.PARTIAL if limited else result.status, diagnostics)

    def _analyze(
        self,
        context: ScanContext,
        facts: NormalizedSnapshot,
        knowledge: KnowledgeSnapshot,
        base: AnalysisSnapshot = AnalysisSnapshot(),
    ) -> StageResult[AnalysisSnapshot]:
        """核心分析逻辑（analyze 的内部实现）。

        流程：1) 引用完整性校验，失败则整阶段 FAILED；
        2) 检测事实 metadata 是否携带 cmake/autoconf/rpm 方言标记——
           有则先递归处理"非该方言"的其余事实得到 base，再交给对应
           方言分析器（共享剩余预算）；
        3) 无 build 事实时直接透传 base；
        4) 通用路径：建 architecture_selector 实体，逐个 build 事实建
           实体+关系+证明路径（conditionGroup 相同的事实共享 build_branch
           中间节点）；随后做 Make 源码可达性解析；最后扫描 x86 分支
           无 RISC-V/通用路径的 subject，生成 selection_gap 实体与证据。
        """
        evidence_ids = {item.id for item in facts.evidence}
        entity_by_id = {item.id: item for item in (*facts.entities, *base.entities)}
        for fact in facts.facts:
            if fact.subject_id not in entity_by_id or not set(fact.evidence_ids).issubset(evidence_ids):
                return _failed("build-selection.invalid-fact-reference", "Build fact references unknown Entity or Evidence")
        build_facts = tuple(item for item in facts.facts if item.kind.startswith("build_"))
        if any("cmake" in fact.metadata for fact in build_facts):
            from .cmake_selection import analyze_cmake
            other = self._analyze(context, replace(facts, facts=tuple(f for f in facts.facts if "cmake" not in f.metadata)), knowledge, base)
            if other.value is None:
                return other
            result = analyze_cmake(context, facts, knowledge, other.value,
                max(0, self._budgets.max_relations - len(other.value.relations) + len(base.relations)),
                max(0, self._budgets.max_paths - len(other.value.proof_paths) + len(base.proof_paths)))
            return StageResult(result.value,
                CompletionStatus.PARTIAL if other.diagnostics or result.diagnostics else CompletionStatus.COMPLETE,
                (*other.diagnostics, *result.diagnostics))
        if any("autoconf" in fact.metadata for fact in build_facts):
            from .autoconf_selection import analyze_autoconf
            other = self._analyze(context, replace(facts, facts=tuple(f for f in facts.facts if "autoconf" not in f.metadata)), knowledge, base)
            if other.value is None:
                return other
            result = analyze_autoconf(context, facts, other.value,
                max(0, self._budgets.max_relations - len(other.value.relations) + len(base.relations)),
                max(0, self._budgets.max_paths - len(other.value.proof_paths) + len(base.proof_paths)))
            return StageResult(result.value,
                CompletionStatus.PARTIAL if other.diagnostics or result.diagnostics else CompletionStatus.COMPLETE,
                (*other.diagnostics, *result.diagnostics))
        if any("rpm" in fact.metadata for fact in build_facts):
            from .rpm_selection import analyze_rpm
            other = self._analyze(context, replace(facts, facts=tuple(f for f in facts.facts if "rpm" not in f.metadata)), knowledge, base)
            if other.value is None:
                return other
            rpm_result = analyze_rpm(context, facts, other.value,
                max(0,self._budgets.max_relations - len(other.value.relations) + len(base.relations)),
                max(0,self._budgets.max_paths - len(other.value.proof_paths) + len(base.proof_paths)))
            return StageResult(rpm_result.value,
                CompletionStatus.PARTIAL if other.diagnostics or rpm_result.diagnostics else CompletionStatus.COMPLETE,
                (*other.diagnostics,*rpm_result.diagnostics))
        if not build_facts:
            return StageResult(base)
        selector = Entity(
            stable_id("build_entity", 1, "architecture_selector", context.target_arch),
            "architecture_selector",
            context.target_arch,
            properties={"identityVersion": 1, "targetArchitecture": context.target_arch},
        )
        entity_by_id[selector.id] = selector
        relations = {item.id: item for item in base.relations}
        base_relation_ids = frozenset(relations)
        proofs = {item.id: item for item in base.proof_paths}
        # Limits count this stage's additions, not inherited graph occupancy.
        budgets = replace(self._budgets, max_relations=len(relations) + self._budgets.max_relations,
                          max_paths=len(proofs) + self._budgets.max_paths)
        diagnostics: list[Diagnostic] = []
        branch_by_key: dict[tuple[str, str, str], Entity] = {}
        relevant_by_subject: dict[str, list[NormalizedFact]] = defaultdict(list)
        item_by_fact: dict[str, Entity] = {}

        for fact in build_facts:
            relevant_by_subject[fact.subject_id].append(fact)
            if len(relations) >= budgets.max_relations:
                diagnostics.append(_warning("build-selection.max-relations", "Build relation budget exceeded", "max-relations"))
                break
            status = _metadata_text(fact, "selectionStatus", "unknown")
            reason = _metadata_text(fact, "reason", "")
            role = _metadata_text(fact, "role", "unknown")
            condition_group = _metadata_text(fact, "conditionGroup", "")
            source_entity = selector
            path_relations: list[str] = []
            if condition_group:
                # 同一 (subject, conditionGroup, role) 的事实共享一个 build_branch
                # 中间节点，避免选择器到分支的关系重复；后续项都从分支出发。
                key = (fact.subject_id, condition_group, role)
                branch = branch_by_key.get(key)
                if branch is None:
                    branch = Entity(
                        stable_id("build_entity", 1, "build_branch", *key),
                        "build_branch",
                        f"{condition_group}:{role}",
                        properties={"identityVersion": 1, "conditionGroup": condition_group, "role": role},
                    )
                    branch_by_key[key] = branch
                    entity_by_id[branch.id] = branch
                    relation = _relation(
                        "selects", selector.id, branch.id, fact.evidence_ids, status,
                        {"targetArchitecture": context.target_arch, "role": role, "conditionGroup": condition_group, "reason": reason or "condition-evaluation"},
                    )
                    relations[relation.id] = relation
                else:
                    relation = next(item for item in relations.values() if item.source_id == selector.id and item.target_id == branch.id)
                source_entity = branch
                path_relations.append(relation.id)
            item = _item_entity(fact, entity_by_id)
            entity_by_id[item.id] = item
            item_by_fact[fact.id] = item
            relation = _relation(
                _RELATION_KIND[fact.kind],
                source_entity.id,
                item.id,
                fact.evidence_ids,
                status,
                {
                    "targetArchitecture": context.target_arch,
                    "itemKind": fact.kind,
                    "value": fact.canonical_value,
                    "role": role,
                    "reason": reason or ("statically-selected" if status != "unknown" else "selection-evidence-incomplete"),
                    "factId": fact.id,
                    "configuration": _metadata_text(fact, "configuration", "default"),
                },
            )
            relations[relation.id] = relation
            path_relations.append(relation.id)
            if len(proofs) < budgets.max_paths:
                proof_status = "complete" if status in {"selected", "inactive", "not_selected"} else "unknown"
                proof_reason = None if proof_status == "complete" else reason or "selection-evidence-incomplete"
                proof = ProofPath(
                    stable_id("proof_path", 1, selector.id, item.id, tuple(path_relations), proof_status, proof_reason),
                    selector.id,
                    item.id,
                    tuple(path_relations),
                    proof_status,
                    proof_reason,
                    len(path_relations) + 1,
                    False,
                )
                proofs[proof.id] = proof

        BuildSourceReachabilityResolver(budgets).resolve(
            context,
            facts,
            knowledge,
            selector,
            entity_by_id,
            relations,
            proofs,
            item_by_fact,
        )

        for subject_id, subject_facts in relevant_by_subject.items():
            # 选择缺口检测：subject 存在 x86 分支但没有任何已选中的
            # riscv/generic/portable/scalar/fallback 分支时，标记为
            # missing_riscv_selection_path（证据取全部分支证据的并集）。
            branch_facts = [item for item in subject_facts if item.kind == "build_branch"]
            has_x86 = any(_metadata_text(item, "role", "") == "x86" for item in branch_facts)
            has_selected_path = any(
                _metadata_text(item, "role", "") in {"riscv", "generic", "portable", "scalar", "fallback"}
                and _metadata_text(item, "selectionStatus", "unknown") == "selected"
                for item in branch_facts
            )
            if has_x86 and not has_selected_path:
                source = entity_by_id[subject_id]
                gap = Entity(
                    stable_id("build_entity", 1, "selection_gap", subject_id, context.target_arch),
                    "selection_gap",
                    f"{source.name}:missing-riscv-selection-path",
                    properties={"identityVersion": 1, "issue": "missing_riscv_selection_path"},
                )
                entity_by_id[gap.id] = gap
                provenance = tuple(sorted({evidence for item in branch_facts for evidence in item.evidence_ids}))
                relation = _relation(
                    "selects", selector.id, gap.id, provenance, "selected",
                    {"targetArchitecture": context.target_arch, "issue": "missing_riscv_selection_path", "reason": "no-riscv-or-generic-path"},
                )
                relations[relation.id] = relation
                proof = ProofPath(
                    stable_id("proof_path", 1, selector.id, gap.id, (relation.id,), "complete", None),
                    selector.id,
                    gap.id,
                    (relation.id,),
                    "complete",
                    visited=2,
                )
                proofs[proof.id] = proof

        if any(item.status == "unknown" for item in relations.values() if item.id not in base_relation_ids):
            diagnostics.append(_warning("build-selection.partial", "Some build selections remain unknown", "selection-evidence-incomplete"))
        snapshot = AnalysisSnapshot(
            relations=tuple(sorted(relations.values(), key=lambda item: (item.kind, item.source_id, item.target_id, item.id))),
            entities=tuple(sorted(entity_by_id.values(), key=lambda item: (item.kind, item.name, item.id))),
            proof_paths=tuple(sorted(proofs.values(), key=lambda item: item.id)),
        )
        return StageResult(snapshot, CompletionStatus.PARTIAL if diagnostics else CompletionStatus.COMPLETE, tuple(diagnostics))


class BuildSourceReachabilityResolver:
    """Make 源码可达性解析器：从构建目标推导源文件成员与 x86 知识依赖。

    思路：对每个 make 目标，若其 subject 存在 "%.c" 等模式规则且能按
    目标名+后缀唯一匹配到一个已注册 source_file 实体，则建立
    target → source 的 uses_source 关系；再沿 includes 关系闭包收集
    可达源文件，把其中命中 x86 知识库（intrinsics/SSE/AVX 等）的事实
    建为 architecture_dependency 实体并挂证明路径。
    事实种类到知识类别的映射由 _KNOWLEDGE_KINDS 固定。
    """
    __slots__ = ("_budgets",)

    _TARGET = re.compile(r"^[A-Za-z0-9_.+-]+$")
    _SOURCE_SUFFIXES = (".c", ".cc", ".cpp", ".cxx", ".s", ".S", ".asm")
    _KNOWLEDGE_KINDS = {
        "include": frozenset({"headers"}),
        "symbol": frozenset({"intrinsics", "builtins", "cpuPlatformApis"}),
        "type": frozenset({"vectorTypes"}),
        "macro": frozenset({"platformMacros"}),
        "macro_replacement": frozenset({"intrinsics", "builtins"}),
        "preprocessor": frozenset({"platformMacros"}),
        "compiler_argument": frozenset({"compilerFlags"}),
        "inline_asm": frozenset({"assemblyInstructions", "registerNames"}),
        "declaration_attribute": frozenset({"targetFeatures", "architectureAliases", "vectorWidthMarkers"}),
    }

    def __init__(self, budgets: BuildProofBudgets) -> None:
        self._budgets = budgets

    def resolve(
        self,
        context: ScanContext,
        facts: NormalizedSnapshot,
        knowledge: KnowledgeSnapshot,
        selector: Entity,
        entities: dict[str, Entity],
        relations: dict[str, Relation],
        proofs: dict[str, ProofPath],
        item_by_fact: dict[str, Entity],
    ) -> None:
        """就地解析：向 entities/relations/proofs 追加可达性结果，无返回值。

        任何一步预算（关系数/路径数）耗尽立即整体返回，保留已产出内容。
        状态合成规则：模式与目标状态均为 selected 才是 selected；目标
        inactive/not_selected 传递为 inactive；其余情况一律 unknown。
        """
        source_by_path = {item.name: item for item in entities.values() if item.kind == "source_file"}
        subject_by_id = {item.id: item for item in facts.entities}
        patterns_by_subject: dict[str, list[NormalizedFact]] = defaultdict(list)
        for fact in facts.facts:
            if fact.kind == "build_source" and "%" in fact.canonical_value:
                patterns_by_subject[fact.subject_id].append(fact)
        facts_by_subject: dict[str, list[NormalizedFact]] = defaultdict(list)
        for fact in facts.facts:
            if fact.kind in self._KNOWLEDGE_KINDS:
                facts_by_subject[fact.subject_id].append(fact)
        entries_by_kind = defaultdict(list)
        for entry in knowledge.entries:
            if entry.architecture_family.lower() == context.source_arch:
                entries_by_kind[entry.fact_kind].append(entry)
        source_facts: dict[str, list[tuple[NormalizedFact, tuple[str, ...]]]] = {}

        for target_fact in sorted((item for item in facts.facts if item.kind == "build_target"), key=lambda item: item.id):
            target_name = target_fact.canonical_value
            if not self._TARGET.fullmatch(target_name) or target_name in {"all", "clean", "run", "runall"}:
                continue
            patterns = tuple(
                item
                for item in patterns_by_subject.get(target_fact.subject_id, ())
                if _pattern_supports_target(item.canonical_value, target_name)
            )
            if not patterns:
                continue
            makefile = subject_by_id.get(target_fact.subject_id)
            if makefile is None:
                continue
            parent = PurePosixPath(makefile.name).parent
            candidates = tuple(
                source_by_path.get((parent / f"{target_name}{suffix}").as_posix())
                for suffix in self._SOURCE_SUFFIXES
            )
            candidates = tuple(item for item in candidates if item is not None)
            if len(candidates) != 1:
                # 模式匹配出的候选源文件必须唯一，否则放弃该目标的可达性推导。
                continue
            target_entity = item_by_fact.get(target_fact.id)
            if target_entity is None:
                continue
            source = candidates[0]
            target_proof = next(
                (
                    item
                    for item in sorted(proofs.values(), key=lambda value: value.id)
                    if item.source_id == selector.id and item.target_id == target_entity.id
                ),
                None,
            )
            if target_proof is None:
                continue
            configuration = _metadata_text(target_fact, "configuration", "default")
            configured_patterns = tuple(
                item
                for item in patterns
                if _metadata_text(item, "configuration", "default") == configuration
            )
            pattern_statuses = {
                _metadata_text(item, "selectionStatus", "unknown") for item in configured_patterns
            }
            target_status = (
                relations[target_proof.relation_ids[-1]].status
                if target_proof.relation_ids and target_proof.relation_ids[-1] in relations
                else "unknown"
            )
            if not configured_patterns or "unknown" in pattern_statuses or target_status == "unknown":
                status = "unknown"
            elif target_status == "selected" and pattern_statuses == {"selected"}:
                status = "selected"
            elif target_status in {"inactive", "not_selected"}:
                status = "inactive"
            else:
                status = "unknown"
            pattern_evidence = tuple(sorted({value for item in patterns for value in item.evidence_ids}))
            source_relation = _relation(
                "uses_source",
                target_entity.id,
                source.id,
                tuple(sorted(set((*target_fact.evidence_ids, *pattern_evidence)))),
                status,
                {
                    "targetArchitecture": context.target_arch,
                    "itemKind": "target_source_membership",
                    "target": target_name,
                    "configuration": configuration,
                    "reason": "make-pattern-source-membership",
                },
            )
            if len(relations) >= self._budgets.max_relations:
                return
            relations[source_relation.id] = source_relation
            reachable_files = self._reachable_source_files(source, entities, relations)
            for reachable, include_path in reachable_files:
                if reachable.id not in source_facts:
                    matches = []
                    for fact in facts_by_subject.get(reachable.id, ()):
                        matched = tuple(sorted(
                            entry.id
                            for kind in self._KNOWLEDGE_KINDS[fact.kind]
                            for entry in entries_by_kind[kind]
                            if _knowledge_matches(fact.canonical_value, entry.canonical_value, fact.kind)
                        ))
                        if matched:
                            matches.append((fact, matched))
                    source_facts[reachable.id] = matches
                for fact, knowledge_ids in source_facts.get(reachable.id, ()):
                    if len(relations) >= self._budgets.max_relations or len(proofs) >= self._budgets.max_paths:
                        return
                    dependency = Entity(
                        stable_id("build_entity", 1, "x86_source_dependency", fact.id, configuration),
                        "architecture_dependency",
                        fact.canonical_value,
                        properties={
                            "identityVersion": 1,
                            "factId": fact.id,
                            "subjectId": fact.subject_id,
                            "knowledgeIds": knowledge_ids,
                            "role": "x86",
                        },
                    )
                    entities[dependency.id] = dependency
                    dependency_relation = _relation(
                        "depends_on",
                        reachable.id,
                        dependency.id,
                        fact.evidence_ids,
                        status,
                        {
                            "targetArchitecture": context.target_arch,
                            "itemKind": "source_x86_dependency",
                            "value": fact.canonical_value,
                            "role": "x86",
                            "reason": "knowledge-backed-x86-source-fact",
                            "factId": fact.id,
                            "knowledgeIds": knowledge_ids,
                            "configuration": configuration,
                        },
                    )
                    relations[dependency_relation.id] = dependency_relation
                    relation_ids = tuple((*target_proof.relation_ids, source_relation.id, *include_path, dependency_relation.id))
                    proof_status = "complete" if status in {"selected", "inactive", "not_selected"} else "unknown"
                    proof = ProofPath(
                        stable_id("proof_path", 1, selector.id, dependency.id, relation_ids, proof_status),
                        selector.id,
                        dependency.id,
                        relation_ids,
                        proof_status,
                        None if proof_status == "complete" else "source-reachability-incomplete",
                        len(relation_ids) + 1,
                        False,
                    )
                    proofs[proof.id] = proof

    @staticmethod
    def _reachable_source_files(
        source: Entity,
        entities: dict[str, Entity],
        relations: dict[str, Relation],
    ) -> tuple[tuple[Entity, tuple[str, ...]], ...]:
        """BFS 计算从 source 出发、沿 status=complete 的 includes 关系可达的
        source_file 集合，返回 (实体, 途经关系 ID 路径) 元组列表（按实体 ID 排序）。
        """
        output: dict[str, tuple[Entity, tuple[str, ...]]] = {source.id: (source, ())}
        queue = [source.id]
        while queue:
            current = queue.pop(0)
            path = output[current][1]
            for relation in sorted(relations.values(), key=lambda item: item.id):
                target = entities.get(relation.target_id)
                if relation.kind != "includes" or relation.source_id != current or relation.status != "complete":
                    continue
                if target is None or target.kind != "source_file" or target.id in output:
                    continue
                output[target.id] = (target, (*path, relation.id))
                queue.append(target.id)
        return tuple(sorted(output.values(), key=lambda item: item[0].id))


def _item_entity(fact: NormalizedFact, entities: dict[str, Entity]) -> Entity:
    """把 build_* 事实映射为图实体；source_file 类复用同名已有实体避免重复。"""
    kind = _ITEM_KIND[fact.kind]
    if kind == "source_file":
        normalized = fact.canonical_value.removeprefix("./")
        for entity in entities.values():
            if entity.kind == "source_file" and entity.name == normalized:
                return entity
    return Entity(
        stable_id("build_entity", 1, kind, fact.subject_id, fact.canonical_value, fact.scope),
        kind,
        fact.canonical_value,
        properties={"identityVersion": 1, "factId": fact.id, "subjectId": fact.subject_id},
    )


def _relation(kind: str, source: str, target: str, evidence_ids: tuple[str, ...], status: str, metadata: dict[str, object]) -> Relation:
    """构造关系：过滤空 metadata 值；status 非 unknown 时置信度为 high。"""
    clean = {key: value for key, value in metadata.items() if value != ""}
    return Relation(
        stable_id("relation", 1, kind, source, target, tuple(sorted(evidence_ids)), status, clean),
        kind,
        source,
        target,
        tuple(sorted(evidence_ids)),
        "high" if status != "unknown" else "medium",
        status,
        clean,
    )


def _metadata_text(fact: NormalizedFact, name: str, default: str) -> str:
    """安全读取事实 metadata 中的字符串字段；非字符串或缺失时返回默认值。"""
    value = fact.metadata.get(name, default)
    return value if isinstance(value, str) else default


def _pattern_supports_target(pattern: str, target: str) -> bool:
    """判断 make 模式规则（如 "%.c"）是否适用于给定目标名。"""
    normalized = pattern.strip().strip("'\"")
    return normalized in {"%.c", "%.cc", "%.cpp", "%.cxx", "%.s", "%.S", "%.asm"} and bool(target)


def _knowledge_matches(value: str, knowledge_value: str, fact_kind: str) -> bool:
    """判断事实值是否命中知识条目：类型/内联汇编/预处理宏类按词边界
    忽略大小写搜索；编译参数支持前缀= 形式；其余要求精确相等。"""
    if fact_kind in {"type", "inline_asm", "declaration_attribute", "preprocessor", "macro"}:
        return re.search(rf"(?<![A-Za-z0-9_]){re.escape(knowledge_value)}(?![A-Za-z0-9_])", value, re.IGNORECASE) is not None
    if fact_kind == "compiler_argument":
        return value == knowledge_value or value.startswith(knowledge_value + "=")
    return value == knowledge_value


def _warning(code: str, message: str, reason: str) -> Diagnostic:
    """构造 build-selection 的 WARNING 诊断。"""
    return Diagnostic(code, "analyzer.build-selection", DiagnosticSeverity.WARNING, message, producer="build-selection-analyzer", details={"reason": reason})


def _failed(code: str, message: str) -> StageResult[AnalysisSnapshot]:
    """构造整阶段 FAILED 的 StageResult（带 ERROR 诊断，影响退出码）。"""
    diagnostic = Diagnostic(code, "analyzer.build-selection", DiagnosticSeverity.ERROR, message, producer="build-selection-analyzer", affects_exit_code=True)
    return StageResult(None, CompletionStatus.FAILED, (diagnostic,))
