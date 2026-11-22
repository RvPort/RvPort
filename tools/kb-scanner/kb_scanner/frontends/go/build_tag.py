"""Go build tag 表达式三值求值器（`//go:build` 构建约束）。

语法（Go build constraint 子集）：`expr`, `!expr`, `expr && expr`,
`expr || expr`, 括号分组, 裸 tag 标识符（GOOS/GOARCH 词形）。

三值语义与 Rust cfg 求值器一致（True/False/None=unknown）：
- 架构 tag（amd64/386/x86 等）→ False（非 riscv 架构形态）
- riscv64/riscv → True
- 其他 tag（linux/darwin/unix/cgo 等非架构词）→ None（不参与架构判定）
- ! 对 default 取反；|| 子项缺省 False；&& 子项缺省 True（Kleene）

解析失败返回 None（五态兜底，不抛）。
"""
from __future__ import annotations

import re
from typing import Optional

TriValue = Optional[bool]

_X86_TAGS = {"amd64", "386", "x86", "x86_64", "amd64p32"}
_ARM_TAGS = {"arm", "arm64", "arm64be", "armbe"}
_RISCV_TAGS = {"riscv64", "riscv"}
# 其他常见 GOARCH（mips 系/ppc 系/s390x/wasm/loong64）也判 False：
# build tag 的架构取值是闭集，非 riscv 即"非目标"。
_KNOWN_OTHER_ARCH = {"mips", "mipsle", "mips64", "mips64le", "mips64p32", "mips64p32le",
                     "ppc", "ppc64", "ppc64le", "s390x", "s390", "wasm", "loong64",
                     "riscv32", *_X86_TAGS, *_ARM_TAGS}

_TOKEN_RE = re.compile(r"\s*(?:(?P<tag>[A-Za-z0-9_][A-Za-z0-9_.]*)|(?P<not>!)|(?P<and>&&)|(?P<or>\|\|)|(?P<lparen>\()|(?P<rparen>\)))")


def evaluate_build_tag(expression: str) -> TriValue:
    """求值一条 //go:build 表达式；解析失败返回 None。"""
    tokens = _tokenize(expression)
    if tokens is None:
        return None
    parser = _Parser(tokens)
    try:
        value = parser.parse()
        if parser.peek() is not None:
            return None
        return value
    except ValueError:
        return None


def _tokenize(text: str) -> list[tuple[str, str]] | None:
    tokens: list[tuple[str, str]] = []
    pos = 0
    while pos < len(text):
        match = _TOKEN_RE.match(text, pos)
        if match is None:
            if text[pos:].strip() == "":
                break
            return None
        pos = match.end()
        for kind in ("tag", "not", "and", "or", "lparen", "rparen"):
            value = match.group(kind)
            if value is not None:
                tokens.append((kind, value))
                break
    return tokens


def _tag_value(tag: str) -> TriValue:
    """单个 tag 的三值：riscv 系 True；已知其他架构 False；非架构词 None。"""
    if tag in _RISCV_TAGS:
        return True
    if tag in _KNOWN_OTHER_ARCH:
        return False
    return None


class _Parser:
    """build tag 子集的递归下降（|| 低优先级、&& 高、! 一元、括号）。"""

    def __init__(self, tokens: list[tuple[str, str]]) -> None:
        self._tokens = tokens
        self._index = 0

    def peek(self) -> tuple[str, str] | None:
        return self._tokens[self._index] if self._index < len(self._tokens) else None

    def _take(self) -> tuple[str, str] | None:
        token = self.peek()
        if token is not None:
            self._index += 1
        return token

    def parse(self) -> TriValue:
        return self._or()

    def _or(self) -> TriValue:
        value = self._and()
        while (token := self.peek()) is not None and token[0] == "or":
            self._take()
            right = self._and()
            value = _or_fold(value, right)
        return value

    def _and(self) -> TriValue:
        value = self._unary()
        while (token := self.peek()) is not None and token[0] == "and":
            self._take()
            right = self._unary()
            value = _and_fold(value, right)
        return value

    def _unary(self) -> TriValue:
        token = self._take()
        if token is None:
            raise ValueError("unexpected end of build tag")
        kind, value = token
        if kind == "not":
            inner = self._unary()
            return None if inner is None else not inner
        if kind == "lparen":
            inner = self._or()
            closing = self._take()
            if closing is None or closing[0] != "rparen":
                raise ValueError("expected ) in build tag")
            return inner
        if kind == "tag":
            return _tag_value(value)
        raise ValueError(f"unexpected token in build tag: {kind}")


def _or_fold(left: TriValue, right: TriValue) -> TriValue:
    """|| 的 Kleene 折叠：任一 True → True；两者 False → False；否则 None。"""
    if left is True or right is True:
        return True
    if left is False and right is False:
        return False
    return None


def _and_fold(left: TriValue, right: TriValue) -> TriValue:
    """&& 的 Kleene 折叠：任一 False → False；两者 True → True；否则 None。"""
    if left is False or right is False:
        return False
    if left is True and right is True:
        return True
    return None
