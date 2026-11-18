# migration-input-merge Specification

## Purpose

明确旧迁移输入 reference 池、节点确认及合并接口退出新报告流程。报告直接固定当前有效分析候选，由报告草稿和不可变确认清单承载人工决定，不保留旧数据兼容。

## Requirements

### Requirement: 旧迁移输入池必须退出新报告流程
后端 SHALL 直接从有效分析结果生成报告快照，删除仅用于旧节点确认的 reference 存储、同步及专用读写接口；不维护旧数据兼容。

#### Scenario: 调用旧接口
- **WHEN** 通过认证和适用 CSRF 请求旧 migration-input 查询、import、auto-merge 或 PATCH
- **THEN** SHALL 因路由未注册返回 404，无写入或模型调用；新复核使用报告草稿接口。

#### Scenario: 四来源生成
- **WHEN** 新报告选择输入
- **THEN** SHALL 读取当前有效分析，不查询旧 reference 默认确认状态。
