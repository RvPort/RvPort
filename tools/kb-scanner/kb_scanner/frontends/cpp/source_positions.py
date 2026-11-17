"""字节偏移到 1-based 行列坐标的只读映射，供 tree-sitter 前端定位源码。

tree-sitter 返回的是 0-based 字节偏移，而扫描器对外统一使用 1-based
行列位置；本模块在解析时预先缓存每个换行符的位置，之后用二分查找
把任意偏移换算为 (line, column)。
"""
from __future__ import annotations

from bisect import bisect_right
from dataclasses import dataclass

from ...core import SourceLocation, SourceSpan, normalize_project_path


@dataclass(frozen=True, slots=True)
class SourceByteIndex:
    """Map parser byte offsets to the scanner's 1-based source locations.

    不可变（frozen）：构建时固化换行符偏移表 line_starts，此后只读。
    所有公开方法都会校验偏移范围，越界抛 ValueError/TypeError。
    """

    path: str
    source_length: int
    line_starts: tuple[int, ...]

    @classmethod
    def build(cls, path: str, source: bytes) -> SourceByteIndex:
        """从源码字节流构建索引：记录每个换行符（0x0A）后的起始偏移。"""
        if not isinstance(source, bytes):
            raise TypeError("source must be bytes")
        starts = (0, *(index + 1 for index, value in enumerate(source) if value == 0x0A))
        return cls(normalize_project_path(path), len(source), starts)

    def location(self, offset: int) -> SourceLocation:
        """把单个字节偏移转换为公开的 SourceLocation（含路径）。"""
        return SourceLocation(self.path, *self.coordinates(offset))

    def coordinates(self, offset: int) -> tuple[int, int]:
        """内部标量记录只需行列；公开 SourceLocation 仍保留完整路径校验。"""
        self._validate_offset(offset)
        line_index = bisect_right(self.line_starts, offset) - 1
        return line_index + 1, offset - self.line_starts[line_index] + 1

    def coordinate_span(self, start: int, end: int) -> tuple[int, int, int, int]:
        """把字节区间转换为 (起行,起列,止行,止列) 四元组标量记录。"""
        first, last = self.coordinates(start), self.coordinates(end)
        if end < start:
            raise ValueError("source byte range end must not precede start")
        return (*first, *last)

    def span(self, start: int, end: int) -> SourceSpan:
        """把字节区间转换为公开的 SourceSpan，两端偏移均先校验。"""
        self._validate_offset(start)
        self._validate_offset(end)
        if end < start:
            raise ValueError("source byte range end must not precede start")
        return SourceSpan(self.location(start), self.location(end))

    def _validate_offset(self, offset: int) -> None:
        if isinstance(offset, bool) or not isinstance(offset, int):
            raise TypeError("source byte offset must be an integer")
        if offset < 0 or offset > self.source_length:
            raise ValueError("source byte offset is outside the source")
