from __future__ import annotations

import copy
from pathlib import Path
from typing import Any

from tests.helpers import RISCV, X86, make_request

from floating_point_precision_scanner.application import Scanner
from floating_point_precision_scanner.canonical import semantic_digest
from floating_point_precision_scanner.compilation import normalize_fp_options
from floating_point_precision_scanner.profiles import ProfileRegistry, TargetProfile
from floating_point_precision_scanner.protocol import SchemaRegistry, parse_request
from floating_point_precision_scanner.rules import (
    DEFAULT_RULES,
    LEGACY_DEFAULT_RULES,
    RULE_REGISTRY_DIGEST,
    evaluate_rules,
    normalize_rule_selection,
)


def _profile(reference: str, *, fma: str | None = None, unknown_abi: bool = False) -> TargetProfile:
    base = ProfileRegistry().get(reference)
    document = copy.deepcopy(base.document)
    if fma is not None:
        document["fma"]["value"]["default_contraction"] = fma
    if unknown_abi:
        document["floating_types"]["long_double"]["abi_class"] = "unknown"
    return TargetProfile(
        base.reference,
        base.target_triple,
        base.abi,
        base.compiler_version,
        semantic_digest(document),
        document,
    )


def _fact(
    index: int,
    *,
    kind: str = "arithmetic",
    type_name: str = "double",
    payload: dict[str, Any] | None = None,
) -> dict[str, Any]:
    return {
        "fact_id": f"{index:064x}",
        "fact_kind": kind,
        "type": type_name,
        "location": {"path": "main.c", "line": index + 1, "column": 1},
        "source_range": None,
        "function_id": "f" * 64,
        "function_symbol": "compute",
        "payload": payload or {},
    }


def _source(
    context: str = "a" * 64,
    *,
    facts: list[dict[str, Any]] | None = None,
    options: dict[str, Any] | None = None,
) -> dict[str, Any]:
    return {
        "translation_unit_id": context,
        "context_id": context,
        "facts": facts or [],
        "effective_fp_options": options or normalize_fp_options([]),
    }


def _ir(
    context: str = "a" * 64,
    *,
    profile: str = "build-faithful",
    opcode: str = "call",
    callee: str | None = "llvm.fma.f64",
    flags: list[str] | None = None,
) -> dict[str, Any]:
    return {
        "artifact": {"context_id": context, "profile": profile},
        "facts": [
            {
                "fact_id": "e" * 64,
                "opcode": opcode,
                "callee": callee,
                "fast_math_flags": flags or [],
            }
        ],
    }


def _contract_ir(context: str = "a" * 64) -> dict[str, Any]:
    return {
        "artifact": {"context_id": context, "profile": "build-faithful"},
        "facts": [
            {
                "fact_id": "d" * 64,
                "opcode": "fmul",
                "callee": None,
                "fast_math_flags": ["contract"],
            },
            {
                "fact_id": "e" * 64,
                "opcode": "fadd",
                "callee": None,
                "fast_math_flags": ["contract"],
            },
        ],
    }


def _evidence(context: str = "a" * 64, *, ambiguous: bool = False) -> dict[str, Any]:
    return {
        "artifact": {"context_id": context, "profile": "build-faithful"},
        "bindings": [
            {
                "binding_id": "b" * 64,
                "source_fact_id": f"{1:064x}",
                "ir_fact_id": "e" * 64,
                "confidence": "exact",
                "method": "explicit-sidecar",
            }
        ],
        "ambiguities": (
            [
                {
                    "ir_fact_id": "e" * 64,
                    "source_fact_ids": [f"{1:064x}", f"{2:064x}"],
                }
            ]
            if ambiguous
            else []
        ),
    }


def _run(
    rules: tuple[str, ...],
    source: dict[str, Any],
    *,
    source_profile: TargetProfile | None = None,
    target_profile: TargetProfile | None = None,
    ir: list[dict[str, Any]] | None = None,
    evidence: list[dict[str, Any]] | None = None,
    budgets: dict[str, int] | None = None,
):
    return evaluate_rules(
        rules,
        source_profile or _profile(X86),
        target_profile or _profile(RISCV),
        [source],
        ir or [],
        evidence or [],
        budgets or {},
    )


def test_rule_selection_default_empty_subset_conflict_and_version() -> None:
    selected, versions, errors = normalize_rule_selection(None)
    assert selected == DEFAULT_RULES and versions == {} and errors == []
    assert normalize_rule_selection({"enabled": []})[0] == ()
    assert normalize_rule_selection({"disabled": ["fma-contraction"]})[0] == tuple(
        code for code in DEFAULT_RULES if code != "fma-contraction"
    )
    assert normalize_rule_selection({"enabled": ["long-double-abi"]})[0] == ("long-double-abi",)
    assert normalize_rule_selection({"enabled": ["missing"]})[2][0].diagnostic_code == (
        "rules_not_available"
    )
    assert (
        normalize_rule_selection({"enabled": ["long-double-abi"], "disabled": ["long-double-abi"]})[
            2
        ][0].diagnostic_code
        == "rule_configuration_conflict"
    )
    assert (
        normalize_rule_selection({"versions": {"long-double-abi": "2.0"}})[2][0].diagnostic_code
        == "rules_not_available"
    )
    assert len(RULE_REGISTRY_DIGEST) == 64


def test_parse_request_defaults_to_core_rules_and_allows_facts_only(tmp_path: Path) -> None:
    document = make_request(tmp_path)
    del document["rules"]
    request, errors = parse_request(document, SchemaRegistry())
    assert request is not None and errors == [] and request.rules_enabled == LEGACY_DEFAULT_RULES
    document["schema_version"] = "1.3"
    request, errors = parse_request(document, SchemaRegistry())
    assert request is not None and errors == [] and request.rules_enabled == DEFAULT_RULES
    document["rules"] = {"enabled": []}
    request, errors = parse_request(document, SchemaRegistry())
    assert request is not None and errors == [] and request.rules_enabled == ()


def test_effective_fp_options_use_last_option_wins() -> None:
    options = normalize_fp_options(
        [
            "-ffast-math",
            "-fhonor-nans",
            "-fhonor-infinities",
            "-fsigned-zeros",
            "-fno-reciprocal-math",
            "-ffp-contract=off",
            "-fmath-errno",
            "-ftrapping-math",
        ]
    )
    assert options["reassociation"] is True
    assert options["no_nans"] is False
    assert options["no_infs"] is False
    assert options["no_signed_zeros"] is False
    assert options["reciprocal_math"] is False
    assert options["fp_contract"] == "off"
    assert options["math_errno"] is True and options["trapping_math"] is True
    assert options["provenance"]["no_nans"] == "-fhonor-nans"
    assert normalize_fp_options(["-mfpmath=387", "-mfpmath=sse"])["x87_enabled"] is False


def test_long_double_rule_matches_boundary_and_excludes_local() -> None:
    boundary = _fact(
        1,
        kind="type",
        type_name="long double",
        payload={"boundary_kind": "parameter"},
    )
    run = _run(("long-double-abi",), _source(facts=[boundary]))
    assert run.counts["matched"] == 1
    assert run.findings[0]["finding_code"] == "long-double-abi"
    assert run.findings[0]["severity"] == "high"
    assert run.findings[0]["requires_human_confirmation"] is True
    local = copy.deepcopy(boundary)
    local["payload"]["boundary_kind"] = "local"
    run = _run(("long-double-abi",), _source(facts=[local]))
    assert run.assessments[0].status == "not_matched" and run.findings == ()


def test_long_double_rule_propagates_unknown_profile() -> None:
    boundary = _fact(
        1,
        kind="type",
        type_name="long double",
        payload={"boundary_kind": "return"},
    )
    run = _run(
        ("long-double-abi",),
        _source(facts=[boundary]),
        target_profile=_profile(RISCV, unknown_abi=True),
    )
    assert run.assessments[0].status == "unknown"
    assert run.diagnostics[0].stage == "rule_evaluation"


def test_external_system_long_double_flood_is_not_an_abi_candidate() -> None:
    facts = [
        _fact(
            index,
            kind="type",
            type_name="long double",
            payload={
                "boundary_kind": "return",
                "is_long_double": True,
                "origin": "external-system",
            },
        )
        for index in range(1, 210)
    ]
    result = _run(("long-double-abi",), _source(facts=facts))
    assert result.assessments[0].status == "not_matched"
    assert result.assessments[0].reason_code == "no_abi_boundary"
    assert result.findings == ()


def test_long_double_occurrences_keep_bounded_direct_evidence_and_frontier() -> None:
    def boundaries(count: int) -> list[dict[str, Any]]:
        return [
            _fact(
                index,
                kind="type",
                type_name="long double",
                payload={"boundary_kind": "parameter"},
            )
            for index in range(1, count + 1)
        ]

    # 每个 ABI boundary 独立发布，只引用声明和两个 profile，不再把整 TU 塞入一条证据链。
    at_limit = _run(("long-double-abi",), _source(facts=boundaries(126)))
    assert at_limit.assessments[0].status == "matched"
    assert len(at_limit.findings) == 126
    assert {len(item["evidence"]) for item in at_limit.findings} == {3}

    over_limit = _run(
        ("long-double-abi",), _source(facts=boundaries(127)),
        budgets={"max_rule_candidates": 126},
    )
    repeated = _run(
        ("long-double-abi",), _source(facts=boundaries(127)),
        budgets={"max_rule_candidates": 126},
    )
    assert over_limit == repeated
    assert len(over_limit.findings) == 126
    assert any(item.status == "unknown" and item.reason_code == "budget_exceeded"
               for item in over_limit.assessments)
    assert over_limit.diagnostics[-1].diagnostic_code == "long_double_abi_budget_exceeded"


def test_x87_rule_matches_with_ir_and_is_unknown_without_it() -> None:
    chain = _fact(1, payload={"operation_chain_length": 3})
    options = normalize_fp_options(["-mfpmath=387", "-fexcess-precision=fast"])
    missing = _run(("x87-excess-precision",), _source(facts=[chain], options=options))
    assert missing.assessments[0].status == "unknown"
    matched = _run(
        ("x87-excess-precision",),
        _source(facts=[chain], options=options),
        ir=[_ir(opcode="fadd", callee=None)],
        evidence=[_evidence()],
    )
    assert matched.assessments[0].status == "matched"
    assert matched.findings[0]["requires_human_confirmation"] is True
    strict = _run(("x87-excess-precision",), _source(facts=[chain]))
    assert strict.assessments[0].status == "not_matched"


def test_fma_rule_handles_match_missing_ir_ambiguity_and_explicit_call() -> None:
    candidate = _fact(1, payload={"fma_candidate": True, "explicit_fma": False})
    target = _profile(RISCV, fma="off")
    matched = _run(
        ("fma-contraction",),
        _source(facts=[candidate]),
        target_profile=target,
        ir=[_ir()],
        evidence=[_evidence()],
    )
    assert matched.assessments[0].status == "matched"
    assert matched.findings[0]["finding_code"] == "fma-contraction"
    missing = _run(("fma-contraction",), _source(facts=[candidate]))
    assert missing.assessments[0].reason_code == "build_faithful_ir_missing"
    ambiguous = _run(
        ("fma-contraction",),
        _source(facts=[candidate]),
        target_profile=target,
        ir=[_ir()],
        evidence=[_evidence(ambiguous=True)],
    )
    assert ambiguous.assessments[0].reason_code == "binding_ambiguous"
    explicit = copy.deepcopy(candidate)
    explicit["payload"]["explicit_fma"] = True
    assert _run(("fma-contraction",), _source(facts=[explicit])).assessments[0].status == (
        "not_matched"
    )
    contract_evidence = _evidence()
    contract_evidence["bindings"].append({
        **contract_evidence["bindings"][0], "binding_id": "c" * 64, "ir_fact_id": "d" * 64,
    })
    contract_ir = _contract_ir()
    contract_ir["facts"][1]["operand_fact_ids"] = ["d" * 64]
    contract_pair = _run(
        ("fma-contraction",),
        _source(
            facts=[candidate],
            options=normalize_fp_options(["-ffp-contract=fast"]),
        ),
        ir=[contract_ir],
        evidence=[contract_evidence],
    )
    assert contract_pair.assessments[0].status == "matched"
    assert contract_pair.findings[0]["semantic"]["lowering"] == "contractable-mul-add"


def test_unsafe_rule_uses_effective_semantics_and_detects_ir_conflict() -> None:
    unsafe = _run(
        ("unsafe-fp-optimization",),
        _source(facts=[_fact(1)], options=normalize_fp_options(["-ffast-math"])),
    )
    assert unsafe.assessments[0].status == "matched"
    semantics = unsafe.findings[0]["semantic"]["active_semantics"]
    assert "no_nans" in semantics and "reassociation" in semantics
    strict = _run(("unsafe-fp-optimization",), _source(facts=[_fact(1)]))
    assert strict.assessments[0].status == "not_matched"
    conflict = _run(
        ("unsafe-fp-optimization",),
        _source(facts=[_fact(1)]),
        ir=[_ir(opcode="fadd", callee=None, flags=["nnan"])],
    )
    assert conflict.assessments[0].status == "unknown"
    assert conflict.assessments[0].reason_code == "option_ir_conflict"


def test_unsafe_findings_aggregate_deterministically_and_budget_is_stable() -> None:
    options = normalize_fp_options(["-fassociative-math"])
    sources = [
        _source("b" * 64, facts=[_fact(2)], options=options),
        _source("a" * 64, facts=[_fact(1)], options=options),
    ]
    run = evaluate_rules(
        ("unsafe-fp-optimization",),
        _profile(X86),
        _profile(RISCV),
        sources,
        [],
        [],
        {"max_related_locations": 1},
    )
    repeated = evaluate_rules(
        ("unsafe-fp-optimization",),
        _profile(X86),
        _profile(RISCV),
        list(reversed(sources)),
        [],
        [],
        {"max_related_locations": 1},
    )
    assert run == repeated
    assert len(run.findings) == 1 and run.findings[0]["affected_count"] == 2
    limited = evaluate_rules(
        ("long-double-abi", "unsafe-fp-optimization"),
        _profile(X86),
        _profile(RISCV),
        [_source(facts=[_fact(1)], options=options)],
        [],
        [],
        {"max_rule_assessments": 1},
    )
    assert limited.assessments[-1].reason_code == "budget_exceeded"


class _CoreFrontend:
    def verify(self) -> None:
        return None

    def ast(self, context: Any) -> dict[str, Any]:
        path = str(context.absolute_file)
        return {
            "kind": "TranslationUnitDecl",
            "inner": [
                {
                    "kind": "FunctionDecl",
                    "name": "exported",
                    "mangledName": "exported",
                    "type": {"qualType": "long double (long double)"},
                    "loc": {"file": path, "line": 1, "col": 1},
                    "inner": [
                        {
                            "kind": "ParmVarDecl",
                            "name": "value",
                            "type": {"qualType": "long double"},
                            "loc": {"file": path, "line": 1, "col": 22},
                        }
                    ],
                }
            ],
        }


def test_application_executes_default_rules_and_result_schema(tmp_path: Path) -> None:
    request = make_request(tmp_path)
    del request["rules"]
    result = Scanner(_CoreFrontend()).scan(request)  # type: ignore[arg-type]
    assert result["scan"]["status"] == "complete"
    analysis = result["extensions"]["org.rvport.floating-point-scanner.analysis"]
    assert analysis["analysisScope"] == "coreMigrationRisks"
    assert len(analysis["enabledRules"]) == 4
    assert result["summary"]["assessments"]["matched"] == 2
    assert result["findings"][0]["conclusion"]["findingCode"] == "long-double-abi"
    assert SchemaRegistry().validate("result", result) == []


def test_rule_engine_isolates_invalid_input() -> None:
    malformed = _source(facts=[_fact(1, kind="type", type_name="long double", payload=None)])
    malformed["facts"][0]["payload"] = None
    run = _run(("long-double-abi",), malformed)
    assert run.assessments[0].status == "failed"
    assert run.diagnostics[0].diagnostic_code == "rule_evaluation_failed"
