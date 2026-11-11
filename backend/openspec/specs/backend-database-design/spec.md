## Purpose

定义 RvPort 后端数据库设计的持久化边界、核心模型约束、数据安全要求和查询支撑方式。该能力覆盖 PostgreSQL schema、Liquibase 迁移、jOOQ 代码生成、敏感字段保护、跨运行时记录关联以及面向 API 分页过滤所需的索引约束。
## Requirements
### Requirement: PostgreSQL 是后端持久化基础
后端数据库设计 SHALL 以 PostgreSQL 作为 MVP 持久化目标，并 SHALL 使用适合 PostgreSQL 的数据类型，包括 `uuid`、`timestamptz`、`jsonb`、`inet` 和必要的文本字段索引。

#### Scenario: 明确 Schema 目标
- **WHEN** 创建后端数据库文档或迁移脚本
- **THEN** 文档或迁移脚本 MUST 明确 PostgreSQL 是 MVP 支持的数据库目标

#### Scenario: 保留半结构化工具数据
- **WHEN** 持久化工具输入、任务输入快照、环境快照、报告摘要或分析器元数据
- **THEN** Schema SHALL 为需要追溯但不需要一等关系字段的数据提供 `jsonb` 字段

### Requirement: 项目阶段是派生的导航提示
数据库设计 SHALL 将项目阶段视为缓存式导航提示，而不是权威线性状态机。

#### Scenario: 导入修复后的代码
- **WHEN** 开发者在报告、构建或测试完成后导入修复后的代码
- **THEN** 项目阶段 MAY 回退到源码导入、扫描、报告或等待修复等提示状态，且不影响历史任务和运行记录

#### Scenario: 请求项目概览
- **WHEN** 后端计算项目概览或下一步引导
- **THEN** 后端 MUST 从源码版本、任务、领域运行表、问题和差异数据推导权威状态，而不是只依赖 `projects.stage`

### Requirement: MVP 不持久化用户角色
MVP 数据库设计 SHALL 保留本地用户和超级管理员标记，但 SHALL NOT 引入用户角色、用户角色映射或项目成员表。

#### Scenario: 定义用户表
- **WHEN** 定义 MVP 用户持久化
- **THEN** Schema SHALL 包含本地 `users` 表，用于登录、创建人引用、审计操作人引用和 `is_super_admin` 标记

#### Scenario: 考虑角色表
- **WHEN** 定义 MVP 数据库表
- **THEN** Schema MUST NOT 包含 `roles`、`user_roles` 或 `project_members` 表

### Requirement: 初始化时创建默认超级管理员
环境初始化脚本 SHALL 在第一次成功运行时创建默认超级管理员用户。

#### Scenario: 首次运行初始化
- **WHEN** 环境初始化脚本运行且不存在 `admin` 用户
- **THEN** 脚本 SHALL 创建 `username = 'admin'`、初始密码输入为 `admin123`、`is_super_admin = true` 的用户
- **AND** `must_change_password` SHALL 为 `true`

#### Scenario: 持久化默认密码
- **WHEN** 使用默认 `admin123` 密码创建初始管理员
- **THEN** 数据库 MUST 只保存派生出的 `password_hash`，并 MUST NOT 保存明文密码

#### Scenario: 再次运行初始化
- **WHEN** 环境初始化脚本运行且 `username = 'admin'` 已存在
- **THEN** 脚本 MUST NOT 创建重复管理员
- **AND** 脚本 MUST NOT 覆盖已有管理员密码

### Requirement: 任务状态持久化存储在 PostgreSQL
系统 SHALL 将 PostgreSQL 作为异步任务状态、任务事件、任务日志元数据和任务关联业务结果的事实来源。

#### Scenario: 创建任务并投递队列
- **WHEN** API 请求创建长时间运行任务
- **THEN** 系统 MUST 先插入 `tasks` 记录，再将任务 ID 投递到队列
- **AND** 初始任务状态 SHALL 为 `PENDING`
- **AND** 队列投递成功后任务状态 SHALL 更新为 `QUEUED`

#### Scenario: 队列载荷最小化
- **WHEN** 任务被投递到 worker 队列
- **THEN** 队列载荷 MUST 只包含 `task_id` 等路由信息，并 MUST NOT 作为任务输入、状态、进度或结果的权威存储

#### Scenario: 修复待投递任务
- **WHEN** 任务已经持久化但长时间停留在 `PENDING`
- **THEN** 系统 SHALL 能基于 PostgreSQL 中的任务记录重新投递任务消息
- **AND** 修复流程 MUST NOT 从 RabbitMQ 或 Redis 推导任务事实

#### Scenario: Worker 更新任务生命周期
- **WHEN** worker 启动、推进、成功、失败、超时或取消任务
- **THEN** worker MUST 更新 PostgreSQL 中的任务状态并追加任务事件
- **AND** worker MUST 使用 PostgreSQL 原子状态变更避免重复消息导致重复执行

### Requirement: SSE 恢复使用持久化任务事件
系统 SHALL 将任务事件持久化到 PostgreSQL，使项目级 SSE 通道能在客户端重连后恢复。

#### Scenario: 客户端携带最后事件 ID 重连
- **WHEN** 前端携带最后消费的事件 ID 重连项目任务事件流
- **THEN** 后端 SHALL 将该 ID 作为 `task_events.id` 游标
- **AND** 后端 SHALL 从持久化的 `task_events` 数据中读取同一项目内后续事件
- **AND** 后续事件 SHALL 按 `task_events.id` 升序返回

#### Scenario: 事件流不包含完整日志
- **WHEN** 任务发出进度或日志更新
- **THEN** 持久化任务事件 SHALL 包含状态、进度、消息、日志级别和短摘要，但 MUST NOT 保存完整 stdout 或 stderr 日志

#### Scenario: 事件游标可索引查询
- **WHEN** 后端按项目和最后事件 ID 恢复 SSE 事件
- **THEN** 数据库 SHALL 支持按 `project_id` 和递增事件 ID 查询后续 `task_events`

### Requirement: 数据库必须支持项目最新任务事件游标查询
数据库 SHALL 支持后端按项目查询最新任务事件 ID，用于项目 overview 返回 SSE baseline。

#### Scenario: 查询项目最新事件 ID
- **WHEN** 后端需要生成 `latestTaskEventId`
- **THEN** 数据库 SHALL 支持按 `project_id` 查询该项目最大的 `task_events.id`
- **AND** 没有事件时后端 SHALL 能返回 `0`

#### Scenario: 复用项目事件索引
- **WHEN** 后端补发项目级 SSE 事件或查询项目最新事件 ID
- **THEN** 查询 SHALL 使用 `task_events(project_id, id)` 或等效索引路径
- **AND** 后端 MUST NOT 只按全局 `id` 查询事件

#### Scenario: 历史事件保留
- **WHEN** 项目级 SSE 首次连接默认从最新事件之后开始
- **THEN** 数据库 SHALL 继续保留历史 `task_events`
- **AND** 历史事件 SHALL 继续可通过任务详情、任务列表或明确游标补发路径审计

### Requirement: 凭据由平台管理并加密
系统 SHALL 使用专用凭据模型保存 SSH 和 Git 凭据，凭据载荷必须加密，并只暴露非敏感摘要。

#### Scenario: 保存凭据
- **WHEN** 用户保存 SSH 密码、SSH 私钥或 Git 密码
- **THEN** 明文秘密 MUST 在持久化前加密，并 MUST NOT 存储到普通文本列

#### Scenario: 展示凭据信息
- **WHEN** API 响应包含凭据信息
- **THEN** 响应 SHALL 只返回配置状态、凭据类型、名称、遮蔽摘要等非敏感元数据

### Requirement: 日志本地存储并用元数据索引
系统 SHALL 将完整任务日志保存在本地文件系统，按天打包，并在 PostgreSQL 中只保存日志元数据和摘要。

#### Scenario: 任务写入 stdout 和 stderr
- **WHEN** worker 捕获任务的 stdout 或 stderr
- **THEN** 完整日志内容 SHALL 写入本地日志文件，PostgreSQL 只保存文件路径、日志日期、归档路径、大小、哈希和摘要元数据

#### Scenario: 打包日志
- **WHEN** 每日日志打包流程运行
- **THEN** 日志元数据 SHALL 能引用每日归档路径，且不要求通用 artifact 表

### Requirement: MVP 不包含通用 artifact 持久化
MVP 数据库设计 SHALL NOT 为源码包、源码工作副本或 Git checkout 等源码输入制品引入通用 artifact 表。后端运行产生的报告、扫描输出、日志归档和项目归档等输出制品 MAY 使用统一对象存储元数据表保存可查询元数据。

#### Scenario: 生成报告
- **WHEN** 生成 HTML 或 JSON 报告
- **THEN** 报告记录 SHALL 保存报告业务元数据
- **AND** 报告文件内容 SHALL 保存到对象存储或受控输出存储
- **AND** 数据库 MAY 通过后端输出制品元数据记录引用报告文件

#### Scenario: 生成归档
- **WHEN** 生成归档包
- **THEN** 归档记录 SHALL 保存归档业务元数据
- **AND** 归档包内容 MAY 保存到对象存储
- **AND** 数据库 MAY 通过后端输出制品元数据记录引用归档包

#### Scenario: 源码输入制品不进入通用输出制品表
- **WHEN** 用户上传源码包、后端创建源码工作副本或 Git 同步源码
- **THEN** 数据库 MUST NOT 将源码包、源码工作副本或 Git checkout 作为后端输出制品写入统一 artifact 元数据表
- **AND** 源码版本事实 SHALL 继续由 `source_revisions` 和 `source_imports` 表表达

### Requirement: 后端输出制品元数据必须支持对象存储
数据库 SHALL 支持保存后端运行产生的输出制品对象存储元数据。

#### Scenario: 创建输出制品元数据表
- **WHEN** 数据库创建或维护后端输出制品元数据表
- **THEN** 表 SHALL 包含主键、`project_id`、`task_id`、`owner_type`、`owner_id`、`artifact_type`、`bucket`、`object_key`、`content_type`、`size_bytes`、`sha256`、`summary` 和 `created_at`
- **AND** `project_id` SHALL 引用 `projects(id)`
- **AND** `task_id` MAY 引用 `tasks(id)`

#### Scenario: 输出制品查询索引
- **WHEN** 后端按业务归属查询输出制品
- **THEN** 数据库 SHALL 提供 `owner_type + owner_id + artifact_type` 查询能力
- **AND** 数据库 SHALL 支持按 `project_id` 和 `created_at` 查询
- **AND** 数据库 SHALL 支持按 `task_id` 追溯制品

#### Scenario: 输出制品内容安全
- **WHEN** 后端保存输出制品元数据
- **THEN** `summary` MUST NOT 包含明文凭据、私钥、token、passphrase、完整任务日志或服务器真实绝对路径
- **AND** `object_key` MUST NOT 包含服务器真实绝对路径

### Requirement: 领域结果表必须关联任务
系统 SHALL 将领域结果建模为关联权威任务记录的数据。

#### Scenario: KB 扫描完成
- **WHEN** KB 扫描任务完成
- **THEN** 系统 SHALL 持久化关联该任务的扫描运行记录，并持久化关联该扫描运行的 findings

#### Scenario: SBOM 分析完成
- **WHEN** SBOM 分析任务完成
- **THEN** 系统 SHALL 持久化关联该任务的 SBOM 运行记录，并持久化关联该运行的组件和替代候选项

#### Scenario: 构建或测试完成
- **WHEN** 构建或测试任务完成
- **THEN** 系统 SHALL 持久化关联任务、源码版本和执行环境的构建运行或测试运行记录

### Requirement: Findings 和 issues 是不同概念
系统 SHALL 区分不可变工具发现和 SBOM 记录，以及用户管理的迁移问题。

#### Scenario: 用户将发现项加入问题清单
- **WHEN** 选中的 findings 或 SBOM 风险被加入迁移问题清单
- **THEN** 系统 SHALL 创建或更新 issue 记录，并 SHALL 通过 issue source 记录保留来源映射

#### Scenario: 用户更新问题状态
- **WHEN** 用户确认、忽略或标记问题已解决
- **THEN** 系统 SHALL 更新 issue 状态，但不得修改原始 finding 或 SBOM component 事实

### Requirement: 环境快照版本化并被引用
系统 SHALL 将环境快照持久化为版本化记录，并 SHALL 在 SBOM 分析中引用实际使用的快照。

#### Scenario: 同一环境被多次采集
- **WHEN** 同一环境被采集多次
- **THEN** 系统 SHALL 将每次采集保存为独立快照

#### Scenario: 创建 SBOM 运行
- **WHEN** SBOM 分析比较 x86 源环境和 RISC-V 目标环境
- **THEN** SBOM 运行记录 SHALL 引用该次分析实际使用的源环境快照和目标环境快照

### Requirement: MVP 测试存储只保存摘要
MVP 数据库设计 SHALL 保存测试执行摘要字段，并 SHALL NOT 要求解析结构化测试报告格式。

#### Scenario: 测试运行完成
- **WHEN** 测试命令完成
- **THEN** 系统 SHALL 保存退出码、通过数、失败数、可选跳过数、状态、耗时和日志引用

#### Scenario: 存在结构化报告
- **WHEN** 存在 JUnit、CTest、pytest 或类似报告
- **THEN** MVP 系统 MUST NOT 要求解析或保存单个测试用例详情

### Requirement: 审计数据持久化且不包含秘密
系统 SHALL 为敏感或高影响操作保存审计记录，并 SHALL 确保审计详情不包含明文秘密。

#### Scenario: 发生敏感操作
- **WHEN** 用户登录、保存凭据、运行远程脚本、启动构建、启动测试、导出报告、归档数据、删除数据或忽略问题
- **THEN** 系统 SHALL 保存包含操作人、动作、资源、时间戳和脱敏详情的审计日志记录

### Requirement: 构建运行表必须关联源码部署
数据库 schema SHALL 使 `build_runs` 能追溯构建使用的远端源码部署记录。

#### Scenario: 保存源码部署关联
- **WHEN** 后端创建构建运行
- **THEN** `build_runs` SHALL 保存 `source_deploy_run_id`
- **AND** `source_deploy_run_id` SHALL 外键引用 `source_deploy_runs.id`
- **AND** 后端 SHALL 保证 `project_id`、`source_revision_id`、`environment_id` 与被引用的源码部署记录一致

#### Scenario: 保存构建脚本摘要
- **WHEN** 后端保存构建运行
- **THEN** `build_runs.command` SHALL 保存脱敏后的构建命令摘要
- **AND** 完整构建脚本 SHALL 使用 `task_script_payloads` 加密保存
- **AND** `build_runs.command` MUST NOT 保存明文密码、token 或私钥

#### Scenario: 构建查询索引
- **WHEN** 后端按项目分页查询构建运行
- **THEN** 数据库 SHALL 支持按 `project_id, started_at desc` 查询
- **AND** 数据库 SHALL 支持按 `source_revision_id`、`environment_id`、`source_deploy_run_id` 和 `status` 过滤

#### Scenario: 审计详情包含输入
- **WHEN** 命令摘要、请求载荷摘要或凭据引用写入审计详情
- **THEN** 密码、私钥、token 和其他秘密 MUST 被省略或遮蔽

### Requirement: 用户表支持强制改密标记
数据库设计 SHALL 在 `users` 表中提供强制改密标记，用于超级管理员创建用户、重置密码和首次登录改密流程。

#### Scenario: 定义用户强制改密字段
- **WHEN** 定义 `users` 表字段
- **THEN** Schema SHALL 包含 `must_change_password boolean not null default false`

#### Scenario: 超级管理员创建用户
- **WHEN** 超级管理员创建本地普通用户并发送邀请
- **THEN** 后端 SHALL 创建用户记录
- **AND** 后端 SHALL 通过用户 action token 表保存邀请 token hash
- **AND** 后端 MUST NOT 在 `users` 表保存明文邀请 token

#### Scenario: 超级管理员重置密码
- **WHEN** 超级管理员重置用户密码并要求下次登录改密
- **THEN** 后端 SHALL 将该用户的 `must_change_password` 设置为 `true`

#### Scenario: 用户完成改密
- **WHEN** 用户成功修改自己的密码
- **THEN** 后端 SHALL 将该用户的 `must_change_password` 设置为 `false`

#### Scenario: 不新增邮箱验证字段
- **WHEN** 设计邀请注册和忘记密码数据库字段
- **THEN** Schema MUST NOT 增加 `email_verified_at` 或等效邮箱验证字段
- **AND** 邮箱可达性 SHALL 由用户 action token 的消费事实表达

### Requirement: Liquibase 必须创建初始 MVP Schema
后端数据库迁移 SHALL 使用 Liquibase 将空 PostgreSQL 数据库迁移为 MVP 可用 schema。

#### Scenario: 空库执行迁移
- **WHEN** PostgreSQL 数据库为空且 Liquibase 迁移被启用
- **THEN** 迁移 SHALL 创建数据库设计文档中的 MVP 表、主键、外键、检查约束、唯一约束和必要索引
- **AND** 迁移 SHALL 启用 `pgcrypto` 扩展

#### Scenario: 迁移脚本组织
- **WHEN** 维护数据库迁移脚本
- **THEN** `db.changelog-master.yaml` SHALL include 分层 changelog
- **AND** 具体 PostgreSQL DDL SHOULD 使用 formatted SQL changelog

#### Scenario: 迁移可重复执行
- **WHEN** 同一数据库已经成功执行过迁移
- **THEN** Liquibase SHALL 通过自身 changelog 记录避免重复执行同一 changeset
- **AND** 幂等 seed MUST NOT 创建重复业务数据

### Requirement: 默认超级管理员必须由初始化迁移幂等创建
数据库迁移 SHALL 在不存在默认管理员时创建默认超级管理员用户。

#### Scenario: 创建默认管理员
- **WHEN** Liquibase 执行用户初始化 changeset 且不存在 `username = 'admin'` 的用户
- **THEN** 迁移 SHALL 插入默认用户 `admin`
- **AND** `is_super_admin` SHALL 为 `true`
- **AND** `must_change_password` SHALL 为 `true`
- **AND** `status` SHALL 为 `ACTIVE`

#### Scenario: 默认密码不得明文存储
- **WHEN** 使用默认密码输入 `admin123` 初始化管理员
- **THEN** 数据库 MUST 只保存预计算后的密码哈希
- **AND** `password_hash` MUST NOT 等于 `admin123`

#### Scenario: 重复执行初始化迁移
- **WHEN** 用户表中已经存在 `username = 'admin'`
- **THEN** 初始化 changeset MUST NOT 创建第二个默认管理员
- **AND** 初始化 changeset MUST NOT 覆盖已有管理员密码

### Requirement: 初始 Schema 不得包含 MVP 排除表
Liquibase 初始 schema SHALL 遵守 MVP 边界，不得提前创建数据库设计明确排除的数据表。

#### Scenario: 检查用户角色相关表
- **WHEN** 初始 schema 迁移完成
- **THEN** 数据库 MUST NOT 包含 `roles`
- **AND** 数据库 MUST NOT 包含 `user_roles`
- **AND** 数据库 MUST NOT 包含 `project_members`

#### Scenario: 检查非 MVP 结果表
- **WHEN** 初始 schema 迁移完成
- **THEN** 数据库 MUST NOT 包含通用 `artifacts` 表
- **AND** 数据库 MUST NOT 包含 `test_cases` 表
- **AND** 数据库 MUST NOT 包含邮箱验证专用表

### Requirement: 用户名和邮箱必须保持唯一
数据库 SHALL 对用户名和非空邮箱保持唯一约束，确保管理员创建和修改用户时不会产生重复登录标识；用户名还 SHALL 受最大长度约束保护。

#### Scenario: 用户名唯一
- **WHEN** 创建或维护 `users` 表
- **THEN** 数据库 SHALL 对 `users.username` 建立唯一索引或唯一约束

#### Scenario: 用户名最大长度
- **WHEN** 创建或维护 `users` 表
- **THEN** 数据库 SHALL 约束 `users.username` 最大 64 字符
- **AND** 数据库 MUST NOT 接受超过 64 字符的用户名

#### Scenario: 非空邮箱唯一
- **WHEN** 创建或维护 `users` 表
- **THEN** 数据库 SHALL 对非空 `users.email` 建立唯一索引或唯一约束
- **AND** 多个空邮箱 MAY 被允许

### Requirement: 项目名称允许重复
数据库 SHALL 允许多个项目使用相同名称，项目唯一识别以 `projects.id` 为准。

#### Scenario: 不创建项目名称唯一约束
- **WHEN** 创建或维护 `projects` 表
- **THEN** 数据库 MUST NOT 对 `projects.name` 建立全局唯一约束
- **AND** 数据库 MUST NOT 对 `created_by, name` 建立唯一约束

#### Scenario: 保存同名项目
- **WHEN** 同一用户或不同用户创建名称相同的项目
- **THEN** 数据库 SHALL 允许保存这些项目
- **AND** 每个项目 SHALL 通过不同 `projects.id` 区分

### Requirement: 用户 action token 必须持久化为 hash
数据库 SHALL 使用 `user_action_tokens` 表保存邀请注册和忘记密码的一次性 token hash。

#### Scenario: 创建用户 action token 表
- **WHEN** 执行数据库迁移
- **THEN** Schema SHALL 创建 `user_action_tokens` 表
- **AND** 表 SHALL 包含 `id`、`user_id`、`token_type`、`token_hash`、`expires_at`、`consumed_at`、`created_by`、`created_at` 和 `metadata`
- **AND** `token_type` SHALL 至少支持 `INVITATION` 和 `PASSWORD_RESET`

#### Scenario: 保存 token
- **WHEN** 后端生成邀请或密码重置 token
- **THEN** 数据库 SHALL 只保存 token hash
- **AND** 数据库 MUST NOT 保存明文 token
- **AND** `expires_at` SHALL 为必填字段

#### Scenario: 限制 token 查询路径
- **WHEN** 创建 `user_action_tokens` 表
- **THEN** 数据库 SHALL 为 `token_hash` 建立唯一索引
- **AND** SHALL 为 `user_id, token_type, consumed_at` 建立索引
- **AND** SHALL 为 `expires_at` 建立索引

#### Scenario: 消费 token
- **WHEN** 用户接受邀请或确认密码重置
- **THEN** 后端 SHALL 设置 `consumed_at`
- **AND** 同一个 token MUST NOT 被重复消费

### Requirement: 系统配置必须统一持久化
数据库 SHALL 使用统一 `system_configs` 表保存邮箱、通知以及后续服务配置。

#### Scenario: 创建系统配置表
- **WHEN** 执行数据库迁移
- **THEN** Schema SHALL 创建 `system_configs` 表
- **AND** 表 SHALL 包含 `id`、`namespace`、`config_key`、`config_value`、`value_type`、`encrypted`、`description`、`updated_by`、`created_at` 和 `updated_at`
- **AND** 数据库 SHALL 对 `namespace, config_key` 建立唯一约束

#### Scenario: 保存 secret 配置
- **WHEN** 配置项为 SMTP 密码、token 或其他 secret
- **THEN** `encrypted` SHALL 为 `true`
- **AND** `config_value` SHALL 保存加密后的值
- **AND** 数据库 MUST NOT 保存 secret 明文

#### Scenario: 保存非 secret 配置
- **WHEN** 配置项为 host、port、from address、TLS 开关或超时时间
- **THEN** `value_type` SHALL 表达值类型
- **AND** API 层 SHALL 在写入前完成类型和范围校验

### Requirement: jOOQ Codegen 必须基于迁移后的 Schema
jOOQ codegen SHALL 读取已由 Liquibase 迁移完成的 PostgreSQL schema，而不得创建或替代 schema 迁移。

#### Scenario: 执行 jOOQ codegen
- **WHEN** 开发者需要生成 jOOQ 代码
- **THEN** 开发者 SHALL 先对目标 PostgreSQL 数据库执行 Liquibase 迁移
- **AND** jOOQ codegen SHALL 连接迁移后的数据库读取 schema
- **AND** jOOQ 生成代码 SHALL 输出到可提交源码目录
- **AND** jOOQ 生成代码 SHALL 与对应 Liquibase schema 变更一起提交

#### Scenario: 空库尚未迁移
- **WHEN** PostgreSQL 数据库尚未执行 Liquibase 迁移
- **THEN** jOOQ codegen MUST NOT 被视为 schema 创建入口
- **AND** 生成结果不应作为数据库迁移来源

#### Scenario: 排除迁移元数据表
- **WHEN** jOOQ codegen 读取 `public` schema
- **THEN** 生成结果 MUST NOT 包含 Liquibase 元数据表对应代码
- **AND** `databasechangelog` MUST 被排除
- **AND** `databasechangeloglock` MUST 被排除

#### Scenario: 排除扩展非业务对象
- **WHEN** jOOQ codegen 读取启用了 `pgcrypto` 的 `public` schema
- **THEN** 生成结果 SHOULD 只包含业务 schema 所需的表、记录、键和索引代码
- **AND** 生成结果 SHOULD NOT 包含 `pgcrypto` 扩展 routine 对应代码

### Requirement: 环境表必须支持单跳跳板机引用
数据库设计 SHALL 允许一个执行环境可选引用同项目内另一个环境作为 SSH 跳板机，并 SHALL 保持该引用可查询、可约束和可索引。

#### Scenario: 保存跳板机引用
- **WHEN** 用户为源环境或目标环境选择跳板机
- **THEN** 数据库 SHALL 在 `environments` 表保存可空的 `jump_host_environment_id`
- **AND** `jump_host_environment_id` SHALL 外键引用 `environments.id`
- **AND** 数据库 SHALL 为 `jump_host_environment_id` 建立索引

#### Scenario: 未配置跳板机
- **WHEN** 环境不需要通过跳板机访问
- **THEN** `environments.jump_host_environment_id` SHALL 允许为空

#### Scenario: 避免自引用
- **WHEN** 保存环境跳板机引用
- **THEN** 数据库或服务层 MUST NOT 允许 `jump_host_environment_id` 等于该环境自身 `id`

#### Scenario: 同项目跳板机约束
- **WHEN** 保存环境跳板机引用
- **THEN** 服务层 MUST 验证目标环境和跳板机环境属于同一项目
- **AND** 服务层 MUST 验证跳板机环境已启用

#### Scenario: MVP 不支持多级跳板
- **WHEN** 保存环境跳板机引用
- **THEN** 服务层 MUST NOT 允许被引用的跳板机环境自身再配置跳板机

### Requirement: SBOM 运行时必须复用现有数据库表
SBOM 分析运行时 SHALL 优先复用现有环境快照、环境组件、任务、工具执行和 SBOM 结果表，不得为 MVP 引入通用 artifact 表。SBOM 分析 MAY 引入仅服务 SBOM 领域的专用分析结果明细表和轻量 artifact 摘要表，用于保存可查询的结果行和非敏感审计摘要。

#### Scenario: 保存环境组件来源
- **WHEN** 环境采集写入 `environment_components`
- **THEN** `source` SHALL 使用稳定来源值表达组件来源
- **AND** 来源值 SHALL 至少包含 `INSTALLED_PACKAGE`、`INSTALLED_LIBRARY`、`TOOL_VERSION`、`NATIVE_FILE`、`REPOSITORY_PACKAGE` 和 `REPOSITORY_PROVIDE`

#### Scenario: 保存 SBOM 运行结果
- **WHEN** SBOM_ANALYSIS 成功完成
- **THEN** 后端 SHALL 使用 `sbom_runs` 保存运行记录
- **AND** SHALL 使用 `sbom_components` 保存组件事实
- **AND** SHALL 使用 `replacement_candidates` 保存替代候选
- **AND** SHALL 使用 `tool_runs` 和 `task_logs` 保存分析器执行记录和日志元数据
- **AND** SHALL 使用 `sbom_analysis_result_items` 按行保存分析结果明细
- **AND** SHALL 使用 `sbom_analysis_artifacts` 保存轻量分析摘要 artifact

#### Scenario: 不新增通用 artifact 表
- **WHEN** 保存分析器输入、输出或日志
- **THEN** 后端 MUST NOT 引入通用 artifact 表
- **AND** 完整日志 SHALL 继续通过 `task_logs` 元数据和本地日志文件管理
- **AND** 可查询结果明细 SHALL 保存到 SBOM 专用 `sbom_analysis_result_items` 表
- **AND** 可追溯摘要 SHALL 保存到 `summary`、`metadata` JSON 字段或 SBOM 专用 `sbom_analysis_artifacts` 表

#### Scenario: 保存 SBOM 分析结果明细
- **WHEN** 后端写入 `sbom_analysis_result_items`
- **THEN** 每条记录 SHALL 表示一条 package、library、tool 或 native file 分析结果
- **AND** 每条记录 SHALL 同时关联 `project_id`、`task_id` 和 `sbom_run_id`
- **AND** 记录 SHALL 保存组件类型、源名称、源版本、源架构、源包名、目标匹配状态、目标名称、目标版本、目标包名、风险等级、匹配策略、建议动作和创建时间等可查询字段
- **AND** 记录 MAY 保存有限 `evidence` JSONB
- **AND** `evidence` MUST 只保存解释匹配所需的有限证据，不得保存完整 analyzer output 原文

#### Scenario: 索引 SBOM 分析结果明细查询路径
- **WHEN** 创建 `sbom_analysis_result_items` 表
- **THEN** 数据库 SHALL 为 `sbom_run_id, created_at DESC` 建立索引
- **AND** SHALL 为 `project_id, task_id` 建立索引
- **AND** SHALL 为 `sbom_run_id, component_type` 建立索引
- **AND** SHALL 为 `sbom_run_id, source_name` 建立索引
- **AND** SHALL 为 `sbom_run_id, risk_level` 建立索引
- **AND** SHALL 为 `sbom_run_id, target_match_status` 建立索引

#### Scenario: 保存 SBOM 专用 artifact 关联
- **WHEN** 后端写入 `sbom_analysis_artifacts`
- **THEN** 每条记录 SHALL 同时关联 `project_id`、`task_id` 和 `sbom_run_id`
- **AND** `project_id` SHALL 引用该 SBOM 运行所属项目
- **AND** `task_id` SHALL 引用该 SBOM 运行对应的 `SBOM_ANALYSIS` 任务
- **AND** `sbom_run_id` SHALL 引用该 SBOM 运行记录

#### Scenario: 限制 SBOM artifact 类型和唯一性
- **WHEN** 后端写入 `sbom_analysis_artifacts`
- **THEN** `artifact_type` SHALL 使用稳定枚举值
- **AND** 同一 `sbom_run_id` 下同一 `artifact_type` MUST NOT 重复
- **AND** 同一 `task_id` 下同一 `artifact_type` MUST NOT 重复
- **AND** `size_bytes` MUST 大于 0

#### Scenario: 索引 SBOM artifact 查询路径
- **WHEN** 创建 `sbom_analysis_artifacts` 表
- **THEN** 数据库 SHALL 为 `project_id, created_at DESC` 建立索引
- **AND** SHALL 为 `task_id` 建立索引
- **AND** SHALL 为 `sbom_run_id` 建立索引
- **AND** SHALL 为 `project_id, task_id` 建立索引

### Requirement: SBOM 结果必须引用实际使用的环境快照
SBOM 运行 SHALL 明确引用源环境快照和目标环境快照，保证分析结果可复现。

#### Scenario: 创建 SBOM 运行
- **WHEN** 后端创建 `sbom_runs` 记录
- **THEN** `source_snapshot_id` SHALL 指向本次分析实际使用的源环境快照
- **AND** `target_snapshot_id` SHALL 指向本次分析实际使用的目标环境快照
- **AND** 快照所属环境和项目 SHALL 与 `sbom_runs` 的项目、源环境和目标环境一致

#### Scenario: 快照被后续采集替代
- **WHEN** 同一环境后续产生新快照
- **THEN** 已有 `sbom_runs` MUST continue 引用原始快照
- **AND** 后端 MUST NOT 自动把历史 SBOM 运行切换到新快照

### Requirement: SBOM 数据不得保存敏感信息
SBOM 相关表中的摘要、元数据和 artifact 内容 SHALL 避免保存明文凭据、私钥、token、passphrase、完整任务日志和服务器真实绝对路径。

#### Scenario: 写入分析器摘要
- **WHEN** 后端保存 `sbom_runs.summary`、`sbom_components.metadata` 或 `replacement_candidates.metadata`
- **THEN** 写入内容 MUST NOT 包含明文凭据
- **AND** 写入内容 MUST NOT 包含服务器真实绝对路径
- **AND** 必要路径信息 SHALL 使用文件名、远端相对摘要、哈希或遮蔽值表达

#### Scenario: 写入 SBOM artifact 和结果明细
- **WHEN** 后端保存 `sbom_analysis_artifacts.content` 或 `sbom_analysis_artifacts.summary`
- **THEN** 写入内容 MUST NOT 包含明文凭据、私钥、token 或 passphrase
- **AND** 写入内容 MUST NOT 包含完整任务日志
- **AND** 写入内容 MUST NOT 复制完整 `environment_snapshots.raw_snapshot`
- **AND** 写入内容 MUST NOT 包含服务器真实绝对路径
- **AND** artifact SHALL 保存 `sha256` 和 `size_bytes` 以支持审计
- **AND** `sbom_analysis_result_items.evidence` MUST NOT 包含完整 analyzer output 原文

#### Scenario: 写入失败摘要
- **WHEN** SBOM_ANALYSIS 失败并保存任务错误信息或 tool run summary
- **THEN** 错误信息 MUST NOT 包含明文凭据、服务器真实绝对路径或完整分析器输入 JSON

### Requirement: 用户 action token 历史数据必须按保留期清理
数据库中的 `user_action_tokens` 历史记录 SHALL 按 30 天保留期清理，以控制历史 token 数据增长。

#### Scenario: 清理已消费历史 token
- **WHEN** 后台清理任务执行
- **THEN** 数据库 SHALL 删除 `consumed_at` 早于当前时间 30 天前的 `user_action_tokens` 记录

#### Scenario: 清理已过期历史 token
- **WHEN** 后台清理任务执行
- **THEN** 数据库 SHALL 删除 `expires_at` 早于当前时间 30 天前的 `user_action_tokens` 记录

#### Scenario: 保留仍在保留期内的 token
- **WHEN** 后台清理任务执行
- **THEN** 数据库 MUST NOT 删除 `consumed_at` 或 `expires_at` 未早于当前时间 30 天前的 token

#### Scenario: 保留仍有效 token
- **WHEN** 后台清理任务执行
- **THEN** 数据库 MUST NOT 删除未消费且未过期的 token

### Requirement: 数据库必须安全保存脚本正文引用
数据库 SHALL 提供受控脚本正文存储，使 `CUSTOM_BASH` 任务可以在 worker 执行时读取脚本正文，同时避免脚本正文进入任务输入快照和事件流。

#### Scenario: 创建脚本正文存储
- **WHEN** 执行数据库迁移
- **THEN** Schema SHALL 创建脚本正文存储表或等效持久化结构
- **AND** 每条记录 SHALL 关联 `project_id`、`task_id`、创建人和创建时间
- **AND** 每条记录 SHALL 保存脚本 SHA256、脚本长度和加密后的脚本正文
- **AND** 数据库 MUST NOT 在普通明文字段保存脚本正文

#### Scenario: 脚本正文与任务唯一关联
- **WHEN** 后端保存 `CUSTOM_BASH` 脚本正文
- **THEN** 该脚本正文 SHALL 关联唯一任务
- **AND** 数据库 SHALL 防止同一任务关联多个活跃脚本正文
- **AND** 任务输入快照 SHALL 只引用脚本正文记录 ID、脚本 SHA256 和脚本长度

#### Scenario: 脚本正文查询路径
- **WHEN** worker 根据任务读取脚本正文
- **THEN** 数据库 SHALL 支持按 `task_id` 查询脚本正文记录
- **AND** 数据库 SHALL 支持按 `project_id, created_at desc` 查询脚本正文摘要
- **AND** API 查询 MUST NOT 返回加密脚本正文或明文脚本正文

#### Scenario: 脚本正文安全边界
- **WHEN** 写入或读取脚本正文
- **THEN** 后端 SHALL 使用应用层加密保护脚本正文
- **AND** 审计日志、任务事件、任务日志摘要和 API 响应 MUST NOT 包含脚本正文

### Requirement: 数据库必须持久化任务日志事件
数据库 SHALL 提供 `task_log_events` 或等效持久化结构，用于任务级日志 SSE 的历史补发、断线恢复和实时展示。

#### Scenario: 创建任务日志事件表
- **WHEN** 执行数据库迁移
- **THEN** Schema SHALL 创建 `task_log_events` 表
- **AND** 表 SHALL 包含 `id`、`project_id`、`task_id`、`log_type`、`sequence`、`content` 和 `created_at`
- **AND** `id` SHALL 是单调递增游标
- **AND** `project_id` SHALL 引用 `projects.id`
- **AND** `task_id` SHALL 引用 `tasks.id`

#### Scenario: 限制日志事件类型
- **WHEN** 创建或维护 `task_log_events`
- **THEN** `log_type` SHALL 限定为 `STDOUT` 或 `STDERR`
- **AND** `content` SHALL 为非空受控长度文本
- **AND** 同一任务内 `sequence` SHALL 可用于稳定排序

#### Scenario: 索引任务日志事件恢复路径
- **WHEN** 创建 `task_log_events` 表
- **THEN** 数据库 SHALL 为 `task_id, id` 建立索引
- **AND** SHALL 为 `project_id, task_id, id` 建立索引
- **AND** SHALL 为 `task_id, sequence` 建立唯一索引或等效约束

#### Scenario: 日志事件不替代完整日志文件
- **WHEN** worker 写入 stdout 或 stderr
- **THEN** 数据库 SHALL 保存任务日志事件用于实时展示
- **AND** `task_logs` SHALL 继续保存完整日志文件元数据
- **AND** `task_log_events.content` MUST NOT 保存明文凭据、私钥、token、passphrase 或服务器真实绝对路径

### Requirement: 数据库必须支持脚本执行记录查询
数据库设计 SHALL 允许后端从任务、工具运行、脚本正文摘要和日志事件中聚合脚本执行记录。

#### Scenario: 查询脚本执行记录列表
- **WHEN** 后端查询项目脚本执行记录
- **THEN** 数据库 SHALL 支持按 `project_id`、任务类型 `ENV_RUN_SCRIPT`、脚本类型和创建时间分页查询
- **AND** 数据库 SHALL 支持关联最近 `tool_runs` 执行结果
- **AND** 数据库 SHALL 支持关联最近日志事件摘要

#### Scenario: 查询脚本执行详情
- **WHEN** 后端查询单个脚本执行记录
- **THEN** 数据库 SHALL 支持通过任务 ID 找到任务、环境引用、脚本摘要、工具执行记录和日志统计
- **AND** 查询结果 MUST NOT 需要读取明文脚本正文

### Requirement: 项目表必须保存源码来源锁定字段
数据库 SHALL 在 `projects` 表保存项目级源码来源锁定状态，用于约束后续源码入库方式。

#### Scenario: 创建源码来源锁定字段
- **WHEN** 执行数据库迁移
- **THEN** Schema SHALL 在 `projects` 表增加 `source_input_type text nullable`
- **AND** `source_input_type` SHALL 只允许 `ARCHIVE`、`GIT` 或 `null`
- **AND** `null` SHALL 表示项目尚未成功入库源码

#### Scenario: 回填已有项目源码来源
- **WHEN** 数据库中已有项目存在源码版本
- **THEN** 迁移 SHALL 根据该项目当前 active 源码版本的 `revision_type` 回填 `source_input_type`
- **AND** 如果没有 active 源码版本但存在历史源码版本，迁移 SHALL 根据最早成功源码版本的 `revision_type` 回填
- **AND** 如果项目没有源码版本，`source_input_type` SHALL 保持 `null`

#### Scenario: 混合历史源码来源处理
- **WHEN** 历史项目同时存在 `ARCHIVE` 和 `GIT` 源码版本
- **THEN** 迁移 SHALL 使用 active 源码版本的 `revision_type` 作为锁定来源
- **AND** 如果没有 active 源码版本，迁移 SHALL 使用最早成功源码版本的 `revision_type`
- **AND** 后续业务请求 SHALL 按回填后的锁定来源执行校验

#### Scenario: 源码版本保存工作副本路径
- **WHEN** 创建或维护 `source_revisions` 表
- **THEN** `working_copy_path` SHALL 用于保存压缩包解包目录或 Git checkout 工作副本目录
- **AND** `archive_path` SHALL 只保存原始上传包路径
- **AND** API 响应、任务事件和审计摘要 MUST NOT 暴露这两个服务器本地路径

#### Scenario: 索引源码来源查询路径
- **WHEN** 后端查询项目列表、项目详情或校验源码入库方式
- **THEN** 数据库 SHALL 支持高效读取 `projects.source_input_type`
- **AND** 如实现需要按源码来源筛选项目，数据库 MAY 为 `source_input_type` 建立索引

### Requirement: KB 扫描运行表必须支持异步任务和摘要查询
数据库 SHALL 使用 `kb_scan_runs` 保存 KB 扫描运行事实，支持按项目、源码、执行上下文和状态查询，并持久化新扫描所绑定的 x86 构建身份。

#### Scenario: 创建 KB 扫描运行
- **WHEN** 后端创建 KB 扫描任务
- **THEN** 数据库 SHALL 保存 `kb_scan_runs` 记录
- **AND** `project_id` SHALL 关联项目
- **AND** `task_id` SHALL 唯一关联 `tasks.id`
- **AND** `source_revision_id` SHALL 关联被扫描源码版本
- **AND** 新服务 SHALL 完整写入 `source_environment_id`、`runtime_environment_version` 和 `build_run_id`
- **AND** `build_run_id` SHALL 关联经服务验证与项目、源码、源环境、运行环境版本一致的成功 `SOURCE_ONLY` 构建
- **AND** `status` SHALL 使用 `PENDING`、`QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED`、`CANCELLED` 或 `TIMEOUT`
- **AND** `scope` SHALL 保存扫描范围、排除路径、语言、目标架构和规则集摘要

#### Scenario: 更新运行摘要
- **WHEN** KB 扫描完成或失败
- **THEN** `kb_scan_runs.summary` SHALL 保存 schema version、tool version、rule pack、文件数、finding 数量、severity counts、diagnostics 摘要和 artifact 摘要
- **AND** compile database 降级时 SHALL 仍保存绑定构建身份及安全原因
- **AND** summary MUST NOT 保存源码工作副本路径、scanner 输出目录、明文凭据或完整 stdout/stderr
- **AND** 执行结果更新 MUST NOT 改写创建时绑定的执行上下文

#### Scenario: 查询 KB 扫描运行
- **WHEN** 创建或维护 `kb_scan_runs`
- **THEN** 数据库 SHALL 保留 `project_id, started_at desc` 和 `source_revision_id` 的既有索引
- **AND** 数据库 SHALL 提供支持项目、源码、源环境、运行环境版本和构建 ID 联合筛选的索引
- **AND** 如实现支持按状态过滤，数据库 MAY 为 `project_id, status, started_at desc` 建立索引
- **AND** repository SHALL 在筛选有效上下文后执行活动优先、创建时间与 ID 稳定排序
- **AND** 计数查询与分页查询 SHALL 使用一致过滤条件

#### Scenario: 旧数据升级
- **WHEN** 数据库从没有扫描上下文列的版本升级
- **THEN** 迁移 SHALL 以可空字段保留旧行，并允许新增上下文全空或完整填写，拒绝部分填写
- **AND** 旧记录 SHALL 保留原状态、finding 和 artifact 关联
- **AND** 迁移 MUST NOT 根据当前环境或 summary 中不完整信息推测回填
- **AND** 新查询 SHALL 将上下文缺失的旧行判为历史，不能作为当前扫描

#### Scenario: 迁移与生成类型验证
- **WHEN** 实施数据库变更
- **THEN** SHALL 在独立 Docker 数据库验证空库初始化、旧数据升级、重复迁移、外键和上下文完整性约束
- **AND** SHALL 在容器内生成 jOOQ 类型并以当前源码完成编译与单元验证
- **AND** 迁移或代码生成验证 MUST NOT 替代业务单元测试和覆盖率门槛

### Requirement: Findings 表必须支持 KB 扫描结果分页审计
数据库 SHALL 将 KB Scanner 输出的每条 finding 保存为独立 `findings` 记录。

#### Scenario: 保存 finding
- **WHEN** 后端导入 `scan-result.json`
- **THEN** 每条 finding SHALL 保存 `project_id`、`scan_run_id`、`source_revision_id`、`rule_id`、`category`、`severity`、`title`、`file_path`、`line_start`、`line_end`、`symbol`、`evidence`、`suggestion`、`fingerprint`、`status` 和 `created_at`
- **AND** `status` SHALL 使用 `DETECTED` 或 `SUPPRESSED`
- **AND** `evidence` SHALL 保存受控 scanner evidence、confidence、snippet、trace、condition macros 和 build context
- **AND** `evidence` MUST NOT 保存服务器真实路径、完整 scanner output、明文凭据或完整任务输入快照

#### Scenario: Finding 行号约束
- **WHEN** 创建或更新 finding
- **THEN** `line_start` 和 `line_end` SHALL 在非空时大于 0
- **AND** `line_end` 在非空时 SHALL 大于或等于 `line_start`

#### Scenario: Finding 查询索引
- **WHEN** 创建或维护 `findings` 表
- **THEN** 数据库 SHALL 为 `scan_run_id` 建立索引
- **AND** 数据库 SHALL 为 `project_id, severity` 建立索引
- **AND** 数据库 SHALL 为 `source_revision_id` 建立索引
- **AND** 数据库 SHALL 为 `fingerprint` 建立索引
- **AND** 数据库 SHALL 为 `file_path` 建立索引

### Requirement: KB 扫描 artifact 元数据必须受控保存
数据库 SHALL 支持保存 KB 扫描输出文件的轻量 metadata 或等效报告记录，避免 API 暴露本地文件系统路径。

#### Scenario: 保存 KB artifact
- **WHEN** 后端保存 `scan-result.json`、`scan-report.html` 或 `scan.log` 的 artifact metadata
- **THEN** 每条 artifact SHALL 关联 `project_id`、`task_id` 和 `scan_run_id`
- **AND** artifact SHALL 保存 artifact type、schema version、sha256、size bytes、summary、created at 和受控文件引用
- **AND** artifact type SHALL 至少能表达 `SCAN_RESULT_JSON`、`SCAN_REPORT_HTML` 和 `SCAN_LOG`

#### Scenario: Artifact 内容限制
- **WHEN** 后端保存 KB artifact metadata、summary 或 content
- **THEN** 数据库 MUST NOT 保存明文凭据、私钥、token、passphrase、完整任务输入快照或未经脱敏的完整日志
- **AND** API 可见字段 MUST NOT 暴露后端服务器真实绝对路径

#### Scenario: Artifact 查询索引
- **WHEN** 创建或维护 KB artifact metadata
- **THEN** 数据库 SHALL 支持按 `scan_run_id` 查询 artifact
- **AND** 数据库 SHALL 支持按 `task_id` 追溯 artifact
- **AND** 数据库 SHALL 防止同一扫描运行下同一 artifact type 出现不可解释的重复成功记录

### Requirement: 数据库必须持久化源码版本迁移输入集合
数据库 SHALL 提供迁移输入集合表，用于表达一个源码版本下的一组迁移输入 reference。

#### Scenario: 创建迁移输入集合表
- **WHEN** 执行数据库迁移
- **THEN** Schema SHALL 创建 `migration_input_sets` 表
- **AND** 表 SHALL 包含 `id`、`project_id`、`source_revision_id`、`status`、`created_at` 和 `updated_at`
- **AND** `project_id` SHALL 引用 `projects.id`
- **AND** `source_revision_id` SHALL 引用 `source_revisions.id`
- **AND** `status` SHALL 至少支持 `ACTIVE` 和 `ARCHIVED`

#### Scenario: 限制源码版本唯一集合
- **WHEN** 创建或维护 `migration_input_sets`
- **THEN** 数据库 SHALL 防止同一 `source_revision_id` 出现多个 active 迁移输入集合
- **AND** 数据库 SHALL 支持按 `project_id, source_revision_id` 查询集合

### Requirement: 数据库必须持久化迁移输入 reference 列表
数据库 SHALL 提供迁移输入 reference 表，用于统一展示候选问题并引用真实来源记录。

#### Scenario: 创建迁移输入 reference 表
- **WHEN** 执行数据库迁移
- **THEN** Schema SHALL 创建 `migration_input_refs` 表
- **AND** 表 SHALL 包含 `id`、`input_set_id`、`project_id`、`source_revision_id`、`issue_title`、`source_type`、`source_ref_id`、`detected_level`、`location_label`、`recommendation_summary`、`status`、`note`、`created_at` 和 `updated_at`
- **AND** `input_set_id` SHALL 引用 `migration_input_sets.id`
- **AND** `project_id` SHALL 引用 `projects.id`
- **AND** `source_revision_id` SHALL 引用 `source_revisions.id`

#### Scenario: 限制 reference 状态
- **WHEN** 创建或更新 `migration_input_refs`
- **THEN** `status` SHALL 只允许 `CANDIDATE`、`CONFIRMED` 或 `EXCLUDED`

#### Scenario: 防止重复导入
- **WHEN** 同一迁移输入集合中导入相同 `source_type` 和 `source_ref_id`
- **THEN** 数据库 SHALL 通过唯一约束或等效机制防止重复 reference

#### Scenario: 索引迁移输入列表查询路径
- **WHEN** 创建 `migration_input_refs`
- **THEN** 数据库 SHALL 为 `project_id, source_revision_id, created_at desc` 建立索引
- **AND** 数据库 SHALL 为 `project_id, source_revision_id, source_type` 建立索引
- **AND** 数据库 SHALL 为 `project_id, source_revision_id, status` 建立索引
- **AND** 数据库 SHALL 为 `project_id, source_revision_id, detected_level` 建立索引

#### Scenario: 多来源引用由服务层校验
- **WHEN** `migration_input_refs.source_ref_id` 指向不同来源表
- **THEN** 数据库 MAY 不为 `source_ref_id` 创建单一外键
- **AND** 服务层 MUST 按 `source_type` 校验真实来源记录存在、项目归属和源码版本约束

### Requirement: 报告表必须绑定源码版本
数据库 SHALL 支持将迁移评估报告显式绑定到生成时使用的源码版本。

#### Scenario: 保存报告源码版本
- **WHEN** 后端创建新的迁移评估报告记录
- **THEN** `reports.source_revision_id` SHALL 保存生成报告使用的 `source_revisions.id`
- **AND** `reports.source_revision_id` SHALL 作为外键引用 `source_revisions(id)`
- **AND** 新报告记录的 `source_revision_id` MUST NOT 为空

#### Scenario: 查询源码版本报告
- **WHEN** 后端按项目和源码版本查询报告列表或最新报告
- **THEN** 数据库 SHALL 提供 `reports(project_id, source_revision_id, generated_at desc)` 或等效索引

### Requirement: 报告输入表必须保存迁移输入快照
数据库 SHALL 使用 `report_inputs` 保存报告生成时使用的迁移输入引用和快照。

#### Scenario: 保存迁移输入引用
- **WHEN** 报告生成使用一条迁移输入 reference
- **THEN** `report_inputs.input_type` SHALL 保存 `MIGRATION_INPUT_REF`
- **AND** `report_inputs.input_id` SHALL 保存 `migration_input_refs.id`
- **AND** `report_inputs.input_snapshot` SHALL 保存生成时的结构化快照

#### Scenario: 查询报告输入
- **WHEN** 后端查询某个报告的输入快照
- **THEN** 数据库 SHALL 能通过 `report_inputs.report_id` 高效查询
- **AND** 数据库 SHALL 能通过 `report_inputs(input_type, input_id)` 追溯输入来源

### Requirement: 报告输出制品必须使用对象存储元数据
数据库 SHALL 使用统一输出制品元数据记录迁移评估报告 HTML 和 JSON 输出位置，避免在报告业务表中保存大文件内容。

#### Scenario: 保存报告输出制品元数据
- **WHEN** 报告生成成功
- **THEN** 后端 SHALL 写入 `stored_artifacts` 元数据记录 HTML 报告制品
- **AND** 后端 SHALL 写入 `stored_artifacts` 元数据记录 JSON 报告制品
- **AND** `owner_type` SHALL 为 `MIGRATION_REPORT`
- **AND** `owner_id` SHALL 为 `reports.id`
- **AND** API MUST NOT 直接暴露 `bucket`、`object_key` 或服务器真实路径

#### Scenario: 历史路径字段兼容
- **WHEN** 数据库中存在历史 `reports.html_path` 或 `reports.json_path`
- **THEN** 这些字段 MAY 仅作为后端内部兼容读取字段
- **AND** 新生成报告 SHALL 优先使用 `stored_artifacts` 表达输出制品

### Requirement: 数据库设计说明必须满足严格校验
`backend-database-design` 主 spec 的 Purpose SHALL 清晰描述数据库设计能力的持久化边界、数据安全边界、索引查询目标和与运行时模块的关系。

#### Scenario: 严格校验数据库设计说明
- **WHEN** 执行 `openspec validate backend-database-design --type spec --strict`
- **THEN** 该 spec SHALL 不再因为 `Purpose section is too brief` 失败

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

### Requirement: 数据库必须支持远程自动化测试输出和日志引用
数据库 SHALL 支持保存自动化测试在远程环境中的执行摘要、输出摘要、日志引用和报告产物引用，并 SHALL 允许通过项目、源码版本、环境和状态查询。

#### Scenario: 保存测试输出摘要
- **WHEN** 自动化测试任务创建或完成
- **THEN** 数据库 SHALL 保存测试运行记录并关联 `project_id`、`task_id`、`source_revision_id`、`environment_id` 和可选 `build_run_id`
- **AND** 数据库 SHALL 保存退出码、状态、输出摘要、stdout 日志引用、stderr 日志引用、报告 artifact 摘要、开始时间和结束时间
- **AND** 数据库 MUST NOT 保存明文凭据、SSH 私钥、token、passphrase 或服务器真实敏感路径

#### Scenario: 测试运行查询索引
- **WHEN** 创建或维护自动化测试运行表
- **THEN** 数据库 SHALL 支持按 `project_id, started_at desc` 查询
- **AND** 数据库 SHALL 支持按 `source_revision_id`、`environment_id` 和 `build_run_id` 查询
- **AND** 数据库 SHALL 支持按 `project_id, status, started_at desc` 查询

#### Scenario: 不保存完整日志正文
- **WHEN** 测试产生 stdout 或 stderr
- **THEN** 数据库 SHALL 只保存日志文件引用、大小、哈希和受控摘要
- **AND** 完整日志正文 SHALL 继续由任务日志文件或受控制品保存

#### Scenario: 保存测试报告 artifact 引用
- **WHEN** 自动化测试回收远端文字报告文件
- **THEN** 数据库 SHALL 通过 `test_runs` 摘要字段、`stored_artifacts` 或等效受控结构保存报告 artifact 引用
- **AND** 报告 artifact SHALL 关联 `project_id`、`task_id` 和测试运行 ID
- **AND** 数据库 MUST NOT 保存服务器真实路径或远端敏感绝对路径

### Requirement: 数据库必须保存项目运行环境配置和版本
数据库 SHALL 使用专用表保存每个项目当前运行环境配置，并 SHALL 使用版本号支持历史运行结果有效性判断。

#### Scenario: 保存项目运行环境配置
- **WHEN** 创建项目运行环境配置表
- **THEN** Schema SHALL 创建 `project_runtime_environments`
- **AND** 表 SHALL 包含 `project_id`、`source_environment_id`、`target_environment_id`、`version`、`updated_by`、`created_at` 和 `updated_at`
- **AND** `project_id` SHALL 唯一并外键引用 `projects.id`
- **AND** `source_environment_id` 和 `target_environment_id` SHALL 外键引用 `environments.id`
- **AND** `source_environment_id` 和 `target_environment_id` SHALL 允许为空以支持局部设置

#### Scenario: 运行记录保存环境配置版本
- **WHEN** 创建或维护 SBOM、源码部署、构建验证和自动化测试运行表
- **THEN** `sbom_runs`、`source_deploy_runs`、`build_runs` 和 `test_runs` SHALL 保存 `environment_config_version`
- **AND** 新创建的运行记录 SHALL 写入创建时的项目运行环境配置版本
- **AND** 查询响应 SHALL 能比较运行记录版本和当前配置版本

#### Scenario: 历史记录兼容
- **WHEN** 数据库中存在没有环境配置版本的历史运行记录
- **THEN** migration SHALL 使用受控默认值填充版本字段或允许后端将其识别为未知版本
- **AND** 后端 MUST NOT 删除历史运行记录

#### Scenario: 版本查询索引
- **WHEN** 创建环境配置版本字段
- **THEN** 数据库 SHALL 为按项目、源码版本、环境配置版本和创建时间查询的路径提供必要索引
- **AND** 查询当前运行环境结果时 SHALL 能有效过滤或标识非当前版本记录

### Requirement: 数据库必须保存测试结果比较运行
数据库 SHALL 使用专用表保存测试结果比较运行记录，并 SHALL 关联项目、可选任务和两条自动化测试记录。

#### Scenario: 保存自动化测试节点批次
- **WHEN** 后端为一次自动化测试节点创建 source 和 target 两侧测试记录
- **THEN** 数据库 SHALL 保存可关联两侧测试记录的批次标识
- **AND** 批次标识 SHALL 能稳定区分同一项目内多次自动化测试运行
- **AND** 后端 MUST NOT 通过最近创建时间推断两侧测试记录归属

#### Scenario: 保存测试比较运行
- **WHEN** 后端根据自动化测试节点批次创建测试结果比较
- **THEN** 数据库 SHALL 保存 `test_comparison_runs` 记录
- **AND** 记录 SHALL 包含项目 ID、任务 ID、批次标识、source 测试运行 ID、target 测试运行 ID、状态、总体结论、风险等级、置信度、摘要、建议、错误摘要、是否发生输入裁剪和关键时间字段
- **AND** `task_id` SHALL 在存在时唯一关联 `tasks.id`
- **AND** source 和 target 测试运行外键 SHALL 引用 `test_runs.id`

#### Scenario: 保存比较不可用摘要
- **WHEN** 两侧自动化测试完成但模型未配置或不可用
- **THEN** 数据库 SHALL 保存比较运行记录
- **AND** 状态 SHALL 表达 `UNAVAILABLE` 或等效不可用状态
- **AND** 记录 SHALL 保存不可用错误码和前端可展示的脱敏原因
- **AND** `task_id` MAY 为空

#### Scenario: 查询测试比较运行
- **WHEN** 后端查询测试比较列表或详情
- **THEN** 数据库 SHALL 支持按项目、状态、source 测试运行、target 测试运行、源码版本、环境、风险等级和时间范围过滤
- **AND** 数据库 SHALL 支持按创建时间、完成时间、状态和风险等级排序

### Requirement: 数据库必须保存测试结果比较差异项
数据库 SHALL 使用专用表保存 agent 输出的结构化差异项，支持前端分页查看。

#### Scenario: 保存比较差异项
- **WHEN** agent 返回合法结构化差异项
- **THEN** 数据库 SHALL 保存 `test_comparison_findings` 记录
- **AND** 记录 SHALL 包含比较运行 ID、序号、差异类型、严重程度、置信度、标题、说明、source 摘要、target 摘要、建议和创建时间
- **AND** 差异项 SHALL 外键引用 `test_comparison_runs.id`

#### Scenario: 查询比较差异项
- **WHEN** 后端查询比较差异项
- **THEN** 数据库 SHALL 支持按比较运行 ID、严重程度、差异类型、置信度和关键字过滤
- **AND** 数据库 SHALL 支持按序号、严重程度、置信度和创建时间排序

### Requirement: 测试比较原始输出必须保存为受控制品
数据库 SHALL 通过 `stored_artifacts` 保存测试比较原始 agent 输出和必要的调试制品引用。

#### Scenario: 保存 agent 输出制品
- **WHEN** 测试结果比较任务完成、失败或 agent 输出不可解析
- **THEN** 后端 SHALL 将原始 agent 输出保存为 `stored_artifacts`
- **AND** `stored_artifacts.owner_type` SHALL 支持 `TEST_COMPARISON`
- **AND** owner ID SHALL 指向 `test_comparison_runs.id`
- **AND** 数据库 MUST NOT 将完整 agent 输出或完整输入直接塞入普通比较详情字段

### Requirement: 数据库必须保存优化轮次记录
数据库 SHALL 使用 `optimization_cycles` 表保存项目优化轮次边界，并 SHALL 通过约束保证同一项目同一源码版本只有一个优化轮次。

#### Scenario: 保存优化轮次基础字段
- **WHEN** 后端创建优化轮次
- **THEN** `optimization_cycles` SHALL 保存 `id`、`project_id`、`source_revision_id`、`cycle_number`、`status`、`summary`、`started_at`、`completed_at`、`created_at` 和 `updated_at`
- **AND** `project_id` SHALL 外键引用 `projects.id`
- **AND** `source_revision_id` SHALL 外键引用 `source_revisions.id`

#### Scenario: 约束优化轮次唯一性
- **WHEN** 后端保存优化轮次
- **THEN** 数据库 SHALL 使用唯一约束保证 `(project_id, source_revision_id)` 不重复
- **AND** 数据库 SHALL 使用唯一约束保证 `(project_id, cycle_number)` 不重复

#### Scenario: 优化轮次索引
- **WHEN** 查询项目优化轮次列表或当前轮次
- **THEN** 数据库 SHALL 提供 `project_id, cycle_number desc` 索引
- **AND** 数据库 SHALL 提供 `project_id, status` 索引
- **AND** 数据库 SHALL 提供 `source_revision_id` 索引

#### Scenario: 优化轮次摘要不保存大内容
- **WHEN** 后端写入 `optimization_cycles.summary`
- **THEN** 摘要 SHALL 只保存安全聚合信息和资源 ID
- **AND** 数据库 MUST NOT 在该字段保存完整日志、完整报告正文、完整 agent 输入、明文凭据或对象存储内部 key

### Requirement: 数据库必须保存待归档候选
数据库 SHALL 使用 `archive_candidates` 表保存当前源码版本下可归档内容的最新引用，使归档运行时可以按候选表生成快照。

#### Scenario: 创建待归档候选表
- **WHEN** 执行数据库迁移
- **THEN** Schema SHALL 创建 `archive_candidates` 表
- **AND** 表 SHALL 包含 `id`、`project_id`、`source_revision_id`、`node_type`、`candidate_type`、`candidate_key`、`resource_type`、`resource_id`、`title`、`summary`、`artifact_refs`、`status`、`created_at` 和 `updated_at`
- **AND** `project_id` SHALL 外键引用 `projects.id`
- **AND** `source_revision_id` SHALL 外键引用 `source_revisions.id`

#### Scenario: 候选唯一槽位
- **WHEN** 后端登记待归档候选
- **THEN** 数据库 SHALL 使用唯一约束保证 `(project_id, source_revision_id, node_type, candidate_type, candidate_key)` 不重复
- **AND** 后端 SHALL 通过 upsert 更新同一槽位的最新候选

#### Scenario: 候选状态约束
- **WHEN** 创建或更新待归档候选
- **THEN** `status` SHALL 只允许 `ACTIVE`、`SUPERSEDED` 或 `DELETED`
- **AND** 当前状态归档 SHALL 只读取 `ACTIVE` 候选

#### Scenario: 候选查询索引
- **WHEN** 后端创建归档或查询当前候选
- **THEN** 数据库 SHALL 支持按 `project_id, source_revision_id, status` 查询
- **AND** 数据库 SHALL 支持按 `project_id, source_revision_id, node_type, candidate_type` 查询

#### Scenario: 候选内容安全
- **WHEN** 后端保存 `summary` 或 `artifact_refs`
- **THEN** 数据库 MUST NOT 在候选表中保存完整日志、完整报告正文、完整 agent 输入、明文凭据、对象存储 object key 或服务器真实路径
- **AND** `artifact_refs` SHALL 通过 `storedArtifactId` 或受控业务资源 ID 引用内容

### Requirement: 归档记录必须绑定源码版本
数据库 SHALL 使每条归档记录明确绑定创建归档时使用的源码版本。

#### Scenario: 保存归档源码版本
- **WHEN** 后端创建归档记录
- **THEN** `archives` SHALL 保存 `source_revision_id`
- **AND** `source_revision_id` SHALL 外键引用 `source_revisions.id`
- **AND** 归档记录 SHALL 保存创建时的项目 active 源码版本 ID

#### Scenario: 查询源码版本归档
- **WHEN** 后端查询项目归档列表
- **THEN** 数据库 SHALL 支持按 `project_id, source_revision_id, created_at desc` 查询
- **AND** 数据库 SHALL 支持按 `project_id, status, created_at desc` 查询

### Requirement: 归档项必须固化候选快照
数据库 SHALL 使用 `archive_items` 保存归档创建时的候选快照，而不是在查询历史归档时读取当前候选状态。

#### Scenario: 保存归档项候选快照
- **WHEN** 后端创建归档项
- **THEN** `archive_items` SHALL 保存 `candidate_id`、`node_type`、`candidate_type`、`candidate_key`、`resource_type`、`resource_id`、`title`、`artifact_refs` 和 `metadata`
- **AND** `candidate_id` MAY 引用 `archive_candidates.id`
- **AND** `metadata` SHALL 保存创建归档时的安全摘要快照

#### Scenario: 归档项查询索引
- **WHEN** 后端查询归档详情
- **THEN** 数据库 SHALL 支持按 `archive_id` 查询归档项
- **AND** 数据库 SHALL 支持按 `archive_id, node_type, candidate_type` 查询归档项

#### Scenario: 归档项安全
- **WHEN** 后端保存归档项 metadata 或 artifact refs
- **THEN** 数据库 MUST NOT 保存明文凭据、对象存储 object key、服务器真实路径或完整任务输入快照

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

### Requirement: 数据库必须支持构建上下文 artifact 引用
数据库 SHALL 支持构建运行保存 compile database 输出制品引用，并 SHALL 支持 KB 扫描按项目和源码版本查询最新可用构建上下文。

#### Scenario: 构建运行保存 compile database 摘要
- **WHEN** 构建验证成功并发现可用 `compile_commands.json`
- **THEN** `build_runs.build_facts` SHALL 保存 compile database 的可用状态、stored artifact ID、sha256、size bytes、command count、来源环境、构建范围和来源摘要
- **AND** `build_facts` MUST NOT 保存完整 `compile_commands.json`、对象存储 key、服务器真实路径或远端绝对路径

#### Scenario: stored artifacts 支持构建归属
- **WHEN** 数据库维护 `stored_artifacts.owner_type` 约束
- **THEN** `owner_type` SHALL 支持 `BUILD_VALIDATION`
- **AND** `owner_id` SHALL 能引用对应业务 owner 的 ID
- **AND** 构建上下文 artifact SHALL 使用 `build_runs.id` 作为 owner ID

#### Scenario: 查询最新可用构建上下文
- **WHEN** KB 扫描需要按项目和源码版本查询最新成功构建上下文
- **THEN** 数据库 SHALL 支持按 `project_id`、`source_revision_id`、`build_scope`、`status` 和完成时间查询 `build_runs`
- **AND** 查询 SHALL 能过滤或识别 `build_facts.compileDatabase.available=true` 的记录
- **AND** 查询 MUST NOT 依赖跨源码版本的最近构建记录

#### Scenario: 对象存储元数据安全
- **WHEN** 保存构建上下文 artifact 元数据
- **THEN** `stored_artifacts.summary` MUST NOT 保存完整日志、完整 compile database、明文凭据、服务器真实路径、远端工作目录、bucket 或 object key

### Requirement: 数据库必须独立保存模型连通性测试结果
数据库 SHALL 支持保存模型 profile 最近连通性测试摘要，使模型 provider 可用性不与 agent runtime 兼容性混淆。

#### Scenario: 保存最近连通性结果
- **WHEN** 模型 profile 连通性测试完成
- **THEN** 数据库 SHALL 保存 profile 级最近连通性状态、脱敏消息、测试详情、测试时间和耗时
- **AND** 连通性结果 SHALL NOT 依赖 agent runtime ID
- **AND** 数据库 MUST NOT 保存 API key 明文、授权头、完整 prompt 或服务器真实敏感路径

#### Scenario: 查询连通性摘要
- **WHEN** 后端查询模型 profile 列表或详情
- **THEN** 数据库 SHALL 支持返回最近连通性状态和最近测试时间
- **AND** 查询 SHALL NOT 读取或返回 API key 密文字段作为响应内容

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

### Requirement: 数据库必须持久化环境配置版本及结果归属
数据库 SHALL 持久化单环境配置版本，并 SHALL 使环境任务和环境快照能够证明其所属配置版本。

#### Scenario: 环境表保存配置版本
- **WHEN** Liquibase 应用本 change 的迁移
- **THEN** `environments` SHALL 增加非空 `config_version`
- **AND** 现有环境 SHALL 幂等回填为版本 `1`
- **AND** 新环境 SHALL 默认从版本 `1` 开始

#### Scenario: 环境快照保存配置版本
- **WHEN** 后端保存环境采集快照
- **THEN** `environment_snapshots` SHALL 保存对应 `environment_config_version`
- **AND** 当前快照查询 SHALL 能按环境 ID 和当前配置版本筛选

#### Scenario: 环境任务保存配置版本
- **WHEN** 后端创建会影响环境当前事实的任务或执行记录
- **THEN** 持久化数据 SHALL 保存创建时的 `environment_config_version`
- **AND** 完成回写 SHALL 能通过数据库条件更新验证版本仍一致

#### Scenario: 支持当前版本查询性能
- **WHEN** 后端按环境及配置版本查询最新成功快照或活动任务
- **THEN** 数据库 SHALL 提供匹配查询模式的索引
- **AND** 查询 MUST NOT 依赖扫描全部历史快照或全部项目任务

#### Scenario: Schema 变更由 Liquibase 管理
- **WHEN** 开发者构建或部署后端
- **THEN** 所有新增表字段、默认值、约束和索引 SHALL 由 Liquibase changelog 创建
- **AND** 开发者 MUST NOT 通过手工建表替代迁移
- **AND** jOOQ 生成代码 SHALL 在迁移后的 Schema 上重新生成并保存到约定源码目录

### Requirement: 数据库必须保存共享 LLVM BC artifact-set 引用
数据库 SHALL 通过 `build_runs.build_facts` 和 `stored_artifacts` 保存共享 LLVM BC 的安全摘要、RvPort 业务关联与不可变制品引用，不得将 BC 二进制、完整 manifest 或业务主键写入中立 manifest。

#### Scenario: 构建事实保存 v2 BC 摘要
- **WHEN** source/x86 构建的 v2 BC 后处理达到终态
- **THEN** `build_runs.build_facts.bitcode` SHALL 保存状态、可用性、artifact-set ID、计数、toolchain/schema/profile、source identity digest、compile context digest、reason code、warning count、生成时间和 stored artifact ID
- **AND** 摘要 MUST NOT 保存 BC 二进制、完整 manifest、完整编译命令、环境变量值或真实绝对路径

#### Scenario: RvPort 业务关联保留在数据库元数据
- **WHEN** 系统保存 v2 manifest 或 bundle
- **THEN** project、source revision 和 build run 的关联 SHALL 由数据库记录、owner 和安全制品元数据表达
- **AND** 这些数据库 ID MUST NOT 被复制到中立 v2 manifest

#### Scenario: stored artifacts 保存 manifest 和 bundle
- **WHEN** BC artifact-set 存在可消费制品
- **THEN** `stored_artifacts.owner_type` SHALL 为 `BUILD_VALIDATION`
- **AND** `owner_id` SHALL 为对应 `build_runs.id`
- **AND** artifact type SHALL 能稳定区分 `LLVM_BITCODE_MANIFEST_JSON` 和 `LLVM_BITCODE_BUNDLE_TGZ`
- **AND** 制品元数据 SHALL 保存 schema version、artifact-set ID、SHA-256、大小、content type、source identity digest、compile context digest 和安全摘要

#### Scenario: 同一构建制品类型不得产生不可解释重复
- **WHEN** 系统保存同一 build run 的 BC manifest 或 bundle
- **THEN** 数据库或服务层 SHALL 防止同一 owner 下相同 artifact type 出现多个当前成功制品
- **AND** 重试 SHALL 复用相同 artifact-set identity 或替换未完成临时上传，不得覆盖其他构建的制品

#### Scenario: 读取历史 v1 元数据
- **WHEN** 数据库中已有 v1 BC 摘要或制品引用
- **THEN** 查询 SHALL 保留原有数据并标记为 legacy
- **AND** 系统 MUST NOT 要求通过数据库迁移回填不存在的 v2 digest

#### Scenario: 对象存储失败只记录 BC 失败
- **WHEN** manifest 或 bundle 写入对象存储失败
- **THEN** `build_facts.bitcode.status` SHALL 为 `FAILED`
- **AND** 不完整 stored artifact 元数据 MUST NOT 被查询为可用
- **AND** `build_runs.status` MUST 保持由真实构建结果决定

### Requirement: 数据库必须持久化专项分析运行
数据库 SHALL 保存 analyzer type、项目、任务、源码版本、构建、artifact set、可选环境配置版本、协议版本、状态、verdict、退出码、计数、摘要、制品引用和时间字段。

#### Scenario: 保存成功运行
- **WHEN** 工具结果通过协议校验
- **THEN** 运行记录 SHALL 在同一事务中写入终态、计数和结果制品引用

#### Scenario: 保存失败运行
- **WHEN** 输入、工具或结果导入失败
- **THEN** 运行记录 SHALL 保存失败阶段和安全错误摘要
- **AND** MUST NOT 保存成功态 Finding 计数

### Requirement: 数据库必须逐条持久化专项分析 Finding
数据库 SHALL 为每条 Finding 保存外部稳定 ID、领域类型、风险、置信度、状态、规则/类别、双语摘要、主位置、人工确认要求、semantic digest 和单条详情 JSON。

#### Scenario: 防止同运行重复 Finding
- **WHEN** 同一运行重复导入相同 external finding ID
- **THEN** 唯一约束 SHALL 阻止重复记录
- **AND** 导入事务 SHALL 回滚而不是留下半套结果

#### Scenario: 查询索引
- **WHEN** API 按项目、运行、领域、风险、状态、路径、规则或时间查询
- **THEN** 数据库 SHALL 提供对应索引支持分页过滤排序

### Requirement: 大型分析输出不得整体写入数据库单字段
数据库 SHALL 只保存安全摘要和逐条 Finding 详情；完整请求、结果 JSON、HTML 和内存序审计目录 SHALL 保存到对象存储。

#### Scenario: 保存大型结果
- **WHEN** 工具输出包含大量 Scope、证据节点或诊断文件
- **THEN** 数据库 SHALL 保存对象制品引用和聚合计数
- **AND** MUST NOT 把完整结果 bundle 写入单个 JSON 字段
