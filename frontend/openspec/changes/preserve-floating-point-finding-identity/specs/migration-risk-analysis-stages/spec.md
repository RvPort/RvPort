## ADDED Requirements

### Requirement: 浮点 Finding 身份与历史位置独立

前端 SHALL 以 Finding ID 为独立条目，不按规则、文件、函数或编译单元合并结果，也不按 occurrences 数量拆分历史 Finding。每条结果的位置、证据、置信度与状态 SHALL 来自自身数据，协议继续兼容 schema v2。

#### Scenario: 同函数两个独立归约循环

- **WHEN** reduction-reassociation@1.1 返回同函数、同规则、同文件的两个不同 Finding ID
- **THEN** 前端 SHALL 展示两个条目，分别打开各自详情，不能复用另一条的证据或判断

#### Scenario: 单位置结果

- **WHEN** 一条 Finding 只有一个位置
- **THEN** 页面 SHALL 直接显示文件与行号，不显示位置下拉框

#### Scenario: 历史 Finding 包含多个位置

- **WHEN** 历史 schema v2 的单条 Finding 包含多个 occurrences
- **THEN** 前端 SHALL 保留一个列表条目，在详情允许位置切换，并以 primaryOccurrenceId 选择主位置
