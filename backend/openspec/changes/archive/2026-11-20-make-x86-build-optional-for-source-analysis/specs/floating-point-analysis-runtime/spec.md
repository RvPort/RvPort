## MODIFIED Requirements

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
