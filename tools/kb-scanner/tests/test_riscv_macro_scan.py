"""RISC-V 目标侧宏词表（collected-riscv-entries）消费的端到端契约测试。

覆盖四件事：
1. `#ifdef __riscv*` 条件分支被判定为 riscv 角色（目标族词表命中），并与
   x86 分支建立 alternative 关系；
2. 已有 riscv 分支的源文件不触发"缺 RISC-V 选择路径"类误报（双向推理收益）；
3. 族隔离：riscv 条目不被任何源族检测器命中（不出 x86_/arm64_ 观察种类）；
4. `_branch_role` 单元级行为：negated 目标宏返回 unknown（五态纪律）。
"""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from kb_scanner.adapters.cli import StagedContextFactory
from kb_scanner.core import ScanRequest
from kb_scanner.knowledge import GeneratedFactsKnowledgeProvider
from kb_scanner.pipeline.default import create_default_application


ROOT = Path(__file__).resolve().parents[1]
RULES = ROOT / "rulesets" / "riscv-porting"


def _request(source: Path, output: Path) -> ScanRequest:
    return ScanRequest(
        input_path=str(source),
        rules_path=str(RULES),
        output_path=str(output),
        formats=("json",),
        context_infer="off",
        source_arch="x86",
    )


class RiscvMacroScanTests(unittest.TestCase):
    def setUp(self) -> None:
        self.sample = Path(tempfile.mkdtemp(prefix="riscv-macro-"))
        (self.sample / "dispatch.c").write_text(
            """#include <immintrin.h>

int vec_add(int *a, int *b, int n) {
#ifdef __x86_64__
    __m256i va = _mm256_loadu_si256((const __m256i *)a);
    __m256i vb = _mm256_loadu_si256((const __m256i *)b);
    __m256i vc = _mm256_add_epi32(va, vb);
    _mm256_storeu_si256((__m256i *)a, vc);
    return 0;
#elif __riscv_xlen >= 64
    /* RVV 1.0 路径已具备 */
    return 0;
#else
    return 1;
#endif
}
""",
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        import shutil

        shutil.rmtree(self.sample, ignore_errors=True)

    def run_scan(self) -> dict:
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "out"
            result = create_default_application().run(_request(self.sample, output))
            self.assertEqual(0, result.exit_code, result.diagnostics)
            return json.loads((output / "scan-result.json").read_text(encoding="utf-8"))

    def test_riscv_macro_branch_is_recognized_and_paired_with_x86(self) -> None:
        """`#elif __riscv_xlen` 分支判为 riscv：与 x86 分支构成 alternative，x86 侧仍被检出。"""
        report = self.run_scan()
        extensions = report.get("extensions", {})
        families = extensions.get("org.rvport.kb-scanner.architecture-family", {})
        # 宏条件分支角色判定发生在证据图层，其结果驱动 alternative 关系与
        # build_selection 的"已有 RISC-V 路径"豁免——间接断言：
        # 1) x86 侧 intrinsic 仍被检出（词表照常工作）；
        types = {finding["conclusion"]["findingType"] for finding in report["findings"]}
        self.assertIn("x86_intrinsic_reference", types)
        # 2) 不出"缺 RISC-V 选择路径"（riscv 分支已观察到）。
        self.assertNotIn("missing_riscv_selection_path", types)

    def test_riscv_target_entries_never_hit_source_detectors(self) -> None:
        """族隔离：__riscv* 条目不产生任何源族观察（不把目标宏报成迁移障碍）。"""
        report = self.run_scan()
        for finding in report["findings"]:
            conclusion = finding["conclusion"]
            symbol = str(finding["fact"].get("symbol", ""))
            self.assertFalse(
                symbol.startswith("__riscv"),
                f"riscv 目标宏被源族检测器命中: {symbol} ({conclusion['findingType']})",
            )

    def test_branch_role_unit_semantics(self) -> None:
        """_branch_role 单元级：目标宏命中返回 riscv + 知识条目；negated 返回 unknown。"""
        from kb_scanner.analyzers.evidence_graph import _branch_role
        from tests.unit.knowledge_detection.support import make_context

        context = make_context(ROOT, rule_dir=RULES)
        knowledge = GeneratedFactsKnowledgeProvider().load(context).value
        role, ids = _branch_role("__riscv_xlen >= 64", knowledge, fallback=False, architecture_family="x86")
        self.assertEqual("riscv", role)
        self.assertTrue(ids, "目标宏命中必须携带知识条目 ID")
        role_neg, _ = _branch_role("__riscv_xlen >= 64", knowledge, fallback=False, negated=True, architecture_family="x86")
        self.assertEqual("unknown", role_neg)
        # 源族条件行为不变：__x86_64__ 仍判 x86。
        role_x86, _ = _branch_role("__x86_64__", knowledge, fallback=False, architecture_family="x86")
        self.assertEqual("x86", role_x86)


if __name__ == "__main__":
    unittest.main()
