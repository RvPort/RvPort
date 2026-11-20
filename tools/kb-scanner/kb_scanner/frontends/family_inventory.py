"""Attach authorized path observations, never synthetic source Evidence.

家族清单后处理器：在 frontend 产出的 source_file Entity 上补充"路径观察"
（Observation，而非 Evidence），只描述扫描器看到的授权文件清单状态，
不制造任何合成源码证据。
"""
from dataclasses import replace

from ..core import CompletionStatus, StageResult


def annotate_inventory(result, workspace, context):
    """为清单中的 source_file Entity 附加 pathObservation 属性。

    - result: 阶段结果，value 为家族清单快照（entities 元组）
    - workspace: 工作区快照，files 为授权文件集合
    - 返回新的 StageResult；原 Entity 不可变，用 dataclasses.replace 复制修改。
    - parseStatus 随阶段完成度映射：COMPLETE→complete，否则→unknown（五态之一）。
    """
    if result.value is None:
        return result
    authorized = frozenset(workspace.files)
    entities = []
    for entity in result.value.entities:
        # 只处理出现在授权文件清单中的 source_file，其余 Entity 原样保留。
        if entity.kind == "source_file" and entity.name in authorized:
            entity = replace(entity, properties={**entity.properties, "pathObservation": {
                "path": entity.name, "scope": "authorized-file-list",
                "parseStatus": "unknown" if result.status != CompletionStatus.COMPLETE else "complete",
                "excludedNames": context.excludes, "excludedPaths": context.exclude_paths,
            }})
        entities.append(entity)
    return StageResult(replace(result.value, entities=tuple(entities)), result.status, result.diagnostics)
