"""在当前解析有效期内，以正式事实选择用途采集锚点。

UsageAnchorSelector 在前端解析阶段预先筛选"值得深挖"的证据（锚点），
让候选用法分析只关注可能命中知识的部分，控制解析期工作量。
"""
from ..core import CompletionStatus, FrontendSnapshot
from ..normalization import CppEvidenceNormalizer
from ..analyzers.candidate_usage.demand import CandidateUsageDemand


class UsageAnchorSelector:
    """仅处理标量 Evidence；不保存语法树，也不输出预归一化事实。"""

    def __init__(self, context, knowledge, *, normalizer=None, demand=None):
        """初始化选择器：预编译知识需求并提取知识条目的规范取值集合。

        prepared 是 demand.prepare(knowledge) 的预处理结果（避免每实体
        重复计算）；test_values 是知识条目 canonical_value 的集合，
        用于快速判断证据值是否命中知识。
        """
        self.context, self.knowledge = context, knowledge
        self.normalizer = normalizer or CppEvidenceNormalizer()
        self.demand = demand or CandidateUsageDemand()
        self.prepared = self.demand.prepare(knowledge)
        self.test_values = frozenset(entry.canonical_value for entry in knowledge.entries)

    def test_anchors(self, evidence, usage_anchors):
        """仅选择采集需求，不以索引命中代替正式知识/用途判断。

        保留：usage_anchors 指定的证据、全部 inline_asm 证据、以及
        值命中知识条目（call/宏参数/宏展开/类型引用四类）的证据。
        """
        ids = {item.id for item in usage_anchors}
        return tuple(item for item in evidence if item.id in ids or item.kind == "inline_asm"
                     or item.kind in {"call_expression", "macro_argument_token", "macro_expansion", "type_reference"}
                     and item.value in self.test_values)

    def select(self, entity, evidence):
        """为单个实体挑选需要进入候选用法分析的证据。

        思路：先按 (kind, value) 挑代表证据（声明类全保留，引用类去重），
        用归一化器把代表转成正式事实，交由 demand 依知识匹配出根，
        最后按选中的 (kind, value) 展开回全部原始出现——代表仅用于
        判定，展开保证不漏 occurrence。
        """
        # 这是采集需求（允许保守多选），不是最终 fact 或排除证明。
        # 同 kind/value 的匹配结果相同；保留声明以满足正式引用校验，
        # 引用只预归一化一个代表，选中后展开全部原始 occurrence。
        declarations = {"enum_declaration", "enum_member_declaration", "macro_definition", "function_declaration"}
        inputs = declarations | {"enum_member_reference", "macro_expansion", "preprocessor_condition", "preprocessor_branch"}
        seen, representatives = set(), []
        for item in evidence:
            if item.kind not in inputs:
                continue
            key = item.kind, item.value
            if item.kind in declarations or key not in seen:
                representatives.append(item)
                seen.add(key)
        result = self.normalizer.normalize(self.context, FrontendSnapshot((entity,), tuple(representatives)))
        if result.value is None or result.status in {CompletionStatus.FAILED, CompletionStatus.UNAVAILABLE}:
            return ()
        roots = self.demand.select(result.value, self.knowledge, prepared=self.prepared)
        identifiers = {identifier for root in roots for identifier in root.matched_evidence_ids}
        selected = {(item.kind, item.value) for item in representatives if item.id in identifiers}
        return tuple(item for item in evidence if (item.kind, item.value) in selected)
