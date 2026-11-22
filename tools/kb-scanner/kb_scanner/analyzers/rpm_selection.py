"""RPM occurrence DAG. Missing ancestors never become complete proof."""
# RPM 出现（occurrence）DAG 分析：基于 RPM spec 的出现记录构建选择图。
# 关键不变量：任何祖先（parent）缺失或结构不完整时，对应证明只能记
# unknown，绝不升格为 complete。位于 analyzer 阶段，由
# BuildSelectionAnalyzer 检测到 rpm 方言事实时调用。
from __future__ import annotations
from dataclasses import replace

from ..core import AnalysisSnapshot, CompletionStatus, Diagnostic, DiagnosticSeverity, Entity, ProofPath, Relation, StageResult, stable_id

KINDS = {"build_assignment":"defines", "build_arch_probe":"depends_on", "build_dependency":"requires",
         "build_argument":"adds_argument", "build_source":"selects", "build_arch_constraint":"excludes"}
# RPM 事实种类到关系 kind 的映射；未列出的种类回退为 selects。


def analyze_rpm(context, facts, base, max_relations, max_paths):
    """对 RPM 方言事实构建出现 DAG、关系与证明路径。

    参数：
      context: 扫描上下文（含 target_arch）。
      facts: normalizer 快照，仅处理 metadata 带 'rpm' 的事实。
      base: 已有分析快照，在其上追加。
      max_relations/max_paths: 本阶段新增关系/路径预算。
    返回 StageResult；不完整/预算截断以去重后的 WARNING 诊断标注 PARTIAL。
    """
    selected = [f for f in facts.facts if 'rpm' in f.metadata]
    by_key = {(f.subject_id,f.metadata['rpm']['key']):f for f in selected}
    entities = {e.id:e for e in (*facts.entities,*base.entities)}
    selector_id = stable_id('build_entity',1,'architecture_selector',context.target_arch)
    root = entities.get(selector_id) or Entity(selector_id,'architecture_selector',context.target_arch,
                  properties={'identityVersion':1,'targetArchitecture':context.target_arch})
    entities[root.id] = root
    for f in selected:
        entities[stable_id('rpm-occurrence',f.id)] = Entity(stable_id('rpm-occurrence',f.id),
            'architecture_probe' if f.kind=='build_arch_probe' else f.kind.removeprefix('build_'),
            f.canonical_value,properties={'rpm':dict(f.metadata['rpm']),'factId':f.id,
                                         'sourceEvidenceId':f.evidence_ids[0] if f.evidence_ids else None})
    relations = {r.id:r for r in base.relations}
    proofs = {p.id:p for p in base.proof_paths}
    counts = [0,0]
    memo = {}
    diagnostics = []

    def warning(reason):
        diagnostics.append(Diagnostic('build-selection.rpm-partial','analysis.build-selection',DiagnosticSeverity.WARNING,
            reason,producer='rpm-selection',details={'reason':reason}))

    def visit(f,trail=()):
        """递归展开出现记录的父链并建边建证明，memo 缓存结果元组。

        返回 (是否完整, 证明路径元组, 涉及关系 ID 元组, 原因)。
        完整性 = 事实状态非 unknown 且结构/取值已知（argument/source 还
        要求 buildUseKnown）；任一父缺失即降级。预算截断时不只丢弃新边，
        还会把本次已建的边/证明统一改写为 unknown（回写降级）。
        """
        if f.id in memo:
            return memo[f.id]
        if f.id in trail or len(trail)>=64:
            return False,(),(), 'rpm-proof-cycle-or-depth'
        rpm = f.metadata['rpm']
        status = str(f.metadata.get('selectionStatus','unknown'))
        complete = status != 'unknown' and rpm.get('structureComplete',True) and rpm.get('valueKnown',True)
        reason = str(rpm.get('reason',''))
        if f.kind in {'build_argument','build_source'} and status!='inactive' and not rpm.get('buildUseKnown',True):
            complete = False
        ancestors = []
        evidence = list(f.evidence_ids)
        all_relations = []
        paths = []
        own_proofs = []
        for key in rpm.get('parents',()):
            parent = by_key.get((f.subject_id,key))
            if parent is None:
                complete,reason = False,'rpm-proof-parent-missing'
                continue
            ok,parent_paths,parent_relations,parent_reason = visit(parent,(*trail,f.id))
            # A complete exclusion parent can be inactive; that doesn't make the else path inactive.
            complete = complete and ok
            if not ok:
                reason = reason or parent_reason
            ancestors.append((parent,parent_paths))
            evidence.extend(parent.evidence_ids)
            all_relations.extend(parent_relations)
        target = stable_id('rpm-occurrence',f.id)
        incoming = ancestors or [(None,((),))]
        for parent,parent_paths in incoming:
            if counts[0]>=max_relations:
                complete,reason=False,'rpm-relation-budget'
                break
            source = root.id if parent is None else stable_id('rpm-occurrence',parent.id)
            relation_status = status if complete else 'unknown'
            issue = 'missing_riscv_selection_path' if f.kind=='build_arch_constraint' and complete and status=='selected' and rpm.get('targetAllowed') is False else None
            metadata = {'rpm':dict(rpm),'itemKind':f.kind,'value':f.canonical_value,'factId':f.id,
                        'matchedEvidenceIds':f.evidence_ids,
                        'configuration':rpm.get('package','main'),'reason':reason or 'rpm-static-selection',
                        'targetArchitecture':context.target_arch}
            if issue:
                metadata['issue']=issue
            rid=stable_id('rpm-relation',source,target,relation_status)
            relation=Relation(rid,KINDS.get(f.kind,'selects'),source,target,tuple(sorted(set(evidence))),
                              status=relation_status,metadata=metadata)
            relations[rid]=relation; counts[0]+=1; all_relations.append(rid)
            for prefix in parent_paths:
                if counts[1]>=max_paths:
                    complete,reason=False,'rpm-proof-budget'
                    break
                path=(*prefix,rid)
                proof_status='complete' if complete else 'unknown'
                proof=ProofPath(stable_id('rpm-proof',f.id,path,proof_status),root.id,target,path,
                                proof_status,None if complete else reason or 'rpm-proof-incomplete',len(path)+1,False)
                proofs[proof.id]=proof;counts[1]+=1;paths.append(path)
                own_proofs.append(proof.id)
        if not complete:
            reason=reason or 'rpm-proof-incomplete'
            # Budget truncation must also downgrade paths/edges made earlier for this occurrence.
            for rid in all_relations:
                if rid in relations and relations[rid].target_id==target:
                    relations[rid]=replace(relations[rid],status='unknown',metadata={**relations[rid].metadata,'reason':reason})
            for pid in own_proofs:
                proofs[pid]=replace(proofs[pid],status='unknown',reason=reason)
            warning(reason)
        memo[f.id]=(complete,tuple(paths),tuple(dict.fromkeys(all_relations)),reason)
        return memo[f.id]

    for fact in sorted(selected,key=lambda f:(f.subject_id,f.scope,f.id)):
        visit(fact)
    snapshot=AnalysisSnapshot(entities=tuple(sorted(entities.values(),key=lambda e:e.id)),
                              relations=tuple(sorted(relations.values(),key=lambda r:r.id)),
                              proof_paths=tuple(sorted(proofs.values(),key=lambda p:p.id)))
    unique={d.message:d for d in diagnostics}
    return StageResult(snapshot,CompletionStatus.PARTIAL if unique else CompletionStatus.COMPLETE,
                       tuple(unique[k] for k in sorted(unique)))
