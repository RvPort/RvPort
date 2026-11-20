"""只访问本轮 Docker 网络内的 optional-target SSH 测试容器。"""
import runpy,json,uuid
from pathlib import Path
m=runpy.run_path(str(Path(__file__).with_name('compose-acceptance.py')));a=m['Client']('admin');state=json.loads(m['STATE'].read_text())
a.request('/api/auth/login',{'username':'admin','password':state['password']})
project=a.request('/api/projects',{'name':'optional-build-chain','sourceArch':'x86','targetArch':'riscv64'})['id']
a.archive(project,{'main.c':'int main(void){return 0;}\n'})
envs=[]
for role,arch,path in [('SOURCE_X86','x86_64','/srv/optional-source'),('TARGET_RISCV','riscv64','/srv/optional-target')]:
    env=a.request(f'/api/projects/{project}/environments',{'name':role,'role':role,'arch':arch,'host':'optional-target','sshPort':22,
        'username':'root','loginType':'KEY','privateKey':Path('/tmp/optional-ssh-key').read_text(),'workingDirectory':path})
    envs.append(env['id'])
a.request(f'/api/projects/{project}/runtime-environments',{'sourceEnvironmentId':envs[0],'targetEnvironmentId':envs[1]},method='PATCH')
readiness=a.request(f'/api/projects/{project}/readiness?stageId=build')
assert all(p['status']=='SATISFIED' for p in readiness['prerequisites'] if p['required'])
assert a.request(f'/api/projects/{project}/builds')['total']==0
assert any(p['status']!='SATISFIED' for p in a.request(f'/api/projects/{project}/readiness?stageId=test')['prerequisites'] if p['required'])
# 两端是受控同架构执行夹具，验证后端依赖/部署/批次，不能用来宣称 RISC-V 运行兼容。
response=a.request(f'/api/projects/{project}/builds',{'buildScope':'BOTH','script':'clang-18 main.c -o optional-app','timeoutSeconds':60})
for build in response['builds']:
    done=a.task(build['taskId']);assert done['status']=='SUCCEEDED',(done.get('errorCode'),done.get('errorMessage'))
assert all(p['status']=='SATISFIED' for p in a.request(f'/api/projects/{project}/readiness?stageId=test')['prerequisites'] if p['required'])
checks={'directBothWithoutSourceOnly':'PASS','builds':response['builds'],'projectId':project}
state['build']=checks;m['STATE'].write_text(json.dumps(state))
for build in response['builds']:
    completed=a.request('/api/builds/'+build['id'])
    task=a.request(f'/api/projects/{project}/tests',{'buildRunId':build['id'],'command':'./optional-app',
        'workingDirectory':completed['workingDirectory'],'timeoutSeconds':30})
    done=a.task(task['id']);assert done['status']=='SUCCEEDED',(done.get('errorCode'),done.get('errorMessage'))
checks['testsAfterBoth']='PASS'
Path('/tmp/optional-build-checks.json').write_text(json.dumps(checks,indent=2))
print('BUILD_TEST_CHAIN_PASS',flush=True)
