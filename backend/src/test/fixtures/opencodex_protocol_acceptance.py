"""真实固定 OpenCodex 协议门；仅在本地隔离 Docker 中使用模拟上游。"""
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import threading
import time

sys.path.insert(0, str(Path(__file__).parents[2] / 'main/resources/agentruntime'))
from opencodex_guard import Guard
from opencodex_runtime import configuration, wait_ready


def event(delta, finish=None, usage=None):
    body = {'id':'fixture', 'model':'fixture-model', 'choices':[{'index':0,'delta':delta,'finish_reason':finish}]}
    if usage is not None:
        body['usage'] = usage
    return ('data: '+json.dumps(body, ensure_ascii=False)+'\n\n').encode()


CASES = {
    'normal': (event({'content':'中文'})+event({},'stop',{'prompt_tokens':7,'completion_tokens':3,'total_tokens':10})+b'data: [DONE]\n\n','completed'),
    'missing-terminal': (event({'content':'partial'}),'failed'),
    'normal-without-usage': (event({'content':'normal'})+event({},'stop')+b'data: [DONE]\n\n','completed'),
    'unknown-finish-reason': (event({'content':'partial'})+event({},'unexpected_reason')+b'data: [DONE]\n\n','failed'),
    'length-truncation': (event({'content':'partial'},'length')+b'data: [DONE]\n\n','incomplete'),
    'content-filter': (event({'content':'partial'},'content_filter')+b'data: [DONE]\n\n','incomplete'),
    'malformed-before-terminal': (event({'content':'partial'})+b'data: {broken\n\n'+event({},'stop')+b'data: [DONE]\n\n','failed'),
    'invalid-content-type': (event({'content':42})+event({},'stop')+b'data: [DONE]\n\n','failed'),
    'invalid-tool-arguments': (event({'tool_calls':[{'index':0,'id':'c1','type':'function','function':{'name':'read','arguments':'{broken'}}]})+event({},'tool_calls')+b'data: [DONE]\n\n','failed'),
    'unknown-tool': (event({'tool_calls':[{'index':0,'id':'c1','type':'function','function':{'name':'not_declared','arguments':'{}'}}]})+event({},'tool_calls')+b'data: [DONE]\n\n','failed'),
    'multiple-tools': (event({'tool_calls':[{'index':0,'id':'a','type':'function','function':{'name':'read','arguments':'{"file":"'}},
        {'index':1,'id':'b','type':'function','function':{'name':'read','arguments':'{"file":'}}]})+
        event({'tool_calls':[{'index':1,'function':{'arguments':'"b"}'}},{'index':0,'function':{'arguments':'中文"}'}}]},'tool_calls')+b'data: [DONE]\n\n','completed'),
}


def main(executable, destination):
    destination=Path(destination); destination.mkdir(parents=True,exist_ok=True)
    class Upstream(BaseHTTPRequestHandler):
        def log_message(self,*_): pass
        def do_POST(self):
            self.rfile.read(int(self.headers['Content-Length']))
            self.server.calls += 1
            if self.server.redirect:
                self.send_response(302); self.send_header('Location', self.server.redirect); self.end_headers(); return
            self.send_response(200); self.send_header('Content-Type','text/event-stream'); self.end_headers()
            # 故意逐字节输出，覆盖 UTF-8 字符边界。
            for byte in self.server.body:
                self.wfile.write(bytes([byte]))
            self.wfile.flush()
    upstream=ThreadingHTTPServer(('127.0.0.1',0),Upstream)
    upstream.calls=0; upstream.redirect=None
    threading.Thread(target=upstream.serve_forever,daemon=True).start()
    rows=[]
    with tempfile.TemporaryDirectory(prefix='rvport-protocol-') as directory:
        home=Path(directory); home.chmod(0o700)
        import socket
        with socket.socket() as reserved:
            reserved.bind(('127.0.0.1',0)); port=reserved.getsockname()[1]
        config=configuration(f'http://127.0.0.1:{upstream.server_port}/v1','fixture-model','fake-key',port,3)
        (home/'config.json').write_text(json.dumps(config)); (home/'config.json').chmod(0o600)
        env={**os.environ,'OPENCODEX_HOME':str(home),'HOME':str(home),'CODEX_HOME':str(home/'codex'),
             'OPENCODEX_ADMIN_AUTH_TOKEN':'fake-admin','OCX_DISABLE_UPDATE_CHECK':'1'}
        proxy=subprocess.Popen([executable,'start'],env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,start_new_session=True)
        guard=None
        try:
            wait_ready(proxy,config,home,'fake-admin',time.monotonic()+10,threading.Event())
            guard=Guard(port,'fixture-model','token',time.monotonic()+60)
            threading.Thread(target=guard.serve_forever,daemon=True).start()
            for name,(body,expected) in CASES.items():
                upstream.body=body
                request={'model':'fixture-model','input':'hello','stream':True,
                         'tools':[{'type':'function','name':'read','parameters':{'type':'object','properties':{'file':{'type':'string'}}}}]}
                connection=http.client.HTTPConnection('127.0.0.1',guard.server_port,timeout=5)
                connection.request('POST','/v1/responses',json.dumps(request),{'Authorization':'Bearer token','Content-Type':'application/json'})
                response=connection.getresponse(); raw=response.read().decode(); connection.close()
                (destination/(name+'.sse')).write_text(raw)
                events=[json.loads(line[6:]) for line in raw.splitlines() if line.startswith('data: {')]
                terminals=[item for item in events if item.get('type') in ('response.completed','response.failed','response.incomplete')]
                actual=terminals[-1]['type'].removeprefix('response.') if terminals else 'no-terminal'
                row={'case':name,'expected':expected,'actual':actual,'passed':actual==expected}
                if name=='normal' and actual=='completed':
                    usage=terminals[-1]['response']['usage']; row['usage']=usage
                    row['passed'] &= usage['input_tokens']==7 and usage['output_tokens']==3
                if name=='normal-without-usage' and actual=='completed':
                    usage=terminals[-1]['response'].get('usage'); row['usage']=usage
                    row['passed'] &= usage is None
                if name=='multiple-tools' and actual=='completed':
                    output=terminals[-1]['response']['output']
                    row['passed'] &= {item['call_id']:json.loads(item['arguments']) for item in output if item['type']=='function_call'}=={'a':{'file':'中文'},'b':{'file':'b'}}
                rows.append(row); print(json.dumps(row,ensure_ascii=False),flush=True)
            before=upstream.calls
            for name,path,token,changes,status in [
                ('wrong-token','/v1/responses','other',{},401),
                ('wrong-model','/v1/responses','token',{'model':'unknown'},400),
                ('management-path','/api/providers','token',{},404),
                ('unsupported-verbosity','/v1/responses','token',{'text':{'verbosity':'low'}},400),
                ('orphan-tool-output','/v1/responses','token',{'input':[{'type':'function_call_output','call_id':'unknown','output':'x'}]},400),
            ]:
                connection=http.client.HTTPConnection('127.0.0.1',guard.server_port,timeout=5)
                connection.request('POST',path,json.dumps({**request,**changes}),{'Authorization':'Bearer '+token,'Content-Type':'application/json'})
                response=connection.getresponse(); response.read(); connection.close()
                row={'case':name,'status':response.status,'passed':response.status==status and upstream.calls==before}
                rows.append(row); print(json.dumps(row),flush=True)
            deadline=guard.deadline
            guard.deadline=time.monotonic()-1
            connection=http.client.HTTPConnection('127.0.0.1',guard.server_port,timeout=5)
            connection.request('POST','/v1/responses',json.dumps(request),{'Authorization':'Bearer token','Content-Type':'application/json'})
            response=connection.getresponse(); response.read(); connection.close()
            rows.append({'case':'expired-token-budget','status':response.status,'passed':response.status==504 and upstream.calls==before})
            guard.deadline=deadline
            class Sink(BaseHTTPRequestHandler):
                def log_message(self,*_): pass
                def do_GET(self):
                    self.server.calls+=1; self.send_response(200); self.end_headers()
                do_POST=do_GET
            sink=ThreadingHTTPServer(('127.0.0.1',0),Sink); sink.calls=0
            threading.Thread(target=sink.serve_forever,daemon=True).start()
            try:
                upstream.redirect=f'http://127.0.0.1:{sink.server_port}/capture'
                connection=http.client.HTTPConnection('127.0.0.1',guard.server_port,timeout=5)
                connection.request('POST','/v1/responses',json.dumps(request),{'Authorization':'Bearer token','Content-Type':'application/json'})
                response=connection.getresponse(); data=response.read().decode(); connection.close()
                row={'case':'upstream-cross-origin-redirect','status':response.status,'redirect_target_calls':sink.calls,
                     'passed':sink.calls==0 and (response.status>=400 or 'response.failed' in data)}
                rows.append(row); print(json.dumps(row),flush=True)
            finally:
                upstream.redirect=None; sink.shutdown(); sink.server_close()
        finally:
            if guard: guard.cancel()
            os.killpg(proxy.pid,signal.SIGTERM)
            try: proxy.wait(timeout=2)
            except subprocess.TimeoutExpired:
                os.killpg(proxy.pid,signal.SIGKILL); proxy.wait(timeout=2)
            upstream.shutdown(); upstream.server_close()
    (destination/'results.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2))
    return 0 if all(row['passed'] for row in rows) else 1


if __name__=='__main__':
    raise SystemExit(main(*sys.argv[1:]))
