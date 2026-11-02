"""由宿主机在隔离 Compose worker/API 停启之间调用；不操作 Docker 或远程环境。"""
import http.cookiejar
import json
from pathlib import Path
import sys
import time
import urllib.request

opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
csrf = {}


def call(method, path, payload=None):
    request = urllib.request.Request('http://127.0.0.1:8080/api/' + path,
        data=None if payload is None else json.dumps(payload).encode(),
        headers={'Content-Type': 'application/json', **csrf}, method=method)
    with opener.open(request, timeout=100) as response:
        return json.load(response)['data']


def refresh():
    token = call('GET', 'auth/csrf')
    csrf.update({token['headerName']: token['token']})


def start(profile, agents):
    return call('POST', 'admin/model-profiles/' + profile + ':test-agents',
                {'agentRuntimeIds': [runtime_ids[agent] for agent in agents]})['id']


def wait(run):
    deadline = time.monotonic() + 100
    while time.monotonic() < deadline:
        result = call('GET', 'admin/model-profile-agent-test-runs/' + run)
        if result['finishedAt']:
            return result
        time.sleep(.3)
    raise AssertionError('Run failed to converge')


def upstream_count():
    with urllib.request.urlopen('http://agent-mock:8089/__metrics', timeout=5) as response:
        return json.load(response)['requests']


refresh()
call('POST', 'auth/login', {'username': 'admin', 'password': 'AgentTest123!'})
refresh()
baseline = json.loads(Path('/tmp/agent-api-acceptance.json').read_text())
runtime_ids = baseline['runtime_ids']
profile = baseline['second']
path = 'admin/model-profiles/' + profile
state_path = Path('/tmp/probe-recovery-acceptance.json')
state = json.loads(state_path.read_text()) if state_path.exists() else {}
phase = sys.argv[1]
if phase == 'offline':
    call('PATCH', path, {'timeoutSeconds': 10})
    result = wait(start(profile, ['CODEX', 'OPENCODE']))
    assert result['status'] == 'PARTIAL_SUCCESS', result
    by_agent = {item['agentType']: item for item in result['results']}
    assert not by_agent['CODEX']['agentCompatible'] and by_agent['OPENCODE']['agentCompatible'], result
    state['offlineMixed'] = result
    call('PATCH', path, {'timeoutSeconds': 1})
    started = time.monotonic()
    legacy = call('POST', path + ':test', {'agentRuntimeId': runtime_ids['CODEX']})
    assert not legacy['success'] and time.monotonic() - started < 8, legacy
    state['offlineLegacy'] = legacy
elif phase == 'queue-disabled':
    call('PATCH', path, {'enabled': True, 'timeoutSeconds': 30})
    state['disabledRun'] = start(profile, ['CODEX'])
    state['disabledUpstreamBefore'] = upstream_count()
    time.sleep(1)
    call('PATCH', path, {'enabled': False})
elif phase == 'check-disabled':
    result = wait(state['disabledRun'])
    assert result['status'] == 'FAILED' and not result['results'][0]['agentCompatible'], result
    assert not result['results'][0]['checks'], result
    assert upstream_count() == state['disabledUpstreamBefore'], 'disabled profile reached upstream'
    state['disabledResult'] = result
    call('PATCH', path, {'enabled': True})
elif phase == 'queue-restart':
    call('PATCH', path, {'enabled': True, 'timeoutSeconds': 2})
    state['beforeRestartProfileAt'] = call('GET', path)['lastTestedAt']
    state['beforeRestartRuntimeAt'] = call('GET', 'admin/agent-runtimes/' + runtime_ids['CODEX'])['lastProbeAt']
    state['restartRun'] = start(profile, ['CODEX'])
    time.sleep(1)
elif phase == 'check-restart':
    result = wait(state['restartRun'])
    assert result['status'] == 'FAILED' and not result['results'][0]['agentCompatible'], result
    summary = call('GET', path)
    runtime = call('GET', 'admin/agent-runtimes/' + runtime_ids['CODEX'])
    assert summary['lastTestStatus'] == 'FAILED', summary
    assert runtime['lastProbeStatus'] == 'FAILED', runtime
    assert summary['lastTestedAt'] != state['beforeRestartProfileAt'], 'profile summary did not advance'
    assert runtime['lastProbeAt'] != state['beforeRestartRuntimeAt'], 'runtime summary did not advance'
    assert summary['lastTestDetails']['errorCode'] == 'AGENT_PROBE_WORKER_TIMED_OUT', summary
    state['restartResult'] = result
    state['restartSummary'] = {'profile': summary['lastTestStatus'], 'runtime': runtime['lastProbeStatus']}
    call('PATCH', path, {'timeoutSeconds': 30})
else:
    raise AssertionError('Unknown phase')
state_path.write_text(json.dumps(state, ensure_ascii=False, indent=2))
print('RECOVERY_ACCEPTANCE_PASSED: ' + phase, flush=True)
