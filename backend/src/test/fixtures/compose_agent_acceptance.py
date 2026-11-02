"""本地隔离 Compose API 验收；只连接本容器与专用模拟上游。"""
import http.cookiejar, json, time, urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
BASE='http://127.0.0.1:8080'
class Client:
    def __init__(self):
        self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf={}
    def call(self, method, path, payload=None, expected=200):
        request=urllib.request.Request(BASE+path, data=None if payload is None else json.dumps(payload).encode(),
            headers={'Content-Type':'application/json', **self.csrf}, method=method)
        try: response=self.opener.open(request, timeout=90)
        except urllib.error.HTTPError as error: response=error
        body=json.load(response)
        assert response.status==expected, (path,response.status,body)
        return body.get('data')
    def refresh(self):
        token=self.call('GET','/api/auth/csrf'); self.csrf={token['headerName']:token['token']}
    def login(self,user,password):
        self.refresh(); self.call('POST','/api/auth/login',{'username':user,'password':password}); self.refresh()
client=Client()
try:
    client.login('admin','admin123')
except AssertionError:
    client.login('admin','AgentTest123!')
else:
    client.call('POST','/api/auth/change-password',{'currentPassword':'admin123','newPassword':'AgentTest123!'})
    client.login('admin','AgentTest123!')
suffix=str(time.time_ns())
for _ in range(60):
    workers=client.call('GET','/api/admin/workers')
    if any(item['instanceId']=='ocx-final-worker' and item['status']=='ONLINE' for item in workers['items']): break
    time.sleep(.5)
else: raise AssertionError(workers)
runtimes=client.call('GET','/api/admin/agent-runtimes')
if isinstance(runtimes,dict): runtimes=runtimes['items']
runtime_ids={item['agentType']:item['id'] for item in runtimes}
assert set(runtime_ids)=={'CODEX','DEEPSEEK_HARNESS','OPENCODE'}
profiles={}
for wire in ('RESPONSES','CHAT'):
    profiles[wire]=client.call('POST','/api/admin/model-profiles',{'name':'local-'+wire+'-'+suffix,'enabled':True,
        'modelProviderType':'OPENAI_COMPATIBLE','baseUrl':'http://agent-mock:8089/v1', 'model':'fixture-model',
        'wireApi':wire,'apiKey':'local-dummy-key','timeoutSeconds':30,'maxRuntimeSeconds':60})['id']
    client.call('PATCH','/api/admin/model-profiles/'+profiles[wire],{'name':'local-'+wire+'-updated-'+suffix})
    result=client.call('POST','/api/admin/model-profiles/'+profiles[wire]+':test-connectivity',{})
    assert result['success'] and result['details']['wireApi']==wire, result

def start(profile, agents):
    return client.call('POST','/api/admin/model-profiles/'+profile+':test-agents',{'agentRuntimeIds':[runtime_ids[x] for x in agents]})['id']
def wait(run):
    deadline=time.monotonic()+240
    while time.monotonic()<deadline:
        result=client.call('GET','/api/admin/model-profile-agent-test-runs/'+run)
        if result['finishedAt']: return result
        time.sleep(.3)
    raise AssertionError('Probe run did not finish')
normal={}
for wire,agents in [('RESPONSES',['CODEX']),('CHAT',list(runtime_ids))]:
    normal[wire]=wait(start(profiles[wire],agents))
    assert len(normal[wire]['results'])==len(agents),normal[wire]
    assert {result['agentType'] for result in normal[wire]['results']}==set(agents),normal[wire]
    assert all(result['agentCompatible'] and result['basicAvailable'] for result in normal[wire]['results']),normal[wire]
    for result in normal[wire]['results']:
        assert len(result['checks'])==3
        assert all(check['details']['cleanup']=='COMPLETE' for check in result['checks'])
        assert all('message_en' in check for check in result['checks'])
        if result['agentType']=='CODEX':
            assert all(check['details']['route']==('OPENCODEX' if wire=='CHAT' else 'NATIVE_RESPONSES') for check in result['checks'])
client.call('PATCH','/api/admin/model-stage-assignments',{'assignments':[
    {'stage':stage,'modelProfileId':profiles['CHAT'],'agentRuntimeId':runtime_ids['CODEX']}
    for stage in ('KB_AI_EXTENSION','TEST_LOG_COMPARE')]})
assignments=client.call('GET','/api/admin/model-stage-assignments')
selected_assignments={item['stage']:item['modelProfileId'] for item in assignments if item['stage'] in ('KB_AI_EXTENSION','TEST_LOG_COMPARE')}
assert selected_assignments==dict.fromkeys(('KB_AI_EXTENSION','TEST_LOG_COMPARE'),profiles['CHAT']),assignments
second=client.call('POST','/api/admin/model-profiles',{'name':'local-two-'+suffix,'enabled':True,'modelProviderType':'OPENAI_COMPATIBLE',
    'baseUrl':'http://agent-mock:8089/two/v1','model':'fixture-model','wireApi':'CHAT','apiKey':'local-dummy-key-two',
    'timeoutSeconds':30,'maxRuntimeSeconds':60})['id']
run_a=start(profiles['CHAT'],['CODEX']); run_b=start(second,['CODEX'])
with ThreadPoolExecutor(2) as pool: concurrent=list(pool.map(wait,[run_a,run_b]))
assert all(run['results'][0]['agentCompatible'] for run in concurrent),concurrent
client.call('PATCH','/api/admin/model-profiles/'+second,{'baseUrl':'http://agent-mock:8089/failure/slow','timeoutSeconds':1})
failed=wait(start(second,['CODEX']))
assert not failed['results'][0]['agentCompatible'],failed
assert (any(check['errorCode']=='AGENT_PROBE_TIMEOUT' for check in failed['results'][0]['checks'])
        or failed['results'][0]['details'].get('errorCode') in
        ('AGENT_PROBE_WORKER_TIMEOUT','AGENT_PROBE_WORKER_TIMED_OUT','AGENT_PROBE_WORKER_CANCELLED')),failed
for check in failed['results'][0]['checks']:
    assert check['details']['cleanup']=='COMPLETE',check
    assert check['durationMs']<4000,check
assert 'local-dummy-key' not in json.dumps(failed)
client.call('PATCH','/api/admin/model-profiles/'+second,{'baseUrl':'http://agent-mock:8089/two/v1','timeoutSeconds':30})
recovered=wait(start(second,['CODEX']))
assert recovered['results'][0]['agentCompatible'],recovered
assert client.call('GET','/api/admin/model-profile-agent-test-runs/'+failed['id'])==failed
assert client.call('GET','/api/admin/model-profile-agent-test-runs/'+normal['CHAT']['id'])==normal['CHAT']
legacy=client.call('POST','/api/admin/model-profiles/'+profiles['CHAT']+':test',
    {'agentRuntimeId':runtime_ids['CODEX'], 'baseUrl':'http://agent-mock:8089/two/v1','apiKey':'local-dummy-key-two'})
assert legacy['success'] and len(legacy['checks'])==3,legacy
assert client.call('GET','/api/admin/model-profiles/'+profiles['CHAT'])['baseUrl']=='http://agent-mock:8089/v1'
viewer_name='local-viewer-'+suffix
user=client.call('POST','/api/admin/users',{'username':viewer_name,'email':viewer_name+'@example.invalid','mustChangePassword':False})
client.call('POST','/api/admin/users/'+user['id']+'/reset-password',{'newPassword':'ViewerTest123!','mustChangePassword':False})
viewer=Client(); viewer.login(viewer_name,'ViewerTest123!')
viewer.call('GET','/api/admin/model-profiles',expected=403)
viewer.call('POST','/api/admin/model-profiles/'+profiles['CHAT']+':test-agents',{'agentRuntimeIds':[runtime_ids['CODEX']]},expected=403)
assert not list(Path('/tmp').glob('rvport-codex-provider-test-*'))
assert not list(Path('/tmp').glob('rvport-agent-supervisor-*.py'))
assert not Path('/root/.codex/auth.json').exists()
for comm in Path('/proc').glob('[0-9]*/comm'):
    try: name=comm.read_text().strip()
    except FileNotFoundError: continue
    assert name not in ('node','codex','opencode','dsh'),name
for line in Path('/proc/net/tcp').read_text().splitlines()[1:]:
    fields=line.split()
    assert not (fields[1].startswith('0100007F:') and fields[3]=='0A'), 'Residual loopback listener'
report={'normal':normal,'concurrent':concurrent,'failure':failed,'recovered':recovered,'legacy':legacy,
        'profiles':profiles,'runtime_ids':runtime_ids,'second':second,'assignments':assignments,'permissions':'PASS'}
Path('/tmp/agent-api-acceptance.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
print('API_ACCEPTANCE_PASSED: profiles/connectivity/three-agents/history/stage-bindings/concurrency/timeout/permissions')
