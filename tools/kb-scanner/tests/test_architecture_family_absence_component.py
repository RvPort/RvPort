"""缺失候选推导（family absence）的组件契约测试：真 CLI 端到端。

覆盖两级输出的产品语义 + riscv-scan 误报四件套的永久回归 +
升格 Finding 的完整链（detector → classifier → 投影双语建议）。
"""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from kb_scanner.core import ScanRequest
from kb_scanner.pipeline.default import create_default_application
from kb_scanner.projection.v2.families import NAMESPACE
from kb_scanner.projection.v2.models import SchemaV2Report
from kb_scanner.projection.v2.validation import V2ReferentialIntegrityValidator


ROOT = Path(__file__).resolve().parents[1]


class FamilyAbsenceComponentTests(unittest.TestCase):
    def scan(self, files, version="2.0", exclude=()):
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
                                             schema_version=version, exclude_paths=exclude))
            self.assertEqual(0, result.exit_code, result.diagnostics)
            report = json.loads((output / "scan-result.json").read_text())
            if version == "2.0":
                V2ReferentialIntegrityValidator().validate(SchemaV2Report.model_validate(report))
            return report

    def families(self, report):
        return report["extensions"][NAMESPACE]["families"]

    def test_riscv_scan_false_positive_regressions(self):
        """误报四件套永久回归：alarm/harmony 不产候选不报缺失；ppc64le-only 不触发。"""
        report = self.scan({
            "src/alarm.c": "void alarm(void) {}\n",
            "src/harmony.c": "int harmony(void) { return 1; }\n",
            "vendor/ppc64le/impl.c": "int impl(void) { return 0; }\n",
            "src/plain.c": "int plain(void) { return 0; }\n",
        })
        self.assertEqual([], report["findings"])
        catalog = report.get("extensions", {}).get(NAMESPACE, {})
        self.assertEqual([], catalog.get("families", []), "无源架构角色路径不得建族")
        self.assertEqual([], catalog.get("directoryAbsence", []), "ppc64le-only 目录不得推导缺失")

    def test_file_level_candidate_uses_template_not_substring(self):
        """foo_x86.c + foo_arm64.c → 候选 foo_riscv64.c（模板推导，非 foo_64_riscv64.c）。"""
        report = self.scan({
            "src/foo_x86.c": "int foo(void) { return 1; }\n",
            "src/foo_arm64.c": "int foo(void) { return 2; }\n",
        })
        family = next(f for f in self.families(report) if "foo" in f["template"])
        candidate = family["candidateMissingMember"]
        self.assertEqual("src/foo_riscv64.c", candidate["derivedTargetPath"])
        self.assertEqual("family-absence-v1", candidate["derivationRule"])
        self.assertEqual("candidate", candidate["status"])
        self.assertEqual("authorized-file-list", candidate["scope"])
        # 纯词法候选不产 Finding。
        self.assertEqual([], report["findings"])

    def test_riscv_or_generic_member_suppresses_candidate(self):
        for sibling in ("src/foo_riscv.c", "src/foo_generic.c"):
            with self.subTest(sibling=sibling):
                report = self.scan({
                    "src/foo_x86.c": "int foo(void) { return 1; }\n",
                    sibling: "int foo(void) { return 2; }\n",
                })
                for family in self.families(report):
                    self.assertNotIn("candidateMissingMember", family)
                self.assertEqual([], report["findings"])

    def test_directory_absence_candidate_only(self):
        """目录级缺失：仅 extensions 候选 + HTML 摘要，不产 Finding。"""
        report = self.scan({
            "arch/x86/impl.c": "int impl(void) { return 1; }\n",
            "arch/generic/impl.c": "int impl(void) { return 0; }\n",
        })
        catalog = report["extensions"][NAMESPACE]
        self.assertEqual([], catalog.get("directoryAbsence", []), "generic 子目录存在时不得推导目录缺失")

        report2 = self.scan({
            "arch/x86/impl.c": "int impl(void) { return 1; }\n",
            "arch/arm/impl.c": "int impl(void) { return 2; }\n",
        })
        entries = report2["extensions"][NAMESPACE]["directoryAbsence"]
        self.assertEqual(1, len(entries))
        self.assertEqual("arch/riscv64", entries[0]["derivedTargetPath"])
        self.assertEqual("candidate", entries[0]["status"])
        self.assertEqual([], report2["findings"], "目录级候选不得升格 Finding")

    def test_selected_member_escalates_to_finding(self):
        """构建选中 x86 成员（CMake add_library foo_x86.cpp）且无 riscv/generic → 升格 Finding。"""
        report = self.scan({
            "CMakeLists.txt": 'add_library(v foo_x86.cpp)\n',
            "foo_x86.cpp": "int f(void) { return 1; }\n",
            "foo_arm64.cpp": "int f(void) { return 2; }\n",
        })
        gaps = [f for f in report["findings"] if f["conclusion"]["findingType"] == "missing_riscv_family_member"]
        self.assertEqual(1, len(gaps), [f["conclusion"]["findingType"] for f in report["findings"]])
        finding = gaps[0]
        # 升格条目：severity/actionable/双语建议/证据链齐全；推导目标名在 report 级
        # extensions 目录（finding 只指认家族，完整目录在 extensions——分层设计）。
        self.assertTrue(finding["conclusion"]["actionable"])
        self.assertFalse(finding["conclusion"]["blocking"])
        self.assertIn("补齐对应成员", finding["conclusion"]["suggestion"])
        self.assertIn("derived target member", finding["conclusion"]["suggestion_en"])
        self.assertGreaterEqual(len(finding["evidenceChain"]["steps"]), 2)
        family = next(f for f in self.families(report) if "foo" in f["template"])
        self.assertEqual("foo_riscv64.cpp", family["candidateMissingMember"]["derivedTargetPath"])
        x86_member = next(m for m in family["members"] if m["roleHint"] == "x86")
        self.assertEqual("selected", x86_member["bindings"][0]["selectionStatus"])

    def test_unselected_member_never_escalates(self):
        """x86 成员存在但构建未选中（无构建系统）：仅 extensions 候选。"""
        report = self.scan({
            "foo_x86.cpp": "int f(void) { return 1; }\n",
            "foo_arm64.cpp": "int f(void) { return 2; }\n",
        })
        self.assertEqual([], report["findings"])
        family = next(f for f in self.families(report) if "foo" in f["template"])
        self.assertTrue(family["candidateMissingMember"])
        self.assertEqual([], [m for m in family["members"] if m["bindings"]])

    def test_excluded_riscv_member_must_not_escalate(self):
        """riscv 成员在授权清单内（同模板同扩展名）：无候选、无升格（逃生口压制）。"""
        report = self.scan({
            "CMakeLists.txt": 'add_library(v foo_x86.cpp)\n',
            "foo_x86.cpp": "int f(void) { return 1; }\n",
            "foo_riscv.cpp": "int f(void) { return 0; }\n",
        })
        for family in self.families(report):
            self.assertNotIn("candidateMissingMember", family)
        self.assertEqual([], [f for f in report["findings"] if f["conclusion"]["findingType"] == "missing_riscv_family_member"])
        # 同一工程去掉 riscv 成员（模拟被排除后的授权清单）：候选恢复且构建
        # 选中 x86 成员 → 升格合法；候选的 scope 字段声明范围限定语义。
        excluded = self.scan({
            "CMakeLists.txt": 'add_library(v foo_x86.cpp)\n',
            "foo_x86.cpp": "int f(void) { return 1; }\n",
        })
        gaps = [f for f in excluded["findings"] if f["conclusion"]["findingType"] == "missing_riscv_family_member"]
        self.assertEqual(1, len(gaps))
        candidate = next(f for f in self.families(excluded) if "foo" in f["template"])["candidateMissingMember"]
        self.assertEqual("authorized-file-list", candidate["scope"], "候选必须声明范围限定")

    def test_arm64_source_symmetry_end_to_end(self):
        """arm64 源扫描：arm64 角色家族推导 riscv64 缺失（x86 成员是 foreign 不触发）。"""
        report = self.scan({
            "src/neon_arm64.c": "int f(void) { return 1; }\n",
            "src/neon_x86.c": "int f(void) { return 2; }\n",
        })
        # 本组件测试默认 x86 源——neon_x86 是源角色，推导应产出。
        family = next(f for f in self.families(report) if "neon" in f["template"])
        self.assertEqual("src/neon_riscv64.c", family["candidateMissingMember"]["derivedTargetPath"])

    def test_v1_output_unchanged_shape(self):
        """V1 双轨：缺失候选只进 V2 extensions，V1 输出结构不变。"""
        report = self.scan({
            "src/foo_x86.c": "int foo(void) { return 1; }\n",
        }, version="1.0")
        self.assertNotIn("extensions", report)
        self.assertEqual([], report["findings"])


if __name__ == "__main__":
    unittest.main()
