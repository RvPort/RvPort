from pathlib import Path

import pytest

from floating_point_precision_scanner.evidence import _compatible
from floating_point_precision_scanner.facts import (
    _classification,
    _is_floating_scalar,
    source_location,
    source_range,
)
from floating_point_precision_scanner.rules import _aggregate_findings
from floating_point_precision_scanner.source_locations import SourceLocations, is_virtual_source


def test_recovers_bytes_and_sibling_file_context(tmp_path: Path) -> None:
    source = tmp_path / "main.c"
    source.write_bytes("// 中文\r\n\tdouble x;\r\n".encode())
    header = tmp_path / "own.h"
    header.write_bytes(b"\ndouble y;\n")
    resolver = SourceLocations(tmp_path, tmp_path, source)
    ast = {"inner": [
        {"loc": {"file": "own.h", "offset": 1, "col": 1}},
        {"range": {"begin": {"offset": 8, "col": 8}}},
        {"loc": {}, "range": {"begin": {"file": "main.c", "offset": 12, "col": 2}}},
    ]}
    resolver.normalize(ast)
    assert source_location(ast["inner"][0], tmp_path)["path"] == "own.h"
    assert source_location(ast["inner"][1], tmp_path)["line"] == 2
    assert source_location(ast["inner"][2], tmp_path) == {
        "path": "main.c", "line": 2, "column": 2, "offset": 12,
    }


def test_macro_separates_spelling_and_expansion(tmp_path: Path) -> None:
    source = tmp_path / "main.c"
    source.write_bytes(b"\nF(x)\n")
    header = tmp_path / "own.h"
    header.write_bytes(b"#define F(x) (x)\n")
    raw = {"spellingLoc": {"file": str(header), "offset": 14},
           "expansionLoc": {"file": str(source), "offset": 1}}
    SourceLocations(tmp_path, tmp_path, source).point(raw)
    result = source_location({"loc": raw}, tmp_path)
    assert result["spelling"]["path"] == "own.h"
    assert result["expansion"]["path"] == "main.c"
    assert result["expansion"]["line"] == 2


def test_virtual_clang_locations_never_become_project_paths_or_file_context(tmp_path: Path) -> None:
    source = tmp_path / "main.cpp"
    source.write_text("double value;\n", encoding="utf-8")
    resolver = SourceLocations(tmp_path, tmp_path / "build", source)
    virtual = {"file": "<scratch space>", "line": 1, "col": 1, "offset": 0}
    physical = {"file": str(source), "line": 1, "col": 1, "offset": 0}

    resolver.point(virtual)
    resolver.point(physical)

    assert virtual == {"file": "<scratch space>", "offset": 0, "sourceOrigin": "virtual"}
    assert resolver.last_file == str(source)
    assert source_location({"loc": virtual}, tmp_path) is None
    assert all(is_virtual_source(value) for value in (
        "<scratch space>", "<built-in>", "<command line>", "<vendor sentinel>"
    ))


@pytest.mark.parametrize("offset", [-1, 100, None, True, "1"])
def test_invalid_offset_does_not_invent_coordinates(tmp_path: Path, offset: object) -> None:
    source = tmp_path / "main.c"
    source.write_bytes(b"abc")
    raw = {"offset": offset, "col": 1}
    SourceLocations(tmp_path, tmp_path, source).point(raw)
    assert source_location({"loc": raw}, tmp_path) is None


def test_missing_external_and_budget_are_conservative(tmp_path: Path) -> None:
    source = tmp_path / "missing.c"
    resolver = SourceLocations(tmp_path, tmp_path, source)
    raw = {"offset": 0}
    resolver.point(raw)
    assert source_location({"loc": raw}, tmp_path) is None
    external = {"file": "/usr/include/math.h", "offset": 0}
    resolver.point(external)
    assert resolver.indexes.keys() == {str(source)}
    source.write_bytes(b"a\nb\n")
    for budget in (1, 5):
        resolver = SourceLocations(tmp_path, tmp_path, source, max_bytes=budget)
        raw = {"offset": 0}
        resolver.point(raw)
        assert source_location({"loc": raw}, tmp_path) is None


def test_presumed_lines_use_physical_offset_and_symlink_is_rejected(tmp_path: Path) -> None:
    source = tmp_path / "main.c"
    source.write_bytes(b'#line 500 "virtual.c"\nfloat x;\n')
    raw = {"file": str(source), "offset": 22, "presumedLine": 500, "line": 500, "col": 1}
    resolver = SourceLocations(tmp_path, tmp_path, source)
    resolver.point(raw)
    assert raw["line"] == 2
    link = tmp_path / "escape.c"
    link.symlink_to("/etc/passwd")
    raw = {"file": str(link), "offset": 0}
    resolver.point(raw)
    assert source_location({"loc": raw}, tmp_path) is None


def test_empty_and_unknown_include_and_complete_points(tmp_path: Path) -> None:
    resolver = SourceLocations(tmp_path, tmp_path, tmp_path / "main.c")
    for raw in ({}, {"includedFrom": {"file": "unknown.h"}, "offset": 2}):
        resolver.point(raw)
        assert source_location({"loc": raw}, tmp_path) is None
    raw = {"file": "main.c", "line": 3, "col": 4}
    resolver.point(raw)
    assert raw["line"] == 3
    assert resolver.indexes == {}
    resolver.normalize({"loc": None, "range": None, "other": 1})


def test_same_line_operations_match_their_actual_opcodes() -> None:
    for operator, opcode in [("+", "fadd"), ("-", "fsub"), ("*", "fmul"),
                             ("/", "fdiv"), ("%", "frem")]:
        fact = {"fact_kind": "arithmetic", "payload": {"opcode": operator}}
        assert _compatible(fact, {"opcode": opcode})
        assert not _compatible(fact, {"opcode": "fcmp"})


def test_aggregate_preserves_positions_and_selects_valid_primary() -> None:
    points = [{"path": "main.c", "line": line, "column": 2} for line in (8, 3, 15)]
    findings = [
        {"finding_id": "a", "assessment_id": "assessment-a",
         "finding_code": "risk", "aggregation_key": "same",
         "location": None, "related_locations": [points[0]]},
        {"finding_id": "b", "assessment_id": "assessment-b",
         "finding_code": "risk", "aggregation_key": "same",
         "location": points[1], "related_locations": [points[1], points[2]]},
    ]
    result = _aggregate_findings(findings, 10)[0]
    assert result["location"] == points[1]
    assert result["related_locations"] == [points[0], points[2]]
    assert _aggregate_findings(findings, 10) == _aggregate_findings(findings[::-1], 10)
    limited = _aggregate_findings(findings, 1)[0]
    assert limited["semantic"]["location_budget_exceeded"] is True
    assert limited["semantic"]["total_locations"] == 3
    assert len(limited["related_locations"]) == 1


def test_range_does_not_join_distinct_files(tmp_path: Path) -> None:
    assert source_range({"range": {
        "begin": {"file": "a.c", "line": 1, "col": 1},
        "end": {"file": "b.c", "line": 2, "col": 3},
    }}, tmp_path) is None
    resolver = SourceLocations(tmp_path, tmp_path, tmp_path / "a.c")
    resolver.point({"expansionLoc": {"file": "a.c", "line": 2, "col": 3}})


@pytest.mark.parametrize("value", ["float", "double", "long double", "const volatile double",
                                  "_Float16", "_Float128", "_Float64x", "__float128",
                                  "half", "__fp16"])
def test_floating_scalar_types(value: str) -> None:
    assert _is_floating_scalar({"type": {"qualType": value}})


@pytest.mark.parametrize("value", [None, 1, "", "const float *", "double **", "float[4]",
                                  "float (&)[4]", "double (*)()", "float Owner::*", "long double &",
                                  "my_double_record", "std::nullptr_t", "int", "_Complex double"])
def test_non_scalar_types_are_not_floating_comparisons(value: object) -> None:
    assert not _is_floating_scalar({"type": {"qualType": value}})


def test_typedefs_missing_types_and_comparison_operand_conversions() -> None:
    assert not _is_floating_scalar({})
    assert not _is_floating_scalar({"type": "double"})
    assert _is_floating_scalar({"type": {"qualType": "Real", "desugaredQualType": "double"}})
    assert not _is_floating_scalar({"type": {
        "qualType": "RealPtr", "desugaredQualType": "double *"}})
    operand = {"kind": "ImplicitCastExpr", "type": {"qualType": "const float *"},
               "inner": [{"kind": "DeclRefExpr", "type": {"qualType": "const float *"}}]}
    node = {"kind": "BinaryOperator", "opcode": "==", "type": {"qualType": "bool"},
            "inner": [operand, {"kind": "CXXNullPtrLiteralExpr",
                                "type": {"qualType": "std::nullptr_t"}}]}
    assert _classification(node, "bool", None, "IfStmt", None)[0] is None
    operand["type"]["qualType"] = "double"
    assert _classification(node, "bool", None, "IfStmt", None)[0] == "comparison"
    operand["type"]["qualType"] = "int"
    operand["inner"][0]["type"]["qualType"] = "double"
    assert _classification(node, "bool", None, "IfStmt", None)[0] is None
