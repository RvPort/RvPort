"""用途会话：按配置与源码位置定位候选，缓存作用域索引并限制全扫描工作量。

UsageSession 是一次候选用途分析的工作单元：初始化时把 usage_scope 事实按
(主体, 配置) 建成有序区间表；resolve() 用二分把每个候选证据 occurrence 定位
到内层作用域，再复用 ScopeIndex 与闭包查询。全程共享缓存（索引/记录ID/物化
实体），预算用尽或作用域不完整时结果退为 unknown。
"""
from bisect import bisect_right
from collections import defaultdict
from dataclasses import dataclass

from .closure import QueryBudget, query
from .index import ScopeIndex


@dataclass(frozen=True)
class ScanBudget:
    """整次扫描的硬上限（frozen；非负整数，且不得超硬上限 100000/1000000）。

    records 限制累计入索引的语法记录数，visits 限制累计 BFS 访问量。
    """

    records: int = 100000
    visits: int = 1000000

    def __post_init__(self):
        if any(type(value) is not int or value < 0 for value in vars(self).values()):
            raise ValueError("usage scan budgets must be nonnegative integers")
        if self.records > 100000 or self.visits > 1000000:
            raise ValueError("usage scan budgets exceed hard limits")


class UsageSession:
    """候选用途扫描会话：作用域定位 + 索引缓存 + 双层预算控制。

    不变量：indexes/record_ids/materialized_* 等缓存在会话生命周期内共享，
    同一作用域/同一记录只建一次；visits/record_count 单调递增，预算耗尽后
    相关根的结果只能 unknown。
    """

    def __init__(self, facts, roots, scan_budget=None, query_budget=None):
        """facts：规范化快照；roots：demand.select 的根集合（决定需求键）；
        两个预算缺省用默认值。构建时完成作用域区间表的排序。"""
        self.budget = scan_budget or ScanBudget()
        self.query_budget = query_budget or QueryBudget()
        self.evidence = {item.id: item for item in facts.evidence}
        self.indexes, self.cache = {}, {}
        self.record_ids, self.materialized_entities, self.materialized_relations = {}, {}, {}
        self.materialized_queries = {}
        self.record_count = self.visits = self.cache_hits = 0
        self.scopes, self.starts = defaultdict(list), {}
        demanded = {(root.subject_id, root.configuration) for root in roots}
        # 只为被需求键收集 usage_scope：证据齐全、位置一致、同主体才算有效作用域。
        for fact in sorted(facts.facts, key=lambda item: item.id):
            configuration = fact.metadata.get("compilationContextId", "default")
            key = fact.subject_id, configuration
            if fact.kind != "usage_scope" or key not in demanded:
                continue
            sources = [self.evidence[eid] for eid in fact.evidence_ids if eid in self.evidence]
            if not sources or any(item.location is None for item in sources):
                continue
            span = sources[0].location
            if any(item.location != span or item.subject_id != fact.subject_id for item in sources):
                continue
            start = span.start.line, span.start.column
            end = span.end.line, span.end.column
            self.scopes[key].append((start, end, fact))
        for key, scopes in self.scopes.items():
            # 组内按 (起点, 终点, 事实ID) 排序并抽起点数组，供 bisect 前驱查找。
            scopes.sort(key=lambda value: (value[0], value[1], value[2].id))
            self.starts[key] = [value[0] for value in scopes]

    def resolve(self, root):
        """解析单个根：定位作用域、建索引、跑闭包查询；返回结果字典。

        返回 {status, reasonCodes, bindings, queries}：bindings 是
        (证据ID, 作用域事实ID, 记录键) 三元组；queries 是 (作用域ID, 闭包结果)。
        任何定位失败（证据无位置、不在作用域内、作用域重叠等）都记原因码，
        最终 status 无原因码才为 complete。
        """
        reasons, bindings, grouped = set(), [], defaultdict(list)
        if not root.configuration_complete:
            reasons.add("usage-configuration-unknown")
        lookup = root.subject_id, root.configuration
        for eid in root.matched_evidence_ids:
            # 根证据必须是这三类之一（枚举成员引用/声明、宏展开），否则视为未解析。
            evidence = self.evidence.get(eid)
            if (evidence is None or evidence.location is None or evidence.subject_id != root.subject_id
                    or evidence.kind not in {"enum_member_reference", "enum_member_declaration", "macro_expansion"}):
                reasons.add("usage-root-unresolved")
                continue
            point = evidence.location.start.line, evidence.location.start.column
            scopes = self.scopes.get(lookup, ())
            position = bisect_right(self.starts.get(lookup, ()), point)-1
            if position < 0 or not point < scopes[position][1]:
                reasons.add("usage-open-scope")
                continue
            start, end, fact = scopes[position]
            if (evidence.location.end.line, evidence.location.end.column) > end:
                reasons.add("usage-root-range-unknown")
                continue
            # 同一配置重叠 owner 不能随意选一份 producer 的结论。
            if position > 0 and scopes[position-1][1] > start:
                reasons.add("usage-scope-ambiguous")
                continue
            scope = fact.metadata.get("usageScope")
            if scope is None:
                reasons.add("usage-scope-unavailable")
                continue
            # 采集预算已耗尽时，闭包无法证明全使用穷尽；原始记录留在事实快照，
            # 不再为注定 unknown 的范围构造非执行图或消耗查询预算。
            if scope["status"] != "complete" and any("budget" in reason for reason in scope["reasonCodes"]):
                reasons.update(scope["reasonCodes"])
                reasons.add("usage-scope-incomplete")
                continue
            if fact.id not in self.indexes:
                # 懒建 ScopeIndex：记录数计入会话级 scan 预算，超限则放弃该作用域。
                count = scope["recordCount"]
                if self.record_count + count > self.budget.records:
                    reasons.add("usage-scan-record-budget")
                    continue
                self.record_count += count
                self.indexes[fact.id] = ScopeIndex(scope)
            index = self.indexes[fact.id]
            reasons.update(index.scope["reasonCodes"])
            if index.scope["status"] != "complete":
                reasons.add("usage-scope-incomplete")
            keys = index.roots_at((*point, evidence.location.end.line, evidence.location.end.column))
            # 取名字最后一段（剥掉 :: 限定）后与记录文本精确比对；必须恰好一个命中。
            name = str(evidence.value).rsplit("::", 1)[-1]
            keys = tuple(key for key in keys if index.rows[key]["text"] == name)
            if len(keys) != 1:
                # 0 个或多个同名标识符：绑定不明，不能选其一。
                reasons.add("usage-root-binding-unknown")
                continue
            bindings.append((eid, fact.id, keys[0]))
            grouped[fact.id].extend(keys)
        if not bindings or len(bindings) != len(root.matched_evidence_ids):
            reasons.add("usage-roots-incomplete")
        results = []
        # 同作用域同根集合的查询结果缓存；命中计 cache_hits。
        for scope_id, keys in sorted(grouped.items()):
            cache_key = scope_id, tuple(sorted(set(keys)))
            if cache_key in self.cache:
                result = self.cache[cache_key]
                self.cache_hits += 1
            else:
                # 剩余访问量钳制本次查询的 nodes 上限；截断时补记 scan 预算原因码。
                remaining = max(0, self.budget.visits-self.visits)
                result = query(self.indexes[scope_id], keys, QueryBudget(
                    self.query_budget.depth, min(remaining, self.query_budget.nodes), self.query_budget.edges))
                self.visits += result["visited"]
                if remaining < self.query_budget.nodes and result["truncated"]:
                    result = {**result, "reasonCodes": tuple(sorted(set(result["reasonCodes"]) | {"usage-scan-visit-budget"}))}
                self.cache[cache_key] = result
            reasons.update(result["reasonCodes"])
            results.append((scope_id, result))
        # 各作用域查询的合计规模也要过单查询预算（防止多作用域累计超限）。
        if (sum(result["visited"] for _, result in results) > self.query_budget.nodes
                or sum(result["edgeCount"] for _, result in results) > self.query_budget.edges):
            reasons.add("usage-query-budget")
        return {"status": "unknown" if reasons else "complete", "reasonCodes": tuple(sorted(reasons)),
                "bindings": tuple(sorted(bindings)), "queries": tuple(results)}
