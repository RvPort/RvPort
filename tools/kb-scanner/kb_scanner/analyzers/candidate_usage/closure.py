"""有界 BFS 闭包查询：全使用、值流与控制后果；截断或未知均不构成排除。

在 ScopeIndex 的无向邻接图上从根标识符出发做广度优先搜索，收集可达记录与
关联边。任何预算触发（深度/节点/边）都记 usage-query-budget 并把整体状态
退为 unknown——即"没有查完就当作不知道"，绝不据此宣称排除。
"""
from collections import deque
from dataclasses import dataclass


@dataclass(frozen=True)
class QueryBudget:
    """单次闭包查询的上限（frozen；非负整数，且不得超硬上限 32/4096/8192）。

    depth 限制 BFS 层级，nodes/edges 限制访问规模。硬上限保证单查询成本可控。
    """

    depth: int = 32
    nodes: int = 4096
    edges: int = 8192

    def __post_init__(self):
        if any(type(value) is not int or value < 0 for value in vars(self).values()):
            raise ValueError("usage query budgets must be nonnegative integers")
        if self.depth > 32 or self.nodes > 4096 or self.edges > 8192:
            raise ValueError("usage query budgets exceed hard limits")


def query(index, roots, budget=None):
    """在作用域索引上从 roots 做 BFS 闭包，返回结果字典。

    算法：队列存 (节点, 深度)；出队时累计节点自身 reasonCodes，再把邻边与邻居
    入队（queued 集合防重复）。任一预算触发即 break 并记 usage-query-budget。
    返回 status（无问题才 complete）、recordKeys/links/useKeys、访问统计与
    truncated 标志。useKeys 是其中 kind=identifier 的记录（即"使用"）。
    """
    budget = budget or QueryBudget()
    reasons = set(index.scope["reasonCodes"])
    # 前置失败也要带原因：作用域不完整或根不在图中，直接标 unknown。
    if index.scope["status"] != "complete":
        reasons.add("usage-scope-incomplete")
    if not roots or any(root not in index.edges for root in roots):
        reasons.add("usage-root-unresolved")
    queue = deque((root, 0) for root in sorted(set(roots)) if root in index.edges)
    queued = {root for root, _ in queue}
    visited, links, depth_seen = set(), set(), 0
    while queue:
        key, depth = queue.popleft()
        # 深度或节点数超预算：停止整个查询（不确定 => unknown）。
        if depth > budget.depth or len(visited) >= budget.nodes:
            reasons.add("usage-query-budget")
            break
        visited.add(key)
        depth_seen = max(depth_seen, depth)
        reasons.update(index.reasons[key])
        for other, link in index.adjacency[key]:
            # 边数超预算：清空队列终止外层循环。
            if link not in links and len(links) >= budget.edges:
                reasons.add("usage-query-budget")
                queue.clear()
                break
            links.add(link)
            if other not in queued:
                queued.add(other)
                queue.append((other, depth+1))
    uses = tuple(sorted(key for key in visited if index.rows[key]["kind"] == "identifier"))
    # 两种"无使用"要区分：没有任何标识符记录 vs 全部只是声明（无实际使用）。
    if not uses:
        reasons.add("usage-no-resolved-uses")
    elif not any(index.rows[key].get("binding", {}).get("role") != "declaration" for key in uses):
        reasons.add("usage-no-observed-use")
    return {"status": "complete" if not reasons else "unknown", "reasonCodes": tuple(sorted(reasons)),
        "recordKeys": tuple(sorted(visited)), "links": tuple(sorted(links)), "useKeys": uses,
        "visited": len(visited), "edgeCount": len(links), "maxDepth": depth_seen,
        "truncated": "usage-query-budget" in reasons}
