"""Three-valued expressions. Unsupported syntax never becomes false.

CMake 三值表达式层：真值恒为 True/False/None（unknown），不支持的
语法一律归为 None，绝不臆断为 False。literal/expand 负责取字面量与
变量展开，truth/matches/condition 实现 if() 条件的有界求值。
"""

import re


def literal(token):
    """提取 token 的字面文本：去引号/括号参数包裹；含转义或生成器表达式返回 None。"""
    text = token.text
    if text.startswith('"') and text.endswith('"'):
        text = text[1:-1]
    elif text.startswith("["):
        match = re.fullmatch(r"\[(=*)\[(.*?)\]\1\]", text, re.S)
        if not match:
            return None
        text = match[2]
    if "\\" in text or "$ENV{" in text or "$CACHE{" in text or "$<" in text:
        return None
    return text


def expand(token, variables, *, max_length=8192):
    """展开 token 中的 ${VAR} 引用：缺变量或残留 $ 等情况返回 None。"""
    text = literal(token)
    if text is None:
        return None
    if token.text.startswith("["):
        return text if len(text) <= max_length else None
    missing = False
    def replace(match):
        nonlocal missing
        value = variables.get(match[1])
        if value is None:
            missing = True
            return ""
        return value
    text = re.sub(r"\$\{([A-Za-z_]\w*)\}", replace, text)
    return None if missing or re.search(r"\$[<{]", text) or len(text) > max_length else text


def negate(value):
    """三值取反：None 传播。"""
    return None if value is None else not value


def both(a, b):
    """三值 AND：任一 False→False，任一 None→None，否则 True。"""
    return False if a is False or b is False else None if a is None or b is None else True


def either(a, b):
    """三值 OR：任一 True→True，任一 None→None，否则 False。"""
    return True if a is True or b is True else None if a is None or b is None else False


def truth(value):
    """按 CMake 真值常量判定字符串真伪（1/ON/YES/... 为真）；不认识的形态返回 None。"""
    if value is None:
        return None
    upper = value.upper()
    if upper in {"1", "ON", "YES", "TRUE", "Y"}:
        return True
    if upper in {"", "0", "OFF", "NO", "FALSE", "N", "IGNORE", "NOTFOUND"} or upper.endswith("-NOTFOUND"):
        return False
    if re.fullmatch(r"-?\d+", value):
        return int(value) != 0
    return None


def matches(value, pattern):
    # Deliberately support only bounded literal alternatives and optional anchors.
    # No untrusted regex is compiled: no repetitions, classes or nested groups.
    if value is None or pattern is None or max(len(value), len(pattern)) > 512:
        return None
    left, right = pattern.startswith("^"), pattern.endswith("$")
    body = pattern[1:] if left else pattern
    body = body[:-1] if right else body
    if body.startswith("(") and body.endswith(")"):
        body = body[1:-1]
    if not re.fullmatch(r"[A-Za-z0-9_./+ -]+(?:\|[A-Za-z0-9_./+ -]+)*", body):
        return None
    # Dot and plus would be regex operators, not literal characters.
    if "." in body or "+" in body:
        return None
    return any(value == item if left and right else value.startswith(item) if left else
               value.endswith(item) if right else item in value for item in body.split("|"))


def condition(tokens, variables):
    if len(tokens) > 256:
        return None
    position = 0
    def operand(token):
        value = expand(token, variables)
        return variables.get(value, value) if not token.quoted and value is not None else value
    def atom(depth=0):
        nonlocal position
        if depth > 32 or position >= len(tokens):
            raise ValueError("cmake-condition-shape")
        token = tokens[position]
        position += 1
        if token.text == "NOT":
            return negate(atom(depth+1))
        if token.text == "(":
            result = expression(depth+1)
            if position >= len(tokens) or tokens[position].text != ")":
                raise ValueError("cmake-condition-parenthesis")
            position += 1
            return result
        value = operand(token)
        if position < len(tokens) and tokens[position].text in {"STREQUAL", "MATCHES"}:
            operator = tokens[position].text
            position += 1
            if position >= len(tokens):
                raise ValueError("cmake-condition-operand")
            other = operand(tokens[position])
            position += 1
            return matches(value, other) if operator == "MATCHES" else None if value is None or other is None else value == other
        return truth(value)
    def expression(depth=0):
        nonlocal position
        value = atom(depth)
        while position < len(tokens) and tokens[position].text in {"AND", "OR"}:
            operator = tokens[position].text
            position += 1
            other = atom(depth)
            value = both(value, other) if operator == "AND" else either(value, other)
        return value
    try:
        result = expression()
        return result if position == len(tokens) else None
    except (ValueError, RecursionError):
        return None
