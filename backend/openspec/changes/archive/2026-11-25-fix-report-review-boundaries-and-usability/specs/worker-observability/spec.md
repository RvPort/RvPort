## ADDED Requirements

### Requirement: Worker 必须上报有时效的报告 AI 执行能力
Worker SHALL 在实际执行容器、身份和安全策略下对报告 AI 沙箱进行有界无凭据自检，并通过既有心跳持久化 runtime/版本及策略身份、能力状态、观测时间与有效期；MUST NOT 调用模型或修改被分析源码。

#### Scenario: 能力观测成功或明确不支持
- **WHEN** 本地能力自检确认支持沙箱或必要系统能力不可用
- **THEN** SHALL 上报真实支持/不支持及稳定脱敏原因，API 不以自身容器能力替代 Worker 事实，不将瞬时工作繁忙表达为缺少沙箱。

#### Scenario: 过期或未探测
- **WHEN** 心跳缺能力字段、自检超时、版本/策略不符或观测过期
- **THEN** SHALL 作为 UNKNOWN，不延长旧探测时间冒充新结果；自检有时限，不长期阻塞心跳和普通任务。

#### Scenario: 多 Worker 聚合
- **WHEN** 查询同队列且有领取资格的在线 Worker 能力
- **THEN** SHALL 全部支持时返回 AVAILABLE，全部明确不支持时返回 UNAVAILABLE；无在线实例、未知、过期或混合支持时返回 UNKNOWN 和适当提示，不暴露主机名、凭据或内部路径。
