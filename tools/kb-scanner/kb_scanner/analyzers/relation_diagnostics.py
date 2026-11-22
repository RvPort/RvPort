"""证据图分析器的关系级诊断工厂。

只提供 relation_diagnostic 一个入口：当 EvidenceGraphAnalyzer 保留了带原因的
部分关系（如 include 解析歧义、宏未解析）时，统一从这里构造 Diagnostic，
保证 stage/producer/details 的字段口径一致。
"""
from __future__ import annotations

from ..core import Diagnostic, DiagnosticSeverity, Evidence


def relation_diagnostic(
    code: str,
    evidence: Evidence,
    reason: str,
    relation_kind: str,
    *,
    severity: DiagnosticSeverity = DiagnosticSeverity.WARNING,
) -> Diagnostic:
    """构造"保留了部分关系"的 WARNING 诊断。

    参数：code 为诊断码（调用方加 graph. 前缀）；evidence 为触发证据（提供位置
    与 ID 溯源）；reason 为原因短语；relation_kind 为受影响的关系类型。
    返回 Diagnostic，不修改任何输入。
    """
    return Diagnostic(
        code=code,
        stage="analyzer.evidence-graph",
        severity=severity,
        message=f"Evidence Graph retained partial relation: {reason}",
        location=evidence.location.start if evidence.location else None,
        producer="evidence-graph-analyzer",
        details={"evidenceId": evidence.id, "reason": reason, "relationKind": relation_kind},
    )
