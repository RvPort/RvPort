# scanner-quality-gates Specification

## Purpose

定义后续实现必须满足的单元测试、固定语料、覆盖率统计范围、排除项和验收报告门禁，使每个实现 change 都能以可重复执行的证据证明正常、边界与异常行为，并防止通过缩小统计范围绕过质量要求。

## Requirements

### Requirement: 项目自有代码必须具备单元测试
后续实现 SHALL 为所有项目自有可执行代码提供单元测试，并覆盖正常行为、边界条件和异常路径。解析适配、规则判断、证据构造、finding 去重、v2 projection、引用完整性校验、协议序列化、退出码映射和失败聚合 MUST 可在不启动完整端到端环境的情况下独立测试。

本次结果协议切换是明确批准的破坏性替换，因此测试 MUST 证明项目内全部生产者、消费者、fixtures、validation/oracle、benchmark 和文档已原子迁移到 v2，并证明不存在旧结果 serializer、版本选择、fallback 或 `schema_version: "1.4"` 结果消费路径；本次变更不要求保留旧结果的向后兼容测试。后续修改 v2 或其他已使用协议时，仍 SHALL 提供适用的兼容性、无效输入、序列化稳定性和双语完整性测试。

#### Scenario: 新增检测规则
- **WHEN** 后续实现新增或修改一条浮点检测规则
- **THEN** 同一变更包含命中、非命中、边界、unknown/unsupported 和异常证据的单元测试

#### Scenario: 本次结果协议直接替换
- **WHEN** `FloatingPointPrecisionScanResult@1.4` 被结果 v2 取代
- **THEN** 正常、partial、invalidRequest、failed 和 internalError 均只输出 v2，全部项目内消费者已切换，且测试确认不存在双写、旧 serializer、fallback 或版本选择参数

#### Scenario: 修改 v2 协议字段
- **WHEN** 本次切换完成后的变更修改结果 v2 或其他已使用输入输出协议
- **THEN** 同一变更包含适用的兼容演进、无效输入、序列化稳定性、引用完整性和双语字段完整性单元测试

### Requirement: 结果 v2 必须通过结构、引用和确定性合同测试
实现 SHALL 提供独立于完整扫描流程的 v2 contract tests，覆盖 JSON Schema、registered extensions、跨字段计数、主 occurrence、typed references、evidence-chain steps/edges/path/breaks、diagnostic finding references、双语字段、路径边界、稳定 ID、规范化排序和 semantic digest。测试 MUST 包含正常、空 finding、facts-only、聚合 finding、heuristic、suppressed、expired suppression、partial、invalid request、预算截断、缓存命中、悬空引用、重复 ID、非法 namespace 和输出大小边界。

#### Scenario: Evidence edge 指向不存在的 step
- **WHEN** fixture 的 evidence-chain edge、primary path 或 impact step 引用不存在的 step ID
- **THEN** contract validator 以稳定诊断拒绝该结果，测试不得只依赖 JSON Schema 的形状校验

#### Scenario: 不同绝对工作区生成结果
- **WHEN** 相同语义输入从不同绝对项目根和 artifact root 执行
- **THEN** v2 finding/occurrence/step/edge IDs、排序和 semantic digest 完全一致，默认结果不泄露任一绝对根

#### Scenario: 仅中英文展示文本变化
- **WHEN** 规则的中英文标题、说明或建议调整但语义证据不变
- **THEN** finding、occurrence、evidence-chain 和 assessment IDs 保持不变，contract tests 仍验证两种语言字段非空且语义对应

### Requirement: 旧结果协议必须从项目内完全移除
交付验收 SHALL 扫描项目自有源码、Schema、tests、fixtures、validation、benchmark 和文档中的旧结果生产/消费路径。除变更说明、历史归档或明确的负向测试数据外，不得保留旧结果 Schema、旧 serializer、`schema_version: "1.4"` 结果断言、旧 snake_case 顶层字段读取或兼容 fallback。负向 fixture MUST 明确标识为 unsupported legacy result，且不能被生产代码接受。

#### Scenario: 项目内仍存在旧结果读取
- **WHEN** 非归档生产代码或测试辅助代码仍读取旧顶层 `request_id`、`assessments`、`suppressions`、`facts`、`ir_facts`、`evidence` 或 `metadata`
- **THEN** 协议切换验收失败，相关任务不得标记完成

#### Scenario: 旧结果作为输入被接受
- **WHEN** validator 收到 `schema_version: "1.4"` 的旧扫描结果
- **THEN** 以稳定 unsupported-result-version 诊断拒绝，不尝试转换或回退解析

### Requirement: 行覆盖率和分支覆盖率均不得低于 90%
覆盖率门禁 SHALL 统计全部项目自有可执行源码，包括测试未主动导入的源码；项目整体及本次受影响模块的行覆盖率和分支覆盖率 MUST 分别不低于 90%。覆盖率工具 MUST 在低于任一门槛时返回非零状态，且不得通过缩小 source 范围、排除业务逻辑、添加无有效断言测试或降低阈值绕过门禁。

#### Scenario: 未加载源码被纳入分母
- **WHEN** 项目自有源码文件未被任何测试导入
- **THEN** 覆盖率报告仍将其可执行行和分支计入分母，而不是从统计中消失

#### Scenario: 分支覆盖率不足
- **WHEN** 行覆盖率为 95% 而分支覆盖率为 89.9%
- **THEN** 质量门禁失败，且 OpenSpec 质量任务不得标记完成

#### Scenario: 受影响模块不足
- **WHEN** 项目整体覆盖率超过 90% 但本次受影响模块低于 90%
- **THEN** 质量门禁失败，并报告整体与受影响模块的独立结果

### Requirement: 排除项必须有限且可审计
覆盖率 MAY 排除第三方依赖、自动生成代码和纯声明文件，但每个排除模式 MUST 在配置和验证报告中列出理由。项目自有规则、协议、错误处理和 CLI 业务逻辑 MUST NOT 因“难以测试”而排除。

#### Scenario: 自动生成 schema 绑定
- **WHEN** 后续实现生成不含手写逻辑的 schema 绑定代码
- **THEN** 可以排除该生成文件，但报告记录生成来源、排除模式和生成物不含项目业务逻辑的依据

### Requirement: 测试语料必须固定且可复现
单元测试 SHALL 使用本地固定的 C/C++ fixtures 和显式 oracle，覆盖 C11、C17、C++17、C++20、非 C/C++ 输入排除、不同浮点编译选项及 x86/RISC-V profiles。测试语料不得把其他源语言当作可分析输入。单元测试 MUST NOT 依赖公网、实时 AI 响应、系统区域设置、非固定时间或未锁定的宿主编译器默认值。

#### Scenario: 相同 fixture 重复执行
- **WHEN** 同一提交在受支持环境重复运行单元测试
- **THEN** finding code、证据、排序、状态和退出码保持一致

### Requirement: 验证报告必须公开统计范围
交付实现前 SHALL 实际运行单元测试与覆盖率门禁，并报告命令、测试数量与结果、行覆盖率、分支覆盖率、统计源码范围、排除项及 HTML/XML 报告位置。集成测试、端到端测试、编译成功和静态检查 MUST NOT 替代单元测试或双覆盖率门禁。

#### Scenario: 环境无法统计分支覆盖率
- **WHEN** 所选覆盖率工具无法提供分支覆盖率
- **THEN** 验收状态保持未完成，并要求更换或补充可统计分支覆盖率的工具，不得将该指标标为自动通过

### Requirement: Reviewed corpus 和检测质量必须成为交付门禁
实现变更交付前 SHALL 运行完整 validation corpus，并按 finding code 报告 TP、FP、FN、strict/supported-only precision/recall、exact match 和 abstention。缺失 oracle 类别、任一规则低于 90% strict precision/recall、整体 exact match 低于 95% 或失败生命周期 case 非 100% exact match 时，质量任务 MUST 保持未完成。

#### Scenario: 只运行单元测试未运行 corpus
- **WHEN** 单元测试和覆盖率通过但 reviewed corpus 未执行
- **THEN** 本 change 的质量验收失败，不能以单元覆盖率替代检测质量

### Requirement: 故障注入必须覆盖全部新增边界
单元测试 SHALL 覆盖共享 deadline、CPU/RSS 超限、Clang/LLVM worker 终止、文件/字节/输出/facts/evidence/rule/diagnostic/suppression 预算、缓存 miss/损坏/冲突/原子写失败、结果 fsync/replace 失败以及 suppression 有效/到期/缺少 policy 日期。每类测试 MUST 断言 status、退出码、分母、frontier、诊断、旧文件保留和路径脱敏。

#### Scenario: 只断言异常被抛出
- **WHEN** 故障测试没有断言公开状态、诊断和既有产物完整性
- **THEN** 该异常路径不视为完成测试覆盖

### Requirement: Python 和 C++ 覆盖率门禁必须继续统计全部项目源码
coverage 配置 SHALL 纳入 `src/floating_point_precision_scanner` 和 `src/cpp` 全部项目自有可执行源码，包括未被测试加载的文件。项目整体及本次受影响模块的行覆盖率、分支覆盖率 MUST 分别不低于 90%；validation/benchmark 集成测试、编译成功或静态检查不得替代单元测试。第三方 LLVM/GTest、生成构建文件和无手写逻辑生成物可以排除，但 MUST 在报告中列出理由。

#### Scenario: 新增缓存模块未被单元测试加载
- **WHEN** 缓存源码存在但测试未导入
- **THEN** 该源码仍进入覆盖率分母并可能导致门禁失败

### Requirement: 性能、确定性和缓存等价必须独立验收
交付前 SHALL 在固定 benchmark 环境执行 S/M/L 三档冷、热缓存测试和不同绝对工作区重复测试，检查绝对资源上限、相对基线回退、热缓存比例以及语义字节等价。无法满足固定环境、任一资源指标缺失或任一档位超限时，性能验收 MUST 保持未完成。

#### Scenario: 热缓存更快但语义不同
- **WHEN** 热缓存达到耗时门禁但任一 assessment、finding、diagnostic 语义、stable ID 或排序不同
- **THEN** 缓存等价门禁失败且不得接受该性能结果

### Requirement: 验证报告必须公开全部命令和产物
最终验证报告 SHALL 列出单元测试、contract、validation corpus、benchmark、Python/C++ coverage、lint、typecheck 和严格 OpenSpec 校验命令，并报告测试数量、失败数、逐规则质量指标、三档性能、整体/受影响模块行分支覆盖率、统计范围、排除项及报告位置。未执行或受环境阻塞的项目 MUST 明确标为未完成。

#### Scenario: 性能环境不可用
- **WHEN** 交付环境无法运行固定 4 vCPU/4 GiB benchmark
- **THEN** 报告记录阻塞原因，任务不标记完成且不声称性能达标

**证据管线专项回归门禁**

### Requirement: 证据修复必须保留固定 before/after 与 Linux Docker 证据
质量门禁 SHALL 覆盖 manifest 2.0/2.1、显式 null、未知版本、摘要篡改、bundle 损坏、虚拟位置、
项目宏/头、canonical scalar type、candidate-local unknown、系统 long-double 洪泛、真实多边界、
缓存失效和 AST-only 降级。backend 全部项目自有 Java 与 scanner 全部项目自有 Python/C++ 的
整体及受影响模块行/分支覆盖率 MUST 各自至少 90%。真实 API 重跑 MUST 新建审计，不得修改
历史 analysis，也不得以隐藏 unknown 或强制 COMPLETE 通过门禁。

#### Scenario: 任一质量证据缺失
- **WHEN** 单测、覆盖率、validation corpus、benchmark 或真实 API 对照未通过
- **THEN** 对应 OpenSpec 任务保持未完成
- **AND** 不得提示具备归档条件

### Requirement: 归约候选隔离必须具有独立回归证据
实现 SHALL 为新增、修改和复用的自有代码维护单元测试，覆盖独立循环、跨函数、同循环独立累加器、嵌套循环、同一行/宏定位、重复事实、多证据单候选、未知位置、unknown/failed 隔离、无关及歧义 IR、补偿、单元素/固定顺序、预算边界、suppression、稳定身份和缓存。测试 SHALL 断言状态、位置、身份、引用归属和置信度，不得仅验证数量或自洽输出。

#### Scenario: 错误共享 IR 的回归
- **WHEN** 只有候选 A 具备有效绑定和完整语义差异证明，候选 B 仅有 AST 归约形态
- **THEN** 单元测试验证 B 不引用 A 的 IR/binding，不被提高置信度且保留人工确认；歧义 binding 和悬空引用有负向断言

#### Scenario: 真实源码两个循环
- **WHEN** Clang 21.1.8 解析同函数连续两段浮点循环并复用累加器
- **THEN** 集成测试独立核对两个归约候选的真实范围和输出；相同规则行为另有不依赖 Clang 的单元测试

#### Scenario: 当前源码与固定语料
- **WHEN** 运行归约修复验证
- **THEN** expected/oracle 按规范独立编写，observed 来自本次源码真实执行，记录输入和工具摘要；固定历史 observed 文件不能替代当前实现验证

#### Scenario: 十规则完整可执行语料
- **WHEN** 执行 `make validation`
- **THEN** 先从固定 C/C++ 源码、编译选项和测试环境契约生成真实扫描 observed，再执行全语料评分；每条规则保留至少六个正例、六个反例、三个 unknown，原有质量门槛不变，源码/输入摘要或 case 集合不一致直接失败

#### Scenario: 多候选与重复结果
- **WHEN** 同一扫描同时存在 matched 与 unknown，或错误地重复发布一个 Finding
- **THEN** 评分比较全部候选状态和结果位置重数，不能只读第一条 assessment，也不能用集合去重隐藏重复结果；抑制和预算生命周期仍须 100% 精确匹配

### Requirement: 归约修复必须通过全范围覆盖率与容器验收
编译、依赖安装、测试、代码生成及覆盖率统计 SHALL 在受资源限制的隔离 Docker 环境中执行。Python 覆盖率 SHALL 纳入整个 `src/floating_point_precision_scanner` 的未加载自有源码；整体及每个受影响模块行和分支覆盖率 MUST 均至少 90%，并通过可重复命令和自动门禁强制执行。C++ SHALL 继续验证全部 `src/cpp` 自有源码覆盖率，修改模块也须独立满足双指标门槛。第三方依赖、生成构建文件和仅用于类型导入的非运行分支可按既有明确规则排除，业务逻辑 MUST NOT 被排除；已有更严格门槛 SHALL 保留。

#### Scenario: 全量和模块门槛
- **WHEN** 实现准备交付
- **THEN** 实际执行单元测试、全量覆盖率与全部受影响模块门禁，记录命令、测试结果、行/分支百分比、范围、排除理由及报告位置；集成/构建成功不能代替单测

#### Scenario: 历史缺口或环境阻塞
- **WHEN** 测试失败、覆盖率不足或验证环境不可用
- **THEN** 保留质量任务未完成并如实报告；需要大规模无关改造时先请求扩大范围，不降低门槛或缩小统计范围

#### Scenario: 仓库级交付验证
- **WHEN** 工具级验证完成并准备交付代码
- **THEN** 按根目录约定完成全部默认服务 Compose 构建和一次 `docker compose up -d --wait`，在独立项目、网络及数据卷中检查健康、日志及两条归约结果的代表性业务流程，不连接已有数据库或真实 SSH，不修改前端源码

### Requirement: 全部十条规则必须通过候选隔离验收矩阵
交付 SHALL 提供覆盖 registry 全部十条规则的机器可检查矩阵，并逐条验证多个独立问题、同问题多事实、混合 matched/notMatched/unknown、局部 failed、无关证据、抑制、预算、稳定身份和冷暖缓存。确实不适用的场景 MUST 给出语义理由及等价验证，不得缺省遗漏；不得只验证截图涉及规则或沿用历史通过状态。任一规则存在同类遗留时本变更 MUST 保持未完成。

#### Scenario: 六规则已修但已有规则未验证
- **WHEN** 六条批量评估规则通过，但 ABI、转换、归约或配置规则缺少候选生命周期回归
- **THEN** 整体质量门禁失败，不能宣称全部修复或建议归档

#### Scenario: 位置数量正确但证据串用
- **WHEN** 输出数量符合预期但候选 B 引用了候选 A 的无关 IR、摘要或 assessment
- **THEN** 引用归属断言失败，不以条目数量正确接受结果

### Requirement: 当前源码语料必须证明独立问题和合法聚合边界
真实语料 SHALL 覆盖 C11/C17/C++17/C++20、同函数独立表达式、跨函数、同一行/宏/模板、多 TU 与不同编译配置，使用实际 Clang/LLVM 和独立 oracle 从本次源码生成 observed。每规则原有正负/unknown 分母及逐规则 strict precision/recall 至少 90%、整体 exact match 至少 95%、生命周期 100% 门槛 SHALL 保留；不得用历史 observed、降低阈值、删除有效负例或全部 unknown 替代修复。

#### Scenario: 实际多函数扫描
- **WHEN** 扫描输入包含多个函数的 API、libm、比较、FMA、求值链或 fenv 状态区间
- **THEN** 实际扫描结果按独立预期逐候选核对，记录源码/输入/oracle 摘要和完整结果；编译失败不能计为粒度验收通过

#### Scenario: 配置例外审计
- **WHEN** 语料包含同语义配置、多目标/不同语义配置和部分成员 suppression
- **THEN** 分别验证合法聚合、必须分离和成员抑制后的正确结果，所有保留多位置的案例具有明确语义依据

### Requirement: 本次全规则修复必须完成全范围双覆盖率和实际安装包验收
所有编译、依赖安装、测试、代码生成及覆盖率统计 SHALL 在隔离且有资源限制的 Docker 中完成。整体及所有受影响 Python/C++ 模块行与分支覆盖率 MUST 各自至少 90%，纳入未加载的自有可执行源码并审计必要排除项。交付 SHALL 重跑 lint、typecheck、单元/契约、集成、当前源码 validation 与既有固定性能门禁，从仓库根完成全部默认服务 Compose 构建并以一次 `up -d --wait` 启动最终验收轮次，核对实际安装包中的十规则粒度矩阵、健康与日志。不得修改前端来掩盖结果粒度缺陷。

#### Scenario: 拟交付快照与报告
- **WHEN** 准备标记实现完成
- **THEN** 记录实际拟交付 Git tree、输入字节、镜像、所有命令、逐规则矩阵、测试结果、整体和逐模块覆盖率、排除项及报告位置，历史镜像和报告不能代替当前验收

#### Scenario: 验证失败或尚有遗漏
- **WHEN** 任一规则矩阵、覆盖率、检测质量、性能或部署验收失败或未执行
- **THEN** 保留对应任务及整体验收未完成，说明真实差距，不将剩余同类修复挪到另一个变更后提前关闭本变更
