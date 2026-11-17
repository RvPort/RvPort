"""对标量语法记录建立保守的块级绑定；不是 C++ 名称解析器。

frontend 阶段 usage_inventory 的配套模块：对 tree-sitter 产出的
标量语法记录做保守的词法作用域绑定——只在同一名字于可见范围内
唯一声明时给出 bound，其余一律 unknown。刻意不做重载/模板/ADL 等
真正的 C++ 名称解析，避免产生不可靠的绑定事实。
"""
from collections import defaultdict


SCOPES = frozenset({"function_definition", "compound_statement", "for_statement",
                    "if_statement", "switch_statement", "while_statement", "catch_clause"})
WRAPPERS = frozenset({"init_declarator", "array_declarator", "pointer_declarator",
                     "reference_declarator", "parenthesized_declarator"})


def bind(records, max_depth=32):
    """输入/输出均为标量；未唯一绑定或深度超限的 identifier 明确 unknown。

    思路分两遍：第一遍按 parent 链计算每个记录的直接作用域（SCOPES
    类型节点即开启新作用域），并收集各作用域内的声明符号表；第二遍
    对每个 identifier 自内向外沿作用域链查找同名声明——恰好一个可见
    候选才 bound，多个候选记入 possibleDeclarations 并标 unknown。
    depth 由 parent 链长度推出，超过 max_depth 的直接放弃绑定。
    """
    rows = {row["key"]: row for row in records}
    declarations, scope_of, scope_parent, depths = {}, {}, {}, {}
    symbols = defaultdict(lambda: defaultdict(list))
    for row in records:
        key, parent = row["key"], row["parent"]
        owner = scope_of.get(parent)
        depths[key] = depths.get(parent, 0) + 1
        if row["kind"] in SCOPES:
            scope_parent[key] = owner
            owner = key
        scope_of[key] = owner
        if row["kind"] != "identifier" or depths[key] > max_depth:
            continue
        declaring = declaration_owner(row, rows)
        if declaring is not None:
            declarations[key] = declaring
            symbols[owner][row["text"]].append(key)
    result = []
    for row in records:
        if row["kind"] != "identifier":
            result.append(dict(row))
            continue
        key, owner = row["key"], scope_of[row["key"]]
        resolved = None
        possible = ()
        if depths[key] <= max_depth:
            # 自内向外沿作用域链查找；item[0] <= key[0] 保证只看源码顺序在前（先声明后使用）的候选。
            cursor = owner
            while cursor is not None:
                visible = [item for item in symbols[cursor].get(row["text"], ()) if item[0] <= key[0]]
                if visible:
                    possible = tuple(visible)
                    resolved = visible[0] if len(visible) == 1 else None
                    break
                cursor = scope_parent.get(cursor)
        parent = rows.get(row["parent"], {})
        # Qualified name 的名字不应退化为同名局部变量；后续可做显式 enum 绑定。
        if parent.get("kind") == "qualified_identifier":
            resolved = None
        if parent.get("kind") == "field_expression" and parent.get("fields", {}).get("argument") != key:
            resolved = None
            if parent.get("fields", {}).get("field") == key:
                # 明确的成员名不是外层同名变量；缺字段信息时仍保留潜在使用。
                possible = ()
        record = dict(row)
        record["binding"] = {"status": "bound" if resolved is not None else "unknown",
            "declaration": resolved, "scope": owner,
            "possibleDeclarations": possible,
            "declarationOwner": declarations.get(resolved),
            "role": "declaration" if key in declarations else _role(row, rows),
            "reason": "usage-binding-depth" if depths[key] > max_depth else
                      "" if resolved is not None else "usage-unresolved-binding"}
        result.append(record)
    return tuple(result)


def declaration_owner(row, rows):
    """只沿 declarator 字段识别声明，不把初始化表达式误认为新声明。"""
    key = row["key"]
    parent = rows.get(row["parent"])
    seen = set()
    while parent is not None and parent["kind"] in WRAPPERS:
        if parent["key"] in seen or parent["fields"].get("declarator") != key:
            return None
        seen.add(parent["key"])
        key, parent = parent["key"], rows.get(parent["parent"])
    if parent is None:
        return None
    if parent["kind"] == "enumerator" and parent["fields"].get("name") == key:
        # scoped enum 不能把成员泄漏到外层词法环境。
        cursor = rows.get(parent["parent"])
        enum = rows.get(cursor["parent"]) if cursor is not None else None
        return parent["key"] if enum is not None and not enum.get("scopedEnum") else None
    if parent["kind"] in {"declaration", "parameter_declaration"}:
        if parent["fields"].get("declarator") == key:
            return parent["key"]
        # Tree-sitter 字段查询只返回第一个 declarator；其他项保守不绑定。
    return None


def _role(row, rows):
    parent = rows.get(row["parent"], {})
    if parent.get("kind") == "assignment_expression" and parent["fields"].get("left") == row["key"]:
        return "write" if parent.get("operator") == "=" else "read_write"
    if parent.get("kind") == "update_expression":
        return "read_write"
    return "read"
