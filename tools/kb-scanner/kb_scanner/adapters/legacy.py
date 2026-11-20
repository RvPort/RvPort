"""冻结的旧版扫描路径，仅作为测试 oracle 保留（FROZEN，勿改）。

本模块封装 kb-scanner 重构前的 解析→规则→报告 单体路径，供测试比对
新旧实现的一致性；生产扫描路径不加载它（含 PyYAML 依赖）。
除注释外不得改动任何逻辑——它是回归比对的基准。
"""

from __future__ import annotations

import argparse
import hashlib
from dataclasses import dataclass
from pathlib import Path

import yaml

from .. import __version__
from ..compile_commands import load_compile_commands
from ..core.context import CompilationUnitContext, ScanContext, ScanRequest
from ..core.diagnostics import Diagnostic, DiagnosticSeverity, merge_diagnostics
from ..core.results import ArtifactRef, CompletionStatus, PipelineResult, StageResult
from ..cpp_frontend import parse_project
from ..models import CompileCommandContext, Finding, ProjectFacts
from ..pipeline import ApplicationProfile, ScanApplication
from ..report import write_reports
from ..rules import evaluate_rules, load_generated_entries, load_rules


def scan_request_from_namespace(args: argparse.Namespace) -> ScanRequest:
    """Normalize the existing scan CLI arguments without changing their defaults."""

    return ScanRequest(
        input_path=args.input,
        rules_path=args.rules,
        output_path=args.output,
        languages=_split_csv(args.language),
        excludes=_split_csv(args.exclude),
        exclude_paths=tuple(item.strip() for item in args.exclude_path if item.strip()),
        formats=_split_csv(args.format),
        context_infer=args.context_infer,
        target_arch=args.target_arch,
        include_inactive_branches=args.include_inactive_branches,
        include_comment_hints=not args.no_comment_hints,
        compile_commands_path=args.compile_commands,
        locale=args.locale,
    )


class LegacyContextFactory:
    """Build the immutable application context from the legacy CLI contract."""

    __slots__ = ()

    def build(self, request: ScanRequest) -> StageResult[ScanContext]:
        root = Path(request.input_path).resolve()
        rule_dir = Path(request.rules_path).resolve()
        output_dir = Path(request.output_path).resolve()
        candidate = (
            Path(request.compile_commands_path).resolve()
            if request.compile_commands_path
            else root / "compile_commands.json"
        )
        compile_commands_path = candidate if candidate.exists() else None
        contexts, messages = load_compile_commands(compile_commands_path)
        compile_contexts = tuple(_to_core_compile_context(item) for item in contexts.values())

        scan_mode = "source_context_with_compile_database" if compile_commands_path and contexts else "source_context"
        if request.context_infer == "off" and scan_mode == "source_context":
            scan_mode = "syntax_scan"

        manifest = _load_manifest(rule_dir)
        rule_pack_id = str(manifest.get("id", rule_dir.name))
        rule_pack_version = str(manifest.get("version", "unknown"))
        diagnostics = tuple(
            Diagnostic(
                code="legacy.compile-context",
                stage="compile-context",
                severity=DiagnosticSeverity.WARNING,
                message=message,
                producer="legacy-context-factory",
            )
            for message in messages
        )
        status = CompletionStatus.PARTIAL if diagnostics else CompletionStatus.COMPLETE
        return StageResult(
            value=ScanContext(
                input_root=root,
                output_dir=output_dir,
                rule_dir=rule_dir,
                languages=request.languages,
                excludes=request.excludes,
                exclude_paths=request.exclude_paths,
                formats=request.formats,
                context_infer=request.context_infer,
                target_arch=request.target_arch,
                include_inactive_branches=request.include_inactive_branches,
                include_comment_hints=request.include_comment_hints,
                locale=request.locale,
                compile_commands_path=compile_commands_path,
                compile_contexts=compile_contexts,
                scan_mode=scan_mode,
                rule_pack_id=rule_pack_id,
                rule_pack_version=rule_pack_version,
                knowledge_id=f"{rule_pack_id}@{rule_pack_version}",
                scanner_name="kb-scanner",
                scanner_version=__version__,
            ),
            status=status,
            diagnostics=diagnostics,
        )


@dataclass(slots=True)
class _LegacyRunState:
    project: ProjectFacts
    findings: list[Finding]


class _LegacyReportProjectorAdapter:
    __slots__ = ()

    def project(self, context: ScanContext, state: _LegacyRunState) -> StageResult[tuple[ArtifactRef, ...]]:
        write_reports(
            project=state.project,
            findings=state.findings,
            output_dir=context.output_dir,
            formats=list(context.formats),
            rule_pack_id=context.rule_pack_id,
            rule_pack_version=context.rule_pack_version,
        )
        artifact_names = []
        if "json" in context.formats:
            artifact_names.append("scan-result.json")
        if "html" in context.formats:
            artifact_names.append("scan-report.html")
        artifact_names.append("scan.log")
        artifacts = tuple(_artifact_ref(context.output_dir / name) for name in artifact_names)
        return StageResult(value=artifacts)


class LegacyPipelineRunner:
    """Run the existing parser/rule/report path behind the new application boundary."""

    __slots__ = ("_projector",)

    def __init__(self, projector: _LegacyReportProjectorAdapter | None = None) -> None:
        self._projector = projector or _LegacyReportProjectorAdapter()

    def run(self, context: ScanContext, initial_diagnostics: tuple[Diagnostic, ...] = ()) -> PipelineResult:
        try:
            compile_contexts = _to_legacy_compile_contexts(context.compile_contexts)
            rules, rule_messages = load_rules(context.rule_dir)
            generated_entries = load_generated_entries(context.rule_dir)
            project = parse_project(
                root=context.input_root,
                excludes=list(context.excludes),
                exclude_paths=list(context.exclude_paths),
                scan_mode=context.scan_mode,
                compile_contexts=compile_contexts,
                target_arch=context.target_arch,
                include_inactive_branches=context.include_inactive_branches,
                include_comment_hints=context.include_comment_hints,
            )
            project.compile_commands_path = context.compile_commands_path

            rule_diagnostics = tuple(
                Diagnostic(
                    code="legacy.rule-load",
                    stage="knowledge",
                    severity=DiagnosticSeverity.ERROR,
                    message=message,
                    producer="legacy-pipeline-runner",
                    affects_exit_code=True,
                )
                for message in rule_messages
            )
            context_diagnostic = Diagnostic(
                code="legacy.scan-context",
                stage="context",
                severity=DiagnosticSeverity.INFO,
                message=_context_message(context),
                producer="legacy-pipeline-runner",
            )
            diagnostics = merge_diagnostics(initial_diagnostics, rule_diagnostics, (context_diagnostic,))
            project.diagnostics.extend(item.message for item in initial_diagnostics)
            project.diagnostics.extend(rule_messages)
            project.diagnostics.append(context_diagnostic.message)

            findings = evaluate_rules(
                project,
                rules,
                locale=context.locale,
                generated_entries=generated_entries,
            )
            projection = self._projector.project(context, _LegacyRunState(project=project, findings=findings))
            diagnostics = merge_diagnostics(diagnostics, projection.diagnostics)
            if projection.status is CompletionStatus.FAILED or projection.value is None:
                return PipelineResult(
                    success=False,
                    exit_code=2,
                    status=CompletionStatus.FAILED,
                    diagnostics=diagnostics,
                )

            exit_code = 1 if any(item.affects_exit_code for item in diagnostics) else 0
            status = CompletionStatus.PARTIAL if diagnostics[:-1] else CompletionStatus.COMPLETE
            return PipelineResult(
                success=exit_code == 0,
                exit_code=exit_code,
                status=status,
                diagnostics=diagnostics,
                artifacts=projection.value,
            )
        except Exception as exc:  # noqa: BLE001 - application boundary converts unexpected failures
            fatal = Diagnostic(
                code="legacy.internal-error",
                stage="legacy-runner",
                severity=DiagnosticSeverity.FATAL,
                message=f"KB Scanner 内部错误: {exc}",
                producer="legacy-pipeline-runner",
                affects_exit_code=True,
            )
            return PipelineResult(
                success=False,
                exit_code=2,
                status=CompletionStatus.FAILED,
                diagnostics=merge_diagnostics(initial_diagnostics, (fatal,)),
            )


def create_legacy_application() -> ScanApplication:
    """组装旧版扫描应用（上下文工厂 + 旧版运行器），供测试 oracle 使用。"""
    return ScanApplication(ApplicationProfile(context_factory=LegacyContextFactory(), runner=LegacyPipelineRunner()))


def _split_csv(value: str) -> tuple[str, ...]:
    return tuple(item.strip() for item in value.split(",") if item.strip())


def _load_manifest(rule_dir: Path) -> dict[str, object]:
    path = rule_dir / "manifest.yaml"
    if not path.exists():
        return {}
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    return data if isinstance(data, dict) else {}


def _to_core_compile_context(context: CompileCommandContext) -> CompilationUnitContext:
    return CompilationUnitContext(
        path=context.path,
        include_dirs=tuple(context.include_dirs),
        defines=tuple(context.defines.items()),
        language_standard=context.language_standard,
        raw_command=context.raw_command,
        tokens=tuple(context.tokens),
        entry_index=context.entry_index,
        compiler=context.compiler,
        directory=context.directory,
        diagnostics=tuple(context.diagnostics),
    )


def _to_legacy_compile_contexts(
    contexts: tuple[CompilationUnitContext, ...],
) -> dict[str, CompileCommandContext]:
    ordered = sorted(contexts, key=lambda item: item.entry_index)
    return {
        str(item.path): CompileCommandContext(
            path=item.path,
            include_dirs=list(item.include_dirs),
            defines=dict(item.defines),
            language_standard=item.language_standard,
            raw_command=item.raw_command,
            tokens=list(item.tokens),
            entry_index=item.entry_index,
            compiler=item.compiler,
            directory=item.directory,
            diagnostics=list(item.diagnostics),
        )
        for item in ordered
    }


def _context_message(context: ScanContext) -> str:
    if context.compile_commands_path and context.compile_contexts:
        return "已使用源码上下文推导，并使用 compile_commands.json 作为可选增强输入。"
    if context.compile_commands_path and not context.compile_contexts:
        return "compile_commands.json 不可用，已继续使用源码上下文推导。"
    return "未提供 compile_commands.json，已使用源码上下文推导完成扫描。"


def _artifact_ref(path: Path) -> ArtifactRef:
    return ArtifactRef(name=path.name, path=path, sha256=hashlib.sha256(path.read_bytes()).hexdigest())
