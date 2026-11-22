"""把人工收集的 AArch64 语料清单转换为知识库 collected 事实载荷。

语料来源：ARM Architecture Reference Manual（PDF 手册人工提取），原件冻结于
rulesets/riscv-porting/collected/arm64/，本脚本读取原件、清洗、映射为 v2 事实
条目并输出确定性 JSON（generated/arm64-collected-entries.json），供知识清单以
collected-v1 类型登记。手册语料无精确 git commit 可引用，溯源以语料原件的
SHA-256 + 官方手册 URL + 采集日期登记（见 CollectedSourceDescriptorModel）。

映射与清洗规则：
- aarch64.builtin   -> builtins（GCC __builtin_aarch64_*，逐行原样）
- aarch64.intrinsics-> headers（ARM 头文件名，逐行原样）
- aarch64.macro     -> platformMacros（丢弃截断脏值：不以标识符字符收尾的行）
- aarch64.opcode    -> assemblyInstructions（跳过 # 注释；丢弃手册表格字段名
  ——含下划线的 EXC_*/DSNP_*/SPSR_* 等非助记符——与标点异常值 M.A.A；
  AArch64 正式指令助记符全部是无下划线的字母数字连写）
所有值过 canonicalize_fact_value 规范化（指令转小写、headers 去尖括号），
排序去重后按 lint_generated 要求的字段序输出。
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
    "collected/arm64/aarch64.builtin": "builtins",
    "collected/arm64/aarch64.intrinsics": "headers",
    "collected/arm64/aarch64.macro": "platformMacros",
    "collected/arm64/aarch64.opcode": "assemblyInstructions",
}
OFFICIAL_URL = "https://developer.arm.com/documentation/ddi0487/latest"
LICENSE = "Arm Architecture Reference Manual content used for interoperability analysis"
COLLECTED_AT = "2026-10-28"
SOURCE_ID = "collected-arm64-entries"

_RULES_ROOT = Path(__file__).resolve().parents[1] / "rulesets" / "riscv-porting"
_OUT_PATH = _RULES_ROOT / "generated" / "arm64-collected-entries.json"

# 手册助记符：纯大写字母数字（多字段表格名带下划线，M.A.A 带标点，均丢弃）
_MNEMONIC_RE = re.compile(r"^[A-Z][A-Z0-9]*$")
# 宏标识符：必须形成完整标识符（丢弃 __arm_、__AARCH64_CMODEL_ 这类截断前缀）
_MACRO_RE = re.compile(r"^__[A-Za-z][A-Za-z0-9_]*[A-Za-z0-9]$")
# 头文件名：字母数字加下划线 + .h
_HEADER_RE = re.compile(r"^[A-Za-z0-9_]+\.h$")
# builtin：__builtin_aarch64_* 完整标识符
_BUILTIN_RE = re.compile(r"^__builtin_aarch64_[A-Za-z0-9_]+$")


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
        pattern = {
            "builtins": _BUILTIN_RE,
            "headers": _HEADER_RE,
            "platformMacros": _MACRO_RE,
            "assemblyInstructions": _MNEMONIC_RE,
        }[fact_kind]
        for value in sorted(set(_read_entries(corpus_path, pattern))):
            facts.append(
                GeneratedFactV2Model(
                    factKind=fact_kind,
                    architectureFamily="arm64",
                    canonicalValue=canonicalize_fact_value(fact_kind, value),
                    semanticScope="arm64-only",
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
