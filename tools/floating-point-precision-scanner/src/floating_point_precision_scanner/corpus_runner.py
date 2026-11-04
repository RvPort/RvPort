"""从固定 C/C++ 输入重新运行扫描；不读取 oracle，不回填预期。"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import re
import subprocess
import tempfile
from collections.abc import Mapping
from pathlib import Path
from typing import Any

from .application import Scanner
from .canonical import semantic_digest
from .compilation import load_compilation_plan
from .frontend import ClangFrontend
from .ir_worker import IrWorker
from .output import write_atomic
from .profiles import ProfileRegistry, TargetProfile
from .protocol import SchemaRegistry, parse_request

X86 = "x86_64-linux-gnu-clang21@1.0"
RISCV = "riscv64-linux-gnu-lp64d-clang21@1.0"


def _profiles(patches: Mapping[str, Any]) -> ProfileRegistry:
    builtin = ProfileRegistry()
    documents = []
    for side, reference in (("source", X86), ("target", RISCV)):
        document = copy.deepcopy(dict(builtin.get(reference).document))
        for path, value in patches.get(side, {}).items():
            fields = path.split(".")
            parent = document
            for field in fields[:-1]:
                parent = parent[field]
            parent[fields[-1]] = value
        documents.append(document)
    return ProfileRegistry(documents)


def _context(profile: TargetProfile, libm: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "kind": "FloatingPointBuildContext",
        "schema_version": "1.0",
        "context_id": f"corpus-{profile.reference}",
        "base_profile_fingerprint": profile.fingerprint,
        "target_triple": profile.target_triple,
        "abi": profile.abi,
        "toolchain": {"name": "clang", "version": "21.1.8"},
        "libm": {"status": "known", "value": dict(libm), "source": "fixed-corpus"},
        "environment": {
            "status": "unknown",
            "reason": "语料未提供环境探测",
            "source": "fixed-corpus",
        },
        "target_features": {
            "status": "unknown",
            "reason": "语料未提供硬件探测",
            "source": "fixed-corpus",
        },
        "sources": {"fixture": "固定测试输入，不代表真实平台 libm"},
    }


def _bitcode(request: dict[str, Any], clang: Path, flags: list[str]) -> None:
    root = Path(request["project_root"])
    parsed, errors = parse_request(request, SchemaRegistry())
    if parsed is None or errors:
        raise ValueError("invalid_corpus_request")
    plan = load_compilation_plan(parsed)
    if plan.failed_count or not plan.contexts:
        raise ValueError("invalid_corpus_compilation_plan")
    artifact_root = root / "bitcode"
    artifact_root.mkdir(exist_ok=True)
    members = []
    for context in plan.contexts:
        filename = "main.bc" if len(plan.contexts) == 1 else f"{context.context_id}.bc"
        path = artifact_root / filename
        compile_flags = flags if len(plan.contexts) == 1 else list(context.semantic_arguments)
        subprocess.run(
            [str(clang), *compile_flags, "-emit-llvm", "-c", context.file, "-o", str(path)],
            cwd=root, check=True, capture_output=True, timeout=60,
        )
        members.append({
            "physical_tu_id": context.physical_tu_id, "context_id": context.context_id,
            "artifact_key": semantic_digest({"context": context.context_id,
                                             "bytes": path.read_bytes().hex()}),
            "language": context.language, "target": context.target, "abi": context.abi,
            "profile": "build-faithful", "profile_version": "1.0",
            "capabilities": ["debug-location", "llvm-fp-facts"], "path": path.name,
            "size_bytes": path.stat().st_size,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "data_layout": None,
        })
    compiler = {"name": "clang", "version": "21.1.8"}
    manifest = {
        "kind": "SharedBitcodeArtifactSet",
        "schema_version": "1.0",
        "artifact_set_id": semantic_digest(request["request_id"]),
        "source_revision": request["source_revision"],
        "build_run_id": "corpus-current-source",
        "compile_commands_sha256": plan.input_digest,
        "status": "READY",
        "producer": {
            "tool": "corpus-runner",
            "version": "1.0",
            "analysis_compiler": compiler,
            "production_compiler": compiler,
        },
        "counts": {"planned": len(members), "completed": len(members), "failed": 0},
        "members": members,
        "failures": [],
    }
    manifest_path = artifact_root / "manifest.json"
    write_atomic(manifest, manifest_path)
    request["bitcode_artifact_set"] = {
        "artifact_root": str(artifact_root),
        "manifest": manifest_path.name,
        "manifest_sha256": hashlib.sha256(manifest_path.read_bytes()).hexdigest(),
        "required_profiles": ["build-faithful"],
        "source_mapping": "best-effort",
    }


def run_case(recipe: Mapping[str, Any], root: Path, clang: Path, worker: Path) -> dict[str, Any]:
    standard = str(recipe.get("standard", "c17"))
    name = "main.cpp" if standard.startswith("c++") else "main.c"
    files = recipe.get("files", {name: recipe.get("source", "")})
    if not isinstance(files, Mapping) or not files:
        raise ValueError("invalid_corpus_files")
    for relative, content in files.items():
        path = root / str(relative)
        if (Path(str(relative)).is_absolute() or "\\" in str(relative)
                or ":" in str(relative) or not path.resolve().is_relative_to(root.resolve())
                or not isinstance(content, str)):
            raise ValueError("invalid_corpus_file_path")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    flags = [f"-std={standard}", *recipe.get("flags", [])]
    units = recipe.get("translation_units", [{"file": name}])
    if not isinstance(units, list) or not units:
        raise ValueError("invalid_corpus_translation_units")
    translation_units = []
    for unit in units:
        if not isinstance(unit, Mapping) or unit.get("file") not in files:
            raise ValueError("invalid_corpus_translation_unit_file")
        unit_flags = [f"-std={unit.get('standard', standard)}", *unit.get("flags", flags[1:])]
        translation_units.append({"file": unit["file"], "directory": str(root),
                                  "arguments": ["clang", *unit_flags, "-c", unit["file"]]})
    profiles = _profiles(recipe.get("profile_patches", {}))
    request: dict[str, Any] = {
        "kind": "FloatingPointPrecisionScanRequest",
        "schema_version": "1.4",
        "request_id": recipe["case_id"],
        "source_revision": semantic_digest(recipe),
        "project_root": str(root),
        "source_profile": X86,
        "target_profile": RISCV,
        "input": {
            "translation_units": translation_units
        },
        "rules": {"enabled": [recipe["rule"]]},
        "budgets": recipe.get("budgets", {}),
        "suppressions": recipe.get("suppressions", []),
    }
    for side, libm in recipe.get("libm", {}).items():
        request.setdefault("build_contexts", {})[side] = _context(
            profiles.get(request[f"{side}_profile"]),
            libm,
        )
    if recipe.get("cache"):
        request["cache"] = {"mode": "read-write", "root": str(root / ".cache")}
    if recipe.get("bitcode"):
        _bitcode(request, clang, [f"-std={standard}", *recipe.get("bitcode_flags", flags[1:])])
    result = Scanner(ClangFrontend(clang), profiles=profiles, ir_worker=IrWorker(worker)).scan(
        request
    )
    if SchemaRegistry().validate("result", result):
        raise ValueError("invalid_corpus_result")
    return result


def source_digests() -> dict[str, str]:
    package = Path(__file__).parent
    return {
        path.relative_to(package).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted(package.rglob("*"))
        if path.suffix in {".py", ".json"}
    }


def run_corpus(
    inputs: Mapping[str, Any], output: Path, clang: Path, worker: Path
) -> dict[str, Any]:
    cases = inputs.get("cases")
    if not isinstance(cases, list) or not cases:
        raise ValueError("executable_corpus_has_no_cases")
    ids = [str(case["case_id"]) for case in cases]
    if len(set(ids)) != len(ids) or any(not re.fullmatch(r"[a-z0-9-]+", value) for value in ids):
        raise ValueError("invalid_executable_case_ids")
    observations = {}
    for recipe in cases:
        case_id = recipe["case_id"]
        with tempfile.TemporaryDirectory(prefix="fp-corpus-") as directory:
            result = run_case(recipe, Path(directory), clang, worker)
        write_atomic(result, output.parent / "cases" / f"{case_id}.json")
        observations[case_id] = result
    report = {
        "kind": "FloatingPointScannerCurrentObservations",
        "schema_version": "1.0",
        "inputs_digest": semantic_digest(inputs),
        "fixture_digests": {case["case_id"]: semantic_digest(case) for case in cases},
        "source_digests": source_digests(),
        "observations": observations,
    }
    write_atomic(report, output)
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--inputs", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args(argv)
    inputs = json.loads(Path(args.inputs).read_text(encoding="utf-8"))
    run_corpus(
        inputs,
        Path(args.output),
        Path(os.environ.get("FP_SCANNER_CLANG", "/usr/lib/llvm-21/bin/clang-21")),
        Path(os.environ.get("FP_SCANNER_IR_WORKER", "/workspace/build/fp-ir-worker")),
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
