"""有界 Make 编译参数读取，保持真实 token 来源，不执行项目命令。"""

from dataclasses import replace
from pathlib import PurePosixPath
import re

from .make_effects import MakeEffects
from .make_values import REF, tokens
from .vocabulary import BuildRecord, record_for_token


_SOURCE = re.compile(r"[\w./+-]+\.(?:c|cc|cpp|cxx|C)$")
_MACRO = re.compile(r"([A-Za-z_]\w*)(?:=(.*))?$")
_COMPILERS = {"cc", "c++", "gcc", "g++", "clang", "clang++"}


def parse_macro_recipes(source, path, *, max_expansions=4096, max_tokens=256,
                        sources=None, target_arch="riscv64"):
    """从 Makefile 的编译 recipe 行提取 -D/-U 宏参数及其完整溯源链。

    思路：MakeEffects 先跨文件求值出可信的 recipe（编译命令行），
    再逐 token 识别 -D/-U（含分离的参数形式），为每个宏参数建立
    use identity（useId）与来源位置（origin），来源与主位置不同的
    记为 origin 节点（build_assignment），主参数记为 build_argument，
    最后整体产出一条 build_configuration（recipe 汇总）。
    known=False 的任何因素都会把 selectionStatus 降为 unknown。
    """
    inputs = {**(sources or {}), path: source}
    effects = MakeEffects(inputs, path, target_arch, depth=min(32,max_expansions), limit=max_tokens)
    records = []
    for recipe in effects.recipes:
        words = tokens(recipe.text, recipe.path, recipe.line)
        command = words[0].text.lstrip("@")
        compiler_ref = REF.fullmatch(command)
        # 只处理编译器命令：直接是 cc/gcc 等，或 $(CC)/$(CXX) 引用。
        if command not in _COMPILERS and (compiler_ref is None or (compiler_ref[1] or compiler_ref[2]) not in {"CC","CXX"}):
            continue
        automatic = {"$@": recipe.target}
        if recipe.prerequisites:
            automatic["$<"] = recipe.prerequisites[0]
        resolved = effects.expand(words[1:], automatic)
        names = tuple(t.text for t in resolved or () if _SOURCE.fullmatch(t.text))
        # known 是"可信编译参数"的合取：无未求值因素 + 有 -c + 恰好一个
        # 源文件且属于 prerequisite；$(CC) 引用还需展开后确是编译器。
        known = (recipe.known and not effects.reasons and resolved is not None
                 and "-c" in [t.text for t in resolved] and len(names) == 1
                 and names[0] in recipe.prerequisites)
        if compiler_ref and (compiler_ref[1] or compiler_ref[2]) in effects.env:
            compiler = effects.expand((replace(words[0],text=command),))
            known &= compiler is not None and len(compiler) == 1 and compiler[0].text in _COMPILERS
        if any(t.text.startswith(("-include","-imacros","-Wp,","-Xpreprocessor","-undef","--")) for t in resolved or ()):
            # 这些编译开关改变宏来源语义，直接判不可信。
            known = False
        configuration = f"{path}:{recipe.target}:{recipe.line}" if recipe.path == path else f"{path}:{recipe.path}:{recipe.target}:{recipe.line}"
        use_id = f"{path}:{recipe.path}:{recipe.line}:{recipe.target}"
        args, origin_records = [], {}
        index = 0
        while index < len(resolved or ()):
            # 逐 token 扫描 -D/-U：支持 "-DNAME=V" 与 "-D NAME=V" 两种形态。
            token = resolved[index]
            index += 1
            if not token.text.startswith(("-D","-U")):
                continue
            operation, value = token.text[:2], token.text[2:]
            pieces = [token]
            end = token.end
            if not value and index < len(resolved):
                other = resolved[index]
                value = other.text
                pieces.append(other)
                index += 1
                if other.path == token.path and other.line == token.line and other.column >= token.end:
                    end = other.end
            match = _MACRO.fullmatch(value)
            if match is None or operation == "-U" and match[2] is not None:
                # -U 带值不合语法；非法名字整体降级为不可信。
                known = False
                continue
            name, value = match.groups()
            key = f"{token.path}:{token.line}:{token.column}:{end}"
            origins = {span for part in pieces for span in (part.source, *part.origins, *part.via)}
            origin_keys = []
            for origin_path, line, column, finish in sorted(origins):
                origin_key = f"{origin_path}:{line}:{column}:{finish}"
                origin_keys.append(origin_key)
                # 参数主位置已作为真实 Evidence，不重复为来源节点。
                if (origin_path,line,column,finish) == (token.path,token.line,token.column,end):
                    continue
                data = {"role":"origin","key":origin_key,"useId":use_id}
                raw = inputs[origin_path].splitlines()[line-1][column-1:finish-1]
                origin_records[origin_key] = BuildRecord("build_assignment",raw,line,column,finish,
                    {"sourcePath":origin_path,"configuration":configuration,"sourceChain":data})
            data = {"role":"argument","key":key,"macro":name,"operation":operation,
                    "value":value if value is not None else "1","order":len(args),
                    "recipeLine":recipe.line,"target":recipe.target,
                    "viaLines":tuple(span[1] for span in token.via)}
            # 旧简单 token 的合同保留；多来源通过稳定 use identity 关联。
            if len(origins) > 1 or recipe.path != path:
                data.update(useId=use_id, originKeys=tuple(origin_keys))
            args.append(data)
            raw = inputs[token.path].splitlines()[token.line-1][token.column-1:end-1]
            metadata = {"sourceChain":data,"selectionStatus":"unknown","configuration":configuration}
            if token.path != path:
                metadata["sourcePath"] = token.path
            records.append(BuildRecord("build_argument",raw,token.line,token.column,end,metadata))
        records.extend(origin_records.values())
        # recipe 汇总节点（build_configuration）：聚合本次编译命令行的全部宏参数。
        data = {"role":"recipe","key":f"{path}:{recipe.path}:{recipe.line}","target":recipe.target,
                "targetLine":recipe.target_line,"source":names[0] if len(names)==1 else None,
                "arguments":args,"proven":known}
        if recipe.path != path:
            data['workingDirectory'] = PurePosixPath(path).parent.as_posix()
        if origin_records or recipe.path != path:
            data["useId"] = use_id
        if effects.reasons:
            data["reasonCodes"] = tuple(sorted(effects.reasons))
        metadata = {"sourceChain":data,"selectionStatus":"selected" if known else "unknown",
                    "configuration":configuration}
        if recipe.path != path:
            metadata["sourcePath"] = recipe.path
        records.append(record_for_token("build_configuration",recipe.text.strip(),recipe.line,recipe.text,metadata=metadata))
    return tuple(records)
