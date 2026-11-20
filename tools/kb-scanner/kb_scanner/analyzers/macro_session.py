"""One translation-unit/configuration state walk over immutable frontend records."""
# 宏会话（macro session）：对单个翻译单元/配置做一次预处理状态遍历。
# 输入是 frontend 阶段产出的不可变 Evidence 记录；本模块按 include 链
# 展开文件、按 #if/#elif/#else 分支合并宏环境，并通过回调把每条证据的
# 活跃性（active）与求值结果交给调用方。不执行任何输入代码。
from collections import defaultdict
from pathlib import PurePosixPath

from .macro_state import MacroState, Evaluation, evaluate, join


def condition(item, environment):
    """把预处理条件证据转成可求值文本并求值。

    ifdef/ifndef 分别改写为 defined(X)/!defined(X)（含 conditionNegated
    取反标记）；结构不完整（structureComplete=False）时强制返回
    partial-condition-structure 的 Evaluation，不做猜测。
    """
    kind = item.metadata.get('nodeType')
    text = str(item.value)
    if kind in {'preproc_ifdef', 'preproc_ifndef'}:
        text = f'defined({text})'
        if kind == 'preproc_ifndef' or item.metadata.get('conditionNegated'):
            text = '!' + text
    result = evaluate(text, environment)
    if item.metadata.get('structureComplete') is False:
        return Evaluation(None, result.evidence_ids, result.names, 'partial-condition-structure')
    return result


def conjunction(left, right):
    """三值逻辑与：任一为 False 得 False，任一为 None（未知）得 None，
    否则 True。用于分支可行性与守卫条件的外层合并。"""
    return False if left is False or right is False else None if left is None or right is None else True


def order(item):
    """计算证据的排序键：优先 metadata 的 sourceOrder，否则按行列折算
    （行*1e6+列），保证同文件内预处理事件有序。"""
    return item.metadata.get('sourceOrder', item.location.start.line * 1000000 + (item.location.start.column or 1))


class MacroSession:
    """预处理状态遍历器：按文件顺序回放宏定义/条件/include 事件。

    构造时把证据按 subject（文件）分组并排序，重复出现的文件以
    tree_sitter_baseline 解析结果为准（非基线记录仅在该文件无基线时保留）。
    不变式：只读输入证据；遍历受三类预算约束（event_limit 事件数、
    include_depth 包含深度、include_limit 访问文件数），超限通过
    invalidate 把所有宏状态降为未知并记录原因，绝不虚构状态。
    """
    def __init__(self, evidence, files, *, event_limit=100000, include_depth=16, include_limit=128):
        """evidence: 全量证据；files: 文件名 → 实体映射，用于解析 include。"""
        self.files = files
        self.event_limit, self.include_depth, self.include_limit = event_limit, include_depth, include_limit
        self.events, self.visits, self.reasons = 0, 0, set()
        self.processed = set()
        self.by_subject, self.replacements = defaultdict(list), defaultdict(list)
        baseline = {item.subject_id for item in evidence if item.parse_mode == 'tree_sitter_baseline'}
        for item in evidence:
            if item.kind == 'macro_replacement_token':
                self.replacements[item.metadata.get('macroDefinitionEvidenceId')].append(item)
            if item.location and (item.subject_id not in baseline or item.parse_mode == 'tree_sitter_baseline'):
                if item.kind in {'preprocessor_condition', 'preprocessor_branch', 'macro_definition', 'include', 'call_expression', 'macro_expansion'}:
                    self.by_subject[item.subject_id].append(item)
        for items in self.by_subject.values():
            items.sort(key=lambda item: (order(item), item.id))
        for items in self.replacements.values():
            items.sort(key=lambda item: item.metadata.get('ordinal', 0))

    def invalidate(self, environment, reason):
        """把环境中所有宏状态降为未知（defined/value 置 None，保留溯源）
        并记录原因；返回降级后的新环境，原环境不被修改。"""
        self.reasons.add(reason)
        return {name: MacroState(None, None, state.evidence_id, state.origins) for name, state in environment.items()}

    def run(self, file, environment, callback, *, active=True, trail=()):
        """从 file 开始按序回放预处理事件；返回遍历结束时的宏环境。

        callback(item, environment, active, evaluation) 在每个活跃事件处
        被调用；include 环（trail 中重复出现）、深度或访问数超限时整环境
        降级。inactive 分支中的事件仍以 active=False 回调（保序），
        但不真正执行其中的 define/include。trail 记录 include 路径防环。
        """
        self.processed.add(file.id)
        self.visits += 1
        if len(trail) >= self.include_depth or file.id in trail or self.visits > self.include_limit:
            return self.invalidate(environment, 'macro-include-cycle-or-budget')
        trail = (*trail, file.id)
        items = self.by_subject[file.id]

        def sequence(first, last, env, outer):
            """回放 items[first:last) 区间：处理条件组分支合并与普通事件。

            条件组：先据 preprocessorRegion 定位 #if/#elif/#else 各分支的
            边界；对每个分支求条件可行性（三值），可行分支用副本环境递归
            回放；不可行分支内的事件仅以 active=False 回调不执行。
            各分支出口环境经 join() 合并（分歧宏降为未知）。
            """
            index = first
            while index < last:
                item = items[index]
                index += 1
                self.events += 1
                if self.events > self.event_limit:
                    return self.invalidate(env, 'macro-event-budget')
                kind = item.metadata.get('nodeType')
                if item.kind == 'preprocessor_condition' and kind not in {'preproc_call', 'preproc_elif'}:
                    region = item.metadata.get('preprocessorRegion', {})
                    end = region.get('groupEnd', (item.location.end.line + 1) * 1000000)
                    finish = index
                    alternatives = []
                    while finish < last and order(items[finish]) < end:
                        candidate = items[finish]
                        other = candidate.metadata.get('preprocessorRegion', {})
                        if region and other.get('groupStart') == region.get('groupStart'):
                            alternatives.append(finish)
                        finish += 1
                    starts = [index - 1, *alternatives]
                    outcomes, remaining = [], True
                    # remaining 累积"前面分支都不可行"的三值判断：
                    # else 分支（is_else）视为恒真，普通分支求条件值。
                    for arm, stop in zip(starts, [*alternatives, finish]):
                        branch = items[arm]
                        is_else = branch.kind == 'preprocessor_branch'
                        result = Evaluation(1) if is_else else condition(branch, env)
                        feasible = conjunction(remaining, result.truth)
                        callback(branch, env, conjunction(outer, feasible), result)
                        if feasible is not False:
                            outcomes.append(sequence(arm + 1, stop, dict(env), conjunction(outer, feasible)))
                        else:
                            # Preserve occurrence activity without executing any inactive define/include.
                            for inactive in items[arm+1:stop]:
                                self.events += 1
                                if self.events > self.event_limit:
                                    return self.invalidate(env, 'macro-event-budget')
                                if inactive.kind == 'call_expression':
                                    callback(inactive, env, False, None)
                        remaining = conjunction(remaining, None if result.truth is None else not result.truth)
                    if remaining is not False:
                        outcomes.append(dict(env))
                    env = join(outcomes)
                    index = finish
                    continue
                if item.metadata.get('unsupportedMacroStateDirective'):
                    env = self.invalidate(env, 'unsupported-macro-state-directive')
                elif item.kind == 'preprocessor_branch' or kind == 'preproc_elif':
                    env = self.invalidate(env, 'unbound-preprocessor-alternative')
                elif item.kind == 'macro_definition' or item.metadata.get('macroUndefName'):
                    # 宏定义/#undef：结构完整且替换 token 语义受支持时记录
                    # 布尔值与展开文本；否则 defined=None（未知），不猜测。
                    name = item.metadata.get('macroUndefName') or str(item.value)
                    tokens = self.replacements.get(item.id, ())
                    supported = item.metadata.get('structureComplete') is not False and not any(t.metadata.get('unsupportedSemantics') for t in tokens)
                    value = ' '.join(str(token.value) for token in tokens)
                    env[name] = MacroState(not bool(item.metadata.get('macroUndefName')) if supported else None,
                                           value if supported else None, item.id,
                                           function_like=bool(item.metadata.get('functionLike') or kind == 'preproc_function_def'))
                elif item.kind == 'include':
                    # #include：仅接受可解析的同项目相对包含；系统头、
                    # 越界路径或找不到目标时整环境降级（无法确定后续宏状态）。
                    path = str(item.value)
                    relative = path.strip('"')
                    target = PurePosixPath(file.name).parent / relative
                    included = self.files.get(target.as_posix())
                    if path.startswith('<') or '..' in target.parts or target.is_absolute() or included is None:
                        env = self.invalidate(env, 'macro-include-unresolved')
                    else:
                        callback(item, env, outer, None)
                        env = self.run(included, env, callback, active=outer, trail=trail)
                else:
                    callback(item, env, outer, None)
            return env

        if active is False:
            return dict(environment)
        return sequence(0, len(items), dict(environment), active)
