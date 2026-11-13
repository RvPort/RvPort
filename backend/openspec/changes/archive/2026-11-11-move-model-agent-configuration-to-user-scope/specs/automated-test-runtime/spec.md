## MODIFIED Requirements

### Requirement: 自动化测试节点必须自动衔接测试结果比较
自动化测试运行时 SHALL 保存测试结果比较所需的最小引用，并 SHALL 在同一自动化测试节点两侧运行完成后自动触发比较流程。

#### Scenario: 保存比较输入引用
- **WHEN** 自动化测试任务完成
- **THEN** 后端 SHALL 保存退出码、状态、stdout 日志引用、stderr 日志引用、输出摘要和报告 artifact 摘要
- **AND** 这些引用 SHALL 足以让测试结果比较任务读取或下载原始材料

#### Scenario: 自动化测试不依赖比较模型
- **WHEN** 该测试批次原触发者的 `TEST_LOG_COMPARE` 阶段没有绑定模型 profile 或模型不可用
- **THEN** 自动化测试任务 SHALL 继续允许创建和执行
- **AND** 后端 MUST NOT 因 AI 配置缺失改变测试运行状态

#### Scenario: 两侧测试完成后自动触发比较
- **WHEN** 同一自动化测试节点的 source 和 target 测试记录均进入 `SUCCEEDED`、`FAILED` 或 `TIMEOUT`
- **THEN** 后端 SHALL 自动创建或更新该节点关联的测试比较运行
- **AND** 如果该批次原触发者的个人比较模型可用，后端 SHALL 自动投递 `TEST_LOG_COMPARE` 任务
- **AND** 如果该批次原触发者的个人比较模型不可用，后端 SHALL 保存比较不可用摘要供前端展示

#### Scenario: 自动化测试节点使用批次关联两侧结果
- **WHEN** 用户点击一次自动化测试节点开始执行
- **THEN** 后端 SHALL 为该次节点运行生成稳定批次标识
- **AND** source 和 target 两侧测试记录 SHALL 保存同一批次标识
- **AND** 自动比较 SHALL 只在同一批次内匹配 source 和 target 测试记录

#### Scenario: 测试被取消时不执行比较
- **WHEN** 同一自动化测试节点任一侧测试记录进入 `CANCELLED`
- **THEN** 后端 SHALL 保存测试真实状态
- **AND** 后端 SHALL 将比较摘要标记为不可比较或取消
- **AND** 后端 MUST NOT 投递 agent 比较任务
#### Scenario: 批次触发者继承
- **WHEN** 同一批次两侧结果完成并自动派生比较
- **THEN** 比较 SHALL 继承该批次持久化的原触发者，在派生时解析其个人绑定
- **AND** MUST NOT 采用查询页面的用户、最后完成任务的 worker 身份或项目所有者
- **AND** 批次触发者缺失或相互冲突 SHALL 记录不可用原因，不投递模型任务

#### Scenario: 手动重新触发测试
- **WHEN** 另一有权用户手动重新触发自动化测试
- **THEN** SHALL 创建新批次并记录新触发者，其自动派生比较 SHALL 使用新触发者的配置
- **AND** 原批次和比较的触发者审计 MUST NOT 被改写
