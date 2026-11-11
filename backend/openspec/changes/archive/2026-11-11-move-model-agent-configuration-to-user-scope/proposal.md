## Why

当前模型 profile、密钥、业务阶段绑定和 Agent 探针摘要由平台共享，普通用户无法独立选择模型，任务实际使用的配置也不体现触发者。需要将配置与测试结果归属到个人，让每次业务执行使用触发者自己的配置，并防止跨用户读取、修改和误用凭据。

## What Changes

- **BREAKING**：模型管理从超级管理员全局接口迁移为登录用户的个人接口，列表、详情、写操作、连通性、兼容性 run 和历史结果均按所有者隔离；超级管理员通过个人接口同样只能管理自己的配置。
- **BREAKING**：删除原 `/api/admin/model-profiles`、`/api/admin/model-stage-assignments`、`/api/admin/model-profile-agent-test-runs` 和 `/api/admin/agent-runtimes` 路径族的全部现有接口及专用权限规则、当前 API 定义，不保留旧兼容入口、重定向或迁移响应接口。平台受管 CLI 维护与其他 admin 功能保持各自边界。
- **BREAKING**：阶段绑定由全局 stage 唯一改为用户与 stage 唯一；模型名称改为用户内大小写不敏感唯一。模型 profile 增加明确所有者，不以历史 created_by 审计字段代替归属。
- 谁触发任务就使用谁的个人绑定。自动派生任务与自动重试继承原触发者，手动重新触发使用新操作者；后台持久化该身份并在实际执行时校验，禁止回退项目所有者或 admin 的配置。
- 现有平台模型、加密密钥与阶段绑定全部迁移到用户名精确为 admin 的账号；保留原有创建、修改、测试操作者及历史结果，不批量复制密钥给其他用户。
- 后端提供个人模型与 Agent 选择接口及前端后续对接说明，本 change 不修改个人中心、页面路由或账号切换缓存。平台继续统一安装和维护 Agent CLI，用户不能上传可执行程序或指定任意服务器命令。
- 全局 runtime 的个人探测摘要不再作为共享兼容状态；保留平台运行能力目录，兼容性按个人 profile 与 runtime 组合计算。
- 建立跨用户、后台执行、迁移及恢复的回归测试；所有安装、测试和完整 Compose 验收在本地 Docker 完成。

## Capabilities

### New Capabilities

- `personal-model-agent-configuration`：个人模型、阶段绑定、Agent 能力目录、测试结果隔离及接口契约。
- `trigger-owned-ai-execution`：触发者身份持久化、自动派生/重试与手动重触发、配置生效时机、worker 恢复和项目结果访问边界。
- `personal-ai-configuration-migration`：旧平台配置向 admin 迁移、历史与在途任务处理、发布顺序和本地质量验收。

### Modified Capabilities

- `backend-api-design`：统一总接口规范中的个人权限、个人路径和安全 runtime 目录，移除旧 admin 模型 API 兼容要求。
- `backend-app-skeleton`：将模型配置测试要求调整为个人归属隔离、旧路由删除和本地隔离验收，不恢复退役 E2E 设施。
- `model-profile-management`：将管理、绑定及测试权限改为个人归属，删除旧 admin API 与旧测试兼容入口要求，保留个人接口的协议和脱敏约束。
- `agent-runtime-selection`：个人可查询受管能力目录，个人测试不得写入共享兼容状态。
- `backend-database-design`：个人所有者、复合唯一约束、历史探针归属和持久化执行身份。
- `kb-ai-extension-runtime`：按触发者校验和执行，readiness 按访问者判断。
- `test-result-comparison-runtime`：比较任务配置归属和执行引用。
- `automated-test-runtime`：自动派生比较继承测试触发者。
- `task-runtime`：后台身份持久化、重试和恢复边界。
- `codex-chat-protocol-bridge`：动态个人 profile 的 worker 配置隔离，保持受管代理生命周期。

本 change 仅归属 backend OpenSpec，仅修改后端代码、测试、文档和规范。frontend、根目录及 tools 不创建关联 change，不修改其规范，不将跨目录验收当成 OpenSpec 归属。

## Impact

- backend：本 change 是实施协调与验收主清单；根目录仅作为完整 Compose 验收执行位置，不建立根级 change，不新增基础设施或扫描器功能。
- backend：modelprofile、agentruntime、security、task/worker/queue、Codex probe 分派恢复、KB_AI_EXTENSION 与 TEST_LOG_COMPARE，以及自动测试派生流程、readiness、项目汇总中受影响的配置判定；数据库迁移和生成 jOOQ 类型需同步。
- frontend：本次不修改；后端对接文档说明新旧接口边界及账号缓存隔离要求，供独立前端工作使用，不将其列为本次实施或验收任务。
- 平台维护的 Agent 版本、协议转换实现和扫描工具不在本次功能修改范围；已有第三方冻结依赖继续保留。
- 采用 dev 当前工作区；不触及用户已有 README 和汇报文档改动，不修改远程 dev/test，不自动 commit/push 或归档。
