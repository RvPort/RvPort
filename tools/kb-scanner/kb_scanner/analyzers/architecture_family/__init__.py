"""architecture_family 分析器子包的门面：对外只导出 ArchitectureFamilyAnalyzer。

该分析器位于 analyzer 阶段，基于文件路径的架构词法模板（如 x86/riscv 目录槽与
文件名前后缀）聚类出"架构家族"候选目录，属于非执行性的候选关系，不构成任何
等价或执行证明。
"""
from .analyzer import ArchitectureFamilyAnalyzer

__all__ = ["ArchitectureFamilyAnalyzer"]
