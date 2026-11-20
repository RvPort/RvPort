# memory-order-analysis-runtime Specification

## Purpose
TBD - created by archiving change integrate-floating-point-and-memory-order-analysis-runtime. Update Purpose after archive.
## Requirements
### Requirement: 内存序分析必须使用统一项目入口
Worker SHALL 根据可信 compile database 或 SOURCE_DISCOVERY 内部计划为合法 C/C++ 翻译单元准备带解析假设的源码输入，并在共享 BC 可用时将其适配为工具外部 bundle；Worker SHALL 只调用 `memory_order_analyzer analyze-project`，不得由 Java 编排工具内部分析阶段。成功 x86 构建、编译数据库和共享 BC SHALL 均为可选增强，不是任务创建硬前置；仅使用独立分析 profile 即可解析时不要求远程环境。

#### Scenario: 使用完整 BC 运行全部 Scope
- **WHEN** 当前源码版本具有成功 x86 构建、真实 compile database 且全部计划翻译单元具有可用 BC record
- **THEN** 后端 SHALL 以 `scope_selection=all`、`source_assistance=auto` 和固定 policy 创建统一请求
- **AND** 所有可分析翻译单元模式 SHALL 为 `ast-ir`
- **AND** 工具 SHALL 自行编排内部阶段和内部 ArtifactKey

#### Scenario: 无 BC 时运行 AST-only
- **WHEN** 当前源码与源码解析计划可用，但没有可用 BC record
- **THEN** 后端 SHALL 为合法翻译单元生成 SourceSemanticRequest 并创建统一分析请求
- **AND** 翻译单元模式 SHALL 为 `ast-only`
- **AND** 工具 SHALL 将仅有源码证据的结论保存为 source candidate、unknown 或 frontier
- **AND** MUST NOT 将其提升为已经证明的 runtime risk 或全程序安全结论

#### Scenario: 部分 BC 使用混合证据
- **WHEN** 仅部分翻译单元具有校验成功的 BC
- **THEN** 具有 BC 和源码证据的翻译单元 SHALL 使用 `ast-ir`
- **AND** 仅具有源码证据的翻译单元 SHALL 使用 `ast-only`
- **AND** 单个翻译单元缺少 BC MUST NOT 阻断其他翻译单元或整个任务

#### Scenario: 平台身份与工具身份隔离
- **WHEN** adapter 转换平台 record、源码请求和 scope
- **THEN** adapter SHALL 保留源码、编译、producer、target、ABI、profile、语言标准、方言、分母和 scope 语义
- **AND** MUST NOT 把平台 artifact key 冒充为分析器内部 ArtifactKey

#### Scenario: 旧语言标准和方言按真实上下文分析
- **WHEN** compile database 指定 C89/C90、C99、C++98/C++03 或相应 GNU 方言
- **THEN** source profile SHALL 保留真实标准与方言并使用锁定 Clang 重放 AST
- **AND** MUST NOT 回退为 C17 或 C++17 标签
- **AND** 依赖较新语言能力的规则 SHALL 对该翻译单元标记不适用或 unknown，而不是拒绝整个项目

#### Scenario: 开放 Scope
- **WHEN** scope 声明 `closed=false`
- **THEN** 后端 SHALL 允许工具运行
- **AND** 最终 partial/frontier 语义 SHALL 由统一报告决定，不得伪造闭合范围

#### Scenario: 无数据库源码计划
- **WHEN** 只有固定源码，没有 x86 构建和编译数据库
- **THEN** SHALL 通过既有 source requests 和开放 Scope 执行统一入口，记录 SOURCE_DISCOVERY、默认假设和 planned/failed/omitted 范围；不运行工程构建，不创建假构建记录或伪造闭合 Scope。

#### Scenario: 解析上下文不足
- **WHEN** 内部计划无法可靠解析部分源码或确认并发关系
- **THEN** SHALL 保留工具合法源码候选及 unknown/frontier，不能用错误恢复 AST 证明线程关系或安全，零候选仍保留缺口。

### Requirement: 内存序任务状态必须遵循统一报告和退出码
后端 SHALL 同时校验 `UnifiedMemoryOrderAnalysisReport v1` 的 status、verdict 和 exit_code，并使用工具定义的退出码语义结束任务。

#### Scenario: 受支持模型内未发现风险
- **WHEN** 工具返回退出码 0 和 `no-finding-within-supported-model`
- **THEN** 任务 SHALL 成功
- **AND** 前端语义 MUST NOT 被翻译为全程序安全或 thread-safe

#### Scenario: 发现正式风险
- **WHEN** 工具返回退出码 3 和 `risk`
- **THEN** 任务 SHALL 成功并发布 `task.succeeded`
- **AND** MUST NOT 因非零退出码进入重试或死信

#### Scenario: 部分或未知结论
- **WHEN** 工具返回退出码 2 和 partial、unknown、ambiguous 或可保留失败结果
- **THEN** 运行 SHALL 以 PARTIAL 结果可查询
- **AND** 后端 SHALL 保存已完成 Finding、source candidates、frontier 和 diagnostics

#### Scenario: 分析失败或状态冲突
- **WHEN** 工具返回退出码 1，或报告 status/verdict/exit_code 相互矛盾
- **THEN** 任务 SHALL 失败
- **AND** 后端 MUST NOT 把不完整输出提升为成功结论

### Requirement: 内存序结果必须区分风险、候选和审计信息
后端 SHALL 逐条保存正式 findings 与 source candidates，并使用 Finding kind、verdict 和 status 明确区分；discharged assessments、unsupported、frontier 和 diagnostics SHALL 作为审计信息保存。

#### Scenario: 导入正式风险 Finding
- **WHEN** 统一报告包含 `MemoryOrderFindingProjection` 且 verdict 为 risk
- **THEN** 后端 SHALL 保存风险、置信度、双语说明、位置、occurrences、evidenceChain、assumptions、limitations 和 semantic digest

#### Scenario: 导入源码候选
- **WHEN** AST-only 或证据不足产生 source candidate
- **THEN** 后端 SHALL 保留并标记为候选或不确定结果
- **AND** MUST NOT 将候选展示为已经证明的 runtime risk

#### Scenario: 保留证据断点
- **WHEN** Finding evidenceChain 包含 breaks 或 frontier
- **THEN** 详情 SHALL 返回对应断点、原因和双语建议
- **AND** 报告 SHALL 能说明置信度限制

### Requirement: 内存序分析必须保存完整审计目录
后端 SHALL 把统一报告、逐项 Finding、Scope execution、source snapshots、artifact index 和 data 审计文件作为受控结果 bundle 保存到对象存储，并生成轻量 HTML。

#### Scenario: 保存部分结果目录
- **WHEN** 某些 Scope 完成而其他 Scope 失败或取消
- **THEN** 后端 SHALL 原子保存工具发布的可审计输出树
- **AND** 查询 SHALL 返回完成与未完成 Scope 的分母

#### Scenario: 拒绝不安全输出引用
- **WHEN** 工具输出引用绝对路径、路径逃逸、链接或 digest 不匹配文件
- **THEN** 后端 MUST 拒绝导入结果
- **AND** MUST NOT 上传不安全文件到对象存储

### Requirement: 内存序源码位置必须来自关联的真实证据
系统 SHALL 为新生成的正式 Finding、源码候选和已消解审计投影解析真实关联的源码位置；位置 SHALL 受 scope、翻译单元和编译上下文约束，MUST NOT 用首个文件或同名符号猜测位置。存在多处可信位置时 SHALL 保留关联位置并确定性选择主位置；步骤只引用自己的证据。

#### Scenario: 正式风险具有访问证据
- **WHEN** publication 或 advanced-atomic 评估关联的源码或 IR 访问具有可信位置
- **THEN** 工具 SHALL 输出非空 primaryLocation、对应 occurrence 和属于相关步骤的 source，后端 SHALL 导入主位置并保留 detail

#### Scenario: 多文件及已消解审计结果
- **WHEN** 一个结论关联多个项目文件或结论属于 discharged 审计结果
- **THEN** 投影 SHALL 保留可信位置及角色，不把主位置复制到无关步骤，不改变原有结论分类和风险判断

#### Scenario: 无法唯一追溯证据
- **WHEN** 引用缺失、歧义或 IR 无可用调试位置且无法可信绑定 AST
- **THEN** 系统 SHALL 保留未知位置、稳定原因码和双语说明，MUST NOT 伪造片段或提高证明完整性

### Requirement: 内存序源码上下文必须与实际文件匹配
系统 SHALL 根据证据所属文件及可信源码快照生成上下文，校验文件路径和摘要；MUST NOT 把头文件行号应用到主翻译单元。宏位置 SHALL 明确区分 expansion 与 spelling，跨文件范围 MUST NOT 被当作单文件范围。

#### Scenario: 头文件与宏展开
- **WHEN** 候选位于项目头文件或宏中
- **THEN** 系统 SHALL 使用实际文件快照，优先选择可信项目内 expansion，并保留不同的可信 spelling 关联；没有 expansion 时显式使用可信 spelling

#### Scenario: 范围及预算
- **WHEN** 位置具有有效源码范围
- **THEN** 上下文 SHALL 默认包含前后各 20 行并正确高亮 end-exclusive 范围，文件边界处返回实际可用行，预算截断 SHALL 结构化标记

#### Scenario: 文件不可安全读取
- **WHEN** 路径越界、链接不安全、摘要不符、文件缺失、编码不支持或坐标无效
- **THEN** 系统 SHALL 返回明确不可用原因，不返回其他文件片段、不泄露服务端路径，不把源码读取问题伪装成并发证明

### Requirement: 内存序详情必须一次返回主位置代码上下文
现有内存序 Finding 详情 API SHALL 在权限校验后随响应返回 codeContext 和 codeContextUnavailableReason，使用 Finding 绑定的源码版本；工具嵌入的关联位置上下文 SHALL 保留在 detail。系统 MUST NOT 为此新增专用取代码 API，也 MUST NOT 静默回填历史扫描。

#### Scenario: 有效位置详情
- **WHEN** 用户有项目权限且 Finding 主位置和绑定源码可用
- **THEN** 同一次详情请求 SHALL 返回路径、代码行、起止行号及高亮，codeContextUnavailableReason 为 null

#### Scenario: 未知位置及历史记录
- **WHEN** 位置或源码不可用，或者读取没有新位置字段的历史结果
- **THEN** 详情 SHALL 继续返回 Finding 及明确不可用原因，不猜测位置，不改写历史 detail

#### Scenario: 跨项目请求
- **WHEN** 用户无项目权限或 Finding 不属于请求项目/分析类型
- **THEN** 系统 SHALL 拒绝请求，不读取或返回代码

### Requirement: 内存序位置修复必须通过独立测试和真实链路验证
后端与工具 SHALL 覆盖正常、异常、边界独立单测，mock 外部连接返回值，在 Linux Docker 中执行；全部自有源码含未加载模块的整体及受影响模块行/可用分支覆盖率 SHALL 至少 90%。系统 SHALL 同步工具规范与中文文档，并保留真实编译器/API 的验证记录。

#### Scenario: 端到端位置一致
- **WHEN** 对 AST-only 和 AST+BC 样本执行验证
- **THEN** 验证 SHALL 对照真实源码、工具 JSON、落库位置和详情的文本/高亮，覆盖头文件、宏、多位置、正式风险、源码候选及不可用负例，且不得以成功退出替代语义断言

#### Scenario: 质量门禁不足
- **WHEN** 单测失败或覆盖率不达标
- **THEN** 验收 SHALL 保持未完成，记录命令、统计范围、排除依据和报告路径，不以集成测试代替独立单测
