## MODIFIED Requirements

### Requirement: AI 必须使用触发者个人配置并完整成功
后端 SHALL 使用 REPORT_AI_REVIEW 个人阶段绑定，复用既有持久身份/租约/恢复机制；AI 结论用于初始化未获人工处置的草稿条目，人工决定优先，最终确认须手动提交。配置资格和 Worker 执行能力 SHALL 独立表达，不以历史测试结果代替当前配置。

#### Scenario: 配置与模式
- **WHEN** 当前触发者无有效个人绑定
- **THEN** SHALL 默认建议 STANDARD，禁止 AI 创建但不阻止普通报告；有绑定且来源有效、没有已知必要执行能力阻塞时建议 AI_ENHANCED，读取不调用模型。

#### Scenario: 当前配置与历史测试分离
- **WHEN** 页面查询当前用户的报告 AI 配置状态
- **THEN** SHALL 根据 REPORT_AI_REVIEW 的个人绑定、profile/runtime 启用状态和必填配置判定 aiConfigurationReady，不要求 probe 成功，不调用模型；配置成功不承诺实际运行成功。
- **AND** SHALL 可复用 GET /api/me/model-stage-assignments 展示当前绑定摘要，不用旧测试成功状态代替当前配置状态；报告 aiBlockers 通过 CONFIGURATION/EXECUTION 分类区分配置与执行阻塞，canGenerateAi 同时考虑来源资格及实际必要的执行能力。

#### Scenario: 完整输出
- **WHEN** N 个实际 AI 输入执行结束
- **THEN** SHALL 恰有 N 个有效唯一结果，校验枚举、双语依据、证据归属和范围；漏项、重复、未知 ID、非法结构/位置、非零退出或超时均不能发布成功增强报告。

#### Scenario: 依据不足与失败
- **WHEN** 输出合法 INSUFFICIENT_EVIDENCE 或执行发生技术失败
- **THEN** SHALL 分别记录合法建议与任务失败，不能用依据不足掩盖漏项/技术失败，不自动切换普通模式。

#### Scenario: 没有实际 AI 输入
- **WHEN** AI 模式全部跳过或零问题
- **THEN** SHALL 校验个人配置但不调用模型，记录 NOT_REQUIRED 和原因；执行能力以 executionRequired=false 表达，缺沙箱不单独阻塞，不能伪造已复核结果。

#### Scenario: 重试恢复
- **WHEN** 自动重试/恢复或另一用户手动重新生成
- **THEN** SHALL 前者保持原主体与固定引用，后者建立新执行使用新操作者；每次领取校验账号、项目及配置归属。

#### Scenario: 多批复核与恢复
- **WHEN** 候选超过单批条数或输入字节预算
- **THEN** SHALL 固定全部 AI 输入 ID、批次、输入摘要及配置快照；逐批持久化已验证结果，恢复复用同计划同配置批次，全部应复核 ID 恰好覆盖且制品完整才发布成功。

#### Scenario: 超限与无效输出
- **WHEN** 单项超限、总预算不足、批次漏项、重复/未知 ID 或非法证据
- **THEN** SHALL 返回稳定失败原因和已验证进度，不截断候选、自动排除未复核项或发布不完整增强报告；配置快照改变不得混用旧批次。

#### Scenario: 默认处置与人工优先
- **WHEN** 合法逐项复核完成
- **THEN** SHALL 将 CONFIRMED_ISSUE 默认映射 TO_FIX，将 SUSPECTED_FALSE_POSITIVE/INSUFFICIENT_EVIDENCE 默认映射 PENDING_REVIEW；可靠继承的人工决定优先，最终清单须用户手动提交，尚有待复核时拒绝提交。

#### Scenario: 已知沙箱不可用
- **WHEN** 有实际 AI 输入，个人配置满足，但所有有资格 Worker 的当前观测均明确不支持所需沙箱
- **THEN** SHALL readiness 返回 canGenerateAi=false、双语执行 aiBlockers 与 Worker 环境恢复动作；创建时复查并返回 409 REPORT_AI_SANDBOX_UNAVAILABLE，无任务副作用，STANDARD 与历史读取不受影响。

#### Scenario: 未知能力与执行时变化
- **WHEN** Worker 无在线观测、过期或能力混合，或创建后能力改变
- **THEN** SHALL 未知时返回 warning 而不假装已验证或禁止正常排队；执行时仍验证真实隔离能力，失败明确终止，不绕过沙箱、不自动转普通模式。
