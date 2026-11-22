"""Go frontend：把 .go 文件解析为架构相关证据（tree-sitter-go AST）。

检测点（规格源自 rvport-skills/riscv-scan solver_go，按 kb-scanner 合同重写）：
1. 文件头 `//go:build` 注释 → preprocessor_condition 证据，metadata 携带
   真表达式三值求值（riscv-scan 用前瞻正则近似 &&/||/! 语义——我们解析）；
2. `runtime.GOARCH == "amd64"` 比较（if 条件/switch tag/case 表达式）→
   selection_condition 证据（metadata 带 goarchValue 与比较形态）；
3. cgo 注释块：`import "C"` 紧邻上方的连续 // 注释文本逐行转 C 检测链
   证据（include 行 → include；含 C intrinsic/asm 的行 → inline_asm/
   call_expression——委托 C solver 的语义按复用链实现）。

预算：单文件 4MB、累计 64MB；语言开关门控（languages 不含 go 时零产出）。
"""
from __future__ import annotations

import re

from ...core import CompletionStatus, Diagnostic, DiagnosticSeverity, FrontendSnapshot, ScanContext, StageResult, WorkspaceSnapshot
from ..asm.frontend import _scan_lines
from ..cpp.source_positions import SourceByteIndex
from .build_tag import evaluate_build_tag
from .vocabulary import (
    MAX_FILE_BYTES,
    MAX_TOTAL_BYTES,
    PRODUCER,
    is_go_path,
    make_go_evidence,
    make_go_file_entity,
)

_BUILD_TAG_RE = re.compile(r"^//go:build\s+(.+)$", re.MULTILINE)
_GOARCH_CMP_RE = re.compile(r'runtime\.GOARCH\s*(==|!=)\s*"([A-Za-z0-9_]+)"')
_RISCV_ARCHS = {"riscv64", "riscv"}
# cgo 注释块里的 C 行：#include 之外的语句按 C 词法扫描（命中 _mm_* 等由
# 检测器判），#include <...> 行产 include 证据。
_C_INCLUDE_RE = re.compile(r'^\s*#\s*include\s*[<"]')


class GoFrontend:
    """Go 源 frontend：流水线 frontend 阶段的第五输入域。"""

    __slots__ = ("_parser", "_max_bytes")

    def __init__(self, parser=None, max_bytes: int = MAX_FILE_BYTES) -> None:
        if max_bytes < 1:
            raise ValueError("max_bytes must be positive")
        self._parser = parser
        self._max_bytes = max_bytes

    def analyze(self, context: ScanContext, workspace: WorkspaceSnapshot) -> StageResult[FrontendSnapshot]:
        if "go" not in set(context.languages):
            return StageResult(FrontendSnapshot((), ()))
        if self._parser is None:
            self._parser = _build_parser()
        entities: dict[str, object] = {}
        evidence: dict[str, object] = {}
        diagnostics: tuple[Diagnostic, ...] = ()
        partial = False
        total_bytes = 0
        for relative in sorted(workspace.files):
            if not is_go_path(relative):
                continue
            entity = make_go_file_entity(relative, context.scanner_version)
            entities[entity.id] = entity
            path = workspace.root / relative
            try:
                path.resolve().relative_to(workspace.root.resolve())
                size = path.stat().st_size
                if size > self._max_bytes or total_bytes + size > MAX_TOTAL_BYTES:
                    raise ValueError("go input exceeds size budget")
                source = path.read_bytes()
                total_bytes += len(source)
            except (OSError, UnicodeError, ValueError, TypeError) as exc:
                diagnostics += (_diagnostic("go-frontend.input-partial",
                    f"Go input unavailable: {relative} ({type(exc).__name__})", relative),)
                partial = True
                continue
            tree = self._parser.parse(source)
            if tree.root_node.has_error:
                diagnostics += (_diagnostic("go-frontend.partial-parse",
                    f"Go input parsed with errors: {relative}", relative),)
                partial = True
            index = SourceByteIndex.build(relative, source)
            text = source.decode("utf-8", errors="replace")
            for item in _collect(tree.root_node, source, text, entity.id, relative, index):
                evidence[item.id] = item
        return StageResult(
            FrontendSnapshot(tuple(entities.values()), tuple(evidence.values())),
            CompletionStatus.PARTIAL if partial else CompletionStatus.COMPLETE,
            diagnostics,
        )


def _collect(root, source: bytes, text: str, subject_id: str, path: str, index: SourceByteIndex):
    """一次遍历产出全部证据：build tag（全文注释头）+ AST 节点 + cgo 块。"""
    yield from _build_tag_evidence(text, subject_id, path, index)
    stack = [root]
    cgo_blocks = []
    while stack:
        node = stack.pop()
        if node.type == "import_declaration" and b'"C"' in source[node.start_byte:node.end_byte]:
            cgo_blocks.append(_cgo_comments(node, source))
        elif node.type in {"binary_expression", "expression_case", "expression_switch_statement"}:
            yield from _goarch_evidence(node, source, subject_id, path, index)
        for child in reversed(node.named_children):
            stack.append(child)
    for comment_nodes in cgo_blocks:
        yield from _cgo_evidence(comment_nodes, subject_id, path, index)


def _build_tag_evidence(text: str, subject_id: str, path: str, index: SourceByteIndex):
    """文件头 //go:build 约束：真表达式求值（&&/||/!，三值）。"""
    match = _BUILD_TAG_RE.search(text)
    if match is None:
        return
    expression = match.group(1).strip()
    value = evaluate_build_tag(expression)
    tag = "true" if value is True else "false" if value is False else "null"
    start = text[:match.start()].count("\n") + 1
    first_line_offset = len(text[:match.start()].encode("utf-8"))
    end_offset = first_line_offset + len(match.group(0).encode("utf-8"))
    yield make_go_evidence(
        subject_id=subject_id, path=path, span=index.span(first_line_offset, end_offset),
        kind="preprocessor_condition", value=expression,
        metadata={"nodeType": "go_build_tag", "tagValue": tag},
    )


def _goarch_evidence(node, source: bytes, subject_id: str, path: str, index: SourceByteIndex):
    """runtime.GOARCH 比较/switch：产 selection_condition（架构闭集二值）。"""
    text = source[node.start_byte:node.end_byte].decode("utf-8", errors="replace")
    match = _GOARCH_CMP_RE.search(text)
    if match is None:
        return
    operator, arch = match.group(1), match.group(2)
    supports = arch in _RISCV_ARCHS
    if operator == "!=":
        supports = not supports
    yield make_go_evidence(
        subject_id=subject_id, path=path, span=index.span(node.start_byte, node.end_byte),
        kind="selection_condition", value=f"runtime.GOARCH {operator} \"{arch}\"",
        metadata={"nodeType": "go_goarch_compare", "goarchValue": arch,
                  "operator": operator, "supportsRiscv": "true" if supports else "false"},
    )


def _cgo_comments(import_node, source: bytes) -> list[tuple[int, str]]:
    """收集 import "C" 紧邻上方的连续注释（cgo C 代码块，// 与 /* */ 皆可）。

    返回 [(注释起始字节偏移, 注释体文本)]，正序；紧邻性由
    prev_named_sibling 链上的连续 comment 节点保证（Go cgo 惯例）。
    // 注释逐条登记；块注释整体登记（内部分行由 _cgo_evidence 处理）。
    """
    collected: list[tuple[int, str]] = []
    node = import_node.prev_named_sibling
    while node is not None and node.type == "comment":
        text = source[node.start_byte:node.end_byte].decode("utf-8", errors="replace")
        if text.startswith("//"):
            collected.append((node.start_byte, text[2:].lstrip()))
        elif text.startswith("/*") and text.rstrip().endswith("*/"):
            body = text[2:].rstrip()[:-2]
            collected.append((node.start_byte + 2, body))
        else:
            break
        node = node.prev_named_sibling
    return list(reversed(collected))


def _cgo_evidence(comment_entries, subject_id: str, path: str, index: SourceByteIndex):
    """cgo 注释块逐行转 C 检测链证据。

    - `#include` 行 → include 证据（C 头文件形态，复用 HEADER 检测语义）；
    - 其余含代码的行 → 复用 asm 词法器产 inline_asm（_mm_* 等由 ASM/调用
      检测器按词表判定——与 riscv-scan 把 C 代码串喂给 4 个 C solver 等价）。
    """
    for start_byte, chunk in comment_entries:
        # 块注释整段（含多行）与 // 单行统一：逐行处理，行内偏移累计。
        offset = start_byte
        for line in chunk.splitlines():
            line_bytes = len(line.encode("utf-8"))
            if _C_INCLUDE_RE.match(line):
                yield make_go_evidence(
                    subject_id=subject_id, path=path,
                    span=index.span(offset, offset + line_bytes),
                    kind="include", value=line.strip(),
                    metadata={"nodeType": "go_cgo_include"},
                )
            else:
                for item in _scan_lines(path, subject_id, [line]):
                    yield make_go_evidence(
                        subject_id=subject_id, path=path,
                        span=index.span(offset, offset + line_bytes),
                        kind="inline_asm", value=str(item.value),
                        metadata={"nodeType": "go_cgo_code"},
                    )
            offset += line_bytes + 1


def _build_parser():
    """tree-sitter 0.26 新式初始化。"""
    from tree_sitter import Language, Parser
    import tree_sitter_go

    return Parser(Language(tree_sitter_go.language()))


def _diagnostic(code: str, message: str, path: str) -> Diagnostic:
    return Diagnostic(code, "frontend.go", DiagnosticSeverity.WARNING, message, producer=PRODUCER,
                      details={"path": path})
