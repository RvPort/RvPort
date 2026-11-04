## ADDED Requirements

### Requirement: 十规则候选必须统一有界调度与局部失败
运行时 SHALL 让全部十条规则按 descriptor 对应的真实语义候选稳定排序并惰性求值，在开始候选评估前应用 candidate/assessment 预算，并在证据闭包及输出阶段应用对应上限。MUST NOT 先 eager 计算全部候选再截断结果。可分类异常仅使当前候选 failed；整份输入无效仍按输入层协议拒绝。所有状态和诊断 SHALL 保留正确 TU 身份及候选 scope。

#### Scenario: ABI 或转换候选异常
- **WHEN** 同一规则有一个可分类的无效候选和另一个有效命中候选
- **THEN** 无效候选独立 failed，有效候选继续完成，不将整个规则或 TU 替换成一条 failed

#### Scenario: 未执行候选超过预算
- **WHEN** 任一规则剩余候选超过 candidate 或 assessment 上限
- **THEN** 仅求值稳定前缀，保留已完成结果，未执行范围通过有界 frontier 和不完整状态审计，不调用超预算候选 evaluator

#### Scenario: 顺序变化与去重
- **WHEN** 输入 facts、worker 完成顺序发生变化或相同事实重复出现
- **THEN** 相同语义候选去重，预算前缀、身份、计数和输出顺序保持一致

#### Scenario: 单候选证据超限
- **WHEN** 一个候选的必要引用超限而其他候选符合预算
- **THEN** 只按既有协议降级受影响候选，不能丢弃必要引用来伪装完整，也不能把其他候选一起降级
