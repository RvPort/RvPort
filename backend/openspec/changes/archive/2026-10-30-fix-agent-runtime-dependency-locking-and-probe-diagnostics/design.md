## Context

2026-10-29 已完成的探索发现：

- dev 前端为 8081、后端为 8080；test 使用 18081/18080。后续远程 dev/test 只允许只读排查，禁止安装、测试、创建临时容器、改配置、改数据或部署。先前探索的临时容器已清理，不能作为后续远程测试授权。
- 远程实际 DSH 主包为 `0.1.0-rc.7`，185 个 DSH 组件为 `0.1.0-rc.8`，Cordis 为 `4.0.5`、HMR 为 `1.0.20`。`^` 依赖允许上述漂移；这证明安装不固定，不直接证明混合版本就是 HMR 的唯一根因。
- 本地现有镜像可复现 `user patch-layer watching requires the Cordis HMR service`；远程与本地版本命令均可成功。增加 `--expose-internals` 的先前隔离试验没有解决 HMR 故障，不能将该参数当作已验证修复。
- 本地 `rc.8` 全新安装在 npm 依赖解析中耗尽约 1 GiB Node 堆；试验容器内存限额 2 GiB。尚未完成 rc.8 启动验收，不能报告升级已修复。
- GLM-5.3 profile 配置 `RESPONSES`、单项超时 30 秒。Codex 基础文本分项约 40 秒后超时，文件读取约 9 秒、写入约 27 秒均成功；OpenCode 三项通过。此次历史超时不能归因于不支持 Responses，也不能证明 Codex 未安装。
- `CodexProviderProbeService.runCheck` 在 `timedOut` 分支直接生成通用文本，未使用进程已收集的诊断；进程执行器只强杀直接进程，分别等待 I/O future，实际耗时可超过执行预算。
- 当前 `CodexAgentCommandFactory` 将 profile 的 `wireApi.codexValue()` 直接传给 CLI；本地枚举包含 `CHAT("chat")`，而最新 Codex 已移除 chat wire API。

本 change 只创建 backend 独立 OpenSpec 变更。实现保持在 `dev` 开发，除非用户另有要求，不创建主题分支。本文定义方案及验收，不代表已安装新版本、完成代理实现或部署。

### 已核实的版本与协议依据

2026-10-29 从 npm `@openai/codex` 的 latest 元数据和 GitHub latest 非预发布记录交叉核实，最新稳定版为 `0.165.0`，发布于 2026-10-27。官方配置参考与该 release 源码一致：provider 仅支持 `responses`；官方 `codex-responses-api-proxy` 转发 Responses，并不转换 Chat Completions。

- [Codex 官方配置参考](https://learn.chatgpt.com/docs/config-file/config-reference)
- [Codex 0.165.0 release](https://github.com/openai/codex/releases/tag/rust-v0.165.0)
- [固定 release 的 provider 协议实现](https://github.com/openai/codex/blob/rust-v0.165.0/codex-rs/model-provider-info/src/lib.rs)
- [固定 release 的官方 Responses 转发代理说明](https://github.com/openai/codex/blob/rust-v0.165.0/codex-rs/responses-api-proxy/README.md)
- [DSH rc.8 发布元数据](https://registry.npmjs.org/@deepseek-ai%2fdsh/0.1.0-rc.8)
- [npm 锁文件定义](https://docs.npmjs.com/cli/v11/configuring-npm/package-lock-json/)
- [npm ci 安装约束](https://docs.npmjs.com/cli/v11/commands/npm-ci/)

实施开始时再次核对最新稳定 Codex；如已有更新，先将精确版本、官方来源和协议差异更新到本设计，再生成版本清单，不在每次镜像构建中重新解析 latest。

## Goals / Non-Goals

**Goals:**

- 使同一 CLI 清单和锁文件能重复安装同一依赖树，并可追溯实际版本、平台及完整性校验值。
- 验证并修复 DSH rc.8 的真实 headless 启动，不以版本输出替代运行验证。
- 升级并锁定最新稳定 Codex，使 RESPONSES 原生上游与仅 CHAT 上游均有明确、可测试的受控调用路径。
- 为 Codex 所需文本与工具工作流提供正确的协议转换，复用到 probe、`KB_AI_EXTENSION` 与 `TEST_LOG_COMPARE`。
- 输出安全、有限、能区分执行/上游/协议/清理问题的诊断，同时保证超时和取消不会泄漏进程、端口及跨任务凭据。
- 在本地 Docker 完成独立单元测试、全量/受影响模块覆盖率、CLI 协议验证和完整 Compose 验收。

**Non-Goals:**

- 不修改远程 dev/test，不部署任何代理或升级远程 CLI，不使用远程环境执行任何测试。
- 不新增面向公网的通用代理、任意目标转发、模型聚合平台、账户轮换或自动协议探测降级。
- 不承诺把所有 Responses 功能无损转换成 Chat Completions；不支持的能力必须明确报错。
- 不修改前端，不伪造历史 probe 成功，不把上下文窗口、模型质量、工具能力差异归结为单一接口兼容。
- 不新增 CI/E2E 或真实 SSH 测试设施，不扩大到无关工具代码，不改变阶段绑定权限。

## Decisions

### 1. 精确主版本与完整依赖树同时锁定

在 backend 内建立独立 Agent CLI 依赖目录，提交 `package.json`、`package-lock.json` 与版本/来源/平台说明。DSH 根依赖明确为 `0.1.0-rc.8`；相关 DSH 组件选择经验证的 rc.8 组合，Cordis、HMR、timer、loader 等按各自发布版本精确锁定，不把不同发布体系强行改成 rc.8。包含 optional/peer/platform 依赖，不能只列一级依赖。

Docker 使用 `npm ci` 在受管目录安装并提供 CLI 入口，取代不读取项目锁文件的 `npm install -g` 路径。固定 Node/npm 版本与目标平台，保存并核对包完整性和实际解析树，检测额外、缺失和版本不一致。Codex 可以保留官方 wrapper + 平台 tarball 安装方式，但两者必须相同精确版本并校验完整性；其他 CLI 若共用清单，保留现有 OpenCode 精确版本并回归，不顺便升级。

锁文件在本地 Docker 生成。针对 npm 内存失败，先诊断 peer 依赖解析及可用堆/容器预算，在资源受限的合理预算下重复成功；不默认使用 `--force` 或 `--legacy-peer-deps` 绕过契约。必要 overrides 仅用于经证实的兼容版本，记录原因及测试。两次空安装比较规范化依赖清单，第二次不得更新锁文件。

备选方案：仅修改 Dockerfile 的版本常量不解决传递依赖漂移；冻结整个 node_modules 不利于审查和平台复用，均不采用。

### 2. DSH 启动问题先复现再修复

本地以旧锁定/现有镜像基线和新的 rc.8 候选作对照，记录 headless profile、provider patch、HMR/loader/timer 的激活顺序、错误链和 Node 能力。使用本地模拟模型服务和假凭据完成普通文本、工具读取、文件写入及正常退出，验证 HMR 故障在模型请求前还是之后发生。

优先采用固定发布组合和官方支持的启动方式。若 rc.8 本身仍有缺陷，实施中保留失败证据并更新设计选择；不能静默忽略 HMR 异常、吞掉启动错误、热改远程 node_modules，或以通过 `--help/--version` 完成任务。任何必须维护的兼容补丁应有固定上游依据、清晰位置和回归测试，再进入镜像构建。

### 3. 协议路由由配置决定

profile 的 `wireApi` 描述管理员配置的上游 API，不等于 CLI 的 wire API。保持数据库枚举和 API 接受的 `RESPONSES`、`CHAT`，不把既有 CHAT 数据迁移为 RESPONSES。

| 调用方/上游 | 调用路径 |
|---|---|
| 模型连通性 + RESPONSES | 直接调用该 profile 的 `/responses` |
| 模型连通性 + CHAT | 直接调用该 profile 的 `/chat/completions` |
| Codex + RESPONSES | `wire_api=responses`，连接原始上游 |
| Codex + CHAT | `wire_api=responses`，连接本次执行的本地转换层，再调用原始 CHAT 上游 |
| DSH / OpenCode | 保留各 Adapter 的原生协议支持边界，分别回归现有路径，不因 Codex 转换改写它们的配置 |

不能根据一次超时、401、404 或 5xx 自动更改协议、模型或 API key。native 与 bridge 路径的选择可在安全诊断中识别，但无需前端新增代理配置。连通性成功仅证明对应上游路径可用；Agent 兼容仍由实际三项 probe 验证。

### 4. 使用后端管理的执行级 loopback 转换层

默认在现有 JVM 内实现小范围、可独立单测的转换模块（例如 `agentruntime.bridge`），由 `CodexAgentRuntimeAdapter` 在 CHAT 执行开始时创建、在 finally 中关闭。监听当前容器 `127.0.0.1` 的动态端口，以本次随机短期 token 认证，持有不可变 profile 快照；无额外 Compose 常驻服务或主机端口。backend 与 worker 都能从同一镜像使用，业务阶段不直接管理代理。

```text
CODEX + RESPONSES ───────────────────────► profile Responses 上游
CODEX + CHAT ─► 执行级 loopback bridge ──► profile Chat Completions 上游
               Responses HTTP/SSE          Chat HTTP/SSE
```

CHAT 路径仅把 bridge 的 token/base URL 暴露给 Codex 子进程；真实上游密钥保留在 backend 内存。上游固定为执行时解析的 profile，客户端不能提供任意 upstream URL、模型覆盖或授权头；不跟随携带凭据的跨源重定向。临时配置权限收紧，不保存真实密钥或请求正文日志。对并发执行隔离端口、token、取消信号和上下文，关闭后旧 token 失效。

备选方案：官方 `codex-responses-api-proxy` 没有协议转换能力，不能解决问题；完整第三方网关会增加本次部署与配置面，当前不作为默认依赖。若实施验证证明自有受限转换无法满足所锁 Codex 的实际协议，先更新方案并明确网关依赖、许可证、版本和部署影响，不能临时接入不受控服务。

### 5. 明确转换范围，不做路径字符串替换

先用目标版本 Codex 和本地 recording mock 捕获非敏感协议样本，固化以下映射契约；模拟器只属于测试夹具，不作为新产品功能。

| 类别 | 必须验证的语义 |
|---|---|
| 输入 | instructions、system/developer/user/assistant 文本及多轮顺序；完整 input 与工具结果不能丢失或压成单条 user 文本 |
| 工具 | function 定义、参数 schema、tool_choice、并行调用；name/call_id/id 与后续 tool result 精确对应 |
| 自定义工具 | 目标 Codex 如发送 custom/freeform 工具，必须提供经过验证的显式映射（如封装为单字符串参数 function）并恢复原始调用类型；不支持的格式/约束明确拒绝 |
| 输出 | Responses 消息、内容、工具调用、完成/失败/不完整状态、finish_reason 和有来源的 usage；不得伪造 usage 或把截断当成成功 |
| 流式 | SSE created/in-progress、item/content、文本及工具参数 delta、done/completed 或 failed；事件 ID/index/顺序一致，UTF-8 与 JSON 参数跨分块、交错多工具均可重建 |
| 后续轮次 | 工具执行后再请求仍包含正确上下文；不能丢失前一轮 assistant 工具调用或错配 tool_call_id |
| 失败 | 鉴权、限流、网络超时、非 JSON/畸形 SSE、连接中断、无终止事件分别有明确受控结果；已开始流式响应后使用失败事件并关闭流 |

桥接范围为当前两个受控业务阶段及通用 probe 所需的文本/工具能力。默认采用完整输入、`store=false` 的无状态请求；若目标 CLI 发出依赖 `previous_response_id` 的续接、加密 reasoning、远端 compaction、WebSocket、后台任务、图像/音频或托管工具请求，先在受支持配置中禁用不需要的能力，剩余无法等价表示的请求返回明确不支持错误。不能以丢字段方式假装支持。目标 CLI 必须完成当前工作流，否则桥接验收未完成。

CHAT 上游不支持工具调用时，连通性可以成功，但工具兼容 probe 必须失败。桥接不补造模型工具能力，不替模型执行工作区命令。

### 6. 统一预算、取消与安全诊断

保留现有 profile `timeoutSeconds` 和业务阶段 `maxRuntimeSeconds` 的作用范围，单项 probe 仍有明确执行预算；不通过无条件放大超时或静默重试把历史失败隐藏为成功。CLI 启动、bridge 连接与上游请求使用同一单项截止时间，重试最多使用剩余预算；没有预算时直接失败。清理有独立、短而有限的宽限时间，分别记录执行耗时与清理耗时。

超时/中断时终止本次拥有的整个进程树（含 wrapper 启动的原生 Codex），关闭 stdin/输出流、取消上游 HTTP、关闭 bridge、终止 I/O future 并清理 workspace。不影响其他任务进程，不能靠依次额外等待多个 future 导致无说明的耗时放大。统一结果明确 cleanup 是否完成；清理失败不能把残留资源隐藏为正常完成。

保留稳定的 `AGENT_PROBE_TIMEOUT` 等错误码，增量 details 包含阶段、预算、总耗时、执行/清理耗时、CLI 精确版本、native/bridge 路由、退出/超时/取消标记及有界诊断。对外 message 与新增展示字段提供中文/英文。诊断先脱敏、再截断；正文/完整 prompt、API key、Authorization、代理 token、敏感 URL 查询串、服务器真实路径和堆栈不得进入公共响应。对上游错误正文同样处理，不返回完整 stderr。

三项分项独立保存；基础文本失败不能被后续读写成功覆盖，后续成功也不能被描述为从未执行。历史记录保持原样，修复后重新探测写入新记录；对历史 Codex 超时只说明已知事实，不能回填推测原因。

### 7. 测试、覆盖率与本地验收

- 独立单元测试使用 mock、确定性时钟及字节/事件夹具，覆盖锁清单校验、版本解析、路由、转换纯逻辑、异常分类、脱敏、预算、进程资源归属与清理。测试运行在本地 Docker，不连接实际模型、远程 SSH 或部署数据库。
- 使用本地隔离模拟服务补充真实 CLI 测试：Codex native RESPONSES、Codex CHAT bridge、DSH rc.8、OpenCode 回归。完成三项 probe、工具调用/回传、连续多轮与流式错误；CLI 集成验证不替代单元测试。
- 整体范围包括 backend 全部自有 Java 及资源脚本，未加载源码纳入；受影响完整模块包括 `agentruntime`、新增 bridge、`codexprovider`、`modelprofile`、实际触及的 `worker`/取消组件，以及 `kb.ai`、`testcomparison.runtime` 等复用模块。按实际改动更新模块清单，不只统计变更行。
- 行和分支分别至少 90%，保留既有更严格门槛；新增自有安装/校验脚本必须同步有单元测试和适用覆盖率。第三方与生成代码可按现有理由排除，自有业务逻辑不能排除。
- 从当前候选源码构建并运行本地完整 Maven `clean verify`、资源 Python 测试和门槛；根目录全部默认服务 Compose 顺序构建后，一次 `up -d --wait` 启动，使用专用 env、网络、卷、端口、CPU/内存预算。前端仅执行既有 lint、类型检查、单元测试和构建，保持代码零修改及现有覆盖率例外。
- 本地模型模拟器只作为隔离测试依赖，不能加入生产默认服务；运行验收通过正常 API 验证 profile/探测/历史/权限/协议路由。真实上游验证如有授权也只从本地执行，缺少凭据不影响 mock 覆盖，但报告不得宣称完成真实上游验收。
- 记录源码标识、CLI/锁文件/镜像版本、完整命令、退出码、测试数量、covered/total、排除项、服务状态和清理记录到本地 `.migration-reports/fix-agent-runtime-dependency-locking-and-probe-diagnostics/`；不提交凭据、日志或环境文件。

## Risks / Trade-offs

- [rc.8 本身仍有启动缺陷] → 保留本地对照实验，定位确切依赖/初始化问题；未修复不勾选启动验收。
- [npm peer 解析耗时或堆溢出] → 诊断解析与资源预算并固定安装工具链；验证两次干净安装，不绕过 peer 契约。
- [新 Codex 产生额外协议类型] → 固定版本采集本地协议夹具，能力白名单和明确错误；不能只测文本或只改 URL。
- [自定义工具与普通 function 不等价] → 分别覆盖定义、流式参数及返回链；无法无损表达的约束拒绝并记录限制。
- [同一 profile 并发导致串用密钥/上下文] → 执行级不可变快照、token、端口及取消句柄隔离，并发回归。
- [增加转换层延迟与维护成本] → 原生 Responses 不经过桥接；转换模块单独单测，使用有界流缓冲和总预算。
- [超时诊断泄漏输出] → 不返回原始流，统一脱敏、路径/正文清洗、限长和双语分类测试。
- [旧共享模块覆盖率缺口过大] → 报告精确缺口并请求扩大范围，不自行改造无关模块或降低门槛。

## Migration Plan

1. 本地固定目标版本和能力矩阵，补充单元测试及协议夹具，再实现锁定安装、DSH 启动修复、Codex 路由/桥接和诊断。
2. 完成本地完整质量验收后交付代码与安装清单；本 change 不自动升级远程 dev/test。部署需用户另行授权，backend/worker 使用同一已验收镜像。
3. 无计划新增 profile 枚举或重写历史结果；CHAT profile 自动选择受控桥接，RESPONSES 保留直连。增量诊断字段保持旧客户端可读。
4. 回滚到上一镜像及对应锁清单；停止时回收所有进程/桥接资源。回滚会恢复旧 Codex/DSH 限制，不能承诺旧版 CHAT 配置可运行，也不篡改新旧探测记录。

## Open Questions

- rc.8 的 HMR 故障具体来自依赖组合、激活时序还是上游实现？这是本地实施的首个验证门，不预设答案。
- 最新稳定 Codex 实际会对这些工作流发送哪些 custom tool、续接或 compaction 字段？以固定版本和本地 recording mock 冻结受支持矩阵后实现；无法完成既定工作流时须更新方案，不能缩减验收。
- 本地 npm 干净安装的合理内存和耗时预算需在锁文件生成阶段实测并记录。

这些问题均已进入任务清单，不需要连接远程运行试验；不阻塞提案就绪，但未解决时阻塞实现验收。

## 实施阶段确认的协议与运行细节（2026-10-29）

- 固定 Codex 0.165.0 的录制证明：禁用 reasoning 后仍发送 `include=["reasoning.encrypted_content"]`。该字段是可选输出投影，并非带密文的输入；桥接不生成 reasoning item，允许此投影，仍拒绝实际 encrypted_content 和 reasoning 历史。这不改变无状态完整 input 的设计。
- Codex apply_patch 使用 custom + Lark grammar。实现保留冻结的完整 grammar，映射为单 input 字符串的 function；返回值经覆盖全部语法产生式的本地验证后才恢复 custom_tool_call。其他 grammar 明确拒绝。真实 CLI 已完成 apply_patch 文件写入往返。
- npm 对 optional 平台下载失败可能成功退出，Codex Linux x64 平台包因此提升为精确版本的必需根 alias；冻结安装后核验工具链、平台与全树。两次空安装均为 462 个包，锁和清单一致。
- Linux 进程执行增加 Python subreaper，以回收 wrapper 提前退出后转移的孙进程；仅跟踪自身后代，单独 2 秒清理宽限。Python 自有代码加入完整行/分支覆盖率门禁。
- 诊断采用固定白名单事实，原始输出全部不进入公共响应；这是更严格的脱敏策略，避免跨块密钥或任意上游正文绕过字符串替换。中断/启动异常未获得进程测量时 cleanup=UNKNOWN。
