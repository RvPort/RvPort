# Design

## 上下文

riscv-scan 的 solver_go 三检测点：build tag 用**前瞻正则近似**（`&&`/`||`/`!` 布尔语义未实现，靠词存在性）；runtime.GOARCH 分支走 tree-sitter 剪枝 walk；cgo 注释块字符串转投 4 个 C solver。

## 决策

### D1: build tag 真表达式求值（修复 riscv-scan 的语义缺口）

递归下降解析完整语法（`!`/`&&`/`||`/括号/优先级），GOARCH 闭集三值（riscv 系 true、已知其他架构含 mips/ppc/s390x/wasm/loong64 与数字开头 tag 如 386 → false、非架构词 → unknown）+ Kleene 折叠。`//go:build linux && amd64` 在 riscv-scan 里只因同时含两词命中，语义上 `||`/`!` 组合会错判——这里按真值表走。

### D2: GOARCH 比较二值化语义

`runtime.GOARCH == "amd64"` → supportsRiscv=false；`!=` 翻转。架构取值闭集故二值合理（对照 cfg/build tag 的 unknown 通道：那边非架构词存在，这里比较对象必然是架构串）。走 selection_condition kind（区分于 build tag 的 preprocessor_condition）。

### D3: cgo 注释块双形态 + 逐行进 C 检测链

`import "C"` 的 prev_named_sibling 链收集紧邻注释：`//` 行注释逐条、`/* */` 块注释整段（后拆行——riscv-scan 只处理行注释，块注释是其实测漏报）。#include 行 → include、代码行 → inline_asm（复用 asm 词法器），与"喂 4 个 C solver"语义等价但走统一证据链。

### D4: _arch.go 文件名排除不做

riscv-scan 用 arch_file_pattern 跳过架构后缀文件（防 missing 检测误报）；我们的 architecture_family 词法层天然按 token 角色分桶（D2 已接），不需要文件名特判。

## 风险与缓解

- goreleaser 检测（solver 的第三块）本 change 未做——归入 Python change 的 build 通道微步（GORELEASER 输入类型），文件归属更合理。
