"""构建系统 frontend：Make/CMake/Autoconf/RPM 等构建脚本的有界静态分析。

包门面，re-export BuildFrontend 与工作区收集工具；各构建系统的具体
解析器位于 adapters / make / cmake / autoconf / rpm 子模块。
"""
from .frontend import BuildFrontend
from .workspace import ProjectWorkspaceCollector, build_input_kind, is_build_input

__all__ = ["BuildFrontend", "ProjectWorkspaceCollector", "build_input_kind", "is_build_input"]
