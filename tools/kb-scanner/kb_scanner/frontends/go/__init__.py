"""Go frontend 子包：.go 文件的第五输入域（tree-sitter-go AST）。

检测点（规格源自 rvport-skills/riscv-scan solver_go，按 kb-scanner 合同重写）：
`//go:build` 文件头构建约束（真表达式求值，三值语义同 Rust cfg）、
`runtime.GOARCH` 比较/switch 分支、cgo 注释块（import "C" 上方 C 代码
逐行转 C 检测链证据）。`_arch.go` 文件名排除不做——architecture_family
词法层天然处理。
"""
