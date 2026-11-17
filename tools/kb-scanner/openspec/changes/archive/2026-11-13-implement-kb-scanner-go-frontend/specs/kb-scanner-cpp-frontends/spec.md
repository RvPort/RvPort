# kb-scanner-cpp-frontends 变更（Go 第五输入域）

## ADDED Requirements

### Requirement: 前端 SHALL 支持 Go 源输入域（opt-in）且 build tag 保持真表达式三值求值

`language_candidates_for_path` SHALL 对 .go 返回 ("go",)（仅当 language 开关含 go）。Go 前端 SHALL 产三类证据：`//go:build` → preprocessor_condition（metadata.tagValue ∈ true/false/null）、`runtime.GOARCH ==/!=` → selection_condition（supportsRiscv 二值，!= 翻转）、cgo 注释块（`import "C"` 紧邻上方 `//` 与 `/* */` 注释）→ include/inline_asm（逐行进 C 检测链）。

build tag 求值 SHALL 解析完整表达式语法（`!`/`&&`/`||`/括号/优先级）而非词存在性近似：GOARCH 取值闭集（riscv 系 true、已知其他架构 false）、非架构 tag（linux/cgo/unix）unknown、Kleene 折叠、解析失败 unknown。

#### Scenario: x86 build tag + cgo
- **WHEN** `--language go` 扫描 `//go:build linux && amd64` + cgo 块内 `_mm256_setzero_si256`
- **THEN** cgo C 代码 SHALL 命中 x86 词表产 Finding（producer=go-frontend），build tag 证据 tagValue=false

#### Scenario: riscv build tag 不误报
- **WHEN** 扫描 `//go:build riscv64` 文件
- **THEN** 不得产任何 x86 confirmed 观察；domain SHALL 为 go-{source_arch}-to-riscv

#### Scenario: 默认语言向后兼容
- **WHEN** 默认 `--language c,cpp`
- **THEN** .go 文件零证据零 Finding，输出字节不变
