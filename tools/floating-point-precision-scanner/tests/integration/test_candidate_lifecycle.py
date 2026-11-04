"""真实十规则冷暖缓存、工作区搬迁与证据归属。"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from floating_point_precision_scanner.corpus_runner import run_case
from floating_point_precision_scanner.rules import DEFAULT_RULES

pytestmark = pytest.mark.integration

CASES = {
    "architecture-specific-fp-api": "granularity-api-two-functions",
    "math-library-variance": "granularity-libm-two-functions",
    "fragile-fp-comparison": "granularity-comparison-mixed",
    "floating-environment": "granularity-fenv-two-regions",
    "fma-contraction": "granularity-fma-two-functions",
    "x87-excess-precision": "granularity-x87-two-functions",
    "long-double-abi": "granularity-abi-two-declarations",
    "mixed-precision-conversion": "granularity-conversions-two-functions",
    "reduction-reassociation": "granularity-reduction-two-functions",
    "unsafe-fp-optimization": "granularity-config-equal",
}


@pytest.mark.parametrize("rule", DEFAULT_RULES)
def test_real_candidate_cache_and_relocation(rule: str, tmp_path: Path) -> None:
    inputs = json.loads(
        (Path(__file__).parents[1] / "fixtures/validation/executable-inputs-v1.json").read_text()
    )
    recipe = next(
        dict(item, cache=True) for item in inputs["cases"] if item["case_id"] == CASES[rule]
    )
    first_root, other_root = tmp_path / "first", tmp_path / "relocated"
    first_root.mkdir()
    other_root.mkdir()
    clang, worker = Path("/usr/lib/llvm-21/bin/clang-21"), Path("/workspace/build/fp-ir-worker")
    cold = run_case(recipe, first_root, clang, worker)
    warm = run_case(recipe, first_root, clang, worker)
    relocated = run_case(recipe, other_root, clang, worker)
    assert cold["scan"]["status"] == "complete"
    assert cold["findings"] == warm["findings"] == relocated["findings"]
    assert warm["extensions"]["org.rvport.floating-point-scanner.runtime"]["cache"]["hit"] >= 1
    extension = "org.rvport.floating-point-scanner.analysis"
    assert (
        cold["extensions"][extension]["assessments"]
        == relocated["extensions"][extension]["assessments"]
    )
    findings = cold["findings"]
    assert len(findings) == (1 if rule == "unsafe-fp-optimization" else 2)
    if rule != "unsafe-fp-optimization":
        assert all(finding["conclusion"]["affectedCount"] == 1 for finding in findings)
        direct_refs = [
            {
                ref["id"]
                for step in finding["evidenceChain"]["steps"]
                for ref in step["references"]
                if ref["kind"] == "sourceFact"
            }
            for finding in findings
        ]
        assert direct_refs[0] and direct_refs[1] and not (direct_refs[0] & direct_refs[1])
