"""supervisor 的系统调用使用替身；真实孤儿/中断回归由 Java 进程夹具补充。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import pytest

SPEC = importlib.util.spec_from_file_location("agent_supervisor", Path(__file__).parents[2] / "main/resources/agentruntime/agent_process_supervisor.py")
supervisor = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(supervisor)


def test_reads_only_own_children(monkeypatch):
    monkeypatch.setattr(supervisor.os, 'getpid', lambda: 42)
    seen = []
    monkeypatch.setattr(supervisor.Path, 'read_text', lambda path: seen.append(str(path)) or '123 456\n')
    assert supervisor.children() == [123, 456]
    assert seen == ['/proc/42/task/42/children']


def test_reap_exited_primary_and_adopted_children(monkeypatch):
    responses = iter([(2, 0), (1, 768), (0, 0)])
    monkeypatch.setattr(supervisor.os, 'waitpid', lambda *args: next(responses))
    assert supervisor.reap(1) == 3
    monkeypatch.setattr(supervisor.os, 'waitpid', lambda *args: (_ for _ in ()).throw(ChildProcessError()))
    assert supervisor.reap(1) is None


def test_kill_only_owned_children_and_handle_exit_race(monkeypatch):
    monkeypatch.setattr(supervisor, 'children', lambda: [1, 2])
    seen = []

    def kill(pid, sig):
        seen.append((pid, sig))
        if pid == 2:
            raise ProcessLookupError()

    monkeypatch.setattr(supervisor.os, 'kill', kill)
    supervisor.terminate_owned()
    assert seen == [(1, supervisor.signal.SIGKILL), (2, supervisor.signal.SIGKILL)]


@pytest.mark.parametrize('exit_code,expected', [(0, 0), (3, 3), (-9, 137)])
def test_normal_exit_waits_for_orphan_reaping(monkeypatch, exit_code, expected):
    reaped = iter([None, exit_code, None])
    children = iter([[12], []])
    kills = []
    sleeps = []
    monkeypatch.setattr(supervisor, 'reap', lambda pid: next(reaped))
    monkeypatch.setattr(supervisor, 'children', lambda: next(children))
    monkeypatch.setattr(supervisor, 'terminate_owned', lambda: kills.append(True))
    process = SimpleNamespace(pid=10, returncode=None)
    assert supervisor.supervise(process, [False], lambda: 0, sleeps.append) == expected
    assert process.returncode == exit_code
    assert kills == [True]
    assert sleeps == [0.01, 0.01]


def test_cancel_and_cleanup_deadline(monkeypatch, capsys):
    monkeypatch.setattr(supervisor, 'reap', lambda pid: None)
    monkeypatch.setattr(supervisor, 'children', lambda: [])
    assert supervisor.supervise(SimpleNamespace(pid=1), [True]) == 130
    monkeypatch.setattr(supervisor, 'children', lambda: [2])
    monkeypatch.setattr(supervisor, 'terminate_owned', lambda: None)
    times = iter([0, 1.5])
    assert supervisor.supervise(SimpleNamespace(pid=1), [True], lambda: next(times)) == 125
    assert 'AGENT_SUPERVISOR_CLEANUP_INCOMPLETE' in capsys.readouterr().err


def test_startup_and_signal_handling(monkeypatch, capsys):
    assert supervisor.main([]) == 127
    monkeypatch.setattr(supervisor.ctypes, 'CDLL', lambda *args, **kwargs: SimpleNamespace(prctl=lambda *args: -1))
    assert supervisor.main(['example']) == 127
    monkeypatch.setattr(supervisor.ctypes, 'CDLL', lambda *args, **kwargs: SimpleNamespace(prctl=lambda *args: 0))
    handlers = {}
    monkeypatch.setattr(supervisor.signal, 'signal', lambda sig, handler: handlers.update({sig: handler}))
    monkeypatch.setattr(supervisor.subprocess, 'Popen', lambda *args, **kwargs: (_ for _ in ()).throw(OSError('secret')))
    assert supervisor.main(['example']) == 127
    process = SimpleNamespace(pid=1)
    monkeypatch.setattr(supervisor.subprocess, 'Popen', lambda *args, **kwargs: process)

    def run(child, cancelled):
        assert child is process
        handlers[supervisor.signal.SIGTERM](15, None)
        assert cancelled == [True]
        return 130

    monkeypatch.setattr(supervisor, 'supervise', run)
    assert supervisor.main(['example']) == 130
    assert 'secret' not in capsys.readouterr().err
