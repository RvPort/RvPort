"""候选用途分析器：在既有分析之上追加用途图，不删除事实或替换执行证明。

analyzer 阶段入口。流程：demand.select 选出候选根（与 Detector 同源的匹配入口）->
session.resolve 逐根做有界闭包查询 -> conflicts.annotate 标注与已确认依赖的冲突 ->
proofs.materialize 物化为实体/关系/非执行用途证明与审计摘要，最后汇总成
candidate_usage_catalog 目录实体。用途结果只有 complete/unknown：截断或未知
不构成排除依据。
"""
from dataclasses import replace
from ...core import AnalysisSnapshot, Entity, StageResult, stable_id, normalize_project_path
from ...frontends.cpp.compilation_database import compilation_context_id
from .demand import CandidateUsageDemand
from .session import UsageSession
from .proofs import materialize
from .conflicts import ExecutionConflictIndex


CATALOG_KIND = "candidate_usage_catalog"


class CandidateUsageAnalyzer:
    """候选用途分析器（analyzer 阶段，追加式）。

    不变量：不修改 facts；只在 base 之上新增 usage_* 实体/关系/证明；
    根的配置若不在编译数据库中，configuration_complete 会被置 False，
    最终结果只能 unknown。
    """

    def __init__(self, demand=None, scan_budget=None, query_budget=None):
        """demand：根选择器；scan_budget/query_budget：扫描与查询预算
        （session.ScanBudget / closure.QueryBudget），None 用默认值。"""
        self.demand = demand or CandidateUsageDemand()
        self.scan_budget, self.query_budget = scan_budget, query_budget

    def analyze(self, context, facts, knowledge, base):
        """执行候选用途分析，返回追加后的 StageResult（无根时透传 base）。

        产出：usage_scope_coverage / usage_record 实体、usage_scope_member /
        usage_dependency / has_semantic_role 关系、非执行用途 ProofPath 与
        candidate_usage_catalog 审计目录。
        """
        roots = self.demand.select(facts, knowledge)
        if not roots:
            return StageResult(base)
        configured = {}
        # 编译数据库：源文件 -> 其出现的全部编译上下文 ID 集合。
        subjects = {entity.name: entity.id for entity in facts.entities if entity.kind == "source_file"}
        for unit in context.compile_contexts:
            try:
                path = normalize_project_path(unit.path, context.input_root)
            except ValueError:
                continue
            if path in subjects:
                configured.setdefault(subjects[path], set()).add(compilation_context_id(unit, context.input_root))
        # 默认 Tree-sitter inventory 不证明编译数据库中的任一具体配置。
        roots = tuple(replace(root, configuration_complete=False)
            if root.subject_id in configured and root.configuration not in configured[root.subject_id]
            else root for root in roots)
        session = UsageSession(facts, roots, self.scan_budget, self.query_budget)
        scope_facts = {fact.id: fact for fact in facts.facts if fact.kind == "usage_scope"}
        conflicts = ExecutionConflictIndex(facts, knowledge, roots)
        # 合并事实实体与 base 实体；新增关系/证明只写入 base 集合之上。
        entities = {entity.id: entity for entity in (*facts.entities, *base.entities)}
        relations = {relation.id: relation for relation in base.relations}
        proofs = {proof.id: proof for proof in base.proof_paths}
        entries = []
        # 逐根流水线：解析 -> 冲突标注 -> 物化；根按排序保证处理顺序确定。
        for root in sorted(roots):
            resolution = session.resolve(root)
            resolution = conflicts.annotate(root, resolution, session, scope_facts)
            extra_entities, extra_relations, extra_proofs, summary = materialize(root, resolution, session, scope_facts)
            entities.update((entity.id, entity) for entity in extra_entities)
            relations.update((relation.id, relation) for relation in extra_relations)
            proofs.update((proof.id, proof) for proof in extra_proofs)
            entries.append(summary)
        # 目录实体固定 stable_id(CATALOG_KIND, 1)，properties 含全部根的审计摘要与会话统计。
        catalog = Entity(stable_id(CATALOG_KIND, 1), CATALOG_KIND, "候选用途审计", properties={
            "version": 1, "entries": tuple(entries), "rootCount": len(roots),
            "scopeIndexes": len(session.indexes), "recordCount": session.record_count,
            "visits": session.visits, "cacheHits": session.cache_hits})
        entities[catalog.id] = catalog
        # unknown 是用途结果，不将项目静态解析状态伪装为 failed 或迁移 ready。
        return StageResult(AnalysisSnapshot(tuple(sorted(relations.values(), key=lambda value: value.id)),
            tuple(sorted(entities.values(), key=lambda value: value.id)), tuple(sorted(proofs.values(), key=lambda value: value.id))))
