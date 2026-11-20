from __future__ import annotations

from dataclasses import dataclass
import hashlib
from importlib.metadata import version
import json
from pathlib import Path
import subprocess
import sys
from time import monotonic

from tree_sitter import LANGUAGE_VERSION, MIN_COMPATIBLE_LANGUAGE_VERSION, Language
import tree_sitter_c
import tree_sitter_cpp
import tree_sitter_go
import tree_sitter_java
import tree_sitter_python
import tree_sitter_rust


@dataclass(frozen=True, slots=True)
class GrammarRuntime:
    package: str
    package_version: str
    abi_version: int


@dataclass(frozen=True, slots=True)
class TreeSitterRuntime:
    binding_version: str
    minimum_abi: int
    maximum_abi: int
    grammars: tuple[GrammarRuntime, ...]


@dataclass(frozen=True, slots=True)
class ProjectFixture:
    project_id: str
    upstream_url: str
    commit: str
    license: str
    source_path: str
    fixture_path: Path
    sha256: str
    trigger: str
    max_duration_seconds: float
    max_frontend_evidence: int


@dataclass(frozen=True, slots=True)
class ProcessOutcome:
    status: str
    returncode: int | None
    duration_seconds: float
    stdout: str
    stderr: str


def inspect_runtime() -> TreeSitterRuntime:
    grammars = (
        GrammarRuntime("tree-sitter-c", version("tree-sitter-c"), Language(tree_sitter_c.language()).abi_version),
        GrammarRuntime("tree-sitter-cpp", version("tree-sitter-cpp"), Language(tree_sitter_cpp.language()).abi_version),
        GrammarRuntime("tree-sitter-go", version("tree-sitter-go"), Language(tree_sitter_go.language()).abi_version),
        GrammarRuntime("tree-sitter-java", version("tree-sitter-java"), Language(tree_sitter_java.language()).abi_version),
        GrammarRuntime("tree-sitter-rust", version("tree-sitter-rust"), Language(tree_sitter_rust.language()).abi_version),
        GrammarRuntime("tree-sitter-python", version("tree-sitter-python"), Language(tree_sitter_python.language()).abi_version),
    )
    return TreeSitterRuntime(
        binding_version=version("tree-sitter"),
        minimum_abi=MIN_COMPATIBLE_LANGUAGE_VERSION,
        maximum_abi=LANGUAGE_VERSION,
        grammars=grammars,
    )


def validate_runtime(runtime: TreeSitterRuntime) -> tuple[str, ...]:
    errors = []
    if runtime.minimum_abi > runtime.maximum_abi:
        errors.append("Tree-sitter binding ABI range is inverted")
    for grammar in runtime.grammars:
        if not runtime.minimum_abi <= grammar.abi_version <= runtime.maximum_abi:
            errors.append(
                f"{grammar.package}@{grammar.package_version} ABI {grammar.abi_version} is outside "
                f"{runtime.minimum_abi}..{runtime.maximum_abi}"
            )
    return tuple(errors)


def runtime_payload(runtime: TreeSitterRuntime) -> dict[str, object]:
    return {
        "bindingVersion": runtime.binding_version,
        "supportedAbi": [runtime.minimum_abi, runtime.maximum_abi],
        "grammars": [
            {"package": item.package, "version": item.package_version, "abi": item.abi_version}
            for item in runtime.grammars
        ],
    }


def load_project_fixtures(root: Path) -> tuple[ProjectFixture, ...]:
    payload = json.loads((root / "PROVENANCE.json").read_text(encoding="utf-8"))
    if payload.get("schemaVersion") != "1.0" or payload.get("fixtureKind") != "synthetic-reduction":
        raise ValueError("native crash fixture provenance contract is invalid")
    if payload.get("networkRequired") is not False or payload.get("projectExecutionAllowed") is not False:
        raise ValueError("native crash fixtures must be offline and non-executable")
    fixtures = []
    for item in payload.get("projects", ()):
        fixture_path = (root / item["fixturePath"]).resolve()
        try:
            fixture_path.relative_to(root.resolve())
        except ValueError as exc:
            raise ValueError("fixture path escapes the provenance root") from exc
        if not fixture_path.is_file():
            raise ValueError(f"fixture source does not exist: {item['fixturePath']}")
        digest = hashlib.sha256(fixture_path.read_bytes()).hexdigest()
        if digest != item["sha256"]:
            raise ValueError(f"fixture digest mismatch: {item['id']}")
        fixtures.append(
            ProjectFixture(
                project_id=item["id"],
                upstream_url=item["upstreamUrl"],
                commit=item["commit"],
                license=item["license"],
                source_path=item["sourcePath"],
                fixture_path=fixture_path,
                sha256=digest,
                trigger=item["trigger"],
                max_duration_seconds=float(item["maxDurationSeconds"]),
                max_frontend_evidence=int(item["maxFrontendEvidence"]),
            )
        )
    if {item.project_id for item in fixtures} != {"cjson", "zlib", "libarchive"}:
        raise ValueError("native crash fixtures must cover cjson, zlib and libarchive")
    return tuple(fixtures)


def classify_process(returncode: int | None, *, timed_out: bool = False) -> str:
    if timed_out:
        return "timeout"
    if returncode == 0:
        return "normal"
    if returncode == 139:
        return "exit-139"
    if returncode is not None and returncode < 0:
        return "signal"
    return "nonzero"


def run_scanner_process(
    project_root: Path,
    rules_root: Path,
    output_root: Path,
    *,
    schema_version: str | None = None,
    timeout_seconds: float = 20,
) -> ProcessOutcome:
    command = [
        sys.executable,
        "-m",
        "kb_scanner",
        "scan",
        "--input",
        str(project_root),
        "--rules",
        str(rules_root),
        "--output",
        str(output_root),
        "--format",
        "json,html",
    ]
    if schema_version is not None:
        command.extend(("--schema-version", schema_version))
    started = monotonic()
    try:
        completed = subprocess.run(
            command,
            check=False,
            capture_output=True,
            text=True,
            timeout=timeout_seconds,
        )
    except subprocess.TimeoutExpired as exc:
        return ProcessOutcome(
            status=classify_process(None, timed_out=True),
            returncode=None,
            duration_seconds=monotonic() - started,
            stdout=_timeout_text(exc.stdout),
            stderr=_timeout_text(exc.stderr),
        )
    return ProcessOutcome(
        status=classify_process(completed.returncode),
        returncode=completed.returncode,
        duration_seconds=monotonic() - started,
        stdout=completed.stdout,
        stderr=completed.stderr,
    )


def _timeout_text(value: str | bytes | None) -> str:
    if value is None:
        return ""
    return value.decode("utf-8", errors="replace") if isinstance(value, bytes) else value
