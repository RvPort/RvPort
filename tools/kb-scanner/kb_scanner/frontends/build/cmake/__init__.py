"""Bounded CMake source analysis; no CMake execution.

CMake 前端子包：syntax 降级 tree-sitter-cmake 节点、values 提供
三值表达式、project 做项目级静态求值、usage 绑定 usage requirement、
test_registration 恢复测试注册，全程不执行 CMake。
"""
