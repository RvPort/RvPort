"""当前源码/架构和失败构建上下文；只使用本轮独立项目。"""
import runpy,json,uuid,urllib.request
from pathlib import Path
m=runpy.run_path(str(Path(__file__).with_name('compose-acceptance.py')));a=m['Client']('admin');state=json.loads(m['STATE'].read_text())
a.request('/api/auth/login',{'username':'admin','password':state['password']})
project=a.request('/api/projects',{'name':'optional-context','sourceArch':'x86','targetArch':'riscv64'})['id']
a.archive(project,{'main.c':'double value(double x){return x+1;}\n'})
task=a.request(f'/api/projects/{project}/floating-point-analyses',{});assert a.task(task['id'])['status']=='SUCCEEDED'
before=a.request(f'/api/projects/{project}/floating-point-analyses/latest')['currentResult']['id']
assert a.request(f'/api/projects/{project}/reports/evaluation/readiness')['canGenerateStandard']
a.request('/api/projects/'+project,{'sourceArch':'arm64'},method='PATCH')
latest=a.request(f'/api/projects/{project}/floating-point-analyses/latest')
assert latest['currentResult'] is None and latest['freshness']=='STALE',latest
assert 'ANALYSIS_PROFILE_CHANGED' in latest['result']['staleReasons']
assert not a.request(f'/api/projects/{project}/reports/evaluation/readiness')['canGenerateStandard']
overview=a.request(f'/api/projects/{project}/overview')
stages=overview['workflow']['stages']
assert next(s for s in stages if s['id']=='floating-point-analysis')['status']=='PENDING'
a.request('/api/projects/'+project,{'sourceArch':'amd64'},method='PATCH')
assert a.request(f'/api/projects/{project}/floating-point-analyses/latest')['currentResult']['id']==before
# 持久 SSE 游标重连：仅读取已完成任务的事件，不等待无限流。
def events(cursor,count):
    request=urllib.request.Request(m['BASE']+f'/api/projects/{project}/task-events',headers={'Last-Event-ID':str(cursor)})
    found=[]
    with a.http.open(request,timeout=10) as response:
        for line in response:
            if line.startswith(b'id:'):
                found.append(int(line.split(b':',1)[1]))
                if len(found)==count:break
    return found
first=events(0,2);second=events(first[0],1)
assert first==sorted(set(first)) and second==[first[1]],(first,second)
# 最新失败 SOURCE_ONLY 不回退旧成功构建，源码入口仍可运行。
build_project=state['build']['projectId']
response=a.request(f'/api/projects/{build_project}/builds',{'buildScope':'SOURCE_ONLY','script':'exit 7','timeoutSeconds':30})
for build in response['builds']:assert a.task(build['taskId'])['status']=='FAILED'
for stage in ('kb','floating-point-analysis','memory-order-analysis'):
    assert all(p['status']=='SATISFIED' for p in a.request(f'/api/projects/{build_project}/readiness?stageId={stage}')['prerequisites'] if p['required'])
assert any(p['required'] and p['status']!='SATISFIED' for p in a.request(f'/api/projects/{build_project}/readiness?stageId=rv-dependency-analysis')['prerequisites'])
for kind in ('kb-scans','floating-point-analyses','memory-order-analyses'):
    task=a.request(f'/api/projects/{build_project}/{kind}',{});assert a.task(task['id'])['status']=='SUCCEEDED'
    assert a.request(f'/api/projects/{build_project}/{kind}/latest')['currentResult']['inputOrigin']=='SOURCE_DISCOVERY'
Path('/tmp/optional-context-checks.json').write_text(json.dumps({'profileInvalidationAndAlias':'PASS','sseResume':'PASS','failedBuildSourceFallback':'PASS'},indent=2))
print('CONTEXT_AND_SSE_PASS',flush=True)
