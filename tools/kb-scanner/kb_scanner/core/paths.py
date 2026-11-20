"""证明路径（ProofPath）模型：analyzer 阶段图搜索的结果。

ProofPath 记录从 source 实体到 target 实体经由哪些关系可达，
是"这个结论如何被证明"的溯源载体；PathSearchResult 是搜索过程的
原始结果（可含多条路径与截断信息）。
"""

from __future__ import annotations

from dataclasses import dataclass

from .evidence import _validate_id, _validate_ids


PROOF_STATUSES = frozenset({"complete", "partial", "unknown"})
# 证明状态三态：complete（完整证明）/ partial（部分证明）/ unknown（未知）。
SEARCH_STATUSES = frozenset({"complete", "partial", "unknown", "not_found"})
# 搜索状态在证明三态之上多一个 not_found（未找到任何路径）。


@dataclass(frozen=True, slots=True)
class ProofPath:
    """证明路径：source→target 经 relation_ids 的可达性证明（frozen）。

    不变式：status=complete 时不得 truncated；visited 记录搜索中
    访问过的节点数（用于诊断搜索规模）；reason 缺省或非空。
    """

    id: str
    source_id: str
    target_id: str
    relation_ids: tuple[str, ...] = ()
    status: str = "unknown"
    reason: str | None = None
    visited: int = 0
    truncated: bool = False

    def __post_init__(self) -> None:
        _validate_id(self.id)
        _validate_id(self.source_id, "source_id")
        _validate_id(self.target_id, "target_id")
        relation_ids = tuple(self.relation_ids)
        _validate_ids(relation_ids, "relation_id")
        if self.status not in PROOF_STATUSES:
            raise ValueError(f"unsupported proof status: {self.status}")
        if self.visited < 0:
            raise ValueError("visited must not be negative")
        if self.status == "complete" and self.truncated:
            raise ValueError("a complete proof path cannot be truncated")
        if self.reason is not None and not self.reason:
            raise ValueError("proof reason must not be empty")
        object.__setattr__(self, "relation_ids", relation_ids)


@dataclass(frozen=True, slots=True)
class PathSearchResult:
    """路径搜索结果：多条关系 ID 序列 + 搜索状态（frozen 不可变）。

    不变式：status=not_found 时 paths 必须为空；complete 时不得截断。
    """

    paths: tuple[tuple[str, ...], ...] = ()
    status: str = "not_found"
    visited: int = 0
    truncated: bool = False
    reason: str | None = None

    def __post_init__(self) -> None:
        paths = tuple(tuple(path) for path in self.paths)
        for path in paths:
            _validate_ids(path, "relation_id")
        if self.status not in SEARCH_STATUSES:
            raise ValueError(f"unsupported path search status: {self.status}")
        if self.visited < 0:
            raise ValueError("visited must not be negative")
        if self.status == "complete" and self.truncated:
            raise ValueError("a complete path search cannot be truncated")
        if self.reason is not None and not self.reason:
            raise ValueError("path search reason must not be empty")
        if self.status == "not_found" and paths:
            raise ValueError("not_found path search cannot contain paths")
        object.__setattr__(self, "paths", paths)
