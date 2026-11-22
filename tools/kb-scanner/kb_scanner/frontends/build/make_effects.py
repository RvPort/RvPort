"""Make 参数桥接的有界读取事件；源文本是输入，不是待执行脚本。"""

from dataclasses import dataclass, replace
from pathlib import PurePosixPath
import re

from .make_values import REF, Variable, expand, merge_environments, tokens
from .safety import symbolic_uname


ASSIGN = re.compile(r'^\s*([A-Za-z_]\w*)\s*(:=|\+=|\?=|=)\s*(.*?)\s*(?:#.*)?$')
CONDITION = re.compile(r'^\s*(ifeq|ifneq|ifdef|ifndef)\b\s*(.*?)\s*$')
TARGET = re.compile(r'([\w./+-]+)\s*:\s*([^:=]*)')


@dataclass(frozen=True)
class Recipe:
    """一条 target recipe（frozen 不可变）：命令行文本、所属目标、prerequisite 与可信度。"""

    path: str
    line: int
    text: str
    target: str
    prerequisites: tuple
    known: bool
    target_line: int


class MakeEffects:
    """Make 副作用有界读取器：跨 include 文件累积变量环境并收集 recipe。

    不执行任何命令，只维护"哪些变量在哪些条件下被赋了什么 token"。
    遇到不可静态求值的构造时调用 invalidate 把全部变量打为未知，
    并在 reasons 里记录原因码，供上层降低可信度。
    预算：include 深度 8、访问文件 128、事件 100000。
    """

    def __init__(self, sources, entry, target_arch='riscv64', *, depth=32, limit=256):
        """sources 为 {相对路径: 文本}，entry 为入口 Makefile；构造时即开始 read。"""
        self.sources, self.entry, self.target_arch = sources, entry, target_arch
        self.depth, self.limit = depth, limit
        self.env, self.recipes, self.reasons = {}, [], set()
        self.visits, self.events = 0, 0
        self.read(entry)

    def expand(self, items, automatic=None):
        """委托 make_values.expand，用自身环境与深度/数量预算。"""
        return expand(items, self.env, automatic=automatic, depth=self.depth, limit=self.limit)

    def invalidate(self, reason):
        """整体失忆：所有变量打为未知并记录原因码（环境结构保留）。"""
        self.reasons.add(reason)
        self.env = {name: Variable(None) for name in self.env}

    def condition(self, kind, expression, path, line):
        """三值求值单个 Make 条件：ifdef/ifndef 查环境，ifeq/ifneq 展开后比较。"""
        if kind in {'ifdef','ifndef'}:
            variable = self.env.get(expression.strip())
            value = None if variable is None or variable.tokens is None else bool(variable.tokens)
            return not value if kind == 'ifndef' and value is not None else value
        match = re.fullmatch(r'\((.*?),(.*?)\)', expression)
        if match is None:
            return None
        values = [self.expand(tokens(part.strip(), path, line)) for part in match.groups()]
        if any(value is None for value in values):
            return None
        equal = tuple(t.text for t in values[0]) == tuple(t.text for t in values[1])
        return not equal if kind == 'ifneq' else equal

    def read(self, path, trail=(), provenance=()):
        """逐行读取一个 Makefile：条件分叉环境、赋值记 token 溯源、include 递归。

        条件处理：压栈时快照当前环境（before），else 恢复快照再进入
        下一分支，endif 时把各分支环境 merge_environments 合并——
        分支间同名变量不一致则降级为未知。trail 防 include 环。
        recipe 行（tab 开头）挂到最近的目标上收集为 Recipe。
        """
        self.visits += 1
        if path in trail or len(trail) >= 8 or self.visits > 128 or path not in self.sources:
            self.invalidate('make-include-unresolved-or-budget')
            return
        frames = []
        condition_sites = []
        active = True
        target = None
        for number, raw in enumerate(self.sources[path].splitlines(), 1):
            self.events += 1
            if self.events > 100000:
                self.invalidate('make-event-budget')
                return
            text = raw.strip()
            if not text or text.startswith('#'):
                continue
            # 条件行：求值后压栈；active 用三值逻辑逐层传播。
            condition = CONDITION.match(raw) if not raw.startswith('\t') else None
            if condition:
                value = self.condition(*condition.groups(), path, number)
                frames.append((active, value, dict(self.env), None))
                condition_sites.append((path, number, 1, len(raw)+1))
                active = False if active is False or value is False else None if active is None or value is None else True
                continue
            if text == 'else' and frames:
                parent, value, before, prior = frames[-1]
                if prior is not None:
                    self.invalidate('make-malformed-condition')
                frames[-1] = parent, value, before, dict(self.env)
                self.env = dict(before)
                active = False if parent is False or value is True else None if parent is None or value is None else True
                continue
            if text == 'endif' and frames:
                parent, value, before, prior = frames.pop()
                condition_sites.pop()
                first, second = (prior, self.env) if prior is not None else (self.env, before)
                self.env = dict(before) if parent is False else dict(first if value is True else second) if value is not None else merge_environments((first, second))
                active = parent
                continue
            if active is False:
                continue
            assignment = ASSIGN.match(raw) if not raw.startswith('\t') else None
            if assignment:
                # := 与既往立即展开的 += 是立即语义；其余推迟到使用点展开。
                name, operator, expression = assignment.groups()
                if operator == '?=' and name in self.env:
                    continue
                prior = self.env.get(name)
                immediate = operator == ':=' or operator == '+=' and prior is not None and prior.immediate
                value = tokens(expression, path, number, assignment.start(3))
                value = tuple(replace(token, via=(*provenance,*condition_sites)) for token in value)
                probe = symbolic_uname(expression, self.target_arch)
                if probe.recognized:
                    # uname -m 探测直接符号化为目标架构，视作已知且立即求值。
                    value = (replace(tokens(expression, path, number, assignment.start(3))[0], text=probe.value),)
                    immediate = True
                elif immediate:
                    value = self.expand(value)
                if operator == '+=' and prior is not None:
                    value = None if value is None or prior.tokens is None else (*prior.tokens,*value)
                self.env[name] = Variable(value, immediate)
                continue
            include = re.fullmatch(r'(?:-?include|sinclude)\s+([\w./+-]+)', text)
            if include:
                child = PurePosixPath(self.entry).parent / include[1]
                if '..' in child.parts or child.is_absolute():
                    self.invalidate('make-include-outside-workspace')
                else:
                    self.read(child.as_posix(), (*trail,path), (*provenance,*condition_sites,(path,number,1,len(raw)+1)))
                continue
            if raw.startswith('\t'):
                # recipe 行：归属最近一次匹配的目标；known 同时取决于目标行与所在分支。
                if target:
                    self.recipes.append(Recipe(path, number, raw, target[0], target[1], active is True and target[2] is True, target[3]))
                continue
            match = TARGET.fullmatch(text)
            target = (match[1], tuple(match[2].split()), active, number) if match else None
            if not match:
                self.invalidate('make-unsupported-effect')
        if frames:
            self.invalidate('make-malformed-condition')
