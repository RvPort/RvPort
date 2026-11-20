import importlib.util
import json
import os
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).parents[2] / "main/resources/analysis/coverage_gate.py"
SPEC = importlib.util.spec_from_file_location("coverage_gate", SCRIPT)
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


class CoverageGateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.write("pom.xml", "<project/>")
        self.write("src/main/java/cn/rvport/backend/sample/A.java", "class A {}")
        self.write("src/main/java/cn/rvport/backend/jooq/T.java", "generated")
        self.write("src/main/resources/analysis/a.py", "value = 1")
        self.write("agent-cli/node_modules/dependency/a.py", "third_party = 1")
        self.receipt = self.root / "receipt.json"
        # 独立控制测试起点，避免文件系统时间粒度导致同一时钟刻度内写入的偶发失败。
        now = time.time_ns()
        with patch.object(GATE.time, "time_ns", return_value=now - 1_000_000_000):
            GATE.start(self.root, self.receipt)
        self.started = json.loads(self.receipt.read_text())["startedNs"]
        self.write("target/classes/cn/rvport/backend/sample/A.class", "compiled fixture")
        self.write("target/classes/cn/rvport/backend/jooq/T.class", "generated fixture")
        self.write("target/jacoco.exec", "execution fixture")
        self.write("target/site/jacoco/index.html", "html fixture")
        self.xml = ('<report><sessioninfo start="' + str(time.time_ns() // 1_000_000 + 1) + '"/>'
                    '<package name="cn/rvport/backend/sample"><class name="cn/rvport/backend/sample/A"/>'
                    '<sourcefile name="A.java"/><counter type="LINE" covered="9" missed="1"/>'
                    '<counter type="BRANCH" covered="9" missed="1"/></package>'
                    '<counter type="LINE" covered="9" missed="1"/>'
                    '<counter type="BRANCH" covered="9" missed="1"/></report>')
        self.write("target/site/jacoco/jacoco.xml", self.xml)
        self.suite = "target/surefire-reports/TEST-A.xml"
        self.write(self.suite, '<testsuite tests="2" failures="0" errors="0" skipped="1"/>')
        self.python = {"meta": {"branch_coverage": True}, "files": {
            "src/main/resources/analysis/a.py": {"summary": {
                "covered_lines": 9, "num_statements": 10, "covered_branches": 0, "num_branches": 0}}}}
        self.python_path = self.write("python.json", json.dumps(self.python))

    def write(self, relative, content):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def test_valid_reports_record_numerators_and_zero_branch_denominator(self):
        result = GATE.verify(self.root, self.receipt, self.python_path)
        self.assertEqual(result["java"]["tests"], 2)
        self.assertEqual(result["java"]["overall"]["LINE"]["percent"], 90)
        self.assertEqual(result["python"]["overall"]["BRANCH"]["total"], 0)
        self.assertIsNone(result["python"]["overall"]["BRANCH"]["percent"])
        self.assertEqual(len(result["python"]["files"]), 1)
        self.assertNotIn("python", GATE.verify(self.root, self.receipt))

    def test_missing_empty_and_stale_artifacts_fail(self):
        for name in ("target/jacoco.exec", "target/site/jacoco/index.html", "target/site/jacoco/jacoco.xml", self.suite):
            path = self.root / name
            original = path.read_bytes()
            path.unlink()
            with self.assertRaises(ValueError):
                GATE.verify(self.root, self.receipt)
            path.write_bytes(b"")
            with self.assertRaisesRegex(ValueError, "缺失或为空"):
                GATE.verify(self.root, self.receipt)
            path.write_bytes(original)
            os.utime(path, ns=(self.started - 1, self.started - 1))
            with self.assertRaisesRegex(ValueError, "早于"):
                GATE.verify(self.root, self.receipt)
            path.write_bytes(original)

    def test_no_successful_tests_and_failure_are_not_valid_coverage(self):
        for suite in ('<testsuite tests="0"/>', '<testsuite tests="3" skipped="3"/>',
                      '<testsuite tests="3" failures="1"/>', '<testsuite tests="3" errors="1"/>'):
            self.write(self.suite, suite)
            with self.assertRaisesRegex(ValueError, "没有成功执行"):
                GATE.verify(self.root, self.receipt)

    def test_collection_session_and_source_module_completeness(self):
        variants = [self.xml.replace("report", "other"),
                    self.xml.replace('<sessioninfo start="', '<unused start="'),
                    self.xml.replace('start="' + str(ET_START(self.xml)) + '"', 'start="0"'),
                    self.xml.replace('sample/A"', 'sample/Missing"')]
        for text in variants:
            self.write("target/site/jacoco/jacoco.xml", text)
            with self.assertRaises(ValueError):
                GATE.verify(self.root, self.receipt)
        self.write("target/site/jacoco/jacoco.xml", self.xml)
        self.write("src/main/java/cn/rvport/backend/newmodule/B.java", "class B {}")
        GATE.start(self.root, self.receipt)
        # 直接调用范围校验以独立验证缺模块，避免先被旧时间门禁挡住。
        with self.assertRaisesRegex(ValueError, "缺少自有源码"):
            GATE.java_report(self.root, self.started)

    def test_changed_sources_and_invalid_receipt_rejected(self):
        self.write("src/test/python/test_new.py", "assert True")
        with self.assertRaisesRegex(ValueError, "源码或测试发生变化"):
            GATE.verify(self.root, self.receipt)
        GATE.start(self.root, self.receipt)
        data = json.loads(self.receipt.read_text())
        for value in (0, time.time_ns() + 1_000_000_000_000):
            self.receipt.write_text(json.dumps({**data, "startedNs": value}))
            with self.assertRaisesRegex(ValueError, "开始时间非法"):
                GATE.verify(self.root, self.receipt)

    def test_python_missing_source_and_branch_or_low_coverage_rejected(self):
        for report in ({"meta": {"branch_coverage": False}}, {"meta": {"branch_coverage": True}, "files": {}}):
            self.python_path.write_text(json.dumps(report))
            with self.assertRaises(ValueError):
                GATE.python_report(self.root, self.python_path, self.started)
        summary = self.python["files"]["src/main/resources/analysis/a.py"]["summary"]
        summary["covered_lines"] = 8
        self.python_path.write_text(json.dumps(self.python))
        with self.assertRaisesRegex(ValueError, "低于90"):
            GATE.python_report(self.root, self.python_path, self.started)

    def test_counter_boundaries(self):
        for covered, total in ((-1, 1), (2, 1), (0, -1), (89, 100)):
            with self.assertRaises(ValueError):
                GATE.ratio(covered, total, "sample")
        self.assertEqual(GATE.ratio(0, 0, "empty")["total"], 0)
        for xml in ('<report/>', '<report><counter type="LINE" covered="1" missed="-1"/></report>'):
            with self.assertRaises(ValueError):
                GATE.java_counters(GATE.ET.fromstring(xml), "invalid")
        self.assertEqual(GATE.java_counters(GATE.ET.fromstring(
            '<report><counter type="LINE" covered="1" missed="0"/></report>'), "no branches")["BRANCH"]["total"], 0)

    def test_cli_start_and_verify(self):
        with patch("sys.argv", ["gate", "start", "--root", str(self.root), "--receipt", str(self.receipt)]):
            GATE.main()
        with patch("sys.argv", ["gate", "verify", "--receipt", str(self.receipt)]), \
                patch.object(GATE, "verify", return_value={"verified": True}) as verify, patch("builtins.print") as output:
            GATE.main()
            verify.assert_called_once()
            self.assertIn('"verified": true', output.call_args.args[0])


def ET_START(xml):
    return GATE.ET.fromstring(xml).find("sessioninfo").attrib["start"]
