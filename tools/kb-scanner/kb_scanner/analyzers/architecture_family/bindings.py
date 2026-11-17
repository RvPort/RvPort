"""把既有 proof 的终点绑定到家族成员；绝不搜索或合成新证明。

绑定算法：遍历 base.proof_paths，找到终点关系（selects/excludes/alternative_to），
验证 proof 链的连通性与证据位置，然后生成带溯源（sources=起止行列）的绑定记录，
写入成员的 bindings 列表。预算耗尽或链非法时记问题码并停止，不伪造绑定。
"""
from collections import defaultdict


def bind(catalog, facts, base, budget):
    """把 proof 终点绑定到家族成员，返回排序后的问题码元组。

    参数：catalog 为 discover() 产出的目录（会就地写入 member["bindings"]）；
    facts 为规范化快照（提供事实与证据索引）；base 为上一阶段的 AnalysisSnapshot
    （提供 proof_paths 与关系索引）；budget 为 FamilyBudget。
    副作用：成员的 bindings 列表被追加记录；不修改 facts/base 本身。
    """
    # 先建 文件ID -> 成员列表 的倒排，便于按 proof 终点快速定位成员。
    members = defaultdict(list)
    for family in catalog["families"]:
        for member in family["members"]:
            members[member["fileId"]].append(member)
    if not members:
        return ()
    entities = {e.id: e for e in base.entities}
    files = {e.name: e.id for e in base.entities if e.kind == "source_file"}
    fact_index = {f.id: f for f in facts.facts}
    evidence = {e.id: e for e in facts.evidence}
    relations = {r.id: r for r in base.relations}
    issues, count, steps = set(), 0, 0
    # 按 proof ID 排序遍历，保证输出确定性。
    for proof in sorted(base.proof_paths, key=lambda p: p.id):
        if not proof.relation_ids:
            continue
        # 终点关系 = proof 链的最后一条；缺失即链不完整。
        terminal = relations.get(proof.relation_ids[-1])
        if terminal is None:
            issues.add("family-invalid-proof")
            continue
        fact = fact_index.get(terminal.metadata.get("factId"))
        file_id = terminal.target_id if terminal.target_id in members else None
        if fact is not None and fact.kind == "build_source":
            # build_source 事实：从 cmake.source 或 canonical_value 还原文件路径再查。
            path = fact.metadata.get("cmake", {}).get("source", fact.canonical_value)
            file_id = files.get(str(path).removeprefix("./"))
        if file_id not in members or terminal.kind not in {"selects", "excludes", "alternative_to"}:
            # 终点不是可绑定的选择/排除/替代关系：跳过，不算错误。
            continue
        if count >= budget.bindings or steps + len(proof.relation_ids) > budget.proof_steps:
            # 绑定数或 proof 步数预算耗尽：记码后整体停止（break，不再尝试后续 proof）。
            issues.add("family-binding-budget")
            break
        steps += len(proof.relation_ids)
        chain = [relations.get(rid) for rid in proof.relation_ids]
        # 一条 proof 链内只允许出现至多一个 configuration；跨配置不算同一绑定。
        configs = {str(r.metadata["configuration"]) for r in chain if r is not None and "configuration" in r.metadata}
        valid = proof.source_id in entities and proof.target_id in entities and len(configs) <= 1
        cursor = proof.source_id
        origins = {}
        # 逐条校验链：源→目标必须首尾相接、端点实体存在、每条关系都有证据。
        for relation in chain:
            if relation is None or relation.source_id != cursor or relation.target_id not in entities or not relation.evidence_ids:
                valid = False
                break
            for eid in relation.evidence_ids:
                item = evidence.get(eid)
                if item is None or item.location is None:
                    # 证据缺失或无位置：无法溯源，链判非法。
                    valid = False
                    continue
                # 记录证据的文件与起止行号，作为绑定的溯源来源。
                start, end = item.location.start, item.location.end
                origins[eid] = {"path": start.path, "startLine": start.line, "endLine": end.line}
            cursor = relation.target_id
        if not valid or cursor != proof.target_id:
            # 链断开或终点不匹配：该 proof 不可用，记码后继续下一个。
            issues.add("family-invalid-proof")
            continue
        # 选择状态只能来自可靠的 proof：proof 完整且链上关系均为可接受状态才保留 terminal.status，
        # 否则一律降级为 unknown（五态语义）。
        state = terminal.status if proof.status == "complete" and not proof.truncated and all(r.status in {"complete", "selected", "inactive", "not_selected"} for r in chain) else "unknown"
        state = state if state in {"selected", "inactive", "not_selected"} else "unknown"
        # 一条绑定记录：配置、选择状态、proof 溯源（ID+关系链）与证据源位置。
        record = {"configuration": next(iter(configs), "default"), "selectionStatus": state,
                  "proofStatus": proof.status, "proofId": proof.id, "relationIds": list(proof.relation_ids),
                  "sources": [origins[k] for k in sorted(origins)], "kind": terminal.kind}
        for member in members[file_id]:
            member["bindings"].append(record)
        count += 1
    return tuple(sorted(issues))
