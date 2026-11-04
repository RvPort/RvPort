## Why

独立浮点问题仍会被合成一条 Finding：已确认 `architecture-specific-fp-api` 将不同函数中的调用合并，代码排查还发现 FMA、x87、浮点环境、比较和 libm 使用整 TU 评估，导致证据、unknown、置信度和抑制相互影响。此前归约粒度修复只解决一条规则；本次按用户“全部一次性修复，不留下同类遗留”的要求覆盖十条规则及共用结果链路。

## What Changes

- 为十条规则明确独立问题的语义边界，并由实际候选身份驱动评估，不再默认按 TU 收集全部命中后生成一条 Finding。
- 修复六条尚未拆分规则：FMA 按完整乘加候选、x87 按有证据连接的求值链、浮点环境按函数内可证明的状态影响区间、比较按表达式、libm 和架构 API 按调用点分别评估。
- 全面回归并补齐 ABI、隐式精度转换和归约的去重、预算、异常、证据隔离，不把“已有逐候选代码”视为自动验收通过。
- 审核危险优化配置规则：仅同一可证明的有效配置风险允许聚合；不同配置、目标、profile 或语义边界不得仅按危险 flag 名称相同合并。聚合必须保全成员证据、身份、数量和逐成员抑制。
- 统一候选级 matched/notMatched/unknown/failed、局部证据筛选、稳定身份、预算停止、诊断和抑制。合法的同一问题多证据可保留多个 occurrences；独立问题不能伪装成关联位置。
- 保持结果 schema v2、公开双语字段和旧请求兼容；更新必要规则/schema/mapper/registry/cache 版本。新扫描使用新粒度，历史结果不回填，不要求前端拆解聚合对象。
- 为全部十条规则提供逐条验收矩阵、有效单元断言和真实 C/C++ 多候选语料，重新生成当前源码 observed；整体和各受影响模块行/分支覆盖率至少 90%，保留既有更严格的质量与性能门槛。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `floating-point-risk-detection`：十规则候选粒度、独立处置边界与唯一合法配置聚合条件。
- `canonical-floating-point-facts`：可证明的候选、表达式/调用/函数/递推/状态影响区间身份与局部 unknown。
- `ast-ir-floating-point-evidence`：候选直接证据与必要依赖闭包，防止无关 IR 提升或否定其他结论。
- `scanner-runtime-foundation`：所有规则统一有界、可隔离的候选调度和版本/缓存策略。
- `scanner-io-contract`：独立 Finding、完整聚合审计、候选 suppression、计数与 v2 投影。
- `scanner-quality-gates`：十规则全覆盖回归矩阵、当前源码真实语料、双覆盖率、性能及部署验收。

## Impact

实施范围为本扫描器的 facts、evidence、rules、candidate 调度、suppression、result_v2、application、schema/版本、测试、固定语料和中文文档；根据证据需要补足现有 LLVM facts worker 的关系信息。所有修改集中在 `tools/floating-point-precision-scanner`，不修改前端、Java 后端或其他工具。

前端保持以 Finding ID 展示，单位置不出现下拉框；扫描器保证返回的多个位置确属同一个问题。Docker 中完成全部代码验证，从仓库根完成隔离 Compose 构建、启动和实际安装包验证。提案仅定义范围与验收，既有归档报告不代表本变更已经通过；不得留下某条规则“后续再修”并宣称整体完成。
