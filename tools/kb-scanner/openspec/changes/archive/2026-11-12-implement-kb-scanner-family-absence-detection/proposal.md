# Why

kb-scanner 的架构家族分析只被动记录"范围内未见候选"（candidateAbsence），不推导缺失、不产 Finding——产品层对"源架构实现存在但 RISC-V 对应物缺失"（如 `src/arch/x86_64/` 有而 `src/arch/riscv64/` 无、`foo_x86.c` 有而 `foo_riscv.c` 无）始终是盲区，AI 迁移链路无法对结构缺失产生工作量条目。上游 rvport-skills/riscv-scan 的 arch_missing.py 有该能力的算法规格（目录/文件级配对 + 目标名推导），但其实现是无词边界子串匹配（实测误报：`alarm.c`→`al_riscv64.c`、`foo_arm64.c`→`foo_64_riscv64.c`），且"匹配即报"语义与五态合同不兼容。本 change 把算法规格按 kb-scanner 合同重写：识别层复用家族分桶的模板产物，输出按两级分层（词法候选 → 构建选中证明升格）。

## What Changes

- 新分析器模块 `analyzers/architecture_family/absence.py`：`analyse()` 在 `bind()` 之后运行，对家族推导 `candidateMissingMember`（文件级）与 `directoryAbsence`（目录级），写入 catalog 并由 `families.py` 投影进 extensions 命名空间（既有先例 `org.rvport.kb-scanner.architecture-family` 扩字段）；目标名直接消费分桶模板（`filename:src/foo.c` → `src/foo_riscv64.c`、`directory:arch/{architecture}/impl.c` → `arch/riscv64`），杜绝二次字面替换。
- 升格链（镜像 selection_gap 先例）：`family_gap` 实体 + `selects` 关系（issue=missing_riscv_family_member）+ complete ProofPath → `detectors/build_selection.py` 新分支产同名观察 → `classifiers/policy.py` 归 actionable_non_blocking → `assessors/migration.py` missing_target 维度 + `projection/policy.py` gap/V1 映射 → `suggestions.py` 新增 `family_gap` 双语建议（措辞声明 authorized-file-list 范围限定）。
- `FamilyBudget` 增 `absence_candidates=512`/`absence_directories=256` 限额；超限记 `family-absence-budget`/`family-absence-directory-budget` 问题码。
- 防线：歧义家族不推导；riscv/generic 成员存在即压制（逃生口）；升格引用悬空降级候选并记 `family-absence-escalation-skipped`；arm64 源对称（角色经 paths 词表按 source_arch 折叠）。
- 输出合同：纯词法候选不产 Finding（V2 extensions 专用，V1 输出不变）；schema 结构零变化（extensions 是自由命名空间）。

## Capabilities

### Modified Capabilities

- `kb-scanner-architecture-family-analysis`: 缺失候选推导与升格证明（本 change 主 spec delta）。
- `kb-scanner-classification-and-assessment`: missing_riscv_family_member 归类与 missing_target 维度。
- `kb-scanner-report-schema-v2`: 仅词汇（findingType 自由字符串 + suggestions 文案），无结构变化。

## Impact

- 代码：absence.py（新）、analyzer.py（挂载）、families.py（投影扩展）、detectors/build_selection.py、classifiers/policy.py、assessors/migration.py、projection/policy.py、projection/v2/suggestions.py、grouping.py（预算字段）
- 测试：tests/unit/test_architecture_family_absence.py（合成快照 14 用例）、tests/test_architecture_family_absence_component.py（真 CLI 9 用例，含 riscv-scan 误报四件套永久回归）、architecture-family-test-inventory.json 登记
- 文档/验收：docs/analysis-capabilities.md、rulesets/riscv-porting/behavior-acceptance.json 增 capability 条目
- 后端：零改动（findingType 自由字符串；AI 迁移投影规范建议增 `arch_missing` 枚举属另一文档线）
