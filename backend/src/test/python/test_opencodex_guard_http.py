"""准入 HTTP 单元测试：本地内存夹具，断言拒绝请求不会到达上游。"""
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import socket
import sys
import threading
import time
from unittest.mock import Mock
import pytest

sys.path.insert(0, str(Path(__file__).parents[2] / 'main/resources/agentruntime'))
import opencodex_guard as module


@pytest.fixture
def servers():
    seen = []
    class Upstream(BaseHTTPRequestHandler):
        def log_message(self, *_): pass
        def do_POST(self):
            data = self.rfile.read(int(self.headers['Content-Length']))
            seen.append((self.path, self.headers, data))
            self.send_response(self.server.code)
            self.send_header('Content-Type', 'text/event-stream')
            self.end_headers()
            self.wfile.write(b'data: unchanged\n\n')
    upstream = ThreadingHTTPServer(('127.0.0.1', 0), Upstream)
    upstream.code = 200
    gate = module.Guard(upstream.server_port, 'model', 'token', time.monotonic()+10)
    for server in (upstream, gate):
        threading.Thread(target=server.serve_forever, kwargs={'poll_interval': .01}, daemon=True).start()
    try:
        yield gate, upstream, seen
    finally:
        gate.cancel()
        upstream.shutdown()
        upstream.server_close()


def request(gate, body=b'{"model":"model","input":"hello"}', path='/v1/responses', headers=None, method='POST'):
    client = http.client.HTTPConnection('127.0.0.1', gate.server_port, timeout=2)
    try:
        client.request(method, path, body, {'Authorization':'Bearer token', **(headers or {})})
        response = client.getresponse()
        return response.status, response.read(), response.headers
    finally:
        client.close()


def test_passthrough_bytes_and_no_credentials(servers):
    gate, upstream, seen = servers
    raw = b'{ "model" : "model", "input" : "hello" }'
    code, data, headers = request(gate, raw)
    assert code == 200 and data == b'data: unchanged\n\n'
    assert headers['Cache-Control'] == 'no-store'
    assert seen[0][0] == '/v1/responses' and seen[0][2] == raw
    assert seen[0][1].get('Authorization') is None
    assert not gate.active


@pytest.mark.parametrize('kwargs,code', [
    ({'headers':{'Authorization':'Bearer wrong'}},401),
    ({'headers':{'Host':'other'}},404), ({'path':'/api/providers'},404),
    ({'path':'/v1/responses?forward=http://other'},404), ({'method':'GET'},404),
    ({'headers':{'Content-Length':'-1'}},413),
    ({'headers':{'Content-Length':str(module.LIMIT+1)}},413),
    ({'headers':{'Content-Length':'invalid'}},400),
    ({'headers':{'Transfer-Encoding':'chunked'}},400),
    ({'body':b'invalid'},400), ({'body':b'{"model":"other","input":"hello"}'},400),
])
def test_reject_before_upstream(servers, kwargs, code):
    gate, upstream, seen = servers
    assert request(gate, **kwargs)[0] == code
    assert not seen


def test_deadline_and_redirect(servers):
    gate, upstream, seen = servers
    upstream.code = 307
    assert request(gate)[0] == 502
    assert len(seen) == 1
    gate.deadline = time.monotonic()-1
    assert request(gate)[0] == 504
    assert len(seen) == 1


def test_missing_duplicate_headers_and_incomplete_body(servers):
    gate, upstream, seen = servers
    host = f'Host: 127.0.0.1:{gate.server_port}\r\n'
    for headers, body in [('', ''), ('Content-Length: 10\r\n','x'),
            ('Content-Length: 1\r\nContent-Length: 1\r\n','x'),
            ('Authorization: Bearer token\r\nContent-Length: 0\r\n','')]:
        with socket.create_connection(('127.0.0.1', gate.server_port), timeout=2) as client:
            client.sendall(('POST /v1/responses HTTP/1.0\r\n'+host+
                'Authorization: Bearer token\r\n'+headers+'\r\n'+body).encode())
            client.shutdown(socket.SHUT_WR)
            assert client.recv(1024).startswith((b'HTTP/1.0 400', b'HTTP/1.0 401'))
    assert not seen


def test_upstream_failure_and_cancel_socket_errors(servers):
    gate, upstream, seen = servers
    with socket.socket() as unused:
        unused.bind(('127.0.0.1',0))
        gate.proxy_port = unused.getsockname()[1]
        assert request(gate)[0] == 502
    broken = Mock()
    broken.shutdown.side_effect = OSError('closed')
    gate.track(broken, True)
    gate.cancel()
    broken.shutdown.assert_called_once()
    gate.track(broken, False)
    assert not seen


def test_budget_expires_during_validation(servers, monkeypatch):
    gate, upstream, seen = servers
    monkeypatch.setattr(module, 'validate_request', lambda *_: setattr(gate, 'deadline', time.monotonic()-1))
    assert request(gate)[0] == 504
    assert not seen
