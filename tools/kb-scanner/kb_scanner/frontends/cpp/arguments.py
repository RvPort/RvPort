"""编译数据库命令行参数的安全过滤（clang enrichment 前置）。

frontend 阶段的参数策略模块：把 compile_commands.json 中的原始编译
参数分成"接受"与"拒绝"两类——只放行包含路径、宏定义、语言标准、
目标架构等只影响解析的选项；输出重定向、插件加载、模块、临时文件
等会改变构建行为或执行环境的选项一律拒绝并记录 WARNING 诊断。
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from ...core import CompilationUnitContext, Diagnostic, DiagnosticSeverity
from .compilation_database import compilation_context_id


@dataclass(frozen=True, slots=True)
class ClangArgumentResult:
    """参数过滤结果：accepted 为放行 token 序列，diagnostics 为拒绝项的诊断。

    不可变（frozen）。accepted 保持与原命令一致的 token 顺序，
    可直接传给 libclang 的 parse args。
    """

    accepted: tuple[str, ...]
    diagnostics: tuple[Diagnostic, ...]


_SAFE_PAIRED = frozenset(
    {"-I", "-isystem", "-iquote", "-idirafter", "-D", "-U", "-x", "-target", "--target", "--sysroot", "-isysroot"}
)
_PATH_PAIRED = frozenset({"-I", "-isystem", "-iquote", "-idirafter", "--sysroot", "-isysroot"})
_UNSAFE_PAIRED = frozenset(
    {
        "-o",
        "-MF",
        "-MT",
        "-MQ",
        "-MJ",
        "-Xclang",
        "-Xlinker",
        "-Xassembler",
        "-load",
        "-plugin",
        "-add-plugin",
        "-fplugin",
        "-include",
        "-include-pch",
        "-include-pth",
        "-fmodule-file",
        "-fmodule-map-file",
        "-serialize-diagnostics",
        "--serialize-diagnostics",
        "-dependency-file",
    }
)
_UNSAFE_PREFIXES = (
    "@",
    "-fplugin=",
    "-fplugin-arg-",
    "-fmodule",
    "-fcxx-modules",
    "-fmodules",
    "-module",
    "-include-pch=",
    "-include-pth=",
    "-fmodule-file=",
    "-fmodule-map-file=",
    "-o",
    "-MF",
    "-MT",
    "-MQ",
    "-MJ",
    "-MD",
    "-MMD",
    "-MP",
    "-save-temps",
    "-ftime-trace",
    "-serialize-diagnostics",
    "--serialize-diagnostics",
    "-Wa,",
    "-Wl,",
    "-fuse-ld",
    "--offload",
    "-foffload",
    "-fopenmp-targets",
    "--cuda",
    "--hip",
)
_SAFE_EXACT = frozenset({"-nostdinc", "-nostdinc++", "-pthread", "-fsyntax-only"})
_SAFE_TARGET_PREFIXES = (
    "-march=",
    "-mcpu=",
    "-mtune=",
    "-mabi=",
    "-mfpmath=",
    "-mfloat-abi=",
    "-mno-",
    "-msse",
    "-mavx",
)


def normalize_clang_arguments(context: CompilationUnitContext, project_root: Path) -> ClangArgumentResult:
    """按白名单策略过滤编译参数，返回 (接受序列, 拒绝诊断)。

    思路：单遍扫描 token；跳过首个非选项的编译器名；-c 丢弃；不安全
    的成对选项连同其值一起拒绝；安全成对选项（-I/-D 等）中的路径类
    值归一为绝对路径（相对 directory 解析）；未知 - 开头选项默认拒绝
    （fail-closed）；非选项操作数（源文件等）静默丢弃，不进入 libclang
    参数表。
    """
    entry_id = compilation_context_id(context, project_root)
    tokens = list(context.tokens)
    accepted: list[str] = []
    diagnostics: list[Diagnostic] = []
    index = 0
    if tokens and not tokens[0].startswith("-"):
        index = 1
    while index < len(tokens):
        token = tokens[index]
        if token == "-c":
            index += 1
            continue
        if token in _UNSAFE_PAIRED:
            value = tokens[index + 1] if index + 1 < len(tokens) else None
            diagnostics.append(_rejected(entry_id, token, value is not None))
            index += 2 if value is not None else 1
            continue
        if token.startswith(_UNSAFE_PREFIXES):
            diagnostics.append(_rejected(entry_id, _option_name(token), False))
            index += 1
            continue
        if token in _SAFE_PAIRED:
            if index + 1 >= len(tokens):
                diagnostics.append(_rejected(entry_id, token, False, code="clang.argument-missing-value"))
                index += 1
                continue
            value = tokens[index + 1]
            if token in _PATH_PAIRED:
                value = _normalize_path_value(value, context.directory or project_root)
            accepted.extend((token, value))
            index += 2
            continue
        if token.startswith(
            ("--target=", "--sysroot=", "-std=", "-D", "-U", "-isystem", "-iquote", "-idirafter", "-I")
        ) and len(token) > 2:
            accepted.append(_normalize_joined_path(token, context.directory or project_root))
            index += 1
            continue
        if token in _SAFE_EXACT or token in {"-m32", "-m64"} or token.startswith(_SAFE_TARGET_PREFIXES):
            accepted.append(token)
            index += 1
            continue
        if token.startswith("-"):
            diagnostics.append(_rejected(entry_id, _option_name(token), False))
            index += 1
            continue
        # Compiler inputs and driver operands are not passed to libclang's args list.
        index += 1
    return ClangArgumentResult(tuple(accepted), tuple(diagnostics))


def _normalize_path_value(value: str, directory: Path) -> str:
    """把参数中的路径值解析为绝对路径：绝对路径直接 resolve，相对路径基于 directory。"""
    path = Path(value)
    return str(path.resolve() if path.is_absolute() else (directory / path).resolve())


def _normalize_joined_path(token: str, directory: Path) -> str:
    """归一连写形式（如 -I/opt/include、--sysroot=/x）中内嵌的路径值。"""
    for prefix in ("--sysroot=", "-isystem", "-iquote", "-idirafter", "-I"):
        if token.startswith(prefix):
            value = token[len(prefix) :]
            return f"{prefix}{_normalize_path_value(value, directory)}"
    return token


def _option_name(token: str) -> str:
    """取选项名（去掉 =value 部分），用于诊断展示。"""
    return token.split("=", 1)[0]


def _rejected(entry_id: str, option: str, had_value: bool, *, code: str = "clang.argument-rejected") -> Diagnostic:
    """构造"选项被拒绝"的 WARNING 诊断；had_value 标记成对选项的值是否一并移除。"""
    return Diagnostic(
        code=code,
        stage="frontend.clang.arguments",
        severity=DiagnosticSeverity.WARNING,
        message=f"Rejected Clang parsing option: {option}",
        producer="clang-argument-policy",
        details={"compilationContextId": entry_id, "option": option, "removedPairedValue": had_value},
    )
