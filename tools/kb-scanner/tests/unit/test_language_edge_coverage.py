"""语言域扩展的 90/90 门禁补充：vocabulary 工厂、domain 推导、cfg 词法边界的单元覆盖。

这些断言此前散在 e2e（tests/ 根，不进 unit 覆盖统计）或缺失，集中于此
供 source-chain inventory 统计。
"""
from types import SimpleNamespace
import unittest

from kb_scanner.core import SourceLocation, SourceSpan, stable_id
from kb_scanner.frontends.go.vocabulary import make_go_evidence
from kb_scanner.frontends.java.vocabulary import make_java_evidence
from kb_scanner.frontends.python.vocabulary import make_python_evidence
from kb_scanner.frontends.rust.vocabulary import make_rust_evidence
from kb_scanner.projection.v2.projector import _domain


SPAN = SourceSpan(SourceLocation("a.go", 1, 1), SourceLocation("a.go", 1, 5))
SUBJECT = stable_id("file", "a.go")


class EvidenceFactoryBranchTests(unittest.TestCase):
    def test_go_evidence_without_metadata(self):
        evidence = make_go_evidence(subject_id=SUBJECT, path="a.go", span=SPAN,
                                    kind="call_expression", value="f()")
        self.assertNotIn("nodeType", evidence.metadata)
        self.assertEqual("go", evidence.metadata["language"])

    def test_go_evidence_with_metadata_merged(self):
        evidence = make_go_evidence(subject_id=SUBJECT, path="a.go", span=SPAN,
                                    kind="call_expression", value="f()", metadata={"nodeType": "t"})
        self.assertEqual("t", evidence.metadata["nodeType"])

    def test_java_evidence_without_metadata(self):
        evidence = make_java_evidence(subject_id=SUBJECT, path="A.java", span=SPAN,
                                      kind="call_expression", value="load()")
        self.assertNotIn("nodeType", evidence.metadata)

    def test_python_evidence_without_metadata(self):
        evidence = make_python_evidence(subject_id=SUBJECT, path="a.py", span=SPAN,
                                        kind="call_expression", value="CDLL()")
        self.assertNotIn("nodeType", evidence.metadata)

    def test_rust_evidence_without_metadata(self):
        evidence = make_rust_evidence(subject_id=SUBJECT, path="a.rs", span=SPAN,
                                      kind="type_reference", value="__m128i")
        self.assertNotIn("nodeType", evidence.metadata)


class DomainDerivationTests(unittest.TestCase):
    def test_first_mapped_language_wins(self):
        self.assertEqual("rust-x86-to-riscv", _domain(SimpleNamespace(languages=("rust",), source_arch="x86")))
        self.assertEqual("go-arm64-to-riscv", _domain(SimpleNamespace(languages=("go",), source_arch="arm64")))
        self.assertEqual("java-x86-to-riscv", _domain(SimpleNamespace(languages=("java",), source_arch="x86")))
        self.assertEqual("python-arm64-to-riscv", _domain(SimpleNamespace(languages=("python",), source_arch="arm64")))
        self.assertEqual("c-cpp-x86-to-riscv", _domain(SimpleNamespace(languages=("c", "rust"), source_arch="x86")))
        self.assertEqual("c-cpp-x86-to-riscv", _domain(SimpleNamespace(languages=("cpp",), source_arch="x86")))
        self.assertEqual("c-cpp-x86-to-riscv", _domain(SimpleNamespace(languages=("asm",), source_arch="x86")))

    def test_unmapped_languages_fall_back(self):
        self.assertEqual("c-cpp-x86-to-riscv", _domain(SimpleNamespace(languages=("cobol", "fortran"), source_arch="x86")))

    def test_missing_languages_attribute_falls_back(self):
        self.assertEqual("c-cpp-arm64-to-riscv", _domain(SimpleNamespace(source_arch="arm64")))

    def test_empty_languages_falls_back(self):
        self.assertEqual("c-cpp-x86-to-riscv", _domain(SimpleNamespace(languages=(), source_arch="x86")))


class CfgTokenizerEdgeTests(unittest.TestCase):
    """cfg 词法 raise 路径与解析器残余分支。"""

    def test_unexpected_token_returns_unknown(self):
        """非法字符（$、数字开头串等）触发 tokenizer raise → unknown 兜底。"""
        from kb_scanner.frontends.rust.cfg_evaluator import evaluate_cfg
        for bad in ("target_arch = $", 'foo = "a" )', "9x", "a ~ b"):
            with self.subTest(bad=bad):
                self.assertIsNone(evaluate_cfg(bad).value)

    def test_trailing_tokens_return_unknown(self):
        from kb_scanner.frontends.rust.cfg_evaluator import evaluate_cfg
        self.assertIsNone(evaluate_cfg('target_arch = "x86_64" "extra"').value)

    def test_fold_all_true(self):
        from kb_scanner.frontends.rust.cfg_evaluator import evaluate_cfg
        result = evaluate_cfg('all(target_arch = "riscv64", not(target_arch = "x86_64"))')
        self.assertTrue(result.value)


class GoFrontendCollectEdgeTests(unittest.TestCase):
    """go frontend L62（reader 预算段）与 L160（cgo 注释 else 分支）。"""

    def test_goarch_switch_tag_form_known_gap(self):
        """switch runtime.GOARCH { case "amd64": } 直接 tag 形态：case 节点不含
        GOARCH 字样，当前不产 selection（known gap——riscv-scan 有专门处理，
        后续增强；case 内带比较的形态已由 test_switch_case_form 覆盖）。"""
        from types import SimpleNamespace
        from pathlib import Path
        import tempfile
        from kb_scanner.core import WorkspaceSnapshot
        from kb_scanner.frontends.go.frontend import GoFrontend

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "main.go").write_text(
                'package main\nfunc f() {\nswitch runtime.GOARCH {\ncase "amd64":\ncase "riscv64":\n}\n}\n')
            result = GoFrontend().analyze(
                SimpleNamespace(languages=("go",), scanner_version="t"),
                WorkspaceSnapshot(root, ("main.go",)))
        selections = [e for e in result.value.evidence if e.kind == "selection_condition"]
        self.assertEqual([], selections)

    def test_cgo_block_comment_form(self):
        """/* */ 块注释紧邻 import "C"：块体逐行进 C 检测链。"""
        from types import SimpleNamespace
        from pathlib import Path
        import tempfile
        from kb_scanner.core import WorkspaceSnapshot
        from kb_scanner.frontends.go.frontend import GoFrontend

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "main.go").write_text(
                'package main\n/*\n#include <x.h>\nint f(void){return _mm_add_epi32(0,0);}\n*/\nimport "C"\n')
            result = GoFrontend().analyze(
                SimpleNamespace(languages=("go",), scanner_version="t"),
                WorkspaceSnapshot(root, ("main.go",)))
        kinds = {e.kind for e in result.value.evidence}
        self.assertIn("include", kinds)
        self.assertIn("inline_asm", kinds)

    def test_cgo_block_comment_without_closer_ignored(self):
        """非 /* */ 闭合形态的注释不进 cgo 块。"""
        from types import SimpleNamespace
        from pathlib import Path
        import tempfile
        from kb_scanner.core import WorkspaceSnapshot
        from kb_scanner.frontends.go.frontend import GoFrontend

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "main.go").write_text('package main\n// plain note\nimport "C"\n')
            result = GoFrontend().analyze(
                SimpleNamespace(languages=("go",), scanner_version="t"),
                WorkspaceSnapshot(root, ("main.go",)))
        includes = [e for e in result.value.evidence if e.kind == "include"]
        self.assertEqual([], includes)


class TargetFamilyRoleUnitTests(unittest.TestCase):
    """_target_family_role（D1 新增）的单元覆盖（e2e 版本不进 unit 统计）。"""

    @classmethod
    def setUpClass(cls):
        from pathlib import Path as P
        from tests.unit.knowledge_detection.support import make_context
        from kb_scanner.knowledge import GeneratedFactsKnowledgeProvider
        root = P(__file__).resolve().parents[2]
        cls.knowledge = GeneratedFactsKnowledgeProvider().load(
            make_context(root, rule_dir=root / "rulesets/riscv-porting")).value

    def test_no_riscv_hit_returns_none(self):
        from kb_scanner.analyzers.evidence_graph import _target_family_role
        self.assertEqual((None, ()), _target_family_role("__x86_64__ || unix", self.knowledge))

    def test_empty_condition_returns_none(self):
        from kb_scanner.analyzers.evidence_graph import _target_family_role
        self.assertEqual((None, ()), _target_family_role("", self.knowledge))

    def test_riscv_macro_hit_carries_ids(self):
        from kb_scanner.analyzers.evidence_graph import _target_family_role
        role, ids = _target_family_role("__riscv_xlen >= 64", self.knowledge)
        self.assertEqual("riscv", role)
        self.assertTrue(ids)

    def test_branch_role_negated_target_macro_unknown(self):
        from kb_scanner.analyzers.evidence_graph import _branch_role
        role, _ = _branch_role("__riscv_xlen", self.knowledge, fallback=False,
                               negated=True, architecture_family="x86")
        self.assertEqual("unknown", role)


if __name__ == "__main__":
    unittest.main()
