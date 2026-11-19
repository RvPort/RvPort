import importlib.util
from pathlib import Path

import pytest

spec = importlib.util.spec_from_file_location("coverage_gate", Path(__file__).resolve().parents[1] / "fixtures/check_python_coverage.py")
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def test_gate_requires_global_and_each_file_with_branches(tmp_path):
    counts = dict(covered_lines=9, num_statements=10, covered_branches=9, num_branches=10)
    report = {"meta": {"branch_coverage": True}, "totals": counts.copy(), "files": {}}
    with pytest.raises(ValueError, match="缺少项目"):
        gate.verify(tmp_path, report)
    source = tmp_path / "agent-cli" / "probe.py"
    source.parent.mkdir()
    source.touch()
    with pytest.raises(ValueError, match="probe.py"):
        gate.verify(tmp_path, report)
    report["files"]["agent-cli/probe.py"] = {"summary": counts.copy()}
    gate.verify(tmp_path, report)
    for summary in (report["totals"], report["files"]["agent-cli/probe.py"]["summary"]):
        for field in ("covered_lines", "covered_branches"):
            summary[field] = 8
            with pytest.raises(ValueError, match=field):
                gate.verify(tmp_path, report)
            summary[field] = 9
    report["meta"]["branch_coverage"] = False
    with pytest.raises(ValueError, match="缺少项目"):
        gate.verify(tmp_path, report)
