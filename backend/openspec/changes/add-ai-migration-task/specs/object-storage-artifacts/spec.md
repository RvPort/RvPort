# object-storage-artifacts delta

## ADDED Requirements

### Requirement: AI 迁移产物必须保存统一元数据
后端 SHALL 以 ownerType `AI_MIGRATION` 保存迁移产物：`MIGRATION_DIFF`（统一 diff 补丁）与 `MIGRATION_RESULT`（条目终态 JSON）；每次运行每类型保留最新一份并登记归档候选。

#### Scenario: 产物入库与下载
- **WHEN** 迁移运行产出补丁与结果文件
- **THEN** SHALL 经对象存储服务入库（上传在任务 attempt 事务外）并可经统一下载链获取
- **AND** 归档候选 SHALL 包含两类产物节点
