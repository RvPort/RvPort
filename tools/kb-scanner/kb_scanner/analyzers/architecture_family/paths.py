"""带版本号的有界词法路径模板；不使用文件系统或编译器知识。

只做纯字符串切分：识别路径中的"架构词"目录槽与文件名前后缀，把同模板路径
归到同一个 key（如 arch/foo.c -> {architecture}/foo.c）。识别结果与具体平台
无关，仅按词表标注角色（x86/riscv/generic/foreign）。

源架构参数化：source_architecture 为 "x86" 时按原词表折叠（arm 系 → foreign）；
为 "arm64" 时 arm 系折叠为 "arm64"（源架构角色），x86 系折叠为 "foreign"。
"""
from dataclasses import dataclass
from pathlib import PurePosixPath
import re

VERSION = 1
# 角色词表：x86 系/riscv 系/通用回退/其他架构（foreign）。同义词折叠到统一角色。
_ROLES = {
    **dict.fromkeys(("x86", "x86_64", "i386", "i686", "amd64", "x64", "avx", "avx2", "avx512", "sse", "sse2", "sse3", "ssse3", "sse4", "sse4_1", "sse4_2"), "x86"),
    **dict.fromkeys(("riscv", "riscv32", "riscv64"), "riscv"),
    **dict.fromkeys(("generic", "portable", "scalar", "fallback"), "generic"),
    **dict.fromkeys(("arm", "arm64", "aarch64"), "foreign"),
}
# arm64 作为源架构时的角色词表：arm 系 → "arm64"（源），x86 系 → "foreign"。
_ROLES_ARM64_SOURCE = {
    **{key: "foreign" for key in _ROLES if _ROLES[key] == "x86"},
    **dict.fromkeys(("riscv", "riscv32", "riscv64"), "riscv"),
    **dict.fromkeys(("generic", "portable", "scalar", "fallback"), "generic"),
    **dict.fromkeys(("arm", "arm64", "aarch64"), "arm64"),
}
# 词表按"长度优先"拼接，保证 avx512 不被 avx 截断抢先匹配。
def _compile_roles(roles):
    words = "|".join(re.escape(word) for word in sorted(roles, key=lambda w: (-len(w), w)))
    return roles, re.compile(rf"^({words})[_.-](.+)$", re.I), re.compile(rf"^(.+?)[_.-]({words})$", re.I)

_ROLES_TABLE, _PREFIX, _SUFFIX = _compile_roles(_ROLES)
_ROLES_ARM64_TABLE, _PREFIX_ARM64, _SUFFIX_ARM64 = _compile_roles(_ROLES_ARM64_SOURCE)


def _tables(source_architecture):
    """按源架构族选择（词表, 前缀正则, 后缀正则, 源角色名）。"""
    if source_architecture == "arm64":
        return _ROLES_ARM64_TABLE, _PREFIX_ARM64, _SUFFIX_ARM64, "arm64"
    return _ROLES_TABLE, _PREFIX, _SUFFIX, "x86"


@dataclass(frozen=True)
class PathCandidate:
    """一个路径候选（frozen 不可变）。

    key 是分桶用的模板键（directory:/filename:/ambiguous: 前缀区分来源）；
    role 是词表折叠后的角色；token 是命中的原词；rule 标识匹配规则版本；
    ambiguous 表示同一路径命中了多个角色槽。
    """

    path: str
    key: str
    role: str
    token: str
    rule: str
    ambiguous: bool = False


def candidates(raw, source_architecture="x86"):
    """从一条原始路径生成 0..n 个 PathCandidate。

    流程：反斜杠归一化 -> 目录段逐一查角色词表（命中即产出 directory 槽候选）->
    文件名词干再试前缀/后缀匹配 -> 多槽命中折叠为单个 ambiguous 候选 ->
    无任何命中时产出 unlabelled 候选。非法路径（空段、..、盘符）返回空元组。
    source_architecture 决定角色折叠方向（x86 源时 arm→foreign；arm64 源时反转）。
    """
    roles, prefix_re, suffix_re, source_role = _tables(source_architecture)
    path = raw.replace("\\", "/")
    parts = path.split("/")
    if not path or any(p in {"", ".", ".."} for p in parts) or ":" in parts[0]:
        # 空段/相对上跳/盘符路径不参与模板化。
        return ()
    pure = PurePosixPath(path)
    slots = []
    for index, part in enumerate(parts[:-1]):
        if part.casefold() in roles:
            # 目录槽：把该段替换为 {architecture} 得到模板 key。
            key = "/".join((*parts[:index], "{architecture}", *parts[index+1:]))
            slots.append(PathCandidate(path, "directory:" + key, roles[part.casefold()], part, "directory-slot-v1"))
    prefix, suffix = prefix_re.fullmatch(pure.stem), suffix_re.fullmatch(pure.stem)
    # 文件名前后缀各试一次；stem 是去掉架构词后的名称。
    for match, token_index, stem_index in ((prefix, 1, 2), (suffix, 2, 1)):
        if match:
            token, stem = match[token_index], match[stem_index]
            slots.append(PathCandidate(path, "filename:" + str(pure.with_name(stem + pure.suffix)),
                                       roles[token.casefold()], token, "filename-affix-v1"))
    if len(slots) > 1:
        # Retain an isolated conflict, never bridge templates transitively.
        return (PathCandidate(path, "ambiguous:" + path, source_role if any(s.role == source_role for s in slots) else "unknown",
                              ",".join(s.token for s in slots), "multiple-role-slots-v1", True),)
    if slots:
        return tuple(slots)
    return (PathCandidate(path, "filename:" + path, "unlabelled", "", "unlabelled-v1"),)
