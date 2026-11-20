"""Conservative necessary ARM conditions, not an instruction vocabulary.

Compiler platform selectors are syntax attribution only. Unknown expressions,
negation, redefinition and missing macro provenance never prove exclusion.
"""
# 架构守卫判定：只识别"必要的 ARM 条件"，而非指令词表。
# 位于 analyzer 阶段，服务于跨架构守卫证明：判断某个预处理条件是否
# 保守地依赖于 ARM 平台宏。编译器平台选择宏只做语法归属，不做语义执行；
# 未知表达式、取反、宏重定义、溯源缺失一律不得证明"排除"。
from __future__ import annotations

import re

ARM_SELECTORS = frozenset({'__aarch64__', '__arm__', '_M_ARM', '_M_ARM64'})
# 已知的 ARM 平台选择宏（frozen，不可变集合）。


def required_foreign(expression: str, resolve=lambda name: ()) -> tuple[str, ...]:
    """解析条件表达式，返回其依赖的"外架构（ARM）宏"名集合。

    算法：先剥离注释与续行符、去掉最外层括号；再按 || / && 拆分——
    || 分支只要有一个分支无证明则整体无证明（保守），&& 则合并各分支证明；
    最后匹配 defined(X)/defined X/裸标识符三种原子形式。
    原子是 ARM_SELECTORS 中的宏直接返回；否则交给 resolve 回调
    （通常由调用方提供，用于沿宏定义链追溯）进一步判定。
    无法解析时返回空元组（视为无证明）。
    """
    text = re.sub(r'/\*.*?\*/|//[^\r\n]*', ' ', expression, flags=re.S).replace('\\\n', ' ').strip()
    while text.startswith('(') and _matching_end(text) == len(text) - 1:
        text = text[1:-1].strip()
    for operator in ('||', '&&'):
        pieces = _split(text, operator)
        if len(pieces) > 1:
            proofs = [required_foreign(piece, resolve) for piece in pieces]
            if operator == '||' and not all(proofs):
                return ()
            return tuple(sorted({item for proof in proofs for item in proof}))
    atom = re.fullmatch(r'(?:defined\s*\(\s*([A-Za-z_]\w*)\s*\)|defined\s+([A-Za-z_]\w*)|([A-Za-z_]\w*))', text)
    if atom is None:
        return ()
    name = next(value for value in atom.groups() if value is not None)
    return (name,) if name in ARM_SELECTORS else resolve(name)


def _matching_end(text: str) -> int:
    """返回首个与位置 0 的 '(' 配对的 ')' 下标；不配对返回 -1。"""
    depth = 0
    for index, char in enumerate(text):
        depth += (char == '(') - (char == ')')
        if depth == 0:
            return index
    return -1


def _split(text: str, operator: str) -> list[str]:
    """在深度为 0 处按指定运算符（|| 或 &&）切分表达式，忽略括号内的出现。"""
    depth, start = 0, 0
    result = []
    for index, char in enumerate(text):
        depth += (char == '(') - (char == ')')
        if depth == 0 and text.startswith(operator, index):
            result.append(text[start:index])
            start = index + len(operator)
    return [*result, text[start:]]


def foreign_guard_proof(condition, evidence_items, seen=frozenset()) -> tuple[str, ...]:
    """Return defining Evidence IDs for a same-file, unambiguous selector.

Definitions under else/elif, forward definitions, undef and cycles are rejected.
No project preprocessing or expression execution is performed.
"""
    # 给定一条预处理条件证据，返回能证明它依赖 ARM 宏的"定义类证据 ID"集合。
    # 只接受同文件、无歧义的选择器：else/elif 分支下的定义、前向定义、
    # #undef、环状引用一律拒绝；不执行任何项目预处理或表达式求值。
    # seen 记录递归路径以防环，深度超过 8 层即放弃。
    if condition.id in seen or len(seen) >= 8 or condition.location is None:
        return ()
    if condition.metadata.get('nodeType') == 'preproc_ifndef' or condition.metadata.get('conditionNegated'):
        return ()
    same_file = [item for item in evidence_items if item.subject_id == condition.subject_id and item.location is not None]
    if any((item.kind == 'macro_definition' and item.value in ARM_SELECTORS) or item.metadata.get('macroUndefName') in ARM_SELECTORS for item in same_file):
        return ()
    provenance = {condition.id}

    def resolve(name):
        # 宏名追溯回调：检查同文件内该宏是否被 #undef；要求恰好一处
        # 位置在条件之前的定义，且该定义被一个 if（非 else/elif）守卫包裹，
        # 再递归证明外层守卫，逐步收集 provenance 证据。
        if any(item.kind == 'macro_expansion' and (item.metadata.get('macroUndefName') == name or (str(item.value) == '#undef' and not item.metadata.get('macroUndefName'))) for item in same_file):
            return ()
        definitions = [item for item in same_file if item.kind == 'macro_definition' and item.value == name]
        if len(definitions) != 1:
            return ()
        definition = definitions[0]
        if definition.location.start.line >= condition.location.start.line:
            return ()
        parents = [item for item in same_file if item.kind in {'preprocessor_condition', 'preprocessor_branch'} and item.id != condition.id and _contains(item.location, definition.location)]
        if not parents:
            return ()
        parent = min(parents, key=lambda item: (item.location.end.line - item.location.start.line, (item.location.end.column or 1) - (item.location.start.column or 1)))
        if parent.kind == 'preprocessor_branch' or parent.metadata.get('nodeType') == 'preproc_elif':
            return ()
        proof = foreign_guard_proof(parent, evidence_items, seen | {condition.id})
        if proof:
            provenance.update((*proof, definition.id))
        return proof

    return tuple(sorted(provenance)) if required_foreign(str(condition.value), resolve) else ()


def _contains(outer, inner):
    """判断位置 span inner 是否完全位于 outer 内（行列坐标比较）。"""
    return (outer.start.line, outer.start.column or 1) <= (inner.start.line, inner.start.column or 1) and (inner.end.line, inner.end.column or 1) <= (outer.end.line, outer.end.column or 1)
