## MODIFIED Requirements

### Requirement: 环境管理必须维护项目当前运行环境
环境管理能力 SHALL 提供项目当前运行环境配置接口，允许用户在环境管理中选择源环境和目标环境作为后续远程运行节点的统一环境来源。

#### Scenario: 环境管理页读取当前运行环境
- **WHEN** 前端打开环境管理页
- **THEN** 后端 SHALL 返回当前项目的源环境、目标环境、配置版本和 ready 状态
- **AND** ready 只有在源环境和目标环境都已配置且可用时为 `true`

#### Scenario: 环境管理页局部更新运行环境
- **WHEN** 用户只修改源环境或只修改目标环境
- **THEN** 后端 SHALL 接受局部更新
- **AND** 后端 SHALL 保留未提交一侧的既有环境配置
- **AND** 后端 SHALL 递增配置版本

#### Scenario: 禁用当前运行环境
- **WHEN** 用户尝试禁用当前运行环境配置引用的源环境或目标环境
- **THEN** 后端 SHALL 拒绝禁用或要求先解除运行环境配置
- **AND** 后端 MUST NOT 让当前运行环境引用禁用环境后仍显示 ready

#### Scenario: 项目概览展示运行环境状态
- **WHEN** 前端调用项目 overview
- **THEN** 响应 SHALL 包含当前运行环境配置摘要、版本和 ready 状态
- **AND** 缺少源环境或目标环境时 SHALL 如实显示环境缺口，但 MUST NOT 无条件覆盖主线推荐
- **AND** 只有当前主线步骤确实依赖缺失环境时，推荐下一步 SHALL 为进入环境管理补齐配置

#### Scenario: 源码分析和报告不因环境缺失改为推荐环境管理
- **WHEN** 当前已有活动源码且可以执行知识库扫描或生成结果评估，运行环境尚未补齐
- **THEN** overview SHALL 按主线与就绪事实推荐 kb 或 report
- **AND** 环境 ready SHALL 继续为 false，不伪造环境可用事实
