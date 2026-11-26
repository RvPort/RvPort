#!/usr/bin/env python3
"""将 RvPort Shared LLVM BC v2 转换为两个分析器的公开输入协议。"""

from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import importlib.util
import json
import posixpath
import shlex
import shutil
from pathlib import Path
from types import SimpleNamespace


def canonical(value: object) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def guarded_path(root: Path, relative: str) -> Path:
    parts = relative.replace("\\", "/").split("/")
    if any(part in ("", ".", "..") for part in parts) or ":" in relative or any(ord(c) < 32 for c in relative):
        raise ValueError(f"不安全的输入路径: {relative}")
    if root.is_symlink():
        raise ValueError("不安全的输入根目录")
    current = root.resolve()
    for part in parts:
        current = current / part
        if current.is_symlink():
            raise ValueError(f"不安全的输入符号链接: {relative}")
    return current


def safe(root: Path, relative: str) -> Path:
    candidate = guarded_path(root, relative)
    if not candidate.is_file():
        raise ValueError(f"不安全或缺失的 BC 路径: {relative}")
    return candidate


def optional_verified_source(root: Path, relative: str, expected_sha256: str) -> Path | None:
    """返回可用于源码辅助的文件；构建生成且未归档的源码允许缺席。"""
    candidate = guarded_path(root, relative)
    if not candidate.exists():
        return None
    if not candidate.is_file():
        raise ValueError(f"不安全的源码路径: {relative}")
    if len(expected_sha256) != 64 or sha256(candidate) != expected_sha256.lower():
        raise ValueError(f"源码摘要不匹配: {relative}")
    return candidate


def verified_artifact(root: Path, artifact: dict[str, object]) -> Path:
    relative = str(artifact.get("path") or "")
    candidate = safe(root, relative)
    expected_bytes = artifact.get("bytes")
    if not isinstance(expected_bytes, int) or candidate.stat().st_size != expected_bytes:
        raise ValueError(f"BC 文件大小不匹配: {relative}")
    expected_sha = str(artifact.get("sha256") or "").lower()
    if len(expected_sha) != 64 or sha256(candidate) != expected_sha:
        raise ValueError(f"BC 文件摘要不匹配: {relative}")
    return candidate


def normalized_standard(raw: str | None, language: str) -> dict[str, str | None]:
    aliases = {"c89": "c89", "c90": "c89", "gnu89": "c89", "gnu90": "c89",
               "c99": "c99", "c9x": "c99", "gnu99": "c99", "gnu9x": "c99",
               "c11": "c11", "c1x": "c11", "gnu11": "c11", "gnu1x": "c11",
               "c17": "c17", "c18": "c17", "gnu17": "c17", "gnu18": "c17",
               "c23": "c23", "c2x": "c23", "gnu23": "c23", "gnu2x": "c23",
               "c++98": "c++98", "gnu++98": "c++98", "c++03": "c++03", "gnu++03": "c++03",
               "c++11": "c++11", "c++0x": "c++11", "gnu++11": "c++11", "gnu++0x": "c++11",
               "c++14": "c++14", "c++1y": "c++14", "gnu++14": "c++14", "gnu++1y": "c++14",
               "c++17": "c++17", "c++1z": "c++17", "gnu++17": "c++17", "gnu++1z": "c++17",
               "c++20": "c++20", "c++2a": "c++20", "gnu++20": "c++20", "gnu++2a": "c++20",
               "c++23": "c++23", "c++2b": "c++23", "gnu++23": "c++23", "gnu++2b": "c++23"}
    if raw is None:
        return {"standard": "compiler-default", "dialect": "compiler-default",
                "standard_source": "compiler-default", "standard_option": None}
    value = aliases.get(raw.lower())
    if value is None or (language == "c++") != value.startswith("c++"):
        return {"standard": "unsupported", "dialect": "gnu" if raw.lower().startswith("gnu") else "iso",
                "standard_source": "explicit", "standard_option": f"-std={raw.lower()}"}
    return {"standard": value, "dialect": "gnu" if raw.lower().startswith("gnu") else "iso",
            "standard_source": "explicit", "standard_option": f"-std={raw.lower()}"}


def localized_compile_database(args: argparse.Namespace) -> Path:
    if args.compile_database is None:
        if args.manifest:
            raise ValueError("BC 增强必须提供可信编译上下文")
        spec = importlib.util.spec_from_file_location(
            "source_analysis_plan", Path(__file__).with_name("source_analysis_plan.py"))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        plan = module.discover(args.source_root)
        args.remote_source_root = ""
        args.source_plan = plan
        (args.cache / "source-analysis-plan.json").write_bytes(canonical(plan) + b"\n")
        if not plan["translationUnits"]:
            raise ValueError("ANALYSIS_SOURCE_LANGUAGE_UNSUPPORTED")
        # 仅作为既有 source-profile 适配器内部输入，不登记构建或编译数据库制品。
        projection = args.cache / "source-discovery-input.json"
        projection.write_bytes(canonical(plan["translationUnits"]) + b"\n")
        return projection
    document = json.loads(args.compile_database.read_text(encoding="utf-8"))
    if not isinstance(document, list):
        raise ValueError("compile_commands.json 顶层必须是数组")
    candidates: Counter[str] = Counter()
    matches: list[tuple[dict[str, object], str, str]] = []
    unavailable_sources = []
    pinned_root = getattr(args, "remote_source_root", None)
    if pinned_root:
        pinned_root = pinned_root.replace("\\", "/").rstrip("/")
        if not pinned_root.startswith("/") or not pinned_root or "/../" in pinned_root + "/":
            raise ValueError("编译数据库源码根目录非法")
    for record in document:
        if not isinstance(record, dict):
            continue
        file_value = str(record.get("file") or "").replace("\\", "/").rstrip("/")
        if pinned_root:
            directory = str(record.get("directory") or pinned_root).replace("\\", "/")
            absolute = posixpath.normpath(posixpath.join(directory, file_value))
            if not absolute.startswith(pinned_root + "/"):
                continue
            relative = absolute[len(pinned_root) + 1:]
            if guarded_path(args.source_root, relative).is_file():
                candidates[pinned_root] += 1
                matches.append(({**record, "file": absolute}, relative, pinned_root))
            else:
                unavailable_sources.append({"path": relative, "reasonCode": "SOURCE_NOT_IN_FIXED_SNAPSHOT"})
            continue
        parts = [part for part in file_value.split("/") if part]
        for start in range(len(parts)):
            relative = "/".join(parts[start:])
            if (args.source_root / relative).is_file():
                remote_root = file_value[:-len(relative)].rstrip("/")
                candidates[remote_root] += 1
                matches.append((record, relative, remote_root))
                break
    if not candidates:
        raise ValueError("无法从编译数据库识别远端源码根目录")
    remote_root = candidates.most_common(1)[0][0].rstrip("/")
    args.remote_source_root = remote_root
    local_root = str(args.source_root.resolve()).replace("\\", "/").rstrip("/")

    def relocate(value: object) -> object:
        if isinstance(value, str):
            return value.replace(remote_root, local_root) if remote_root else value
        if isinstance(value, list):
            return [relocate(item) for item in value]
        if isinstance(value, dict):
            return {key: relocate(item) for key, item in value.items()}
        return value

    selected = []
    for record, relative, matched_root in matches:
        if matched_root != remote_root:
            continue
        directory_value = str(record.get("directory") or "").replace("\\", "/")
        if not (directory_value == remote_root or directory_value.startswith(remote_root + "/")):
            continue
        localized_record = relocate(record)
        localized_directory = Path(str(localized_record.get("directory") or local_root)).resolve()
        source_root = args.source_root.resolve()
        if localized_directory != source_root and source_root not in localized_directory.parents:
            raise ValueError("重定位后的编译工作目录越出任务源码目录")
        localized_directory.mkdir(parents=True, exist_ok=True)
        selected.append(localized_record)
    if not selected:
        raise ValueError("编译数据库中没有可映射到本地源码的翻译单元")
    localized = args.cache / "compile_commands.json"
    localized.write_bytes(canonical(selected) + b"\n")
    spec = importlib.util.spec_from_file_location("source_analysis_plan", Path(__file__).with_name("source_analysis_plan.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    inventory = module.discover(args.source_root)
    selected_paths = {str((Path(str(item.get("directory") or args.source_root)) / str(item["file"]))
                         .resolve().relative_to(args.source_root.resolve())).replace("\\", "/")
                      for item in selected}
    for entry in inventory["files"]:
        if entry["status"] == "PLANNED" and entry["path"] not in selected_paths:
            entry.update(status="OMITTED", reasonCode="NOT_IN_COMPILE_DATABASE")
    coverage_manifest = {"inputOrigin": "BUILD_ARTIFACTS", "files": inventory["files"], "compileContextCoverage": {
        "rawCommandCount": len(document), "localizedCommandCount": len(selected),
        "unavailableSourceCount": len(unavailable_sources), "unavailableSources": unavailable_sources,
        "excludedCommandCount": len(document) - len(selected) - len(unavailable_sources),
        "message": "未归档的生成源码不能参与本次源码分析，计数单位为编译条目。",
        "message_en": "Generated sources absent from the fixed snapshot cannot be analyzed; counts are compile entries."
    }}
    coverage_manifest["inputDigest"] = hashlib.sha256(canonical(coverage_manifest)).hexdigest()
    (args.cache / "source-analysis-plan.json").write_bytes(canonical(coverage_manifest) + b"\n")
    return localized


def fp(args: argparse.Namespace) -> None:
    from floating_point_precision_scanner.compilation import load_compilation_plan

    platform = json.loads(args.manifest.read_text(encoding="utf-8")) if args.manifest else None
    compile_database = localized_compile_database(args)
    compilation_input = ({"translation_units": args.source_plan["translationUnits"]}
                         if args.compile_database is None else
                         {"compile_commands": str(compile_database.resolve())})
    compilation = load_compilation_plan(SimpleNamespace(
        input=compilation_input,
        project_root=args.source_root.resolve(),
        budgets={"max_response_depth": 8, "max_files": 32, "max_response_bytes": 1_048_576},
    ))
    contexts_by_source: dict[tuple[str, str], list[object]] = {}
    for context in compilation.contexts:
        contexts_by_source.setdefault((context.file.replace("\\", "/"), context.language), []).append(context)

    members, failures = [], []
    for record in (platform or {}).get("records", []):
        profile = record.get("profile") or {}
        name = profile.get("name") or "source-preserving"
        if name not in {"source-preserving", "build-faithful"}:
            name = "source-preserving"
        source_path = str((record.get("source") or {}).get("path") or "").replace("\\", "/").lstrip("./")
        language = "c++" if record.get("language") in {"c++", "c++-header"} else "c"
        candidates = contexts_by_source.get((source_path, language), [])
        if not candidates:
            continue
        if len(candidates) > 1:
            candidates = [item for item in candidates if item.context_id == record.get("context_id")]
        if len(candidates) != 1:
            raise ValueError(f"共享 BC 无法唯一匹配本地编译上下文: {source_path}")
        context = candidates[0]
        if record.get("status") == "produced" and record.get("bitcode"):
            bitcode = record["bitcode"]
            verified_artifact(args.artifact_root, bitcode)
            profile_version = str(profile.get("version") or "1.0")
            if profile_version.isdigit():
                profile_version = f"{profile_version}.0"
            capabilities = set(record.get("capabilities") or [])
            capabilities.add("llvm-fp-facts")
            if record.get("source"):
                capabilities.add("debug-location")
            members.append({
                "physical_tu_id": context.physical_tu_id, "context_id": context.context_id,
                "artifact_key": record["artifact_key"], "language": context.language,
                "target": context.target, "abi": context.abi, "profile": name,
                "profile_version": profile_version,
                "capabilities": sorted(capabilities),
                "path": bitcode["path"], "size_bytes": bitcode.get("bytes", 0), "sha256": bitcode["sha256"],
                "data_layout": (record.get("target") or {}).get("data_layout"),
            })
        else:
            diagnostic = record.get("diagnostic") or {}
            failures.append({
                "physical_tu_id": context.physical_tu_id, "context_id": context.context_id,
                "profile": name, "code": diagnostic.get("code") or "shared-bitcode-unavailable",
                "detail": diagnostic.get("detail") or "共享 BC 不可用",
                "detail_en": diagnostic.get("detail_en") or "Shared bitcode is unavailable",
            })
    request = {
        "kind": "FloatingPointPrecisionScanRequest", "schema_version": "1.4",
        "request_id": args.run_id, "project_root": str(args.source_root.resolve()),
        "source_profile": "x86_64-linux-gnu-clang21@1.0",
        "target_profile": "riscv64-linux-gnu-lp64d-clang21@1.0",
        "source_revision": args.source_revision,
        "input": compilation_input,
        "cache": {"mode": "read-write", "root": str(args.cache.resolve()), "max_entry_bytes": 268435456},
        "rules": {"disabled": []},
        "budgets": {"wall_seconds": args.timeout, "max_files": 100000,
                    "max_frontend_output_bytes": args.max_frontend_output_bytes,
                    "max_bitcode_bytes": 8589934592, "max_result_bytes": 536870912,
                    "max_rule_assessments": 1000000, "max_related_locations": 10000,
                    "max_diagnostics": 100000},
    }
    if platform is None:
        args.output.write_bytes(canonical(request) + b"\n")
        return
    producer = platform.get("producer") or {}
    manifest = {
        "kind": "SharedBitcodeArtifactSet", "schema_version": "1.0",
        "artifact_set_id": platform["artifact_set_id"], "source_revision": args.source_revision,
        "build_run_id": args.build_run, "compile_commands_sha256": sha256(compile_database),
        "status": "READY" if not failures else "PARTIAL",
        "producer": {"tool": producer.get("family", "clang"), "version": producer.get("version", "21.1.8"),
                     "analysis_compiler": {"name": "clang", "version": producer.get("llvm_version", "21.1.8")},
                     "production_compiler": {"name": producer.get("family", "clang"), "version": producer.get("version", "21.1.8")}},
        "counts": {"planned": len(members) + len(failures), "completed": len(members), "failed": len(failures)},
        "members": sorted(members, key=lambda item: (item["physical_tu_id"], item["context_id"], item["profile"])),
        "failures": sorted(failures, key=lambda item: (item["physical_tu_id"], item["context_id"])),
    }
    adapted = args.artifact_root / "fp-manifest.json"
    adapted.write_bytes(canonical(manifest) + b"\n")
    available_profiles = sorted({item["profile"] for item in members})
    required_profile = ("source-preserving" if "source-preserving" in available_profiles
                        else available_profiles[0] if available_profiles else "source-preserving")
    request["bitcode_artifact_set"] = {"artifact_root": str(args.artifact_root.resolve()),
                                        "manifest": adapted.name, "manifest_sha256": sha256(adapted),
                                        "required_profiles": [required_profile], "source_mapping": "best-effort"}
    args.output.write_bytes(canonical(request) + b"\n")


def synthetic_platform(records: list[dict[str, object]], source_root: Path) -> dict[str, object]:
    """Create stable missing-BC identities so memory analysis can still consume every AST unit."""
    values = []
    for index, record in enumerate(records):
        directory = Path(str(record.get("directory") or source_root))
        source = Path(str(record.get("file") or ""))
        source = (source if source.is_absolute() else directory / source).resolve()
        try:
            relative = str(source.relative_to(source_root.resolve())).replace("\\", "/")
        except ValueError:
            continue
        language = "c++" if source.suffix.lower() in {".cc", ".cp", ".cpp", ".cxx", ".c++"} else "c"
        arguments = record.get("arguments")
        if not isinstance(arguments, list):
            arguments = shlex.split(str(record.get("command") or ""))
        raw_standard = next((str(item).split("=", 1)[1] for item in reversed(arguments)
                             if str(item).startswith("-std=")), None)
        standard_context = normalized_standard(raw_standard, language)
        source_sha = sha256(source)
        physical = hashlib.sha256(canonical({"source": relative, "language": language,
                                             "sha256": source_sha})).hexdigest()
        context = hashlib.sha256(canonical({"record": record, "physical_tu_id": physical})).hexdigest()
        record_id = hashlib.sha256(canonical({"context_id": context, "profile": "source-preserving"})).hexdigest()
        values.append({"record_id": record_id, "physical_tu_id": physical, "context_id": context,
                       "artifact_key": "0" * 64, "status": "missing",
                       "diagnostic": {"code": "bitcode-not-available", "detail": "BC 不可用，使用 AST 分析",
                                      "detail_en": "Bitcode is unavailable; AST analysis is used"},
                       "bitcode": None, "language": language, **standard_context,
                       "source": {"path": relative, "sha256": source_sha},
                       "compile_record_digest": hashlib.sha256(canonical(record)).hexdigest(),
                       "input_digests": {relative: source_sha}, "optimization": "unknown", "debug": "unknown",
                       "module_kind": "translation-unit", "target": {"triple": "x86_64-pc-linux-gnu",
                       "data_layout": "unknown", "abi": {}}, "profile": {"name": "source-preserving", "version": "1"}})
    artifact_set_id = hashlib.sha256(canonical({"records": [item["record_id"] for item in values],
                                                "mode": "ast-only"})).hexdigest()
    return {"artifact_set_id": artifact_set_id, "producer": {"family": "clang", "version": "21.1.8",
            "llvm_version": "21.1.8", "host_triple": "x86_64-pc-linux-gnu"}, "records": values,
            "scopes": [{"scope_id": "ast-only", "target_id": "project", "record_ids":
                        [item["record_id"] for item in values], "evidence": "compile_commands.json",
                        "closed": False, "external_boundaries": ["bitcode-unavailable"]}]}


def memory(args: argparse.Namespace) -> None:
    from memory_order_analyzer.contracts import AnalysisError, canonical as moa_canonical, file_hash
    from memory_order_analyzer.external_bitcode_contracts import (
        diagnostic, expected_external_bitcode_protocol, external_document,
    )
    from memory_order_analyzer.unified_contracts import expected_unified_protocol, unified_document
    from memory_order_analyzer.source_contracts import expected_source_protocol, source_document
    from memory_order_analyzer.source_profile import build_profile

    compile_database = localized_compile_database(args)
    compile_records = json.loads(compile_database.read_text(encoding="utf-8"))
    platform = json.loads(args.manifest.read_text(encoding="utf-8")) if args.manifest else synthetic_platform(
        compile_records, args.source_root)
    if args.compile_database is None:
        for scope in platform["scopes"]:
            scope["evidence"] = "SOURCE_DISCOVERY: default parsing assumptions, not build evidence"
            scope["external_boundaries"].append("compile-context-unavailable")
    compile_by_source: dict[str, list[dict[str, object]]] = {}
    for compile_record in compile_records:
        directory = Path(str(compile_record.get("directory") or args.source_root))
        source_file = Path(str(compile_record.get("file") or ""))
        source_file = (source_file if source_file.is_absolute() else directory / source_file).resolve()
        try:
            relative = str(source_file.relative_to(args.source_root.resolve())).replace("\\", "/")
        except ValueError:
            continue
        compile_by_source.setdefault(relative, []).append(compile_record)
    args.external_root.mkdir(parents=True, exist_ok=True)
    (args.external_root / "records").mkdir(exist_ok=True)
    (args.external_root / "bc").mkdir(exist_ok=True)
    (args.external_root / "source").mkdir(exist_ok=True)
    (args.external_root / "source-requests").mkdir(exist_ok=True)
    producer_value = platform.get("producer") or {}
    family = producer_value.get("family", "clang")
    producer = {"family": family if family in {"clang", "llvm-compatible"} else "llvm-compatible",
                "version": producer_value.get("version", "21.1.8"),
                "llvm_version": producer_value.get("llvm_version", "21.1.8"),
                "host_triple": producer_value.get("host_triple", "x86_64-pc-linux-gnu")}
    refs, produced_ids, source_requests = [], [], []
    for item in sorted(platform.get("records", []), key=lambda value: value["record_id"]):
        record_id = item["record_id"]
        produced = item.get("status") == "produced" and item.get("bitcode") is not None
        if produced:
            produced_ids.append(record_id)
        bitcode_value = None
        if produced:
            source_bc = verified_artifact(args.artifact_root, item["bitcode"])
            destination = args.external_root / "bc" / f"{record_id}.bc"
            shutil.copyfile(source_bc, destination)
            bitcode_value = {"path": f"bc/{record_id}.bc", "sha256": file_hash(destination),
                             "bytes": destination.stat().st_size}
        diagnostic_value = None if produced else diagnostic(
            "external_artifact_missing" if item.get("status") == "missing" else "external_build_failed",
            "上游共享 BC 不可用", "The upstream shared bitcode is unavailable", record_id=record_id)
        target = item.get("target") or {}
        standard = item.get("standard") or "compiler-default"
        optimization = item.get("optimization") or "unknown"
        if optimization not in {"O0", "O1", "O2", "O3", "Os", "Oz", "Ofast", "unknown"}:
            optimization = "unknown"
        debug = item.get("debug") or "unknown"
        if debug not in {"full", "line-tables", "none", "unknown"}:
            debug = "unknown"
        source_value = None
        source = item.get("source") or {}
        compile_candidates = compile_by_source.get(str(source.get("path") or "").replace("\\", "/"), [])
        if source.get("path") and source.get("sha256"):
            # 未生成 BC 的记录可能在编译器检查前失败，source.sha256 此时只是
            # producer 的占位身份。源码辅助独立绑定已固定源码和可信编译条目，
            # 不把占位摘要当源码证据；真正产出的 BC 仍必须严格匹配原摘要。
            expected_sha = source["sha256"]
            if not produced and compile_candidates:
                expected_sha = sha256(safe(args.source_root, source["path"]))
            source_path = optional_verified_source(args.source_root, source["path"], expected_sha)
            if source_path is not None:
                suffix = source_path.suffix or ".source"
                destination_source = args.external_root / "source" / f"{record_id}{suffix}"
                shutil.copyfile(source_path, destination_source)
                source_value = {"path": f"source/{destination_source.name}",
                                "sha256": file_hash(destination_source)}
                if compile_candidates:
                    try:
                        path_map = [{"from": str(args.source_root.resolve()), "to": "."}]
                        if args.remote_source_root and args.remote_source_root != str(args.source_root.resolve()):
                            path_map.append({"from": args.remote_source_root, "to": "."})
                        profile = build_profile(compile_candidates[0], compiler_version=producer["version"],
                                                path_map=path_map)
                        source_request = source_document(
                            "SourceSemanticRequest", profile=profile,
                            physical_tu_id=item["physical_tu_id"], source_path=str(source_path),
                            budgets=expected_source_protocol()["budgets"])
                        source_request_path = args.external_root / "source-requests" / f"{record_id}.json"
                        source_request_path.write_text(moa_canonical(source_request) + "\n", encoding="utf-8")
                        relative_request = str(source_request_path.relative_to(args.workspace)).replace("\\", "/")
                        source_requests.append({"record_id": record_id, "path": relative_request,
                                                "sha256": file_hash(source_request_path),
                                                "schema_version": source_request["schema_version"]})
                    except AnalysisError:
                        # Unsupported source profiles remain eligible for the IR-only path.
                        pass
        record = external_document(
            "ExternalBitcodeArtifactRecord", record_id=record_id,
            physical_tu_id=item["physical_tu_id"], status="produced" if produced else item.get("status", "failed"),
            diagnostic=diagnostic_value, bitcode=bitcode_value, producer=producer,
            target={"triple": target.get("triple") or "x86_64-pc-linux-gnu",
                    "data_layout": target.get("data_layout") or "unknown", "abi": target.get("abi") or {}},
            language="c++" if item.get("language") in {"c++", "c++-header"} else "c", standard=standard,
            dialect=item.get("dialect") or "compiler-default",
            standard_source=item.get("standard_source") or "compiler-default",
            standard_option=item.get("standard_option"),
            optimization=optimization, debug=debug, module_kind=item.get("module_kind") or "translation-unit",
            source=source_value, compile_record_digest=item.get("compile_record_digest"),
            input_digests=item.get("input_digests") or {})
        record_path = args.external_root / "records" / f"{record_id}.json"
        record_path.write_text(moa_canonical(record) + "\n", encoding="utf-8")
        refs.append({"path": f"records/{record_id}.json", "sha256": file_hash(record_path)})
    scopes = []
    records_by_id = {item["record_id"]: item for item in platform.get("records", [])}
    assigned_ids: set[str] = set()
    for scope in platform.get("scopes", []):
        grouped: dict[str, list[str]] = {}
        for identifier in scope.get("record_ids", []):
            if identifier not in records_by_id:
                continue
            target = (records_by_id.get(identifier) or {}).get("target") or {}
            key = json.dumps({"triple": target.get("triple"), "data_layout": target.get("data_layout"),
                              "abi": target.get("abi") or {}}, sort_keys=True, separators=(",", ":"))
            grouped.setdefault(key, []).append(identifier)
        for index, (profile_key, record_ids) in enumerate(sorted(grouped.items()), start=1):
            profile = json.loads(profile_key)
            base_id = scope.get("target_id") or scope.get("scope_id") or "project"
            target_id = base_id if len(grouped) == 1 else f"{base_id}-profile-{index}"
            scopes.append({"target_id": target_id, "record_ids": sorted(record_ids),
                           "evidence": scope.get("evidence") or "RvPort source/x86 build graph",
                           "closed": bool(scope.get("closed")),
                           "external_boundaries": scope.get("external_boundaries") or [],
                           "target_triple": profile.get("triple") or "x86_64-pc-linux-gnu"})
            assigned_ids.update(record_ids)
    unassigned: dict[str, list[str]] = {}
    for identifier, record in records_by_id.items():
        if identifier in assigned_ids:
            continue
        target = record.get("target") or {}
        key = json.dumps({"triple": target.get("triple"), "data_layout": target.get("data_layout"),
                          "abi": target.get("abi") or {}}, sort_keys=True, separators=(",", ":"))
        unassigned.setdefault(key, []).append(identifier)
    for index, (profile_key, record_ids) in enumerate(sorted(unassigned.items()), start=1):
        profile = json.loads(profile_key)
        scopes.append({"target_id": f"project-unassigned-{index}", "record_ids": sorted(record_ids),
                       "evidence": "RvPort records without an upstream scope declaration",
                       "closed": False, "external_boundaries": ["scope-not-declared"],
                       "target_triple": profile.get("triple") or "x86_64-pc-linux-gnu"})
    bundle = external_document("ExternalBitcodeBundleManifest", bundle_id=platform["artifact_set_id"],
                               producer=producer, records=sorted(refs, key=lambda value: value["path"]),
                               scopes=sorted(scopes, key=lambda value: value["target_id"]),
                               budgets=expected_external_bitcode_protocol()["budgets"])
    bundle_path = args.external_root / "bundle.json"
    bundle_path.write_text(moa_canonical(bundle) + "\n", encoding="utf-8")
    request = unified_document(
        "UnifiedMemoryOrderAnalysisRequest", policy_preset="bounded-default-v1",
        project={"id": args.project_id, "name": args.project_name},
        bundle={"path": str(bundle_path.relative_to(args.workspace)), "sha256": file_hash(bundle_path)},
        scope_selection={"mode": "all", "scope_ids": []}, source_assistance="auto",
        source_requests=sorted(source_requests, key=lambda item: item["record_id"]),
        explicit_roots=[], cache={"mode": "read-write", "resume": True},
        cancel_file=str(args.cancel_file.resolve()), budgets=expected_unified_protocol()["budgets"])
    args.output.write_text(moa_canonical(request) + "\n", encoding="utf-8")


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser()
    sub = root.add_subparsers(dest="mode", required=True)
    for name in ("floating", "memory"):
        command = sub.add_parser(name)
        command.add_argument("--manifest", type=Path)
        command.add_argument("--artifact-root", type=Path)
        command.add_argument("--source-root", type=Path, required=True)
        command.add_argument("--compile-database", type=Path)
        command.add_argument("--remote-source-root")
        command.add_argument("--output", type=Path, required=True)
        command.add_argument("--run-id", required=True)
        command.add_argument("--source-revision", required=True)
        command.add_argument("--build-run")
        command.add_argument("--cache", type=Path, required=True)
        command.add_argument("--timeout", type=int, default=3600)
        command.add_argument("--max-frontend-output-bytes", type=int, default=536870912)
    memory_parser = sub.choices["memory"]
    memory_parser.add_argument("--external-root", type=Path, required=True)
    memory_parser.add_argument("--workspace", type=Path, required=True)
    memory_parser.add_argument("--cancel-file", type=Path, required=True)
    memory_parser.add_argument("--project-id", required=True)
    memory_parser.add_argument("--project-name", required=True)
    return root


if __name__ == "__main__":
    args = parser().parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.cache.mkdir(parents=True, exist_ok=True)
    (fp if args.mode == "floating" else memory)(args)
