# kb-scanner-cpp-frontends 变更（汇编第三输入域）

## ADDED Requirements

### Requirement: 前端 SHALL 支持汇编源输入域（opt-in）

`language_candidates_for_path` SHALL 对 .s/.S/.asm 后缀返回 ("asm",)（仅当 language 开关含 asm）；默认 languages=("c","cpp") 时汇编文件 MUST NOT 进入任何子前端清单（输出字节不变）。汇编前端 SHALL 逐行词法扫描：跳过 C 预处理指令行（不展开）、剥离 ;///**/ 注释与标签前缀，每个剩余非空语句产一条 `inline_asm` 证据（producer=asm-frontend，parse_mode=lexical-line，行首字节偏移进 metadata，稳定 ID 确定）。预算：单文件 1MB / 累计 32MB / 5 万行，超限降级 PARTIAL 并记诊断；decode 失败按字符替换不崩溃。

#### Scenario: 启用汇编扫描
- **WHEN** `--language asm`（或 c,asm）扫描含 `movaps` 的 .S 文件
- **THEN** SHALL 产 x86_assembly_instruction Finding，位置为语句所在行列
- **AND** riscv 独有指令（vsetvli/vle32.v）不产生 confirmed 观察，仅 structural 候选（unknown 态）

#### Scenario: 默认语言向后兼容
- **WHEN** 默认 `--language c,cpp` 扫描含 .S 文件的项目
- **THEN** 汇编文件零证据、零清单、零 Finding，输出与无此能力时一致

#### Scenario: C+asm 混合家族
- **WHEN** `src/foo_x86.c` 与 `src/foo.S` 同模板同扩展名并存
- **THEN** 架构家族分析 SHALL 将二者归入同一家族（asm 成员带 pathObservation）

#### Scenario: 预算与降级
- **WHEN** 汇编文件超 1MB 或 5 万行
- **THEN** 该文件 SHALL 截断处理并降级 PARTIAL（记 asm-frontend.line-budget/input-partial），阶段 MUST NOT FAILED
