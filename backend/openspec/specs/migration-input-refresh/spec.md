## Purpose

定义四类分析来源在重跑和 Context 变化后的有效选择规则，区分有效成功零发现与无分析，保护新流程的原始结果、报告快照、人工审计和确认历史。

## Requirements

### Requirement: 来源刷新必须按有效上下文隔离
后端 SHALL 对四类来源独立判定当前有效成功运行；同 Context 新成功替代旧成功，失败/取消/PARTIAL 不能伪装成有效成功。

#### Scenario: 重跑与上下文变化
- **WHEN** 任一来源新结果发布
- **THEN** SHALL 只改变对应来源选择，不修改其他来源或历史报告；Context 不同的旧成功不得兜底。

#### Scenario: 保留证据
- **WHEN** 来源索引重建或清理重复索引
- **THEN** SHALL 保留原始运行/Finding、报告输入、人工事件和确认；先保存可靠历史关联，禁止级联抹除。

### Requirement: 零问题报告必须有成功分析依据
后端 SHALL 区分有效成功零发现、过滤后零问题、全部人工排除和无分析。

#### Scenario: 没有确认 reference
- **WHEN** 旧 CONFIRMED 数量为零
- **THEN** SHALL 仍按有效成功分析决定资格，不依赖该数量；有成功可生成，无成功拒绝。

#### Scenario: 全部排除
- **WHEN** 用户排除了全部报告问题
- **THEN** SHALL 保留原报告问题与排除记录，另行确认空清单，不重写全零扫描事实。
