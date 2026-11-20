"""Rust frontend 子包：.rs 文件的第四输入域（tree-sitter-rust AST + cfg 三值求值）。

检测点（规格源自 rvport-skills/riscv-scan solver_rust，按 kb-scanner 合同重写）：
`#[cfg(...)]` 条件编译属性（三值求值，unknown 不二值化）、`asm!`/`global_asm!`
宏体（逐行词法转 inline_asm，复用 asm frontend 的扫描器）、架构函数调用与
类型引用（走知识词表检测器）、`use core::arch::*` 模块路径。
"""
