"""kb-scanner 包根：C/C++ 代码 x86→RISC-V 迁移静态扫描器。

八阶段流水线：workspace→knowledge→frontend→normalizer→analyzer→detector→
classifier→assessor→projector。本包按层组织：core（数据合同）、
knowledge/pipeline/analyzers/detectors/classifiers/assessors（各阶段实现）、
adapters 与 projection（适配与输出投影）。
"""

__version__ = "0.1.0"

