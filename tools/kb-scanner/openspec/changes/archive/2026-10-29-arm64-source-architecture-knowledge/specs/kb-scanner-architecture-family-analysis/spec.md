# kb-scanner-architecture-family-analysis 变更（源架构角色参数化）

## MODIFIED Requirements

### Requirement: 路径角色折叠 SHALL 按扫描源架构定向

路径词法模板的角色词表 SHALL 按源架构族定向：`x86` 扫描保持既有折叠（arm 系 → foreign）；`arm64` 扫描时 arm/arm64/aarch64 折叠为源架构角色 `arm64`，x86 系折叠为 `foreign`。riscv/generic 角色不变。

#### Scenario: arm64 扫描下的路径角色
- **WHEN** arm64 扫描分析 `arch/aarch64/impl.c` 与 `arch/x86_64/impl.c`
- **THEN** 前者角色 SHALL 为 `arm64`（源），后者 SHALL 为 `foreign`
- **AND** 语义上与 x86 扫描中 `arch/x86_64/impl.c` 的角色对等

### Requirement: 家族建族条件 SHALL 匹配源架构角色

分桶升格为架构家族的条件从"桶内出现 x86 角色候选"泛化为"桶内出现扫描声明的源架构角色候选"；纯 riscv/generic/foreign 桶依旧不建家族。
