"""诊断（Diagnostic）模型：各阶段报告问题与警告的统一通道。

诊断贯穿整个流水线（stage 标识产出阶段，severity 分四级），
最终汇入 PipelineResult 供 CLI 呈现；重复诊断按 identity 去重合并。
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from enum import Enum

from .location import SourceLocation
from .metadata import JsonValue, freeze_metadata


class DiagnosticSeverity(str, Enum):
    """诊断严重级别：info < warning < error < fatal。"""

    INFO = "info"
    WARNING = "warning"
    ERROR = "error"
    FATAL = "fatal"


@dataclass(frozen=True, slots=True)
class Diagnostic:
    """单条诊断（frozen 不可变）：code+stage+message 定位问题。

    affects_exit_code 控制该诊断是否影响进程退出码（多数警告不影响）；
    location/producer/details 均可缺省，details 冻结为只读映射。
    """

    code: str
    stage: str
    severity: DiagnosticSeverity
    message: str
    location: SourceLocation | None = None
    producer: str | None = None
    details: Mapping[str, JsonValue] | None = None
    affects_exit_code: bool = False

    def __post_init__(self) -> None:
        if not all((self.code, self.stage, self.message)):
            raise ValueError("diagnostic code, stage and message must not be empty")
        object.__setattr__(self, "details", freeze_metadata(self.details))

    @property
    def identity(self) -> tuple[object, ...]:
        """诊断的去重键：同一 code/stage/severity/message/位置/producer 视为重复。"""
        location = None
        if self.location is not None:
            location = (self.location.path, self.location.line, self.location.column)
        return self.code, self.stage, self.severity.value, self.message, location, self.producer


def merge_diagnostics(*groups: Iterable[Diagnostic]) -> tuple[Diagnostic, ...]:
    """合并多组诊断并按 identity 去重，保持首次出现顺序。"""
    merged: list[Diagnostic] = []
    seen: set[tuple[object, ...]] = set()
    for group in groups:
        for diagnostic in group:
            if diagnostic.identity in seen:
                continue
            seen.add(diagnostic.identity)
            merged.append(diagnostic)
    return tuple(merged)
