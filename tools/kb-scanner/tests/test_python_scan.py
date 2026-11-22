"""Python/Cython 前端域与 goreleaser 配置的端到端契约测试（真 CLI 全管线）。"""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from kb_scanner.core import ScanRequest
from kb_scanner.pipeline.default import create_default_application


ROOT = Path(__file__).resolve().parents[1]
SAMPLE = ROOT / "samples" / "python"


class PythonScanTests(unittest.TestCase):
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

    def test_library_names_and_embedded_c_produce_observations(self):
        report = self.scan({
            "app.py": (
                "import ctypes\n"
                'lib = ctypes.CDLL("libsimd_x86.so")\n'
                "from cffi import FFI\nffi = FFI()\n"
                'ffi.set_source("_m", "#include <immintrin.h>\\nint f(void) { _mm_add_epi32(0, 0); }")\n'
            ),
        }, languages=("python",))
        symbols = {str(f["fact"]["symbol"]) for f in report["findings"]}
        # 库名经启发式词元（x86）出候选观察；内嵌 C 的 _mm_add_epi32 命中词表。
        self.assertTrue(any("x86" in s for s in symbols) or any("_mm_add" in s or "immintrin" in s for s in symbols),
                        symbols)
        for finding in report["findings"]:
            for item in finding["fact"]["provenance"]:
                self.assertEqual("python-frontend", item["producer"])

    def test_domain_python(self):
        report = self.scan({"a.py": "import os\n"}, languages=("python",))
        self.assertEqual("python-x86-to-riscv", report["scan"]["scope"]["domain"])

    def test_default_language_excludes_python(self):
        report = self.scan({
            "a.py": 'import ctypes\n_ = ctypes.CDLL("libx.so")\n',
            "main.c": "int main(void) { return 0; }\n",
        })
        self.assertEqual([], report["findings"])
        self.assertEqual("c-cpp-x86-to-riscv", report["scan"]["scope"]["domain"])

    def test_goreleaser_missing_riscv_observation(self):
        """goreleaser goarch 缺 riscv → build_arch_constraint 观察（build 通道）。"""
        report = self.scan({
            ".goreleaser.yml": "builds:\n  - goarch:\n      - amd64\n      - arm64\n",
        })
        symbols = {str(f["fact"]["symbol"]) for f in report["findings"]}
        self.assertTrue(any("goarch" in s for s in symbols), symbols)

    def test_goreleaser_with_riscv_no_observation(self):
        report = self.scan({
            ".goreleaser.yml": "builds:\n  - goarch:\n      - amd64\n      - riscv64\n",
        })
        symbols = {str(f["fact"]["symbol"]) for f in report["findings"]}
        self.assertFalse(any("goarch" in s for s in symbols), symbols)

    def test_sample_project_scans(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "out"
            app = create_default_application()
            with patch("subprocess.run", side_effect=AssertionError("forbidden")):
                result = app.run(ScanRequest(str(SAMPLE), str(ROOT / "rulesets/riscv-porting"),
                                             str(output), languages=("python",), context_infer="off"))
            self.assertEqual(0, result.exit_code, result.diagnostics)
            report = json.loads((output / "scan-result.json").read_text())
        symbols = {str(f["fact"]["symbol"]) for f in report["findings"]}
        self.assertTrue(any("_mm_add" in s for s in symbols), f"内嵌 C intrinsic 应命中: {symbols}")
        self.assertTrue(any("x86" in s or "amd64" in s for s in symbols), f"库名词元应命中: {symbols}")


if __name__ == "__main__":
    unittest.main()
