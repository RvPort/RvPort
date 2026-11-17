# Why

kb-scanner 的语言轴只有 C/C++（+opt-in 汇编），Rust 工程的架构相关代码（`#[cfg(target_arch)]` 条件编译、`std::arch` intrinsic、`asm!` 宏）对扫描是盲区。上游 rvport-skills/riscv-scan 的 solver_rust 有最深的检测面（cfg 三值求值 + 11235 项架构函数表 + asm! 委托），本 change 按既有"拿规格不拿实现"决策把检测点重写为 kb-scanner 第四输入域，并启用 D1 冻结的 rust 语料。

## What Changes

- **知识（R1）**：`collected/reserve/` 的 rust_arch 语料移入 `collected/rust/` 启用；`tools/collect_rust_entries.py` 按单源单族拆三源——arm64 intrinsics 2141+vectorTypes 124（纯增量：C 词表无 arm64 intrinsics）、riscv 目标族 intrinsics 186（命中=已用 RV intrinsic）、x86 vectorTypes 17；**x86 函数名跳过**（与既有 C 词表重叠 6121/6159=99.4%，收编只造重复条目）。manifest 三层哈希链同步（version 2026.11-rust.1）。
- **frontend（R2）**：`frontends/rust/` 三文件——cfg 三值求值器（target_arch 闭集二值、裸标识符 unknown、any/all Kleene 折叠、not 对 default 取反；解析失败返回 unknown 不抛）、`#[cfg]` → preprocessor_condition（metadata 带求值结果）、`asm!`/`global_asm!`/`naked_asm!` 字符串字面量 → 逐语句 inline_asm（复用 asm frontend 词法器）、调用/类型引用 → call_expression/type_reference（既有 intrinsics/vectorTypes 检测器按词表命中）、`use std::arch::*` → include。tree-sitter-rust 0.24.0（ABI 15，与 core 0.26 兼容）。
- **接线（R3）**：CompositeProjectFrontend 第四成员；`language_candidates_for_path` 加 `.rs`；**cpp 子前端过滤修正**（排除非 c/cpp 语言域文件——此前 cpp 的错误恢复解析会误处理 .rs/.S 产重复证据）；`--language rust` opt-in，默认 c,cpp 输出字节不变。
- **domain 参数化**：projector 的 domain 从硬编码 `c-cpp-` 改为按 languages 推导前缀（c/cpp/asm→c-cpp、rust→rust；混合取第一命中），新增枚举 `rust-x86-to-riscv`/`rust-arm64-to-riscv`（models Literal + schema JSON 同步）；V1 不受影响。

## Capabilities

### Modified Capabilities

- `kb-scanner-cpp-frontends`: rust 第四输入域（语言门控 + 后缀判定 + cpp 过滤修正）。
- `kb-scanner-knowledge-data-production`: rust 语料三源收编（collected-v1 通道复用）。
- `kb-scanner-report-schema-v2`: domain 枚举扩展（语言维度参数化）。

## Impact

- 代码：frontends/rust/（新）、frontends/cpp/workspace.py、pipeline/composite.py、projection/v2/projector.py + models.py、schemas/scan-result-v2.schema.json
- 数据：collected/rust/（自 reserve 移入）、generated/rust-{arm64,riscv,x86}-collected-entries.json（新）、manifests
- 测试：unit 14 用例（cfg 三值矩阵/asm 提取/门控/预算/确定性）+ e2e 7 用例（x86 检出/riscv 分支不误报/asm 链/domain/默认兼容/C+Rust 混合/arm64 NEON）+ samples/rust
- 依赖：runtime.lock 增 tree-sitter-rust==0.24.0
- 文档：knowledge.md 双语 rust 语料段落；behavior-acceptance 增 3 条目
