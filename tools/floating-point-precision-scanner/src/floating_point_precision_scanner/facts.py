"""Clang 21 AST JSON 到 canonical floating-point facts 的唯一映射层。"""

from __future__ import annotations

import re
from collections import defaultdict
from collections.abc import Iterator, Mapping
from pathlib import Path
from typing import Any

from . import CLANG_VERSION, FACT_MAPPER_VERSION, FACTS_SCHEMA_VERSION, TOOL_VERSION
from .canonical import normalize_repo_path, semantic_digest, stable_id
from .canonical_types import canonical_scalar_type, from_spellings
from .compilation import CompileContext
from .diagnostics import diagnostic
from .source_locations import SourceLocations, is_virtual_source

FLOAT_NAMES = ("float", "double", "_Float", "__fp16", "half")
TYPE_KINDS = {"VarDecl", "ParmVarDecl", "FieldDecl", "TypedefDecl"}
CAST_KINDS = {"ImplicitCastExpr", "CStyleCastExpr", "CXXStaticCastExpr", "CXXFunctionalCastExpr"}
NUMERIC_CAST_KINDS = {"FloatingCast", "IntegralToFloating"}
STRUCTURAL_FLOAT_KINDS = {
    "TranslationUnitDecl",
    "CompoundStmt",
    "DeclRefExpr",
    "ArraySubscriptExpr",
    "InitListExpr",
    "ImplicitValueInitExpr",
    "ParenExpr",
    "MaterializeTemporaryExpr",
    "CXXBindTemporaryExpr",
    "ExprWithCleanups",
    *CAST_KINDS,
}
TRANSPARENT_BINARY_OPS = {"=", ","}
TRANSPARENT_UNARY_OPS = {"*"}
ARITHMETIC_OPS = {"+", "-", "*", "/", "%"}
COMPARISON_OPS = {"==", "!=", "<", "<=", ">", ">="}
FENV_ACTIONS = {
    "fesetround": "changes_rounding",
    "fegetround": "reads_environment",
    "fetestexcept": "tests_exceptions",
    "feclearexcept": "clears_exceptions",
    "feraiseexcept": "raises_exceptions",
    "fegetenv": "saves_environment",
    "feholdexcept": "saves_environment",
    "fesetenv": "restores_environment",
    "feupdateenv": "restores_environment",
}
MATH_CALLS = {
    "sin",
    "sinf",
    "sinl",
    "cos",
    "cosf",
    "cosl",
    "tan",
    "tanf",
    "tanl",
    "exp",
    "expf",
    "expl",
    "log",
    "logf",
    "logl",
    "pow",
    "powf",
    "powl",
    "sqrt",
    "sqrtf",
    "sqrtl",
    "hypot",
    "hypotf",
    "hypotl",
    "erf",
    "erfc",
}
_SUPPRESSION_RE = re.compile(r"fp-precision-scanner:\s*ignore\s+([a-z0-9-]+)\s+--\s+(.+?)\s*$")


def _walk(node: Mapping[str, Any]) -> Iterator[Mapping[str, Any]]:
    yield node
    children = node.get("inner", ())
    if isinstance(children, list):
        for child in children:
            if isinstance(child, Mapping):
                yield from _walk(child)


_FUNCTION_KINDS = {
    "FunctionDecl",
    "CXXMethodDecl",
    "CXXConstructorDecl",
    "CXXDestructorDecl",
    "ConversionFunctionDecl",
}


def _walk_with_function(
    node: Mapping[str, Any],
    function_id: str | None,
    function_symbol: str | None,
    function_metadata: Mapping[str, Any] | None,
    translation_unit_id: str,
    root: Path,
    parent_kind: str | None = None,
    enclosing_loop_kind: str | None = None,
    project_member: bool | None = None,
    enclosing_loop: Mapping[str, Any] | None = None,
) -> Iterator[
    tuple[
        Mapping[str, Any],
        str | None,
        str | None,
        Mapping[str, Any] | None,
        str | None,
        str | None,
        bool | None,
        Mapping[str, Any] | None,
    ]
]:
    explicit_membership = _node_project_membership(node, root)
    implicit_main_file_membership = (
        explicit_membership is None
        and project_member is None
        and parent_kind == "TranslationUnitDecl"
        and _has_unqualified_source_location(node)
    )
    current_project_member: bool | None
    if explicit_membership is not None:
        current_project_member = explicit_membership
    elif implicit_main_file_membership:
        current_project_member = True
    else:
        current_project_member = project_member
    current = function_id
    current_symbol = function_symbol
    current_metadata = function_metadata
    if str(node.get("kind", "")) in _FUNCTION_KINDS:
        enclosing_loop_kind = None
        enclosing_loop = None
        current_symbol = str(node.get("mangledName") or node.get("name") or "") or None
        current = stable_id(
            "function",
            {
                "translation_unit_id": translation_unit_id,
                "kind": node.get("kind"),
                "name": node.get("name"),
                "mangled_name": node.get("mangledName"),
                "type": _qual_type(node),
                "location": source_location(node, root),
            },
        )
        qual_type = _qual_type(node) or ""
        current_metadata = {
            "template_pattern": (
                parent_kind == "FunctionTemplateDecl" and not node.get("mangledName")
            ),
            "external": node.get("storageClass") != "static",
            "language_linkage": node.get("languageLinkage", "C++"),
            "variadic": "..." in qual_type,
            "return_type": qual_type.split("(", 1)[0].strip(),
            "visibility": node.get("visibility", "default"),
        }
    kind = str(node.get("kind", ""))
    current_loop = (
        kind
        if kind in {"ForStmt", "WhileStmt", "DoStmt", "CXXForRangeStmt"}
        else enclosing_loop_kind
    )
    loop = enclosing_loop
    if kind in {"ForStmt", "WhileStmt", "DoStmt", "CXXForRangeStmt"}:
        loop_range = source_range(node, root)
        loop_location = source_location(node, root)
        loop = {
            "loop_id": stable_id("reduction_loop", {
                "function": current, "kind": kind,
                "range": loop_range, "location": loop_location,
            }) if current and (loop_range or loop_location) else None,
            "loop_range": loop_range,
        }
    yield (
        node,
        current,
        current_symbol,
        current_metadata,
        parent_kind,
        current_loop,
        current_project_member,
        loop,
    )
    children = node.get("inner", ())
    if isinstance(children, list):
        for child in children:
            if isinstance(child, Mapping):
                yield from _walk_with_function(
                    child,
                    current,
                    current_symbol,
                    current_metadata,
                    translation_unit_id,
                    root,
                    str(node.get("kind", "")) or None,
                    current_loop,
                    current_project_member,
                    loop,
                )


def _node_project_membership(node: Mapping[str, Any], root: Path) -> bool | None:
    """Return explicit source membership, leaving omitted Clang locations inheritable."""
    candidates: list[Mapping[str, Any]] = []
    raw = node.get("loc")
    if isinstance(raw, Mapping):
        expansion = raw.get("expansionLoc")
        spelling = raw.get("spellingLoc")
        if isinstance(expansion, Mapping):
            candidates.append(expansion)
        if isinstance(spelling, Mapping):
            candidates.append(spelling)
        candidates.append(raw)
    raw_range = node.get("range")
    if isinstance(raw_range, Mapping):
        for label in ("begin", "end"):
            point = raw_range.get(label)
            if isinstance(point, Mapping):
                candidates.append(point)
    files = [str(item["file"]) for item in candidates if isinstance(item.get("file"), str)]
    if not files:
        return None
    project_root = root.resolve()
    for value in files:
        if is_virtual_source(value):
            continue
        path = Path(value)
        candidate = (path if path.is_absolute() else project_root / path).resolve()
        try:
            candidate.relative_to(project_root)
            return True
        except ValueError:
            continue
    return False


def _has_unqualified_source_location(node: Mapping[str, Any]) -> bool:
    """Recognize Clang's file-elided main-file locations without claiming included headers."""
    candidates: list[Mapping[str, Any]] = []
    raw = node.get("loc")
    if isinstance(raw, Mapping):
        candidates.append(raw)
    raw_range = node.get("range")
    if isinstance(raw_range, Mapping):
        for label in ("begin", "end"):
            point = raw_range.get(label)
            if isinstance(point, Mapping):
                candidates.append(point)
    if any(isinstance(item.get("includedFrom"), Mapping) for item in candidates):
        return False
    return any(
        isinstance(item.get("offset"), int)
        and isinstance(item.get("line"), int)
        and isinstance(item.get("col"), int)
        for item in candidates
    )


def _has_source_location_shape(node: Mapping[str, Any]) -> bool:
    return isinstance(node.get("loc"), Mapping) or isinstance(node.get("range"), Mapping)


def source_range(node: Mapping[str, Any], root: Path) -> dict[str, Any] | None:
    raw_range = node.get("range")
    if not isinstance(raw_range, Mapping):
        return None
    result: dict[str, Any] = {}
    for label in ("begin", "end"):
        raw = raw_range.get(label)
        if isinstance(raw, Mapping):
            raw = raw.get("expansionLoc") or raw.get("spellingLoc") or raw
        if isinstance(raw, Mapping) and (point := _point(raw, root)):
            result[label] = point
    if "begin" in result and "end" in result and result["begin"]["path"] != result["end"]["path"]:
        return None
    return result or None


def _qual_type(node: Mapping[str, Any]) -> str | None:
    type_node = node.get("type")
    if isinstance(type_node, Mapping):
        value = type_node.get("qualType")
        return str(value) if value is not None else None
    return None


def _is_floating(qual_type: str | None) -> bool:
    return bool(qual_type and any(name in qual_type for name in FLOAT_NAMES))


def _is_floating_scalar(node: Mapping[str, Any]) -> bool:
    return canonical_scalar_type(node)["category"] == "floating"


def _point(raw: Mapping[str, Any], root: Path) -> dict[str, Any] | None:
    file = raw.get("file")
    line = raw.get("line")
    column = raw.get("col")
    if (
        not isinstance(file, str)
        or not isinstance(line, int)
        or not isinstance(column, int)
        or line < 1
        or column < 1
    ):
        return None
    try:
        path = normalize_repo_path(file, root)
    except ValueError:
        return None
    result: dict[str, Any] = {"path": path, "line": line, "column": column}
    if isinstance(raw.get("offset"), int):
        result["offset"] = raw["offset"]
    return result


def source_location(node: Mapping[str, Any], root: Path) -> dict[str, Any] | None:
    raw = node.get("loc")
    if not isinstance(raw, Mapping) or not raw:
        range_node = node.get("range")
        raw = range_node.get("begin") if isinstance(range_node, Mapping) else None
    if not isinstance(raw, Mapping):
        return None
    spelling = raw.get("spellingLoc")
    expansion = raw.get("expansionLoc")
    if isinstance(spelling, Mapping) or isinstance(expansion, Mapping):
        result: dict[str, Any] = {}
        if isinstance(spelling, Mapping) and (point := _point(spelling, root)):
            result["spelling"] = point
        if isinstance(expansion, Mapping) and (point := _point(expansion, root)):
            result["expansion"] = point
            if isinstance(expansion.get("macroName"), str):
                result["macro_name"] = expansion["macroName"]
        return result or None
    return _point(raw, root)


def _children(node: Mapping[str, Any]) -> list[Mapping[str, Any]]:
    value = node.get("inner")
    return [item for item in value if isinstance(item, Mapping)] if isinstance(value, list) else []


def _operand_nodes(node: Mapping[str, Any]) -> list[Mapping[str, Any]]:
    result: list[Mapping[str, Any]] = []
    for child in _children(node):
        current = child
        while str(current.get("kind", "")) in {
            "ParenExpr",
            "ImplicitCastExpr",
            "MaterializeTemporaryExpr",
            "CXXBindTemporaryExpr",
        } and _children(current):
            current = _children(current)[0]
        result.append(current)
    return result


def _literal_value(node: Mapping[str, Any]) -> str | None:
    for item in _walk(node):
        if (
            item.get("kind") in {"IntegerLiteral", "FloatingLiteral"}
            and item.get("value") is not None
        ):
            return str(item["value"])
    return None


def _referenced_declaration(node: Mapping[str, Any]) -> Mapping[str, Any] | None:
    for child in _walk(node):
        referenced = child.get("referencedDecl")
        if isinstance(referenced, Mapping):
            return referenced
    return None


def _call_payload(node: Mapping[str, Any]) -> dict[str, Any]:
    declaration = _referenced_declaration(node) or {}
    callee = str(declaration.get("qualifiedName") or declaration.get("name") or "") or None
    base = callee.removeprefix("std::") if callee else ""
    header = declaration.get("header") or node.get("definitionHeader")
    intrinsic = declaration.get("builtinId") or node.get("intrinsicId")
    return {
        "callee": callee,
        "callee_symbol": declaration.get("mangledName") or callee,
        "resolved": bool(declaration),
        "declaration_id": stable_id("callee", {
            "symbol": declaration.get("mangledName") or callee,
            "type": _qual_type(declaration), "namespace": declaration.get("namespace"),
        }) if declaration else None,
        "namespace": declaration.get("namespace"),
        "definition_header": header,
        "builtin_id": intrinsic,
        "intrinsic_family": node.get("intrinsicFamily"),
        "required_target_features": sorted(
            str(item) for item in node.get("requiredTargetFeatures", [])
        )
        if isinstance(node.get("requiredTargetFeatures"), list)
        else [],
        "active_target_guard": node.get("activeTargetGuard"),
        "provenance_ambiguous": bool(
            isinstance(node.get("loc"), Mapping)
            and (
                isinstance(node["loc"].get("spellingLoc"), Mapping)
                or isinstance(node["loc"].get("expansionLoc"), Mapping)
            )
            and not node.get("activeTargetGuard")
        ),
        "portable_target_implementation": bool(node.get("portableTargetImplementation", False)),
        "template_instantiation": node.get("templateInstantiation"),
        "argument_types": [_qual_type(item) for item in _children(node)[1:]],
        "argument_values": [_literal_value(item) for item in _children(node)[1:]],
        "argument_domain": node.get("argumentDomain", "unknown"),
        "explicit_fma": base in {"fma", "fmaf", "fmal"},
        "math_family": base if base in MATH_CALLS else None,
        "fenv_action": FENV_ACTIONS.get(base),
        "architecture_specific": bool(
            intrinsic
            or (callee and (callee.startswith("_mm") or "x86" in callee.lower()))
            or (isinstance(header, str) and header in {"immintrin.h", "xmmintrin.h", "x86intrin.h"})
        ),
    }


def _classification(
    node: Mapping[str, Any],
    qual_type: str | None,
    function_metadata: Mapping[str, Any] | None,
    parent_kind: str | None,
    enclosing_loop_kind: str | None,
) -> tuple[str | None, dict[str, Any]]:
    kind = str(node.get("kind", ""))
    scalar_type = canonical_scalar_type(node)
    if kind in _FUNCTION_KINDS and function_metadata:
        return_type = str(function_metadata.get("return_type") or "") or None
        scalar_type = from_spellings(return_type)
    if kind in TYPE_KINDS | _FUNCTION_KINDS and scalar_type["category"] == "floating":
        boundary_kind = "local"
        if kind == "ParmVarDecl" and function_metadata and function_metadata.get("external"):
            boundary_kind = (
                "variadic_parameter" if function_metadata.get("variadic") else "parameter"
            )
        elif kind == "FieldDecl":
            boundary_kind = (
                "persistent_field" if node.get("persistent") else "external_record_field"
            )
        elif kind == "VarDecl" and function_metadata is None:
            boundary_kind = "global"
        elif kind in _FUNCTION_KINDS and function_metadata and function_metadata.get("external"):
            boundary_kind = "return"
        return "type", {
            "declaration_kind": kind,
            "name": node.get("name"),
            "is_long_double": scalar_type["canonical"] == "long double",
            "canonical_type": scalar_type,
            "origin": "project-physical",
            "boundary_kind": boundary_kind,
            "linkage": "external"
            if function_metadata and function_metadata.get("external")
            else "internal",
            "visibility": (
                function_metadata.get("visibility", "default")
                if function_metadata
                else node.get("visibility", "default")
            ),
            "language_linkage": (
                function_metadata.get("language_linkage", "C++")
                if function_metadata
                else node.get("languageLinkage")
            ),
            "variadic": bool(function_metadata and function_metadata.get("variadic")),
        }
    if kind == "FloatingLiteral":
        return "literal", {"value": node.get("value")}
    if (
        kind == "UnaryOperator"
        and node.get("opcode") in {"+", "-"}
        and scalar_type["category"] == "floating"
    ):
        operands = _operand_nodes(node)
        return "arithmetic", {
            "opcode": f"unary{node['opcode']}",
            "fma_candidate": False,
            "explicit_fma": False,
            "operation_chain_length": 1,
            "storage_rounding_boundary": False,
            "operand_types": [_qual_type(item) for item in operands],
            "parent_use": parent_kind,
        }
    if kind == "BinaryOperator":
        opcode = node.get("opcode")
        operands = _operand_nodes(node)
        operand_types = [_qual_type(item) for item in operands]
        floating_operation = _is_floating(qual_type) or any(
            _is_floating(item) for item in operand_types
        )
        if opcode in ARITHMETIC_OPS and floating_operation:
            arithmetic_children = [
                child
                for child in _walk(node)
                if child is not node
                and child.get("kind") == "BinaryOperator"
                and child.get("opcode") in ARITHMETIC_OPS
            ]
            fma_candidate = opcode in {"+", "-"} and any(
                child.get("opcode") == "*" for child in arithmetic_children
            )
            return "arithmetic", {
                "opcode": opcode,
                "fma_candidate": fma_candidate,
                "explicit_fma": False,
                "operation_chain_length": 1 + len(arithmetic_children),
                "storage_rounding_boundary": bool(node.get("storageBoundary", False)),
                "operand_types": operand_types,
                "parent_use": parent_kind,
            }
        # Classify the operands after implicit conversions, not their pointee types.
        if opcode in COMPARISON_OPS and any(_is_floating_scalar(item) for item in _children(node)):
            values = [_literal_value(item) for item in operands]
            callee_names = [_callee_name(item) for item in operands]
            return "comparison", {
                "opcode": opcode,
                "operand_types": operand_types,
                "operand_values": values,
                "operand_origins": [str(item.get("kind", "unknown")) for item in operands],
                "use_context": parent_kind or "expression",
                "zero_comparison": any(value in {"0", "0.0", "0x0p+0"} for value in values),
                "nan_comparison": any(
                    (value and "nan" in value.lower())
                    or (callee and callee.removeprefix("std::") in {"nan", "nanf", "nanl"})
                    for value, callee in zip(values, callee_names, strict=False)
                ),
                "epsilon_pattern": bool(node.get("epsilonPattern", False)),
                "nan_guard": bool(node.get("nanGuard", False)),
                "same_origin": bool(node.get("sameOrigin", False)),
            }
    if (
        kind == "CompoundAssignOperator"
        and _is_floating(qual_type)
        and (enclosing_loop_kind is not None or node.get("iterationCount") is not None)
    ):
        opcode = str(node.get("opcode", ""))
        if opcode in {"+=", "*=", "-= "} or opcode.strip() in {"+=", "*=", "-="}:
            return "reduction", {
                "operation": opcode.strip(),
                "reduction_kind": node.get("reductionKind", "sum" if "+" in opcode else "product"),
                "accumulator": node.get("accumulator"),
                "iteration_count": node.get("iterationCount"),
                "parallel": bool(node.get("parallel", False)),
                "vectorized": bool(node.get("vectorized", False)),
                "compensation": node.get("compensation"),
                "fixed_order": bool(node.get("fixedOrder", False)),
                "ambiguous": bool(node.get("ambiguous", False)),
                "loop_kind": enclosing_loop_kind,
            }
    if kind in CAST_KINDS and node.get("castKind") in NUMERIC_CAST_KINDS:
        children = _children(node)
        source_node = children[0] if children else {}
        source_type_info = (
            canonical_scalar_type(source_node)
            if children
            else from_spellings(
                str(node.get("sourceType")) if node.get("sourceType") is not None else None
            )
        )
        destination_type_info = canonical_scalar_type(node)
        source_type = source_type_info["canonical"]
        value = _literal_value(children[0]) if children else None
        destination_is_float = destination_type_info["category"] == "floating"
        source_is_numeric = source_type_info["category"] in {"floating", "integer"}
        endpoint_unknown = (
            destination_type_info["category"] == "unknown"
            or source_type_info["category"] == "unknown"
        )
        if not ((destination_is_float and source_is_numeric) or endpoint_unknown):
            return None, {}
        return "conversion", {
            "cast_kind": node.get("castKind"),
            "syntax_kind": kind,
            "implicit": kind == "ImplicitCastExpr",
            "source_type": source_type,
            "destination_type": destination_type_info["canonical"],
            "source_scalar_type": source_type_info,
            "destination_scalar_type": destination_type_info,
            "endpoint_status": "unknown" if endpoint_unknown else "known",
            "constant_value": value,
            "integer_width": node.get("integerWidth"),
            "integer_signed": node.get("integerSigned"),
            "use_context": parent_kind or "expression",
            "storage_rounding_boundary": kind != "ImplicitCastExpr",
        }
    if kind in {"CallExpr", "CXXMemberCallExpr"}:
        payload = _call_payload(node)
        if (
            _is_floating(qual_type)
            or payload["fenv_action"]
            or payload["architecture_specific"]
            or payload["resolved"]
        ):
            fact_kind = (
                "environment"
                if payload["fenv_action"]
                else ("api" if payload["architecture_specific"] else "call")
            )
            return fact_kind, payload
    if kind in {"PragmaSTDCFenvAccess", "FEnvAccessAttr"}:
        return "environment", {
            "fenv_action": "pragma_access",
            "enabled": bool(node.get("enabled", True)),
            "scope": node.get("scope", parent_kind or "translation-unit"),
        }
    return None, {}


def _callee_name(node: Mapping[str, Any]) -> str | None:
    declaration = _referenced_declaration(node)
    if declaration is None:
        return None
    name = declaration.get("qualifiedName") or declaration.get("name")
    return str(name) if isinstance(name, str) else None


def map_ast(
    context: CompileContext,
    ast: Mapping[str, Any],
    project_root: Path,
    source_profile: str,
    target_profile: str,
    *,
    max_function_summary_depth: int = 3,
) -> dict[str, Any]:
    SourceLocations(project_root, context.directory, context.absolute_file).normalize(ast)
    from .ast_relations import relations
    walked = list(_walk_with_function(
        ast, None, None, None, context.translation_unit_id, project_root,
    ))
    node_relations = relations(ast, project_root, context.context_id,
                               {id(item[0]) for item in walked if item[6]})
    # Clang 的声明指针仅用于本次查找，不能进入稳定身份。
    declarations = {
        str(node["id"]): stable_id("reduction_accumulator", {
            "kind": node.get("kind"), "name": node.get("name"),
            "type": _qual_type(node), "location": source_location(node, project_root),
        })
        for node in _walk(ast)
        if node.get("kind") in {"VarDecl", "ParmVarDecl"}
        and node.get("id") and source_location(node, project_root)
    }
    facts: list[dict[str, Any]] = []
    unknowns: list[dict[str, Any]] = []
    for (
        node,
        function_id,
        function_symbol,
        function_metadata,
        parent_kind,
        enclosing_loop_kind,
        project_member,
        enclosing_loop,
    ) in walked:
        if project_member is None:
            if _has_source_location_shape(node):
                unknowns.append(
                    {
                        "kind": str(node.get("kind", "unknown")),
                        "reason": "source_origin_unknown",
                        "location": None,
                        "blocking": False,
                        "affects_fact_kinds": [],
                        "affects_rules": [],
                        "candidate_id": stable_id(
                            "source_origin_unknown",
                            {"translation_unit_id": context.translation_unit_id,
                             "kind": node.get("kind")},
                        ),
                    }
                )
            continue
        if not project_member:
            continue
        if function_metadata and function_metadata.get("template_pattern"):
            continue
        qual_type = _qual_type(node)
        fact_kind, payload = _classification(
            node,
            qual_type,
            function_metadata,
            parent_kind,
            enclosing_loop_kind,
        )
        location = source_location(node, project_root)
        if fact_kind == "reduction":
            payload.update(enclosing_loop or {"loop_id": None, "loop_range": None})
            children = _children(node)
            lhs = children[0] if children else {}
            while lhs.get("kind") in {"ParenExpr", "ImplicitCastExpr"} and _children(lhs):
                lhs = _children(lhs)[0]
            declaration = lhs.get("referencedDecl", {}) if lhs.get("kind") == "DeclRefExpr" else {}
            payload["accumulator_id"] = declarations.get(str(declaration.get("id")))
        if fact_kind:
            payload.update(node_relations.get(id(node), {}))
            identity = {
                "translation_unit_id": context.translation_unit_id,
                "fact_kind": fact_kind,
                "type": qual_type,
                "location": location,
                "source_range": source_range(node, project_root),
                "function_id": function_id,
                "function_symbol": function_symbol,
                "payload": payload,
            }
            facts.append(
                {
                    "fact_id": stable_id("fact", identity),
                    "fact_kind": fact_kind,
                    "type": qual_type,
                    "location": location,
                    "source_range": source_range(node, project_root),
                    "function_id": function_id,
                    "function_symbol": function_symbol,
                    "payload": payload,
                }
            )
        elif (
            canonical_scalar_type(node)["category"] == "floating"
            and str(node.get("kind", "")) not in STRUCTURAL_FLOAT_KINDS
            and not (
                node.get("kind") == "BinaryOperator"
                and node.get("opcode") in TRANSPARENT_BINARY_OPS
            )
            and not (
                node.get("kind") == "UnaryOperator"
                and node.get("opcode") in TRANSPARENT_UNARY_OPS
            )
        ):
            unknowns.append(
                {
                    "kind": str(node.get("kind", "unknown")),
                    "reason": "unsupported_floating_ast_node",
                    "location": location,
                    "blocking": True,
                    "affects_fact_kinds": ["type"],
                    "affects_rules": ["long-double-abi"],
                    "candidate_id": stable_id(
                        "unsupported_candidate",
                        {"kind": node.get("kind"), "location": location, "type": qual_type},
                    ),
                }
            )
    by_function: dict[tuple[str, str | None], list[dict[str, Any]]] = defaultdict(list)
    for fact in facts:
        function_id = fact.get("function_id")
        if function_id:
            by_function[(str(function_id), fact.get("function_symbol"))].append(fact)
    summary_payloads: dict[tuple[str, str | None], dict[str, Any]] = {}
    symbol_to_key = {symbol: key for key in by_function if (symbol := key[1]) is not None}
    for key, members in sorted(by_function.items()):
        actions = sorted(
            {
                str(item.get("payload", {}).get("fenv_action"))
                for item in members
                if item.get("payload", {}).get("fenv_action")
            }
        )
        unresolved_calls = any(
            item.get("fact_kind") == "call"
            and (
                not bool(item.get("payload", {}).get("resolved"))
                or str(item.get("payload", {}).get("callee_symbol", "")) not in symbol_to_key
            )
            for item in members
        )
        summary_payloads[key] = {
            "actions": actions,
            "reads_environment": "reads_environment" in actions,
            "changes_rounding": "changes_rounding" in actions,
            "tests_exceptions": "tests_exceptions" in actions,
            "restores_environment": "restores_environment" in actions,
            "unknown_effect": unresolved_calls,
            "propagation_depth": 0,
        }
    last_changed_keys: set[tuple[str, str | None]] = set()
    if max_function_summary_depth == 0:
        for key, members in sorted(by_function.items()):
            if any(
                (callee := _payload_mapping(call).get("callee_symbol"))
                and symbol_to_key.get(str(callee)) not in {None, key}
                for call in members
                if call.get("fact_kind") == "call"
            ):
                summary_payloads[key]["unknown_effect"] = True
                summary_payloads[key]["frontier"] = "max_function_summary_depth"
    for depth in range(1, max_function_summary_depth + 1):
        changed = False
        last_changed_keys = set()
        for key, members in sorted(by_function.items()):
            payload = summary_payloads[key]
            action_set = set(payload["actions"])
            unknown_effect = bool(payload["unknown_effect"])
            for call in (item for item in members if item.get("fact_kind") == "call"):
                callee = _payload_mapping(call).get("callee_symbol")
                callee_key = symbol_to_key.get(str(callee)) if callee else None
                if callee_key is None:
                    continue
                if callee_key == key:
                    unknown_effect = True
                    continue
                callee_payload = summary_payloads[callee_key]
                action_set.update(str(item) for item in callee_payload["actions"])
                unknown_effect = unknown_effect or bool(callee_payload["unknown_effect"])
            ordered = sorted(action_set)
            if ordered != payload["actions"] or unknown_effect != payload["unknown_effect"]:
                payload.update(
                    {
                        "actions": ordered,
                        "reads_environment": "reads_environment" in action_set,
                        "changes_rounding": "changes_rounding" in action_set,
                        "tests_exceptions": "tests_exceptions" in action_set,
                        "restores_environment": "restores_environment" in action_set,
                        "unknown_effect": unknown_effect,
                        "propagation_depth": depth,
                    }
                )
                changed = True
                last_changed_keys.add(key)
        if not changed:
            break
    else:
        for key in last_changed_keys:
            summary_payloads[key]["unknown_effect"] = True
            summary_payloads[key]["frontier"] = "max_function_summary_depth"
    for (function_id, function_symbol), members in sorted(by_function.items()):
        payload = summary_payloads[(function_id, function_symbol)]
        if not payload["actions"] and not payload["unknown_effect"]:
            continue
        identity = {
            "translation_unit_id": context.translation_unit_id,
            "fact_kind": "function_summary",
            "function_id": function_id,
            "payload": payload,
        }
        facts.append(
            {
                "fact_id": stable_id("fact", identity),
                "fact_kind": "function_summary",
                "type": None,
                "location": _primary_fact_location(members),
                "source_range": None,
                "function_id": function_id,
                "function_symbol": function_symbol,
                "payload": payload,
            }
        )
    facts.extend(_source_suppression_facts(context, project_root))
    facts.sort(
        key=lambda item: (_location_key(item["location"]), item["fact_kind"], item["fact_id"])
    )
    unknowns.sort(key=lambda item: (_location_key(item.get("location")), item["kind"]))
    body: dict[str, Any] = {
        "kind": "FloatingPointFactBundle",
        "schema_version": FACTS_SCHEMA_VERSION,
        "producer": {
            "tool_version": TOOL_VERSION,
            "clang_version": CLANG_VERSION,
            "mapper_version": FACT_MAPPER_VERSION,
        },
        "translation_unit_id": context.translation_unit_id,
        "physical_tu_id": context.physical_tu_id,
        "context_id": context.context_id,
        "compile_context": context.public_dict(),
        "effective_fp_options": context.public_dict()["effective_fp_options"],
        "profiles": {"source": source_profile, "target": target_profile},
        "facts": facts,
        "unknowns": unknowns,
        "diagnostics": [diagnostic(
            "source_location_unresolved", "source", "部分源码事实无法安全恢复位置。",
            "Some source fact locations could not be safely resolved.",
            severity="warning", translation_unit_id=context.translation_unit_id,
        ).to_dict()] if any(item.get("location") is None for item in facts) else [],
    }
    body["semantic_digest"] = semantic_digest(body)
    return body


def _primary_fact_location(facts: list[dict[str, Any]]) -> dict[str, Any] | None:
    locations = [item["location"] for item in facts if isinstance(item.get("location"), Mapping)]
    return dict(sorted(locations, key=_location_key)[0]) if locations else None


def _payload_mapping(fact: Mapping[str, Any]) -> Mapping[str, Any]:
    value = fact.get("payload")
    return value if isinstance(value, Mapping) else {}


def _source_suppression_facts(context: CompileContext, project_root: Path) -> list[dict[str, Any]]:
    try:
        lines = context.absolute_file.read_text(encoding="utf-8").splitlines()
        relative = normalize_repo_path(context.absolute_file, project_root)
    except (OSError, UnicodeError, ValueError):
        return []
    result: list[dict[str, Any]] = []
    for index, line in enumerate(lines, start=1):
        match = _SUPPRESSION_RE.search(line)
        if match is None:
            continue
        target_line = next(
            (
                candidate
                for candidate in range(index + 1, len(lines) + 1)
                if lines[candidate - 1].strip()
            ),
            None,
        )
        payload = {
            "code": match.group(1),
            "reason": match.group(2),
            "target_line": target_line,
            "ambiguous": target_line is None,
        }
        identity = {
            "translation_unit_id": context.translation_unit_id,
            "fact_kind": "suppression",
            "location": {"path": relative, "line": index, "column": match.start() + 1},
            "payload": payload,
        }
        result.append(
            {
                "fact_id": stable_id("fact", identity),
                "fact_kind": "suppression",
                "type": None,
                "location": identity["location"],
                "source_range": None,
                "function_id": None,
                "function_symbol": None,
                "payload": payload,
            }
        )
    return result


def _location_key(location: Any) -> tuple[str, int, int]:
    if not isinstance(location, Mapping):
        return ("", 0, 0)
    point = location.get("expansion") or location.get("spelling") or location
    if not isinstance(point, Mapping):
        return ("", 0, 0)
    return (str(point.get("path", "")), int(point.get("line", 0)), int(point.get("column", 0)))


def blocking_unknown_diagnostic(bundle: Mapping[str, Any]) -> dict[str, Any] | None:
    unknowns = bundle.get("unknowns", ())
    if not any(
        isinstance(item, Mapping) and item.get("blocking")
        for item in unknowns
        if isinstance(unknowns, list)
    ):
        return None
    return diagnostic(
        "unsupported_floating_fact",
        "canonical_facts",
        "存在无法规范化的浮点 AST 节点。",
        "A floating-point AST node could not be canonicalized.",
        severity="warning",
        translation_unit_id=str(bundle.get("translation_unit_id", "")),
    ).to_dict()
