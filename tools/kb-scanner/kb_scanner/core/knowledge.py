"""知识包模型：knowledge 阶段加载的迁移知识条目与来源。

KnowledgeSource 描述一个知识来源文件（含 SHA-256 溯源），KnowledgeEntry
是"某架构家族下某类事实的规范取值"，供 detector 阶段匹配 x86 专有用法。
"""

from __future__ import annotations

import re
from collections.abc import Mapping
from dataclasses import dataclass

from .evidence import _validate_id, _validate_ids
from .metadata import JsonValue, freeze_metadata


_SHA256 = re.compile(r"^[0-9a-f]{64}$")


def _unique_sorted(values: tuple[str, ...]) -> tuple[str, ...]:
    return tuple(sorted(set(values)))


@dataclass(frozen=True, slots=True)
class KnowledgeSource:
    """知识来源：一个知识文件的可校验引用（frozen 不可变）。

    logical_path 必须是包内相对路径（分隔符统一为 "/"，禁止 ".."），
    sha256 是来源文件内容的摘要，用于加载时防篡改校验。
    """

    id: str
    source_id: str
    kind: str
    logical_path: str
    sha256: str
    metadata: Mapping[str, JsonValue] | None = None

    def __post_init__(self) -> None:
        _validate_id(self.id)
        if not all((self.source_id, self.kind, self.logical_path)):
            raise ValueError("knowledge source identity fields must not be empty")
        if self.logical_path.startswith(("/", "\\")) or ".." in self.logical_path.replace("\\", "/").split("/"):
            raise ValueError("knowledge source path must be project relative")
        if not _SHA256.fullmatch(self.sha256):
            raise ValueError("knowledge source sha256 must be a complete lowercase digest")
        object.__setattr__(self, "logical_path", self.logical_path.replace("\\", "/"))
        object.__setattr__(self, "metadata", freeze_metadata(self.metadata))


@dataclass(frozen=True, slots=True)
class KnowledgeEntry:
    """知识条目：某架构家族（如 x86）下 fact_kind 事实的规范取值。

    source_ids 必须非空（溯源到 KnowledgeSource），去重排序后冻结。
    """

    id: str
    fact_kind: str
    architecture_family: str
    canonical_value: str
    source_ids: tuple[str, ...]
    metadata: Mapping[str, JsonValue] | None = None

    def __post_init__(self) -> None:
        _validate_id(self.id)
        if not all((self.fact_kind, self.architecture_family, self.canonical_value)):
            raise ValueError("knowledge entry fields must not be empty")
        source_ids = _unique_sorted(tuple(self.source_ids))
        if not source_ids:
            raise ValueError("knowledge entry requires at least one source")
        _validate_ids(source_ids, "source_id")
        object.__setattr__(self, "source_ids", source_ids)
        object.__setattr__(self, "metadata", freeze_metadata(self.metadata))
