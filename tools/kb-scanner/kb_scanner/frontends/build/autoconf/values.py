"""Small shell value domain. None is unknown, never the empty string.

Autoconf 值域：Value.text 为 None 表示"未知"，与空字符串严格区分；
所有求值保持这一约定，未知会沿组合向上传播。
"""
from __future__ import annotations

from dataclasses import dataclass
import fnmatch
import re

from .syntax import Node, Source


@dataclass(frozen=True, slots=True)
class Value:
    """求值结果（frozen 不可变）：text 为 None 即未知，reason 记录原因码。"""

    text: str | None
    reason: str = ""


UNKNOWN = Value(None, "autoconf-value-unknown")


def value(source: Source, node: Node | None, env: dict[str, Value], substitute=None) -> Value:
    """对语法节点做纯求值（无副作用）：字面量/变量展开/拼接。

    覆盖 word/number/字符串/裸字符串/简单展开/${...} 截断展开/命令
    替换（委托 substitute 回调）/拼接；未覆盖的节点类型返回 UNKNOWN。
    转义符或 NUL 字节视为未知。
    """
    if node is None:
        return Value("")
    raw = source.text(node)
    if node.kind in {"word", "number", "string_content", "variable_name", "extglob_pattern"}:
        return UNKNOWN if "\\" in raw or "\x00" in raw else Value(raw)
    if node.kind == "raw_string":
        return Value(raw[1:-1])
    if node.kind == "simple_expansion":
        return env.get(raw[1:], UNKNOWN)
    if node.kind == "expansion":
        match = re.fullmatch(r"\$\{([\w]+)(?:(%%|%|##|#)([^{}$`\\]*))?\}", raw)
        if not match:
            return UNKNOWN
        original = env.get(match[1], UNKNOWN)
        if original.text is None or not match[2]:
            return original
        return Value(trim(original.text, match[2], match[3]))
    if node.kind == "command_substitution":
        return substitute(node) if substitute is not None else UNKNOWN
    if node.kind in {"string", "concatenation", "command_name"}:
        parts = [value(source, child, env, substitute) for _, child in node.children
                 if child.kind not in {'"', "'"}]
        if any(part.text is None for part in parts):
            return next(part for part in parts if part.text is None)
        return Value("".join(part.text for part in parts))
    return UNKNOWN


def trim(text: str, operator: str, pattern: str) -> str:
    """实现 shell ${var#pat}/${var%pat} 前后缀删除（##/%% 为贪婪版本）。

    思路：按候选长度枚举前缀（或后缀），用 fnmatch 匹配模式；单字符
    操作符取最短匹配先返回，双字符（贪婪）从最长开始尝试。
    """
    sizes = range(len(text) + 1)
    if len(operator) == 2:
        sizes = reversed(sizes)
    for size in sizes:
        prefix = operator.startswith("#")
        part = text[:size] if prefix else text[len(text) - size:]
        if fnmatch.fnmatchcase(part, pattern):
            return text[size:] if prefix else text[:len(text) - size]
    return text


def pattern_match(text: str | None, pattern: str | None) -> bool | None:
    """case 模式匹配：仅支持安全 fnmatch 子集；extglob/转义等返回 None。"""
    if pattern is None or re.search(r"[!+@?*]\(|\\|\[[:.]", pattern):
        return None
    if pattern == "*":
        return True
    return None if text is None else fnmatch.fnmatchcase(text, pattern)


def triplet(text: str | None, target: str) -> bool:
    """校验 config.guess 输出是否是 target 的合法三元组（target-vendor-os）。"""
    return bool(text and re.fullmatch(re.escape(target) + r"-[A-Za-z0-9_.]+-[A-Za-z0-9_.+-]+", text))
