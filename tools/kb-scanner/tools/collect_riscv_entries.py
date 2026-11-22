"""把人工收集的 RISC-V 目标侧平台宏清单转换为知识库 collected 事实载荷。

语料来源：rvport-skills/riscv-scan 仓库人工核出的 RISC-V C-API 平台宏清单
（源出 RISC-V C-API/ISA 手册约定），原件冻结于
rulesets/riscv-porting/collected/riscv/，本脚本读取原件、清洗、映射为 v2
事实条目并输出确定性 JSON（generated/riscv-collected-entries.json），供知识
清单以 collected-v1 类型登记。手册语料无精确 git commit 可引用，溯源以语料
原件的 SHA-256 + 官方规范 URL + 采集日期登记（见 CollectedSourceDescriptorModel）。

与源族（x86/arm64）语料的语义差异：riscv 是**目标族**——条目出现表示代码
已含 RISC-V 分支/实现（"出现即已移植"），与源族"出现即迁移障碍"方向相反。
消费端必须显式 family="riscv" 查询（evidence_graph 的目标族角色判定），
不进入 source_arch 取值集与检测器注册表。

映射与清洗规则：
- riscv.macro -> platformMacros（丢弃不以标识符字符收尾的截断脏值）
所有值过 canonicalize_fact_value 规范化，排序去重后按 lint_generated 要求
的字段序输出。
"""

from __future__ import annotations

import hashlib
import re
from pathlib import Path

from kb_scanner.knowledge.identity import canonicalize_fact_value
from kb_scanner.knowledge.production import render_model
from kb_scanner.knowledge.schema import CollectedFactsModel, GeneratedFactV2Model


# 语料原件（相对规则包根）与事实种类映射
CORPUS_FILES = {
    "collected/riscv/riscv.macro": "platformMacros",
}
OFFICIAL_URL = "https://github.com/riscv-non-isa/riscv-c-api-doc"
LICENSE = "RISC-V C-API specification macros, Apache-2.0, collected via rvport-skills/riscv-scan"
COLLECTED_AT = "2026-11-12"
SOURCE_ID = "collected-riscv-entries"

_RULES_ROOT = Path(__file__).resolve().parents[1] / "rulesets" / "riscv-porting"
_OUT_PATH = _RULES_ROOT / "generated" / "riscv-collected-entries.json"

# 宏标识符：必须形成完整标识符（丢弃 __riscv_ 这类截断前缀）
_MACRO_RE = re.compile(r"^__[A-Za-z][A-Za-z0-9_]*[A-Za-z0-9]$")


def _read_entries(path: Path, pattern: re.Pattern[str]) -> list[str]:
    """读取语料清单：跳过 # 注释与空行，逐行校验后返回原始值列表。"""
    values: list[str] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if pattern.fullmatch(stripped):
            values.append(stripped)
    return values


def collect(rules_root: Path = _RULES_ROOT) -> tuple[CollectedFactsModel, dict[str, int]]:
    """读取语料原件并构造 collected 事实载荷；返回 (模型, 各种类计数)。"""
    facts: list[GeneratedFactV2Model] = []
    digests: list[str] = []
    names: list[str] = []
    for relative, fact_kind in CORPUS_FILES.items():
        corpus_path = rules_root / relative
        content = corpus_path.read_bytes()
        digests.append(hashlib.sha256(content).hexdigest())
        names.append(relative)
        pattern = {"platformMacros": _MACRO_RE}[fact_kind]
        for value in sorted(set(_read_entries(corpus_path, pattern))):
            facts.append(
                GeneratedFactV2Model(
                    factKind=fact_kind,
                    architectureFamily="riscv",
                    canonicalValue=canonicalize_fact_value(fact_kind, value),
                    semanticScope="riscv-only",
                )
            )
    facts.sort(key=lambda item: (item.factKind, item.architectureFamily, item.canonicalValue))
    model = CollectedFactsModel(
        schemaVersion="1.0",
        source={
            "sourceId": SOURCE_ID,
            "officialUrl": OFFICIAL_URL,
            "license": LICENSE,
            "collectedAt": COLLECTED_AT,
            "inputFiles": tuple(names),
            "inputSha256": tuple(digests),
        },
        facts=tuple(facts),
    )
    counts: dict[str, int] = {}
    for fact in facts:
        counts[fact.factKind] = counts.get(fact.factKind, 0) + 1
    return model, counts


def main() -> int:
    model, counts = collect()
    _OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    _OUT_PATH.write_bytes(render_model(model))
    print(f"wrote {_OUT_PATH}")
    for kind, count in sorted(counts.items()):
        print(f"  {kind}: {count}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
