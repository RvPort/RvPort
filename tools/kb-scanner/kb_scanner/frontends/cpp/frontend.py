"""C/C++ frontend 阶段的编排器：workspace 文件 → FrontendSnapshot。

kb-scanner 八阶段流水线的第三阶段（workspace→knowledge→frontend→…）：
对 workspace 收集到的每个源文件跑 tree-sitter 基线解析，再可选地用
libclang 做富化（enrichment），全程施加记录预算，最终产出排序后的
实体与证据快照。任何文件失败则整体 FAILED；libclang 不可用只降级
为 PARTIAL，不阻断基线。
"""
from __future__ import annotations

from pathlib import Path

from ...core import (
    CompletionStatus,
    Diagnostic,
    DiagnosticSeverity,
    FrontendSnapshot,
    ScanContext,
    StageResult,
    WorkspaceSnapshot,
    merge_diagnostics,
    normalize_project_path,
)
from .clang_adapter import ClangCppAdapter
from .tree_sitter_adapter import TreeSitterCppAdapter
from .vocabulary import entity_sort_key, evidence_sort_key, make_source_file_entity
from .workspace import language_candidates_for_path
from .usage_inventory import cap_records

# 累计源文本字节预算：与其他语言前端的 MAX_TOTAL_BYTES（64MB）对齐。
MAX_TOTAL_SOURCE_BYTES = 64 * 1024 * 1024
from .test_inventory import cap_records as cap_test_records


class CppFrontend:
    """frontend 阶段入口：组合 tree-sitter 基线与可选的 clang 富化。

    不变量：产出的每条 Evidence 的 subject_id 必须指向本快照内的实体，
    违反即整体 FAILED（防悬挂引用）；usage/test 记录按扫描级预算逐文件
    扣减，超限的记录被裁剪并降级为 partial。
    """

    __slots__ = ("_tree_sitter", "_clang", "_usage_record_limit")

    def __init__(
        self,
        tree_sitter: TreeSitterCppAdapter | None = None,
        clang: ClangCppAdapter | None = None,
        *, usage_record_limit: int = 100000,
    ) -> None:
        """clang 适配器可缺省（None 即跳过富化）；usage_record_limit 为扫描级记录总预算。"""
        self._tree_sitter = tree_sitter or TreeSitterCppAdapter()
        self._clang = clang
        if type(usage_record_limit) is not int or not 0 <= usage_record_limit <= 100000:
            raise ValueError("usage record limit must be between 0 and 100000")
        self._usage_record_limit = usage_record_limit

    def analyze(self, context: ScanContext, workspace: WorkspaceSnapshot, *, usage_selector=None) -> StageResult[FrontendSnapshot]:
        """对 workspace 内每个文件执行基线解析 + 可选富化，返回 FrontendSnapshot。

        流程：先为每个文件建 source_file 实体；逐文件跑 tree-sitter 并
        施加 usage/test 记录预算；若配置了 clang 适配器，则对每个编译
        上下文做富化（锚定基线函数名位置）；最后校验引用完整性并按
        确定顺序输出。任一文件 FAILED 即整体失败。
        """
        entities_by_path = {
            relative_path: make_source_file_entity(
                relative_path,
                language_candidates_for_path(relative_path, context.languages),
                context.scanner_version,
            )
            for relative_path in workspace.files
        }
        evidence_by_id = {}
        diagnostics: tuple[Diagnostic, ...] = ()
        overall_status = CompletionStatus.COMPLETE
        usage_remaining = self._usage_record_limit
        test_remaining = 100000
        total_source_bytes = 0

        for relative_path in workspace.files:
            # 累计源文本预算（64MB，与其他语言前端对齐）：超出后跳过剩余文件
            # 并降级 PARTIAL——防大仓库全量读入。
            source_path = (workspace.root / relative_path)
            try:
                file_size = source_path.stat().st_size
            except OSError:
                file_size = 0
            if total_source_bytes + file_size > MAX_TOTAL_SOURCE_BYTES:
                diagnostics = merge_diagnostics(diagnostics, (Diagnostic(
                    code="tree-sitter.total-bytes-budget",
                    stage="frontend",
                    severity=DiagnosticSeverity.WARNING,
                    message=f"Skipped remaining C/C++ inputs after {relative_path}: total source bytes budget {MAX_TOTAL_SOURCE_BYTES} exceeded",
                    producer="cpp-frontend",
                ),))
                overall_status = CompletionStatus.PARTIAL
                break
            total_source_bytes += file_size
            result = self._tree_sitter.analyze_file(
                context,
                workspace.root,
                relative_path,
                entities_by_path[relative_path],
                **({"usage_selector": usage_selector} if usage_selector is not None else {}),
                **({"test_record_limit": test_remaining} if test_remaining < 10000 else {}),
            )
            diagnostics = merge_diagnostics(diagnostics, result.diagnostics)
            if result.status is CompletionStatus.FAILED or result.value is None:
                return StageResult(value=None, status=CompletionStatus.FAILED, diagnostics=diagnostics)
            if result.status is CompletionStatus.PARTIAL:
                overall_status = CompletionStatus.PARTIAL
            for item in result.value:
                item, usage_remaining = cap_records(item, usage_remaining)
                item, test_remaining = cap_test_records(item, test_remaining)
                evidence_by_id[item.id] = item

        if self._clang is not None:
            # 汇总基线函数声明的 (path, line, column) 锚点，富化时只对精确命中的位置补语义。
            function_targets = {}
            for item in evidence_by_id.values():
                if item.kind == "function_declaration" and item.location is not None:
                    location = item.location.start
                    function_targets.setdefault(location.path, set()).add((location.line, location.column))
            for unit in context.compile_contexts:
                relative_path = _unit_relative_path(unit.path, workspace.root)
                if relative_path is None or relative_path not in entities_by_path:
                    continue
                options = {"function_targets": function_targets} if function_targets else {}
                result = self._clang.enrich(context, workspace, unit, entities_by_path, **options)
                diagnostics = merge_diagnostics(diagnostics, result.diagnostics)
                if result.status in {CompletionStatus.PARTIAL, CompletionStatus.UNAVAILABLE, CompletionStatus.FAILED}:
                    overall_status = CompletionStatus.PARTIAL
                if result.value is not None:
                    for item in result.value:
                        evidence_by_id[item.id] = item

        entity_ids = {item.id for item in entities_by_path.values()}
        # 完整性校验：Evidence 不得引用本快照之外的实体，违者为致命错误。
        invalid = [item for item in evidence_by_id.values() if item.subject_id not in entity_ids]
        if invalid:
            diagnostic = Diagnostic(
                code="frontend.invalid-subject-reference",
                stage="frontend",
                severity=DiagnosticSeverity.FATAL,
                message="Frontend produced Evidence with an unknown subject Entity",
                producer="cpp-frontend",
                details={"invalidReferenceCount": len(invalid)},
                affects_exit_code=True,
            )
            return StageResult(
                value=None,
                status=CompletionStatus.FAILED,
                diagnostics=merge_diagnostics(diagnostics, (diagnostic,)),
            )

        snapshot = FrontendSnapshot(
            entities=tuple(sorted(entities_by_path.values(), key=entity_sort_key)),
            evidence=tuple(sorted(evidence_by_id.values(), key=evidence_sort_key)),
        )
        return StageResult(value=snapshot, status=overall_status, diagnostics=diagnostics)


def _unit_relative_path(path: Path, workspace_root: Path) -> str | None:
    """把编译单元路径归一为工作区相对路径；落在工作区外返回 None。"""
    try:
        return normalize_project_path(path, workspace_root)
    except ValueError:
        return None
