# 主线推荐验收记录

状态：实现与本地验收完成，已于 2026-11-27 同步主规范并归档。实现验收日期：2026-11-26。

## 范围与隔离

- 保持 dev，实施基线 HEAD：`4267c2cc211398c389303babf5cea06777636303`，本次修改尚未提交。
- 仅修改 backend。根 README.zh-CN.md 和根 docs 中用户原有修改保留；未修改前端、工具源码或远程环境。
- 原始证据在仓库根 `.migration-reports/fix-workflow-mainline-recommendation/`，下称证据目录。凭据、会话、运行数据及日志不提交。
- 独立 Compose project 为 `rvport-mainline-1126`，网络为 `rvport-mainline-1126-infra`，命名卷均使用该 project 前缀。API/前端端口 30080/30081，PostgreSQL 30432、RabbitMQ 30672/30673、Redis 30379、MinIO 30090/30091；未连接现有部署数据或真实 SSH。
- 单测容器 2 CPU / 6 GiB，Maven 堆 1536 MiB、ActiveProcessorCount=2；API 2 CPU / 2 GiB、Worker 2 CPU / 4 GiB，其余服务按专用 override 限额运行。

## 最终测试与覆盖率

合并 Surefire 结果：**Java 1,994 项，0 失败、0 错误、0 跳过**；其中 1,986 项单元/条件数据库测试，另有 8 项显式数据库 IT。**Python 211 项全部通过**。IT 是补充证据，不替代单元测试。

| 范围 | 行覆盖率 | 分支覆盖率 |
|---|---:|---:|
| Java 整体（867 个类） | 96.6622%（24442/25286） | 91.6898%（10901/11889） |
| project | 98.6965%（530/537） | 94.9091%（261/275） |
| report/review | 94.6393%（1430/1511） | 90.8358%（1239/1364） |
| optimization（补充数据库夹具所在包） | 98.8889% | 94.8276% |
| Python 整体 | 98.1795%（1510/1538） | 96.2064%（634/659） |

新增 WorkflowRecommendationPolicy、ReportWorkflowService 行/分支均为 100%；ProjectWorkflowStatusService 为 194/195 行、149/159 分支；CurrentFixListResolver 为 24/24 行、28/28 分支。沿用整体、全部包及既有指定类门禁，没有降低标准或新增排除项。

Java 统计全量自有可执行类，含未加载类，仅排除 jOOQ 生成代码；Python 统计 analysis、bitcode、agentruntime、agent-cli 中全部自有源码，仅排除第三方 node_modules。最终源码 fingerprint：`14aa5857912a77fb658a2b6d5d7f046c95253747fe14cb10cfe7ef671afd931e`。

证据：`unit-reports/`、`jacoco/index.html`、`jacoco/jacoco.xml`、`jacoco.exec`、`python-coverage.json`、`coverage-receipt.json`、`coverage-final.json`。JaCoCo agent 实际加载，exec/XML/HTML 非空且包含新增类，时间戳及源码 fingerprint 门禁通过。

`coverage-negative.log` 记录在单测容器副本中删除、置空 JaCoCo XML，两次均退出 1；恢复原内容与时间戳后门禁通过。未修改项目源码。

## 单测实际执行

以下命令从仓库根执行，Maven、Python 测试及覆盖率全部在 Docker 内进行。

```powershell
docker build --target unit-test -t rvport-mainline-unit:final -f backend/Dockerfile .
docker cp backend/src/test/java/cn/rvport/backend/project/ProjectWorkflowStatusServiceTests.java rvport-mainline-unit-final:/workspace/backend/src/test/java/cn/rvport/backend/project/ProjectWorkflowStatusServiceTests.java
docker cp backend/src/test/java/cn/rvport/backend/optimization/OptimizationEvidenceDatabaseIT.java rvport-mainline-unit-final:/workspace/backend/src/test/java/cn/rvport/backend/optimization/OptimizationEvidenceDatabaseIT.java
docker exec rvport-mainline-unit-final python3 src/main/resources/analysis/coverage_gate.py start --receipt /tmp/mainline-receipt.json
docker exec -e RVPORT_TEST_JDBC_URL=jdbc:postgresql://postgres:5432/rvport -e RVPORT_TEST_DB_USER=rvport -e RVPORT_TEST_DB_PASSWORD=local-mainline-db rvport-mainline-unit-final mvn -B -s /opt/maven-cache/settings.xml '-Dmaven.repo.local=/opt/maven-cache/repository' '-Dtest=*,!KbScanDatabaseTests' clean verify
docker exec -e RVPORT_MIGRATION_TEST_JDBC_URL=jdbc:postgresql://postgres:5432/rvport_mainline_kb_unit rvport-mainline-unit-final mvn -B -s /opt/maven-cache/settings.xml '-Dmaven.repo.local=/opt/maven-cache/repository' '-Dtest=KbScanDatabaseTests' verify
docker exec -e RVPORT_FINALIZATION_DB_TEST=true -e RVPORT_FINALIZATION_JDBC_URL=jdbc:postgresql://postgres:5432/rvport_mainline_finalization rvport-mainline-unit-final mvn -B -s /opt/maven-cache/settings.xml '-Dmaven.repo.local=/opt/maven-cache/repository' '-Dtest=TaskAttemptDatabaseIT' verify
docker exec -e PYTHONPATH=/workspace/tools/memory-order-analyzer/python rvport-mainline-unit-final python3 -m coverage run --branch --source=src/main/resources/analysis,src/main/resources/bitcode,src/main/resources/agentruntime,agent-cli -m pytest -q src/test/python
docker exec rvport-mainline-unit-final python3 -m coverage json -o /tmp/python.json
docker exec rvport-mainline-unit-final python3 src/main/resources/analysis/coverage_gate.py verify --receipt /tmp/mainline-receipt.json --python-json /tmp/python.json
docker exec rvport-mainline-unit-final python3 /tmp/negative-gate.py
```

镜像构建后，将两份修正的测试复制到专用单测容器，再重新生成 receipt 并 clean verify；生产源码无后续修改。容器预设 `RVPORT_MIGRATION_TEST_ENABLED=true`、`RVPORT_REPORT_REVIEW_DB_TEST=true` 及独立数据库地址。命令中的密码仅是专用测试凭据，非部署凭据。

拆分执行原因与结果：KbScanDatabaseTests 的历史迁移按约束名判断存在性，会受同库其他 schema 影响，故实际运行于专用空数据库 `rvport_mainline_kb_unit`。TaskAttemptDatabaseIT 的 7 项以其环境开关在另一空数据库实际运行；OptimizationEvidenceDatabaseIT 已包含在完整轮。后两轮不 clean，保留其他 Surefire XML，JaCoCo append。最终合并无跳过，未删除或绕过有效测试。

旧构建夹具补齐服务端任务 inputSnapshot 中的批次身份及脚本摘要，验证双端构建、关联测试、比较结果和 REPEATABLE_READ；未修改优化闭环生产逻辑。日志为 `unit-accepted-final.log`、`unit-kb-final.log`、`task-attempt-database.log`、`python-final.log`。首轮失败日志保留，不作为最终通过证据。

## 完整 Compose

从仓库根完整构建全部默认服务，并以一次 up -d --wait 启动。验收仅暂停/启动专用 Worker 来固定排队窗口，最后再次 up -d --wait 恢复所有服务。

```powershell
docker compose --parallel 1 --env-file .migration-reports/fix-workflow-mainline-recommendation/compose.env -p rvport-mainline-1126 -f docker-compose.yml -f .migration-reports/fix-workflow-mainline-recommendation/compose.override.yml build
docker compose --env-file .migration-reports/fix-workflow-mainline-recommendation/compose.env -p rvport-mainline-1126 -f docker-compose.yml -f .migration-reports/fix-workflow-mainline-recommendation/compose.override.yml up -d --wait
docker compose --env-file .migration-reports/fix-workflow-mainline-recommendation/compose.env -p rvport-mainline-1126 -f docker-compose.yml -f .migration-reports/fix-workflow-mainline-recommendation/compose.override.yml ps
docker compose --env-file .migration-reports/fix-workflow-mainline-recommendation/compose.env -p rvport-mainline-1126 -f docker-compose.yml -f .migration-reports/fix-workflow-mainline-recommendation/compose.override.yml logs --no-color --tail 120
```

结果：backend、frontend、PostgreSQL、RabbitMQ、Redis、MinIO 六个 healthcheck 均 healthy；Worker 正常运行并完成实际任务（原 Compose 未定义其独立 healthcheck）。七个默认服务均在独立 project 内。

首次构建遇到 Ubuntu 包源重试及校验错误，改用临时 Dockerfile 和 additional contexts 复用固定第三方缓存。临时 Dockerfile 与当前项目 Dockerfile 仅两处 npm 下载块不同：复制依赖后比较 package-lock、执行 verify_lock，并保留 OpenCodex 补丁哈希校验。LLVM 21.1.8 bundle 实测 SHA-256 为 `b456e58a78fc48d9f0cf3d767762840baf0346806f71d399d1dfffff58c5b489`，与固定值一致。Java、前端及工具仍从当前源码构建，不以旧业务镜像代替当前构建。

证据：`compose-build-cached-deps.log`、`compose-up.log`、`compose-final-up.log`、`compose-final-health.txt`、`compose-final-logs.txt`、`final-images.txt`、`toolchain-cache-sha256.txt`。数据库日志中的约束失败属于数据库负向测试；实际业务窗口 API/Worker 无 ERROR/FATAL，四个业务任务均 SUCCEEDED，见 `api-business.log`、`worker-business.log`、`business-tasks.txt`。

## 实际 API 验收

临时 `http_acceptance.py` 仅访问独立 Compose API，没有调用真实模型或 SSH。执行 prepare、branch-queue、review、regenerate-queue、finish 五阶段全部 PASS；排队阶段前暂停专用 Worker、后续阶段前恢复。每阶段命令如下，仅最后一个参数依次替换为上述阶段名：

```powershell
docker exec -e RVPORT_ACCEPTANCE_PROJECT=rvport-mainline-1126 rvport-mainline-1126-backend-1 python3 /tmp/mainline-acceptance.py prepare
```

| 实际场景 | 结果 |
|---|---|
| 无源码、环境未配 | assets / NOT_GENERATED |
| 源码有效、未构建 x86 | kb / NOT_GENERATED，x86 optional=true |
| KB 完成、报告与专项同时就绪 | report / NOT_GENERATED |
| 浮点专项排队及实际 RUNNING | report / NOT_GENERATED，保留支线真实状态 |
| 普通报告成功 | report / PENDING_REVIEW |
| 固定源码连续读取及 500/501 行跨段新增 | 连续行号及人工条目写入成功 |
| 批量 INCLUDE 完成、尚未提交 | report / UNCONFIRMED |
| 手动确认非空清单 | manual / CONFIRMED_WITH_ITEMS |
| 单条 EXCLUDE 修改草稿 | report / NEEDS_CONFIRMATION |
| RESTORE 后重新确认 | manual / CONFIRMED_WITH_ITEMS |
| 新生成任务排队、旧清单有效 | report / GENERATING，旧 currentFixList 保持不变 |
| 新报告继承人工决定 | report / UNCONFIRMED，仍需手动提交 |
| 原幂等键重试 | 返回原 task ID |
| 历史报告 | 可读，写入返回 409 |
| 合法空清单且无验证环境 | env / CONFIRMED_EMPTY，build/test 未伪造 DONE |
| 更换源码版本 | kb / STALE，不借用旧确认 |
| 权限 | 本人可读、他人项目 404、管理员可读；普通用户管理接口 403 |

`api-evidence.json` 保存脱敏 overview 快照及任务/报告关联。M01–M18 全部通过 JSON 夹具及每场景 30 次确定性数组置换覆盖，上表为实际 HTTP 子集。缺失引用、失败/旧 Context 任务、合法 PARTIAL、空清单 build→test→optimize/archive、无动作 null 和拒绝访问查询等由单元测试覆盖。后续构建/测试身份另有真实 PostgreSQL IT；未创建真实 SSH 环境，也未写入伪造成功任务作为业务验收。

## 对接及最终检查

- 后端准确返回推荐；前端旧 KB→x86 改写规则未修改，页面仍可能覆盖该值。对接说明见后端工作流主线推荐契约，前端后续应原样消费 recommendedStageId。
- 从 backend 执行 `openspec validate fix-workflow-mainline-recommendation --strict` 通过；文档链接、任务完整性及 Git 范围检查通过。
- 无未解决的本轮实现或验收阻塞。2026-11-27 按用户授权完成 sync/archive，归档到 `openspec/changes/archive/2026-11-27-fix-workflow-mainline-recommendation/`；34 项任务全部完成，46 份主规范严格校验通过，归档移动的 9 个文件（含 `.openspec.yaml`）均通过 SHA-256 一致性校验。
- 本次同步归档仅变更规范文档，未重跑代码测试；上方数字保留为 2026-11-26 的实际验收证据。保持 dev，未 commit、未 push。此 change 的 OpenSpec 流程结束。
