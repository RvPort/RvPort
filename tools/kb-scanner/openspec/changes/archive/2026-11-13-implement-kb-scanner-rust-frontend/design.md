# Design

## 上下文

riscv-scan 的 solver_rust 是其最深检测面：cfg 三值递归下降求值（~200 行）、11235 项架构函数表（按 arch key 分堆查 set）、asm! 宏委托 InlineAsmSolver、`use core::arch` 模块路径。kb-scanner 侧 D1 已把 rust_arch 语料冻结在 reserve；tree-sitter 0.26 新式 binding 与 SourceByteIndex 行列换算可直接复用。

## 决策

### D1: cfg 三值求值器全新实现，unknown 不二值化

riscv-scan 的三值只体现为 default 传递、最终二值化输出（其缺陷）。我们保留三值到证据 metadata（cfgValue ∈ true/false/null）：target_arch 取值是闭集故二值（riscv 系 true、其余 false）；非 target_arch 键与裸标识符（unix 等）→ unknown（feature 语义留给下游知识匹配）；any 子项缺省 False、all 缺省 True、not 对 default 取反（Kleene）；malformed 返回 unknown 不抛。

### D2: 语料三源拆分（单源单族约束）+ x86 函数跳过

CollectedFactsModel 硬约束单源单族 → 按族拆三个 manifest 源。实测 x86 函数名与既有 C intrinsics 词表重叠 6121/6159（99.4%），收编只造重复条目——跳过；arm64 NEON 2141 项是纯增量（C 词表无 arm64 intrinsics，`--source-arch arm64` 扫 Rust 即刻可用）；riscv 186 项进目标族（D1 通道复用）。

### D3: asm! 宏体提取字符串字面量再逐行词法

只取第一个字符串字面量（字符串拼接与非字符串首参不产证据——拼接语义需编译期知识，保守）；复用 D3 asm frontend 的 `_scan_lines` 逐语句产 inline_asm。

### D4: domain 语言维度参数化（而非枚举翻倍）

projector 的 domain 从硬编码 `c-cpp-` 改为 `_LANG_DOMAIN_PREFIX` 映射 + languages 第一命中语言取前缀。混合语言 c,rust 时 c 在前 → domain 仍 c-cpp-*（向后兼容）；默认 c,cpp 字节不变。四同步点：projector 拼装、models Literal、schema JSON enum、契约测试断言。

### D5: 修复 cpp 子前端过滤（实现期发现的真 bug）

cpp 的 tree-sitter-cpp 错误恢复模式会误解析 .rs 产重复证据（同 symbol 双 Finding、producer=tree-sitter-rust@0.23.4 泄露）。修复：cpp 子集过滤限定 `set(language_candidates) & {"c","cpp"}`。这是 D3 汇编域就该修的隐患，此处一并解决。

## 风险与缓解

- tree-sitter-rust ABI：lang.abi_version=15 实测落在 core 13..15 区间（门禁脚本后补列入）。
- 11235 项装载性能：仅收 arm64+riscv 子集（2327 项），KnowledgeIndex 计数断言。
