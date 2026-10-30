# assessment-report Specification

## Purpose

定义评估报告的输入纳入、当前报告生成、模块—文件—Finding 阅读路径、源码证据及历史追溯，使用户能从当前迁移结论定位到报告绑定版本的具体代码问题。

## Requirements

### Requirement: 报告纳入状态必须在列表与详情保持一致

知识库 finding 与依赖分析结果 SHALL 在当前 Context 下提供互斥的单条“纳入报告 / 排除”操作，历史 Context 保持只读。

#### Scenario: 修改当前结果的纳入状态

- **WHEN** 用户对当前 finding 或依赖结果执行纳入或排除
- **THEN** 未纳入或已排除时 SHALL 只提供“纳入报告”
- **AND** 已纳入时 SHALL 只提供“排除”
- **AND** 更新成功 SHALL 同步刷新详情、来源列表、报告 readiness 和受影响的聚合结果

#### Scenario: 查看历史结果

- **WHEN** finding 或依赖结果不属于当前源码或当前环境配置
- **THEN** 列表和详情 Footer SHALL NOT 提供纳入或排除操作
- **AND** 页面 SHALL 明确其为历史只读记录

### Requirement: 当前报告生成与历史

评估报告 SHALL 使用 `report` readiness 的 `KB_SCAN`、`DEPENDENCY_ANALYSIS` 和 `INCLUDED_FINDINGS` 条件及领域 latest，区分当前报告、旧 Context 报告和尚未生成。

#### Scenario: 生成报告

- **WHEN** 报告前置条件满足且用户确认生成
- **THEN** 页面 SHALL 创建报告任务并通过项目事件刷新执行状态和报告结果
- **AND** 前置条件不满足或查询失败 SHALL 分别展示业务恢复或请求重试

#### Scenario: 浏览历史报告

- **WHEN** 用户选择历史报告
- **THEN** 页面 SHALL 展示该报告绑定的模块、文件、Finding 与源码上下文，不把历史报告当作当前 Context 结果

#### Scenario: 从当前概览进入历史

- **WHEN** 当前报告加载失败、受阻、执行中或已有结果
- **THEN** frontend SHALL 保留标题区历史入口，独立查询项目范围的报告列表
- **AND** 选择记录 SHALL 通过记录 ID 读取详情，沿用模块、文件、问题阅读路径，返回历史后再返回当前概览
- **AND** 历史详情 SHALL NOT 提供生成或重新生成入口

### Requirement: 评估报告依赖分析映射

评估报告 SHALL 将依赖分析输入的来源、风险和目标侧匹配状态映射为用户可读文本。

#### Scenario: 展示依赖分析来源与状态

- **WHEN** 评估报告包含 `DEPENDENCY_RESULT` 输入
- **THEN** 系统 SHALL 展示“依赖分析”而非原始来源枚举
- **AND** SHALL 映射依赖分析的目标侧匹配状态和 `COMMENT` 风险等级

### Requirement: 评估报告信息层级

评估报告 SHALL 以报告结论和代码模块作为主要阅读路径，并统一使用模块、文件和 Finding 的服务端组织事实；页面 SHALL 通过清晰的分段、筛选、对齐与文案层级表达这些事实，且 MUST NOT 生成跨字段推导结论。

#### Scenario: 展示报告概览

- **WHEN** 评估报告生成成功
- **THEN** 系统 SHALL 展示生成时间、总体风险、代码模块数、影响文件数、代码 Finding 数、依赖问题数和高风险问题数
- **AND** SHALL 以代码 Finding 数作为主要数量，并将其余统计保持为独立标注的次级事实
- **AND** SHALL 以模块作为代码迁移的一级主体
- **AND** SHALL NOT 根据模块数、文件数或问题数生成分布、结论或其他组合推导文案
- **AND** SHALL NOT 生成工作包、前置依赖或验收标准

#### Scenario: 展示输入构成

- **WHEN** 模块化报告包含代码 Finding 与依赖输入
- **THEN** 系统 SHALL 在代码模块中展示风险与问题类型分布
- **AND** SHALL 在存在依赖输入时将依赖清单作为可展开辅助区展示

#### Scenario: 展示代码模块列表

- **WHEN** 报告包含一个或多个代码模块
- **THEN** 模块行 SHALL 将代码问题数作为主要统计，并将影响文件数作为次级统计
- **AND** SHALL NOT 在右侧重复展示已由问题类型分布明确表达的类型数量
- **AND** 模块行中的标题、元信息、统计和操作 SHALL 建立清晰的阅读层级

#### Scenario: 从模块进入文件

- **WHEN** 用户选择一个代码模块
- **THEN** 系统 SHALL 展示该模块的识别依据、置信度、风险与问题类型分布
- **AND** SHALL 分页展示该模块包含的文件
- **AND** 问题类型 SHALL 作为文件筛选条件而非独立导航层级
- **AND** 动态问题类型 SHALL 使用可搜索且可容纳长名称和大量选项的选择控件
- **AND** 模块只有一种问题类型时 SHALL 继续提供全部问题与该类型之间的筛选能力

#### Scenario: 查看报告文件

- **WHEN** 用户选择模块中的文件
- **THEN** 系统 SHALL 展示该文件的 Finding 列表和报告绑定源码版本的联合上下文
- **AND** 初次进入文件详情时 SHALL 展示源码上下文接口声明的默认命中行，同时保持初始源码位置
- **AND** 选择 Finding SHALL 突出对应源码行
- **AND** 选中 Finding 时，系统 SHALL 优先使用列范围突出具体命中代码；列范围为空时 SHALL 依次使用 `sourceDetail.evidence.matchedText` 和 `symbol` 在对应源码行中进行精确文本匹配，仅在匹配成功时展示代码范围下划线
- **AND** 系统 SHALL 使用代码编辑器展示真实源码行号及接口返回的列范围高亮
- **AND** 文件问题类型 SHALL 使用可搜索且可容纳长名称和大量选项的选择控件
- **AND** 文件只有一种问题类型时 SHALL 继续提供全部问题与该类型之间的筛选能力
- **AND** 未选中的长内容 SHALL 提供紧凑预览和受控悬浮提示，同一时刻 SHALL 至多展示一个提示，切换 Finding 时 SHALL 立即关闭先前提示，选中的 Finding SHALL 在列表内展示完整内容
- **AND** Finding 列表 SHALL 在同一摘要行展示行号、问题类型和风险级别，再依次展示命中内容和建议，不得将长度和语义不稳定的命中符号强制作为标题
- **AND** 文件图标、路径首行、统计和操作 SHALL 以上边缘为锚点对齐
- **AND** 文件路径换行时图标 SHALL 保持与路径第一行对齐而非相对整个内容块垂直居中
- **AND** 源码上下文不可用时 SHALL 保留 Finding 列表并说明原因

#### Scenario: 展示结构化省略区间

- **WHEN** 联合源码上下文在两个 `CODE` block 之间包含 `OMITTED` block
- **THEN** 编辑器 SHALL 在两个真实代码片段之间原位展示省略区间的起止行和省略行数
- **AND** 省略提示 SHALL NOT 作为源码文本写入编辑器模型或复制内容
- **AND** 可加载区间 SHALL 在对应断点提供加载操作，而不是只在编辑器顶部列出脱离上下文的区间按钮

#### Scenario: 分批展开较大省略区间

- **WHEN** 用户展开的 `OMITTED` 区间超过接口单次允许读取的行数
- **THEN** 系统 SHALL 按接口上限加载真实源码并将已返回行插入正确位置
- **AND** 尚未加载的剩余范围 SHALL 继续显示为可展开省略区间
- **AND** 系统 SHALL NOT 在只加载部分范围后将整个区间标记为已加载

#### Scenario: 展示报告枚举和动态分类

- **WHEN** 页面展示模块识别依据、置信度或已知 Finding 类型
- **THEN** 系统 SHALL 使用集中维护的中文业务文案而非直接展示后端枚举
- **AND** 模块识别依据 SHALL 覆盖后端当前返回的 `EXPLICIT_BUILD_TARGET`、`NEAREST_BUILD_DESCRIPTOR`、`DIRECTORY`、`PROJECT_ROOT` 和 `BUILD_ARTIFACT`
- **AND** 已知 Finding 类型 SHALL 在筛选、统计和详情中使用相同映射
- **AND** 未知动态 Finding 类型 SHALL 保留稳定、非空且可辨识的降级文案

### Requirement: 报告下载

当前报告 SHALL 提供后端 HTML 和 JSON 格式的下载入口，下载目标由报告 ID 与请求格式确定。

#### Scenario: 下载当前报告

- **WHEN** 用户选择下载 HTML 或 JSON
- **THEN** 页面 SHALL 打开该报告对应的后端下载地址，不在浏览器重新拼装报告产物

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
