"""本轮独立本地目标上的真实编译数据库/共享 BC 增强。"""
import runpy,json,sys
from pathlib import Path
m=runpy.run_path(str(Path(__file__).with_name('compose-acceptance.py')));a=m['Client']('admin');state=json.loads(m['STATE'].read_text())
a.request('/api/auth/login',{'username':'admin','password':state['password']})
project=state['build']['projectId']
partial=len(sys.argv)>1 and sys.argv[1]=='partial'
if partial:a.archive(project,{'main.c':'int main(void){return 0;}\n','other.c':'double other(double x){return x+1;}\n'})
# 首先保留源码结果。之后补建必须提示增强，而不能立刻使旧源码结果失效。
task=a.request(f'/api/projects/{project}/floating-point-analyses',{});assert a.task(task['id'])['status']=='SUCCEEDED'
before=a.request(f'/api/projects/{project}/floating-point-analyses/latest')['currentResult']
script='''clang-21 -std=c99 -DMODE=1 -c main.c -o main.o
python3 - <<'PY'
import json,pathlib
p=pathlib.Path.cwd()
pathlib.Path('compile_commands.json').write_text(json.dumps([{'directory':str(p),'file':str(p/'main.c'),'arguments':['clang-21','-std=c99','-DMODE=1','-c',str(p/'main.c'),'-o','main.o']}]))
PY
'''
if partial:
    script += '''python3 - <<'PY'
import json,pathlib
p=pathlib.Path.cwd();f=pathlib.Path('compile_commands.json');rows=json.loads(f.read_text())
rows.append({'directory':str(p),'file':str(p/'other.c'),'arguments':['clang-18','-std=c99','-c',str(p/'other.c'),'-o','other.o']})
f.write_text(json.dumps(rows))
PY
'''
response=a.request(f'/api/projects/{project}/builds',{'buildScope':'SOURCE_ONLY','script':script,'timeoutSeconds':180})
build=response['builds'][0];done=a.task(build['taskId']);assert done['status']=='SUCCEEDED',done.get('errorMessage')
complete=a.request('/api/builds/'+build['id'])
after=a.request(f'/api/projects/{project}/floating-point-analyses/latest')['currentResult']
if before['inputOrigin']=='SOURCE_DISCOVERY':
    assert before['id']==after['id'] and after['enhancementAvailable'] is True
output={'build':complete,'sourceResultRetained':'PASS' if before['inputOrigin']=='SOURCE_DISCOVERY' else 'PREVIOUS_RUN_VERIFIED','analyses':{}}
for kind in ('floating-point-analyses','memory-order-analyses','kb-scans'):
    task=a.request(f'/api/projects/{project}/{kind}',{});done=a.task(task['id'])
    assert done['status']=='SUCCEEDED',(kind,done.get('errorCode'),done.get('errorMessage'))
    latest=a.request(f'/api/projects/{project}/{kind}/latest')
    assert latest['currentResult']['inputOrigin']=='BUILD_ARTIFACTS',latest['currentResult']['summary']
    output['analyses'][kind]=latest
if partial:
    assert complete['bitcode']['succeededCount']==1 and complete['bitcode']['failedCount']==1
    for kind in ('floating-point-analyses','memory-order-analyses'):
        assert output['analyses'][kind]['currentResult']['analysisMode']=='AST_BC_MIXED'
else:
    assert complete['bitcode']['succeededCount']==1 and complete['bitcode']['failedCount']==0
    for kind in ('floating-point-analyses','memory-order-analyses'):
        assert output['analyses'][kind]['currentResult']['analysisMode']=='AST_BC_COMPLETE'
Path('/tmp/optional-'+('bc-partial' if partial else 'enhanced')+'-checks.json').write_text(json.dumps(output,indent=2))
print('ENHANCED_INPUT_PASS '+json.dumps({k:v['currentResult'].get('analysisMode') for k,v in output['analyses'].items()}),flush=True)
