## REMOVED Requirements

### Requirement: KB AI 扩展扫描必须作为异步任务运行
**Reason**: 独立知识库 AI 建议与报告复核重复，且部分成功不满足新增强报告完整性。
**Migration**: 以 REPORT_AI_REVIEW 个人绑定和 REPORT_GENERATE 增强模式替代；开发数据直接弃用，不保留旧查询或迁移分支。

### Requirement: KB AI 扩展扫描必须使用受控 agent workspace 文件
**Reason**: 独立知识库 AI 建议与报告复核重复，且部分成功不满足新增强报告完整性。
**Migration**: 以 REPORT_AI_REVIEW 个人绑定和 REPORT_GENERATE 增强模式替代；开发数据直接弃用，不保留旧查询或迁移分支。

### Requirement: KB AI 扩展输出必须校验后逐条入库并允许部分成功
**Reason**: 独立知识库 AI 建议与报告复核重复，且部分成功不满足新增强报告完整性。
**Migration**: 以 REPORT_AI_REVIEW 个人绑定和 REPORT_GENERATE 增强模式替代；开发数据直接弃用，不保留旧查询或迁移分支。

### Requirement: KB AI 扩展结果必须绑定源码版本和 KB scan
**Reason**: 独立知识库 AI 建议与报告复核重复，且部分成功不满足新增强报告完整性。
**Migration**: 以 REPORT_AI_REVIEW 个人绑定和 REPORT_GENERATE 增强模式替代；开发数据直接弃用，不保留旧查询或迁移分支。

### Requirement: KB AI 扩展运行必须提供查询和 readiness API
**Reason**: 独立知识库 AI 建议与报告复核重复，且部分成功不满足新增强报告完整性。
**Migration**: 以 REPORT_AI_REVIEW 个人绑定和 REPORT_GENERATE 增强模式替代；开发数据直接弃用，不保留旧查询或迁移分支。

### Requirement: KB AI 扩展制品必须登记归档候选
**Reason**: 独立知识库 AI 建议与报告复核重复，且部分成功不满足新增强报告完整性。
**Migration**: 以 REPORT_AI_REVIEW 个人绑定和 REPORT_GENERATE 增强模式替代；开发数据直接弃用，不保留旧查询或迁移分支。

### Requirement: KB AI 扩展必须配套测试覆盖
**Reason**: 独立知识库 AI 建议与报告复核重复，且部分成功不满足新增强报告完整性。
**Migration**: 以 REPORT_AI_REVIEW 个人绑定和 REPORT_GENERATE 增强模式替代；开发数据直接弃用，不保留旧查询或迁移分支。

### Requirement: KB AI 扩展必须消费 schema v2 finding
**Reason**: 独立知识库 AI 建议与报告复核重复，且部分成功不满足新增强报告完整性。
**Migration**: 以 REPORT_AI_REVIEW 个人绑定和 REPORT_GENERATE 增强模式替代；开发数据直接弃用，不保留旧查询或迁移分支。

## ADDED Requirements

### Requirement: 旧 KB AI 扩展必须完整删除
后端 SHALL 删除旧创建/readiness/list/detail/artifact 下载路由、handler、专用存储和 Finding 上的动态 aiExtension 附加，不保留历史查询或旧任务解码兼容。

#### Scenario: 请求旧 API
- **WHEN** 通过认证和适用 CSRF 请求任一旧 KB AI 路由
- **THEN** SHALL 返回未注册路由 404，且无数据写入或模型调用。

#### Scenario: 全新启动
- **WHEN** 在重置的开发实例启动新 API/worker
- **THEN** SHALL 不注册旧任务处理器或旧制品读取路径；初始化清除旧任务队列，不实现旧任务恢复。
