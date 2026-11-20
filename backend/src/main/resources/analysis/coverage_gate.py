"""核验本轮单元测试覆盖率的来源、完整性与门槛；只在隔离验收容器使用。"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import time
import xml.etree.ElementTree as ET

PYTHON_ROOTS = ("src/main/resources/analysis", "src/main/resources/bitcode",
                "src/main/resources/agentruntime", "agent-cli")
GENERATED_JAVA = "cn/rvport/backend/jooq/"


def require(condition: bool, reason: str) -> None:
    if not condition:
        raise ValueError(reason)


def python_sources(root: Path) -> set[str]:
    # agent-cli 的 node_modules 是第三方依赖；其余自有 Python 文件全部纳入。
    return {p.relative_to(root).as_posix() for base in PYTHON_ROOTS
            for p in (root / base).rglob("*.py") if "node_modules" not in p.parts}


def fingerprint(root: Path) -> str:
    files = {root / p for p in python_sources(root)}
    files.update((root / "src/main/java").rglob("*.java"))
    files.update((root / "src/test").rglob("*.java"))
    files.update((root / "src/test").rglob("*.py"))
    files.add(root / "pom.xml")
    digest = hashlib.sha256()
    for path in sorted(files):
        digest.update(path.relative_to(root).as_posix().encode())
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def start(root: Path, receipt: Path) -> None:
    receipt.write_text(json.dumps({"startedNs": time.time_ns(), "sourceDigest": fingerprint(root)}),
                       encoding="utf-8")


def fresh(path: Path, started: int) -> Path:
    require(path.is_file() and path.stat().st_size > 0, "报告缺失或为空: " + str(path))
    require(path.stat().st_mtime_ns >= started, "报告早于本轮测试: " + str(path))
    return path


def ratio(covered: int, total: int, name: str) -> dict:
    require(0 <= covered <= total, "覆盖率计数非法: " + name)
    require(total == 0 or covered * 100 >= total * 90, "覆盖率低于90%: " + name)
    return {"covered": covered, "total": total,
            "percent": None if total == 0 else round(covered * 100 / total, 4)}


def java_counters(element: ET.Element, name: str) -> dict:
    counters = {c.attrib["type"]: c.attrib for c in element.findall("counter")}
    require("LINE" in counters, "缺少行覆盖率: " + name)
    result = {}
    for kind in ("LINE", "BRANCH"):
        value = counters.get(kind, {"covered": "0", "missed": "0"})
        covered, missed = int(value["covered"]), int(value["missed"])
        require(missed >= 0, "负数覆盖率: " + name)
        result[kind] = ratio(covered, covered + missed, name + "/" + kind)
    return result


def java_report(root: Path, started: int) -> dict:
    target = root / "target"
    fresh(target / "jacoco.exec", started)
    fresh(target / "site/jacoco/index.html", started)
    report = ET.parse(fresh(target / "site/jacoco/jacoco.xml", started)).getroot()
    require(report.tag == "report", "非法JaCoCo报告")
    sessions = report.findall("sessioninfo")
    require(bool(sessions) and any(int(s.attrib["start"]) >= started // 1_000_000 for s in sessions),
            "缺少本轮JaCoCo采集会话")
    tests = failures = skipped = 0
    suites = sorted((target / "surefire-reports").glob("TEST-*.xml"))
    require(bool(suites), "缺少Surefire单元测试报告")
    for path in suites:
        suite = ET.parse(fresh(path, started)).getroot()
        tests += int(suite.attrib.get("tests", 0))
        failures += int(suite.attrib.get("failures", 0)) + int(suite.attrib.get("errors", 0))
        skipped += int(suite.attrib.get("skipped", 0))
    require(tests > skipped and failures == 0, "没有成功执行单元测试或存在失败")
    compiled = {p.relative_to(target / "classes").as_posix()[:-6]
                for p in (target / "classes/cn/rvport/backend").rglob("*.class")}
    compiled = {name for name in compiled if not name.startswith(GENERATED_JAVA)}
    reported = {c.attrib["name"] for c in report.findall("package/class")}
    # JaCoCo 不展示无可执行指令的接口/注解；以源文件为底线并检查所有已报告类属于当前编译输出。
    source_files = {p.relative_to(root / "src/main/java").as_posix()
                    for p in (root / "src/main/java/cn/rvport/backend").rglob("*.java")
                    if not p.relative_to(root / "src/main/java").as_posix().startswith(GENERATED_JAVA)}
    report_sources = {pkg.attrib["name"] + "/" + src.attrib["name"]
                      for pkg in report.findall("package") for src in pkg.findall("sourcefile")}
    class_sources = {name.split("$", 1)[0] + ".java" for name in compiled}
    require(bool(compiled) and bool(reported) and reported <= compiled, "JaCoCo类与当前编译输出不匹配")
    # 源码可以是纯接口，无指令时JaCoCo不会生成sourcefile；但它仍须有当前class和报告class。
    reported_sources = {name.split("$", 1)[0] + ".java" for name in reported}
    require(source_files <= class_sources and source_files <= (report_sources | reported_sources),
            "JaCoCo缺少自有源码或模块")
    packages = {pkg.attrib["name"]: java_counters(pkg, pkg.attrib["name"])
                for pkg in report.findall("package") if pkg.findall("counter")}
    return {"tests": tests, "skipped": skipped, "overall": java_counters(report, "Java整体"),
            "packages": packages, "excluded": [GENERATED_JAVA + "**（jOOQ自动生成）"]}


def python_report(root: Path, report_path: Path, started: int) -> dict:
    report = json.loads(fresh(report_path, started).read_text(encoding="utf-8"))
    require(report.get("meta", {}).get("branch_coverage") is True, "Python未采集分支")
    files = report.get("files", {})
    expected = python_sources(root)
    require(bool(expected) and expected == set(files), "Python统计源码范围不完整或混入非自有源码")
    rows = {}
    for name, entry in files.items():
        value = entry["summary"]
        rows[name] = {"LINE": ratio(value["covered_lines"], value["num_statements"], name + "/LINE"),
                      "BRANCH": ratio(value["covered_branches"], value["num_branches"], name + "/BRANCH")}
    # 从每个文件累加，不信任外部填写的整体百分比。
    overall = {kind: ratio(sum(row[kind]["covered"] for row in rows.values()),
                           sum(row[kind]["total"] for row in rows.values()), "Python整体/" + kind)
               for kind in ("LINE", "BRANCH")}
    return {"overall": overall, "files": rows, "excluded": ["agent-cli/node_modules（第三方依赖）"]}


def verify(root: Path, receipt: Path, python_json: Path | None = None) -> dict:
    data = json.loads(receipt.read_text(encoding="utf-8"))
    require(data["sourceDigest"] == fingerprint(root), "测试期间源码或测试发生变化")
    require(0 < data["startedNs"] <= time.time_ns(), "测试开始时间非法")
    result = {"sourceDigest": data["sourceDigest"], "java": java_report(root, data["startedNs"])}
    if python_json is not None:
        result["python"] = python_report(root, python_json, data["startedNs"])
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("start", "verify"))
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--receipt", type=Path, required=True)
    parser.add_argument("--python-json", type=Path)
    args = parser.parse_args()
    if args.action == "start":
        start(args.root, args.receipt)
    else:
        print(json.dumps(verify(args.root, args.receipt, args.python_json), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
