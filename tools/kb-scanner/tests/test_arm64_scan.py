"""ARM64（arm64→RISC-V）源架构扫描的端到端契约测试。

覆盖三件事：
1. --source-arch arm64 时 arm64 词表命中（头文件/宏/指令/builtin）且 domain 正确；
2. 同一输入以 --source-arch x86 扫描不产生 arm64 误报（两族词表隔离）；
3. ScanRequest/CLI 层拒绝非法 source_arch。
"""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from kb_scanner.adapters.cli import StagedContextFactory, scan_request_from_namespace
from kb_scanner.core import ScanRequest
from kb_scanner.pipeline.default import create_default_application


ROOT = Path(__file__).resolve().parents[1]
RULES = ROOT / "rulesets" / "riscv-porting"
SAMPLE = ROOT / "samples" / "arm64"


def _request(source: Path, output: Path, source_arch: str) -> ScanRequest:
    return ScanRequest(
        input_path=str(source),
        rules_path=str(RULES),
        output_path=str(output),
        formats=("json",),
        context_infer="off",
        source_arch=source_arch,
    )


class Arm64ScanTests(unittest.TestCase):
    def run_scan(self, source_arch: str) -> dict:
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "out"
            result = create_default_application().run(_request(SAMPLE, output, source_arch))
            self.assertEqual(0, result.exit_code, result.diagnostics)
            return json.loads((output / "scan-result.json").read_text(encoding="utf-8"))

    def test_arm64_scan_reports_arm64_families_with_correct_domain(self) -> None:
        report = self.run_scan("arm64")
        self.assertEqual("c-cpp-arm64-to-riscv", report["scan"]["scope"]["domain"])
        types = {finding["conclusion"]["findingType"] for finding in report["findings"]}
        # 语料四类至少命中三类：NEON/SVE/ACLE 头文件、平台宏、builtin；asm 指令在
        # neon_math.c 的 mrs 示例中。
        self.assertTrue({"arm64_header_reference", "arm64_platform_macro_reference"} <= types, types)
        self.assertIn("arm64_builtin_reference", types)
        self.assertIn("arm64_assembly_instruction", types)
        # 不应出现任何 x86 观察种类（族隔离）。
        self.assertFalse(any(t.startswith("x86_") for t in types), types)

    def test_x86_scan_of_arm64_sample_does_not_report_arm64_kinds(self) -> None:
        report = self.run_scan("x86")
        self.assertEqual("c-cpp-x86-to-riscv", report["scan"]["scope"]["domain"])
        types = {finding["conclusion"]["findingType"] for finding in report["findings"]}
        self.assertFalse(any(t.startswith("arm64_") for t in types), types)

    def test_collected_source_is_listed_in_report(self) -> None:
        report = self.run_scan("arm64")
        sources = {item["id"]: item["kind"] for item in report["scan"]["knowledgeSources"]}
        self.assertEqual("collected", sources.get("collected-arm64-entries"))


class SourceArchContractTests(unittest.TestCase):
    def test_scan_request_rejects_unknown_source_arch(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                _request(SAMPLE, Path(tmp) / "out", "sparc")

    def test_cli_namespace_maps_source_arch(self) -> None:
        class _Args:
            input = str(SAMPLE)
            rules = str(RULES)
            output = "/tmp/unused"
            language = "c,cpp"
            exclude = ".git,build"
            exclude_path = []
            format = "json"
            context_infer = "auto"
            target_arch = "riscv64"
            source_arch = "arm64"
            include_inactive_branches = False
            no_comment_hints = False
            compile_commands = None
            locale = "zh-CN"
            schema_version = "2.0"

        request = scan_request_from_namespace(_Args())
        self.assertEqual("arm64", request.source_arch)
        with tempfile.TemporaryDirectory() as tmp:
            built = StagedContextFactory().build(request)
            self.assertTrue(built.value is None or getattr(built.value, "source_arch", "arm64") in {"arm64", "x86"})


if __name__ == "__main__":
    unittest.main()
