# Agent CLI 冻结安装

本目录固定 backend/worker 共用镜像的 CLI。安装、实验及验收只在本地 Docker 中执行；远程 dev/test 不创建测试资源，不安装、不测试、不部署。开发保留 `dev` 分支。

## 版本与来源

2026-10-29 核对 Codex 官方 release 与 npm latest，目标稳定版为 **0.165.0**。DSH 按本次需求固定 **0.1.0-rc.8**，OpenCode 保持 **1.18.18**。Node 为 **22.23.2**，随附 npm 为 **10.9.8**；当前交付平台为 Linux x64。

- [Codex release](https://github.com/openai/codex/releases/tag/rust-v0.165.0)
- [Codex npm 元数据](https://registry.npmjs.org/@openai%2fcodex/0.165.0)
- [DSH rc.8 元数据](https://registry.npmjs.org/@deepseek-ai%2fdsh/0.1.0-rc.8)
- [HMR 1.0.17 元数据](https://registry.npmjs.org/@deepseek-ai%2fcordis-plugin-hmr/1.0.17)

`package-lock.json` 包含传递、peer、optional 及各平台包的精确版本、官方 registry 地址与 SHA-512 摘要。Codex wrapper 与 Linux 平台包必须同时存在且版本匹配。npm 对 optional 下载失败可能返回成功，因此 `npm ci` 后必须运行 `verify_lock.py`，不能仅凭安装退出码完成验收。平台不匹配的 optional 包无需安装，其他锁定包缺失、版本不同或出现额外包均失败。

DSH 的 peer 图较大，裸根包解析曾耗尽约 1 GiB V8 堆。本清单显式固定 DSH 的 peer 根与组件组合，冻结安装无需重新解析版本。锁生成/安装容器限制为 2 CPU、6 GiB，Node 堆上限 4 GiB；不使用 `--force` 或 `--legacy-peer-deps`。实际完整安装、重装耗时与验收状态以本次私有报告为准。

## HMR 兼容依据

DSH rc.8 的 `watchUserPatches` 调用 HMR 的 `registerConfig`；该接口存在于 HMR 1.0.16、1.0.17，从 1.0.18 起不再存在。DSH 的 `^1.0.16` 范围允许解析到不兼容的 1.0.20。本目录通过精确依赖和 override 固定 **HMR 1.0.17**，其 peer 约束接受当前 Cordis/timer。

另一个问题是 headless profile 默认禁用 HMR，启动器动态 `loader.create` 只完成插件创建，未等到 fiber 激活便调用 `watchUserPatches`。后端生成的 profile overlay 在启动阶段启用已有 `hmr` entry，并设置 `root: []`；由正常 boot 激活检查保证服务就绪。保留用户 patch 文件 watcher，不吞掉启动错误，不修改第三方源码，也不把 `--expose-internals` 作为修复。

本地旧基线的对照：默认启动报缺失 HMR；仅启用 HMR 1.0.20 后报缺失 `registerConfig`；固定 1.0.17 并启用后进入模型凭据阶段。rc.8 已在本地模拟上游完成真实文本、工作区读写和正常退出；这不代表任意真实模型均兼容。

## 更新与验收

在资源受限的本地 Docker 中更新精确依赖、生成锁并审查差异，随后两次空目录 `npm ci --engine-strict`。每次运行 `python3 verify_lock.py <安装目录>` 并比较规范化清单，确认锁文件字节未变化。`npm ci` 校验下载内容完整性；本项目验证器检查锁/清单、工具链、平台及安装树，不声称能替代包内容的密码学校验。

升级后必须完成 DSH/Codex/OpenCode 三项 probe、工具往返、超时/取消清理及整套后端测试。安装校验器的单元测试位于 `src/test/python/test_agent_cli_lock.py`；覆盖率 source 必须加入 `agent-cli`。Java 覆盖范围为全部 `src/main/java`，仅排除生成的 `cn/rvport/backend/jooq`；资源脚本包括 `analysis`、`bitcode`、`agentruntime`，以及本目录校验器，未加载源码也纳入统计。

受影响完整 Java 包包括 `agentruntime`、`agentruntime.bridge`、`codexprovider`、`modelprofile`、`kb.ai`、`testcomparison.runtime`，以及实际改动的 worker/取消组件。整体和受影响包行/分支分别至少 90%，保留既有更严格 class 门槛。第三方 node_modules 不属于项目自有代码，不纳入源码覆盖率。

backend 与 worker 必须使用同一验收镜像。部署需另行授权；回滚镜像会恢复旧 CLI 限制，不重写历史探测结果。

镜像设置 `OPENCODE_DISABLE_AUTOUPDATE=true`，避免启动后绕过冻结清单自行升级。版本更新通过清单、锁文件和新镜像交付。

## 协议路由与能力边界

`RESPONSES` profile 原生直连；Codex 的 `CHAT` profile 由现有 worker 为每次执行启动固定 OpenCodex 2.94.0，完成 Responses → Chat Completions 转换。项目只实现配置、纯准入和生命周期监管，旧 Java 转换器已删除。Codex 始终使用 `wire_api="responses"`；DSH/OpenCode 保留原生 Adapter。probe、KB_AI_EXTENSION 和 TEST_LOG_COMPARE 共用 Adapter，前端无需新增配置、接口或代理服务。连通性检查仍按 profile 的原始协议请求，不自动降级。

| 能力 | CHAT 桥接行为 |
|---|---|
| instructions、system/developer/user/assistant、多轮文本 | 由固定 OpenCodex 转换，准入层保持请求字节不变 |
| function schema、tool_choice、并行调用及结果 | 保留定义与参数；严格核对 call ID、类型与顺序 |
| custom 文本工具 | 当前仅接受 apply_patch，由 OpenCodex 完成工具协议映射 |
| Codex 0.165.0 apply_patch Lark grammar | 准入只接受冻结的完整语法，真实 CLI 执行/文件结果另作验收 |
| 其他 grammar / JSON Schema 输出、图像、音频、托管工具 | 明确拒绝，不丢弃约束 |
| SSE / 非流式、usage、截断 | 只转换真实 usage，缺失为 null；截断为 incomplete，缺少终态、未知结束原因或非法内容类型为失败 |
| previous_response_id、加密历史、reasoning、compaction、WebSocket | 不支持；CHAT 配置禁用相关可选能力，剩余请求明确拒绝 |

Codex 0.165.0 即使禁用 reasoning 也会发送 `include=["reasoning.encrypted_content"]`，允许这个可选输出投影；实际带加密内容的历史输入仍拒绝。安全录制夹具位于 `../src/test/resources/agentruntime/codex-0.165.0-request.json`。verbosity、其他推理配置和结构化输出请求仍明确拒绝。仅有推理内容或 CLI 零退出不代表业务探针通过，仍校验要求的 JSON 和真实工作区文件。

上游密钥只供 worker 和代理使用，写入执行专属 0700 目录内的 0600 配置；Codex 环境仅获得 loopback 地址与短期随机 token。准入监听和代理绑定 127.0.0.1 动态端口，不增加 Compose 服务或主机端口；固定模型和上游地址，拒绝客户端覆盖与重定向。每次执行生成独立 provider/profile 快照，不需要人工注册、共享配置刷新或重启服务。不同执行不共享上下文，关闭后原 token 无法连接。

Codex CHAT 管理探针通过现有 RabbitMQ 队列 `rvport.codex.profile-probe` 下沉 worker，消息只携带执行 UUID。内部 `codex_probe_jobs` 保存所有权、截止时间和幂等结果；临时覆盖使用加密短期快照，完成/取消/过期清除。worker 领取时复核 profile/runtime 启用状态并冻结当前配置；普通编辑只影响之后领取的执行。重复投递不重跑模型，发布恢复最多三次，超期和失联有界失败。旧同步测试接口等待内部结果，异步多 Agent run 保持原返回结构；历史结果不回填。

API 重启恢复时归并已持久化终态，并同步更新 profile/runtime 摘要；摘要按测试时间单调更新，迟到结果不能覆盖更新的测试。Codex 探针只从 stdout 读取最终 JSON，stderr 中回显的提示词和示例 JSON 不作为成功结果。

API 角色不能启动代理；业务与 probe 共用 worker 中的并发闸门，默认最多两个，且不超过 worker concurrencyLimit。对应 Spring 配置为 `rvport.agent.opencodex.max-concurrency` 和 `rvport.agent.opencodex.executable`。冻结安装、四项受控修补及升级规则见 [OpenCodex 说明](opencodex/README.md)。

2026-11-02 最终候选镜像在 2 CPU / 1536 MiB 容器中，单实例就绪 1978 ms、进程树 RSS 252071936 字节；双实例就绪 3158 ms、合计 RSS 435273728 字节。启动 CPU 时间分别 2.01 / 4.49 秒，清理 65 / 64 ms，监听和目录均回收。该采样用于默认两实例上限，不代表外部模型的响应时延或所有部署的性能保证。

## 时间预算、清理与诊断

每次 CLI 执行使用单调时钟统一预算，排队、代理就绪、HTTP、CLI 启动与执行共享剩余时间；三个 probe 分项各有原有 timeoutSeconds 预算，外层 worker job 另限制总截止时间，不自动扩大。失败后最多额外 2 秒进程清理宽限，Linux subreaper 回收 wrapper 遗留的后代；不终止其他任务的进程。文件锁避免误删活跃执行目录，worker 下次启动执行前回收自身遗留目录。已有 supervisor 无法启动时显式失败。

probe 保留原 `name/status/errorCode/message/durationMs`，增加 `message_en` 与 `details`。details 包含 `phase/budgetMs/totalMs/route/cliVersion/exitCode/timedOut/cancelled/observedSignal/cleanup`；有实际进程测量时增加 `executionMs/cleanupMs/processTotalMs`。`UNKNOWN` 表示未观测到，不代表成功；中断或启动异常没有进程测量时不伪造清理完成。文件校验失败保留同次执行的诊断。

公共响应仅使用白名单事实和固定中英文说明，不返回原始 stdout/stderr、prompt、上游正文、密钥、token、路径、堆栈或 ANSI；无法分类时报告 UNKNOWN。`observedSignal` 仅表示日志出现已知信号，不证明根因。历史记录保持原样，新字段增量写入，不迁移旧失败为成功。

本地协议验收入口：`src/test/fixtures/agent_runtime_acceptance.py` 与 test-only `AgentCliAcceptanceMain`；支持 `--failures` 验证真实 CLI 错误/超时/取消，`--serve` 为隔离 Compose 提供模拟上游。只使用假凭据，不连接真实模型。运行命令、报告与覆盖率见本变更的验证记录。
