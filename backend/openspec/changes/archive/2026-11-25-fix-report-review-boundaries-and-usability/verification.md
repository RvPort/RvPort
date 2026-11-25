# 报告边界与易用性验收记录

## 范围与资源

2026-11-25，dev 分支，基线 85cba719698098a3fbc85f83873e266e4d7b5edf。仅修改 backend；保留根 README.zh-CN.md 和 docs 下用户已有修改。不修改前端、工具源码或远程环境，不提交或推送。OpenSpec 根为 backend/openspec。

编译、单测、覆盖率、依赖安装及业务验收均在本地 Docker。专用 Compose project 为 rvport-report-handoff-1125，网络 rvport-report-handoff-1125-infra，使用该 project 新建的数据卷。七个默认服务为 frontend、backend、backend-worker、postgres、rabbitmq、redis、minio；API/前端仅发布本机 29980/29981，数据服务不发布宿主端口。API 2 CPU/2 GiB，Worker 与单测 2 CPU/3 GiB，其余各 1 CPU/256～768 MiB。只清理本轮命名资源，不使用全局 prune。

证据均在 backend/.migration-reports/report-handoff/。compose.env、会话、临时凭据和原始运行数据不纳入源码。Docker Desktop 自行恢复的已有容器未被本轮访问。

## 八项验收

| 项目 | 结果与证据 |
|---|---|
| 1 领取与恢复 | 新版任务 a442b76a-cf94-439f-a7e6-27351a0c2972 在 Worker 停止时创建，API 重启后同任务 QUEUED，Worker 启动后 SUCCEEDED。对专用项目注入数据库更新故障，b8bb9d6e-a612-46e8-b76c-542885f360a5 重试到 attempt=3 后进入 DLQ；收尾失败时消息保留，解除故障后自动 FAILED/TASK_DELIVERY_EXHAUSTED，报告 FAILED、run.active=false、task.failed=1，无人工重投。合法重复投递不增加事件；坏消息持久隔离。见 restart-*、recovery-*、database-final.log。 |
| 2 非法请求 | 基线旧字段返回 500 已复现；新版旧/未知字段、坏 JSON、空体、null、坏枚举/UUID 均 400 REPORT_REQUEST_INVALID。非法后同键合法提交成功，同键同内容指向原任务，异内容 409 IDEMPOTENCY_KEY_REUSED。参数解析及缺必填值均有安全双语说明。 |
| 3 缺 run | 当前 schema 中构造报告 22222222-2222-4222-8222-222222222222，无 run；基线混排和详情 500，新版均可读，review=null、reviewAvailable=false。复核/源码工作区及确认返回 409 REPORT_REVIEW_UNAVAILABLE，越权仍先返回 404。无旧协议转换、backfill 或读取时补写。 |
| 4 正文搜索 | 原始证据、AI 依据、人工说明、排除理由及 displayEvidence 使用展示白名单；依赖 recommendation、分析 detail/testSuggestion 已覆盖。独有关键词、大小写、未知结构、隐藏 ID/hash 反例、分页与统计有单测。真实接口仅在人工说明中出现的 MANUAL-ONLY-TEXT 命中 1 项。 |
| 5 执行能力 | 正常容器真实 helper 自检 exit 0；移除 SETUID/SETGID 的实际受限容器 exit 126。支持环境使用个人配置、受管 Codex CLI 与本地模拟模型完整生成 AI 报告。受限环境观测稳定后 UNAVAILABLE、配置仍成功、AI 创建 409，标准报告成功。排队后能力丢失保留 REPORT_AI_SANDBOX_UNAVAILABLE，最终镜像复验记录见收尾部分。 |
| 6 固定源码搜索 | 4,800,000 字节/50,000 行文件，单次搜索命中 2、501、49,999 行，与完整 100 段查找一致。limit 截断、无匹配、非法参数和跨 500/501 行新增通过；context 保持 500 行。Unicode、代理对、摘要损坏、二进制、UTF-8、大小与权限边界有单测。 |
| 7 版本展示 | 新报告固定 SNAPSHOT 标签/提交号；历史批量关联同一 revision，不依赖 active 版本。上传/Git/无元数据/切换源码/批量查询单测与真实数据库 JSON 投影通过。 |
| 8 原因双语 | report-reasons.v1 独立 displayEvidence 在工作区及新 HTML/JSON/确认导出共用；已知/未知码、命名空间、READ_TIME 回退有单测。原始证据/hash 不变；真实确认的解释与报告一致，重新生成后旧下载字节不变。 |

首组主流程项目 4a1a61da-9cfd-419c-bd74-7812c1ae866f；原报告 2c3af97b-5120-47e9-916f-345b1254e403，重新生成报告 27a00be4-ef69-4a44-a55c-3df034199d3a。旧下载 SHA-256 为 cb5fef824a5005f94577f4f286e9d23cf7f165deb553db8579a517524e2ff629，人工修改、确认及重新生成后不变。人工新增/决定优先继承，旧草稿只读。空报告 d0c98398-dfe2-4846-9968-a06b952ff478 确认为 CONFIRMED_EMPTY。A/B/admin 验证 B 读取/确认 A 报告 404，A/B 访问管理员接口 403，admin 可读，个人模型配置不串用。

## 单元测试与覆盖率

工作目录 /workspace/backend，MAVEN_OPTS=-Xmx1536m -XX:ActiveProcessorCount=2；在 Docker 中实际执行：

```sh
python3 src/main/resources/analysis/coverage_gate.py start --receipt /tmp/receipt-final.json
mvn -B -s /opt/maven-cache/settings.xml -Dmaven.repo.local=/opt/maven-cache/repository clean verify
PYTHONPATH=/workspace/tools/memory-order-analyzer/python python3 -m coverage run --branch --source=src/main/resources/analysis,src/main/resources/bitcode,src/main/resources/agentruntime,agent-cli -m pytest src/test/python -q -p no:cacheprovider
python3 -m coverage json -o /tmp/python-final.json
python3 src/main/resources/analysis/coverage_gate.py verify --receipt /tmp/receipt-final.json --python-json /tmp/python-final.json
```

quality-final-d.log：Java 1,973 项、0 失败/错误；其中 9 项需显式独立数据库，已全部另行通过：database-final.log 3 项、migration-tests.log 5 项、kb-database-tests-empty.log 1 项，均无跳过。Python 211 项通过。KB schema 测试使用独立空库 handoff_kb_schema；首次放在已有完整 schema 的迁移库发生约束冲突，改用专用空库后通过，未修改无关迁移。

| 范围 | 行 covered/total（%） | 分支 covered/total（%） |
|---|---:|---:|
| Java 整体 | 24347/25215（96.5576） | 10832/11829（91.5716） |
| report | 672/693（96.9697） | 230/252（91.2698） |
| report/review | 1378/1476（93.3604） | 1221/1348（90.5786） |
| worker | 1152/1190（96.8067） | 400/436（91.7431） |
| task | 1984/2063（96.1706） | 670/742（90.2965） |
| queue | 115/119（96.6387） | 19/20（95.0000） |
| common/error | 43/43（100） | 8/8（100） |
| config | 155/155（100） | 96/102（94.1176） |
| source | 726/759（95.6522） | 189/209（90.4306） |
| agentruntime | 277/279（99.2832） | 55/60（91.6667） |
| Python 整体 | 1510/1538（98.1795） | 634/659（96.2064） |

完整包/脚本分母见 quality-gate.json；所有可统计包/脚本均达到 90%，无分支项标为不适用。包含未加载自有源码；仅排除生成的 jOOQ 类、第三方 node_modules 等，不排除自有业务逻辑。实际采集的 JaCoCo exec/XML/HTML、surefire-reports、Python JSON 和本轮 receipt 已导出，源码摘要 2d55cd906f8f1ceb80bdf1ac4f21ef8ae0a16ea01444dd52e65508dc6658b19f。

coverage-negative.json：缺 exec、空 XML、过期 HTML、缺 Java 包、低于阈值、缺 Python 源码、源码与 receipt 不同共七种实际负向门禁均拒绝；逐项恢复报告后门禁通过，未降低门槛或改写分母。

报告相对 backend 路径：.migration-reports/report-handoff/jacoco/index.html、jacoco/jacoco.xml、jacoco.exec、surefire-reports/、python-coverage.json、quality-gate.json、quality-receipt.json、coverage-negative.json。

## Compose、性能与回退

从 RvPort 根执行，COMPOSE_PARALLEL_LIMIT=1：

```powershell
docker build --target unit-test -t rvport-report-handoff-unit:final -f backend/Dockerfile --build-arg MAVEN_OPTS='-Xmx1536m -XX:ActiveProcessorCount=2' .
docker compose --env-file backend/.migration-reports/report-handoff/compose.env -f docker-compose.yml -f backend/.migration-reports/report-handoff/compose.override.yml -p rvport-report-handoff-1125 build
docker compose --env-file backend/.migration-reports/report-handoff/compose.env -f docker-compose.yml -f backend/.migration-reports/report-handoff/compose.override.yml -p rvport-report-handoff-1125 up -d --wait
```

独立覆盖文件调整资源隔离及缓存来源。私有 Dockerfile 仅将锁定第三方 Agent npm 依赖下载替换成缓存复制，并逐字节比较 lock、验证依赖树/受控补丁；LLVM bundle 使用固定摘要第三方缓存。Java、Python 资源、前端和分析工具均使用当前源码构建。旧业务镜像仅用于问题基线和回退读取，不代替本轮源码验收。没有前端或工具源码修改。

首轮同范围性能：完整 100 段共 15,671.79 ms，新接口 1 请求、预热后 5 次中位数 174.75 ms，命中集合完全一致。另九段局部基线合计 1,515.60 ms、单段中位数 160.16 ms；只覆盖 4,500 行，不与完整查找混算。API 容器内存采样 732.8 MiB/2 GiB。数字只代表本机 Docker、本次资源限制和文件。

本次无需 DDL，既有 JSON 增加可空独立字段，缺观测 UNKNOWN、缺展示快照 READ_TIME；不回填历史。基线 API 专用容器 rvport-report-handoff-rollback-1125 连接同一独立验收库，读取新报告 history/detail/review-items/download 全部通过（rollback-read.log），随后停止。未重建已有部署业务实例。

可复用 src/test/fixtures/report_handoff_acceptance.py，阶段 main/missing/permissions/ai-setup/ai/queue/recovered，必须设置 RVPORT_ACCEPTANCE_PROJECT=rvport-report-handoff-1125。客户端状态保存在专用容器 /tmp；受管 Codex 通过模拟上游工具调用读取受限源码并生成完整结果，这不代表真实外部模型质量评测。

领取故障通过专用 tasks BEFORE UPDATE 触发器实现，仅拒绝项目 d8143894-d8c7-4508-aef6-310bb2189f5e 的 REPORT_GENERATE 进入 RUNNING/FAILED；重试耗尽后观察 DLQ，移除本轮触发器/函数，等待 30 秒恢复周期。不修改真实队列或数据库。

实现期发现并修复：PostgreSQL 时间参数必须显式 ::timestamptz，增加真实数据库回归；任务执行后沙箱丢失需保留具体 TaskExecutionException 错误码；业务必填值缺失补齐英文说明。受限 Worker 切换后短暂混合观测 UNKNOWN 为预期，待旧观测过期再验收。首次重复消息夹具误带 requestedBy 被隔离，改为 createdAt 后验证合法重复投递；两类证据均保留。

最终镜像及最后一轮复验结果见下节。

## 最终收尾（2026-11-25）

最终源码 unit-test 镜像 sha256:84869ecf2067b160863fb6759861466bd65c7383ecc7e2d184f472287f04c28d，当前业务镜像 sha256:acfbaae567235fc719ff7c2a8189607c79e29d6b7f03efee3536ab22bb36f5bc。quality-accepted.log 是从该最终单测镜像新建 rvport-report-handoff-quality-accepted-1125（3 GiB、2 CPU）后实际 clean verify、Python 覆盖率及 receipt 门禁的通过记录，结果与上述质量表一致；导出报告已更新为这一轮。

compose-build-accepted.log 从根目录完成全部默认服务构建；compose-up-accepted.log 以一次 up -d --wait 启动全部默认服务。故障注入后 compose-up-restored.log 恢复正常 Worker 权限，六个配置 healthcheck 的服务 healthy，Worker running、心跳与真实能力观测 AVAILABLE（它本身没有 Docker healthcheck）。最终 API/Worker 日志无未处理异常，完整业务验收通过。

最后一轮主流程项目 fe1adbe1-64ca-4673-af7d-0e8d1a56a43a，原报告 f0d2b19d-5f43-4668-bc72-bf25b7c931ae，重新生成 bc214941-6fae-4f6b-a6a7-1a9311c8c8c0，旧下载摘要 fb4473aaf1e24cb2a91c29db754f52103c9fdbb512131b3ed6811b87b01df978 不变。标准生成、搜索、跨段新增、单次/批量操作、幂等、人工继承、确认、历史只读和权限/空确认全部重跑通过。此轮全文旧方式 100 请求 14,512.87 ms，新搜索单请求 5 次预热中位数 174.17 ms，命中集合不变。

最终 AI 报告 e0c5ab20-1390-40ae-80a5-2ebda6a4ef36，任务 881f5e9f-d080-4905-879b-d24d67c71ba6，5/5 条目、1/1 批次完整验证，execution=COMPLETED。排队后能力变化任务 1f5c1abd-ccee-47ac-aa3c-0ded79e3bc7f 明确 FAILED/REPORT_AI_SANDBOX_UNAVAILABLE；受限状态创建普通报告任务 787de7b0-ad8b-472e-99cd-7b68dba28a14 成功，零 AI 输入任务 94f68c66-3d2a-4455-b77b-3574f2f0c817 为 NOT_REQUIRED 且成功。恢复后当前能力 AVAILABLE。对应 capability-*-final.log、capability-zero-input.log、acceptance-*-final.log、acceptance-results.json。

合法重复死信处理后 DLQ=0，原任务只有一条 task.failed（recovery-duplicate-valid-state.txt）；之前的非法夹具仅保留在 .invalid 队列，不影响业务消息。故障触发器/函数均已删除，专用回退 API 已停止。所有操作限定本轮本地资源。

在 backend 根执行 openspec validate fix-report-review-boundaries-and-usability --strict 通过。42 项任务已完成，具备同步/归档条件；本轮未同步主规范、归档、commit 或 push。


## 规范同步与归档（2026-11-25）

用户授权后已同步 6 份主规范（新增 7 条、修改 2 条要求），保留其他既有要求及场景。42/42 任务完成，全部 45 份主规范严格校验通过。归档到 backend/openspec/changes/archive/2026-11-25-fix-report-review-boundaries-and-usability。本阶段仅修改规范文档并移动 change，代码与既有验证报告未改动，无需重跑代码覆盖率；保持 dev，未 commit/push。
