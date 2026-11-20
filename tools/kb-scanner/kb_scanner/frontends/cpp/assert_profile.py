"""复制已解析 TU 中的标准 assert 定义；不把主机配置当作 RISC-V 配置。

kb-scanner frontend 阶段的 clang enrichment 辅助模块：仅当宏定义与已知
glibc assert 展开体逐 token 匹配时才记录 profile，其余情况一律不产出，
避免把构建主机的 assert 配置误标为目标机事实。
"""
from itertools import islice
from pathlib import Path


# Exact, bounded glibc assert macro profiles. Other libraries/versions remain
# unknown; a header basename or a call named assert is never sufficient.
_BODIES = {
    "( __ASSERT_VOID_CAST ( 0 ) )": "disabled",
    "( ( expr ) ? __ASSERT_VOID_CAST ( 0 ) : __assert_fail ( # expr , __FILE__ , __LINE__ , __ASSERT_FUNCTION ) )": "enabled",
    "( static_cast < bool > ( expr ) ? void ( 0 ) : __assert_fail ( # expr , __ASSERT_FILE , __ASSERT_LINE , __ASSERT_FUNCTION ) )": "enabled",
    "( static_cast < bool > ( expr ) ? void ( 0 ) : __assert_fail ( # expr , __FILE__ , __LINE__ , __ASSERT_FUNCTION ) )": "enabled",
    "( ( void ) sizeof ( ( expr ) ? 1 : 0 ) , __extension__ ( { if ( expr ) ; else __assert_fail ( # expr , __FILE__ , __LINE__ , __ASSERT_FUNCTION ) ; } ) )": "enabled",
}


def capture(cursor, context_id, has_error):
    """尝试从 assert 宏实例化游标复制标准 assert profile。

    仅当游标是 assert 宏展开、其定义位于系统头 assert.h、且定义体与
    _BODIES 中某个已知 glibc 展开体逐 token 一致时才返回元数据；
    解析出错时状态降级为 unknown。任何不满足条件的情况都返回空 dict。
    """
    if cursor.spelling != "assert" or cursor.kind.name not in {"MACRO_INSTANTIATION", "MACRO_EXPANSION"}:
        return {}
    try:
        definition = cursor.referenced
        location = definition.location
        if not location.is_in_system_header or Path(str(location.file)).name != "assert.h":
            return {}
        tokens = tuple(str(t.spelling) for t in islice(definition.get_tokens(), 257)
                       if not str(t.spelling).startswith(("/*", "//")))
        status = _BODIES.get(" ".join(tokens[4:])) if tokens[:4] == ("assert", "(", "expr", ")") else None
        if status is None:
            return {}
        return {"standardAssert": {"profile": "glibc-assert-v1", "header": "assert.h",
                "definitionLine": location.line, "configuration": context_id,
                "status": "unknown" if has_error else status, "scope": "observed-compilation-unit"}}
    except (AttributeError, RuntimeError, ValueError):
        return {}
