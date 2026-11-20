"""汇编 frontend 子包：.s/.S/.asm 文件的第三输入域。

逐行词法扫描（无 asm 语法树依赖）：跳过 C 预处理指令行、剥离注释与标签，
每个剩余非空行产出一条 inline_asm 证据，下游复用既有
C_CPP.{X86,ARM64}.ASM_INSTRUCTION/ASM_REGISTER 检测器与 C/C++ 归一化链。
位置用行内列坐标（1-based）；行首字节偏移进 metadata 保证稳定 ID 可复现。
"""
