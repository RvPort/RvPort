"""把已完成的局部闭包物化为非执行用途证明；未知闭包只保留审计摘要。

materialize() 把 session.resolve 的结果转成实体/关系：usage_scope_coverage
（一次闭包覆盖范围）+ usage_record（每条语法记录）+ usage_scope_member /
usage_dependency 关系。只有整条链没有任何原因码时才额外产出 semantic_role
实体、has_semantic_role 关系与 ProofPath（非执行用途证明）；任何未知都只留
审计摘要（status=unknown），绝不输出半成品证明。
"""
from ...core import Entity, Relation, ProofPath, SourceLocation, SourceSpan, stable_id
from ...core.metadata import freeze_shared_metadata


NAMESPACE = "org.rvport.kb-scanner.candidate-usage"
NON_EXECUTING = "non_executing_usage"


def materialize(root, resolution, session, scope_facts):
    """物化一个根的用途结果；返回 (实体, 关系, 证明, 审计摘要) 四元组。

    副作用：通过 session 的共享缓存（record_ids/materialized_entities/
    materialized_relations/materialized_queries）跨根复用同一份实体与关系，
    相同闭包不重复物化。摘要经 freeze_shared_metadata 冻结共享。
    """
    entities, relations, proofs = {}, {}, []
    record_ids, scope_ids, supporting, use_ids, source_ids = set(), [], set(), set(), set(root.matched_evidence_ids)
    reasons = set(resolution["reasonCodes"])
    conflict_ids = set(resolution.get("conflictEvidenceIds", ()))
    source_ids.update(conflict_ids)
    if conflict_ids:
        reasons.add("usage-known-execution-conflict")

    def record_id(scope_id, key):
        # 记录稳定ID = stable_id("usage_record", 1, 作用域, 配置, 记录键)；会话内缓存。
        identity = scope_id, root.configuration, key
        if identity not in session.record_ids:
            session.record_ids[identity] = stable_id("usage_record", 1, *identity)
        return session.record_ids[identity]

    root_bindings = tuple({"evidenceId": eid, "scopeId": sid, "recordId": record_id(sid, key)}
                          for eid, sid, key in resolution["bindings"])
    for scope_id, result in resolution["queries"]:
        fact = scope_facts[scope_id]
        source_ids.update(fact.evidence_ids)
        # 查询级缓存键：作用域+配置+记录/边/使用集合+状态+截断标志，命中则整体复用。
        cache_key = (scope_id, root.configuration, result["recordKeys"], result["links"],
                     result["useKeys"], result["status"], result["truncated"])
        if cache_key in session.materialized_queries:
            cached = session.materialized_queries[cache_key]
            entities.update(cached[0])
            relations.update(cached[1])
            scope_ids.append(cached[2])
            record_ids.update(cached[3])
            supporting.update(cached[1])
            use_ids.update(cached[4])
            reasons.update(cached[5])
            continue
        local_entities, local_relations, local_reasons = {}, {}, set()
        index = session.indexes[scope_id]
        source = session.evidence[fact.evidence_ids[0]]
        path = source.location.start.path
        keys = set(result["recordKeys"])
        ids = tuple(sorted(record_id(scope_id, key) for key in keys))
        # 按实际声明收集全部使用；不能只信 query 的 complete 字符串。
        declarations = {index.rows[key].get("binding", {}).get("declaration") for key in keys}
        expected = {key for declaration in declarations for key in index.uses.get(declaration, ())}
        if not expected.issubset(keys):
            # 闭包漏掉了某个声明的使用：不完整，记码。
            local_reasons.add("usage-uses-not-closed")
        expected_ids = tuple(sorted(record_id(scope_id, key) for key in expected))
        coverage_id = stable_id("usage_scope_coverage", 1, scope_id, root.configuration, ids)
        scope_ids.append(coverage_id)
        # 覆盖实体：一次闭包的范围 + 期望使用数（供审计对照实际解析数）。
        if coverage_id not in session.materialized_entities:
            session.materialized_entities[coverage_id] = Entity(coverage_id, "usage_scope_coverage", "局部使用范围",
                location=source.location, properties={"configuration": root.configuration,
                    "scopeFactId": scope_id, "sourceEvidenceIds": fact.evidence_ids,
                    "status": index.scope["status"], "recordIds": ids,
                    "expectedUseRecordIds": expected_ids, "expectedUseCount": len(expected_ids)})
        local_entities[coverage_id] = session.materialized_entities[coverage_id]
        for key in keys:
            # 每条记录一个 usage_record 实体 + 一条 member 关系（非执行语义角色）。
            row = index.rows[key]
            rid = record_id(scope_id, key)
            if rid not in session.materialized_entities:
                a, b, c, d = row["span"]
                location = SourceSpan(SourceLocation(path, a, b), SourceLocation(path, c, d))
                session.materialized_entities[rid] = Entity(rid, "usage_record", row["kind"], location=location,
                    properties={"scopeFactId": scope_id, "configuration": root.configuration,
                        "sourceEvidenceIds": fact.evidence_ids, "recordKey": key,
                        "bindingStatus": row.get("binding", {}).get("status", "not_applicable")})
            local_entities[rid] = session.materialized_entities[rid]
            identity = "usage_scope_member", coverage_id, rid
            if identity not in session.materialized_relations:
                membership = stable_id(*identity)
                session.materialized_relations[identity] = Relation(membership, "usage_scope_member", coverage_id, rid,
                    fact.evidence_ids, "high", "complete", {"semanticRole": NON_EXECUTING,
                        "configuration": root.configuration})
            member = session.materialized_relations[identity]
            membership = member.id
            local_relations[membership] = member
        for left, right in result["links"]:
            # 闭包外的链接端点：图不完整，记 usage-links-not-closed 后跳过该边。
            if left not in keys or right not in keys:
                local_reasons.add("usage-links-not-closed")
                continue
            lid, rid = record_id(scope_id, left), record_id(scope_id, right)
            identity = "usage_dependency", 1, lid, rid
            if identity not in session.materialized_relations:
                edge_id = stable_id(*identity)
                session.materialized_relations[identity] = Relation(edge_id, "usage_dependency", lid, rid,
                    fact.evidence_ids, "high", "complete", {"semanticRole": NON_EXECUTING,
                        "configuration": root.configuration})
            edge = session.materialized_relations[identity]
            edge_id = edge.id
            local_relations[edge_id] = edge
        record_ids.update(ids)
        local_uses = tuple(record_id(scope_id, key) for key in result["useKeys"])
        use_ids.update(local_uses)
        if result["status"] != "complete" or result["truncated"]:
            local_reasons.add("usage-query-incomplete")
        entities.update(local_entities)
        relations.update(local_relations)
        supporting.update(local_relations)
        reasons.update(local_reasons)
        session.materialized_queries[cache_key] = (local_entities, local_relations, coverage_id,
                                                   ids, local_uses, frozenset(local_reasons))
    if (not root_bindings or len(root_bindings) != len(root.matched_evidence_ids)
            or not record_ids or not use_ids or not root.configuration_complete):
        # 根绑定不齐 / 无记录 / 无使用 / 配置未确认：证明不完整。
        reasons.add("usage-proof-incomplete")
    if any(binding["recordId"] not in record_ids for binding in root_bindings):
        # 根指向的记录不在本次闭包内：悬挂绑定。
        reasons.add("usage-root-record-missing")
    if len(record_ids) > session.query_budget.nodes or len(supporting) > session.query_budget.edges:
        # 物化规模本身也要过预算复核。
        reasons.add("usage-proof-budget")
        # 审计摘要：期望/解析/冲突/逃逸计数 + 预算 + 截断标志 + 原因码。
    summary = {"version": 1, "factId": root.fact_id, "subjectId": root.subject_id,
        "configuration": root.configuration, "matchedEvidenceIds": root.matched_evidence_ids,
        "rootBindings": root_bindings, "scopeCoverageIds": tuple(sorted(scope_ids)),
        "recordIds": tuple(sorted(record_ids)), "supportingRelationIds": tuple(sorted(supporting)),
        "supportingEvidenceIds": tuple(sorted(source_ids)), "useRecordIds": tuple(sorted(use_ids)),
        "expectedUseCount": sum(entities[sid].properties["expectedUseCount"] for sid in scope_ids),
        "resolvedUseCount": len(use_ids), "unresolvedCount": len(reasons), "conflictCount": len(conflict_ids),
        "escapeCount": sum("escape" in reason or reason == "usage-external-write" for reason in reasons),
        "budget": {"maxDepth": session.query_budget.depth, "maxNodes": session.query_budget.nodes,
            "maxRelations": session.query_budget.edges, "scanMaxRecords": session.budget.records,
            "scanMaxVisits": session.budget.visits,
            "visited": sum(result["visited"] for _, result in resolution["queries"]),
            "supportRelations": len(supporting),
            "maxDepthSeen": max((result["maxDepth"] for _, result in resolution["queries"]), default=0)},
        "truncated": any("budget" in reason for reason in reasons) or any(result["truncated"] for _, result in resolution["queries"]),
        "reasonCodes": tuple(sorted(reasons)), "status": "unknown" if reasons else "complete"}
    # role 边和审计目录引用同一份已校验摘要，不重复深复制大闭包 ID 集合。
    summary = freeze_shared_metadata(summary)
    if not reasons:
        # 零原因码才发证明：semantic_role 实体 + has_semantic_role 关系 + ProofPath
        # （reason="closed-local-non-execution-usage"，len(record_ids) 为证据规模）。
        role_id = stable_id("usage_role", 1, root.fact_id, root.configuration, root.matched_evidence_ids)
        entities[role_id] = Entity(role_id, "semantic_role", "non_execution_data")
        role_relation_id = stable_id("usage_role_relation", 1, root.subject_id, role_id)
        relations[role_relation_id] = Relation(role_relation_id, "has_semantic_role", root.subject_id, role_id,
            tuple(sorted(source_ids)), "high", "complete", {"semanticRole": NON_EXECUTING,
                "factId": root.fact_id, "configuration": root.configuration,
                "scope": "occurrence", "scopeComplete": True, "usageProof": summary})
        proofs.append(ProofPath(stable_id("usage_role_proof", 1, role_relation_id), root.subject_id, role_id,
            (role_relation_id,), "complete", "closed-local-non-execution-usage", len(record_ids)))
    return (tuple(sorted(entities.values(), key=lambda value: value.id)),
            tuple(sorted(relations.values(), key=lambda value: value.id)), tuple(proofs), summary)
