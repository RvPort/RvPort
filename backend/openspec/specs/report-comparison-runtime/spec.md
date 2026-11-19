## Purpose

定义基于不可变报告事实或明确确认清单版本的异步比较、身份匹配和差异查询，区分来源消失、人工排除及不再选择，避免把集合变化误判为已验证修复。

## Requirements

### Requirement: 报告比较必须基于评估报告快照
后端 SHALL 提供评估报告比较能力，默认 basis=REPORT_FACTS 使用报告生成时保存的 `report_inputs.input_snapshot`；显式 basis=CONFIRMED_FIX_LIST 时 SHALL 使用请求固定的两侧不可变确认快照，不混用草稿。

#### Scenario: 创建报告比较
- **WHEN** 已登录用户调用 `POST /api/reports/{reportId}/comparisons`
- **THEN** 后端 MUST 校验当前用户对该报告所属项目有访问权限
- **AND** 后端 MUST 使用 `reportId` 对应报告作为 target report
- **AND** REPORT_FACTS 模式下后端 MUST 使用 target report 的 `report_inputs.input_snapshot` 作为 target 比较输入
- **AND** 后端 MUST NOT 动态读取当前 `migration_input_refs` 或原始扫描结果替代报告快照

#### Scenario: 指定历史报告比较
- **WHEN** 创建报告比较请求包含 `baseReportId`
- **THEN** 后端 MUST 校验 base report 与 target report 属于同一项目
- **AND** 后端 MUST 校验 base report 早于 target report 或明确返回比较方向
- **AND** REPORT_FACTS 模式下后端 MUST 使用 base report 的 `report_inputs.input_snapshot` 作为 base 比较输入

#### Scenario: 自动选择上一份可比较报告
- **WHEN** 创建报告比较请求未包含 `baseReportId`
- **THEN** 后端 SHALL 自动选择同项目、同报告类型、早于 target report 且已成功生成的最近一份可比较报告
- **AND** 找不到可比较报告时 SHALL 返回明确业务错误

### Requirement: 报告比较查询必须支持分页过滤排序
后端 SHALL 提供报告比较详情、差异列表和差异详情查询 API，并 SHALL 支持项目访问权限校验。

#### Scenario: 查询报告比较详情
- **WHEN** 已登录用户调用 `GET /api/report-comparisons/{comparisonId}`
- **THEN** 后端 MUST 校验当前用户对比较所属项目有访问权限
- **AND** 响应 SHALL 返回 base report 摘要、target report 摘要、状态、任务引用、差异摘要和关键时间字段
- **AND** 响应 MUST NOT 暴露服务器真实路径、对象存储内部 key、明文凭据或完整任务输入快照

#### Scenario: 查询报告比较差异列表
- **WHEN** 已登录用户调用 `GET /api/report-comparisons/{comparisonId}/diff-items`
- **THEN** 后端 MUST 校验当前用户对比较所属项目有访问权限
- **AND** 响应 SHALL 支持分页
- **AND** 响应 SHALL 支持按 `diffStatus`、`sourceType`、`severity`、`matchConfidence` 和关键字过滤
- **AND** 响应 SHALL 支持按 `diffStatus`、`sourceType`、`severity`、`matchConfidence`、`title`、`locationLabel` 和创建时间排序

#### Scenario: 查询报告比较差异详情
- **WHEN** 已登录用户调用 `GET /api/report-comparisons/{comparisonId}/diff-items/{diffItemId}`
- **THEN** 后端 MUST 校验差异项属于该比较
- **AND** 响应 SHALL 返回 old item 与 new item 快照
- **AND** 新比较的 `NOT_OBSERVED` 差异 SHALL 只包含 old item；MUST NOT 用缺失推断已验证修复，不兼容变更前 RESOLVED 数据
- **AND** `NEW` 差异 SHALL 只包含 new item
- **AND** `UNCHANGED` 和 `CHANGED` 差异 SHALL 同时包含 old item 与 new item

#### Scenario: 查询确认版本比较
- **WHEN** 比较 basis 为 CONFIRMED_FIX_LIST
- **THEN** SHALL 返回两侧固定 confirmationId，EXCLUDED/NO_LONGER_SELECTED 与已验证修复分开，保留实际存在的两侧处置与来源证据。

### Requirement: 报告比较必须可异步执行并复用已有结果
报告比较 SHALL 使用长任务运行模型，并 SHALL 避免重复创建同一组报告、basis 和确认版本之间的并发比较任务。

#### Scenario: 创建异步报告比较任务
- **WHEN** 后端接受报告比较创建请求
- **THEN** 后端 SHALL 创建或复用报告比较运行记录
- **AND** 后端 SHALL 创建任务类型为 `REPORT_COMPARE` 或等效类型的长任务
- **AND** 响应 SHALL 返回比较运行 ID、任务 ID 和初始状态

#### Scenario: 重复创建同一报告比较
- **WHEN** 同一 base report、target report、basis 及两侧确认版本已存在 `PENDING`、`QUEUED`、`RUNNING` 或 `SUCCEEDED` 比较
- **THEN** 后端 SHALL 返回已有比较运行或已有任务
- **AND** 后端 MUST NOT 创建重复并发比较任务

#### Scenario: 不支持的历史报告
- **WHEN** base report 或 target report 的 schema/制品缺失无法验证输入快照（合法空集合不属于此情况）
- **THEN** 后端 SHALL 返回 `REPORT_COMPARISON_UNSUPPORTED` 或等效业务错误
- **AND** 后端 MUST NOT 创建比较任务

### Requirement: 报告比较必须区分来源变化处置变化和修复证据
比较 SHALL 保留两侧快照、basis、匹配置信度/原因及源码/运行引用；新流程集合消失不能单独证明修复。

#### Scenario: 报告问题消失
- **WHEN** REPORT_FACTS 的 base 有而 target 无匹配项
- **THEN** SHALL 返回 NOT_OBSERVED 和原因（来源缺失/准入变化等），不得仅凭缺失生成 RESOLVED。

#### Scenario: 清单处置变化
- **WHEN** CONFIRMED_FIX_LIST 中旧 TO_FIX 被人工排除或不再选择
- **THEN** SHALL 返回 EXCLUDED 或 NO_LONGER_SELECTED，不称代码已修复；原问题仍可追溯。

#### Scenario: 空集合和新增
- **WHEN** 合法两侧空或一侧为空
- **THEN** SHALL 可正常比较；target 独有 NEW，同一问题未变 UNCHANGED，证据/处置改变 CHANGED；只有 schema 不可用才 UNSUPPORTED。

#### Scenario: 复用键和历史
- **WHEN** 同报告有多个确认版本
- **THEN** SHALL 按 basis+确认 ID 独立幂等，不覆盖旧历史；不支持变更前旧差异数据转换，不能将集合消失称已验证修复。
