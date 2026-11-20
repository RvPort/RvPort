# ai-migration-runtime delta

## ADDED Requirements

### Requirement: 容器执行必须保持既有 workspace 完整性合同

后端 SHALL 在隔离 workspace 内组装 `input/scan_result.json`、`input/manifest.json`、按投影涉及文件物化的 `source/` 与技能装载，并在独立执行器容器内执行；执行前后 SHALL 对输入文件做 hash 校验；workspace 在产物入库后 SHALL 清理。

#### Scenario: 只物化涉及文件
- **WHEN** 组装 source 树
- **THEN** SHALL 仅物化投影条目引用的文件，缺失文件计入 manifest 且不中断
- **AND** 物化路径 MUST 经服务端路径校验且不跟随符号链接逃逸

#### Scenario: 输入被篡改
- **WHEN** 执行后输入文件 hash 与执行前不一致
- **THEN** SHALL 以业务错误失败并保留日志证据

#### Scenario: stdout 合同
- **WHEN** agent 执行完成
- **THEN** 最终回复 SHALL 仅一行 `DONE` 或 `FAIL: <原因>`，全部结果 MUST 写入 `output/` 文件
