"""字面属性解码：只解析属性字符串本身。

不展开宏、不执行编译器、不猜测目标 ISA。decode() 做的是一个极小的、带预算的
字面量解析器：识别 target/target_clones 等机制名，逐 token 读取字符串字面量，
只有全部由字面量组成才算 complete；任何非字面量（宏、表达式）都退为 unknown。
"""
import re
from dataclasses import dataclass

_NAME = re.compile(r"(?<![\w:])(?:(\w+)\s*::\s*)?(__)?(target_clones|target_version|target|cpu_dispatch|cpu_specific)(?:__)?\s*\(")
_TOKEN = re.compile(r'\s*("(?:[^"\\]|\\["\\])*"|,)')
SOURCE = "https://releases.llvm.org/18.1.8/tools/clang/docs/AttributeReference.html"


def decode(value, max_chars=65536, max_options=128):
    """解码函数多版本属性字符串，返回机制/选项/状态字典。

    状态机思路：先定位唯一的机制名（多个或不识别 => unknown），再从括号后逐 token
    扫描——只接受字符串字面量与逗号分隔；遇到 ')' 且没有悬挂字面量即收尾。
    target 的逗号列表是一个版本的特征集合（合并成单选项）；target_clones 的列表
    才是版本列表（去重排序后逐项保留）。超预算或非法 token 返回带 reason 的
    unknown/部分结果，不抛异常。
    """
    # 预算必须非负；max_chars 限字符串长度，max_options 限选项数。
    if min(max_chars, max_options) < 0:
        raise ValueError("attribute budgets must be nonnegative")
    result = {"mechanism": "unknown", "options": (), "status": "unknown",
              "reason": "multiversion-attribute-unsupported", "optionCount": 0}
    if not isinstance(value, str):
        return result
    if len(value) > max_chars:
        return {**result, "reason": "multiversion-budget-exceeded"}
    matches = list(_NAME.finditer(value))
    if len(matches) != 1:
        # 机制名必须恰好出现一次；零次或多次都无法确定语义。
        return result
    match = matches[0]
    mechanism = match.group(3)
    result["mechanism"] = mechanism
    if match.group(1) not in {None, "gnu"} or mechanism not in {"target", "target_clones"}:
        # 命名空间非 gnu 或机制不受支持：机制名保留，选项保持 unknown。
        return result
    # expect_literal 状态：True 表示"当前段还没有字面量"，此时遇到 ) 或 , 即非法。
    position, segments, current, expect_literal = match.end(), [], "", True
    while position < len(value):
        if value[position:].lstrip().startswith(")"):
            if expect_literal:
                return result
            segments.append(current)
            break
        token = _TOKEN.match(value, position)
        if token is None:
            return {**result, "reason": "multiversion-nonliteral-attribute"}
        position = token.end()
        text = token.group(1)
        if text == ",":
            if expect_literal:
                return result
            segments.append(current)
            current, expect_literal = "", True
        else:
            # 字符串字面量：去掉引号并反转义 \\ 与 \"，拼入当前段。
            current += re.sub(r'\\(["\\])', r'\1', text[1:-1])
            expect_literal = False
    else:
        # 扫描到末尾也没遇到 ')'：括号未闭合，判 unknown。
        return result
    # 段内再按逗号细分选项；strip 后空选项视为非法。
    options = tuple(part.strip() for segment in segments for part in segment.split(","))
    result["optionCount"] = len(options)
    if len(options) > max_options:
        return {**result, "reason": "multiversion-budget-exceeded"}
    if not all(options) or (mechanism == "target" and "default" in options and len(options) != 1):
        return result
    # target's comma list is a feature set for one version; clones is a version list.
    versions = tuple(sorted(set(options))) if mechanism == "target_clones" else (",".join(sorted(set(options))),)
    return {**result, "options": versions, "status": "complete", "reason": "multiversion-literal-attribute"}


@dataclass(frozen=True)
class CompilerProfile:
    """外部提供的编译器画像（frozen 不可变）。

    全部字段为可选字符串：family/version/language/source_target/provenance。
    只作为可信输入参与 semantic_rule 判定，分析器绝不自行推断这些值。
    """

    family: str = ""
    version: str = ""
    language: str = ""
    source_target: str = ""
    provenance: str = ""


def semantic_rule(profile, mechanism):
    """判定编译器语义规则是否适用于该机制，返回规则字典。

    适用条件极窄：仅 clang 18.1.x、C/C++、x86 源目标、带 provenance，且机制为
    target（仅 C++）或 target_clones。任一条件不满足返回 status=unknown；
    满足时返回 ruleId（clang-18.1-x86-<mechanism>）与 requiresDefault=True
    （多版本函数必须有 default 版本的规则要求）。
    """
    unknown = {"status": "unknown", "requiresDefault": False, "ruleId": None,
               "source": SOURCE, "targetApplicability": "unknown"}
    if profile is None:
        return unknown
    applies = (profile.family == "clang" and re.fullmatch(r"18\.1(?:\.\d+)?", profile.version)
               and profile.language in {"c", "cpp"} and profile.source_target in {"x86", "x86_64"}
               and bool(profile.provenance))
    if not applies or mechanism not in {"target", "target_clones"} or (mechanism == "target" and profile.language != "cpp"):
        return unknown
    return {**unknown, "status": "applicable", "requiresDefault": True,
            "ruleId": f"clang-18.1-x86-{mechanism}", "provenance": profile.provenance}
