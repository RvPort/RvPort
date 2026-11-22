"""compile_commands.json 读取器：把编译数据库归一为编译单元上下文。

frontend 阶段的前置输入：解析 JSON 编译数据库（支持 arguments 数组
与 command 字符串两种形式），校验每条目的源文件必须落在项目根内，
并提取包含目录/宏定义/语言标准等结构化字段。数据库缺失只是
UNAVAILABLE（基线解析照常），条目级问题降级为 PARTIAL。
"""
from __future__ import annotations

import json
import shlex
from pathlib import Path

from ...core import (
    CompilationUnitContext,
    CompletionStatus,
    Diagnostic,
    DiagnosticSeverity,
    StageResult,
    normalize_project_path,
    stable_id,
)


class CompilationDatabaseReader:
    """编译数据库读取器：单入口 read()，无状态（slots 为空）。

    不变量：返回的每个 CompilationUnitContext.path 都是解析后的绝对
    路径且位于 project_root 内；上下文按 (相对路径, entry_index) 排序，
    保证同一输入得到确定顺序。
    """

    __slots__ = ()

    def read(
        self,
        path: Path | None,
        project_root: Path,
    ) -> StageResult[tuple[CompilationUnitContext, ...]]:
        """读取并校验编译数据库，返回排序后的编译单元上下文元组。

        path 为 None 或不存在 → UNAVAILABLE（附 INFO 诊断，属正常路径）；
        JSON 无效/根不是数组 → UNAVAILABLE；单条目缺 file、源文件逃逸
        工作区、命令 tokenize 失败 → 跳过该条目并记 WARNING，其余继续。
        """
        if path is None or not path.exists():
            diagnostic = Diagnostic(
                code="compilation-database.missing",
                stage="compilation-database",
                severity=DiagnosticSeverity.INFO,
                message="Compilation database is not available; baseline parsing remains enabled",
                producer="compilation-database-reader",
            )
            return StageResult(value=None, status=CompletionStatus.UNAVAILABLE, diagnostics=(diagnostic,))
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except Exception as exc:  # noqa: BLE001 - invalid external input is a supported path
            return _unavailable("compilation-database.invalid-json", f"Compilation database is invalid: {type(exc).__name__}")
        if not isinstance(payload, list):
            return _unavailable("compilation-database.invalid-shape", "Compilation database root must be an array")

        root = project_root.resolve()
        contexts: list[CompilationUnitContext] = []
        diagnostics: list[Diagnostic] = []
        for entry_index, entry in enumerate(payload):
            if not isinstance(entry, dict) or not isinstance(entry.get("file"), str):
                diagnostics.append(_entry_diagnostic("compilation-database.missing-file", entry_index, "Entry has no string file field"))
                continue
            directory = Path(str(entry.get("directory") or path.parent))
            if not directory.is_absolute():
                directory = (path.parent / directory).resolve()
            else:
                directory = directory.resolve()
            source = Path(entry["file"])
            if not source.is_absolute():
                source = (directory / source).resolve()
            else:
                source = source.resolve()
            if not source.is_relative_to(root):
                diagnostics.append(
                    _entry_diagnostic(
                        "compilation-database.source-outside-workspace",
                        entry_index,
                        "Entry source resolves outside the project",
                    )
                )
                continue

            tokens, raw_command, token_error = _entry_tokens(entry)
            if token_error is not None:
                diagnostics.append(_entry_diagnostic("compilation-database.invalid-command", entry_index, token_error))
                continue
            include_dirs, defines, language_standard = _parse_context_tokens(tokens)
            compiler = _compiler_name(tokens[0]) if tokens else None
            contexts.append(
                CompilationUnitContext(
                    path=source,
                    include_dirs=tuple(include_dirs),
                    defines=tuple(defines.items()),
                    language_standard=language_standard,
                    raw_command=raw_command,
                    tokens=tuple(tokens),
                    entry_index=entry_index,
                    compiler=compiler,
                    directory=directory,
                )
            )

        ordered = tuple(sorted(contexts, key=lambda item: (normalize_project_path(item.path, root), item.entry_index)))
        if not ordered:
            status = CompletionStatus.UNAVAILABLE
            value = None
        elif diagnostics:
            status = CompletionStatus.PARTIAL
            value = ordered
        else:
            status = CompletionStatus.COMPLETE
            value = ordered
        return StageResult(value=value, status=status, diagnostics=tuple(diagnostics))


def compilation_context_id(context: CompilationUnitContext, project_root: Path) -> str:
    """编译上下文的稳定ID（SHA-256）：同一编译命令在同一项目内得到同一 ID。

    token 先经 _identity_token 归一（项目根/命令目录替换为占位符、外部
    路径折叠为 $EXTERNAL/文件名），避免仅因绝对路径不同而产生不同 ID，
    同时保留会改变解析语义的差异。
    """
    root = project_root.resolve()
    relative_source = normalize_project_path(context.path, root)
    identity_tokens = tuple(_identity_token(token, root, context.directory) for token in context.tokens)
    return stable_id(
        "compilation_context",
        1,
        relative_source,
        context.entry_index,
        context.defines,
        context.language_standard,
        identity_tokens,
    )


def _entry_tokens(entry: dict[str, object]) -> tuple[list[str], str | None, str | None]:
    """取条目命令 token：优先 arguments 数组，否则 shlex 切分 command 字符串。

    返回 (tokens, raw_command, error)；两者都缺省时返回空 token 且不报错
    （该条目仍可作为无参上下文参与）。
    """
    arguments = entry.get("arguments")
    command = entry.get("command")
    if isinstance(arguments, list):
        if not all(isinstance(item, (str, int, float)) for item in arguments):
            return [], None, "arguments must contain scalar tokens"
        tokens = [str(item) for item in arguments]
        return tokens, " ".join(tokens), None
    if isinstance(command, str):
        try:
            return shlex.split(command, posix=True), command, None
        except ValueError as exc:
            return [], command, f"command tokenization failed: {type(exc).__name__}"
    return [], None, None


def _parse_context_tokens(tokens: list[str]) -> tuple[list[str], dict[str, str | None], str | None]:
    """从命令 token 中提取 (include 目录, 宏定义, 语言标准)。

    单遍扫描：-I/-isystem 等支持分离与连写两种形式；-D 支持
    -DNAME=VALUE 与 -DNAME（值为 None）；-std= 只保留最后一个。
    """
    include_dirs: list[str] = []
    defines: dict[str, str | None] = {}
    language_standard: str | None = None
    index = 0
    while index < len(tokens):
        token = tokens[index]
        if token in {"-I", "-isystem", "-iquote", "-idirafter"} and index + 1 < len(tokens):
            include_dirs.append(tokens[index + 1])
            index += 2
            continue
        joined_include_prefix = next(
            (prefix for prefix in ("-isystem", "-iquote", "-idirafter", "-I") if token.startswith(prefix) and len(token) > len(prefix)),
            None,
        )
        if joined_include_prefix is not None:
            include_dirs.append(token[len(joined_include_prefix) :])
        elif token == "-D" and index + 1 < len(tokens):
            _add_define(defines, tokens[index + 1])
            index += 2
            continue
        elif token.startswith("-D") and len(token) > 2:
            _add_define(defines, token[2:])
        elif token.startswith("-std="):
            language_standard = token.removeprefix("-std=")
        index += 1
    return include_dirs, defines, language_standard


def _add_define(defines: dict[str, str | None], raw: str) -> None:
    """解析单个 -D 参数：NAME=VALUE 拆分键值，仅 NAME 则值为 None。"""
    if "=" in raw:
        key, value = raw.split("=", 1)
        defines[key] = value
    else:
        defines[raw] = None


def _compiler_name(value: str) -> str:
    """从编译器路径（含反斜杠）取最后的文件名部分。"""
    return value.replace("\\", "/").rsplit("/", 1)[-1]


def _identity_token(token: str, root: Path, directory: Path | None) -> str:
    """把单个 token 归一为身份等价形式，用于稳定ID 计算。

    项目根 → $PROJECT、命令目录 → $COMMAND_DIR（换机器/换检出路径不
    改变 ID）；include 前缀外的绝对路径折叠为 $EXTERNAL/文件名（外部
    依赖只按名字参与身份，具体安装位置不算语义差异）。
    """
    normalized = token.replace("\\", "/")
    root_text = root.as_posix().rstrip("/")
    if root_text and root_text in normalized:
        normalized = normalized.replace(root_text, "$PROJECT")
    if directory is not None:
        directory_text = directory.resolve().as_posix().rstrip("/")
        if directory_text and directory_text != root_text and directory_text in normalized:
            normalized = normalized.replace(directory_text, "$COMMAND_DIR")
    path_prefixes = ("-I", "-isystem", "-iquote", "-idirafter", "--sysroot=")
    for prefix in path_prefixes:
        if normalized.startswith(prefix):
            suffix = normalized[len(prefix) :]
            if suffix.startswith("/") and not suffix.startswith("$PROJECT"):
                return f"{prefix}$EXTERNAL/{Path(suffix).name}"
    if normalized.startswith("/"):
        return f"$EXTERNAL/{Path(normalized).name}"
    return normalized


def _entry_diagnostic(code: str, entry_index: int, message: str) -> Diagnostic:
    """构造条目级 WARNING 诊断，details 携带 entryIndex 便于定位。"""
    return Diagnostic(
        code=code,
        stage="compilation-database",
        severity=DiagnosticSeverity.WARNING,
        message=f"Compilation database entry {entry_index}: {message}",
        producer="compilation-database-reader",
        details={"entryIndex": entry_index},
    )


def _unavailable(code: str, message: str) -> StageResult[tuple[CompilationUnitContext, ...]]:
    """构造整体 UNAVAILABLE 的 StageResult（数据库级失败，非条目级）。"""
    diagnostic = Diagnostic(
        code=code,
        stage="compilation-database",
        severity=DiagnosticSeverity.WARNING,
        message=message,
        producer="compilation-database-reader",
    )
    return StageResult(value=None, status=CompletionStatus.UNAVAILABLE, diagnostics=(diagnostic,))
