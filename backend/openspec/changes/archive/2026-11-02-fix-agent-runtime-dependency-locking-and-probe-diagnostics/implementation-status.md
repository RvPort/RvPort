# OpenCodex 本轮实施与验收记录（2026-11-02）

本文件替代本目录此前的中间检查点；原 2026-10-30 归档保持不变。apply 已完成，41/41 项任务通过，清理及最终 OpenSpec strict 校验完成。2026-11-02 按用户授权同步三份主规范并归档本轮变更；未执行 commit 或 push。

## 实现范围

- 固定 OpenCodex 2.94.0、Bun 1.4.4；Codex 0.165.0、DSH 0.1.0-rc.8、OpenCode 1.18.18 保持冻结。代理独立完整依赖树、精确 lock、平台及二进制哈希受校验，两个空 volume 的冻结安装清单一致。
- 四项已授权补丁：缺少终态的 EOF 失败；非法 content 类型失败；缺失 usage 保留 null、真实零值不变；未知 finish_reason 在流式和非流式路径失败。全部三个目标文件先校验原始/最终 SHA-512，再写入；不接受未经校验的中间修补状态。
- Codex CHAT 使用 worker 执行级第三方代理，旧 Java 转换器已删除。自有代码负责配置快照、纯请求准入、预算、并发、监管、清理和脱敏诊断。原生 Responses、DSH、OpenCode 保持原路径。
- 管理探针通过持久 job 和现有 RabbitMQ 下沉 worker；消息仅 UUID，临时覆盖加密并在终态清除。领取时复核 profile/runtime，重复投递不重复模型执行，发布最多三次。
- Codex 仅解析 stdout，避免 stderr 提示词的示例 JSON 假成功。恢复同时归并 run 和 profile/runtime 摘要；保留原测试时间，取消/过期采用持久终态时间，旧结果不能覆盖新摘要，终态 run 不能被迟到线程改写。
- 真实 PostgreSQL 验收发现并修复截止时间的原生 SQL 参数类型问题，显式转换为 timestamptz；不以模拟数据库单测代替实际 SQL 验证。
- 保持 dev；无前端修改，无新增生产 Compose 代理服务，无远程 dev/test 操作。

## 源码与镜像标识

Git 基线 `107cf00bd6f5d7aa7ad13e7b15a724dca7bff104` 加本 change 未提交工作区。依赖锁与补丁脚本 SHA-256：

| 文件 | SHA-256 |
|---|---|
| agent-cli/package-lock.json | 8ce3285c011350aea515b2d5c233e4eea0f20aa283b0778e10833c05dd1810fc |
| agent-cli/opencodex/package-lock.json | ab708fdb93b46631d8cdbc3847951e891932624b3007ac2abee358f2ce90dde7 |
| agent-cli/opencodex/patch_eof.py | 3ca3e39f0dd0f066bbc50e99ee4667b75c560c6bb4f2cb8a9bef30052183a549 |

| 本地镜像 | 最终 image ID |
|---|---|
| rvport-ocx-backend:final | sha256:42ae1452d0ba9c3bca378583fe018ae6f849a193fb1984ccbc77514b36f73f71 |
| rvport-ocx-frontend:final | sha256:0c1b752d24d2cb7d24cebad11209d7e2a6bd7f28d7cc8dd33a6b4879ffb25f2c |
| rvport-ocx-minio:final | sha256:d1e820b357db4ce94f3e2ff755c7a4aa042bf4db032b06c35626ee63304933b3 |

最终镜像再次执行完整安装清单校验及补丁幂等校验，结果位于 `delivery-agent-manifest.json`、`delivery-opencodex-manifest.json`、`delivery-patches.json`。

## 单元测试及覆盖率

全部命令在本地 Docker 执行。最终 Java 容器限制 2 CPU / 2 GiB，Maven 堆上限 512 MiB；不设置会污染子进程精确输出的 JAVA_TOOL_OPTIONS：

```text
docker exec -e MAVEN_OPTS="-Xmx512m -XX:ActiveProcessorCount=2" -w /workspace/backend rvport-ocx-unit mvn -o -B -s /opt/maven-cache/settings.xml -Dmaven.repo.local=/opt/maven-cache/repository clean verify
```

`java-sql-delivery.log`：1861 项，0 失败、0 错误、2 项 opt-in 数据库测试跳过；退出 0，BUILD SUCCESS，全部 JaCoCo 门槛通过。专用 PostgreSQL 验收另行执行成功，不将跳过测试计为通过。

| 范围 | 行 covered/total | 分支 covered/total |
|---|---:|---:|
| Java 整体 | 24326/25105 | 9450/10317 |
| agentruntime | 286/288 | 61/66 |
| codexprovider | 509/529 | 231/253 |
| modelprofile | 1019/1056 | 415/457 |
| worker | 1018/1057 | 307/340 |
| queue | 115/119 | 19/20 |
| kb.ai | 563/578 | 179/198 |
| testcomparison.runtime | 416/423 | 180/194 |

整体行 96.90%、分支 91.60%，受影响完整模块均至少 90%。扫描全部自有生产 Java 类，包含未加载类；仅排除自动生成的 jOOQ。最终报告为 `jacoco-sql-delivery/index.html`、同目录 XML/CSV 及 `java-coverage-final-summary.json`；其他早期报告不是最终门槛结果。

```text
docker exec -e PYTHONPATH=/workspace/tools/memory-order-analyzer/python -w /workspace/backend rvport-ocx-unit python3 -m coverage run --branch --source=src/main/resources/analysis,src/main/resources/bitcode,src/main/resources/agentruntime,agent-cli -m pytest src/test/python -q
docker exec -w /workspace/backend rvport-ocx-unit python3 -m coverage json -o /tmp/python-final.json
```

`python-final.log`：181 项通过，退出 0。七个自有可执行 Python 文件全部纳入，包含未加载源码；整体行 1169/1196（97.74%），分支 500/523（95.60%）。

| 脚本 | 行 covered/total | 分支 covered/total |
|---|---:|---:|
| patch_eof.py | 56/57 | 18/19 |
| verify_lock.py | 64/65 | 41/42 |
| agent_process_supervisor.py | 65/66 | 21/22 |
| opencodex_guard.py | 189/192 | 110/113 |
| opencodex_runtime.py | 159/161 | 72/74 |
| prepare_specialized_analysis.py | 293/310 | 111/122 |
| produce_bitcode.py | 343/345 | 127/131 |

每个完整脚本行/分支均超过 90%，自动门槛记录在 `coverage-gates-final.txt`，完整报告 `python-final.json`。后续 Java 修复未改动这些脚本。排除第三方依赖、测试夹具和生成代码，不排除自有业务源码。

真实第三方补丁单元测试 `opencodex_patch.test.js`：20 项、67 个断言通过，三处修改涉及的可执行行 14/14 命中。使用已校验真实包的原样副本及同一冻结依赖，避免 Bun 默认忽略 node_modules；未替换第三方实现。命令：

```text
OPENCODEX_PACKAGE_ROOT=/opt/opencodex-patch-audit /opt/opencodex/node_modules/.bin/bun test src/test/fixtures/opencodex_patch.test.js --coverage --coverage-reporter=lcov --coverage-dir=/poc/patch-unit-delivery
```

证据：`patch-unit-delivery.log`、`patch-unit-delivery/lcov.info`、`patch-coverage-final.json`。Bun LCOV 不提供 BRDA，数值分支覆盖率不可用；新增条件两侧均有明确断言。未修改的第三方实现不计为项目自有源码，不把安装器覆盖率冒充补丁逻辑覆盖率。

前端没有修改。容器执行 `pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm test --maxWorkers=2 && pnpm build`，全部退出 0：128 个文件、697 项测试通过。报告 `frontend-delivery-final.log`；沿用前端覆盖率例外。jsdom/CodeMirror 的浏览器 API 提示及 Vite 大 chunk 提示不导致失败。

## 构建方式与本地环境问题

全部默认服务从仓库根目录按模块顺序构建，使用专用 env、资源上限、网络、卷及 loopback 端口。完整 backend 工具链构建、frontend 和 MinIO 构建分别见 `build-backend-export.log`、`build-frontend.log`、`build-minio-final-2.log`。

Docker Desktop 中断对应 Windows System 2004 虚拟内存不足事件；中断的构建/测试不算成功。导出层逐一验证解压后的 diff ID 后添加验收标记并导入，避免复用零字节损坏 snapshot。Go 下载缓存也发现零字节文件，仅清理本轮构建器的 Go 缓存后重建，原 checksum 验证保持启用。

恢复一致性及 SQL 修复后的重复完整导出被自动审批拦截（仅返回 blocked by policy），因此最终 Java 层采取较小的本地构建：以本轮已完整构建并验证的相同工具链镜像为基础，仅 COPY 当前源码完整 `mvn clean verify` 产出的应用 JAR；由仓库根 Compose 和私有 app-overlay.yml 构建。没有用原来的历史镜像替代新代码。最终装配日志 `build-backend-sql-overlay.log`，JAR 位于私有 `app-overlay/rvport-backend.jar`。生产 Dockerfile 仍可按正常完整流程构建当前源码。

## 完整 Compose 与真实路径验收

本轮项目名 `rvport-ocx-final`，网络 `rvport-ocx-final-infra`，数据卷全部为该项目前缀。按根 Compose 的 external 网络约定预创建专用网络，然后一次命令启动全部默认服务：

```text
docker compose --env-file D:/projects/RvPort/.migration-reports/fix-agent-runtime-dependency-locking-and-probe-diagnostics-opencodex/compose.env -f docker-compose.yml -f D:/projects/RvPort/.migration-reports/fix-agent-runtime-dependency-locking-and-probe-diagnostics-opencodex/compose.override.yml up -d --wait
```

最终启动及恢复日志 `compose-up-final.log`、`compose-restored.log` 均退出 0。七个默认服务就绪，frontend HTTP 通过。无新增生产代理服务/主机端口；临时 agent-mock 仅是隔离网络中的验收夹具，未发布主机端口。

| 验收 | 结果与最终证据 |
|---|---|
| 正常 API | `compose-api-final.log`、`agent-api-acceptance.json`：动态创建/修改 profile、原生/CHAT 连通性、三个 Agent 的文本/读写、不同 URL/key、超时恢复、历史记录保持、旧接口临时覆盖、权限拒绝全部通过 |
| PostgreSQL | `database-acceptance-final.log`：原子领取、重复/迟到结果、所有权、过期、取消、有限发布、秘密清除、唯一约束、旧摘要拒绝、终态 run 不可改写全部通过；夹具事务回滚 |
| 业务绑定 | `business-stage-database.log`：读取正常 API 保存的 KB_AI_EXTENSION / TEST_LOG_COMPARE 绑定，通过真实 repository/cipher 和 Agent Adapter 完成实际工具与输出文件校验；使用本地业务输入，不访问 SSH，不声称执行了完整远程项目流水线 |
| 停机/恢复 | `recovery-offline.log`、`recovery-disabled.log`、`recovery-restart.log`、`probe-recovery-acceptance.json`：worker 离线混合部分成功/旧接口有界失败，领取前禁用无上游调用，API 强制中断后过期 run 及摘要正确收敛 |
| 真实 CLI 故障 | `delivery-failures/outcomes.json`：12/12，401/403/429/5xx、慢响应、断流、畸形、纯推理、未知 finish_reason、非法 content、截断 JSON、取消；全部证明到达模拟上游，有限失败且清理 |
| 最终协议门 | `delivery-protocol/results.json`：18/18，真实/缺失 usage、SSE/UTF-8/多工具、终态/截断、无关联工具、token/模型/管理路径/预算、跨源重定向无凭据转发 |
| 最终自定义工具/动态并发 | `delivery-custom`、`delivery-missing-usage`、`delivery-concurrent`：真实 custom apply_patch 文件结果、缺失 usage 下三项 probe、不同 URL/key 同时执行全部通过 |

可重复执行的容器内命令、fixture 编译和故障编排见 [验收夹具说明](../../../../src/test/fixtures/README.md)。上述隔离验收只使用本地模拟上游和假凭据，未验证真实外部模型；后续用户授权的本地部署复测单独记录如下。

最终镜像在 2 CPU / 1536 MiB 容器采样：单实例就绪 1978 ms、RSS 252071936 字节、CPU 2.01 秒、清理 65 ms；双实例就绪 3158 ms、RSS 435273728 字节、CPU 4.49 秒、清理 64 ms。目录及监听均回收，保留默认最多两实例且不超过 worker concurrencyLimit。报告 `delivery-resources.json`。

日志未发现 ERROR；Java 25/Netty 原生访问提示保留为既有非致命警告。API 角色无代理，worker/CLI 验收容器无 Node/Bun/Codex/DSH/OpenCode 残留；probe 队列消息为 0，终态 job 的临时加密 payload 为 0。证据 `api-runtime.log`、`worker-runtime.log`、`queue-final.txt`、`probe-job-cleanup.txt`。

所有原始证据位于私有目录 `D:/projects/RvPort/.migration-reports/fix-agent-runtime-dependency-locking-and-probe-diagnostics-opencodex`，不纳入 Git。本轮容器、网络、卷、构建器及运行 env 已清理，保留本地候选镜像和仅含本地假数据的私有验收记录。清理记录 `compose-cleanup.log`、`cleanup-final.json`；最终 strict 结果 `openspec-validation-final.log`。分支仍为 dev，前端无变更。

## 后续本地部署与一分钟探针复测

2026-11-02，按用户另行授权，将已有本地部署的 backend 和 worker 替换为本轮已验收镜像，保留原有数据并先备份数据库；健康检查、数据库迁移和 worker 心跳正常。该部署供用户继续验证，不属于已清理的隔离验收资源，远程 dev/test 未修改或测试。

随后通过本地管理页面将当前 profile 的 `timeoutSeconds` 从 30 改为 60，`maxRuntimeSeconds` 保持 1800，并重新执行三个 Agent 的通用兼容性测试。此次使用已配置的真实模型，所有进程均运行于本地容器；Codex 使用 profile 已配置的原生 Responses 路径，因此本次结果不作为 OpenCodex CHAT 转换的真实模型验收证据。

| Agent | 文本耗时（秒） | 读取耗时（秒） | 写入耗时（秒） | 结果 |
|---|---:|---:|---:|---|
| Codex | 10.488 | 18.885 | 29.733 | 三项通过 |
| DeepSeek Harness | 11.016 | 17.908 | 29.058 | 三项通过 |
| OpenCode | 10.359 | 11.333 | 33.650 | 三项通过 |

九项诊断预算均为 60000 ms，run 最终为 SUCCESS。此操作仅调整本地已保存 profile，不改变全局默认超时、不拆分现有执行预算，也不改写原有失败记录。部署记录与复测截图位于私有目录 `.migration-reports/local-agent-runtime-update-20261102/`，不纳入公开源码。
