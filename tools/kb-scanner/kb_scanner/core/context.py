"""扫描上下文模型：workspace 阶段产出的环境信息（frozen 不可变）。

ScanRequest 是 CLI 层的原始请求；ScanContext 是 workspace 阶段归一化
后的上下文（解析后的根目录、编译单元上下文、规则包标识等），供后续
所有阶段只读共享。CompilationUnitContext 描述单个编译单元的编译环境。
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True, slots=True)
class CompilationUnitContext:
    """单个编译单元的编译环境（来自 compile_commands.json 或推断）。

    defines 按宏名排序冻结；tokens 是该单元涉及的关键 token 集合
    （供前端快速过滤）；diagnostics 记录解析该单元时的告警。
    """

    path: Path
    include_dirs: tuple[str, ...] = ()
    defines: tuple[tuple[str, str | None], ...] = ()
    language_standard: str | None = None
    raw_command: str | None = None
    tokens: tuple[str, ...] = ()
    entry_index: int = 0
    compiler: str | None = None
    directory: Path | None = None
    diagnostics: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        object.__setattr__(self, "path", self.path.resolve())
        object.__setattr__(self, "include_dirs", tuple(self.include_dirs))
        object.__setattr__(self, "defines", tuple(sorted(self.defines, key=lambda item: item[0])))
        object.__setattr__(self, "tokens", tuple(self.tokens))
        object.__setattr__(self, "diagnostics", tuple(self.diagnostics))
        if self.directory is not None:
            object.__setattr__(self, "directory", self.directory.resolve())


@dataclass(frozen=True, slots=True)
class ScanRequest:
    """用户扫描请求（frozen 不可变）：CLI 参数到扫描器的入参合同。

    context_infer 取 auto/off（是否从 build 系统推断编译上下文）；
    include_inactive_branches 控制是否分析被预处理条件剔除的分支；
    schema_version 决定输出合同版本（1.0 旧格式 / 2.0 当前默认）；
    source_arch 指定源架构族（x86/arm64），决定检测器装载哪族词表。
    """

    input_path: str
    rules_path: str
    output_path: str
    languages: tuple[str, ...] = ("c", "cpp")
    excludes: tuple[str, ...] = (".git", "build")
    exclude_paths: tuple[str, ...] = ()
    formats: tuple[str, ...] = ("json", "html")
    context_infer: str = "auto"
    target_arch: str = "riscv64"
    source_arch: str = "x86"
    include_inactive_branches: bool = False
    include_comment_hints: bool = True
    compile_commands_path: str | None = None
    locale: str = "zh-CN"
    schema_version: str = "2.0"

    def __post_init__(self) -> None:
        object.__setattr__(self, "languages", tuple(self.languages))
        object.__setattr__(self, "excludes", tuple(self.excludes))
        object.__setattr__(self, "exclude_paths", tuple(self.exclude_paths))
        object.__setattr__(self, "formats", tuple(self.formats))
        if self.context_infer not in {"auto", "off"}:
            raise ValueError("context_infer must be auto or off")
        if self.schema_version not in {"1.0", "2.0"}:
            raise ValueError("schema_version must be 1.0 or 2.0")
        if self.source_arch not in {"x86", "arm64"}:
            raise ValueError("source_arch must be x86 or arm64")


@dataclass(frozen=True, slots=True)
class ScanContext:
    """归一化后的扫描上下文：workspace 阶段的输出、后续阶段的只读输入。

    路径全部 resolve 为绝对路径；compile_contexts 按路径排序冻结，
    保证同一工作区在任何环境下产生一致的上下文顺序；scan_mode
    标识编译上下文的来源方式（如 compile-commands/inferred/plain）；
    source_arch 声明本次扫描的源架构族（x86/arm64），各阶段按族
    选择词表、检测器定义与输出 domain。
    """

    input_root: Path
    output_dir: Path
    rule_dir: Path
    languages: tuple[str, ...]
    excludes: tuple[str, ...]
    exclude_paths: tuple[str, ...]
    formats: tuple[str, ...]
    context_infer: str
    target_arch: str
    include_inactive_branches: bool
    include_comment_hints: bool
    locale: str
    compile_commands_path: Path | None
    compile_contexts: tuple[CompilationUnitContext, ...]
    scan_mode: str
    rule_pack_id: str
    rule_pack_version: str
    knowledge_id: str
    scanner_name: str
    scanner_version: str
    knowledge_manifest_name: str = "knowledge-manifest.json"
    schema_version: str = "2.0"
    source_arch: str = "x86"

    def __post_init__(self) -> None:
        object.__setattr__(self, "input_root", self.input_root.resolve())
        object.__setattr__(self, "output_dir", self.output_dir.resolve())
        object.__setattr__(self, "rule_dir", self.rule_dir.resolve())
        object.__setattr__(self, "languages", tuple(self.languages))
        object.__setattr__(self, "excludes", tuple(self.excludes))
        object.__setattr__(self, "exclude_paths", tuple(self.exclude_paths))
        object.__setattr__(self, "formats", tuple(self.formats))
        object.__setattr__(self, "compile_contexts", tuple(sorted(self.compile_contexts, key=lambda item: str(item.path))))
        if self.compile_commands_path is not None:
            object.__setattr__(self, "compile_commands_path", self.compile_commands_path.resolve())
        if not all((self.target_arch, self.rule_pack_id, self.rule_pack_version, self.knowledge_id, self.scanner_name, self.scanner_version, self.knowledge_manifest_name)):
            raise ValueError("scan context identity fields must not be empty")
        if self.schema_version not in {"1.0", "2.0"}:
            raise ValueError("schema_version must be 1.0 or 2.0")
        if self.source_arch not in {"x86", "arm64"}:
            raise ValueError("source_arch must be x86 or arm64")
