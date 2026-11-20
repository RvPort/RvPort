"""一次构建局部使用/控制索引；对值流采用保守的双向关联闭包。

ScopeIndex 把 front-end 产出的 usage_scope 记录（Tree-sitter 语法树行）压成
无向图：透明节点（函数体/复合语句等）穿透到最近的实质所有者；标识符按绑定
结果连到声明；未解析绑定的标识符连到全部候选声明（保守策略：这些边只会
阻止排除，不会制造确定结论）。_reasons() 给不支持的语法记原因码。
"""
from collections import defaultdict
import re


TRANSPARENT = frozenset({"function_definition", "compound_statement", "enum_specifier", "enumerator_list"})
# 受支持的语法节点白名单；不在其中的节点在 _reasons 里记对应原因码。
SUPPORTED = frozenset({"declaration", "init_declarator", "array_declarator", "enumerator",
    "identifier", "primitive_type", "sized_type_specifier", "type_qualifier", "number_literal",
    "char_literal", "character", "escape_sequence", "assignment_expression", "binary_expression",
    "if_statement", "condition_clause", "parenthesized_expression", "expression_statement",
    "subscript_expression", "subscript_argument_list", "comment"})
INTEGERS = frozenset({"bool", "_Bool", "char", "int", "short", "long", "unsigned", "signed",
    "unsigned char", "signed char", "unsigned int", "signed int", "long long", "unsigned long",
    "unsigned long long", "unsigned short", "short int", "long int", "long long int"})


class ScopeIndex:
    """单个 usage_scope 的图索引（构建一次，查询多次）。

    数据结构：rows=记录表、edges/adjacency=无向邻接、positions=起点位置 ->
    记录键、uses=声明 -> 引用它的标识符集合、reasons=记录级原因码。
    所有映射在 __init__ 内建完之后只读。
    """

    def __init__(self, scope):
        """scope：usage_scope 事实里的 usageScope 元数据（records + 状态 + 原因码）。"""
        self.scope = scope
        self.rows = {row["key"]: row for row in scope["records"]}
        self.edges = defaultdict(set)
        self.reasons = defaultdict(set)
        self.positions = defaultdict(list)
        self.uses = defaultdict(set)
        owner_of = {}
        for row in scope["records"]:
            # 穿透规则：function_definition 重置所有者；透明节点继承父所有者。
            key, kind = row["key"], row["kind"]
            owner = owner_of.get(row["parent"])
            if kind == "function_definition":
                owner = None
            if kind in TRANSPARENT:
                owner_of[key] = owner
                continue
            self.positions[row["span"][:2]].append(key)
            owner_of[key] = key
            self.edges[key]  # 无边节点也计入索引。
            if owner is not None:
                self.link(key, owner)
            self.reasons[key].update(self._reasons(row))
            if kind == "identifier":
                binding = row.get("binding", {})
                declaration = binding.get("declaration")
                if binding.get("status") == "bound" and declaration in self.rows:
                    self.link(key, declaration)
                    self.uses[declaration].add(key)
                else:
                    self.reasons[key].add(binding.get("reason") or "usage-unresolved-binding")
                    # 不能把未知绑定当成没有使用；这些边只能阻止排除。
                    for possible in binding.get("possibleDeclarations", ()):
                        if possible in self.rows:
                            self.link(key, possible)
        # 相同作用域的不同根仍独立执行预算查询，但不重复排序或分配同一条边。
        canonical = {tuple(sorted((key, other))) for key, neighbors in self.edges.items()
                     for other in neighbors}
        edge_lookup = {edge: edge for edge in canonical}
        self.adjacency = {key: tuple((other, edge_lookup[tuple(sorted((key, other)))])
                                    for other in sorted(neighbors))
                          for key, neighbors in self.edges.items()}

    def link(self, left, right):
        """建立无向边（自环忽略）；同一对节点多次 link 自动去重（集合语义）。"""
        if left != right:
            self.edges[left].add(right)
            self.edges[right].add(left)

    def _reasons(self, row):
        """按记录类型给出保守原因码；返回空集合表示该语法受支持。

        逃逸类（调用/return/取地址/外部写）与不支持类型各占独立原因码，
        这些原因码最终会把闭包状态拉成 unknown。
        """
        kind = row["kind"]
        if kind not in SUPPORTED:
            return {"usage-unknown-call" if kind == "call_expression" else
                    "usage-return-escape" if kind == "return_statement" else
                    "usage-address-escape" if kind == "pointer_expression" else
                    "usage-external-write" if kind == "field_expression" else "usage-unsupported-syntax"}
        if kind == "binary_expression" and row["operator"] not in {"==", "!=", "<", "<=", ">", ">="}:
            return {"usage-unsupported-operator"}
        if kind == "assignment_expression" and row["operator"] != "=":
            return {"usage-unsupported-operator"}
        if kind == "type_qualifier" and row["text"] != "const":
            return {"usage-unsupported-type"}
        if kind in {"primitive_type", "sized_type_specifier"} and row["text"] not in INTEGERS:
            return {"usage-unsupported-type"}
        if kind == "number_literal" and not re.fullmatch(r"(?:0[xX][0-9a-fA-F]+|[0-9]+)[uUlL]*", row["text"]):
            return {"usage-noninteger-value"}
        if kind == "declaration":
            type_row = self.rows.get(row["fields"].get("type"), {})
            if type_row.get("kind") not in {"primitive_type", "sized_type_specifier"}:
                return {"usage-unsupported-type"}
        if kind == "subscript_expression" and not self._local_array(row):
            return {"usage-array-binding-unknown"}
        return set()

    def _local_array(self, row):
        """判定下标访问的基是否是"定长局部数组"（array_declarator + 正整数字面量长度）。

        只有定长数组下标才被认为是局部可控值流；其余记 usage-array-binding-unknown。
        """
        base = self.rows.get(row["fields"].get("argument"), {})
        declaration = self.rows.get(base.get("binding", {}).get("declarationOwner"), {})
        declarator = self.rows.get(declaration.get("fields", {}).get("declarator"), {})
        size = self.rows.get(declarator.get("fields", {}).get("size"), {})
        return (declarator.get("kind") == "array_declarator" and size.get("kind") == "number_literal"
                and bool(re.fullmatch(r"[1-9][0-9]*", size.get("text", ""))))

    def roots_at(self, span):
        """只使用精确命中开始位置；不凭同名去别处借用绑定。"""
        return tuple(key for key in self.positions.get(tuple(span[:2]), ())
                     if self.rows[key]["kind"] == "identifier")
