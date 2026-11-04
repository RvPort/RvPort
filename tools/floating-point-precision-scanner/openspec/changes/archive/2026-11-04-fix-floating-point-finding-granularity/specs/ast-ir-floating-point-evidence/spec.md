## ADDED Requirements

### Requirement: 规则证据必须属于当前候选的有界依赖闭包
规则 SHALL 从候选直接 source refs 沿可靠 binding、operand/use-chain 和必要函数摘要收集有界证据闭包，仅附加真正影响该候选的 profile、build context 和编译选项。MUST NOT 将同 TU 的全部 IR、binding 或函数摘要作为任意候选的证明。共享依赖必须保留作用关系；悬空、歧义或冲突关联 SHALL 局部降级。

#### Scenario: 不同候选分别具有 multiply 和 add
- **WHEN** 两个无数据依赖的候选分别具备 contract multiply 与 contract add
- **THEN** 不能将二者拼成一个已验证 FMA lowering，只有完整关联当前候选的证据才能支持确定结论

#### Scenario: 无关 IR 增强
- **WHEN** 增加一个无关函数的重结合、扩展精度或融合 IR 证据，且预算充足
- **THEN** 原候选的证据集合、状态、置信度和稳定身份保持不变

#### Scenario: 函数摘要隔离
- **WHEN** 一个函数摘要存在未知调用效果，另一个候选与其没有已证明调用或状态依赖
- **THEN** 未知效果不影响另一个候选；有可靠依赖时仅向依赖闭包传播并说明原因

### Requirement: 关系扩展和缓存必须同步版本
事实或 evidence 关系、候选绑定方法及其身份语义变化时，扫描器 SHALL 同步对应 mapper/schema/worker 版本、registry 摘要和缓存指纹。旧缓存 MUST NOT 被当作满足新候选关系契约的输入；冷暖缓存及工作区搬迁 SHALL 保持同语义候选结果一致。

#### Scenario: 旧缓存缺少候选关系
- **WHEN** 缓存条目不能提供新版本要求的表达式、调用或状态区间关系
- **THEN** 拒绝该缓存命中并重新分析，不凭旧位置列表猜测归属
