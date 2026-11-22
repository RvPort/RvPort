"""Java frontend：把 .java 文件解析为 JNI/架构相关证据（tree-sitter-java AST）。

检测点（规格源自 rvport-skills/riscv-scan solver_java，按 kb-scanner 合同重写）：
1. `System.load("...")` / `System.loadLibrary("...")` / `Native.loadLibrary(...)`
   → call_expression 证据 + 库名实参单发 preprocessor kind（走
   ARCH_CONDITION_CANDIDATE 的 architecture_candidate 启发式——
   _ARCHITECTURE_TOKENS 词元正则识别 x86/amd64/sse 等，免词表）；
2. `Runtime.getRuntime().load[Library](...)` 链式 → 同上；
3. `rt.load[Library](...)` 变量链式（消误报）：先全文件收集
   `Runtime v = ... getRuntime()` 的赋值变量名，`v.load*` 只在收集集命中时
   产证（metadata 带 resolvedRuntimeVar=true）——与 riscv-scan 一致，不猜；
4. `System.getProperty("os.arch")` 比较 → selection_condition（架构闭集二值）。

预算：单文件 4MB、累计 64MB；语言开关门控（languages 不含 java 时零产出）。
"""
from __future__ import annotations

import re

from ...core import CompletionStatus, Diagnostic, DiagnosticSeverity, FrontendSnapshot, ScanContext, StageResult, WorkspaceSnapshot
from ..cpp.source_positions import SourceByteIndex
from .vocabulary import (
    MAX_FILE_BYTES,
    MAX_TOTAL_BYTES,
    PRODUCER,
    is_java_path,
    make_java_evidence,
    make_java_file_entity,
)

_LOAD_METHODS = {"load", "loadLibrary"}
_LIBRARY_RECEIVERS = {"System", "Native"}
# os.arch 取值闭集（JVM os.arch 属性的常见架构值）：riscv 系支持，其余不支持。
_RISCV_ARCHS = {"riscv64", "riscv32", "riscv"}
_STRING_LITERAL_RE = re.compile(r'"([A-Za-z0-9_./+\-]+)"')
_OPS_ARCH_CONTEXT_RE = re.compile(r"os\.arch|getProperty")


class JavaFrontend:
    """Java 源 frontend：流水线 frontend 阶段的第六输入域。"""

    __slots__ = ("_parser", "_max_bytes")

    def __init__(self, parser=None, max_bytes: int = MAX_FILE_BYTES) -> None:
        if max_bytes < 1:
            raise ValueError("max_bytes must be positive")
        self._parser = parser
        self._max_bytes = max_bytes

    def analyze(self, context: ScanContext, workspace: WorkspaceSnapshot) -> StageResult[FrontendSnapshot]:
        if "java" not in set(context.languages):
            return StageResult(FrontendSnapshot((), ()))
        if self._parser is None:
            self._parser = _build_parser()
        entities: dict[str, object] = {}
        evidence: dict[str, object] = {}
        diagnostics: tuple[Diagnostic, ...] = ()
        partial = False
        total_bytes = 0
        for relative in sorted(workspace.files):
            if not is_java_path(relative):
                continue
            entity = make_java_file_entity(relative, context.scanner_version)
            entities[entity.id] = entity
            path = workspace.root / relative
            try:
                path.resolve().relative_to(workspace.root.resolve())
                size = path.stat().st_size
                if size > self._max_bytes or total_bytes + size > MAX_TOTAL_BYTES:
                    raise ValueError("java input exceeds size budget")
                source = path.read_bytes()
                total_bytes += len(source)
            except (OSError, UnicodeError, ValueError, TypeError) as exc:
                diagnostics += (_diagnostic("java-frontend.input-partial",
                    f"Java input unavailable: {relative} ({type(exc).__name__})", relative),)
                partial = True
                continue
            tree = self._parser.parse(source)
            if tree.root_node.has_error:
                diagnostics += (_diagnostic("java-frontend.partial-parse",
                    f"Java input parsed with errors: {relative}", relative),)
                partial = True
            index = SourceByteIndex.build(relative, source)
            # 第一遍：收集 Runtime 实例变量名（全文件，不区分作用域——riscv-scan
            # 同款局限，文档化）；第二遍：按节点产证。
            runtime_vars = _collect_runtime_vars(tree.root_node, source)
            for item in _walk_collect(tree.root_node, source, entity.id, relative, index, runtime_vars):
                evidence[item.id] = item
        return StageResult(
            FrontendSnapshot(tuple(entities.values()), tuple(evidence.values())),
            CompletionStatus.PARTIAL if partial else CompletionStatus.COMPLETE,
            diagnostics,
        )


def _collect_runtime_vars(root, source: bytes) -> set[str]:
    """收集 `Runtime v = ...Runtime.getRuntime();` 形态的变量名（含字段声明）。"""
    names: set[str] = set()
    stack = [root]
    while stack:
        node = stack.pop()
        if node.type in {"local_variable_declaration", "field_declaration"}:
            text = source[node.start_byte:node.end_byte].decode("utf-8", errors="replace")
            if "Runtime" in text and "getRuntime()" in text:
                match = re.search(r"\b([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(?:\w+\s*\.\s*)?Runtime\s*\.\s*getRuntime\s*\(\s*\)", text)
                if match is not None:
                    names.add(match.group(1))
        for child in reversed(node.named_children):
            stack.append(child)
    return names


def _walk_collect(root, source: bytes, subject_id: str, path: str, index: SourceByteIndex, runtime_vars: set[str]):
    """栈式先序遍历：method_invocation 分派产证。"""
    stack = [root]
    while stack:
        node = stack.pop()
        if node.type == "method_invocation":
            for item in _emit_invocation(node, source, subject_id, path, index, runtime_vars):
                yield item
        for child in reversed(node.named_children):
            stack.append(child)


def _emit_invocation(node, source: bytes, subject_id: str, path: str, index: SourceByteIndex, runtime_vars: set[str]):
    """单个 method_invocation：JNI 加载与 os.arch 比较两类检测点。"""
    name_node = node.child_by_field_name("name")
    object_node = node.child_by_field_name("object")
    arguments = node.child_by_field_name("arguments")
    if name_node is None:
        return
    method = name_node.text.decode("utf-8", errors="replace")
    receiver = source[object_node.start_byte:object_node.end_byte].decode("utf-8", errors="replace") if object_node is not None else ""
    args_text = source[arguments.start_byte:arguments.end_byte].decode("utf-8", errors="replace") if arguments is not None else "()"
    invocation_text = source[node.start_byte:node.end_byte].decode("utf-8", errors="replace")

    jni = _jni_kind(method, receiver, runtime_vars)
    if jni is not None:
        # 调用本身 → call_expression；库名实参（首个字符串）单发 token 证据。
        yield make_java_evidence(
            subject_id=subject_id, path=path, span=index.span(node.start_byte, node.end_byte),
            kind="call_expression", value=invocation_text[:512],
            metadata={"nodeType": "java_jni_load", "jniForm": jni},
        )
        library = _first_string_argument(args_text)
        if library is not None:
            yield make_java_evidence(
                subject_id=subject_id, path=path, span=index.span(node.start_byte, node.end_byte),
                kind="preprocessor_condition", value=library,
                metadata={"nodeType": "java_native_library", "jniForm": jni},
            )
        return
    if _OPS_ARCH_CONTEXT_RE.search(invocation_text):
        arch = _arch_compare_value(invocation_text)
        if arch is not None:
            yield make_java_evidence(
                subject_id=subject_id, path=path, span=index.span(node.start_byte, node.end_byte),
                kind="selection_condition", value=invocation_text[:512],
                metadata={"nodeType": "java_os_arch_compare", "archValue": arch,
                          "supportsRiscv": "true" if arch in _RISCV_ARCHS else "false"},
            )


def _jni_kind(method: str, receiver: str, runtime_vars: set[str]) -> str | None:
    """判定 JNI 加载形态：standard / runtime_chain / runtime_var；非加载返回 None。

    消误报机制：裸变量接收者 `rt.load(...)` 只在 rt ∈ runtime_vars 时才算
    （未收集的变量不猜——可能是任意类型的 load 方法）。
    """
    if method not in _LOAD_METHODS:
        return None
    if receiver in _LIBRARY_RECEIVERS:
        return "standard" if receiver == "System" else "native_jna"
    if receiver.endswith("Runtime.getRuntime()"):
        return "runtime_chain"
    if receiver and "." not in receiver:
        return "runtime_var" if receiver in runtime_vars else None
    return None


def _first_string_argument(args_text: str) -> str | None:
    """提取实参列表里的首个字符串字面量（库名）。"""
    match = _STRING_LITERAL_RE.search(args_text)
    return match.group(1) if match is not None else None


def _arch_compare_value(text: str) -> str | None:
    """os.arch 上下文里被比较的架构字面量（如 "amd64".equals(os.arch)）。"""
    for match in _STRING_LITERAL_RE.finditer(text):
        value = match.group(1)
        if value != "os.arch":
            return value
    return None


def _build_parser():
    """tree-sitter 0.26 新式初始化。"""
    from tree_sitter import Language, Parser
    import tree_sitter_java

    return Parser(Language(tree_sitter_java.language()))


def _diagnostic(code: str, message: str, path: str) -> Diagnostic:
    return Diagnostic(code, "frontend.java", DiagnosticSeverity.WARNING, message, producer=PRODUCER,
                      details={"path": path})
