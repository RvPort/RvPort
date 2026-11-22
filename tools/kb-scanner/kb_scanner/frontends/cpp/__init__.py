"""C/C++ 前端子包的对外门面：仅做 re-export，不承载实现逻辑。

kb-scanner 八阶段流水线中的 frontend 阶段由本包实现，负责把 C/C++ 源码
解析为实体（Entity）与证据（Evidence），供后续 normalizer/analyzer 消费。
"""
from .arguments import ClangArgumentResult, normalize_clang_arguments
from .clang_adapter import ClangCppAdapter
from .compilation_database import CompilationDatabaseReader, compilation_context_id
from .frontend import CppFrontend
from .tree_sitter_adapter import TreeSitterCppAdapter
from .vocabulary import CPP_EVIDENCE_KINDS, make_source_file_entity
from .workspace import CppWorkspaceCollector

__all__ = [
    "CPP_EVIDENCE_KINDS",
    "ClangArgumentResult",
    "ClangCppAdapter",
    "CompilationDatabaseReader",
    "CppFrontend",
    "CppWorkspaceCollector",
    "TreeSitterCppAdapter",
    "compilation_context_id",
    "make_source_file_entity",
    "normalize_clang_arguments",
]
