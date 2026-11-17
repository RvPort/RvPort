# kb-scanner-report-schema-v2 变更（domain 与 readiness 枚举扩展）

## MODIFIED Requirements

### Requirement: scan.scope.domain SHALL 按源架构输出

`domain` 从常量 `c-cpp-x86-to-riscv` 扩展为枚举 `[c-cpp-x86-to-riscv, c-cpp-arm64-to-riscv]`，取值由扫描声明的源架构决定。既有消费者对 x86 值的兼容 MUST 保持。

#### Scenario: arm64 扫描报告
- **WHEN** 以 `--source-arch arm64` 扫描并输出 v2 报告
- **THEN** `scan.scope.domain` SHALL 为 `c-cpp-arm64-to-riscv`
- **AND** 报告 SHALL 通过 JSON schema 校验

### Requirement: summary.readiness SHALL 含源架构锁定态

`readiness` 枚举新增 `arm64_only`（与 `x86_only` 同义：源架构锁定），按扫描源架构命名；其余取值不变。

#### Scenario: arm64 锁定项目
- **WHEN** arm64 扫描的评估结论为源架构锁定（blocker 且无替代实现）
- **THEN** `summary.readiness` SHALL 为 `arm64_only`

### Requirement: knowledgeSources.kind SHALL 含 collected

知识来源视图 kind 枚举新增 `collected`（手册语料），与 `compiler-derived`/`curated` 并列；JSON schema 同步。
