"""按文件、作用域、签名和配置分桶发现函数版本组；缺失证据不等于缺少实现。

发现流程：收集 function_declaration 事实 -> 把 declaration_attribute 事实按
functionOwnerEvidenceIds 归到唯一属主（否则作为孤儿组）-> 按 (路径, 签名, 链接性,
内部性, 配置) 分桶 -> 只有带属性的桶才建组 -> 补语义提示（function_semantics）。
任何证据缺失只影响状态（退为 unknown），不推断"无实现"。
"""
from collections import defaultdict
from dataclasses import dataclass

from ...core import stable_id
from .attributes import decode, semantic_rule


@dataclass(frozen=True)
class Budget:
    """多版本分析的各项工作量上限（frozen；非负整数，否则构造抛 ValueError）。

    declarations/groups 限制发现规模，versions 限制单组版本数，
    attribute_chars/attribute_options 限制属性解码，associations 限制关联总数。
    """

    declarations: int = 20000
    groups: int = 10000
    versions: int = 64
    attribute_chars: int = 65536
    attribute_options: int = 128
    associations: int = 100000

    def __post_init__(self):
        if any(type(value) is not int or value < 0 for value in vars(self).values()):
            raise ValueError("multiversion budgets must be nonnegative integers")


def source(item):
    """把证据条目转成可序列化的溯源字典（path + 起止行列 + 值 + 类型）。

    无 location 的证据返回 None，调用方负责过滤。
    """
    if item.location is None:
        return None
    start, end = item.location.start, item.location.end
    return {"path": start.path, "startLine": start.line, "startColumn": start.column,
            "endLine": end.line, "endColumn": end.column, "value": str(item.value), "kind": item.kind}


def discover(snapshot, budget, profiles=None):
    """发现函数版本组，返回目录字典（groups + 统计 + discoveryStatus）。

    参数 snapshot 为规范化快照；budget 为 Budget；profiles 为可选的
    {配置: CompilerProfile}。返回的每个 group 是一个大字典（由 _group 构造），
    包含机制、版本列表、default 缺口判定与 reasonCodes。
    """
    profiles = profiles or {}
    evidence = {item.id: item for item in snapshot.evidence}
    functions = sorted((f for f in snapshot.facts if f.kind == "function_declaration"), key=lambda f: f.id)
    # 证据ID -> 声明事实ID 的属主表（只看预算内的前 budget.declarations 条声明）。
    owners = {eid: f.id for f in functions[:budget.declarations] for eid in f.evidence_ids}
    attrs = defaultdict(list)
    orphans = []
    for fact in snapshot.facts:
        if fact.kind != "declaration_attribute":
            continue
        # 解码属性字符串；机制未知且证据不带函数清单标记的属性直接忽略。
        decoded = decode(fact.canonical_value, budget.attribute_chars, budget.attribute_options)
        if decoded["mechanism"] == "unknown" and not any("functionInventory" in evidence[eid].metadata for eid in fact.evidence_ids):
            continue
        ids = fact.metadata.get("functionOwnerEvidenceIds", (fact.metadata.get("functionOwnerEvidenceId"),))
        found = {owners[eid] for eid in ids if eid in owners}
        if len(found) == 1:
            # 属主唯一：属性归到该声明。
            attrs[next(iter(found))].append((fact, decoded))
        else:
            # 属主缺失或多个：作为孤儿属性，后面单独建组（不绑定到任何声明）。
            orphans.append((fact, decoded))
    buckets = defaultdict(list)
    for fact in functions[:budget.declarations]:
        # 分桶键：(文件路径, 签名, 链接性, 是否内部, 配置)。
        record = fact.metadata["function"]
        item = evidence[fact.evidence_ids[0]]
        configuration = fact.context_ids[0] if len(fact.context_ids) == 1 else "source-unconfigured"
        key = (item.location.start.path, record["signature"], record["linkage"], record["internal"], configuration)
        buckets[key].append((fact, record, attrs[fact.id]))
    groups, issues = [], set()
    discovery_truncated = len(functions) > budget.declarations
    for key, members in sorted(buckets.items()):
        # 没有任何多版本属性的桶不建组（普通函数不属于多版本分析）。
        if not any(attributes for _, _, attributes in members):
            continue
        if len(groups) >= budget.groups:
            issues.add("multiversion-budget-exceeded")
            continue
        groups.append(_group(key, members, evidence, budget, profiles.get(key[-1]), discovery_truncated))
    for fact, decoded in sorted(orphans, key=lambda pair: pair[0].id):
        # 孤儿属性：合成一个无声明成员的占位组，签名键用 "<unbound>:"+事实ID 隔离。
        if len(groups) >= budget.groups:
            issues.add("multiversion-budget-exceeded")
            continue
        item = evidence[fact.evidence_ids[0]]
        path = item.location.start.path if item.location else "<unknown>"
        key = (path, "<unbound>:" + fact.id, "unknown", False, fact.context_ids[0] if len(fact.context_ids) == 1 else "source-unconfigured")
        group = _group(key, (), evidence, budget, None, True)
        group.update(mechanism=decoded["mechanism"], attributeFactIds=[fact.id], evidenceIds=list(fact.evidence_ids),
                     sources=[s for eid in fact.evidence_ids if (s := source(evidence[eid])) is not None])
        groups.append(group)
    if discovery_truncated:
        issues.add("multiversion-budget-exceeded")
    _semantic_hints(groups, snapshot, evidence, budget.declarations)
    return {"version": 1, "groups": groups, "declarationCount": len(functions),
            "retainedDeclarationCount": min(len(functions), budget.declarations),
            "groupCount": sum(any(a for _, _, a in m) for m in buckets.values()) + len(orphans),
            "discoveryStatus": "partial" if issues else "complete", "reasonCodes": sorted(issues)}


def _semantic_hints(groups, snapshot, evidence, limit):
    """给每个组补充 function_semantics 语义绑定提示。

    索引 (证据起点 -> 语义记录)，再按组内声明的证据起点匹配。语义记录的配置必须
    与组一致（或组本身无配置）。冲突（同配置多个不同 canonicalKey）时组退为
    unknown 并记 multiversion-semantic-binding-ambiguous；超预算同样退化为 unknown。
    """
    index = defaultdict(list)
    for fact in sorted(snapshot.facts, key=lambda f: f.id):
        if fact.kind != "function_semantics":
            continue
        for eid in fact.evidence_ids:
            item = evidence[eid]
            if item.location is not None:
                index[item.location.start].append((fact.context_ids, fact.metadata["functionSemantics"]))
    retained = 0
    for group in groups:
        hints, keys = [], defaultdict(set)
        for declaration in group["declarations"]:
            for eid in declaration["evidenceIds"]:
                for contexts, record in index.get(evidence[eid].location.start, ()):
                    if len(contexts) != 1 or (group["configuration"] != "source-unconfigured" and contexts[0] != group["configuration"]):
                        continue
                    if retained >= limit:
                        group["reasonCodes"] = sorted(set(group["reasonCodes"]) | {"multiversion-budget-exceeded"})
                        group["groupCompleteness"], group["defaultGap"] = "unknown", False
                        continue
                    retained += 1
                    hints.append({"configuration": contexts[0], **record})
                    keys[contexts[0]].add(record.get("canonicalKey"))
        conflict = any(len(values) != 1 or None in values for values in keys.values())
        if conflict:
            group["reasonCodes"] = sorted(set(group["reasonCodes"]) | {"multiversion-semantic-binding-ambiguous"})
            group["groupCompleteness"], group["defaultGap"] = "unknown", False
        if group["groupCompleteness"] != "complete" and group["defaultPresence"] == "absent_in_scope":
            group["defaultPresence"] = "unknown"
        group["semanticBindings"] = hints


def _group(key, members, evidence, budget, profile, truncated):
    """把一个桶物化成组字典；所有一致性与 default 缺口判定都在这里完成。

    key = (路径, 签名, 链接性, 内部性, 配置)；members 为 (事实, function 记录, 属性列表)。
    关键判定：机制必须唯一；返回类型必须唯一；default 版本是否存在/是否只有声明；
    编译器规则是否要求 default（requiresDefault + absent_in_scope => defaultGap）。
    """
    path, signature, linkage, internal, configuration = key
    reasons, versions, origins, fact_ids, evidence_ids, declarations = set(), [], {}, set(), set(), []
    mechanisms, returns = set(), set()
    for fact, record, attrs in sorted(members, key=lambda item: item[0].id):
        # 无属性的成员声明：记 multiversion-unclassified-declaration。
        declarations.append({"factId": fact.id, "evidenceIds": fact.evidence_ids, "record": record})
        if not attrs:
            reasons.add("multiversion-unclassified-declaration")
        returns.add(record["returnType"])
        # 绑定不完整/条件编译/生产者冲突都会让组绑定状态退为 ambiguous。
        if record["bindingStatus"] != "complete" or record["conditional"] or record.get("producerConflict"):
            reasons.add("multiversion-binding-ambiguous")
        if record["inventoryTruncated"]:
            truncated = True
        for eid in fact.evidence_ids:
            if (description := source(evidence[eid])) is not None:
                origins[eid] = description
        for attr, decoded in attrs:
            fact_ids.add(attr.id)
            evidence_ids.update(attr.evidence_ids)
            mechanisms.add(decoded["mechanism"])
            if decoded["status"] != "complete":
                reasons.add(decoded["reason"])
            for eid in attr.evidence_ids:
                if (description := source(evidence[eid])) is not None:
                    origins[eid] = description
            for option in decoded["options"]:
                # 版本条目：定义状态由绑定完整性与函数体字节数共同决定。
                versions.append({"option": option, "functionFactId": fact.id, "attributeFactId": attr.id,
                                 "definition": "unknown" if record["bindingStatus"] != "complete" else "present" if record["bodyBytes"] is not None else "declaration_only",
                                 "conditional": record["conditional"], "bodySpan": record["bodySpan"], "path": path})
    if len(returns) > 1 or len(mechanisms) != 1:
        # 返回类型不一致或机制混杂：无法判定绑定，退为 ambiguous。
        reasons.add("multiversion-binding-ambiguous")
    mechanism = next(iter(mechanisms)) if len(mechanisms) == 1 else "unknown"
    # target 只有一个版本时实际上是单目标标注，不算多版本机制。
    if mechanism == "target" and len({v["option"] for v in versions}) == 1:
        mechanism = "single_target"
    if len(versions) > budget.versions or truncated:
        reasons.add("multiversion-budget-exceeded")
    rule = semantic_rule(profile, mechanism)
    default = [v for v in versions if v["option"] == "default"]
    complete = not reasons
    # defaultPresence 三分支：有非条件 default => present；单目标 => not_applicable；
    # 完整且规则适用但没有 default => absent_in_scope；其余一律 unknown。
    presence = ("present" if default and not any(v["conditional"] for v in default) else
                "not_applicable" if complete and mechanism == "single_target" else
                "absent_in_scope" if complete and rule["status"] == "applicable" else "unknown")
    definition = ("present" if presence == "present" and any(v["definition"] == "present" for v in default) else
                  "declaration_only" if presence == "present" and all(v["definition"] == "declaration_only" for v in default) else "not_applicable" if presence == "not_applicable" else "unknown")
    if definition == "declaration_only":
        # 只有声明没有定义的 default：记专门的问题码。
        reasons.add("multiversion-default-declaration-only")
    if rule["status"] == "unknown" and mechanism != "single_target":
        # 编译器规则不明：无法判定 default 必要性。
        reasons.add("multiversion-compiler-profile-unknown")
    # defaultGap = 分析完整 + 范围内缺 default + 规则要求 default，是可上报的缺口。
    gap = complete and presence == "absent_in_scope" and rule["requiresDefault"]
    if gap:
        reasons.add("multiversion-default-absent-in-scope")
    return {"id": stable_id("function_multiversion_group", 1, key), "path": path, "signature": signature,
            "configuration": configuration, "linkage": linkage, "internal": internal, "mechanism": mechanism,
            "groupCompleteness": "complete" if complete else "unknown", "defaultPresence": presence,
            "defaultDefinition": definition, "defaultGap": bool(gap), "semanticRule": rule,
            "targetApplicability": "unknown", "targetSelection": "unknown", "bodyDependency": "unknown",
            "versions": versions[:budget.versions], "versionCount": len(versions), "reasonCodes": sorted(reasons),
            "declarations": declarations, "attributeFactIds": sorted(fact_ids), "evidenceIds": sorted(evidence_ids),
            "sources": [origins[eid] for eid in sorted(origins)], "dependencies": [], "bindings": []}
