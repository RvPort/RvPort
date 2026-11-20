"""Bounded preprocessor values. Missing environment entries are unknown, not zero."""
# 有界预处理求值：不执行任何输入代码，只对预处理表达式做受限的
# 词法分析+递归下降求值。核心不变量：环境中缺失的宏是 unknown（?），
# 绝不当作 0 处理；表达式无法解析或超出预算时整体返回 unknown。
from __future__ import annotations

from dataclasses import dataclass
import re


@dataclass(frozen=True)
class MacroState:
    """单个宏的环境状态（frozen 不可变）。

    defined: 是否已定义（None=未知）；value: 展开文本（None=未知）；
    evidence_id: 最近一次定义/undef 的证据；origins: 聚合的溯源证据
    （分支合并后可能来自多条路径）；function_like: 是否函数式宏。
    """
    defined: bool | None
    value: str | None
    evidence_id: str
    origins: tuple[str, ...] = ()
    function_like: bool = False

    @property
    def evidence_ids(self):
        """去重排序后的全部溯源证据 ID（含 origins，剔除空串）。"""
        return tuple(sorted({self.evidence_id, *self.origins} - {""}))


@dataclass(frozen=True)
class Evaluation:
    """表达式求值结果（frozen 不可变）：值为 None 表示 unknown。

    evidence_ids/names 记录求值时触及的证据与宏名（供上层溯源）；
    reason 非 None 时为放弃求值的原因码。
    """
    value: int | None
    evidence_ids: tuple[str, ...] = ()
    names: tuple[str, ...] = ()
    reason: str | None = None

    @property
    def truth(self):
        """三值真值：value 为 None 时未知，否则转 bool。"""
        return None if self.value is None else bool(self.value)


_TOKEN = re.compile(r"\s*(defined\b|[A-Za-z_]\w*|0[xX][0-9a-fA-F]+|[0-9]+|&&|\|\||==|!=|[!()\-])")
_NAME = re.compile(r"[A-Za-z_]\w*\Z")


def integer(text):
    """按 C 语义解析整数字面量：支持 0x 十六进制与 0 前缀八进制。"""
    digits = text.lstrip("-")
    return int(text, 16 if "x" in digits.lower() else 8 if len(digits) > 1 and digits.startswith("0") else 10)


def _tokens(text, limit):
    """词法分析：把表达式切成 token 列表；出现不支持的字符或超出
    token 预算即抛 ValueError（由 evaluate 捕获转为 unknown）。"""
    result, offset = [], 0
    text = text.strip()
    while offset < len(text):
        match = _TOKEN.match(text, offset)
        if match is None or len(result) >= limit:
            raise ValueError("unsupported-expression-or-budget")
        result.append(match[1])
        offset = match.end()
    return result


def evaluate(text, environment, *, depth_limit=32, token_limit=4096):
    """Expand object aliases at the use site, then parse without eval or execution."""
    # 受限求值入口：先把对象宏别名在使用点展开（函数式宏与未知宏一律
    # 折叠为 "?"），再用递归下降法解析 ||/&&/==/!=/!/括号/整数字面量。
    # 全程不使用 eval、不执行任何代码；环、深度、token 超预算或语法
    # 不支持时返回 value=None 的 Evaluation（含原因码）。
    evidence, names = set(), set()
    consumed = 0

    def state_for(name):
        names.add(name)
        state = environment.get(name)
        if state is not None:
            evidence.update(state.evidence_ids)
        return state

    def expand(tokens, trail=()):
        # 对象宏展开：defined(X) 按环境折叠为 0/1/?；普通标识符在
        # 已定义且有值时递归展开其替换文本（trail 防自引用环），
        # 未定义/未知/函数式宏一律输出 "?" 参与后续三值运算。
        nonlocal consumed
        result, index = [], 0
        while index < len(tokens):
            consumed += 1
            if consumed > token_limit:
                raise ValueError("macro-token-budget")
            token = tokens[index]
            index += 1
            if token == "defined":
                parenthesized = index < len(tokens) and tokens[index] == "("
                index += int(parenthesized)
                if index >= len(tokens) or not _NAME.fullmatch(tokens[index]):
                    raise ValueError("malformed-defined")
                state = state_for(tokens[index])
                index += 1
                if parenthesized:
                    if index >= len(tokens) or tokens[index] != ")":
                        raise ValueError("malformed-defined")
                    index += 1
                result.append("?" if state is None or state.defined is None else str(int(state.defined)))
            elif _NAME.fullmatch(token):
                state = state_for(token)
                if state is None or state.defined is None or state.function_like:
                    result.append("?")
                elif not state.defined:
                    result.append("0")
                elif state.value is None:
                    result.append("?")
                else:
                    if token in trail or len(trail) >= depth_limit:
                        raise ValueError("macro-cycle-or-depth")
                    replacement = _tokens(state.value, token_limit)
                    if not replacement:
                        raise ValueError("empty-object-value")
                    result.extend(expand(replacement, (*trail, token)))
            else:
                result.append(token)
        return result

    try:
        tokens = expand(_tokens(text, token_limit))
        index = 0

        def expression(minimum=0, depth=0):
            # 递归下降解析器：minimum 为优先级门限（|| < && < ==/!= < 一元），
            # 任何含 "?" 的操作数按三值传播（结果未知即 None）。
            nonlocal index
            if index >= len(tokens) or depth > depth_limit:
                raise ValueError("malformed-expression-or-depth")
            token = tokens[index]
            index += 1
            if token == "(":
                left = expression(0, depth + 1)
                if index >= len(tokens) or tokens[index] != ")":
                    raise ValueError("unclosed-expression")
                index += 1
            elif token == "!":
                value = expression(4, depth + 1)
                left = None if value is None else int(not value)
            elif token == "-":
                if index >= len(tokens) or not tokens[index][:1].isdigit():
                    raise ValueError("unsupported-unary-minus")
                left = -integer(tokens[index])
                index += 1
            elif token == "?":
                left = None
            else:
                left = integer(token)
            precedence = {"||": 1, "&&": 2, "==": 3, "!=": 3}
            while index < len(tokens) and precedence.get(tokens[index], -1) >= minimum:
                operator = tokens[index]
                index += 1
                right = expression(precedence[operator] + 1, depth + 1)
                if operator == "&&":
                    left = 0 if left == 0 or right == 0 else None if left is None or right is None else 1
                elif operator == "||":
                    left = 1 if (left is not None and left != 0) or (right is not None and right != 0) else None if left is None or right is None else 0
                else:
                    left = None if left is None or right is None else int((left == right) == (operator == "=="))
            return left

        value = expression()
        if index != len(tokens):
            raise ValueError("trailing-expression-tokens")
        return Evaluation(value, tuple(sorted(evidence)), tuple(sorted(names)))
    except (ValueError, RecursionError) as exc:
        return Evaluation(None, tuple(sorted(evidence)), tuple(sorted(names)), str(exc))


def join(environments):
    """Join feasible arms, preserving every origin; absence is incomplete knowledge."""
    # 分支合并（join）：对 #if 各可行分支的出口环境求"最大共同近似"——
    # 各分支状态完全一致则保留；defined/value 任一分歧即降为 None（未知），
    # 但溯源证据取所有分支并集，保证 provenance 不丢失。
    if len(environments) == 1:
        return dict(environments[0])
    result = {}
    for name in sorted(set().union(*(set(env) for env in environments))):
        states = [env.get(name) for env in environments]
        if all(state is states[0] for state in states):
            result[name] = states[0]
            continue
        known = [state for state in states if state is not None]
        origins = tuple(sorted({identifier for state in known for identifier in state.evidence_ids}))
        defined = known[0].defined if len(known) == len(states) and len({s.defined for s in known}) == 1 else None
        values = {(state.value, state.function_like) for state in known}
        value, function_like = next(iter(values)) if len(known) == len(states) and len(values) == 1 else (None, False)
        result[name] = MacroState(defined, value, origins[0] if origins else "", origins, function_like)
    return result
