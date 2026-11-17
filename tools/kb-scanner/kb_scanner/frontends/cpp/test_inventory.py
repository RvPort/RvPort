"""在原生树有效期内复制测试入口及有事实锚点的函数结构。

frontend 阶段 tree-sitter 基线的辅助模块：遍历语法树收集测试相关
函数的结构化记录（声明名、跨度、嵌套体），并对 main 函数或有锚点
（调用/内联汇编/枚举引用）的函数展开局部使用记录。所有输出均为
标量，native Node 不逃逸出本调用。
"""
from bisect import bisect_left
from dataclasses import replace

from ...core import Evidence, stable_id
from .usage_inventory import InventoryBudget, _scope
from .function_inventory import _nested_bodies


def collect(root, source, positions, subject, producer, anchors, *, limit=10000):
    """收集测试内容结构并生成 test_content / test_structure 证据。

    思路：先取锚点（已有 Evidence）的起始位置；栈式遍历整棵树，函数
    定义用二分查找判断是否有锚点落入其跨度；main 或含锚点的函数才
    展开局部使用记录，其余只记名字与跨度。预算（节点/记录上限）耗尽
    时状态降级为 partial 并记录原因码，绝不静默截断。
    """
    if type(limit) is not int or not 0 <= limit <= 10000:
        raise ValueError("test content limit must be an integer between 0 and 10000")
    starts = sorted({(e.location.start.line, e.location.start.column) for e in anchors if e.location})
    if not starts:
        return ()
    functions, reasons, stack, visited, used = [], set(), [(root, False)], 0, 0
    declared = set()
    has_includes = False
    while stack:
        node, unsafe_parent = stack.pop()
        visited += 1
        # 双重预算：文件级节点数（100000）与本文件记录配额 limit，任一超限即停止。
        if visited > 100000 or used >= limit:
            reasons.add("test-content-file-budget")
            if limit < 10000:
                reasons.add("test-content-scan-budget")
            break
        kind = str(node.type)
        has_includes |= kind == "preproc_include"
        if kind == "function_declarator":
            name = node.child_by_field_name("declarator")
            if name is not None:
                declared.add(source[name.start_byte:name.end_byte].decode("utf-8", errors="replace")[:256])
                used += 1
        unsafe = unsafe_parent or kind in {"template_declaration", "class_specifier", "struct_specifier",
                                           "lambda_expression", "namespace_definition"}
        if kind != "function_definition":
            stack.extend((child, unsafe) for child in reversed(node.named_children))
            continue
        decl, body = node.child_by_field_name("declarator"), node.child_by_field_name("body")
        name = decl.child_by_field_name("declarator") if decl is not None else None
        if name is None or body is None:
            reasons.add("test-content-function-shape-unknown")
            continue
        text = source[name.start_byte:name.end_byte].decode("utf-8", errors="replace")
        span = positions.coordinate_span(node.start_byte, node.end_byte)
        # 二分查找：是否存在锚点起始位置落在 [span 起点, span 终点) 内。
        index = bisect_left(starts, span[:2])
        relevant = index < len(starts) and starts[index] < span[2:]
        plain = not unsafe and decl.type == "function_declarator" and name.type == "identifier"
        records, status, issues = (), "complete", ()
        # All names count toward uniqueness; detailed bodies only for main/anchors.
        if text == "main" or relevant:
            scope, _, _ = _scope(node, source, positions, subject, producer,
                InventoryBudget(scope_nodes=max(0, limit-used-1)), unsafe, 100000)
            data = scope[2]["usageScope"]
            records, status, issues = data["records"], data["status"], data["reasonCodes"]
        used += 1 + len(records)
        functions.append({"name": text[:256], "plain": plain and len(text) <= 256,
            "span": span, "nameSpan": positions.coordinate_span(name.start_byte, name.end_byte),
            "bodySpan": positions.coordinate_span(body.start_byte, body.end_byte),
            "nestedSpans": tuple(positions.coordinate_span(*pair) for pair in _nested_bodies(body)),
            "records": records, "status": status, "reasonCodes": issues})
        if status != "complete":
            reasons.update(issues)
    if not functions and not reasons:
        return ()
    location = positions.span(0, len(source))
    data = {"version": 1, "functions": tuple(functions), "declaredNames": tuple(sorted(declared)),
            "hasIncludes": has_includes, "recordCount": used,
            "status": "partial" if reasons else "complete", "reasonCodes": tuple(sorted(reasons))}
    from ...core import SourceLocation, SourceSpan
    structures = []
    for function in functions:
        if not function["records"]:
            continue
        a, b, c, d = function["nameSpan"]
        span = SourceSpan(SourceLocation(positions.path, a, b), SourceLocation(positions.path, c, d))
        identifier = stable_id("test_structure", 1, subject, producer, span)
        function["evidenceId"] = identifier
        structures.append(Evidence(identifier, "test_structure", subject, function["name"], producer,
            "tree_sitter_baseline", "medium", span, metadata={"identityVersion": 1, "testStructure": "function"}))
    return (*structures, Evidence(stable_id("test_content", 1, subject, producer), "test_content", subject,
                     "test-content", producer, "tree_sitter_baseline", "medium", location,
                     metadata={"identityVersion": 1, "testContent": data}),)


def cap_records(item, remaining):
    """扫描级配额裁剪：记录数超预算时清空记录并降级为 partial，原始函数结构保留。"""
    if item.kind != "test_content":
        return item, remaining
    data = item.metadata["testContent"]
    if data["recordCount"] <= remaining:
        return item, remaining-data["recordCount"]
    data = {**data, "functions": (), "recordCount": 0, "status": "partial",
            "reasonCodes": tuple(sorted(set(data["reasonCodes"]) | {"test-content-scan-budget"}))}
    return replace(item, metadata={**item.metadata, "testContent": data}), 0
