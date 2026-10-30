# migration-risk-analysis-stages Specification

## Purpose

定义强弱内存序分析与浮点精度扫描的前端阶段入口、真实接口交互及风险展示规则，使用户能够在 Workflow 和 Step 中查看前置条件、发起分析、追溯执行与证据，并按既有边界将结果纳入报告。

## Requirements

### Requirement: 两项迁移风险分析的阶段入口

前端 SHALL 为强弱内存序分析与浮点精度扫描提供独立阶段入口，并在 Workflow Drawer 与 Step Panel 复用对应阶段业务内容。

#### Scenario: 查看分析阶段

- **WHEN** 用户点击任一新分析节点、Step 导航或访问其有效深链接
- **THEN** 页面 SHALL 在当前视图的阶段载体中展示该分析
- **AND** 打开阶段 SHALL NOT 自动创建任务

### Requirement: 分析阶段沿用既有状态体系

两项分析 SHALL 沿用既有阶段事实状态与展示辅助状态，不新增能力占位状态。

#### Scenario: 展示分析阶段事实

- **WHEN** Overview 返回新分析阶段事实
- **THEN** 节点及阶段内容 SHALL 展示对应的 `PENDING`、`RUNNING`、`DONE` 或 `BLOCKED` 状态
- **AND** SHALL 复用现有前置条件辅助状态，不伪造执行结果

#### Scenario: 分析尚未执行

- **WHEN** 阶段尚无执行记录或结果
- **THEN** 页面 SHALL 展示真实空状态、输入说明及 readiness 允许的创建操作
- **AND** SHALL NOT 以接口开发进度替代业务阶段状态

#### Scenario: 分析请求失败

- **WHEN** 分析接口返回权限、网络或业务失败
- **THEN** 页面 SHALL 按真实失败语义显示错误与适用恢复路径
- **AND** SHALL NOT 将请求失败转换成虚构的能力状态

### Requirement: 按后端契约执行分析

前端 SHALL 使用已确认的 stage ID、请求、响应、readiness 与任务事件契约，遵循 request-client 与 project-stage-runtime 的共享边界。

#### Scenario: 前置输入不可用

- **WHEN** readiness 表明当前源码、x86 构建、分析工具不满足条件，或创建接口表明共享 BC 不可用
- **THEN** 页面 SHALL 展示后端提供的原因和适用的上游处理入口
- **AND** SHALL NOT 仅凭 x86 构建成功或画布辅助状态允许扫描

#### Scenario: 用户主动启动分析

- **WHEN** 接口已接入且用户明确发起分析
- **THEN** 页面 SHALL 调用真实创建接口并依据服务端任务标识展示执行状态
- **AND** SHALL 处理进行中、失败及终态，通过既有任务事件机制刷新相关数据

### Requirement: 专项分析使用真实数据

两项分析 SHALL 使用后端正式接口，不以示例数据填充空记录或请求失败。

#### Scenario: 真实接口已发布

- **WHEN** 用户查看任一专项分析
- **THEN** 页面 SHALL 使用 `memory-order-analysis` 或 `floating-point-analysis` 的正式阶段 ID 查询最新执行、readiness 和分页历史
- **AND** SHALL 将完整工具证据保留在详情中，后端未提供源码片段时明确提示缺失

#### Scenario: 结果纳入报告

- **WHEN** 用户确认当前源码的已发布 finding 或 source candidate
- **THEN** 前端 SHALL 使用 `MEMORY_ORDER_FINDING` 或 `FLOATING_POINT_FINDING` 的既有报告输入操作
- **AND** SHALL NOT 自动确认候选或将旧源码结果加入当前报告

### Requirement: 分析风险与完整性独立表达

前端 SHALL 分别展示执行状态、结果完整性及风险结论，并保留后端提供的未知、跳过、失败和源码证据。

#### Scenario: 当前前置条件阻塞且已有分析记录

- **WHEN** 当前前置条件未满足且项目已有分析记录
- **THEN** 最新分析入口 SHALL 优先展示当前阻塞原因与上游处理入口，不同时展示旧终态完整结果或重试操作；若真实任务仍在运行，SHALL 按 project-stage-runtime 保留辅助运行事实与日志入口
- **AND** SHALL 保留历史记录入口，历史详情按所选记录展示执行状态和日志，不受当前前置条件阻塞覆盖
- **AND** Workflow Drawer 与 Step Panel SHALL 使用相同状态优先级

#### Scenario: 返回部分分析结果

- **WHEN** 扫描结果包含 partial、unknown、skipped 或 failed 的分析范围
- **THEN** 页面 SHALL 显示已分析与未确定的范围，不将未知项隐藏为成功

#### Scenario: 没有风险发现

- **WHEN** 结果返回零 finding
- **THEN** 页面 SHALL 展示真实空结果与分析边界
- **AND** SHALL NOT 将零 finding 表达为全程序安全或迁移无风险

#### Scenario: 查看执行历史与源码证据

- **WHEN** 用户查看历史分析或风险详情
- **THEN** 页面 SHALL 显示后端给出的源码版本、新鲜度及证据
- **AND** SHALL 沿用阶段共享的日志、正文滚动及列表详情往返规则

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
