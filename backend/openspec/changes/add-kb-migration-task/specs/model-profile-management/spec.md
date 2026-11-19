# model-profile-management delta

## MODIFIED Requirements

### Requirement: 阶段模型绑定必须从已配置 Profile 中选择
后端 SHALL 提供阶段模型绑定能力，使 `TEST_LOG_COMPARE` 测试结果比较阶段、`REPORT_AI_REVIEW` 报告 AI 复核阶段和 `KB_MIGRATION` KB 迁移阶段可以选择一个已启用模型 profile 和一个已启用 agent runtime。

#### Scenario: 查询阶段模型绑定
- **WHEN** 已认证配置所有者调用阶段模型绑定查询 API
- **THEN** 后端 SHALL 返回 `TEST_LOG_COMPARE`、`REPORT_AI_REVIEW` 和 `KB_MIGRATION` 阶段及其当前绑定的模型 profile 摘要和 agent runtime 摘要
- **AND** 未绑定时 SHALL 明确返回空绑定状态

#### Scenario: 保存阶段模型绑定
- **WHEN** 已认证配置所有者保存阶段模型绑定
- **THEN** 后端 SHALL 校验阶段名称属于后端白名单，当前允许 `TEST_LOG_COMPARE`、`REPORT_AI_REVIEW` 和 `KB_MIGRATION`
- **AND** 后端 SHALL 校验绑定的模型 profile 属于当前用户、存在且已启用
- **AND** 后端 SHALL 校验绑定的 agent runtime 存在且已启用
- **AND** 后端 SHALL 保存当前所有者与阶段到模型 profile 和 agent runtime 的唯一绑定

#### Scenario: 阶段绑定非法
- **WHEN** 请求包含未知阶段、已禁用 profile、不存在 profile、已禁用 agent runtime、不存在 agent runtime 或重复阶段绑定
- **THEN** 后端 SHALL 返回统一参数或业务错误
- **AND** 后端 MUST NOT 保存部分非法绑定

#### Scenario: 运行时解析阶段模型
- **WHEN** 用户触发 AI 增强报告复核、KB 迁移，或后续测试结果比较需要执行模型任务
- **THEN** 后端 SHALL 创建时通过配置主体的个人阶段绑定解析模型 profile 和 agent runtime，执行时使用已持久化引用并复核归属
- **AND** 如果阶段未绑定、绑定 profile 已禁用或绑定 agent runtime 已禁用，后端 SHALL 返回明确业务错误
- **AND** 业务任务请求 MUST NOT 直接提交 base URL、API key、agent 类型、可执行路径或任意模型参数
