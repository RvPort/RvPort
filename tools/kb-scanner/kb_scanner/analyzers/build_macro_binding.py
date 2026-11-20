"""Static build argument/translation-unit binding; never executes input."""
# 构建参数与翻译单元的静态绑定（analyzer 阶段）：把 Make recipe 中的
# -D 宏参数与源码翻译单元通过 MacroSession 串联，产出带作用域的
# 出现级边（source_occurrence 实体 + 选择/展开/包含关系）。
# 全程静态分析，绝不执行任何输入。
from pathlib import PurePosixPath

from ..core import Entity, Relation, stable_id
from ..normalization.build_effects import validate_effects
from .macro_state import MacroState
from .macro_session import MacroSession, condition
from .macro_effects import MacroEffectIndex


def evaluate_condition(evidence, macros):
    """求值单条预处理条件：返回 (涉及的宏名或 None, 三值真值)。"""
    result = condition(evidence, macros)
    return (result.names[0] if result.names else None), result.truth


def bind_build_macros(facts, entities, relations, *, max_relations, demand_symbols=None):
    """Append only scoped occurrence edges; unknown semantics remain unknown."""
    # 绑定主流程：1) 从证据 sourceChain 元数据分拣出 recipe（配方行）、
    # argument（-D/-U 参数）与 origin（参数来源）三类记录；
    # 2) 对每条已证明的 recipe，按其参数构造初始宏环境并对目标源文件
    # 跑 MacroSession，回调里对条件证据与按需宏调用建边；
    # 3) 对不在任何 recipe 内但含按需调用的源文件，以空环境补跑一遍。
    # 返回是否达到关系预算（limited）；语义未知的边一律保持 unknown。
    arguments, origins, recipes = {}, {}, []
    for item in facts.evidence:
        data = item.metadata.get("sourceChain", {})
        if data.get("role") == "argument":
            arguments[(data["key"], data["recipeLine"], data["order"], data.get("useId"))] = item
        elif data.get("role") == "origin":
            origins[data["useId"], data["key"]] = item
        elif data.get("role") == "recipe":
            recipes.append(item)
    files = {e.name: e for e in facts.entities if e.kind == "source_file"}
    limited = False
    invalid_effects = validate_effects(facts.evidence)
    initial_relations = len(relations)
    evidence_by_id = {e.id: e for e in facts.evidence}
    effects = MacroEffectIndex(facts.evidence, demand_symbols)
    if not recipes and not effects.demanded_calls:
        return False
    session = MacroSession(facts.evidence, files)
    bound_sources = set()
    covered_files = set()

    def entity(item, configuration, invocation=None):
        identifier = stable_id("source_chain_entity", item.id, configuration, invocation)
        entities.setdefault(identifier, Entity(identifier, "source_occurrence", str(item.value), item.location,
            {"sourceEvidenceId": item.id, "configuration": configuration, "invocationId": invocation}))
        return identifier

    def edge(left, right, configuration, result, kind="selects", extra=(), effect=None):
        """在两条证据的出现实体之间建边（就地写入 relations/entities）。

        result 三值：True→selected，False→inactive，None→unknown；
        带 effect（宏展开效果）时状态另有规则：转发链被证明即 complete，
        目标活动性未知不掩盖转发本身的证明。预算已满时置 limited 并跳过。
        """
        nonlocal limited
        status = "unknown" if result is None else "selected" if result else "inactive"
        invocation = effect.get('invocationId') if effect else None
        source = stable_id("source_chain_entity", left.id, configuration, invocation)
        target = stable_id("source_chain_entity", right.id, configuration, invocation)
        evidence_ids = tuple(sorted({left.id, right.id, *extra}))
        metadata = {"configuration": configuration, "sourceChain": {"binding": True, "result": result}}
        if effect:
            metadata['macroEffect'] = effect
            # The forwarding is proven even when enclosing target activity is unknown.
            status = 'inactive' if result is False else 'complete' if effect.get('proven') else 'unknown'
        identifier = stable_id("source_chain_relation", kind, source, target, configuration, status, evidence_ids)
        if len(relations) - initial_relations >= max_relations and identifier not in relations:
            limited = True
            return
        entity(left, configuration, invocation)
        entity(right, configuration, invocation)
        relations[identifier] = Relation(identifier, kind, source, target, evidence_ids,
            "high" if result is not None else "medium", status, metadata)

    def run(source, macros, configuration, recipe=None):
        """对单个源文件跑一次宏会话；consume 回调里完成所有建边。

        每次运行前重置会话计数器与原因集合，使各文件独立计量预算。
        """
        nonlocal limited
        session.events = session.visits = 0
        session.reasons = set()
        session.processed = set()

        def consume(item, environment, active, evaluation):
            # MacroSession 回调：evaluation 非空说明是条件证据——把求值
            # 溯源证据连到 recipe 与来源参数上；否则若该调用在按需集合中，
            # 做宏效果展开：被证明的终端逐段建 expands_to 边，
            # 未被证明的实参 token 建 contains 边并附原因码。
            nonlocal limited
            if evaluation is not None:
                source_ids = tuple(eid for eid in evaluation.evidence_ids if eid in evidence_by_id)
                if source_ids:
                    if recipe is not None:
                        edge(recipe, item, configuration, active, extra=source_ids)
                    for eid in source_ids:
                        origin = evidence_by_id[eid]
                        if origin.kind != 'build_argument':
                            edge(origin, item, configuration, active)
            elif item.id in effects.demanded_calls:
                result = effects.resolve(item, environment)
                limited |= any('budget' in reason for reason in result.reasons)
                proven_terminals = {path[-1] for path in result.paths}
                for path in result.paths:
                    path = tuple(dict.fromkeys(path))
                    effect = {'invocationId': item.id, 'terminalEvidenceId': path[-1],
                              'translationUnit': source.name, 'active': active, 'proven': True, 'buildBound':recipe is not None}
                    for left, right in zip(path, path[1:]):
                        if left in evidence_by_id and right in evidence_by_id:
                            edge(evidence_by_id[left], evidence_by_id[right], configuration, active,
                                 kind='expands_to', effect=effect)
                for arg in item.metadata.get('macroCall', {}).get('arguments', ()):
                    for token in arg:
                        eid = token.get('evidenceId')
                        if eid in evidence_by_id and eid not in proven_terminals:
                            effect = {'invocationId':item.id,'terminalEvidenceId':eid,'translationUnit':source.name,
                                      'active':active,'proven':False,'buildBound':recipe is not None,
                                      'reasonCodes':result.reasons or ('macro-argument-not-proven-called',)}
                            edge(item,evidence_by_id[eid],configuration,active,kind='contains',effect=effect)

        session.run(source, macros, consume)
        covered_files.update(session.processed)
        limited |= any('budget' in reason for reason in session.reasons)

    for recipe in sorted(recipes, key=lambda e: e.id):
        data = recipe.metadata["sourceChain"]
        if not data["proven"]:
            continue
        parent = PurePosixPath(data.get("workingDirectory", PurePosixPath(recipe.location.start.path).parent.as_posix()))
        source = files.get((parent / data["source"]).as_posix())
        if source is None:
            continue
        bound_sources.add(source.id)
        configuration = str(recipe.metadata.get("configuration", data["key"]))
        macros = {}
        for value in data["arguments"]:
            argument = arguments.get((value["key"], recipe.location.start.line, value["order"], value.get("useId")))
            if argument is not None:
                valid = argument.id not in invalid_effects
                macros[value["macro"]] = MacroState(value["operation"] == "-D" if valid else None, value["value"], argument.id)
                edge(argument, recipe, configuration, True if valid else None)
                for key in value.get("originKeys", ()):
                    origin = origins.get((value.get("useId"), key))
                    if origin is not None:
                        edge(origin, argument, configuration, True if valid else None, kind="defines")

        run(source, macros, configuration, recipe)
    demanded_files = {item.subject_id for item in facts.evidence
                      if item.id in effects.demanded_calls}
    for source in sorted(files.values(), key=lambda item: item.id):
        if source.id in demanded_files - bound_sources - covered_files:
            run(source, {}, 'source:' + source.name)
    return limited
