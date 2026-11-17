"""线性桶发现 + 确定性的有界物化，产出架构家族目录。

算法：对每个带 pathObservation 的 source_file 实体套用 paths.candidates 词法模板，
按模板 key 分桶；只保留含 x86 角色候选的桶作为家族。所有遍历与截断均按排序键
进行，保证相同输入产出相同目录。缺失候选只记 absent，不推断原因。
"""
from collections import defaultdict
from dataclasses import dataclass

from ...core import stable_id
from .paths import VERSION, candidates


@dataclass(frozen=True)
class FamilyBudget:
    """家族发现的各项工作量上限（frozen 不可变；负值在构造时抛 ValueError）。

    paths/families/members 限制发现阶段，bindings/proof_steps 限制绑定阶段，
    relations 限制最终物化的成员关系数。超限不是错误：记 reasonCodes 并截断。
    """

    paths: int = 100000
    families: int = 10000
    members: int = 128
    bindings: int = 4096
    proof_steps: int = 10000
    relations: int = 10000

    def __post_init__(self):
        if any(v < 0 for v in vars(self).values()):
            raise ValueError("family budgets must be nonnegative")


def discover(entities, budget, source_architecture="x86"):
    """在授权文件清单内发现有界家族目录，返回目录字典。

    参数 entities 为事实快照中的实体；budget 为 FamilyBudget；
    source_architecture 为源架构族（x86/arm64），决定哪个角色触发建族。
    返回的目录含 version/scope/discoveryStatus/统计计数/reasonCodes/families，
    其中每个 family 带 members（bindings 由后续 bind() 就地填充）。
    """
    # 只看授权清单内的 source_file 且带路径观察记录的实体；排序保证确定性。
    sources = sorted((e for e in entities if e.kind == "source_file" and "pathObservation" in e.properties), key=lambda e: (e.name, e.id))
    buckets, folded = defaultdict(list), defaultdict(set)
    reasons = set()
    if len(sources) > budget.paths:
        reasons.add("family-path-budget")
    operations = 0
    for entity in sources[:budget.paths]:
        # casefold 折叠计数用于检测"仅大小写不同"的路径歧义。
        folded[entity.name.casefold()].add(entity.name)
        for candidate in candidates(entity.name, source_architecture):
            buckets[candidate.key].append((candidate, entity))
            operations += 1
    # 只有桶里出现源架构角色候选才算架构家族；纯 riscv/generic/foreign 桶不建家族。
    keys = sorted(key for key, values in buckets.items() if any(c.role == source_architecture for c, _ in values))
    if len(keys) > budget.families:
        reasons.add("family-count-budget")
    families = []
    for key in keys[:budget.families]:
        values = buckets[key]
        member_reasons = ("family-member-budget",) if len(values) > budget.members else ()
        reasons.update(member_reasons)
        members = []
        for candidate, entity in values[:budget.members]:
            # 词法槽冲突或大小写折叠歧义都会让成员状态退为 ambiguous。
            ambiguous = candidate.ambiguous or len(folded[entity.name.casefold()]) > 1
            # 候选状态五态取值：这里只可能是 ambiguous 或 unknown，发现阶段不下结论。
            members.append({"fileId": entity.id, "path": candidate.path, "roleHint": candidate.role,
                "candidateStatus": "ambiguous" if ambiguous else "unknown", "token": candidate.token,
                "rule": candidate.rule, "observation": dict(entity.properties["pathObservation"]), "bindings": []})
        # 家族稳定ID = stable_id("architecture-family", VERSION, 模板key)；任一成员
        # ambiguous 则整个家族 ambiguous；单成员家族记 candidateAbsence（范围内未见其他候选）。
        families.append({"id": stable_id("architecture-family", VERSION, key), "template": key,
            "status": "ambiguous" if any(m["candidateStatus"] == "ambiguous" for m in members) else "unknown",
            "memberCount": len(values), "members": members, "reasonCodes": list(member_reasons),
            "alternativeStatus": "unknown", "candidateAbsence": "no-candidate-observed-in-scope" if len(values) == 1 else None})
    return {"version": VERSION, "scope": "authorized-file-list", "discoveryStatus": "partial" if reasons else "complete",
            "pathCount": len(sources), "pathsProcessed": min(len(sources), budget.paths), "familyCount": len(keys),
            "bucketOperations": operations, "reasonCodes": sorted(reasons), "families": families}
