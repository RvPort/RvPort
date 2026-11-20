"""仅连接 rvport-optional-1120 网络中的独立本地实例；输出不含会话或凭据。"""
import http.cookiejar, io, json, secrets, time, urllib.request, urllib.error, uuid, zipfile
from pathlib import Path

BASE='http://backend:8080'
STATE=Path('/tmp/optional-acceptance-state.json')
OUT=Path('/tmp/optional-acceptance-results.json')

class Client:
    def __init__(self,name):
        self.cookies=http.cookiejar.LWPCookieJar('/tmp/optional-'+name+'.cookies')
        if Path(self.cookies.filename).exists(): self.cookies.load(ignore_discard=True)
        self.http=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))
    def request(self,path,body=None,method=None,headers=None,expected=200):
        method=method or ('GET' if body is None else 'POST')
        headers=dict(headers or {})
        if method!='GET':
            csrf=self.request('/api/auth/csrf')
            headers[csrf['headerName']]=csrf['token']
        if body is not None and not isinstance(body,bytes):
            body=json.dumps(body).encode();headers['Content-Type']='application/json'
        request=urllib.request.Request(BASE+path,body,headers,method=method)
        try:
            response=self.http.open(request,timeout=60);status=response.status;raw=response.read()
        except urllib.error.HTTPError as error:
            status=error.code;raw=error.read()
        self.cookies.save(ignore_discard=True)
        try: value=json.loads(raw)
        except ValueError: value={'rawBytes':len(raw)}
        if status!=expected: raise AssertionError(f'{method} {path}: {status}, code={value.get("code")}, message={value.get("message")}')
        return value.get('data',value)
    def archive(self,project,files):
        stream=io.BytesIO()
        with zipfile.ZipFile(stream,'w') as archive:
            for name,content in files.items(): archive.writestr(name,content)
        boundary='rvport-'+uuid.uuid4().hex
        body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="source.zip"\r\nContent-Type: application/zip\r\n\r\n'.encode()
              +stream.getvalue()+f'\r\n--{boundary}--\r\n'.encode())
        return self.request(f'/api/projects/{project}/sources/archive',body,headers={'Content-Type':'multipart/form-data; boundary='+boundary})
    def task(self,task_id):
        previous=None
        for _ in range(360):
            task=self.request('/api/tasks/'+task_id)
            status=task['status']
            if status!=previous: print('task',task_id,status,flush=True);previous=status
            if status in ('SUCCEEDED','FAILED','CANCELLED','TIMED_OUT'): return task
            time.sleep(1)
        raise AssertionError('task timeout '+task_id)

def main():
    admin=Client('admin')
    if not STATE.exists():
        admin.request('/api/auth/login',{'username':'admin','password':'admin123'})
        password='R!'+secrets.token_hex(6)
        admin.request('/api/auth/change-password',{'currentPassword':'admin123','newPassword':password})
        state={'password':password,'projects':{}}
        STATE.write_text(json.dumps(state))
    else: state=json.loads(STATE.read_text())
    admin.request('/api/auth/login',{'username':'admin','password':state['password']})
    results={}
    cases={
        'source-only':{'main.c':'double add(double a,double b){return a+b;}\n'},
        'partial':{'main.c':'double add(double a,double b){return a+b;}\n','broken.c':'#include "missing.h"\nint broken(void){return 0;}\n'},
        'multilanguage':{'main.c':'int main(void){return 0;}\n','other.cpp':'int value(){return 1;}\n','main.rs':'fn main() {}\n',
            'main.go':'package main\nfunc main(){}\n','Main.java':'class Main { public static void main(String[] args) {} }\n',
            'main.py':'print(1)\n','main.s':'.text\n'},
        'go-only':{'main.go':'package main\nfunc main() {}\n'}
    }
    for name,files in cases.items():
        project=admin.request('/api/projects',{'name':'optional-'+name,'sourceArch':'x86','targetArch':'riscv64'})['id']
        state['projects'][name]=project;STATE.write_text(json.dumps(state))
        admin.archive(project,files)
        checks={}
        for stage in ('kb','floating-point-analysis','memory-order-analysis','rv-dependency-analysis','build','test'):
            checks[stage]=admin.request(f'/api/projects/{project}/readiness?stageId={stage}')
        results[name]={'projectId':project,'readiness':checks,'analyses':{}}
        if name=='source-only':
            for stage in ('kb','floating-point-analysis','memory-order-analysis'):
                assert all(p['status']=='SATISFIED' for p in checks[stage]['prerequisites'] if p['required']),checks[stage]
            assert any(p['required'] and p['status']!='SATISFIED' for p in checks['rv-dependency-analysis']['prerequisites'])
            assert admin.request(f'/api/projects/{project}/reports/evaluation/readiness')['canGenerate'] is False
        kinds=('kb-scans','floating-point-analyses','memory-order-analyses') if name=='source-only' else ('floating-point-analyses',) if name=='partial' else ('kb-scans',)
        for kind in kinds:
            body={'language':'c,cpp,rust,go,java,python,asm','sourceArch':'x86'} if kind=='kb-scans' and name=='multilanguage' else {'language':'go'} if name=='go-only' else {}
            task=admin.request(f'/api/projects/{project}/{kind}',body)
            done=admin.task(task['id']);assert done['status']=='SUCCEEDED',(name,kind,done.get('errorCode'),done.get('message'))
            latest=admin.request(f'/api/projects/{project}/{kind}/latest')
            results[name]['analyses'][kind]=latest
            if name=='source-only' and kind=='floating-point-analyses':
                assert latest['result']['status']=='COMPLETE',latest['result']['status']
                assert latest['result']['bcStatus']=='MISSING'
                assert latest['result']['summary']['analysisComplete'] is True
            if name=='partial': assert latest['result']['status']=='PARTIAL',latest['result']['status']
        ready=admin.request(f'/api/projects/{project}/reports/evaluation/readiness')
        assert ready['canGenerateStandard'] is True
        request={'sourceRevisionId':ready['sourceRevisionId'],'mode':'STANDARD','selectionToken':ready['selectionToken'],
                 'baseReportId':ready.get('regenerationBaseReportId'),'expectedDraftVersion':ready.get('regenerationBaseDraftVersion')}
        report=admin.request(f'/api/projects/{project}/reports/evaluation',request,headers={'Idempotency-Key':str(uuid.uuid4())})
        assert admin.task(report['id'])['status']=='SUCCEEDED'
        results[name]['reportId']=report['reportId']
        results[name]['reportLatest']=admin.request(f'/api/projects/{project}/reports/evaluation/latest')
        results[name]['overview']=admin.request(f'/api/projects/{project}/overview')
        OUT.write_text(json.dumps(results,ensure_ascii=False,indent=2))
        print('PASS',name,flush=True)
    print('SOURCE_ANALYSIS_COMPOSE_PASS',flush=True)

if __name__=='__main__':main()
