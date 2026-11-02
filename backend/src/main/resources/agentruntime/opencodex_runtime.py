"""worker 内单次执行的 OpenCodex/Codex 监管器，外层 subreaper 负责强制回收。"""
import http.client
import json
import os
from pathlib import Path
import secrets
import signal
import socket
import subprocess
import sys
import threading
import time
from urllib.parse import urlsplit
from opencodex_guard import Guard


def configuration(url, model, key, port, seconds):
    parsed = urlsplit(url)
    if parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError('UPSTREAM_URL_INVALID')
    if not model or any(ord(c) < 32 for c in model) or not key:
        raise ValueError('PROFILE_INVALID')
    return {'port': port, 'hostname': '127.0.0.1', 'defaultProvider': 'rvport',
        'providers': {'rvport': {'adapter': 'openai-chat', 'baseUrl': url.rstrip('/'),
            'apiKey': key, 'models': [model], 'defaultModel': model, 'liveModels': False,
            'allowPrivateNetwork': True, 'proxy': 'direct', 'openaiChatEofTolerance': False}},
        'clientIntegrations': {'codex': False, 'grok': False, 'claude-desktop': False},
        'catalogAutoRefresh': {'enabled': False}, 'oauthOpenBrowser': False,
        'claudeCode': {'intercept': {'enabled': False}}, 'fastRows': False,
        'fallback': {'model': False, 'provider': False}, 'emptyCompletionRetry': False,
        'stallTimeoutSec': max(1, seconds), 'connectTimeoutMs': max(1, seconds * 1000)}


def management(port, path, token, timeout):
    connection = http.client.HTTPConnection('127.0.0.1', port, timeout=timeout)
    try:
        connection.request('GET', path, headers={'Authorization': 'Bearer ' + token})
        response = connection.getresponse()
        if response.status != 200:
            raise OSError('PROXY_READINESS_HTTP')
        data = response.read(1024 * 1024 + 1)
        if len(data) > 1024 * 1024:
            raise ValueError('PROXY_READINESS_LIMIT')
        return json.loads(data)
    finally:
        connection.close()


def verify_ready(health, providers, models, config):
    expected = config['providers']['rvport']
    if health.get('service') != 'opencodex' or health.get('version') != '2.94.0' or health.get('port') != config['port']:
        raise ValueError('PROXY_IDENTITY_MISMATCH')
    if len(providers) != 1:
        raise ValueError('PROXY_PROVIDER_MISMATCH')
    provider = providers[0]
    if (provider.get('name'), provider.get('adapter'), provider.get('baseUrl'), provider.get('defaultModel')) != (
            'rvport', 'openai-chat', expected['baseUrl'], expected['defaultModel']):
        raise ValueError('PROXY_PROFILE_MISMATCH')
    if not provider.get('hasApiKey') or provider.get('models') != expected['models']:
        raise ValueError('PROXY_PROFILE_MISMATCH')
    if {m['id'] for m in models['data']} != {'rvport/' + expected['defaultModel']}:
        raise ValueError('PROXY_MODEL_MISMATCH')


def verify_listeners(pid, port, proc=Path('/proc')):
    pending, owned, inodes = [str(pid)], set(), set()
    while pending:
        current = pending.pop()
        if current in owned:
            continue
        owned.add(current)
        try:
            pending.extend((proc/current/'task'/current/'children').read_text().split())
            for descriptor in (proc/current/'fd').iterdir():
                link = os.readlink(descriptor)
                if link.startswith('socket:['):
                    inodes.add(link[8:-1])
        except FileNotFoundError:
            continue
    listening = set()
    for protocol in ('tcp', 'tcp6'):
        for line in (proc/'net'/protocol).read_text().splitlines()[1:]:
            fields = line.split()
            if fields[3] == '0A' and fields[9] in inodes:
                listening.add(fields[1])
    if listening != {f'0100007F:{port:04X}'}:
        raise ValueError('PROXY_LISTENER_MISMATCH')


def wait_ready(process, config, home, token, deadline, cancelled, pause=time.sleep):
    while time.monotonic() < deadline and not cancelled.is_set():
        if process.poll() is not None or list(home.glob('config.json.invalid-*')):
            raise ValueError('PROXY_START_FAILED')
        remaining = max(.01, min(1, deadline - time.monotonic()))
        try:
            port = config['port']
            health = management(port, '/healthz', token, remaining)
            providers = management(port, '/api/providers', token, remaining)
            models = management(port, '/v1/models', token, remaining)
            verify_ready(health, providers, models, config)
            verify_listeners(process.pid, port)
            return
        except OSError:
            pause(.05)
    raise TimeoutError('PROXY_START_DEADLINE')


def local_command(command, url):
    prefix = 'model_providers.rvport-openai-compatible.base_url='
    result = [prefix + json.dumps(url) if value.startswith(prefix) else value for value in command]
    if not any(value.startswith(prefix) for value in command):
        raise ValueError('CODEX_PROVIDER_CONFIG_MISSING')
    return result


def stop(process):
    if process is None:
        return
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=.5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=.5)


def run(command, env, deadline, cancelled):
    home = Path(env['RVPORT_OCX_HOME'])
    ocx_home = home / 'proxy'
    codex_home = home / 'codex'
    for directory in (ocx_home, codex_home):
        directory.mkdir(mode=0o700)
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1', 0))
        port = reservation.getsockname()[1]
    config = configuration(env['OPENAI_BASE_URL'], env['OPENAI_MODEL'], env['OPENAI_API_KEY'],
                           port, max(1, int(deadline - time.monotonic())))
    config_path = ocx_home / 'config.json'
    descriptor = os.open(config_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w') as file:
        json.dump(config, file)
    admin, token = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
    # 不继承进程中的上游凭据/代理环境；只开放运行时所需变量。
    base = {k: env[k] for k in ('PATH', 'LANG', 'LC_ALL') if k in env}
    proxy_env = {**base, 'HOME': str(ocx_home), 'OPENCODEX_HOME': str(ocx_home),
        'CODEX_HOME': str(ocx_home / 'unused-codex'), 'OCX_DISABLE_UPDATE_CHECK': '1',
        'OPENCODEX_ADMIN_AUTH_TOKEN': admin}
    proxy = codex = guard = None
    try:
        proxy = subprocess.Popen([env['RVPORT_OCX_EXECUTABLE'], 'start'], env=proxy_env,
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        wait_ready(proxy, config, ocx_home, admin, deadline, cancelled)
        guard = Guard(port, env['OPENAI_MODEL'], token, deadline)
        threading.Thread(target=guard.serve_forever, daemon=True).start()
        url = 'http://127.0.0.1:' + str(guard.server_port) + '/v1'
        codex_env = {**base, 'HOME': str(codex_home), 'CODEX_HOME': str(codex_home),
            'OPENAI_API_KEY': token, 'OPENAI_BASE_URL': url, 'OPENAI_MODEL': env['OPENAI_MODEL']}
        codex = subprocess.Popen(local_command(command, url), env=codex_env)
        while time.monotonic() < deadline and not cancelled.is_set():
            if proxy.poll() is not None:
                raise RuntimeError('PROXY_EXITED')
            if codex.poll() is not None:
                return codex.returncode
            cancelled.wait(.02)
        raise TimeoutError('EXECUTION_DEADLINE')
    finally:
        try:
            stop(codex)
        finally:
            try:
                if guard:
                    guard.cancel()
            finally:
                stop(proxy)


def main(argv, env=os.environ):
    cancelled = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: cancelled.set())
    signal.signal(signal.SIGINT, lambda *_: cancelled.set())
    try:
        return run(argv, env, time.monotonic() + float(env['RVPORT_OCX_TIMEOUT_SECONDS']), cancelled)
    except TimeoutError as error:
        print('RVPORT_OCX_READINESS_DEADLINE' if str(error) == 'PROXY_START_DEADLINE'
              else 'RVPORT_OCX_EXECUTION_DEADLINE', file=sys.stderr)
        return 124
    except (OSError, ValueError, RuntimeError, KeyError) as error:
        # 不把第三方输出、上游 URL、配置或密钥带入公共诊断。
        code = str(error)
        if code in ('UPSTREAM_URL_INVALID', 'PROFILE_INVALID', 'CODEX_PROVIDER_CONFIG_MISSING'):
            signal_code = 'RVPORT_OCX_CONFIGURATION_FAILED'
        elif code in ('PROXY_START_FAILED', 'PROXY_IDENTITY_MISMATCH', 'PROXY_PROVIDER_MISMATCH',
                      'PROXY_PROFILE_MISMATCH', 'PROXY_MODEL_MISMATCH', 'PROXY_LISTENER_MISMATCH'):
            signal_code = 'RVPORT_OCX_READINESS_FAILED'
        elif code == 'PROXY_EXITED':
            signal_code = 'RVPORT_OCX_PROXY_EXITED'
        else:
            signal_code = 'RVPORT_OCX_START_FAILED'
        print(signal_code, file=sys.stderr)
        return 126


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
