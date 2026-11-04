# floating-point-risk-detection Specification

## Purpose

定义仅面向 C/C++ 的 x86→RISC-V 浮点精度风险检测范围、证据要求、风险分级、人工确认与抑制边界。

## Requirements

### Requirement: 以编译上下文驱动 C/C++ 扫描
扫描器 SHALL 仅分析 C 与 C++ 两种源语言，首期标准基线为 C11、C17、C++17 和 C++20。扫描器 SHALL 以每个 C/C++ 翻译单元的实际编译上下文消费源文件、语言标准、目标三元组、宏、包含路径和会改变浮点语义的编译选项。缺少关键上下文时，扫描器 MUST 将受影响结论标记为 `unknown` 或返回部分分析，不得假定默认编译语义并报告确定性安全结论。

#### Scenario: 完整编译数据库
- **WHEN** 请求提供可解析的 `compile_commands.json`，且翻译单元具备完整编译参数
- **THEN** 扫描器按翻译单元解析源码，并在证据中记录生效的语言标准、目标与浮点相关选项

#### Scenario: 缺少关键编译参数
- **WHEN** 翻译单元无法确定目标、宏或浮点选项，且这些信息会改变某条规则的判断
- **THEN** 扫描器将该判断标记为 `unknown`，并输出缺失上下文诊断，而不是生成确定性无风险结论

#### Scenario: 编译数据库包含非 C/C++ 条目
- **WHEN** 编译数据库同时包含 C/C++ 与其他源语言的条目
- **THEN** 扫描器仅将 C/C++ 翻译单元纳入 planned 分母，并为被排除条目输出 `unsupported_language` 信息诊断，不尝试推断其浮点语义

### Requirement: 覆盖迁移相关浮点风险类别
扫描器 SHALL 使用稳定 finding code 和独立 rule version 检测并分类十类 x86→RISC-V 浮点迁移风险：`long-double-abi`、`x87-excess-precision`、`fma-contraction`、`unsafe-fp-optimization`、`floating-environment`、`mixed-precision-conversion`、`fragile-fp-comparison`、`reduction-reassociation`、`math-library-variance` 和 `architecture-specific-fp-api`。registry MUST 明确每条规则的 source/IR fact kinds、profile/build-context 字段、IR capability、assessment scope、evidence mode、默认启用状态、预算键和确定性排序；请求未注册 code 或不受支持 rule version MUST 返回 `rules_not_available`，不得被静默接受或产生占位结论。

#### Scenario: 使用默认完整规则集合
- **WHEN** schema 1.3 请求省略规则选择
- **THEN** 扫描器按 registry 稳定顺序启用十条规则，并在结果 metadata 中记录 code、rule version、dependency 摘要和 registry digest

#### Scenario: 旧版本请求保持既有默认规则
- **WHEN** 受支持的 schema 1.0-1.2 请求省略规则选择
- **THEN** 扫描器按该协议版本锁定的四条核心规则解释请求，不因工具升级静默增加 findings

#### Scenario: 选择规则子集
- **WHEN** 请求只选择 `mixed-precision-conversion` 和 `fragile-fp-comparison`
- **THEN** 扫描器只规划这两条规则的 assessment，其他规则为未请求且不影响整体状态

#### Scenario: 请求未知或不兼容规则
- **WHEN** 请求启用未注册 code 或指定不受支持的 rule major version
- **THEN** 扫描器在事实提取前返回 `invalid_request/rules_not_available`，并列出当前支持的稳定 code/version

### Requirement: long double ABI 风险必须绑定真实边界和 profile 差异
`long-double-abi` 规则 SHALL 比较 source/target profile 中 `long double` 的格式、storage bits、precision、alignment、ABI class、calling convention 和 variadic 语义，并只在 AST facts 证明值穿越公开函数参数/返回、`extern "C"`、可变参数、外部可见记录布局或持久化/二进制边界时发布 finding。纯局部计算、两端相关属性完全一致或边界不含 `long double` MUST NOT 命中；必需 profile 字段或边界分类缺失时 MUST 返回 unknown。

#### Scenario: long double 穿越公开 ABI 且表示不同
- **WHEN** 外部可见 C/C++ 函数参数或返回值包含 `long double`，且 source/target profile 的格式、对齐或 ABI class 存在确定差异
- **THEN** 扫描器发布 `long-double-abi` high finding，要求人工确认，并引用声明事实、边界类型和两个 profile 指纹

#### Scenario: long double 只用于函数内部
- **WHEN** `long double` 只出现在无外部布局影响的局部变量和内部表达式中
- **THEN** 该 scope 的 assessment 为 `not_matched`，不发布 ABI finding

#### Scenario: 目标 profile 缺少 ABI class
- **WHEN** 边界事实存在，但 source 或 target profile 的 `long double` ABI class 为 unknown
- **THEN** assessment 为 `unknown` 并输出缺失字段及 provenance，不假定宿主 ABI

### Requirement: x87 扩展精度风险必须区分中间求值和存储边界
`x87-excess-precision` 规则 SHALL 结合 source/target evaluation method、x87 extended precision、`FLT_EVAL_METHOD`、有效 excess-precision 选项、AST 浮点运算链及赋值、volatile、显式 cast 等存储舍入边界，并在可用时核对 IR 中间类型与 `fpext`/`fptrunc`。只有 source 端可能保留扩展中间精度、target 行为不同且存在可观察运算链时才能命中；明确标准精度求值或每步已强制存储/截断时 MUST NOT 命中。决定性 profile、option 或 IR/evidence 不可用时 MUST 返回 unknown。

#### Scenario: x87 中间精度跨多个运算保留
- **WHEN** source profile/选项允许 x87 扩展中间精度，目标 profile 在声明类型精度求值，且表达式在可观察存储边界前包含连续浮点运算
- **THEN** 扫描器发布 `x87-excess-precision` finding，引用求值策略、运算链、存储边界和可用 IR 事实，并建议边界值差分测试

#### Scenario: 每步结果明确截断到声明类型
- **WHEN** AST 与 IR 证据证明每个相关中间结果在后续运算前已存储或显式截断到相同声明类型
- **THEN** assessment 为 `not_matched`，不因 source profile 支持 x87 就发布 finding

#### Scenario: 缺少决定性 IR 映射
- **WHEN** AST 存在候选运算链，但必需 IR fact 缺失或 AST↔IR binding ambiguous
- **THEN** assessment 为 `unknown`，不得仅按函数名、行号或 x86 profile 推断实际扩展精度

### Requirement: FMA contraction 风险必须比较候选表达式、策略和实际 lowering
`fma-contraction` 规则 SHALL 从 AST facts 识别乘加/乘减候选，并通过 source-preserving/build-faithful IR facts 与 verified evidence 区分独立乘法加法、隐式 contraction、显式 `fma` 调用、优化删除和歧义。只有 source/target contraction policy、有效选项或实际 lowering 表明迁移后可能发生融合或展开差异时才能命中；两端策略与可核验 lowering 一致时 MUST NOT 命中。显式 `fma` MUST NOT 被误报为编译器隐式 contraction。

#### Scenario: build-faithful IR 只在一端形成 FMA
- **WHEN** AST 存在乘加候选，verified evidence 将其绑定到一端的 `llvm.fma`，而另一端策略或事实保持 `fmul` 加 `fadd/fsub`
- **THEN** 扫描器发布 `fma-contraction` finding，并说明一次舍入与两次舍入差异及双平台测试建议

#### Scenario: 显式 fma 调用
- **WHEN** source fact 表明程序显式调用受支持的 `fma`/`std::fma`，且 IR lowering 与显式语义一致
- **THEN** 该候选不因存在 FMA 指令而命中隐式 contraction finding

#### Scenario: build-faithful profile 未提供
- **WHEN** 规则判断依赖实际优化 lowering，但请求没有可用 build-faithful IR 或可靠 evidence
- **THEN** assessment 为 `unknown`，整体状态按启用规则的必需证据保守传播

### Requirement: fast-math 风险必须基于有效语义而非原始文本
`unsafe-fp-optimization` 规则 SHALL 消费按编译器覆盖顺序归一化后的有效浮点选项，并在可用时核对 IR fast-math flags。规则至少 SHALL 表达 reassociation、no-NaNs、no-Infs、no-signed-zeros、reciprocal/approximate functions、finite-math-only、FP contraction、errno 和异常语义；原始命令行包含 `-ffast-math` 或 `-Ofast` 但后续否定选项关闭相应语义时 MUST 以最终有效状态为准。

#### Scenario: 多个危险语义实际生效
- **WHEN** 归一化编译上下文表明 reassociation、no-NaNs 和 reciprocal transformations 生效，且 IR facts 与适用范围一致
- **THEN** 扫描器发布按 TU 或共享配置聚合的 `unsafe-fp-optimization` finding，列出每个有效语义及受影响事实

#### Scenario: 后续选项关闭 fast-math 子语义
- **WHEN** 原始命令包含 `-ffast-math`，但后续选项明确恢复 signed-zero 或 NaN/Inf 语义
- **THEN** finding 只包含最终仍生效的危险语义；全部相关语义均关闭时 assessment 为 `not_matched`

#### Scenario: AST options 与 IR flags 冲突
- **WHEN** 规范化有效选项和 build-faithful IR flags 对同一语义给出不可解释的冲突结果
- **THEN** assessment 为 `unknown` 并输出证据冲突诊断，不任选一侧发布确定性结论

### Requirement: 结论必须绑定可核验的证据
每条 finding SHALL 至少包含规范化文件路径、源码范围、相关表达式或配置项、规则命中原因、来源 profile、目标 profile、严重性、置信度、证据来源和中英文建议。没有足够证据区分风险与非风险时 MUST 保留 `unknown`，不得伪造源码位置、类型语义或架构差异。

#### Scenario: 确定性 AST 证据
- **WHEN** 规则由已解析的类型、表达式和编译参数确定性命中
- **THEN** finding 的 `evidence_origin` 为 `deterministic`，并包含可回溯的 AST/编译参数证据

#### Scenario: 启发式数值敏感区域
- **WHEN** 扫描器仅依据归约循环、差值抵消或领域线索推断数值敏感性
- **THEN** finding 的 `evidence_origin` 为 `heuristic`，置信度不得标为确定，并明确需要人工确认的判断依据

#### Scenario: AI 扩展证据
- **WHEN** 可选 AI 扩展参与判断
- **THEN** finding 的 `evidence_origin` 为 `ai`，并提供置信度、可核验证据与失败回退语义；AI 结论不得覆盖确定性事实

### Requirement: 风险分级与人工确认
扫描器 SHALL 将严重性限制为 `critical`、`high`、`medium`、`low` 和 `info`，并依据 ABI 暴露范围、结果敏感度、证据强度和影响范围计算。所有 `critical`、`high` 以及来源为 `heuristic` 或 `ai` 的结论 MUST 标记 `requires_human_confirmation=true`。

#### Scenario: 高风险结论
- **WHEN** 检测到可能改变公开 ABI、持久化数据或关键业务数值结果的差异
- **THEN** finding 至少为 `high`，要求人工确认，并给出最小化的 x86/RISC-V 对比测试建议

### Requirement: 抑制不得隐藏分析边界
扫描器 SHALL 支持按 finding code、路径和带理由的源码抑制；每次抑制 MUST 记录规则、理由和位置。无理由的通配抑制、解析失败或不支持语义 MUST NOT 被统计为“无风险”。

#### Scenario: 有理由的局部抑制
- **WHEN** 用户对特定源码范围配置有效的 finding code 与理由
- **THEN** 扫描器不发布该 finding，但在摘要中计入 suppressed 数量并保留可审计抑制记录

### Requirement: floating environment 风险必须绑定状态操作、作用域和有效语义
`floating-environment` SHALL 结合解析后的 fenv API、`#pragma STDC FENV_ACCESS` 作用域、有效编译选项、constrained FP/IR behavior、source/target environment profiles 和有界函数摘要，判断 rounding mode、异常标志或环境保存/恢复依赖是否在迁移后可能改变。仅包含头文件、未调用 API 或完整保存并恢复且两端语义一致 MUST NOT 命中；间接调用、外部调用、线程边界、pragma 作用域或 profile 能力无法确定时 MUST 返回 unknown。

#### Scenario: 动态舍入与优化假设冲突
- **WHEN** 源码在有效 fenv-access 范围修改 rounding mode，而 build-faithful IR 或有效选项假定默认 rounding，且证据绑定唯一
- **THEN** 扫描器发布 deterministic `floating-environment` finding，引用状态操作、作用域、选项/IR 和 source/target profiles

#### Scenario: 环境完整保存和恢复
- **WHEN** 函数摘要证明环境在所有已解析正常出口恢复，且调用范围内没有冲突优化或未解析调用
- **THEN** 对该 scope 的 assessment 为 `not_matched`

#### Scenario: 函数指针穿过环境边界
- **WHEN** 状态影响可能通过无法解析的间接调用或线程入口传播
- **THEN** assessment 为 `unknown`，诊断公开稳定 frontier，不假定调用无副作用

### Requirement: mixed precision conversion 必须按类型和可表示性判断
`mixed-precision-conversion` SHALL 分析隐式浮点窄化、整数转浮点、常量类型、usual arithmetic conversions、混合精度表达式和最终存储/use 边界。规则 MUST 报告源/目标 canonical type、precision/range 关系以及可确定时的常量精确可表示性；仅因出现 `auto`、显式 cast 或不同类型而不得命中。显式 cast 默认视为意图证据，除非其外层仍存在独立隐式损失转换。

#### Scenario: 非常量 double 隐式存入 float
- **WHEN** `double` 值在没有显式 cast 的情况下转换并存入 `float`，且目标 precision/range 小于源类型
- **THEN** 扫描器发布 deterministic finding，区分 precision loss、range loss 或值域未知，并引用 conversion/storage facts

#### Scenario: 精确可表示整数常量转 float
- **WHEN** 整数常量的规范值可由目标浮点类型精确表示
- **THEN** assessment 为 `not_matched`，不因类型类别不同发布 finding

#### Scenario: auto 推导显式转换结果
- **WHEN** `auto result = static_cast<float>(double_value)` 的 AST 明确包含显式 cast 且没有后续隐式窄化
- **THEN** 该候选为 `not_matched` 或意图信息，不把 `auto` 误判为未知或隐式转换

#### Scenario: 模板实例类型恢复失败
- **WHEN** 转换位于宏/模板实例中且 canonical source 或 destination type 无法可靠恢复
- **THEN** assessment 为 `unknown`，不得按源码 token 推断类型

### Requirement: fragile floating-point comparison 必须分离模式事实与业务容差
`fragile-fp-comparison` SHALL 识别计算所得浮点值的精确 `==`/`!=`、近零绝对/相对 epsilon 组合、NaN/Inf 语义错误及比较的控制流用途。可由语言语义证明的 NaN 错误模式 MAY 产生 deterministic finding；仅能推断比较对舍入敏感时 MUST 产生 heuristic finding，使用固定 rationale、`confidence=medium|low` 和 `requires_human_confirmation=true`。扫描器 MUST NOT 发明、替换或宣称唯一业务 epsilon。

#### Scenario: 计算结果精确相等
- **WHEN** 两个非同源计算浮点表达式使用精确相等控制关键分支，且没有领域容差证据
- **THEN** 扫描器发布 heuristic finding，说明模式与证据，并建议由领域测试确定绝对、相对或 ULP 策略

#### Scenario: 通过等号测试 NaN
- **WHEN** 代码使用 `x == NAN`、`x != NAN` 或等价可证明永真/永假的 NaN 比较
- **THEN** 扫描器发布 deterministic finding，并建议使用标准分类 API

#### Scenario: 明确的领域比较 helper
- **WHEN** 比较调用解析到项目配置的领域 tolerance helper，且语义/参数完整
- **THEN** 规则不把该调用当作裸精确比较；若 tolerance 合理性无法证明，不生成确定安全结论

#### Scenario: 比较用途不可恢复
- **WHEN** 比较存在但控制流用途、operand provenance 或 NaN guard 因 unsupported AST/evidence 不完整
- **THEN** assessment 为 `unknown`，不得按同一行文本拼接推断

### Requirement: reduction reassociation 必须结合递推形态和实际 lowering
`reduction-reassociation` SHALL 从 AST facts 识别浮点 sum、product、dot-product、并行/向量归约与受支持补偿算法，并在可用时通过 source-preserving/build-faithful IR 验证 phi recurrence、vector reduction、reassociation flags 和实际顺序。仅有归约形态但缺少敏感度或 lowering 差异时最多产生 heuristic finding；IR/profile/option 证明一端重结合或向量化且另一端语义不同 MAY 提升证据强度。单元素、固定无重排小表达式或已验证补偿算法 MUST NOT 被当作普通未补偿归约命中。

规则 SHALL 按独立循环中的独立归约候选分别评估，使用当前候选直接事实及有可靠关联的 IR、binding、profile/build context。不同循环、不同函数或同一循环的独立累加器 MUST NOT 因属于同一 TU、使用相同变量名称或命中相同规则而合并。每个候选的 unknown、failed、补偿判断和置信度 MUST 与其他候选隔离。归约规则版本 SHALL 提升至 1.1，并保留稳定 rule code。

#### Scenario: build-faithful IR 发生向量归约
- **WHEN** AST 证明浮点累加，verified evidence 将当前候选绑定到带重结合语义的向量归约，且 source/target policy 不同
- **THEN** 扫描器发布仅引用该候选 AST recurrence、关联 IR group、flags 和必要 profiles 的 finding，并建议病态输入和跨平台顺序测试

#### Scenario: 只有 AST 求和循环
- **WHEN** 可识别长浮点求和但没有可靠 build-faithful IR 或输入敏感度证明
- **THEN** 规则仅发布需要人工确认的 heuristic finding，或按配置保留候选 assessment，不宣称已发生重排

#### Scenario: Kahan 补偿求和
- **WHEN** AST/IR facts 完整证明受支持的 Kahan 更新关系且没有证据显示优化破坏补偿步骤
- **THEN** 普通归约风险为 `not_matched`；若 fast-math 可能破坏关系，由相应优化证据单独评估
- **AND** 其他候选的 IR 或 flags 不得被用于认定该补偿失效

#### Scenario: 循环递推歧义
- **WHEN** 多个 phi/update 候选无法由 evidence 唯一绑定到 accumulator
- **THEN** 受影响候选的 assessment 为 `unknown`，不任选递推链发布 finding，其他可确定候选继续独立评估

#### Scenario: 同一函数的相邻循环复用累加器
- **WHEN** 向量通道累加循环与尾部元素累加循环分别存在归约风险，且更新同一个 `result`
- **THEN** 扫描器发布两个不同 scope、assessment 和 Finding，各自位置与证据只对应所属候选

#### Scenario: 跨函数与同循环多累加器
- **WHEN** 同一 TU 不同函数存在归约，或同一循环存在两个独立累加器
- **THEN** 每个独立候选分别判断并生成适用结果，不按 TU、函数或循环整体强制合并

#### Scenario: 无关 IR 不提高置信度
- **WHEN** 一个候选具有经验证的重结合 IR 和必要语义差异，另一个候选只有 AST 顺序敏感形态
- **THEN** 前者独立评估其证据强度，后者保持 heuristic 和人工确认，不引用前者的 IR 或 binding

### Requirement: math library variance 必须绑定调用、输入域和 build context
`math-library-variance` SHALL 识别解析后的标量/向量数学调用，并比较 source/target `FloatingPointBuildContext` 中的 libm identity、version、errno、exception、special-value 和已验证能力。只有调用语义与 profile/context 差异共同支持风险时才能发布 finding；仅看到 `sin`、`exp` 等函数名不得命中。输入域未知时 MAY 给出适用的特殊值/极值测试建议，但 MUST NOT 断言业务路径一定覆盖该域。

#### Scenario: 两端 libm 特殊值语义不同
- **WHEN** 解析后的数学调用可达，参数域可能包含相关特殊值，且经过验证的 source/target build contexts 对 errno/exception/special-value 行为不同
- **THEN** 扫描器发布 deterministic context-difference finding，引用调用、域线索和两个 context fingerprints

#### Scenario: 精确函数且语义一致
- **WHEN** 调用解析到标准要求精确的受支持函数，且两端 context 对依赖语义一致
- **THEN** assessment 为 `not_matched`

#### Scenario: libm 身份未知
- **WHEN** 调用事实存在，但任一端 libm implementation/version 或依赖能力为 unknown
- **THEN** assessment 为 `unknown`，不得使用扫描器宿主 libm 代替

### Requirement: architecture-specific API 必须绑定声明身份和目标保护
`architecture-specific-fp-api` SHALL 根据解析后的 declaration/builtin/intrinsic identity、头文件、required target features、模板/宏 provenance 和有效条件编译分支识别 x86 SIMD intrinsic、专用向量数学入口及其他架构限定浮点 API。字符串名称相似不得单独命中；已验证只在 x86 分支编译且目标有独立实现的可移植 wrapper MUST NOT 作为无保护调用报告。finding SHALL 给出 RISC-V 替代调查和语义对比清单，但不得自动声明某替代完全等价。

#### Scenario: 无保护使用 x86 intrinsic
- **WHEN** C/C++ 表达式解析到 x86-only floating-point intrinsic，当前编译路径可达且 target profile 不支持所需 feature
- **THEN** 扫描器发布 deterministic finding，引用 declaration、header、feature、guard 和 source/target profiles

#### Scenario: 可移植 wrapper 具有目标分支
- **WHEN** wrapper 的 x86 intrinsic 位于验证过的 x86 分支，RISC-V 分支解析到独立受支持实现，且对外调用不泄露架构类型
- **THEN** x86 分支自身不产生无保护 API finding，相关语义等价性仍可作为人工验证建议

#### Scenario: 宏分支 provenance 不完整
- **WHEN** intrinsic 位于宏/条件编译中但 active guard 或 expansion provenance 无法确定
- **THEN** assessment 为 `unknown`，不得仅按头文件或函数名称报告确定风险

**证据可信度加固（规则 1.1）**

### Requirement: mixed-precision 和 long-double 必须按直接候选证据评估
`mixed-precision-conversion@1.1` SHALL 分别保留确定损失、精确或显式转换及真正缺失端点的
matched、notMatched 与 unknown assessment；一个候选 unknown 不得覆盖同 TU 的确定命中。
`long-double-abi@1.1` SHALL 只消费项目物理文件中已证明的 ABI/持久化边界，并按声明身份去重。
每个 finding MUST 只引用直接声明/转换、必要 profiles 和明确 build context，不得引用整 TU 的
无关事实。

#### Scenario: 同 TU 命中与未知并存
- **WHEN** 一个 `double→float` 隐式转换可证明损失，另一个数值 cast 确实缺少源类型
- **THEN** 前者产生 matched/finding，后者独立产生 unknown
- **AND** 两个 assessment 使用独立稳定候选 scope

#### Scenario: 系统 long-double 声明洪泛
- **WHEN** 系统数学头通过虚拟位置导入数百个 `long double` 声明而项目没有该 ABI 边界
- **THEN** 规则返回 `notMatched/no_abi_boundary`
- **AND** 不产生 provenance budget unknown

### Requirement: 归约候选必须具有可追溯的独立身份
扫描器 SHALL 从规范化 TU/编译上下文、函数身份、最近所属循环、更新表达式及可证明的递推关系建立稳定候选身份。MUST NOT 以相同行号、变量名或循环种类推断候选等价。完全相同的事实 SHALL 去重；同一递推的多份证据 SHALL 归于同一候选，不能作为多个独立风险重复发布。身份归属无法安全证明时 SHALL 保留局部 unknown，不伪造循环、范围或变量关系。

#### Scenario: 嵌套循环和同一行更新
- **WHEN** 嵌套循环中存在独立归约，或不同更新位于同一行但不同列/宏展开范围
- **THEN** 候选使用直接所属循环及表达式/声明身份区分，不能合并到外层循环或同一行

#### Scenario: 同一候选多证据
- **WHEN** 一个已证明的归约候选有重复 AST 事实、多个关联 IR 事实或来源位置
- **THEN** 重复事实去重，仅生成一个候选 assessment 和适用 Finding，同时保留有界且可追溯的必要证据

#### Scenario: 定位未知但语义明确
- **WHEN** 候选递推身份和风险可证明，但部分展示位置不可恢复
- **THEN** 保留该候选独立判断并沿用未知位置协议，不填造坐标，不因该位置未知影响其他候选

#### Scenario: 身份归属不可证明
- **WHEN** 当前事实无法安全确定候选所属递推或循环
- **THEN** 使用稳定直接事实身份记录该范围的 unknown 及双语原因，不合并其他候选以消除歧义

### Requirement: 十条规则必须以独立语义问题为评估边界
扫描器 SHALL 为全部十条规则构造独立且稳定的语义候选。FMA 以完整乘加/乘减候选为单位，x87 以有证据连接的求值链至存储/舍入边界为单位，浮点环境以函数内可证明的状态影响区间为单位，比较和精度转换以表达式为单位，数学库与架构 API 以实际调用点为单位，ABI 以独立声明边界为单位，归约以独立循环与递推累加器为单位，危险优化以有效配置风险成员为单位。不同调用、表达式、循环、状态区间或函数 MUST NOT 因同规则、同名、同行、同文件或同 TU 合并；同一语义候选的多个直接事实 SHALL 去重并保全。

#### Scenario: 跨函数独立 API 和数学调用
- **WHEN** 两个函数分别包含可确定命中的架构 API 或 libm 调用
- **THEN** 对应规则分别产生两个 assessment 和两个 Finding，各自仅归属自己的调用及必要共享环境信息

#### Scenario: 同函数的独立表达式和归约
- **WHEN** 同函数包含两个独立 FMA 候选、求值链、比较表达式、精度转换或归约递推
- **THEN** 按规则语义分别评估并输出，不能将全部位置包装为同一风险

#### Scenario: 浮点环境状态影响区间
- **WHEN** 不同函数或同一函数内存在可证明独立的环境状态影响区间
- **THEN** 各区间分别判断，另一区间的恢复操作或未知调用不能覆盖当前区间结论

#### Scenario: 同一乘加的多份事实
- **WHEN** 同一 FMA 表达式具有 multiply、add、cast 和关联 lowering 等事实
- **THEN** 这些事实仅形成同一候选的证据，不按节点数重复发布 Finding

### Requirement: 候选状态和置信度必须相互隔离
每条规则 SHALL 独立产生候选 matched、notMatched、unknown 或 failed，不因其他候选的保护、精确性、补偿、恢复、歧义或可分类错误覆盖其结论。置信度提升或否定风险 MUST 依赖当前候选可追溯证据及实际必要的全局语义；共享输入缺失只能影响确实依赖该输入的候选。

#### Scenario: 命中与未知并存
- **WHEN** 当前规则中候选 A 确定命中，候选 B 缺少直接语义或 binding
- **THEN** A 保留自己的 Finding 和证据强度，B 独立 unknown，整体完整性按现有协议保守计算

#### Scenario: NaN 与普通比较并存
- **WHEN** 同一 TU 包含 NaN 比较和另一个独立的顺序或精度敏感比较
- **THEN** 两者按各自语义评估，NaN 分支不能提前结束其他候选的判断

#### Scenario: 共享信息缺失
- **WHEN** 两个 libm 候选都依赖同一缺失的运行库能力，另一个规则不依赖该能力
- **THEN** 两个依赖候选各自记录 unknown，其他规则正常评估，不借用其他候选结果充当证明

### Requirement: 配置风险聚合必须证明语义等价
只有 `unsafe-fp-optimization` 的配置风险成员 SHALL 在完整语义身份等价时进行跨成员聚合。身份 MUST 包含规则版本、完整有效危险语义及有关编译器、目标、profile、build context 和证据模式；MUST NOT 仅以危险 flag 名称集合或相同展示标题聚合。无法证明等价的成员 SHALL 独立保留。普通源码候选不适用该例外。

#### Scenario: 同 flag 不同配置
- **WHEN** 两个 TU 启用同名危险 flag，但相关目标、有效选项或 profile/context 语义不同
- **THEN** 保留独立配置风险，不形成共享 Finding

#### Scenario: 同一配置影响多个 TU
- **WHEN** 完整语义身份证明多个成员属于同一配置风险
- **THEN** 允许形成一条结果，但必须保存所有成员身份、assessment、必要证据、影响位置和实际成员数量
