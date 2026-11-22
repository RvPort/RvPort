"""稳定ID（stable_id）生成：所有跨阶段对象的唯一标识来源。

对 (kind, 身份部件) 做规范化 JSON 编码后取 SHA-256，保证同一逻辑身份
在不同机器、不同运行下得到相同 ID。绝对路径被禁止（必须先归一化为
项目相对路径），否则不同环境会生成不一致的 ID。
"""

from __future__ import annotations

import hashlib
import json
from collections.abc import Mapping, Sequence
from dataclasses import fields, is_dataclass
from enum import Enum
from pathlib import Path
from typing import Any


def stable_id(kind: str, *identity_parts: Any) -> str:
    """生成稳定ID：SHA-256(kind + 规范化的身份部件)。

    kind 是对象类别（如 fact/observation/finding），identity_parts 是
    唯一确定该对象的身份材料。规范化过程消除键序/路径分隔符等环境差异，
    使输出跨平台稳定。空 kind 直接拒绝。
    """
    if not kind or not kind.strip():
        raise ValueError("kind must not be empty")
    payload = {
        "version": 1,
        "kind": kind.strip(),
        "parts": [_canonicalize(item) for item in identity_parts],
    }
    encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _canonicalize(value: Any) -> Any:
    """把任意身份材料递归规范化为可确定性序列化的 JSON 结构。

    规则：标量原样保留；Enum 取值；Path 必须是相对路径且统一为 "/" 分隔；
    dataclass 展开为字段字典；Mapping 按键排序；Sequence 逐项处理。
    不支持的材料抛 TypeError，绝对路径抛 ValueError。
    """
    if value is None or isinstance(value, (bool, int, float, str)):
        return value
    if isinstance(value, Enum):
        return _canonicalize(value.value)
    if isinstance(value, Path):
        if value.is_absolute():
            raise ValueError("absolute paths must be normalized before stable ID construction")
        return str(value).replace("\\", "/")
    if is_dataclass(value):
        return {field.name: _canonicalize(getattr(value, field.name)) for field in fields(value)}
    if isinstance(value, Mapping):
        if not all(isinstance(key, str) for key in value):
            raise TypeError("stable ID mapping keys must be strings")
        return {key: _canonicalize(value[key]) for key in sorted(value)}
    if isinstance(value, Sequence) and not isinstance(value, (str, bytes, bytearray)):
        return [_canonicalize(item) for item in value]
    raise TypeError(f"unsupported stable ID material: {type(value).__name__}")
