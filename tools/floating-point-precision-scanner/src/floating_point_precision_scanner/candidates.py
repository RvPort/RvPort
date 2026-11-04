"""规则候选身份、直接事实和局部证据；不执行规则判断。"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any

from .canonical import semantic_digest, stable_id
from .reduction_candidates import ReductionCandidate, bound_evidence
from .reduction_candidates import candidates as reductions


@dataclass(frozen=True)
class Candidate:
    identity: str
    facts: tuple[Mapping[str, Any], ...]
    dependencies: tuple[Mapping[str, Any], ...] = ()


def payload(fact: Mapping[str, Any]) -> Mapping[str, Any]:
    value = fact.get("payload")
    return value if isinstance(value, Mapping) else {}


def relation(fact: Mapping[str, Any], key: str) -> str | None:
    value = payload(fact).get(key)
    return value if isinstance(value, str) else None


def _selected(code: str, fact: Mapping[str, Any]) -> bool:
    kind, data = fact.get("fact_kind"), payload(fact)
    if code == "long-double-abi":
        return kind == "type" and (
            not isinstance(fact.get("payload"), Mapping)
            or (
                (data.get("is_long_double") or "long double" in str(fact.get("type", "")))
                and data.get("boundary_kind")
                in {
                    "parameter",
                    "variadic_parameter",
                    "return",
                    "external_record_field",
                    "persistent_field",
                    "global",
                }
            )
        )
    if code == "fma-contraction":
        return kind == "arithmetic" and (
            bool(data.get("fma_candidate")) or not isinstance(fact.get("payload"), Mapping)
        )
    if code == "x87-excess-precision":
        return kind == "arithmetic" and (
            data.get("operation_chain_length", 1) != 1
            or not isinstance(fact.get("payload"), Mapping)
        )
    if code == "math-library-variance":
        return kind == "call" and (
            bool(data.get("math_family")) or not isinstance(fact.get("payload"), Mapping)
        )
    return kind == {
        "architecture-specific-fp-api": "api",
        "fragile-fp-comparison": "comparison",
        "mixed-precision-conversion": "conversion",
        "floating-environment": "environment",
        "long-double-abi": "type",
    }.get(code)


def construct(
    code: str,
    facts: Sequence[Mapping[str, Any]],
    context: str,
) -> list[Candidate]:
    """相同事实去重；候选排序不依赖输入顺序或其他候选。"""
    from .evidence import _point

    if code == "reduction-reassociation":
        return [Candidate(item.identity, item.facts) for item in reductions(facts, context)]
    if code == "unsafe-fp-optimization":
        return [Candidate(stable_id("configuration_member", {"context": context}), tuple(facts))]
    groups: dict[str, dict[str, Mapping[str, Any]]] = {}
    for fact in facts:
        if not _selected(code, fact):
            continue
        data = payload(fact)
        relation_field = {
            "x87-excess-precision": "evaluation_chain_id",
            "floating-environment": "environment_region_id",
        }.get(code, "expression_id")
        identity: Any = data.get(relation_field) or fact.get("fact_id") or semantic_digest(fact)
        if code == "long-double-abi":
            identity = {
                "function": fact.get("function_id"),
                "location": fact.get("location"),
                "kind": data.get("declaration_kind"),
                "name": data.get("name"),
                "boundary": data.get("boundary_kind"),
                "type": fact.get("type"),
            }
        key = stable_id(
            "semantic_candidate", {"rule": code, "context": context, "identity": identity}
        )
        groups.setdefault(key, {})[semantic_digest(fact)] = fact
    index: dict[tuple[str, str], list[Mapping[str, Any]]] = {}
    for fact in facts:
        for field in ("expression_id", "evaluation_chain_id", "environment_region_id"):
            value = relation(fact, field)
            if value is not None:
                index.setdefault((field, value), []).append(fact)
    result = []
    for key, members in groups.items():
        direct = tuple(members[k] for k in sorted(members))
        dependency_ids = {
            str(ref)
            for fact in direct
            if isinstance(payload(fact).get("dependency_expression_ids"), list)
            for ref in payload(fact).get("dependency_expression_ids", [])
        }
        if code == "fma-contraction":
            dependency_ids = {
                str(ref)
                for fact in direct
                if isinstance(payload(fact).get("fma_operand_expression_ids"), list)
                for ref in payload(fact).get("fma_operand_expression_ids", [])
                if isinstance(ref, str)
            }
        chain_ids = {relation(fact, "evaluation_chain_id") for fact in direct} - {None}
        region_ids = {relation(fact, "environment_region_id") for fact in direct} - {None}
        keys = {("expression_id", value) for value in dependency_ids}
        if code == "x87-excess-precision":
            keys.clear()
            keys.update(("evaluation_chain_id", value) for value in chain_ids if value is not None)
        if code == "floating-environment":
            keys.update(
                ("environment_region_id", value) for value in region_ids if value is not None
            )
        direct_ids = {str(f["fact_id"]) for f in direct}
        selected = {
            str(f["fact_id"]): f
            for relation_key in sorted(keys)
            for f in index.get(relation_key, [])
            if str(f["fact_id"]) not in direct_ids
        }
        dependencies = tuple(selected[k] for k in sorted(selected))
        if code == "floating-environment":
            functions = {fact.get("function_id") for fact in direct} - {None}
            called = {
                payload(fact).get("callee_symbol")
                for fact in dependencies
                if fact.get("fact_kind") == "call"
            } - {None}
            dependencies += tuple(
                fact
                for fact in facts
                if fact.get("fact_kind") == "function_summary"
                and (
                    fact.get("function_symbol") in called
                    or (not region_ids and fact.get("function_id") in functions)
                )
            )
        result.append(Candidate(key, direct, dependencies))
    return sorted(
        result,
        key=lambda item: (
            min(_point(f.get("location")) or ("", 0, 0) for f in item.facts),
            item.identity,
        ),
    )


def local_evidence(
    candidate: Candidate,
    ir_bundles: Sequence[Mapping[str, Any]],
    evidence_bundles: Sequence[Mapping[str, Any]],
) -> tuple[tuple[Mapping[str, Any], ...], tuple[Mapping[str, Any], ...]]:
    """只保留可靠关联的当前候选事实，歧义仍交给 evaluator 明确降级。"""
    members = (*candidate.facts, *candidate.dependencies)
    ir, bindings, ambiguous = bound_evidence(
        ReductionCandidate(candidate.identity, members),
        ir_bundles,
        evidence_bundles,
    )
    ir_ids = {item["fact_id"] for item in ir}
    binding_ids = {item["binding_id"] for item in bindings}
    local_ir = tuple(
        {**bundle, "facts": [f for f in bundle.get("facts", []) if f["fact_id"] in ir_ids]}
        for bundle in ir_bundles
    )
    local_bindings = tuple(
        {
            **bundle,
            "bindings": [
                b for b in bundle.get("bindings", []) if b.get("binding_id") in binding_ids
            ],
            "ambiguities": [{"source_fact_ids": [f["fact_id"] for f in candidate.facts]}]
            if ambiguous
            else [],
        }
        for bundle in evidence_bundles
    )
    return local_ir, local_bindings
