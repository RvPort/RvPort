from __future__ import annotations

from pathlib import Path

from tests.helpers import RISCV, X86
from tests.unit.test_frontend_facts import context
from tests.unit.test_rules import _profile

from floating_point_precision_scanner.ast_relations import relations
from floating_point_precision_scanner.facts import map_ast
from floating_point_precision_scanner.rules import evaluate_rules


def test_environment_regions_are_independent_and_calls_are_local(tmp_path: Path) -> None:
    compilation = context(tmp_path)

    def call(name: str, line: int):
        return {
            "kind": "CallExpr",
            "type": {"qualType": "int"},
            "loc": {"file": str(compilation.absolute_file), "line": line, "col": 1},
            "inner": [
                {
                    "kind": "DeclRefExpr",
                    "referencedDecl": {
                        "kind": "FunctionDecl",
                        "name": name,
                        "id": "ephemeral-pointer",
                    },
                },
                {
                    "kind": "DeclRefExpr",
                    "referencedDecl": {
                        "kind": "VarDecl",
                        "name": "state",
                        "id": "state-pointer",
                    },
                },
            ],
        }

    nodes = [
        call("fegetenv", 2),
        call("fesetround", 3),
        call("fesetenv", 4),
        call("fesetround", 5),
        call("unknown_function", 6),
        call("fesetenv", 7),
        call("fesetround", 8),
        call("fesetround", 9),
    ]
    ast = {
        "kind": "TranslationUnitDecl",
        "inner": [
            {
                "kind": "FunctionDecl",
                "name": "compute",
                "type": {"qualType": "void ()"},
                "loc": {"file": str(compilation.absolute_file), "line": 1, "col": 1},
                "inner": [
                    {
                        "kind": "VarDecl",
                        "name": "state",
                        "id": "state-pointer",
                        "loc": {"file": str(compilation.absolute_file), "line": 1, "col": 20},
                    },
                    {"kind": "CompoundStmt", "inner": nodes},
                ],
            }
        ],
    }
    bundle = map_ast(compilation, ast, tmp_path, "source", "target")
    environments = [fact for fact in bundle["facts"] if fact["fact_kind"] == "environment"]
    by_line = {f["location"]["line"]: f["payload"] for f in environments}
    assert by_line[2]["environment_region_id"] == by_line[4]["environment_region_id"]
    assert len({by_line[i]["environment_region_id"] for i in (2, 5, 8, 9)}) == 4
    result = evaluate_rules(
        ["floating-environment"], _profile(X86), _profile(RISCV), [bundle], [], [], {}
    )
    assert result.counts["matched"] == 3 and result.counts["unknown"] == 1
    assert {f["location"]["line"] for f in result.findings} == {2, 8, 9}
    unknown = next(a for a in result.assessments if a.status == "unknown")
    unknown_fact_ids = {ref.ref for ref in unknown.evidence if ref.kind == "source_fact"}
    assert all(
        f["fact_id"] not in unknown_fact_ids
        for f in environments
        if f["location"]["line"] in {2, 3, 4, 8, 9}
    )


def test_ast_data_edges_group_one_chain_but_keep_separate_fma_candidates(tmp_path: Path) -> None:
    def op(symbol: str, line: int, children=()):
        return {
            "kind": "BinaryOperator",
            "opcode": symbol,
            "type": {"qualType": "double"},
            "loc": {"file": str(tmp_path / "main.c"), "line": line, "col": 1},
            "inner": list(children),
        }

    multiply = op("*", 2)
    plus = op("+", 3, [{"kind": "ParenExpr", "inner": [multiply]}])
    outer = op("+", 4, [plus])
    separate = op("-", 6, [op("*", 7)])
    ast = {
        "kind": "FunctionDecl",
        "name": "compute",
        "inner": [
            {
                "kind": "CompoundStmt",
                "inner": [
                    {"kind": "ReturnStmt", "inner": [outer]},
                    {"kind": "ReturnStmt", "inner": [separate]},
                ],
            },
        ],
    }
    mapped = relations(ast, tmp_path, "context")
    assert mapped[id(plus)]["fma_candidate"] is True
    assert mapped[id(outer)]["fma_candidate"] is False
    assert mapped[id(multiply)]["evaluation_chain_id"] == mapped[id(outer)]["evaluation_chain_id"]
    assert mapped[id(separate)]["evaluation_chain_id"] != mapped[id(outer)]["evaluation_chain_id"]
    assert mapped[id(multiply)]["expression_id"] in mapped[id(plus)]["dependency_expression_ids"]


def test_control_flow_environment_relation_is_explicitly_uncertain(tmp_path: Path) -> None:
    call = {
        "kind": "CallExpr",
        "inner": [
            {
                "kind": "DeclRefExpr",
                "referencedDecl": {
                    "name": "fesetround",
                    "kind": "FunctionDecl",
                },
            }
        ],
    }
    ast = {"kind": "IfStmt", "inner": [{"kind": "CompoundStmt", "inner": [call]}]}
    mapped = relations(ast, tmp_path, "context")
    assert mapped[id(call)]["environment_region_unknown"] is True


def test_rounding_cast_ends_evaluation_chain_and_prevents_cross_cast_fma(tmp_path: Path) -> None:
    multiply = {"kind": "BinaryOperator", "opcode": "*", "type": {"qualType": "double"}}
    inner_add = {"kind": "BinaryOperator", "opcode": "+", "inner": [multiply]}
    cast = {
        "kind": "ImplicitCastExpr",
        "castKind": "FloatingCast",
        "type": {"qualType": "float"},
        "inner": [inner_add],
    }
    outer = {"kind": "BinaryOperator", "opcode": "+", "inner": [cast]}
    mapped = relations(outer, tmp_path, "context")
    assert mapped[id(cast)]["evaluation_chain_id"] == mapped[id(inner_add)]["evaluation_chain_id"]
    assert mapped[id(outer)]["evaluation_chain_id"] != mapped[id(inner_add)]["evaluation_chain_id"]
    assert mapped[id(outer)]["operation_chain_length"] == 1
    assert mapped[id(inner_add)]["operation_chain_length"] == 2
    assert mapped[id(outer)]["fma_candidate"] is False
    assert mapped[id(inner_add)]["fma_operand_expression_ids"] == [
        mapped[id(multiply)]["expression_id"]
    ]


def test_uninstantiated_template_pattern_is_not_an_executable_call(tmp_path: Path) -> None:
    import copy

    compilation = context(tmp_path)
    point = {"file": str(compilation.absolute_file), "line": 1, "col": 1}
    pattern = {
        "kind": "FunctionDecl",
        "name": "compute",
        "loc": point,
        "inner": [
            {
                "kind": "CallExpr",
                "loc": point,
                "inner": [
                    {"kind": "DeclRefExpr", "referencedDecl": {"name": "_mm_add_ss"}},
                ],
            }
        ],
    }
    first, second = copy.deepcopy(pattern), copy.deepcopy(pattern)
    first["mangledName"], second["mangledName"] = "compute_1", "compute_2"
    bundle = map_ast(
        compilation,
        {
            "kind": "TranslationUnitDecl",
            "inner": [
                {
                    "kind": "FunctionTemplateDecl",
                    "loc": point,
                    "inner": [pattern, first, second],
                }
            ],
        },
        tmp_path,
        "source",
        "target",
    )
    calls = [f for f in bundle["facts"] if f["fact_kind"] == "api"]
    assert len(calls) == 2
    assert {f["function_symbol"] for f in calls} == {"compute_1", "compute_2"}
    assert len({f["payload"]["expression_id"] for f in calls}) == 2
