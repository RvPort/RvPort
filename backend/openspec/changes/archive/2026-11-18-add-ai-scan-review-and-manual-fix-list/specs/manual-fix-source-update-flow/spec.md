## REMOVED Requirements

### Requirement: 人工修复节点作为源码更新入口
**Reason**: 新流程后续修复依据明确确认快照，旧 refs 与集合消失不能作为确认或修复证明。
**Migration**: 保留源码更新主入口及既有权限，接入 CurrentFixListResolver；recheck 现有入口使用两侧明确确认快照。

### Requirement: 复检任务必须基于已确认迁移输入创建
**Reason**: 新流程后续修复依据明确确认快照，旧 refs 与集合消失不能作为确认或修复证明。
**Migration**: 保留源码更新主入口及既有权限，接入 CurrentFixListResolver；recheck 现有入口使用两侧明确确认快照。

### Requirement: 复检差异项必须表达修复、新增、仍存在和变化
**Reason**: 新流程后续修复依据明确确认快照，旧 refs 与集合消失不能作为确认或修复证明。
**Migration**: 保留源码更新主入口及既有权限，接入 CurrentFixListResolver；recheck 现有入口使用两侧明确确认快照。

## MODIFIED Requirements

### Requirement: 复检差异必须使用稳定 issue identity 和匹配置信度
系统 SHALL 为确认清单快照项生成稳定 issue identity，并在复检时结合 exact fingerprint 与规则化模糊匹配判断 old/new 是否为同一 issue；比较身份匹配 SHALL NOT 自动授权跨源码继承人工决定。

#### Scenario: 代码类 finding 行号变化
- **WHEN** base 和 target 中两个代码类 finding 的行号不同
- **AND** 二者具有相同 `sourceType`、`ruleId`、相对路径、符号名和 normalized anchor
- **THEN** 系统 MUST 将二者匹配为同一 issue
- **AND** 系统 MUST NOT 仅因行号变化生成 `NOT_OBSERVED` 与 `NEW`

#### Scenario: exact fingerprint 匹配
- **WHEN** base 和 target 中两个确认清单快照项具有相同 stable fingerprint
- **THEN** 系统 MUST 使用 `matchConfidence=HIGH`
- **AND** 系统 MUST 使用 `matchReason=EXACT_FINGERPRINT`

#### Scenario: 模糊匹配
- **WHEN** exact fingerprint 未匹配成功
- **AND** base 和 target 中两个确认清单快照项满足同来源类型下的规则化模糊匹配条件
- **THEN** 系统 MUST 将二者匹配为同一 issue
- **AND** 系统 MUST 记录 `matchConfidence` 和 `matchReason`

#### Scenario: 无匹配项
- **WHEN** base 确认清单快照项无法在 target 中找到匹配项
- **THEN** 系统 MUST 生成 `NOT_OBSERVED` 差异项
- **WHEN** target 确认清单快照项无法在 base 中找到匹配项
- **THEN** 系统 MUST 生成 `NEW` 差异项

### Requirement: 复检差异查询必须支持列表和详情
系统 SHALL 提供复检差异项分页列表和单条详情 API，列表用于表格展示，详情用于查看 old/new 两侧来源信息。

#### Scenario: 分页查询差异项列表
- **WHEN** 用户调用 `GET /api/rechecks/{recheckRunId}/diff-items`
- **THEN** 系统 MUST 校验当前用户对该复检所属项目有访问权限
- **AND** 响应 MUST 支持按 `diffStatus`、`sourceType`、`severity`、`matchConfidence` 过滤
- **AND** 响应 MUST 支持分页、排序
- **AND** 每条列表项 MUST 返回 `title`、`locationLabel`、`recommendation`、`confirmationStatus`、`matchConfidence` 和 `matchReason`

#### Scenario: 查询单条差异详情
- **WHEN** 用户调用 `GET /api/rechecks/{recheckRunId}/diff-items/{diffItemId}`
- **THEN** 系统 MUST 校验当前用户对该复检所属项目有访问权限
- **AND** 系统 MUST 校验差异项属于该复检
- **AND** 响应 MUST 返回 `oldItem` 与 `newItem` 快照
- **AND** 新比较的 `NOT_OBSERVED` 差异 MUST 只包含 `oldItem`；MUST NOT 视为已验证修复，不提供旧数据转换
- **AND** `NEW` 差异 MUST 只包含 `newItem`
- **AND** `UNCHANGED` 和 `CHANGED` 差异 MUST 同时包含 `oldItem` 与 `newItem`

#### Scenario: 差异详情越权访问
- **WHEN** 用户访问无权限项目的复检差异详情
- **THEN** 系统 MUST 返回安全错误
- **AND** 响应 MUST NOT 暴露复检或差异项是否存在

#### Scenario: 清单处置变化
- **WHEN** 比较发现条目被排除或不再入选
- **THEN** SHALL 返回 EXCLUDED 或 NO_LONGER_SELECTED 及实际存在的两侧证据，不能仅因不在待修复子集中而称已修复。

### Requirement: latest compare API 作为兼容入口
系统 SHALL 保留项目级 latest compare API 作为现有入口，由后端基于项目源码版本关系选择当前版本和上一可比较版本，并返回基于明确确认清单版本生成的复检差异状态。该能力 SHALL NOT 作为人工修复主流程的推荐入口。

#### Scenario: 获取 latest compare
- **WHEN** 用户调用 `GET /api/projects/{projectId}/source/compare/latest`
- **THEN** 系统 MUST 校验当前用户对项目有访问权限
- **AND** 系统 MUST 使用项目 active 源码版本作为 `currentRevision`
- **AND** 系统 MUST 使用同项目中早于当前版本的最近可比较源码版本作为 `previousRevision`
- **AND** 系统 MUST 返回当前版本相对上一版本的复检差异状态

#### Scenario: 只有一个源码版本
- **WHEN** 项目只有一个可用源码版本
- **THEN** latest compare 响应 MUST 返回 `previousRevision=null`
- **AND** 响应 MUST 返回 `emptyReason=NO_PREVIOUS_REVISION`
- **AND** 响应 MUST 返回空差异摘要和空差异列表入口

#### Scenario: 比较结果已生成
- **WHEN** 当前版本和上一版存在已完成的复检记录
- **THEN** latest compare 响应 MUST 返回复检状态、差异摘要和分页差异项入口信息
- **AND** 差异摘要 MUST 基于固定确认快照统计 `NOT_OBSERVED`、`EXCLUDED`、`NO_LONGER_SELECTED`、`UNCHANGED`、`NEW`、`CHANGED`；不提供旧 `RESOLVED` 兼容，不能声称经过修复验证

#### Scenario: 比较结果尚未生成
- **WHEN** 当前版本和上一版存在但没有可用复检记录
- **THEN** latest compare 响应 MUST 返回 `status=NOT_STARTED`
- **AND** 响应 MUST 包含 current/previous 源码版本摘要
- **AND** 响应 MUST NOT 自动创建复检任务

#### Scenario: 比较任务运行中
- **WHEN** 当前版本和上一版的复检任务正在运行
- **THEN** latest compare 响应 MUST 返回 `status=RUNNING`
- **AND** 响应 MUST 返回关联任务 ID，供前端结合 SSE 展示进度

#### Scenario: 越权访问 latest compare
- **WHEN** 用户访问无权限项目的 latest compare API
- **THEN** 系统 MUST 返回安全错误
- **AND** 响应 MUST NOT 暴露项目或源码版本是否存在

## ADDED Requirements

### Requirement: 人工修复入口必须消费当前有效确认清单
后端 SHALL 返回绑定当前源码和报告的确认状态、待修复数、确认 ID、源码摘要和更新入口事实；不提供在线编辑、自动 patch 或一键源码修复。

#### Scenario: 未确认或草稿更改
- **WHEN** 当前清单不可用
- **THEN** SHALL 返回需要确认的原因，不从旧迁移输入推断后续修复范围。

#### Scenario: 确认空清单
- **WHEN** 当前确认 TO_FIX 为零
- **THEN** SHALL 表示无需本次清单修复，保留确认记录；不阻止通用源码上传或伪造验证通过。

#### Scenario: 更新源码
- **WHEN** 源码上传/Git 同步激活新 revision
- **THEN** SHALL 旧报告/草稿/清单历史只读，新版本重新生成并确认，不复制旧清单作为当前事实。

### Requirement: 复检现有入口必须固定确认快照并保持差异语义
后端 SHALL 在现有 rechecks 和 source/compare/latest 现有入口使用同项目两侧源码的明确确认快照，复用异步任务/权限/分页能力。

#### Scenario: 缺少确认
- **WHEN** 目标源码无有效确认或指定历史基线无确认快照
- **THEN** SHALL 返回 FIX_LIST_CONFIRMATION_REQUIRED，不回退旧 CONFIRMED refs。

#### Scenario: 历史基线
- **WHEN** 显式使用上一源码的历史确认比较
- **THEN** SHALL 允许作为只读基线，固定其 ID；该历史确认不得作为新源码当前修复依据。

#### Scenario: 任务和差异
- **WHEN** 同两侧确认请求重复或比较集合为空
- **THEN** SHALL 以确认 ID 幂等并支持空集合；缺失为 NOT_OBSERVED，排除为 EXCLUDED，不凭消失标已修复，保留 NEW/UNCHANGED/CHANGED 与匹配原因。
