## ADDED Requirements

### Requirement: 内存序结论协议与报告选择独立

前端 SHALL 兼容内存序 2.0/3.0，以 kind/verdict 区分正式风险、源码候选、证据不足与受限消解，读取 evidenceLevel 及 reason/detail 双语字段。空 confidence SHALL 表示未评估；候选 SHALL NOT 显示为风险或概率。

#### Scenario: 历史候选与人工纳入

- **WHEN** 2.0 候选包含 medium/0.5，或 3.0 候选已人工 CONFIRMED
- **THEN** 页面 SHALL 继续显示待核实项，不升级 verdict、严重性或正式风险计数
- **AND** 新 run SHALL 使用新 Finding 自身的待确认状态，不继承历史选择

#### Scenario: 正式严重风险与不可靠的历史等级筛选

- **WHEN** 正式 Finding 返回 critical，或旧候选的原始等级不同于派生展示等级
- **THEN** 页面 SHALL 保留 critical 的严重风险语义，候选仍为待核实项
- **AND** 内存序页面 SHALL 不提供无法由现有接口正确实现的等级筛选，忽略旧等级筛选状态，保留服务端搜索与分页；浮点页面 SHALL 保留等级筛选

### Requirement: 内存序诊断保留未知与截断

前端 SHALL 在概览展示 diagnosticCount、frontierCount 和 primaryDiagnostic 摘要，通过阶段内二级页查看完整 diagnostics，并保留已生成 JSON 与审计包的下载入口。概览 SHALL 沿用既有整体布局，不内嵌长诊断列表。

#### Scenario: 未汇总或未知诊断

- **WHEN** 历史未汇总、字段缺失、null 或请求失败
- **THEN** 页面 SHALL 展示未知、未汇总或加载失败，不转换为零诊断

#### Scenario: 仅有前沿或明细截断

- **WHEN** 结果仅有 frontier 或 diagnosticsTruncated=true
- **THEN** 页面 SHALL 独立显示前沿与诊断数量，说明明细截断及完整审计包入口

#### Scenario: 打开诊断并返回原概览

- **WHEN** 用户从最新或历史概览打开诊断明细
- **THEN** 二级页 SHALL 绑定当时的 run ID，不因最新记录更新而切换执行
- **AND** 返回 SHALL 保留原入口、所选历史记录和筛选状态；读取失败 SHALL 提供重试而非零诊断

### Requirement: 内存序证据图按主路径呈现

前端 SHALL 通过 steps.id 解析 primaryPath、edges 和 breaks，不以 steps 数组顺序推导证明。

#### Scenario: 乱序步骤与多分支

- **WHEN** steps 存储顺序不同于 primaryPath，且存在旁支与断点
- **THEN** 页面 SHALL 按主路径展示主链，并允许查看其他分支、断点原因、期望证据与建议

#### Scenario: 异常或受限证据图

- **WHEN** 图包含缺引用、环、未知类型或预算截断
- **THEN** 页面 SHALL 明确提示结构异常或截断，保留可验证片段，不补造完整链

#### Scenario: 断点期望证据名称

- **WHEN** breaks.expectedKind 为 source-context、runtime-evidence 或其他证据缺口名称
- **THEN** 页面 SHALL 保留该开放名称，不将其当作 Step.kind 枚举产生错误警告

### Requirement: 内存序源码随所选证据隔离

前端 SHALL 绑定 occurrence/step 对应的源码 digest 与位置，不将其他位置的片段作为当前步骤证据。

#### Scenario: 无源码或 digest 不匹配

- **WHEN** 用户选择无源码断点或不同 digest 的位置
- **THEN** 页面 SHALL 清空不匹配代码并说明缺失，不能沿用前一个位置片段

#### Scenario: 现有接口补充源码没有分析 digest

- **WHEN** codeContext 的 Finding、run、sourceRevision、文件与位置范围匹配，但接口没有分析快照 digest
- **THEN** 页面 MAY 在主位置提供单独的按需补充阅读入口，明确未校验分析 digest
- **AND** 补充源码 SHALL NOT 替代分析快照或参与步骤、位置的证据复用；切换后 SHALL 收起该阅读区
