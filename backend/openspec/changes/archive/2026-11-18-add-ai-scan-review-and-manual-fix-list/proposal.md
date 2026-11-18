## Why

现有评估报告从已确认的迁移输入生成，只提供静态报告与有限源码阅读，无法完整支持四类分析的逐项 AI 复核、人工补漏和最终清单确认。需要在现有评估报告阶段建立报告事实、复核草稿与确认快照的清晰边界，使历史可追溯、当前状态一致，并防止未确认或失效清单进入后续修复流程。

## What Changes

- **BREAKING**：报告生成改为消费当前源码及有效上下文下四类成功分析的固定输入，不再由旧 `CONFIRMED` 迁移输入决定。至少一项有效成功运行即可生成，零问题允许生成，完全没有有效分析则拒绝。
- 知识库含低风险 Finding，浮点含有效 Finding，内存序保留正式风险与待核实候选语义；依赖仅纳入尚未解决且风险中及以上或结论未知的问题。已解决、明确低风险和提示依赖不进入问题输入，但保留原分析记录。
- 普通和 AI 增强模式复用报告生成入口。AI 使用触发者个人 `REPORT_AI_REVIEW` 阶段绑定；AI 不完整成功不得发布增强成功，不自动降级普通模式。
- 无继承的扫描项按本次结论初始化，AI 确认存在问题进入待修复，疑似误报、证据不足及普通报告进入待复核；新增持久化草稿、单次/批量统一处置接口、从已知源码框选片段新增人工待修复项、不可变清单确认快照。待复核不为零或草稿版本冲突时拒绝确认，允许确认空清单。
- 同版本且问题身份、依据、位置无实质变化时继承人工决定；人工项要求片段可验证定位；新报告仍需重新确认。旧报告、原始输入、AI 结论、草稿历史及确认快照不被覆盖。
- 固定源码、分析运行、问题、继承基线与执行身份；防重复提交、并发覆盖和迟到发布。统一生成资格、当前有效报告、最近任务、草稿确认、overview/latest/readiness 和下游消费语义。
- 文件树仅包含绑定版本的已知源码文件，包含零发现文件；依赖或未定位问题保留在全局列表，不伪造文件节点。保留受控 HTML/JSON 下载及历史模块—文件—Finding 阅读。
- **BREAKING**：退役旧知识库 AI 建议入口及动态结果附加，删除旧阶段绑定及其兼容路径，按全新初始化方案处理开发数据与任务队列，不搬迁旧记录。删除旧节点内确认读写入口和仅服务旧流程的字段，不保留过渡响应。
- 形成可评审接口契约、业务基线逐项映射和本地联调夹具计划；执行阶段要求 Docker 单元测试、整体及受影响模块行/分支各至少 90% 和完整本地 Compose 验收。

## Capabilities

### New Capabilities

- `report-ai-review`：有效来源、依赖准入、个人 Agent 复核、完整结果验证和执行隔离。
- `report-review-workspace`：已知源码浏览、人工问题、持久草稿、统一批量操作、不可变确认和下游消费。
- `report-review-transition`：任务幂等、当前报告切换、重新生成继承、旧功能删除、全新初始化及本地验收。

### Modified Capabilities

- `migration-report-runtime`：四类输入快照、普通/AI 模式、生成资格、历史与下载语义。
- `migration-input-merge`：旧生成前确认不再控制报告，删除旧 reference 读写接口及仅为旧流程保留的数据依赖。
- `node-local-migration-input-confirmation`：停止以节点确认或默认风险规则决定新报告输入和最终清单。
- `migration-input-refresh`：结果更新不清除人工审计，来源有效性与报告草稿分离。
- `kb-ai-extension-runtime`：旧知识库 AI 扩展退役，删除完整旧 API/任务/制品读取链。
- `model-profile-management`：以个人 `REPORT_AI_REVIEW` 绑定替代 `KB_AI_EXTENSION`。
- `backend-api-design`：报告及复核接口、当前状态、旧写入口边界和联调契约。
- `backend-database-design`：输入 manifest、草稿版本、操作记录、确认快照及原子发布约束。
- `report-comparison-runtime`：保留原报告比较，明确处置差异不等于代码修复、确认快照比较与空集合。
- `manual-fix-source-update-flow`：后续修复依据当前确认清单，旧版只读及空清单语义。
- `optimization-cycle-runtime`：聚合当前清单有效性，不将 AI 完成或空清单当成验证链完成。

## Impact

- 唯一 OpenSpec 根与实施范围为 `backend/`；本 change 路径为 `backend/openspec/changes/add-ai-scan-review-and-manual-fix-list/`。根目录只用于完整 Compose 验收，不创建根级、frontend 或 tools change。
- 影响 report/runtime、migration、kb/ai、analysis/依赖结果读取策略、modelprofile、workflow/overview、task/worker、reportcomparison、source/recheck、optimization、archive，以及数据库迁移、生成 jOOQ、单元测试与后端对接文档。
- 复用现有受管 Agent、个人身份、任务/SSE、对象存储、路径安全及 Compose 服务；不更新 CLI 版本、不增加 Compose 基础设施、不新增自动修改源码功能。
- 保持 dev，保留用户已有未提交文件；不修改前端、不测试或修改远程 dev/test。本轮只创建提案，不实现、不 sync/archive、不 commit/push。
- 业务依据见 `business-baseline.md`；设计、契约、映射分别见 `design.md`、`api-contract.md`、`requirements-mapping.md`。所有项目文件路径相对于仓库根目录，API 路径不受此约定影响。

## 开发阶段数据策略

本次允许抛弃变更前的开发数据，不设计旧数据迁移、旧绑定搬迁、旧任务续跑或旧接口兼容层。切换采用停止旧程序并重建指定开发实例数据存储的方式；本轮仅更新方案，不清空任何实际数据。新流程上线后产生的报告、人工决定和确认版本仍须完整保留历史、只读追溯和固定下载。本变更仅处理相关后端遗留路径，不借此重构无关功能。
