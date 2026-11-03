## ADDED Requirements

### Requirement: 分析完整性不由执行终态推断

Workflow、Step 和阶段内容 SHALL 保留服务端执行事实，并独立提示分析完整性。

#### Scenario: 已完成节点包含部分分析

- **WHEN** 任务为 SUCCEEDED 或节点为 DONE，但 analysisStatus=PARTIAL 或 analysisComplete=false
- **THEN** 前端 SHALL 提示“部分分析完成”，不把零正式风险解释为迁移安全
