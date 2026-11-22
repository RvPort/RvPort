"""汇编 frontend：把 .s/.S/.asm 文件逐行词法扫描为 inline_asm 证据。

不解析汇编语法树：跳过 C 预处理指令行（.S 文件的 #include/#define 等，
不展开、不递归 include）、剥离注释（; 行注释、块注释状态机、//）与标签
前缀后，每个剩余非空行产出一条 inline_asm 证据。下游复用既有
C_CPP.{X86,ARM64}.ASM_INSTRUCTION/ASM_REGISTER 检测器（按 source_arch 选族）
与 C/C++ 归一化链，fact.kind 落 architecture_probe，schema 零变化。

预算：单文件 1MB、累计 32MB、5 万行/文件；超限只降级 PARTIAL 并记问题码，
不让阶段 FAILED。decode 失败按字符替换并记诊断（不崩是底线）。
"""
from __future__ import annotations

from ...core import CompletionStatus, Diagnostic, DiagnosticSeverity, FrontendSnapshot, ScanContext, StageResult, WorkspaceSnapshot
from .vocabulary import (
    MAX_FILE_BYTES,
    MAX_LINES,
    MAX_TOTAL_BYTES,
    PRODUCER,
    is_assembly_path,
    make_asm_evidence,
    make_asm_file_entity,
)


class AsmFrontend:
    """汇编源 frontend：流水线 frontend 阶段的第三输入域。

    与 BuildFrontend 同款双重预算模式；reader 可注入（测试用）。
    """

    __slots__ = ("_reader", "_max_bytes")

    def __init__(self, reader=None, max_bytes: int = MAX_FILE_BYTES) -> None:
        if max_bytes < 1:
            raise ValueError("max_bytes must be positive")
        self._reader = reader or _read_text
        self._max_bytes = max_bytes

    def analyze(self, context: ScanContext, workspace: WorkspaceSnapshot) -> StageResult[FrontendSnapshot]:
        """扫描工作区全部汇编输入（按 language 开关过滤），产出 FrontendSnapshot。"""
        if "asm" not in set(context.languages):
            return StageResult(FrontendSnapshot((), ()))
        entities: dict[str, object] = {}
        evidence: dict[str, object] = {}
        diagnostics: tuple[Diagnostic, ...] = ()
        partial = False
        total_bytes = 0
        for relative in sorted(workspace.files):
            if not is_assembly_path(relative):
                continue
            entity = make_asm_file_entity(relative, context.scanner_version)
            entities[entity.id] = entity
            path = workspace.root / relative
            try:
                # 防目录逃逸：解析后的路径必须仍位于工作区根之内。
                path.resolve().relative_to(workspace.root.resolve())
                size = path.stat().st_size
                if size > self._max_bytes or total_bytes + size > MAX_TOTAL_BYTES:
                    raise ValueError("assembly input exceeds size budget")
                text = self._reader(path)
                actual = len(text.encode("utf-8", errors="replace"))
                if actual > self._max_bytes or total_bytes + actual > MAX_TOTAL_BYTES:
                    raise ValueError("assembly input changed beyond size budget")
                total_bytes += actual
            except (OSError, UnicodeError, ValueError, TypeError) as exc:
                diagnostics += (_diagnostic("asm-frontend.input-partial",
                    f"Assembly input unavailable: {relative} ({type(exc).__name__})", relative),)
                partial = True
                continue
            lines = text.splitlines()
            if len(lines) > MAX_LINES:
                diagnostics += (_diagnostic("asm-frontend.line-budget",
                    f"Assembly input truncated at {MAX_LINES} lines: {relative}", relative),)
                partial = True
                lines = lines[:MAX_LINES]
            if "�" in text:
                diagnostics += (_diagnostic("asm-frontend.decode-partial",
                    f"Assembly input decoded with replacement characters: {relative}", relative),)
                partial = True
            for evidence_item in _scan_lines(relative, entity.id, lines):
                evidence[evidence_item.id] = evidence_item
        return StageResult(
            FrontendSnapshot(tuple(entities.values()), tuple(evidence.values())),
            CompletionStatus.PARTIAL if partial else CompletionStatus.COMPLETE,
            diagnostics,
        )


def _scan_lines(path: str, subject_id: str, lines: list[str]):
    """逐行词法：跳过预处理/注释/标签行，产出每语句一条 inline_asm 证据。

    块注释跨行由 in_block_comment 状态机维护；# 指令行先于行注释判定
    （GNU .S 的 #include 不会被当注释误杀）。
    """
    byte_offset = 0
    in_block_comment = False
    for lineno, raw in enumerate(lines, start=1):
        line_bytes = len(raw.encode("utf-8", errors="replace")) + 1
        stripped = raw.strip()
        if in_block_comment:
            if "*/" in stripped:
                stripped = stripped.split("*/", 1)[1].strip()
                in_block_comment = False
            else:
                byte_offset += line_bytes
                continue
        # C 预处理指令行：不解析、不展开，整行跳过（.S 的 #include/#define/#if…）。
        if stripped.startswith("#"):
            byte_offset += line_bytes
            continue
        # 注释剥离：; 行注释（结尾 \ 与下一行续接的少见形态不处理）、// 行注释、/* */ 块注释。
        for marker in (";", "//"):
            if marker in stripped:
                stripped = stripped.split(marker, 1)[0].strip()
        if "/*" in stripped:
            head, _, _ = stripped.partition("/*")
            stripped = head.strip()
            in_block_comment = True
        # 标签前缀剥离：`foo:` / `1:` / `.L1:` 形态（标签后跟语句的剥前缀；
        # 标签独占一行的整行跳过，不作为语句产出）。
        while stripped and ":" in stripped:
            head, sep, tail = stripped.partition(":")
            if not sep or not tail.strip():
                stripped = ""
                break
            if any(ch.isspace() for ch in head):
                break
            stripped = tail.strip()
        if not stripped:
            byte_offset += line_bytes
            continue
        # 前导空白已被 strip；列坐标按原始行内的语句起点计算。
        start_column = raw.find(stripped[:1]) + 1 if stripped else 1
        start_column = max(start_column, 1)
        end_column = start_column + len(stripped) - 1
        yield make_asm_evidence(
            subject_id=subject_id, path=path, line=lineno,
            start_column=start_column, end_column=end_column,
            value=stripped, byte_offset=byte_offset,
        )
        byte_offset += line_bytes


def _read_text(path) -> str:
    """读文件为 UTF-8 文本；解码失败按字符替换（记诊断由调用方负责）。"""
    return path.read_text(encoding="utf-8", errors="replace")


def _diagnostic(code: str, message: str, path: str) -> Diagnostic:
    return Diagnostic(code, "frontend.asm", DiagnosticSeverity.WARNING, message, producer=PRODUCER,
                      details={"path": path})
