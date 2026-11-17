# kb-scanner-knowledge-data-production 变更（riscv 目标族语料）

## ADDED Requirements

### Requirement: 知识库 SHALL 支持 riscv 目标族并以 riscv-only 语义范围登记

知识架构族枚举 SHALL 包含 `riscv`，语义范围 SHALL 包含 `riscv-only`。riscv 族条目 SHALL 仅经 `collected-v1` 来源登记（手册语料溯源：语料原件 SHA-256 + 官方规范 URL + 采集日期），单源单族。riscv 为**目标族**：条目命中表示代码已有 RISC-V 分支/实现（方向与源族相反），MUST NOT 进入 `source_arch` 取值集与检测器注册表 `DEFINITIONS_BY_FAMILY`；其唯一运行时消费路径是分支角色分析中显式 `family="riscv"` 的查询。

#### Scenario: riscv 宏装载
- **WHEN** Provider 装载生产 manifest
- **THEN** `collected-riscv-entries` 源 SHALL 装载 203 条 platformMacros（族 riscv）
- **AND** 检测器注册表键集 SHALL 保持 `{"x86","arm64"}`

#### Scenario: 目标宏分支判定
- **WHEN** 扫描遇到 `#elif __riscv_xlen >= 64` 条件分支
- **THEN** 分支角色 SHALL 判定为 `riscv` 并携带命中的知识条目 ID
- **AND** 该条件取反（`#ifndef __riscv*`）时 SHALL 返回 `unknown`（不构成"已移植"证据）

#### Scenario: 族隔离
- **WHEN** x86/arm64 源族扫描
- **THEN** riscv 条目 MUST NOT 产生任何源族观察（目标宏不得被报为迁移障碍）

### Requirement: 储备语料 SHALL 哈希钉死且不进运行时清单

无消费者的语料（如 Rust 架构函数/结构体表）SHALL 冻结于 `collected/reserve/`，由契约测试钉 SHA-256 防漂移；knowledge-manifest MUST NOT 声明它们。

#### Scenario: 储备隔离
- **WHEN** 校验生产 manifest 与 reserve 文件
- **THEN** manifest 中 SHALL 不出现 `reserve` 路径
- **AND** 储备文件字节变化 SHALL 触发契约测试失败
