## MODIFIED Requirements

### Requirement: 后端必须提供可选 Agent Runtime
后端 SHALL 支持多个 agent runtime，并 SHALL 至少提供 `CODEX`、`DEEPSEEK_HARNESS` 和 `OPENCODE` 三种可选 agent 类型。

#### Scenario: 初始化默认 agent runtime
- **WHEN** 执行数据库迁移或应用初始化
- **THEN** 后端 SHALL 创建或保留 `CODEX`、`DEEPSEEK_HARNESS` 和 `OPENCODE` 三类 agent runtime 配置
- **AND** 每个 runtime SHALL 包含 agent 类型、展示名称、可执行路径、启用状态和安全摘要
- **AND** 初始化过程 SHALL 可重复执行

#### Scenario: 查询 agent runtime 列表
- **WHEN** 已认证用户打开模型或阶段配置页面
- **THEN** 后端 SHALL 提供 agent runtime 列表
- **AND** 列表项 SHALL 包含 runtime ID、agent 类型、展示名称、启用状态和安全能力摘要，不包含内部路径或共享个人探测状态
- **AND** 响应 MUST NOT 返回完整命令、API key、授权头、完整 prompt 或服务器真实敏感路径

#### Scenario: 查询 agent runtime 详情
- **WHEN** 已认证用户查询单个 agent runtime
- **THEN** 后端 SHALL 返回该 runtime 的安全选择元数据、支持阶段和启用状态，不返回全局个人 probe 结果
- **AND** 响应 MUST NOT 返回明文密钥或完整命令行

### Requirement: Agent Runtime 选择必须仅用于受控阶段
后端 SHALL 只允许已声明阶段使用 agent runtime，当前允许 `TEST_LOG_COMPARE` 和 `KB_AI_EXTENSION` 阶段绑定 agent runtime。

#### Scenario: 保存 TEST_LOG_COMPARE agent 绑定
- **WHEN** 已认证用户保存 `TEST_LOG_COMPARE` 阶段绑定
- **THEN** 请求 SHALL 同时指定属于当前用户的已启用模型 profile 和已启用 agent runtime
- **AND** 后端 SHALL 保存当前用户与该阶段到模型 profile 和 agent runtime 的唯一绑定

#### Scenario: 保存 KB_AI_EXTENSION agent 绑定
- **WHEN** 已认证用户保存 `KB_AI_EXTENSION` 阶段绑定
- **THEN** 请求 SHALL 同时指定属于当前用户的已启用模型 profile 和已启用 agent runtime
- **AND** 后端 SHALL 保存当前用户与该阶段到模型 profile 和 agent runtime 的唯一绑定

#### Scenario: 绑定未知阶段
- **WHEN** 请求绑定未知阶段或非本 change 允许阶段
- **THEN** 后端 SHALL 返回统一参数错误
- **AND** 后端 MUST NOT 保存部分绑定

#### Scenario: 绑定禁用 agent
- **WHEN** 请求绑定已禁用或不存在的 agent runtime
- **THEN** 后端 SHALL 拒绝绑定
- **AND** 响应 MUST NOT 泄漏内部命令路径或配置细节

### Requirement: Agent Runtime Probe 必须支持按模型 Profile 批量执行
后端 agent runtime 选择能力 SHALL 支持模型 profile 针对多个指定 agent runtime 执行阶段无关的通用兼容性 probe，并 SHALL 使用统一 adapter 返回一致的分项结果。

#### Scenario: 按 Runtime 列表执行 Probe
- **WHEN** 模型 profile agent probe run 包含多个 agent runtime
- **THEN** 后端 SHALL 对每个 runtime 使用对应 agent adapter 执行兼容性 probe
- **AND** 每个 runtime SHALL 生成独立的 probe 状态、通用分项结果、耗时和脱敏消息
- **AND** 单个 runtime 失败 SHALL NOT 阻止其他 runtime 执行

#### Scenario: Runtime 不可用
- **WHEN** 请求中的 agent runtime 不存在、未启用或没有已注册 adapter
- **THEN** 后端 SHALL 拒绝创建 probe run 或将对应 runtime 标记为受控失败
- **AND** 响应 MUST NOT 泄漏完整命令、内部路径或 provider secret

#### Scenario: Probe 输出统一
- **WHEN** 任意 agent runtime 执行 profile 兼容性 probe
- **THEN** 后端 SHALL 使用统一结果结构保存 Agent 模型响应、workspace 读取、workspace 写入和基础 JSON 解析分项结果
- **AND** 聚合兼容状态 SHALL 只由这些通用分项计算
- **AND** probe MUST NOT 执行或返回 `TEST_COMPARE_SCHEMA`、`KB_AI_EXTENSION_SCHEMA` 或其他业务阶段专属检查

#### Scenario: Workspace 写入闭环
- **WHEN** Agent Runtime 执行通用 workspace 写入检查
- **THEN** Agent SHALL 根据中性输入文件写出受控 JSON 结果文件
- **AND** 后端 SHALL 校验文件存在、JSON 合法且 marker 与预期一致
- **AND** 临时 workspace SHALL 在 probe 完成、失败或超时后按现有安全策略清理

#### Scenario: 容器内 Agent CLI 完整安装
- **WHEN** 构建后端运行镜像
- **THEN** 镜像 SHALL 安装与 Codex CLI 版本匹配的平台二进制依赖
- **AND** 构建过程 SHALL 实际执行各内置 Agent CLI 的版本命令
- **AND** backend 与 backend-worker SHALL 使用包含相同 Agent CLI 的同一后端镜像

#### Scenario: 个人测试不覆盖平台状态
- **WHEN** 任一用户对受管 runtime 完成 probe
- **THEN** SHALL 仅按其个人 profile 与 runtime 保存结果，不更新共享 last_probe_* 作为全局兼容结论，不影响其他用户摘要
