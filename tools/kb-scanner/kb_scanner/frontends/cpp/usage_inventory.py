"""在 native tree 生命周期内提取有界、只含标量的局部使用语法。

这里只采集结构，不凭名称决定用途，不生成迁移结论。

frontend 阶段 tree-sitter 基线的局部使用清单：对包含锚点（枚举/
宏展开等已有证据）的函数体做全量语法记录，交给 usage_bindings 做
保守绑定后封装为 usage_scope 证据。预算耗尽时降级为 partial 并给出
原因码，绝不静默丢节点。
"""
from dataclasses import dataclass, replace
import re

from ...core import Evidence, stable_id
from ...core.metadata import freeze_shared_metadata
from .usage_bindings import bind
from .vocabulary import IDENTITY_VERSION


FIELDS = ("name", "type", "declarator", "parameters", "body", "value",
          "left", "right", "argument", "index", "indices", "size", "condition",
          "consequence", "alternative", "function", "arguments", "scope", "field")
TEXT_KINDS = frozenset({"identifier", "type_identifier", "primitive_type",
                       "sized_type_specifier", "number_literal", "char_literal",
                       "string_literal", "true", "false", "null", "type_qualifier"})


@dataclass(frozen=True)
class InventoryBudget:
    """使用清单的三重预算：文件节点数 / 单作用域节点数 / token 截取字节数。

    不可变（frozen），且各值都有硬上限（100000 / 10000 / 256），
    超过直接抛 ValueError——防止调用方用大预算绕过资源限制。
    """

    file_nodes: int = 100000
    scope_nodes: int = 10000
    token_bytes: int = 256

    def __post_init__(self):
        if any(type(value) is not int or value < 0 for value in vars(self).values()):
            raise ValueError("usage inventory budgets must be nonnegative integers")
        if self.file_nodes > 100000 or self.scope_nodes > 10000 or self.token_bytes > 256:
            raise ValueError("usage inventory budgets exceed hard limits")


def _key(node):
    """节点的标量键：(起始字节, 结束字节, 节点类型)，不保留 native 对象。"""
    return (int(node.start_byte), int(node.end_byte), str(node.type))


def cap_records(item, remaining):
    """扫描级记录预算只裁剪新增 usage 记录，原始事实不变。"""
    if item.kind != "usage_scope":
        return item, remaining
    scope = item.metadata["usageScope"]
    count = scope["recordCount"]
    if count <= remaining:
        return item, remaining-count
    scope = {**scope, "records": (), "recordCount": 0, "status": "partial",
             "reasonCodes": tuple(sorted(set(scope["reasonCodes"]) | {"usage-scan-record-budget"}))}
    return replace(item, metadata={**item.metadata, "usageScope": scope}), 0


def collect(root, source, positions, subject_id, producer, anchors, budget=None):
    """anchors 为既有 Evidence；只采集包含锚点的函数，返回新的 Evidence。

    调用者保留旧事实原样。全部节点，包括未支持语法，都有记录或截断原因，
    因而后续白名单不能把遗漏语句当作不存在。

    思路：先取锚点起始位置并排序；栈式遍历文件树（预算受限），遇
    function_definition 用二分查找判断是否有锚点落入其区间，命中则调
    _scope 展开该函数体。文件预算触发时统一把所有已收集作用域降级为
    partial（延迟冻结，避免重复复制记录）。
    """
    budget = budget or InventoryBudget()
    anchor_positions = sorted({(e.location.start.line, e.location.start.column)
        for e in anchors if e.location is not None})
    if not anchor_positions:
        return ()
    from bisect import bisect_left
    stack, scopes, visited = [(root, False)], [], 0
    file_truncated = False
    while stack:
        node, unsupported_parent = stack.pop()
        visited += 1
        if visited > budget.file_nodes:
            file_truncated = True
            break
        nested = unsupported_parent or node.type in {
            "template_declaration", "lambda_expression", "class_specifier", "struct_specifier"
        } or str(node.type).startswith("preproc_")
        if node.type == "function_definition":
            span = positions.span(int(node.start_byte), int(node.end_byte))
            start = (span.start.line, span.start.column)
            end = (span.end.line, span.end.column)
            index = bisect_left(anchor_positions, start)
            if index < len(anchor_positions) and anchor_positions[index] < end:
                scope, consumed, limited = _scope(node, source, positions, subject_id,
                    producer, budget, nested, budget.file_nodes - visited + 1)
                scopes.append(scope)
                visited += consumed - 1
                if limited:
                    file_truncated = True
                    break
            # 内嵌函数/方法只作为当前函数中的不支持语法记录，不能另造完整闭包。
            continue
        stack.extend((child, nested) for child in reversed(node.named_children))
    result = []
    for scope_id, location, metadata in scopes:
        # 文件遍历结束后才冻结公开 DTO，避免文件预算触发时复制、重新冻结全部记录。
        # 待定结构只含标量，未对外发布，也不保存 native Node/Tree/Point。
        if file_truncated:
            scope = metadata["usageScope"]
            scope["status"] = "partial"
            scope["reasonCodes"] = tuple(sorted(set(scope["reasonCodes"]) | {"usage-file-budget"}))
        metadata["usageScope"] = freeze_shared_metadata(metadata["usageScope"])
        result.append(Evidence(scope_id, "usage_scope", subject_id, "local-use-scope", producer,
            "tree_sitter_baseline", "medium", location, metadata=metadata))
    return tuple(result)


def _scope(root, source, positions, subject, producer, budget, unsupported_parent, file_remaining):
    """展开单个函数体的语法记录：DFS 收集标量行 → bind 做保守绑定 → 组装 usageScope 元数据。

    返回 (scope_id, location, metadata), consumed, limited；consumed 为
    消耗的节点数（计入文件预算），limited 表示是否因文件预算截断。
    enum_specifier 额外记录 scopedEnum（enum class/struct）供绑定阶段
    判断成员是否泄漏到外层词法环境。
    """
    stack, rows, reasons = [(root, None)], [], set()
    consumed = 0
    if unsupported_parent:
        reasons.add("usage-unsupported-owner")
    while stack:
        if consumed >= file_remaining:
            reasons.add("usage-file-budget")
            break
        node, parent = stack.pop()
        consumed += 1
        if len(rows) >= budget.scope_nodes:
            reasons.add("usage-scope-budget")
            break
        start, end, kind = _key(node)
        if not 0 <= start <= end <= len(source):
            reasons.add("usage-invalid-range")
            continue
        key = start, end, kind
        text = ""
        if kind in TEXT_KINDS:
            if end - start > budget.token_bytes:
                reasons.add("usage-token-budget")
            text = source[start:min(end, start + budget.token_bytes)].decode("utf-8", errors="replace")
        if node.is_error or node.is_missing:
            reasons.add("usage-partial-parse")
        operator = node.child_by_field_name("operator")
        fields = {name: _key(child) for name in FIELDS
                  if (child := node.child_by_field_name(name)) is not None}
        span = positions.coordinate_span(start, end)
        rows.append({"key": key, "parent": parent, "kind": kind, "fields": fields,
            "children": tuple(_key(child) for child in node.named_children), "text": text,
            "operator": source[operator.start_byte:operator.end_byte].decode("utf-8", errors="replace") if operator is not None else "",
            "span": span})
        if kind == "enum_specifier":
            rows[-1]["scopedEnum"] = bool(re.match(rb"enum\s+(?:class|struct)\b", source[start:min(end, start+64)]))
        stack.extend((child, key) for child in reversed(node.named_children))
    location = positions.span(int(root.start_byte), int(root.end_byte))
    bound_rows = bind(rows)
    if any(row.get("binding", {}).get("reason") == "usage-binding-depth" for row in bound_rows):
        reasons.add("usage-binding-depth")
    scope_id = stable_id("usage_scope", IDENTITY_VERSION, subject, location, producer)
    metadata = {"identityVersion": IDENTITY_VERSION, "usageScope": {
            "version": 1, "status": "partial" if reasons else "complete",
            "reasonCodes": tuple(sorted(reasons)), "records": bound_rows,
            "recordCount": len(rows), "scopeLimit": budget.scope_nodes,
        }}
    return (scope_id, location, metadata), consumed, "usage-file-budget" in reasons
