"""AST facts 与 LLVM IR facts 的确定性 evidence 绑定。"""

from __future__ import annotations

import json
from collections.abc import Mapping
from typing import Any

from . import EVIDENCE_SCHEMA_VERSION, TOOL_VERSION
from .artifacts import BitcodeMember
from .canonical import semantic_digest, stable_id
from .diagnostics import Diagnostic, diagnostic
from .protocol import SchemaRegistry
from .reduction_candidates import position_matches_source


def _point(location: Any) -> tuple[str, int, int] | None:
    if not isinstance(location, Mapping):
        return None
    point = location.get("expansion") or location.get("spelling") or location
    if not isinstance(point, Mapping):
        return None
    path, line, column = point.get("path"), point.get("line"), point.get("column", 0)
    if isinstance(path, str) and isinstance(line, int):
        return path, line, int(column) if isinstance(column, int) else 0
    return None


def _points(location: Any) -> tuple[tuple[str, int, int], ...]:
    if not isinstance(location, Mapping):
        return ()
    candidates: list[Any] = [location]
    for key in ("spelling", "expansion"):
        if isinstance(location.get(key), Mapping):
            candidates.append(location[key])
    inline_chain = location.get("inlined_at")
    if isinstance(inline_chain, list):
        candidates.extend(item for item in inline_chain if isinstance(item, Mapping))
    return tuple(dict.fromkeys(point for item in candidates if (point := _point(item)) is not None))


def _compatible(source: Mapping[str, Any], ir: Mapping[str, Any]) -> bool:
    kind = source.get("fact_kind")
    opcode = str(ir.get("opcode", ""))
    arithmetic = {"+": "fadd", "-": "fsub", "*": "fmul", "/": "fdiv", "%": "frem"}
    source_opcode = source.get("payload", {}).get("opcode")
    if kind == "arithmetic" and source.get("payload", {}).get("fma_candidate") and (
        opcode == "fma" or str(ir.get("callee", "")).startswith(("llvm.fma.", "llvm.fmuladd."))
    ):
        return True
    if kind == "arithmetic" and source_opcode in arithmetic:
        return opcode == arithmetic[source_opcode]
    if kind == "reduction":
        operation = source.get("payload", {}).get("operation")
        expected = {"+=": "fadd", "-=": "fsub", "*=": "fmul"}.get(operation)
        if opcode == "call":
            return str(ir.get("callee", "")).startswith("llvm.vector.reduce." + (expected or "f"))
        return opcode == "phi" or opcode in ({expected} if expected else {"fadd", "fsub", "fmul"})
    return (
        (kind == "arithmetic" and opcode in {"fadd", "fsub", "fmul", "fdiv", "frem"})
        or (kind == "comparison" and opcode == "fcmp")
        or (
            kind == "conversion"
            and opcode in {"fptrunc", "fpext", "sitofp", "uitofp", "fptosi", "fptoui"}
        )
        or (kind in {"call", "environment", "api"} and opcode in {"call", "invoke", "callbr"})
    )


def _binding(
    source_id: str,
    ir_id: str,
    method: str,
    confidence: str,
    reason: str,
    source_digest: str,
    ir_digest: str,
    relation: str = "lowering",
) -> dict[str, Any]:
    identity = {
        "source_fact_id": source_id,
        "ir_fact_id": ir_id,
        "method": method,
        "confidence": confidence,
        "source_fact_bundle_digest": source_digest,
        "ir_fact_bundle_digest": ir_digest,
        "relation": relation,
    }
    stable_identity = dict(identity)
    if relation:
        # bundle 摘要用于校验来源；无关候选变化不能改变本条归约关联的身份。
        stable_identity.pop("source_fact_bundle_digest")
        stable_identity.pop("ir_fact_bundle_digest")
    return {
        "binding_id": stable_id("evidence_binding", stable_identity),
        **identity,
        "reason": reason,
    }


def _sidecar_bindings(
    member: BitcodeMember,
    schemas: SchemaRegistry,
    source_ids: set[str],
    ir_ids: set[str],
    source_digest: str,
    ir_digest: str,
    reduction_ids: set[str],
) -> tuple[list[dict[str, Any]], list[Diagnostic]]:
    if member.sidecar_path is None:
        return [], []
    try:
        document = json.loads(member.sidecar_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        return [], [
            diagnostic(
                "binding_sidecar_invalid_json",
                "evidence",
                "evidence sidecar 不是有效 JSON。",
                "The evidence sidecar is not valid JSON.",
                translation_unit_id=member.context_id,
            )
        ]
    if not isinstance(document, Mapping):
        return [], [
            diagnostic(
                "binding_sidecar_invalid",
                "evidence",
                "evidence sidecar 顶层必须是对象。",
                "The evidence sidecar root must be an object.",
                translation_unit_id=member.context_id,
            )
        ]
    errors = schemas.validate("binding_sidecar", document)
    if errors:
        return [], errors
    if (
        document["artifact_key"] != member.artifact_key
        or document["context_id"] != member.context_id
        or document["source_fact_bundle_digest"] != source_digest
        or document["ir_fact_bundle_digest"] != ir_digest
    ):
        return [], [
            diagnostic(
                "binding_sidecar_artifact_mismatch",
                "evidence",
                "evidence sidecar 与 BC 成员不匹配。",
                "The evidence sidecar does not match the bitcode member.",
                translation_unit_id=member.context_id,
            )
        ]
    bindings: list[dict[str, Any]] = []
    for item in document["bindings"]:
        source_id, ir_id = str(item["source_fact_id"]), str(item["ir_fact_id"])
        if source_id not in source_ids or ir_id not in ir_ids:
            continue
        bindings.append(
            _binding(
                source_id,
                ir_id,
                "explicit-sidecar",
                "exact",
                "producer binding sidecar",
                source_digest,
                ir_digest,
                "reduction-lowering" if source_id in reduction_ids else "explicit",
            )
        )
    return bindings, []


def build_evidence(
    source_bundle: Mapping[str, Any],
    ir_bundle: Mapping[str, Any],
    member: BitcodeMember,
    schemas: SchemaRegistry,
    *,
    mapping_required: bool,
    max_candidates: int = 100_000,
) -> tuple[dict[str, Any], list[Diagnostic]]:
    source_facts = [item for item in source_bundle["facts"] if isinstance(item, Mapping)]
    ir_facts = [item for item in ir_bundle["facts"] if isinstance(item, Mapping)]
    source_by_id = {str(item["fact_id"]): item for item in source_facts}
    ir_by_id = {str(item["fact_id"]): item for item in ir_facts}
    source_digest = str(source_bundle["semantic_digest"])
    ir_digest = str(ir_bundle["semantic_digest"])
    bindings, diagnostics = _sidecar_bindings(
        member,
        schemas,
        set(source_by_id),
        set(ir_by_id),
        source_digest,
        ir_digest,
        {key for key, value in source_by_id.items() if value.get("fact_kind") == "reduction"},
    )
    matched_source = {item["source_fact_id"] for item in bindings}
    matched_ir = {item["ir_fact_id"] for item in bindings}
    ambiguities: list[dict[str, Any]] = []
    candidates_seen = 0
    budget_exhausted = False

    for ir_id, ir_fact in sorted(ir_by_id.items()):
        if ir_id in matched_ir:
            continue
        debug_points = _points(ir_fact.get("debug_location"))
        candidates: list[tuple[str, Mapping[str, Any]]] = []
        for source_id, source_fact in sorted(source_by_id.items()):
            candidates_seen += 1
            if candidates_seen > max_candidates:
                budget_exhausted = True
                break
            source_points = _points(source_fact.get("location"))
            if (
                source_id not in matched_source
                and source_fact.get("function_symbol")
                and source_fact.get("function_symbol") == ir_fact.get("function")
                and any(
                    source_point[:2] == ir_point[:2]
                    for source_point in source_points
                    for ir_point in debug_points
                )
                and _compatible(source_fact, ir_fact)
                and (
                    position_matches_source(source_fact, ir_fact.get("debug_location"))
                )
            ):
                candidates.append((source_id, source_fact))
        if budget_exhausted:
            break
        if len(candidates) == 1:
            source_id = candidates[0][0]
            bindings.append(
                _binding(
                    source_id,
                    ir_id,
                    "derived-debug",
                    "strong",
                    "matching function identity and structured debug/source location",
                    source_digest,
                    ir_digest,
                    _relation(str(candidates[0][1].get("fact_kind", ""))),
                )
            )
            matched_source.add(source_id)
            matched_ir.add(ir_id)
        elif len(candidates) > 1:
            ambiguities.append(
                {
                    "ir_fact_id": ir_id,
                    "source_fact_ids": sorted(item[0] for item in candidates),
                    "reason": "multiple structured source candidates",
                }
            )

    if budget_exhausted:
        diagnostics.append(
            diagnostic(
                "evidence_candidate_budget_exceeded",
                "evidence",
                "evidence 候选预算耗尽，剩余关联范围保持未知。",
                "The evidence candidate budget was exhausted; remaining mappings are unknown.",
                translation_unit_id=member.context_id,
            )
        )

    bindings.sort(key=lambda item: (item["source_fact_id"], item["ir_fact_id"], item["method"]))
    unmatched_source = sorted(set(source_by_id) - matched_source)
    unmatched_ir = sorted(set(ir_by_id) - matched_ir)
    if mapping_required and unmatched_ir:
        diagnostics.append(
            diagnostic(
                "source_mapping_incomplete",
                "evidence",
                "部分 LLVM 浮点事实无法映射回源码证据。",
                "Some LLVM floating-point facts could not be mapped to source evidence.",
                translation_unit_id=member.context_id,
            )
        )
    elif unmatched_ir:
        diagnostics.append(
            diagnostic(
                "source_mapping_incomplete",
                "evidence",
                "部分 LLVM 浮点事实没有源码证据。",
                "Some LLVM floating-point facts have no source evidence.",
                severity="warning",
                translation_unit_id=member.context_id,
            )
        )
    body: dict[str, Any] = {
        "kind": "FloatingPointEvidenceBundle",
        "schema_version": EVIDENCE_SCHEMA_VERSION,
        "producer": {"tool_version": TOOL_VERSION},
        "artifact": dict(ir_bundle["artifact"]),
        "source_fact_bundle_digest": str(source_bundle["semantic_digest"]),
        "ir_fact_bundle_digest": str(ir_bundle["semantic_digest"]),
        "bindings": bindings,
        "ambiguities": ambiguities,
        "unmatched_source_fact_ids": unmatched_source,
        "unmatched_ir_fact_ids": unmatched_ir,
        "diagnostics": [item.to_dict() for item in diagnostics],
    }
    body["semantic_digest"] = semantic_digest(body)
    return body, diagnostics


def _relation(source_kind: str) -> str:
    return {
        "conversion": "conversion-lowering",
        "comparison": "comparison-use",
        "environment": "environment-call",
        "reduction": "reduction-lowering",
        "call": "math-call",
        "api": "architecture-api",
    }.get(source_kind, "lowering")
