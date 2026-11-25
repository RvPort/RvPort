"""本轮独立 Compose 的报告与权限验收；不输出会话、口令和模型密钥。"""
import runpy, json, uuid, secrets
from pathlib import Path
m=runpy.run_path(str(Path(__file__).with_name('compose-acceptance.py')))
Client=m['Client']; state=json.loads(m['STATE'].read_text()); admin=Client('admin')
admin.request('/api/auth/login',{'username':'admin','password':state['password']})
results=json.loads(m['OUT'].read_text())
checks={}
def key():return {'Idempotency-Key':str(uuid.uuid4())}
def generate(client,project):
    ready=client.request(f'/api/projects/{project}/reports/evaluation/readiness')
    body={'sourceRevisionId':ready['sourceRevisionId'],'mode':'STANDARD','selectionToken':ready['selectionToken'],
          'baseReportId':ready.get('regenerationBaseReportId'),'expectedDraftVersion':ready.get('regenerationBaseDraftVersion')}
    headers=key(); task=client.request(f'/api/projects/{project}/reports/evaluation',body,headers=headers)
    replay=client.request(f'/api/projects/{project}/reports/evaluation',body,headers=headers)
    assert replay['id']==task['id'] and replay['reportId']==task['reportId']
    assert client.task(task['id'])['status']=='SUCCEEDED'
    return task['reportId']

# 零候选 PARTIAL 可确认空清单，下载必须保留执行缺口。
project=results['partial']['projectId'];report=results['partial']['reportId']
items=admin.request(f'/api/reports/{report}/review-items')
assert items['total']==0
headers=key(); body={'expectedVersion':items['draftVersion']}
confirmation=admin.request(f'/api/reports/{report}/fix-list-confirmations',body,headers=headers)
assert confirmation['confirmationState']=='CONFIRMED_EMPTY'
assert admin.request(f'/api/reports/{report}/fix-list-confirmations',body,headers=headers)['confirmationId']==confirmation['confirmationId']
download=f'/api/reports/{report}/download?format=json'
original=admin.request(download)
confirmed=admin.request(download+'&confirmationId='+confirmation['confirmationId'])
assert original['schemaVersion']=='report-review.v2'
assert confirmed['schemaVersion']=='confirmed-fix-list.v2'
source=next(s for s in original['sources'] if s['type']=='FLOATING_POINT_FINDING')
assert source['analysisComplete'] is False
assert source['coverage']['failedTuCount']==1 and source['coverage']['analyzedTuCount']==1
assert original['sources']==confirmed['sources']
new_report=generate(admin,project)
assert admin.request(download)==original
assert admin.request(f'/api/reports/{report}/review-items')['writable'] is False
admin.request(f'/api/reports/{report}/fix-list-confirmations',body,headers=key(),expected=409)
checks['partialEmptyConfirmationAndHistory']='PASS'

# 同版本框选人工条目、并发版本、重新生成继承、跨源码失效。
files=admin.request(f'/api/reports/{new_report}/source-files?keyword=main.c')['items']
file=next(f for f in files if f['path']=='main.c')
context=admin.request(f'/api/reports/{new_report}/source-files/'+file['fileKey']+'/context')
version=admin.request(f'/api/reports/{new_report}/review-items')['draftVersion']
manual={'expectedVersion':version,'fileKey':file['fileKey'],'sourceContentHash':context['contentHash'],
        'range':{'startLine':1,'startColumn':1,'endLine':1,'endColumn':7},
        'title':'人工检查','titleEn':'Manual review','description':'核对数值范围','descriptionEn':'Check numeric range',
        'repairReason':'验收人工补充','repairReasonEn':'Acceptance manual addition'}
added=admin.request(f'/api/reports/{new_report}/review-items',manual,headers=key())
admin.request(f'/api/reports/{new_report}/review-items',manual,headers=key(),expected=409)
inherited=generate(admin,project)
current=admin.request(f'/api/reports/{inherited}/review-items')
assert current['total']==1 and current['items'][0]['origin']=='MANUAL'
admin.archive(project,{'main.c':'double changed(double x){return x*x;}\n'})
assert admin.request(f'/api/reports/{inherited}/review-items')['writable'] is False
assert admin.request(download)==original
checks['manualInheritanceConflictAndSourceInvalidation']='PASS'

# A/B 普通用户与管理员的真实接口隔离。
users=state.setdefault('users',{})
for name in ('a','b'):
    if name not in users:
        username='optional-'+name+'-'+uuid.uuid4().hex[:8]
        user=admin.request('/api/admin/users',{'username':username,'email':username+'@example.invalid'})
        password='T!'+secrets.token_hex(6)
        admin.request('/api/admin/users/'+user['id']+'/reset-password',{'newPassword':password,'mustChangePassword':False})
        users[name]={'username':username,'password':password,'id':user['id']};m['STATE'].write_text(json.dumps(state))
clients={}
for name in users:
    clients[name]=Client(name);clients[name].request('/api/auth/login',{'username':users[name]['username'],'password':users[name]['password']})
a=clients['a'];b=clients['b']
owned=a.request('/api/projects',{'name':'optional-permission','sourceArch':'x86','targetArch':'riscv64'})['id']
a.archive(owned,{'main.c':'double add(double x){return x+1;}\n'})
task=a.request(f'/api/projects/{owned}/floating-point-analyses',{})
assert a.task(task['id'])['status']=='SUCCEEDED'
report=generate(a,owned)
for path in (f'/api/projects/{owned}/readiness?stageId=floating-point-analysis',f'/api/projects/{owned}/floating-point-analyses/latest',
             f'/api/projects/{owned}/reports/evaluation/readiness',f'/api/tasks/{task["id"]}',f'/api/reports/{report}/review-items',
             f'/api/reports/{report}/source-files',f'/api/reports/{report}/download?format=json'):
    b.request(path,expected=404);admin.request(path)
b.request(f'/api/projects/{owned}/floating-point-analyses',{},expected=404)
b.request(f'/api/reports/{report}/fix-list-confirmations',{'expectedVersion':1},headers=key(),expected=404)
for client in (a,b):client.request('/api/admin/users',expected=403)
checks['horizontalAndVerticalIsolation']='PASS'
Path('/tmp/optional-review-checks.json').write_text(json.dumps(checks,indent=2))
print(json.dumps(checks),flush=True)
