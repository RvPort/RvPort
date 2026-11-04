from __future__ import annotations

import copy

from tests.helpers import RISCV, X86
from tests.unit.test_rules import _fact, _profile, _source

from floating_point_precision_scanner.compilation import normalize_fp_options
from floating_point_precision_scanner.output import serialize_json
from floating_point_precision_scanner.protocol import SchemaRegistry
from floating_point_precision_scanner.result_v2 import (
    EXTENSION_ANALYSIS,
    project_result_v2,
    truncate_result_v2,
    validate_result_invariants,
)
from floating_point_precision_scanner.rules import RuleRun, _aggregate_findings, evaluate_rules
from floating_point_precision_scanner.suppression import Suppression, apply_suppressions


def test_configuration_member_audit_remains_closed_after_suppression() -> None:
    rule = "unsafe-fp-optimization"
    bundles = [
        {
            **_source(
                str(index) * 64, facts=[_fact(index)], options=normalize_fp_options(["-ffast-math"])
            ),
            "semantic_digest": str(index) * 64,
        }
        for index in (1, 2, 3)
    ]
    evaluated = evaluate_rules(
        [rule], _profile(X86), _profile(RISCV), bundles, [], [], {}, aggregate=False
    )
    visible, records = apply_suppressions(
        evaluated.findings,
        [
            Suppression(
                rule,
                "main.c",
                "只抑制第三成员",
                4,
                4,
            )
        ],
    )
    run = RuleRun(
        evaluated.assessments, _aggregate_findings(visible, 100), (), evaluated.candidate_findings
    )
    result = project_result_v2(
        "configuration-contract",
        "complete",
        3,
        3,
        0,
        0,
        bundles,
        [],
        {"fingerprint": _profile(X86).fingerprint},
        {"fingerprint": _profile(RISCV).fingerprint},
        rule_run=run,
        enabled_rules=(rule,),
        suppression_records=records,
    )
    assert SchemaRegistry().validate("result", result) == []
    members = result["extensions"][EXTENSION_ANALYSIS]["configurationMembers"]
    assert len(members) == 3
    assert sorted(item["status"] for item in members) == ["suppressed", "visible", "visible"]
    finding = result["findings"][0]
    assert finding["conclusion"]["affectedCount"] == 2
    visible_assessments = {item["assessmentId"] for item in members if item["status"] == "visible"}
    refs = {
        ref["id"]
        for step in finding["evidenceChain"]["steps"]
        for ref in step["references"]
        if ref["kind"] == "assessment"
    }
    assert refs == visible_assessments
    assert [item["location"]["line"] for item in finding["occurrences"]] == [2, 3]
    broken = copy.deepcopy(result)
    broken["extensions"][EXTENSION_ANALYSIS]["configurationMembers"][0]["assessmentId"] = "missing"
    assert SchemaRegistry().validate("result", broken)
    broken = copy.deepcopy(result)
    broken["extensions"][EXTENSION_ANALYSIS]["configurationMembers"][0]["evidence"][0]["id"] = (
        "missing"
    )
    assert "dangling-external-reference" in validate_result_invariants(broken)
    truncated = truncate_result_v2(result, len(serialize_json(result)) - 1)
    assert truncated is not None and not truncated["findings"]
    assert SchemaRegistry().validate("result", truncated) == []
    audit = truncated["extensions"][EXTENSION_ANALYSIS]["configurationMembers"]
    assert sorted(item["status"] for item in audit) == ["suppressed", "truncated", "truncated"]
    assert all(item["aggregateFindingId"] is None for item in audit)
    assert {item["assessmentId"] for item in audit} == {item["assessmentId"] for item in members}
    malformed = copy.deepcopy(truncated)
    malformed["diagnostics"] = []
    assert "dangling-configuration-member" in validate_result_invariants(malformed)
    malformed = copy.deepcopy(truncated)
    next(
        item
        for item in malformed["extensions"][EXTENSION_ANALYSIS]["configurationMembers"]
        if item["status"] == "truncated"
    )["aggregateFindingId"] = "missing"
    assert "dangling-configuration-member" in validate_result_invariants(malformed)
