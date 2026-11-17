"""架构家族分析器：在既有证明之后追加非执行性的家族关系与目录实体。

位于 analyzer 阶段。职责有二：
1. 调用 grouping.discover 按 authorize 文件清单做有界分桶，产出"架构家族"目录；
2. 调用 bindings.bind 把已有 proof 的终点绑定到家族成员上。
全过程只新增候选关系，不删除或改写任何原始事实与结论。
"""
from ...core import AnalysisSnapshot, CompletionStatus, Diagnostic, DiagnosticSeverity, Entity, Relation, StageResult, stable_id
from .grouping import FamilyBudget, discover
from .bindings import bind

CATALOG_KIND = "architecture_family_catalog"


class ArchitectureFamilyAnalyzer:
    """架构家族分析器。

    在流水线中位于 analyzer 阶段、现有 source/build 证明之后：只做路径词法聚类与
    证明绑定，输出 candidate_member_of 候选关系和一个 architecture_family_catalog
    目录实体。所有产出都是非执行性的（semanticRole=candidate）。
    """

    def __init__(self, budget=None):
        """budget：FamilyBudget 实例，缺省使用默认限额；只影响工作量上限，不影响语义。"""
        self.budget = budget or FamilyBudget()

    def analyze(self, context, facts, knowledge, base):
        """执行架构家族分析。

        参数 base 是上一阶段产出的 AnalysisSnapshot；本方法在其之上追加实体与关系。
        返回 StageResult：无家族且无问题时直接透传 base；否则返回包含目录实体、
        candidate_member_of 关系的新快照，status 为 COMPLETE 或 PARTIAL（有问题码时），
        并附带 WARNING 级 Diagnostic。不修改 base 中已有内容。
        """
        # 第一遍：有界分桶发现家族；第二遍：把既有 proof 终点绑定到成员。
        catalog = discover(facts.entities, self.budget, getattr(context, "source_arch", "x86"))
        issues = set(catalog["reasonCodes"])
        issues.update(bind(catalog, facts, base, self.budget))
        if not catalog["families"] and not issues:
            # 无家族无问题：零新增，直接透传上一阶段快照。
            return StageResult(base)
        entities = {e.id: e for e in base.entities}
        relations = list(base.relations)
        added = 0
        for family in catalog["families"]:
            # 每个家族一个 architecture_family 实体，模板 key 即其名称。
            entity = Entity(family["id"], "architecture_family", family["template"])
            entities[entity.id] = entity
            for member in family["members"]:
                if member["fileId"] not in entities:
                    issues.add("family-member-unavailable")
                    continue
                if added >= self.budget.relations:
                    # 关系预算耗尽：跳过剩余成员并记录问题码，不静默丢弃。
                    issues.add("family-relation-budget")
                    continue
                # 成员边稳定ID = stable_id("candidate-member", 成员文件ID, 家族实体ID)。
                relations.append(Relation(stable_id("candidate-member", member["fileId"], entity.id),
                    "candidate_member_of", member["fileId"], entity.id,
                    metadata={"semanticRole": "candidate", "path": member["path"], "rule": member["rule"]}))
                added += 1
        catalog["reasonCodes"] = sorted(issues)
        catalog["discoveryStatus"] = "partial" if issues else "complete"
        # 目录根实体固定为 stable_id(CATALOG_KIND, 1)，整份目录作为其 properties。
        root = Entity(stable_id(CATALOG_KIND, 1), CATALOG_KIND, "授权路径的架构候选目录", properties=catalog)
        entities[root.id] = root
        # 每个问题码一条 WARNING 诊断；只提示不完整，原始事实与结论一律保留。
        diagnostics = tuple(Diagnostic(reason, "analysis.architecture-family", DiagnosticSeverity.WARNING,
            "架构家族分析不完整；保留原有事实与结论。", producer="architecture-family",
            details={"reason": reason, "message_en": "Architecture family analysis is incomplete; existing facts and conclusions are preserved."}) for reason in sorted(issues))
        # 关系与实体按 ID 排序保证输出确定性。
        return StageResult(AnalysisSnapshot(tuple(sorted(relations, key=lambda r: r.id)),
            tuple(sorted(entities.values(), key=lambda e: e.id)), base.proof_paths),
            CompletionStatus.PARTIAL if issues else CompletionStatus.COMPLETE, diagnostics)
