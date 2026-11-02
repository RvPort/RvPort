"""执行级准入；Responses 字节原样转发，不实现协议转换。"""
import hmac
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import socket
import threading
import time

LIMIT = 2 * 1024 * 1024
PATCH_GRAMMAR = Path(__file__).with_name('apply_patch.lark').read_text()
FIELDS = {'model', 'input', 'instructions', 'tools', 'tool_choice', 'parallel_tool_calls', 'stream',
          'store', 'include', 'reasoning', 'text', 'prompt_cache_key', 'metadata', 'client_metadata',
          'max_output_tokens', 'temperature', 'top_p', 'service_tier'}


def text_content(content):
    if isinstance(content, str):
        return
    if not isinstance(content, list) or any(not isinstance(c, dict)
            or c.get('type') not in ('input_text', 'output_text')
            or not isinstance(c.get('text'), str) for c in content):
        raise ValueError('CONTENT_UNSUPPORTED')


def validate_request(value, model):
    if not isinstance(value, dict) or value.get('model') != model:
        raise ValueError('MODEL_NOT_ALLOWED')
    if set(value) - FIELDS:
        raise ValueError('CAPABILITY_UNSUPPORTED')
    if value.get('instructions') is not None and not isinstance(value['instructions'], str):
        raise ValueError('INSTRUCTIONS_INVALID')
    if value.get('service_tier', 'auto') != 'auto':
        raise ValueError('SERVICE_TIER_UNSUPPORTED')
    if value.get('store') or value.get('text', {}) not in ({}, {'format': {'type': 'text'}}):
        raise ValueError('CAPABILITY_UNSUPPORTED')
    if value.get('reasoning', {}) not in (None, {}, {'effort': 'none'}):
        raise ValueError('REASONING_UNSUPPORTED')
    if value.get('include', []) not in ([], ['reasoning.encrypted_content']):
        raise ValueError('INCLUDE_UNSUPPORTED')
    tools = value.get('tools', [])
    if not isinstance(tools, list):
        raise ValueError('TOOLS_INVALID')
    names = {}
    for tool in tools:
        if not isinstance(tool, dict) or tool.get('type') not in ('function', 'custom'):
            raise ValueError('TOOL_UNSUPPORTED')
        if not isinstance(tool.get('name'), str) or not tool['name']:
            raise ValueError('TOOL_INVALID')
        if tool['name'] in names:
            raise ValueError('TOOL_DUPLICATE')
        names[tool['name']] = tool['type']
        if tool['type'] == 'function' and not isinstance(tool.get('parameters'), dict):
            raise ValueError('TOOL_SCHEMA_INVALID')
        if tool['type'] == 'custom' and tool['name'] != 'apply_patch':
            raise ValueError('CUSTOM_TOOL_UNSUPPORTED')
        if tool['type'] == 'custom' and tool.get('format', {}) not in ({}, {'type': 'text'},
                {'type': 'grammar', 'syntax': 'lark', 'definition': PATCH_GRAMMAR}):
            raise ValueError('GRAMMAR_UNSUPPORTED')
    choice = value.get('tool_choice', 'auto')
    if isinstance(choice, str):
        if choice not in ('auto', 'none', 'required'):
            raise ValueError('TOOL_CHOICE_INVALID')
    elif not isinstance(choice, dict) or choice.get('type') not in ('function', 'custom') \
            or names.get(choice.get('name')) != choice['type']:
        raise ValueError('TOOL_CHOICE_INVALID')
    items = value.get('input')
    if isinstance(items, str):
        return
    if not isinstance(items, list):
        raise ValueError('INPUT_INVALID')
    calls, outputs = {}, set()
    for item in items:
        if not isinstance(item, dict):
            raise ValueError('INPUT_INVALID')
        if item.get('encrypted_content'):
            raise ValueError('ENCRYPTED_INPUT_UNSUPPORTED')
        kind = item.get('type', 'message')
        if kind in ('function_call', 'custom_tool_call'):
            call_id = item.get('call_id')
            if not isinstance(call_id, str) or not call_id or call_id in calls:
                raise ValueError('TOOL_REFERENCE_INVALID')
            calls[call_id] = kind
            expected = 'function' if kind == 'function_call' else 'custom'
            if names.get(item.get('name')) != expected:
                raise ValueError('TOOL_REFERENCE_INVALID')
            if kind == 'function_call':
                if not isinstance(json.loads(item.get('arguments', '')), dict):
                    raise ValueError('TOOL_ARGUMENT_INVALID')
            elif not isinstance(item.get('input'), str):
                raise ValueError('TOOL_ARGUMENT_INVALID')
        elif kind in ('function_call_output', 'custom_tool_call_output'):
            call_id = item.get('call_id')
            if calls.get(call_id) != kind.removesuffix('_output') or call_id in outputs:
                raise ValueError('TOOL_REFERENCE_INVALID')
            outputs.add(call_id)
            text_content(item.get('output'))
        elif kind == 'message':
            if calls.keys() != outputs:
                raise ValueError('TOOL_RESULT_MISSING')
            if item.get('role') not in ('system', 'developer', 'user', 'assistant'):
                raise ValueError('ROLE_INVALID')
            text_content(item.get('content'))
        else:
            raise ValueError('INPUT_TYPE_UNSUPPORTED')
    if calls.keys() != outputs:
        raise ValueError('TOOL_RESULT_MISSING')


class Guard(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, proxy_port, model, token, deadline):
        self.proxy_port, self.model, self.token, self.deadline = proxy_port, model, token, deadline
        self.active = set()
        self.lock = threading.Lock()
        super().__init__(('127.0.0.1', 0), Handler)

    def track(self, connection, add):
        with self.lock:
            if add:
                self.active.add(connection)
            else:
                self.active.discard(connection)

    def cancel(self):
        with self.lock:
            for connection in self.active:
                try:
                    connection.shutdown(socket.SHUT_RDWR)
                    connection.close()
                except OSError:
                    pass
        self.shutdown()
        self.server_close()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def reject(self, status, code):
        data = json.dumps({'error': {'code': code, 'message': 'Agent request rejected',
                                    'message_zh': 'Agent 请求被拒绝'}}).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self.reject(404, 'ROUTE_NOT_ALLOWED')

    def do_POST(self):
        upstream = None
        tracked = None
        sent = False
        self.server.track(self.connection, True)
        try:
            remaining = self.server.deadline - time.monotonic()
            if remaining <= 0:
                return self.reject(504, 'EXECUTION_DEADLINE')
            self.connection.settimeout(remaining)
            auth = self.headers.get_all('Authorization', [])
            if len(auth) != 1 or not hmac.compare_digest(auth[0], 'Bearer ' + self.server.token):
                return self.reject(401, 'UNAUTHORIZED')
            host = '127.0.0.1:' + str(self.server.server_port)
            if self.path != '/v1/responses' or self.headers.get_all('Host', []) != [host]:
                return self.reject(404, 'ROUTE_NOT_ALLOWED')
            lengths = self.headers.get_all('Content-Length', [])
            if len(lengths) != 1 or self.headers.get('Transfer-Encoding'):
                return self.reject(400, 'BODY_INVALID')
            length = int(lengths[0])
            if length < 0 or length > LIMIT:
                return self.reject(413, 'BODY_LIMIT')
            raw = self.rfile.read(length)
            if len(raw) != length:
                return self.reject(400, 'BODY_INCOMPLETE')
            validate_request(json.loads(raw), self.server.model)
            remaining = self.server.deadline - time.monotonic()
            if remaining <= 0:
                return self.reject(504, 'EXECUTION_DEADLINE')
            upstream = http.client.HTTPConnection('127.0.0.1', self.server.proxy_port, timeout=remaining)
            upstream.connect()
            tracked = upstream.sock
            self.server.track(tracked, True)
            upstream.request('POST', '/v1/responses', raw, {'Content-Type': 'application/json'})
            response = upstream.getresponse()
            # 不跟随重定向，也不把管理 token 或客户端认证转交第三方。
            if 300 <= response.status < 400:
                return self.reject(502, 'REDIRECT_REJECTED')
            self.send_response(response.status)
            self.send_header('Content-Type', response.getheader('Content-Type', 'application/json'))
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            sent = True
            while time.monotonic() < self.server.deadline:
                chunk = response.read1(8192)
                if not chunk:
                    break
                self.wfile.write(chunk)
                self.wfile.flush()
        except (ValueError, TypeError):
            if not sent:
                self.reject(400, 'REQUEST_INVALID')
        except (OSError, http.client.HTTPException):
            if not sent:
                try:
                    self.reject(502, 'PROXY_IO_ERROR')
                except OSError:
                    pass
        finally:
            if upstream:
                if tracked:
                    self.server.track(tracked, False)
                upstream.close()
            self.server.track(self.connection, False)
            self.close_connection = True
