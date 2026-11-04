## ADDED Requirements

### Requirement: 全规则独立问题必须分别投影为 Finding
每个 matched 独立候选 SHALL 输出一个有自身稳定 ID、assessment、直接事实、conclusion、主位置及闭合 evidenceChain 的 v2 Finding。普通候选的 affectedCount SHALL 为 1；多个证据不得重复计数。不同独立问题 MUST NOT 在输出、存储消费契约或排序阶段再次按规则、文件、TU 或位置合并。同一问题多证据位置可以保留，但每个 occurrence MUST 具有可证明的归属，辅助依赖不能自动变为额外命中。

#### Scenario: API 跨函数结果
- **WHEN** 同一文件两个函数中的独立 API 调用均命中
- **THEN** 返回两条 Finding，各自的主位置、occurrences、证据链和 assessment 仅对应自己的问题，前端按两个 ID 展示即可

#### Scenario: 一问题多证据与位置排序
- **WHEN** 同一语义问题存在多个可信直接证据位置
- **THEN** 仅发布一条结果并保留必要位置，主位置优先，其余按路径、行、列及稳定 tie-breaker 排序，不按随机 hash 跳跃排列

#### Scenario: 历史结果和双语兼容
- **WHEN** 新规则版本生成结果且历史多位置记录仍存在
- **THEN** 新旧结果均遵循 schema v2，历史记录不回填，新增用户可见解释保持中英文字段完整

### Requirement: 配置聚合与抑制必须保全独立成员审计
合法配置聚合 SHALL 保存所有成员身份、assessment refs、必要证据、位置及真实成员数量，MUST NOT 仅保留首个成员依据。稳定候选身份 SHALL 先于 suppression 生成；抑制 SHALL 对独立成员应用，再对可见且语义等价的成员形成最终聚合。公开 summary、诊断、suppression records 和 evidence refs MUST 一致且可解析，不能隐藏无关成员或把聚合 ID 当作所有候选的身份。

#### Scenario: 单点抑制不隐藏其他问题
- **WHEN** 合法 suppression 只匹配一个普通候选或配置影响成员
- **THEN** 只隐藏匹配成员，其他候选仍可见；最终聚合重新计算可见成员证据、位置和数量，抑制审计保留原独立候选身份

#### Scenario: 非主成员范围抑制
- **WHEN** suppression 匹配聚合前的非主成员位置
- **THEN** 仍按该成员生效，不因最终主位置不同误判 unmatched

#### Scenario: 多成员证据闭合
- **WHEN** 多个合法配置成员合成一条 Finding
- **THEN** 每个成员的必要证据与 assessment 均可审计，去重后的图引用闭合，不使用第一成员证据代替其他成员
