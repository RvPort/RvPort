## ADDED Requirements

### Requirement: 源码分析必须独立于可选构建步骤
后端 SHALL 允许固定源码及必需本地工具/profile 已就绪的 KB、浮点和内存序分析启动，不强制 x86 构建、远程环境、编译数据库或 bitcode；MUST NOT 新增跳过动作、状态或伪造构建。

#### Scenario: 只有源码
- **WHEN** 项目没有构建和远程环境绑定，但源码及工具/profile 可用
- **THEN** 三个源码分析 SHALL 可以创建，运行使用内部源码计划；x86 状态仍为未执行，依赖分析继续受其硬前置约束。

#### Scenario: 前置构建失败
- **WHEN** x86 构建失败、取消、超时或仍在运行
- **THEN** SHALL 保留真实构建状态且允许源码模式分析，不自动补建、不制造 SKIPPED。

### Requirement: 内部源码计划必须固定并公开解析假设
后端 SHALL 基于已知源码 manifest 按分析器支持能力建立计划；KB 保留所选多语言，浮点/内存序建立稳定 C/C++ 分析计划，固定语言/profile/工具/规则版本和安全解析参数；没有真实参数时 SHALL 标为默认分析假设，不能登记为真实 compile database。

#### Scenario: 没有编译数据库
- **WHEN** 内部为现有 CLI 准备显式翻译单元或源码请求
- **THEN** SHALL 标记 SOURCE_DISCOVERY 与 DEFAULT_ASSUMPTION，保留 planned/failed/unsupported/omitted 范围，不执行用户构建脚本或猜测完整链接 Scope。

#### Scenario: 无法可靠解析
- **WHEN** 头文件、宏、语言或资源限制使部分源码无法分析
- **THEN** SHALL 保留合法候选及真实双语诊断，无法证明的位置保持未知，不把错误恢复 AST 或零候选解释为安全。

#### Scenario: 输入完整性和安全
- **WHEN** 读取固定源码和可选制品
- **THEN** SHALL 校验归属、路径边界、符号链接、hash 和版本；必需源码不完整时失败，不读取任意本机文件或执行仓库命令。

### Requirement: 可选增强必须按实际输入和上下文校验
后端 SHALL 固定 requestedInputDigest 与 effectiveInputDigest，使用同项目同源码及匹配上下文的可信产物；MUST NOT 运行时重新选 latest。

#### Scenario: 增强输入可用
- **WHEN** 当前选中的真实成功 SOURCE_ONLY 构建有可信编译数据库及可选 BC
- **THEN** SHALL 固定其 ID/hash/profile 和覆盖信息，真实编译标准不能被默认解析假设覆盖。

#### Scenario: 增强输入不可用
- **WHEN** 可选产物缺失、损坏、过期或归属不符
- **THEN** SHALL 不使用该产物，安全选择源码模式或记录执行降级，保留原因及实际计划，不从其他构建补绑。

#### Scenario: 无关环境变化
- **WHEN** SOURCE_DISCOVERY 没有消费远程环境或构建且仅这些配置改变
- **THEN** 结果 SHALL 仍适用；实际源码/profile/策略改变则重新判定，不以全局环境版本统一作废。

#### Scenario: 后续构建补齐
- **WHEN** 同源码新增可信增强产物但尚未重跑分析
- **THEN** SHALL 保留仍适用的源码结果并返回 enhancementAvailable，不能把它伪装为已使用新产物的结果。

#### Scenario: 已固定实际计划后的重试
- **WHEN** 首次尝试已记录源码降级或仅编译数据库的实际计划，后续尝试时可选产物恢复
- **THEN** SHALL 沿用已记录的实际计划；不能重新启用此前未消费的产物。已经绑定并实际消费的输入若在恢复时不可用，应受控失败，不覆盖已固定计划。

#### Scenario: 文件范围不等于执行完成
- **WHEN** manifest 已知文件及语言范围，但工具没有逐语言实际完成计数
- **THEN** SHALL 保留各语言的计划/未选择/不支持/排除/遗漏计数并标明执行统计未知；不得将 PLANNED 文件改称已分析或混用文件和 TU 分母。

### Requirement: 分析候选必须优先召回且保持事实边界
后端 SHALL 保留工具合法产出的低置信、SOURCE_CANDIDATE、INCONCLUSIVE 和 partial 候选供后续复核，不因判断不确定或数量较多静默删除。

#### Scenario: 判断可能存在误报
- **WHEN** 候选有真实源码线索但缺少编译或运行依据
- **THEN** SHALL 保留位置/来源/假设/限制并标记待核实，不能伪造已证明风险或置信度。

#### Scenario: 多来源及数量超限
- **WHEN** 多来源候选相似或达到处理预算
- **THEN** SHALL 保留逐来源身份和证据；已有同源精确归并可继续但不可丢关联；超限返回明确未处理范围，不能截断后宣称完整成功。

### Requirement: 源码独立分析必须完成本地质量验收
实现 SHALL 限 backend、dev 和本地 Docker，整体及完整受影响模块行/分支各至少 90%，统计未加载自有源码，不修改前端、工具独立根或远程环境。

#### Scenario: 交付门禁
- **WHEN** 实施完成前
- **THEN** SHALL 运行 Java/自有脚本单元测试、锁定 CLI 的无编译数据库真实输入验证、独立数据库与完整 Compose V01–V19，保存命令/范围/分母/报告；任一失败不得标完成。

### Requirement: 分析计划必须固定语言架构和选择范围
后端 SHALL 固定规范化语言集合、源/目标架构、scanScope、excludePaths 及规范化策略版本，纳入请求/实际输入摘要与来源快照；MUST NOT 在恢复时重新解释为新的进程默认配置。

#### Scenario: 等价语言与别名
- **WHEN** 语言顺序、空白、大小写、重复或已支持架构别名不同但语义相同
- **THEN** SHALL 产生一致规范化身份；未知语言拒绝，架构既有回退需保存请求值与假设，不将未知架构当真实x86证据。

#### Scenario: 参数改变或仅预览
- **WHEN** 同源码的新请求改变语言、架构、scope或排除范围，或用户只读取新选项预览
- **THEN** SHALL 真实变化作为不同计划，不能复用旧批次或冒充新选项结果；只读预览不得修改当前结果，合法新结果发布后才重算报告来源选择。

#### Scenario: 架构与TU匹配
- **WHEN** 增强产物架构、producer/target/ABI、编译上下文或TU语言与计划不匹配，或无法证明匹配
- **THEN** SHALL 拒绝使用该增强并记录源码降级原因；x86制品不得用于arm64请求，仅匹配子集可增强，其他语言保留原生源码分析与覆盖事实。

### Requirement: 覆盖率验收必须证明当前测试实际采集
后端 SHALL 同时验证测试实际执行、JaCoCo代理生效、当前exec/XML/HTML完整及全部自有代码范围；整体与完整受影响模块行/分支至少90%，报告缺失不能视为通过。

#### Scenario: 保留限堆与代理
- **WHEN** Surefire运行单元测试
- **THEN** SHALL 同时保留堆上限和JaCoCo注入，Docker小范围采集验证后再执行完整clean verify，不使用旧镜像或旧报告。

#### Scenario: 缺失或无效报告
- **WHEN** exec/XML缺失、空报告、跳过测试、旧来源报告或完整模块清单不匹配
- **THEN** SHALL 独立门禁非零退出，即使Maven返回成功也不得标记验收；负向夹具验证这些失败路径，Python报告同样检查来源和完整性。

#### Scenario: 分母校验
- **WHEN** 读取当前覆盖报告
- **THEN** SHALL 测试数和整体行分母大于零，纳入未加载自有代码，逐完整受影响包/脚本校验；确无分支的模块明示零分母，不伪报100%。
