## MODIFIED Requirements

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
