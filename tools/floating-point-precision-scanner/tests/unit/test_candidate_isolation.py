"""十规则共用调度与隔离矩阵；每条断言核对状态、引用和数量。"""

from __future__ import annotations

import copy
import json
from dataclasses import replace
from pathlib import Path
from typing import Any

import pytest
from tests.helpers import RISCV, X86
from tests.unit.test_expression_rules import build_contexts
from tests.unit.test_rules import _fact, _profile, _source

from floating_point_precision_scanner.candidates import construct
from floating_point_precision_scanner.canonical import semantic_digest
from floating_point_precision_scanner.compilation import normalize_fp_options
from floating_point_precision_scanner.rules import (
    DEFAULT_RULES,
    _aggregate_findings,
    evaluate_rules,
)
from floating_point_precision_scanner.suppression import Suppression, apply_suppressions

MATRIX = {
    "architecture-specific-fp-api": ("api", {"resolved": True, "callee": "_mm_add_ss"}),
    "math-library-variance": ("call", {"math_family": "sin", "callee": "sin"}),
    "fragile-fp-comparison": ("comparison", {"opcode": "==", "nan_comparison": True}),
    "floating-environment": ("environment", {"fenv_action": "changes_rounding"}),
    "fma-contraction": ("arithmetic", {"fma_candidate": True}),
    "x87-excess-precision": ("arithmetic", {"operation_chain_length": 3}),
    "long-double-abi": ("type", {"is_long_double": True, "boundary_kind": "parameter"}),
    "mixed-precision-conversion": (
        "conversion",
        {
            "implicit": True,
            "source_type": "double",
            "destination_type": "float",
        },
    ),
    "reduction-reassociation": (
        "reduction",
        {
            "operation": "+=",
            "reduction_kind": "sum",
            "iteration_count": 10,
        },
    ),
    "unsafe-fp-optimization": ("arithmetic", {}),
}


def fixture(code: str, index: int) -> dict[str, Any]:
    kind, data = MATRIX[code]
    return _fact(index, kind=kind, payload=copy.deepcopy(data))


def run(code: str, facts: list[dict[str, Any]], **kwargs: Any):
    source_context, target_context = build_contexts()
    missing_ir = kwargs.pop("missing_ir_for", set())
    ir = []
    evidence = []
    source = _source(
        facts=facts,
        options=normalize_fp_options(
            [
                "-mfpmath=387",
                "-fexcess-precision=fast",
                "-ffp-contract=fast",
                "-fassociative-math",
            ]
        ),
    )
    ir_sources = [fact for fact in facts if int(str(fact["fact_id"]), 16) not in missing_ir]
    for fact in ir_sources:
        ir_id = str(fact["fact_id"])[:-2] + "ee"
        ir.append(
            {
                "fact_id": ir_id,
                "opcode": "call" if code == "fma-contraction" else "fadd",
                "callee": "llvm.fma.f64" if code == "fma-contraction" else None,
            }
        )
        evidence.append(
            {
                "binding_id": str(fact["fact_id"])[:-2] + "bb",
                "source_fact_id": fact["fact_id"],
                "ir_fact_id": ir_id,
                "confidence": "exact",
                "method": "explicit-sidecar",
            }
        )
    # 每个 IR ID 必须独立，不使用前缀截断导致的碰撞。
    for fact, ir_fact, binding in zip(ir_sources, ir, evidence, strict=True):
        index = int(str(fact["fact_id"]), 16)
        ir_fact["fact_id"] = f"{index + 100:064x}"
        binding["ir_fact_id"] = ir_fact["fact_id"]
        binding["binding_id"] = f"{index + 200:064x}"
    artifact = {"context_id": "a" * 64, "profile": "build-faithful"}
    return evaluate_rules(
        [code],
        _profile(X86),
        _profile(RISCV, fma="off"),
        [source],
        [{"artifact": artifact, "facts": ir}],
        [{"artifact": artifact, "bindings": evidence, "ambiguities": []}],
        kwargs.pop("budgets", {}),
        source_context,
        target_context,
        **kwargs,
    )


def test_matrix_exhausts_registry() -> None:
    assert set(MATRIX) == set(DEFAULT_RULES)
    document = json.loads(
        (Path(__file__).parents[1] / "fixtures/validation/candidate-matrix-v1.json").read_text()
    )
    assert set(document["rules"]) == set(DEFAULT_RULES)
    inputs = json.loads(
        (Path(__file__).parents[1] / "fixtures/validation/executable-inputs-v1.json").read_text()
    )
    cases = {item["case_id"]: item for item in inputs["cases"]}
    for rule, entry in document["rules"].items():
        assert entry["boundary"] and entry["merge"] and entry["evidence"]
        assert cases[entry["real_case"]]["rule"] == rule
        assert set(entry["lifecycle"]) == set(document["required_lifecycle"])
        assert all(entry["lifecycle"].values())


def test_named_abi_boundary_without_location_keeps_independent_identity() -> None:
    facts = [fixture("long-double-abi", index) for index in (1, 2)]
    for index, fact in enumerate(facts):
        fact["location"] = None
        fact["payload"].update(name=f"external_{index}", expression_identity_unknown=True)
    result = run("long-double-abi", facts)
    assert result.counts["matched"] == 2
    assert len({item["finding_id"] for item in result.findings}) == 2


@pytest.mark.parametrize("code", [c for c in MATRIX if c != "unsafe-fp-optimization"])
def test_matched_unknown_failed_coexist_without_cross_candidate_refs(code: str) -> None:
    good, unknown, invalid = [fixture(code, i) for i in (1, 2, 3)]
    unknown["payload"]["expression_identity_unknown"] = True
    invalid["payload"] = None
    result = run(code, [invalid, unknown, good])
    assert result.counts["matched"] == result.counts["unknown"] == result.counts["failed"] == 1
    assert len(result.findings) == 1
    refs = {ref["ref"] for ref in result.findings[0]["evidence"] if ref["kind"] == "source_fact"}
    assert refs == {good["fact_id"]}


@pytest.mark.parametrize(
    "code,safe_payload",
    [
        (
            "architecture-specific-fp-api",
            {"active_target_guard": True, "portable_target_implementation": True},
        ),
        ("fragile-fp-comparison", {"nan_comparison": False, "zero_comparison": True}),
        ("floating-environment", {"fenv_action": "restores_environment"}),
        ("mixed-precision-conversion", {"source_type": "float", "destination_type": "double"}),
        ("long-double-abi", {"origin": "system"}),
    ],
)
def test_safe_candidate_does_not_hide_matched_candidate(code, safe_payload) -> None:
    good, safe = fixture(code, 1), fixture(code, 2)
    safe["payload"].update(safe_payload)
    result = run(code, [safe, good])
    assert result.counts["matched"] == result.counts["not_matched"] == 1
    assert len(result.findings) == 1


def test_configuration_member_mixed_states_are_isolated_by_context() -> None:
    code = "unsafe-fp-optimization"
    sources = [
        _source(
            f"{i:064x}",
            facts=[fixture(code, i)],
            options=normalize_fp_options(["-ffast-math"] if i in (1, 4) else []),
        )
        for i in range(1, 5)
    ]
    sources[3]["compile_context"] = "invalid"
    result = evaluate_rules(
        [code],
        _profile(X86),
        _profile(RISCV),
        sources,
        [
            {
                "artifact": {"context_id": sources[2]["context_id"]},
                "facts": [{"fact_id": "f" * 64, "fast_math_flags": ["fast"]}],
            }
        ],
        [],
        {},
    )
    assert all(
        result.counts[state] == 1 for state in ("matched", "not_matched", "unknown", "failed")
    )
    assert len(result.findings) == 1
    assert result.findings[0]["affected_count"] == 1


def test_evidence_budget_stops_before_local_closure(monkeypatch: pytest.MonkeyPatch) -> None:
    def forbidden(*args, **kwargs):
        raise AssertionError("预算不足时不应展开 IR 闭包")

    monkeypatch.setattr("floating_point_precision_scanner.rules.local_evidence", forbidden)
    result = run(
        "fma-contraction", [fixture("fma-contraction", 1)], budgets={"max_rule_evidence_refs": 0}
    )
    assert result.assessments[0].reason_code == "provenance_budget_exceeded"
    assert not result.findings


@pytest.mark.parametrize("code", MATRIX)
def test_independent_candidates_and_configuration_exception(code: str) -> None:
    facts = [fixture(code, 1), fixture(code, 2)]
    result = run(code, facts)
    expected = 1 if code == "unsafe-fp-optimization" else 2
    assert len(result.findings) == expected
    assert result.counts["matched"] == expected
    for finding in result.findings:
        assert finding["affected_count"] == 1
        assert finding["related_locations"] == []
        refs = {ref["ref"] for ref in finding["evidence"] if ref["kind"] == "source_fact"}
        assert len(refs) <= 1
        assert refs <= {fact["fact_id"] for fact in facts}


@pytest.mark.parametrize("code", MATRIX)
def test_zero_budget_does_not_evaluate(code: str, monkeypatch: pytest.MonkeyPatch) -> None:
    import floating_point_precision_scanner.rules as rules

    def forbidden(*args: Any, **kwargs: Any):
        pytest.fail("预算用尽之后不得调用 evaluator")

    monkeypatch.setitem(rules._EVALUATORS, code, forbidden)
    monkeypatch.setattr(rules, "_reduction_candidate", forbidden)
    result = run(code, [fixture(code, 1)], budgets={"max_rule_candidates": 0})
    assert not result.findings
    assert result.assessments[0].reason_code == "budget_exceeded"
    assert result.diagnostics[0].translation_unit_id == "a" * 64


@pytest.mark.parametrize("code", [c for c in MATRIX if c != "unsafe-fp-optimization"])
def test_local_invalid_candidate_preserves_other_finding(code: str) -> None:
    invalid = fixture(code, 2)
    invalid["payload"] = None
    result = run(code, [fixture(code, 1), invalid])
    assert len(result.findings) == 1
    assert result.counts["matched"] == 1 and result.counts["failed"] == 1
    assert result.diagnostics[0].translation_unit_id == "a" * 64


@pytest.mark.parametrize("code", [c for c in MATRIX if c != "unsafe-fp-optimization"])
def test_duplicate_order_budget_and_member_suppression(code: str) -> None:
    first, second = fixture(code, 1), fixture(code, 2)
    # 候选身份和求值前缀只来自自己的语义，不来自输入迭代顺序。
    assert construct(code, [first, first, second], "tu") == construct(code, [second, first], "tu")
    result = run(code, [second, first], budgets={"max_rule_candidates": 1})
    assert len(result.findings) == 1
    assert result.findings[0]["location"]["line"] == 2
    assert result.counts["unknown"] == 1
    full = run(code, [first, second])
    visible, records = apply_suppressions(
        full.findings, [Suppression(code, "main.c", "已审查", 2, 2)]
    )
    assert len(visible) == len(records) == 1
    assert visible[0]["location"]["line"] == 3
    assert records[0]["finding_id"] != visible[0]["finding_id"]


def test_nan_and_regular_comparison_and_unknown_coexist() -> None:
    nan = fixture("fragile-fp-comparison", 1)
    regular = _fact(2, kind="comparison", payload={"opcode": "==", "use_context": "return"})
    unknown = _fact(3, kind="comparison", payload={"opcode": "=="})
    safe = _fact(4, kind="comparison", payload={"opcode": "==", "zero_comparison": True})
    result = run("fragile-fp-comparison", [nan, regular, unknown, safe])
    assert result.counts == {
        "planned": 4,
        "completed": 3,
        "matched": 2,
        "not_matched": 1,
        "unknown": 1,
        "skipped": 0,
        "failed": 0,
    }
    assert {f["evidence_origin"] for f in result.findings} == {"deterministic", "heuristic"}


def test_configuration_members_keep_all_evidence_and_suppress_before_aggregation() -> None:
    sources = [
        _source(
            context,
            facts=[fixture("unsafe-fp-optimization", index)],
            options=normalize_fp_options(["-ffast-math"]),
        )
        for context, index in [("a" * 64, 1), ("b" * 64, 2)]
    ]
    result = evaluate_rules(
        ["unsafe-fp-optimization"],
        _profile(X86),
        _profile(RISCV),
        sources,
        [],
        [],
        {},
        aggregate=False,
    )
    assert len(result.findings) == 2
    aggregate = _aggregate_findings(result.findings, 100)[0]
    assert aggregate["affected_count"] == 2
    assert {ref["ref"] for ref in aggregate["evidence"] if ref["kind"] == "assessment"} == {
        assessment.assessment_id for assessment in result.assessments
    }
    visible, records = apply_suppressions(
        result.findings,
        [
            Suppression(
                "unsafe-fp-optimization",
                "main.c",
                "仅抑制第二成员",
                3,
                3,
            )
        ],
    )
    assert len(records) == 1 and records[0]["status"] == "applied"
    assert _aggregate_findings(visible, 100)[0]["affected_count"] == 1
    sources[1]["effective_fp_options"]["fp_contract"] = "off"
    different = evaluate_rules(
        ["unsafe-fp-optimization"], _profile(X86), _profile(RISCV), sources, [], [], {}
    )
    assert len(different.findings) == 2


@pytest.mark.parametrize("code", MATRIX)
def test_unrelated_facts_and_ir_do_not_change_existing_candidate(code: str) -> None:
    first, other = fixture(code, 1), fixture(code, 2)
    before = run(code, [first])
    after = run(code, [other, first])
    assert before.findings[0] in after.findings
    assert before.assessments[0] in after.assessments


@pytest.mark.parametrize(
    "field,value", [("dependency_expression_ids", None), ("evaluation_chain_id", {"invalid": True})]
)
def test_invalid_relation_is_candidate_local(field, value) -> None:
    first, broken = fixture("fma-contraction", 1), fixture("fma-contraction", 2)
    broken["payload"][field] = value
    result = run("fma-contraction", [first, broken])
    assert result.counts["matched"] == result.counts["failed"] == 1
    assert len(result.findings) == 1


def test_unproved_identity_is_unknown_without_hiding_known_candidate() -> None:
    known, unknown = (
        fixture("architecture-specific-fp-api", 1),
        fixture("architecture-specific-fp-api", 2),
    )
    unknown["location"] = None
    unknown["payload"]["expression_identity_unknown"] = True
    result = run("architecture-specific-fp-api", [unknown, known])
    assert result.counts["matched"] == result.counts["unknown"] == 1
    assert result.findings[0]["location"]["line"] == 2


@pytest.mark.parametrize("rule", ["fma-contraction", "x87-excess-precision"])
def test_missing_ir_is_local_to_candidate(rule: str) -> None:
    result = run(rule, [fixture(rule, 1), fixture(rule, 2)], missing_ir_for={2})
    assert result.counts["matched"] == result.counts["unknown"] == 1
    assert result.findings[0]["location"]["line"] == 2


def test_libm_function_table_is_scoped_to_each_call() -> None:
    source, target = build_contexts(semantic_difference=False)
    left, right = copy.deepcopy(source.document), copy.deepcopy(target.document)
    left["libm"]["value"]["functions"] = {"sin": {"ulp": 1}, "cos": {"ulp": 1}}
    right["libm"]["value"]["functions"] = {"sin": {"ulp": 2}, "cos": {"ulp": 1}}
    source = replace(source, document=left, fingerprint=semantic_digest(left))
    target = replace(target, document=right, fingerprint=semantic_digest(right))
    facts = [
        _fact(index, kind="call", payload={"math_family": name})
        for index, name in enumerate(("sin", "cos", "exp"), 1)
    ]
    result = evaluate_rules(
        ["math-library-variance"],
        _profile(X86),
        _profile(RISCV),
        [_source(facts=facts)],
        [],
        [],
        {},
        source,
        target,
    )
    assert result.counts["matched"] == result.counts["not_matched"] == result.counts["unknown"] == 1
    assert result.findings[0]["location"]["line"] == 2
    assert result.findings[0]["semantic"]["semantic_differences"] == {
        "functions": {"source": {"ulp": 1}, "target": {"ulp": 2}},
    }


def test_large_configuration_group_keeps_complete_members_instead_of_truncation() -> None:
    code = "unsafe-fp-optimization"
    members = [
        {
            **run(code, [fixture(code, 1)]).findings[0],
            "finding_id": f"f{index}",
            "assessment_id": f"a{index}",
        }
        for index in range(129)
    ]
    result = _aggregate_findings(members, 100)
    assert len(result) == 129
    assert {item["finding_id"] for item in result} == {item["finding_id"] for item in members}
    assert all(item["affected_count"] == 1 for item in result)
