from __future__ import annotations

import copy

from tests.helpers import RISCV, X86
from tests.unit.test_reduction_candidates import RULE, evaluate, reduction
from tests.unit.test_rules import _profile, _source

from floating_point_precision_scanner.protocol import SchemaRegistry
from floating_point_precision_scanner.result_v2 import EXTENSION_ANALYSIS, project_result_v2
from floating_point_precision_scanner.rules import normalize_rule_selection


def test_independent_reductions_keep_v2_references_counts_and_bilingual_fields() -> None:
    facts = [reduction(1), reduction(2, loop=2)]
    run = evaluate(facts)
    source = {**_source(facts=facts), "semantic_digest": "1" * 64}
    result = project_result_v2(
        "reduction-contract",
        "complete",
        1,
        1,
        0,
        0,
        [source],
        [],
        {"fingerprint": _profile(X86).fingerprint},
        {"fingerprint": _profile(RISCV).fingerprint},
        rule_run=run,
        enabled_rules=(RULE,),
    )
    assert SchemaRegistry().validate("result", result) == []
    assert len(result["findings"]) == 2
    assert result["summary"]["assessments"]["matched"] == 2
    assessments = result["extensions"][EXTENSION_ANALYSIS]["assessments"]
    assert len({item["scope"] for item in assessments}) == 2
    for finding in result["findings"]:
        assert finding["conclusion"]["ruleVersion"] == "1.2"
        assert finding["conclusion"]["affectedCount"] == 1
        assert len(finding["occurrences"]) == 1
        assert finding["primaryOccurrenceId"] == finding["occurrences"][0]["id"]
        for field in ("title", "message", "suggestion", "testSuggestion"):
            assert finding["conclusion"][field]
            assert finding["conclusion"][field + "En"]
        sources = [
            step
            for step in finding["evidenceChain"]["steps"]
            if step["kind"] == "sourceObservation"
        ]
        assert len(sources) == 1
    broken = copy.deepcopy(result)
    broken["findings"][0]["primaryOccurrenceId"] = "missing"
    assert SchemaRegistry().validate("result", broken)
    assert normalize_rule_selection({"enabled": [RULE], "versions": {RULE: "1.0"}})[2]
    assert normalize_rule_selection({"enabled": [RULE], "versions": {RULE: "1.2"}})[2] == []
