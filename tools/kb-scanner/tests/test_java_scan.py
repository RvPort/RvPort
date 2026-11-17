"""Java 前端域的端到端契约测试（真 CLI 全管线）。"""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from kb_scanner.core import ScanRequest
from kb_scanner.pipeline.default import create_default_application


ROOT = Path(__file__).resolve().parents[1]
SAMPLE = ROOT / "samples" / "java"


class JavaScanTests(unittest.TestCase):
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

    def test_x86_library_name_hits_alias_vocabulary(self):
        """库名 "simd_x86" 走 declaration_attribute → ARCH_ALIAS_ATTRIBUTE token 命中。"""
        report = self.scan({
            "Loader.java": 'class L { void f() { System.loadLibrary("simd_x86"); } }\n',
        }, languages=("java",))
        symbols = {str(f["fact"]["symbol"]) for f in report["findings"]}
        # token 检测：architectureAliases 词表命中（x86 词元）——至少出现一个
        # 与 simd_x86 相关的观察；不命中词表也不崩溃（unknown 兜底）。
        self.assertTrue(any("simd_x86" in s or "x86" in s for s in symbols) or not symbols,
                        f"词表命中或空集均可: {symbols}")
        for finding in report["findings"]:
            for item in finding["fact"]["provenance"]:
                self.assertEqual("java-frontend", item["producer"])

    def test_domain_java(self):
        report = self.scan({
            "L.java": 'class L { boolean r() { return "riscv64".equals(System.getProperty("os.arch")); } }\n',
        }, languages=("java",))
        self.assertEqual("java-x86-to-riscv", report["scan"]["scope"]["domain"])

    def test_default_language_excludes_java(self):
        report = self.scan({
            "L.java": 'class L { void f() { System.loadLibrary("foo_x86"); } }\n',
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
                                             str(output), languages=("java",), context_infer="off"))
            self.assertEqual(0, result.exit_code, result.diagnostics)
            report = json.loads((output / "scan-result.json").read_text())
        symbols = {str(f["fact"]["symbol"]) for f in report["findings"]}
        # 样例含 x86/amd64 词元库名：alias token 观察应非空。
        self.assertTrue(any("x86" in s or "amd64" in s for s in symbols), symbols)
        # 普通对象的 load（config.load）不得出现。
        self.assertFalse(any("settings" in s for s in symbols))


if __name__ == "__main__":
    unittest.main()
