"""构建 frontend 的安全基线：符号化 uname、架构规范化与危险构造清单。

所有解析器共用的"不执行"保障层：只允许符号化的 uname -m 求值，
并集中登记各构建语言中不可静态求值的危险构造（shell 函数调用、
syscmd、execute_process 等）。
"""
from __future__ import annotations

import re
from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class CommandDisposition:
    """命令的静态判定结果：是否可识别、符号化值与原因（frozen 不可变）。"""

    recognized: bool
    value: str | None
    reason: str


_UNAME_FORMS = (
    re.compile(r"^uname\s+-m$"),
    re.compile(r"^\$\(\s*uname\s+-m\s*\)$"),
    re.compile(r"^`\s*uname\s+-m\s*`$"),
    re.compile(r"^\$\(\s*shell\s+uname\s+-m\s*\)$"),
)


UNSAFE_PATTERNS: dict[str, tuple[tuple[str, re.Pattern[str]], ...]] = {
    "make": (
        ("shell-function", re.compile(r"\$\(\s*shell\b", re.IGNORECASE)),
        ("shell-assignment", re.compile(r"(?m)^\s*[^#\n]+?\s*!=\s*.+$")),
        ("eval-function", re.compile(r"\$\(\s*eval\b", re.IGNORECASE)),
        ("dynamic-function", re.compile(r"\$\(\s*(?:call|foreach|wildcard)\b", re.IGNORECASE)),
    ),
    "m4": (
        ("m4-syscmd", re.compile(r"\b(?:m4_)?syscmd\s*\(", re.IGNORECASE)),
        ("m4-esyscmd", re.compile(r"\b(?:m4_)?esyscmd\s*\(", re.IGNORECASE)),
        ("m4-include", re.compile(r"\b(?:m4_)?include\s*\(", re.IGNORECASE)),
    ),
    "cmake": (("cmake-execute-process", re.compile(r"\bexecute_process\s*\(", re.IGNORECASE)),),
    "rpm": (
        ("rpm-shell-expansion", re.compile(r"%\(")),
        ("rpm-lua-expansion", re.compile(r"%\{\s*lua\s*:", re.IGNORECASE)),
        ("rpm-include", re.compile(r"(?m)^\s*%include\b", re.IGNORECASE)),
    ),
}


def normalize_target_arch(target_arch: str) -> str:
    """把目标架构别名规范为 "riscv32"/"riscv64"；仅支持 RISC-V，其他抛 ValueError。"""
    value = target_arch.strip().lower().replace("_", "-")
    if value.startswith("riscv32") or value in {"rv32", "risc-v32"}:
        return "riscv32"
    if value.startswith("riscv64") or value in {"riscv", "rv64", "risc-v", "risc-v64"}:
        return "riscv64"
    raise ValueError("build selection only supports RISC-V target architectures")


def symbolic_uname(command: str, target_arch: str) -> CommandDisposition:
    """符号化求值 uname -m：仅识别四种标准写法，返回目标架构字符串。

    这是对 shell 探测的唯一合法替身——不运行命令，直接把
    `uname -m` / `$(uname -m)` 等映射为规范化的目标架构。
    """
    candidate = " ".join(command.strip().split())
    if any(pattern.fullmatch(candidate) for pattern in _UNAME_FORMS):
        return CommandDisposition(True, normalize_target_arch(target_arch), "symbolic-uname-m")
    return CommandDisposition(False, None, "unsupported-command-shape")


def unsafe_constructs(source: str, input_kind: str) -> tuple[tuple[str, int, str], ...]:
    """扫描源文本中该构建语言的全部危险构造，返回 (名称, 行号, 行文本)。

    例外：make 的 shell 函数/赋值若是可符号化的 uname -m，则不算危险。
    """
    found: list[tuple[str, int, str]] = []
    lines = source.splitlines()
    for name, pattern in UNSAFE_PATTERNS.get(input_kind, ()):
        for match in pattern.finditer(source):
            line = source.count("\n", 0, match.start()) + 1
            text = lines[line - 1].strip() if line <= len(lines) else ""
            if input_kind == "make" and name in {"shell-function", "shell-assignment"}:
                command = _make_command(text, name)
                if command is not None and symbolic_uname(command, "riscv64").recognized:
                    continue
            found.append((name, line, text))
    return tuple(sorted(found, key=lambda item: (item[1], item[0], item[2])))


def _make_command(line: str, construct: str) -> str | None:
    """从 make 行文本中提取被 $(shell ...)/!= 包裹的命令文本。"""
    if construct == "shell-function":
        match = re.search(r"\$\(\s*shell\s+([^)]*)\)", line, re.IGNORECASE)
        return match.group(1).strip() if match else None
    if construct == "shell-assignment":
        return line.split("!=", 1)[1].strip() if "!=" in line else None
    return None
