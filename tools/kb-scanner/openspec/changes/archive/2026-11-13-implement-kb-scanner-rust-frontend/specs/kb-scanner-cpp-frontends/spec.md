# kb-scanner-cpp-frontends 变更（Rust 第四输入域）

## ADDED Requirements

### Requirement: 前端 SHALL 支持 Rust 源输入域（opt-in）且 cfg 求值保持三值语义

`language_candidates_for_path` SHALL 对 .rs 返回 ("rust",)（仅当 language 开关含 rust）；默认 languages=("c","cpp") 时 Rust 文件 MUST NOT 进入任何子前端（cpp 子前端的文件过滤 MUST 限定 c/cpp/头文件候选）。Rust 前端 SHALL 产出五类证据：cfg 属性 → preprocessor_condition（metadata.cfgValue ∈ true/false/null + cfgTargetArch）、asm!/global_asm!/naked_asm! 字符串字面量 → 逐语句 inline_asm、调用 → call_expression、类型引用 → type_reference、`use std::arch::*` → include。

cfg 求值 SHALL 三值（Kleene）：target_arch 取值是闭集（riscv64/riscv32→true，其余架构字面量→false）；非 target_arch 键与裸标识符→unknown；any 子项缺省 false、all 子项缺省 true、not 对 default 取反；解析失败 SHALL 返回 unknown（不抛异常、不静默丢弃证据）。

#### Scenario: x86 cfg + intrinsic
- **WHEN** `--language rust` 扫描 `#[cfg(target_arch="x86_64")]` 下的 `_mm_add_epi32` 调用
- **THEN** SHALL 产 x86_intrinsic_reference Finding（证据 producer=rust-frontend），cfg 条件证据携带 cfgValue=false

#### Scenario: riscv 分支不误报
- **WHEN** 扫描仅含 `#[cfg(target_arch="riscv64")]` 函数的文件
- **THEN** 不得产任何 x86 confirmed 观察

#### Scenario: 默认语言向后兼容
- **WHEN** 默认 `--language c,cpp` 扫描含 .rs 文件的项目
- **THEN** .rs 零证据零 Finding，cpp 子前端不得以错误恢复模式解析它

### Requirement: domain SHALL 按主语言推导（语言维度参数化）

domain SHALL 按 languages 中第一个有映射的语言推导前缀（c/cpp/asm→c-cpp、rust→rust），新增枚举值 rust-x86-to-riscv / rust-arm64-to-riscv；默认 c,cpp 输出 MUST 字节不变。

#### Scenario: Rust 单语言
- **WHEN** `--language rust` 扫描
- **THEN** domain SHALL 为 rust-{source_arch}-to-riscv

#### Scenario: 混合语言
- **WHEN** `--language c,rust`
- **THEN** domain 取 c → c-cpp-*（向后兼容），两域证据并存

### Requirement: rust 语料 SHALL 以三源 collected-v1 形态收编

arch-functions/arch-structs 语料 SHALL 拆为三个单族源：arm64（intrinsics 2141 + vectorTypes 124，C 词表缺口的纯增量）、riscv 目标族（intrinsics 186）、x86（vectorTypes 17）；x86 函数名 MUST 跳过（与既有 C 词表重叠 99.4%，避免重复条目）；其余架构（loongarch/mips/wasm/s390x/ppc/nvptx）丢弃。

#### Scenario: arm64 NEON 检出
- **WHEN** `--language rust --source-arch arm64` 扫描含 `__crc32cd` 的文件
- **THEN** SHALL 命中 rust-arm64 语料产 arm64_intrinsic_reference 观察
