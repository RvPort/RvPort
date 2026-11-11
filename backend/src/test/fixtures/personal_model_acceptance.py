"""个人配置的本地隔离 Compose 验收，不连接真实模型或 SSH。"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import http.cookiejar
from http.server import ThreadingHTTPServer
import json
from pathlib import Path
import time
import urllib.error
import urllib.request

from agent_runtime_acceptance import MockModel


class PersonalMock(MockModel):
    def expected_api_key(self):
        name = self.path.split('/')[1]
        assert name in ('alice', 'bob', 'admin'), self.path
        return 'fixture-' + name

    def do_POST(self):
        self.server.identities.append({'path': self.path, 'key': self.headers.get('Authorization'),
                                       'at': time.monotonic()})
        super().do_POST()

    def do_GET(self):
        if self.path == '/__identities':
            body = json.dumps(self.server.identities).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            super().do_GET()


class Client:
    def __init__(self):
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf = {}

    def call(self, method, path, payload=None, expected=200):
        request = urllib.request.Request('http://127.0.0.1:8080' + path,
            data=None if payload is None else json.dumps(payload).encode(),
            headers={'Content-Type': 'application/json', **self.csrf}, method=method)
        try:
            response = self.opener.open(request, timeout=240)
        except urllib.error.HTTPError as error:
            response = error
        raw = response.read()
        assert response.status == expected, (method, path, response.status, raw[:500])
        assert 'no-store' in response.headers.get('Cache-Control', ''), path
        return json.loads(raw).get('data') if raw else None

    def refresh(self):
        value = self.call('GET', '/api/auth/csrf')
        self.csrf = {value['headerName']: value['token']}

    def login(self, username, password):
        self.refresh()
        self.call('POST', '/api/auth/login', {'username': username, 'password': password})
        self.refresh()


def identities():
    with urllib.request.urlopen('http://personal-model-mock:8089/__identities', timeout=5) as response:
        return json.load(response)


def wait(client, run):
    deadline = time.monotonic() + 240
    while time.monotonic() < deadline:
        result = client.call('GET', '/api/me/model-profile-agent-test-runs/' + run)
        if result['finishedAt']:
            return result
        time.sleep(.4)
    raise AssertionError('run did not converge')


def login_admin():
    client = Client()
    try:
        client.login('admin', 'PersonalTest123!')
    except AssertionError:
        client.login('admin', 'admin123')
        client.call('POST', '/api/auth/change-password',
                    {'currentPassword': 'admin123', 'newPassword': 'PersonalTest123!'})
        client.login('admin', 'PersonalTest123!')
    return client


def api(report):
    admin = login_admin()
    users = {}
    clients = {'admin': admin}
    for name in ('alice', 'bob'):
        user = admin.call('POST', '/api/admin/users',
                          {'username': name, 'email': name + '@example.invalid', 'mustChangePassword': False})
        admin.call('POST', '/api/admin/users/' + user['id'] + '/reset-password',
                   {'newPassword': 'PersonalTest123!', 'mustChangePassword': False})
        client = Client()
        client.login(name, 'PersonalTest123!')
        clients[name], users[name] = client, user['id']
        assert client.call('GET', '/api/me/model-profiles')['total'] == 0
    runtimes = admin.call('GET', '/api/me/agent-runtimes')
    runtime_ids = {row['agentType']: row['id'] for row in runtimes}
    assert set(runtime_ids) == {'CODEX', 'DEEPSEEK_HARNESS', 'OPENCODE'}
    for runtime in runtimes:
        assert runtime['executablePath'] is None and runtime['lastProbeAt'] is None
        assert not runtime['config'] and not runtime['defaultArgs'] and not runtime['lastProbeDetails']
    profiles = {}
    for name, client in clients.items():
        profiles[name] = client.call('POST', '/api/me/model-profiles', {
            'name': 'Same personal name', 'enabled': True, 'modelProviderType': 'OPENAI_COMPATIBLE',
            'baseUrl': 'http://personal-model-mock:8089/' + name + '/v1', 'model': 'fixture-model',
            'wireApi': 'CHAT', 'apiKey': 'fixture-' + name, 'timeoutSeconds': 60, 'maxRuntimeSeconds': 1800})['id']
        client.call('PATCH', '/api/me/model-stage-assignments', {'assignments': [
            {'stage': stage, 'modelProfileId': profiles[name], 'agentRuntimeId': runtime_ids['CODEX']}
            for stage in ('KB_AI_EXTENSION', 'TEST_LOG_COMPARE')]})
        listed = client.call('GET', '/api/me/model-profiles')
        assert profiles[name] in {row['id'] for row in listed['items']}
        assert client.call('GET', '/api/me/model-profiles/' + profiles[name])['executablePath'] is None
    before = len(identities())
    for name, client in clients.items():
        foreign = profiles['bob' if name != 'bob' else 'alice']
        path = '/api/me/model-profiles/' + foreign
        for method, suffix, body in [('GET', '', None), ('PATCH', '', {'model': 'wrong'}),
                ('DELETE', '', None), ('POST', ':test', {}), ('POST', ':test-connectivity', {}),
                ('POST', ':test-agents', {'agentRuntimeIds': [runtime_ids['CODEX']]}),
                ('GET', '/agent-probe-results', None)]:
            client.call(method, path + suffix, body, 404)
        client.call('PATCH', '/api/me/model-stage-assignments', {'assignments': [
            {'stage': 'KB_AI_EXTENSION', 'modelProfileId': foreign, 'agentRuntimeId': runtime_ids['CODEX']}]}, 400)
        client.call('POST', '/api/me/model-profiles', {'ownerUserId': users['alice']}, 400)
        client.call('PATCH', '/api/me/model-profiles/' + profiles[name], {'executablePath': '/bin/sh'}, 400)
        client.call('GET', '/api/admin/model-profiles', expected=404)
    assert len(identities()) == before, 'unauthorized API called upstream'
    runs = {name: clients[name].call('POST', '/api/me/model-profiles/' + profiles[name] + ':test-agents',
            {'agentRuntimeIds': [runtime_ids['CODEX']]})['id'] for name in ('alice', 'bob')}
    with ThreadPoolExecutor(2) as pool:
        results = list(pool.map(lambda name: wait(clients[name], runs[name]), ('alice', 'bob')))
    assert all(row['status'] == 'SUCCESS' and row['results'][0]['agentCompatible'] for row in results), results
    observed = identities()[before:]
    for name in ('alice', 'bob'):
        rows = [row for row in observed if row['path'].startswith('/' + name + '/')]
        assert rows and all(row['key'] == 'Bearer fixture-' + name for row in rows), rows
        for other, client in clients.items():
            if other != name:
                client.call('GET', '/api/me/model-profile-agent-test-runs/' + runs[name], expected=404)
        history = clients[name].call('GET', '/api/me/model-profiles/' + profiles[name] + '/agent-probe-results')
        assert history and all(row['modelProfileId'] == profiles[name] for row in history)
    for agent in ('OPENCODE', 'DEEPSEEK_HARNESS'):
        run = clients['alice'].call('POST', '/api/me/model-profiles/' + profiles['alice'] + ':test-agents',
                                  {'agentRuntimeIds': [runtime_ids[agent]]})['id']
        result = wait(clients['alice'], run)
        assert result['status'] == 'SUCCESS', result
    native = clients['alice'].call('POST', '/api/me/model-profiles/' + profiles['alice'] + ':test',
                                  {'agentRuntimeId': runtime_ids['CODEX'], 'wireApi': 'RESPONSES'})
    assert native['success'], native
    assert clients['alice'].call('GET', '/api/me/model-profiles/' + profiles['alice'])['wireApi'] == 'CHAT'
    before = len(identities())
    admin.call('PATCH', '/api/admin/users/' + users['bob'], {'status': 'DISABLED'})
    clients['bob'].call('GET', '/api/me/model-profiles', expected=401)
    assert len(identities()) == before
    admin.call('PATCH', '/api/admin/users/' + users['bob'], {'status': 'ACTIVE'})
    report.write_text(json.dumps({'users': users, 'profiles': profiles, 'runtimeIds': runtime_ids,
                                 'runs': runs, 'api': 'PASS', 'upstreamRequests': len(identities())}, indent=2))
    print('PERSONAL_API_PASS: A/B/admin isolation, owner bindings, concurrent CHAT, all runtimes, native Responses, disabled account')


def recovery(phase, report):
    state = json.loads(report.read_text())
    client = Client()
    client.login('alice', 'PersonalTest123!')
    path = '/api/me/model-profiles/' + state['profiles']['alice']
    if phase.startswith('queue-'):
        timeout = 2 if phase == 'queue-restart' else 30
        client.call('PATCH', path, {'enabled': True, 'timeoutSeconds': timeout})
        state['recoveryRun'] = client.call('POST', path + ':test-agents',
                            {'agentRuntimeIds': [state['runtimeIds']['CODEX']]})['id']
        state['beforeRecoveryRequests'] = len(identities())
        if phase == 'queue-disabled':
            time.sleep(.5)
            client.call('PATCH', path, {'enabled': False})
        report.write_text(json.dumps(state, indent=2))
    else:
        result = wait(client, state['recoveryRun'])
        assert result['status'] == 'FAILED', result
        assert len(identities()) == state['beforeRecoveryRequests'], 'invalid queued probe reached model'
        for runtime in client.call('GET', '/api/me/agent-runtimes'):
            assert runtime['lastProbeStatus'] is None and runtime['lastProbeAt'] is None
        state[phase] = 'PASS'
        report.write_text(json.dumps(state, indent=2))
        client.call('PATCH', path, {'enabled': True, 'timeoutSeconds': 60})
        print('PERSONAL_RECOVERY_PASS:', phase)


def concurrent(report):
    state = json.loads(report.read_text())
    clients = {name: Client() for name in ('alice', 'bob')}
    for name, client in clients.items():
        client.login(name, 'PersonalTest123!')
    before = len(identities())
    runs = {name: client.call('POST', '/api/me/model-profiles/' + state['profiles'][name] + ':test-agents',
            {'agentRuntimeIds': [state['runtimeIds']['CODEX']]})['id'] for name, client in clients.items()}
    with ThreadPoolExecutor(2) as pool:
        results = list(pool.map(lambda name: wait(clients[name], runs[name]), clients))
    assert all(row['status'] == 'SUCCESS' for row in results), results
    observed = identities()[before:]
    spans = {}
    for name in clients:
        rows = [row for row in observed if row['path'].startswith('/' + name + '/')]
        assert rows and all(row['key'] == 'Bearer fixture-' + name for row in rows)
        spans[name] = [min(row['at'] for row in rows), max(row['at'] for row in rows)]
    assert max(span[0] for span in spans.values()) < min(span[1] for span in spans.values()), spans
    state['parallelWorkers'] = {'status': 'PASS', 'overlappingSpans': spans}
    report.write_text(json.dumps(state, indent=2))
    print('PERSONAL_PARALLEL_PASS: overlapping real CLI requests preserve owner-specific endpoint and key')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('phase', choices=['serve', 'api', 'concurrent', 'queue-disabled', 'check-disabled', 'queue-restart', 'check-restart'])
    parser.add_argument('--report', default='/tmp/personal-model-api.json')
    args = parser.parse_args()
    if args.phase == 'serve':
        server = ThreadingHTTPServer(('0.0.0.0', 8089), PersonalMock)
        server.requests, server.identities = [], []
        server.omit_usage, server.failure_marker = False, None
        server.serve_forever()
    elif args.phase == 'api':
        api(Path(args.report))
    elif args.phase == 'concurrent':
        concurrent(Path(args.report))
    else:
        recovery(args.phase, Path(args.report))
