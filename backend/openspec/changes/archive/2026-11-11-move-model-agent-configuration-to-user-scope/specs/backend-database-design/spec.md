## MODIFIED Requirements

### Requirement: 模型 Profile 配置必须使用专用表
数据库 SHALL 使用专用模型配置表保存多个模型 profile，并 SHALL 使用阶段绑定表表达测试结果比较阶段使用哪个模型和哪个 agent runtime。历史 `system_configs` 的 `codex-provider` namespace MAY 作为一次性迁移来源，但 SHALL NOT 作为兼容读取来源；测试结果比较运行时模型和 agent 选择 SHALL 以 `model_profiles`、`agent_runtimes` 和 `model_stage_assignments` 为事实来源。

#### Scenario: 保存模型 profile 普通配置
- **WHEN** 后端保存模型 profile 的普通配置项
- **THEN** 数据库 SHALL 将配置写入 `model_profiles`
- **AND** `owner_user_id` SHALL 非空引用 users；`name` SHALL 在同一所有者未删除 profile 内大小写不敏感唯一
- **AND** `model_provider_type` SHALL 表达 provider 类型，MVP 只允许 `OPENAI_COMPATIBLE`
- **AND** `wire_api` SHALL 使用非加密值保存，并 SHALL 只表达 `RESPONSES` 或 `CHAT`
- **AND** `enabled` SHALL 表达该 profile 是否可被阶段绑定和运行时解析
- **AND** `model_profiles` MUST NOT 作为 agent runtime 可执行路径的事实来源

#### Scenario: 保存模型 profile secret
- **WHEN** 后端保存模型 profile API key
- **THEN** 数据库 SHALL 将 API key 加密载荷写入 `model_profiles.api_key_encrypted`
- **AND** `api_key_encrypted` MUST NOT 保存 API key 明文
- **AND** 查询 API SHALL 只返回 API key 是否已配置和遮蔽摘要

#### Scenario: 保存模型 profile 测试摘要
- **WHEN** 模型 profile 测试完成
- **THEN** 数据库 SHALL 保存 `last_test_status`、`last_test_message`、`last_tested_at`、`last_test_agent_type` 和 `last_test_details`
- **AND** `last_test_details` SHALL 包含测试使用的 agent runtime ID 和分项 probe 摘要
- **AND** `last_test_message` MUST 为脱敏后的短摘要
- **AND** `last_test_message` MUST NOT 包含 API key、授权头或服务器真实敏感路径

#### Scenario: 保存阶段模型绑定
- **WHEN** 后端保存阶段模型绑定
- **THEN** 数据库 SHALL 将绑定写入 `model_stage_assignments`
- **AND** (`owner_user_id`, `stage`) SHALL 唯一
- **AND** SHALL 保存 TEST_LOG_COMPARE 和 KB_AI_EXTENSION 的个人阶段绑定
- **AND** (`owner_user_id`, `model_profile_id`) SHALL 通过复合外键引用 profile 的同所有者唯一键，拒绝跨用户绑定
- **AND** `agent_runtime_id` SHALL 外键引用 `agent_runtimes.id`
- **AND** 后端 SHALL 拒绝绑定不存在或已禁用的模型 profile
- **AND** 后端 SHALL 拒绝绑定不存在或已禁用的 agent runtime

#### Scenario: 查询阶段模型绑定
- **WHEN** 后端按阶段解析模型和 agent 配置
- **THEN** 数据库 SHALL 支持按当前配置主体的 owner_user_id 与 stage 查询绑定 profile 和 runtime
- **AND** 数据库 SHALL 支持按 `model_profiles.enabled` 和 `agent_runtimes.enabled` 过滤可用配置
- **AND** 后端 MUST NOT 从历史 `system_configs.codex-provider` 直接解析阶段模型或 agent

#### Scenario: 审计与执行身份独立
- **WHEN** 保存个人配置或任务执行上下文
- **THEN** owner SHALL 独立于 created_by/updated_by；AI 任务 SHALL 持久化配置主体、选中的 profile/runtime、父任务或批次引用和安全尝试版本，不保存明文密钥

### Requirement: 数据库必须保存 Agent Runtime 配置
数据库 SHALL 使用专用表保存 agent runtime 配置，使模型配置和 agent 执行器配置解耦。

#### Scenario: 创建 agent runtime 表
- **WHEN** 执行数据库迁移
- **THEN** 数据库 SHALL 创建 `agent_runtimes` 表
- **AND** 表 SHALL 包含 `id`、`agent_type`、`name`、`enabled`、`executable_path`、`default_args`、`config`、`last_probe_status`、`last_probe_message`、`last_probe_details`、`last_probe_at`、`created_at` 和 `updated_at`
- **AND** `agent_type` SHALL 支持 `CODEX`、`DEEPSEEK_HARNESS` 和 `OPENCODE`

#### Scenario: Agent runtime 唯一性
- **WHEN** 后端初始化或保存 agent runtime
- **THEN** 数据库 SHALL 保证 `agent_type` 唯一
- **AND** 数据库 SHALL 保证 runtime `name` 在未删除记录中唯一或通过等效策略避免展示重名歧义

#### Scenario: Agent runtime 安全边界
- **WHEN** 保存 `default_args`、`config` 或最近 probe 结果
- **THEN** 数据库 MUST NOT 保存 API key 明文、授权头、完整 prompt、完整 agent 输入或服务器真实敏感路径
- **AND** 可执行路径 SHALL 仅用于平台受管配置，个人 API MUST NOT 返回或允许覆盖

#### Scenario: 初始化默认 runtime
- **WHEN** 首次执行迁移或应用启动初始化
- **THEN** 数据库 SHALL upsert `CODEX`、`DEEPSEEK_HARNESS` 和 `OPENCODE` 默认 runtime
- **AND** 该初始化 SHALL 可重复执行

#### Scenario: 遗留全局探测字段
- **WHEN** 个人 probe 完成或查询 runtime 目录
- **THEN** last_probe_* 历史字段 SHALL 不再承接个人结果写入或作为共享兼容状态返回；历史数据不伪造或改写为新结果

### Requirement: 数据库必须保存模型 Profile 到 Agent Runtime 的 Probe Run 和结果
数据库 SHALL 使用独立表保存模型 profile 对多个 agent runtime 的兼容性测试 run 和分项结果，避免 `model_profiles` 上的单组 `last_test_*` 字段覆盖多个 agent 的测试结果。

#### Scenario: 创建 Agent Probe Run
- **WHEN** 后端创建模型 profile 多 agent 测试
- **THEN** 数据库 SHALL 创建 `model_profile_agent_probe_runs`
- **AND** run SHALL 关联 `model_profile_id`
- **AND** run SHALL 保存聚合状态、触发用户、创建时间、开始时间、结束时间和脱敏消息
- **AND** run 状态 SHALL 支持 `PENDING`、`RUNNING`、`SUCCESS`、`PARTIAL_SUCCESS`、`FAILED` 和 `TIMEOUT`

#### Scenario: 保存单 Agent Probe 结果
- **WHEN** 某个 agent runtime 的 probe 完成
- **THEN** 数据库 SHALL 创建或更新 `model_profile_agent_probe_results`
- **AND** 结果 SHALL 关联 `run_id`、`model_profile_id` 和 `agent_runtime_id`
- **AND** 结果 SHALL 保存 agent 类型、聚合兼容性状态、分项 probe JSON、脱敏消息、错误码、开始时间、结束时间和耗时
- **AND** 数据库 SHALL 支持按 `model_profile_id`、`agent_runtime_id` 和最近测试时间查询最新结果

#### Scenario: 保持最新矩阵查询
- **WHEN** 后端查询某个模型 profile 的 agent 兼容性矩阵
- **THEN** 数据库 SHALL 能返回每个 agent runtime 的最近一次 probe 结果
- **AND** 新增 agent runtime SHALL NOT 要求修改 `model_profiles` 表的基础字段结构

#### Scenario: 查询活跃 Agent Probe Run
- **WHEN** 后端查询模型 profile 列表或详情
- **THEN** 数据库 SHALL 支持按 `model_profile_id` 查询最新的未终态 agent probe run
- **AND** 未终态 SHALL 包含 `PENDING` 和 `RUNNING`
- **AND** 查询 SHALL 能返回 run ID、聚合状态、所选 agent runtime 摘要和创建时间
- **AND** 同一个 profile 同时存在多个未终态 run 时，后端 SHALL 依据创建时间返回最新 run 并可将旧 run 标记为受控终态或忽略

#### Scenario: Probe 结果安全边界
- **WHEN** 保存 run 或 result 的详情字段
- **THEN** 数据库 MUST NOT 保存 API key、授权头、完整 agent 命令、完整 prompt、临时 workspace 绝对路径或服务器真实敏感路径

#### Scenario: 个人 Probe 所有者一致性
- **WHEN** 创建、查询、恢复或写入 run/result/job
- **THEN** 数据库约束或受控事务 SHALL 验证 profile、run、result 与 job 配置主体一致，个人查询 SHALL 带 owner 条件
- **AND** 历史记录 SHALL 随 profile 归 admin 而保留原触发用户和测试事实
