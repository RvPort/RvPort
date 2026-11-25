# floating-point-analysis-runtime Specification

## Purpose
TBD - created by archiving change integrate-floating-point-and-memory-order-analysis-runtime. Update Purpose after archive.
## Requirements
### Requirement: 专项 Finding 可读取版本绑定的源码上下文
后端 SHALL 在浮点和内存序 Finding 的现有详情响应中直接内嵌 codeContext，不提供独立 code-context API；使用 Finding 的 sourceRevisionId 读取源码，不回退到当前版本。主位置前后 SHALL 各展示最多 20 行，返回真实行号、片段起止、高亮范围及逐行 highlighted 标记。列表 SHALL 不批量加载源码。

#### Scenario: 历史结果源码读取
- **WHEN** 当前源码版本已更新但历史 Finding 对应工作副本仍存在
- **THEN** SHALL 返回历史版本的源码及 sourceRevisionId，不能把当前代码拼入历史结果

#### Scenario: 上下文边界及安全
- **WHEN** 请求无权限或跨项目/类型
- **THEN** SHALL 拒绝整个请求，不读取源码

#### Scenario: 上下文不可用不影响结果详情
- **WHEN** 路径越界、无有效位置、文件已清理、编码错误或超过 8 MiB
- **THEN** SHALL 保留 Finding 详情，codeContext 返回 null，并提供不泄漏服务器路径的 codeContextUnavailableReason；合法短文件 SHALL 仅返回已有行

### Requirement: 浮点源码位置必须兼容 Clang 省略字段
扫描器 SHALL 在文件归属可证明时结合翻译单元、AST 文件上下文和源码字节偏移恢复位置，输出项目相对路径及 1-based 行列，并正确恢复内部源码范围。系统 MUST NOT 因 Clang 合法省略 file 或 line 而丢失本可恢复的位置，也 MUST NOT 伪造坐标。

#### Scenario: 表达式只包含偏移和列
- **WHEN** 浮点表达式节点只有合法 offset、col，所属项目文件可确定
- **THEN** 扫描器 SHALL 根据该文件字节内容恢复准确文件和行列并传递到 Finding

#### Scenario: 多字节与换行边界
- **WHEN** 表达式之前存在 UTF-8 多字节字符、CRLF、制表符或多行内容
- **THEN** 恢复位置 SHALL 使用 Clang 字节偏移语义并准确指向表达式，不能使用 Unicode 字符计数替代字节索引

#### Scenario: 宏与头文件位置
- **WHEN** AST 含项目头文件、外部头文件和宏的 spelling/expansion 位置
- **THEN** 扫描器 SHALL 分别解析真实来源并维持项目边界，不能把外部文件偏移应用到主源码

#### Scenario: 位置无法安全恢复
- **WHEN** 文件缺失、归属不明、偏移越界或路径超出授权边界
- **THEN** 扫描器 SHALL 保留未知位置并提供脱敏双语诊断，不读取未授权文件，不默认填充第 1 行，不删除风险结论

### Requirement: 浮点聚合位置必须完整一致
扫描器 SHALL 以直接证据对应的去重位置集合生成稳定主位置和关联 occurrences；主位置 SHALL 属于该集合，其他可用位置 SHALL 在预算内保留，预算不足 SHALL 显式说明。

#### Scenario: 同类结果包含多个位置
- **WHEN** 同类结果包含重复位置、不同位置和未知位置
- **THEN** 主位置 SHALL 优先取可用位置，关联位置 SHALL 去重且不能漏掉任一预算内的不同位置，也不能重复主位置

### Requirement: 浮点位置修复必须贯通现有结果接口
后端 SHALL 将工具提供的位置一致地保存到 Finding 索引字段和 detail，并通过现有列表、详情和 HTML 返回；系统 SHALL 保持 schema v2 与历史结果不可变。

#### Scenario: 新扫描位置贯通
- **WHEN** 新扫描 JSON 包含非空合法主位置及关联位置
- **THEN** 数据库和列表/详情/HTML SHALL 与原始结果一致，列表按该路径行号过滤排序有效

#### Scenario: 历史空位置结果
- **WHEN** 部署修复后读取已有空位置扫描
- **THEN** 历史结果 SHALL 保持原状，新的准确位置 SHALL 通过重新扫描产生

### Requirement: 指针比较不得误判为浮点标量比较
扫描器 SHALL 根据比较操作数的实际标量类型识别浮点比较，支持 typedef 展开；MUST NOT 仅因指针指向浮点类型就产生浮点比较风险。

#### Scenario: 空指针及指针间比较
- **WHEN** float/double 指针与 nullptr、零值空指针常量或另一个指针比较
- **THEN** SHALL 不产生浮点 comparison 事实及对应的浮点比较 Finding

#### Scenario: 解引用及别名标量比较
- **WHEN** 指针解引用、数组元素、引用值或 typedef 浮点标量执行数值比较
- **THEN** SHALL 保留真实浮点比较事实并继续按现有规则判断风险

### Requirement: 位置恢复必须通过独立测试与真实 AST 验证
修复 SHALL 覆盖正常、异常、边界单测，纳入未加载自有源码；整体与受影响模块行及可用分支覆盖率 SHALL 至少 90%。真实 Clang 集成测试和项目 API E2E SHALL 补充验证真实位置，但不能替代单测。

#### Scenario: 验证真实位置
- **WHEN** Linux Docker 中运行测试及真实 Clang fixture
- **THEN** 测试 SHALL 断言具体路径行列、宏和头文件边界、聚合完整性与 v2 合法性，单测不依赖外部服务

#### Scenario: 质量门禁不通过
- **WHEN** 测试失败或整体/模块覆盖率未达门槛
- **THEN** 本 change SHALL 保持未完成并记录差距，不能通过排除自有业务代码降低统计范围

### Requirement: 浮点分析必须消费当前构建上下文和共享 BC
系统 SHALL 基于固定源码、受管工具及可解析的分析 profile 创建浮点分析；成功构建、真实 compile database 与共享 BC 均为可选增强。没有数据库时 SHALL 使用内部显式翻译单元计划，标明解析假设和未分析范围。

#### Scenario: 创建完整浮点分析
- **WHEN** 当前源码版本存在成功 x86 构建、compile database、全部计划翻译单元均具有校验成功的 produced BC record 和受支持的源/目标 profile
- **THEN** 后端 SHALL 创建 `FLOATING_POINT_ANALYSIS` 异步任务
- **AND** 运行模式 SHALL 为 `AST_BC_COMPLETE`
- **AND** 运行记录 SHALL 固定源码版本、可空构建/artifact set、实际依赖的 profile/环境配置、覆盖分母和工具协议版本

#### Scenario: 缺少可用 BC 时执行 AST-only
- **WHEN** BC 未生成、状态为 FAILED、UNSUPPORTED 或 NOT_ATTEMPTED、制品缺失、哈希或身份无效，或没有 produced record
- **AND** 当前源码、工具/profile 及真实或内部源码解析计划可用
- **THEN** 后端 SHALL 创建浮点分析任务并省略 `bitcode_artifact_set`
- **AND** 运行模式 SHALL 为 `AST_ONLY`
- **AND** readiness、运行摘要和结果 MUST 返回稳定 BC 降级原因

#### Scenario: 部分 BC 按翻译单元混合运行
- **WHEN** artifact set 为 PARTIAL 且至少包含一个合法 produced record和完整失败分母
- **THEN** 后端 SHALL 对 produced 翻译单元运行 AST+BC
- **AND** SHALL 对没有可用 BC 的合法翻译单元运行 AST-only
- **AND** 运行模式 SHALL 为 `AST_BC_MIXED`
- **AND** readiness 与最终结果 MUST 保留计划、可用、失败和 AST-only 翻译单元计数

#### Scenario: BC 损坏不污染 AST 结果
- **WHEN** manifest、bundle、摘要、源码身份或编译上下文校验失败
- **THEN** 后端 MUST NOT 将该 BC 传给扫描器
- **AND** SHALL 使用可信编译输入或内部源码计划降级为 AST-only
- **AND** MUST NOT 把降级结果标记为完整 AST+BC 分析

#### Scenario: 旧语言标准保留真实编译语义
- **WHEN** compile database 中的 C/C++ 翻译单元使用 C89/C90、C99、C++98/C++03 或常见 GNU 方言
- **THEN** 浮点扫描 SHALL 使用真实 `-std` 参数生成 AST facts
- **AND** MUST NOT 将旧标准静默改写为 C17 或 C++17
- **AND** 单个不支持的编译上下文 SHALL 产生可审计的 unknown 或 unsupported，而不得阻断其他翻译单元

#### Scenario: 只有源码也可分析
- **WHEN** 没有构建、compile database 和 BC
- **THEN** SHALL 以 SOURCE_DISCOVERY 创建并运行显式翻译单元分析，记录默认语言/profile 假设；未知宏、缺头文件及解析失败保留为候选或缺口，不因缺产物整体拒绝。

#### Scenario: 合法部分或无法判定结果
- **WHEN** 工具按协议完整发布 PARTIAL 或 unknown，包含覆盖与诊断但没有 Finding
- **THEN** SHALL 保存可查询的合法产出和原因，供报告纳入，不视为技术失败或无风险；协议损坏仍失败。

#### Scenario: 无构建产物但源码计划完整完成
- **WHEN** 无 compile database 或 bitcode，工具完整执行声明的源码计划并发布 complete/退出码0
- **THEN** SHALL 保存 COMPLETE 和 analysisComplete=true，同时保留缺失构建证据、解析假设及真实覆盖范围
- **AND** MUST NOT 根据 bcStatus=MISSING、AST_ONLY 或未执行 x86 构建将状态改为 PARTIAL。

#### Scenario: 源码计划实际存在未完成范围
- **WHEN** 工具发布合法 partial/退出码4，说明部分计划失败、未知、未完成或输出预算截断
- **THEN** SHALL 保留 PARTIAL、实际计数及原因码；AI 结论不能消除该缺口或将整次分析升级为完整完成。

### Requirement: Worker 必须通过统一 CLI 运行浮点扫描
Worker SHALL 生成符合工具最新请求 schema 的 request，先执行请求校验，再调用 `fp-precision-scanner scan`，且默认启用当前 schema 的全部稳定规则。

#### Scenario: 工具完整成功
- **WHEN** 工具返回退出码 0 且结果 schema v2 校验成功
- **THEN** 任务 SHALL 成功
- **AND** 运行状态 SHALL 为 COMPLETE，无论 Finding 数量是否为零

#### Scenario: 工具返回部分结果
- **WHEN** 工具返回退出码 4 且结果 schema v2 校验成功
- **THEN** 任务 SHALL 成功完成
- **AND** 分析运行状态 SHALL 为 PARTIAL 并保留 diagnostics 与分母

#### Scenario: 工具或协议失败
- **WHEN** 工具返回退出码 2、3 或 5，或结果缺失、损坏、版本不支持
- **THEN** 任务 SHALL 失败
- **AND** 后端 MUST NOT 导入成功态 Finding 或刷新当前迁移输入

### Requirement: 浮点 Finding 必须逐条保存并保持 v2 语义
后端 SHALL 独立保存每条浮点 Finding 的稳定 ID、规则、风险、置信度、状态、双语内容、位置、occurrences、validation plan、证据和 semantic digest。

#### Scenario: 导入带位置的 Finding
- **WHEN** v2 Finding 包含主位置和多个 occurrence
- **THEN** 列表 SHALL 保存主位置供排序过滤
- **AND** 详情 SHALL 返回完整 occurrence、evidenceChain 与 validationPlan

#### Scenario: Finding 缺少可定位主位置
- **WHEN** 合法 v2 Finding 的位置不可用或状态为 unknown
- **THEN** 后端 SHALL 保留该 Finding
- **AND** MUST NOT 伪造文件路径或行号

#### Scenario: 空 Finding 完整结果
- **WHEN** 合法完整结果的 Finding 数量为零
- **THEN** 运行 SHALL 成功并返回零计数
- **AND** 后端 MUST NOT 将其描述为所有浮点行为均安全

### Requirement: 浮点分析必须提供可审计制品
后端 SHALL 保存原始请求、结果 JSON 和后端生成的轻量 HTML，并保留工具版本、profile、build context、层级分母和 diagnostics。

#### Scenario: 下载分析制品
- **WHEN** 有项目权限的用户下载浮点分析 JSON 或 HTML
- **THEN** 后端 SHALL 返回该运行对应的不可变制品
- **AND** 内容 MUST NOT 暴露服务器路径、对象存储 key、凭据或环境变量值

### Requirement: 浮点扫描必须有界处理大型 Clang AST
浮点扫描器 SHALL 在任务级临时文件中承接 Clang AST 输出，并在输出写入期间执行可配置硬预算；系统 MUST NOT 截断 AST 字符串后继续将其作为完整 JSON 解析。

#### Scenario: 大于旧默认限制的合法 AST
- **WHEN** Clang 为翻译单元生成超过 16 MiB但未超过当前硬预算的合法 AST JSON
- **THEN** 扫描器 SHALL 完整解析该 AST 并继续生成 source facts
- **AND** MUST NOT 返回 `frontend_invalid_json`

#### Scenario: AST 超过硬预算
- **WHEN** Clang AST 输出超过 `max_frontend_output_bytes`
- **THEN** 扫描器 SHALL 终止该翻译单元的 frontend 进程并返回 `frontend_output_limit_exceeded`
- **AND** 诊断 SHALL 包含翻译单元身份并标记为不可重试
- **AND** MUST NOT 将输出超限描述为 JSON 损坏

#### Scenario: 完整 AST 确实损坏
- **WHEN** Clang 成功退出、输出未被截断且完整输出无法解析为合法 JSON 对象
- **THEN** 扫描器 SHALL 返回 `frontend_invalid_json`
- **AND** SHALL 保留与输出超限不同的稳定原因码

#### Scenario: AST 临时文件清理
- **WHEN** 翻译单元处理成功、失败、取消或超时
- **THEN** 扫描器 SHALL 清理对应任务级 AST 临时文件
- **AND** MUST NOT 删除源码、compile database、共享 BC 或已发布制品

### Requirement: 浮点源码事实和 provenance 必须收敛到项目语义
浮点扫描器 SHALL 仅将项目根内源码和项目头文件产生的事实作为 source facts，并 SHALL 只把规则结论直接依赖的去重事实写入 Finding provenance；系统头文件和标准库实现细节 MUST NOT 作为独立 Finding 事实批量进入结果。

#### Scenario: 项目源码包含标准库头文件
- **WHEN** 项目翻译单元包含 C++ 标准库或系统头文件并产生大量外部 AST 节点
- **THEN** 扫描器 SHALL 保留项目源码节点及其必要的有界外部符号摘要
- **AND** SHALL 排除 project root 外部节点形成的独立 source facts

#### Scenario: Finding 使用直接证据
- **WHEN** 某条规则基于少量项目 facts 形成 Finding
- **THEN** Finding fact、occurrence 和 evidence chain SHALL 只引用该结论的直接证据
- **AND** 引用 SHALL 去重并按稳定顺序输出
- **AND** MUST NOT 引用整个翻译单元的全部 source facts

#### Scenario: 直接证据超过协议上限
- **WHEN** 合法直接证据数量仍超过结果 Schema 的 provenance 上限
- **THEN** 扫描器 SHALL 使用确定性的聚合或分组表达并记录 provenance budget frontier
- **AND** SHALL 将相关结果标记为 partial 或 unknown
- **AND** MUST NOT 静默截断后声称证据链完整或发布不符合 Schema 的结果

#### Scenario: 结果原子发布前校验
- **WHEN** 扫描器准备发布浮点结果 JSON
- **THEN** 结果 SHALL 通过随工具发布的 v2 Schema 和引用闭包校验
- **AND** 合法业务扫描 MUST NOT 因生成器自身产生超过 `maxItems` 的 provenance 而转为 `internal_error`

### Requirement: 浮点分析失败必须保留可审计根因
后端 SHALL 对扫描器已原子发布、大小合规且可安全脱敏的失败结果 JSON进行不可变制品保存，并 SHALL 根据诊断因果阶段向任务错误和运行摘要公开最接近根因的诊断，而不是固定选择 diagnostics 数组第一项。

#### Scenario: frontend 失败产生派生 evidence 错误
- **WHEN** 同一结果同时包含 `frontend_invalid_json` 或 frontend budget 错误和 `source_fact_bundle_missing`
- **THEN** 后端 SHALL 将 frontend 诊断作为主失败原因
- **AND** 运行摘要 SHALL 返回主原因码和完整诊断数量

#### Scenario: 失败结果可安全保存
- **WHEN** 扫描器退出码表示失败但已发布可解析、大小合规且完成路径脱敏的结果 JSON
- **THEN** 后端 SHALL 保存该原始结果制品并关联失败运行
- **AND** 有权限用户 SHALL 能通过既有制品访问机制审计完整 diagnostics

#### Scenario: 失败结果包含不安全内容
- **WHEN** 失败结果无法通过路径、凭据或内容安全校验
- **THEN** 后端 MUST NOT 保存不安全原文
- **AND** SHALL 保存脱敏的最小诊断摘要并完成失败状态写入

#### Scenario: 不可重试错误不重复投递
- **WHEN** 主诊断属于输入无效、协议不支持、Schema 错误、AST 输出预算或其他 `retryable=false` 分类
- **THEN** worker SHALL 将任务直接标记为失败且不重新投递
- **AND** 数据库运行状态和任务状态 SHALL 保持一致

#### Scenario: 可重试瞬时错误
- **WHEN** 主诊断明确标记 `retryable=true` 且尚未达到既有重试上限
- **THEN** worker MAY 按现有任务重试策略重新投递
- **AND** 每次尝试 SHALL 保留可区分的任务事件和最终原因

### Requirement: 浮点扫描修复必须通过独立单元测试和覆盖率门禁
浮点扫描器与后端运行时修复 SHALL 使用 mock 的进程、对象存储、数据库和任务连接返回值完成独立单元测试，测试 MUST NOT 依赖真实外部服务，且项目整体与受影响模块行覆盖率和可用分支覆盖率 SHALL 均至少为 90%。

#### Scenario: 覆盖正常异常和边界流程
- **WHEN** 执行本变更的单元测试
- **THEN** 测试 SHALL 覆盖小型 AST、大于 16 MiB 的合法 AST、硬预算超限、进程失败、取消、超时、损坏 JSON、系统头文件过滤、provenance 超限、失败制品安全处理、诊断优先级和重试分类
- **AND** 测试 SHALL 对原因码、翻译单元身份、Schema 合法性、清理、制品关联和任务状态进行有效断言

#### Scenario: 覆盖率未达门槛
- **WHEN** 项目整体或受影响模块的行覆盖率低于 90%，或工具支持的分支覆盖率低于 90%
- **THEN** 本 change MUST NOT 标记实现和质量验收完成

### Requirement: 浮点证据步骤必须提供双语标题和说明
新生成的浮点结果 SHALL 在每个 evidenceChain.steps 条目返回非空 title、titleEn、reason、reasonEn，中文为默认展示。标题 SHALL 不超过 512 字符，说明 SHALL 不超过 4096 字符；工具 Schema SHALL 校验这些字段。系统 MUST NOT 仅在 Finding 顶层添加文案来替代步骤说明。

#### Scenario: 多种证据步骤
- **WHEN** 新结果包含源码、IR、绑定、profile、构建上下文、编译选项、评估或结论步骤
- **THEN** 每步 SHALL 包含完整双语标题和说明，严格 Schema 校验通过

#### Scenario: 非法展示字段
- **WHEN** 新生成步骤缺少字段、字段为空或超出长度上限
- **THEN** 校验 SHALL 拒绝将该结果作为合法新协议输出发布

### Requirement: 步骤说明必须基于直接引用的真实证据
生成器 SHALL 根据步骤引用的实际事实和评估生成可读摘要，区分观察、判断与结论；MUST NOT 将所有步骤填为固定类型名或重复最终结论。文本 SHALL 使用项目相对位置，不暴露服务器路径或敏感配置。

#### Scenario: 源码及 IR 对照
- **WHEN** 引用中具有实际运算、位置、LLVM opcode 或浮点属性
- **THEN** 对应说明 SHALL 与这些事实一致，不杜撰缺失的编译选项或确定性失败

#### Scenario: 证据不足
- **WHEN** 有效引用缺少可解释语义或证据为未知、部分、歧义
- **THEN** 步骤 SHALL 提供双语限制说明与稳定原因码，遵守既有状态传播，不将其描述为完整确定证明

#### Scenario: 悬空引用
- **WHEN** 引用无法通过既有闭包校验
- **THEN** 系统 SHALL 保持校验失败语义，不以填充可读文案绕过引用校验

### Requirement: 步骤说明必须贯通现有制品与详情
后端 SHALL 将工具步骤说明完整保存在 Finding detail，并经既有详情 API 和原始 JSON 制品返回；MUST NOT 新增专用说明接口或静默重写历史扫描。系统 SHALL 保留稳定 Finding/step 身份并按既有规则计算新结果摘要。

#### Scenario: 真实新扫描
- **WHEN** worker 完成包含步骤文案的新扫描并导入
- **THEN** 原始结果、落库 detail 及现有详情响应的双语字段 SHALL 一致，继续受项目权限约束

#### Scenario: 历史结果与重复执行
- **WHEN** 读取旧扫描或以同一输入和版本重复生成结果
- **THEN** 历史内容 SHALL 保持原样；新版本重复输出 SHALL 确定，展示字段不得改变稳定身份计算规则

### Requirement: 步骤展示变更必须通过独立测试和覆盖率门禁
工具与受影响后端 SHALL 覆盖正常、异常、边界独立单元测试，mock 外部连接返回值；整体与受影响模块行及可用分支覆盖率 SHALL 至少 90%，统计包含未加载自有源码，并同步工具规范与文档。

#### Scenario: Linux 验收
- **WHEN** 在 Docker 中运行测试与覆盖率
- **THEN** SHALL 验证步骤类型、证据限制、UTF-8、长度、身份稳定性、Schema、预算和后端透传，记录范围、排除项及报告位置

#### Scenario: 门禁失败
- **WHEN** 测试或覆盖率未达要求
- **THEN** 任务 SHALL 保持未完成，不以真实扫描成功替代单元测试验收

### Requirement: Clang 虚拟位置和系统声明不得成为项目 source facts
浮点扫描器 SHALL 根据可验证的物理 spelling/expansion 文件、include provenance 和 main-file 省略规则确定项目归属。`<scratch space>`、`<built-in>`、`<command line>` 及等价 Clang 虚拟来源 MUST NOT 相对于编译目录解析为项目文件；项目根外系统声明 MUST NOT 形成独立 source facts。无法证明物理归属时 SHALL 保守记录 unknown，而不得默认继承为项目成员。

#### Scenario: 系统数学声明来自 scratch space
- **WHEN** 项目测试包含系统数学头且 Clang 将系统函数位置报告为 `<scratch space>`
- **THEN** 这些函数声明 MUST NOT 形成项目 type、call 或 long-double ABI 候选
- **AND** 项目源码中的实际调用节点仍可形成有界的外部声明摘要

#### Scenario: 项目头文件和宏
- **WHEN** 浮点表达式位于项目头文件或项目宏的 spelling/expansion 位置
- **THEN** 扫描器 SHALL 保留真实项目相对路径和宏 provenance
- **AND** 不得因排除虚拟位置而丢弃可证明的项目事实

#### Scenario: 位置归属无法证明
- **WHEN** 节点既没有可验证物理文件，也不能通过受控 main-file 省略规则确定归属
- **THEN** 扫描器 SHALL 记录脱敏的归属 unknown
- **AND** MUST NOT 将节点默认为主源码或 `build/<scratch space>`

### Requirement: Conversion facts 必须只表达 canonical 标量数值转换
扫描器 SHALL 通过统一 canonical type 解析器识别浮点和整数标量，优先使用 `desugaredQualType` 并保存原始 `qualType` provenance。conversion fact SHALL 由数值 cast kind 和规范化端点类型共同决定；指针、函数、成员指针、数组、record/container 或其他复合类型 MUST NOT 仅因类型文本包含 `float`、`double` 或 `_Float` 而进入 mixed-precision 标量候选。

#### Scenario: 隐式 double 到 float
- **WHEN** Clang AST 表示 `double` 值隐式转换并存入 `float`
- **THEN** mapper SHALL 生成包含 canonical source/destination scalar type、cast kind 和存储用途的 conversion fact
- **AND** mixed-precision 规则 SHALL 按实际 precision/range 判断

#### Scenario: typedef 和 auto 推导
- **WHEN** 浮点标量通过 typedef、cv 修饰或 `auto` 推导表达
- **THEN** mapper SHALL 使用可验证的脱糖类型恢复 canonical scalar type
- **AND** SHALL 保留原始类型拼写用于审计而不把 `auto` 当成 unknown

#### Scenario: 函数和浮点指针转换
- **WHEN** AST 包含 FunctionToPointerDecay、数组到指针、空指针到 `float*` 或 `const float*` 的 LValueToRValue
- **THEN** 这些节点 MUST NOT 形成 mixed-precision conversion 候选
- **AND** 不得产生 `conversion_type_unknown`

#### Scenario: 浮点容器类型
- **WHEN** 声明或转换类型为 `std::array<float, N>`、浮点数组或其他包含浮点元素的 record/container
- **THEN** 类型本身 MUST NOT 被当成浮点标量
- **AND** 容器元素发生真实标量转换时仍 SHALL 对该元素生成 conversion fact

#### Scenario: 数值 cast 端点确实缺失
- **WHEN** cast kind 明确表示数值转换但 source 或 destination canonical scalar type 无法可靠恢复
- **THEN** mapper SHALL 生成候选级 unknown 和类型 provenance
- **AND** MUST NOT 按变量名、字面量后缀或类型子字符串猜测

### Requirement: Unknown 和 unsupported 必须按候选与规则依赖传播
扫描器 SHALL 区分已由子事实表达的结构节点、与启用规则无关的非阻断 unsupported 和缺失规则必需语义的 blocking unknown。blocking 记录 MUST 标识受影响候选、scope、fact kind 或 rule dependency；规则 MUST NOT 因无关节点或同 TU 的无关候选而整体降级。

#### Scenario: 数组下标和赋值包装已被子事实表达
- **WHEN** `output[i] = left[i] + right[i]` 的加法和标量操作数已生成完整 arithmetic/conversion facts
- **THEN** 外层 ArraySubscriptExpr 和赋值 BinaryOperator MUST NOT 单独产生 blocking unknown

#### Scenario: 初始化列表包含精确整数常量
- **WHEN** `std::array<float, N>` 的初始化列表包含可精确表示的整数常量
- **THEN** 相关标量转换 SHALL 被正常评估
- **AND** InitListExpr 或 ImplicitValueInitExpr 包装节点不得使整个 TU 为 unknown

#### Scenario: 同一 TU 同时包含确定损失和未知转换
- **WHEN** 一个候选具有可证明的隐式精度损失，另一个真正缺少 canonical endpoint type
- **THEN** 确定候选 SHALL 保留 matched assessment/finding 和直接证据
- **AND** 未知候选 SHALL 独立计入 unknown，不得删除或覆盖已确定结论

#### Scenario: 与规则无关的 unsupported 节点
- **WHEN** 未识别节点不与某条规则声明的 fact/evidence dependency 相交
- **THEN** 该规则 SHALL 继续完成 matched 或 not-matched assessment
- **AND** 可保留非阻断诊断而不降低该规则状态

### Requirement: Long double ABI 必须使用真实边界和有界直接证据
`long-double-abi` SHALL 只消费真实项目物理文件中的 canonical `type` fact，且候选必须属于可证明的公开参数/返回、变参、外部可见布局、持久化或二进制边界。候选 SHALL 按稳定声明身份去重；finding/evidence SHALL 只引用结论直接依赖的边界事实与 source/target profiles，不得引用整个翻译单元的全部浮点或类型事实。

#### Scenario: 系统头包含大量 long double API
- **WHEN** 系统数学库声明数百个 `long double` 函数而项目未声明任何 long-double 边界
- **THEN** 项目 scope 的 assessment SHALL 为 `not_matched/no_abi_boundary`
- **AND** MUST NOT 产生 provenance budget unknown

#### Scenario: 项目公开函数使用 long double
- **WHEN** 项目头声明外部可见的 `long double` 参数或返回值，且 source/target ABI 属性不同
- **THEN** 扫描器 SHALL 发布引用该声明和两个 profile 的 finding
- **AND** 其他无关类型事实不得进入其 evidence

#### Scenario: 多个真实 ABI 边界
- **WHEN** 项目具有多个去重后的 long-double ABI 边界
- **THEN** 扫描器 SHALL 按稳定 declaration/boundary key 评估并使用 occurrence、分组或有界聚合表达
- **AND** 达到既有 provenance 上限时 SHALL 保留稳定 frontier/partial 语义而不得提高上限或静默截断

### Requirement: Facts 和规则语义变化必须版本化并安全失效缓存
canonical type、origin、blocking dependency 或规则候选语义发生变化时，扫描器 SHALL 提升相应 facts producer/schema 与规则实现版本，并使旧内容寻址缓存安全降级为 miss。系统 MUST NOT 就地改写旧缓存、复用旧语义生成新结果或改变历史 Finding。

#### Scenario: 读取旧 facts 缓存
- **WHEN** 缓存条目的 facts schema 或 producer version 早于本 change
- **THEN** 扫描器 SHALL 将其视为 cache miss 并从可信输入重新计算
- **AND** 缓存失效本身不得使扫描状态降为 partial

#### Scenario: 冷热缓存结果等价
- **WHEN** 相同输入在冷缓存和新版本热缓存下重复执行
- **THEN** assessment、finding、diagnostic、稳定 ID、排序和 semantic digest SHALL 一致

#### Scenario: 修正规则候选语义
- **WHEN** long-double 或 mixed-precision 的候选范围、assessment scope 或身份输入发生语义变化
- **THEN** registry SHALL 使用新的实现版本表达该变化
- **AND** 历史结果及其旧 ID SHALL 保持不可变

### Requirement: 证据链修复必须通过独立测试和真实 API 对照
后端与浮点扫描器 SHALL 在 Linux Docker 中分别执行独立单元测试，mock 外部服务，并覆盖正常、边界、异常、unknown、缓存和预算路径。backend 项目以及 scanner 的全部项目自有 Python/C++ 源码均 SHALL 纳入覆盖率统计；各项目整体和受影响模块的行覆盖率、可用分支覆盖率 MUST 分别至少为 90%。修复完成后 SHALL 通过真实 API 重新运行 `demo-test1` 并保留前后审计。

#### Scenario: 固定回归 fixture
- **WHEN** 执行本 change 的 scanner 单元测试和 validation corpus
- **THEN** SHALL 覆盖虚拟位置、系统头、项目宏/头、真实 long-double 边界、typedef/auto、函数指针、浮点指针、容器、确实缺失类型、合法证据超限和 AST-only
- **AND** 对 assessment 状态、直接证据、双语诊断、稳定排序和 v2 引用闭包进行有效断言

#### Scenario: 真实 API 重跑
- **WHEN** 单元测试、覆盖率和协议校验全部通过
- **THEN** 系统 SHALL 使用 project `9915a12c-9338-419e-b589-9a3984d56c62`、source revision `a019391f-89e6-4d8e-8eed-1b7871055541` 和 build run `be53c759-1e2e-459a-9f73-ef8289ecfaf2` 创建新的浮点分析
- **AND** 验证报告 SHALL 与历史 analysis `aee4ef66-6d81-4e88-afd7-606cc7d6cc9a` 对比 BC 状态、各层分母、诊断、finding、证据来源和状态
- **AND** 不得预设新结果必须为 COMPLETE 或删除合法的 reduction heuristic finding

#### Scenario: 覆盖率或真实验证失败
- **WHEN** 任一单元测试失败，项目整体或受影响模块行/分支覆盖率低于 90%，或真实 API 结果不符合证据边界
- **THEN** 本 change 的实现和质量任务 MUST 保持未完成
- **AND** 不得以集成测试、编译成功或隐藏诊断替代门禁
