## REMOVED Requirements

### Requirement: 迁移输入必须按源码版本隔离
**Reason**: 新流程先生成报告再复核，旧节点确认不再控制报告输入。
**Migration**: 弃用旧开发数据，使用报告候选快照与 report-review-workspace 草稿和确认。

### Requirement: 迁移输入列表必须使用 reference 记录统一展示
**Reason**: 新流程先生成报告再复核，旧节点确认不再控制报告输入。
**Migration**: 弃用旧开发数据，使用报告候选快照与 report-review-workspace 草稿和确认。

### Requirement: 迁移输入必须支持批量导入真实来源记录
**Reason**: 新流程先生成报告再复核，旧节点确认不再控制报告输入。
**Migration**: 弃用旧开发数据，使用报告候选快照与 report-review-workspace 草稿和确认。

### Requirement: 迁移输入确认状态必须控制报告输入
**Reason**: 新流程先生成报告再复核，旧节点确认不再控制报告输入。
**Migration**: 弃用旧开发数据，使用报告候选快照与 report-review-workspace 草稿和确认。

## ADDED Requirements

### Requirement: 旧迁移输入池必须退出新报告流程
后端 SHALL 直接从有效分析结果生成报告快照，删除仅用于旧节点确认的 reference 存储、同步及专用读写接口；不维护旧数据兼容。

#### Scenario: 调用旧接口
- **WHEN** 通过认证和适用 CSRF 请求旧 migration-input 查询、import、auto-merge 或 PATCH
- **THEN** SHALL 因路由未注册返回 404，无写入或模型调用；新复核使用报告草稿接口。

#### Scenario: 四来源生成
- **WHEN** 新报告选择输入
- **THEN** SHALL 读取当前有效分析，不查询旧 reference 默认确认状态。
