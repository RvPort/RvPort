"""Make 参数子集的不可变 token 来源和展开时机，不执行任何函数。"""

from dataclasses import dataclass, replace
import re


REF = re.compile(r'\$\(([A-Za-z_]\w*)\)|\$\{([A-Za-z_]\w*)\}')


@dataclass(frozen=True)
class Token:
    """带溯源的字面 token（frozen 不可变）。

    text 为 token 文本；path/line/column/end 为 1-based 字节位置；
    via 记录展开路径上经过的引用点，origins 记录合并前的来源位置。
    """

    text: str
    path: str
    line: int
    column: int
    end: int
    via: tuple = ()
    origins: tuple = ()

    @property
    def source(self):
        """(path, line, column, end) 四元组，作为唯一溯源坐标。"""
        return self.path, self.line, self.column, self.end


@dataclass(frozen=True)
class Variable:
    """Make 变量的求值状态：tokens 为 None 表示值未知；immediate 表示 := 立即展开。"""

    tokens: tuple | None
    immediate: bool = False


def tokens(text, path, line, offset=0):
    """按空白切分文本为 Token 元组，offset 用于把片段坐标换算回整行坐标。"""
    return tuple(Token(m.group(), path, line, offset+m.start()+1, offset+m.end()+1)
                 for m in re.finditer(r'\S+', text))


def expand(items, env, *, automatic=None, depth=32, limit=256, trail=()):
    """递归展开 token 序列，返回展开后的 Token 元组；无法有界展开时返回 None。

    算法：单个 token 若是 $(VAR)/${VAR} 引用则递归展开其值并记录
    via 溯源；自动变量（如 $@、$<）用 automatic 表替换；含 $、@、
    shell 元字符的 token 视为不可静态求值，整体放弃。防环靠 trail
    记录展开链，防爆炸靠 depth 与 token 数 limit。
    """
    if items is None or len(trail) >= depth:
        return None
    result = []
    for token in items:
        match = REF.fullmatch(token.text)
        if match:
            name = match[1] or match[2]
            value = env.get(name)
            if value is None or name in trail:
                return None
            nested = value.tokens if value.immediate else expand(value.tokens, env, automatic=automatic,
                depth=depth, limit=limit, trail=(*trail,name))
            if nested is None:
                return None
            result.extend(replace(t, via=(*t.via, token.source)) for t in nested)
        elif token.text in (automatic or {}):
            result.append(replace(token, text=automatic[token.text]))
        elif '$' in token.text or token.text.startswith('@') or any(c in token.text for c in '`;&|<>\\"\''):
            return None
        else:
            result.append(token)
        if len(result) > limit:
            return None
    return tuple(result)


def merge_environments(environments):
    """合并多个条件分支各自求值出的变量环境。

    同名变量在所有环境中 text/immediate/展开性完全一致时保留值并
    合并 origins/via 溯源；任一分支为 None 或不一致时降级为
    Variable(None)（值未知）。
    """
    result = {}
    for name in sorted(set().union(*(env.keys() for env in environments))):
        values = [env.get(name) for env in environments]
        first = values[0]
        if first is None or first.tokens is None or any(value is None or value.tokens is None
                or value.immediate != first.immediate
                or tuple(t.text for t in value.tokens) != tuple(t.text for t in first.tokens) for value in values):
            result[name] = Variable(None)
            continue
        joined = []
        for index, token in enumerate(first.tokens):
            sources = {origin for value in values for origin in
                       (value.tokens[index].source, *value.tokens[index].origins)}
            via = {origin for value in values for origin in value.tokens[index].via}
            joined.append(replace(token, origins=tuple(sorted(sources)), via=tuple(sorted(via))))
        result[name] = Variable(tuple(joined), first.immediate)
    return result
