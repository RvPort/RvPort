from __future__ import annotations

import hashlib
import json
import subprocess
from copy import deepcopy
from pathlib import Path
from typing import Any

import pytest
from tests.helpers import QueueRunner, ast_for, make_request

from floating_point_precision_scanner.artifacts import ArtifactPlan, BitcodeMember
from floating_point_precision_scanner.compilation import load_compilation_plan
from floating_point_precision_scanner.evidence import _point, build_evidence
from floating_point_precision_scanner.facts import map_ast
from floating_point_precision_scanner.frontend import ProcessResult
from floating_point_precision_scanner.ir_worker import IrWorker, IrWorkerFailure
from floating_point_precision_scanner.protocol import SchemaRegistry, parse_request


def ir_bundle(context_id: str, *, debug: bool = True, opcode: str = "fadd") -> dict[str, Any]:
    bundle: dict[str, Any] = {
        "kind": "LLVMFloatingPointFactBundle",
        "schema_version": "1.1",
        "producer": {
            "tool_version": "0.6.0",
            "llvm_version": "21.1.8",
            "reader_version": "1.0",
            "extractor_version": "1.2",
            "worker_protocol_version": "1.1",
        },
        "artifact": {
            "artifact_set_id": "a" * 64,
            "artifact_key": "b" * 64,
            "physical_tu_id": "c" * 64,
            "context_id": context_id,
            "profile": "source-preserving",
            "sha256": "d" * 64,
        },
        "module": {
            "identifier": "main",
            "target_triple": "",
            "data_layout": "",
            "module_flags": [],
        },
        "functions": [
            {
                "function_id": "f" * 64,
                "name": "main",
                "attributes": [],
                "environment_summary": {
                    "actions": [],
                    "unknown_effect": False,
                    "direct_calls": [],
                    "propagated_callee_ids": [],
                    "propagation_depth": 0,
                    "frontier": [],
                },
            }
        ],
        "facts": [
            {
                "fact_id": "e" * 64,
                "function_id": "f" * 64,
                "function": "main",
                "basic_block": "entry",
                "instruction_index": 0,
                "opcode": opcode,
                "result_type": "double",
                "operand_types": ["double", "double"],
                "operand_fact_ids": [],
                "use_fact_ids": [],
                "predicate": None,
                "reduction_group": None,
                "fast_math_flags": [],
                "instruction_attributes": [],
                "callee": None,
                "intrinsic_id": None,
                "constrained": False,
                "rounding_mode": None,
                "exception_behavior": None,
                "debug_location": (
                    {
                        "path": "main.c",
                        "line": 1,
                        "column": 16,
                        "scope": "main",
                        "inlined_at": [],
                    }
                    if debug
                    else None
                ),
            }
        ],
        "unknowns": [],
        "diagnostics": [],
        "budgets": {
            "max_facts": 100000,
            "max_use_edges": 1000,
            "max_summary_depth": 3,
            "max_summary_call_edges": 1000,
            "facts_emitted": 1,
            "use_edges_emitted": 0,
            "summary_call_edges_emitted": 0,
            "exhausted": False,
            "use_chain_exhausted": False,
            "summary_depth_exhausted": False,
            "summary_call_edges_exhausted": False,
        },
        "frontier": None,
        "semantic_digest": "",
    }
    material = str(bundle["artifact"]["artifact_key"])
    for fact in bundle["facts"]:
        material += "\n" + str(fact["fact_id"])
    bundle["semantic_digest"] = hashlib.sha256(material.encode()).hexdigest()
    return bundle


def identity(tmp_path: Path) -> tuple[ArtifactPlan, BitcodeMember]:
    path = tmp_path / "main.bc"
    path.write_bytes(b"BC")
    member = BitcodeMember(
        {
            "artifact_key": "b" * 64,
            "physical_tu_id": "c" * 64,
            "context_id": "d" * 64,
            "profile": "source-preserving",
            "sha256": "e" * 64,
        },
        path,
        None,
    )
    plan = ArtifactPlan({"artifact_set_id": "a" * 64}, (member,), (), 0)
    return plan, member


def test_ir_worker_verify_and_extract_success(tmp_path: Path) -> None:
    executable = tmp_path / "worker"
    executable.write_text("worker", encoding="utf-8")
    bundle = ir_bundle("d" * 64)
    runner = QueueRunner(
        ProcessResult(
            0,
            json.dumps(
                {
                    "tool_version": "0.6.0",
                    "llvm_version": "21.1.8",
                    "reader_version": "1.0",
                    "extractor_version": "1.2",
                    "worker_protocol_version": "1.1",
                }
            ),
            "",
        ),
        ProcessResult(0, json.dumps(bundle), ""),
    )
    worker = IrWorker(executable, runner)
    worker.verify()
    plan, member = identity(tmp_path)
    assert worker.extract(plan, member, max_facts=10, project_root=tmp_path) == bundle
    assert "--artifact-key" in runner.calls[1]


def test_ir_worker_verify_failures(tmp_path: Path) -> None:
    missing = IrWorker(tmp_path / "missing")
    with pytest.raises(IrWorkerFailure) as error:
        missing.verify()
    assert error.value.diagnostic.diagnostic_code == "ir_worker_unavailable"

    executable = tmp_path / "worker"
    executable.write_text("worker", encoding="utf-8")
    for result in (
        ProcessResult(1, "{}", ""),
        ProcessResult(0, "{", ""),
        ProcessResult(0, json.dumps({"llvm_version": "20"}), ""),
    ):
        with pytest.raises(IrWorkerFailure) as error:
            IrWorker(executable, QueueRunner(result)).verify()
        assert error.value.diagnostic.diagnostic_code == "ir_worker_version_mismatch"


def test_ir_worker_process_and_output_failures(tmp_path: Path) -> None:
    executable = tmp_path / "worker"
    executable.write_text("worker", encoding="utf-8")
    for raised, expected in (
        (subprocess.TimeoutExpired("worker", 1), "ir_worker_timeout"),
        (OSError("no process"), "ir_worker_unavailable"),
    ):
        with pytest.raises(IrWorkerFailure) as error:
            IrWorker(executable, QueueRunner(raised)).verify()
        assert error.value.diagnostic.diagnostic_code == expected


def test_ir_worker_normalizes_debug_paths_and_marks_external_locations(
    tmp_path: Path,
) -> None:
    executable = tmp_path / "worker"
    executable.write_text("worker", encoding="utf-8")
    plan, member = identity(tmp_path)
    bundle = ir_bundle("d" * 64)
    bundle["facts"][0]["debug_location"] = {
        "path": str(tmp_path / "main.c"),
        "line": 1,
        "column": 2,
        "scope": "main",
        "inlined_at": [
            {
                "path": str(tmp_path.parent / "external" / "secret.c"),
                "line": 3,
                "column": 4,
                "scope": "inline",
            }
        ],
    }
    result = IrWorker(executable, QueueRunner(ProcessResult(0, json.dumps(bundle), ""))).extract(
        plan, member, max_facts=10, project_root=tmp_path
    )
    assert result["facts"][0]["debug_location"]["path"] == "main.c"
    assert result["facts"][0]["debug_location"]["inlined_at"][0]["path"] == "secret.c"
    assert result["unknowns"][0]["kind"] == "debug_location_outside_project"


def test_ir_worker_debug_path_normalization_ignores_malformed_entries(tmp_path: Path) -> None:
    document: dict[str, Any] = {
        "facts": [
            None,
            {"debug_location": None},
            {"debug_location": {"path": None, "inlined_at": [None]}},
        ],
        "unknowns": [],
    }
    IrWorker._normalize_debug_paths(document, tmp_path)
    assert document["unknowns"] == []

    executable = tmp_path / "worker"
    executable.write_text("worker", encoding="utf-8")
    plan, member = identity(tmp_path)
    cases = [
        (ProcessResult(3, "", "bad"), 1000, "ir_worker_failed"),
        (ProcessResult(0, "{", ""), 1000, "ir_worker_invalid_json"),
        (ProcessResult(0, "[]", ""), 1000, "ir_worker_invalid_json"),
        (ProcessResult(0, json.dumps({"x": "long"}), ""), 1, "ir_worker_output_too_large"),
        (
            ProcessResult(0, json.dumps({**ir_bundle("d" * 64), "semantic_digest": "0" * 64}), ""),
            100_000,
            "ir_worker_semantic_digest_mismatch",
        ),
    ]
    for result, limit, expected in cases:
        worker = IrWorker(executable, QueueRunner(result), output_limit=limit)
        with pytest.raises(IrWorkerFailure) as error:
            worker.extract(plan, member, max_facts=1, project_root=tmp_path)
        assert error.value.diagnostic.diagnostic_code == expected


def source_and_member(tmp_path: Path) -> tuple[dict[str, Any], BitcodeMember]:
    request_document = make_request(tmp_path)
    request, errors = parse_request(request_document, SchemaRegistry())
    assert request is not None and not errors
    context = load_compilation_plan(request).contexts[0]
    source = map_ast(
        context,
        ast_for(context.absolute_file),
        tmp_path,
        request.source_profile,
        request.target_profile,
    )
    path = tmp_path / "main.bc"
    path.write_bytes(b"BC")
    return source, BitcodeMember(
        {
            "artifact_key": "b" * 64,
            "context_id": context.context_id,
            "profile": "source-preserving",
        },
        path,
        None,
    )


def test_evidence_debug_and_structural_binding(tmp_path: Path) -> None:
    source, member = source_and_member(tmp_path)
    for fact in source["facts"]:
        fact["function_symbol"] = "main"
    bundle = ir_bundle(str(source["context_id"]))
    result, diagnostics = build_evidence(
        source, bundle, member, SchemaRegistry(), mapping_required=False
    )
    assert diagnostics == []
    assert result["bindings"][0]["method"] == "derived-debug"
    assert result["bindings"][0]["confidence"] == "strong"
    assert SchemaRegistry().validate("evidence", result) == []

    bundle = ir_bundle(str(source["context_id"]), debug=False)
    for fact in source["facts"]:
        fact["function_symbol"] = "main"
    result, diagnostics = build_evidence(
        source, bundle, member, SchemaRegistry(), mapping_required=False
    )
    assert result["bindings"] == []
    assert diagnostics[0].diagnostic_code == "source_mapping_incomplete"


def test_evidence_unknown_and_required_mapping(tmp_path: Path) -> None:
    source, member = source_and_member(tmp_path)
    bundle = ir_bundle(str(source["context_id"]), opcode="unknown")
    result, diagnostics = build_evidence(
        source, bundle, member, SchemaRegistry(), mapping_required=False
    )
    assert result["unmatched_ir_fact_ids"] == ["e" * 64]
    assert diagnostics[0].severity == "warning"
    _, diagnostics = build_evidence(source, bundle, member, SchemaRegistry(), mapping_required=True)
    assert diagnostics[0].severity == "error"


def test_evidence_ambiguity_and_candidate_budget(tmp_path: Path) -> None:
    source, member = source_and_member(tmp_path)
    arithmetic = next(item for item in source["facts"] if item["fact_kind"] == "arithmetic")
    arithmetic["function_symbol"] = "main"
    duplicate = deepcopy(arithmetic)
    duplicate["fact_id"] = "1" * 64
    source["facts"].append(duplicate)
    bundle = ir_bundle(str(source["context_id"]))
    result, _ = build_evidence(source, bundle, member, SchemaRegistry(), mapping_required=False)
    assert result["bindings"] == []
    assert len(result["ambiguities"][0]["source_fact_ids"]) == 2

    result, diagnostics = build_evidence(
        source,
        bundle,
        member,
        SchemaRegistry(),
        mapping_required=True,
        max_candidates=1,
    )
    assert result["bindings"] == []
    assert diagnostics[0].diagnostic_code == "evidence_candidate_budget_exceeded"


def test_evidence_sidecar_paths(tmp_path: Path) -> None:
    source, member = source_and_member(tmp_path)
    bundle = ir_bundle(str(source["context_id"]), opcode="unknown")
    source_id = str(source["facts"][0]["fact_id"])
    sidecar = tmp_path / "bindings.json"

    def sidecar_document(
        *, artifact_key: str = "b" * 64, bindings: list[dict[str, str]] | None = None
    ) -> dict[str, Any]:
        return {
            "kind": "FloatingPointBindingSidecar",
            "schema_version": "1.0",
            "artifact_key": artifact_key,
            "context_id": member.context_id,
            "source_fact_bundle_digest": source["semantic_digest"],
            "ir_fact_bundle_digest": bundle["semantic_digest"],
            "bindings": bindings or [],
        }

    sidecar.write_text(
        json.dumps(
            sidecar_document(bindings=[{"source_fact_id": source_id, "ir_fact_id": "e" * 64}])
        ),
        encoding="utf-8",
    )
    member = BitcodeMember(member.document, member.path, sidecar)
    result, diagnostics = build_evidence(
        source, bundle, member, SchemaRegistry(), mapping_required=True
    )
    assert diagnostics == []
    assert result["bindings"][0]["confidence"] == "exact"
    assert result["bindings"][0]["method"] == "explicit-sidecar"

    source["facts"][0]["fact_kind"] = "reduction"
    reduction_result, _ = build_evidence(
        source, bundle, member, SchemaRegistry(), mapping_required=True
    )
    assert reduction_result["bindings"][0]["relation"] == "reduction-lowering"
    assert reduction_result["bindings"][0]["binding_id"] != result["bindings"][0]["binding_id"]

    mismatched = sidecar_document()
    mismatched["source_fact_bundle_digest"] = "0" * 64
    sidecar.write_text(json.dumps(mismatched), encoding="utf-8")
    result, diagnostics = build_evidence(
        source, bundle, member, SchemaRegistry(), mapping_required=False
    )
    assert result["bindings"] == []
    assert diagnostics[0].diagnostic_code == "binding_sidecar_artifact_mismatch"

    sidecar.write_text("{", encoding="utf-8")
    _, diagnostics = build_evidence(
        source, bundle, member, SchemaRegistry(), mapping_required=False
    )
    assert diagnostics[0].diagnostic_code == "binding_sidecar_invalid_json"

    sidecar.write_text(json.dumps([]), encoding="utf-8")
    _, diagnostics = build_evidence(
        source, bundle, member, SchemaRegistry(), mapping_required=False
    )
    assert diagnostics[0].diagnostic_code == "binding_sidecar_invalid"

    sidecar.write_text(
        json.dumps(sidecar_document(artifact_key="0" * 64)),
        encoding="utf-8",
    )
    _, diagnostics = build_evidence(
        source, bundle, member, SchemaRegistry(), mapping_required=False
    )
    assert diagnostics[0].diagnostic_code == "binding_sidecar_artifact_mismatch"

    sidecar.write_text(
        json.dumps(
            sidecar_document(bindings=[{"source_fact_id": "0" * 64, "ir_fact_id": "9" * 64}])
        ),
        encoding="utf-8",
    )
    result, _ = build_evidence(source, bundle, member, SchemaRegistry(), mapping_required=False)
    assert result["bindings"] == []


def test_evidence_location_and_sidecar_schema_edges(tmp_path: Path) -> None:
    assert _point({"expansion": "bad"}) is None
    assert _point({"path": 1, "line": "bad"}) is None
    assert _point({"path": "main.c", "line": 1, "column": "bad"}) == (
        "main.c",
        1,
        0,
    )
    source, member = source_and_member(tmp_path)
    sidecar = tmp_path / "bad-schema.json"
    sidecar.write_text(json.dumps({"kind": "FloatingPointBindingSidecar"}), encoding="utf-8")
    member = BitcodeMember(member.document, member.path, sidecar)
    _, diagnostics = build_evidence(
        source,
        ir_bundle(str(source["context_id"])),
        member,
        SchemaRegistry(),
        mapping_required=False,
    )
    assert diagnostics[0].diagnostic_code == "schema_validation_error"


def test_ir_worker_accepts_schema_layer_for_structurally_invalid_payload(tmp_path: Path) -> None:
    executable = tmp_path / "worker"
    executable.write_text("worker", encoding="utf-8")
    plan, member = identity(tmp_path)
    payload = {"artifact": [], "facts": "bad", "semantic_digest": "not-checked-here"}
    assert (
        IrWorker(executable, QueueRunner(ProcessResult(0, json.dumps(payload), ""))).extract(
            plan, member, max_facts=1, project_root=tmp_path
        )
        == payload
    )
    payload = {
        "artifact": {"artifact_key": "b" * 64},
        "facts": ["bad"],
        "semantic_digest": hashlib.sha256(("b" * 64).encode()).hexdigest(),
    }
    assert (
        IrWorker(executable, QueueRunner(ProcessResult(0, json.dumps(payload), ""))).extract(
            plan, member, max_facts=1, project_root=tmp_path
        )
        == payload
    )
