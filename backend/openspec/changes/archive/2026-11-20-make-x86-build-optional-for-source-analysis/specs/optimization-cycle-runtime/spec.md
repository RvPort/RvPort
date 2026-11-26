## ADDED Requirements

### Requirement: 优化闭环不得以可选分析或 AI 结论替代验证链
后端 SHALL 保持成功双端构建验证、对应有效测试批次和比较结果的闭环门槛；前置 SOURCE_ONLY 是否执行与源码分析是否可启动 SHALL 不替代该验证事实。

#### Scenario: 未执行前置构建
- **WHEN** 用户仅运行源码分析后直接完成当前源码的双端构建验证及对应测试比较
- **THEN** SHALL 按实际验证链判断闭环资格，不要求补做前置 x86 构建。

#### Scenario: 只有源码分析或 AI 确认
- **WHEN** 来源合法 PARTIAL、报告生成成功、AI 确认全部条目或用户提交空清单，但正式验证链未完成
- **THEN** SHALL 保持闭环未完成并返回真实缺失前置，不将报告完成或零待修复解释为构建/测试成功。

#### Scenario: 仅单侧或历史成功
- **WHEN** 只有一侧构建成功，或测试/比较不匹配当前有效双端构建
- **THEN** SHALL 阻止以这些结果认定闭环完成，readiness、overview、latest 与执行使用同一判定。
