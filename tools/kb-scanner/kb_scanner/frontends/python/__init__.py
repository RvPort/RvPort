"""Python/Cython frontend 子包：.py（tree-sitter AST）与 .pyx（行正则）第七输入域。

检测点（规格源自 rvport-skills/riscv-scan solver_python/solver_cython，按
kb-scanner 合同重写）：ctypes/cffi 动态库加载（四类 import 状态机）、FFI
实例收集、cdef/set_source 内嵌 C 代码复扫（复用 asm 词法器进 C 检测链）。
"""
