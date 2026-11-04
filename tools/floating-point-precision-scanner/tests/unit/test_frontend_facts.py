from __future__ import annotations

import json
import subprocess
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
from tests.helpers import QueueRunner, ast_for

from floating_point_precision_scanner.compilation import CompileContext
from floating_point_precision_scanner.facts import (
    _classification,
    _location_key,
    _walk,
    blocking_unknown_diagnostic,
    map_ast,
    source_location,
    source_range,
)
from floating_point_precision_scanner.frontend import (
    ClangFrontend,
    FileProcessResult,
    FrontendFailure,
    ProcessResult,
    SubprocessRunner,
    _resource_limiter,
)
from floating_point_precision_scanner.protocol import SchemaRegistry


def context(root: Path) -> CompileContext:
    source = root / "main.c"
    source.write_text("double x = 1.0;\n", encoding="utf-8")
    return CompileContext(
        "a" * 64,
        "main.c",
        source,
        root,
        "c",
        "c17",
        None,
        None,
        ("clang", "main.c"),
        ("-std=c17",),
        ("command",),
    )


def test_numeric_conversion_requires_canonical_scalar_endpoints() -> None:
    pointer = {
        "kind": "ImplicitCastExpr",
        "castKind": "NoOp",
        "type": {"qualType": "float *"},
        "inner": [{"kind": "DeclRefExpr", "type": {"qualType": "float *"}}],
    }
    assert _classification(pointer, "float *", None, "VarDecl", None) == (None, {})

    scalar = {
        "kind": "ImplicitCastExpr",
        "castKind": "FloatingCast",
        "type": {"qualType": "float"},
        "inner": [
            {"kind": "DeclRefExpr", "type": {
                "qualType": "real64", "desugaredQualType": "double"
            }}
        ],
    }
    fact_kind, payload = _classification(scalar, "float", None, "VarDecl", None)
    assert fact_kind == "conversion"
    assert payload["source_type"] == "double"
    assert payload["destination_type"] == "float"
    assert payload["source_scalar_type"]["source"] == "desugaredQualType"
    assert payload["endpoint_status"] == "known"


def test_genuine_missing_numeric_endpoint_is_candidate_local_unknown() -> None:
    node = {
        "kind": "ImplicitCastExpr",
        "castKind": "IntegralToFloating",
        "type": {"qualType": "float"},
        "inner": [{"kind": "RecoveryExpr"}],
    }
    fact_kind, payload = _classification(node, "float", None, "VarDecl", None)
    assert fact_kind == "conversion"
    assert payload["endpoint_status"] == "unknown"
    assert payload["source_scalar_type"]["category"] == "unknown"


def test_non_numeric_implicit_cast_is_structural_not_blocking(tmp_path: Path) -> None:
    item = context(tmp_path)
    ast = {
        "kind": "TranslationUnitDecl",
        "inner": [{
            "kind": "FunctionDecl",
            "name": "read_value",
            "type": {"qualType": "double (double)"},
            "loc": {"file": str(item.absolute_file), "line": 1, "col": 1},
            "inner": [{
                "kind": "ImplicitCastExpr",
                "castKind": "LValueToRValue",
                "type": {"qualType": "double"},
                "loc": {"line": 1, "col": 1},
                "inner": [{
                    "kind": "DeclRefExpr",
                    "type": {"qualType": "double"},
                    "loc": {"line": 1, "col": 1},
                }],
            }],
        }],
    }

    bundle = map_ast(item, ast, tmp_path, "x86", "riscv")

    assert bundle["unknowns"] == []
    assert blocking_unknown_diagnostic(bundle) is None


def test_assignment_and_dereference_are_transparent_but_unary_minus_is_arithmetic(
    tmp_path: Path,
) -> None:
    item = context(tmp_path)
    location = {"file": str(item.absolute_file), "line": 1, "col": 1}
    ast = {
        "kind": "TranslationUnitDecl",
        "inner": [{
            "kind": "FunctionDecl",
            "name": "operators",
            "type": {"qualType": "double (double *)"},
            "loc": location,
            "inner": [
                {
                    "kind": "BinaryOperator",
                    "opcode": "=",
                    "type": {"qualType": "double"},
                    "loc": {"line": 1, "col": 1},
                },
                {
                    "kind": "UnaryOperator",
                    "opcode": "*",
                    "type": {"qualType": "double"},
                    "loc": {"line": 1, "col": 1},
                },
                {
                    "kind": "UnaryOperator",
                    "opcode": "-",
                    "type": {"qualType": "double"},
                    "loc": {"line": 1, "col": 1},
                    "inner": [{
                        "kind": "DeclRefExpr",
                        "type": {"qualType": "double"},
                        "loc": {"line": 1, "col": 1},
                    }],
                },
            ],
        }],
    }

    bundle = map_ast(item, ast, tmp_path, "x86", "riscv")

    assert bundle["unknowns"] == []
    unary = [fact for fact in bundle["facts"] if fact["fact_kind"] == "arithmetic"]
    assert [fact["payload"]["opcode"] for fact in unary] == ["unary-"]


def test_scratch_space_system_long_double_declaration_is_not_a_project_fact(tmp_path: Path) -> None:
    ast = {
        "kind": "TranslationUnitDecl",
        "inner": [{
            "kind": "FunctionDecl",
            "name": "sinl",
            "type": {"qualType": "long double (long double)"},
            "loc": {"file": "<scratch space>", "line": 1, "col": 1},
            "inner": [{
                "kind": "ParmVarDecl", "name": "value",
                "type": {"qualType": "long double"},
                "loc": {"line": 1, "col": 1},
            }],
        }],
    }
    bundle = map_ast(context(tmp_path), ast, tmp_path, "x86", "riscv")
    assert bundle["facts"] == []
    assert bundle["unknowns"] == []


def test_old_fact_schema_is_a_safe_validation_miss(tmp_path: Path) -> None:
    bundle = map_ast(context(tmp_path), {"kind": "TranslationUnitDecl"}, tmp_path, "x86", "riscv")
    schemas = SchemaRegistry()
    assert schemas.validate("facts", bundle) == []
    stale = dict(bundle)
    stale["schema_version"] = "1.3"
    assert schemas.validate("facts", stale)


def executable(root: Path) -> Path:
    path = root / "clang-21"
    path.write_text("fake", encoding="utf-8")
    return path


class FileQueueRunner(SubprocessRunner):
    def __init__(self, document: str, *, returncode: int = 0, stderr: str = "") -> None:
        self.document = document
        self.returncode = returncode
        self.stderr = stderr
        self.output_path: Path | None = None

    def run_to_file(
        self, _arguments: Any, *, cwd: Path, output_limit: int, **_kwargs: Any
    ) -> FileProcessResult:
        self.output_path = cwd / "queued-ast.json"
        self.output_path.write_text(self.document, encoding="utf-8")
        return FileProcessResult(
            ProcessResult(
                self.returncode,
                "",
                self.stderr,
                stdout_truncated=len(self.document.encode("utf-8")) > output_limit,
            ),
            self.output_path,
        )


class FailingFileRunner(SubprocessRunner):
    def __init__(self, failure: BaseException) -> None:
        self.failure = failure

    def run_to_file(self, _arguments: Any, **_kwargs: Any) -> FileProcessResult:
        raise self.failure


def test_frontend_verify_and_ast_command(tmp_path: Path) -> None:
    runner = QueueRunner(
        ProcessResult(0, "clang version 21.1.8", ""),
        ProcessResult(0, json.dumps(ast_for(tmp_path / "main.c")), ""),
    )
    frontend = ClangFrontend(executable(tmp_path), runner)
    frontend.verify()
    ast = frontend.ast(context(tmp_path))
    assert ast["kind"] == "TranslationUnitDecl"
    command = runner.calls[1]
    assert command[-3:-1] == ["-Xclang", "-ast-dump=json"]
    assert "-fsyntax-only" in command


def test_demo_test1_large_ast_regression_fixture_is_sanitized_and_bounded() -> None:
    fixture = json.loads(
        (Path(__file__).parents[1] / "fixtures" / "demo-test1-large-ast-regression.json")
        .read_text(encoding="utf-8")
    )

    assert [item["path"] for item in fixture["translation_units"]] == [
        "app/main.cpp",
        "src/vectorcalc.cpp",
        "tests/vectorcalc_tests.cpp",
    ]
    largest_ast = max(item["observed_ast_bytes"] for item in fixture["translation_units"])
    assert largest_ast > 16 * 1024 * 1024
    assert fixture["expected"]["maximum_provenance_items"] == 128
    assert "frontend_invalid_json" in fixture["legacy_failure_chain"]


def test_frontend_missing_and_wrong_version(tmp_path: Path) -> None:
    with pytest.raises(FrontendFailure, match="frontend_unavailable"):
        ClangFrontend(tmp_path / "missing", QueueRunner()).verify()
    runner = QueueRunner(ProcessResult(0, "clang version 20.0.0", ""))
    with pytest.raises(FrontendFailure, match="frontend_version_mismatch"):
        ClangFrontend(executable(tmp_path), runner).verify()


@pytest.mark.parametrize(
    ("result", "code"),
    [
        (ProcessResult(1, "", "password=hidden C:\\secret\\a.h"), "frontend_nonzero_exit"),
        (ProcessResult(0, "{", ""), "frontend_invalid_json"),
        (ProcessResult(0, "[]", ""), "frontend_invalid_json"),
    ],
)
def test_frontend_ast_failures(tmp_path: Path, result: ProcessResult, code: str) -> None:
    frontend = ClangFrontend(executable(tmp_path), QueueRunner(result))
    with pytest.raises(FrontendFailure) as error:
        frontend.ast(context(tmp_path))
    assert error.value.diagnostic.diagnostic_code == code
    assert "hidden" not in error.value.diagnostic.suggestion


def test_frontend_reads_complete_ast_from_file_and_cleans_it(tmp_path: Path) -> None:
    document = json.dumps(ast_for(tmp_path / "main.c"))
    runner = FileQueueRunner(document)
    frontend = ClangFrontend(executable(tmp_path), runner, output_limit=len(document) + 1)

    assert frontend.ast(context(tmp_path))["kind"] == "TranslationUnitDecl"
    assert runner.output_path is not None and not runner.output_path.exists()


@pytest.mark.parametrize("returncode", [0, -9])
def test_frontend_reports_output_limit_before_json_parsing(tmp_path: Path, returncode: int) -> None:
    runner = FileQueueRunner(json.dumps(ast_for(tmp_path / "main.c")), returncode=returncode)
    frontend = ClangFrontend(executable(tmp_path), runner, output_limit=2)

    with pytest.raises(FrontendFailure) as error:
        frontend.ast(context(tmp_path))

    assert error.value.diagnostic.diagnostic_code == "frontend_output_limit_exceeded"
    assert error.value.diagnostic.retryable is False
    assert error.value.diagnostic.translation_unit_id == "a" * 64
    assert runner.output_path is not None and not runner.output_path.exists()


def test_frontend_file_output_is_cleaned_on_nonzero_exit(tmp_path: Path) -> None:
    runner = FileQueueRunner("not-json", returncode=1, stderr="compile failed")
    frontend = ClangFrontend(executable(tmp_path), runner)

    with pytest.raises(FrontendFailure) as error:
        frontend.ast(context(tmp_path))

    assert error.value.diagnostic.diagnostic_code == "frontend_nonzero_exit"
    assert runner.output_path is not None and not runner.output_path.exists()


@pytest.mark.parametrize("document", ["{", "[]"])
def test_frontend_file_output_is_cleaned_when_json_is_invalid(
    tmp_path: Path, document: str
) -> None:
    runner = FileQueueRunner(document)
    frontend = ClangFrontend(executable(tmp_path), runner)

    with pytest.raises(FrontendFailure) as error:
        frontend.ast(context(tmp_path))

    assert error.value.diagnostic.diagnostic_code == "frontend_invalid_json"
    assert runner.output_path is not None and not runner.output_path.exists()


@pytest.mark.parametrize("failure", [subprocess.TimeoutExpired("clang", 1), OSError("no")])
def test_frontend_invocation_failures(tmp_path: Path, failure: BaseException) -> None:
    frontend = ClangFrontend(executable(tmp_path), QueueRunner(failure))
    with pytest.raises(FrontendFailure) as error:
        frontend.ast(context(tmp_path))
    assert error.value.diagnostic.diagnostic_code in {"frontend_timeout", "frontend_unavailable"}


@pytest.mark.parametrize(
    ("failure", "code", "retryable"),
    [
        (subprocess.TimeoutExpired("clang", 1), "frontend_timeout", True),
        (OSError("unavailable"), "frontend_unavailable", False),
    ],
)
def test_frontend_file_invocation_failures(
    tmp_path: Path, failure: BaseException, code: str, retryable: bool
) -> None:
    frontend = ClangFrontend(executable(tmp_path), FailingFileRunner(failure))

    with pytest.raises(FrontendFailure) as error:
        frontend.ast(context(tmp_path))

    assert error.value.diagnostic.diagnostic_code == code
    assert error.value.diagnostic.retryable is retryable
    assert error.value.diagnostic.translation_unit_id == "a" * 64


def test_subprocess_runner_without_shell(tmp_path: Path) -> None:
    result = SubprocessRunner().run(
        [str(Path(__import__("sys").executable)), "-c", "print('ok')"],
        cwd=tmp_path,
        timeout=5,
        output_limit=1,
        cpu_seconds=2,
        memory_bytes=512 * 1024 * 1024,
    )
    assert result.returncode == 0
    assert result.stdout == "o"
    assert result.stdout_truncated is True
    assert result.stderr_truncated is False


def test_subprocess_runner_spools_stdout_and_enforces_limit(tmp_path: Path) -> None:
    runner = SubprocessRunner()
    value = runner.run_to_file(
        [str(Path(__import__("sys").executable)), "-c", "print('x' * 4096)"],
        cwd=tmp_path,
        timeout=5,
        output_limit=32,
        memory_bytes=512 * 1024 * 1024,
    )
    try:
        assert value.process.stdout_truncated is True
        assert value.stdout_path.stat().st_size > 32
    finally:
        value.stdout_path.unlink(missing_ok=True)


def test_spooled_limit_kills_running_producer_and_preserves_diagnostic(tmp_path: Path) -> None:
    runner = SubprocessRunner()
    result = runner.run_to_file(
        [str(Path(__import__("sys").executable)), "-c",
         "import sys,time; sys.stdout.write('x'*4096); sys.stdout.flush(); time.sleep(5)"],
        cwd=tmp_path, timeout=10, output_limit=32,
    )
    try:
        assert result.process.stdout_truncated and result.process.returncode != 0
    finally:
        result.stdout_path.unlink()
    fallback = ClangFrontend(executable(tmp_path), QueueRunner(result.process))
    with pytest.raises(FrontendFailure) as error:
        fallback.ast(context(tmp_path))
    assert error.value.diagnostic.diagnostic_code == "frontend_output_limit_exceeded"


def test_unlimited_process_preserves_stdout_and_stderr(tmp_path: Path) -> None:
    result = SubprocessRunner().run(
        [str(Path(__import__("sys").executable)), "-c",
         "import sys; print('out'); print('err',file=sys.stderr)"],
        cwd=tmp_path, timeout=5, output_limit=1024,
    )
    assert (result.returncode, result.stdout, result.stderr) == (0, "out\n", "err\n")


def test_subprocess_runner_timeout_removes_spooled_file(tmp_path: Path) -> None:
    runner = SubprocessRunner()

    with pytest.raises(subprocess.TimeoutExpired):
        runner.run_to_file(
            [str(Path(__import__("sys").executable)), "-c", "import time; time.sleep(2)"],
            cwd=tmp_path,
            timeout=0.05,
            output_limit=1024,
        )

    assert list(tmp_path.glob("clang-ast-*.json")) == []


def test_resource_limiter_applies_cpu_and_address_space() -> None:
    calls: list[tuple[int, tuple[int, int]]] = []
    resource = SimpleNamespace(
        setrlimit=lambda kind, value: calls.append((kind, value)),
        RLIMIT_CPU=1,
        RLIMIT_AS=2,
    )
    _resource_limiter(1.2, 4096, resource)()
    assert calls == [(1, (2, 2)), (2, (4096, 4096))]
    calls.clear()
    _resource_limiter(None, None, resource)()
    assert calls == []


def test_map_ast_produces_all_fact_kinds(tmp_path: Path) -> None:
    item = context(tmp_path)
    bundle = map_ast(item, ast_for(item.absolute_file), tmp_path, "source@1.0", "target@1.0")
    assert {fact["fact_kind"] for fact in bundle["facts"]} == {
        "type",
        "literal",
        "arithmetic",
        "comparison",
        "conversion",
        "call",
    }
    assert len({fact["fact_id"] for fact in bundle["facts"]}) == 6
    assert bundle["unknowns"] == []
    assert SchemaRegistry().validate("facts", bundle) == []
    repeated = map_ast(item, ast_for(item.absolute_file), tmp_path, "source@1.0", "target@1.0")
    assert bundle == repeated


def test_map_ast_unknown_is_blocking(tmp_path: Path) -> None:
    item = context(tmp_path)
    bundle = map_ast(item, ast_for(item.absolute_file, unknown=True), tmp_path, "s@1", "t@1")
    assert bundle["unknowns"][0]["kind"] == "FutureFloatExpr"
    diagnostic_item = blocking_unknown_diagnostic(bundle)
    assert diagnostic_item is not None
    assert diagnostic_item["diagnostic_code"] == "unsupported_floating_fact"
    assert blocking_unknown_diagnostic({"unknowns": []}) is None


def test_map_ast_excludes_external_header_facts_and_inherits_project_membership(
    tmp_path: Path,
) -> None:
    item = context(tmp_path)
    ast = {
        "kind": "TranslationUnitDecl",
        "inner": [
            {
                "kind": "FunctionDecl",
                "name": "external",
                "type": {"qualType": "double ()"},
                "loc": {"file": "/usr/include/math.h", "line": 1, "col": 1},
                "inner": [
                    {
                        "kind": "FloatingLiteral",
                        "value": "9.0",
                        "type": {"qualType": "double"},
                        "loc": {"line": 1, "col": 2},
                    }
                ],
            },
            {
                "kind": "FunctionDecl",
                "name": "local",
                "type": {"qualType": "double ()"},
                "loc": {"file": str(item.absolute_file), "line": 1, "col": 1},
                "inner": [
                    {
                        "kind": "FloatingLiteral",
                        "value": "1.0",
                        "type": {"qualType": "double"},
                        "loc": {"line": 1, "col": 2},
                    }
                ],
            },
        ],
    }

    facts = map_ast(item, ast, tmp_path, "s", "t")["facts"]

    assert [fact["payload"].get("value") for fact in facts if fact["fact_kind"] == "literal"] == [
        "1.0"
    ]


def test_map_ast_recognizes_file_elided_main_declaration_without_including_headers(
    tmp_path: Path,
) -> None:
    item = context(tmp_path)
    ast = {
        "kind": "TranslationUnitDecl",
        "inner": [
            {
                "kind": "FunctionDecl",
                "name": "local",
                "type": {"qualType": "double (double, double)"},
                "loc": {"offset": 7, "line": 1, "col": 8},
                "inner": [
                    {
                        "kind": "BinaryOperator",
                        "opcode": "+",
                        "type": {"qualType": "double"},
                        "range": {
                            "begin": {"offset": 28, "line": 1, "col": 29},
                            "end": {"offset": 32, "line": 1, "col": 33},
                        },
                    }
                ],
            },
            {
                "kind": "FunctionDecl",
                "name": "external",
                "type": {"qualType": "double ()"},
                "loc": {
                    "offset": 3,
                    "line": 2,
                    "col": 1,
                    "includedFrom": {"file": str(item.absolute_file)},
                },
            },
        ],
    }

    facts = map_ast(item, ast, tmp_path, "s", "t")["facts"]

    assert {fact["fact_kind"] for fact in facts} == {"type", "arithmetic"}
    assert {fact["function_symbol"] for fact in facts} == {"local"}


def test_source_location_macro_missing_and_external(tmp_path: Path) -> None:
    source = tmp_path / "main.c"
    macro = {
        "loc": {
            "spellingLoc": {"file": str(source), "line": 1, "col": 2},
            "expansionLoc": {"file": str(source), "line": 4, "col": 5, "macroName": "FP"},
        }
    }
    assert source_location(macro, tmp_path) == {
        "spelling": {"path": "main.c", "line": 1, "column": 2},
        "expansion": {"path": "main.c", "line": 4, "column": 5},
        "macro_name": "FP",
    }
    assert source_location({}, tmp_path) is None
    assert (
        source_location(
            {"range": {"begin": {"file": str(source), "line": 1, "col": 1, "offset": 3}}}, tmp_path
        )["offset"]
        == 3
    )
    assert (
        source_location(
            {"loc": {"file": str(tmp_path.parent / "outside.c"), "line": 1, "col": 1}}, tmp_path
        )
        is None
    )
    assert source_location({"loc": {"file": str(source), "line": 0, "col": 0}}, tmp_path) is None


def test_mapper_ignores_nonfloating_and_unknown_irrelevant_nodes(tmp_path: Path) -> None:
    item = context(tmp_path)
    ast = {
        "kind": "TranslationUnitDecl",
        "inner": [{"kind": "FutureNode", "type": {"qualType": "int"}}, {"kind": "CompoundStmt"}],
    }
    bundle = map_ast(item, ast, tmp_path, "s", "t")
    assert bundle["facts"] == []
    assert bundle["unknowns"] == []


def test_mapper_preserves_function_identity_and_source_range(tmp_path: Path) -> None:
    item = context(tmp_path)
    ast = {
        "kind": "TranslationUnitDecl",
        "inner": [
            {
                "kind": "FunctionDecl",
                "name": "compute",
                "mangledName": "compute",
                "loc": {"file": str(item.absolute_file), "line": 1, "col": 1},
                "inner": [
                    {
                        "kind": "BinaryOperator",
                        "opcode": "+",
                        "type": {"qualType": "double"},
                        "range": {
                            "begin": {
                                "file": str(item.absolute_file),
                                "line": 2,
                                "col": 3,
                            },
                            "end": {
                                "file": str(item.absolute_file),
                                "line": 2,
                                "col": 7,
                            },
                        },
                    }
                ],
            }
        ],
    }
    fact = map_ast(item, ast, tmp_path, "s", "t")["facts"][0]
    assert len(fact["function_id"]) == 64
    assert fact["function_symbol"] == "compute"
    assert fact["source_range"]["end"]["column"] == 7
    assert source_range({}, tmp_path) is None
    assert source_range({"range": {"begin": "bad"}}, tmp_path) is None


def test_mapper_tracks_nested_arithmetic_through_parentheses(tmp_path: Path) -> None:
    item = context(tmp_path)
    ast = {
        "kind": "TranslationUnitDecl",
        "inner": [
            {
                "kind": "BinaryOperator",
                "opcode": "*",
                "type": {"qualType": "double"},
                "loc": {"file": str(item.absolute_file), "line": 1, "col": 1},
                "inner": [
                    {
                        "kind": "ParenExpr",
                        "type": {"qualType": "double"},
                        "inner": [
                            {
                                "kind": "BinaryOperator",
                                "opcode": "+",
                                "type": {"qualType": "double"},
                                "loc": {
                                    "file": str(item.absolute_file),
                                    "line": 1,
                                    "col": 2,
                                },
                            }
                        ],
                    }
                ],
            }
        ],
    }
    arithmetic = [
        fact
        for fact in map_ast(item, ast, tmp_path, "s", "t")["facts"]
        if fact["fact_kind"] == "arithmetic"
    ]
    assert arithmetic[0]["payload"]["operation_chain_length"] == 2


def test_fact_mapping_branch_edges(tmp_path: Path) -> None:
    source = tmp_path / "main.c"
    source.write_text("double x;", encoding="utf-8")
    assert list(_walk({"inner": ["not-a-node"]})) == [{"inner": ["not-a-node"]}]
    assert list(_walk({"inner": "not-a-list"})) == [{"inner": "not-a-list"}]
    assert _location_key(None) == ("", 0, 0)
    assert _location_key({"expansion": "bad"}) == ("", 0, 0)
    assert source_location(
        {
            "loc": {
                "spellingLoc": "bad",
                "expansionLoc": {
                    "file": str(source),
                    "line": 2,
                    "col": 3,
                    "macroName": 7,
                },
            }
        },
        tmp_path,
    ) == {"expansion": {"path": "main.c", "line": 2, "column": 3}}
    assert source_location(
        {
            "loc": {
                "spellingLoc": {"file": str(source), "line": 1, "col": 1},
                "expansionLoc": "bad",
            }
        },
        tmp_path,
    ) == {"spelling": {"path": "main.c", "line": 1, "column": 1}}
    item = context(tmp_path)
    ast = {
        "kind": "TranslationUnitDecl",
        "inner": [
            {
                "kind": "BinaryOperator",
                "opcode": "?",
                "type": {"qualType": "double"},
                "loc": {"file": str(source), "line": 1, "col": 1},
            },
            {
                "kind": "CallExpr",
                "type": {"qualType": "double"},
                "loc": {"file": str(source), "line": 1, "col": 2},
                "inner": ["bad-child"],
            },
        ],
    }
    bundle = map_ast(item, ast, tmp_path, "s", "t")
    assert bundle["facts"][0]["payload"]["callee"] is None


def test_expression_context_facts_cover_conversion_comparison_reduction_and_api(
    tmp_path: Path,
) -> None:
    item = context(tmp_path)
    source = str(item.absolute_file)
    ast = {
        "kind": "TranslationUnitDecl",
        "inner": [
            {
                "kind": "ImplicitCastExpr",
                "castKind": "IntegralToFloating",
                "integerWidth": 64,
                "integerSigned": True,
                "type": {"qualType": "float"},
                "loc": {"file": source, "line": 1, "col": 1},
                "inner": [
                    {
                        "kind": "IntegerLiteral",
                        "value": "16777217",
                        "type": {"qualType": "long"},
                    }
                ],
            },
            {
                "kind": "IfStmt",
                "inner": [
                    {
                        "kind": "BinaryOperator",
                        "opcode": "==",
                        "type": {"qualType": "bool"},
                        "loc": {"file": source, "line": 2, "col": 3},
                        "inner": [
                            {"kind": "DeclRefExpr", "type": {"qualType": "double"}},
                            {
                                "kind": "FloatingLiteral",
                                "value": "1.0",
                                "type": {"qualType": "double"},
                            },
                        ],
                    }
                ],
            },
            {
                "kind": "CompoundAssignOperator",
                "opcode": "+=",
                "type": {"qualType": "double"},
                "accumulator": "sum",
                "iterationCount": 100,
                "loc": {"file": source, "line": 3, "col": 3},
            },
            {
                "kind": "CallExpr",
                "type": {"qualType": "__m128"},
                "intrinsicFamily": "x86-sse",
                "requiredTargetFeatures": ["sse"],
                "definitionHeader": "immintrin.h",
                "loc": {"file": source, "line": 4, "col": 3},
                "inner": [
                    {
                        "kind": "DeclRefExpr",
                        "referencedDecl": {
                            "id": "decl-1",
                            "name": "_mm_add_ps",
                            "builtinId": 42,
                            "header": "immintrin.h",
                        },
                    }
                ],
            },
        ],
    }
    facts = map_ast(item, ast, tmp_path, "s", "t")["facts"]
    by_kind = {fact["fact_kind"]: fact for fact in facts}
    assert by_kind["conversion"]["payload"]["source_type"] == "long"
    assert by_kind["conversion"]["payload"]["constant_value"] == "16777217"
    assert by_kind["comparison"]["payload"]["use_context"] == "IfStmt"
    assert by_kind["comparison"]["payload"]["operand_types"] == ["double", "double"]
    assert by_kind["reduction"]["payload"]["accumulator"] == "sum"
    assert by_kind["api"]["payload"]["resolved"] is True
    assert by_kind["api"]["payload"]["required_target_features"] == ["sse"]


def test_fenv_function_summary_propagates_direct_calls_and_marks_recursion(
    tmp_path: Path,
) -> None:
    item = context(tmp_path)
    source = str(item.absolute_file)
    ast = {
        "kind": "TranslationUnitDecl",
        "inner": [
            {
                "kind": "FunctionDecl",
                "name": "set_mode",
                "mangledName": "set_mode",
                "type": {"qualType": "void ()"},
                "loc": {"file": source, "line": 1, "col": 1},
                "inner": [
                    {
                        "kind": "CallExpr",
                        "type": {"qualType": "int"},
                        "loc": {"file": source, "line": 2, "col": 3},
                        "inner": [
                            {
                                "kind": "DeclRefExpr",
                                "referencedDecl": {"name": "fesetround"},
                            }
                        ],
                    }
                ],
            },
            {
                "kind": "FunctionDecl",
                "name": "wrapper",
                "mangledName": "wrapper",
                "type": {"qualType": "void ()"},
                "loc": {"file": source, "line": 4, "col": 1},
                "inner": [
                    {
                        "kind": "CallExpr",
                        "type": {"qualType": "void"},
                        "loc": {"file": source, "line": 5, "col": 3},
                        "inner": [
                            {
                                "kind": "DeclRefExpr",
                                "referencedDecl": {
                                    "name": "set_mode",
                                    "mangledName": "set_mode",
                                },
                            }
                        ],
                    },
                    {
                        "kind": "CallExpr",
                        "type": {"qualType": "void"},
                        "loc": {"file": source, "line": 6, "col": 3},
                        "inner": [
                            {
                                "kind": "DeclRefExpr",
                                "referencedDecl": {
                                    "name": "wrapper",
                                    "mangledName": "wrapper",
                                },
                            }
                        ],
                    },
                ],
            },
        ],
    }
    summaries = {
        fact["function_symbol"]: fact["payload"]
        for fact in map_ast(item, ast, tmp_path, "s", "t")["facts"]
        if fact["fact_kind"] == "function_summary"
    }
    assert summaries["set_mode"]["changes_rounding"] is True
    assert summaries["wrapper"]["changes_rounding"] is True
    assert summaries["wrapper"]["unknown_effect"] is True
    assert summaries["wrapper"]["propagation_depth"] == 1


def test_source_suppression_fact_requires_reason_and_adjacent_target(tmp_path: Path) -> None:
    item = context(tmp_path)
    item.absolute_file.write_text(
        "// fp-precision-scanner: ignore mixed-precision-conversion -- reviewed\nfloat value;\n",
        encoding="utf-8",
    )
    facts = map_ast(item, {"kind": "TranslationUnitDecl"}, tmp_path, "s", "t")["facts"]
    suppression = next(fact for fact in facts if fact["fact_kind"] == "suppression")
    assert suppression["payload"]["target_line"] == 2
    assert suppression["payload"]["reason"] == "reviewed"
    assert suppression["location"]["path"] == "main.c"


def test_reduction_requires_loop_or_explicit_iteration_evidence(tmp_path: Path) -> None:
    item = context(tmp_path)
    source = str(item.absolute_file)
    update = {
        "kind": "CompoundAssignOperator",
        "opcode": "+=",
        "type": {"qualType": "double"},
        "loc": {"file": source, "line": 2, "col": 3},
    }
    ast = {
        "kind": "TranslationUnitDecl",
        "inner": [
            update,
            {
                "kind": "ForStmt",
                "inner": [
                    {
                        **update,
                        "loc": {"file": source, "line": 4, "col": 5},
                    }
                ],
            },
        ],
    }
    reductions = [
        fact
        for fact in map_ast(item, ast, tmp_path, "s", "t")["facts"]
        if fact["fact_kind"] == "reduction"
    ]
    assert len(reductions) == 1
    assert reductions[0]["payload"]["loop_kind"] == "ForStmt"


def test_function_summary_depth_zero_reports_stable_frontier(tmp_path: Path) -> None:
    item = context(tmp_path)
    source = str(item.absolute_file)

    def call(name: str, line: int) -> dict[str, Any]:
        return {
            "kind": "CallExpr",
            "type": {"qualType": "void"},
            "loc": {"file": source, "line": line, "col": 3},
            "inner": [
                {
                    "kind": "DeclRefExpr",
                    "referencedDecl": {"name": name, "mangledName": name},
                }
            ],
        }

    ast = {
        "kind": "TranslationUnitDecl",
        "inner": [
            {
                "kind": "FunctionDecl",
                "name": "set_mode",
                "mangledName": "set_mode",
                "type": {"qualType": "void ()"},
                "loc": {"file": source, "line": 1, "col": 1},
                "inner": [call("fesetround", 2)],
            },
            {
                "kind": "FunctionDecl",
                "name": "wrapper",
                "mangledName": "wrapper",
                "type": {"qualType": "void ()"},
                "loc": {"file": source, "line": 4, "col": 1},
                "inner": [call("set_mode", 5)],
            },
        ],
    }
    summaries = {
        fact["function_symbol"]: fact["payload"]
        for fact in map_ast(
            item,
            ast,
            tmp_path,
            "s",
            "t",
            max_function_summary_depth=0,
        )["facts"]
        if fact["fact_kind"] == "function_summary"
    }
    assert summaries["wrapper"]["unknown_effect"] is True
    assert summaries["wrapper"]["frontier"] == "max_function_summary_depth"


def test_function_summary_marks_resolved_external_call_unknown(tmp_path: Path) -> None:
    item = context(tmp_path)
    source = str(item.absolute_file)
    ast = {
        "kind": "TranslationUnitDecl",
        "inner": [
            {
                "kind": "FunctionDecl",
                "name": "caller",
                "mangledName": "caller",
                "type": {"qualType": "double (double)"},
                "loc": {"file": source, "line": 1, "col": 1},
                "inner": [
                    {
                        "kind": "CallExpr",
                        "type": {"qualType": "double"},
                        "loc": {"file": source, "line": 2, "col": 3},
                        "inner": [
                            {
                                "kind": "DeclRefExpr",
                                "referencedDecl": {
                                    "name": "external_compute",
                                    "mangledName": "external_compute",
                                },
                            }
                        ],
                    }
                ],
            }
        ],
    }
    summary = next(
        fact
        for fact in map_ast(item, ast, tmp_path, "s", "t")["facts"]
        if fact["fact_kind"] == "function_summary"
    )
    assert summary["payload"]["unknown_effect"] is True
