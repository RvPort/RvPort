## REMOVED Requirements

### Requirement: 数据库必须持久化源码版本迁移输入集合
**Reason**: 开发阶段弃用旧数据，删除旧确认池及兼容路径。
**Migration**: 使用新报告输入、草稿和确认快照；旧开发实例全新初始化，不转换旧记录。

### Requirement: 数据库必须持久化迁移输入 reference 列表
**Reason**: 开发阶段弃用旧数据，删除旧确认池及兼容路径。
**Migration**: 使用新报告输入、草稿和确认快照；旧开发实例全新初始化，不转换旧记录。

## MODIFIED Requirements

### Requirement: 报告输入表必须保存迁移输入快照
数据库 SHALL 保存新报告固定分析来源快照，不保留旧 MIGRATION_INPUT_REF 数据读取或转换路径。

#### Scenario: 新来源快照
- **WHEN** 创建新报告输入 manifest
- **THEN** SHALL 使用可区分的 ANALYSIS_RESULT 类型保存 sourceType/sourceRunId/sourceRefId、源码/Context/证据快照和摘要，按 report_id 及来源建立索引与唯一约束。

#### Scenario: 空库初始化
- **WHEN** 在独立开发数据库初始化新 schema
- **THEN** SHALL 直接建立新报告与草稿/确认约束，不需要旧引用 backfill；新流程生成后的历史快照须保留。

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
- **AND** SHALL 保存 TEST_LOG_COMPARE 和 REPORT_AI_REVIEW 的个人阶段绑定
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

## ADDED Requirements

### Requirement: 数据库必须保证报告草稿确认和发布一致性
数据库 SHALL 以专用关系记录 manifest、逐项 AI 结果、人工条目/事件、草稿版本和确认快照；引用必须约束同项目同报告同源码，不将全部业务明细放入单个 JSON。

#### Scenario: 原子草稿修改
- **WHEN** 批量更新多个条目
- **THEN** SHALL 比较草稿版本、验证全部归属、一次递增版本并追加 actor 事件；失败整体回滚。

#### Scenario: 确认唯一性
- **WHEN** 重复或并发确认同一报告草稿版本
- **THEN** SHALL 最多形成一份确认，保存完整处置与 TO_FIX 子集；空集合也有明确确认记录。

#### Scenario: 当前发布
- **WHEN** 新报告与旧草稿变化竞争
- **THEN** SHALL 原子校验 Context、reviewRevision 和 generationSequence 后切换指针，不能出现两个可写当前报告。

#### Scenario: 执行和源码存储
- **WHEN** 任务跨 worker 恢复
- **THEN** SHALL 持久安全主体/profile/runtime/尝试版本及不可变源码制品引用，禁止消息/审计保存明文凭据，jOOQ 在 Docker 从迁移后 schema 生成。
