"""C/C++ workspace 阶段：发现输入文件并产出 WorkspaceSnapshot。

kb-scanner 八阶段流水线的第一阶段（workspace）：枚举输入目录下所有
受支持的 C/C++ 源文件与头文件，应用排除规则，并防御符号链接逃逸
（解析后必须仍位于项目根内）。产出排序去重后的相对路径清单。
"""
from __future__ import annotations

from pathlib import Path, PurePosixPath

from ...core import (
    CompletionStatus,
    Diagnostic,
    DiagnosticSeverity,
    ScanContext,
    StageResult,
    WorkspaceSnapshot,
)


C_SUFFIXES = frozenset({".c"})
CPP_SUFFIXES = frozenset({".cc", ".cpp", ".cxx"})
HEADER_SUFFIXES = frozenset({".h", ".hh", ".hpp", ".hxx", ".inc"})
# 汇编输入后缀（与 frontends/asm/vocabulary.ASM_SUFFIXES 一致；.inc 保持 C 头语义不列入）。
ASM_SUFFIXES = frozenset({".s", ".S", ".asm"})
SUPPORTED_SUFFIXES = C_SUFFIXES | CPP_SUFFIXES | HEADER_SUFFIXES


class CppWorkspaceCollector:
    """workspace 阶段收集器：只发现文件，不读取内容、不解析语法。

    不变量：files 中的路径都是 POSIX 风格相对路径，且已按字典序排序
    去重；任何解析后逃逸出项目根的路径都会被跳过并记录诊断。
    """

    __slots__ = ()

    def collect(self, context: ScanContext) -> StageResult[WorkspaceSnapshot]:
        """扫描 context.input_root，返回受支持源文件的 WorkspaceSnapshot。

        输入可以是单文件（父目录作为根）或目录（递归枚举）。输入缺失
        或类型不支持分别返回 FAILED / UNAVAILABLE；目录枚举期间个别
        路径不可读或逃逸只降级为 PARTIAL，不阻断整体。
        """
        source = context.input_root
        if not source.exists():
            diagnostic = _workspace_diagnostic("workspace.input-missing", "Input path does not exist", error=True)
            return StageResult(value=None, status=CompletionStatus.FAILED, diagnostics=(diagnostic,))

        if source.is_file():
            root = source.parent.resolve()
            if not _is_supported(source, context.languages):
                diagnostic = _workspace_diagnostic(
                    "workspace.unsupported-input",
                    "Input file is not a supported C/C++ source or header",
                    error=True,
                )
                return StageResult(value=None, status=CompletionStatus.UNAVAILABLE, diagnostics=(diagnostic,))
            return StageResult(value=WorkspaceSnapshot(root=root, files=(source.name,)))

        root = source.resolve()
        files: list[str] = []
        diagnostics: list[Diagnostic] = []
        try:
            candidates = sorted((item for item in source.rglob("*") if item.is_file()), key=lambda item: item.as_posix())
        except OSError as exc:
            diagnostic = _workspace_diagnostic(
                "workspace.discovery-failed",
                f"Workspace discovery failed: {type(exc).__name__}",
                error=True,
            )
            return StageResult(value=None, status=CompletionStatus.FAILED, diagnostics=(diagnostic,))

        for candidate in candidates:
            try:
                relative = candidate.relative_to(source).as_posix()
            except ValueError:
                continue
            if _is_excluded(relative, context.excludes, context.exclude_paths):
                continue
            if not _is_supported(candidate, context.languages):
                continue
            try:
                resolved = candidate.resolve()
            except OSError as exc:
                diagnostics.append(
                    _workspace_diagnostic(
                        "workspace.path-unreadable",
                        f"Skipped unreadable workspace path {relative}: {type(exc).__name__}",
                    )
                )
                continue
            if not resolved.is_relative_to(root):
                diagnostics.append(
                    _workspace_diagnostic(
                        "workspace.path-escape",
                        f"Skipped workspace path that resolves outside the project: {relative}",
                    )
                )
                continue
            files.append(PurePosixPath(relative).as_posix())

        status = CompletionStatus.PARTIAL if diagnostics else CompletionStatus.COMPLETE
        return StageResult(
            value=WorkspaceSnapshot(root=root, files=tuple(sorted(set(files)))),
            status=status,
            diagnostics=tuple(diagnostics),
        )


def language_candidates_for_path(path: str, languages: tuple[str, ...] = ("c", "cpp")) -> tuple[str, ...]:
    """按后缀推断语言候选：.c→c，.cc/.cpp/.cxx→cpp，头文件→两者皆可，
    .s/.S/.asm→asm（汇编输入域，默认不启用，opt-in）。

    返回值会与 context.languages（启用的语言开关）取交集；后缀不识别
    或语言未启用时返回空元组（该文件不受支持）。
    """
    enabled = set(languages)
    suffix = Path(path).suffix.lower()
    if suffix in C_SUFFIXES:
        return ("c",) if "c" in enabled else ()
    if suffix in CPP_SUFFIXES:
        return ("cpp",) if "cpp" in enabled else ()
    if suffix in HEADER_SUFFIXES:
        return tuple(item for item in ("c", "cpp") if item in enabled)
    if suffix in ASM_SUFFIXES:
        return ("asm",) if "asm" in enabled else ()
    return ()


def _is_supported(path: Path, languages: tuple[str, ...]) -> bool:
    """文件是否为受支持且语言已启用的 C/C++ 源/头文件。"""
    return bool(language_candidates_for_path(path.name, languages))


def _is_excluded(relative_path: str, excludes: tuple[str, ...], exclude_paths: tuple[str, ...]) -> bool:
    """判断相对路径是否命中排除规则：目录名匹配 excludes，或路径前缀匹配 exclude_paths。

    excludes 只匹配路径中的目录名部分；exclude_paths 归一化
    （反斜杠→正斜杠）后做整路径或目录前缀比较。
    """
    relative = PurePosixPath(relative_path)
    directory_parts = set(relative.parts[:-1])
    if any(name in directory_parts for name in excludes):
        return True
    normalized = relative.as_posix()
    for raw in exclude_paths:
        excluded = PurePosixPath(str(raw).replace("\\", "/").strip("/")).as_posix()
        if excluded in {"", "."}:
            continue
        if normalized == excluded or normalized.startswith(f"{excluded}/"):
            return True
    return False


def _workspace_diagnostic(code: str, message: str, *, error: bool = False) -> Diagnostic:
    """构造 workspace 阶段诊断；error=True 时影响退出码。"""
    return Diagnostic(
        code=code,
        stage="workspace",
        severity=DiagnosticSeverity.ERROR if error else DiagnosticSeverity.WARNING,
        message=message,
        producer="cpp-workspace-collector",
        affects_exit_code=error,
    )
