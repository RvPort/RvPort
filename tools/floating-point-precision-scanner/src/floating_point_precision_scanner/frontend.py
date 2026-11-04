"""锁定 Clang 21.1.8 的可注入进程适配器。"""

from __future__ import annotations

import json
import math
import os
import subprocess
import tempfile
import time
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol

from . import CLANG_VERSION
from .compilation import CompileContext
from .diagnostics import Diagnostic, diagnostic, sanitize_text


@dataclass(frozen=True, slots=True)
class ProcessResult:
    returncode: int
    stdout: str
    stderr: str
    stdout_truncated: bool = False
    stderr_truncated: bool = False


@dataclass(frozen=True, slots=True)
class FileProcessResult:
    process: ProcessResult
    stdout_path: Path


class ProcessRunner(Protocol):
    def run(
        self,
        arguments: Sequence[str],
        *,
        cwd: Path,
        timeout: float,
        output_limit: int,
        cpu_seconds: float | None = None,
        memory_bytes: int | None = None,
    ) -> ProcessResult: ...


def _resource_limiter(
    cpu_seconds: float | None, memory_bytes: int | None, resource_module: Any
) -> Callable[[], None]:
    def apply_limits() -> None:
        set_limit = resource_module.__dict__["setrlimit"]
        if cpu_seconds is not None:
            seconds = max(1, math.ceil(cpu_seconds))
            set_limit(resource_module.__dict__["RLIMIT_CPU"], (seconds, seconds))
        if memory_bytes is not None:
            set_limit(resource_module.__dict__["RLIMIT_AS"], (memory_bytes, memory_bytes))

    return apply_limits


class SubprocessRunner:
    def run(
        self,
        arguments: Sequence[str],
        *,
        cwd: Path,
        timeout: float,
        output_limit: int,
        cpu_seconds: float | None = None,
        memory_bytes: int | None = None,
    ) -> ProcessResult:
        limit_resources = None
        if os.name != "nt" and (cpu_seconds is not None or memory_bytes is not None):
            import resource

            limit_resources = _resource_limiter(cpu_seconds, memory_bytes, resource)

        completed = subprocess.run(
            list(arguments),
            cwd=cwd,
            shell=False,
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            preexec_fn=limit_resources,
        )
        stdout_truncated = len(completed.stdout.encode("utf-8")) > output_limit
        stderr_truncated = len(completed.stderr.encode("utf-8")) > output_limit
        return ProcessResult(
            completed.returncode,
            completed.stdout[:output_limit],
            completed.stderr[:output_limit],
            stdout_truncated,
            stderr_truncated,
        )

    def run_to_file(
        self,
        arguments: Sequence[str],
        *,
        cwd: Path,
        timeout: float,
        output_limit: int,
        cpu_seconds: float | None = None,
        memory_bytes: int | None = None,
    ) -> FileProcessResult:
        """Run a process with stdout spooled to disk and enforce a byte limit while it runs."""
        limit_resources = None
        if os.name != "nt" and (cpu_seconds is not None or memory_bytes is not None):
            import resource

            limit_resources = _resource_limiter(cpu_seconds, memory_bytes, resource)

        cwd.mkdir(parents=True, exist_ok=True)
        # The subprocess owns the descriptor until it exits; cleanup is handled by the caller.
        handle = tempfile.NamedTemporaryFile(  # noqa: SIM115
            mode="w+b", prefix="clang-ast-", suffix=".json", dir=cwd, delete=False
        )
        path = Path(handle.name)
        process: subprocess.Popen[bytes] | None = None
        started = time.monotonic()
        try:
            process = subprocess.Popen(
                list(arguments),
                cwd=cwd,
                shell=False,
                stdout=handle,
                stderr=subprocess.PIPE,
                preexec_fn=limit_resources,
            )
            stderr = b""
            while True:
                elapsed = time.monotonic() - started
                if elapsed >= timeout:
                    process.kill()
                    _, stderr = process.communicate()
                    raise subprocess.TimeoutExpired(list(arguments), timeout, stderr=stderr)
                try:
                    _, stderr = process.communicate(timeout=min(0.05, timeout - elapsed))
                    break
                except subprocess.TimeoutExpired:
                    handle.flush()
                    if path.stat().st_size > output_limit:
                        process.kill()
                        _, stderr = process.communicate()
                        handle.flush()
                        safe_stderr = stderr.decode("utf-8", errors="replace")
                        return FileProcessResult(
                            ProcessResult(
                                process.returncode if process.returncode is not None else -1,
                                "",
                                safe_stderr[:output_limit],
                                stdout_truncated=True,
                                stderr_truncated=len(stderr) > output_limit,
                            ),
                            path,
                        )
            handle.flush()
            size = path.stat().st_size
            safe_stderr = stderr.decode("utf-8", errors="replace")
            return FileProcessResult(
                ProcessResult(
                    process.returncode if process.returncode is not None else -1,
                    "",
                    safe_stderr[:output_limit],
                    stdout_truncated=size > output_limit,
                    stderr_truncated=len(stderr) > output_limit,
                ),
                path,
            )
        except BaseException:
            if process is not None and process.poll() is None:
                process.kill()
                process.communicate()
            handle.close()
            path.unlink(missing_ok=True)
            raise
        finally:
            if not handle.closed:
                handle.close()


class FrontendFailure(RuntimeError):
    def __init__(self, item: Diagnostic) -> None:
        self.diagnostic = item
        super().__init__(item.diagnostic_code)


class ClangFrontend:
    def __init__(
        self,
        executable: Path,
        runner: ProcessRunner | None = None,
        *,
        timeout: float = 60.0,
        output_limit: int = 16_777_216,
    ) -> None:
        self.executable = executable
        self.runner = runner or SubprocessRunner()
        self.timeout = timeout
        self.output_limit = output_limit

    def verify(
        self,
        *,
        timeout: float | None = None,
        cpu_seconds: float | None = None,
        memory_bytes: int | None = None,
        output_limit: int | None = None,
    ) -> None:
        if not self.executable.is_file():
            raise FrontendFailure(
                diagnostic(
                    "frontend_unavailable",
                    "frontend",
                    "锁定的 Clang 21.1.8 不可用。",
                    "The pinned Clang 21.1.8 frontend is unavailable.",
                )
            )
        result = self._invoke(
            [str(self.executable), "--version"],
            self.executable.parent,
            timeout=timeout,
            cpu_seconds=cpu_seconds,
            memory_bytes=memory_bytes,
            output_limit=output_limit,
        )
        if result.returncode != 0 or f"clang version {CLANG_VERSION}" not in result.stdout.lower():
            raise FrontendFailure(
                diagnostic(
                    "frontend_version_mismatch",
                    "frontend",
                    "Clang 版本不是锁定的 21.1.8。",
                    "Clang is not the pinned version 21.1.8.",
                )
            )

    def ast(
        self,
        context: CompileContext,
        *,
        timeout: float | None = None,
        cpu_seconds: float | None = None,
        memory_bytes: int | None = None,
        output_limit: int | None = None,
    ) -> dict[str, object]:
        command = self.ast_command(context)
        active_limit = output_limit if output_limit is not None else self.output_limit
        file_result: FileProcessResult | None = None
        if isinstance(self.runner, SubprocessRunner):
            try:
                file_result = self.runner.run_to_file(
                    command,
                    cwd=context.directory,
                    timeout=timeout if timeout is not None else self.timeout,
                    output_limit=active_limit,
                    cpu_seconds=cpu_seconds,
                    memory_bytes=memory_bytes,
                )
                result = file_result.process
            except subprocess.TimeoutExpired as exc:
                raise FrontendFailure(
                    diagnostic(
                        "frontend_timeout",
                        "frontend",
                        "Clang 分析超时。",
                        "Clang analysis timed out.",
                        retryable=True,
                        translation_unit_id=context.translation_unit_id,
                    )
                ) from exc
            except OSError as exc:
                raise FrontendFailure(
                    diagnostic(
                        "frontend_unavailable",
                        "frontend",
                        "无法启动锁定的 Clang。",
                        "Unable to start the pinned Clang frontend.",
                        translation_unit_id=context.translation_unit_id,
                    )
                ) from exc
        else:
            result = self._invoke(
                command,
                context.directory,
                timeout=timeout,
                cpu_seconds=cpu_seconds,
                memory_bytes=memory_bytes,
                output_limit=active_limit,
            )
        if result.returncode != 0 and not result.stdout_truncated:
            safe = sanitize_text(result.stderr, context.directory)
            if file_result is not None:
                file_result.stdout_path.unlink(missing_ok=True)
            raise FrontendFailure(
                diagnostic(
                    "frontend_nonzero_exit",
                    "frontend",
                    "Clang 无法解析翻译单元。",
                    "Clang could not parse the translation unit.",
                    suggestion=safe or "请检查编译上下文。",
                    suggestion_en=safe or "Check the compile context.",
                    translation_unit_id=context.translation_unit_id,
                )
            )
        if result.stdout_truncated:
            if file_result is not None:
                file_result.stdout_path.unlink(missing_ok=True)
            raise FrontendFailure(
                diagnostic(
                    "frontend_output_limit_exceeded",
                    "frontend",
                    "Clang AST 输出超过允许的大小。",
                    "Clang AST output exceeds the configured limit.",
                    suggestion="请提高受控输出预算或缩小翻译单元分析范围。",
                    suggestion_en=(
                        "Increase the bounded output budget or reduce the translation-unit scope."
                    ),
                    translation_unit_id=context.translation_unit_id,
                )
            )
        try:
            if file_result is None:
                document = json.loads(result.stdout)
            else:
                with file_result.stdout_path.open("r", encoding="utf-8") as stream:
                    document = json.load(stream)
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            raise FrontendFailure(
                diagnostic(
                    "frontend_invalid_json",
                    "frontend",
                    "Clang AST JSON 无效。",
                    "Clang AST JSON is invalid.",
                    translation_unit_id=context.translation_unit_id,
                )
            ) from exc
        finally:
            if file_result is not None:
                file_result.stdout_path.unlink(missing_ok=True)
        if not isinstance(document, dict):
            raise FrontendFailure(
                diagnostic(
                    "frontend_invalid_json",
                    "frontend",
                    "Clang AST JSON 顶层必须是对象。",
                    "The Clang AST JSON root must be an object.",
                    translation_unit_id=context.translation_unit_id,
                )
            )
        return document

    def ast_command(self, context: CompileContext) -> list[str]:
        return [
            str(self.executable),
            *context.semantic_arguments,
            "-fsyntax-only",
            "-Xclang",
            "-ast-dump=json",
            str(context.absolute_file),
        ]

    def _invoke(
        self,
        arguments: Sequence[str],
        cwd: Path,
        *,
        timeout: float | None = None,
        cpu_seconds: float | None = None,
        memory_bytes: int | None = None,
        output_limit: int | None = None,
    ) -> ProcessResult:
        try:
            return self.runner.run(
                arguments,
                cwd=cwd,
                timeout=timeout if timeout is not None else self.timeout,
                output_limit=output_limit if output_limit is not None else self.output_limit,
                cpu_seconds=cpu_seconds,
                memory_bytes=memory_bytes,
            )
        except subprocess.TimeoutExpired as exc:
            raise FrontendFailure(
                diagnostic(
                    "frontend_timeout",
                    "frontend",
                    "Clang 分析超时。",
                    "Clang analysis timed out.",
                    retryable=True,
                )
            ) from exc
        except OSError as exc:
            raise FrontendFailure(
                diagnostic(
                    "frontend_unavailable",
                    "frontend",
                    "无法启动锁定的 Clang。",
                    "Unable to start the pinned Clang frontend.",
                )
            ) from exc
