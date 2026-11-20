"""cfg 表达式三值求值器：Rust `#[cfg(...)]` / `[target.'cfg(...)']` 的布尔语义。

三值语义（True/False/None=unknown）与 riscv-scan 的 Kleene 风格 default
传递一致，但 unknown 不被二值化——证据携带求值结果，分类器按五态决定影响：
- target_arch = "riscv64" / "riscv32"  → True（目标支持）
- target_arch = 其他字面量            → False
- target_feature 含源架构 feature      → False（下游知识匹配）
- 其余键/裸标识符                      → None（unknown）
- not(e)  对 default 取反；any(...) 子项缺省 False；all(...) 子项缺省 True

语法（cfg 核心子集）：`any(...)`, `all(...)`, `not(...)`, `key = "value"`,
裸标识符。未知形态返回 None（解析失败不抛——五态兜底）。
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Optional

# 三值：True/False/None（unknown）。public alias 保持可读。
TriValue = Optional[bool]

_TARGET_ARCH_RISCV = {"riscv64", "riscv32"}

_TOKEN_RE = re.compile(r'\s*(?:(?P<string>"(?:[^"\\]|\\.)*")|(?P<ident>[A-Za-z_][A-Za-z0-9_]*)|(?P<eq>=)|(?P<comma>,)|(?P<lparen>\()|(?P<rparen>\)))')


class _Tokenizer:
    """cfg 表达式词法：字符串字面量/标识符/=/,/(/)。"""

    def __init__(self, text: str) -> None:
        self.tokens: list[tuple[str, str]] = []
        pos = 0
        while pos < len(text):
            match = _TOKEN_RE.match(text, pos)
            if match is None:
                if text[pos:].strip() == "":
                    break
                raise ValueError(f"unexpected cfg token at {pos}")
            pos = match.end()
            for kind in ("string", "ident", "eq", "comma", "lparen", "rparen"):
                value = match.group(kind)
                if value is not None:
                    self.tokens.append((kind, value if kind != "string" else value[1:-1]))
                    break
        self.index = 0

    def peek(self) -> tuple[str, str] | None:
        return self.tokens[self.index] if self.index < len(self.tokens) else None

    def take(self) -> tuple[str, str] | None:
        token = self.peek()
        if token is not None:
            self.index += 1
        return token


@dataclass(frozen=True, slots=True)
class CfgEvaluation:
    """一次 cfg 求值结果：三值 + 命中的 target_arch 值（若有）。"""

    value: TriValue
    target_arch: str | None = None


class _Parser:
    """cfg 核心子集的递归下降解析 + 三值求值（一次遍历完成）。"""

    def __init__(self, tokens: _Tokenizer) -> None:
        self._tokens = tokens

    def _expect(self, kind: str) -> tuple[str, str]:
        token = self._tokens.take()
        if token is None or token[0] != kind:
            raise ValueError(f"expected {kind} in cfg expression")
        return token

    def parse(self) -> CfgEvaluation:
        result = self._expr()
        if self._tokens.peek() is not None:
            raise ValueError("trailing cfg tokens")
        return result

    def _expr(self) -> CfgEvaluation:
        token = self._tokens.take()
        if token is None:
            raise ValueError("empty cfg expression")
        kind, value = token
        if kind != "ident":
            raise ValueError("cfg expression must start with an identifier")
        if value == "not":
            self._expect("lparen")
            inner = self._expr()
            self._expect("rparen")
            flipped = None if inner.value is None else not inner.value
            return CfgEvaluation(flipped, inner.target_arch)
        if value in {"any", "all"}:
            self._expect("lparen")
            items: list[CfgEvaluation] = []
            if self._tokens.peek() is not None and self._tokens.peek()[0] == "rparen":
                self._tokens.take()
            else:
                while True:
                    items.append(self._expr())
                    nxt = self._tokens.take()
                    if nxt is None or nxt[0] == "rparen":
                        break
                    if nxt[0] != "comma":
                        raise ValueError("expected , or ) in cfg list")
            return _fold(value, items)
        # key = "value" 对；裸标识符（如 unix/windows）→ unknown。
        if self._tokens.peek() is not None and self._tokens.peek()[0] == "eq":
            self._tokens.take()
            value_token = self._tokens.take()
            if value_token is None or value_token[0] != "string":
                raise ValueError("cfg key requires a string value")
            return _predicate(value, value_token[1])
        return CfgEvaluation(None)


def _fold(op: str, items: list[CfgEvaluation]) -> CfgEvaluation:
    """any/all 的三值折叠：any 子项缺省 False、all 子项缺省 True（Kleene）。"""
    if op == "any":
        value: TriValue = False
        for item in items:
            if item.value is True:
                return CfgEvaluation(True, item.target_arch)
            if item.value is None:
                value = None
        return CfgEvaluation(value)
    value = True
    for item in items:
        if item.value is False:
            return CfgEvaluation(False, item.target_arch)
        if item.value is None:
            value = None
    return CfgEvaluation(value)


def _predicate(key: str, value: str) -> CfgEvaluation:
    """单条谓词：target_arch 二值（riscv 系 True，其余架构字面量 False——
    cfg 的架构取值是闭集）；其他键一律 unknown（feature 语义留给下游知识
    匹配层）。"""
    if key == "target_arch":
        return CfgEvaluation(value in _TARGET_ARCH_RISCV, value)
    return CfgEvaluation(None)


def evaluate_cfg(expression: str) -> CfgEvaluation:
    """求值一条 cfg 表达式；解析失败返回 unknown（不抛异常）。"""
    try:
        return _Parser(_Tokenizer(expression)).parse()
    except ValueError:
        return CfgEvaluation(None)
