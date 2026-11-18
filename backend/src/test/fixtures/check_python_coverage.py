"""从 backend 根校验 Docker coverage JSON，未加载源码也必须出现在分母中。"""
import json
from pathlib import Path
import sys


ROOTS = ("agent-cli", "src/main/resources/agentruntime", "src/main/resources/analysis", "src/main/resources/bitcode")


def verify(root, report):
    expected = {path.relative_to(root).as_posix() for folder in ROOTS
                for path in (root / folder).rglob("*.py") if "node_modules" not in path.parts}
    if not expected or not report["meta"]["branch_coverage"]:
        raise ValueError("缺少项目源码或分支统计")
    missing = expected.difference(report["files"])
    if missing:
        raise ValueError("缺少源码覆盖率: " + ", ".join(sorted(missing)))
    for name, summary in [("整体", report["totals"])] + [
            (name, report["files"][name]["summary"]) for name in sorted(expected)]:
        for covered, total in (("covered_lines", "num_statements"), ("covered_branches", "num_branches")):
            if summary[covered] * 10 < summary[total] * 9:
                raise ValueError(f"{name} {covered} {summary[covered]}/{summary[total]} < 90%")
        print(f"{name}: {summary['covered_lines']}/{summary['num_statements']} 行, "
              f"{summary['covered_branches']}/{summary['num_branches']} 分支")


if __name__ == "__main__":
    verify(Path.cwd(), json.loads(Path(sys.argv[1]).read_text()))
