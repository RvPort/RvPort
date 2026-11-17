"""把 rvport-skills/riscv-scan 收集的 Rust 架构函数/类型清单转换为知识库 collected 事实。

语料来源：rvport-skills/riscv-scan 仓库（源出 Rust std::arch 各架构模块的文档/
源码提取），原件冻结于 rulesets/riscv-porting/collected/rust/，本脚本读取原件、
映射为 v2 事实条目并输出确定性 JSON（generated/rust-collected-entries.json），
供知识清单以 collected-v1 类型登记。溯源 = 语料原件 SHA-256 + 上游仓库 URL +
采集日期。

架构族映射（kb-scanner 只有 x86/arm64 两源族 + riscv 目标族）：
- functions JSON 的 `aarch64*`/`arm*` keys → arm64 源族 intrinsics（NEON/CRC 等
  纯增量——既有 C 词表无 arm64 intrinsics）
- `riscv*` keys → riscv 目标族 intrinsics（出现即"已用 RVV/位操作 intrinsic"）
- `x86*` keys 的函数**跳过**——与既有 C intrinsics 词表同名同语义（实测重叠
  6121/6159，99.4%），检测器已覆盖，收编只制造重复条目
- 其余架构（loongarch/mips/wasm32/s390x/powerpc/nvptx）丢弃——kb-scanner 不
  做第三源架构
- structs JSON：x86 → vectorTypes（__m128* 等，与 C 词表差集小但为独立类型
  命名空间，整体收编）；arm 系 → arm64 vectorTypes（float16x4_t 等）

factKind 映射：函数名 → intrinsics、类型名 → vectorTypes（与 C 侧同 kind，
检测器按 (kind, family) 索引天然分流）。
"""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

from kb_scanner.knowledge.identity import canonicalize_fact_value
from kb_scanner.knowledge.production import render_model
from kb_scanner.knowledge.schema import CollectedFactsModel, GeneratedFactV2Model


CORPUS_FILES = {
    "collected/rust/arch-functions.json": "functions",
    "collected/rust/arch-structs.json": "structs",
}
OFFICIAL_URL = "https://github.com/zte-riscv/rvport-skills/tree/master/riscv-scan"
LICENSE = "rvport-skills riscv-scan corpus, Apache-2.0, extracted from Rust std::arch modules"
COLLECTED_AT = "2026-11-13"

# 单源单族约束（CollectedFactsModel）→ 按族拆三个输出文件/manifest 源。
OUTPUTS = {
    "arm64": "generated/rust-arm64-collected-entries.json",
    "riscv": "generated/rust-riscv-collected-entries.json",
    "x86": "generated/rust-x86-collected-entries.json",
}

_RULES_ROOT = Path(__file__).resolve().parents[1] / "rulesets" / "riscv-porting"

# 语料 arch key → 知识架构族（未列出的 key 丢弃：loongarch/mips/wasm/s390x/ppc/nvptx）。
_FUNCTIONS_FAMILY_KEYS = {
    "arm64": ("aarch64", "aarch64/neon", "arm", "arm_shared", "arm_shared/neon", "arm_shared/barrier"),
    "riscv": ("riscv64", "riscv32", "riscv_shared"),
}
_STRUCTS_FAMILY_KEYS = {
    "x86": ("x86", "x86_64"),
    "arm64": ("aarch64", "aarch64/neon", "arm", "arm_shared", "arm_shared/neon", "arm_shared/barrier"),
}
# Rust 标识符（函数名/类型名）：字母数字下划线，不以数字开头。
_IDENT_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


def _read_functions(payload) -> dict[tuple[str, str], set[str]]:
    """按族聚合函数名：{(family, factKind): {names}}。x86 函数显式跳过（重叠）。"""
    grouped: dict[tuple[str, str], set[str]] = {}
    for family, keys in _FUNCTIONS_FAMILY_KEYS.items():
        names: set[str] = set()
        for key in keys:
            for name in payload.get(key, {}):
                if _IDENT_RE.fullmatch(name):
                    names.add(name)
        grouped[(family, "intrinsics")] = names
    return grouped


def _read_structs(payload) -> dict[tuple[str, str], set[str]]:
    """按族聚合类型名（语料形态：arch key → list[str]）。"""
    grouped: dict[tuple[str, str], set[str]] = {}
    for family, keys in _STRUCTS_FAMILY_KEYS.items():
        names: set[str] = set()
        for key in keys:
            for name in payload.get(key, ()):
                if _IDENT_RE.fullmatch(name):
                    names.add(name)
        grouped[(family, "vectorTypes")] = names
    return grouped


def collect(rules_root: Path = _RULES_ROOT) -> dict[str, tuple[CollectedFactsModel, dict[str, int]]]:
    """读取语料原件并按族构造三个 collected 载荷；返回 {family: (模型, 计数)}。"""
    grouped_by_family: dict[str, list[tuple[str, str]]] = {"arm64": [], "riscv": [], "x86": []}
    digests: list[str] = []
    names: list[str] = []
    for relative, corpus_kind in CORPUS_FILES.items():
        corpus_path = rules_root / relative
        content = corpus_path.read_bytes()
        digests.append(hashlib.sha256(content).hexdigest())
        names.append(relative)
        payload = json.loads(content)
        grouped = _read_functions(payload) if corpus_kind == "functions" else _read_structs(payload)
        for (family, fact_kind), values in sorted(grouped.items()):
            for value in sorted(values):
                grouped_by_family[family].append((fact_kind, value))
    results: dict[str, tuple[CollectedFactsModel, dict[str, int]]] = {}
    for family, pairs in grouped_by_family.items():
        if not pairs:
            continue
        facts = sorted(
            (
                GeneratedFactV2Model(
                    factKind=fact_kind,
                    architectureFamily=family,
                    canonicalValue=canonicalize_fact_value(fact_kind, value),
                    semanticScope=f"{family}-only",
                )
                for fact_kind, value in pairs
            ),
            key=lambda item: (item.factKind, item.architectureFamily, item.canonicalValue),
        )
        model = CollectedFactsModel(
            schemaVersion="1.0",
            source={
                "sourceId": f"collected-rust-{family}-entries",
                "officialUrl": OFFICIAL_URL,
                "license": LICENSE,
                "collectedAt": COLLECTED_AT,
                "inputFiles": tuple(names),
                "inputSha256": tuple(digests),
            },
            facts=tuple(facts),
        )
        counter: dict[str, int] = {}
        for fact in facts:
            counter[fact.factKind] = counter.get(fact.factKind, 0) + 1
        results[family] = (model, counter)
    return results


def main() -> int:
    for family, (model, counts) in collect().items():
        out_path = _RULES_ROOT / OUTPUTS[family]
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_bytes(render_model(model))
        print(f"wrote {out_path}")
        for kind, count in sorted(counts.items()):
            print(f"  {family}/{kind}: {count}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
