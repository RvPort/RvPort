"""证明路径解析器：在关系图上做有界的 source -> target 可达性搜索。

ProofPathResolver 是 analyzer 阶段证据图分析的收尾一步：对每个 (source, target)
组合调用 GraphStore.search_paths 做带预算的图搜索，把找到的关系链包装成
ProofPath（稳定ID = stable_id("proof_path", ...)）。可选的 plan_queries 钩子
用于先裁剪不相关的 target 组合，required_evidence_ids 用于按需过滤。
"""
from __future__ import annotations

from dataclasses import dataclass

from ..core import ProofPath, Relation, stable_id
from ..graph import GraphStore


@dataclass(frozen=True, slots=True)
class ProofBudgets:
    """证明搜索预算（frozen；三项都必须 >= 1，否则构造抛 ValueError）。

    max_depth 限制路径长度，max_visited 限制单次搜索访问节点数，
    max_paths 限制每次 (source, target) 保留的路径条数。
    """

    max_depth: int = 8
    max_visited: int = 10_000
    max_paths: int = 4

    def __post_init__(self) -> None:
        if min(self.max_depth, self.max_visited, self.max_paths) < 1:
            raise ValueError("proof budgets must be positive")


class ProofPathResolver:
    """在 GraphStore 上解析 ProofPath；只读关系与图，不产生副作用。

    图搜索本体在 GraphStore.search_paths（可能由 NetworkX 实现）；本类负责
    组合枚举、按需过滤与状态聚合（搜索截断/关系缺失/非 complete 均降为 partial）。
    """

    __slots__ = ("_store", "_budgets")

    def __init__(self, store: GraphStore, budgets: ProofBudgets = ProofBudgets()) -> None:
        """store：已装入实体与关系的图存储；budgets：搜索预算。"""
        self._store = store
        self._budgets = budgets

    def resolve(
        self,
        source_ids: tuple[str, ...],
        target_ids: tuple[str, ...],
        relations: tuple[Relation, ...],
        *,
        required_evidence_ids: frozenset[str] | None = None,
    ) -> tuple[ProofPath, ...]:
        """枚举 (source, target) 组合并搜索路径，返回按 ID 排序的 ProofPath 元组。

        参数：source_ids/target_ids 为起终点实体 ID 集合；relations 提供路径中
        关系 ID -> Relation 的映射（用于状态聚合与按需过滤）；
        required_evidence_ids 非空时只保留至少命中一个必需证据的路径
        （空集 => 直接返回空元组，表示没有需求）。同 ID 证明去重（dict 覆盖）。
        """
        if required_evidence_ids == frozenset():
            # 需求为空集：显式表示"没有必需证据"，无需任何搜索。
            return ()
        by_id = {item.id: item for item in relations}
        proofs: dict[str, ProofPath] = {}
        # 可选的存储端查询计划钩子：预先裁剪每个 source 需要尝试的 target 集合。
        planner = getattr(self._store, "plan_queries", None)
        plan = None if planner is None else planner(
            source_ids, target_ids, max_depth=self._budgets.max_depth,
            required_evidence_ids=required_evidence_ids,
        )
        targets = tuple(sorted(set(target_ids)))
        for source_id in sorted(set(source_ids)):
            for target_id in targets if plan is None else plan.get(source_id, ()):
                result = self._store.search_paths(
                    source_id,
                    target_id,
                    max_depth=self._budgets.max_depth,
                    max_visited=self._budgets.max_visited,
                    max_paths=self._budgets.max_paths,
                )
                for relation_ids in result.paths:
                    path_relations = tuple(by_id[item] for item in relation_ids if item in by_id)
                    # 按需过滤：路径必须至少携带一个必需证据，否则丢弃。
                    if required_evidence_ids is not None and not any(
                        evidence_id in required_evidence_ids
                        for item in path_relations for evidence_id in item.evidence_ids
                    ):
                        continue
                    local_reasons = {
                        str(item.metadata.get("reason"))
                        for item in path_relations
                        if item.status != "complete" and item.metadata.get("reason")
                    }
                    incomplete = len(path_relations) != len(relation_ids) or any(
                        item.status != "complete" for item in path_relations
                    )
                    # 状态聚合：搜索截断 或 链不完整/含非 complete 关系 => partial。
                    status = "partial" if result.status != "complete" or incomplete else "complete"
                    reasons = set(filter(None, (result.reason,))) | local_reasons
                    if len(path_relations) != len(relation_ids):
                        reasons.add("missing-relation")
                    reason = ",".join(sorted(reasons)) or None
                    proof_id = stable_id(
                        "proof_path",
                        1,
                        source_id,
                        target_id,
                        relation_ids,
                        status,
                        reason,
                    )
                    proofs[proof_id] = ProofPath(
                        id=proof_id,
                        source_id=source_id,
                        target_id=target_id,
                        relation_ids=relation_ids,
                        status=status,
                        reason=reason,
                        visited=result.visited,
                        truncated=result.truncated,
                    )
        return tuple(sorted(proofs.values(), key=lambda item: item.id))
