## MODIFIED Requirements

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
