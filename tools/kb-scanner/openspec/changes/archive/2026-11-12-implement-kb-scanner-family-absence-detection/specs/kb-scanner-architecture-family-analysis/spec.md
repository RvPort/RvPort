# kb-scanner-architecture-family-analysis 变更（缺失候选推导与升格证明）

## ADDED Requirements

### Requirement: 家族分析 SHALL 推导缺失 RISC-V 成员候选（两级输出）

家族目录发现后，分析器 SHALL 对"源架构角色领衔且无 riscv/generic 成员"的家族推导缺失候选：目标名直接消费分桶模板（token 已按词边界剥离），MUST NOT 做二次字面子串替换。纯词法缺失 SHALL 只进 extensions 命名空间 `org.rvport.kb-scanner.architecture-family`（`candidateMissingMember`：derivedTargetPath/derivationRule=family-absence-v1/anchorPath/status=candidate/scope=authorized-file-list），MUST NOT 产生 Finding。目录级缺失 SHALL 同样仅产 `directoryAbsence` extensions 候选。

#### Scenario: 文件级候选
- **WHEN** 授权清单含 `src/foo_x86.c` 与 `src/foo_arm64.c`（无 riscv/generic 成员）
- **THEN** 家族 extensions SHALL 记 `derivedTargetPath=src/foo_riscv64.c`
- **AND** findings SHALL 保持为空

#### Scenario: 误报防线（riscv-scan 黑盒回归）
- **WHEN** 清单含 `alarm.c`/`harmony.c`/`ppc64le` 目录（无词边界命中）
- **THEN** 不得建族、不得推导任何缺失候选

#### Scenario: 逃生口压制
- **WHEN** 家族存在 riscv 或 generic 角色成员
- **THEN** 不得推导缺失候选

### Requirement: 构建选中证明 SHALL 升格缺失为 Finding

仅当成员绑定证明 `selectionStatus=selected`（构建已选中源架构成员）且家族无 riscv/generic 成员时，分析器 SHALL 产 `family_gap` 实体 + `selects` 关系（metadata.issue=missing_riscv_family_member，携带 familyId/derivedTargetPath）+ complete ProofPath；BUILD.SELECTION 检测器 SHALL 产 `missing_riscv_family_member` 观察，分类策略归 actionable_non_blocking（portability_gap），建议 SHALL 双语并声明范围限定（authorized-file-list，不代表全项目缺失）。升格链引用（实体/证据/proof）MUST 全部实存；任何引用悬空 SHALL 降级为仅候选并记 `family-absence-escalation-skipped` 问题码。

#### Scenario: 升格
- **WHEN** CMake 选中 `foo_x86.cpp` 且家族无 riscv/generic 成员
- **THEN** SHALL 产 findingType=missing_riscv_family_member 的 Finding（actionable、非 blocking、双语建议）
- **AND** 证据链 SHALL 复现选中成员的构建选择证明

#### Scenario: 未选中不升格
- **WHEN** 家族无任何 selected 绑定
- **THEN** SHALL 仅产 extensions 候选，findings 保持为空

### Requirement: 缺失推导 SHALL 按源架构对称且预算有界

角色判定 SHALL 经 paths 词表按 `source_arch` 折叠（x86 源与 arm64 源对称：arm64 源时 arm 系为源角色、x86 系为 foreign）；推导目标恒为 `riscv64`（目标架构词）。候选数（absence_candidates）与目录数（absence_directories）超限 SHALL 截断并记问题码，不静默丢弃。歧义家族（任一成员 candidateStatus=ambiguous）MUST NOT 推导。

#### Scenario: arm64 源对称
- **WHEN** `--source-arch arm64` 扫描含 `simd/arm64/neon.c` 与 `simd/x86/neon.c`
- **THEN** arm64 为源角色、x86 为 foreign，缺失候选 SHALL 推导 `simd/riscv64`
