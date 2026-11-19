# node-local-migration-input-confirmation Specification

## Purpose

明确旧分析节点确认能力的退役边界。节点保留原始分析与风险语义，报告阶段独立管理人工复核和最终清单确认，节点状态不再决定报告纳入或清单确认。

## Requirements

### Requirement: 分析节点不得代替报告人工确认
后端 SHALL 保留新流程原始分析结果，删除旧节点确认状态和接口，停止旧默认风险策略对新报告输入和确认清单的控制。

#### Scenario: 节点返回状态
- **WHEN** 查询节点结果
- **THEN** SHALL 不再返回 migrationInputRefId/migrationInputStatus/confirmedForNextReport；includedInLatestReport 如保留则依据当前报告真实来源引用，不伪造清单确认。

#### Scenario: 新专项结果
- **WHEN** 导入浮点或内存序新结果
- **THEN** SHALL 保留原风险与候选语义；新报告从有效来源提取，低风险不因旧默认 CANDIDATE 被遗漏，内存序候选不升级正式风险。
