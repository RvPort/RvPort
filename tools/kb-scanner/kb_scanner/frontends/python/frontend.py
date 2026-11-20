"""Python/Cython frontend：ctypes/cffi 动态加载与内嵌 C 证据。

检测点（规格源自 rvport-skills/riscv-scan solver_python/solver_cython，
按 kb-scanner 合同重写）：
1. **四类 import 状态**（不产证）：import ctypes / from ctypes import CDLL /
   from ctypes import cdll / import cffi / from cffi import FFI（含 as 别名）；
2. **动态库加载调用**（参数非空才产证）：ctypes.CDLL("...") / cdll.LoadLibrary("...")
   / 直接 CDLL(...)（from-import 形态）/ FFI().dlopen(...) / ffi 实例 .dlopen(...)
   → call_expression + 库名实参单发 preprocessor_condition（ARCH_CONDITION_CANDIDATE
   启发式，词元正则识别 x86/amd64，免词表）；
3. **FFI 实例收集**：`ffi = FFI()` / `ffi = cffi.FFI()` 赋值扫描（不产证）；
4. **cdef/set_source 内嵌 C 代码**（字符串实参）：#include 行 → include、
   其余行 → inline_asm（复用 asm 词法器，行偏移回 Python 起始行——照 go cgo
   模式；riscv-scan 把字符串喂给 4 个 C solver，这里等价走检测链）；
5. **.pyx（Cython）**：无 tree-sitter-cython 包 → 行正则（# 注释跳过）：
   ctypes 调用形态 + from ctypes import *dll* 两检测点，kind 路由同上。

预算：单文件 4MB、累计 64MB；语言开关门控（languages 不含 python 时零产出）。
"""
from __future__ import annotations

import re

from ...core import CompletionStatus, Diagnostic, DiagnosticSeverity, FrontendSnapshot, ScanContext, StageResult, WorkspaceSnapshot
from ..asm.frontend import _scan_lines
from ..cpp.source_positions import SourceByteIndex
from .vocabulary import (
    MAX_FILE_BYTES,
    MAX_TOTAL_BYTES,
    PRODUCER,
    is_python_path,
    make_python_evidence,
    make_python_file_entity,
)

_STRING_RE = re.compile(r'"([^"\\]*(?:\\.[^"\\]*)*)"')
_C_INCLUDE_RE = re.compile(r'^\s*#\s*include\s*[<"]')
# .pyx 行正则（照 solver_cython 规格）：
PYX_CTYPES_CALL_RE = re.compile(r"\b(?:ctypes\.CDLL|ctypes\.cdll\.LoadLibrary|cdll\.LoadLibrary|cffi\.FFI\(\)\.dlopen)\s*\(")
PYX_DLL_IMPORT_RE = re.compile(r"\bfrom\s+ctypes\s+import\s+\w*[dD][lL][lL]\b")


class _ImportState:
    """一次文件的 import 状态机（四类 import + FFI 实例变量）。"""

    def __init__(self) -> None:
        self.ctypes_imported = False      # import ctypes / import ctypes as x
        self.cdll_imported = False        # from ctypes import cdll
        self.cdll_class_imported = False  # from ctypes import CDLL
        self.cffi_imported = False        # import cffi
        self.ffi_class_imported = False   # from cffi import FFI
        self.ffi_vars: set[str] = set()   # ffi = FFI() 实例变量名

    @property
    def any_import(self) -> bool:
        return self.ctypes_imported or self.cdll_imported or self.cdll_class_imported or self.cffi_imported or self.ffi_class_imported


class PythonFrontend:
    """Python/Cython 源 frontend：流水线 frontend 阶段的第七输入域。"""

    __slots__ = ("_parser", "_max_bytes")

    def __init__(self, parser=None, max_bytes: int = MAX_FILE_BYTES) -> None:
        if max_bytes < 1:
            raise ValueError("max_bytes must be positive")
        self._parser = parser
        self._max_bytes = max_bytes

    def analyze(self, context: ScanContext, workspace: WorkspaceSnapshot) -> StageResult[FrontendSnapshot]:
        if "python" not in set(context.languages):
            return StageResult(FrontendSnapshot((), ()))
        entities: dict[str, object] = {}
        evidence: dict[str, object] = {}
        diagnostics: tuple[Diagnostic, ...] = ()
        partial = False
        total_bytes = 0
        for relative in sorted(workspace.files):
            if not is_python_path(relative):
                continue
            entity = make_python_file_entity(relative, context.scanner_version)
            entities[entity.id] = entity
            path = workspace.root / relative
            try:
                path.resolve().relative_to(workspace.root.resolve())
                size = path.stat().st_size
                if size > self._max_bytes or total_bytes + size > MAX_TOTAL_BYTES:
                    raise ValueError("python input exceeds size budget")
                source = path.read_bytes()
                total_bytes += len(source)
            except (OSError, UnicodeError, ValueError, TypeError) as exc:
                diagnostics += (_diagnostic("python-frontend.input-partial",
                    f"Python input unavailable: {relative} ({type(exc).__name__})", relative),)
                partial = True
                continue
            if relative.lower().endswith(".pyx"):
                # Cython：行正则（无 grammar 包的降级路线）。
                for item in _scan_pyx(relative, source, entity.id):
                    evidence[item.id] = item
                continue
            if self._parser is None:
                self._parser = _build_parser()
            tree = self._parser.parse(source)
            if tree.root_node.has_error:
                diagnostics += (_diagnostic("python-frontend.partial-parse",
                    f"Python input parsed with errors: {relative}", relative),)
                partial = True
            index = SourceByteIndex.build(relative, source)
            for item in _collect(tree.root_node, source, entity.id, relative, index):
                evidence[item.id] = item
        return StageResult(
            FrontendSnapshot(tuple(entities.values()), tuple(evidence.values())),
            CompletionStatus.PARTIAL if partial else CompletionStatus.COMPLETE,
            diagnostics,
        )


def _collect(root, source: bytes, subject_id: str, path: str, index: SourceByteIndex):
    """两遍：先收集 import 状态与 FFI 实例变量，再按 call 节点产证。"""
    state = _ImportState()
    stack = [root]
    calls = []
    while stack:
        node = stack.pop()
        node_type = node.type
        if node_type in {"import_statement", "import_from_statement"}:
            _apply_import(state, source[node.start_byte:node.end_byte].decode("utf-8", errors="replace"))
        elif node_type == "assignment":
            _apply_assignment(state, source[node.start_byte:node.end_byte].decode("utf-8", errors="replace"))
        elif node_type == "call":
            calls.append(node)
        for child in reversed(node.named_children):
            stack.append(child)
    for node in sorted(calls, key=lambda n: n.start_byte):
        text = source[node.start_byte:node.end_byte].decode("utf-8", errors="replace")
        for item in _emit_call(node, text, state, subject_id, path, index, source):
            yield item


def _apply_import(state: _ImportState, text: str) -> None:
    """按 import 语句文本更新状态（含 as 别名形态，按模块名归类）。"""
    normalized = " ".join(text.split())
    if normalized.startswith("import "):
        for name in normalized.removeprefix("import ").split(","):
            module = name.split(" as ")[0].strip()
            if module == "ctypes":
                state.ctypes_imported = True
            elif module == "cffi":
                state.cffi_imported = True
        return
    match = re.match(r"from\s+([A-Za-z_][\w.]*)\s+import\s+(.+)", normalized)
    if match is None:
        return
    module, names = match.group(1), match.group(2)
    for name in names.replace("(", "").replace(")", "").split(","):
        symbol = name.split(" as ")[0].strip()
        if module == "ctypes" and symbol == "cdll":
            state.cdll_imported = True
        elif module == "ctypes" and symbol == "CDLL":
            state.cdll_class_imported = True
        elif module == "cffi" and symbol == "FFI":
            state.ffi_class_imported = True


def _apply_assignment(state: _ImportState, text: str) -> None:
    """`ffi = FFI()` / `ffi = cffi.FFI()` 实例变量收集（不产证）。"""
    match = re.match(r"\s*([A-Za-z_]\w*)\s*=\s*(?:cffi\s*\.\s*)?FFI\s*\(\s*\)", text)
    if match is not None:
        state.ffi_vars.add(match.group(1))


def _emit_call(node, text: str, state: _ImportState, subject_id: str, path: str, index: SourceByteIndex, source: bytes):
    """单个 call 节点分派：动态库加载 / cdef / set_source。"""
    function = node.child_by_field_name("function")
    function_text = source[function.start_byte:function.end_byte].decode("utf-8", errors="replace") if function is not None else text
    arguments = node.child_by_field_name("arguments")
    args_text = source[arguments.start_byte:arguments.end_byte].decode("utf-8", errors="replace") if arguments is not None else "()"
    has_argument = any(child.type in {"string", "identifier", "call", "attribute"} for child in (arguments.named_children if arguments is not None else ()))

    form = _load_form(function_text, state)
    if form is not None:
        if not has_argument:
            return  # 参数为空不产证（与 riscv-scan 一致）
        yield make_python_evidence(
            subject_id=subject_id, path=path, span=index.span(node.start_byte, node.end_byte),
            kind="call_expression", value=text[:512],
            metadata={"nodeType": "python_dynamic_load", "loadForm": form},
        )
        library = _first_string(args_text)
        if library is not None:
            yield make_python_evidence(
                subject_id=subject_id, path=path, span=index.span(node.start_byte, node.end_byte),
                kind="preprocessor_condition", value=library,
                metadata={"nodeType": "python_native_library", "loadForm": form},
            )
        return
    method = function_text.rsplit(".", 1)[-1]
    if method == "cdef":
        yield from _embedded_c(node, _first_string(args_text), subject_id, path, index, "python_cffi_cdef", text)
        return
    if method == "set_source":
        # set_source(name, "C code")：首参是模块名，其余字符串是 C 源。
        strings = _all_strings(args_text)
        if len(strings) >= 1:
            yield from _embedded_c(node, strings[0] if len(strings) == 1 else "\n".join(strings[1:]),
                                   subject_id, path, index, "python_cffi_set_source", text)
        return
    if method == "verify":
        yield make_python_evidence(
            subject_id=subject_id, path=path, span=index.span(node.start_byte, node.end_byte),
            kind="selection_condition", value=text[:512],
            metadata={"nodeType": "python_cffi_verify", "deprecated": "true"},
        )


def _load_form(function_text: str, state: _ImportState) -> str | None:
    """判定动态库加载形态；非加载返回 None（按 import 状态解析限定名）。"""
    stripped = function_text.strip()
    if stripped in {"ctypes.CDLL", "ctypes.cdll.LoadLibrary"}:
        return "ctypes_qualified"
    if stripped == "cdll.LoadLibrary" and state.cdll_imported:
        return "ctypes_from_import"
    if stripped == "CDLL" and state.cdll_class_imported:
        return "ctypes_from_import"
    if stripped in {"cffi.FFI().dlopen", "FFI().dlopen"}:
        return "cffi_inline"
    if "." in stripped and stripped.endswith(".dlopen"):
        receiver = stripped.rsplit(".", 1)[0]
        if receiver in state.ffi_vars or state.cffi_imported or state.ffi_class_imported:
            return "cffi_instance"
    return None


def _embedded_c(node, c_text: str | None, subject_id: str, path: str, index: SourceByteIndex, node_type: str,
                node_text: str = ""):
    """cdef/set_source 的内嵌 C 代码：#include 行 → include；其余行 → inline_asm。

    行偏移锚定：字符串实参内容首字节（node.start_byte + 实参引号在节点文本内
    的相对偏移）；多行字符串按 \\n 拆分逐行累计。
    """
    if not c_text:
        return
    quote = node_text.find('"')
    offset = node.start_byte + (quote + 1 if quote >= 0 else 0)
    for line in c_text.splitlines():
        line_bytes = len(line.encode("utf-8"))
        if _C_INCLUDE_RE.match(line):
            yield make_python_evidence(
                subject_id=subject_id, path=path,
                span=index.span(offset, offset + line_bytes),
                kind="include", value=line.strip(),
                metadata={"nodeType": node_type},
            )
        else:
            for _ in _scan_lines(path, subject_id, [line]):
                yield make_python_evidence(
                    subject_id=subject_id, path=path,
                    span=index.span(offset, offset + line_bytes),
                    kind="inline_asm", value=line.strip()[:512],
                    metadata={"nodeType": node_type},
                )
        offset += line_bytes + 1


def _first_string(args_text: str) -> str | None:
    """实参列表里首个字符串字面量的内容（解码转义 \\n）。"""
    match = _STRING_RE.search(args_text)
    return match.group(1).replace("\\n", "\n").replace("\\t", " ").replace('\\"', '"') if match is not None else None


def _all_strings(args_text: str) -> list[str]:
    return [m.group(1).replace("\\n", "\n").replace("\\t", " ").replace('\\"', '"') for m in _STRING_RE.finditer(args_text)]


def _scan_pyx(path: str, source: bytes, subject_id: str):
    """Cython 行正则路线：# 注释跳过，两检测点逐行匹配。"""
    text = source.decode("utf-8", errors="replace")
    offset = 0
    for line in text.splitlines():
        line_bytes = len(line.encode("utf-8")) + 1
        stripped = line.strip()
        if stripped and not stripped.startswith("#"):
            span = None
            from ...core import SourceLocation, SourceSpan, normalize_project_path
            normalized = normalize_project_path(path)
            start = SourceLocation(normalized, offset + 1 + (len(line) - len(line.lstrip())), 1)
            end = SourceLocation(normalized, offset + 1 + (len(line) - len(line.lstrip())), max(len(stripped), 1))
            span = SourceSpan(start, end)
            if PYX_CTYPES_CALL_RE.search(line):
                yield make_python_evidence(
                    subject_id=subject_id, path=path, span=span,
                    kind="call_expression", value=stripped[:512],
                    metadata={"nodeType": "cython_ctypes_call", "loadForm": "cython_line_match"},
                )
                library = _first_string(stripped)
                if library is not None:
                    yield make_python_evidence(
                        subject_id=subject_id, path=path, span=span,
                        kind="preprocessor_condition", value=library,
                        metadata={"nodeType": "cython_native_library", "loadForm": "cython_line_match"},
                    )
            elif PYX_DLL_IMPORT_RE.search(line):
                yield make_python_evidence(
                    subject_id=subject_id, path=path, span=span,
                    kind="call_expression", value=stripped[:512],
                    metadata={"nodeType": "cython_ctypes_import", "loadForm": "cython_import"},
                )
        offset += line_bytes


def _build_parser():
    """tree-sitter 0.26 新式初始化。"""
    from tree_sitter import Language, Parser
    import tree_sitter_python

    return Parser(Language(tree_sitter_python.language()))


def _diagnostic(code: str, message: str, path: str) -> Diagnostic:
    return Diagnostic(code, "frontend.python", DiagnosticSeverity.WARNING, message, producer=PRODUCER,
                      details={"path": path})
