"""构建输入的工作区收集：在 C/C++ 工作区之上补充构建系统文件。

workspace 阶段：先用 CppWorkspaceCollector 收集常规源文件，再递归
遍历目录找出 Makefile/configure/CMakeLists/RPM spec 等构建输入，
过滤排除规则并拒绝逃逸出工作区根的路径，输出 WorkspaceSnapshot。
"""
from __future__ import annotations

from pathlib import Path, PurePosixPath

from ...core import CompletionStatus, Diagnostic, DiagnosticSeverity, ScanContext, StageResult, WorkspaceSnapshot
from ..cpp.workspace import CppWorkspaceCollector
from .vocabulary import BuildInputKind


_MAKE_NAMES = frozenset({"makefile", "gnumakefile", "bsdmakefile"})


def build_input_kind(path: str) -> BuildInputKind | None:
    """按相对路径（文件名 + 目录部件）判定构建输入类型；非构建文件返回 None。

    判定规则按优先级排列：Makefile 家族 → configure 脚本 → .m4 →
    compile_commands.json → ctest 元数据 → meson introspection →
    CMake FileAPI reply → CMakeLists/.cmake 源码 → RPM spec。
    路径分隔符统一为 POSIX 风格，比较不分大小写。
    """
    normalized = path.replace("\\", "/")
    name = PurePosixPath(normalized).name
    lowered = name.lower()
    parts = {part.lower() for part in PurePosixPath(normalized).parts}
    if lowered in _MAKE_NAMES or lowered.endswith(".mk") or lowered.startswith("makefile."):
        return BuildInputKind.MAKE
    if lowered in {"configure", "config.sub", "config.guess"}:
        return BuildInputKind.SHELL
    if lowered == "configure.ac" or lowered.endswith(".m4"):
        return BuildInputKind.M4
    if lowered == "compile_commands.json":
        return BuildInputKind.COMPILATION_DATABASE
    if lowered == "ctesttestfile.cmake" or lowered.startswith("ctest") and lowered.endswith(".json"):
        return BuildInputKind.CTEST
    if "meson-info" in parts and lowered.startswith("intro-") and lowered.endswith(".json"):
        return BuildInputKind.MESON
    if ".cmake" in parts and "reply" in parts and lowered.endswith(".json"):
        return BuildInputKind.CMAKE
    if lowered == "cmakelists.txt" or lowered.endswith(".cmake"):
        return BuildInputKind.CMAKE_SOURCE
    if lowered.endswith(".spec"):
        return BuildInputKind.RPM
    return None


def is_build_input(path: str) -> bool:
    """判断路径是否属于任一构建输入类型。"""
    return build_input_kind(path) is not None


class ProjectWorkspaceCollector:
    """工作区收集器：合并 C/C++ 源文件清单与构建输入清单。

    位于 workspace 阶段。不变量：输出 files 恒为排序后的 POSIX 相对
    路径元组；符号链接解析后逃逸出输入根的路径一律跳过并记 WARNING。
    """

    __slots__ = ("_cpp",)

    def __init__(self, cpp: CppWorkspaceCollector | None = None) -> None:
        """cpp 参数可注入自定义 C/C++ 收集器（测试用），默认全新实例。"""
        self._cpp = cpp or CppWorkspaceCollector()

    def collect(self, context: ScanContext) -> StageResult[WorkspaceSnapshot]:
        """收集工作区快照。

        - 输入是单文件且是构建输入：以父目录为根、单文件为清单。
        - 输入是目录：先跑 C/C++ 收集，再 rglob 补充构建输入，
          应用 excludes（目录名级）与 exclude_paths（路径前缀级）过滤。
        - 输入不存在：FAILED；遍历失败/路径逃逸只降级为 PARTIAL。
        """
        source = context.input_root
        if not source.exists():
            return _failed("workspace.input-missing", "Input path does not exist")
        if source.is_file():
            kind = build_input_kind(source.name)
            if kind is not None:
                return StageResult(WorkspaceSnapshot(source.parent.resolve(), (source.name,)))
            return self._cpp.collect(context)

        cpp_result = self._cpp.collect(context)
        if cpp_result.status is CompletionStatus.FAILED or cpp_result.value is None:
            return cpp_result
        root = source.resolve()
        files = set(cpp_result.value.files)
        diagnostics = list(cpp_result.diagnostics)
        try:
            candidates = sorted((item for item in source.rglob("*") if item.is_file()), key=lambda item: item.as_posix())
        except OSError as exc:
            return _failed("workspace.discovery-failed", f"Workspace discovery failed: {type(exc).__name__}")
        for candidate in candidates:
            relative = candidate.relative_to(source).as_posix()
            # 先应用排除规则，再确认是构建输入，二者都不满足才跳过。
            if _excluded(relative, context.excludes, context.exclude_paths) or not is_build_input(relative):
                continue
            try:
                resolved = candidate.resolve()
            except OSError as exc:
                diagnostics.append(_warning("workspace.build-path-unreadable", f"Skipped unreadable build path {relative}: {type(exc).__name__}"))
                continue
            if not resolved.is_relative_to(root):
                diagnostics.append(_warning("workspace.build-path-escape", f"Skipped build path outside workspace: {relative}"))
                continue
            files.add(PurePosixPath(relative).as_posix())
        status = CompletionStatus.PARTIAL if diagnostics else cpp_result.status
        return StageResult(WorkspaceSnapshot(root, tuple(sorted(files))), status, tuple(diagnostics))


def _excluded(relative_path: str, excludes: tuple[str, ...], exclude_paths: tuple[str, ...]) -> bool:
    """判断路径是否命中排除规则。

    excludes 按目录名匹配（父目录任一段命中即排除）；
    exclude_paths 按路径前缀匹配（相等或以 prefix/ 开头即排除）。
    """
    relative = PurePosixPath(relative_path)
    if any(name in set(relative.parts[:-1]) for name in excludes):
        return True
    normalized = relative.as_posix()
    return any(
        normalized == raw.replace("\\", "/").strip("/")
        or normalized.startswith(raw.replace("\\", "/").strip("/") + "/")
        for raw in exclude_paths
        if raw.replace("\\", "/").strip("/")
    )


def _warning(code: str, message: str) -> Diagnostic:
    """构造 workspace 阶段 WARNING 诊断。"""
    return Diagnostic(code, "workspace", DiagnosticSeverity.WARNING, message, producer="project-workspace-collector")


def _failed(code: str, message: str) -> StageResult[WorkspaceSnapshot]:
    """构造 workspace 阶段 FAILED 结果（影响退出码）。"""
    diagnostic = Diagnostic(code, "workspace", DiagnosticSeverity.ERROR, message, producer="project-workspace-collector", affects_exit_code=True)
    return StageResult(None, CompletionStatus.FAILED, (diagnostic,))
