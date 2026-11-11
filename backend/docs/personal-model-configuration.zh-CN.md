# 个人模型配置迁移与验收记录

本文路径相对于仓库根目录。本变更仅修改 `backend/`；不修改前端，不访问远程环境，不自动提交或归档。

## 实施对应表

| 契约 | 主要实现位置 |
|---|---|
| 个人管理、绑定与测试 | `backend/src/main/java/cn/rvport/backend/modelprofile/ModelProfileController.java`、`ModelProfileService.java`、`ModelProfileRepository.java` |
| 平台受管目录与删除 admin 路由 | `backend/src/main/java/cn/rvport/backend/agentruntime/`、`security/SecurityConfiguration.java` |
| 配置主体与固定引用 | `backend/src/main/java/cn/rvport/backend/modelprofile/ModelConfigurationAccess.java`、`ModelExecutionSelection.java` |
| 知识库 AI 创建、readiness 和执行 | `backend/src/main/java/cn/rvport/backend/kb/ai/` |
| 自动比较继承测试批次触发者 | `backend/src/main/java/cn/rvport/backend/testcomparison/`、`automatedtest/` |
| worker probe 分派、恢复与归并 | `backend/src/main/java/cn/rvport/backend/modelprofile/CodexProbeDispatcher.java`、`CodexProbeJobRepository.java`、`CodexProbeRecovery.java` |
| admin 归属迁移与历史保护 | `backend/src/main/resources/db/changelog/changes/0130-personal-model-configuration.sql` |

## 验证范围与入口

- Java：容器内 `mvn -B clean verify`，JaCoCo 整体及完整受影响包的行/分支各至少 90%；只排除自动生成 jOOQ，不排除未加载业务源码。
- 受影响 Java 包包括 modelprofile、agentruntime、security、kb.ai、testcomparison 及其 runtime；以最终改动清单补齐 automatedtest、task、worker 等实际复用路径，不能缩小分母。
- Python：根开发指南的 coverage `--branch` 命令，source 包含 `src/main/resources/analysis,src/main/resources/bitcode,src/main/resources/agentruntime,agent-cli`，分别校验整体及脚本行/分支门槛。
- 数据库：本次独立网络 `rvport-personal-model-acceptance` 和数据库容器 `rvport-personal-model-db`，不暴露主机端口。迁移、代码生成与最终验证均使用当前 schema；bootstrap 工具镜像不是本次代码验收依据。
- 完整 Compose：在仓库根构建所有默认服务，以独立项目/env/网络/卷/端口执行一次 `docker compose up -d --wait`。前端仅随默认服务构建启动，个人模型页面适配不在本次范围。
- 原始日志及报告导出到 `.migration-reports/personal-model-configuration/`，不提交运行数据或凭据。

本轮 Java、Python、隔离数据库及完整本地 Compose 验收已通过。未修改前端，也未连接或修改远程环境。

本轮完整 Java `clean verify` 为 1893 项测试、0 失败、0 错误、5 项条件跳过；迁移测试另在启用数据库夹具后运行，4 项全部通过、无跳过。Java 统计 842 个自有生产类，排除自动生成 jOOQ；包含未加载源码。报告为 `.migration-reports/personal-model-configuration/surefire-final-03/`、`jacoco-final-03/`、`migration-final.log`。

| 范围 | 行 covered/total | 行覆盖率 | 分支 covered/total | 分支覆盖率 |
|---|---:|---:|---:|---:|
| Java 整体 | 24422/25197 | 96.924% | 9501/10375 | 91.576% |
| security | 124/126 | 98.413% | 29/30 | 96.667% |
| testcomparison.runtime | 424/431 | 98.376% | 183/198 | 92.424% |
| kb.ai | 575/587 | 97.956% | 189/206 | 91.748% |
| modelprofile | 1088/1124 | 96.797% | 439/485 | 90.515% |
| testcomparison | 367/382 | 96.073% | 119/129 | 92.248% |
| agentruntime | 267/269 | 99.257% | 49/54 | 90.741% |
| task | 1986/2065 | 96.174% | 674/746 | 90.349% |
| automatedtest | 370/372 | 99.462% | 127/140 | 90.714% |
| worker（复用执行路径） | 1018/1057 | 96.310% | 307/340 | 90.294% |
| Python 整体 | 1169/1196 | 97.742% | 500/523 | 95.602% |

Python 181 项测试通过，统计全部 7 个自有资源/CLI 脚本，未排除任何脚本行；整体及每个文件分别通过行、分支 90% 门禁。结果见同目录 `python-final.log`、`python-coverage.json`。Java 门禁由 `pom.xml` 的 JaCoCo 整体与包规则执行，Python 独立行/分支门禁沿用根开发指南的 JSON 校验命令。

从仓库根目录执行的本轮 Java 入口如下；完整日志保留实际 Maven 参数和退出结果：

```powershell
docker build --target unit-test -t rvport-user-scope-unit:local -f backend/Dockerfile .
docker run --name rvport-personal-model-unit-final-03 --memory=4g --cpus=2 -e MAVEN_OPTS=-Xmx1536m rvport-user-scope-unit:local
docker cp rvport-personal-model-unit-final-03:/workspace/backend/target/surefire-reports .migration-reports/personal-model-configuration/surefire-final-03
docker cp rvport-personal-model-unit-final-03:/workspace/backend/target/site/jacoco .migration-reports/personal-model-configuration/jacoco-final-03
```

完整 Compose 命令均从仓库根目录执行，使用本轮私有报告目录中的假凭据 env 和资源上限 override：

```powershell
docker compose --parallel 1 --env-file .migration-reports/personal-model-configuration/compose.env -f docker-compose.yml -f .migration-reports/personal-model-configuration/compose.override.yaml -p rvport-personal-model build frontend minio
docker compose --parallel 1 --env-file .migration-reports/personal-model-configuration/compose.env -f docker-compose.yml -f .migration-reports/personal-model-configuration/compose.override.yaml -p rvport-personal-model build backend
docker compose --env-file .migration-reports/personal-model-configuration/compose.env -f docker-compose.yml -f .migration-reports/personal-model-configuration/compose.override.yaml -p rvport-personal-model up -d --wait
docker exec rvport-personal-model-backend-1 python3 /tmp/fixtures/personal_model_acceptance.py api
docker exec -e RVPORT_ACCEPTANCE_PROJECT=rvport-personal-model rvport-personal-model-backend-1 java -Xmx512m -cp '/tmp/acceptance-java/classes:/tmp/acceptance-java/test-classes:/tmp/acceptance-java/lib/*' cn.rvport.backend.modelprofile.PersonalModelBusinessAcceptanceMain
```

本地构建遇到 LLVM 下载低速中断和 Ubuntu 官方源停滞，采用以下仅限本轮验收的依赖缓存措施，生产 Dockerfile 未修改：

- LLVM 21.1.8 最小包从已有本地镜像导出，SHA-256 `b456e58a78fc48d9f0cf3d767762840baf0346806f71d399d1dfffff58c5b489` 与当前 Dockerfile 一致；源包指纹同时一致。scratch 缓存镜像只包含这个第三方工具链。
- 使用已安装相同固定 LLVM Debian 版本的编译环境，清除旧自有分析工具、Python 包及数据，重新 COPY 并编译当前两个分析工具源码；固定 Python 第三方依赖校验版本后复用。产物的 version/protocol/doctor 检查通过。
- 在私有 Dockerfile 副本中仅将最后的 Python 开发包下载源改为清华 Ubuntu 镜像，保留包声明、APT 签名及 TLS 校验。Compose override 通过 additional_contexts 引用工具链缓存和本轮新编译的分析工具；Java、KB scanner、前端及 MinIO 均从当前源码构建。

上述副本、构建日志、源码 SHA-256 清单和完整镜像 ID 保存在同一私有报告目录。不能把旧应用镜像或旧分析工具产物作为当前代码的验收依据。

最终后端镜像为 `sha256:57d6786b1bdcbec9011c0bf537ee9a703d1ded924652d95b5a5f6f654def787b`，单元测试镜像为 `sha256:fcca5c8f0d11b5d57c30c512b4b3b3b85455260fcbf4bf91e767b68464f602fb`。修复验收发现的账号失效响应缓存头遗漏后，重新运行全量 Java 门禁、重建镜像，并清空本轮隔离数据卷，以一次 `up -d --wait` 重新启动全部 7 个默认服务进行最终验证。

最终验收结果：

- 6 个带 Docker healthcheck 的服务均 healthy；worker 无独立 Docker healthcheck，通过持续运行、数据库新鲜心跳和实际任务完成确认可用。日志没有非预期 ERROR；禁用 profile 重试产生的失败 WARN 为负例预期。
- A/B/admin 同名 profile、个人绑定、越权 CRUD/测试/run/矩阵读取、伪造 owner、禁止 CLI 命令覆盖、旧 admin 路由 404、禁用账号与 no-store 缓存头均通过。
- 实际 Codex CHAT、原生 RESPONSES、DeepSeek Harness 和 OpenCode 均通过。临时第二个 worker 验证 A/B 真实 CLI 请求区间重叠，地址和假密钥正确隔离；临时 worker 随后删除，不增加生产服务。
- KB AI 的普通用户触发、admin 手动重新触发、历史项目结果访问、自动测试比较派生及重复完成幂等通过；逐次核验实际请求属于预期触发者。
- 自动重试保持原主体及固定引用；排队改绑并禁用原 profile 不会改用新绑定，零上游调用。worker 停启、API 强制中断、到期 job 及个人 run 恢复通过；终态加密临时 payload 残留为 0，共享 runtime 个人摘要为 0。

证据文件包括 `compose-up-final.log`、`compose-health-final.json`、`compose-api-final.log`、`compose-business-final.log`、`compose-parallel-final.log`、`compose-retry-check-final.log`、`compose-disabled-check.log`、`compose-restart-check.log`、`compose-db-final.txt`、`api-final.json`、`business-final.json`。它们均位于本轮私有报告目录。

已按明确名称清理本轮 Compose 容器和数据卷、临时模型与第二个 worker、单元测试/数据库/codegen 容器及两个专用网络。`cleanup-final.log` 保留清理结果；复查没有本轮容器、数据卷或网络残留。已有本地部署未替换，构建镜像及私有报告保留供复核。

## 个人 API 与客户端迁移

下表中的旧路径已删除，不存在重定向或兼容处理器；认证及 CSRF 校验通过后返回 404。当前工程没有独立的静态 OpenAPI 定义或 springdoc 端点，Controller 路由注册表是接口来源。`PersonalModelRoutesTests` 校验全部旧方法及动作的路由不存在、404 和业务服务零调用。

| 旧路径 | 新路径 | 方法及动作 |
|---|---|---|
| `/api/admin/model-profiles` | `/api/me/model-profiles` | GET 分页、POST 创建；`/{id}` GET/PATCH/DELETE |
| `/api/admin/model-profiles/{id}:test` | `/api/me/model-profiles/{id}:test` | POST 单 Agent 测试，支持 `agentRuntimeId` |
| `/api/admin/model-profiles/{id}:test-connectivity` | `/api/me/model-profiles/{id}:test-connectivity` | POST 基础连通性 |
| `/api/admin/model-profiles/{id}:test-agents` | `/api/me/model-profiles/{id}:test-agents` | POST `agentRuntimeIds`，返回异步 run |
| `/api/admin/model-profiles/{id}/agent-probe-results` | `/api/me/model-profiles/{id}/agent-probe-results` | GET 个人 profile 最新兼容矩阵 |
| `/api/admin/model-profile-agent-test-runs/{id}` | `/api/me/model-profile-agent-test-runs/{id}` | GET 个人 run 与分项 |
| `/api/admin/model-stage-assignments` | `/api/me/model-stage-assignments` | GET/PATCH 两个业务阶段的个人绑定 |
| `/api/admin/agent-runtimes` | `/api/me/agent-runtimes` | GET 安全目录；`/{id}` GET |

普通用户、admin 均只能读写自己的模型、绑定及测试历史，admin 无越权特例；项目访问仍按原项目权限判断。平台 CLI 继续由镜像、安装清单和受管 runtime 维护。目录的 `executablePath`、`lastProbeStatus/Message/At` 为 null，`defaultArgs/config/lastProbeDetails` 为空；它们不代表当前用户的兼容性。profile 响应同样不提供 CLI 路径。个人请求拒绝 `ownerUserId`、`configurationUserId`、`executablePath`、`executable_path` 和命令覆盖。

创建例子：

```json
{"name":"个人模型","enabled":true,"modelProviderType":"OPENAI_COMPATIBLE","baseUrl":"https://model.example.invalid/v1","model":"example-model","wireApi":"CHAT","apiKey":"示例占位值","timeoutSeconds":60,"maxRuntimeSeconds":1800}
```

`apiKey` PATCH 缺省时保持原密钥，显式 null/空字符串清除，非空值加密更新；响应仅提供 `apiKeyConfigured`、`apiKeyMasked`，不返回原值。测试请求中的临时覆盖不更新配置；Codex CHAT 跨 worker 临时覆盖加密保存，终态、取消或超时清除。测试分项沿用 `message`/`message_en` 及原诊断结构。

绑定例子：

```json
{"assignments":[{"stage":"KB_AI_EXTENSION","modelProfileId":"本人-profile-UUID","agentRuntimeId":"平台-runtime-UUID"},{"stage":"TEST_LOG_COMPARE","modelProfileId":"本人-profile-UUID","agentRuntimeId":"平台-runtime-UUID"}]}
```

批次原子保存；重复或未知阶段、跨用户 profile、禁用 profile/runtime 均拒绝。个人名称大小写不敏感唯一，不同用户可以同名。轮询由 `:test-agents` 返回的 run ID，直到 `finishedAt` 非空；profile 的 `activeAgentProbeRun` 和 `agentProbeResults` 也只属于本人。跨用户 UUID 与不存在 UUID 均为 404；禁止通过其他账号 run ID 查询详情。

| 原因码 | 含义 |
|---|---|
| `MODEL_PROFILE_NOT_FOUND` / `MODEL_PROFILE_AGENT_PROBE_RUN_NOT_FOUND` | 本人资源不存在，包含越权 UUID |
| `MODEL_PROFILE_NOT_AVAILABLE` | 绑定目标不存在、归属不符或禁用 |
| `MODEL_PROFILE_ASSIGNED` | profile 仍被阶段引用，不能删除 |
| `MODEL_CONFIGURATION_USER_UNAVAILABLE` | 后台执行用户缺失、禁用或必须改密码 |
| `MODEL_EXECUTION_IDENTITY_MISSING` | 旧任务、损坏引用或冲突配置身份，需重新触发 |
| `TEST_COMPARE_MODEL_NOT_CONFIGURED` | 自动派生比较不可用；不修改两侧真实测试状态 |

个人响应由安全过滤链设置 `Cache-Control: no-cache, no-store, ...`。后端不维护跨用户的个人配置缓存。客户端后续需要个人中心入口、以 userId 分区的 query key、退出/换号取消请求及轮询、清除缓存和拒绝迟到响应；本次未修改或验证前端，旧页面仍需单独适配。

## 任务身份、重试与历史

业务任务输入的 `modelExecution` 仅包含配置主体、选定 profile/runtime UUID；任务 `created_by` 保持操作者审计含义。自动测试两侧任务显式保存 `configurationUserId`；比较派生要求其与双方任务操作者一致且项目一致。旧任务缺少显式主体时比较标记不可用，禁止按项目所有者或 admin 回退。父子关系沿用比较 run 的源/目标测试 run 外键及其 task ID。

任务创建时固定引用。领取/自动重试复核账号、项目资格、profile 归属和启用、runtime 启用，并重新读取选定 profile 的当前参数；本次调用使用同一解析快照。排队改绑不会换掉既有引用，凭据修复可在下一次尝试生效。`TOOL` 日志的 `MODEL_EXECUTION` 记录主体、UUID、profile/runtime 更新时间，不记录地址、密钥、完整参数或 prompt。兼容性结果的 `details.modelExecution` 保存同类安全版本信息；worker 已保存的实际领取快照不会被 API 归并时的新版本覆盖。重复完成通知不会重跑已派生比较，包括 FAILED/UNAVAILABLE；自动重试由原任务机制处理。

现有手动重新触发是创建新的 KB AI 执行或新的自动测试批次。新批次使用新操作者；同一旧批次的重复完成事件不是手动重新触发。本次不新增比较重跑 API。AI 项目产物继续按项目权限共享，只带必要执行引用，不返回触发者的完整个人配置；其他查看者缺少模型不会使历史项目结果失效。

## 发布、核验与回滚

1. 本地演练使用独立 Compose 项目 `rvport-personal-model`、独立网络和卷、假凭据与模拟模型；报告仅写 `.migration-reports/personal-model-configuration/`。不得将这些命令改指远程环境或既有业务数据库。
2. 正式发布操作需单独执行维护窗口：停止接收新 AI 任务、停止旧 worker 领取并排空 RUNNING。0130 迁移发现运行中的 AI/probe job 会事务失败，不能绕过校验。
3. 升级前做数据库及配套镜像备份。精确核验 username=`admin` 且为超级管理员；缺失或角色错误会失败。禁用 admin 可以承接历史所有权，但不会被重新启用。密钥密文、历史操作者、测试结果和时间不改写。
4. 0130 将旧模型、绑定、测试归属迁给 admin；旧排队 AI 任务失败，旧 probe job 取消并清除临时 payload。启动后核验旧消息不会调用上游；新普通用户为空配置，admin 可见迁移结果。重复启动不会重迁新个人数据。
5. 回滚必须先停止新 API/worker，保存升级后数据，再恢复升级前数据库与旧镜像的成套备份。在已产生个人数据的数据库上直接启动旧全局程序会破坏隔离，禁止采用。恢复备份会丢失其后的写入，须先导出留存并确认；没有无损自动降级承诺。本轮仅在本地隔离 schema 演练迁移失败回滚，不执行远程发布。
