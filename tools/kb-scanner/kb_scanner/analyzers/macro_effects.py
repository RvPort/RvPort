"""Demand-driven fixed-argument macro forwarding; terminal tokens stay original."""
# 宏效果索引与展开（analyzer 阶段）：按需（demand-driven）做固定参数
# 宏的转发展开，产出"调用点→…→终端 token"的证据路径。
# 终端 token（intrinsic/汇编等知识符号）始终保留原始出处，不做改写。
from collections import defaultdict
from dataclasses import dataclass
import re


@dataclass(frozen=True)
class Token:
    """展开过程中的 token（frozen 不可变）。

    forwarding 记录该 token 作为实参被逐层转发时途经的
    （形参证据, 替换点证据）链，用于构造完整溯源路径。
    """
    value: str
    evidence_id: str | None
    forwarding: tuple[str, ...] = ()


@dataclass(frozen=True)
class Effects:
    """一次宏调用展开的结果（frozen 不可变）。

    paths: 终端证据路径集合（每个元素为证据 ID 元组）；reasons:
    放弃/降级原因码；expanded_tokens: 消耗的 token 计数（预算审计）。
    有 reasons 时 paths 为空——截断的展开不能证明任何终端。
    """
    paths: tuple[tuple[str, ...], ...]
    reasons: tuple[str, ...]
    expanded_tokens: int


def arguments(tokens, start):
    """从 start 起解析实参列表（顶层逗号分隔、括号深度为 0 处切分）。

    返回 (各实参的 token 元组元组, 结束下标)；找不到配对右括号返回
    (None, len(tokens)) 表示畸形。
    """
    result, current, depth = [], [], 0
    for index in range(start, len(tokens)):
        token = tokens[index]
        if token.value == ')' and depth == 0:
            if current or result:
                result.append(tuple(current))
            return tuple(result), index + 1
        if token.value == ',' and depth == 0:
            result.append(tuple(current))
            current = []
        else:
            depth += 1 if token.value == '(' else -1 if token.value == ')' else 0
            current.append(token)
    return None, len(tokens)


class MacroEffectIndex:
    """宏效果索引：预建宏定义/形参/替换 token 的查询表，并筛选"按需"调用。

    demand_symbols 给定时，从知识符号（含对象别名与作为实参传入的
    包装名）反向可达的调用才进入 demanded_calls；不给时全部调用入选。
    索引只用于保守筛选查询，绝不充当展开证明。
    """
    def __init__(self, evidence, demand_symbols=None):
        """evidence: 全量证据；demand_symbols: 知识库要求的符号集合（可选）。"""
        self.evidence = {item.id: item for item in evidence}
        self.parameters, self.replacements = defaultdict(list), defaultdict(list)
        self.names = set()
        for item in evidence:
            if item.kind == 'macro_definition':
                self.names.add(str(item.value))
            table = self.parameters if item.kind == 'macro_parameter' else self.replacements if item.kind == 'macro_replacement_token' else None
            if table is not None:
                table[item.metadata.get('macroDefinitionEvidenceId')].append(item)
        for table in (self.parameters, self.replacements):
            for items in table.values():
                items.sort(key=lambda item: item.metadata.get('ordinal', 0))
        # This is only a conservative query index, never an expansion proof.
        # Reach backwards from formal knowledge, including object aliases and
        # wrapper names supplied as actual arguments; preserve every raw record.
        self.demanded_calls = set()
        reachable = set(demand_symbols or ())
        if demand_symbols is not None:
            users = defaultdict(set)
            for definition_id, items in self.replacements.items():
                definition = self.evidence.get(definition_id)
                if definition is not None:
                    for item in items:
                        users[str(item.value)].add(str(definition.value))
            pending = list(reachable)
            while pending:
                for name in users.get(pending.pop(), ()):
                    if name not in reachable:
                        reachable.add(name)
                        pending.append(name)
        for item in evidence:
            if item.kind == 'call_expression' and str(item.value) in self.names:
                if demand_symbols is None or str(item.value) in reachable or any(
                    token['value'] in reachable
                    for arg in item.metadata.get('macroCall', {}).get('arguments', ()) for token in arg
                ):
                    self.demanded_calls.add(item.id)

    def resolve(self, call, environment, *, depth_limit=32, token_limit=4096):
        """展开一次宏调用，返回 Effects。

        先校验实参 token 的证据引用（同文件、同调用、同编译配置），
        不合法直接返回 invalid-macro-argument-reference；随后 invoke/walk
        相互递归完成"实参绑定→替换→再扫描嵌套调用"的固定参数展开；
        环、深度、token 预算或语义不支持都会记入 reasons 并作废全部路径。
        """
        paths, reasons, count = set(), set(), 0
        data = call.metadata.get('macroCall', {})
        if data.get('status') != 'complete':
            return Effects((), (data.get('reason') or 'missing-call-arguments',), 0)
        for arg in data['arguments']:
            for token in arg:
                identifier = token.get('evidenceId')
                if identifier is None:
                    continue
                original = self.evidence.get(identifier)
                if (original is None or original.kind != 'macro_argument_token'
                    or original.subject_id != call.subject_id or original.value != token['value']
                    or original.metadata.get('callEvidenceId', call.id) != call.id
                    or original.metadata.get('compilationContextId') != call.metadata.get('compilationContextId')):
                    return Effects((), ('invalid-macro-argument-reference',), 0)
        actual = tuple(tuple(Token(t['value'], t.get('evidenceId')) for t in arg) for arg in data['arguments'])

        def invoke(name, args, prefix, trail, terminal=None):
            # 调用名为 name 的宏：环境未定义它时，terminal 携带的终端
            # 路径落地（token 保持原始出处），实参继续扫描嵌套调用；
            # 已定义且证据唯一时，做对象别名转发或形参绑定替换后 walk。
            # trail 防自引用环；深度/token 超限即记原因并放弃。
            nonlocal count
            if len(trail) >= depth_limit or name in trail:
                reasons.add('macro-expansion-cycle-or-depth')
                return
            state = environment.get(name)
            if state is None or state.defined is False:
                if terminal is not None and terminal.evidence_id in self.evidence:
                    paths.add((*prefix, *terminal.forwarding, terminal.evidence_id))
                if terminal is not None:
                    for arg in args:
                        walk(arg, prefix, trail)
                return
            if state.defined is None or len(state.evidence_ids) != 1:
                reasons.add('macro-definition-unknown-or-conflicting')
                return
            definition = self.evidence.get(state.evidence_id)
            if definition is None or definition.kind != 'macro_definition':
                reasons.add('macro-definition-source-missing')
                return
            prefix = (*prefix, definition.id)
            if not state.function_like:
                if state.value and re.fullmatch(r'[A-Za-z_]\w*', state.value):
                    candidates = [item for item in self.replacements[definition.id] if item.value == state.value]
                    alias_token = Token(state.value,candidates[0].id) if len(candidates) == 1 else terminal
                    invoke(state.value, args, prefix, (*trail, name), alias_token)
                else:
                    reasons.add('unsupported-object-call')
                return
            params = self.parameters[definition.id]
            # 固定参数要求：形参数=实参数、无空实参、无变参(...)、
            # 替换体无不受支持的语义；否则记原因放弃本次展开。
            body = [token for token in self.replacements[definition.id] if token.metadata.get('tokenRole') != 'comment']
            if len(params) != len(args) or any(not arg for arg in args):
                reasons.add('macro-argument-count')
                return
            if any(t.metadata.get('unsupportedSemantics') for t in body) or any(p.value == '...' for p in params):
                reasons.add('unsupported-macro-semantics')
                return
            bindings = {str(parameter.value): (parameter, value) for parameter, value in zip(params, args)}
            # 形参替换：替换体中的形参位用实参 token 原样代入，并把
            # （形参证据, 替换点证据）追加进 forwarding 溯源链。
            substituted = []
            for item in body:
                if item.value in bindings:
                    parameter, tokens = bindings[item.value]
                    substituted.extend(Token(t.value, t.evidence_id, (*t.forwarding, parameter.id, item.id)) for t in tokens)
                else:
                    substituted.append(Token(str(item.value), item.id))
                count += len(bindings[item.value][1]) if item.value in bindings else 1
                if count > token_limit:
                    reasons.add('macro-token-budget')
                    return
            walk(substituted, prefix, (*trail, name))

        def walk(tokens, prefix, trail):
            # 扫描 token 序列找嵌套调用：先吸收前缀括号并要求右侧配对
            # 括号（形如 ((f))(…) 的包裹写法），再对"标识符("模式解析
            # 实参并递归 invoke；其余 token 前移一格。
            index = 0
            while index + 1 < len(tokens):
                callee, opening = index, index + 1
                while callee < len(tokens) and tokens[callee].value == '(':
                    callee += 1
                parentheses = callee - index
                closing = callee + 1
                if parentheses and callee < len(tokens) and all(
                    i < len(tokens) and tokens[i].value == ')' for i in range(closing, closing+parentheses)
                ):
                    opening = closing + parentheses
                elif parentheses:
                    index += 1
                    continue
                token = tokens[callee] if callee < len(tokens) else tokens[index]
                if re.fullmatch(r'[A-Za-z_]\w*', token.value) and opening < len(tokens) and tokens[opening].value == '(':
                    nested, finish = arguments(tokens, opening+1)
                    if nested is None:
                        reasons.add('malformed-macro-replacement')
                        return
                    invoke(token.value, nested, (*prefix, *((token.evidence_id,) if token.evidence_id and not token.forwarding else ())),
                           trail, token)
                    index = finish
                else:
                    index += 1

        invoke(str(call.value), actual, (call.id,), ())
        # A truncated expansion cannot certify even an earlier terminal in that call.
        return Effects(tuple(sorted(paths)) if not reasons else (), tuple(sorted(reasons)), count)
