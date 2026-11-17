"""函数版本组分析器：位于既有 source/build 证明之后，成员边不参与执行证明。

analyzer 阶段入口。流程：grouping.discover 按 (路径, 签名, 链接性, 内部性, 配置)
分桶产出函数版本组 -> bindings.associate 按源码范围把既有事实与 proof 关联到组 ->
把组、成员边、属性归属边物化为实体与关系，并输出 function_multiversion_catalog
目录实体。所有边 semanticRole 均为非执行性（membership/source_provenance）。
"""
from ...core import AnalysisSnapshot, CompletionStatus, Diagnostic, DiagnosticSeverity, Entity, Relation, StageResult, stable_id
from .grouping import Budget, discover
from .bindings import associate

CATALOG_KIND = "function_multiversion_catalog"


class FunctionMultiversionAnalyzer:
    """函数多版本分析器（analyzer 阶段）。

    不变量：只读输入快照，只新增 function_version_group/function_source 等实体和
    非执行性关系；所有新增关系的 configuration 元数据来自分组键，不跨配置合并。
    """

    def __init__(self, budget=None, profiles=None):
        """budget：grouping.Budget 工作量上限；profiles：可选的 {配置: CompilerProfile}
        可信外部元数据，仅用于判定编译器规则适用性，绝不据此推断编译器版本。"""
        self.budget = budget or Budget()
        # Optional trusted, already collected metadata; never infer a compiler version.
        self.profiles = dict(profiles or {})

    def analyze(self, context, facts, knowledge, base):
        """执行函数多版本分析。

        参数 base 为上一阶段 AnalysisSnapshot；无组且无问题时直接透传。
        返回 StageResult：新增组实体、function_version_member / function_attribute_owner /
        function_body_contains 关系与目录实体；有问题码时 status=PARTIAL 并附 WARNING。
        """
        catalog = discover(facts, self.budget, self.profiles)
        if not catalog["groups"] and not catalog["reasonCodes"]:
            # 无组无问题：零新增，透传上一阶段快照。
            return StageResult(base)
        links, binding_issues = associate(catalog, facts, base, self.budget)
        issues = set(catalog["reasonCodes"]) | binding_issues
        entities = {e.id: e for e in base.entities}
        relations = {r.id: r for r in base.relations}
        def node(eid):
            # 证据 -> function_source 实体的懒创建缓存；同一证据只建一个节点。
            identifier = stable_id("function_source_node", eid)
            entities[identifier] = Entity(identifier, "function_source", "真实函数源码", properties={"sourceEvidenceId": eid})
            return identifier
        for group in catalog["groups"]:
            # 组实体：kind=function_version_group，名称为签名。
            entities[group["id"]] = Entity(group["id"], "function_version_group", group["signature"])
            for declaration in group["declarations"]:
                # 成员边：声明证据节点 -> 版本组，非执行成员关系（不参与执行证明）。
                eid = declaration["evidenceIds"][0]
                nid = node(eid)
                rid = stable_id("function_version_member", nid, group["id"])
                relations[rid] = Relation(rid, "function_version_member", nid, group["id"], evidence_ids=(eid,),
                    metadata={"semanticRole": "non_executing_membership", "configuration": group["configuration"]})
        owners = {e.id: e.metadata.get("functionOwnerEvidenceId") for e in facts.evidence}
        for group in catalog["groups"]:
            for eid in group["evidenceIds"]:
                owner = owners.get(eid)
                if owner is not None:
                    # 属性归属边：属性证据节点 -> 其所属函数的证据节点，source_provenance。
                    rid = stable_id("function_attribute_owner", eid, owner, group["configuration"])
                    relations[rid] = Relation(rid, "function_attribute_owner", node(eid), node(owner), evidence_ids=(eid, owner), status="complete",
                        metadata={"semanticRole": "source_provenance", "configuration": group["configuration"]})
        for owner, dependency, configuration in links:
            # 函数体包含边：来自 associate 的范围关联（owner 函数体包含依赖证据）。
            rid = stable_id("function_body_contains", owner, dependency, configuration)
            relations[rid] = Relation(rid, "function_body_contains", node(owner), node(dependency), evidence_ids=(owner, dependency), status="complete",
                metadata={"semanticRole": "source_provenance", "configuration": configuration})
        catalog["reasonCodes"] = sorted(issues)
        catalog["discoveryStatus"] = "partial" if issues else "complete"
        # 目录根实体固定 stable_id(CATALOG_KIND, 1)；实体与关系按 ID 排序保证确定性。
        root = Entity(stable_id(CATALOG_KIND, 1), CATALOG_KIND, "函数版本组", properties=catalog)
        entities[root.id] = root
        diagnostics = tuple(Diagnostic(reason, "analysis.function-multiversion", DiagnosticSeverity.WARNING,
            "函数版本分析存在未闭合证据，保留原始事实。", producer="function-multiversion",
            details={"message_en": "Function version analysis has incomplete evidence; original facts are retained."}) for reason in sorted(issues))
        return StageResult(AnalysisSnapshot(tuple(sorted(relations.values(), key=lambda r: r.id)),
            tuple(sorted(entities.values(), key=lambda e: e.id)), base.proof_paths),
            CompletionStatus.PARTIAL if issues else CompletionStatus.COMPLETE, diagnostics)
