"""analyzer 阶段实现包：把 normalizer 产出的事实转换为证据图（实体/关系/证明路径）。

本包只做 re-export 门面，对外暴露证据图分析、构建选择分析、测试资产分析、
证明预算与宏定义解析等公共入口。
"""
from .evidence_graph import EvidenceGraphAnalyzer, GraphEntityIndexer, IncludeResolver, RelationBuilder
from .build_selection import BuildProofBudgets, BuildSelectionAnalyzer
from .test_selection import TestAssetAnalyzer, TestProofBudgets, TestSelectionAnalyzer
from .proof import ProofBudgets, ProofPathResolver
from .macro_resolution import MacroDefinitionResolver, MacroResolution

__all__ = [
    "EvidenceGraphAnalyzer",
    "BuildProofBudgets",
    "BuildSelectionAnalyzer",
    "TestAssetAnalyzer",
    "TestProofBudgets",
    "TestSelectionAnalyzer",
    "GraphEntityIndexer",
    "IncludeResolver",
    "ProofBudgets",
    "ProofPathResolver",
    "RelationBuilder",
    "MacroDefinitionResolver",
    "MacroResolution",
]
