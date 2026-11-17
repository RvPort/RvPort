from __future__ import annotations

from ...core import CompletionStatus, Diagnostic, DiagnosticSeverity, StageResult
from .chain import EvidenceChainBuilder
from .context import SourceContextProjector
from .diagnostics import V2DiagnosticProjector
from .index import SnapshotIndex
from .mapper import V2FindingMapper
from .models import KnowledgeSourceView, Project, Scan, SchemaV2Report, Scope, Tool, stable_view_id
from .renderers import render_html, render_html_chunks, render_json, render_json_chunks, render_log
from .summary import V2SummaryReducer
from .validation import V2ReferentialIntegrityValidator
from ..publisher import AtomicArtifactPublisher
from ...core.candidate_contract import CandidateAdmissionError, validate_candidates
from .families import FamilyProjection
from .multiversion import MultiversionProjection
from .candidate_usage import CandidateUsageProjection


class SchemaV2ReportProjector:
    def __init__(self, publisher: AtomicArtifactPublisher | None = None) -> None:
        self._publisher = publisher or AtomicArtifactPublisher()

    def project(self, context, workspace, knowledge, frontend, facts, analysis, detections, classifications, assessments, diagnostics):
        try:
            validate_candidates(detections, classifications)
            source_context = SourceContextProjector(context.input_root)
            index = SnapshotIndex.build(knowledge, frontend, facts, analysis, detections, classifications, assessments)
            chain = EvidenceChainBuilder(index, knowledge, source_context, architecture_family=context.source_arch)
            usage = CandidateUsageProjection(index, source_context)
            findings = V2FindingMapper(index, chain, source_context, usage, architecture_family=context.source_arch).map(classifications)
            for finding in findings:
                finding.conclusion.require_bilingual_suggestions()
            projected_diagnostics = V2DiagnosticProjector(source_context).project(tuple(diagnostics))
            input_complete = not any(item.affects_exit_code for item in diagnostics)
            summary = V2SummaryReducer(architecture_family=context.source_arch).reduce(workspace, findings, assessments, input_complete=input_complete)
            sources = tuple(sorted((KnowledgeSourceView(id=item.source_id, kind="curated" if "curated" in item.kind else "collected" if "collected" in item.kind else "compiler-derived", sha256=item.sha256) for item in knowledge.sources), key=lambda item: item.id))
            report = SchemaV2Report(
                scan=Scan(id=stable_view_id("scan", context.knowledge_id, workspace.files, context.target_arch), tool=Tool(version=context.scanner_version), scope=Scope(domain=f"c-cpp-{context.source_arch}-to-riscv", targetArchitecture=context.target_arch), knowledgeSources=sources),
                project=Project(id=stable_view_id("project", context.input_root.name), name=context.input_root.name or "project", filesAnalyzed=len(workspace.files), status=summary.status, reasonCodes=summary.reasonCodes),
                summary=summary, findings=findings, diagnostics=projected_diagnostics,
                extensions={**(FamilyProjection(analysis.entities).report() or {}),
                    **MultiversionProjection(analysis.entities, detections.observations, source_context).report(),
                    **usage.report()} or None,
            )
            V2ReferentialIntegrityValidator().validate(report)
            rendered = {
                "scan-result.json": render_json(report) if len(findings) < 256 else render_json_chunks(report),
                "scan-report.html": render_html(report) if len(findings) < 256 else render_html_chunks(report),
                "scan.log": render_log(report),
            }
            return StageResult(self._publisher.publish(context.output_dir, rendered))
        except Exception as exc:  # noqa: BLE001 - projection is the artifact boundary
            diagnostic = Diagnostic(
                "projection.invalid-candidate-admission" if isinstance(exc, CandidateAdmissionError) else "projection.v2-failed", "projector", DiagnosticSeverity.ERROR,
                f"Schema v2 report projection failed: {type(exc).__name__}: {exc}",
                producer="schema-v2-report-projector", affects_exit_code=True,
            )
            return StageResult(None, CompletionStatus.FAILED, (diagnostic,))
