"""冻结安装验收；在镜像内运行，输出可比较的实际依赖清单。"""
import base64
import json
from pathlib import Path
import subprocess
import sys


def supported(package, platform):
    return all(not package.get(key) or value in package[key]
               for key, value in zip(("os", "cpu"), platform))


def installed_packages(directory):
    result = {}

    def visit(modules):
        if not modules.exists():
            return
        for path in modules.iterdir():
            if path.name.startswith("."):
                continue
            candidates = path.iterdir() if path.name.startswith("@") else [path]
            for candidate in candidates:
                manifest = candidate / "package.json"
                if not manifest.is_file():
                    raise ValueError(f"包清单缺失: {candidate.relative_to(directory)}")
                result[candidate.relative_to(directory).as_posix()] = json.loads(manifest.read_text())
                visit(candidate / "node_modules")

    visit(directory / "node_modules")
    return result


def verify(directory, platform=("linux", "x64"), versions=None):
    manifest = json.loads((directory / "package.json").read_text())
    lock = json.loads((directory / "package-lock.json").read_text())
    if platform != ("linux", "x64"):
        raise ValueError("当前镜像只验收 linux/x64")
    if lock.get("lockfileVersion") != 3:
        raise ValueError("需要 npm v3 锁文件")
    packages = lock["packages"]
    if packages[""]["dependencies"] != manifest["dependencies"]:
        raise ValueError("清单与锁文件不一致")
    if versions is not None and versions != manifest["engines"]:
        raise ValueError("Node/npm 版本不一致")
    actual = installed_packages(directory)
    normalized = []
    for path, package in packages.items():
        if not path:
            continue
        integrity = package.get("integrity", "")
        if not integrity.startswith("sha512-") or len(base64.b64decode(integrity[7:], validate=True)) != 64:
            raise ValueError(f"包完整性摘要无效: {path}")
        if not package.get("resolved", "").startswith("https://registry.npmjs.org/"):
            raise ValueError(f"包来源不受信任: {path}")
        if not supported(package, platform):
            if path in actual:
                raise ValueError(f"平台不匹配: {path}")
            continue
        if path not in actual:
            raise ValueError(f"缺失包: {path}")
        installed = actual.pop(path)
        if installed["version"] != package["version"]:
            raise ValueError(f"版本不一致: {path}")
        normalized.append({"path": path, "version": package["version"], "integrity": integrity})
    if actual:
        raise ValueError("额外包: " + ", ".join(sorted(actual)))
    return sorted(normalized, key=lambda item: item["path"])


def main(directory):
    versions = {name: subprocess.check_output([name, "--version"], text=True).strip().removeprefix("v")
                for name in ("node", "npm")}
    platform = tuple(json.loads(subprocess.check_output(
        ["node", "-p", "JSON.stringify([process.platform,process.arch])"], text=True)))
    print(json.dumps(verify(Path(directory), platform, versions), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main(sys.argv[1])
