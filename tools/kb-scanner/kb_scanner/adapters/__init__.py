"""Infrastructure adapters for the active KB Scanner application boundary.

Legacy remains available from :mod:`kb_scanner.adapters.legacy` only as an
explicit test oracle.  Importing this package must not load it (or PyYAML) on
the production scan path.

适配器门面：当前生产边界使用的 CLI 适配器（上下文工厂与请求转换）。
注意 legacy 仅作为测试 oracle 显式导入，生产扫描路径不得加载它。
"""

from .cli import StagedContextFactory, scan_request_from_namespace

__all__ = ["StagedContextFactory", "scan_request_from_namespace"]
