## MODIFIED Requirements

### Requirement: 模型测试 API 必须返回指定 agent 兼容性分项结果
后端 API SHALL 在模型 profile 测试响应中返回 agent 类型、阶段无关的通用分项 probe 结果和聚合兼容性状态，使前端能够判断模型 Profile 是否能被指定 Agent Runtime 驱动。

#### Scenario: 返回模型测试分项结果
- **WHEN** 已认证配置所有者调用模型 profile Agent 兼容性测试 API
- **THEN** 响应 SHALL 包含每个通用 probe 的名称、状态、错误码、脱敏消息和测试时间
- **AND** 响应 SHALL 包含当前测试的 agent runtime ID 和 agent 类型
- **AND** 响应 SHALL 包含 Agent 响应、workspace 读取、workspace 写入和聚合兼容状态
- **AND** 响应 MUST NOT 包含测试比较、KB AI 扩展或其他业务阶段兼容状态
- **AND** 响应 MUST NOT 返回 API key、授权头、完整 agent 命令或服务器真实路径

#### Scenario: 列表展示最近兼容性状态
- **WHEN** 已认证配置所有者查询模型 profile 列表或详情
- **THEN** 列表项 SHALL 仅返回本人 profile 对每个 Agent Runtime 的最近通用兼容状态、通用检查摘要和测试时间
- **AND** 列表项 MUST NOT 返回 `lastTestCompareCompatible` 或其他阶段兼容性字段
- **AND** 列表项 MUST NOT 返回 provider secret

### Requirement: 模型 Profile API 必须拆分连通性测试和 Agent 兼容性测试
后端 API SHALL 为已认证配置所有者提供独立的模型连通性测试 API 和多 agent runtime 通用兼容性测试 API，避免长时间 agent probe 阻塞同步 HTTP 请求，并保持阶段绑定职责独立。

#### Scenario: 模型连通性测试
- **WHEN** 已认证配置所有者调用 `POST /api/me/model-profiles/{profileId}:test-connectivity`
- **THEN** API SHALL 使用已保存配置或请求中的临时覆盖配置执行模型 provider 连通性测试
- **AND** API SHALL 返回 `status`、`message`、`testedAt`、`durationMillis` 和脱敏后的 `details`
- **AND** 后端 SHALL 记录最近一次连通性测试摘要
- **AND** 响应 MUST NOT 返回 API key、授权头、完整 prompt 或服务器真实敏感路径

#### Scenario: 创建多 Agent 测试 Run
- **WHEN** 已认证配置所有者调用 `POST /api/me/model-profiles/{profileId}:test-agents` 并提交 `agentRuntimeIds`
- **THEN** API SHALL 校验 `agentRuntimeIds` 非空且去重后全部指向已启用 agent runtime
- **AND** API SHALL 创建 agent probe run 并立即返回 `runId`、`status`、`profileId`、`agentRuntimes`、`createdAt`
- **AND** 后端 SHALL 在后台执行所选 agent runtime 的通用兼容性测试
- **AND** 后端 MUST NOT 读取 `model_stage_assignments` 决定测试内容或结果
- **AND** 响应 MUST NOT 等待所有 agent runtime probe 完成

#### Scenario: 查询 Agent 测试 Run
- **WHEN** 已认证配置所有者调用 `GET /api/me/model-profile-agent-test-runs/{runId}`
- **THEN** API SHALL 返回 run 聚合状态、模型 profile 摘要、agent runtime 分项状态、通用 probe、脱敏消息、开始时间和结束时间
- **AND** 未完成的 runtime SHALL 返回 `PENDING` 或 `RUNNING`
- **AND** 失败 runtime SHALL 返回脱敏失败原因
- **AND** 响应 MUST NOT 返回业务阶段兼容性字段

#### Scenario: 查询 Profile Agent Probe 结果
- **WHEN** 已认证配置所有者调用 `GET /api/me/model-profiles/{profileId}/agent-probe-results`
- **THEN** API SHALL 返回该 profile 对各 agent runtime 的最近通用兼容性结果
- **AND** 响应 SHALL 支持按 agent 类型、runtime 启用状态、兼容性状态和最近测试时间过滤或排序
- **AND** 兼容性结果 SHALL 以模型 Profile ID 与 Agent Runtime ID 为组合维度

#### Scenario: 列表返回测试摘要
- **WHEN** 已认证配置所有者调用 `GET /api/me/model-profiles`
- **THEN** 列表项 SHALL 返回最近连通性状态、最近连通性测试时间和脱敏连通性消息
- **AND** 列表项 SHALL 返回通用 agent 兼容性摘要或可展开结果引用
- **AND** 如果该 profile 存在 `PENDING` 或 `RUNNING` 的 agent probe run，列表项 SHALL 返回 `activeAgentProbeRun`
- **AND** `activeAgentProbeRun` SHALL 包含 `runId`、`status`、所选 agent runtime 摘要和创建时间
- **AND** 列表项 SHALL NOT 返回 `activeConnectivityRun`，因为连通性测试为同步请求且不创建后台 run
- **AND** 响应 MUST NOT 返回 provider secret 或阶段兼容性字段

#### Scenario: 详情返回活跃 Agent Probe Run
- **WHEN** 已认证配置所有者调用模型 profile 详情 API
- **THEN** API SHALL 返回该 profile 最近连通性摘要、通用 agent 兼容性矩阵摘要和当前活跃 agent probe run
- **AND** 如果不存在未终态 run，`activeAgentProbeRun` SHALL 为空
- **AND** API SHALL NOT 返回活跃连通性 run
- **AND** 前端 SHALL 能依据 `activeAgentProbeRun.runId` 继续查询 run 结果

#### Scenario: 阶段绑定独立于兼容性测试
- **WHEN** 已认证配置所有者查询或保存 `model_stage_assignments`
- **THEN** API SHALL 按认证所有者与阶段保存本人模型 Profile 与受管 Agent Runtime 组合
- **AND** 阶段绑定 SHALL NOT 改变该组合的通用兼容性记录
- **AND** 兼容性测试 SHALL NOT 因组合绑定到不同阶段而产生不同结果结构

#### Scenario: 跨用户访问测试 API
- **WHEN** 任一用户（包括 admin）使用他人的 profile 或 run 调用模型连通性、agent probe run 或结果查询 API
- **THEN** API SHALL 返回与不存在资源一致的 404，不写入结果或调用上游
- **AND** 响应 MUST NOT 泄漏 profile、runtime、run 是否存在或内部执行细节

## REMOVED Requirements

### Requirement: 管理员 Codex Provider 配置 API 必须受超级管理员保护
**Reason**: 模型管理下放到个人，旧全局管理权限与路由必须移除。
**Migration**: 使用个人模型配置 API，admin 也只能管理自己的配置。

### Requirement: Agent Runtime API 必须受超级管理员保护
**Reason**: 普通用户需要安全选择平台受管 runtime，不能继续保留旧 admin 目录路由。
**Migration**: 使用 `/api/me/agent-runtimes` 及详情接口，平台继续统一维护 CLI。

### Requirement: 模型测试旧 API 必须保持兼容但不作为新流程入口
**Reason**: 已批准的个人化契约要求删除旧 admin API，不能保留兼容或迁移响应接口。
**Migration**: 单 Agent、连通性和多 runtime 测试均通过个人 API；旧路由通过认证及 CSRF 后返回 404。

## ADDED Requirements

### Requirement: 个人模型配置 API 必须按认证所有者授权
后端 API SHALL 为已认证配置所有者提供模型 profile 管理、安全 agent runtime 目录，以及 `TEST_LOG_COMPARE`、`KB_AI_EXTENSION` 两个阶段的个人模型/agent 绑定接口。旧 `codex-provider` 单例配置 API SHALL NOT 继续作为管理入口保留，受控业务阶段的模型和 agent 选择 SHALL 以个人模型 profile、平台 agent runtime 和个人阶段绑定为事实来源。

#### Scenario: 查询模型 profile 列表
- **WHEN** 已认证配置所有者调用 `GET /api/me/model-profiles`
- **THEN** API SHALL 返回分页模型 profile 列表
- **AND** 响应 SHALL 包含 `id`、`name`、`enabled`、`modelProviderType`、`baseUrl`、`model`、`wireApi`、`timeoutSeconds`、`maxRuntimeSeconds`、`apiKeyConfigured`、`apiKeyMasked`、`lastTestStatus`、`lastTestMessage`、`lastTestAgentType`、`lastTestDetails`、`lastTestedAt`、`createdAt` 和 `updatedAt`
- **AND** 响应 MUST NOT 返回 API key 明文或密文

#### Scenario: 创建或更新模型 profile
- **WHEN** 已认证配置所有者调用 `POST /api/me/model-profiles` 或 `PATCH /api/me/model-profiles/{profileId}`
- **THEN** API SHALL 校验并保存模型 profile
- **AND** `modelProviderType` SHALL 只允许 `OPENAI_COMPATIBLE`
- **AND** `wireApi` SHALL 只允许 `RESPONSES` 或 `CHAT`
- **AND** `baseUrl` SHALL 只允许 `http` 或 `https` URL
- **AND** `model` SHALL 为非空字符串
- **AND** `timeoutSeconds` 和 `maxRuntimeSeconds` SHALL 在后端定义的安全范围内
- **AND** API MUST NOT 接受未声明的任意配置字段
- **AND** 个人 API MUST NOT 接受 `executablePath` 或任意命令覆盖

#### Scenario: 保存模型 profile API key
- **WHEN** 已认证配置所有者保存模型 profile 且请求包含非空 `apiKey`
- **THEN** 后端 SHALL 加密保存 API key
- **AND** API 响应 MUST NOT 返回 API key 明文或密文

#### Scenario: 保留或清空模型 profile API key
- **WHEN** 已认证配置所有者更新模型 profile 且请求不包含 `apiKey`
- **THEN** 后端 SHALL 保留已有 API key
- **WHEN** 已认证配置所有者更新模型 profile 且 `apiKey` 为空字符串
- **THEN** 后端 SHALL 清空已有 API key

#### Scenario: 测试模型 profile
- **WHEN** 已认证配置所有者调用 `POST /api/me/model-profiles/{profileId}:test`
- **THEN** API SHALL 使用已保存配置或请求中的临时覆盖配置执行最小可用性测试
- **AND** 请求 SHALL 支持指定 `agentRuntimeId`
- **AND** 未指定 `agentRuntimeId` 时后端 SHALL 使用本人的 `TEST_LOG_COMPARE` 当前绑定的 agent runtime 或默认受管 Codex runtime
- **AND** 临时覆盖配置中的 API key MUST NOT 写入已保存 profile；跨 worker 暂存 SHALL 加密、限定当前执行并在终态清理
- **AND** 响应 SHALL 返回成功或失败状态、agent 类型、agent runtime ID、分项 probe、脱敏消息和测试时间
- **AND** 后端 SHALL 记录最近一次测试状态、脱敏消息、agent 类型、agent runtime ID、分项结果和测试时间

#### Scenario: 查询和保存阶段模型绑定
- **WHEN** 已认证配置所有者调用 `GET /api/me/model-stage-assignments` 或 `PATCH /api/me/model-stage-assignments`
- **THEN** API SHALL 返回或保存当前用户两个受控阶段到本人模型 profile 和平台 agent runtime 的绑定
- **AND** 阶段名称 SHALL 使用后端白名单枚举，当前允许 `TEST_LOG_COMPARE` 和 `KB_AI_EXTENSION`
- **AND** 绑定目标 SHALL 为本人已启用模型 profile 和已启用 agent runtime
- **AND** 响应 MUST NOT 返回 API key 明文、密文、完整 agent 命令或完整 prompt

#### Scenario: 跨用户访问模型配置
- **WHEN** 任一已认证用户（包括 admin）使用他人 profile 或绑定目标访问个人接口
- **THEN** 资源访问 SHALL 返回与不存在资源一致的 404；跨用户绑定 SHALL 整体拒绝，不产生上游调用
- **AND** 后端 MUST NOT 泄漏任何 provider secret、agent 命令或内部执行细节

#### Scenario: 模型配置 API 不直接暴露模型调用能力
- **WHEN** 前端配置模型 profile、agent runtime 和阶段绑定
- **THEN** 后端 SHALL 只保存、查询、测试配置以及为后续测试结果比较 agent runtime 提供阶段解析
- **AND** 后端 MUST NOT 通过配置 API 提供任意提示词执行或直接 LLM 调用能力

#### Scenario: 普通用户和 admin 使用个人配置
- **WHEN** 已认证且账号有效的普通用户或 admin 管理自己的模型配置
- **THEN** API SHALL 从认证主体确定 owner，限定列表、计数和全部操作，拒绝客户端覆盖身份
- **AND** 同一所有者内名称 SHALL 大小写不敏感唯一，不同用户可以同名

### Requirement: Agent Runtime API 必须提供安全个人选择目录
后端 API SHALL 为已认证用户提供平台统一维护的 Agent Runtime 安全选择目录；个人测试结果 SHALL 按个人 profile 与 runtime 组合查询，不能成为共享 runtime 状态。

#### Scenario: 查询 agent runtime 列表
- **WHEN** 已认证用户调用 `GET /api/me/agent-runtimes`
- **THEN** API SHALL 返回 runtime ID、agent 类型、名称、启用状态及安全能力摘要
- **AND** 响应 MUST NOT 返回内部路径、完整命令、默认参数、私有配置、密钥或共享个人 probe 摘要

#### Scenario: 查询 agent runtime 详情
- **WHEN** 已认证用户调用 `GET /api/me/agent-runtimes/{runtimeId}`
- **THEN** API SHALL 返回该 runtime 的安全选择元数据，不返回全局个人 probe 结果
- **AND** 个人请求 MUST NOT 指定任意可执行路径或修改平台 CLI 安装配置

#### Scenario: 普通用户访问目录
- **WHEN** 普通用户或 admin 查询受管 runtime 目录
- **THEN** 后端 SHALL 允许账号有效的已认证用户读取安全目录，继续执行强制改密码等既有规则
- **AND** 不存在 runtime SHALL 返回受控错误，不泄漏内部执行细节

### Requirement: 原 admin 模型 API 必须彻底移除
后端 SHALL 删除原 `/api/admin/model-profiles`、`/api/admin/model-stage-assignments`、`/api/admin/model-profile-agent-test-runs` 和 `/api/admin/agent-runtimes` 路径族及其全部方法、动作后缀、子资源、专用安全匹配规则与当前 OpenAPI 定义；MUST NOT 保留别名、兼容处理器、重定向或专用迁移响应。

#### Scenario: 已认证调用旧接口
- **WHEN** 请求通过认证与适用 CSRF 校验后调用任一旧模型 API
- **THEN** 后端 SHALL 按未注册 API 返回 404，不调用业务服务、不修改配置或结果、不请求模型
- **AND** 无关 admin 功能及平台受管 CLI 维护 SHALL 保持原有边界

#### Scenario: 客户端使用个人接口
- **WHEN** 客户端调用 `/api/me/model-profiles/{profileId}:test` 等个人接口
- **THEN** 后端 SHALL 验证本人归属并保留主要响应字段、脱敏及双语诊断
- **AND** 单 Agent 入口 SHALL 保持有界同步语义，异步多 runtime 入口 SHALL 返回可轮询 run；前端适配由独立工作完成
