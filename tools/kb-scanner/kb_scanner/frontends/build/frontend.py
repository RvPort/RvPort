"""构建 frontend 主入口：调度各构建系统解析器并汇聚 Entity / Evidence。

位于八阶段流水线的 frontend 阶段：遍历工作区内的构建输入（Make、
configure、.m4、CMake 源码等），按类型分派到 autoconf/cmake/make 等
有界解析器，把 BuildRecord 统一包装为 build_file Entity 与构建类
Evidence，输出 FrontendSnapshot。
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
)
from .adapters import AdapterResult, parse_m4, parse_machine_json, parse_rpm, parse_shell
from .goreleaser import parse_goreleaser
from .autoconf.project import analyze_project
from .cmake.project import analyze_project as analyze_cmake
from .cmake.usage import bind_usage
from .make import parse_make
from .vocabulary import BuildInputKind, make_build_evidence, make_build_file_entity
from .workspace import build_input_kind


class BuildFrontend:
    """构建脚本 frontend：把工作区构建输入解析为 Entity / Evidence。

    流水线 frontend 阶段的构建系统入口。不执行任何项目命令，所有解析
    都委托给有界静态分析器（tree-sitter + 有界求值器）。
    关键不变量：单文件读取不超过 _max_bytes，累计输入不超过 64MB；
    任何输入读取/解析失败只降级为 PARTIAL，不让整个阶段 FAILED。
    """

    __slots__ = ("_reader", "_max_bytes")

    def __init__(self, reader=None, max_bytes: int = 4 * 1024 * 1024) -> None:
        """reader 可注入自定义读文件函数（测试用），默认 UTF-8 读取并截断。"""
        if max_bytes < 1:
            raise ValueError("max_bytes must be positive")
        self._reader = reader or (lambda path: _read_text(path, max_bytes))
        self._max_bytes = max_bytes

    def analyze(self, context: ScanContext, workspace: WorkspaceSnapshot) -> StageResult[FrontendSnapshot]:
        """扫描工作区全部构建输入，产出 FrontendSnapshot。

        三遍流程：
        1. 按大小预算收集 SHELL/M4/CMAKE_SOURCE/MAKE 四类需要全文读取的源文本
           （越界或读失败记入 failed_inputs，只降级为 PARTIAL）；
        2. autoconf/cmake 项目级分析一次性拿到跨文件结果；
        3. 逐输入产生 build_file Entity，再把解析出的 BuildRecord 包装为
           build_evidence Evidence（跨文件 origin 时补建 origin Entity）。
        返回 StageResult：有任何降级则状态为 PARTIAL，否则 COMPLETE。
        """
        entities = {}
        evidence = {}
        diagnostics: tuple[Diagnostic, ...] = ()
        partial = False
        autoconf = {}
        cmake = {}
        make = {}
        failed_inputs = set()
        total_bytes = 0
        for relative in sorted(workspace.files):
            # 第一遍只收集四类需要全文读取的源文本；其余类型（JSON 元数据等）后面惰性读取。
            if build_input_kind(relative) not in {BuildInputKind.SHELL, BuildInputKind.M4, BuildInputKind.CMAKE_SOURCE, BuildInputKind.MAKE}:
                continue
            try:
                path = workspace.root / relative
                # 防目录逃逸：解析后的路径必须仍位于工作区根之内。
                path.resolve().relative_to(workspace.root.resolve())
                size = path.stat().st_size
                # 双重预算：stat 大小与实际读取字节数都要同时满足单文件与累计上限。
                if size > self._max_bytes or total_bytes + size > 64 * 1024 * 1024:
                    raise ValueError("Build source input exceeds size budget")
                source = self._reader(path)
                actual = len(source.encode("utf-8"))
                if actual > self._max_bytes or total_bytes + actual > 64 * 1024 * 1024:
                    raise ValueError("Build source input changed beyond size budget")
                total_bytes += actual
                destination = make if build_input_kind(relative) is BuildInputKind.MAKE else cmake if build_input_kind(relative) is BuildInputKind.CMAKE_SOURCE else autoconf
                destination[relative] = source
            except (OSError, UnicodeError, ValueError, TypeError) as exc:
                failed_inputs.add(relative)
                diagnostics = merge_diagnostics(diagnostics, (_diagnostic("build-frontend.input-partial",
                    f"Build source input unavailable: {relative} ({type(exc).__name__})", relative),))
                partial = True
        autoconf_results = analyze_project(autoconf, context.target_arch)
        cmake_results = bind_usage(analyze_cmake(cmake, context.target_arch))
        # 第二遍：为每个构建输入建立 Entity，并把解析记录转换为 Evidence。
        for relative in sorted(workspace.files):
            input_kind = build_input_kind(relative)
            if input_kind is None:
                continue
            entity = make_build_file_entity(relative, input_kind, context.scanner_version)
            entities[entity.id] = entity
            if relative in failed_inputs:
                continue
            path = workspace.root / relative
            try:
                # 解析优先级：cmake 项目级结果 > make（可访问跨文件 sources）> autoconf 项目级结果 > 单文件惰性解析。
                if relative in cmake_results:
                    result = cmake_results[relative]
                elif relative in make:
                    result = parse_make(make[relative], relative, context.target_arch, sources=make)
                elif relative in autoconf_results:
                    result = autoconf_results[relative]
                else:
                    # JSON 元数据类输入在此惰性读取，仍受单文件大小预算约束。
                    if path.stat().st_size > self._max_bytes:
                        raise ValueError("input exceeds size budget")
                    source = self._reader(path)
                    result = self._parse(source, relative, input_kind, context.target_arch)
            except (OSError, UnicodeError, ValueError, TypeError) as exc:
                diagnostic = _diagnostic(
                    "build-frontend.input-partial",
                    f"Build input could not be fully analyzed: {relative} ({type(exc).__name__})",
                    relative,
                )
                diagnostics = merge_diagnostics(diagnostics, (diagnostic,))
                partial = True
                continue
            for message in result.diagnostics:
                diagnostics = merge_diagnostics(diagnostics, (_diagnostic("build-frontend.partial", message, relative),))
            partial = partial or result.partial
            producer = _producer(input_kind)
            for record in result.records:
                # Make 宏参数解析可能把 Evidence 归属到被 include 的 origin 文件，
                # 此时补建 origin Entity 并把 Evidence 挂到 origin 上。
                origin = (record.metadata or {}).get('sourcePath', relative)
                origin_entity = entity
                if origin != relative:
                    if origin not in make:
                        raise ValueError('Make origin is outside collected inputs')
                    origin_entity = make_build_file_entity(origin, input_kind, context.scanner_version)
                    entities[origin_entity.id] = origin_entity
                item = make_build_evidence(record, path=origin, subject_id=origin_entity.id, producer=producer, input_kind=input_kind)
                evidence[item.id] = item
        snapshot = FrontendSnapshot(
            entities=tuple(sorted(entities.values(), key=lambda item: (item.kind, item.name, item.id))),
            evidence=tuple(sorted(evidence.values(), key=lambda item: (item.location.start.path if item.location else "", item.location.start.line if item.location else 0, item.kind, item.id))),
        )
        return StageResult(snapshot, CompletionStatus.PARTIAL if partial else CompletionStatus.COMPLETE, diagnostics)

    @staticmethod
    def _parse(source: str, path: str, input_kind: BuildInputKind, target_arch: str) -> AdapterResult:
        """按输入类型分派到对应解析器（Make 需重新包装为无 sources 的结果）。"""
        if input_kind is BuildInputKind.MAKE:
            result = parse_make(source, path, target_arch)
            return AdapterResult(result.records, result.diagnostics, result.partial)
        if input_kind is BuildInputKind.SHELL:
            return parse_shell(source, path, target_arch)
        if input_kind is BuildInputKind.M4:
            return parse_m4(source, path, target_arch)
        if input_kind is BuildInputKind.RPM:
            return parse_rpm(source, path, target_arch)
        if input_kind is BuildInputKind.GORELEASER:
            return parse_goreleaser(source, path, target_arch)
        return parse_machine_json(source, path, input_kind.value, target_arch)


def _read_text(path: Path, max_bytes: int = 4 * 1024 * 1024) -> str:
    """默认读取器：UTF-8 读入，多读 1 字节以便配合截断检测。"""
    with path.open("r", encoding="utf-8", newline="") as stream:
        return stream.read(max_bytes + 1)


def _producer(input_kind: BuildInputKind) -> str:
    """为 Evidence.producer 字段生成人类可读的解析器名称。"""
    return {
        BuildInputKind.MAKE: "tree-sitter-make+bounded-evaluator",
        BuildInputKind.SHELL: "tree-sitter-bash+bounded-autoconf",
        BuildInputKind.M4: "bounded-m4-recognizer",
        BuildInputKind.CMAKE: "cmake-file-api-reader",
        BuildInputKind.CMAKE_SOURCE: "tree-sitter-cmake+bounded-evaluator",
        BuildInputKind.MESON: "meson-introspection-reader",
        BuildInputKind.CTEST: "ctest-metadata-reader",
        BuildInputKind.COMPILATION_DATABASE: "compilation-database-build-reader",
        BuildInputKind.RPM: "bounded-rpm-parser",
        BuildInputKind.GORELEASER: "goreleaser-indent-parser",
    }[input_kind]


def _diagnostic(code: str, message: str, path: str) -> Diagnostic:
    """构造 build-frontend 阶段的 WARNING 级诊断。"""
    return Diagnostic(
        code=code,
        stage="frontend.build",
        severity=DiagnosticSeverity.WARNING,
        message=message,
        producer="build-frontend",
        details={"path": path},
    )
