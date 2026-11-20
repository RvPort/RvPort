"""宏定义解析（analyzer 阶段）：从候选宏定义实体中选出对某次调用可见的定义。

按可见文件 → 编译配置 → 包含顺序 → 同文件位置逐级过滤，最终唯一或
各定义等价时给出 complete，多于一个且不等价时给出 partial（歧义）。
"""
from __future__ import annotations

from dataclasses import dataclass
from collections.abc import Mapping

from ..core import Entity, Evidence


@dataclass(frozen=True, slots=True)
class MacroResolution:
    """宏解析结果（frozen 不可变）：候选定义元组 + 五态语义的状态。

    status: complete（唯一/等价定义）、partial（多个不等价定义，歧义）、
    unknown（无可行候选）。reason 为非 complete 时的原因码。
    """
    definitions: tuple[Entity, ...]
    status: str
    reason: str | None = None


class MacroDefinitionResolver:
    """宏定义解析器：analyzer 阶段用于消解宏调用的定义归属。

    依赖两张外部映射：_definition_file（定义实体 ID → 所在文件）与
    _evidence（证据 ID → Evidence）。不做任何预处理执行，仅静态过滤。
    """
    __slots__ = ("_definition_file", "_evidence")

    def __init__(self, definition_file: Mapping[str, str], evidence: Mapping[str, Evidence]) -> None:
        self._definition_file = definition_file
        self._evidence = evidence

    def resolve(
        self,
        invocation: Evidence,
        candidates: tuple[Entity, ...],
        visible_files: set[str],
    ) -> MacroResolution:
        """解析对给定调用（invocation）生效的宏定义。

        过滤漏斗（逐级收窄，空则保持上一级结果）：
        1) 定义所在文件 ∈ 调用点可见文件集；
        2) 编译配置（configuration/compilationContextId）一致或定义无配置；
        3) 定义包含顺序 ≤ 调用顺序（无顺序信息的定义放行）；
        4) 同文件且定义位置不晚于调用位置时，取最晚的定义（就近屏蔽）。
        最终按实体 ID 排序去歧义：唯一或互相等价 → complete；
        多个不等价 → partial；为空 → unknown。
        """
        considered = tuple(item for item in candidates if self._definition_file.get(item.id) in visible_files)
        invocation_configuration = _configuration(invocation)
        if invocation_configuration:
            matching = tuple(
                item
                for item in considered
                if (definition := self._definition_evidence(item)) is not None
                and _configuration(definition) in {None, invocation_configuration}
            )
            if matching:
                considered = matching
        invocation_order = _order(invocation)
        if invocation_order is not None:
            considered = tuple(
                item
                for item in considered
                if (definition := self._definition_evidence(item)) is not None
                and (_order(definition) is None or _order(definition) <= invocation_order)
            )
        same_file = tuple(
            item
            for item in considered
            if self._definition_file.get(item.id) == invocation.subject_id
            and (definition := self._definition_evidence(item)) is not None
            and definition.location is not None
            and invocation.location is not None
            and (
                definition.location.start.line,
                definition.location.start.column or 0,
            ) <= (
                invocation.location.start.line,
                invocation.location.start.column or 0,
            )
        )
        if same_file:
            latest = max(
                (
                    self._definition_evidence(item).location.start.line,
                    self._definition_evidence(item).location.start.column or 0,
                )
                for item in same_file
            )
            considered = tuple(
                item
                for item in same_file
                if (
                    self._definition_evidence(item).location.start.line,
                    self._definition_evidence(item).location.start.column or 0,
                ) == latest
            )
        considered = tuple(sorted(considered, key=lambda item: item.id))
        if not considered:
            return MacroResolution((), "unknown", "unresolved-macro")
        if len(considered) == 1 or _equivalent(considered, self._evidence):
            return MacroResolution(considered, "complete")
        return MacroResolution(considered, "partial", "ambiguous-macro-definition")

    def _definition_evidence(self, entity: Entity) -> Evidence | None:
        """取定义实体的 sourceEvidenceId 对应证据；无则返回 None。"""
        evidence_id = entity.properties.get("sourceEvidenceId")
        return self._evidence.get(evidence_id) if isinstance(evidence_id, str) else None


def _configuration(evidence: Evidence) -> str | None:
    """读取证据的编译配置标识（compilationContextId 优先于 configuration）。"""
    value = evidence.metadata.get("compilationContextId") or evidence.metadata.get("configuration")
    return value if isinstance(value, str) and value else None


def _order(evidence: Evidence) -> int | None:
    """读取证据的包含/定义顺序（非负整数；bool 不算），无则 None。"""
    value = evidence.metadata.get("includeOrder") or evidence.metadata.get("definitionOrder")
    return value if isinstance(value, int) and not isinstance(value, bool) and value >= 0 else None


def _equivalent(definitions: tuple[Entity, ...], evidence: Mapping[str, Evidence]) -> bool:
    """判断多个定义是否等价：忽略配置/路径类 metadata 后，值与元数据
    签名完全一致（只剩一种签名）即等价——视为同一有效定义。"""
    signatures = set()
    for definition in definitions:
        evidence_id = definition.properties.get("sourceEvidenceId")
        item = evidence.get(evidence_id) if isinstance(evidence_id, str) else None
        if item is None:
            return False
        metadata = {
            key: value
            for key, value in item.metadata.items()
            if key not in {"compilationContextId", "sourcePath", "sourceFileId"}
        }
        signatures.add((str(item.value), repr(sorted(metadata.items()))))
    return len(signatures) == 1
