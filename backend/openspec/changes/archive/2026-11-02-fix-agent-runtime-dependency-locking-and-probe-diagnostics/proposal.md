## Why

上一轮已完成并归档的方案使用自有 Java 转换器；用户确认改用第三方 OpenCodex，减少自行维护 Responses/Chat 协议转换的负担，并要求它内嵌 worker、不新增 Compose 基础设施。2026-10-30 本地隔离 PoC 已证明固定版本可完成真实 Codex 的文本、文件读写及 apply_patch，但配置回退、默认路由和进程生命周期仍须由项目集成约束。

本 change 是同名变更的第二轮提案，基于已经同步的主规范编写增量；上一轮记录保留在同一归档目录下的 `../2026-10-30-fix-agent-runtime-dependency-locking-and-probe-diagnostics/`，其任务与验收不代表本轮已完成。

## What Changes

- 用固定 `@bitkyc08/opencodex@2.94.0` 的受管子进程替换自有 Java Responses → Chat 转换器，仅用于 Codex + CHAT；原生 RESPONSES 直连，DSH/OpenCode 保持现有实现与版本。
- 代理只在现有 worker 运行角色内按执行启动，独立目录、端口、凭据和 profile 快照；不增加 Compose 服务、主机端口、外部数据库或共享 profile 注册中心。
- 锁定 OpenCodex、Bun、平台二进制和完整传递依赖，加入现有冻结安装和版本校验；保留 DSH rc.8 / HMR 修复、Codex 0.165.0、OpenCode 1.18.18 基线，本轮不顺带升级其他 CLI。
- 将 Codex CHAT 的管理探针（含旧兼容入口）转交现有 worker 执行，覆盖原先 backend 本地执行的入口；保留 API、三分项、权限及前端契约，其他 Agent 不迁移。
- 动态 profile 从本次执行快照生成配置，无需人工注册/重启；删除、禁用、更新、排队及正在执行时的生效规则明确且可测试。
- 项目仅维护生命周期、请求准入和诊断，不再维护协议语义转换。对默认配置回退、未知模型转默认 provider、额外管理/桌面功能建立拒绝和隔离边界。
- 重新完成自有集成代码单元测试、整体及受影响模块行/分支各至少 90%、真实 CLI 协议回归和完整本地 Docker Compose 验收；全部新任务保持未完成直到本轮证据通过。

## Capabilities

### New Capabilities

无；延续已有能力名称，避免新建与现有桥接规范重叠的能力。

### Modified Capabilities

- `codex-chat-protocol-bridge`：第三方转换、worker 执行边界、运行凭据、请求准入、动态快照、生命周期和验收职责。
- `agent-runtime-selection`：增加 OpenCodex/Bun 完整锁定及 worker 资源监管，保留现有 Agent 版本、诊断和质量门槛。
- `model-profile-management`：Codex CHAT 探针分派、可靠结果归并、旧 API 兼容及 profile 变更生效规则。

## Impact

- 后端：`agentruntime`、现有 `bridge`、`codexprovider`、`modelprofile`、worker/队列/配置与必要的探针执行记录迁移；回归 `kb.ai`、`testcomparison.runtime`。
- 构建与文档：`backend/agent-cli`、`backend/Dockerfile`、自有校验/监管脚本及开发/安装指南。继续保持 `dev`，不提交、推送或部署。
- backend 与 worker 可共用镜像文件，代理执行权限由运行角色约束；只有 worker 启动 OpenCodex。依赖现有 RabbitMQ/PostgreSQL 完成内部探针调度，不引入新基础设施。
- 上游密钥由 worker 按最小范围传给 OpenCodex 专属受限配置，Codex 仅获得本次本地准入 token；这替换旧规范“密钥不得出现在任何子进程配置”的 JVM 内存假设，必须同步修改相应契约并实测清理。
- 不修改前端、业务 API 协议枚举或历史探针结果，不扩展其他 Agent 的协议适配。所有安装、测试、覆盖率和运行验收仅在本地 Docker；远程 dev/test 只读，且本轮不需要连接远程。
- 第三方原始源码按依赖排除，自有适配、准入、调度、脚本和诊断全部进入覆盖率范围；PoC 与上一轮报告不能替代本轮代码验证。
