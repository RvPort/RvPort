## Why

dev 的 DeepSeek Harness 已安装，但 headless 启动出现 Cordis HMR 服务缺失；当前仅固定主包版本，实际形成 rc.7 主包与大量 rc.8 组件混用，版本命令无法发现启动故障。Codex 探测超时又丢失执行诊断；升级到最新稳定版后，还必须解决仅支持 Chat Completions 的模型服务无法被 Responses-only Codex 直接使用的问题。

## What Changes

- 将 DeepSeek Harness 升级至 `0.1.0-rc.8`，验证真实 headless 启动，定位 HMR 故障；不预设升级或增加 Node 参数即可修复。
- 为 Agent CLI 建立可审查、可重复安装的完整依赖锁定。DSH 及其传递依赖、Cordis/HMR 独立版本、平台包与完整性信息纳入版本清单，禁止构建时浮动解析或只锁主包。
- 将 Codex CLI 从 `0.147.0` 升级到实施开始时核实的最新稳定版，并锁定精确版本与匹配平台包；2026-10-29 提案核实值为 `0.165.0`，不使用浮动 `latest` 构建。
- 保留模型 profile 的 `RESPONSES` / `CHAT` 上游协议语义。Codex 对原生 Responses 直接连接；对 CHAT 使用后端受管、执行级隔离的 Responses → Chat Completions 转换层，不再传入已移除的 `wire_api="chat"`。
- 转换层必须覆盖 Codex 当前任务所需的文本、工具调用及回传、多轮上下文、流式事件、错误与取消；不把普通请求转发器当成协议转换器，不以文本成功冒充 Agent 兼容。
- 完善探测超时的有界脱敏诊断、执行与清理耗时、进程树清理和可追溯版本信息，保留三项通用 probe 及真实分项结果。
- 所有依赖安装、试装、编译、单元测试、覆盖率、CLI 实验和完整 Compose 验收仅在本地 Docker 进行；远程 dev/test 仅允许只读排查，禁止任何修改或临时测试资源。
- 只修改 backend 范围内实现和文档，不修改前端，不自动部署；新增转换逻辑同样纳入整体及受影响模块行/分支覆盖率至少 90% 的验收。

## Capabilities

### New Capabilities

- `codex-chat-protocol-bridge`：定义 Codex Responses 请求到 CHAT 上游的受管转换、工具和流式语义、隔离与生命周期、错误边界及本地验收。

### Modified Capabilities

- `agent-runtime-selection`：增加 CLI 精确版本与完整依赖锁定、DSH 启动门槛、Codex 升级契约、超时诊断和进程清理要求。
- `model-profile-management`：明确上游协议与 Agent 传输协议的区别、原生与转换路径、探测可信性和兼容性边界。

## Impact

- 预计涉及 `backend/Dockerfile`、backend 内独立 CLI 依赖清单/锁文件、`agentruntime`、`codexprovider`、`modelprofile` 及新增转换模块；回归 `kb.ai` 和 `testcomparison.runtime` 的统一 Adapter 调用。
- 转换层优先由现有后端 JVM 管理，监听当前容器 loopback 临时端口；backend/worker 均可使用，不新增公网 API、默认 Compose 服务或远程代理部署。
- 保留已有数据库 profile 协议枚举、密钥加密存储、阶段绑定及 API 路径；新增诊断展示说明提供中英文字段。历史失败记录不回写成功，修复后新探测产生新记录。
- 整体统计范围为 backend 全部自有 Java 和资源脚本；新增桥接代码及自有可执行安装/校验脚本不能排除。完整运行验收仍从仓库根目录在本地执行全部默认 Compose 服务构建和一次启动。
- 探索已证实依赖漂移和 HMR 启动失败，但尚未证实两者存在直接因果；rc.8 本地全新安装曾在 npm 依赖解析阶段堆内存耗尽，安装可重复性及合理资源预算属于必须解决的验收项。
