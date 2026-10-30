## ADDED Requirements

### Requirement: 报告区分内存序待核实项与正式风险

报告 SHALL 独立展示 pendingVerification 和 memoryOrderAnalysis 的分析局限，不能因人工选择而升级分析结论。

#### Scenario: 候选已人工纳入

- **WHEN** 报告包含已确认纳入的内存序候选
- **THEN** 页面 SHALL 将其标记为待核实，不作为正式风险展示

#### Scenario: 历史报告缺少完整性

- **WHEN** 报告有内存序输入，但历史报告未提供待核实项或分析完整性字段
- **THEN** 页面 SHALL 保留未知状态，并提示零正式风险不能证明迁移安全

#### Scenario: 报告没有内存序分析或输入

- **WHEN** 报告只有知识库、浮点等来源，且没有内存序分析记录或待核实项
- **THEN** 页面 SHALL 不显示不适用的内存序警告，不能将未执行描述成历史未知
