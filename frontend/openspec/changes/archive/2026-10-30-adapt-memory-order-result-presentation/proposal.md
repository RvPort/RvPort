## Why

内存序结果协议 3.0 将源码候选、证据不足、受限消解和正式风险区分，并提供诊断汇总、证据图和完整性事实。前端原有通用风险展示会混淆候选与风险、沿用错误源码或忽略部分分析。

## What Changes

- 同时消费 2.0/3.0，按 kind/verdict 保守分类，空置信度为未评估。
- 展示诊断、分析前沿、截断和下载，保留历史未知状态。
- 按主路径与 ID 解析证据图，独立展示分支、断点、结构异常和预算。
- 内存序源码片段绑定位置及 digest，无源码步骤不借用其他片段。
- Workflow 与报告表达部分分析和待核实项；人工纳入不升级结论。
- 补适配器与组件测试，并保留浮点和知识库行为。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `migration-risk-analysis-stages`：内存序结果协议、诊断、证据链与报告输入语义。
- `project-pipeline-workbench`：保留 DONE，同时提示部分分析完成。
- `assessment-report`：独立展示待核实项及内存序分析局限。

## Impact

仅 `frontend/`，不修改后端、分析器、运行环境或根目录 Compose。用户追加“验证收尾”后，在隔离 Docker 容器中完成格式检查、lint、类型检查、全量普通单元测试与生产构建；实际命令、结果及未验证范围见 [验证记录](verification.md)。不执行部署、真实扫描或覆盖率统计。
