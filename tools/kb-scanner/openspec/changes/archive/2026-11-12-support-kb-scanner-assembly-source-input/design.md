# Design

## 上下文

迁移项目的 C/C++ 工程普遍含架构专用汇编（.s/.S/.asm），但 CppWorkspaceCollector 的后缀清单只有 C/C++/头文件——汇编源不是输入。riscv-scan 的 AsmFileSolver 做整文件架构判定（solver_type=5），语义是"匹配即报"。

## 决策

### D1: 第三输入域（独立 frontend 包），不塞 cpp frontend

cpp 的 tree-sitter-c 管不到 asm 方言（错误恢复模式下会产噪声证据——后来 Rust 接线时实测坐实并修复了 cpp 过滤）；asm 走独立逐行词法（无语法树依赖），对齐 BuildFrontend 的"独立输入域 + 双重预算"模式。

### D2: 复用 inline_asm 证据通道，下游零改动

每语句产 kind=inline_asm 证据 → CppEvidenceNormalizer 既有 _KIND_MAP 直通 → fact.kind 兜底 architecture_probe → 既有 C_CPP.{X86,ARM64}.ASM_INSTRUCTION/ASM_REGISTER 检测器按 source_arch 选族。schema 零变化；纯 riscv .S 无源族词表命中 → 仅 structural 候选（unknown 态，不下结论）。

### D3: opt-in 语言开关，默认字节不变

`--language` 增 asm 合法值但默认保持 c,cpp——存量项目输出与无此能力时逐字节一致（向后兼容断言进测试）。后端零代码（配置 RVPORT_KB_SCAN_LANGUAGE=asm 即启用）。

### D4: 词法边界与已知语义

`#` 预处理行整行跳过（不展开不递归——.S 的 #include 不解析，保守）；`;`/`/* */`/`//` 注释剥离（块注释跨行状态机）；标签前缀剥离且标签独占行整行跳过；行首字节偏移进 metadata 保证稳定 ID 可复现。已知边界：与源架构词表交集的 token（ret/add）仍报源架构观察——与 C 内联汇编同语义（检测器不知文件"是目标架构汇编"），测试文档化。

### D5: 预算

单文件 1MB / 累计 32MB / 5 万行，超限降级 PARTIAL 记码（内核 style 数十万行汇编不拖垮扫描）；decode 失败字符替换不崩。

## 风险与缓解

- tree-sitter-cpp 误吞 .S（后置发现）：CompositeProjectFrontend 的 cpp 子集过滤限定 {c,cpp}（Rust change 修复，此处为受影响面）。
