"""按源码范围把既有事实和 proof 关联到函数版本组；不构造运行时选择证明。

两遍算法：
1. 范围关联——对每个声明建立 bodySpan 区间索引，把落在区间内的非声明类事实
   （调用、包含等）作为该组的依赖，并产出 (owner证据, 依赖证据, 配置) 三元组；
2. proof 绑定——复用 base 里已完成的 proof，验证链完整后把选择状态写入组的
   bindings，但 runtimeVersionSelection 恒为 unknown（不宣称运行时行为）。
"""
from bisect import bisect_right
from collections import defaultdict

from .grouping import source


def associate(catalog, snapshot, base, budget):
    """关联事实与 proof 到版本组；返回 (links, issues)。

    links 为 (owner证据ID, 依赖证据ID, 配置) 三元组列表，供物化 function_body_contains；
    issues 为问题码集合。副作用：就地写入 group["dependencies"]、group["bindings"]，
    预算超限时回写组级 reasonCodes 并把过于乐观的状态退回 unknown。
    """
    evidence = {e.id: e for e in snapshot.evidence}
    intervals, attributes = defaultdict(list), defaultdict(list)
    for group in catalog["groups"]:
        # 属性事实ID -> 组 的倒排，供第二遍按 factId 找组。
        for fact_id in group["attributeFactIds"]:
            attributes[fact_id].append(group)
        # 只索引有函数体跨度的声明；(路径, 配置) 为区间键。
        for declaration in group["declarations"]:
            record = declaration["record"]
            span = record["bodySpan"]
            if span is not None:
                intervals[(group["path"], group["configuration"])].append((tuple(span[:2]), tuple(span[2:]), declaration, group))
    starts = {}
    for key, values in intervals.items():
        # 按区间起点排序后抽起点数组，配合 bisect 做前驱查找。
        values.sort(key=lambda value: (value[0], value[1], value[2]["factId"]))
        starts[key] = [v[0] for v in values]
    count, links, issues, bound = 0, [], set(), defaultdict(list)
    # 按 fact ID 排序遍历非声明类事实：声明/语义事实不属于"函数体内依赖"。
    for fact in sorted(snapshot.facts, key=lambda item: item.id):
        if fact.kind in {"function_declaration", "function_semantics"}:
            continue
        # 单一 context 才算有配置，否则标 source-unconfigured。
        configuration = fact.context_ids[0] if len(fact.context_ids) == 1 else "source-unconfigured"
        for eid in fact.evidence_ids:
            item = evidence[eid]
            if item.location is None:
                continue
            start, end = item.location.start, item.location.end
            key = (start.path, configuration)
            # 二分找"最后一个起点 <= 证据起点"的声明区间，候选 owner。
            index = bisect_right(starts.get(key, ()), (start.line, start.column)) - 1
            if index < 0:
                continue
            lower, upper, declaration, group = intervals[key][index]
            record = declaration["record"]
            # 两个排除条件：证据终点越过声明区间（跨界），或落入嵌套子跨度（属于内层函数）。
            if (end.line, end.column) > upper or any(tuple(span[:2]) <= (start.line, start.column) < tuple(span[2:]) for span in record["nestedSpans"]):
                continue
            if count >= budget.associations:
                issues.add("multiversion-association-budget-exceeded")
                break
            count += 1
            dependency = {"factId": fact.id, "evidenceId": eid, "functionFactId": declaration["factId"],
                          "kind": fact.kind, "source": source(item), "status": "unresolved"}
            group["dependencies"].append(dependency)
            bound[fact.id].append(group)
            links.append((declaration["evidenceIds"][0], eid, configuration))
        if issues:
            break
    # Exact fact endpoints only. A selected file is not a selected runtime clone.
    relations = {r.id: r for r in base.relations}
    entities = {e.id for e in base.entities}
    # 第二遍：按 proof 终点的 factId 找到关联组（属性直达 或 范围绑定）。
    for proof in sorted(base.proof_paths, key=lambda item: item.id):
        if not proof.relation_ids:
            continue
        terminal = relations.get(proof.relation_ids[-1])
        if terminal is None:
            continue
        fid = terminal.metadata.get("factId")
        groups = {g["id"]: g for g in (*attributes.get(fid, ()), *bound.get(fid, ()))}
        if not groups:
            continue
        if count + len(proof.relation_ids) > budget.associations:
            issues.add("multiversion-association-budget-exceeded")
            break
        count += len(proof.relation_ids)
        cursor, origins, configurations = proof.source_id, {}, set()
        valid = cursor in entities
        chain = []
        # 逐条校验 proof 链：端点连续、实体存在、证据可溯源，并收集唯一配置。
        for rid in proof.relation_ids:
            relation = relations.get(rid)
            if relation is None or relation.source_id != cursor or relation.target_id not in entities or not relation.evidence_ids:
                valid = False
                break
            chain.append(relation)
            configuration = relation.metadata.get("configuration", "source-unconfigured")
            configurations.add(configuration)
            for eid in relation.evidence_ids:
                item = evidence.get(eid)
                if item is None or item.location is None:
                    valid = False
                else:
                    origins[eid] = source(item)
            cursor = relation.target_id
        if not valid or cursor != proof.target_id or len(configurations) != 1:
            issues.add("multiversion-existing-proof-invalid")
            continue
        configuration = next(iter(configurations))
        complete = proof.status == "complete" and not proof.truncated and all(r.status in {"complete", "selected", "inactive", "not_selected"} for r in chain)
        for group in groups.values():
            # 只绑同一配置的组；scope=source_occurrence，运行时版本选择恒 unknown。
            if configuration != group["configuration"]:
                continue
            group["bindings"].append({"factId": fid, "configuration": configuration,
                "selectionStatus": terminal.status if complete and terminal.status in {"selected", "inactive", "not_selected"} else "unknown",
                "scope": "source_occurrence", "runtimeVersionSelection": "unknown",
                "proofId": proof.id, "relationIds": list(proof.relation_ids), "sources": list(origins.values())})
    if issues:
        # 有问题时回写组级 reasonCodes，并把过于乐观的 defaultPresence 退回 unknown。
        for group in catalog["groups"]:
            group["reasonCodes"] = sorted(set(group["reasonCodes"]) | issues)
            group["defaultGap"] = False
            if group["defaultPresence"] == "absent_in_scope":
                group["defaultPresence"] = "unknown"
    catalog["associationCount"] = count
    return links, issues
