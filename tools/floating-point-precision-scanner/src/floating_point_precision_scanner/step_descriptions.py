"""从本次扫描的直接证据生成有界双语说明，不读取文件或外部服务。"""

from __future__ import annotations

import re
from collections.abc import Mapping, Sequence
from typing import Any

from .canonical import canonical_json, semantic_digest

_KINDS = {
    "sourceFact": ("源码观测", "Source observation"),
    "irFact": ("LLVM 指令观测", "LLVM instruction observation"),
    "binding": ("源码与 IR 对应", "Source-to-IR binding"),
    "profile": ("浮点目标模型", "Floating-point target model"),
    "buildContext": ("构建环境", "Build context"),
    "compileOption": ("浮点编译选项", "Floating-point compile options"),
    "assessment": ("迁移风险判断", "Migration risk assessment"),
}
_FACTS = {
    "reduction": ("浮点归约累加", "Floating-point reduction"),
    "comparison": ("浮点数值比较", "Floating-point comparison"),
    "arithmetic": ("浮点算术运算", "Floating-point arithmetic"),
    "conversion": ("数值类型转换", "Numeric conversion"),
    "call": ("函数调用", "Function call"),
    "type": ("浮点类型声明", "Floating-point type declaration"),
    "api": ("浮点接口调用", "Floating-point API call"),
    "environment": ("浮点环境操作", "Floating-point environment operation"),
    "function_summary": ("函数浮点环境摘要", "Function floating-point environment summary"),
    "literal": ("浮点常量", "Floating-point literal"),
}
_REASONS = {
    "abi_difference": (
        "源码涉及 long double ABI 边界，源与目标模型的布局或调用约定不同。",
        "Source uses a long double ABI boundary; source and target layouts or conventions differ.",
    ),
    "excess_precision_difference": (
        "源端使用扩展中间精度，目标求值模型不同，需验证舍入差异。",
        "Source uses excess intermediate precision; the target evaluation model differs.",
    ),
    "contraction_difference": (
        "融合乘加或收缩行为存在差异，可能改变舍入次数。",
        "Fused multiply-add or contraction behavior differs and may change rounding steps.",
    ),
    "environment_option_conflict": (
        "浮点环境操作与编译选项存在冲突，需检查舍入模式或异常行为。",
        "Floating-point environment operations conflict with rounding or exception options.",
    ),
    "implicit_precision_loss": (
        "隐式数值转换存在精度损失风险，需检查类型范围和有效精度。",
        "Implicit numeric conversion risks precision loss; review range and precision.",
    ),
    "nan_equality_semantics": (
        "比较涉及 NaN 的相等语义，需验证条件判断是否符合预期。",
        "The comparison involves NaN equality semantics; verify the intended condition.",
    ),
    "rounding_sensitive_comparison": (
        "比较可能受舍入误差影响，业务容差仍需人工确认。",
        "The comparison may be rounding-sensitive; business tolerance needs review.",
    ),
    "libm_semantics_differ": (
        "源与目标构建上下文声明的数学库语义不同，需验证相关函数。",
        "Source and target build contexts declare different math-library semantics.",
    ),
    "x86_api_on_riscv_target": (
        "源码使用 x86 特定浮点接口，需确认 RISC-V 上的可移植实现。",
        "Source uses an x86-specific floating-point API; review its RISC-V implementation.",
    ),
    "order_sensitive_reduction": (
        "归约结果可能受累加顺序影响；尚无 IR 重排证明。",
        "Reduction may depend on accumulation order; no IR reassociation proof.",
    ),
    "verified_reassociation": (
        "IR 存在归约或允许重排的证据，需验证误差容限。",
        "IR has reduction or reassociation evidence; validate error tolerances.",
    ),
    "unsafe_semantics_active": (
        "检测到放宽浮点语义的选项，需验证特殊值和舍入行为。",
        "Relaxed floating-point options require special-value and rounding tests.",
    ),
    "option_ir_conflict": (
        "编译选项与 IR 证据不一致，无法作确定判断。",
        "Compile options conflict with IR evidence; a definite assessment is unavailable.",
    ),
}


def _text(value: Any, limit: int = 240) -> str:
    text = " ".join((canonical_json(value) if isinstance(value, Mapping | list | tuple)
                     else str(value)).split())
    # Never copy absolute paths, credentials, or arbitrary command lines into display prose.
    text = re.sub(
        r"(?i)(?<![\w.])(?:[a-z][a-z0-9+.-]*://\S+|(?:[A-Z]:[\\/]|/|\.\.[/\\])\S+)",
        "<redacted>",
        text,
    )
    text = re.sub(r"(?i)\b(?:password|token|secret|api[_-]?key)\s*[=:]\s*\S+", "<redacted>", text)
    return text if len(text) <= limit else text[: limit - 3] + "..."


def _fields(value: Mapping[str, Any], names: Sequence[str]) -> str:
    return "; ".join(
        f"{name}={_text(value[name])}"
        for name in names
        if name in value and value[name] is not None
    )


def _position(value: Any) -> str:
    if not isinstance(value, Mapping):
        return ""
    point = value.get("expansion") or value.get("spelling") or value
    path = str(point.get("path", "")).replace("\\", "/")
    if not path or path.startswith("/") or re.match(r"^[A-Za-z]:", path) or ".." in path.split("/"):
        return ""
    return f"{_text(path)}:{_text(point.get('line', '?'))}"


class StepDescriptions:
    def __init__(
        self,
        facts: Sequence[Mapping[str, Any]] = (),
        ir: Sequence[Mapping[str, Any]] = (),
        evidence: Sequence[Mapping[str, Any]] = (),
        contexts: Sequence[Mapping[str, Any]] = (),
        assessments: Sequence[Mapping[str, Any]] = (),
    ) -> None:
        self.index: dict[tuple[str, str], Mapping[str, Any]] = {}
        for kind, bundles in (("sourceFact", facts), ("irFact", ir)):
            for bundle in bundles:
                for fact in bundle.get("facts", []):
                    self.index[kind, str(fact["fact_id"])] = fact
        for bundle in facts:
            options = bundle.get("effective_fp_options", {})
            self.index["compileOption", semantic_digest(options)] = options
        for bundle in evidence:
            for binding in bundle.get("bindings", []):
                self.index["binding", str(binding["binding_id"])] = binding
        for item in contexts:
            self.index[str(item["kind"]), str(item["fingerprint"])] = item
        for item in assessments:
            self.index["assessment", str(item["id"])] = item

    def describe(self, kind: str, identity: str) -> dict[str, Any]:
        zh, en = _KINDS.get(kind, ("证据观测", "Evidence observation"))
        value = self.index.get((kind, identity))
        if not value:
            return self._result(
                zh,
                en,
                f"引用类型 {_text(kind)} 缺少可解释的事实内容，无法补全判断依据。",
                f"Reference type {_text(kind)} lacks interpretable facts; "
                "rationale is unavailable.",
                "unknown",
                ["step_description_unavailable"],
            )
        status = "complete"
        codes: list[str] = []
        if kind == "sourceFact":
            fact_kind = str(value.get("fact_kind", "unknown"))
            zh, en = _FACTS.get(
                fact_kind, (f"源码事实：{_text(fact_kind)}", f"Source fact: {_text(fact_kind)}")
            )
            position = _position(value.get("location"))
            payload = value.get("payload", {})
            details = _fields(value, ("type", "function_symbol"))
            details += "; " + _fields(
                payload,
                (
                    "opcode",
                    "operation",
                    "reduction_kind",
                    "loop_kind",
                    "source_type",
                    "destination_type",
                    "callee",
                    "compensation",
                    "fixed_order",
                    "iteration_count",
                    "ambiguous",
                    "fenv_action",
                    "actions",
                    "unknown_effect",
                ),
            )
            reason = (
                f"在 {position or '未知位置'} 观察到{zh}。{details}。"
                "这是源码证据，不代表迁移必然失败。"
            )
            reason_en = (
                f"Observed {en} at {position or 'unknown location'}. {details}. "
                "This is source evidence, not proof of migration failure."
            )
            if (
                payload.get("ambiguous")
                or payload.get("provenance_ambiguous")
                or payload.get("unknown_effect")
            ):
                status, codes = "unknown", ["source_semantics_ambiguous"]
                reason += " 存在歧义或未知影响，不能视为完整证明。"
                reason_en += " Ambiguity or unknown effects prevent a complete proof."
        elif kind == "irFact":
            opcode = _text(value.get("opcode", "unknown"))
            zh, en = f"LLVM 浮点指令：{opcode}", f"LLVM floating-point instruction: {opcode}"
            details = _fields(
                value,
                (
                    "function",
                    "opcode",
                    "result_type",
                    "operand_types",
                    "fast_math_flags",
                    "callee",
                    "rounding_mode",
                    "exception_behavior",
                ),
            )
            position = _position(value.get("debug_location"))
            reason = (
                f"在 {position or '未知位置'} 记录指令属性：{details}。未记录的属性不能推断为启用。"
            )
            reason_en = (
                f"Recorded instruction at {position or 'unknown location'}: {details}. "
                "Unrecorded attributes are not assumed enabled."
            )
        elif kind == "binding":
            details = _fields(value, ("method", "confidence", "relation"))
            reason, reason_en = (
                f"依据 {details} 关联源码事实与 IR 事实。",
                f"Source and IR facts are linked using {details}.",
            )
        elif kind == "profile":
            details = _fields(value, ("profile", "target_triple", "abi"))
            model = _fields(
                value.get("long_double", {}), ("format", "precision_bits", "storage_bits")
            )
            details += "; " + _fields(
                value, ("evaluation", "fma", "environment", "target_features")
            )
            reason = (
                f"引用目标模型 {details}；long double：{model or '未提供布局'}。"
                "用于比较浮点语义，不代表实机测试结果。"
            )
            reason_en = (
                f"Referenced model {details}; long double: {model or 'layout unavailable'}. "
                "Used for semantic comparison, not a runtime test result."
            )
        elif kind == "buildContext":
            details = _fields(value, ("context_id", "target_triple", "abi"))
            details += "; " + _fields(value, ("libm", "floating_point_environment"))
            reason, reason_en = (
                f"引用构建配置 {details}；仅使用已声明的能力。",
                f"Referenced build configuration {details}; only declared capabilities apply.",
            )
        elif kind == "compileOption":
            details = _fields(value, tuple(sorted(value)))
            reason = f"本次编译的有效浮点选项：{details}。需结合 IR 和数值测试判断实际影响。"
            reason_en = (
                f"Effective floating-point compile options: {details}. "
                "Assess impact with IR and numerical tests."
            )
        elif kind == "assessment":
            code = str(value.get("reasonCode", "unknown"))
            state = str(value.get("status", "unknown"))
            rule = _text(value.get("ruleCode", "unknown"))
            zh, en = f"规则判断：{rule}", f"Rule assessment: {rule}"
            explanation = _REASONS.get(
                code,
                (
                    "请结合直接证据审查该规则判断。",
                    "Review this rule assessment with its direct evidence.",
                ),
            )
            reason = (
                f"规则 {rule} 的评估状态为 {_text(state)}，依据代码 {_text(code)}。{explanation[0]}"
            )
            reason_en = (
                f"Rule {rule} assessed as {_text(state)}; "
                f"reason code {_text(code)}. {explanation[1]}"
            )
            if state not in {"matched", "notMatched"}:
                status, codes = "unknown", ["assessment_not_complete"]
        else:
            reason, reason_en = (
                "该引用暂不支持语义解释，保留原始证据供审查。",
                "Semantic explanation is unsupported; inspect the original evidence.",
            )
            status, codes = "unknown", ["step_description_unavailable"]
        return self._result(zh, en, reason, reason_en, status, codes)

    @staticmethod
    def _result(
        title: str,
        title_en: str,
        reason: str,
        reason_en: str,
        status: str = "complete",
        codes: list[str] | None = None,
    ) -> dict[str, Any]:
        return {
            "title": _text(title, 512),
            "titleEn": _text(title_en, 512),
            "reason": _text(reason, 4096),
            "reasonEn": _text(reason_en, 4096),
            "status": status,
            "reasonCodes": codes or [],
        }

    @staticmethod
    def conclusion(finding: Mapping[str, Any]) -> dict[str, Any]:
        limited = bool(finding.get("semantic", {}).get("location_budget_exceeded"))
        return StepDescriptions._result(
            str(finding["title"]),
            str(finding["title_en"]),
            str(finding["message"]) + (" 位置输出达到预算，部分位置未展示。" if limited else ""),
            str(finding["message_en"])
            + (" Location budget reached; some locations are omitted." if limited else ""),
            "partial" if limited else "complete",
            ["location_budget_exceeded"] if limited else [],
        )
