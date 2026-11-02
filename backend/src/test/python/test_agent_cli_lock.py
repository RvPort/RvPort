"""依赖锁验证器的独立单元测试，不安装第三方包。"""
import base64
import importlib.util
import json
from pathlib import Path
import pytest

SPEC = importlib.util.spec_from_file_location("verify_lock", Path(__file__).parents[3] / "agent-cli/verify_lock.py")
lockcheck = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(lockcheck)


@pytest.fixture
def tree(tmp_path):
    package = {"version": "1.0.0", "integrity": "sha512-" + base64.b64encode(bytes(64)).decode(),
               "resolved": "https://registry.npmjs.org/a/-/a-1.0.0.tgz"}
    manifest = {"dependencies": {"a": "1.0.0"}, "engines": {"node": "22.23.2", "npm": "10.9.8"}}
    lock = {"lockfileVersion": 3, "packages": {"": manifest, "node_modules/a": package}}
    save(tmp_path / "package.json", manifest)
    save(tmp_path / "package-lock.json", lock)
    save(tmp_path / "node_modules/a/package.json", {"version": "1.0.0"})
    (tmp_path / "node_modules/.bin").mkdir()
    return tmp_path, lock


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value))


def test_valid_tree_and_main(tree, monkeypatch, capsys):
    root, _ = tree
    assert lockcheck.verify(root)[0]["path"] == "node_modules/a"
    monkeypatch.setattr(lockcheck.subprocess, "check_output", lambda args, **kw:
        '["linux","x64"]' if "-p" in args else ('v22.23.2\n' if args[0] == "node" else '10.9.8\n'))
    lockcheck.main(root)
    assert json.loads(capsys.readouterr().out)[0]["version"] == "1.0.0"


@pytest.mark.parametrize("change,error", [
    (lambda l: l.update(lockfileVersion=2), "锁文件"),
    (lambda l: l['packages'][''].update(dependencies={}), "不一致"),
    (lambda l: l['packages']['node_modules/a'].update(integrity='sha1-abc'), "摘要"),
    (lambda l: l['packages']['node_modules/a'].update(integrity='sha512-YQ=='), "摘要"),
    (lambda l: l['packages']['node_modules/a'].update(resolved='http://example.org/a'), "来源"),
    (lambda l: l['packages']['node_modules/a'].update(version='2.0.0'), "版本"),
    (lambda l: l['packages']['node_modules/a'].update(os=['darwin']), "平台"),
    (lambda l: l['packages'].pop('node_modules/a'), "额外包"),
])
def test_invalid_lock(tree, change, error):
    root, lock = tree
    change(lock)
    save(root / 'package-lock.json', lock)
    with pytest.raises(ValueError, match=error):
        lockcheck.verify(root)


def test_missing_extra_scoped_and_platform(tree):
    root, lock = tree
    with pytest.raises(ValueError, match="linux/x64"):
        lockcheck.verify(root, ("linux", "arm64"))
    with pytest.raises(ValueError, match="Node/npm"):
        lockcheck.verify(root, versions={})
    (root / 'node_modules/a/package.json').unlink()
    with pytest.raises(ValueError, match="清单缺失"):
        lockcheck.verify(root)
    (root / 'node_modules/a').rmdir()
    with pytest.raises(ValueError, match="缺失包"):
        lockcheck.verify(root)
    lock['packages']['node_modules/a']['os'] = ['darwin']
    save(root / 'package-lock.json', lock)
    assert lockcheck.verify(root) == []
    save(root / 'node_modules/@scope/b/package.json', {'version': '1.0.0'})
    with pytest.raises(ValueError, match="额外包"):
        lockcheck.verify(root)


def test_empty_installed_directory(tmp_path):
    assert lockcheck.installed_packages(tmp_path) == {}
    assert not lockcheck.supported({'cpu': ['arm64']}, ('linux', 'x64'))
