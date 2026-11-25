"""固定源码发现计划；内部解析参数不是用户的构建事实。"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path

LANGUAGES = ("c", "cpp", "rust", "go", "java", "python", "asm")
EXTENSIONS = {".c": "c", ".cc": "cpp", ".cp": "cpp", ".cpp": "cpp",
              ".cxx": "cpp", ".c++": "cpp", ".rs": "rust", ".go": "go",
              ".java": "java", ".py": "python", ".s": "asm", ".asm": "asm"}


def canonical(value: object) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def languages(value: str | None) -> list[str]:
    items = {item.strip().lower() for item in (value or "c,cpp").split(",")}
    if not items or not items <= set(LANGUAGES):
        raise ValueError("ANALYSIS_LANGUAGE_UNSUPPORTED")
    return [item for item in LANGUAGES if item in items]


def relative_path(value: str) -> str:
    normalized = value.replace("\\", "/")
    if not normalized or normalized.startswith("/") or ":" in normalized or any(ord(c) < 32 or ord(c) == 127 for c in normalized):
        raise ValueError("SOURCE_INPUT_INVALID")
    if any(part in ("", ".", "..") for part in normalized.split("/")):
        raise ValueError("SOURCE_INPUT_INVALID")
    return normalized


def discover(root: Path, selected: str = "c,cpp", *, excludes: tuple[str, ...] = (),
             max_files: int = 100000, max_entries: int = 100000, max_bytes: int = 1024 * 1024 * 1024) -> dict:
    if root.is_symlink() or not root.is_dir() or max_files < 1:
        raise ValueError("SOURCE_INPUT_INVALID")
    root = root.resolve()
    selected_languages = languages(selected)
    excluded = sorted({relative_path(value) for value in excludes})
    files, units = [], []
    planned_count = 0
    # 不跟随目录符号链接，也拒绝文件符号链接；不能把越界文件默认为已扫描。
    paths = []
    total_entries = total_bytes = 0
    for directory, directories, names in os.walk(root, followlinks=False):
        directories[:] = sorted(name for name in directories if name not in (".git", ".rvport-analysis-cache"))
        if any((Path(directory) / name).is_symlink() for name in directories):
            raise ValueError("SOURCE_INPUT_INVALID")
        total_entries += len(directories) + len(names)
        if total_entries > max_entries:
            raise ValueError("ANALYSIS_SOURCE_MANIFEST_LIMIT_EXCEEDED")
        paths.extend(Path(directory) / name for name in names)
    for path in sorted(paths):
        if path.is_symlink():
            raise ValueError("SOURCE_INPUT_INVALID")
        if not path.is_file():
            raise ValueError("SOURCE_INPUT_INVALID")
        relative = relative_path(path.relative_to(root).as_posix())
        language = "cpp" if path.suffix == ".C" else EXTENSIONS.get(path.suffix.lower())
        status = "PLANNED"
        if any(relative == item or relative.startswith(item + "/") for item in excluded):
            status = "EXCLUDED"
        elif language is None:
            status = "UNSUPPORTED"
        elif language not in selected_languages:
            status = "NOT_SELECTED"
        elif planned_count >= max_files:
            status = "OMITTED"
        if status == "PLANNED":
            planned_count += 1
        content_hash = hashlib.sha256()
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                total_bytes += len(chunk)
                if total_bytes > max_bytes:
                    raise ValueError("ANALYSIS_SOURCE_BYTE_LIMIT_EXCEEDED")
                content_hash.update(chunk)
        digest = content_hash.hexdigest()
        files.append({"path": relative, "language": language, "status": status, "sha256": digest})
        if status == "PLANNED" and language in ("c", "cpp"):
            standard = "c17" if language == "c" else "c++17"
            units.append({"file": str(path), "directory": str(root),
                          "arguments": ["clang" if language == "c" else "clang++",
                                        "-std=" + standard, "-I", str(root), "-c", str(path)]})
    semantic = {"planVersion": 1, "inputOrigin": "SOURCE_DISCOVERY",
                "effectiveLanguages": selected_languages, "excludePaths": excluded,
                "files": files, "maxFiles": max_files,
                "assumptions": ["DEFAULT_ASSUMPTION", "C17_CPP17", "OPEN_SCOPE"],
                "missingEvidence": ["COMPILE_DATABASE_UNAVAILABLE", "BITCODE_UNAVAILABLE"]}
    return {**semantic, "inputDigest": hashlib.sha256(canonical(semantic)).hexdigest(),
            "translationUnits": units, "denominatorKnown": False}
