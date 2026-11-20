"""D1 目标族预检的单元覆盖（graph 门禁套件内；e2e 版本不进本 inventory）。

_target_family_role / _branch_role 的 riscv 目标宏路径：命中携带知识条目 ID、
族过滤（intrinsics 不参与宏匹配）、negated unknown、源族行为不变。
"""
from pathlib import Path
import unittest

from tests.unit.knowledge_detection.support import make_context

ROOT = Path(__file__).resolve().parents[3]


class TargetFamilyRoleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from kb_scanner.knowledge import GeneratedFactsKnowledgeProvider

        cls.knowledge = GeneratedFactsKnowledgeProvider().load(
            make_context(ROOT, rule_dir=ROOT / "rulesets/riscv-porting")).value

    def test_riscv_macro_hit_carries_entry_ids(self):
        from kb_scanner.analyzers.evidence_graph import _target_family_role

        role, ids = _target_family_role("__riscv_xlen >= 64", self.knowledge)
        self.assertEqual("riscv", role)
        self.assertTrue(ids, "命中必须携带知识条目 ID（可溯源）")

    def test_non_riscv_condition_returns_none(self):
        from kb_scanner.analyzers.evidence_graph import _target_family_role

        self.assertEqual((None, ()), _target_family_role("__x86_64__ || unix", self.knowledge))

    def test_empty_condition_returns_none(self):
        from kb_scanner.analyzers.evidence_graph import _target_family_role

        self.assertEqual((None, ()), _target_family_role("", self.knowledge))

    def test_riscv_intrinsics_do_not_match_macros(self):
        """kind 过滤：riscv intrinsics（rust 语料）不参与宏匹配——
        同名 token 也不得经 platformMacros 通道命中（构造快照直接验证）。"""
        from types import SimpleNamespace
        from kb_scanner.analyzers.evidence_graph import _target_family_role

        entry = SimpleNamespace(architecture_family="riscv", fact_kind="intrinsics",
                                canonical_value="aes64ds", id="e-intrinsics")
        snapshot = SimpleNamespace(entries=(entry,))
        self.assertEqual((None, ()), _target_family_role("aes64ds", snapshot))

    def test_branch_role_negated_target_macro_is_unknown(self):
        from kb_scanner.analyzers.evidence_graph import _branch_role

        role, _ = _branch_role("__riscv_xlen", self.knowledge, fallback=False,
                               negated=True, architecture_family="x86")
        self.assertEqual("unknown", role)

    def test_branch_role_source_family_unchanged(self):
        from kb_scanner.analyzers.evidence_graph import _branch_role

        role, _ = _branch_role("__x86_64__", self.knowledge, fallback=False, architecture_family="x86")
        self.assertEqual("x86", role)


if __name__ == "__main__":
    unittest.main()
