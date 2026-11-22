"""知识库身份计算：规范值归一化与各类稳定 ID（stable_id）的派生。

kb-scanner 流水线中所有跨阶段引用的知识对象（来源/事实条目/参考/候选/快照）都用
SHA-256 派生的稳定 ID 标识，保证相同内容在任何环境下得到相同 ID，可复现、可追溯。
"""

from __future__ import annotations

import hashlib
import re
from pathlib import PurePosixPath

from ..core import stable_id


_WHITESPACE = re.compile(r"\s+")


def canonicalize_fact_value(fact_kind: str, value: str) -> str:
    """按事实种类把原始值归一化为规范值（canonical value）。

    规则：headers 去掉尖括号/引号并统一为正斜杠；编译开关/内联汇编等仅折叠空白；
    汇编指令/寄存器名等折叠空白并转小写；其余种类只做 strip。归一化结果为空则报错。
    """
    if not fact_kind or not isinstance(value, str):
        raise ValueError("fact kind and value must be strings")
    normalized = value.strip()
    if fact_kind == "headers":
        normalized = normalized.strip('<>"').replace("\\", "/")
    elif fact_kind in {"compilerFlags", "inline_asm", "comment", "declaration_attribute", "vectorWidthMarkers"}:
        normalized = _WHITESPACE.sub(" ", normalized)
    elif fact_kind in {"assemblyInstructions", "registerNames", "targetFeatures", "architectureAliases"}:
        normalized = _WHITESPACE.sub(" ", normalized).lower()
    if not normalized:
        raise ValueError("canonical fact value must not be empty")
    return normalized


def logical_path(value: str) -> str:
    """把任意分隔符路径归一化为 POSIX 风格的相对逻辑路径；绝对路径或含 .. 即报错。"""
    normalized = str(value).replace("\\", "/")
    path = PurePosixPath(normalized)
    if path.is_absolute() or ".." in path.parts:
        raise ValueError("logical path must be relative")
    return path.as_posix()


def content_sha256(content: bytes) -> str:
    """计算字节内容的 SHA-256 十六进制摘要（小写）。"""
    return hashlib.sha256(content).hexdigest()


def knowledge_source_id(source_id: str, path: str, sha256: str) -> str:
    """知识来源稳定 ID：由 sourceId + 逻辑路径 + 内容摘要派生。"""
    return stable_id("knowledge_source", 1, source_id, logical_path(path), sha256)


def knowledge_entry_id(fact_kind: str, architecture_family: str, canonical_value: str) -> str:
    """知识事实条目稳定 ID：由事实种类 + 架构族 + 规范值派生。"""
    return stable_id("knowledge_entry", 1, fact_kind, architecture_family, canonical_value)


def knowledge_reference_id(official_url: str, revision: str, logical_location: str, sha256: str) -> str:
    """参考证据稳定 ID：由官方 URL + 精确 commit + 逻辑位置 + 内容摘要派生。"""
    return stable_id("knowledge_reference", 1, official_url, revision, logical_location, sha256)


def curated_entry_id(fact_kind: str, architecture_family: str, canonical_value: str) -> str:
    """策展条目稳定 ID：先归一化规范值，再复用 knowledge_entry_id（保证与运行时条目同构）。"""
    canonical = canonicalize_fact_value(fact_kind, canonical_value)
    return knowledge_entry_id(fact_kind, architecture_family, canonical)


def candidate_id(category: str, canonical_value: str) -> str:
    """候选值稳定 ID：由类别 + strip 后的值派生（用于决策台账）。"""
    return stable_id("knowledge_candidate", 1, category, canonical_value.strip())


def knowledge_snapshot_id(
    catalog_id: str,
    version: str,
    source_ids: tuple[str, ...],
    entry_ids: tuple[str, ...],
) -> str:
    """知识快照稳定 ID：目录 ID + 版本 + 排序后的来源/条目 ID 集合派生，集合顺序无关。"""
    return stable_id("knowledge_snapshot", 1, catalog_id, version, tuple(sorted(source_ids)), tuple(sorted(entry_ids)))
