"""CLI 适配器：把 argparse 命名空间转换为扫描请求并构建扫描上下文。

StagedContextFactory 实现 core.protocols.ScanContextFactory，是 workspace
阶段之前的准备工作：校验输入目录与规则包、读取编译数据库
（compile_commands.json）、确定 scan_mode，最终产出 ScanContext。
"""

from __future__ import annotations

import argparse
from pathlib import Path

from .. import __version__
from ..bundle import BundleError, load_bundle_descriptor
from ..core import CompletionStatus, Diagnostic, DiagnosticSeverity, ScanContext, ScanRequest, StageResult
from ..frontends.cpp.compilation_database import CompilationDatabaseReader


def scan_request_from_namespace(args: argparse.Namespace) -> ScanRequest:
    """把 argparse 命名空间平铺转换为 frozen 的 ScanRequest。"""
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
        source_arch=getattr(args, "source_arch", "x86"),
        include_inactive_branches=args.include_inactive_branches,
        include_comment_hints=not args.no_comment_hints,
        compile_commands_path=args.compile_commands,
        locale=args.locale,
        schema_version=getattr(args, "schema_version", "2.0"),
    )


class StagedContextFactory:
    """扫描上下文工厂（实现 core.protocols.ScanContextFactory）。"""

    __slots__ = ("_database",)

    def __init__(self, database: CompilationDatabaseReader | None = None) -> None:
        self._database = database or CompilationDatabaseReader()

    def build(self, request: ScanRequest) -> StageResult[ScanContext]:
        """构建扫描上下文：校验输入/规则包，读取编译数据库并定 scan_mode。

        scan_mode 三态：有编译上下文→source_context_with_compile_database；
        无编译上下文→source_context；用户关闭上下文推断且无编译上下文→
        syntax_scan（纯语法扫描）。编译数据库缺失不阻断（仅诊断降级为
        partial），但输入目录/规则包无效直接 FAILED。
        """
        root = Path(request.input_path).resolve()
        rule_dir = Path(request.rules_path).resolve()
        output_dir = Path(request.output_path).resolve()
        if not root.is_dir():
            return _failed("context.input-invalid", "Input root must be an existing directory")
        try:
            bundle = load_bundle_descriptor(rule_dir)
        except BundleError as exc:
            return _failed("context.bundle-invalid", str(exc))
        candidate = Path(request.compile_commands_path).resolve() if request.compile_commands_path else root / "compile_commands.json"
        # 编译数据库缺省位置是输入根下的 compile_commands.json；
        # "compilation-database.missing" 诊断被过滤（缺失不算错误），其余诊断保留。
        database = self._database.read(candidate if candidate.exists() else None, root)
        contexts = database.value or ()
        diagnostics = tuple(
            item for item in database.diagnostics if item.code != "compilation-database.missing"
        )
        scan_mode = "source_context_with_compile_database" if contexts else "source_context"
        if request.context_infer == "off" and not contexts:
            scan_mode = "syntax_scan"
        status = CompletionStatus.PARTIAL if diagnostics else CompletionStatus.COMPLETE
        return StageResult(
            ScanContext(
                input_root=root,
                output_dir=output_dir,
                rule_dir=rule_dir,
                languages=request.languages,
                excludes=request.excludes,
                exclude_paths=request.exclude_paths,
                formats=request.formats,
                context_infer=request.context_infer,
                target_arch=request.target_arch,
                source_arch=request.source_arch,
                include_inactive_branches=request.include_inactive_branches,
                include_comment_hints=request.include_comment_hints,
                locale=request.locale,
                compile_commands_path=candidate if candidate.exists() else None,
                compile_contexts=contexts,
                scan_mode=scan_mode,
                rule_pack_id=bundle.rule_pack_id,
                rule_pack_version=bundle.rule_pack_version,
                knowledge_id=bundle.identity,
                scanner_name="kb-scanner",
                scanner_version=__version__,
                knowledge_manifest_name=bundle.knowledge_manifest_path.relative_to(rule_dir).as_posix(),
                schema_version=request.schema_version,
            ),
            status,
            diagnostics,
        )


def _split_csv(value: str) -> tuple[str, ...]:
    """拆分逗号分隔的 CLI 参数值（去空白、去空项）。"""
    return tuple(item.strip() for item in value.split(",") if item.strip())


def _failed(code: str, message: str) -> StageResult[ScanContext]:
    """构造上下文构建失败的 StageResult（ERROR 级、影响退出码）。"""
    return StageResult(
        None,
        CompletionStatus.FAILED,
        (Diagnostic(code, "context", DiagnosticSeverity.ERROR, message, producer="staged-context-factory", affects_exit_code=True),),
    )
