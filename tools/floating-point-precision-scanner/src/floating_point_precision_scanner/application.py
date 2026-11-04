"""浮点事实、证据与核心迁移规则应用服务。"""

from __future__ import annotations

import hashlib
from collections.abc import Mapping
from typing import Any

from . import (
    EVIDENCE_SCHEMA_VERSION,
    FACT_MAPPER_VERSION,
    FACTS_SCHEMA_VERSION,
    LLVM_EXTRACTOR_VERSION,
    LLVM_READER_VERSION,
    LLVM_VERSION,
    LLVM_WORKER_PROTOCOL_VERSION,
    TOOL_VERSION,
)
from .aggregation import aggregate_status
from .artifacts import ArtifactInputError, ArtifactNotReady, ArtifactPlan, load_artifact_plan
from .budget import BudgetCoordinator, BudgetExceeded, resource_limits_supported
from .build_contexts import BuildContext, build_context
from .cache import AnalysisCache, cache_key
from .compilation import CompilationInputError, load_compilation_plan
from .diagnostics import Diagnostic, diagnostic
from .evidence import build_evidence
from .facts import blocking_unknown_diagnostic, map_ast
from .frontend import ClangFrontend, FrontendFailure
from .ir_worker import IrWorker, IrWorkerFailure
from .profiles import ProfileLookupError, ProfileRegistry, profile_context_diagnostic
from .protocol import SchemaRegistry, parse_request
from .result_v2 import project_result_v2, refresh_semantic_digest
from .rules import RULE_BY_CODE, RuleRun, _aggregate_findings, evaluate_rules
from .suppression import Suppression, apply_suppressions


class Scanner:
    def __init__(
        self,
        frontend: ClangFrontend,
        profiles: ProfileRegistry | None = None,
        schemas: SchemaRegistry | None = None,
        ir_worker: IrWorker | None = None,
    ) -> None:
        self.frontend = frontend
        self.schemas = schemas or SchemaRegistry()
        self.profiles = profiles or ProfileRegistry(schema_registry=self.schemas)
        self.ir_worker = ir_worker

    def validate_request(self, document: Mapping[str, Any]) -> list[Diagnostic]:
        request, errors = parse_request(document, self.schemas)
        if errors or request is None:
            return errors
        try:
            source_profile, target_profile = self.profiles.bind(
                request.source_profile, request.target_profile
            )
            _, context_errors = self._build_contexts(request, source_profile, target_profile)
            if context_errors:
                return context_errors
            plan = load_compilation_plan(request)
            if request.bitcode_artifact_set:
                load_artifact_plan(
                    request.bitcode_artifact_set,
                    plan,
                    self.schemas,
                    source_revision=request.source_revision,
                    max_bytes=int(request.budgets.get("max_bitcode_bytes", 536_870_912)),
                )
        except ProfileLookupError as exc:
            return [self._unknown_profile(str(exc.args[0]))]
        except CompilationInputError as exc:
            return exc.diagnostics
        except ArtifactInputError as exc:
            return exc.diagnostics
        return []

    def scan(self, document: Mapping[str, Any]) -> dict[str, Any]:
        request, errors = parse_request(document, self.schemas)
        request_id = str(document.get("request_id", "invalid-request"))
        if errors or request is None:
            return self._result(request_id, "invalid_request", 0, 0, 0, 0, [], errors, {}, {})
        budget = BudgetCoordinator(request.budgets)
        cache = AnalysisCache(request.cache)
        process_limits_requested = any(
            name in request.budgets for name in ("cpu_seconds", "max_memory_bytes")
        )
        process_adapter_used = isinstance(self.frontend, ClangFrontend) or isinstance(
            self.ir_worker, IrWorker
        )
        if process_limits_requested and process_adapter_used and not resource_limits_supported():
            return self._result(
                request.request_id,
                "failed",
                0,
                0,
                0,
                0,
                [],
                [
                    diagnostic(
                        "resource_budget_unsupported",
                        "budget",
                        "当前平台无法强制子进程 CPU/RSS 预算，扫描未启动。",
                        "This platform cannot enforce child CPU/RSS budgets; "
                        "the scan was not started.",
                    )
                ],
                {},
                {},
                request_schema_version=request.schema_version,
                policy_as_of=request.policy_as_of,
                budget_snapshot=budget.snapshot(),
                cache_mode=request.cache.mode,
                cache_stats=cache.stats.to_dict(),
            )
        try:
            source_profile, target_profile = self.profiles.bind(
                request.source_profile, request.target_profile
            )
        except ProfileLookupError as exc:
            item = self._unknown_profile(str(exc.args[0]))
            return self._result(
                request.request_id, "invalid_request", 0, 0, 0, 0, [], [item], {}, {}
            )
        (source_build_context, target_build_context), context_errors = self._build_contexts(
            request, source_profile, target_profile
        )
        if context_errors:
            return self._result(
                request.request_id,
                "invalid_request",
                0,
                0,
                0,
                0,
                [],
                context_errors,
                source_profile.summary(),
                target_profile.summary(),
                request_schema_version=request.schema_version,
                policy_as_of=request.policy_as_of,
                budget_snapshot=budget.snapshot(),
                cache_mode=request.cache.mode,
                cache_stats=cache.stats.to_dict(),
            )
        try:
            plan = load_compilation_plan(request)
        except CompilationInputError as exc:
            return self._result(
                request.request_id,
                "invalid_request",
                0,
                0,
                0,
                0,
                [],
                exc.diagnostics,
                source_profile.summary(),
                target_profile.summary(),
                request_schema_version=request.schema_version,
                policy_as_of=request.policy_as_of,
                budget_snapshot=budget.snapshot(),
                cache_mode=request.cache.mode,
                cache_stats=cache.stats.to_dict(),
            )
        try:
            budget.consume("max_files", len(plan.contexts), scope="compilation-plan", frontier=None)
        except BudgetExceeded as exc:
            return self._result(
                request.request_id,
                "failed",
                len(plan.contexts),
                0,
                len(plan.contexts),
                0,
                [],
                [self._budget_diagnostic(exc)],
                source_profile.summary(),
                target_profile.summary(),
                request_schema_version=request.schema_version,
                policy_as_of=request.policy_as_of,
                budget_snapshot=budget.snapshot(),
                cache_mode=request.cache.mode,
                cache_stats=cache.stats.to_dict(),
            )
        artifact_plan: ArtifactPlan | None = None
        if request.bitcode_artifact_set:
            try:
                artifact_plan = load_artifact_plan(
                    request.bitcode_artifact_set,
                    plan,
                    self.schemas,
                    source_revision=request.source_revision,
                    max_bytes=int(request.budgets.get("max_bitcode_bytes", 536_870_912)),
                )
            except ArtifactNotReady as exc:
                result = self._result(
                    request.request_id,
                    "failed",
                    1,
                    0,
                    1,
                    0,
                    [],
                    exc.diagnostics,
                    source_profile.summary(),
                    target_profile.summary(),
                )
                artifacts_extension = result["extensions"][
                    "org.rvport.floating-point-scanner.artifacts"
                ]
                artifacts_extension["irAnalysis"]["status"] = "failed"
                artifacts_extension["bitcodeProfiles"] = {
                    "requested": list(request.bitcode_artifact_set.required_profiles),
                    "completed": [],
                }
                artifacts_extension["sourceMapping"] = {
                    "mode": request.bitcode_artifact_set.source_mapping,
                    "status": "incomplete",
                }
                refresh_semantic_digest(result)
                return result
            except ArtifactInputError as exc:
                return self._result(
                    request.request_id,
                    "invalid_request",
                    0,
                    0,
                    0,
                    0,
                    [],
                    exc.diagnostics,
                    source_profile.summary(),
                    target_profile.summary(),
                )
        diagnostics = list(plan.diagnostics)
        if artifact_plan:
            diagnostics.extend(artifact_plan.diagnostics)
        facts: list[dict[str, Any]] = []
        failed = plan.failed_count
        source_unknown = 0
        frontend_available = True
        try:
            if isinstance(self.frontend, ClangFrontend):
                self.frontend.verify(
                    timeout=budget.remaining_wall(self.frontend.timeout),
                    cpu_seconds=(
                        budget.remaining_cpu(float(request.budgets["cpu_seconds"]))
                        if "cpu_seconds" in request.budgets
                        else None
                    ),
                    memory_bytes=(
                        int(request.budgets["max_memory_bytes"])
                        if "max_memory_bytes" in request.budgets
                        else None
                    ),
                    output_limit=int(
                        request.budgets.get("max_frontend_output_bytes", self.frontend.output_limit)
                    ),
                )
            else:
                self.frontend.verify()
        except BudgetExceeded as exc:
            diagnostics.append(self._budget_diagnostic(exc))
            failed += len(plan.contexts)
            frontend_available = False
            if artifact_plan is None:
                return self._result(
                    request.request_id,
                    "failed",
                    len(plan.contexts) + plan.failed_count,
                    0,
                    failed,
                    0,
                    facts,
                    diagnostics,
                    source_profile.summary(),
                    target_profile.summary(),
                    request_schema_version=request.schema_version,
                    policy_as_of=request.policy_as_of,
                    budget_snapshot=budget.snapshot(),
                    cache_mode=request.cache.mode,
                    cache_stats=cache.stats.to_dict(),
                )
        except FrontendFailure as exc:
            diagnostics.append(exc.diagnostic)
            failed += len(plan.contexts)
            frontend_available = False
            if artifact_plan is None:
                return self._result(
                    request.request_id,
                    "failed",
                    len(plan.contexts) + plan.failed_count,
                    0,
                    failed,
                    0,
                    facts,
                    diagnostics,
                    source_profile.summary(),
                    target_profile.summary(),
                )
        for context in plan.contexts if frontend_available else ():
            try:
                budget.check_resources(context.context_id, context.context_id)
            except BudgetExceeded as exc:
                diagnostics.append(self._budget_diagnostic(exc))
                failed += len(plan.contexts) - len(facts)
                break
            conflict = profile_context_diagnostic(
                source_profile, target=context.target, abi=context.abi
            )
            if conflict:
                diagnostics.append(conflict)
                failed += 1
                continue
            try:
                source_digest = hashlib.sha256(context.absolute_file.read_bytes()).hexdigest()
                source_cache_key = cache_key(
                    "source-facts",
                    {
                        "context_id": context.context_id,
                        "source_sha256": source_digest,
                        "semantic_arguments": list(context.semantic_arguments),
                        "source_profile": source_profile.fingerprint,
                        "target_profile": target_profile.fingerprint,
                        "tool_version": TOOL_VERSION,
                        "facts_schema_version": FACTS_SCHEMA_VERSION,
                        "fact_mapper_version": FACT_MAPPER_VERSION,
                        "source_location_revision": 4,
                        "max_function_summary_depth": int(
                            request.budgets.get("max_function_summary_depth", 3)
                        ),
                    },
                )
                before_corrupt = cache.stats.corrupt
                cached = cache.read("source-facts", source_cache_key)
                if cache.stats.corrupt > before_corrupt:
                    diagnostics.append(
                        self._cache_diagnostic("cache_entry_corrupt", context.context_id)
                    )
                if cached is not None and not self.schemas.validate("facts", cached):
                    bundle = dict(cached)
                else:
                    if isinstance(self.frontend, ClangFrontend):
                        ast = self.frontend.ast(
                            context,
                            timeout=budget.remaining_wall(self.frontend.timeout),
                            cpu_seconds=(
                                budget.remaining_cpu(float(request.budgets["cpu_seconds"]))
                                if "cpu_seconds" in request.budgets
                                else None
                            ),
                            memory_bytes=(
                                int(request.budgets["max_memory_bytes"])
                                if "max_memory_bytes" in request.budgets
                                else None
                            ),
                            output_limit=int(
                                request.budgets.get(
                                    "max_frontend_output_bytes", self.frontend.output_limit
                                )
                            ),
                        )
                    else:
                        ast = self.frontend.ast(context)
                    bundle = map_ast(
                        context,
                        ast,
                        request.project_root,
                        source_profile.reference,
                        target_profile.reference,
                        max_function_summary_depth=int(
                            request.budgets.get("max_function_summary_depth", 3)
                        ),
                    )
                    try:
                        cache.write(
                            "source-facts",
                            source_cache_key,
                            bundle,
                            dependencies={"source_sha256": source_digest},
                        )
                    except (OSError, ValueError):
                        diagnostics.append(
                            self._cache_diagnostic("cache_publish_failed", context.context_id)
                        )
                schema_errors = self.schemas.validate("facts", bundle)
                if schema_errors:
                    diagnostics.extend(schema_errors)
                    failed += 1
                    continue
                facts.append(bundle)
                diagnostics.extend(Diagnostic(**item) for item in bundle.get("diagnostics", []))
                if unknown_item := blocking_unknown_diagnostic(bundle):
                    diagnostics.append(Diagnostic(**unknown_item))
                    source_unknown += 1
            except FrontendFailure as exc:
                diagnostics.append(exc.diagnostic)
                failed += 1
            except BudgetExceeded as exc:
                diagnostics.append(self._budget_diagnostic(exc))
                failed += len(plan.contexts) - len(facts)
                break
        source_planned = len(plan.contexts) + plan.failed_count
        source_completed = len(facts)
        source_failed = failed
        ir_facts: list[dict[str, Any]] = []
        evidence: list[dict[str, Any]] = []
        ir_failed = artifact_plan.failed_count if artifact_plan else 0
        ir_unknown = 0
        evidence_failed = 0
        evidence_unknown = 0
        if artifact_plan:
            if self.ir_worker is None:
                diagnostics.append(
                    diagnostic(
                        "ir_worker_unavailable",
                        "ir_worker",
                        "请求了共享 BC 分析，但未配置 LLVM IR worker。",
                        "Shared bitcode analysis was requested but no LLVM IR worker "
                        "is configured.",
                    )
                )
                ir_failed += len(artifact_plan.members)
            else:
                try:
                    self.ir_worker.verify()
                except IrWorkerFailure as exc:
                    diagnostics.append(exc.diagnostic)
                    ir_failed += len(artifact_plan.members)
                else:
                    source_by_context = {str(bundle["context_id"]): bundle for bundle in facts}
                    for member in artifact_plan.members:
                        try:
                            budget.check_resources(member.context_id, member.artifact_key)
                            ir_key = cache_key(
                                "ir-facts",
                                {
                                    "artifact_key": member.artifact_key,
                                    "sha256": member.document["sha256"],
                                    "profile": member.profile,
                                    "llvm_version": LLVM_VERSION,
                                    "reader_version": LLVM_READER_VERSION,
                                    "extractor_version": LLVM_EXTRACTOR_VERSION,
                                    "max_ir_facts": int(
                                        request.budgets.get("max_ir_facts", 100_000)
                                    ),
                                    "max_ir_use_chain": int(
                                        request.budgets.get("max_ir_use_chain", 1000)
                                    ),
                                    "max_function_summary_depth": int(
                                        request.budgets.get("max_function_summary_depth", 3)
                                    ),
                                    "max_ir_summary_call_edges": int(
                                        request.budgets.get("max_ir_summary_call_edges", 1000)
                                    ),
                                },
                            )
                            before_corrupt = cache.stats.corrupt
                            cached_ir = cache.read("ir-facts", ir_key)
                            if cache.stats.corrupt > before_corrupt:
                                diagnostics.append(
                                    self._cache_diagnostic("cache_entry_corrupt", member.context_id)
                                )
                            if cached_ir is not None and not self.schemas.validate(
                                "llvm_facts", cached_ir
                            ):
                                bundle = dict(cached_ir)
                            else:
                                worker_arguments: dict[str, Any] = {
                                    "max_facts": int(request.budgets.get("max_ir_facts", 100_000)),
                                    "max_use_edges": int(
                                        request.budgets.get("max_ir_use_chain", 1000)
                                    ),
                                    "max_summary_depth": int(
                                        request.budgets.get("max_function_summary_depth", 3)
                                    ),
                                    "max_summary_call_edges": int(
                                        request.budgets.get("max_ir_summary_call_edges", 1000)
                                    ),
                                    "project_root": request.project_root,
                                    "cpu_seconds": (
                                        budget.remaining_cpu(float(request.budgets["cpu_seconds"]))
                                        if "cpu_seconds" in request.budgets
                                        else None
                                    ),
                                    "memory_bytes": (
                                        int(request.budgets["max_memory_bytes"])
                                        if "max_memory_bytes" in request.budgets
                                        else None
                                    ),
                                }
                                if isinstance(self.ir_worker, IrWorker):
                                    worker_arguments.update(
                                        timeout=budget.remaining_wall(self.ir_worker.timeout),
                                        output_limit=int(
                                            request.budgets.get(
                                                "max_ir_output_bytes",
                                                self.ir_worker.output_limit,
                                            )
                                        ),
                                    )
                                bundle = self.ir_worker.extract(
                                    artifact_plan, member, **worker_arguments
                                )
                                try:
                                    cache.write(
                                        "ir-facts",
                                        ir_key,
                                        bundle,
                                        dependencies={
                                            "artifact_sha256": str(member.document["sha256"])
                                        },
                                    )
                                except (OSError, ValueError):
                                    diagnostics.append(
                                        self._cache_diagnostic(
                                            "cache_publish_failed", member.context_id
                                        )
                                    )
                            schema_errors = self.schemas.validate("llvm_facts", bundle)
                            if schema_errors:
                                diagnostics.extend(schema_errors)
                                ir_failed += 1
                                continue
                            identity_error = self._ir_identity_error(bundle, artifact_plan, member)
                            if identity_error:
                                diagnostics.append(identity_error)
                                ir_failed += 1
                                continue
                            ir_facts.append(bundle)
                            if any(
                                isinstance(item, Mapping) and item.get("blocking")
                                for item in bundle.get("unknowns", [])
                            ):
                                ir_unknown += 1
                                diagnostics.append(
                                    diagnostic(
                                        "ir_budget_exceeded",
                                        "ir_worker",
                                        "LLVM facts 预算耗尽，存在未分析范围。",
                                        "The LLVM facts budget was exhausted; some scope was "
                                        "not analyzed.",
                                        severity="warning",
                                        translation_unit_id=member.context_id,
                                    )
                                )
                            source_bundle = source_by_context.get(member.context_id)
                            if source_bundle is None:
                                evidence_failed += 1
                                diagnostics.append(
                                    diagnostic(
                                        "source_fact_bundle_missing",
                                        "evidence",
                                        "无法为该 BC 成员找到源码 facts。",
                                        "No source fact bundle was found for this bitcode member.",
                                        translation_unit_id=member.context_id,
                                    )
                                )
                                continue
                            evidence_key = cache_key(
                                "evidence",
                                {
                                    "evidence_schema_version": EVIDENCE_SCHEMA_VERSION,
                                    "source_digest": source_bundle["semantic_digest"],
                                    "ir_digest": bundle["semantic_digest"],
                                    "artifact_key": member.artifact_key,
                                    "mapping_required": (
                                        request.bitcode_artifact_set is not None
                                        and request.bitcode_artifact_set.source_mapping
                                        == "required"
                                    ),
                                    "max_evidence_candidates": int(
                                        request.budgets.get("max_evidence_candidates", 100_000)
                                    ),
                                },
                            )
                            before_corrupt = cache.stats.corrupt
                            cached_evidence = cache.read("evidence", evidence_key)
                            evidence_diagnostics: list[Diagnostic]
                            if cache.stats.corrupt > before_corrupt:
                                diagnostics.append(
                                    self._cache_diagnostic("cache_entry_corrupt", member.context_id)
                                )
                            if cached_evidence is not None and not self.schemas.validate(
                                "evidence", cached_evidence
                            ):
                                evidence_bundle = dict(cached_evidence)
                                evidence_diagnostics = [
                                    Diagnostic(**item)
                                    for item in evidence_bundle.get("diagnostics", [])
                                    if isinstance(item, Mapping)
                                ]
                            else:
                                evidence_bundle, evidence_diagnostics = build_evidence(
                                    source_bundle,
                                    bundle,
                                    member,
                                    self.schemas,
                                    mapping_required=(
                                        request.bitcode_artifact_set is not None
                                        and request.bitcode_artifact_set.source_mapping
                                        == "required"
                                    ),
                                    max_candidates=int(
                                        request.budgets.get("max_evidence_candidates", 100_000)
                                    ),
                                )
                                try:
                                    cache.write(
                                        "evidence",
                                        evidence_key,
                                        evidence_bundle,
                                        dependencies={
                                            "source": str(source_bundle["semantic_digest"]),
                                            "ir": str(bundle["semantic_digest"]),
                                        },
                                    )
                                except (OSError, ValueError):
                                    diagnostics.append(
                                        self._cache_diagnostic(
                                            "cache_publish_failed", member.context_id
                                        )
                                    )
                            evidence_schema_errors = self.schemas.validate(
                                "evidence", evidence_bundle
                            )
                            if evidence_schema_errors:
                                diagnostics.extend(evidence_schema_errors)
                                evidence_failed += 1
                                continue
                            evidence.append(evidence_bundle)
                            diagnostics.extend(evidence_diagnostics)
                            if any(item.severity == "error" for item in evidence_diagnostics):
                                evidence_unknown += 1
                        except IrWorkerFailure as exc:
                            diagnostics.append(exc.diagnostic)
                            ir_failed += 1
                        except BudgetExceeded as exc:
                            diagnostics.append(self._budget_diagnostic(exc))
                            ir_failed += len(artifact_plan.members) - len(ir_facts)
                            break
        artifact_planned = (
            len(artifact_plan.members) + artifact_plan.failed_count if artifact_plan else 0
        )
        artifact_completed = len(artifact_plan.members) if artifact_plan else 0
        artifact_failed = artifact_plan.failed_count if artifact_plan else 0
        ir_planned = artifact_planned
        evidence_planned = ir_planned
        evidence_failed += ir_failed
        planned = artifact_planned + source_planned + ir_planned + evidence_planned
        completed = artifact_completed + source_completed + len(ir_facts) + len(evidence)
        failed = artifact_failed + source_failed + ir_failed + evidence_failed
        unknown = source_unknown + ir_unknown + evidence_unknown
        rule_run = evaluate_rules(
            request.rules_enabled,
            source_profile,
            target_profile,
            facts,
            ir_facts,
            evidence,
            request.budgets,
            source_build_context,
            target_build_context,
            aggregate=False,
        )
        diagnostics.extend(rule_run.diagnostics)
        inline_suppressions, suppression_diagnostics = self._source_suppressions(facts)
        diagnostics.extend(suppression_diagnostics)
        all_suppressions = (*request.suppressions, *inline_suppressions)
        max_suppression_records = int(request.budgets.get("max_suppression_records", 1000))
        visible_findings, suppression_records = apply_suppressions(
            rule_run.findings,
            all_suppressions,
            max_records=max_suppression_records,
            policy_as_of=request.policy_as_of,
        )
        for record in suppression_records:
            if record.get("status") == "expired":
                diagnostics.append(
                    diagnostic(
                        "suppression_expired",
                        "suppression",
                        "Suppression 已过期，finding 保持可见。",
                        "The suppression expired; the finding remains visible.",
                        severity="warning",
                    )
                )
        if len(suppression_records) >= max_suppression_records and any(
            suppression.matches(finding)
            for finding in visible_findings
            for suppression in all_suppressions
        ):
            diagnostics.append(
                diagnostic(
                    "suppression_budget_exceeded",
                    "suppression",
                    "Suppression 审计记录预算耗尽，剩余 finding 保持可见。",
                    "The suppression audit budget was exhausted; remaining findings stay visible.",
                    severity="warning",
                )
            )
        rule_run = RuleRun(rule_run.assessments, _aggregate_findings(
            visible_findings, int(request.budgets.get("max_related_locations", 100)),
        ), rule_run.diagnostics, rule_run.candidate_findings)
        rule_counts = rule_run.counts
        planned += rule_counts["planned"]
        completed += rule_counts["completed"]
        failed += rule_counts["failed"]
        unknown += rule_counts["unknown"] + rule_counts["skipped"]
        status = aggregate_status(
            planned=planned, completed=completed, failed=failed, unknown=unknown
        )
        if artifact_plan is not None and ir_planned > 0 and not ir_facts:
            status = "failed"
        if request.rules_enabled and rule_counts["completed"] == 0:
            status = "failed"
        return self._result(
            request.request_id,
            status,
            planned,
            completed,
            failed,
            unknown,
            facts,
            diagnostics,
            source_profile.summary(),
            target_profile.summary(),
            ir_facts=ir_facts,
            evidence=evidence,
            artifact_plan=artifact_plan,
            layers={
                "artifact": {
                    "planned": artifact_planned,
                    "completed": artifact_completed,
                    "failed": artifact_failed,
                    "unknown": 0,
                },
                "source": {
                    "planned": source_planned,
                    "completed": source_completed,
                    "failed": source_failed,
                    "unknown": source_unknown,
                },
                "ir": {
                    "planned": ir_planned,
                    "completed": len(ir_facts),
                    "failed": ir_failed,
                    "unknown": ir_unknown,
                },
                "evidence": {
                    "planned": evidence_planned,
                    "completed": len(evidence),
                    "failed": evidence_failed,
                    "unknown": evidence_unknown,
                },
                "rules": {
                    "planned": rule_counts["planned"],
                    "completed": rule_counts["completed"],
                    "failed": rule_counts["failed"],
                    "unknown": rule_counts["unknown"] + rule_counts["skipped"],
                },
            },
            source_mapping_mode=(
                request.bitcode_artifact_set.source_mapping
                if request.bitcode_artifact_set
                else "not_requested"
            ),
            rule_run=rule_run,
            enabled_rules=request.rules_enabled,
            description_context=[
                {**profile.core_rule_view(), **profile.summary(), "kind": "profile"}
                for profile in (source_profile, target_profile)
            ]
            + [
                {**context.document, **context.summary(), "kind": "buildContext"}
                for context in (source_build_context, target_build_context)
                if context is not None
            ],
            source_build_context=(source_build_context.summary() if source_build_context else None),
            target_build_context=(target_build_context.summary() if target_build_context else None),
            suppression_records=suppression_records,
            request_schema_version=request.schema_version,
            source_revision=request.source_revision,
            policy_as_of=request.policy_as_of,
            budget_snapshot=budget.snapshot(),
            cache_mode=request.cache.mode,
            cache_stats=cache.stats.to_dict(),
            diagnostic_limit=int(
                request.budgets.get(
                    "max_diagnostics", request.budgets.get("max_rule_diagnostics", 1000)
                )
            ),
        )

    def _build_contexts(
        self, request: Any, source_profile: Any, target_profile: Any
    ) -> tuple[tuple[BuildContext | None, BuildContext | None], list[Diagnostic]]:
        contexts: list[BuildContext | None] = []
        errors: list[Diagnostic] = []
        for document, profile in (
            (request.source_build_context, source_profile),
            (request.target_build_context, target_profile),
        ):
            if document is None:
                contexts.append(None)
                continue
            item, item_errors = build_context(
                document,
                profile_fingerprint=profile.fingerprint,
                target_triple=profile.target_triple,
                abi=profile.abi,
            )
            contexts.append(item)
            errors.extend(item_errors)
        return (contexts[0], contexts[1]), errors

    @staticmethod
    def _source_suppressions(
        facts: list[dict[str, Any]],
    ) -> tuple[tuple[Suppression, ...], list[Diagnostic]]:
        result: list[Suppression] = []
        diagnostics: list[Diagnostic] = []
        for bundle in facts:
            for fact in bundle.get("facts", []):
                if not isinstance(fact, Mapping) or fact.get("fact_kind") != "suppression":
                    continue
                payload = fact.get("payload")
                location = fact.get("location")
                if not isinstance(payload, Mapping) or not isinstance(location, Mapping):
                    continue
                code = str(payload.get("code", ""))
                target_line = payload.get("target_line")
                reason = str(payload.get("reason", "")).strip()
                point = location.get("expansion") or location.get("spelling") or location
                if (
                    code not in RULE_BY_CODE
                    or not reason
                    or bool(payload.get("ambiguous"))
                    or not isinstance(target_line, int)
                    or not isinstance(point, Mapping)
                ):
                    diagnostics.append(
                        diagnostic(
                            "source_suppression_invalid",
                            "suppression",
                            "源码 suppression 无法唯一绑定，未隐藏 finding。",
                            "The source suppression could not be bound uniquely; "
                            "no finding was hidden.",
                            severity="warning",
                            location=dict(point) if isinstance(point, Mapping) else None,
                        )
                    )
                    continue
                result.append(
                    Suppression(
                        code,
                        str(point.get("path", "")),
                        reason,
                        target_line,
                        target_line,
                        "source",
                    )
                )
        return tuple(result), diagnostics

    @staticmethod
    def _unknown_profile(reference: str) -> Diagnostic:
        return diagnostic(
            "unknown_profile",
            "profile_binding",
            f"未找到固定版本 Profile：{reference}。",
            f"Pinned profile not found: {reference}.",
            suggestion="使用 profiles list 查询可用 Profile。",
            suggestion_en="Use profiles list to inspect available profiles.",
        )

    @staticmethod
    def _budget_diagnostic(exc: BudgetExceeded) -> Diagnostic:
        item = exc.item
        return diagnostic(
            "budget_exceeded",
            "budget",
            f"预算 {item.budget} 已耗尽，未分析范围保持可见。",
            f"Budget {item.budget} was exhausted; unanalyzed scope remains visible.",
            severity="warning",
            suggestion=f"limit={item.limit}; used={item.used}; frontier={item.frontier}",
            suggestion_en=f"limit={item.limit}; used={item.used}; frontier={item.frontier}",
            translation_unit_id=item.scope,
        )

    @staticmethod
    def _cache_diagnostic(code: str, scope: str) -> Diagnostic:
        return diagnostic(
            code,
            "cache",
            "分析缓存条目不可用，已安全回退到重新计算。",
            "The analysis cache entry was unavailable; recomputation was used safely.",
            severity="warning",
            translation_unit_id=scope,
        )

    @staticmethod
    def _ir_identity_error(
        bundle: Mapping[str, Any], artifact_plan: ArtifactPlan, member: Any
    ) -> Diagnostic | None:
        artifact = bundle.get("artifact")
        module = bundle.get("module")
        producer = bundle.get("producer")
        expected = {
            "artifact_set_id": artifact_plan.artifact_set_id,
            "artifact_key": member.artifact_key,
            "physical_tu_id": member.document["physical_tu_id"],
            "context_id": member.context_id,
            "profile": member.profile,
            "sha256": member.document["sha256"],
        }
        if not isinstance(artifact, Mapping) or any(
            artifact.get(key) != value for key, value in expected.items()
        ):
            return diagnostic(
                "ir_fact_artifact_identity_mismatch",
                "ir_validation",
                "LLVM facts 与请求的 BC 成员身份不一致。",
                "LLVM facts do not match the requested bitcode member identity.",
                translation_unit_id=member.context_id,
            )
        expected_versions = {
            "llvm_version": LLVM_VERSION,
            "reader_version": LLVM_READER_VERSION,
            "extractor_version": LLVM_EXTRACTOR_VERSION,
            "worker_protocol_version": LLVM_WORKER_PROTOCOL_VERSION,
        }
        if not isinstance(producer, Mapping) or any(
            producer.get(key) != value for key, value in expected_versions.items()
        ):
            return diagnostic(
                "ir_fact_producer_version_mismatch",
                "ir_validation",
                "LLVM facts 的 reader/extractor 版本与扫描器不一致。",
                "LLVM facts reader/extractor versions do not match the scanner.",
                translation_unit_id=member.context_id,
            )
        if isinstance(module, Mapping):
            target = member.document.get("target")
            data_layout = member.document.get("data_layout")
            if (target and module.get("target_triple") != target) or (
                data_layout and module.get("data_layout") != data_layout
            ):
                return diagnostic(
                    "ir_module_identity_mismatch",
                    "ir_validation",
                    "LLVM module 的 target 或 data layout 与 manifest 不一致。",
                    "LLVM module target or data layout does not match the manifest.",
                    translation_unit_id=member.context_id,
                )
        return None

    @staticmethod
    def _result(
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
        artifact_plan: ArtifactPlan | None = None,
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
        description_context: tuple[Mapping[str, Any], ...] | list[dict[str, Any]] = (),
    ) -> dict[str, Any]:
        return project_result_v2(
            request_id,
            status,
            planned,
            completed,
            failed,
            unknown,
            facts,
            diagnostics,
            source_profile,
            target_profile,
            ir_facts=ir_facts,
            evidence=evidence,
            artifact_plan=artifact_plan,
            layers=layers,
            source_mapping_mode=source_mapping_mode,
            rule_run=rule_run,
            enabled_rules=enabled_rules,
            source_build_context=source_build_context,
            target_build_context=target_build_context,
            suppression_records=suppression_records,
            request_schema_version=request_schema_version,
            source_revision=source_revision,
            policy_as_of=policy_as_of,
            budget_snapshot=budget_snapshot,
            cache_mode=cache_mode,
            cache_stats=cache_stats,
            diagnostic_limit=diagnostic_limit,
            description_context=description_context,
        )
