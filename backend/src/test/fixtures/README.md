# Agent 本地 Docker 验收夹具

这些入口仅连接本地模拟模型与专用 Compose 数据库。它们属于验收代码，不进入生产镜像运行入口，也不代替 `mvn clean verify`、Python coverage 或第三方补丁单元测试。

## 候选镜像真实 CLI

使用当前源码构建的 backend 镜像，创建 `--init --cpus 2 --memory 2g` 的独立容器与网络，将当前 `src/test/fixtures`、`src/test/resources` 和 `src/main/resources/agentruntime` 复制到 `/workspace/backend` 的对应位置。报告写入专用 `/report`。在容器中从当前应用 JAR 提取 `BOOT-INF/classes` 和 `BOOT-INF/lib`，分别放入 `/tmp/acceptance-java/classes`、`/tmp/acceptance-java/lib`，编译两个 test-only Java 入口到 `/tmp/acceptance-java/test-classes`：

```sh
javac -J-Xmx512m -cp '/tmp/acceptance-java/classes:/tmp/acceptance-java/lib/*' \
  -d /tmp/acceptance-java/test-classes \
  /workspace/backend/src/test/java/cn/rvport/backend/agentruntime/AgentCliAcceptanceMain.java \
  /workspace/backend/src/test/java/cn/rvport/backend/modelprofile/CodexProbeDatabaseAcceptanceMain.java
```

在容器的 `/workspace/backend` 下顺序执行：

```sh
python3 src/test/fixtures/agent_runtime_acceptance.py --cli-root /usr/local/bin --java-runtime /tmp/acceptance-java --report /report/cli --catalog /workspace/backend/src/test/resources/agentruntime/codex-fixture-model.json
python3 src/test/fixtures/agent_runtime_acceptance.py --cli-root /usr/local/bin --java-runtime /tmp/acceptance-java --report /report/failures --failures-only
python3 src/test/fixtures/agent_runtime_acceptance.py --cli-root /usr/local/bin --java-runtime /tmp/acceptance-java --report /report/business --business-only
python3 src/test/fixtures/agent_runtime_acceptance.py --cli-root /usr/local/bin --java-runtime /tmp/acceptance-java --report /report/concurrent --concurrent-only
python3 src/test/fixtures/agent_runtime_acceptance.py --cli-root /usr/local/bin --java-runtime /tmp/acceptance-java --report /report/missing-usage --agent CODEX --wire CHAT --omit-usage
python3 src/test/fixtures/opencodex_protocol_acceptance.py /opt/rvport/opencodex/node_modules/.bin/ocx /report/protocol
python3 src/test/fixtures/opencodex_resources.py /opt/rvport/opencodex/node_modules/.bin/ocx /report/resources.json
```

模拟上游验证实际工具返回及输出文件，不能替换为只返回预期最终 JSON 的假 CLI。custom 模型目录仅为测试副本，声明模拟上游不支持 verbosity；生产准入仍拒绝该能力。

## 完整隔离 Compose

从仓库根目录使用独立项目名、假凭据、网络、数据卷及 loopback 端口构建所有默认服务，再用一次 `docker compose up -d --wait` 启动。不要复用已有部署或远程数据库。

在同一专用网络启动临时模拟模型容器，网络别名 `agent-mock`，执行 `agent_runtime_acceptance.py --serve --report /tmp/mock`。它监听 8089，不发布主机端口；`/__metrics` 只供负例断言查询上游请求计数。该容器不是新增生产 Compose 服务。

将 `compose_agent_acceptance.py` 复制到本轮 API 容器执行。它面向全新隔离数据库，会修改本轮默认管理员密码、创建 profile/测试用户、保存阶段绑定并输出 `/tmp/agent-api-acceptance.json`。之后在本轮 worker 中，用上面的 classpath 执行：

```sh
java -cp '/tmp/acceptance-java/classes:/tmp/acceptance-java/test-classes:/tmp/acceptance-java/lib/*' cn.rvport.backend.modelprofile.CodexProbeDatabaseAcceptanceMain
java -cp '/tmp/acceptance-java/classes:/tmp/acceptance-java/test-classes:/tmp/acceptance-java/lib/*' cn.rvport.backend.agentruntime.AgentCliAcceptanceMain /usr/local/bin CODEX http://agent-mock:8089/v1 CHAT --business-db
```

数据库入口使用该隔离 worker 的 `RVPORT_DB_*`，所有写入事务回滚；业务入口通过真实 repository/cipher 解析正常 API 保存的两个阶段绑定，执行实际 Agent 工具及输出契约，使用本地输入而不访问 SSH。

故障恢复按宿主机编排顺序执行 `compose_probe_recovery_acceptance.py` 的 phase 参数：

1. 停止本轮 worker，在 API 中执行 `offline`，验证混合 runtime 部分成功及旧同步入口有界失败。
2. worker 仍停止时执行 `queue-disabled`，启动 worker 后执行 `check-disabled`，断言领取时禁用 profile 不产生上游调用。
3. 再次停止本轮 worker，执行 `queue-restart`；强制结束仅本轮 API 进程以模拟中断，启动 worker 等待 job 过期，再启动 API，执行 `check-restart` 检查 run 与个人 profile 摘要；共享 runtime 不包含个人测试摘要。

## 个人模型隔离验收

`personal_model_acceptance.py serve` 在本轮独立网络以 `personal-model-mock` 别名运行模拟模型，`api` 在本轮 API 容器执行。它创建 alice/bob，验证与 admin 的个人 CRUD、绑定、run、结果隔离，真实 Codex CHAT 并发、原生 RESPONSES、DeepSeek Harness/OpenCode、禁用账号及零越权上游调用。假密钥只在本地模拟端比较，报告不保存实际密钥。`queue-disabled`/`check-disabled` 和 `queue-restart`/`check-restart` 分别配合仅本轮 worker/API 停启验证恢复。

默认单 worker 的 probe listener 串行消费。验证实际 CLI 并行时，以同一服务镜像和本轮网络/卷临时运行第二个 worker（独立 instance ID，不发布端口、不增加生产 Compose 服务），待启动后执行 `concurrent`。该阶段除了检查账号地址/密钥，还要求两个账号的实际请求时间区间重叠；完成后删除临时 worker，再进行停机恢复场景。

`PersonalModelBusinessAcceptanceMain` 使用当前应用 classpath 在本轮 API 容器运行，仅接受本地隔离项目标识 `rvport-personal-model` 或 `rvport-report-review-1117`（环境变量 `RVPORT_ACCEPTANCE_PROJECT`）。它创建本地源码及已完成测试夹具，经真实服务派发 AI 增强报告复核和自动比较，由本轮 worker 实际执行模型；不会运行环境收集、构建或自动测试 SSH 命令。检查 admin 手动重触发使用自己的 profile、历史项目结果可见、自动派生沿用原用户及重复完成不重跑。原始结果写 `/tmp/personal-business-acceptance.json`。

随后停止本轮 worker，执行该 Java 入口的 `queue-retry` 参数；它创建 Alice 的任务，改绑到另一 profile 并禁用原 profile。启动本轮 worker 后执行 `check-retry`，检查实际自动重试次数、原主体和固定引用，以及零上游调用。业务成功路径也逐次检查模拟端收到的地址和假密钥属于预期触发者。

只停启本轮容器；不重启 Docker 引擎、不触及其他项目。结束后导出报告，清理本轮容器、网络、卷和运行凭据。完整执行记录放在私有 `.migration-reports/`，不得提交真实配置、凭据或运行日志。


### 评估报告个人阶段验证

在本轮独立 Compose 项目内，`PersonalModelBusinessAcceptanceMain standard-review` 使用普通模式检查空确认、框选与历史下载；`personal-review` 使用真实 worker 验证所有者的 Codex 与管理员的 OpenCode 读取各自的模型配置。后者由同一个隔离 worker 容器中的测试进程启动临时回环模型；将本目录中的 `report_review_loopback.py` 和 `agent_runtime_acceptance.py` 放到容器内 `/tmp/report-workflow/fixtures/`，仅复制测试入口的已编译 class，不改运行镜像。测试入口接受隔离项目名 `rvport-report-review-数字` 或 `rvport-review-final-数字`；其生产者进程使用 `RVPORT_APP_MODE=api` 及独立 `RVPORT_WORKER_INSTANCE_ID` 以免占用 worker 的专用队列。模拟模型不接受外部连接、不保存凭据，执行结束后随测试入口退出。

### 结果报告完整联调

从仓库根操作。为每轮选择未使用的数字后缀和回环端口，在本地忽略目录准备独立的 `compose.env`。设置 COMPOSE_PROJECT_NAME、BACKEND_IMAGE、FRONTEND_IMAGE、MINIO_IMAGE 为本轮专用值。FRONTEND_PORT、RVPORT_BACKEND_PORT、POSTGRES_PORT、REDIS_PORT、MINIO_API_PORT、MINIO_CONSOLE_PORT、RABBITMQ_AMQP_PORT、RABBITMQ_MANAGEMENT_PORT 各使用不同的 `127.0.0.1:端口`。按根 .env.example 生成独立 RVPORT_CREDENTIAL_MASTER_KEY；其余数据库、队列和模型凭据只用夹具值。卷名按项目默认派生。先创建同名外部网络，再启动完整默认服务。

```powershell
$p = 'rvport-review-final-1117'
$r = '.migration-reports/report-review-final-1117'
$cfg = @('-p',$p,'--env-file',"$r/compose.env",'-f','docker-compose.yml')
docker network create "$p-infra"
docker compose --parallel 1 @cfg build
docker compose @cfg up -d --wait
$w = "$p-backend-worker-1"
docker exec $w mkdir -p /tmp/report-workflow/fixtures /tmp/report-workflow/test
docker cp backend/src/test/fixtures/. "${w}:/tmp/report-workflow/fixtures/"
docker cp backend/src/test/java/cn/rvport/backend/modelprofile/PersonalModelBusinessAcceptanceMain.java "${w}:/tmp/report-workflow/"
docker exec -w /tmp/report-workflow $w sh -ec 'jar xf /app/backend/rvport-backend.jar BOOT-INF/classes BOOT-INF/lib && javac -cp "BOOT-INF/classes:BOOT-INF/lib/*" -d test PersonalModelBusinessAcceptanceMain.java'
foreach ($mode in 'standard-review','personal-review') {
    docker exec -w /tmp/report-workflow -e RVPORT_APP_MODE=api -e RVPORT_WORKER_INSTANCE_ID=review-scenario-producer -e "RVPORT_ACCEPTANCE_PROJECT=$p" $w java -Xmx512m -XX:ActiveProcessorCount=1 -cp 'test:BOOT-INF/classes:BOOT-INF/lib/*' cn.rvport.backend.modelprofile.PersonalModelBusinessAcceptanceMain $mode
    if ($LASTEXITCODE) { throw "验收失败 $mode" }
}
```

standard-review 创建独立项目并验证四类来源、普通生成、确认、源码切换、继承、跨项目隔离及复检。personal-review 创建独立所有者和管理员，经消息队列运行三种 CLI 的四种 Agent/协议组合。在本地回环模型分别注入三种结论、五种非法输出和超时，用完整任务状态、模型访问次数、快照与发布指针交叉断言。组合夹具包含四类来源共七项，预期六项进入 AI，一项继承人工排除。

HTTP 夹具使用上述 standard-review 创建的项目。将 report_review_http_acceptance.py 复制到本轮连接 `$p-infra` 的 Python 测试容器中。该夹具仅针对新的隔离测试 admin，首次执行完成测试密码初始化。

```powershell
$project = (docker exec "$p-postgres-1" psql -U rvport -Atc "select p.id from projects p join users u on u.id=p.created_by and u.username='admin' where exists(select 1 from report_review_runs r where r.project_id=p.id) order by p.created_at desc limit 1").Trim()
docker run -d --name "$p-http" --network "$p-infra" --cpus 1 -m 256m --entrypoint sleep "rvport-review-final-backend:1117" infinity
docker cp backend/src/test/fixtures/report_review_http_acceptance.py "${p}-http:/tmp/review-http.py"
foreach ($phase in 'enqueue','race') {
    docker compose @cfg stop backend-worker
    docker exec -e "RVPORT_ACCEPTANCE_PROJECT=$p" "$p-http" python3 /tmp/review-http.py $phase $project /tmp/b08.json
    if ($LASTEXITCODE) { throw "入队失败 $phase" }
    docker compose @cfg stop backend
    docker compose @cfg up -d --wait
    $check = if ($phase -eq 'race') { 'check-race' } else { 'resume' }
    docker exec -e "RVPORT_ACCEPTANCE_PROJECT=$p" "$p-http" python3 /tmp/review-http.py $check $project /tmp/b08.json
    if ($LASTEXITCODE) { throw "恢复校验失败 $check" }
}
docker rm -f "$p-http"
```

enqueue/resume 验证带游标重连、队列恢复和幂等重放；race/check-race 在派发后先修改并确认基线，再允许 worker 执行，检查迟到的发布不能覆盖人工决定。两条路径都实测已退役的 11 个路由返回 404，项目前后的任务数量相等。

Java 单元测试在当前源码的 Dockerfile unit-test target 执行 `mvn -B -s /opt/maven-cache/settings.xml -Dmaven.repo.local=/opt/maven-cache/repository clean verify`。使用同一镜像在 /workspace/backend 下依次执行 Python 分支覆盖率与门禁：

```sh
PYTHONPATH=/workspace/tools/memory-order-analyzer/python python3 -m coverage run --branch --source=src/main/resources/analysis,src/main/resources/bitcode,src/main/resources/agentruntime,agent-cli -m pytest -q src/test/python
python3 -m coverage json -o /tmp/python.json
python3 src/test/fixtures/check_python_coverage.py /tmp/python.json
```

在已运行的 worker 中使用固定 Bun/包执行 `OPENCODEX_PACKAGE_ROOT=/opt/rvport/opencodex/node_modules/@bitkyc08/opencodex /opt/rvport/opencodex/node_modules/.bin/bun test /tmp/report-workflow/fixtures/opencodex_patch.test.js`。导出本轮报告、日志和镜像标识后，删除本轮测试容器，执行 `docker compose @cfg down --volumes` 和 `docker network rm "$p-infra"`。新一轮使用空资源重新初始化。
