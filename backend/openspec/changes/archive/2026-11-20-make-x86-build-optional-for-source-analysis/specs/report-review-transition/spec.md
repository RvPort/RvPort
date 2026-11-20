## MODIFIED Requirements

### Requirement: 重新生成必须有条件继承且重新确认
后端 SHALL 固定继承基线，只有同源码版本且身份/关键证据/位置无实质变化才继承 TO_FIX/EXCLUDED，禁止按标题模糊确认。

#### Scenario: 可靠继承
- **WHEN** 同来源问题唯一匹配且语义/位置/源码内容一致
- **THEN** SHALL 保留人工决定并记录原报告/条目/操作者，不继承最终确认状态。只有自动分流记录的条目按本次结论初始化，连续重新生成仍须保留可追溯的人工决定。

#### Scenario: 变化或歧义
- **WHEN** 新增、证据变化、一对多匹配或跨源码版本
- **THEN** SHALL 按本次模式与结论初始化新问题，返回未继承原因；跨源码不继承旧人工项。

#### Scenario: 人工项继承
- **WHEN** 同版本人工片段可精确验证
- **THEN** SHALL 继承并保留原始 actor；不能定位时列出未继承记录，不静默丢弃。

#### Scenario: 生成期间草稿变化
- **WHEN** 旧报告草稿修改或确认后新生成尝试发布
- **THEN** SHALL 校验完整 reviewRevision，冲突则失败且保留旧报告，不能覆盖新人工决定或自动重跑 AI。

#### Scenario: 成功替换
- **WHEN** 新报告完成且 Context/基线未变化
- **THEN** SHALL 原子冻结旧草稿、切换当前报告，新草稿须重新确认；历史所有写请求拒绝。

#### Scenario: 输入增强改变关键证据
- **WHEN** 同源码后来用真实构建产物重新分析导致关键证据或位置实质改变
- **THEN** SHALL 重新初始化相应条目并说明未继承原因；完全一致的人工决定仍优先，不因为分析模式名称改变而盲目继承或全部丢弃。

## ADDED Requirements

### Requirement: 报告 Context 必须按实际分析依赖失效
后端 SHALL 以固定来源选择及其实际输入依赖统一 selectionToken、reportUpToDate、草稿可写性和 confirmation 有效性，不将未消费的环境/构建变化强行加入 SOURCE_DISCOVERY Context。

#### Scenario: 新产物但尚未重新分析
- **WHEN** 同源码出现新的可信构建产物而既有源码分析仍有效
- **THEN** SHALL 提示 enhancementAvailable，不自动替换来源或使报告与确认失效；新合法分析发布后才重算来源选择。

#### Scenario: 相关 Context 变化
- **WHEN** 源码、实际 profile/解析策略、已消费构建上下文或所选合法结果改变
- **THEN** SHALL 旧报告与确认只读且不得用于当前修复依据，生成发布校验相同 Context 及 reviewRevision，迟到任务不能覆盖当前报告。

#### Scenario: 历史与协议切换
- **WHEN** 引入新输入计划版本
- **THEN** SHALL 保留已有新报告流程的不可变报告、审计与确认，不推测填充旧输入；无法证明兼容的排队任务受控拒绝并提示重发，不从 latest 补绑定，不复用早期流程重建策略删除业务历史。

### Requirement: 人工继承必须考虑语言架构和范围身份
后端 SHALL 将影响条目的语言、源/目标架构、规则和关键证据纳入继承判定，不以同源码同位置代替语义一致性。

#### Scenario: 架构改变
- **WHEN** 新报告同位置条目的源架构或规则证据实质改变
- **THEN** SHALL 不继承旧人工结论并说明原因；等价语言顺序/架构别名本身不导致继承失效，所有报告仍须重新手动确认。
