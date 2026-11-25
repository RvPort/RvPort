"""真实 Codex CLI + worker 回环模型夹具，不访问任何真实模型。"""
import runpy,json,uuid,sys
from pathlib import Path
m=runpy.run_path(str(Path(__file__).with_name('compose-acceptance.py')));Client=m['Client'];state=json.loads(m['STATE'].read_text());a=Client('admin')
a.request('/api/auth/login',{'username':'admin','password':state['password']})
phase=sys.argv[1]
def key():return {'Idempotency-Key':str(uuid.uuid4())}
if phase=='setup':
    runtimes=a.request('/api/me/agent-runtimes');runtimes=runtimes.get('items',[]) if isinstance(runtimes,dict) else runtimes
    runtime=next(r for r in runtimes if r['agentType']=='CODEX')
    profile=a.request('/api/me/model-profiles',{'name':'optional-controlled-'+uuid.uuid4().hex[:8],'enabled':True,
        'modelProviderType':'OPENAI_COMPATIBLE','baseUrl':'http://127.0.0.1:'+sys.argv[2]+'/admin/v1','model':'fixture-model',
        'wireApi':'RESPONSES','apiKey':'fixture-admin','timeoutSeconds':30,'maxRuntimeSeconds':60})
    a.request('/api/me/model-stage-assignments',{'assignments':[{'stage':'REPORT_AI_REVIEW','modelProfileId':profile['id'],'agentRuntimeId':runtime['id']}]},method='PATCH')
    project=a.request('/api/projects',{'name':'optional-ai-batches','sourceArch':'x86','targetArch':'riscv64'})['id']
    a.archive(project,{f'f{i}.c':'#include <xmmintrin.h>\n__m128 f(__m128 a,__m128 b){return _mm_add_ps(a,b);}\n' for i in range(36)})
    task=a.request(f'/api/projects/{project}/kb-scans',{});assert a.task(task['id'])['status']=='SUCCEEDED'
    state['ai']={'project':project,'profile':profile['id']};m['STATE'].write_text(json.dumps(state))
    preview=a.request(f'/api/projects/{project}/reports/evaluation/input-preview')
    assert preview['total']>32,preview['total']
    print('AI_SETUP candidates='+str(preview['total']),flush=True)
else:
    project=state['ai']['project'];ready=a.request(f'/api/projects/{project}/reports/evaluation/readiness')
    assert ready['canGenerateAi']
    body={'sourceRevisionId':ready['sourceRevisionId'],'mode':'AI_ENHANCED','selectionToken':ready['selectionToken'],
          'baseReportId':ready.get('regenerationBaseReportId'),'expectedDraftVersion':ready.get('regenerationBaseDraftVersion')}
    task=a.request(f'/api/projects/{project}/reports/evaluation',body,headers=key());done=a.task(task['id'])
    if phase=='missing':
        assert done['status']=='FAILED',done['status']
        latest=a.request(f'/api/projects/{project}/reports/evaluation/latest')
        assert latest['currentReport']['reportId']==state['ai']['report']
        print('AI_MISSING_REJECTED_OLD_RESULT_RETAINED',flush=True)
    else:
        assert done['status']=='SUCCEEDED',done.get('errorMessage')
        report=task['reportId'];doc=a.request(f'/api/reports/{report}/download?format=json')
        summary=doc['aiSummary'];assert summary['validatedBatches']>1
        assert summary['validatedItems']==summary['totalItems']==len(doc['aiReviews'])
        items=a.request(f'/api/reports/{report}/review-items?pageSize=200')
        confirmed=sum(x['verdict']=='CONFIRMED_ISSUE' for x in doc['aiReviews'])
        assert confirmed==summary['totalBatches']
        assert items['draftSummary']['toFixCount']==confirmed
        assert items['draftSummary']['pendingReviewCount']==summary['totalItems']-confirmed
        a.request(f'/api/reports/{report}/fix-list-confirmations',{'expectedVersion':items['draftVersion']},headers=key(),expected=409)
        assert any(s.get('missingEvidence') for s in doc['sources'])
        state['ai']['report']=report;m['STATE'].write_text(json.dumps(state))
        Path('/tmp/optional-ai-checks.json').write_text(json.dumps({'summary':summary,'defaults':items['draftSummary']},indent=2))
        print('AI_COMPLETE '+json.dumps(summary),flush=True)
