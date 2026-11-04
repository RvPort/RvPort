from copy import deepcopy

import pytest

from floating_point_precision_scanner.canonical import semantic_digest
from floating_point_precision_scanner.step_descriptions import (
    _REASONS,
    StepDescriptions,
    _position,
    _text,
)


@pytest.mark.parametrize("code", list(_REASONS))
def test_known_rule_reason_preserves_its_bilingual_explanation(code):
    result = StepDescriptions(
        assessments=[{"id": "a", "status": "matched", "reasonCode": code}]
    ).describe("assessment", "a")
    assert _REASONS[code][0] in result["reason"]
    assert _REASONS[code][1] in result["reasonEn"]


def test_typed_evidence_and_assessment_are_described_without_cross_reference_leaks():
    options = {"fast_math": True}
    descriptions = StepDescriptions(
        facts=[
            {
                "facts": [
                    {
                        "fact_id": "same",
                        "fact_kind": "reduction",
                        "type": "float",
                        "function_symbol": "sum",
                        "location": {"path": "src/归约.cpp", "line": 101},
                        "payload": {"operation": "+=", "fixed_order": False},
                    }
                ],
                "effective_fp_options": options,
            }
        ],
        ir=[
            {
                "facts": [
                    {
                        "fact_id": "same",
                        "opcode": "fadd",
                        "function": "sum",
                        "result_type": "float",
                        "fast_math_flags": ["reassoc"],
                        "debug_location": {"path": "src/sum.cpp", "line": 101},
                    }
                ]
            }
        ],
        evidence=[
            {
                "bindings": [
                    {
                        "binding_id": "bind",
                        "method": "debug",
                        "confidence": "high",
                        "relation": "exact",
                    }
                ]
            }
        ],
        contexts=[
            {
                "kind": "profile",
                "fingerprint": "profile",
                "profile": "riscv64",
                "abi": "lp64d",
                "long_double": {"precision_bits": 113},
            },
            {
                "kind": "buildContext",
                "fingerprint": "build",
                "context_id": "context",
                "command": "secret command",
            },
        ],
        assessments=[
            {
                "id": "assessment",
                "ruleCode": "fp-order",
                "status": "matched",
                "reasonCode": "order_sensitive_reduction",
            }
        ],
    )
    for kind, identity, expected in [
        ("sourceFact", "same", "src/归约.cpp:101"),
        ("irFact", "same", "reassoc"),
        ("binding", "bind", "debug"),
        ("profile", "profile", "113"),
        ("buildContext", "build", "context"),
        ("compileOption", semantic_digest(options), "fast_math=True"),
        ("assessment", "assessment", "尚无 IR 重排证明"),
    ]:
        result = descriptions.describe(kind, identity)
        assert result["status"] == "complete"
        assert result["reasonCodes"] == []
        assert expected in result["reason"]
        assert all(result[field].strip() for field in ("title", "titleEn", "reason", "reasonEn"))
        assert "secret command" not in result["reason"]
    assert "fadd" not in descriptions.describe("sourceFact", "same")["reason"]
    assert "source evidence" in descriptions.describe("sourceFact", "same")["reasonEn"]


@pytest.mark.parametrize(
    "kind",
    [
        "sourceFact",
        "irFact",
        "binding",
        "profile",
        "buildContext",
        "compileOption",
        "assessment",
        "other",
    ],
)
def test_missing_evidence_is_explicitly_unknown(kind):
    result = StepDescriptions().describe(kind, "absent")
    assert result["status"] == "unknown"
    assert result["reasonCodes"] == ["step_description_unavailable"]
    assert "unavailable" in result["reasonEn"]


@pytest.mark.parametrize("state", ["unknown", "conflict", "notMatched", "matched"])
def test_assessment_unknown_and_fallback(state):
    result = StepDescriptions(assessments=[{"id": "a", "status": state}]).describe(
        "assessment", "a"
    )
    assert result["status"] == ("complete" if state in {"matched", "notMatched"} else "unknown")
    assert "unknown" in result["reason"]


@pytest.mark.parametrize("flag", ["ambiguous", "provenance_ambiguous", "unknown_effect"])
def test_ambiguous_source_does_not_claim_complete(flag):
    result = StepDescriptions(
        facts=[{"facts": [{"fact_id": "a", "fact_kind": "custom", "payload": {flag: True}}]}]
    ).describe("sourceFact", "a")
    assert result["status"] == "unknown"
    assert result["reasonCodes"] == ["source_semantics_ambiguous"]
    assert "custom" in result["titleEn"]
    assert "unknown location" in result["reasonEn"]


def test_minimal_context_and_unsupported_kind():
    descriptions = StepDescriptions(
        contexts=[{"kind": "profile", "fingerprint": "p"}, {"kind": "other", "fingerprint": "x"}],
        ir=[{"facts": [{"fact_id": "i"}]}],
    )
    assert "layout unavailable" in descriptions.describe("profile", "p")["reasonEn"]
    assert descriptions.describe("other", "x")["status"] == "unknown"
    assert "unknown location" in descriptions.describe("irFact", "i")["reasonEn"]


@pytest.mark.parametrize(
    "position,expected",
    [
        (None, ""),
        ({}, ""),
        ({"path": "/secret/file"}, ""),
        ({"path": "C:/secret/file"}, ""),
        ({"path": "src/../secret"}, ""),
        ({"path": "src\\a.c"}, "src/a.c:?"),
        ({"expansion": {"path": "src/a.c", "line": 4}}, "src/a.c:4"),
        ({"spelling": {"path": "include/a.h", "line": 8}}, "include/a.h:8"),
    ],
)
def test_safe_positions(position, expected):
    assert _position(position) == expected


def test_text_limits_safety_and_determinism():
    assert _text({"outer": {"z": 1, "a": 2}}) == _text({"outer": {"a": 2, "z": 1}})
    assert _text("a\n b\t c") == "a b c"
    assert _text("src/main.c:3") == "src/main.c:3"
    for raw in [
        "/tmp/private/key",
        "C:\\private\\key",
        "../private/key",
        "https://host/key",
        "token=private",
        "password:private",
    ]:
        assert "private" not in _text(raw)
        assert "<redacted>" in _text(raw)
    finding = {
        "title": "标题" * 600,
        "title_en": "Title" * 600,
        "message": "说明" * 3000,
        "message_en": "Reason" * 2000,
    }
    before = deepcopy(finding)
    result = StepDescriptions.conclusion(finding)
    assert result == StepDescriptions.conclusion(finding)
    assert finding == before
    assert len(result["title"]) == len(result["titleEn"]) == 512
    assert len(result["reason"]) == len(result["reasonEn"]) == 4096
    assert result["reason"].endswith("...")
