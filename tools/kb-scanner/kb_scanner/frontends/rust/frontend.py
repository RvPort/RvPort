"""Rust frontend：把 .rs 文件解析为架构相关证据（tree-sitter-rust AST）。

检测点（规格源自 rvport-skills/riscv-scan solver_rust，按 kb-scanner 合同重写）：
1. `#[cfg(...)]` 属性 → preprocessor_condition 证据，metadata 携带三值求值
   结果（cfgValue: true/false/null + cfgTargetArch），unknown 不二值化；
2. `asm!`/`global_asm!`/`naked_asm!` 宏体 → 宏体内逐语句 inline_asm 证据
   （复用 asm frontend 的 _scan_lines 词法器）；
3. 调用表达式 → call_expression 证据（函数名命中知识词表由检测器判定）；
4. 类型引用（type_identifier）→ type_reference 证据；
5. `use std::arch::<arch>::*` / 路径调用 → include 证据（metadata 带模块名）。

预算：单文件 4MB、累计 64MB；语言开关门控（languages 不含 rust 时零产出）。
"""
from __future__ import annotations

import re

from ...core import CompletionStatus, Diagnostic, DiagnosticSeverity, FrontendSnapshot, ScanContext, StageResult, WorkspaceSnapshot
from ..asm.frontend import _scan_lines
from ..cpp.source_positions import SourceByteIndex
from .cfg_evaluator import evaluate_cfg
from .vocabulary import (
    MAX_FILE_BYTES,
    MAX_TOTAL_BYTES,
    PRODUCER,
    is_rust_path,
    make_rust_evidence,
    make_rust_file_entity,
)

_CFG_RE = re.compile(r"#\[\s*cfg\s*\((.*?)\)\s*\]", re.DOTALL)
_ASM_MACRO_RE = re.compile(r"\b(?:asm|global_asm|naked_asm)!\s*\(")
_ARCH_USE_RE = re.compile(r"^use\s+(?:std|core)::arch::([A-Za-z0-9_]+)")


class RustFrontend:
    """Rust 源 frontend：流水线 frontend 阶段的第四输入域。"""

    __slots__ = ("_parser", "_max_bytes")

    def __init__(self, parser=None, max_bytes: int = MAX_FILE_BYTES) -> None:
        if max_bytes < 1:
            raise ValueError("max_bytes must be positive")
        self._parser = parser
        self._max_bytes = max_bytes

    def analyze(self, context: ScanContext, workspace: WorkspaceSnapshot) -> StageResult[FrontendSnapshot]:
        if "rust" not in set(context.languages):
            return StageResult(FrontendSnapshot((), ()))
        if self._parser is None:
            self._parser = _build_parser()
        entities: dict[str, object] = {}
        evidence: dict[str, object] = {}
        diagnostics: tuple[Diagnostic, ...] = ()
        partial = False
        total_bytes = 0
        for relative in sorted(workspace.files):
            if not is_rust_path(relative):
                continue
            entity = make_rust_file_entity(relative, context.scanner_version)
            entities[entity.id] = entity
            path = workspace.root / relative
            try:
                path.resolve().relative_to(workspace.root.resolve())
                size = path.stat().st_size
                if size > self._max_bytes or total_bytes + size > MAX_TOTAL_BYTES:
                    raise ValueError("rust input exceeds size budget")
                source = path.read_bytes()
                total_bytes += len(source)
            except (OSError, UnicodeError, ValueError, TypeError) as exc:
                diagnostics += (_diagnostic("rust-frontend.input-partial",
                    f"Rust input unavailable: {relative} ({type(exc).__name__})", relative),)
                partial = True
                continue
            tree = self._parser.parse(source)
            if tree.root_node.has_error:
                diagnostics += (_diagnostic("rust-frontend.partial-parse",
                    f"Rust input parsed with errors: {relative}", relative),)
                partial = True
            index = SourceByteIndex.build(relative, source)
            for item in _walk_collect(tree.root_node, source, entity.id, relative, index):
                evidence[item.id] = item
        return StageResult(
            FrontendSnapshot(tuple(entities.values()), tuple(evidence.values())),
            CompletionStatus.PARTIAL if partial else CompletionStatus.COMPLETE,
            diagnostics,
        )


def _walk_collect(root, source: bytes, subject_id: str, path: str, index: SourceByteIndex):
    """栈式先序遍历：按节点类型产出证据（不递归，防深栈）。"""
    stack = [root]
    while stack:
        node = stack.pop()
        for item in _emit(node, source, subject_id, path, index):
            yield item
        # 逆序压栈保证先序遍历顺序确定。
        for child in reversed(node.named_children):
            stack.append(child)


def _emit(node, source: bytes, subject_id: str, path: str, index: SourceByteIndex):
    """按节点类型产证据：cfg 属性 / asm 宏 / 调用 / 类型引用 / arch use。"""
    node_type = node.type
    text = source[node.start_byte:node.end_byte].decode("utf-8", errors="replace")
    if node_type == "attribute_item":
        match = _CFG_RE.search(text)
        if match is not None:
            expression = match.group(1)
            evaluation = evaluate_cfg(expression)
            yield make_rust_evidence(
                subject_id=subject_id, path=path, span=index.span(node.start_byte, node.end_byte),
                kind="preprocessor_condition", value=expression,
                metadata={
                    "nodeType": "rust_cfg",
                    "cfgValue": "true" if evaluation.value is True else "false" if evaluation.value is False else "null",
                    "cfgTargetArch": evaluation.target_arch or "",
                },
            )
        return
    if node_type in {"macro_invocation", "macro_definition"} and _ASM_MACRO_RE.search(text):
        # asm! 宏体逐语句转 inline_asm：先提取括号内参数文本（字符串字面量
        # 的汇编源），再复用 asm frontend 的词法器（注释/标签/预处理剥离一致）。
        body = _extract_macro_body(text)
        if body:
            body_offset = node.start_byte + text.encode("utf-8").find(body[:min(32, len(body))].encode("utf-8", errors="replace"))
            for line_index, line in enumerate(body.splitlines()):
                for statement in _asm_statements(line):
                    start = body_offset + _byte_offset_of_line(body, line_index)
                    yield make_rust_evidence(
                        subject_id=subject_id, path=path,
                        span=index.span(start, start + len(statement.encode("utf-8"))),
                        kind="inline_asm", value=statement,
                        metadata={"nodeType": "rust_asm_macro"},
                    )
        return
    if node_type == "call_expression":
        function = node.child_by_field_name("function")
        name = source[function.start_byte:function.end_byte].decode("utf-8", errors="replace") if function is not None else text
        yield make_rust_evidence(
            subject_id=subject_id, path=path, span=index.span(node.start_byte, node.end_byte),
            kind="call_expression", value=name[:512],
        )
        return
    if node_type == "type_identifier":
        yield make_rust_evidence(
            subject_id=subject_id, path=path, span=index.span(node.start_byte, node.end_byte),
            kind="type_reference", value=text[:512],
        )
        return
    if node_type == "use_declaration":
        match = _ARCH_USE_RE.match(text.strip())
        if match is not None:
            yield make_rust_evidence(
                subject_id=subject_id, path=path, span=index.span(node.start_byte, node.end_byte),
                kind="include", value=f"std::arch::{match.group(1)}",
                metadata={"nodeType": "rust_arch_use", "archModule": match.group(1)},
            )
        return
    return


def _extract_macro_body(text: str) -> str:
    """提取 asm! 宏第一个字符串字面量的内容（汇编源文本）。

    形态：asm!("insn...", options...) —— 取第一对引号之间的文本；字符串
    拼接（"a" "b"）或非字符串首参（global_asm!(label)）返回空（不产证据，
    保守——拼接语义需编译器知识）。
    """
    match = re.search(r'"((?:[^"\\]|\\.)*)"', text, re.DOTALL)
    return match.group(1).replace("\\n", "\n").replace("\\t", " ") if match else ""


def _asm_statements(line: str) -> list[str]:
    """单行内的汇编语句（按 ; 分隔的指令串；空段丢弃）。"""
    return [part.strip() for part in line.split(";") if part.strip()]


def _byte_offset_of_line(text: str, line_index: int) -> int:
    """宏体文本内第 line_index 行（0-based）的起始字节偏移。"""
    offset = 0
    for _ in range(line_index):
        position = text.find("\n", offset)
        if position < 0:
            return len(text.encode("utf-8"))
        offset = position + 1
    return len(text[:offset].encode("utf-8"))


def _build_parser():
    """tree-sitter 0.26 新式初始化（与 cpp adapter 一致）。"""
    from tree_sitter import Language, Parser
    import tree_sitter_rust

    return Parser(Language(tree_sitter_rust.language()))


def _diagnostic(code: str, message: str, path: str) -> Diagnostic:
    return Diagnostic(code, "frontend.rust", DiagnosticSeverity.WARNING, message, producer=PRODUCER,
                      details={"path": path})
