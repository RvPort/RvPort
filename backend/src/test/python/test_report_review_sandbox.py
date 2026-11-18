"""使用独立子进程验证内核拒绝输入修改；不降权测试进程本身。"""
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from unittest.mock import patch

import pytest

SCRIPT = Path(__file__).parents[2] / "main/resources/agentruntime/report_review_sandbox.py"
spec = importlib.util.spec_from_file_location("report_review_sandbox", SCRIPT)
sandbox = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sandbox)


def test_real_child_reads_bound_source_but_cannot_write_truncate_delete_or_read_worker_secret():
    with tempfile.TemporaryDirectory(prefix="report-sandbox-test-") as root:
        workspace = Path(root)
        (workspace / "input").mkdir()
        (workspace / "source").mkdir()
        (workspace / "source/a.c").write_text("original")
        (workspace / "input/data.json").write_text("{}")
        with tempfile.NamedTemporaryFile(prefix="report-worker-secret-") as secret:
            secret.write(b"private")
            secret.flush()
            code = """
import os,pathlib
p=pathlib.Path('source/a.c')
assert p.read_text()=='original'
assert 'RVPORT_DATABASE_PASSWORD' not in os.environ
for action in [lambda:p.write_text('changed'),lambda:os.truncate(p,0),lambda:p.unlink(),lambda:p.chmod(0o777),lambda:open(os.environ.get('FORBIDDEN','/nonexistent')).read()]:
    try: action()
    except (PermissionError,FileNotFoundError): pass
    else: raise AssertionError('forbidden operation succeeded')
pathlib.Path('output/result.json').write_text('ok')
pathlib.Path(os.environ['HOME'],'cache').write_text('cache')
"""
            env = dict(os.environ, RVPORT_REPORT_REVIEW_SANDBOX=root, RVPORT_DATABASE_PASSWORD="fake",
                       FORBIDDEN=secret.name)
            code += "\ntry:\n    open(" + repr(secret.name) + ").read()\nexcept PermissionError:\n    pass\nelse:\n    raise AssertionError('worker secret readable')\n"
            result = subprocess.run([sys.executable, str(SCRIPT.resolve()), sys.executable, "-c", code],
                                    cwd=root, env=env, text=True, capture_output=True, timeout=20)
        assert result.returncode == 0, result.stderr
        assert (workspace / "source/a.c").read_text() == "original"
        assert (workspace / "output/result.json").read_text() == "ok"


def test_rejects_symlinks_and_nonroot_without_fallback(tmp_path):
    (tmp_path / "link").symlink_to("/etc/passwd")
    with pytest.raises(ValueError, match="PATH_UNSAFE"):
        sandbox.protect(tmp_path)
    with patch.object(sandbox.os, "geteuid", return_value=1):
        with pytest.raises(RuntimeError, match="SANDBOX_UNAVAILABLE"):
            sandbox.restrict(tmp_path)


def test_missing_configuration_and_kernel_failure_are_explicit():
    with patch.dict(os.environ, {}, clear=True):
        assert sandbox.main([]) == 126
        assert sandbox.main(["cmd"]) == 126
    with pytest.raises(OSError, match="SANDBOX_UNAVAILABLE"):
        sandbox.checked(-1)
    assert sandbox.checked(3) == 3
    with patch.dict(os.environ, {"RVPORT_REPORT_REVIEW_SANDBOX": "/fake"}, clear=True), \
            patch.object(sandbox, "restrict", side_effect=OSError("not supported")):
        assert sandbox.main(["cmd"]) == 126


def test_main_preserves_model_configuration_without_proxy_state():
    with patch.dict(os.environ, {"RVPORT_REPORT_REVIEW_SANDBOX": "/workspace", "RVPORT_OCX_HOME": "/proxy",
                                "RVPORT_AGENT_API_KEY": "fake-model", "RVPORT_DATABASE_PASSWORD": "fake-db"}, clear=True), \
            patch.object(sandbox, "restrict") as restrict, patch.object(sandbox.os, "execvp", side_effect=RuntimeError):
        assert sandbox.main(["agent", "arg"]) == 126
        restrict.assert_called_once_with("/workspace")
        assert "RVPORT_OCX_HOME" not in os.environ
        assert os.environ["RVPORT_AGENT_API_KEY"] == "fake-model"
        assert "RVPORT_DATABASE_PASSWORD" not in os.environ


@pytest.mark.parametrize("abi", [1, 2, 3])
def test_rules_cover_read_and_write_scopes_for_supported_abis(tmp_path, abi):
    from unittest.mock import Mock
    workspace = tmp_path / "workspace"
    (workspace / "input").mkdir(parents=True)
    (workspace / "source").mkdir()
    (workspace / "input/data").write_text("fixed")
    proxy = tmp_path / "proxy"
    proxy.mkdir()
    (proxy / "config").write_text("private runtime state")
    libc = Mock()
    def syscall(number, *args):
        if number == 444 and args == (0, 0, 1):
            return abi
        if number == 444:
            return os.open('/dev/null', os.O_RDONLY)
        return 0
    libc.syscall.side_effect = syscall
    libc.prctl.return_value = 0
    with patch.object(sandbox.ctypes, "CDLL", return_value=libc), patch.object(sandbox.os, "setuid") as uid, \
            patch.object(sandbox.os, "setgid"), patch.object(sandbox.os, "setgroups"), patch.dict(os.environ):
        sandbox.restrict(workspace)
        assert (workspace / ".runtime/codex").is_dir()
        assert (proxy / "config").stat().st_uid != sandbox.UID
        uid.assert_called_once_with(65534)
        libc.prctl.assert_called_once_with(38, 1, 0, 0, 0)
        assert (workspace / "input/data").stat().st_mode & 0o777 == 0o444
        assert os.environ["HOME"] == str(workspace / ".runtime")
