## Purpose

定义知识库 AI 扩展扫描运行时能力的执行边界、输入输出契约、结果入库、查询接口和归档要求。该能力在 KB 扫描完成后运行，基于已入库 finding 和当前源码版本生成 AI 扩展建议，并保持原 KB Scanner 主输出不变。

## Requirements

### Requirement: KB AI 扩展扫描必须作为异步任务运行
后端 SHALL 提供 KB AI 扩展扫描能力，对一次已完成的 KB 扫描 findings 生成 AI 扩展建议，并 SHALL 通过任务系统、队列和 worker 异步执行。

#### Scenario: 创建 AI 扩展扫描任务
- **WHEN** 有项目权限的用户对已成功完成的 KB scan 发起 AI 扩展扫描
- **THEN** 后端 SHALL 校验 KB scan 属于 URL 中的项目
- **AND** 后端 SHALL 校验 KB scan 状态为成功且存在可处理 findings
- **AND** 后端 SHALL 校验当前触发者的个人绑定，创建 KB_AI_EXTENSION 任务和 AI 扩展运行记录并保存配置主体与已选 profile/runtime 引用
- **AND** 后端 SHALL 返回任务 ID 和运行记录 ID

#### Scenario: 阶段配置不可用
- **WHEN** 用户发起 AI 扩展扫描且当前触发者的 `KB_AI_EXTENSION` 阶段未绑定模型 profile 或 agent runtime
- **THEN** 后端 SHALL 返回明确业务错误
- **AND** 后端 MUST NOT 创建任务、运行记录或队列消息
- **AND** 响应 MUST NOT 泄漏 provider secret、agent 命令或服务器真实路径

#### Scenario: 无可处理 finding
- **WHEN** KB scan 成功完成但 finding 数量为 0
- **THEN** 后端 SHALL 返回明确业务状态表示无需执行 AI 扩展
- **AND** 后端 MUST NOT 创建必然无结果的 agent 任务

### Requirement: KB AI 扩展扫描必须使用受控 agent workspace 文件
后端 SHALL 将当前 KB scan 的 findings 导出为 agent 可读取的 workspace 文件，并 SHALL 要求 agent 将结构化结果写回受控输出文件。

#### Scenario: 写入 AI 输入文件
- **WHEN** worker 执行 `KB_AI_EXTENSION` 任务
- **THEN** 后端 SHALL 创建专用 workspace
- **AND** 后端 SHALL 写入 `input/manifest.json`
- **AND** 后端 SHALL 将 findings 按 JSONL 分片写入 `input/findings-*.jsonl`
- **AND** 每条 finding 输入 SHALL 包含 finding ID、rule、severity、title、description、原始 suggestion、受控 evidence 摘要、相对路径、行号和固定大小代码上下文

#### Scenario: 提供源码只读上下文
- **WHEN** 后端准备 agent workspace
- **THEN** 后端 SHALL 提供当前 source revision 的源码上下文供 agent 读取
- **AND** agent prompt SHALL 明确说明当 finding 输入不足以判断时可以读取源码
- **AND** agent prompt SHALL 明确禁止修改源码、删除输入文件或生成源码补丁
- **AND** 后端 SHALL 使用 workspace 隔离或校验机制避免 agent 修改真实源码工作副本

#### Scenario: 保存输入输出 artifact
- **WHEN** AI 扩展任务完成、失败或输出校验失败
- **THEN** 后端 SHALL 保存脱敏后的输入摘要、agent 结构化输出和 agent 执行摘要 artifact
- **AND** artifact 元数据 SHALL 包含类型、大小、sha256、创建时间和安全摘要
- **AND** API 响应 MUST NOT 返回服务器真实 workspace 路径

### Requirement: KB AI 扩展输出必须校验后逐条入库并允许部分成功
后端 SHALL 解析 agent 输出文件，并 SHALL 将每条 AI 建议按 finding 粒度写入数据库，支持后续查询、审计和报告引用。

#### Scenario: 成功解析 AI 输出
- **WHEN** agent 成功写出 `output/results-*.jsonl` 和 `output/summary.json`
- **THEN** 后端 SHALL 校验每行 JSON 为对象
- **AND** 每条结果 SHALL 包含属于本次 KB scan 的 `findingId`
- **AND** 后端 SHALL 为每条结果保存 summary、suggestion、confidence、action type 和 raw payload
- **AND** 后端 SHALL 将运行记录和任务标记为成功

#### Scenario: 部分结果可入库
- **WHEN** agent 输出中部分 finding 缺失、部分单条结果 schema 非法或部分条目重复
- **THEN** 后端 SHALL 将合法且可追溯到本次 KB scan finding 的结果逐条入库
- **AND** 后端 SHALL 在运行 summary 中记录输入总数、成功写入数、缺失数、重复数、非法数和未知 finding 数
- **AND** 后端 SHALL 将运行记录标记为部分成功或带诊断的成功状态
- **AND** finding 列表和详情 SHALL 只对已有合法 AI 结果返回 `aiExtension`

#### Scenario: 输出缺失或非法
- **WHEN** agent 未写出任何结果文件、所有结果文件都无法解析或没有任何合法可追溯结果
- **THEN** 后端 SHALL 将 AI 扩展运行记录和任务标记为失败
- **AND** 后端 SHALL 写入脱敏失败摘要和任务日志
- **AND** 后端 MUST NOT 写入不可追溯的成功态 AI 建议

#### Scenario: agent 执行失败
- **WHEN** agent 进程退出码非 0、超时或被取消
- **THEN** 后端 SHALL 将 AI 扩展运行记录和任务标记为失败或取消
- **AND** 后端 SHALL 保存脱敏后的 `mergedOutput` 摘要供审计
- **AND** 响应和日志 MUST NOT 泄漏 API key、授权头、完整 prompt 或服务器真实敏感路径

### Requirement: KB AI 扩展结果必须绑定源码版本和 KB scan
AI 扩展运行和结果 SHALL 绑定 `project_id`、`source_revision_id` 和 `kb_scan_run_id`，避免源码更新或 KB scan 重跑后错误复用旧建议。

#### Scenario: 当前源码版本结果
- **WHEN** 用户查询当前 KB scan 的 AI 扩展状态或结果
- **THEN** 后端 SHALL 只返回同一 `kb_scan_run_id` 下的 AI 扩展运行和结果
- **AND** 响应 SHALL 标明该运行是否属于当前 active source revision

#### Scenario: 源码更新后的旧结果
- **WHEN** 项目 active source revision 已变更
- **THEN** 旧 source revision 下的 AI 扩展结果 SHALL 仅作为历史记录保留
- **AND** 后端 MUST NOT 将旧源码版本的 AI 建议挂到当前源码版本的 findings 上

#### Scenario: KB scan 重跑后的旧结果
- **WHEN** 同一源码版本产生新的 KB scan run
- **THEN** 旧 KB scan run 的 AI 扩展结果 SHALL 仅绑定旧 run
- **AND** 后端 MUST NOT 将旧 run 的 AI 建议挂到新 KB scan findings 上

### Requirement: KB AI 扩展运行必须提供查询和 readiness API
后端 SHALL 提供 AI 扩展扫描 readiness、运行列表、运行详情和 artifact 下载能力，并 SHALL 保持分页、排序、过滤和权限校验。

#### Scenario: 查询 readiness
- **WHEN** 前端打开 KB scan 页面
- **THEN** 后端 SHALL 基于当前访问者的个人配置和项目权限返回 AI 扩展扫描是否可启动，不读取项目创建者或 admin 的绑定
- **AND** 不可启动时 SHALL 返回原因，包括 KB scan 未成功、无 findings、阶段未配置、模型 profile 不可用或 agent runtime 不可用

#### Scenario: 查询运行记录
- **WHEN** 用户查询项目下 KB AI 扩展运行记录
- **THEN** 后端 SHALL 返回分页列表
- **AND** 列表 SHALL 支持按 status、source revision、KB scan、创建时间和完成时间过滤或排序
- **AND** 后端 SHALL 执行项目权限校验

#### Scenario: 查询运行详情
- **WHEN** 用户查询某个 AI 扩展运行详情
- **THEN** 后端 SHALL 返回运行状态、关联项目、源码版本、KB scan、任务 ID、经最小化脱敏的执行模型和 agent runtime 审计摘要、summary、artifact 摘要和时间信息
- **AND** 响应 MUST NOT 返回 API key、完整 agent 命令、完整 prompt 或服务器真实路径

#### Scenario: 历史结果与当前资格分开
- **WHEN** 有项目权限的用户读取另一触发者的历史 AI 扩展结果
- **THEN** SHALL 保持历史业务结果有效性规则，不因查看者个人配置不同而使结果失效
- **AND** MUST NOT 返回原触发者完整 profile、上游地址、密钥或私人 probe 历史；新执行资格 SHALL 按查看者判定

### Requirement: KB AI 扩展制品必须登记归档候选
KB AI 扩展运行生成的输入、输出和 agent 执行摘要 SHALL 登记为归档候选，使归档当前状态时可以包含这些后续分析和报告依赖的输入材料。

#### Scenario: AI 扩展成功或部分成功后登记归档
- **WHEN** KB AI 扩展运行成功或部分成功
- **THEN** 后端 SHALL 将输入 manifest、finding JSONL、agent 结构化输出、agent 执行摘要和安全 summary 登记为归档候选
- **AND** 归档候选 SHALL 关联 project、source revision、KB scan 和 AI extension run
- **AND** 归档候选 MUST NOT 包含 API key、授权头、完整 prompt 或服务器真实路径

#### Scenario: 未生成制品不登记归档
- **WHEN** KB AI 扩展未运行、启动前校验失败或未生成任何可归档制品
- **THEN** 后端 SHALL NOT 创建无效归档候选
- **AND** 项目归档中 SHALL 不显示不存在的 KB AI 扩展内容

### Requirement: KB AI 扩展必须配套测试覆盖
KB AI 扩展实现 SHALL 覆盖正常流程、异常流程和 edge case。

#### Scenario: 正常流程测试
- **WHEN** 编写 KB AI 扩展测试
- **THEN** 测试 SHALL 覆盖 readiness 成功、创建任务、导出 findings、agent 输出解析、逐条入库、finding 列表附带 AI 建议、finding 详情附带 AI 建议和归档候选登记

#### Scenario: 异常流程测试
- **WHEN** 编写 KB AI 扩展测试
- **THEN** 测试 SHALL 覆盖无项目权限、KB scan 不存在、KB scan 不属于项目、KB scan 未成功、阶段未配置、agent 执行失败和输出 JSON 非法

#### Scenario: edge case 测试
- **WHEN** 编写 KB AI 扩展测试
- **THEN** 测试 SHALL 覆盖空 findings、重复 finding ID、未知 finding ID、缺失 finding ID、部分结果入库、大量 findings 分片、源码路径脱敏、取消运行中任务和测试数据清理
### Requirement: KB AI 扩展必须消费 schema v2 finding
KB AI 扩展任务 SHALL 使用 schema v2 finding 作为 agent 输入，不再依赖 schema v1 的 trace 或扁平字段。

#### Scenario: 导出 AI 扩展输入
- **WHEN** 用户对 KB 扫描结果启动 AI 扩展扫描
- **THEN** 后端 SHALL 生成包含 schema v2 finding 的输入文件
- **AND** 每条输入 SHALL 包含 `fact`、`conclusion`、`occurrences`、`evidenceChain` 和可用源码上下文
- **AND** 输入 SHALL 包含 finding 的当前人工确认状态和报告纳入状态

#### Scenario: finding 信息不足
- **WHEN** v2 finding 自带 evidence 不足以判断迁移建议
- **THEN** agent prompt SHALL 允许 agent 在项目源码目录中读取相关源码
- **AND** 后端 SHALL 继续保留可用的 finding evidence 和源码上下文

#### Scenario: 部分 AI 扩展结果写入
- **WHEN** agent 只为部分 finding 生成有效建议
- **THEN** 后端 SHALL 写入有效建议
- **AND** 未生成建议的 finding SHALL 保持原状态
- **AND** 列表和详情 API SHALL 对已写入的 finding 返回 AI 扩展建议
