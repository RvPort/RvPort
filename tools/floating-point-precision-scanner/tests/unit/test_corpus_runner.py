from __future__ import annotations

import copy
import hashlib
import json
import runpy
import subprocess
import sys
from pathlib import Path

import pytest
from tests.helpers import X86, make_request

from floating_point_precision_scanner import corpus_runner as runner
from floating_point_precision_scanner import validation
from floating_point_precision_scanner.canonical import semantic_digest
from floating_point_precision_scanner.profiles import ProfileRegistry
from floating_point_precision_scanner.protocol import SchemaRegistry


def test_profile_overlays_are_validated_and_do_not_mutate_builtins():
    registry = runner._profiles({"source": {"fma.value.default_contraction": "fast"}})
    assert registry.get(X86).document["fma"]["value"]["default_contraction"] == "fast"
    assert ProfileRegistry().get(X86).document["fma"]["value"]["default_contraction"] == "on"
    with pytest.raises(KeyError):
        runner._profiles({"source": {"missing.value": True}})
    with pytest.raises(ValueError):
        runner._profiles({"source": {"floating_types.float.precision_bits": -1}})
    context = runner._context(registry.get(X86), {"errno": True})
    assert context["base_profile_fingerprint"] == registry.get(X86).fingerprint
    assert SchemaRegistry().validate("build_context", context) == []


def test_bitcode_uses_actual_bytes_and_compilation_context(tmp_path, monkeypatch):
    request = make_request(tmp_path)
    request.update(schema_version="1.4", source_revision="revision")
    calls = []

    def compile_fixture(arguments, **kwargs):
        calls.append((arguments, kwargs))
        Path(arguments[-1]).write_bytes(b"BC independent fixture")

    monkeypatch.setattr(runner.subprocess, "run", compile_fixture)
    runner._bitcode(request, Path("/clang"), ["-g"])
    reference = request["bitcode_artifact_set"]
    manifest_path = Path(reference["artifact_root"]) / reference["manifest"]
    document = json.loads(manifest_path.read_text())
    assert document["source_revision"] == "revision"
    assert document["members"][0]["sha256"] == hashlib.sha256(b"BC independent fixture").hexdigest()
    assert reference["manifest_sha256"] == hashlib.sha256(manifest_path.read_bytes()).hexdigest()
    assert calls[0][0] == [
        "/clang",
        "-g",
        "-emit-llvm",
        "-c",
        "main.c",
        "-o",
        str(tmp_path / "bitcode/main.bc"),
    ]
    assert calls[0][1] == dict(cwd=tmp_path, check=True, capture_output=True, timeout=60)


@pytest.mark.parametrize(
    "error", [subprocess.CalledProcessError(1, "clang"), subprocess.TimeoutExpired("clang", 60)]
)
def test_bitcode_producer_errors_are_not_replaced_by_observations(tmp_path, monkeypatch, error):
    request = make_request(tmp_path)
    request.update(schema_version="1.4", source_revision="revision")

    def fail(*args, **kwargs):
        raise error

    monkeypatch.setattr(runner.subprocess, "run", fail)
    with pytest.raises(type(error)):
        runner._bitcode(request, Path("clang"), [])
    assert "bitcode_artifact_set" not in request
    with pytest.raises(ValueError, match="invalid_corpus_request"):
        runner._bitcode({"project_root": str(tmp_path)}, Path("clang"), [])


@pytest.mark.parametrize("standard,bitcode", [("c17", False), ("c++20", True)])
def test_case_materializes_input_and_passes_contexts(tmp_path, monkeypatch, standard, bitcode):
    result = runner.Scanner(runner.ClangFrontend(Path("clang"))).scan({})
    calls = []
    bc_calls = []
    monkeypatch.setattr(
        runner.Scanner, "scan", lambda self, request: calls.append(request) or result
    )
    monkeypatch.setattr(runner, "_bitcode", lambda *args: bc_calls.append(args))
    recipe = dict(
        case_id="case",
        rule="reduction-reassociation",
        source="double value;\n",
        standard=standard,
        flags=["-g"],
        bitcode=bitcode,
        libm={"source": {"errno": True}},
        budgets={"max_rule_candidates": 1},
    )
    assert runner.run_case(recipe, tmp_path, Path("clang"), Path("worker")) is result
    name = "main.cpp" if bitcode else "main.c"
    assert (tmp_path / name).read_text() == recipe["source"]
    request = calls[0]
    assert request["input"]["translation_units"][0]["arguments"] == [
        "clang",
        f"-std={standard}",
        "-g",
        "-c",
        name,
    ]
    assert request["build_contexts"]["source"]["libm"]["value"] == {"errno": True}
    assert request["source_revision"] == semantic_digest(recipe)
    assert bool(bc_calls) is bitcode
    if bitcode:
        assert bc_calls[0][2] == [f"-std={standard}", "-g"]
    monkeypatch.setattr(runner.Scanner, "scan", lambda *args: {})
    with pytest.raises(ValueError, match="invalid_corpus_result"):
        runner.run_case(recipe, tmp_path, Path("clang"), Path("worker"))


@pytest.mark.parametrize(
    "cases", [None, [], [{"case_id": "a"}, {"case_id": "a"}], [{"case_id": "../escape"}]]
)
def test_invalid_corpus_inputs_fail_before_execution(tmp_path, cases):
    with pytest.raises(ValueError, match="executable"):
        runner.run_corpus({"cases": cases}, tmp_path / "out.json", Path("clang"), Path("worker"))
    assert not (tmp_path / "out.json").exists()


def test_runner_and_cli_save_fresh_results_and_provenance(tmp_path, monkeypatch):
    inputs = {"cases": [{"case_id": "one", "source": "input"}, {"case_id": "two"}]}
    roots = []

    def scan(recipe, root, clang, worker):
        assert root.is_dir()
        roots.append(root)
        return {"observed": recipe["case_id"]}

    monkeypatch.setattr(runner, "run_case", scan)
    output = tmp_path / "out/current.json"
    report = runner.run_corpus(inputs, output, Path("clang"), Path("worker"))
    assert report["observations"] == {"one": {"observed": "one"}, "two": {"observed": "two"}}
    assert report["fixture_digests"]["one"] == semantic_digest(inputs["cases"][0])
    assert (
        report["source_digests"]["corpus_runner.py"]
        == hashlib.sha256(Path(runner.__file__).read_bytes()).hexdigest()
    )
    assert all(not root.exists() for root in roots)
    assert json.loads((output.parent / "cases/one.json").read_text()) == {"observed": "one"}
    input_path = tmp_path / "inputs.json"
    input_path.write_text(json.dumps(inputs))
    assert runner.main(["--inputs", str(input_path), "--output", str(output)]) == 0


def test_module_entry_executes_main(tmp_path, monkeypatch):
    input_path = tmp_path / "empty.json"
    input_path.write_text('{"cases": []}')
    monkeypatch.setattr(
        sys,
        "argv",
        ["corpus_runner", "--inputs", str(input_path), "--output", str(tmp_path / "out.json")],
    )
    with (
        pytest.warns(RuntimeWarning, match="found in sys.modules"),
        pytest.raises(ValueError, match="executable_corpus_has_no_cases"),
    ):
        runpy.run_module("floating_point_precision_scanner.corpus_runner", run_name="__main__")


def test_multicandidate_observation_keeps_unknown_counts_and_duplicate_positions():
    rule = "reduction-reassociation"
    result = {
        "extensions": {
            validation.EXTENSION_ANALYSIS: {
                "assessments": [
                    {"ruleCode": rule, "status": "unknown"},
                    {"ruleCode": rule, "status": "matched"},
                ]
            }
        },
        "scan": {"status": "partial"},
        "findings": [
            {
                "fact": {"primaryLocation": {"file": "main.c", "line": 3}},
                "conclusion": {"findingCode": rule, "affectedCount": 1},
            },
            {
                "fact": {"primaryLocation": {"file": "main.c", "line": 3}},
                "conclusion": {"findingCode": rule, "affectedCount": 2},
            },
        ],
    }
    observation = validation._observation_from_result(result, rule, locations=True)
    assert observation["status"] == "matched"
    assert observation["assessment_statuses"] == ["matched", "unknown"]
    assert observation["finding_keys"] == ["main.c:3#1", "main.c:3#2"]
    assert observation["affected_counts"] == [1, 2]
    oracle = {**observation, "expected_status": "matched"}
    assert validation._score_case(oracle, observation).exact == 1
    changed = {**observation, "assessment_statuses": ["matched"]}
    assert validation._score_case(oracle, changed).exact == 0
    changed = {**observation, "finding_keys": observation["finding_keys"] * 2}
    assert validation._score_case(oracle, changed).false_positive == 2
    result["findings"][0]["fact"]["primaryLocation"] = None
    assert (
        "unlocated#1"
        in validation._observation_from_result(result, rule, locations=True)["finding_keys"]
    )


def test_current_observations_must_match_input_digests_and_case_set(tmp_path):
    fixture = Path(__file__).parents[1] / "fixtures/validation/executable-corpus-v1.json"
    corpus = json.loads(fixture.read_text())
    corpus["cases"] = corpus["cases"][:1]
    case = corpus["cases"][0]
    observed = {
        "source_digests": runner.source_digests(),
        "fixture_digests": {case["case_id"]: case["fixture_digest"]},
        "observations": {case["case_id"]: {"status": "missing"}},
    }
    corpus_path, observed_path = tmp_path / "oracle.json", tmp_path / "observed.json"
    corpus_path.write_text(json.dumps(corpus))
    args = [
        "--corpus",
        str(corpus_path),
        "--observed",
        str(observed_path),
        "--output",
        str(tmp_path / "score.json"),
    ]
    observed_path.write_text(json.dumps(observed))
    assert validation.main(args) == 1  # 缺失实际判断，不得通过门禁。
    stale = copy.deepcopy(observed)
    stale["fixture_digests"][case["case_id"]] = "0" * 64
    observed_path.write_text(json.dumps(stale))
    with pytest.raises(ValueError, match="fixture_digest_mismatch"):
        validation.main(args)
    observed["observations"]["unexpected"] = {}
    observed_path.write_text(json.dumps(observed))
    with pytest.raises(ValueError, match="observation_cases_mismatch"):
        validation.main(args)
    observed["source_digests"] = {}
    observed_path.write_text(json.dumps(observed))
    with pytest.raises(ValueError, match="source_digest_mismatch"):
        validation.main(args)
    corpus["extra"] = "invalid"
    corpus_path.write_text(json.dumps(corpus))
    with pytest.raises(ValueError, match="schema_validation_error"):
        validation.main(args)


def test_failed_corpus_keeps_previous_complete_report(tmp_path, monkeypatch):
    output = tmp_path / "observed.json"
    output.write_text("previous report")

    def fail(*args):
        raise subprocess.TimeoutExpired("clang", 60)

    monkeypatch.setattr(runner, "run_case", fail)
    with pytest.raises(subprocess.TimeoutExpired):
        runner.run_corpus({"cases": [{"case_id": "broken"}]}, output, Path("clang"), Path("worker"))
    assert output.read_text() == "previous report"


def test_multifile_recipe_preserves_each_compile_context(tmp_path, monkeypatch):
    captured = []
    result = runner.Scanner(runner.ClangFrontend(Path("clang"))).scan({})
    monkeypatch.setattr(runner.Scanner, "scan", lambda self, req: captured.append(req) or result)
    recipe = {"case_id": "multi", "rule": "unsafe-fp-optimization",
              "files": {"a.c": "double a;", "b.cpp": "double b;", "include/x.h": "// header"},
              "translation_units": [{"file": "a.c", "flags": ["-ffast-math"]},
                                    {"file": "b.cpp", "standard": "c++20", "flags": []}]}
    runner.run_case(recipe, tmp_path, Path("clang"), Path("worker"))
    assert (tmp_path / "include/x.h").read_text() == "// header"
    units = captured[0]["input"]["translation_units"]
    assert units[0]["arguments"] == ["clang", "-std=c17", "-ffast-math", "-c", "a.c"]
    assert units[1]["arguments"] == ["clang", "-std=c++20", "-c", "b.cpp"]
    compiled = []

    def compile_fixture(arguments, **kwargs):
        compiled.append(arguments)
        Path(arguments[-1]).write_bytes(str(arguments).encode())

    monkeypatch.setattr(runner.subprocess, "run", compile_fixture)
    runner._bitcode(captured[0], Path("clang"), [])
    manifest = json.loads((tmp_path / "bitcode/manifest.json").read_text())
    assert manifest["counts"] == {"planned": 2, "completed": 2, "failed": 0}
    assert len({m["context_id"] for m in manifest["members"]}) == 2
    assert any("-ffast-math" in args for args in compiled)
    assert any("-std=c++20" in args for args in compiled)


@pytest.mark.parametrize("extra", [
    {"files": {}}, {"files": []}, {"files": {"../outside.c": "text"}},
    {"files": {"/tmp/outside.c": "text"}}, {"files": {"C:\\outside.c": "text"}},
    {"files": {"main.c": None}}, {"translation_units": []},
    {"translation_units": [None]}, {"translation_units": [{"file": "missing.c"}]},
])
def test_invalid_multifile_recipe_is_rejected(tmp_path, extra):
    with pytest.raises(ValueError, match="invalid_corpus"):
        runner.run_case({"source": "void f() {}", "case_id": "bad", "rule": "long-double-abi",
                         **extra}, tmp_path, Path("clang"), Path("worker"))
    assert not (tmp_path / "cases/broken.json").exists()
