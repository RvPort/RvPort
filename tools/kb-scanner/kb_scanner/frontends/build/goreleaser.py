"""GoReleaser 配置的 goarch 块解析（缩进感知）。

检测点规格源自 rvport-skills/riscv-scan solver_go_releaser（约 90 行缩进
状态机），按 kb-scanner 合同重写为 build 通道解析器：
`goarch:` 锚点 → 缩进更深的 `- arch` 列表项 → 块结束（缩进回落）时，
列表与 {'riscv64','riscv'} 无交集则产 build_arch_constraint 记录
（targetAllowed=False，复用 RPM arch 约束语义——BuildEvidenceNormalizer
既有链消费为 build_arch_constraint 观察）。
"""
from __future__ import annotations

import re

from .vocabulary import BuildInputKind
from .adapters import AdapterResult

_GOARCH_ANCHOR_RE = re.compile(r"^(\s*)(?:-\s+)?goarch:\s*$")
_LIST_ITEM_RE = re.compile(r"^(\s*)-\s+(\S+)\s*$")
_RISCV_ARCHS = {"riscv64", "riscv"}


def parse_goreleaser(source: str, path: str, target_arch: str) -> AdapterResult:
    """解析 .goreleaser.yml 的 goarch 块：缺失 riscv 产 arch 约束记录。"""
    from .rpm.records import Records

    records = Records(path)
    lines = source.splitlines()
    block_start = None
    block_indent = 0
    arches: list[str] = []
    for index, line in enumerate(lines, start=1):
        if block_start is None:
            match = _GOARCH_ANCHOR_RE.match(line)
            if match is not None:
                block_start = index
                block_indent = len(match.group(1))
                arches = []
            continue
        item = _LIST_ITEM_RE.match(line)
        if item is not None and len(item.group(1)) > block_indent:
            arches.append(item.group(2))
            continue
        if line.strip():
            _flush(records, block_start, index - 1, arches)
            block_start = None
            # 结束行不再当新块起点（与 riscv-scan 一致：锚点行独占）。
            rematch = _GOARCH_ANCHOR_RE.match(line)
            if rematch is not None:
                block_start = index
                block_indent = len(rematch.group(1))
                arches = []
    if block_start is not None:
        _flush(records, block_start, len(lines), arches)
    return AdapterResult(records.items, tuple(records.diagnostics), False)


def _flush(records, start: int, end: int, arches: list[str]) -> None:
    """块结束：列表非空且无 riscv 架构 → build_arch_constraint（targetAllowed=False）。"""
    if not arches or arches and (_RISCV_ARCHS & set(arches)):
        return
    body = f"goarch: [{', '.join(arches)}]"
    records.emit("build_arch_constraint", body, start, 1,
                 targetAllowed=False, valueKnown=True, architectureRelated=True,
                 requiredSelection=True, reason="goarch-list-missing-riscv")


def goreleaser_producer() -> str:
    return "goreleaser-indent-parser"
