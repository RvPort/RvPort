# Design

## 上下文

riscv-scan 的 riscv.macro（203 项 `__riscv*`）是唯一的目标侧词表资产；kb-scanner 的 `ARCHITECTURE_FAMILIES` 只有 x86/arm64 两源族，schema 无法表达"目标侧"。消费缺口在 `evidence_graph._branch_role`：`#elif __riscv_xlen` 之前只能靠 `_ROLE_PATTERNS` 的裸 "riscv" 词形正则兜底（不带知识 ID、无法溯源）。

## 决策

### D1: riscv 进知识族枚举，而非塞 paths.py._ROLES 词表

词表路线违反数据/策略分离纪律（structural 词表禁内嵌知识），且链路错位——`_ROLES` 消费路径 token（目录/文件名），`__riscv*` 的消费链在宏条件分支。知识族路线复用 collected-v1 通道（语料冻结 + sha256 链 + 确定性再生成），溯源完整。

### D2: 方向性钉在 semanticScope，硬约束钉在两处"不加"

`semanticScope="riscv-only"` 表达"出现即已移植"（与源族相反）；`context.source_arch` 取值集**不加** riscv、`DEFINITIONS_BY_FAMILY` **不加** riscv 键——riscv 条目只被 `_target_family_role` 显式 `family="riscv"` 查询命中，杜绝被误当源架构词表装载的方向反转事故（负测断言键集）。

### D3: 目标族预检必须先于 _ROLE_PATTERNS 词形正则

实测发现 `__riscv_xlen` 内的 "riscv" 子串会被词形路径截胡（前导排除集不含下划线），返回 (riscv, ()) 且不带知识 ID。预检提前后 `__riscv*` 走知识库路径（带 entry ID，可溯源），裸 riscv/riscv64 词形仍走原路径。negated（`#ifndef __riscv*`）返回 unknown——"未定义目标宏"不构成"已移植"证据（五态纪律）。

### D4: 储备数据哈希钉死但不进 manifest

rust_arch_*.json 在本 change 时点无消费者（Rust frontend 未立项），进 manifest 会污染 bundle identity 并连带全部快照基线。冻结在 collected/reserve/ + 契约测试钉哈希，启用时再走正式通道（后被 Rust change 按此路径启用）。

## 风险与缓解

- 新源进 manifest 改变 knowledgeSources 视图 → 全局摘要哈希基线刷新（与 arm64 收编同款既有惯例）；v1.0 输出不含该视图，digest 不变（曾误刷后回退）。
- 词表污染：契约测试钉死 203 计数、单源单族、再生成字节一致。
