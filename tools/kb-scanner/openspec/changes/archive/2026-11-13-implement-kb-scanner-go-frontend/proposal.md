# Why

Go 工程的架构相关代码（`//go:build` 构建约束、`runtime.GOARCH` 运行时分派、cgo 内嵌 C）对 kb-scanner 是盲区。riscv-scan 的 solver_go 提供检测点规格，但 build tag 用前瞻正则近似（`&&`/`||`/`!` 语义未实现），cgo 委托 4 个 C solver 为"匹配即报"。本 change 按合同重写为第五输入域：真表达式求值 + 五态语义 + 复用既有检测链。

## What Changes

- `frontends/go/` 三文件：build_tag 求值器（GOARCH 闭集三值、非架构 tag unknown、Kleene &&/||/!、数字开头 tag 如 386、malformed → unknown）；frontend 三检测点——`//go:build` → preprocessor_condition（带 tagValue）、`runtime.GOARCH ==/!=`（if/switch）→ selection_condition（supportsRiscv 二值语义）、cgo 注释块（`import "C"` 上方 `//` 与 `/* */` 注释）→ include/inline_asm（逐行复用 asm 词法器进 C 检测链——委托 C solver 的语义等价重实现）。
- 接线：Composite 第五成员（tree-sitter-go 0.23.4，ABI 14）；`language_candidates_for_path` 加 `.go`；domain 枚举增 `go-x86/arm64-to-riscv`；`--language go` opt-in，默认 c,cpp 输出字节不变。
- 不做：`_arch.go` 文件名排除（architecture_family 词法层天然处理）；goreleaser 配置检测（属 build 通道，独立小步可后补）。

## Capabilities

### Modified Capabilities

- `kb-scanner-cpp-frontends`: go 第五输入域。

## Impact

- 代码：frontends/go/（新三文件）、frontends/cpp/workspace.py、pipeline/composite.py、projection/v2/models.py + projector（_LANG_DOMAIN_PREFIX 已含 go）、schemas、runtime.lock（tree-sitter-go==0.23.4）
- 测试：unit 12（三值矩阵 19 subtest/GOARCH 双形态/cgo 两形态/门控/确定性）+ e2e 5（build tag+cgo 检出/domain/riscv tag 零 confirmed/默认兼容/sample）+ samples/go
- 依赖：runtime.lock 增 tree-sitter-go==0.23.4（ABI 14 < 15 兼容实测）
