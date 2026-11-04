"""从 AST 的父子关系和顺序语句建立表达式、舍入链与环境区间。"""

from __future__ import annotations

from collections.abc import Mapping
from pathlib import Path
from typing import Any

from .canonical import stable_id


def relations(
    ast: Mapping[str, Any],
    root: Path,
    context: str,
    eligible: set[int] | None = None,
) -> dict[int, dict[str, Any]]:
    # 对象地址只作为本次遍历的查找键，不序列化或参与稳定身份。
    from .facts import (
        _FUNCTION_KINDS,
        ARITHMETIC_OPS,
        _call_payload,
        _children,
        _qual_type,
        _walk,
        source_location,
        source_range,
    )

    result: dict[int, dict[str, Any]] = {}
    arithmetic_chains: dict[str, list[dict[str, Any]]] = {}
    declarations = {
        str(node["id"]): stable_id(
            "environment_storage",
            {
                "context": context,
                "name": node.get("name"),
                "type": _qual_type(node),
                "location": source_location(node, root),
            },
        )
        for node in _walk(ast)
        if node.get("kind") in {"VarDecl", "ParmVarDecl"}
        and (eligible is None or id(node) in eligible)
        and node.get("id")
        and source_location(node, root)
    }

    def buffer(node: Mapping[str, Any]) -> str | None:
        references = {
            declarations[str(decl["id"])]
            for argument in _children(node)[1:]
            for child in _walk(argument)
            if isinstance(decl := child.get("referencedDecl"), Mapping)
            and str(decl.get("id")) in declarations
        }
        return next(iter(references)) if len(references) == 1 else None

    def identity(node: Mapping[str, Any], function: str | None) -> str:
        return stable_id(
            "source_expression",
            {
                "context": context,
                "function": function,
                "kind": node.get("kind"),
                "type": _qual_type(node),
                "opcode": node.get("opcode"),
                "location": source_location(node, root),
                "range": source_range(node, root),
            },
        )

    def walk(
        node: Mapping[str, Any],
        function: str | None = None,
        chain: str | None = None,
        region: str | None = None,
        uncertain: bool = False,
    ) -> list[str]:
        if eligible is not None and id(node) not in eligible:
            for child in _children(node):
                walk(child, function, chain, region, uncertain)
            return []
        kind = node.get("kind")
        if kind in _FUNCTION_KINDS:
            function = stable_id(
                "function_semantics",
                {
                    "context": context,
                    "symbol": node.get("mangledName") or node.get("name"),
                    "type": _qual_type(node),
                    "location": source_location(node, root),
                },
            )
            chain, region, uncertain = None, None, False
        expression = identity(node, function)
        arithmetic = kind == "BinaryOperator" and node.get("opcode") in ARITHMETIC_OPS
        conversion = kind in {"ImplicitCastExpr", "CStyleCastExpr", "CXXStaticCastExpr"}
        transparent = kind in {"ParenExpr", "ExprWithCleanups", "MaterializeTemporaryExpr"}
        if not (arithmetic or conversion or transparent):
            chain = None
        elif (
            conversion
            and (kind != "ImplicitCastExpr" or node.get("castKind") == "FloatingCast")
            or arithmetic
            and chain is None
        ):
            chain = expression
        data: dict[str, Any] = {
            "expression_id": expression,
            "expression_identity_unknown": not bool(
                source_location(node, root) or source_range(node, root)
            ),
        }
        if chain:
            data["evaluation_chain_id"] = chain
            if arithmetic:
                arithmetic_chains.setdefault(chain, []).append(data)
        if region:
            data["environment_region_id"] = region
            data["environment_region_unknown"] = uncertain
        result[id(node)] = data
        descendants: list[str] = []
        children = _children(node)
        if kind == "CompoundStmt":
            active: str | None = region
            saved: str | None = None
            region_uncertain = uncertain
            for child in children:
                action = (
                    _call_payload(child).get("fenv_action")
                    if child.get("kind") in {"CallExpr", "CXXMemberCallExpr"}
                    else None
                )
                if action == "saves_environment":
                    active = identity(child, function)
                    saved = buffer(child)
                    region_uncertain = uncertain or saved is None
                elif action and active is None or action == "changes_rounding" and saved is None:
                    active = identity(child, function)
                if action == "restores_environment" and (saved is None or buffer(child) != saved):
                    region_uncertain = True
                    for previous in result.values():
                        if previous.get("environment_region_id") == active:
                            previous["environment_region_unknown"] = True
                descendants.extend(walk(child, function, None, active, region_uncertain))
                if action == "restores_environment":
                    active, saved = None, None
                    region_uncertain = uncertain
            return descendants
        branch = kind in {
            "IfStmt",
            "SwitchStmt",
            "ForStmt",
            "WhileStmt",
            "DoStmt",
            "CXXForRangeStmt",
            "ConditionalOperator",
            "GotoStmt",
        }
        for child in children:
            descendants.extend(walk(child, function, chain, region, uncertain or branch))
        if arithmetic:
            data["dependency_expression_ids"] = sorted(set(descendants))
            # 收缩只允许直接乘法操作数；(a*b+c)+d 的外层不是第二次 FMA。
            operands = []
            for child in children:
                while (
                    child.get("kind") == "ParenExpr"
                    or (
                        child.get("kind") == "ImplicitCastExpr"
                        and child.get("castKind") in {None, "NoOp", "LValueToRValue"}
                    )
                ) and _children(child):
                    child = _children(child)[0]
                operands.append(child)
            data["fma_candidate"] = node.get("opcode") in {"+", "-"} and any(
                child.get("kind") == "BinaryOperator" and child.get("opcode") == "*"
                for child in operands
            )
            data["fma_operand_expression_ids"] = [
                result[id(child)]["expression_id"]
                for child in operands
                if child.get("kind") == "BinaryOperator"
                and child.get("opcode") == "*"
                and id(child) in result
            ]
        return [expression, *descendants]

    walk(ast)
    for members in arithmetic_chains.values():
        for data in members:
            data["operation_chain_length"] = len(members)
    return result
