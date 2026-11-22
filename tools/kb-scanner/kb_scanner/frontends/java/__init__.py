"""Java frontend 子包：.java 文件的第六输入域（tree-sitter-java AST）。

检测点（规格源自 rvport-skills/riscv-scan solver_java，按 kb-scanner 合同重写）：
JNI 原生库加载（System/Native.load[Library]、Runtime.getRuntime() 链式、
已收集 Runtime 变量的链式调用）与 os.arch 属性比较。库名实参单发
declaration_attribute 证据走 token 检测（architectureAliases 词表）。
"""
