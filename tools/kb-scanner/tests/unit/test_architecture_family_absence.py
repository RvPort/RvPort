"""缺失候选推导（absence）与升格链的单测：合成快照，无解析器/命令/源码读取。

误报防线四件套（riscv-scan 黑盒实测误报的零复发回归）+ 两级输出语义 +
arm64 源对称性 + 升格 proof 复现与预算/歧义防线。
"""
from dataclasses import replace
import unittest

from kb_scanner.core import AnalysisSnapshot, Entity, Evidence, NormalizedFact, NormalizedSnapshot, ProofPath, Relation, SourceLocation, SourceSpan, stable_id
from kb_scanner.analyzers.architecture_family.absence import analyse, derive_target_path
from kb_scanner.analyzers.architecture_family.grouping import FamilyBudget, discover
from kb_scanner.analyzers.architecture_family.bindings import bind


def sources(*paths):
    return tuple(Entity(stable_id("file", p), "source_file", p, properties={"pathObservation": {
        "path": p, "scope": "authorized-file-list", "parseStatus": "unknown"}}) for p in paths)


def selected_fixture(paths, selected_path):
    """构造最小升格场景：selector --selects--> selected_path（evidence 实存）。"""
    files = sources(*paths)
    root = Entity(stable_id("root"), "architecture_selector", "riscv64")
    target = next(f for f in files if f.name == selected_path)
    span = SourceSpan(SourceLocation("CMakeLists.txt", 1, 1), SourceLocation("CMakeLists.txt", 1, 20))
    ev = Evidence(stable_id("ev"), "build_source", root.id, selected_path, "cmake", "cmake", "high", span)
    fact = NormalizedFact(stable_id("fact"), "build_source", root.id, selected_path, "unit", (ev.id,))
    relation = Relation(stable_id("selects"), "selects", root.id, target.id, (ev.id,), status="selected",
                        metadata={"configuration": "rv", "factId": fact.id})
    proof = ProofPath(stable_id("proof"), root.id, target.id, (relation.id,), "complete")
    facts = NormalizedSnapshot((*files, root), (ev,), (fact,))
    base = AnalysisSnapshot((relation,), (*files, root), (proof,))
    return files, facts, base


class AbsenceDerivationTests(unittest.TestCase):
    def test_false_positive_regressions_never_fire(self):
        """误报四件套：alarm/harmony 不产候选；无词边界中缀永不命中。"""
        catalog = discover(sources("alarm.c", "harmony.c", "plain.cpp"), FamilyBudget())
        self.assertEqual([], catalog["families"], "无源架构角色路径不得建族")
        _, _, _, issues = analyse(catalog, NormalizedSnapshot(), AnalysisSnapshot(), "x86", "riscv64", FamilyBudget())
        self.assertEqual((), issues)

    def test_derivation_consumes_slot_not_substring(self):
        """foo_arm64.c（x86 源下 arm=foreign）与 foo_x86.c 推导 foo_riscv64.c，非 foo_64_riscv64.c。"""
        files = sources("src/foo_x86.c", "src/foo_arm64.c")
        catalog = discover(files, FamilyBudget())
        family = next(f for f in catalog["families"] if "foo" in f["template"])
        analyse(catalog, NormalizedSnapshot(), AnalysisSnapshot(), "x86", "riscv64", FamilyBudget())
        self.assertEqual("src/foo_riscv64.c", family["candidateMissingMember"]["derivedTargetPath"])
        self.assertEqual("family-absence-v1", family["candidateMissingMember"]["derivationRule"])
        self.assertEqual("candidate", family["candidateMissingMember"]["status"])

    def test_directory_slot_derives_riscv64_directory(self):
        files = sources("arch/x86/impl.c", "arch/generic/impl.c", "arch/ppc64le/impl.c")
        catalog = discover(files, FamilyBudget())
        analyse(catalog, NormalizedSnapshot(), AnalysisSnapshot(), "x86", "riscv64", FamilyBudget())
        family = next(f for f in catalog["families"] if "impl" in f["template"])
        # generic 成员存在：可移植逃生口压制，不产缺失候选。
        self.assertNotIn("candidateMissingMember", family)

    def test_directory_absence_reports_only_source_arch_parents(self):
        """目录级：x86 子目录存在且无 riscv → 候选；ppc64le-only 父目录不报。"""
        files = sources("a/x86/impl.c", "b/ppc64le/impl.c", "c/riscv/impl.c")
        catalog = discover(files, FamilyBudget())
        analyse(catalog, NormalizedSnapshot(), AnalysisSnapshot(), "x86", "riscv64", FamilyBudget())
        entries = catalog["directoryAbsence"]
        self.assertEqual(["a"], [e["parentPath"] for e in entries])
        self.assertEqual("a/riscv64", entries[0]["derivedTargetPath"])
        # ppc64le 是 unlabelled：不触发也不计入 existingRoles。
        self.assertNotIn("b", [e["parentPath"] for e in entries])

    def test_riscv_or_generic_member_suppresses(self):
        for extra in ("foo_riscv.c", "foo_generic.c"):
            with self.subTest(extra=extra):
                catalog = discover(sources(f"src/foo_x86.c", f"src/{extra}"), FamilyBudget())
                analyse(catalog, NormalizedSnapshot(), AnalysisSnapshot(), "x86", "riscv64", FamilyBudget())
                self.assertNotIn("candidateMissingMember", catalog["families"][0])

    def test_arm64_source_symmetry(self):
        """arm64 源：arm64 角色触发推导（x86 是 foreign 不触发）；目标恒 riscv64。"""
        catalog = discover(sources("simd/arm64/neon.c", "simd/x86/neon.c"), FamilyBudget(), "arm64")
        analyse(catalog, NormalizedSnapshot(), AnalysisSnapshot(), "arm64", "riscv64", FamilyBudget())
        family = catalog["families"][0]
        # arm64 源下：arm64 是源角色，x86 是 foreign；目录槽推导父目录/riscv64。
        roles = {m["roleHint"] for m in family["members"]}
        self.assertIn("arm64", roles)
        self.assertIn("foreign", roles)
        self.assertNotIn("riscv", roles)
        self.assertTrue(family.get("candidateMissingMember"))
        self.assertEqual("simd/riscv64", family["candidateMissingMember"]["derivedTargetPath"])

    def test_ambiguous_family_never_derives(self):
        catalog = discover(sources("x86/foo_generic.cpp"), FamilyBudget())
        analyse(catalog, NormalizedSnapshot(), AnalysisSnapshot(), "x86", "riscv64", FamilyBudget())
        for family in catalog["families"]:
            self.assertNotIn("candidateMissingMember", family, "歧义家族不得推导")


class AbsenceEscalationTests(unittest.TestCase):
    def test_selected_member_escalates_with_complete_proof(self):
        """构建选中 x86 成员且无 riscv/generic：升格 family_gap + selects + complete proof。"""
        files, facts, base = selected_fixture(("foo_x86.cpp", "foo_arm64.cpp"), "foo_x86.cpp")
        catalog = discover(files, FamilyBudget())
        bind(catalog, facts, base, FamilyBudget())
        gap_entities, gap_relations, gap_proofs, issues = analyse(catalog, facts, base, "x86", "riscv64", FamilyBudget())
        self.assertEqual((), issues)
        self.assertEqual(1, len(gap_entities))
        entity = next(iter(gap_entities.values()))
        self.assertEqual("family_gap", entity.kind)
        self.assertEqual("missing_riscv_family_member", entity.properties["issue"])
        relation = gap_relations[0]
        self.assertEqual("selects", relation.kind)
        self.assertEqual("missing_riscv_family_member", relation.metadata["issue"])
        self.assertEqual("foo_riscv64.cpp", relation.metadata["derivedTargetPath"])
        self.assertTrue(relation.evidence_ids, "升格关系必须携带实存证据")
        proof = gap_proofs[0]
        self.assertEqual("complete", proof.status)
        self.assertEqual((relation.id,), proof.relation_ids)
        # selector 可在快照实体中落实（链可复现）。
        self.assertIn(relation.source_id, {e.id for e in base.entities})

    def test_unselected_member_stays_candidate_only(self):
        """无选中绑定：只产 extensions 候选，无升格三元组。"""
        files = sources("foo_x86.cpp", "foo_arm64.cpp")
        facts = NormalizedSnapshot(files, (), ())
        base = AnalysisSnapshot((), files, ())
        catalog = discover(files, FamilyBudget())
        gap_entities, gap_relations, gap_proofs, _ = analyse(catalog, facts, base, "x86", "riscv64", FamilyBudget())
        self.assertEqual({}, gap_entities)
        self.assertEqual([], gap_relations)
        self.assertEqual([], gap_proofs)
        self.assertTrue(catalog["families"][0].get("candidateMissingMember"))

    def test_dangling_proof_reference_degrades_to_candidate(self):
        """成员绑定引用悬空（relationIds 失效）：停候选层并记码，不发悬空引用。"""
        files, facts, base = selected_fixture(("foo_x86.cpp",), "foo_x86.cpp")
        # bind 正常写入绑定；随后令 base 的 relations 失效（模拟上游快照损坏）。
        catalog = discover(files, FamilyBudget())
        bind(catalog, facts, base, FamilyBudget())
        broken = replace(base, relations=())
        gap_entities, gap_relations, gap_proofs, issues = analyse(catalog, facts, broken, "x86", "riscv64", FamilyBudget())
        self.assertEqual({}, gap_entities)
        self.assertEqual([], gap_relations)
        self.assertIn("family-absence-escalation-skipped", issues)
        self.assertTrue(catalog["families"][0].get("candidateMissingMember"), "候选层仍然产出")

    def test_absence_budget_truncates_with_reason_code(self):
        files = sources(*[f"m{i}_x86.cpp" for i in range(4)])
        catalog = discover(files, FamilyBudget())
        _, _, _, issues = analyse(catalog, NormalizedSnapshot(files, (), ()), AnalysisSnapshot((), files, ()),
                                  "x86", "riscv64", FamilyBudget(absence_candidates=2))
        self.assertIn("family-absence-budget", issues)
        derived = [f for f in catalog["families"] if f.get("candidateMissingMember")]
        self.assertEqual(2, len(derived))


class DeriveTargetPathTests(unittest.TestCase):
    def test_filename_affix_rules(self):
        # 模板已剥离 token：filename:src/foo.c → src/foo_riscv64.c（无论锚是 foo_x86 还是 foo_arm64）。
        for template, anchor_path, token, expected in (
            ("filename:src/foo.c", "src/foo_arm64.c", "arm64", "src/foo_riscv64.c"),
            ("filename:foo.cmake", "foo-x86.cmake", "x86", "foo_riscv64.cmake"),
            ("filename:.h", "aarch64.h", "aarch64", "_riscv64.h"),
        ):
            with self.subTest(anchor=anchor_path):
                family = {"template": template}
                member = {"path": anchor_path, "token": token, "rule": "filename-affix-v1"}
                self.assertEqual(expected, derive_target_path(family, member))

    def test_directory_slot_rule(self):
        family = {"template": "directory:arch/{architecture}/impl.c"}
        member = {"path": "arch/x86/impl.c", "token": "x86", "rule": "directory-slot-v1"}
        self.assertEqual("arch/riscv64", derive_target_path(family, member))

    def test_unknown_rule_returns_none(self):
        family = {"template": "filename:a.cpp"}
        self.assertIsNone(derive_target_path(family, {"path": "a.cpp", "token": "", "rule": "unlabelled-v1"}))
        self.assertIsNone(derive_target_path(family, {"path": "a.cpp", "token": "", "rule": "multiple-role-slots-v1"}))


if __name__ == "__main__":
    unittest.main()
