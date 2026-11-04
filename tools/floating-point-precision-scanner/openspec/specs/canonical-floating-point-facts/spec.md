# canonical-floating-point-facts Specification

## Purpose

定义锁定 Clang 21.1.8 到版本化 canonical floating-point facts 的映射、位置/provenance、unknown/unsupported、确定性身份和失败隔离。

## Requirements

### Requirement: 浮点比较采用标量操作数类型
映射器 SHALL 使用隐式转换后的直接操作数类型识别浮点 comparison，优先展开 desugaredQualType，并完整匹配浮点标量类型。MUST NOT 将浮点指针的空值或地址比较视作浮点数值比较。

#### Scenario: 指针空值检查
- **WHEN** float/double 指针与 nullptr、空指针常量或另一指针比较
- **THEN** SHALL 不产生浮点 comparison 事实

#### Scenario: 指针指向的值参与比较
- **WHEN** 解引用、数组元素、引用值或 typedef 标量参与浮点比较
- **THEN** SHALL 保留 comparison 事实，不因值源自指针而丢失检测

### Requirement: 前端版本固定且不得静默回退
前端适配器 SHALL 只调用请求或锁定环境中明确解析到的 Clang 21.1.8，并 SHALL 校验实际版本。缺少编译器、版本不匹配或版本输出不可解析时 MUST 产生结构化失败，且不得搜索 PATH 中其他版本继续分析。

#### Scenario: 锁定 Clang 可用
- **WHEN** 明确路径的 Clang 报告版本 21.1.8
- **THEN** 前端记录可复现的 producer 信息并开始处理翻译单元

#### Scenario: 宿主只有其他 Clang
- **WHEN** 锁定路径不存在但 PATH 中存在其他 Clang
- **THEN** 前端返回 `frontend_unavailable`，不调用 PATH 中的替代版本

### Requirement: Canonical fact bundle 与 Clang 原始节点解耦
每个成功翻译单元 SHALL 生成符合版本化 schema 的 `FloatingPointFactBundle`，包含 producer/profile 版本、稳定翻译单元 ID、规范化编译上下文、浮点类型、字面量、算术与比较表达式、显式/隐式转换、函数与内建调用、源码/宏位置、浮点相关选项以及 unknown/unsupported 记录。为支持核心迁移规则，bundle 还 SHALL 规范化表达 `long double` 的精确 canonical 类型属性、声明 linkage/visibility/语言链接、参数与返回边界、可变参数、记录字段与布局/持久化边界标记、乘加/乘减候选、运算链、赋值/volatile/显式 cast 等存储舍入边界，以及按命令行覆盖顺序计算的有效浮点语义选项。规则层 MUST NOT 接收 Clang 原始 JSON 节点、进程对象或未归一化命令行文本。

#### Scenario: 最小 C 翻译单元
- **WHEN** C11/C17 fixture 包含基本浮点声明、算术和隐式转换
- **THEN** bundle 以 canonical 类型和 fact kinds 表达相应事实，并通过 schema 校验

#### Scenario: 最小 C++ 翻译单元
- **WHEN** C++17/C++20 fixture 包含重载调用、显式转换和浮点表达式
- **THEN** bundle 保留解析后的被调用声明、转换种类和标准版本，不暴露 Clang 节点 ID

#### Scenario: long double 出现在公开边界
- **WHEN** C 或 C++ 声明通过外部 linkage、`extern "C"`、可变参数或外部可见记录字段暴露 `long double`
- **THEN** bundle 记录 canonical 类型、边界种类、linkage/visibility、语言链接、声明位置和完整 provenance

#### Scenario: 识别乘加和存储舍入边界
- **WHEN** 表达式包含浮点乘加链，并通过赋值、volatile 对象或显式 cast 形成可观察存储/截断边界
- **THEN** bundle 分别记录运算候选和边界事实及其稳定引用，不在 facts 层判断是否构成风险

#### Scenario: 浮点选项具有覆盖顺序
- **WHEN** 编译命令先启用 `-ffast-math`，后用更具体的否定选项恢复部分 IEEE 语义
- **THEN** bundle 保存规范化的最终有效语义及 option provenance，而不是仅保存原始 token 是否出现

### Requirement: 源位置同时表达拼写与宏展开
Fact 位置 SHALL 使用仓库相对 POSIX 路径、1-based 行列和可选字节范围，并在宏参与时分别保留 spelling location、expansion location 与宏名称。缺失位置不得伪造为行列 0。

位置恢复 SHALL 使用字节偏移，兼容 UTF-8、CRLF 和制表符；`#line` 的 presumed 坐标不得替代文件物理位置。空 loc SHALL 回退到 range.begin，跨文件范围 MUST NOT 拼成连续区间。文件读取及索引 SHALL 使用翻译单元内预算并拒绝符号链接越界；无法恢复时 SHALL 保留空值并产生双语 `source_location_unresolved` 诊断。位置解析版本 SHALL 参与 source-facts 缓存身份，防止复用旧空位置事实。

#### Scenario: Clang 省略行和文件
- **WHEN** 项目表达式只有 offset、col，所属文件可证明
- **THEN** mapper 恢复实际文件、行列及可用源码范围，不使用函数声明位置替代表达式位置

#### Scenario: 同行不同浮点操作关联 IR
- **WHEN** 同一行同时存在乘法和加法，源码事实明确给出操作符
- **THEN** AST/IR 匹配 SHALL 要求对应 LLVM 操作符兼容，不能仅因同一行就生成错误歧义；不能确定的关联仍保持未知

#### Scenario: 宏内浮点表达式
- **WHEN** 浮点运算来自项目内宏展开
- **THEN** fact 同时包含宏定义的 spelling location 与调用点 expansion location

#### Scenario: 无可用源码位置
- **WHEN** Clang 节点没有可验证的文件位置
- **THEN** mapper 产生显式 unknown location 或诊断，不构造虚假项目路径

### Requirement: Unknown 与 unsupported 必须保守且可审计
mapper 不认识节点形状、类型语义、编译选项或目标属性时 SHALL 生成带 stage、reason、provenance 和影响范围的 unknown/unsupported 记录。未知事实 MUST NOT 被默认值补齐，也 MUST NOT 被解释为“未发现风险”。

#### Scenario: 新增的 Clang 节点形状
- **WHEN** AST fixture 包含 mapper 未注册但与浮点相关的节点形状
- **THEN** bundle 记录 unsupported fact 和受影响范围，扫描状态按阻断性保守传播

#### Scenario: 无关的未知节点
- **WHEN** 未识别节点与任何浮点类型、表达式或位置无关
- **THEN** mapper 可记录非阻断诊断而不降低状态，但不得把节点误分类为浮点事实

### Requirement: Facts 身份、摘要和排序具有确定性
每个 fact SHALL 具有由 facts schema 版本、翻译单元 ID、fact kind、规范化位置和语义载荷计算的稳定标识。bundle SHALL 按明确键排序 facts、diagnostics 和 locations，并 SHALL 计算不包含绝对路径、时间戳、进程 ID 或并发顺序的语义摘要。

#### Scenario: 重复映射同一 AST
- **WHEN** 同一 AST、编译上下文和 profiles 在不同临时目录重复映射
- **THEN** fact IDs、bundle 摘要和序列化字节完全一致

#### Scenario: 编译语义发生变化
- **WHEN** 生效语言标准、宏、target 或浮点选项改变
- **THEN** 翻译单元 ID 和 bundle 语义摘要发生变化

### Requirement: 前端失败按翻译单元隔离并脱敏
Clang 超时、非零退出、无效 JSON、资源超限或诊断不可解析 SHALL 转换为结构化双语诊断并关联稳定翻译单元 ID。默认结果 MUST 限制 stderr 大小并移除凭据、环境变量值和项目根外绝对路径；单个失败不得删除其他成功 bundle。

#### Scenario: 一个翻译单元产生无效 AST JSON
- **WHEN** 多个计划翻译单元中一个 Clang 输出被截断
- **THEN** 该单元计入 failed，其他单元继续，最终状态为 `partial` 且没有伪造 bundle

#### Scenario: 所有前端调用失败
- **WHEN** 所有计划翻译单元均未产生可用 bundle
- **THEN** 最终状态为 `failed`、退出码为 3，并明确未分析范围

### Requirement: Canonical facts 必须表达表达式风险的语义上下文
`FloatingPointFactBundle` SHALL 以版本化、与 Clang 原始节点解耦的 facts 表达六条表达式风险规则需要的语义上下文。转换 fact MUST 包含源/目标 canonical type、隐式或显式、cast kind、常量规范值与可表示性（可确定时）、整数位宽/符号和存储/use 边界；比较 fact MUST 包含 operand 来源、常量/零/epsilon 形态、NaN/Inf guard 与控制流用途；归约 fact MUST 包含 accumulator、递推操作、循环/算法范围、并行/向量提示与已识别补偿形态。缺失的语义 MUST 显式 unknown，不得由源码文本推断。

#### Scenario: 隐式 double 到 float 窄化
- **WHEN** C/C++ AST 将 `double` 表达式隐式转换并存储到 `float`
- **THEN** bundle 记录源/目标精度与范围、implicit cast kind、表达式及存储位置，并提供稳定 fact refs

#### Scenario: auto 接收显式 float cast
- **WHEN** C++ 表达式为 `auto result = static_cast<float>(double_value)` 或等价 C 风格显式转换
- **THEN** bundle 记录显式 cast 和 Clang 推导后的 `result` canonical type，不把 `auto` 或源码拼写当作未知类型

#### Scenario: 比较结果进入控制流
- **WHEN** 浮点比较用于 `if`、循环条件或断言，且周围存在 NaN/epsilon guard
- **THEN** bundle 保存比较、用途和 guard 之间的稳定关系，不在 facts 层判断容差是否合理

#### Scenario: 识别补偿求和
- **WHEN** 支持的循环形态包含 Kahan 或 Neumaier 补偿变量与更新关系
- **THEN** bundle 记录 accumulator、compensation、递推边和循环范围，使规则能区别普通归约与补偿算法

### Requirement: Canonical facts 必须表达浮点环境和解析后 API 身份
bundle SHALL 记录 `#pragma STDC FENV_ACCESS` 的有效作用域、解析后的 fenv 调用及读取/修改/清除/保存/恢复动作，并为每个函数生成有界浮点环境摘要。调用 fact SHALL 记录 canonical declaration identity、namespace、builtin/intrinsic family、定义头文件、required target features、模板实例、宏 spelling/expansion 和已生效 target/preprocessor guard；数学调用 SHALL 额外记录参数类型以及可静态证明的常量/区间/特殊值域线索。名称文本 MUST NOT 单独构成已解析 API 身份。

#### Scenario: 函数修改并恢复舍入模式
- **WHEN** 函数通过解析后的 fenv API 保存环境、修改 rounding mode 并在所有已解析正常出口恢复
- **THEN** facts 记录各动作、控制流位置和 `restores_environment` 函数摘要

#### Scenario: 间接调用阻断环境摘要
- **WHEN** 浮点环境影响可能穿过无法解析的函数指针、虚调用或外部函数
- **THEN** 函数摘要记录 `unknown_effect` 和 frontier，不假定调用无影响

#### Scenario: x86 intrinsic 位于条件分支
- **WHEN** 解析后的 x86 intrinsic 只出现在已验证的 x86 预处理分支中
- **THEN** fact 同时记录 declaration/intrinsic identity、头文件、target feature 和条件分支 provenance

#### Scenario: 数学调用参数域不可恢复
- **WHEN** libm 调用参数来自无法有界分析的数据流
- **THEN** 调用本身仍形成确定 fact，参数域以 unknown 表达且不伪造边界值

**证据可信度加固（facts 1.4）**

### Requirement: 项目事实必须具有可证明的物理来源和 canonical scalar type
扫描器 SHALL 在 facts 1.4 / mapper 1.1 中排除 `<scratch space>`、`<built-in>`、
`<command line>` 等 Clang 虚拟文件及项目根外系统声明。类型事实与数值转换 SHALL 优先使用
`desugaredQualType`，保留 `qualType` provenance，并严格区分 floating、integer、other 与
unknown；指针、函数、成员指针、数组和容器不得因类型文本包含 `float` 或 `double` 被当作标量。

#### Scenario: typedef 标量与浮点容器并存
- **WHEN** `real64` 脱糖为 `double`，同一翻译单元还包含 `std::array<float, N>` 和 `float*`
- **THEN** typedef 数值转换进入 conversion facts，容器和指针不进入标量 conversion 候选
- **AND** facts 保存原始、脱糖、canonical spelling 及其来源

#### Scenario: 来源无法证明
- **WHEN** 节点具有位置形状但没有可验证物理来源，也不满足受控 main-file 省略规则
- **THEN** 扫描器记录脱敏且非臆造的 `source_origin_unknown`
- **AND** 不得相对 build 目录拼接虚拟文件名

### Requirement: 全规则候选关系必须来自可证明的事实身份
Canonical facts SHALL 提供构造十规则独立候选所需的声明、表达式、调用点、所属函数/循环、递推和可证明状态/数据依赖关系。必要新增字段 SHALL 使用规范化位置及编译语义生成稳定身份，不依赖 Clang 指针、遍历序号、绝对目录或整个 bundle 摘要。缺少关系时 MUST 记录局部 unknown，不能按同行或同名猜测候选等价。

#### Scenario: 宏和模板中的独立调用
- **WHEN** 多个调用共享拼写位置但具有不同展开点或实例语义
- **THEN** 可证明的独立实例保留独立身份，无法区分的范围明确 unknown，不合并其他已知调用

#### Scenario: 同一行多个表达式
- **WHEN** 同一行有两个独立比较或转换表达式
- **THEN** 使用列、范围及表达式语义区分，不按行号生成同一候选

#### Scenario: 复杂候选需要多节点
- **WHEN** FMA、x87 求值链或 fenv 区间依赖多个节点与边界
- **THEN** facts 保留已证明的关系和边界，不能仅凭节点位置相近合并，也不能把一个完整关系拆成重复风险

#### Scenario: 未定位但身份可知
- **WHEN** 候选语义身份可证明但展示位置不可恢复
- **THEN** 候选仍独立保留并沿用未知位置协议，不造坐标，不影响其他有位置候选
