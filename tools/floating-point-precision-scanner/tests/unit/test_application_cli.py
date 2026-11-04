from __future__ import annotations

import json
import runpy
import sys
from pathlib import Path
from typing import Any

import pytest
from tests.helpers import X86, ast_for, make_request

from floating_point_precision_scanner import cli
from floating_point_precision_scanner.application import Scanner
from floating_point_precision_scanner.diagnostics import diagnostic
from floating_point_precision_scanner.frontend import FrontendFailure
from floating_point_precision_scanner.protocol import SchemaRegistry
from floating_point_precision_scanner.result_v2 import failure_result_v2

ANALYSIS = "org.rvport.floating-point-scanner.analysis"


class FakeFrontend:
    def __init__(
        self,
        *,
        fail_verify: bool = False,
        fail_files: set[str] | None = None,
        unknown: bool = False,
    ) -> None:
        self.fail_verify = fail_verify
        self.fail_files = fail_files or set()
        self.unknown = unknown
        self.calls: list[str] = []

    def verify(self) -> None:
        if self.fail_verify:
            raise FrontendFailure(
                diagnostic("frontend_unavailable", "frontend", "不可用", "Unavailable")
            )

    def ast(self, context: Any) -> dict[str, Any]:
        self.calls.append(context.file)
        if context.file in self.fail_files:
            raise FrontendFailure(
                diagnostic(
                    "frontend_nonzero_exit",
                    "frontend",
                    "失败",
                    "Failed",
                    translation_unit_id=context.translation_unit_id,
                )
            )
        return ast_for(context.absolute_file, unknown=self.unknown)


def test_scanner_complete_and_result_contract(tmp_path: Path) -> None:
    result = Scanner(FakeFrontend()).scan(make_request(tmp_path))
    assert result["scan"]["status"] == "complete"
    assert result["findings"] == []
    assert result["extensions"][ANALYSIS]["analysisScope"] == "factsOnly"
    assert result["extensions"][ANALYSIS]["enabledRules"] == []
    assert result["summary"] == {
        "findings": 0,
        "suppressed": 0,
        "diagnostics": 0,
        "assessments": {
            "planned": 0,
            "completed": 0,
            "matched": 0,
            "notMatched": 0,
            "unknown": 0,
            "skipped": 0,
            "failed": 0,
        },
        "layers": {
            "artifact": {"planned": 0, "completed": 0, "failed": 0, "unknown": 0},
            "source": {"planned": 1, "completed": 1, "failed": 0, "unknown": 0},
            "ir": {"planned": 0, "completed": 0, "failed": 0, "unknown": 0},
            "evidence": {"planned": 0, "completed": 0, "failed": 0, "unknown": 0},
            "rules": {"planned": 0, "completed": 0, "failed": 0, "unknown": 0},
        },
    }
    assert SchemaRegistry().validate("result", result) == []


def test_scanner_invalid_request_and_unknown_profile(tmp_path: Path) -> None:
    invalid = make_request(tmp_path)
    invalid["schema_version"] = "9.0"
    result = Scanner(FakeFrontend()).scan(invalid)
    assert result["scan"]["status"] == "invalid_request"
    assert result["diagnostics"][0]["code"] == "schema_validation_error"
    unknown = make_request(tmp_path)
    unknown["source_profile"] = "missing@1.0"
    result = Scanner(FakeFrontend()).scan(unknown)
    assert result["scan"]["status"] == "invalid_request"
    assert result["diagnostics"][0]["code"] == "unknown_profile"


def test_validate_request_paths(tmp_path: Path) -> None:
    scanner = Scanner(FakeFrontend())
    assert scanner.validate_request(make_request(tmp_path)) == []
    bad = make_request(tmp_path)
    bad["rules"] = {"enabled": ["rule"]}
    assert scanner.validate_request(bad)[0].diagnostic_code == "rules_not_available"
    bad = make_request(tmp_path)
    bad["source_profile"] = "missing@1.0"
    assert scanner.validate_request(bad)[0].diagnostic_code == "unknown_profile"
    bad = make_request(tmp_path)
    bad["input"] = {"compile_commands": "missing.json"}
    assert scanner.validate_request(bad)[0].diagnostic_code == "invalid_compilation_database"


def test_scanner_partial_failed_unknown_and_frontend_unavailable(tmp_path: Path) -> None:
    first = tmp_path / "first.c"
    second = tmp_path / "second.c"
    first.write_text("double x;", encoding="utf-8")
    second.write_text("double y;", encoding="utf-8")
    units = [
        {"file": name, "directory": str(tmp_path), "arguments": ["clang", name]}
        for name in ("first.c", "second.c")
    ]
    partial = Scanner(FakeFrontend(fail_files={"second.c"})).scan(make_request(tmp_path, units))
    assert partial["scan"]["status"] == "partial"
    assert partial["project"]["translationUnits"]["completed"] == 1
    assert partial["project"]["translationUnits"]["failed"] == 1
    failed = Scanner(FakeFrontend(fail_files={"first.c", "second.c"})).scan(
        make_request(tmp_path, units)
    )
    assert failed["scan"]["status"] == "failed"
    unknown = Scanner(FakeFrontend(unknown=True)).scan(make_request(tmp_path))
    assert unknown["scan"]["status"] == "partial"
    assert unknown["project"]["translationUnits"]["unknown"] == 1
    unavailable = Scanner(FakeFrontend(fail_verify=True)).scan(make_request(tmp_path))
    assert unavailable["scan"]["status"] == "failed"
    assert unavailable["diagnostics"][0]["code"] == "frontend_unavailable"


def test_scanner_profile_conflict_and_missing_source(tmp_path: Path) -> None:
    source = tmp_path / "target.c"
    source.write_text("double x;", encoding="utf-8")
    units = [
        {
            "file": "target.c",
            "directory": str(tmp_path),
            "arguments": ["clang", "--target=riscv64-unknown-linux-gnu", "target.c"],
        }
    ]
    result = Scanner(FakeFrontend()).scan(make_request(tmp_path, units))
    assert result["scan"]["status"] == "failed"
    assert result["diagnostics"][0]["code"] == "profile_context_conflict"
    missing = [
        {"file": "missing.c", "directory": str(tmp_path), "arguments": ["clang", "missing.c"]}
    ]
    result = Scanner(FakeFrontend()).scan(make_request(tmp_path, missing))
    assert result["scan"]["status"] == "failed"
    assert result["project"]["translationUnits"]["planned"] == 1


def test_scanner_rejects_invalid_fact_bundle(tmp_path: Path) -> None:
    class RejectFactsSchema(SchemaRegistry):
        def validate(self, name: str, document: Any):  # type: ignore[no-untyped-def]
            if name == "facts":
                return [diagnostic("bad_facts", "facts_validation", "无效事实", "Invalid facts")]
            return super().validate(name, document)

    result = Scanner(FakeFrontend(), schemas=RejectFactsSchema()).scan(make_request(tmp_path))
    assert result["scan"]["status"] == "failed"
    assert result["diagnostics"][0]["code"] == "bad_facts"


def write_request(path: Path, request: dict[str, Any]) -> Path:
    path.write_text(json.dumps(request), encoding="utf-8")
    return path


def test_cli_version_and_profiles(capsys: pytest.CaptureFixture[str]) -> None:
    assert cli.main(["version"]) == 0
    version = json.loads(capsys.readouterr().out)
    assert version["clang_version"] == "21.1.8"
    assert cli.main(["profiles", "list"]) == 0
    profiles = json.loads(capsys.readouterr().out)
    assert len(profiles) == 2
    assert cli.main(["profiles", "show", X86]) == 0
    assert json.loads(capsys.readouterr().out)["profile_id"].startswith("x86_64")


def test_python_module_entrypoint(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.setattr(sys, "argv", ["fp-precision-scanner", "version"])
    with pytest.raises(SystemExit) as error:
        runpy.run_module("floating_point_precision_scanner.__main__", run_name="__main__")
    assert error.value.code == 0
    assert json.loads(capsys.readouterr().out)["tool_version"] == "0.6.0"


def test_cli_validate_and_usage_errors(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    request_path = write_request(tmp_path / "request.json", make_request(tmp_path))
    assert cli.main(["validate-request", "--request", str(request_path)]) == 0
    assert json.loads(capsys.readouterr().out)["valid"] is True
    assert cli.main(["unknown"]) == 2
    error = json.loads(capsys.readouterr().err)
    assert error["diagnostics"][0]["messageEn"] == "The CLI request is invalid."
    (tmp_path / "bad.json").write_text("{", encoding="utf-8")
    assert cli.main(["validate-request", "--request", str(tmp_path / "bad.json")]) == 2
    (tmp_path / "array.json").write_text("[]", encoding="utf-8")
    assert cli.main(["validate-request", "--request", str(tmp_path / "array.json")]) == 2
    assert cli.main(["validate-request", "--request", str(tmp_path / "absent.json")]) == 2
    assert cli.main(["profiles", "show", "missing@1.0"]) == 2


def test_cli_scan_failed_writes_schema_result(tmp_path: Path) -> None:
    request_path = write_request(tmp_path / "request.json", make_request(tmp_path))
    output = tmp_path / "result.json"
    assert (
        cli.main(
            [
                "scan",
                "--request",
                str(request_path),
                "--output",
                str(output),
                "--clang",
                str(tmp_path / "missing"),
            ]
        )
        == 3
    )
    result = json.loads(output.read_text(encoding="utf-8"))
    assert result["scan"]["status"] == "failed"


def test_cli_maps_all_result_statuses(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    request_path = write_request(tmp_path / "request.json", make_request(tmp_path))

    class StubScanner:
        def __init__(self, *_: Any, **__: Any) -> None:
            pass

        def scan(self, request: dict[str, Any]) -> dict[str, Any]:
            return failure_result_v2("stub", str(request["status"]), [])

    monkeypatch.setattr(cli, "Scanner", StubScanner)
    for status, code in [
        ("complete", 0),
        ("invalid_request", 2),
        ("failed", 3),
        ("partial", 4),
        ("internal_error", 5),
    ]:
        document = make_request(tmp_path)
        document["status"] = status
        request_path.write_text(json.dumps(document), encoding="utf-8")
        assert (
            cli.main(
                [
                    "scan",
                    "--request",
                    str(request_path),
                    "--output",
                    str(tmp_path / f"{status}.json"),
                ]
            )
            == code
        )


def test_cli_output_and_internal_errors(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    request_path = write_request(tmp_path / "request.json", make_request(tmp_path))

    class StubScanner:
        def __init__(self, *_: Any, **__: Any) -> None:
            pass

        def scan(self, _: dict[str, Any]) -> dict[str, Any]:
            return failure_result_v2("stub", "complete", [])

    monkeypatch.setattr(cli, "Scanner", StubScanner)
    monkeypatch.setattr(cli, "write_atomic", lambda *_: (_ for _ in ()).throw(OSError("disk")))
    assert cli.main(["scan", "--request", str(request_path), "--output", str(tmp_path / "x")]) == 5
    error = json.loads(capsys.readouterr().err)
    assert error["diagnostics"][0]["code"] == "output_write_failed"
    monkeypatch.setattr(cli, "ProfileRegistry", lambda: (_ for _ in ()).throw(RuntimeError("boom")))
    assert cli.main(["version"]) == 0
    capsys.readouterr()
    assert cli.main(["profiles", "list"]) == 5
    error = json.loads(capsys.readouterr().err)
    assert error["diagnostics"][0]["code"] == "internal_error"


def test_cli_rejects_invalid_projected_result_and_result_budget(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    request = make_request(tmp_path)
    request_path = write_request(tmp_path / "request.json", request)

    class InvalidResultScanner:
        def __init__(self, *_: Any, **__: Any) -> None:
            pass

        @staticmethod
        def scan(_: dict[str, Any]) -> dict[str, Any]:
            return {"status": "complete"}

    monkeypatch.setattr(cli, "Scanner", InvalidResultScanner)
    output = tmp_path / "invalid-result.json"
    assert cli.main(["scan", "--request", str(request_path), "--output", str(output)]) == 5
    result = json.loads(output.read_text(encoding="utf-8"))
    assert result["scan"]["status"] == "internal_error"
    assert result["diagnostics"][0]["code"] == "unsupported-result-version"

    class ValidResultScanner:
        def __init__(self, *_: Any, **__: Any) -> None:
            pass

        @staticmethod
        def scan(_: dict[str, Any]) -> dict[str, Any]:
            return failure_result_v2("budget", "complete", [])

    monkeypatch.setattr(cli, "Scanner", ValidResultScanner)
    request["budgets"] = {"max_result_bytes": 1}
    request_path.write_text(json.dumps(request), encoding="utf-8")
    assert cli.main(["scan", "--request", str(request_path), "--output", str(output)]) == 3
    error = json.loads(capsys.readouterr().err)
    assert error["diagnostics"][0]["code"] == "result_budget_exceeded"


def test_cli_publishes_internal_error_result(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    request_path = write_request(tmp_path / "request.json", make_request(tmp_path))

    class BrokenScanner:
        def __init__(self, *_: Any, **__: Any) -> None:
            pass

        def scan(self, _: dict[str, Any]) -> dict[str, Any]:
            raise RuntimeError("sensitive details")

    monkeypatch.setattr(cli, "Scanner", BrokenScanner)
    output = tmp_path / "internal.json"
    assert cli.main(["scan", "--request", str(request_path), "--output", str(output)]) == 5
    result = json.loads(output.read_text(encoding="utf-8"))
    assert result["scan"]["status"] == "internal_error"
    assert "sensitive" not in json.dumps(result)
    assert SchemaRegistry().validate("result", result) == []
