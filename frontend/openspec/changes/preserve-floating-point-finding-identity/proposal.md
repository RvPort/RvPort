## Why

`555edea` 中的 `reduction-reassociation@1.1` 按独立循环与归约候选生成 Finding。前端须维持结果身份，不按规则、文件或编译单元重新合并，也不能拆分历史 Finding。

## What Changes

- 明确每条 Finding 的位置、证据、置信度与状态独立展示。
- 保留单条历史 Finding 的 occurrences 切换；单位置直接显示文件与行号。
- 补列表至详情的回归测试，不为已经正确的 ID 行标识增加合并或拆分逻辑。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `migration-risk-analysis-stages`：明确浮点 Finding 身份与位置切换规则。

## Impact

仅 `frontend/`。结果协议继续为 schema v2，不修改后端和分析器。2026-10-30 用户追加“验证收尾”后，本变更的回归随整个前端的 Docker 格式、lint、类型检查、1,335 个普通单元测试与生产构建通过。不执行部署和真实扫描；本 change 保留为独立活动记录。
