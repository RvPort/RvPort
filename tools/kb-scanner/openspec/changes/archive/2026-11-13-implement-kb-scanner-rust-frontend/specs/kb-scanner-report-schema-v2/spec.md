# kb-scanner-report-schema-v2 变更（rust 语言 domain 枚举）

## MODIFIED Requirements

### Requirement: scope.domain SHALL 支持语言维度参数化（rust）

domain SHALL 按 languages 中第一个有映射的语言推导前缀（c/cpp/asm→c-cpp、rust→rust、go→go、java→java、python→python），后接 `-{source_arch}-to-riscv`；本 change 新增枚举值 rust-x86-to-riscv / rust-arm64-to-riscv（models Literal 与 scan-result-v2.schema.json enum 同步）。默认 languages=("c","cpp") 的 domain 与输出字节 MUST 保持不变；V1 输出不受影响。新语言证据的 fact.kind 经 architecture_probe 兜底，vocabulary 无需新增。

#### Scenario: rust 单语言
- **WHEN** `--language rust` 扫描
- **THEN** domain SHALL 为 rust-{source_arch}-to-riscv，schema 校验通过

#### Scenario: 默认与混合向后兼容
- **WHEN** 默认 c,cpp 或 c 在前的混合语言（如 c,rust）
- **THEN** domain SHALL 保持 c-cpp-*，输出与改动前一致
