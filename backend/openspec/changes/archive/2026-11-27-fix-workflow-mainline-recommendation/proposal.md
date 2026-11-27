## Why

overview 当前先取任意运行中的阶段，再按 `workflow.stages` 的展示顺序选择阶段，导致支线分析抢占已经就绪的结果评估；报告生成成功也未表达后续人工复核和清单确认的进度。需要按当前有效业务事实计算主线导航，让用户沿「项目基础配置 → 源码管理 → 知识库扫描 → 结果评估 → 迁移修复」推进。

## What Changes

- 建立独立于阶段排列的推荐策略。结果评估具备有效输入即可优先推荐，支线处于可执行、运行或失败状态均不抢占，不新增 KB、环境或 x86 构建硬前置。
- 区分报告生成、待复核、未确认、需重新确认、有效非空确认清单及有效空清单；复用当前 Context、报告发布状态与确认资格判断，历史成功不冒充当前完成。
- 保留阶段状态、独立执行条件及构建验证 → 自动化测试顺序；推荐是导航焦点，不代表执行授权。空清单免除修复，不生成虚假的构建或测试成功事实。
- 更新 overview 推荐契约及前端旧 KB → x86 覆盖逻辑的对接说明；本 change 不修改前端。
- 补齐单元测试、整体及受影响模块至少 90% 行/分支覆盖率门禁、独立本地 Docker 和完整 Compose 验收任务。

## Capabilities

### New Capabilities

- `workflow-mainline-recommendation`：当前有效主线的确定性推荐、报告复核状态分流、支线隔离、后续验证顺序及 overview 对接契约。

### Modified Capabilities

- `project-source-environment`：环境缺失不再无条件覆盖主线推荐；仅当主线当前操作确实依赖缺失环境时推荐环境管理，环境摘要及配置接口保持原语义。

## Impact

- 后端主要涉及 `ProjectWorkflowStatusService`、独立推荐策略、报告复核摘要读取与 `ProjectOverviewResponse` 的兼容性增量字段，以及相应测试和接口文档。
- `GET /api/projects/{projectId}/overview` 的 `workflow.recommendedStageId` 改为业务导航推荐；保留阶段 ID、展示顺序、状态枚举、`canStart` 和 `currentFixList` 既有字段。
- 不新增数据库持久化推荐状态，不修改模型绑定、扫描器算法、独立阶段执行 API 或个人权限边界。
- 仅 backend 独立 OpenSpec 根和后端实现范围；保持 `dev`，不修改前端、工具源码或远程环境，不操作已有本地部署数据。
- 本阶段只编写规划文档。实施验证必须使用届时的实际源码和独立验收资源，不复用历史报告冒充本 change 验证结果。
