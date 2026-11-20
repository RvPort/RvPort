"""有界函数多版本静态分析。

function_multiversion 子包的门面，不直接导出符号；实际入口是
analyzer.FunctionMultiversionAnalyzer（由上层 analyzers 包统一 re-export）。
该分析器识别 target/target_clones 等函数多版本属性，把声明分组成
函数版本组，属于 analyzer 阶段的非执行性分析。
"""
