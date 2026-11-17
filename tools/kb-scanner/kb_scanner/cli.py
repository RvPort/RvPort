"""命令行入口：定义 scan / validate-rules / list-rules / version 子命令并分发。

scan 子命令把 argparse 参数转换成 ScanRequest 交给默认流水线应用执行；
validate-rules 与 list-rules 用于在发布前校验规则包（bundle）的完整性。
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path

from . import __version__
from .adapters.cli import StagedContextFactory, scan_request_from_namespace
from .bundle import BundleError, load_bundle_descriptor
from .core import ScanRequest
from .knowledge import GeneratedFactsKnowledgeProvider
from .pipeline.default import create_default_application
from .projection import DEFAULT_PROJECTION_POLICY


def main(argv: list[str] | None = None) -> int:
    """构建参数解析器并分发子命令。

    argv 为 None 时读取 sys.argv；返回进程退出码（0 成功，非 0 失败）。
    """
    parser = argparse.ArgumentParser(prog="kb-scanner")
    subparsers = parser.add_subparsers(dest="command", required=True)

    scan = subparsers.add_parser("scan", help="执行扫描")
    scan.add_argument("--input", required=True)
    scan.add_argument("--language", default="c,cpp")
    scan.add_argument("--rules", required=True)
    scan.add_argument("--compile-commands")
    scan.add_argument("--exclude", default=".git,build")
    scan.add_argument(
        "--exclude-path",
        action="append",
        default=[],
        help="按项目根目录下的精确目录路径排除，可重复传入；例如 --exclude-path third_party/simd --exclude-path vendor/foo",
    )
    scan.add_argument("--output", required=True)
    scan.add_argument("--format", default="json,html")
    scan.add_argument("--context-infer", choices=["auto", "off"], default="auto")
    scan.add_argument("--target-arch", default="riscv64")
    scan.add_argument(
        "--source-arch", choices=["x86", "arm64"], default="x86",
        help="源架构族：决定装载哪族知识词表与检测器定义；默认 x86",
    )
    scan.add_argument("--include-inactive-branches", action="store_true")
    scan.add_argument("--no-comment-hints", action="store_true", help="关闭注释中的架构线索扫描")
    scan.add_argument("--locale", default="zh-CN")
    scan.add_argument(
        "--schema-version", choices=["1.0", "2.0"], default="2.0",
        help="输出合同版本；默认 2.0，旧消费者可显式选择 1.0",
    )

    validate = subparsers.add_parser("validate-rules", help="校验规则包")
    validate.add_argument("--rules", required=True)

    list_rules_parser = subparsers.add_parser("list-rules", help="列出规则")
    list_rules_parser.add_argument("--rules", required=True)

    subparsers.add_parser("version", help="显示版本")

    args = parser.parse_args(argv)
    if args.command == "scan":
        return run_scan(args)
    if args.command == "validate-rules":
        return validate_rules(Path(args.rules))
    if args.command == "list-rules":
        return list_rules(Path(args.rules))
    if args.command == "version":
        print(__version__)
        return 0
    return 2


def _apply_memory_budget() -> tuple[str, ...]:
    """按 KB_SCANNER_MEMORY_LIMIT_MIB 设置进程地址空间硬上限（RLIMIT_AS）。

    环境变量未设置时默认 2048 MiB；设为 0 关闭（本机调试）。超限的分配抛
    MemoryError，由 orchestrator 转成 pipeline.memory-budget-exceeded FATAL——
    把"内存跑满后换页抖动卡死数小时"变成"分钟级失败 + 明确诊断"。
    返回应用说明（诊断记录用）。
    """
    import resource

    limit_mib = int(os.environ.get("KB_SCANNER_MEMORY_LIMIT_MIB", "2048"))
    if limit_mib <= 0:
        return ("memory-budget-disabled",)
    limit_bytes = limit_mib * 1024 * 1024
    try:
        soft, hard = resource.getrlimit(resource.RLIMIT_AS)
        # 只降 soft、保留原 hard：后续（如测试）仍可恢复到原有上限。
        resource.setrlimit(resource.RLIMIT_AS, (min(limit_bytes, hard if hard != resource.RLIM_INFINITY else limit_bytes), hard))
    except (ValueError, OSError):
        # 容器环境可能禁止调低（比如父进程限制更小）——尽力而为，不阻断扫描。
        return ("memory-budget-unapplied",)
    return ("memory-budget-applied", str(limit_mib))


def run_scan(args: argparse.Namespace) -> int:
    """执行 scan 子命令：组装默认应用并运行扫描，返回退出码。"""
    budget_notes = _apply_memory_budget()
    result = create_default_application().run(scan_request_from_namespace(args))
    if budget_notes[0] != "memory-budget-applied":
        # 未应用时以 WARNING 诊断附加到结果（可见但不变更退出码）。
        from .core import CompletionStatus, Diagnostic, DiagnosticSeverity, merge_diagnostics

        note = Diagnostic(
            code=budget_notes[0],
            stage="cli",
            severity=DiagnosticSeverity.WARNING,
            message=f"Scanner memory budget was not applied: {budget_notes[0]}",
            producer="kb-scanner-cli",
        )
        result = type(result)(result.exit_code, result.diagnostics and tuple(result.diagnostics) + (note,) or (note,))
    return result.exit_code


def validate_rules(rule_dir: Path) -> int:
    """校验规则包：清单结构、知识清单哈希、投影策略完整性。

    依次执行：加载 bundle 描述符（含哈希校验）→ 构建 workspace 上下文 →
    加载生成事实知识包 → 校验默认投影策略完整性；任一步失败即返回 1。
    """
    try:
        descriptor = load_bundle_descriptor(rule_dir)
    except BundleError as exc:
        print(f"规则包无效: {exc}")
        return 1
    request = ScanRequest(str(rule_dir), str(rule_dir), str(rule_dir))
    context = StagedContextFactory().build(request)
    if context.value is None:
        for item in context.diagnostics:
            print(item.message)
        return 1
    knowledge = GeneratedFactsKnowledgeProvider().load(context.value)
    for item in knowledge.diagnostics:
        print(item.message)
    missing = DEFAULT_PROJECTION_POLICY.validate_completeness()
    if missing:
        print(f"投影策略缺少: {', '.join(missing)}")
        return 1
    print(f"规则数量: {len(DEFAULT_PROJECTION_POLICY.rule_ids)}")
    print(f"知识包: {descriptor.rule_pack_id}@{descriptor.rule_pack_version}")
    return 0 if knowledge.value is not None else 1


def list_rules(rule_dir: Path) -> int:
    """列出规则包内全部规则 ID（先经 validate_rules 校验通过才输出）。"""
    if validate_rules(rule_dir) != 0:
        return 1
    for rule_id in DEFAULT_PROJECTION_POLICY.rule_ids:
        print(f"{rule_id}\tcode-owned\tversion={DEFAULT_PROJECTION_POLICY.version}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
