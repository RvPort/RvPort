"""源码位置模型：项目相对路径归一化与位置/区间的不可变表示。

所有指向源码的位置（SourceLocation/SourceSpan）都强制使用归一化的
POSIX 风格项目相对路径，这是稳定ID可跨环境复现的前提。
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path, PurePosixPath


def normalize_project_path(path: str | Path, project_root: Path | None = None) -> str:
    """把任意路径归一化为 POSIX 风格的项目相对路径字符串。

    绝对路径必须提供 project_root 且必须位于其内，否则抛 ValueError；
    相对路径去掉 "." 段并统一 "\\" 为 "/"。拒绝空路径、含 ".." 的路径
    和绝对路径。这是所有源码位置入池前的强制归一化步骤。
    """
    candidate = Path(path)
    if candidate.is_absolute():
        if project_root is None:
            raise ValueError("absolute source paths require a project root")
        root = project_root.resolve()
        resolved = candidate.resolve()
        try:
            candidate = resolved.relative_to(root)
        except ValueError as exc:
            raise ValueError("source path must be inside the project root") from exc

    raw = str(candidate).replace("\\", "/")
    normalized = PurePosixPath(raw)
    parts = tuple(part for part in normalized.parts if part not in {"", "."})
    if not parts or any(part == ".." for part in parts) or normalized.is_absolute():
        raise ValueError("source path must be a non-empty project-relative path")
    return PurePosixPath(*parts).as_posix()


@dataclass(frozen=True, slots=True)
class SourceLocation:
    """源码中的一个精确位置（frozen 不可变；path 经强制归一化）。

    line/column 均从 1 开始；column 允许为 None（前端未提供列信息时）。
    """

    path: str
    line: int
    column: int | None = None

    def __post_init__(self) -> None:
        object.__setattr__(self, "path", normalize_project_path(self.path))
        if self.line < 1:
            raise ValueError("line must be at least 1")
        if self.column is not None and self.column < 1:
            raise ValueError("column must be at least 1")


@dataclass(frozen=True, slots=True)
class SourceSpan:
    """同一文件内的一段源码区间 [start, end]，end 不得早于 start。

    column 缺失时按 1 参与比较，保证仅有行号的端点也能正确排序。
    """

    start: SourceLocation
    end: SourceLocation

    def __post_init__(self) -> None:
        if self.start.path != self.end.path:
            raise ValueError("source span endpoints must use the same path")
        start_position = (self.start.line, self.start.column or 1)
        end_position = (self.end.line, self.end.column or 1)
        if end_position < start_position:
            raise ValueError("source span end must not precede its start")
