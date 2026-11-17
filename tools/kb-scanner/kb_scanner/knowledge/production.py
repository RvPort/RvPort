"""知识生产侧工具：模型序列化、生成/策展事实 lint 与候选决策过滤。

服务于规则包构建流程（构建者保证产物满足这些约束后，运行时 provider 才会放行装载）：
产物必须排序、值必须已规范化、策展条目必须挂接登记表内的参考证据。
"""

from __future__ import annotations

import json
from collections.abc import Iterable
from dataclasses import dataclass
from typing import Literal

from pydantic import BaseModel

from .identity import canonicalize_fact_value, curated_entry_id
from .schema import CuratedFactsModel, GeneratedFactsV2Model, ReferenceRegistryModel


DecisionStatus = Literal["generated", "accepted", "rejected", "deferred"]


@dataclass(frozen=True, slots=True)
class CandidateDecision:
    """不可变的候选决策：对某个候选值的处置结论，进入运行时必须携带参考证据。"""

    candidate_id: str
    category: str
    canonical_value: str
    status: DecisionStatus
    reference_ids: tuple[str, ...]
    rationale: str

    def __post_init__(self) -> None:
        """构造期校验：字段非空、状态合法、运行时决策必须有参考；reference_ids 排序去重。"""
        if not all((self.candidate_id, self.category, self.canonical_value, self.rationale)):
            raise ValueError("candidate decision fields must not be empty")
        if self.status not in {"generated", "accepted", "rejected", "deferred"}:
            raise ValueError("unknown candidate decision status")
        if self.status in {"generated", "accepted"} and not self.reference_ids:
            raise ValueError("runtime decisions require authoritative references")
        object.__setattr__(self, "reference_ids", tuple(sorted(set(self.reference_ids))))


def render_model(model: BaseModel) -> bytes:
    """把 pydantic 模型序列化为确定性 JSON 字节（排序键、无 None、缩进 2、结尾换行）。"""
    payload = model.model_dump(mode="json", exclude_none=True)
    return (json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")


def lint_generated(model: GeneratedFactsV2Model) -> tuple[str, ...]:
    """lint v2 生成事实：要求事实按键排序且每个值已是规范形式；返回失败原因元组（空即通过）。"""
    keys = [(item.factKind, item.architectureFamily, item.canonicalValue) for item in model.facts]
    failures: list[str] = []
    if keys != sorted(keys):
        failures.append("facts-not-sorted")
    for item in model.facts:
        if canonicalize_fact_value(item.factKind, item.canonicalValue) != item.canonicalValue:
            failures.append(f"non-canonical:{item.factKind}:{item.canonicalValue}")
    return tuple(failures)


def lint_curated(model: CuratedFactsModel, registry: ReferenceRegistryModel) -> tuple[str, ...]:
    """lint 策展事实：条目排序、entryId 与内容一致、引用的参考都在登记表中；返回失败原因元组。"""
    reference_ids = {item.referenceId for item in registry.references}
    failures: list[str] = []
    keys = [(item.factKind, item.architectureFamily, item.canonicalValue) for item in model.entries]
    if keys != sorted(keys):
        failures.append("entries-not-sorted")
    for item in model.entries:
        if item.entryId != curated_entry_id(item.factKind, item.architectureFamily, item.canonicalValue):
            failures.append(f"entry-id-mismatch:{item.entryId}")
        missing = sorted(set(item.referenceIds) - reference_ids)
        if missing:
            failures.append(f"missing-references:{item.entryId}:{','.join(missing)}")
    return tuple(failures)


def runtime_decisions(decisions: Iterable[CandidateDecision]) -> tuple[CandidateDecision, ...]:
    return tuple(sorted((item for item in decisions if item.status == "accepted"), key=lambda item: item.candidate_id))
