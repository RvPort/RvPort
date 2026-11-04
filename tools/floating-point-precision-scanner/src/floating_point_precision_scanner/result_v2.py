"""将扫描器内部模型投影为唯一公开的结果协议 2.0。"""

from __future__ import annotations

import re
from collections.abc import Mapping, Sequence
from pathlib import PurePosixPath
from typing import Any

from . import (
    CACHE_SCHEMA_VERSION,
    LLVM_EXTRACTOR_VERSION,
    LLVM_READER_VERSION,
    LLVM_VERSION,
    RESULT_SCHEMA_VERSION,
    TOOL_VERSION,
)
from .canonical import canonical_json, semantic_digest, stable_id
from .diagnostics import Diagnostic, aggregate_diagnostics
from .output import serialize_json
from .rules import RULE_BY_CODE, RULE_REGISTRY_DIGEST, RuleRun
from .step_descriptions import StepDescriptions

EXTENSION_ANALYSIS = "org.rvport.floating-point-scanner.analysis"
EXTENSION_PROFILES = "org.rvport.floating-point-scanner.profiles"
EXTENSION_ARTIFACTS = "org.rvport.floating-point-scanner.artifacts"
EXTENSION_SUPPRESSIONS = "org.rvport.floating-point-scanner.suppressions"
EXTENSION_RUNTIME = "org.rvport.floating-point-scanner.runtime"
REGISTERED_EXTENSIONS = frozenset(
    {
        EXTENSION_ANALYSIS,
        EXTENSION_PROFILES,
        EXTENSION_ARTIFACTS,
        EXTENSION_SUPPRESSIONS,
        EXTENSION_RUNTIME,
    }
)

_REFERENCE_KINDS = {
    "source_fact": "sourceFact",
    "ir_fact": "irFact",
    "binding": "binding",
    "profile": "profile",
    "build_context": "buildContext",
    "compile_option": "compileOption",
    "assessment": "assessment",
}
_PRODUCERS = {
    "sourceFact": "clang-ast-fact-mapper",
    "irFact": "llvm-floating-point-fact-extractor",
    "binding": "ast-ir-evidence-builder",
    "profile": "profile-registry",
    "buildContext": "build-context-registry",
    "compileOption": "compile-context-normalizer",
    "assessment": "floating-point-rule-engine",
}
_STEP_KINDS = {
    "sourceFact": "sourceObservation",
    "irFact": "irObservation",
    "binding": "binding",
    "profile": "profile",
    "buildContext": "buildContext",
    "compileOption": "compileOption",
    "assessment": "assessment",
}
_REFERENCE_ORDER = {
    "sourceFact": 0,
    "binding": 1,
    "irFact": 2,
    "profile": 3,
    "buildContext": 4,
    "compileOption": 5,
    "assessment": 6,
}


def _reference_key(value: Mapping[str, Any]) -> tuple[int, str]:
    return (_REFERENCE_ORDER.get(str(value["kind"]), 99), str(value["id"]))


def _camel_key(value: str) -> str:
    head, *tail = value.split("_")
    return head + "".join(part[:1].upper() + part[1:] for part in tail)


def _camelize(value: Any) -> Any:
    if isinstance(value, Mapping):
        return {_camel_key(str(key)): _camelize(item) for key, item in value.items()}
    if isinstance(value, list | tuple):
        return [_camelize(item) for item in value]
    return value


def _camel_validation_plan(value: Mapping[str, Any] | None) -> dict[str, Any]:
    source = value or {}
    return {
        "inputClasses": sorted(str(item) for item in source.get("input_classes", [])),
        "errorMetrics": sorted(str(item) for item in source.get("error_metrics", [])),
        "thresholdSource": str(source.get("threshold_source", "unknown")),
        "repeatStrategy": str(source.get("repeat_strategy", "unspecified")),
    }


def _safe_location(value: Any) -> dict[str, Any] | None:
    if not isinstance(value, Mapping):
        return None
    point: Mapping[str, Any] = value
    for key in ("expansion", "spelling"):
        candidate = value.get(key)
        if isinstance(candidate, Mapping):
            point = candidate
            break
    raw_path = str(point.get("path", point.get("file", ""))).replace("\\", "/")
    if not raw_path:
        return None
    path = PurePosixPath(raw_path)
    if path.is_absolute() or re.match(r"^[A-Za-z]:", raw_path) or ".." in path.parts:
        raw_path = "<external_path>"
    result: dict[str, Any] = {
        "file": raw_path,
        "line": max(1, int(point.get("line", 1))),
        "column": max(1, int(point.get("column", 1))),
    }
    if isinstance(value.get("spelling"), Mapping):
        result["origin"] = "macroExpansion"
    return result


def _typed_reference(value: Mapping[str, Any]) -> dict[str, Any]:
    kind = _REFERENCE_KINDS.get(str(value.get("kind")), str(value.get("kind")))
    reference = str(value.get("ref", value.get("id", "unknown")))
    return {
        "kind": kind,
        "id": reference,
        "producer": _PRODUCERS.get(kind, "floating-point-precision-scanner"),
        "schemaVersion": "1.0",
        "digest": reference if re.fullmatch(r"[a-f0-9]{64}", reference) else semantic_digest(value),
    }


def _assessment(value: Mapping[str, Any]) -> dict[str, Any]:
    evidence = value.get("evidence", [])
    return {
        "id": str(value.get("assessment_id", value.get("id", "unknown"))),
        "ruleCode": str(value.get("rule_code", value.get("ruleCode", "unknown"))),
        "ruleVersion": str(value.get("rule_version", value.get("ruleVersion", "unknown"))),
        "scope": str(value.get("scope", "unknown")),
        "status": (
            "notMatched"
            if value.get("status") == "not_matched"
            else str(value.get("status", "unknown"))
        ),
        "reasonCode": str(value.get("reason_code", value.get("reasonCode", "unknown"))),
        "evidence": sorted(
            (_typed_reference(item) for item in evidence if isinstance(item, Mapping)),
            key=_reference_key,
        ),
        "frontier": value.get("frontier"),
    }


def _occurrences(finding: Mapping[str, Any]) -> list[dict[str, Any]]:
    locations = [finding.get("location"), *list(finding.get("related_locations", []))]
    normalized: list[dict[str, Any] | None] = []
    seen: set[str] = set()
    for raw in locations:
        location = _safe_location(raw)
        key = canonical_json(location)
        if key not in seen:
            seen.add(key)
            normalized.append(location)
    finding_id = str(finding["finding_id"])
    provenance = sorted(
        (
            _typed_reference(item)
            for item in finding.get("evidence", [])
            if isinstance(item, Mapping)
        ),
        key=_reference_key,
    )
    values = [
        {
            "id": stable_id(
                "occurrence", {"findingId": finding_id, "location": location, "index": index}
            ),
            "kind": "primary" if index == 0 else "related",
            "location": location,
            "configuration": "default",
            "status": "confirmed",
            "reasonCodes": [],
            "provenance": provenance,
        }
        for index, location in enumerate(normalized)
    ]
    members = finding.get("semantic", {}).get("members", [])
    for occurrence in values:
        local = [
            ref
            for member in members
            if _safe_location(member.get("location")) == occurrence["location"]
            for ref in member.get("evidence", [])
        ]
        if local:
            occurrence["provenance"] = sorted(
                {
                    _reference_key(ref): ref for raw in local if (ref := _typed_reference(raw))
                }.values(),
                key=_reference_key,
            )

    def source_order(item: Mapping[str, Any]) -> tuple[Any, ...]:
        location = item.get("location") or {}
        point = location.get("expansion") or location.get("spelling") or location
        return (
            item["kind"] != "primary",
            point.get("file", point.get("path", "")),
            point.get("line", 0),
            point.get("column", 0),
            item["id"],
        )

    return sorted(values, key=source_order)


def _evidence_chain(finding: Mapping[str, Any], descriptions: StepDescriptions) -> dict[str, Any]:
    finding_id = str(finding["finding_id"])
    references = sorted(
        (
            _typed_reference(item)
            for item in finding.get("evidence", [])
            if isinstance(item, Mapping)
        ),
        key=_reference_key,
    )
    steps: list[dict[str, Any]] = []
    for reference in references:
        steps.append(
            {
                "id": stable_id("evidence-step", {"findingId": finding_id, "reference": reference}),
                "kind": _STEP_KINDS.get(reference["kind"], "observation"),
                "status": "complete",
                "reasonCodes": [],
                "references": [reference],
                **descriptions.describe(reference["kind"], reference["id"]),
            }
        )
    assessment_reference = _typed_reference(
        {"kind": "assessment", "ref": str(finding.get("assessment_id", "unknown"))}
    )
    assessment_step = {
        "id": stable_id(
            "evidence-step", {"findingId": finding_id, "assessment": assessment_reference["id"]}
        ),
        "kind": "assessment",
        "status": "complete",
        "reasonCodes": [],
        "references": [assessment_reference],
        **descriptions.describe("assessment", assessment_reference["id"]),
    }
    conclusion_step = {
        "id": stable_id("evidence-step", {"findingId": finding_id, "conclusion": finding_id}),
        "kind": "conclusion",
        "status": "complete",
        "reasonCodes": [],
        "references": [],
        **descriptions.conclusion(finding),
    }
    steps.extend((assessment_step, conclusion_step))
    edges: list[dict[str, Any]] = []
    path_steps = [*steps[:-2], assessment_step, conclusion_step]
    for index, step in enumerate(path_steps[:-1]):
        target = path_steps[index + 1]
        edge_kind = "assessedAs" if target is conclusion_step else "supports"
        edges.append(
            {
                "id": stable_id(
                    "evidence-edge",
                    {
                        "findingId": finding_id,
                        "from": step["id"],
                        "to": target["id"],
                        "kind": edge_kind,
                    },
                ),
                "kind": edge_kind,
                "fromStepId": step["id"],
                "toStepId": target["id"],
            }
        )
    primary = [step["id"] for step in path_steps]
    breaks = sorted({code for step in steps for code in step["reasonCodes"]})
    return {
        "status": "partial" if breaks else "complete",
        "rootStepId": primary[0],
        "steps": sorted(steps, key=lambda item: item["id"]),
        "edges": sorted(edges, key=lambda item: item["id"]),
        "primaryPath": primary,
        "breaks": [{"reasonCode": code} for code in breaks],
        "impactStepIds": [conclusion_step["id"]],
        "budget": {
            "maxDepth": len(primary),
            "maxVisited": len(steps),
            "maxPaths": 1,
            "visited": len(steps),
            "paths": 1,
            "truncated": False,
        },
    }


def _finding(
    value: Mapping[str, Any], descriptions: StepDescriptions | None = None
) -> dict[str, Any]:
    finding_id = str(value["finding_id"])
    occurrences = _occurrences(value)
    references = sorted(
        (_typed_reference(item) for item in value.get("evidence", []) if isinstance(item, Mapping)),
        key=_reference_key,
    )
    conclusion = {
        "findingCode": str(value["finding_code"]),
        "ruleVersion": str(value["rule_version"]),
        "assessmentId": str(value["assessment_id"]),
        "category": str(value["category"]),
        "severity": str(value["severity"]),
        "confidence": str(value["confidence"]),
        "evidenceOrigin": str(value["evidence_origin"]),
        "requiresHumanConfirmation": bool(value["requires_human_confirmation"]),
        "status": (
            "partial" if value.get("semantic", {}).get("location_budget_exceeded") else "complete"
        ),
        "rationale": str(value["finding_code"]),
        "title": str(value["title"]),
        "titleEn": str(value["title_en"]),
        "message": str(value["message"]),
        "messageEn": str(value["message_en"]),
        "impact": str(value["impact"]),
        "impactEn": str(value["impact_en"]),
        "suggestion": str(value["suggestion"]),
        "suggestionEn": str(value["suggestion_en"]),
        "testSuggestion": str(value["test_suggestion"]),
        "testSuggestionEn": str(value["test_suggestion_en"]),
        "validationPlan": _camel_validation_plan(value.get("validation_plan")),
        "affectedCount": int(value.get("affected_count", 1)),
        "aggregationKey": value.get("aggregation_key"),
        "sourceProfileFingerprint": value.get("source_profile_fingerprint"),
        "targetProfileFingerprint": value.get("target_profile_fingerprint"),
        "sourceBuildContextFingerprint": value.get("source_build_context_fingerprint"),
        "targetBuildContextFingerprint": value.get("target_build_context_fingerprint"),
        "semantic": _camelize(
            {key: item for key, item in value.get("semantic", {}).items() if key != "members"}
        ),
    }
    return {
        "id": finding_id,
        "fact": {
            "id": stable_id("finding-fact", {"findingId": finding_id}),
            "kind": str(value["finding_code"]),
            "matchedText": str(value["finding_code"]),
            "primaryLocation": occurrences[0]["location"],
            "provenance": references,
        },
        "conclusion": conclusion,
        "occurrences": occurrences,
        "primaryOccurrenceId": occurrences[0]["id"],
        "evidenceChain": _evidence_chain(value, descriptions or StepDescriptions()),
    }


def _diagnostic(value: Diagnostic) -> dict[str, Any]:
    location = _safe_location(value.location)
    identity = {
        "code": value.diagnostic_code,
        "stage": value.stage,
        "location": location,
        "translationUnitId": value.translation_unit_id,
    }
    return {
        "id": stable_id("diagnostic", identity),
        "code": value.diagnostic_code,
        "stage": value.stage,
        "severity": value.severity,
        "message": value.message,
        "messageEn": value.message_en,
        "suggestion": value.suggestion,
        "suggestionEn": value.suggestion_en,
        "retryable": value.retryable,
        "count": value.count,
        "location": location,
        "translationUnitId": value.translation_unit_id,
        "findingIds": [],
    }


def _suppression(value: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "id": str(value.get("suppression_id", value.get("id", "unknown"))),
        "findingId": value.get("finding_id"),
        "findingCode": str(value.get("finding_code", value.get("code", "unknown"))),
        "path": str(value.get("path", "")),
        "startLine": value.get("start_line"),
        "endLine": value.get("end_line"),
        "reason": str(value.get("reason", "未提供原因。")),
        "reasonEn": str(value.get("reason_en", "No reason was provided.")),
        "source": str(value.get("source", "request")),
        "status": (
            "budgetUnrecorded"
            if value.get("status") == "budget_unrecorded"
            else str(value.get("status", "unmatched"))
        ),
        "expiresOn": value.get("expires_on"),
    }


def _catalog(
    findings: Sequence[Mapping[str, Any]], assessments: Sequence[Mapping[str, Any]]
) -> list[dict[str, Any]]:
    values: dict[tuple[str, str], dict[str, Any]] = {}
    for assessment in assessments:
        reference = _typed_reference({"kind": "assessment", "ref": assessment["id"]})
        values[(reference["kind"], reference["id"])] = reference
        for item in assessment.get("evidence", []):
            if isinstance(item, Mapping):
                values[(str(item["kind"]), str(item["id"]))] = dict(item)
    for finding in findings:
        for item in finding["fact"]["provenance"]:
            values[(str(item["kind"]), str(item["id"]))] = dict(item)
    return sorted(values.values(), key=lambda item: (item["kind"], item["id"]))


def _semantic_projection(document: Mapping[str, Any]) -> dict[str, Any]:
    findings = []
    for finding in document["findings"]:
        conclusion = finding["conclusion"]
        findings.append(
            {
                "id": finding["id"],
                "fact": finding["fact"],
                "conclusion": {
                    key: value
                    for key, value in conclusion.items()
                    if key
                    not in {
                        "title",
                        "titleEn",
                        "message",
                        "messageEn",
                        "impact",
                        "impactEn",
                        "suggestion",
                        "suggestionEn",
                        "testSuggestion",
                        "testSuggestionEn",
                    }
                },
                "occurrences": finding["occurrences"],
                "primaryOccurrenceId": finding["primaryOccurrenceId"],
                "evidenceChain": finding["evidenceChain"],
            }
        )
    extensions = dict(document["extensions"])
    runtime = dict(extensions[EXTENSION_RUNTIME])
    runtime["cache"] = {
        "schemaVersion": runtime["cache"]["schemaVersion"],
        "mode": "normalized",
    }
    extensions[EXTENSION_RUNTIME] = runtime
    suppression_extension = dict(extensions[EXTENSION_SUPPRESSIONS])
    suppression_extension["records"] = [
        {key: value for key, value in item.items() if key not in {"reason", "reasonEn"}}
        for item in suppression_extension["records"]
    ]
    extensions[EXTENSION_SUPPRESSIONS] = suppression_extension
    diagnostics = [
        {
            "id": item["id"],
            "code": item["code"],
            "stage": item["stage"],
            "severity": item["severity"],
            "count": item["count"],
            "findingIds": item["findingIds"],
        }
        for item in document["diagnostics"]
    ]
    return {
        "scan": {key: value for key, value in document["scan"].items() if key != "semanticDigest"},
        "project": document["project"],
        "summary": document["summary"],
        "findings": findings,
        "diagnostics": diagnostics,
        "extensions": extensions,
    }


def _configuration_members(rule_run: RuleRun | None) -> list[dict[str, Any]]:
    if rule_run is None:
        return []
    visible = {
        str(member["finding_id"]): str(finding["finding_id"])
        for finding in rule_run.findings
        for member in finding.get("semantic", {}).get("members", [finding])
    }
    return [
        {
            "candidateFindingId": str(member["finding_id"]),
            "assessmentId": str(member["assessment_id"]),
            "aggregateFindingId": visible.get(str(member["finding_id"])),
            "status": "visible" if str(member["finding_id"]) in visible else "suppressed",
            "location": _safe_location(member.get("location")),
            "evidence": sorted(
                (_typed_reference(ref) for ref in member.get("evidence", [])), key=_reference_key
            ),
        }
        for member in sorted(
            rule_run.candidate_findings or rule_run.findings,
            key=lambda item: str(item["finding_id"]),
        )
        if member["finding_code"] == "unsafe-fp-optimization"
    ]


def project_result_v2(
    request_id: str,
    status: str,
    planned: int,
    completed: int,
    failed: int,
    unknown: int,
    facts: list[dict[str, Any]],
    diagnostics: list[Diagnostic],
    source_profile: Mapping[str, Any],
    target_profile: Mapping[str, Any],
    *,
    ir_facts: list[dict[str, Any]] | None = None,
    evidence: list[dict[str, Any]] | None = None,
    artifact_plan: Any | None = None,
    layers: Mapping[str, Any] | None = None,
    source_mapping_mode: str = "not_requested",
    rule_run: RuleRun | None = None,
    enabled_rules: tuple[str, ...] = (),
    source_build_context: Mapping[str, Any] | None = None,
    target_build_context: Mapping[str, Any] | None = None,
    suppression_records: tuple[Mapping[str, Any], ...] = (),
    request_schema_version: str = "1.4",
    source_revision: str | None = None,
    policy_as_of: str | None = None,
    budget_snapshot: Mapping[str, Any] | None = None,
    cache_mode: str = "off",
    cache_stats: Mapping[str, int] | None = None,
    diagnostic_limit: int = 1000,
    description_context: Sequence[Mapping[str, Any]] = (),
) -> dict[str, Any]:
    ordered_diagnostics = aggregate_diagnostics(diagnostics)
    if len(ordered_diagnostics) > diagnostic_limit:
        dropped = sum(item.count for item in ordered_diagnostics[diagnostic_limit - 1 :])
        ordered_diagnostics = ordered_diagnostics[: max(0, diagnostic_limit - 1)]
        ordered_diagnostics.append(
            Diagnostic(
                "diagnostic_budget_exceeded",
                "budget",
                "warning",
                "诊断预算耗尽，重复根因已聚合。",
                "The diagnostic budget was exhausted; repeated causes were aggregated.",
                "请检查代表性诊断和未分析分母。",
                "Inspect representative diagnostics and unanalyzed denominators.",
                count=max(1, dropped),
            )
        )
    assessment_counts = (
        dict(rule_run.counts)
        if rule_run is not None
        else {
            "planned": 0,
            "completed": 0,
            "matched": 0,
            "not_matched": 0,
            "unknown": 0,
            "skipped": 0,
            "failed": 0,
        }
    )
    assessments = sorted(
        (
            _assessment(item.to_dict())
            for item in (rule_run.assessments if rule_run is not None else ())
        ),
        key=lambda item: item["id"],
    )
    contexts = [
        {**item, "kind": kind}
        for kind, items in (
            ("profile", (source_profile, target_profile)),
            ("buildContext", (source_build_context, target_build_context)),
        )
        for item in items
        if item and item.get("fingerprint")
    ]
    descriptions = StepDescriptions(
        facts, ir_facts or [], evidence or [], [*contexts, *description_context], assessments
    )
    projected_findings = sorted(
        (
            _finding(item, descriptions)
            for item in (rule_run.findings if rule_run is not None else ())
        ),
        key=lambda item: item["id"],
    )
    projected_diagnostics = sorted(
        (_diagnostic(item) for item in ordered_diagnostics), key=lambda item: item["id"]
    )
    suppressions = sorted(
        (_suppression(item) for item in suppression_records), key=lambda item: item["id"]
    )
    ordered_rules = tuple(sorted(enabled_rules))
    analysis_scope = (
        "migrationRisks"
        if ordered_rules and request_schema_version in {"1.3", "1.4"}
        else "coreMigrationRisks"
        if ordered_rules
        else "factsOnly"
    )
    requested_profiles: list[str] = []
    completed_profiles: list[str] = []
    capabilities: list[str] = []
    artifact_set: dict[str, Any] | None = None
    project_source_revision = source_revision
    if artifact_plan is not None:
        project_source_revision = (
            str(artifact_plan.document.get("source_revision") or "") or project_source_revision
        )
        artifact_set = {
            "artifactSetId": artifact_plan.artifact_set_id,
            "sourceRevision": artifact_plan.document["source_revision"],
            "buildRunId": artifact_plan.document["build_run_id"],
            "manifestSha256": artifact_plan.manifest_sha256,
        }
        requested_profiles = sorted(
            {member.profile for member in artifact_plan.members}
            | {str(item["profile"]) for item in artifact_plan.document.get("failures", [])}
        )
        completed_profiles = sorted(
            {
                str(item.get("artifact", {}).get("profile", ""))
                for item in (ir_facts or [])
                if item.get("artifact", {}).get("profile")
            }
        )
        capabilities = sorted(
            {
                str(capability)
                for member in artifact_plan.members
                for capability in member.document.get("capabilities", [])
            }
        )
    layer_values = {str(key): dict(value) for key, value in (layers or {}).items()}
    extensions: dict[str, Any] = {
        EXTENSION_ANALYSIS: {
            "schemaVersion": "1.0",
            "analysisScope": analysis_scope,
            "enabledRules": list(ordered_rules),
            "ruleVersions": {
                code: RULE_BY_CODE[code].version for code in ordered_rules if code in RULE_BY_CODE
            },
            "ruleDependencies": [
                _camelize(RULE_BY_CODE[code].to_dict())
                for code in ordered_rules
                if code in RULE_BY_CODE
            ],
            "ruleRegistryDigest": RULE_REGISTRY_DIGEST,
            "assessments": assessments,
            "configurationMembers": _configuration_members(rule_run),
            "layers": layer_values,
            "objects": _catalog(projected_findings, assessments),
            "bundleDigests": {
                "source": sorted(str(item["semantic_digest"]) for item in facts),
                "ir": sorted(str(item["semantic_digest"]) for item in (ir_facts or [])),
                "evidence": sorted(str(item["semantic_digest"]) for item in (evidence or [])),
            },
        },
        EXTENSION_PROFILES: {
            "schemaVersion": "1.0",
            "source": _camelize(source_profile),
            "target": _camelize(target_profile),
            "sourceBuildContext": (
                _camelize(source_build_context) if source_build_context is not None else None
            ),
            "targetBuildContext": (
                _camelize(target_build_context) if target_build_context is not None else None
            ),
        },
        EXTENSION_ARTIFACTS: {
            "schemaVersion": "1.0",
            "artifactSet": artifact_set,
            "bitcodeProfiles": {
                "requested": requested_profiles,
                "completed": completed_profiles,
            },
            "bitcodeCapabilities": capabilities,
            "irAnalysis": {
                "status": (
                    "notRequested"
                    if artifact_plan is None
                    else "complete"
                    if layer_values.get("ir", {}).get("planned", 0)
                    == layer_values.get("ir", {}).get("completed", 0)
                    else "failed"
                    if layer_values.get("ir", {}).get("completed", 0) == 0
                    else "partial"
                ),
                "llvmVersion": LLVM_VERSION,
                "readerVersion": LLVM_READER_VERSION,
                "extractorVersion": LLVM_EXTRACTOR_VERSION,
            },
            "sourceMapping": {
                "mode": source_mapping_mode if artifact_plan is not None else "notRequested",
                "status": (
                    "notRequested"
                    if artifact_plan is None
                    else "complete"
                    if sum(len(item.get("unmatched_ir_fact_ids", [])) for item in (evidence or []))
                    == 0
                    else "incomplete"
                ),
            },
        },
        EXTENSION_SUPPRESSIONS: {
            "schemaVersion": "1.0",
            "policyAsOf": policy_as_of,
            "records": suppressions,
        },
        EXTENSION_RUNTIME: {
            "schemaVersion": "1.0",
            "budgets": _camelize(
                budget_snapshot or {"limits": {}, "logical_used": {}, "frontiers": []}
            ),
            "cache": {
                "schemaVersion": CACHE_SCHEMA_VERSION,
                "mode": cache_mode,
                **dict(cache_stats or {"hit": 0, "miss": 0, "corrupt": 0, "write": 0}),
            },
        },
    }
    summary_layers = {
        key: {
            "planned": int(value.get("planned", 0)),
            "completed": int(value.get("completed", 0)),
            "failed": int(value.get("failed", 0)),
            "unknown": int(value.get("unknown", 0)),
        }
        for key, value in sorted(layer_values.items())
    }
    translation_unit_activity = summary_layers.get(
        "source",
        {"planned": planned, "completed": completed, "failed": failed, "unknown": unknown},
    )
    tu_planned = int(translation_unit_activity["planned"])
    tu_failed = min(tu_planned, int(translation_unit_activity["failed"]))
    tu_unknown = min(
        tu_planned - tu_failed,
        int(translation_unit_activity["unknown"]),
    )
    translation_units = {
        "planned": tu_planned,
        "completed": min(
            tu_planned - tu_failed - tu_unknown,
            int(translation_unit_activity["completed"]),
        ),
        "failed": tu_failed,
        "unknown": tu_unknown,
    }
    reason_codes = sorted(
        {item["code"] for item in projected_diagnostics if item["severity"] == "error"}
    )
    document: dict[str, Any] = {
        "schemaVersion": RESULT_SCHEMA_VERSION,
        "scan": {
            "id": request_id,
            "tool": {"name": "floating-point-precision-scanner", "version": TOOL_VERSION},
            "analysisVersion": TOOL_VERSION,
            "requestSchemaVersion": request_schema_version,
            "analysisScope": analysis_scope,
            "status": status,
        },
        "project": {
            "sourceRevision": project_source_revision,
            "status": (
                "complete"
                if status == "complete"
                else "partial"
                if status == "partial"
                else "unknown"
            ),
            "filesAnalyzed": int(translation_unit_activity["completed"]),
            "translationUnits": dict(translation_units),
            "reasonCodes": reason_codes,
        },
        "summary": {
            "findings": len(projected_findings),
            "suppressed": sum(1 for item in suppressions if item["status"] == "applied"),
            "diagnostics": len(projected_diagnostics),
            "assessments": {
                "planned": int(assessment_counts["planned"]),
                "completed": int(assessment_counts["completed"]),
                "matched": int(assessment_counts["matched"]),
                "notMatched": int(assessment_counts["not_matched"]),
                "unknown": int(assessment_counts["unknown"]),
                "skipped": int(assessment_counts["skipped"]),
                "failed": int(assessment_counts["failed"]),
            },
            "layers": summary_layers,
        },
        "findings": projected_findings,
        "diagnostics": projected_diagnostics,
        "extensions": extensions,
    }
    document["scan"]["semanticDigest"] = semantic_digest(_semantic_projection(document))
    return document


def failure_result_v2(
    request_id: str,
    status: str,
    diagnostics: Sequence[Diagnostic],
    *,
    request_schema_version: str = "unknown",
) -> dict[str, Any]:
    """为 CLI 预分析失败生成与正常路径相同的最小 v2 文档。"""
    return project_result_v2(
        request_id,
        status,
        0,
        0,
        0,
        0,
        [],
        list(diagnostics),
        {},
        {},
        request_schema_version=request_schema_version,
    )


def refresh_semantic_digest(document: dict[str, Any]) -> None:
    """在受控的投影后状态调整完成时刷新语义摘要。"""
    document["scan"]["semanticDigest"] = semantic_digest(_semantic_projection(document))


def truncate_result_v2(document: Mapping[str, Any], maximum_bytes: int) -> dict[str, Any] | None:
    """按 finding 稳定尾部截断；最小闭合文档仍超限时返回 ``None``。"""
    if maximum_bytes <= 0:
        return None
    candidate = _camelize(document)
    if not isinstance(candidate, dict):
        return None
    findings = candidate.get("findings")
    if not isinstance(findings, list):
        return None
    original_count = len(findings)
    while len(serialize_json(candidate)) > maximum_bytes and findings:
        findings.pop()
    if len(serialize_json(candidate)) > maximum_bytes:
        return None
    if len(findings) == original_count:
        return candidate
    dropped = original_count - len(findings)
    item = _diagnostic(
        Diagnostic(
            "result_budget_truncated",
            "output",
            "warning",
            "扫描结果因输出预算而稳定截断；请检查预算 frontier。",
            "The scan result was deterministically truncated by the output budget; "
            "inspect the frontier.",
            "请提高 max_result_bytes 或缩小扫描范围。",
            "Increase max_result_bytes or reduce the scan scope.",
            count=dropped,
        )
    )
    diagnostics = candidate["diagnostics"]
    if len(diagnostics) >= 1000:
        diagnostics[-1] = item
    else:
        diagnostics.append(item)
    diagnostics.sort(key=lambda value: value["id"])
    candidate["summary"]["findings"] = len(findings)
    candidate["summary"]["diagnostics"] = len(diagnostics)
    candidate["scan"]["status"] = "partial"
    candidate["project"]["status"] = "partial"
    if "result_budget_truncated" not in candidate["project"]["reasonCodes"]:
        candidate["project"]["reasonCodes"].append("result_budget_truncated")
        candidate["project"]["reasonCodes"].sort()
    runtime = candidate["extensions"][EXTENSION_RUNTIME]
    runtime["budgets"]["limits"]["maxResultBytes"] = maximum_bytes
    runtime["budgets"]["frontiers"].append(
        {
            "budget": "maxResultBytes",
            "limit": maximum_bytes,
            "used": maximum_bytes,
            "scope": "findings",
            "frontier": findings[-1]["id"] if findings else None,
        }
    )
    runtime["budgets"]["frontiers"].sort(
        key=lambda value: (str(value.get("budget")), str(value.get("scope")))
    )
    while len(serialize_json(candidate)) > maximum_bytes and findings:
        findings.pop()
    dropped = original_count - len(findings)
    item["count"] = max(1, dropped)
    candidate["summary"]["findings"] = len(findings)
    runtime["budgets"]["frontiers"][-1]["frontier"] = findings[-1]["id"] if findings else None
    retained_ids = {finding["id"] for finding in findings}
    for member in candidate["extensions"][EXTENSION_ANALYSIS].get("configurationMembers", []):
        if member["status"] == "visible" and member["aggregateFindingId"] not in retained_ids:
            member["status"] = "truncated"
            member["aggregateFindingId"] = None
    refresh_semantic_digest(candidate)
    return candidate if len(serialize_json(candidate)) <= maximum_bytes else None


def validate_result_invariants(document: Mapping[str, Any]) -> list[str]:
    """返回稳定 reason codes；JSON Schema 负责形状，本函数负责跨引用语义。"""
    errors: set[str] = set()
    if document.get("schemaVersion") != RESULT_SCHEMA_VERSION:
        return ["unsupported-result-version"]
    findings = document.get("findings", [])
    diagnostics = document.get("diagnostics", [])
    extensions = document.get("extensions", {})
    if (
        not isinstance(findings, list)
        or not isinstance(diagnostics, list)
        or not isinstance(extensions, Mapping)
    ):
        return []
    if set(extensions) != REGISTERED_EXTENSIONS:
        errors.add("unregistered-extension")
    finding_ids: set[str] = set()
    all_ids: set[str] = set()
    catalog_ids: set[str] = set()
    analysis = extensions.get(EXTENSION_ANALYSIS, {})
    if isinstance(analysis, Mapping):
        for item in analysis.get("objects", []):
            if isinstance(item, Mapping):
                catalog_ids.add(str(item.get("id")))
    for finding in findings:
        if not isinstance(finding, Mapping):
            continue
        finding_id = str(finding.get("id"))
        fact = finding.get("fact", {})
        fact_id = str(fact.get("id")) if isinstance(fact, Mapping) else ""
        local_ids = {finding_id, fact_id} - {""}
        if finding_id in finding_ids or all_ids.intersection(local_ids):
            errors.add("duplicate-id")
        finding_ids.add(finding_id)
        all_ids.update(local_ids)
        occurrences = finding.get("occurrences", [])
        occurrence_ids = {str(item.get("id")) for item in occurrences if isinstance(item, Mapping)}
        if len(occurrence_ids) != len(occurrences) or all_ids.intersection(occurrence_ids):
            errors.add("duplicate-id")
        if finding.get("primaryOccurrenceId") not in occurrence_ids:
            errors.add("dangling-primary-occurrence")
        chain = finding.get("evidenceChain", {})
        if not isinstance(chain, Mapping):
            continue
        steps = chain.get("steps", [])
        step_ids = {str(item.get("id")) for item in steps if isinstance(item, Mapping)}
        if len(step_ids) != len(steps) or all_ids.intersection(step_ids | occurrence_ids):
            errors.add("duplicate-id")
        all_ids.update(step_ids | occurrence_ids)
        if chain.get("rootStepId") not in step_ids:
            errors.add("dangling-step-reference")
        path = [str(item) for item in chain.get("primaryPath", [])]
        if not path or len(path) != len(set(path)) or any(item not in step_ids for item in path):
            errors.add("invalid-primary-path")
        if any(str(item) not in step_ids for item in chain.get("impactStepIds", [])):
            errors.add("dangling-impact-reference")
        edge_pairs: set[tuple[str, str]] = set()
        edge_ids: set[str] = set()
        edges = chain.get("edges", [])
        for edge in edges:
            if (
                not isinstance(edge, Mapping)
                or edge.get("fromStepId") not in step_ids
                or edge.get("toStepId") not in step_ids
            ):
                errors.add("dangling-edge-reference")
                continue
            edge_id = str(edge.get("id"))
            if edge_id in edge_ids or edge_id in all_ids:
                errors.add("duplicate-id")
            edge_ids.add(edge_id)
            edge_pairs.add((str(edge.get("fromStepId")), str(edge.get("toStepId"))))
        all_ids.update(edge_ids)
        if any(
            (left, right) not in edge_pairs for left, right in zip(path, path[1:], strict=False)
        ):
            errors.add("invalid-primary-path")
        breaks = chain.get("breaks", [])
        if chain.get("status") == "complete" and breaks:
            errors.add("complete-chain-has-break")
        if chain.get("status") in {"partial", "unknown", "ambiguous"} and not breaks:
            errors.add("incomplete-chain-missing-break")
        for step in steps:
            if not isinstance(step, Mapping):
                continue
            for reference in step.get("references", []):
                if isinstance(reference, Mapping) and str(reference.get("id")) not in catalog_ids:
                    errors.add("dangling-external-reference")
        provenance_groups: list[Any] = []
        if isinstance(fact, Mapping):
            provenance_groups.append(fact.get("provenance", []))
        provenance_groups.extend(
            item.get("provenance", []) for item in occurrences if isinstance(item, Mapping)
        )
        for group in provenance_groups:
            for reference in group if isinstance(group, list) else []:
                if isinstance(reference, Mapping) and str(reference.get("id")) not in catalog_ids:
                    errors.add("dangling-external-reference")
        conclusion = finding.get("conclusion", {})
        if isinstance(conclusion, Mapping):
            bilingual = (
                "title",
                "titleEn",
                "message",
                "messageEn",
                "impact",
                "impactEn",
                "suggestion",
                "suggestionEn",
                "testSuggestion",
                "testSuggestionEn",
            )
            if any(not str(conclusion.get(field, "")).strip() for field in bilingual):
                errors.add("missing-bilingual-field")
            if (
                conclusion.get("evidenceOrigin") == "heuristic"
                or conclusion.get("severity") in {"critical", "high"}
            ) and conclusion.get("requiresHumanConfirmation") is not True:
                errors.add("confirmation-policy-violation")
        for occurrence in occurrences:
            if not isinstance(occurrence, Mapping):
                continue
            location = occurrence.get("location")
            if isinstance(location, Mapping):
                path_value = str(location.get("file", ""))
                pure = PurePosixPath(path_value.replace("\\", "/"))
                if pure.is_absolute() or re.match(r"^[A-Za-z]:", path_value) or ".." in pure.parts:
                    errors.add("unsafe-path")
    for item in diagnostics:
        if not isinstance(item, Mapping):
            continue
        if any(str(value) not in finding_ids for value in item.get("findingIds", [])):
            errors.add("dangling-diagnostic-reference")
        diagnostic_bilingual = ("message", "messageEn", "suggestion", "suggestionEn")
        if any(not str(item.get(field, "")).strip() for field in diagnostic_bilingual):
            errors.add("missing-bilingual-field")
        diagnostic_id = str(item.get("id"))
        if diagnostic_id in all_ids:
            errors.add("duplicate-id")
        all_ids.add(diagnostic_id)
    summary = document.get("summary", {})
    suppressions_ext = extensions.get(EXTENSION_SUPPRESSIONS, {})
    suppressions = (
        suppressions_ext.get("records", []) if isinstance(suppressions_ext, Mapping) else []
    )
    if isinstance(analysis, Mapping):
        assessment_ids = {str(item.get("id")) for item in analysis.get("assessments", [])}
        suppressed_ids = {
            str(item.get("findingId")) for item in suppressions if item.get("status") == "applied"
        }
        for member in analysis.get("configurationMembers", []):
            if any(str(ref.get("id")) not in catalog_ids for ref in member.get("evidence", [])):
                errors.add("dangling-external-reference")
            if (
                member.get("assessmentId") not in assessment_ids
                or (
                    member.get("status") == "visible"
                    and member.get("aggregateFindingId") not in finding_ids
                )
                or (
                    member.get("status") == "suppressed"
                    and member.get("candidateFindingId") not in suppressed_ids
                )
                or (
                    member.get("status") == "truncated"
                    and (
                        member.get("aggregateFindingId") is not None
                        or not any(
                            item.get("code") == "result_budget_truncated" for item in diagnostics
                        )
                    )
                )
            ):
                errors.add("dangling-configuration-member")
    if isinstance(summary, Mapping):
        if summary.get("findings") != len(findings) or summary.get("diagnostics") != len(
            diagnostics
        ):
            errors.add("count-mismatch")
        if summary.get("suppressed") != sum(
            1
            for item in suppressions
            if isinstance(item, Mapping) and item.get("status") == "applied"
        ):
            errors.add("count-mismatch")
        assessment_summary = summary.get("assessments", {})
        assessments = analysis.get("assessments", []) if isinstance(analysis, Mapping) else []
        statuses = [item.get("status") for item in assessments if isinstance(item, Mapping)]
        expected = {
            "planned": len(assessments),
            "completed": statuses.count("matched") + statuses.count("notMatched"),
            "matched": statuses.count("matched"),
            "notMatched": statuses.count("notMatched"),
            "unknown": statuses.count("unknown"),
            "skipped": statuses.count("skipped"),
            "failed": statuses.count("failed"),
        }
        if assessment_summary != expected:
            errors.add("count-mismatch")
    project = document.get("project", {})
    if isinstance(project, Mapping):
        translation_units = project.get("translationUnits", {})
        if isinstance(translation_units, Mapping):
            planned = translation_units.get("planned")
            analyzed = sum(
                int(translation_units.get(key, 0)) for key in ("completed", "failed", "unknown")
            )
            if isinstance(planned, int) and analyzed > planned:
                errors.add("count-mismatch")
    return sorted(errors)


__all__ = [
    "EXTENSION_ANALYSIS",
    "EXTENSION_ARTIFACTS",
    "EXTENSION_PROFILES",
    "EXTENSION_RUNTIME",
    "EXTENSION_SUPPRESSIONS",
    "REGISTERED_EXTENSIONS",
    "failure_result_v2",
    "project_result_v2",
    "refresh_semantic_digest",
    "truncate_result_v2",
    "validate_result_invariants",
]
