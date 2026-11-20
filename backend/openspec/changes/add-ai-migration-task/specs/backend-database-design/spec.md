# backend-database-design delta

## ADDED Requirements

### Requirement: AI 迁移运行必须持久化
后端 SHALL 提供 `ai_migration_runs` 表持久化每次 AI 迁移运行：项目、扫描运行引用、任务引用、状态、摘要、条目分类计数（item_count/done_count/false_positive_count/pending_count）、模型 profile 与 agent runtime 引用及时间戳；SHALL 建立按项目索引与外键约束。

#### Scenario: 运行生命周期写入
- **WHEN** AI 迁移创建、开始或终结
- **THEN** SHALL 在 `ai_migration_runs` 写入或更新对应行，终态含摘要与分类计数

#### Scenario: 枚举约束扩展
- **WHEN** 执行 Liquibase 迁移
- **THEN** `tasks_type_chk`、`stored_artifacts_owner_type_chk` 与 `model_stage_assignments_stage_chk` SHALL 分别允许 `AI_MIGRATION` 值，迁移 SHALL 幂等可重放
