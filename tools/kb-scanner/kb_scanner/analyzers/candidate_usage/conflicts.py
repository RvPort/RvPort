"""按配置和源码位置索引已确认依赖；只补充用途冲突，不生成执行关系。

ExecutionConflictIndex 复用 Detector 的匹配入口，把"已被确认的本地 x86 依赖"
（函数调用/头文件/内联汇编/属性等）按 (主体, 配置, 文件) 建成区间索引；
annotate() 检查用途闭包的记录跨度是否与这些依赖区间重叠——重叠即冲突，
用途结论退为 unknown（存在真实执行依赖，非执行结论不成立）。
"""
from bisect import bisect_left
from collections import defaultdict
from dataclasses import replace

from ...classifiers.risk import DEPENDENCIES
from ...detectors.definitions import DEFAULT_DEFINITIONS
from ...detectors.matchers import KnowledgeIndex, match_fact


class ExecutionConflictIndex:
    """已确认依赖区间索引，用于标注用途冲突。

    不变量：只读 facts/knowledge；locations 按 (主体, 配置, 文件) 分组，组内区间
    已排序去重；cache 缓存 (根, 作用域, 记录集) -> 冲突证据集合，同参复用。
    """

    def __init__(self, facts, knowledge, roots):
        """构建索引。

        roots 是 demand.select 的结果，用于限定 (主体, 配置) 需求集合——只为被
        查询的配置建索引。匹配口径与 Detector 完全一致（match_fact +
        confirmed_local），因此不产生新的执行语义。
        """
        self.locations = defaultdict(list)
        self.cache = {}
        demanded = {(root.subject_id, root.configuration) for root in roots}
        evidence = {item.id: item for item in facts.evidence}
        # 依赖类观察定义：排除 compiler_argument（编译参数不算源内依赖）。
        definitions = defaultdict(list)
        for definition in DEFAULT_DEFINITIONS.values():
            if definition.observation_kind in DEPENDENCIES and definition.input_kind != "compiler_argument":
                definitions[definition.input_kind].append(definition)
        relevant = []
        for fact in facts.facts:
            # 只保留：属于依赖定义的事实 + 落在被需求的 (主体, 配置) + context 一致。
            configuration = fact.metadata.get("compilationContextId") or fact.metadata.get("configuration", "default")
            if (fact.kind not in definitions or (fact.subject_id, configuration) not in demanded
                    or fact.context_ids and fact.context_ids != (configuration,)):
                continue
            relevant.append((fact, configuration))
        kinds = {kind for fact, _ in relevant for definition in definitions[fact.kind]
                 for kind in definition.knowledge_kinds}
        # 知识索引裁剪到相关 fact_kind，减小匹配范围；无相关事实则不建索引。
        index = KnowledgeIndex(replace(knowledge, entries=tuple(entry for entry in knowledge.entries
            if entry.fact_kind in kinds))) if relevant else None
        for fact, configuration in relevant:
            # 只有 confirmed_local 的事实才算已确认依赖；candidate 状态不算。
            if not any((match := match_fact(definition, fact, index)) is not None
                       and match.status == "confirmed_local" for definition in definitions.get(fact.kind, ())):
                continue
            for identifier in fact.evidence_ids:
                # 证据必须在同一主体上且带列号，否则无法做精确区间比较。
                item = evidence.get(identifier)
                if item is None or item.location is None or item.subject_id != fact.subject_id:
                    continue
                span = item.location
                if span.start.column is None or span.end.column is None:
                    continue
                key = fact.subject_id, configuration, span.start.path
                self.locations[key].append(((span.start.line, span.start.column),
                    (span.end.line, span.end.column), identifier))
        self.locations = {key: tuple(sorted(set(values))) for key, values in self.locations.items()}
        # 每组抽起点数组，供 annotate 内 bisect 前驱查找。
        self.starts = {key: tuple(value[0] for value in values) for key, values in self.locations.items()}

    def annotate(self, root, resolution, session, scope_facts):
        """给解析结果标注冲突；有冲突时状态退为 unknown。

        参数 resolution 是 session.resolve 的返回字典；返回值是其扩展副本，
        追加 conflictEvidenceIds 并视情况加入 usage-known-execution-conflict 原因码。
        冲突证据集合按 (根, 作用域, 记录集) 缓存，同一闭包不重算。
        """
        conflicts = set()
        for scope_id, query in resolution["queries"]:
            cache_key = root.subject_id, root.configuration, scope_id, query["recordKeys"]
            if cache_key not in self.cache:
                source = session.evidence[scope_facts[scope_id].evidence_ids[0]]
                key = root.subject_id, root.configuration, source.location.start.path
                values, starts = self.locations.get(key, ()), self.starts.get(key, ())
                found = set()
                for record_key in query["recordKeys"]:
                    # 二分查找与闭包记录跨度重叠的依赖区间：区间起点 <= 记录起点，
                    # 且区间终点 <= 记录终点（完全包含于记录跨度内才算命中）。
                    span = session.indexes[scope_id].rows[record_key]["span"]
                    start, end = span[:2], span[2:]
                    first, last = bisect_left(starts, start), bisect_left(starts, end)
                    found.update(identifier for _, finish, identifier in values[first:last] if finish <= end)
                self.cache[cache_key] = tuple(sorted(found))
            conflicts.update(self.cache[cache_key])
        reasons = set(resolution["reasonCodes"])
        if conflicts:
            reasons.add("usage-known-execution-conflict")
        return {**resolution, "status": "unknown" if conflicts else resolution["status"],
            "reasonCodes": tuple(sorted(reasons)), "conflictEvidenceIds": tuple(sorted(conflicts))}
