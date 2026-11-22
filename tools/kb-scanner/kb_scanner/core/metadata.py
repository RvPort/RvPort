"""JSON 元数据的"深冻结"工具：把任意 JSON 风格值变成不可变结构。

流水线中所有 DTO 的 value/metadata 字段都经本模块冻结（dict→
MappingProxyType、list→tuple），从机制上杜绝跨阶段共享可变状态。
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
import math
from types import MappingProxyType
from typing import Any, TypeAlias


JsonScalar: TypeAlias = None | bool | int | float | str
JsonValue: TypeAlias = JsonScalar | tuple["JsonValue", ...] | Mapping[str, "JsonValue"]
# JSON 值的类型别名：冻结后的容器只能是 tuple 与只读 Mapping。


class _SharedJsonMapping(Mapping):
    """仅用于跨 DTO 共享已校验的只读子树，外部只读映射仍须逐项校验。"""

    __slots__ = ("_values",)

    def __init__(self, value):
        if hasattr(self, "_values"):
            raise TypeError("shared metadata cannot be reinitialized")
        object.__setattr__(self, "_values", freeze_metadata(value))

    def __setattr__(self, name, value):
        raise TypeError("shared metadata is immutable")

    def __delattr__(self, name):
        raise TypeError("shared metadata is immutable")

    def __getitem__(self, key):
        return self._values[key]

    def __iter__(self):
        return iter(self._values)

    def __len__(self):
        return len(self._values)

    def items(self):
        return self._values.items()

    def get(self, key, default=None):
        return self._values.get(key, default)

    def __repr__(self):
        return repr(self._values)


def freeze_shared_metadata(value: Mapping[str, Any]) -> Mapping[str, JsonValue]:
    """一次深冻结并允许后续 DTO 安全复用；不信任调用方的 MappingProxyType。"""
    return _SharedJsonMapping(value)


def freeze_json(value: Any, *, field_name: str = "value") -> JsonValue:
    """递归冻结 JSON 风格值：dict→只读 MappingProxyType，list→tuple。

    逐项校验：键必须是字符串、浮点数必须有限（拒绝 NaN/Inf）、
    不支持的类型抛 TypeError。已经是 _SharedJsonMapping 的值原样返回
    （跨 DTO 共享已冻结子树时避免重复拷贝）。
    """
    if type(value) is _SharedJsonMapping:
        return value
    if isinstance(value, float) and not math.isfinite(value):
        raise TypeError(f"{field_name} must contain finite JSON numbers")
    if value is None or isinstance(value, (bool, int, float, str)):
        return value
    if isinstance(value, Mapping):
        frozen: dict[str, JsonValue] = {}
        for key, item in value.items():
            if not isinstance(key, str):
                raise TypeError(f"{field_name} keys must be strings")
            frozen[key] = freeze_json(item, field_name=f"{field_name}.{key}")
        return MappingProxyType(frozen)
    if isinstance(value, Sequence) and not isinstance(value, (str, bytes, bytearray)):
        return tuple(freeze_json(item, field_name=field_name) for item in value)
    raise TypeError(f"{field_name} must contain JSON-safe values, got {type(value).__name__}")


def freeze_metadata(value: Mapping[str, Any] | None) -> Mapping[str, JsonValue]:
    """冻结 metadata 字段：None 视为空映射，结果必须仍是 Mapping。"""
    frozen = freeze_json(value or {}, field_name="metadata")
    if not isinstance(frozen, Mapping):
        raise TypeError("metadata must be a mapping")
    return frozen


def to_json_compatible(value: JsonValue) -> Any:
    """把冻结 JSON 值还原为可序列化结构（只读 Mapping→dict，tuple→list）。"""
    if isinstance(value, Mapping):
        return {key: to_json_compatible(item) for key, item in value.items()}
    if isinstance(value, tuple):
        return [to_json_compatible(item) for item in value]
    return value
