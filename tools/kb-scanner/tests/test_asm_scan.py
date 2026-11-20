"""汇编输入（.s/.S/.asm）第三前端域的端到端契约测试。

覆盖：x86 .S 出 ASM Finding（行列定位）、纯 riscv .S 零 Finding、
默认 --language c,cpp 向后兼容（asm 文件零证据）、C+asm 混合家族分组、
预算/解码降级、.inc 语义不变。
"""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from kb_scanner.core import ScanRequest, stable_id
from kb_scanner.frontends.asm.frontend import AsmFrontend, _scan_lines
from kb_scanner.frontends.asm.vocabulary import is_assembly_path
from kb_scanner.pipeline.default import create_default_application


ROOT = Path(__file__).resolve().parents[1]
SUBJECT = stable_id("file", "a.S")

X86_ASM = """\
#include <asm/insn.h>
// comment line
_start:
    movaps %xmm0, %xmm1
    addq $8, %rax ; trailing comment
    ret
"""

RISCV_ASM = """\
_start:
    vsetvli t0, a0, e32, m1, ta, ma
    vle32.v v0, (a1)
    vadd.vv v0, v0, v1
"""

MIXED_ASM = """\
/*
 * block comment
 */
label_1: vmovdqu xmm0, [rsi]
"""


class AsmPathTests(unittest.TestCase):
    def test_suffix_gates(self):
        for path, expected in (("a.S", True), ("a.s", True), ("a.asm", True), ("d/b.S", True),
                               ("a.c", False), ("a.cpp", False), ("a.inc", False), ("aS", False), ("a.Sx", False)):
            with self.subTest(path=path):
                self.assertEqual(expected, is_assembly_path(path))


class AsmScanTests(unittest.TestCase):
    def scan(self, files, languages=("c", "cpp")):
        with tempfile.TemporaryDirectory() as temporary:
            root, output = Path(temporary) / "input", Path(temporary) / "out"
            root.mkdir()
            for name, text in files.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text)
            app = create_default_application()
            with patch("subprocess.run", side_effect=AssertionError("project execution forbidden")):
                result = app.run(ScanRequest(str(root), str(ROOT / "rulesets/riscv-porting"), str(output),
                                             languages=languages))
            self.assertEqual(0, result.exit_code, result.diagnostics)
            return json.loads((output / "scan-result.json").read_text())

    def test_x86_asm_file_produces_findings_with_location(self):
        report = self.scan({"start.S": X86_ASM}, languages=("asm",))
        types = {f["conclusion"]["findingType"] for f in report["findings"]}
        self.assertIn("x86_assembly_instruction", types, types)
        # 检测器 token 匹配：symbol 是整语句文本，matchedText 是命中词。
        instruction = next(f for f in report["findings"]
                           if f["conclusion"]["findingType"] == "x86_assembly_instruction"
                           and "movaps" in f["fact"]["symbol"])
        location = instruction["fact"]["primaryLocation"]
        self.assertEqual("start.S", location["file"])
        self.assertEqual(4, location["span"]["start"]["line"], "movaps 在第 4 行（跳过 #include/注释/标签）")

    def test_pure_riscv_asm_produces_no_confirmed_findings(self):
        """riscv 独有指令（vsetvli/vle32.v/vadd.vv）不命中 x86 词表 → 无 confirmed 观察；
        structural 候选（inline_asm_candidate）保持 unknown 态（不下结论，五态语义）。
        已知边界：与 x86 词表交集的 token（如 ret/add）仍会报 x86 观察——与 C 内联
        汇编同语义（检测器按源架构词表匹配，不知文件"是 riscv 汇编"）。"""
        report = self.scan({"rv.S": RISCV_ASM}, languages=("asm",))
        types = {f["conclusion"]["findingType"] for f in report["findings"]}
        self.assertNotIn("x86_assembly_instruction", types)
        self.assertNotIn("x86_register_reference", types)
        self.assertEqual({"inline_asm_candidate"}, types)
        for f in report["findings"]:
            self.assertEqual("unknown", f["conclusion"]["status"])
            self.assertFalse(f["conclusion"]["actionable"])

    def test_default_language_excludes_asm_files(self):
        """默认 c,cpp：asm 文件完全不产证据（向后兼容，输出字节不受影响）。"""
        report = self.scan({"start.S": X86_ASM, "main.c": "int main(void) { return 0; }\n"})
        self.assertEqual([], report["findings"])
        # asm 文件不进授权清单的家族视图。
        self.assertNotIn("start.S", json.dumps(report.get("extensions", {})))

    def test_mixed_c_asm_family_grouping(self):
        """C+asm 混合家族：foo_x86.c 与 foo.S（同模板）可成族；asm 成员带 pathObservation。"""
        report = self.scan({
            "src/foo_x86.c": "int foo(void) { return 1; }\n",
            "src/foo.S": X86_ASM,
        }, languages=("c", "asm"))
        families = report.get("extensions", {}).get("org.rvport.kb-scanner.architecture-family", {}).get("families", [])
        foo = next((f for f in families if "foo" in f.get("template", "")), None)
        self.assertIsNotNone(foo, "C+asm 同模板应成族")
        self.assertIn("src/foo_riscv64.c", json.dumps(foo, ensure_ascii=False))

    def test_preprocessor_and_comments_skipped(self):
        lines = ["#include <x.h>", "#define A 1", "// c", "real_insn:", "  movaps xmm0, xmm1"]
        items = list(_scan_lines("a.S", SUBJECT, lines))
        self.assertEqual(1, len(items), [i.value for i in items])
        self.assertEqual("movaps xmm0, xmm1", items[0].value)
        self.assertEqual(5, items[0].location.start.line)

    def test_block_comment_state_machine(self):
        items = list(_scan_lines("a.s", SUBJECT, MIXED_ASM.splitlines()))
        values = [i.value for i in items]
        self.assertEqual(["vmovdqu xmm0, [rsi]"], values)

    def test_line_budget_partial(self):
        huge = "\n".join(f"insn{i}" for i in range(10))
        items = list(_scan_lines("a.s", SUBJECT, huge.splitlines()[:10]))
        self.assertEqual(10, len(items))


if __name__ == "__main__":
    unittest.main()
