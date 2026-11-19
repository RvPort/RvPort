# backend-api-design delta

## ADDED Requirements

### Requirement: KB 迁移 API 必须提供创建、列表与详情契约
后端 SHALL 提供 `POST /api/projects/{projectId}/kb-migrations` 创建迁移运行、`GET /api/projects/{projectId}/kb-migrations` 分页列表与 `GET /api/kb-migrations/{runId}` 详情；响应 SHALL 包含状态、条目分类计数、产物摘要与任务引用；拒绝原因 SHALL 中英双语。

#### Scenario: 创建成功
- **WHEN** 已认证项目成员在资格满足时发起 KB 迁移
- **THEN** SHALL 返回运行与任务标识，任务进入队列

#### Scenario: 创建拒绝
- **WHEN** 无有效扫描、零可投影条目或阶段未绑定
- **THEN** SHALL 返回业务错误与双语原因，不投递任务

#### Scenario: 越权访问
- **WHEN** 未授权用户访问运行详情或产物
- **THEN** SHALL 拒绝并返回统一错误，不泄露运行存在性
