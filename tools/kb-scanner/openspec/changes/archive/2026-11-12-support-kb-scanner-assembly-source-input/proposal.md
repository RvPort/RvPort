# Why

kb-scanner 的 C/C++ 前端只认 .c/.cc/.cpp/.cxx 与头文件后缀——汇编源文件（.s/.S/.asm）不是输入，迁移项目里常见的架构专用汇编（CRC/AES/原子操作内核汇编）对扫描是盲区。riscv-scan 的 AsmFileSolver 有整文件架构判定的检测点设计，但其行为按 kb-scanner 合同重写：汇编不进 cpp 语法树（tree-sitter-c 管不到 asm 方言），而是作为第三输入域走逐行词法，复用既有 ASM 检测器（C_CPP.{X86,ARM64}.ASM_INSTRUCTION/ASM_REGISTER，按 source_arch 选族）与 C/C++ 归一化链。

## What Changes

- 新子包 `frontends/asm/`（第三输入域，对齐 BuildFrontend 模式）：vocabulary（后缀集 {.s,.S,.asm}——不含 .inc，保持 C 头语义；Entity/Evidence 工厂）+ frontend（逐行词法：跳过 C 预处理指令行不展开、;///**/注释剥离、标签前缀剥离；每语句产 inline_asm 证据，行列 1-based、行首字节偏移进 metadata）。
- `language_candidates_for_path` 增 asm 分支（后缀命中且 language 开关启用才返回 ("asm",)）；`--language` 增合法值 asm，**默认 c,cpp 不变**（opt-in：存量项目输出字节不受影响，asm 文件零证据零清单）。
- `CompositeProjectFrontend` 第三成员 AsmFrontend；下游零改动（inline_asm → 既有 _KIND_MAP → fact architecture_probe → 既有 ASM 检测器 → 既有 Finding 路径，schema 零变化）。
- 预算：单文件 1MB、累计 32MB、5 万行；decode 失败字符替换 + 诊断（不崩底线）；防目录逃逸。
- 已知边界（文档化）：与源架构词表交集的 token（ret/add）仍报源架构观察——与 C 内联汇编同语义（检测器按源架构词表匹配，不知文件"是目标架构汇编"）；structural 候选（inline_asm_candidate）保持 unknown 态。

## Capabilities

### Modified Capabilities

- `kb-scanner-cpp-frontends`: 汇编第三输入域（language_candidates_for_path asm 分支 + frontends/asm 子包）。
- `kb-scanner-core-pipeline`: CompositeProjectFrontend 三成员装配。

## Impact

- 代码：frontends/asm/（新三文件）、frontends/cpp/workspace.py（ASM_SUFFIXES + language_candidates 分支）、pipeline/composite.py（第三成员）
- 测试：tests/unit/test_asm_frontend.py（15 用例：词法/门控/预算/确定性）、tests/test_asm_scan.py（8 用例：端到端 x86 定位、riscv 零 confirmed、默认兼容、C+asm 混合家族）
- 配置：后端零改动（RVPORT_KB_SCAN_LANGUAGE 配置值加 asm 即启用）；CLI --language 无枚举校验（自由值），文档说明合法值
- 文档：CLI help、docs/development.md --language 说明、behavior-acceptance.json 增条目
