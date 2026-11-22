"""规则包（bundle）描述符的加载与严格校验。

一个规则包是一个目录：scanner-manifest.json 声明规则包标识、知识清单
（含 SHA-256 校验）与投影策略版本。本模块在加载时做防篡改与防路径逃逸
校验，产出的 identity 是整个包内容的 SHA-256 指纹（稳定ID来源之一）。
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any


class BundleError(ValueError):
    """A scanner bundle is malformed or cannot be trusted."""


@dataclass(frozen=True, slots=True)
class BundleDescriptor:
    """规则包描述符：frozen 不可变，加载时一次性校验并填充。

    identity 字段是对 scanner-manifest.json 规范化 JSON 的 SHA-256，
    用于唯一标识一个规则包的精确内容版本。
    """

    root: Path
    rule_pack_id: str
    rule_pack_version: str
    knowledge_manifest_path: Path
    knowledge_manifest_sha256: str
    projection_policy_id: str
    projection_policy_version: int
    identity: str


def load_bundle_descriptor(root: Path, manifest_name: str = "scanner-manifest.json") -> BundleDescriptor:
    """加载并严格校验规则包描述符，任何不一致都抛 BundleError。

    校验链：manifest 必须位于包根内 → 顶层/子对象字段必须严格相等
    （不允许多余或缺失字段）→ 知识清单路径必须是包内相对 .json 文件 →
    其内容 SHA-256 必须与 manifest 声明一致 → 投影策略版本必须是正整数。
    通过后基于规范化 manifest JSON 计算 identity（SHA-256）。
    """
    bundle_root = root.resolve()
    if not bundle_root.is_dir():
        raise BundleError("bundle root is not a directory")
    manifest_path = (bundle_root / manifest_name).resolve()
    if not manifest_path.is_relative_to(bundle_root):
        raise BundleError("scanner manifest escapes the bundle root")
    try:
        content = manifest_path.read_bytes()
        payload = json.loads(content)
    except (OSError, json.JSONDecodeError) as exc:
        raise BundleError(f"scanner manifest is unavailable or invalid: {type(exc).__name__}") from exc
    root_object = _strict_object(
        payload,
        {"schemaVersion", "rulePack", "knowledgeManifest", "projectionPolicy"},
        "scanner manifest",
    )
    if root_object["schemaVersion"] != "1.0":
        raise BundleError("unsupported scanner manifest version")
    rule_pack = _strict_object(root_object["rulePack"], {"id", "version"}, "rulePack")
    knowledge = _strict_object(root_object["knowledgeManifest"], {"path", "sha256"}, "knowledgeManifest")
    policy = _strict_object(root_object["projectionPolicy"], {"id", "version"}, "projectionPolicy")
    rule_pack_id = _nonempty(rule_pack["id"], "rulePack.id")
    rule_pack_version = _nonempty(rule_pack["version"], "rulePack.version")
    logical_path = _nonempty(knowledge["path"], "knowledgeManifest.path")
    expected_hash = _digest(knowledge["sha256"], "knowledgeManifest.sha256")
    knowledge_path = (bundle_root / logical_path).resolve()
    if Path(logical_path).is_absolute() or not knowledge_path.is_relative_to(bundle_root) or knowledge_path.suffix != ".json":
        raise BundleError("knowledge manifest path must be a bundle-local JSON file")
    try:
        knowledge_bytes = knowledge_path.read_bytes()
    except OSError as exc:
        raise BundleError(f"knowledge manifest is unreadable: {type(exc).__name__}") from exc
    if hashlib.sha256(knowledge_bytes).hexdigest() != expected_hash:
        raise BundleError("knowledge manifest digest does not match scanner manifest")
    policy_id = _nonempty(policy["id"], "projectionPolicy.id")
    policy_version = policy["version"]
    if isinstance(policy_version, bool) or not isinstance(policy_version, int) or policy_version < 1:
        raise BundleError("projectionPolicy.version must be a positive integer")
    identity_material = json.dumps(root_object, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    return BundleDescriptor(
        root=bundle_root,
        rule_pack_id=rule_pack_id,
        rule_pack_version=rule_pack_version,
        knowledge_manifest_path=knowledge_path,
        knowledge_manifest_sha256=expected_hash,
        projection_policy_id=policy_id,
        projection_policy_version=policy_version,
        identity=hashlib.sha256(identity_material).hexdigest(),
    )


def _strict_object(value: Any, keys: set[str], label: str) -> dict[str, Any]:
    """校验 value 是 dict 且字段集合与 keys 严格相等（多余/缺失都拒绝）。"""
    if not isinstance(value, dict) or set(value) != keys:
        raise BundleError(f"{label} must contain exactly: {', '.join(sorted(keys))}")
    return value


def _nonempty(value: Any, label: str) -> str:
    """校验 value 是非空白字符串并返回 strip 后的值。"""
    if not isinstance(value, str) or not value.strip():
        raise BundleError(f"{label} must be a non-empty string")
    return value.strip()


def _digest(value: Any, label: str) -> str:
    """校验 value 是 64 位小写十六进制 SHA-256 摘要。"""
    digest = _nonempty(value, label)
    if len(digest) != 64 or any(char not in "0123456789abcdef" for char in digest):
        raise BundleError(f"{label} must be a lowercase SHA-256 digest")
    return digest
