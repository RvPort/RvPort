"""架构家族缺失候选推导：从家族目录推导"缺 riscv 对应成员"的候选与升格证明。

算法规格源自 rvport-skills/riscv-scan 的 arch_missing.py（目录/文件级配对检测），
但实现完全按 kb-scanner 合同重写：
1. 识别层不重新做字面子串替换——直接消费 grouping.discover 产出的
   PathCandidate 槽位产物（词表整词匹配 + 分隔符边界），从根上消灭
   riscv-scan 的无词边界误报（alarm.c→al_riscv64.c）与 token 拆分误报
   （foo_arm64.c→foo_64_riscv64.c）；
2. 两级输出（五态纪律）：
   - 纯词法缺失只进 extensions 候选（candidateMissingMember/directoryAbsence，
     status=candidate、scope=authorized-file-list），不产 Finding；
   - 仅当成员绑定证明"构建已选中源架构成员"（bindings.selectionStatus=selected）
     且家族内无 riscv/generic 成员时，产 family_gap 实体 + selects 关系 +
     complete ProofPath（升格链，镜像 build_selection 的 selection_gap 先例），
     由 BUILD.SELECTION 检测器消费为 missing_riscv_family_member 观察。
3. 对称性：角色判定全部经 paths._tables(source_architecture) 折叠，x86 源与
   arm64 源自动对称；推导目标恒为 riscv64（目标架构词，与源无关）。
"""
from ...core import Entity, ProofPath, Relation, stable_id
from .paths import VERSION


def derive_target_path(family, anchor_member):
    """从家族模板与锚成员推导缺失的目标文件/目录名。

    直接消费 grouping 的模板 key（token 已按词边界剥离），不做二次字面替换——
    这是误报防线的根：foo_arm64.c 的模板是 filename:foo.c（token=arm64 整词），
    目标 = foo_riscv64.c（而非 riscv-scan 的 foo_64_riscv64.c）。
    - filename:<stem><suffix> 模板 → <stem>_riscv64<suffix>
    - directory:<parent>/{architecture}/<rest> 模板 → <parent>/riscv64
    """
    template = family["template"]
    if anchor_member["rule"] == "filename-affix-v1" and template.startswith("filename:"):
        path = template.removeprefix("filename:")
        stem, _, name = path.rpartition("/")
        base, dot, ext = name.partition(".")
        target_name = f"{base}_riscv64{'.' + ext if dot else ''}"
        return f"{stem}/{target_name}" if stem else target_name
    if anchor_member["rule"] == "directory-slot-v1" and template.startswith("directory:"):
        path = template.removeprefix("directory:")
        parts = path.split("/")
        if "{architecture}" not in parts:
            return None
        index = parts.index("{architecture}")
        if index == 0:
            return "riscv64"
        return "/".join((*parts[:index], "riscv64"))
    return None


def analyse(catalog, facts, base, source_architecture, target_arch, budget):
    """对家族目录执行缺失推导；就地写入 candidateMissingMember/directoryAbsence，
    返回 (gap_entities, gap_relations, gap_proofs, issues)。

    gap 元组的每一项只含升格条目（extensions 候选直接写进 catalog，不在此返回）。
    """
    issues = set()
    escalated = 0
    gap_entities: dict[str, Entity] = {}
    gap_relations: list[Relation] = []
    gap_proofs: list[ProofPath] = []
    for family in catalog["families"]:
        if escalated >= budget.absence_candidates:
            issues.add("family-absence-budget")
            break
        roles = {member["roleHint"] for member in family["members"]}
        # 只推导源架构角色领衔的家族；歧义家族不下结论（五态纪律）。
        if source_architecture not in roles:
            continue
        if any(member["candidateStatus"] == "ambiguous" for member in family["members"]):
            continue
        if "riscv" in roles or "generic" in roles:
            # 已有 riscv 或通用成员：无缺失（可移植逃生口压制）。
            family["candidateAbsence"] = None
            continue
        # 第一级（总是产出）：extensions 候选，带推导目标名。
        source_members = [m for m in family["members"] if m["roleHint"] == source_architecture and m["rule"] in {"filename-affix-v1", "directory-slot-v1"}]
        if not source_members:
            continue
        anchor = min(source_members, key=lambda m: m["path"])
        derived = derive_target_path(family, anchor)
        if derived is None:
            continue
        family["candidateMissingMember"] = {
            "role": "riscv", "derivedTargetPath": derived,
            "derivationRule": "family-absence-v1",
            "anchorToken": anchor["token"], "anchorPath": anchor["path"],
            "status": "candidate", "scope": "authorized-file-list",
        }
        escalated += 1
        # 第二级（需证明升格）：构建已选中源架构成员且无 riscv/generic 成员。
        selected = [
            (member, binding)
            for member in family["members"] if member["roleHint"] == source_architecture
            for binding in member["bindings"] if binding["selectionStatus"] == "selected"
        ]
        if not selected:
            continue
        member, binding = min(selected, key=lambda item: (item[0]["path"], item[1]["proofId"]))
        entity, relation, proof = _emit_gap(family, member, binding, derived, facts, base, target_arch)
        if entity is None:
            # 升格链无法复现成员 proof（引用悬空防御）：停在候选层，记问题码。
            issues.add("family-absence-escalation-skipped")
            continue
        gap_entities[entity.id] = entity
        gap_relations.append(relation)
        gap_proofs.append(proof)
    # 目录级推导（仅 extensions，保守不升格）写进 catalog 顶层。
    catalog["directoryAbsence"] = _directory_absence(catalog, source_architecture, budget, issues)
    return gap_entities, gap_relations, gap_proofs, tuple(sorted(issues))


def _emit_gap(family, member, binding, derived, facts, base, target_arch):
    """产升格三元组：family_gap 实体 + selects 关系 + complete ProofPath。

    关系 selector 取成员绑定 proof 的 source 实体（链可完整复现）；证据取
    绑定 proof 链上全部实存证据 ID。任何引用无法在 facts/base 中落实即返回
    (None, None, None)——绝不发悬空引用（检测器校验会整段 FAILED）。
    """
    relations = {r.id: r for r in base.relations}
    entities = {e.id: e for e in base.entities}
    evidence_ids = {e.id for e in facts.evidence}
    chain = [relations.get(rid) for rid in binding["relationIds"]]
    if binding["proofId"] not in {p.id for p in base.proof_paths} or any(r is None for r in chain):
        return None, None, None
    provenance = tuple(sorted({eid for r in chain if r is not None for eid in r.evidence_ids if eid in evidence_ids}))
    if not provenance:
        return None, None, None
    selector_id = chain[0].source_id if chain and chain[0] is not None else None
    if selector_id is None or selector_id not in entities:
        return None, None, None
    gap_id = stable_id("architecture-family-gap", VERSION, family["id"], target_arch)
    entity = Entity(gap_id, "family_gap", f"{family['template']}:missing-riscv-family-member",
                    properties={"identityVersion": 1, "issue": "missing_riscv_family_member"})
    relation = Relation(
        stable_id("family-gap-selects", selector_id, gap_id),
        "selects", selector_id, gap_id,
        evidence_ids=provenance,
        status="selected",
        metadata={"targetArchitecture": target_arch, "issue": "missing_riscv_family_member",
                  "reason": "selected-source-member-without-riscv-or-generic",
                  "familyId": family["id"], "derivedTargetPath": derived},
    )
    proof = ProofPath(
        stable_id("proof_path", 1, selector_id, gap_id, (relation.id,), "complete", None),
        selector_id, gap_id, (relation.id,), "complete", visited=2,
    )
    return entity, relation, proof


def _directory_absence(catalog, source_architecture, budget, issues):
    """目录级缺失候选：同一父目录下有源架构角色子目录且无 riscv 子目录。

    槽位来自 directory: 模板（{architecture} 段即架构子目录）；子目录角色按
    paths 词表整词匹配（非子串）。只产 extensions 候选（无升格）。
    ppc64le 等未登记目录名 → 不建槽位，既不触发也不计入 existing。
    """
    parents = {}
    for family in catalog["families"]:
        template = family["template"]
        if not template.startswith("directory:"):
            continue
        parts = template.removeprefix("directory:").split("/")
        if "{architecture}" not in parts or parts.index("{architecture}") == 0:
            continue
        index = parts.index("{architecture}")
        parent = "/".join(parts[:index])
        roles = {m["roleHint"] for m in family["members"] if m["rule"] == "directory-slot-v1"}
        parents.setdefault(parent, set()).update(roles)
    entries = []
    for parent in sorted(parents)[:budget.absence_directories]:
        roles = parents[parent]
        # generic 视为可移植逃生口（与文件级一致）：存在通用子目录不推导缺失。
        if source_architecture in roles and not roles & {"riscv", "generic"}:
            entries.append({"parentPath": parent, "derivedTargetPath": f"{parent}/riscv64",
                            "existingRoles": sorted(roles), "status": "candidate",
                            "scope": "authorized-file-list"})
    if len(parents) > budget.absence_directories:
        issues.add("family-absence-directory-budget")
    return entries
