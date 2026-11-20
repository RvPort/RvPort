"""独立本地 Compose 故障注入的 API 侧断言；容器停启由宿主显式控制。"""
import runpy,json,sys,time,uuid
from pathlib import Path
m=runpy.run_path(str(Path(__file__).with_name('compose-acceptance.py')));a=m['Client']('admin');state=json.loads(m['STATE'].read_text())
a.request('/api/auth/login',{'username':'admin','password':state['password']})
phase=sys.argv[1];project=state['projects']['source-only']
def save():m['STATE'].write_text(json.dumps(state))
def report_task():
    p=state['ai']['project'];r=a.request(f'/api/projects/{p}/reports/evaluation/readiness')
    return a.request(f'/api/projects/{p}/reports/evaluation',{'sourceRevisionId':r['sourceRevisionId'],'mode':'AI_ENHANCED',
        'selectionToken':r['selectionToken'],'baseReportId':r.get('regenerationBaseReportId'),
        'expectedDraftVersion':r.get('regenerationBaseDraftVersion')},headers={'Idempotency-Key':str(uuid.uuid4())})
if phase=='enqueue':
    task=a.request(f'/api/projects/{project}/floating-point-analyses',{})
    state['restartTask']=task['id'];save();print('RESTART_QUEUED '+task['id'])
elif phase=='finish':
    assert a.task(state['restartTask'])['status']=='SUCCEEDED';print('QUEUED_RESTART_PASS')
elif phase=='oom':
    task=a.request(f'/api/projects/{project}/kb-scans',{});done=a.task(task['id'])
    assert done['status']=='FAILED',done
    latest=a.request(f'/api/projects/{project}/kb-scans/latest');summary=latest['result']['summary']
    assert summary['resultAvailable'] is False and summary['memoryLimitMib']==1,summary
    assert summary['reasonCode'] in ('ANALYSIS_MEMORY_BUDGET_EXCEEDED','ANALYSIS_PROCESS_FAILED','ANALYSIS_PROCESS_TERMINATED'),summary
    follow=a.request(f'/api/projects/{project}/floating-point-analyses',{});assert a.task(follow['id'])['status']=='SUCCEEDED'
    Path('/tmp/optional-memory-checks.json').write_text(json.dumps({'diagnostic':summary,'workerContinues':'PASS'},indent=2))
    print('MEMORY_FAILURE_ISOLATED '+summary['reasonCode'])
elif phase in ('lost-start','cancel-start'):
    task=report_task();state[phase]=task;save()
    for _ in range(60):
        current=a.request('/api/tasks/'+task['id'])
        if current['status']=='RUNNING' and current['progress']>=10:break
        assert current['status'] not in ('FAILED','SUCCEEDED'),current
        time.sleep(1)
    else:raise AssertionError('task did not begin')
    if phase=='cancel-start':a.request('/api/tasks/'+task['id']+'/cancel',{})
    print('TASK_IN_FLIGHT '+task['id'],flush=True)
elif phase in ('lost-check','cancel-check'):
    start='lost-start' if phase=='lost-check' else 'cancel-start'
    task=state[start];done=a.task(task['id'])
    if phase=='lost-check':assert done['status']=='FAILED' and done['errorCode']=='WORKER_LOST',done
    else:assert done['status']=='CANCELLED',done
    latest=a.request(f'/api/projects/{state["ai"]["project"]}/reports/evaluation/latest')
    assert latest['currentReport']['reportId']==state['ai']['report'],latest
    Path('/tmp/optional-'+phase+'.json').write_text(json.dumps({'task':done,'oldReportRetained':'PASS'},indent=2))
    print(phase.upper()+'_PASS')
