"""代理集成单元测试：第三方及进程边界全部由确定性替身控制。"""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import threading
import time
from unittest.mock import Mock
import pytest

SOURCE = Path(__file__).parents[2] / 'main/resources/agentruntime'
sys.path.insert(0, str(SOURCE))
import opencodex_guard as guard
import opencodex_runtime as runtime


def profile():
    return runtime.configuration('http://upstream/v1', 'model', 'private-key', 1234, 3)


@pytest.mark.parametrize('url,model,key', [
    ('file:///tmp/a', 'm', 'k'), ('http://user:pw@host', 'm', 'k'), ('http://host?q=x', 'm', 'k'),
    ('http://host/#x', 'm', 'k'), ('http:///a', 'm', 'k'), ('http://h', '', 'k'),
    ('http://h', 'a\nb', 'k'), ('http://h', 'a', ''),
])
def test_invalid_profile(url, model, key):
    with pytest.raises(ValueError):
        runtime.configuration(url, model, key, 1, 1)


def test_configuration_and_command():
    cfg = profile()
    assert cfg['providers']['rvport']['apiKey'] == 'private-key'
    assert cfg['clientIntegrations'] == {'codex': False, 'grok': False, 'claude-desktop': False}
    assert cfg['fallback'] == {'model': False, 'provider': False}
    command = ['codex', '-c', 'model_providers.rvport-openai-compatible.base_url="secret"', '-']
    changed = runtime.local_command(command, 'http://127.0.0.1:2/v1')
    assert changed[2].endswith('"http://127.0.0.1:2/v1"') and command[2].endswith('"secret"')
    with pytest.raises(ValueError):
        runtime.local_command(['codex'], 'url')


def ready_values():
    return ({'service': 'opencodex', 'version': '2.94.0', 'port': 1234},
        [{'name': 'rvport', 'adapter': 'openai-chat', 'baseUrl': 'http://upstream/v1',
          'defaultModel': 'model', 'hasApiKey': True, 'models': ['model']}],
        {'data': [{'id': 'rvport/model'}]})


def test_readiness_rejects_fallback_and_mismatch():
    health, providers, models = ready_values()
    runtime.verify_ready(health, providers, models, profile())
    for index, replacement in [(0, {}), (1, []), (1, [{}]),
            (1, [{**providers[0], 'hasApiKey': False}]), (1, [{**providers[0], 'models': []}]),
            (2, {'data': [{'id': 'other'}]})]:
        args = [health, providers, models]
        args[index] = replacement
        with pytest.raises(ValueError):
            runtime.verify_ready(*args, profile())


def test_management_is_bounded_and_closed(monkeypatch):
    conn = Mock()
    response = conn.getresponse.return_value
    response.status = 200
    response.read.return_value = b'{"ok":true}'
    monkeypatch.setattr(runtime.http.client, 'HTTPConnection', Mock(return_value=conn))
    assert runtime.management(2, '/healthz', 'private-admin', .1) == {'ok': True}
    conn.close.assert_called_once()
    response.status = 401
    with pytest.raises(OSError):
        runtime.management(2, '/', 'key', 1)
    response.status = 200
    response.read.return_value = b'x' * (1024 * 1024 + 1)
    with pytest.raises(ValueError):
        runtime.management(2, '/', 'key', 1)


def test_wait_ready_failure_retry_and_deadline(tmp_path, monkeypatch):
    proc = Mock(); proc.poll.return_value = None
    values = iter([OSError('retry'), *ready_values()])
    def query(*args):
        item = next(values)
        if isinstance(item, Exception):
            raise item
        return item
    monkeypatch.setattr(runtime, 'management', query)
    monkeypatch.setattr(runtime, 'verify_listeners', Mock())
    runtime.wait_ready(proc, profile(), tmp_path, 'admin', time.monotonic()+1, threading.Event(), lambda _: None)
    proc.poll.return_value = 1
    with pytest.raises(ValueError):
        runtime.wait_ready(proc, profile(), tmp_path, 'a', time.monotonic()+1, threading.Event())
    proc.poll.return_value = None
    (tmp_path/'config.json.invalid-test').touch()
    with pytest.raises(ValueError):
        runtime.wait_ready(proc, profile(), tmp_path, 'a', time.monotonic()+1, threading.Event())
    with pytest.raises(TimeoutError):
        runtime.wait_ready(proc, profile(), tmp_path, 'a', 0, threading.Event())


def test_stop_escalates_and_reaps():
    runtime.stop(None)
    proc = Mock(); proc.poll.return_value = 0
    runtime.stop(proc); proc.terminate.assert_not_called()
    proc.poll.return_value = None
    runtime.stop(proc); proc.terminate.assert_called_once()
    proc.wait.side_effect = [subprocess.TimeoutExpired('ocx', .5), 0]
    runtime.stop(proc); proc.kill.assert_called_once()


def test_listener_ownership_and_extra_ports(tmp_path):
    proc = tmp_path
    for pid, children in [('10','11 11 12'),('11','')]:
        (proc/pid/'task'/pid).mkdir(parents=True)
        (proc/pid/'task'/pid/'children').write_text(children)
        (proc/pid/'fd').mkdir()
    (proc/'11/fd/4').symlink_to('socket:[45]')
    (proc/'11/fd/5').symlink_to('/dev/null')
    (proc/'net').mkdir()
    (proc/'net/tcp6').write_text('header\n')
    good='0: 0100007F:04D2 00000000:0000 0A 0 0 0 0 0 45\n'
    (proc/'net/tcp').write_text('header\n'+good)
    runtime.verify_listeners(10,1234,proc)
    (proc/'net/tcp').write_text('header\n'+good+good.replace('04D2','04D3'))
    with pytest.raises(ValueError,match='LISTENER'):
        runtime.verify_listeners(10,1234,proc)
    (proc/'net/tcp').write_text('header\n'+good.replace('45','99')+good.replace('0A','01'))
    with pytest.raises(ValueError,match='LISTENER'):
        runtime.verify_listeners(10,1234,proc)


@pytest.mark.parametrize('scenario', ['ok', 'proxy-failed', 'deadline', 'startup-failed'])
def test_runtime_separates_credentials_and_cleans(tmp_path, monkeypatch, scenario):
    calls = []
    proxy = Mock(); proxy.poll.return_value = None
    cli = Mock(); cli.poll.return_value = 0; cli.returncode = 0
    fake_guard = Mock(); fake_guard.server_port = 5001
    monkeypatch.setattr(runtime, 'Guard', Mock(return_value=fake_guard))
    def launch(command, **kwargs):
        calls.append((command, kwargs))
        if len(calls) == 1:
            return proxy
        if scenario == 'proxy-failed':
            proxy.poll.return_value = 1
        return cli
    monkeypatch.setattr(runtime.subprocess, 'Popen', launch)
    cancelled = threading.Event()
    def ready(*args):
        if scenario == 'startup-failed':
            raise ValueError('readiness')
        if scenario == 'deadline':
            cancelled.set()
    monkeypatch.setattr(runtime, 'wait_ready', ready)
    env = {'RVPORT_OCX_HOME': str(tmp_path), 'OPENAI_BASE_URL': 'http://upstream/v1',
           'OPENAI_MODEL': 'model', 'OPENAI_API_KEY': 'private-key', 'RVPORT_OCX_EXECUTABLE': 'ocx',
           'PATH': '/bin', 'UNRELATED_SECRET': 'other-secret'}
    command = ['codex', 'model_providers.rvport-openai-compatible.base_url="old"']
    if scenario == 'ok':
        assert runtime.run(command, env, time.monotonic()+3, cancelled) == 0
    else:
        with pytest.raises((ValueError, RuntimeError, TimeoutError)):
            runtime.run(command, env, time.monotonic()+3, cancelled)
    assert 'OPENAI_API_KEY' not in calls[0][1]['env']
    assert 'UNRELATED_SECRET' not in calls[0][1]['env']
    assert json.loads((tmp_path/'proxy/config.json').read_text())['providers']['rvport']['apiKey'] == 'private-key'
    assert (tmp_path/'proxy/config.json').stat().st_mode & 0o777 == 0o600
    if scenario != 'startup-failed':
        assert calls[1][1]['env']['OPENAI_API_KEY'] != 'private-key'
        assert 'private-key' not in str(calls[1])
        fake_guard.cancel.assert_called_once()
    proxy.terminate.assert_called_once() if scenario != 'proxy-failed' else None


@pytest.mark.parametrize('error,expected', [(None,0), (TimeoutError(),124), (OSError(),126),
    (ValueError('private-key'),126), (KeyError(),126), (ValueError('PROFILE_INVALID'),126),
    (ValueError('PROXY_MODEL_MISMATCH'),126), (RuntimeError('PROXY_EXITED'),126),
    (TimeoutError('PROXY_START_DEADLINE'),124)])
def test_main_safe_diagnostics(monkeypatch, capsys, error, expected):
    def execute(*args):
        if error is not None:
            raise error
        return 0
    handlers = []
    monkeypatch.setattr(runtime.signal, 'signal', lambda _, handler: handlers.append(handler))
    monkeypatch.setattr(runtime, 'run', execute)
    assert runtime.main(['codex'], {'RVPORT_OCX_TIMEOUT_SECONDS': '1'}) == expected
    for handler in handlers:
        handler(None,None)
    assert 'private-key' not in capsys.readouterr().err


@pytest.mark.parametrize('value', [
    {'model':'model','input':'hello'},
    {'model':'model','input':[{'role':'user','content':'hi'}]},
    {'model':'model','input':[{'role':'assistant','content':[{'type':'output_text','text':'好'}]}]},
    {'model':'model','tools':[{'type':'function','name':'read','parameters':{}}],
     'input':[{'type':'function_call','call_id':'a','name':'read','arguments':'{}'},
                             {'type':'function_call_output','call_id':'a','output':'result'}]},
    {'model':'model','tools':[{'type':'custom','name':'apply_patch'}], 'input':[
        {'type':'custom_tool_call','call_id':'a','name':'apply_patch','input':'patch'},
        {'type':'custom_tool_call_output','call_id':'a','output':'result'}]},
])
def test_request_validation_preserves_input(value):
    original = json.dumps(value)
    guard.validate_request(value,'model')
    assert json.dumps(value) == original


@pytest.mark.parametrize('extra', [
    {'model':'other'}, {'previous_response_id':'r'}, {'background':True}, {'tools':{}},
    {'tools':['bad']}, {'tools':[{'type':'web_search'}]}, {'tools':[{'type':'function','name':''}]},
    {'tools':[{'type':'custom','name':'other'}]}, {'input':1}, {'input':['bad']},
    {'input':[{'type':'function_call_output','call_id':'unknown'}]},
    {'input':[{'type':'function_call','call_id':'a','arguments':'[]'}]},
    {'input':[{'type':'function_call','call_id':'','arguments':'{}'}]},
    {'input':[{'type':'function_call','call_id':'a','arguments':'{}'}]*2},
    {'input':[{'type':'custom_tool_call','call_id':'a'}, {'type':'function_call_output','call_id':'a'}]},
    {'input':[{'type':'custom_tool_call','call_id':'a'}, {'type':'custom_tool_call_output','call_id':'a'},
              {'type':'custom_tool_call_output','call_id':'a'}]},
    {'input':[{'role':'tool','content':'x'}]}, {'input':[{'role':'user','content':None}]},
    {'input':[{'role':'user','content':[{'type':'input_image'}]}]}, {'input':[{'type':'reasoning'}]},
    {'store':True}, {'text':{'format':{'type':'json_schema'}}}, {'reasoning':{'effort':'high'}},
    {'include':['other']}, {'input':[{'role':'user','content':'hi','encrypted_content':'secret'}]},
    {'tools':[{'type':'custom','name':'apply_patch','format':{'type':'grammar','syntax':'regex','definition':'.*'}}]},
])
def test_request_rejection(extra):
    with pytest.raises(ValueError):
        guard.validate_request({'model':'model','input':'hi',**extra}, 'model')
    with pytest.raises(ValueError):
        guard.validate_request([], 'model')
