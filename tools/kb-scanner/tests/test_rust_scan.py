"""Rust 前端域的端到端契约测试（真 CLI 全管线）。

覆盖：x86 cfg+intrinsic 出 Finding、riscv 分支不产 confirmed、asm! 宏体
走 inline_asm 检测链、domain=rust-x86-to-riscv、默认语言向后兼容
（.rs 文件零证据、domain 不变）、C+Rust 混合扫描。
"""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from kb_scanner.core import ScanRequest
from kb_scanner.pipeline.default import create_default_application


ROOT = Path(__file__).resolve().parents[1]
SAMPLE = ROOT / "samples" / "rust"


class RustScanTests(unittest.TestCase):
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

    def test_x86_cfg_and_intrinsics_produce_findings(self):
        report = self.scan({
            "lib.rs": (
                "use core::arch::x86_64::*;\n"
                "#[cfg(target_arch = \"x86_64\")]\n"
                "pub fn crc32(d: &[u8]) -> u32 {\n"
                "    let v: __m128i = unsafe { _mm_add_epi32(1, 2) };\n"
                "    unsafe { _mm_crc32_u32(0, d[0] as u32) }\n"
                "}\n"
            ),
        }, languages=("rust",))
        types = {f["conclusion"]["findingType"] for f in report["findings"]}
        self.assertIn("x86_intrinsic_reference", types, types)
        self.assertIn("x86_vector_type_reference", types)
        # 所有 finding 的证据 producer 都是 rust-frontend（cpp 不得误处理 .rs）。
        for finding in report["findings"]:
            for item in finding["fact"]["provenance"]:
                self.assertEqual("rust-frontend", item["producer"])

    def test_domain_is_rust_and_riscv_branch_not_confirmed(self):
        report = self.scan({
            "lib.rs": (
                "#[cfg(target_arch = \"riscv64\")]\n"
                "pub fn rv(d: &[u8]) -> u32 { 0 }\n"
            ),
        }, languages=("rust",))
        self.assertEqual("rust-x86-to-riscv", report["scan"]["scope"]["domain"])
        # 纯 riscv 分支：cfg 求值 True → 不产 x86 依赖观察；仅 structural 候选。
        types = {f["conclusion"]["findingType"] for f in report["findings"]}
        self.assertNotIn("x86_intrinsic_reference", types)
        self.assertNotIn("x86_assembly_instruction", types)

    def test_asm_macro_flows_to_asm_detector(self):
        report = self.scan({
            "lib.rs": 'unsafe fn r() { global_asm!("movaps %xmm0, %xmm1"); }\n',
        }, languages=("rust",))
        types = {f["conclusion"]["findingType"] for f in report["findings"]}
        self.assertIn("x86_assembly_instruction", types, types)

    def test_default_language_excludes_rust_files(self):
        """默认 c,cpp：.rs 文件零证据零 Finding，domain 保持 c-cpp-*。"""
        report = self.scan({
            "lib.rs": "use core::arch::x86_64::*;\nfn f() { unsafe { _mm_add_epi32(1, 2) }; }\n",
            "main.c": "int main(void) { return 0; }\n",
        })
        self.assertEqual([], report["findings"])
        self.assertEqual("c-cpp-x86-to-riscv", report["scan"]["scope"]["domain"])

    def test_mixed_c_rust_scan(self):
        """c+rust 混合：两域证据并存，domain 取第一命中语言（c → c-cpp-*）。"""
        report = self.scan({
            "main.c": "#include <immintrin.h>\nint m(void) { __m256i v = _mm256_setzero_si256(); return 0; }\n",
            "lib.rs": "#[cfg(target_arch = \"x86_64\")]\nfn r() { unsafe { _mm_add_epi32(1, 2) }; }\n",
        }, languages=("c", "rust"))
        symbols = {f["fact"]["symbol"] for f in report["findings"]}
        self.assertIn("_mm256_setzero_si256", symbols, "C 侧 intrinsic 应检出")
        self.assertIn("_mm_add_epi32", symbols, "Rust 侧 intrinsic 应检出")
        self.assertEqual("c-cpp-x86-to-riscv", report["scan"]["scope"]["domain"])
        # Rust 单语言时 domain 切 rust-*。
        rust_only = self.scan({"lib.rs": "fn r() {}\n"}, languages=("rust",))
        self.assertEqual("rust-x86-to-riscv", rust_only["scan"]["scope"]["domain"])

    def test_rust_arm64_source_arch(self):
        """arm64 源 + rust：arm64 NEON intrinsic（R1 词表 2141 项）可检出，domain=rust-arm64-*。"""
        report = self.scan({
            "lib.rs": 'pub fn crc(d: &[u8]) -> u32 { unsafe { __crc32cd(0, d[0] as u64) } }\n',
        }, languages=("rust",), source_arch="arm64")
        self.assertEqual("rust-arm64-to-riscv", report["scan"]["scope"]["domain"])
        symbols = {f["fact"]["symbol"] for f in report["findings"]}
        self.assertIn("__crc32cd", symbols, "arm64 NEON/CRC intrinsic（rust 语料）应检出")

    def test_sample_project_scans(self):
        """samples/rust 样例可全管线扫描（契约冒烟，不钉死输出）。"""
        report = self.scan(None, languages=("rust",))
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "out"
            app = create_default_application()
            with patch("subprocess.run", side_effect=AssertionError("forbidden")):
                result = app.run(ScanRequest(str(SAMPLE), str(ROOT / "rulesets/riscv-porting"),
                                             str(output), languages=("rust",), context_infer="off"))
            self.assertEqual(0, result.exit_code, result.diagnostics)
            sample_report = json.loads((output / "scan-result.json").read_text())
        types = {f["conclusion"]["findingType"] for f in sample_report["findings"]}
        self.assertIn("x86_intrinsic_reference", types)
        self.assertIn("x86_assembly_instruction", types, "global_asm! 宏体应走 asm 检测链")


if __name__ == "__main__":
    unittest.main()
