"""Optional scalar hints from a live translation unit, never compiler profiles.

kb-scanner frontend 阶段的 clang enrichment 辅助模块：在 libclang TU
存活期间，为 tree-sitter 基线已定位的函数声明补充可选的标量语义提示
（canonical key、签名、定义位置）。所有字段都是纯标量，不保留 Cursor
等原生对象；编译器画像（compilerProfile）恒为 unknown，绝不冒充事实。
"""
from pathlib import Path

from ...core import SourceLocation, SourceSpan, stable_id
from .vocabulary import make_evidence


def _location(cursor, root, entities):
    """把游标位置换算为工作区相对路径的 SourceLocation；无法落在已注册实体内的返回 None。"""
    location = cursor.location
    if location.file is None or location.line < 1 or location.column < 1:
        return None
    path = Path(str(location.file)).resolve()
    if not path.is_relative_to(root):
        return None
    relative = path.relative_to(root).as_posix()
    if relative not in entities:
        return None
    return SourceLocation(relative, location.line, location.column)


def function_semantics(cursor, root, entities, targets, context_id, producer, parse_has_error):
    """Only materialize exact baseline function-name positions in this context.

    只在游标位置与 tree-sitter 基线给出的函数名锚点 (path, line, column)
    完全一致时产出 function_semantics 证据；canonical_key 为基于
    canonical 位置与签名的稳定ID（SHA-256）。任何可选游标数据不可用
    都降级为 unknown，而不是让基线事实失败。
    """
    if cursor.kind.name != "FUNCTION_DECL" or not targets:
        return None
    location = _location(cursor, root, entities)
    if location is None or (location.line, location.column) not in targets.get(location.path, ()):
        return None
    status, canonical_key, definition_location, signature = "unknown", None, None, ""
    try:
        canonical = cursor.canonical
        canonical_location = _location(canonical, root, entities)
        signature = str(cursor.type.get_canonical().spelling)
        definition = cursor.get_definition()
        definition_location = _location(definition, root, entities) if definition is not None else None
        if canonical_location is not None and signature and not parse_has_error:
            canonical_key = stable_id("function_semantic", 1, context_id, canonical_location, signature)
            status = "complete"
    except (AttributeError, RuntimeError, ValueError):
        # The source baseline survives unavailable/invalid optional cursor data.
        status = "unknown"
    def coordinates(value):
        return {"path": value.path, "line": value.line, "column": value.column} if value is not None else None
    name = str(cursor.spelling or "<unknown>")
    return make_evidence(
        kind="function_semantics", subject_id=entities[location.path].id, value=name,
        producer=producer, parse_mode="clang_enrichment", confidence="medium",
        span=SourceSpan(location, SourceLocation(location.path, location.line, location.column + len(name.encode("utf-8")))),
        context_id=context_id, metadata={"functionSemantics": {
            "bindingStatus": status, "canonicalKey": canonical_key, "signature": signature,
            "definitionLocation": coordinates(definition_location),
            "definitionStatus": "located" if definition_location is not None else "unknown",
            "compilerProfile": "unknown",
        }},
    )
