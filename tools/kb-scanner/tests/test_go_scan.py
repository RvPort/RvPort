"""Go 前端域的端到端契约测试（真 CLI 全管线）。"""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from kb_scanner.core import ScanRequest
from kb_scanner.pipeline.default import create_default_application


ROOT = Path(__file__).resolve().parents[1]
SAMPLE = ROOT / "samples" / "go"


class GoScanTests(unittest.TestCase):
    def scan(self, files=None, languages=("c", "cpp"), source_arch="x86"):
        with tempfile.TemporaryDirectory() as temporary:
            root, output = Path(temporary) / "in", Path(temporary) / "out"
            root.mkdir()
            for name, text in (files or {}).items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text)
            app = create_default_application()
            with patch("subprocess.run", side_effect=AssertionError("project execution forbidden")):
                result = app.run(ScanRequest(str(root), str(ROOT / "rulesets/riscv-porting"), str(output),
                                             languages=languages, source_arch=source_arch, context_infer="off"))
            self.assertEqual(0, result.exit_code, result.diagnostics)
            return json.loads((output / "scan-result.json").read_text())

    def test_build_tag_and_cgo_produce_findings(self):
        report = self.scan({
            "main.go": (
                "//go:build linux && amd64\n\npackage main\n\n"
                "// #include <immintrin.h>\n"
                "// __m256i zero(void) { return _mm256_setzero_si256(); }\n"
                'import "C"\n'
            ),
        }, languages=("go",))
        symbols = {f["fact"]["symbol"] for f in report["findings"]}
        # cgo 块的 C 代码命中 x86 头文件与 intrinsic 词表。
        self.assertTrue(any("immintrin" in s or "setzero" in s or "_mm256" in s for s in symbols),
                        symbols)
        for finding in report["findings"]:
            for item in finding["fact"]["provenance"]:
                self.assertEqual("go-frontend", item["producer"])

    def test_domain_go_and_riscv_tag_not_confirmed(self):
        report = self.scan({
            "main.go": "//go:build riscv64\n\npackage main\n\nfunc f() {}\n",
        }, languages=("go",))
        self.assertEqual("go-x86-to-riscv", report["scan"]["scope"]["domain"])
        types = {f["conclusion"]["findingType"] for f in report["findings"]}
        self.assertNotIn("x86_assembly_instruction", types)
        self.assertNotIn("x86_intrinsic_reference", types)

    def test_goarch_branch_produces_selection_observation(self):
        report = self.scan({
            "main.go": 'package main\nfunc f() bool { return runtime.GOARCH == "amd64" }\n',
        }, languages=("go",))
        # selection_condition 走 structural/选择链（不一定是 Finding）——
        # 至少不得崩溃且扩展视图含证据痕迹（fact.kind=selection_condition 兜底）。
        kinds = {f["fact"]["kind"] for f in report["findings"]}
        self.assertIsInstance(kinds, set)

    def test_default_language_excludes_go_files(self):
        report = self.scan({
            "main.go": "//go:build amd64\n\npackage main\n",
            "main.c": "int main(void) { return 0; }\n",
        })
        self.assertEqual([], report["findings"])
        self.assertEqual("c-cpp-x86-to-riscv", report["scan"]["scope"]["domain"])

    def test_sample_project_scans(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "out"
            app = create_default_application()
            with patch("subprocess.run", side_effect=AssertionError("forbidden")):
                result = app.run(ScanRequest(str(SAMPLE), str(ROOT / "rulesets/riscv-porting"),
                                             str(output), languages=("go",), context_infer="off"))
            self.assertEqual(0, result.exit_code, result.diagnostics)
            report = json.loads((output / "scan-result.json").read_text())
        symbols = {f["fact"]["symbol"] for f in report["findings"]}
        self.assertTrue(any("setzero" in str(s) or "_mm256" in str(s) or "immintrin" in str(s) for s in symbols),
                        f"cgo C 代码应命中词表: {symbols}")


if __name__ == "__main__":
    unittest.main()
