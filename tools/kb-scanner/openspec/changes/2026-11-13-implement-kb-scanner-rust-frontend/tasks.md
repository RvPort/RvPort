# Tasks

- [x] 1. 语料启用：reserve → collected/rust/；collect_rust_entries.py 三源拆分（arm64 2141+124 / riscv 186 / x86 类型 17；x86 函数跳过 99.4% 重叠）；manifest 哈希链；契约测试钉计数/哈希/再生成
- [x] 2. frontends/rust/：cfg_evaluator（三值矩阵：闭集二值/裸 ident unknown/Kleene any/all/not 对 default 取反；malformed → unknown）+ frontend（cfg 属性/asm! 字符串字面量逐语句/调用/类型/arch use 五检测点）+ vocabulary
- [x] 3. 接线：Composite 第四成员 + language_candidates .rs 分支 + cpp 子前端过滤修正（排除非 c/cpp 域文件）+ runtime.lock tree-sitter-rust 0.24.0（ABI 15 实测兼容）
- [x] 4. domain 参数化：projector 按 languages 推导前缀；models Literal + schema JSON 增 rust-x86/rust-arm64-to-riscv；默认路径字节不变
- [x] 5. 测试：unit 14（三值矩阵 16 subtest/asm 提取/门控/确定性/partial-parse 降级）+ e2e 7（x86 cfg+intrinsic、riscv 分支零 confirmed、asm 宏走检测链、domain、默认兼容、C+Rust 混合、arm64 NEON 检出）
- [x] 6. samples/rust + inventory 登记 + behavior-acceptance 3 条目 + knowledge.md 双语 + 本机全量回归
- [ ] 7. 容器级验证：kb-scanner-test 容器内 90/90 覆盖率门禁（与 D1-D3 遗留合并）
- [ ] 8. 归档
