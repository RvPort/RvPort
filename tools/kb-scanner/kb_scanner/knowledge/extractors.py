"""上游事实抽取器：从 binutils / LLVM / GCC 官方源码仓库中提取 x86 事实。

属于知识生产侧（构建规则包的工具链），不在运行时扫描流水线上。抽取结果为 v2 生成事实
（汇编指令、寄存器名、目标特性、架构别名），供 provider 在 knowledge 阶段装载。
每个抽取器都只读声明根目录内的文件，输入路径与 SHA-256 会回填到来源描述中留档。
"""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass
from pathlib import Path

from .identity import canonicalize_fact_value
from .schema import GeneratedFactV2Model, SourceDescriptorModel


_BINUTILS_TOKEN = re.compile(r"^[A-Za-z][A-Za-z0-9_.()]*$")
_LLVM_REGISTER = re.compile(r'X86Reg(?:WithSubRegs)?<"([^"]+)"')
_LLVM_FEATURE = re.compile(r'SubtargetFeature<"([^"]+)"')
_LLVM_CPU = re.compile(r'(?:Proc|ProcModel)<"([^"]+)"')
_LLVM_ASM_STRING = re.compile(r'"([a-z][a-z0-9.]*)(?:\{[^"\n]*\})?[^"\n]*(?:\\t|\$)[^"\n]*"')
_LLVM_META_MNEMONICS = frozenset({"dq", "j", "pd", "ps", "sd", "set", "x", "y", "z"})
_GCC_ALIAS = re.compile(r'\{"([^"]+)",\s*PROCESSOR_[A-Z0-9_]+')
_GCC_FEATURE = re.compile(r'ISA_NAMES_TABLE_ENTRY\("([^"]+)"')


@dataclass(frozen=True, slots=True)
class ExtractionDiagnostic:
    """抽取过程中的失败信息（路径越界/不可读/结构不识别），logical_path 指明出错文件。"""

    code: str
    message: str
    logical_path: str


@dataclass(frozen=True, slots=True)
class ExtractionResult:
    """抽取结果：成功时携带事实元组与输入路径/摘要；succeeded 要求有事实且无诊断。"""

    facts: tuple[GeneratedFactV2Model, ...] = ()
    input_paths: tuple[str, ...] = ()
    input_sha256: tuple[str, ...] = ()
    diagnostics: tuple[ExtractionDiagnostic, ...] = ()

    @property
    def succeeded(self) -> bool:
        return bool(self.facts) and not self.diagnostics


class ReadOnlySource:
    """受限只读文件源：只允许读取声明根目录内的 UTF-8 文本文件，防止路径逃逸。"""

    __slots__ = ("_root",)

    def __init__(self, root: Path) -> None:
        self._root = root.resolve()

    def read(self, logical_path: str) -> tuple[str, str] | ExtractionDiagnostic:
        """读取文件，返回 (文本内容, 内容SHA-256)；越界/不可读/非 UTF-8 返回诊断。"""
        normalized = logical_path.replace("\\", "/")
        path = (self._root / normalized).resolve()
        if path == self._root or not path.is_relative_to(self._root):
            return ExtractionDiagnostic("extractor.path-escape", "Source path resolves outside the declared root", normalized)
        try:
            content = path.read_bytes()
        except OSError as exc:
            return ExtractionDiagnostic("extractor.source-unreadable", f"Source input is unreadable: {type(exc).__name__}", normalized)
        try:
            text = content.decode("utf-8")
        except UnicodeDecodeError:
            return ExtractionDiagnostic("extractor.source-encoding", "Source input is not UTF-8", normalized)
        return text, hashlib.sha256(content).hexdigest()


def extract_binutils(root: Path, opcode_path: str, register_path: str) -> ExtractionResult:
    """从 binutils 的 opcode 表与寄存器表中提取汇编指令名和寄存器名（scope=x86-only）。"""
    loaded = _load_all(ReadOnlySource(root), (opcode_path, register_path))
    if isinstance(loaded, ExtractionResult):
        return loaded
    texts, hashes = loaded
    instructions = _binutils_column(texts[0])
    registers = _binutils_column(texts[1])
    if not instructions or not registers:
        return _unsupported((opcode_path, register_path), "Binutils tables contain no recognized x86 records")
    facts = _facts("assemblyInstructions", instructions, "x86-only") + _facts("registerNames", registers, "x86-only")
    return ExtractionResult(_ordered(facts), (opcode_path, register_path), hashes)


def extract_llvm(
    root: Path, register_path: str, target_path: str, instruction_paths: tuple[str, ...] = (),
) -> ExtractionResult:
    """从 LLVM X86 目标定义（TableGen）中提取寄存器名、目标特性、CPU 别名与汇编助记符。

    寄存器来自 X86Reg 记录，特性/CPU 来自 SubtargetFeature 与 Proc 记录；
    汇编字符串来自指令定义中的字符串字面量，过滤掉单字符与元助记符（如 ps/sd 后缀）。
    """
    paths = (register_path, target_path, *instruction_paths)
    loaded = _load_all(ReadOnlySource(root), paths)
    if isinstance(loaded, ExtractionResult):
        return loaded
    texts, hashes = loaded
    registers = set(_LLVM_REGISTER.findall(texts[0]))
    features = set(_LLVM_FEATURE.findall(texts[1]))
    cpus = set(_LLVM_CPU.findall(texts[1]))
    if not registers or not features or not cpus:
        return _unsupported((register_path, target_path), "LLVM X86 definitions do not match the supported structure")
    facts = (
        _facts("registerNames", registers, "x86-only")
        + _facts("targetFeatures", features, "x86-toolchain")
        + _facts("architectureAliases", cpus, "x86-toolchain")
    )
    if instruction_paths:
        instructions = {
            value
            for text in texts[2:]
            for value in _LLVM_ASM_STRING.findall(text)
            if len(value) > 1 and value not in _LLVM_META_MNEMONICS
        }
        if not instructions:
            return _unsupported(paths, "LLVM instruction definitions contain no recognized assembly strings")
        facts += _facts("assemblyInstructions", instructions, "x86-only")
    return ExtractionResult(_ordered(facts), paths, hashes)


def extract_gcc(root: Path, aliases_path: str, features_path: str) -> ExtractionResult:
    """从 GCC i386 后端定义中提取 CPU 架构别名与 ISA 目标特性（scope=x86-toolchain）。"""
    loaded = _load_all(ReadOnlySource(root), (aliases_path, features_path))
    if isinstance(loaded, ExtractionResult):
        return loaded
    texts, hashes = loaded
    aliases = set(_GCC_ALIAS.findall(texts[0]))
    features = set(_GCC_FEATURE.findall(texts[1]))
    if not aliases or not features:
        return _unsupported((aliases_path, features_path), "GCC i386 definitions do not match the supported structure")
    facts = _facts("architectureAliases", aliases, "x86-toolchain") + _facts("targetFeatures", features, "x86-toolchain")
    return ExtractionResult(_ordered(facts), (aliases_path, features_path), hashes)


def with_descriptor(descriptor: SourceDescriptorModel, result: ExtractionResult) -> SourceDescriptorModel:
    """把成功抽取的输入路径与摘要回填到来源描述（model_copy，保持 frozen 语义）。"""
    if not result.succeeded:
        raise ValueError("cannot attach an unsuccessful extraction")
    return descriptor.model_copy(update={"inputPaths": result.input_paths, "inputSha256": result.input_sha256})


def _load_all(source: ReadOnlySource, paths: tuple[str, ...]) -> tuple[tuple[str, ...], tuple[str, ...]] | ExtractionResult:
    """批量读取文件；任一失败即整体失败（诊断聚合），全成功返回 (文本们, 摘要们)。"""
    texts: list[str] = []
    hashes: list[str] = []
    diagnostics: list[ExtractionDiagnostic] = []
    for path in paths:
        loaded = source.read(path)
        if isinstance(loaded, ExtractionDiagnostic):
            diagnostics.append(loaded)
        else:
            text, digest = loaded
            texts.append(text)
            hashes.append(digest)
    if diagnostics:
        return ExtractionResult(input_paths=paths, diagnostics=tuple(diagnostics))
    return tuple(texts), tuple(hashes)


def _binutils_column(text: str) -> set[str]:
    """解析 binutils 表格：取每行首个逗号前的 token，跳过注释行，要求 token 形如标识符。"""
    values: set[str] = set()
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith(("#", "//", ";")) or "," not in line:
            continue
        token = line.split(",", 1)[0].strip()
        if _BINUTILS_TOKEN.fullmatch(token):
            values.add(token)
    return values


def _facts(kind: str, values: set[str], scope: str) -> tuple[GeneratedFactV2Model, ...]:
    """把一组原始值归一化后包装为 v2 生成事实元组。"""
    return tuple(
        GeneratedFactV2Model(
            factKind=kind,
            architectureFamily="x86",
            canonicalValue=canonicalize_fact_value(kind, value),
            semanticScope=scope,
        )
        for value in values
    )


def _ordered(facts: tuple[GeneratedFactV2Model, ...]) -> tuple[GeneratedFactV2Model, ...]:
    """按 (种类, 架构, 规范值) 去重并排序，产出确定性顺序（保证可复现的摘要）。"""
    unique = {(item.factKind, item.architectureFamily, item.canonicalValue): item for item in facts}
    return tuple(unique[key] for key in sorted(unique))


def _unsupported(paths: tuple[str, ...], message: str) -> ExtractionResult:
    """上游源码结构与当前抽取器不匹配时的失败结果（避免静默产出空知识库）。"""
    return ExtractionResult(
        input_paths=paths,
        diagnostics=(ExtractionDiagnostic("extractor.unsupported-structure", message, paths[0]),),
    )
