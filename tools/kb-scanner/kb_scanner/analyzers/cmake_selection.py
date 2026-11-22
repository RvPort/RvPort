"""CMake event proofs and target/source closure, independent of Make recipes."""
# CMake 事件证明与目标/源闭包分析：基于 CMake 事件流（metadata 带 "cmake"
# 键的事实）构建选择证明，与 Make recipe 路径相互独立。位于 analyzer 阶段，
# 由 BuildSelectionAnalyzer 检测到 CMake 方言事实时调用。
from collections import defaultdict

from ..core import AnalysisSnapshot, CompletionStatus, Diagnostic, DiagnosticSeverity, Entity, ProofPath, Relation, StageResult, stable_id


def analyze_cmake(context, facts, knowledge, base, max_relations, max_paths):
    """对 CMake 方言事实构建事件图、证明路径与源文件依赖。

    参数：
      context: 扫描上下文（含 target_arch）。
      facts: normalizer 快照，仅处理 metadata 带 "cmake" 的事实。
      knowledge: 知识快照，用于把源文件事实匹配到 x86 知识条目。
      base: 已有分析快照，在其上追加。
      max_relations/max_paths: 本阶段新增关系/路径预算（限额 = 已有量 + 预算）。
    返回 StageResult；预算耗尽、父事件缺失等以 WARNING 诊断标注 PARTIAL。
    """
    from .build_selection import BuildSourceReachabilityResolver, _knowledge_matches
    selected = [f for f in facts.facts if "cmake" in f.metadata]
    by_key = {f.metadata["cmake"]["key"]: f for f in selected}
    entities = {e.id: e for e in (*facts.entities, *base.entities)}
    relations, proofs = {r.id: r for r in base.relations}, {p.id: p for p in base.proof_paths}
    root = Entity(stable_id("cmake-root", context.target_arch), "architecture_selector", context.target_arch)
    entities[root.id] = root
    memo, reasons = {}, set()
    limits = (len(relations)+max_relations, len(proofs)+max_paths)
    for fact in selected:
        identifier = stable_id("cmake-event", fact.id)
        entities[identifier] = Entity(identifier, "build_configuration", fact.canonical_value,
                                     properties={"factId": fact.id, "sourceEvidenceId": fact.evidence_ids[0]})

    def edge(source, target, evidence_ids, state, metadata, prefix=(), kind="selects"):
        """建一条边并同步生成证明路径；预算耗尽时记 reason 并返回空路径。

        路径完整性 = 本边状态为 selected/inactive 且前缀路径上的关系
        均非 unknown；不完整时证明状态记 unknown。
        """
        if len(relations) >= limits[0] or len(proofs) >= limits[1]:
            reasons.add("cmake-proof-budget")
            return ()
        rid = stable_id("cmake-relation", source, target, state, evidence_ids)
        relations[rid] = Relation(rid, kind, source, target, evidence_ids, status=state, metadata=metadata)
        path = (*prefix, rid)
        complete = state in {"selected", "inactive"} and all(relations[r].status != "unknown" for r in prefix)
        status = "complete" if complete else "unknown"
        proof = ProofPath(stable_id("cmake-proof", path, status), root.id, target, path, status,
                          None if complete else "cmake-selection-incomplete", len(path)+1, False)
        proofs[proof.id] = proof
        return path

    def visit(fact, trail=()):
        """沿事件 parents 递归访问，memo 缓存 (首条成功路径, 状态)。

        父事件缺失或父状态 unknown 时本事件状态降级为 unknown；
        环或深度超 64 层记 cmake-proof-cycle-or-depth。
        """
        if fact.id in memo:
            return memo[fact.id]
        if fact.id in trail or len(trail) >= 64:
            reasons.add("cmake-proof-cycle-or-depth")
            return (), "unknown"
        info = fact.metadata["cmake"]
        state = fact.metadata.get("selectionStatus", "unknown")
        incoming = []
        for key in info.get("parents", ()):
            parent = by_key.get(key)
            if parent is None:
                reasons.add("cmake-proof-parent-missing")
                state = "unknown"
                continue
            prefix, parent_state = visit(parent, (*trail, fact.id))
            if not prefix or parent_state == "unknown":
                state = "unknown"
            incoming.append((stable_id("cmake-event", parent.id), prefix))
        metadata = {"cmake": dict(info), "itemKind": fact.kind, "factId": fact.id,
                    "value": fact.canonical_value, "matchedEvidenceIds": fact.evidence_ids,
                    "configuration": fact.metadata.get("configuration", "default"),
                    "reason": info.get("reason", "cmake-static-selection")}
        if fact.kind == "build_argument" and not info.get("effective"):
            metadata["itemKind"] = "cmake_argument_declaration"
        if info.get("rejection") and info.get("required") and state == "selected":
            metadata["issue"] = "missing_riscv_selection_path"
        target = stable_id("cmake-event", fact.id)
        paths = [edge(parent, target, fact.evidence_ids, state, metadata, prefix)
                 for parent, prefix in incoming or [(root.id, ())]]
        memo[fact.id] = (next((p for p in paths if p), ()), state if all(paths) else "unknown")
        return memo[fact.id]

    for fact in sorted(selected, key=lambda f: f.id):
        visit(fact)
    # Macro effects are bound once by BuildSelectionAnalyzer after all dialects
    # have contributed their records, with a separate per-scan relation budget.
    source_entities = {e.name: e for e in facts.entities if e.kind == "source_file"}
    local = defaultdict(list)
    entries_by_kind = defaultdict(list)
    for entry in knowledge.entries:
        if entry.architecture_family.lower() == context.source_arch:
            entries_by_kind[entry.fact_kind].append(entry)
    matches_by_value = {}
    for fact in facts.facts:
        if fact.kind in BuildSourceReachabilityResolver._KNOWLEDGE_KINDS:
            local[fact.subject_id].append(fact)
    # Multiple target memberships must not be silently collapsed into one proof.
    memberships = defaultdict(list)
    for fact in selected:
        if fact.kind == "build_source":
            memberships[fact.metadata["cmake"].get("source")].append(fact)
    for name, sources in sorted(memberships.items(), key=lambda item: str(item[0])):
        # 目标-源成员闭包：对每个被 CMake 目标收录的源文件，把其中命中
        # x86 知识的事实建为 architecture_dependency 实体并挂边与证明。
        source = source_entities.get(name)
        if source is None:
            reasons.add("cmake-source-unavailable")
            continue
        states = {memo[f.id][1] for f in sources}
        for origin in sources:
            prefix, state = memo[origin.id]
            if not prefix:
                continue
            if len(states) > 1:
                state = "unknown"
            for fact in local[source.id]:
                key = (fact.kind, fact.canonical_value)
                if key not in matches_by_value:
                    matches_by_value[key] = tuple(sorted(e.id
                        for kind in BuildSourceReachabilityResolver._KNOWLEDGE_KINDS[fact.kind]
                        for e in entries_by_kind[kind]
                        if _knowledge_matches(fact.canonical_value, e.canonical_value, fact.kind)))
                matches = matches_by_value[key]
                if not matches:
                    continue
                # Source membership alone cannot decide a source-level guard.
                guarded = [r for r in base.relations if r.kind == "guarded_by" and set(r.evidence_ids) & set(fact.evidence_ids)]
                dependency_state = "unknown" if guarded else state
                identifier = stable_id("cmake-source-dependency", fact.id, origin.id)
                entities[identifier] = Entity(identifier, "architecture_dependency", fact.canonical_value,
                    properties={"factId": fact.id, "sourceEvidenceId": fact.evidence_ids[0], "knowledgeIds": matches})
                edge(stable_id("cmake-event", origin.id), identifier, fact.evidence_ids, dependency_state,
                     {"itemKind": "source_x86_dependency", "factId": fact.id, "knowledgeIds": matches,
                      "configuration": origin.metadata.get("configuration", "default"), "reason": "cmake-target-source-membership"},
                     prefix, "depends_on")
    diagnostics = tuple(Diagnostic("build-selection.cmake-partial", "analysis.build-selection", DiagnosticSeverity.WARNING,
                                   reason, producer="cmake-selection", details={"reason": reason}) for reason in sorted(reasons))
    snapshot = AnalysisSnapshot(entities=tuple(sorted(entities.values(), key=lambda e:e.id)),
        relations=tuple(sorted(relations.values(), key=lambda r:r.id)), proof_paths=tuple(sorted(proofs.values(), key=lambda p:p.id)))
    return StageResult(snapshot, CompletionStatus.PARTIAL if diagnostics else CompletionStatus.COMPLETE, diagnostics)
