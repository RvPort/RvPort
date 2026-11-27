# project-source-environment Specification

## Purpose
TBD - created by archiving change add-project-source-environment. Update Purpose after archive.
## Requirements
### Requirement: 项目 API 必须提供工作台根实体管理
后端 SHALL 提供项目列表、创建、详情、基础信息更新和概览 API，并 SHALL 使用 `projects` 表作为项目根实体事实来源。项目名称允许重复，项目唯一识别 SHALL 使用 `projectId`。

#### Scenario: 创建项目
- **WHEN** 已登录用户调用 `POST /api/projects` 并提交项目名称、描述、源架构和目标架构
- **THEN** 后端 SHALL 创建 `projects` 记录
- **AND** 默认 `stage` SHALL 为 `PROJECT_CREATED`
- **AND** 默认 `status` SHALL 为 `ACTIVE`
- **AND** `created_by` SHALL 使用当前登录用户 ID

#### Scenario: 创建同名项目
- **WHEN** 已登录用户创建一个与已有项目名称相同的新项目
- **THEN** 后端 SHALL 允许创建
- **AND** 后端 SHALL 使用新的 `projectId` 区分该项目
- **AND** 后端 MUST NOT 因名称重复返回冲突错误

#### Scenario: 查询项目列表
- **WHEN** 已登录用户调用 `GET /api/projects`
- **THEN** 后端 SHALL 返回未软删除项目的分页列表
- **AND** 列表 SHALL 支持按 `status`、`stage` 和关键字筛选
- **AND** 每个列表项 SHALL 包含 `highRiskCount`、`latestScanAt`、`latestBuildStatus` 和 `latestTestStatus`
- **AND** 尚无对应扫描、构建或测试记录时，摘要字段 SHALL 返回 `0` 或 `null`

#### Scenario: 更新项目基础信息
- **WHEN** 已登录用户调用 `PATCH /api/projects/{projectId}`
- **THEN** 后端 SHALL 只允许更新项目名称、描述、源架构、目标架构和项目状态
- **AND** 后端 MUST NOT 通过该接口把 `stage` 当作任意可写状态机字段

#### Scenario: 读取项目概览
- **WHEN** 已登录用户调用 `GET /api/projects/{projectId}/overview`
- **THEN** 后端 SHALL 返回项目基本信息、当前启用源码版本、源码版本数量、环境摘要、最近任务摘要和推荐下一步动作
- **AND** 概览 SHALL 从源码版本、环境和任务等事实数据聚合当前状态
- **AND** 概览 MUST NOT 只依赖 `projects.stage` 表达项目真实状态

### Requirement: 源码版本 API 必须支持修复后源码重新入库
后端 SHALL 提供源码版本列表、压缩包上传、Git 同步任务创建和当前源码版本切换 API，并 SHALL 通过新增 `source_revisions` 表记录表达每次源码入库。

#### Scenario: 查询源码版本列表
- **WHEN** 已登录用户调用 `GET /api/projects/{projectId}/sources`
- **THEN** 后端 SHALL 返回该项目的源码版本分页列表
- **AND** 响应 SHALL 标记哪个源码版本是当前启用版本
- **AND** 响应 MUST NOT 返回 `archive_path` 或 `working_copy_path`

#### Scenario: 上传源码压缩包
- **WHEN** 已登录用户调用 `POST /api/projects/{projectId}/sources/archive` 并上传源码压缩包
- **THEN** 后端 SHALL 将文件保存到 `RVPORT_UPLOAD_DIR` 管理的本地目录
- **AND** 后端 SHALL 创建新的 `source_revisions` 记录，`revision_type` 为 `ARCHIVE`
- **AND** 后端 SHALL 创建对应 `source_imports` 记录
- **AND** 如果请求要求启用该版本，后端 SHALL 将同项目其他源码版本置为非启用，并将新版本置为启用
- **AND** 响应 MUST NOT 返回服务器本地文件路径

#### Scenario: 修复后再次上传源码
- **WHEN** 同一项目已有当前源码版本并再次上传修复后的源码压缩包
- **THEN** 后端 SHALL 创建新的 `source_revisions` 记录
- **AND** 后端 MUST NOT 覆盖旧源码版本记录

#### Scenario: 创建 Git 同步任务
- **WHEN** 已登录用户调用 `POST /api/projects/{projectId}/sources/git-sync`
- **THEN** 后端 SHALL 校验 Git URL 不包含用户名、密码或 token
- **AND** 后端 SHALL 创建 `source_imports` 记录
- **AND** 后端 SHALL 创建任务类型为 `SOURCE_GIT_SYNC` 的 `tasks` 记录并投递队列
- **AND** 队列载荷 SHALL 只包含任务 ID 等路由信息
- **AND** 任务输入快照 SHALL 包含 `sourceImportId`、脱敏 Git URL、可选 branch、可选 commit、可选 credentialId 和 activate 标记
- **AND** 任务输入快照 MUST NOT 包含明文凭据
- **AND** 任务输入快照 MUST NOT 包含带用户名、密码或 token 的 Git URL

#### Scenario: Git 同步成功后产生源码版本
- **WHEN** `SOURCE_GIT_SYNC` 任务成功完成
- **THEN** 后端 SHALL 创建新的 `source_revisions` 记录，`revision_type` 为 `GIT`
- **AND** 后端 SHALL 将对应 `source_imports` 更新为 `SUCCEEDED`
- **AND** 后端 SHALL 记录实际 checkout commit
- **AND** 后端 SHALL 保存后端本机工作副本路径作为内部字段
- **AND** API 响应 MUST NOT 返回服务器本机工作副本路径

#### Scenario: 切换当前源码版本
- **WHEN** 已登录用户调用 `POST /api/projects/{projectId}/sources/{sourceRevisionId}/activate`
- **THEN** 后端 SHALL 验证源码版本属于该项目
- **AND** 后端 SHALL 在同一事务中取消同项目其他源码版本的启用状态，并启用目标源码版本

### Requirement: 环境 API 必须管理 SSH 执行环境
后端 SHALL 提供 SSH 环境列表、创建、详情、更新、禁用和快照列表 API，并 SHALL 使用 `environments` 表作为环境配置事实来源。

#### Scenario: 创建 SSH 环境
- **WHEN** 已登录用户调用 `POST /api/projects/{projectId}/environments`
- **THEN** 后端 SHALL 创建 `environments` 记录
- **AND** `role` SHALL 限定为 `SOURCE_X86`、`TARGET_RISCV` 或 `GENERAL_EXECUTION`
- **AND** `sshPort` SHALL 限定在 1 到 65535
- **AND** 默认 `status` SHALL 为 `UNKNOWN`
- **AND** 默认 `enabled` SHALL 为 `true`

#### Scenario: 创建使用跳板机的 SSH 环境
- **WHEN** 已登录用户创建环境并提交 `jumpHostEnvironmentId`
- **THEN** 后端 SHALL 验证跳板机环境存在且属于同一项目
- **AND** 后端 SHALL 验证跳板机环境已启用
- **AND** 后端 SHALL 保存该跳板机引用
- **AND** 后端 MUST NOT 允许环境引用自己作为跳板机
- **AND** 后端 MUST NOT 允许多级跳板链路

#### Scenario: 查询环境列表
- **WHEN** 已登录用户调用 `GET /api/projects/{projectId}/environments`
- **THEN** 后端 SHALL 返回该项目环境列表
- **AND** 列表 SHALL 支持按环境角色和启用状态筛选
- **AND** 响应 SHALL 包含凭据遮蔽摘要
- **AND** 响应 SHALL 包含已配置跳板机的非敏感摘要

#### Scenario: 更新环境
- **WHEN** 已登录用户调用 `PATCH /api/environments/{environmentId}`
- **THEN** 后端 SHALL 允许更新名称、角色、架构、主机、端口、用户名、工作目录、启用状态、跳板机引用和凭据
- **AND** 当环境被禁用时，后端 SHALL 将 `enabled` 设置为 `false`
- **AND** 被禁用环境 MUST NOT 用于新的连接测试、环境采集或远程脚本任务

#### Scenario: 更新环境跳板机
- **WHEN** 已登录用户更新环境并设置 `jumpHostEnvironmentId`
- **THEN** 后端 SHALL 验证跳板机环境属于同一项目且已启用
- **AND** 后端 MUST NOT 允许自引用
- **AND** 后端 MUST NOT 允许多级跳板链路

#### Scenario: 禁用被引用的跳板机
- **WHEN** 已登录用户禁用一个已被其他环境引用的跳板机环境
- **THEN** 后端 SHALL 拒绝禁用并返回明确错误
- **AND** 后端 MUST NOT 让已有环境保留指向禁用跳板机的有效配置

#### Scenario: 查询环境快照
- **WHEN** 已登录用户调用 `GET /api/environments/{environmentId}/snapshots`
- **THEN** 后端 SHALL 返回该环境的快照分页列表
- **AND** 响应 SHALL 按采集时间倒序排列

### Requirement: 环境工作目录必须作为远端源码部署根路径
环境管理能力 SHALL 将 `environments.working_directory` 作为源码部署和后续构建验证的远端工作目录根路径。

#### Scenario: 使用工作目录部署源码
- **WHEN** 用户为某个环境创建源码部署任务
- **THEN** 后端 SHALL 使用该环境的 `working_directory` 作为远端部署根路径
- **AND** 后端 SHALL 在该根路径下生成受控源码部署目录
- **AND** 前端 MUST NOT 通过源码部署 API 覆盖该根路径

#### Scenario: 工作目录缺失时拒绝源码部署
- **WHEN** 环境缺少 `working_directory`
- **AND** 用户尝试将源码部署到该环境
- **THEN** 后端 SHALL 拒绝请求并返回 `ENVIRONMENT_WORKING_DIRECTORY_REQUIRED`
- **AND** 后端 MUST NOT 创建源码部署任务或远端执行命令

#### Scenario: 更新工作目录影响后续部署
- **WHEN** 用户更新环境的 `working_directory`
- **THEN** 后端 SHALL 保留既有源码部署记录中的历史 `remote_path`
- **AND** 后续新建源码部署 SHALL 使用更新后的 `working_directory` 生成新的远端路径

### Requirement: Git 和 SSH 凭据必须加密保存并遮蔽展示
后端 SHALL 使用应用层凭据加密能力保存 Git/SSH 业务凭据，并 SHALL 保证 API 响应、任务输入摘要、日志和审计详情不包含明文秘密。

#### Scenario: 保存 SSH 密码凭据
- **WHEN** 用户创建或更新 SSH 环境并提交密码
- **THEN** 后端 SHALL 将凭据 payload 加密后写入 `credentials.encrypted_payload`
- **AND** `credentials.kind` SHALL 为 `SSH_PASSWORD`
- **AND** 响应 SHALL 只返回配置状态、凭据类型、名称和遮蔽摘要

#### Scenario: 保存 SSH 私钥凭据
- **WHEN** 用户创建或更新 SSH 环境并提交私钥
- **THEN** 后端 SHALL 将私钥和可选 passphrase 加密保存
- **AND** `credentials.kind` SHALL 为 `SSH_KEY`
- **AND** 响应 MUST NOT 返回私钥、passphrase 或加密密文

#### Scenario: 保存 Git 凭据
- **WHEN** 用户创建 Git 同步请求并提交 Git 密码或 token
- **THEN** 后端 SHALL 将 Git 凭据加密保存或引用已有凭据
- **AND** `credentials.kind` SHALL 为 `GIT_PASSWORD` 或 `GIT_TOKEN`
- **AND** Git URL MUST NOT 保存用户名、密码或 token
- **AND** API 响应、任务输入快照、任务日志、事件和审计详情 MUST NOT 包含明文 Git 密码或 token

#### Scenario: 凭据加密密钥缺失
- **WHEN** 请求需要保存业务凭据但 `RVPORT_CREDENTIAL_MASTER_KEY` 未配置或无效
- **THEN** 后端 SHALL 拒绝请求并返回明确错误
- **AND** 后端 MUST NOT 保存明文凭据

#### Scenario: 运行时读取 Git 凭据
- **WHEN** worker 执行 `SOURCE_GIT_SYNC` 并需要 Git 凭据
- **THEN** 后端 SHALL 校验凭据存在、启用且属于当前项目
- **AND** 后端 SHALL 只在 worker 执行期间解密凭据
- **AND** 解密后的凭据 SHALL 只传递给 Git 执行适配层
- **AND** 后端 MUST NOT 持久化运行时明文凭据

### Requirement: 环境长操作必须通过任务运行时投递
后端 SHALL 将环境连接测试、环境检查、环境采集和远程脚本执行建模为长任务，并 SHALL 复用已有 PostgreSQL 任务模型、RabbitMQ 投递、任务执行上下文和项目级 SSE。

#### Scenario: 创建环境连接测试任务
- **WHEN** 已登录用户调用 `POST /api/projects/{projectId}/environments/{environmentId}/test-connection`
- **THEN** 后端 SHALL 创建任务类型为 `ENV_TEST_CONNECTION` 的 `tasks` 记录
- **AND** 后端 SHALL 校验环境存在且属于 URL 中的 `projectId`
- **AND** 后端 SHALL 校验当前用户有权访问该项目
- **AND** 后端 SHALL 投递任务 ID 到 RabbitMQ
- **AND** 响应 SHALL 返回任务 ID 和初始任务状态
- **AND** 任务输入快照 MAY 包含 `jumpHostEnvironmentId`
- **AND** 任务输入快照 MAY 包含 `timeoutSeconds`
- **AND** 任务输入快照 MUST NOT 包含目标环境或跳板机的明文凭据

#### Scenario: 创建环境检查任务
- **WHEN** 已登录用户调用 `POST /api/projects/{projectId}/environments/{environmentId}/check`
- **THEN** 后端 SHALL 创建任务类型为 `ENV_RUN_SCRIPT` 的 `tasks` 记录
- **AND** 后端 SHALL 校验环境存在且属于 URL 中的 `projectId`
- **AND** 后端 SHALL 校验当前用户有权访问该项目
- **AND** 任务输入快照 SHALL 包含 `scriptType=ENV_CHECK`
- **AND** 任务输入快照 SHALL 包含 `checkItems`
- **AND** `checkItems` SHALL 只允许 `CONNECTION`、`BASIC_TOOLS`、`WORKING_DIRECTORY`、`PERMISSIONS`
- **AND** 任务输入快照 MAY 包含 `workingDirectory` 和 `timeoutSeconds`
- **AND** 后端 SHALL 投递任务 ID 到 RabbitMQ
- **AND** 响应 SHALL 返回任务 ID 和初始任务状态
- **AND** 任务输入快照 MUST NOT 包含明文凭据

#### Scenario: 执行环境连接测试任务
- **WHEN** worker 认领 `ENV_TEST_CONNECTION` 任务
- **THEN** worker SHALL 调用 SSH runtime 真实测试目标环境连接
- **AND** worker SHALL 通过任务执行上下文写入进度、日志摘要和终态事件
- **AND** worker SHALL 根据连接结果更新任务状态和环境连接状态
- **AND** 任务日志和事件 MUST NOT 包含明文凭据或服务器真实路径

#### Scenario: 创建环境采集任务
- **WHEN** 已登录用户调用 `POST /api/projects/{projectId}/environments/{environmentId}/collect`
- **THEN** 后端 SHALL 创建任务类型为 `ENV_COLLECT` 的 `tasks` 记录
- **AND** 后端 SHALL 校验环境存在且属于 URL 中的 `projectId`
- **AND** 后端 SHALL 校验当前用户有权访问该项目
- **AND** 任务输入快照 SHALL 引用环境 ID 和项目 ID
- **AND** 任务输入快照 MAY 包含 `collectScopes` 和 `timeoutSeconds`
- **AND** `collectScopes` 未传时 SHALL 使用默认采集范围
- **AND** 后端 SHALL 投递任务 ID 到 RabbitMQ
- **AND** 响应 SHALL 返回任务 ID 和初始任务状态
- **AND** 任务输入快照 MUST NOT 包含明文凭据

#### Scenario: 创建远程脚本任务
- **WHEN** 已登录用户调用 `POST /api/projects/{projectId}/environments/{environmentId}/scripts`
- **THEN** 后端 SHALL 创建任务类型为 `ENV_RUN_SCRIPT` 的 `tasks` 记录
- **AND** 后端 SHALL 校验环境存在且属于 URL 中的 `projectId`
- **AND** 后端 SHALL 校验当前用户有权访问该项目
- **AND** 任务输入快照 SHALL 包含 `scriptType`
- **AND** 任务输入快照 MAY 包含脚本摘要、脚本哈希、超时、工作目录和内置脚本参数
- **AND** 后端 SHALL 投递任务 ID 到 RabbitMQ
- **AND** 响应 SHALL 返回任务 ID 和初始任务状态
- **AND** 任务输入快照 MUST NOT 包含明文凭据
- **AND** 任务输入快照 MUST NOT 包含未经脱敏的敏感脚本内容

#### Scenario: 拒绝不支持的环境运行类型
- **WHEN** 已登录用户调用环境运行 API 并提交未知 `scriptType`、未知 `checkItems` 或非法 `collectScopes`
- **THEN** 后端 SHALL 拒绝请求并返回校验错误
- **AND** 后端 MUST NOT 创建任务
- **AND** 后端 MUST NOT 投递队列消息

#### Scenario: 拒绝跨项目环境运行请求
- **WHEN** 已登录用户调用项目作用域环境运行 API
- **AND** URL 中的 `environmentId` 不属于 URL 中的 `projectId`
- **THEN** 后端 SHALL 拒绝请求
- **AND** 后端 MUST NOT 创建任务
- **AND** 后端 MUST NOT 投递队列消息

#### Scenario: 拒绝无项目权限的环境运行请求
- **WHEN** 已登录用户调用项目作用域环境运行 API
- **AND** 当前用户无权访问 URL 中的 `projectId`
- **THEN** 后端 SHALL 拒绝请求
- **AND** 后端 MUST NOT 暴露该项目环境的连接信息或凭据摘要
- **AND** 后端 MUST NOT 创建任务
- **AND** 后端 MUST NOT 投递队列消息

### Requirement: 项目源码环境操作必须写入审计日志
后端 SHALL 对项目、源码、环境和凭据相关的敏感操作写入审计日志，并 SHALL 对审计详情做脱敏处理。

#### Scenario: 记录项目变更审计
- **WHEN** 用户创建或更新项目
- **THEN** 后端 SHALL 写入审计日志
- **AND** 审计日志 SHALL 包含用户 ID、动作、资源类型、资源 ID 和安全详情

#### Scenario: 记录凭据相关审计
- **WHEN** 用户创建或替换 Git/SSH 凭据
- **THEN** 后端 SHALL 写入审计日志
- **AND** 审计详情 MUST NOT 包含明文密码、私钥、token、passphrase 或加密密文

### Requirement: 单元测试必须覆盖正常异常和边界路径
项目、源码、环境和凭据相关实现 SHALL 配套单元测试或轻量集成测试，并 SHALL 覆盖正常流程、异常流程和 edge case。

#### Scenario: 覆盖正常流程
- **WHEN** 为项目、源码、环境、凭据和任务投递能力编写测试
- **THEN** 测试 SHALL 覆盖合法输入下的创建、查询、更新、启用、任务创建和队列投递行为
- **AND** 测试 SHALL 覆盖 Git 同步成功创建源码版本、更新导入状态和写入执行记录

#### Scenario: 覆盖异常流程
- **WHEN** 为项目、源码、环境、凭据和任务投递能力编写测试
- **THEN** 测试 SHALL 覆盖未登录、资源不存在、状态不允许、Git URL 携带凭据、凭据密钥无效和禁用环境创建任务等错误路径
- **AND** 测试 SHALL 覆盖 Git 同步认证失败、仓库不存在、checkout 失败、凭据解密失败和进程超时

#### Scenario: 覆盖 edge case
- **WHEN** 为项目、源码、环境、凭据和任务投递能力编写测试
- **THEN** 测试 SHALL 覆盖空列表、可选字段为空、重复源码上传、active 源码切换、跳板机自引用、多级跳板、跨项目跳板、分页边界和 SSH 端口边界
- **AND** 测试 SHALL 覆盖重复 Git 同步、失败不改变 active source、临时目录清理、任务输入脱敏和日志脱敏

### Requirement: SSH runtime 必须真实执行环境连接测试
后端 SHALL 提供 SSH runtime，用于 worker 根据环境配置真实建立 SSH 连接并执行统一的轻量探测命令。

#### Scenario: 每次命令使用短生命周期 SSH session
- **WHEN** SSH runtime 执行连接测试或后续远程命令
- **THEN** SSH runtime SHALL 为该次命令建立 SSH session
- **AND** 命令完成、失败或超时后 SHALL 关闭该 SSH session
- **AND** SSH runtime MUST NOT 跨任务或跨命令复用长期空闲 SSH session

#### Scenario: 直连环境连接测试成功
- **WHEN** worker 执行 `ENV_TEST_CONNECTION` 任务且目标环境未配置跳板机
- **THEN** SSH runtime SHALL 使用目标环境的主机、端口、用户名和凭据建立 SSH 连接
- **AND** SHALL 执行统一的轻量探测命令验证远程命令执行能力
- **AND** 任务 SHALL 写入进度事件和安全日志摘要
- **AND** 环境状态 SHALL 更新为可连接状态

#### Scenario: 连接测试执行统一探测命令
- **WHEN** SSH runtime 执行 `ENV_TEST_CONNECTION`
- **THEN** SSH runtime SHALL 运行无需 sudo 且无写入副作用的 POSIX shell 兼容探测脚本
- **AND** 探测脚本 SHALL 输出 `RVPORT_PROBE=ok`
- **AND** 探测脚本 SHALL 检查 kernel name、kernel version、machine arch、远程执行用户、远程命令执行能力、工作目录可用性和基础工具存在性
- **AND** 基础工具存在性 SHALL 至少覆盖 `sh`、`bash`、`git`、`gcc`、`g++`、`make`、`cmake`、`python3`、`java`、`mvn`
- **AND** 探测脚本 MUST NOT 读取项目目录、源码目录、用户 home 敏感文件或输出服务器真实路径

#### Scenario: 连接测试保存执行记录
- **WHEN** `ENV_TEST_CONNECTION` 探测命令成功且输出包含 `RVPORT_PROBE=ok`
- **THEN** 后端 SHALL 写入一条 `tool_runs` 执行记录
- **AND** `tool_runs.tool_name` SHALL 为稳定的连接探测工具名
- **AND** `tool_runs.exit_code` SHALL 保存远程命令退出码
- **AND** `tool_runs.summary` SHALL 保存探测命令版本、kernel、arch、远程用户、工作目录检查结果、基础工具存在性和解析警告
- **AND** 后端 SHALL 通过 `task_logs` 保存 stdout/stderr 日志文件元数据和安全 excerpt
- **AND** 后端 SHALL 更新 `environments.status` 和 `last_connected_at`
- **AND** 连接测试 MUST NOT 写入 `environment_snapshots` 或 `environment_components`
- **AND** 连接测试 MUST NOT 更新 `last_collected_at`

#### Scenario: 跳板机环境连接测试成功
- **WHEN** worker 执行 `ENV_TEST_CONNECTION` 任务且目标环境配置 `jumpHostEnvironmentId`
- **THEN** SSH runtime SHALL 先连接跳板机环境
- **AND** SHALL 通过该跳板机连接目标环境
- **AND** SHALL 使用跳板机和目标环境各自绑定的 SSH 凭据
- **AND** 当前命令完成、失败或超时后 SHALL 关闭目标 session 和跳板机 session
- **AND** 任务日志和事件 MUST NOT 包含跳板机或目标环境的明文凭据

#### Scenario: 连接测试失败
- **WHEN** SSH 连接、认证或探测命令执行失败
- **THEN** worker SHALL 将任务标记为 `FAILED`
- **AND** 任务错误摘要 SHALL 使用安全错误消息
- **AND** 环境状态 SHALL 更新为不可连接或未知状态
- **AND** 错误摘要 MUST NOT 包含密码、私钥、passphrase、token、加密密文或服务器真实路径

#### Scenario: 探测输出无法识别
- **WHEN** SSH 连接成功但探测输出不包含 `RVPORT_PROBE=ok` 或无法按 `KEY=VALUE` 解析
- **THEN** worker SHALL 将连接测试任务标记为 `FAILED`
- **AND** 任务错误摘要 SHALL 表示探测输出无效
- **AND** 后端 MUST NOT 保存不可信的环境快照

### Requirement: SSH runtime 必须统一环境采集和脚本执行记录语义
后端 SHALL 通过统一 Env Runner/SSH runtime 模型支撑环境检查、环境信息采集和远程脚本执行，并 SHALL 统一保存执行记录、日志和结构化结果。

#### Scenario: 环境检查保存检查结果和基础快照
- **WHEN** worker 执行 `ENV_RUN_SCRIPT` 且 `scriptType` 为 `ENV_CHECK`
- **THEN** SSH runtime SHALL 根据输入检查项执行受控环境检查脚本
- **AND** 检查项 SHALL 支持 `CONNECTION`、`BASIC_TOOLS`、`WORKING_DIRECTORY`、`PERMISSIONS`
- **AND** 检查脚本 SHALL 同时采集 OS、OS version、kernel、arch、package manager、基础工具版本、远程用户和工作目录检查结果
- **AND** 后端 SHALL 将每个检查项的结果写入 `tool_runs.summary.checkResults`
- **AND** 后端 SHALL 保存 stdout/stderr 到任务日志
- **AND** 后端 SHALL 写入一条成功的轻量 `environment_snapshots` 记录
- **AND** `environment_snapshots.task_id` SHALL 指向当前任务
- **AND** `raw_snapshot` SHALL 标记该快照来自环境检查，并保存标准化后的非敏感检查结果、脚本版本和解析警告
- **AND** 环境检查 MUST NOT 写入 `environment_components`

#### Scenario: 环境检查失败不覆盖上一轮成功基础信息
- **WHEN** `ENV_CHECK` 连接、认证、脚本执行或解析失败
- **THEN** worker SHALL 将任务标记为 `FAILED`
- **AND** 后端 SHALL 保存执行记录、stdout/stderr 日志和安全失败摘要
- **AND** 后端 MUST NOT 写入成功 `environment_snapshots`
- **AND** 后端 MUST NOT 删除或覆盖上一轮成功的环境基础快照
- **AND** 后端 MUST NOT 更新表示成功采集或成功检查的时间字段

#### Scenario: 环境采集保存环境快照
- **WHEN** worker 执行 `ENV_COLLECT`
- **THEN** SSH runtime SHALL 执行内置环境采集脚本
- **AND** 采集脚本 SHALL 采集 OS、kernel、arch、package manager、软件源摘要、已安装包摘要和基础工具版本
- **AND** 后端 SHALL 写入 `environment_snapshots` 记录
- **AND** `environment_snapshots.task_id` SHALL 指向当前任务
- **AND** `os_name`、`os_version`、`kernel_version`、`arch`、`package_manager` SHALL 来自采集输出
- **AND** `repo_summary` SHALL 保存软件源摘要 JSON
- **AND** `tool_versions` SHALL 保存基础工具版本 JSON
- **AND** `raw_snapshot` SHALL 保存标准化后的非敏感采集结果、脚本版本和解析警告
- **AND** 后端 MAY 写入 `environment_components` 保存系统包、库、工具和 native 文件摘要
- **AND** 后端 SHALL 更新 `environments.last_collected_at`

#### Scenario: 环境采集失败不覆盖上一轮成功结果
- **WHEN** `ENV_COLLECT` 连接、认证、脚本执行或解析失败
- **THEN** worker SHALL 将任务标记为 `FAILED`
- **AND** 后端 SHALL 保存执行记录、stdout/stderr 日志和安全失败摘要
- **AND** 后端 MUST NOT 覆盖上一轮成功的 `environment_snapshots`
- **AND** 后端 MUST NOT 更新 `last_collected_at`

#### Scenario: 远程脚本执行保存执行记录
- **WHEN** worker 执行 `ENV_RUN_SCRIPT`
- **THEN** 后端 SHALL 写入 `tool_runs` 执行记录
- **AND** `tool_runs.summary` SHALL 保存脚本类型、工作目录检查结果、执行用户、安全命令摘要和耗时
- **AND** `tool_runs.exit_code` SHALL 保存远程脚本退出码
- **AND** 后端 SHALL 通过 `task_logs` 保存 stdout/stderr
- **AND** 后端 SHALL 写入审计日志记录执行人、环境、脚本类型、开始时间、结束时间、状态和退出码
- **AND** 执行记录、日志摘要和审计详情 MUST NOT 包含明文凭据、私钥、passphrase、token 或未经脱敏的敏感脚本内容

#### Scenario: 连接测试超时
- **WHEN** SSH 连接或探测命令超过配置的超时时间
- **THEN** worker SHALL 将任务标记为 `FAILED`
- **AND** 任务错误摘要 SHALL 表示连接超时或命令超时
- **AND** SSH session 和跳板机 session SHALL 被关闭

#### Scenario: 单条长时间命令
- **WHEN** 后续任务通过 SSH runtime 执行单条长时间运行命令
- **THEN** SSH runtime SHALL 将该 SSH session 限定为服务当前命令
- **AND** SSH runtime SHALL 使用命令超时或任务超时控制最长运行时间
- **AND** SSH runtime MUST NOT 在命令完成后保留该 session 等待后续命令

### Requirement: 环境检查必须提供普通页面所需基础信息
后端 SHALL 将普通环境页面需要展示的基础信息纳入环境检查结果，避免普通页面依赖独立环境采集入口。

#### Scenario: 环境检查生成基础信息摘要
- **WHEN** 环境检查成功完成
- **THEN** 后端 SHALL 生成环境基础信息摘要
- **AND** 摘要 SHALL 至少包含 OS、OS version、kernel、arch、package manager、基础工具可用性、基础工具版本、远程用户、工作目录检查结果和权限检查结果
- **AND** 摘要 MUST NOT 包含明文凭据、私钥、token、passphrase、用户 home 敏感文件内容或服务器敏感路径

#### Scenario: 区分轻量检查和重型采集
- **WHEN** 后端保存环境检查产生的快照
- **THEN** 后端 SHALL 在快照原始摘要或等效字段中标记快照来源为轻量环境检查
- **AND** SBOM 分析 MUST NOT 将只有轻量检查来源的快照误判为完整组件清单
- **AND** 需要 package、library、native file、repo provide 或 ELF metadata 时，后端 SHALL 继续使用重型 `ENV_COLLECT` 快照

#### Scenario: 普通环境采集入口可被前端隐藏
- **WHEN** 前端不再暴露独立环境采集入口
- **THEN** 后端 SHALL 保持环境检查 API 可提供页面所需基础信息
- **AND** 后端 MAY 继续保留 `ENV_COLLECT` API 供兼容、测试或后端流程使用

### Requirement: SSH runtime 必须运行时解密并保护 SSH 凭据
后端 SHALL 只在 worker 执行期间解密 SSH 凭据，并 SHALL 避免明文凭据进入任务输入、队列消息、事件、日志、审计和 API 响应。

#### Scenario: 解密 SSH 密码凭据
- **WHEN** SSH runtime 需要使用 `SSH_PASSWORD` 凭据连接环境
- **THEN** 后端 SHALL 校验凭据存在、启用且属于当前项目
- **AND** SHALL 使用应用层凭据加密能力解密 `credentials.encrypted_payload`
- **AND** SHALL 将解密后的密码只传递给 SSH 客户端适配层

#### Scenario: 解密 SSH 私钥凭据
- **WHEN** SSH runtime 需要使用 `SSH_KEY` 凭据连接环境
- **THEN** 后端 SHALL 解密私钥和可选 passphrase
- **AND** SHALL 将解密后的私钥只传递给 SSH 客户端适配层
- **AND** 日志、事件和错误摘要 MUST NOT 包含私钥内容或 passphrase

#### Scenario: 凭据类型不匹配
- **WHEN** 环境绑定的凭据不是 `SSH_PASSWORD` 或 `SSH_KEY`
- **THEN** SSH runtime SHALL 拒绝执行连接
- **AND** 任务 SHALL 失败并写入安全错误摘要

#### Scenario: 凭据解密失败
- **WHEN** 凭据密文无法解密或加密主密钥无效
- **THEN** SSH runtime SHALL 拒绝执行连接
- **AND** 任务 SHALL 失败并写入安全错误摘要
- **AND** 错误摘要 MUST NOT 包含密文、主密钥或原始异常中的秘密内容

### Requirement: SSH runtime 必须配套测试覆盖
SSH runtime 相关实现 SHALL 配套单元测试或轻量集成测试，并 SHALL 覆盖正常流程、异常流程和 edge case。

#### Scenario: 覆盖正常流程
- **WHEN** 为 SSH runtime 编写测试
- **THEN** 测试 SHALL 覆盖密码直连、私钥直连、跳板机连接、统一探测输出解析、连接测试执行记录、环境检查结果保存、环境采集快照保存和 `ENV_TEST_CONNECTION` handler 成功路径

#### Scenario: 覆盖异常流程
- **WHEN** 为 SSH runtime 编写测试
- **THEN** 测试 SHALL 覆盖认证失败、连接超时、命令失败、探测输出无效、凭据不存在、凭据项目不匹配和凭据解密失败

#### Scenario: 覆盖 edge case
- **WHEN** 为 SSH runtime 编写测试
- **THEN** 测试 SHALL 覆盖 passphrase 为空、跳板机禁用、多级跳板防御、部分工具缺失、工作目录不存在、工作目录不可写、日志脱敏、每次命令关闭 session 和禁止跨命令复用 session

### Requirement: Git 同步运行时必须在后端本机获取源码
后端 SHALL 提供 `SOURCE_GIT_SYNC` worker runtime，在后端本机执行 Git clone/fetch/checkout，并将源码工作副本保存到受配置控制的本地 runtime source 目录。

#### Scenario: Git 同步任务成功获取源码
- **WHEN** worker 认领 `SOURCE_GIT_SYNC` 任务
- **THEN** worker SHALL 从任务输入快照读取 `projectId`、`sourceImportId`、`gitUrl`、可选 `gitBranch`、可选 `gitCommit`、可选 `credentialId` 和 `activate`
- **AND** worker SHALL 在后端本机执行 Git 获取源码
- **AND** worker SHALL 将源码工作副本写入受配置控制的本地目录
- **AND** worker SHALL 获取实际 checkout commit
- **AND** worker SHALL 创建新的 `source_revisions` 记录，`revision_type` 为 `GIT`
- **AND** `source_revisions.working_copy_path` SHALL 保存后端本机工作副本路径
- **AND** `source_revisions.git_url`、`git_branch`、`git_commit` SHALL 保存脱敏后的 Git URL、目标分支和实际 commit
- **AND** worker SHALL 将对应 `source_imports` 更新为 `SUCCEEDED` 并绑定新 `source_revision_id`
- **AND** worker SHALL 将任务标记为 `SUCCEEDED`

#### Scenario: Git 同步按请求启用新源码版本
- **WHEN** `SOURCE_GIT_SYNC` 任务成功
- **AND** 任务输入快照中的 `activate` 为 `true`
- **THEN** 后端 SHALL 在同一事务中清除同项目其他源码版本的启用状态
- **AND** 后端 SHALL 将新创建的 Git 源码版本设为当前启用版本
- **AND** 后端 SHALL 将项目阶段更新为 `SOURCE_IMPORTED`

#### Scenario: Git 同步失败
- **WHEN** Git clone、fetch、checkout、凭据解密或文件系统写入失败
- **THEN** worker SHALL 将任务标记为 `FAILED`
- **AND** worker SHALL 将对应 `source_imports` 更新为 `FAILED`
- **AND** `source_imports.error_message` SHALL 保存安全错误摘要
- **AND** worker SHALL 保存 `tool_runs` 执行记录和 stdout/stderr 任务日志
- **AND** 后端 MUST NOT 创建成功态 `source_revisions`
- **AND** 后端 MUST NOT 改变当前 active source revision

#### Scenario: Git 同步保存执行记录
- **WHEN** worker 执行 `SOURCE_GIT_SYNC`
- **THEN** 后端 SHALL 写入一条 `tool_runs` 记录
- **AND** `tool_runs.tool_name` SHALL 使用稳定 Git runtime 工具名
- **AND** `tool_runs.exit_code` SHALL 保存 Git 进程退出码
- **AND** `tool_runs.summary` SHALL 保存脱敏 Git URL、branch、commit、工作副本摘要、耗时和解析警告
- **AND** 后端 SHALL 通过 `task_logs` 保存 stdout/stderr 日志文件元数据和安全 excerpt

#### Scenario: Git 同步清理失败工作目录
- **WHEN** Git 同步在 clone、fetch 或 checkout 过程中失败
- **THEN** worker SHALL 清理该次任务创建的临时工作目录
- **AND** worker MUST NOT 删除其他 source revision 的工作副本

### Requirement: Git 同步运行时必须保护 Git 凭据
后端 SHALL 只在 worker 执行期间解密 Git 凭据，并 SHALL 避免明文 Git 密码、token 或用户名密码组合进入任务输入、队列消息、日志、审计和 API 响应。

#### Scenario: 解密 Git token 凭据
- **WHEN** `SOURCE_GIT_SYNC` 任务输入包含 `credentialId`
- **AND** 凭据类型为 `GIT_TOKEN`
- **THEN** worker SHALL 校验凭据存在、启用且属于当前项目
- **AND** worker SHALL 解密 token
- **AND** worker SHALL 通过临时安全机制将 token 提供给 Git 进程
- **AND** token MUST NOT 出现在 Git URL、任务输入快照、队列消息、日志 excerpt、事件、审计详情或 `tool_runs.summary`

#### Scenario: 解密 Git 密码凭据
- **WHEN** `SOURCE_GIT_SYNC` 任务输入包含 `credentialId`
- **AND** 凭据类型为 `GIT_PASSWORD`
- **THEN** worker SHALL 校验凭据存在、启用且属于当前项目
- **AND** worker SHALL 解密用户名和密码
- **AND** worker SHALL 通过临时安全机制将用户名和密码提供给 Git 进程
- **AND** 明文用户名密码组合 MUST NOT 出现在 Git URL、任务输入快照、队列消息、日志 excerpt、事件、审计详情或 `tool_runs.summary`

#### Scenario: Git 凭据类型不匹配
- **WHEN** `SOURCE_GIT_SYNC` 任务引用的凭据不是 `GIT_PASSWORD` 或 `GIT_TOKEN`
- **THEN** worker SHALL 拒绝执行 Git 同步
- **AND** 任务 SHALL 失败并写入安全错误摘要
- **AND** 后端 MUST NOT 创建源码版本

#### Scenario: Git 凭据临时文件必须清理
- **WHEN** worker 为 Git 凭据创建临时 helper、askpass 或等效临时文件
- **THEN** worker SHALL 在任务成功、失败或超时后删除临时文件
- **AND** 临时文件路径 MUST NOT 写入 API 响应、审计详情或任务日志 excerpt

### Requirement: Git 同步运行时必须配套测试覆盖
Git 同步运行时 SHALL 配套单元测试和 API E2E 覆盖，并 SHALL 满足正常流程、异常流程和 edge case 测试策略。

#### Scenario: 覆盖正常流程
- **WHEN** 为 Git 同步运行时编写测试
- **THEN** 测试 SHALL 覆盖无凭据公开仓库同步、token 凭据同步、指定 branch、指定 commit、创建 source revision、更新 source import、按请求 activate 和写入 tool run

#### Scenario: 覆盖异常流程
- **WHEN** 为 Git 同步运行时编写测试
- **THEN** 测试 SHALL 覆盖 Git URL 无效、Git 命令不存在、认证失败、仓库不存在、checkout commit 不存在、凭据不存在、凭据项目不匹配、凭据类型不匹配和进程超时

#### Scenario: 覆盖 edge case
- **WHEN** 为 Git 同步运行时编写测试
- **THEN** 测试 SHALL 覆盖重复同步同一仓库、并发同步同一项目、空 branch 使用默认分支、失败清理临时目录、日志脱敏、任务输入不含明文凭据和 active revision 不被失败任务改变

### Requirement: 项目源码环境 API 必须执行项目访问守卫
项目、源码和环境 API SHALL 对项目级资源执行统一项目访问守卫，普通用户只能访问自己创建的项目资源，超级管理员可以访问任意未软删除项目资源。

#### Scenario: 查询和更新项目
- **WHEN** 已登录用户调用 `GET /api/projects/{projectId}`、`PATCH /api/projects/{projectId}` 或 `GET /api/projects/{projectId}/overview`
- **THEN** 后端 SHALL 校验当前用户有权访问该项目
- **AND** 无权限时 SHALL 返回与项目不存在一致的安全错误

#### Scenario: 查询项目列表
- **WHEN** 普通用户调用 `GET /api/projects`
- **THEN** 后端 SHALL 只返回当前用户创建的未软删除项目
- **AND** 后端 MUST NOT 返回其他用户创建的项目

#### Scenario: 超级管理员查询项目列表
- **WHEN** 超级管理员调用 `GET /api/projects`
- **THEN** 后端 SHALL 返回所有未软删除项目

#### Scenario: 访问源码 API
- **WHEN** 已登录用户调用源码列表、压缩包上传、Git 同步或源码版本切换 API
- **THEN** 后端 SHALL 校验当前用户有权访问 URL 中的项目
- **AND** 源码版本切换 SHALL 同时校验源码版本属于该项目
- **AND** 无权限时 SHALL 返回安全错误且 MUST NOT 暴露源码版本或服务器本机路径

#### Scenario: 访问环境 API
- **WHEN** 已登录用户调用环境列表、创建、详情、更新、禁用、快照、连接测试、环境检查、环境采集或脚本运行 API
- **THEN** 后端 SHALL 校验当前用户有权访问对应项目
- **AND** 对只包含 `environmentId` 的 API，后端 SHALL 根据环境所属项目执行访问守卫
- **AND** 无权限时 SHALL 返回安全错误且 MUST NOT 暴露环境主机、用户名、凭据摘要、跳板机摘要或快照内容

#### Scenario: 校验跳板机归属和访问权限
- **WHEN** 用户创建或更新环境并设置 `jumpHostEnvironmentId`
- **THEN** 后端 SHALL 验证跳板机属于同一项目
- **AND** 后端 SHALL 验证当前用户有权访问该项目
- **AND** 无权限时 SHALL 拒绝请求且 MUST NOT 暴露跳板机环境信息

### Requirement: 环境采集必须支持 SBOM 组件清单
环境采集 SHALL 为 SBOM 分析保存可复用的组件清单，并根据环境角色区分源侧已安装清单和目标侧仓库可用清单。该能力 SHALL 可由独立 `ENV_COLLECT` 任务调用，也 SHALL 可由 `SBOM_ANALYSIS` 任务作为内部步骤调用。

#### Scenario: 采集 x86 源环境组件
- **WHEN** worker 执行 `SOURCE_X86` 环境的 `ENV_COLLECT`
- **THEN** 后端 SHALL 采集已安装软件包、已安装库、基础工具版本和独立 native/so 文件摘要
- **AND** 后端 SHALL 将组件写入 `environment_components`
- **AND** `source` SHALL 标记为 `INSTALLED_PACKAGE`、`INSTALLED_LIBRARY`、`TOOL_VERSION` 或 `NATIVE_FILE`

#### Scenario: 采集 RISC-V 目标环境已安装组件
- **WHEN** worker 执行 `TARGET_RISCV` 环境的 `ENV_COLLECT`
- **THEN** 后端 SHALL 采集已安装软件包、已安装库、基础工具版本和独立 native/so 文件摘要
- **AND** 后端 SHALL 将组件写入 `environment_components`
- **AND** `source` SHALL 标记为 `INSTALLED_PACKAGE`、`INSTALLED_LIBRARY`、`TOOL_VERSION` 或 `NATIVE_FILE`

#### Scenario: 采集 RISC-V 目标环境仓库候选
- **WHEN** worker 执行 `TARGET_RISCV` 环境的 `ENV_COLLECT`
- **THEN** 后端 SHALL 尝试采集软件源可用包和仓库 provides/so 能力
- **AND** 仓库可用包 SHALL 写入 `environment_components` 并标记 `source=REPOSITORY_PACKAGE`
- **AND** 仓库 provides/so 能力 SHALL 写入 `environment_components` 并标记 `source=REPOSITORY_PROVIDE`
- **AND** 采集失败或命令不可用时 SHALL 在快照 `raw_snapshot` 中记录解析警告

#### Scenario: SBOM 内部采集复用环境采集语义
- **WHEN** `SBOM_ANALYSIS` 任务内部调用环境采集
- **THEN** 后端 SHALL 复用环境采集的脱敏、组件写入、仓库证据、ELF metadata、provider 证据和 unmanaged 工具证据规则
- **AND** 生成的环境快照 SHALL 关联当前 `SBOM_ANALYSIS` 任务 ID
- **AND** 采集日志 SHALL 写入当前 SBOM 任务的日志事件，供项目级 SSE 和任务日志接口恢复查看

### Requirement: 环境采集不得因仓库清单不完整阻断基础快照
目标环境仓库清单属于 SBOM 增强信息，基础 OS、包管理器、已安装组件和工具版本仍 SHALL 在可用时保存。

#### Scenario: 仓库 provides 命令不可用
- **WHEN** 目标环境无法执行仓库 provides 查询
- **THEN** `ENV_COLLECT` MAY 成功保存基础快照和已安装组件
- **AND** `raw_snapshot.parseWarnings` SHALL 记录仓库 provides 缺失原因
- **AND** 后续 SBOM 分析 SHALL 能基于已安装组件继续运行

#### Scenario: 已安装组件采集失败
- **WHEN** 已安装包或已安装库清单无法采集或无法解析
- **THEN** worker SHALL 将 `ENV_COLLECT` 任务标记为 `FAILED`
- **AND** 后端 MUST NOT 写入不完整成功快照

### Requirement: SBOM 组件采集必须保护路径和敏感信息
环境采集写入组件清单时 SHALL 避免把敏感路径、凭据或用户 home 文件内容暴露给 API、事件或日志摘要。

#### Scenario: 采集 native/so 文件
- **WHEN** 环境采集发现独立 native/so 文件
- **THEN** `environment_components.path` MAY 保存后端内部需要的远端文件路径
- **AND** API、任务事件和日志摘要 MUST NOT 暴露敏感路径或用户 home 文件内容
- **AND** 组件 `metadata` SHOULD 保存文件名、soname、哈希或大小等非敏感摘要

#### Scenario: 日志脱敏
- **WHEN** 采集脚本输出包含凭据、token、私钥、passphrase 或敏感路径
- **THEN** 后端 SHALL 在写入任务事件和日志 excerpt 前执行脱敏

### Requirement: 环境采集必须记录库文件 provider 证据
环境采集 SHALL 在源环境和目标环境中为 `.so` 与 native 文件记录可用的 provider/owner package 证据，供 SBOM 分析使用。

#### Scenario: x86 源环境采集库 owner package
- **WHEN** worker 执行 `SOURCE_X86` 环境的 `ENV_COLLECT`
- **THEN** 后端 SHALL 尝试查询已发现 `.so` 或 native 文件所属的软件包
- **AND** 后端 SHALL 将 owner package、owner version、owner arch 和查询工具写入对应 `environment_components.metadata`
- **AND** 后端 SHALL 保留原始库文件名和原始组件事实

#### Scenario: RISC-V 目标环境采集库 owner package
- **WHEN** worker 执行 `TARGET_RISCV` 环境的 `ENV_COLLECT`
- **THEN** 后端 SHALL 尝试查询已安装 `.so` 或 native 文件所属的软件包
- **AND** 后端 SHALL 将 owner package、owner version、owner arch 和查询工具写入对应 `environment_components.metadata`
- **AND** owner 查询失败时 SHALL 在快照 `raw_snapshot.parseWarnings` 中记录安全警告

#### Scenario: provider 查询工具缺失
- **WHEN** 远程环境缺少 `rpm`、`dpkg-query` 或其他可用 owner 查询工具
- **THEN** `ENV_COLLECT` MAY 成功保存基础快照和库文件组件
- **AND** 后端 SHALL 记录 provider 查询不可用的解析警告
- **AND** 后端 MUST NOT 将工具缺失伪装为库不存在

### Requirement: RISC-V 仓库候选采集必须准备 metadata
RISC-V 目标环境仓库候选采集 SHALL 在查询可用包或 provides 能力前执行受控 metadata refresh 或 metadata 可用性检查。

#### Scenario: metadata refresh 成功
- **WHEN** worker 执行 `TARGET_RISCV` 环境的 `ENV_COLLECT`
- **THEN** 后端 SHALL 在仓库候选查询前运行受控 metadata refresh 或校验命令
- **AND** 命令 SHALL 使用超时、低重试和 `skip_if_unavailable` 等非交互参数
- **AND** 后端 SHALL 在 `raw_snapshot.repositories` 或等效摘要中记录 refresh 成功状态

#### Scenario: metadata refresh 失败
- **WHEN** RISC-V 仓库 metadata refresh 因 DNS、超时、源不可用或 repo 配置错误失败
- **THEN** `ENV_COLLECT` MAY 成功保存基础快照、已安装组件和工具版本
- **AND** 后端 SHALL 记录 refresh 失败原因的安全摘要
- **AND** 后端 SHALL 将仓库候选结果标记为不完整

#### Scenario: metadata refresh 不允许提权
- **WHEN** 远程仓库 metadata refresh 需要 sudo 或交互输入
- **THEN** 后端 SHALL 跳过该 refresh 操作并记录警告
- **AND** 后端 MUST NOT 在 MVP 中要求或保存 sudo 密码

### Requirement: 环境采集必须生成架构归一化候选
环境采集 SHALL 为库文件名、库路径和包名生成跨架构查询候选，但不得覆盖原始事实。

#### Scenario: 归一化 x86 架构路径
- **WHEN** 源侧库路径包含 `x86_64`、`amd64` 或等效架构 token
- **THEN** 后端 SHALL 生成不含源架构 token 的 normalized library name
- **AND** 后端 MAY 生成 RISC-V 目标架构路径或 `*/libname.so*` 查询 pattern
- **AND** 后端 SHALL 在 metadata 中保存原始值和归一化依据

#### Scenario: 归一化包名架构后缀
- **WHEN** 源侧包名或库名包含 x86 架构后缀
- **THEN** 后端 SHALL 生成去除源架构 token 的 package candidate
- **AND** 后端 MAY 生成替换为目标架构 token 的 package candidate
- **AND** 这些候选 MUST 标记为查询候选而非事实字段

#### Scenario: 归一化候选可能冲突
- **WHEN** 多个源侧组件生成相同 normalized name 或 package candidate
- **THEN** 后端 SHALL 保留每个源组件的独立 fingerprint
- **AND** 后端 SHALL 在 metadata 中记录候选来源，避免合并不同组件事实

### Requirement: 环境采集必须提取 ELF metadata
环境采集 SHALL 对源 x86 和目标 RISC-V 环境中的 ELF `.so` 与 native 文件提取非敏感 ELF metadata，并将其写入 `environment_components.metadata`。

#### Scenario: 提取 ELF SONAME 和 NEEDED
- **WHEN** worker 执行 `SOURCE_X86` 或 `TARGET_RISCV` 环境的 `ENV_COLLECT`
- **THEN** 后端 SHALL 对采集到的 ELF `.so` 或 native 文件尝试提取 `SONAME`
- **AND** 后端 SHALL 尝试提取 `NEEDED` 依赖列表
- **AND** 后端 SHALL 将 `elfSoname`、`elfNeeded` 和 `elfScanner` 写入组件 metadata
- **AND** 后端 MUST NOT 将完整敏感路径或凭据写入 API 响应、事件或日志摘要

#### Scenario: 提取 ELF machine 和 class
- **WHEN** ELF header 可读取
- **THEN** 后端 SHALL 提取 ELF machine 和 ELF class
- **AND** 后端 SHALL 将 `elfMachine` 和 `elfClass` 写入组件 metadata
- **AND** 后端 SHALL 保留组件原始 `arch` 字段，不得用解析失败的 ELF 信息覆盖原始架构事实

#### Scenario: ELF 工具不可用
- **WHEN** 远程环境缺少 `readelf`、`objdump` 或等效 ELF metadata 工具
- **THEN** `ENV_COLLECT` MAY 成功保存基础快照、包、库和 owner package 信息
- **AND** 后端 SHALL 在 `raw_snapshot.parseWarnings` 中记录 ELF metadata scanner 不可用
- **AND** 后端 MUST NOT 将工具缺失解释为 ELF 文件不存在

### Requirement: 环境采集必须记录 installed ELF provides capability
环境采集 SHALL 在源 x86 和目标 RISC-V 环境中记录已安装 ELF 文件提供或依赖的 capability，用于 SBOM 分析做精确匹配。

#### Scenario: 记录源环境 ELF capability
- **WHEN** 源 x86 环境采集到 ELF `.so` 或 native 文件
- **THEN** 后端 SHALL 基于 `SONAME`、文件名和 owner package 生成 installed provides capability
- **AND** 后端 SHALL 将 `providesCapabilities` 写入组件 metadata
- **AND** 若存在 `NEEDED` 依赖，后端 SHALL 将 `requiredCapabilities` 写入组件 metadata

#### Scenario: 记录目标环境 ELF capability
- **WHEN** 目标 RISC-V 环境采集到已安装 ELF `.so` 或 native 文件
- **THEN** 后端 SHALL 记录目标侧 installed provides capability
- **AND** 后端 SHALL 保留目标 ELF machine/class 作为匹配 evidence
- **AND** 后端 SHALL 将 owner package、provider scanner 和 capability evidence 关联到同一组件 metadata

#### Scenario: capability 冲突
- **WHEN** 多个组件提供同一个 SONAME 或 capability
- **THEN** 后端 SHALL 保留每个组件独立记录
- **AND** 后端 SHALL 在 metadata 中记录 capability 来源，避免合并不同组件事实

### Requirement: 仓库 provides 查询必须优先使用 ELF capability
目标 RISC-V 仓库候选采集 SHALL 优先使用 ELF SONAME/capability 查询 repository provides，再 fallback 到路径、文件名和 normalized library name。

#### Scenario: 使用 exact SONAME 查询 repository provides
- **WHEN** 源侧或目标侧采集到 `elfSoname`
- **THEN** 后端 SHALL 尝试使用 exact SONAME 或包管理器支持的 ELF capability 表达式查询目标仓库
- **AND** 命中结果 SHALL 写入 `environment_components` 并标记 `source=REPOSITORY_PROVIDE`
- **AND** metadata SHALL 包含 `provideKind=ELF_SONAME`、`queryValue`、`packageName` 和 `scanner`

#### Scenario: fallback 到路径或文件名查询
- **WHEN** exact SONAME/capability 查询不可用或无命中
- **THEN** 后端 MAY 使用 `*/libname.so*`、文件名或 normalized library name 查询
- **AND** metadata SHALL 标记 fallback 查询类型
- **AND** 后端 SHALL 在 warning 或 metadata 中区分 fallback 证据与 exact capability 证据

#### Scenario: repository provides 查询失败
- **WHEN** repoquery、yum whatprovides 或 apt-file search 因 metadata、网络、权限或工具缺失失败
- **THEN** `ENV_COLLECT` MAY 成功保存基础快照和已安装组件
- **AND** 后端 SHALL 记录安全失败摘要
- **AND** 后端 MUST NOT 把 repository provides 缺失解释为目标环境无替代包

### Requirement: 环境采集必须记录 unmanaged 工具证据
环境采集 SHALL 对 PATH 可见的基础工具记录可执行事实，并在包管理器无法归属工具路径时保留 unmanaged 或 unknown 安装来源 metadata。

#### Scenario: package-managed 工具
- **WHEN** worker 执行 `ENV_COLLECT`
- **AND** 工具命令可通过 `command -v` 找到
- **AND** `rpm -qf`、`dpkg-query -S` 或等效 provider 查询能找到 owner package
- **THEN** 后端 SHALL 写入 `TOOL` 组件
- **AND** 组件 metadata SHALL 包含 `toolPath`、`ownerPackage`、`ownerVersion`、`ownerArch`、`providerScanner`
- **AND** 组件 metadata SHALL 包含 `installOrigin=package`
- **AND** 组件 metadata SHALL 包含 `versionText` 和可解析时的 `versionMajor`

#### Scenario: unmanaged 工具
- **WHEN** worker 执行 `ENV_COLLECT`
- **AND** 工具命令可通过 `command -v` 找到
- **AND** provider 查询无法找到 owner package
- **AND** 工具真实路径位于 `/usr/local`、`/opt`、`/home/*/.local` 或 `/root/.local`
- **THEN** 后端 SHALL 写入 `TOOL` 组件
- **AND** 组件 metadata SHALL 包含 `toolPath`
- **AND** 组件 metadata SHALL 包含 `installOrigin=unmanaged`
- **AND** 组件 metadata SHALL 包含 `providerScanner=none`
- **AND** 组件 metadata SHALL 包含 `versionText` 和可解析时的 `versionMajor`
- **AND** 后端 MUST NOT 因 owner package 为空而丢弃该工具组件

#### Scenario: unknown 来源工具
- **WHEN** worker 执行 `ENV_COLLECT`
- **AND** 工具命令可通过 `command -v` 找到
- **AND** provider 查询无法找到 owner package
- **AND** 工具路径不满足 unmanaged 路径规则
- **THEN** 后端 SHALL 写入 `TOOL` 组件
- **AND** 组件 metadata SHALL 包含 `installOrigin=unknown`
- **AND** 组件 metadata SHALL 包含 `providerScanner=none`
- **AND** 后端 SHALL 保留 `toolPath`、`versionText` 和可解析时的 `versionMajor`

#### Scenario: 工具不在 PATH
- **WHEN** 某个源码安装工具存在于远程文件系统但不在当前 PATH
- **THEN** 默认环境采集 MAY 不记录该工具
- **AND** 后端 MUST NOT 扫描用户 home、`/opt` 或 `/usr/local` 的全部可执行文件
- **AND** 后端 SHALL 仅基于已发现的 PATH 可见工具生成 `TOOL` 组件

### Requirement: CUSTOM_BASH 远程脚本必须真实执行
环境脚本运行时 SHALL 支持用户提交 `CUSTOM_BASH` 脚本，并 SHALL 在目标环境中以非交互方式真实执行。

#### Scenario: 创建 CUSTOM_BASH 脚本任务
- **WHEN** 已登录用户调用 `POST /api/projects/{projectId}/environments/{environmentId}/scripts` 并提交 `scriptType=CUSTOM_BASH` 和非空脚本正文
- **THEN** 后端 SHALL 校验用户有权访问该项目和环境
- **AND** 后端 SHALL 创建 `ENV_RUN_SCRIPT` 任务
- **AND** 后端 SHALL 安全保存脚本正文
- **AND** 任务输入快照 SHALL 只保存脚本引用、脚本 SHA256、脚本长度、工作目录、超时和环境引用
- **AND** 任务输入快照 MUST NOT 保存脚本正文、明文凭据、私钥、token、passphrase 或服务器真实路径

#### Scenario: Worker 执行 CUSTOM_BASH
- **WHEN** worker 认领 `ENV_RUN_SCRIPT` 且任务输入 `scriptType=CUSTOM_BASH`
- **THEN** worker SHALL 读取并校验脚本引用对应的脚本正文
- **AND** worker SHALL 在目标环境创建临时脚本文件并执行该脚本
- **AND** worker SHALL 捕获 stdout、stderr、退出码和耗时
- **AND** worker SHALL 在执行完成或失败后尽力清理远端临时脚本文件
- **AND** worker MUST NOT 在日志摘要、任务事件、审计详情或 API 响应中暴露脚本正文

#### Scenario: CUSTOM_BASH 执行成功
- **WHEN** 远程脚本退出码为 0
- **THEN** worker SHALL 将任务标记为 `SUCCEEDED`
- **AND** SHALL 写入 `tool_runs` 执行记录
- **AND** `tool_runs.exit_code` SHALL 保存退出码
- **AND** `tool_runs.summary` SHALL 保存脚本类型、脚本 SHA256、脚本长度、工作目录配置状态、耗时和日志事件数量
- **AND** 任务级日志 SSE SHALL 能观察到 stdout/stderr 日志事件和成功终态

#### Scenario: CUSTOM_BASH 执行失败
- **WHEN** 远程脚本返回非 0 退出码、远程执行失败、脚本引用不存在或脚本 hash 校验失败
- **THEN** worker SHALL 将任务标记为 `FAILED`
- **AND** SHALL 写入失败态 `tool_runs`
- **AND** SHALL 保存 stdout/stderr 日志和安全错误摘要
- **AND** 任务级日志 SSE SHALL 能观察到已产生日志和失败终态

#### Scenario: 拒绝空脚本
- **WHEN** 用户提交 `scriptType=CUSTOM_BASH` 但脚本正文为空
- **THEN** API SHALL 返回参数校验错误
- **AND** 后端 MUST NOT 创建任务
- **AND** 后端 MUST NOT 保存脚本正文或投递队列消息

### Requirement: 脚本执行记录必须可恢复查看
后端 SHALL 提供脚本执行记录视图，使前端能在页面关闭后重新打开并恢复查看历史日志和当前执行状态。

#### Scenario: 查询项目脚本执行记录
- **WHEN** 已登录用户查询某个项目的脚本执行记录
- **THEN** 后端 SHALL 返回该项目下 `ENV_RUN_SCRIPT` 脚本任务的分页列表
- **AND** 列表项 SHALL 包含执行记录 ID、任务 ID、环境摘要、脚本类型、脚本 SHA256、脚本长度、执行状态、退出码、开始时间、结束时间和日志摘要
- **AND** 后端 SHALL 支持按状态、脚本类型、环境 ID、创建时间范围过滤
- **AND** 后端 SHALL 支持按创建时间、开始时间、结束时间和状态排序

#### Scenario: 执行记录状态映射
- **WHEN** 后端返回脚本执行记录
- **THEN** `PENDING`、`QUEUED` 和 `RUNNING` 任务状态 SHALL 映射为执行记录状态 `RUNNING`
- **AND** `SUCCEEDED` 任务状态 SHALL 映射为执行记录状态 `SUCCEEDED`
- **AND** `FAILED`、`TIMEOUT` 和 `CANCELLED` 任务状态 SHALL 映射为执行记录状态 `ERROR`
- **AND** 执行记录状态 MUST NOT 引入独立于 `tasks.status` 的第二套事实状态机

#### Scenario: 恢复运行中执行记录
- **WHEN** 用户重新打开仍在运行的脚本执行记录
- **THEN** 前端 SHALL 能读取执行记录详情
- **AND** 前端 SHALL 能通过任务级日志 SSE 获取全部历史日志并继续接收新增日志
- **AND** 后端 MUST NOT 因页面关闭或 SSE 断开而取消运行中的脚本任务

#### Scenario: 执行记录权限校验
- **WHEN** 用户查询脚本执行记录、执行记录详情或执行记录日志
- **THEN** 后端 SHALL 根据记录所属项目执行项目访问守卫
- **AND** 无权限时 SHALL 返回与资源不存在一致的安全错误
- **AND** 后端 MUST NOT 暴露目标项目、环境、任务、脚本摘要或日志是否存在

### Requirement: 项目必须在首次成功源码入库后锁定源码来源
后端 SHALL 在项目级记录源码来源锁定状态，并 SHALL 防止同一项目混用压缩包上传和 Git 同步两种源码入库方式。

#### Scenario: 新项目尚未锁定源码来源
- **WHEN** 已登录用户创建新项目
- **THEN** 后端 SHALL 将项目源码来源锁定字段保存为空
- **AND** 后端 SHALL 允许该项目后续选择压缩包上传或 Git 同步作为首次源码入库方式

#### Scenario: 压缩包首次成功入库后锁定 ARCHIVE
- **WHEN** 项目源码来源尚未锁定
- **AND** 用户通过 `POST /api/projects/{projectId}/sources/archive` 成功上传、校验并解包源码包
- **THEN** 后端 SHALL 将项目源码来源锁定为 `ARCHIVE`
- **AND** 后端 SHALL 创建 `revision_type=ARCHIVE` 的源码版本
- **AND** 后端 SHALL 创建 `input_type=ARCHIVE` 且 `status=SUCCEEDED` 的导入记录

#### Scenario: Git 首次成功同步后锁定 GIT
- **WHEN** 项目源码来源尚未锁定
- **AND** `SOURCE_GIT_SYNC` worker 成功完成 Git clone 或 fetch checkout
- **THEN** 后端 SHALL 将项目源码来源锁定为 `GIT`
- **AND** 后端 SHALL 创建 `revision_type=GIT` 的源码版本
- **AND** 后端 SHALL 将对应导入记录更新为 `SUCCEEDED`

#### Scenario: 失败入库不锁定源码来源
- **WHEN** 压缩包保存、格式校验、解包、源码结构校验或 Git 同步失败
- **THEN** 后端 MUST NOT 将空源码来源锁定字段写为 `ARCHIVE` 或 `GIT`
- **AND** 后端 MUST NOT 创建成功态源码版本

#### Scenario: 拒绝与锁定来源不一致的压缩包上传
- **WHEN** 项目源码来源已锁定为 `GIT`
- **AND** 用户调用 `POST /api/projects/{projectId}/sources/archive`
- **THEN** 后端 SHALL 返回 `SOURCE_INPUT_TYPE_LOCKED` 错误
- **AND** 后端 MUST NOT 保存上传文件、解包源码、创建源码版本或创建成功导入记录

#### Scenario: 拒绝与锁定来源不一致的 Git 同步
- **WHEN** 项目源码来源已锁定为 `ARCHIVE`
- **AND** 用户调用 `POST /api/projects/{projectId}/sources/git-sync`
- **THEN** 后端 SHALL 返回 `SOURCE_INPUT_TYPE_LOCKED` 错误
- **AND** 后端 MUST NOT 创建 Git 同步任务、创建导入记录或投递队列消息

### Requirement: 压缩包源码上传必须自动解包到源码运行目录
后端 SHALL 在压缩包上传成功后自动解包源码包，并 SHALL 将解包后的源码目录作为源码版本工作副本保存。

#### Scenario: 支持指定压缩包格式
- **WHEN** 用户上传源码压缩包
- **THEN** 后端 SHALL 支持 `zip`、`rar`、`7z`、`tar.gz`、`tgz`、`tar.xz` 和 `gzip`
- **AND** 后端 SHALL 对不支持的格式返回 `SOURCE_ARCHIVE_UNSUPPORTED_FORMAT`

#### Scenario: 解包到独立源码目录
- **WHEN** 源码压缩包格式有效且内容校验通过
- **THEN** 后端 SHALL 将源码解包到独立运行目录
- **AND** 运行目录 SHALL 与 `projectId` 和 `sourceRevisionId` 绑定
- **AND** `source_revisions.working_copy_path` SHALL 保存该运行目录作为内部字段
- **AND** API 响应 MUST NOT 返回该服务器本地路径

#### Scenario: 保存原始压缩包和解包目录
- **WHEN** 压缩包源码上传成功
- **THEN** 后端 SHALL 保存原始上传包到受控上传目录
- **AND** `source_revisions.archive_path` SHALL 保存原始上传包路径作为内部字段
- **AND** `source_revisions.working_copy_path` SHALL 保存解包后的源码工作副本路径

#### Scenario: gzip 单文件压缩结构校验
- **WHEN** 用户上传单文件 `gzip` 源码包
- **THEN** 后端 SHALL 解压出单个文件
- **AND** 后端 SHALL 校验该文件或其所在目录满足源码输入最小结构要求
- **AND** 如果无法形成可用源码输入，后端 SHALL 返回 `SOURCE_ARCHIVE_UNSUPPORTED_STRUCTURE`

#### Scenario: 防止压缩包路径逃逸
- **WHEN** 压缩包 entry 包含绝对路径、`..` 路径段或 normalize 后逃逸目标目录的路径
- **THEN** 后端 SHALL 拒绝该压缩包并返回 `SOURCE_ARCHIVE_UNSAFE_PATH`
- **AND** 后端 MUST NOT 将逃逸文件写入目标目录之外
- **AND** 后端 MUST 尽力清理本次临时解包目录

#### Scenario: 拒绝空源码包
- **WHEN** 压缩包为空、只包含目录或解包后没有可用文件
- **THEN** 后端 SHALL 返回 `SOURCE_ARCHIVE_EMPTY`
- **AND** 后端 MUST NOT 创建成功态源码版本

#### Scenario: 修复后再次上传同来源源码
- **WHEN** 项目源码来源已锁定为 `ARCHIVE`
- **AND** 用户再次上传修复后的有效源码包
- **THEN** 后端 SHALL 创建新的 `ARCHIVE` 源码版本
- **AND** 后端 MUST NOT 覆盖旧源码版本记录或旧源码工作目录

#### Scenario: 修复后再次 Git 同步同来源源码
- **WHEN** 项目源码来源已锁定为 `GIT`
- **AND** 用户再次创建 Git 同步任务
- **THEN** 后端 SHALL 允许创建新的 Git 同步任务
- **AND** worker 成功后 SHALL 创建新的 `GIT` 源码版本
- **AND** 后端 MUST NOT 覆盖旧源码版本记录或旧源码工作目录

### Requirement: 源码版本必须可作为 KB 扫描输入
项目源码管理 SHALL 为 KB 扫描提供后端本机可读的源码工作副本，并 SHALL 防止跨项目扫描源码。

#### Scenario: 当前启用源码版本用于 KB 扫描
- **WHEN** 项目存在当前启用源码版本且用户创建 KB 扫描未指定 `sourceRevisionId`
- **THEN** 后端 SHALL 使用当前启用源码版本作为扫描输入
- **AND** 后端 SHALL 校验该源码版本的工作副本可用
- **AND** API 响应和任务输入快照 MUST NOT 暴露 `working_copy_path`

#### Scenario: 指定源码版本用于 KB 扫描
- **WHEN** 用户创建 KB 扫描并指定 `sourceRevisionId`
- **THEN** 后端 SHALL 校验源码版本属于 URL 中的 `projectId`
- **AND** 后端 SHALL 校验该源码版本不是失败导入产生的无效版本
- **AND** 后端 SHALL 校验工作副本可供后端本机 scanner 读取

#### Scenario: 源码工作副本不可用
- **WHEN** 源码版本缺少工作副本、工作副本被清理或后端不可读取
- **THEN** 后端 SHALL 拒绝创建 KB 扫描任务或将任务安全失败
- **AND** 错误摘要 MUST NOT 暴露服务器真实路径
- **AND** 后端 MUST NOT 投递无法执行的 scanner 任务，除非失败只能在 worker 阶段发现

### Requirement: KB 扫描不得修改源码版本事实
KB 扫描 SHALL 只读取源码工作副本，不得修改源码版本记录、当前启用版本或源码来源锁定状态。

#### Scenario: KB 扫描成功
- **WHEN** KB_SCAN 任务成功完成
- **THEN** 后端 MUST NOT 修改 `source_revisions` 的源码内容事实
- **AND** 后端 MUST NOT 修改项目 `sourceInputType`
- **AND** 后端 MUST NOT 自动切换当前启用源码版本

#### Scenario: KB 扫描失败
- **WHEN** KB_SCAN 任务失败或取消
- **THEN** 后端 MUST NOT 回滚或删除被扫描的源码版本
- **AND** 后端 MUST NOT 改变当前启用源码版本

### Requirement: 源码压缩包输入必须具备可配置的大文件上传能力
源码管理能力 SHALL 将源码压缩包上传上限作为部署配置管理，默认支持最大 10GB 的源码包输入。

#### Scenario: 默认大文件上传上限
- **WHEN** 后端使用默认配置启动
- **THEN** multipart 单文件大小上限 SHALL 为 10GB
- **AND** multipart 请求总大小上限 SHALL 为 10GB
- **AND** 后端 MUST NOT 使用 Spring/Tomcat 默认 1MiB 限制作为源码包上传上限

#### Scenario: 部署时调整上传上限
- **WHEN** 运维通过环境变量配置上传大小上限
- **THEN** 后端 SHALL 使用配置值作为 multipart 单文件和请求总大小上限
- **AND** 源码上传 API SHALL 遵循该配置进行请求接收

#### Scenario: 大文件上传仍遵循源码来源锁定
- **WHEN** 项目源码来源已锁定为 `GIT`
- **AND** 用户上传任意大小的源码压缩包
- **THEN** 后端 SHALL 返回 `SOURCE_INPUT_TYPE_LOCKED`
- **AND** 后端 MUST NOT 保存上传文件、解包源码或创建源码版本

### Requirement: Git 源码输入必须规范化常见仓库 URL
Git 源码输入 SHALL 在创建源码导入任务前规范化常见仓库 URL，减少用户输入是否带 `.git` 后缀造成的行为差异。

#### Scenario: 规范化 HTTP(S) 仓库 URL
- **WHEN** Git 源码输入为 HTTP(S) URL 且 path 最后一段未以 `.git` 结尾
- **THEN** 后端 SHALL 去除末尾 `/` 并追加 `.git`
- **AND** `source_imports` 关联任务 input snapshot SHALL 使用规范化后的 URL
- **AND** 后续 `source_revisions.git_url` SHALL 保存规范化后的 URL

#### Scenario: 规范化 SSH scp 仓库 URL
- **WHEN** Git 源码输入为 SSH scp 风格 URL 且仓库路径未以 `.git` 结尾
- **THEN** 后端 SHALL 去除末尾 `/` 并追加 `.git`
- **AND** Git worker SHALL 使用规范化后的 URL 执行 clone

#### Scenario: 不重复追加 .git
- **WHEN** Git 源码输入已经以 `.git` 结尾
- **THEN** 后端 SHALL 保持 URL 不变
- **AND** 后端 MUST NOT 生成 `.git.git` 形式的 URL

#### Scenario: 保留 Git URL 安全边界
- **WHEN** Git 源码输入包含 HTTP(S) 内嵌凭据
- **THEN** 后端 SHALL 拒绝请求
- **AND** 后端 MUST NOT 创建源码导入记录、任务、凭据或审计中的敏感 URL

### Requirement: 源码版本创建后必须隔离迁移输入
项目源码管理 SHALL 保证每个成功创建的源码版本拥有独立迁移输入作用域。

#### Scenario: 压缩包源码入库后迁移输入为空
- **WHEN** 用户通过压缩包上传成功创建新的 `source_revision`
- **THEN** 后端 SHALL 为该源码版本提供空迁移输入集合
- **AND** 后端 MUST NOT 复制上一源码版本迁移输入 reference

#### Scenario: Git 同步成功后迁移输入为空
- **WHEN** `SOURCE_GIT_SYNC` worker 成功创建新的 `source_revision`
- **THEN** 后端 SHALL 为该源码版本提供空迁移输入集合
- **AND** 后端 MUST NOT 复制上一源码版本迁移输入 reference

#### Scenario: 失败源码入库不创建迁移输入事实
- **WHEN** 压缩包上传、解包、源码结构校验或 Git 同步失败
- **THEN** 后端 MUST NOT 为失败入库创建可用迁移输入集合
- **AND** 后端 MUST NOT 改变当前 active source revision 的迁移输入集合

### Requirement: 环境连接测试必须支持未落库目标配置
环境运行时 SHALL 支持基于未创建环境的 SSH 目标配置执行一次性连接预检，并 SHALL 将预检结果限定在任务事实内。

#### Scenario: 执行新建环境连接预检任务
- **WHEN** worker 认领由新建环境预检接口创建的 `ENV_TEST_CONNECTION` 任务
- **THEN** worker SHALL 使用任务输入中的目标主机、端口、用户名、正式凭据引用或任务级临时凭据引用、工作目录和可选跳板机执行 SSH 探测
- **AND** worker SHALL 写入任务进度、任务日志和项目级 SSE 事件
- **AND** worker SHALL 根据探测结果将任务标记为 `SUCCEEDED` 或 `FAILED`
- **AND** worker MUST NOT 创建或更新 `environments`、`environment_snapshots` 或 `environment_components`

#### Scenario: 读取任务级临时 SSH 凭据
- **WHEN** target 模式任务输入包含 `temporaryCredentialPayloadId` 且不包含 `credentialId`
- **THEN** worker SHALL 按 `projectId`、`taskId` 和 payload ID 读取 `task_secret_payloads`
- **AND** worker SHALL 解密并校验 payload hash 后构造 SSH runtime 凭据
- **AND** worker MUST NOT 查询或创建正式 `credentials` 记录
- **AND** 解密失败或 hash 不匹配时任务 SHALL 失败并返回安全错误

#### Scenario: 任务终态后清理任务级临时 SSH 凭据
- **GIVEN** target 模式环境预检在 `task_secret_payloads` 保存了加密凭据
- **WHEN** 任务进入 `SUCCEEDED`、`FAILED`、`CANCELLED` 或 `TIMEOUT`
- **THEN** worker SHALL 删除该任务关联的 `task_secret_payloads` 记录
- **AND** worker SHALL 保留任务状态、任务事件和任务日志供前端查看
- **AND** worker MUST NOT 在任务重试重新入队时删除临时凭据

#### Scenario: 新建环境预检成功
- **WHEN** 新建环境预检 SSH 探测成功且输出包含预期探测标记
- **THEN** 任务 SHALL 进入 `SUCCEEDED`
- **AND** 任务日志和 tool run summary SHALL 保存脱敏后的 OS、arch、远程用户、工作目录检查和基础工具检查摘要
- **AND** 后端 MUST NOT 更新任何正式环境的 `status` 或 `last_connected_at`

#### Scenario: 新建环境预检失败
- **WHEN** 新建环境预检发生连接失败、认证失败、跳板机失败、探测命令失败或超时
- **THEN** 任务 SHALL 进入 `FAILED` 或 `TIMEOUT`
- **AND** 错误摘要 SHALL 使用安全错误码和安全消息
- **AND** 后端 MUST NOT 创建无效环境记录
- **AND** 日志、事件和审计 MUST NOT 暴露明文凭据

#### Scenario: 复测已创建环境仍更新环境状态
- **WHEN** worker 认领包含 `environmentId` 的 `ENV_TEST_CONNECTION` 任务
- **THEN** worker SHALL 保持现有行为，基于正式环境配置执行连接测试
- **AND** 成功时 SHALL 更新对应环境为可连接状态
- **AND** 失败时 SHALL 更新对应环境为连接失败状态

### Requirement: 环境查询必须支持运行状态筛选
环境管理能力 SHALL 允许调用方按 `environments.status` 查询环境列表，并 SHALL 与角色、启用状态过滤共同生效。

#### Scenario: 查询可用环境
- **WHEN** 用户查询项目环境列表并提交 `status=AVAILABLE`
- **THEN** 后端 SHALL 只返回状态为 `AVAILABLE` 的环境
- **AND** 返回环境 MAY 包含最新成功基础信息摘要
- **AND** 后端 MUST NOT 因缺少最新基础信息摘要而隐藏状态可用的环境

#### Scenario: 查询禁用环境状态
- **WHEN** 用户查询项目环境列表并提交 `enabled=false` 或 `status=DISABLED`
- **THEN** 后端 SHALL 按请求条件返回匹配环境
- **AND** 禁用环境 MUST NOT 被新建任务接口作为可运行环境使用

#### Scenario: 组合筛选 SBOM 可选环境
- **WHEN** 前端为 SBOM 配置查询源环境或目标环境
- **THEN** 后端 SHALL 支持 `role=SOURCE_X86&status=AVAILABLE&enabled=true`
- **AND** 后端 SHALL 支持 `role=TARGET_RISCV&status=AVAILABLE&enabled=true`
- **AND** 响应 SHALL 保持分页、凭据遮蔽摘要和跳板机非敏感摘要

### Requirement: 环境采集必须显式处理采集辅助工具缺失
环境采集 SHALL 在源环境和目标环境中检测采集辅助工具可用性，并 SHALL 在工具缺失、安装失败或降级采集时记录可审计的 warning。

#### Scenario: 采集前检测源环境辅助工具
- **WHEN** worker 在源环境执行依赖分析前环境刷新或环境采集
- **THEN** 后端 SHALL 检测 `find`、`readelf`、`objdump`、`rpm` 或对应包管理器查询工具是否可用
- **AND** 后端 SHALL 检测构建观测所需的 `strace` 和 `bear` 是否可用
- **AND** 检测结果 SHALL 写入采集日志或快照 raw summary

#### Scenario: 采集前检测目标环境辅助工具
- **WHEN** worker 在目标环境执行依赖分析前环境刷新或环境采集
- **THEN** 后端 SHALL 检测 `find`、`readelf`、`objdump`、`rpm` 或对应包管理器查询工具是否可用
- **AND** 后端 MUST NOT 要求目标环境安装 `strace` 或 `bear` 才能完成基础 provider 采集
- **AND** 检测结果 SHALL 写入采集日志或快照 raw summary

#### Scenario: 工具缺失时记录 warning
- **WHEN** 远程环境缺少采集辅助工具且无法补齐
- **THEN** 环境快照 raw summary SHALL 包含明确的 `PARSE_WARNING`
- **AND** warning SHALL 指明缺失工具名和受影响的采集范围
- **AND** 后端 MUST NOT 静默产生空的库文件清单并把它解释为目标环境没有库

### Requirement: 环境采集必须受控尝试安装缺失辅助工具
依赖分析触发的环境刷新 SHALL 在源环境和目标环境中对缺失的采集辅助工具执行受控自动安装尝试，并 SHALL 保持安装范围可预测、可审计。

#### Scenario: 源环境补齐构建观测工具
- **WHEN** 依赖分析前刷新源环境且检测到 `find`、`readelf`、`objdump`、`strace` 或 `bear` 缺失
- **THEN** 后端 SHALL 根据包管理器能力尝试安装固定 allowlist 中的 `findutils`、`binutils`、`strace` 和 `bear`
- **AND** 安装命令、成功或失败摘要 SHALL 写入任务日志或快照 warning
- **AND** 安装失败时后端 SHALL 继续使用已有构建日志、已有 compile database 或其他可用证据完成分析

#### Scenario: 目标环境补齐 provider 采集工具
- **WHEN** 依赖分析前刷新目标环境且检测到 `find`、`readelf` 或 `objdump` 缺失
- **THEN** 后端 SHALL 根据包管理器能力尝试安装固定 allowlist 中的 `findutils` 和 `binutils`
- **AND** 安装命令、成功或失败摘要 SHALL 写入任务日志或快照 warning
- **AND** 安装失败时后端 SHALL 继续保存已安装包、仓库包和其他可用 provider 证据

#### Scenario: 非支持包管理器环境
- **WHEN** 远程环境缺少辅助工具且后端无法识别可支持的安装命令
- **THEN** 后端 SHALL 跳过自动安装
- **AND** 快照 SHALL 记录自动安装不可用的 warning
- **AND** 后端 MUST NOT 执行非 allowlist 的任意安装命令

### Requirement: 库文件采集必须具备标准目录降级扫描
环境采集 SHALL 在标准库目录中采集 `.so`、版本化 `.so` 和相关符号链接，并 SHALL 在 `find` 不可用时使用降级策略保留基础库证据。

#### Scenario: find 可用时完整扫描标准库目录
- **WHEN** 远程环境存在 `find`
- **THEN** 后端 SHALL 扫描 `/lib`、`/lib64`、`/usr/lib`、`/usr/lib64`、`/usr/local/lib` 中的 `.so` 文件
- **AND** 后端 SHALL 为可归属文件记录 owner package、owner version、owner arch 和 provider scanner

#### Scenario: find 不可用时降级扫描标准库目录
- **WHEN** 远程环境缺少 `find`
- **THEN** 后端 SHALL 使用 shell glob 或等价固定深度策略扫描标准库目录中常见 `.so` 文件
- **AND** 对发现的文件仍 SHALL 尝试执行 owner package 查询
- **AND** 快照 SHALL 记录本次使用了降级扫描

#### Scenario: 标准库符号链接目录
- **WHEN** `/lib64` 或其他标准库目录是指向 `/usr/lib64` 的符号链接
- **THEN** 后端 SHALL 保证实际库文件或符号链接路径至少有一类路径被采集
- **AND** 后端 MUST NOT 因目录是符号链接而完全跳过基础库文件

### Requirement: 环境管理必须维护项目当前运行环境
环境管理能力 SHALL 提供项目当前运行环境配置接口，允许用户在环境管理中选择源环境和目标环境作为后续远程运行节点的统一环境来源。

#### Scenario: 环境管理页读取当前运行环境
- **WHEN** 前端打开环境管理页
- **THEN** 后端 SHALL 返回当前项目的源环境、目标环境、配置版本和 ready 状态
- **AND** ready 只有在源环境和目标环境都已配置且可用时为 `true`

#### Scenario: 环境管理页局部更新运行环境
- **WHEN** 用户只修改源环境或只修改目标环境
- **THEN** 后端 SHALL 接受局部更新
- **AND** 后端 SHALL 保留未提交一侧的既有环境配置
- **AND** 后端 SHALL 递增配置版本

#### Scenario: 禁用当前运行环境
- **WHEN** 用户尝试禁用当前运行环境配置引用的源环境或目标环境
- **THEN** 后端 SHALL 拒绝禁用或要求先解除运行环境配置
- **AND** 后端 MUST NOT 让当前运行环境引用禁用环境后仍显示 ready

#### Scenario: 项目概览展示运行环境状态
- **WHEN** 前端调用项目 overview
- **THEN** 响应 SHALL 包含当前运行环境配置摘要、版本和 ready 状态
- **AND** 缺少源环境或目标环境时 SHALL 如实显示环境缺口，但 MUST NOT 无条件覆盖主线推荐
- **AND** 只有当前主线步骤确实依赖缺失环境时，推荐下一步 SHALL 为进入环境管理补齐配置

#### Scenario: 源码分析和报告不因环境缺失改为推荐环境管理
- **WHEN** 当前已有活动源码且可以执行知识库扫描或生成结果评估，运行环境尚未补齐
- **THEN** overview SHALL 按主线与就绪事实推荐 kb 或 report
- **AND** 环境 ready SHALL 继续为 false，不伪造环境可用事实

### Requirement: 环境有效配置变更必须使当前验证事实失效
环境管理 SHALL 区分有效运行配置和纯展示字段，并 SHALL 在有效运行配置变化后使旧连接测试与采集事实失效。

#### Scenario: 修改环境有效配置
- **WHEN** 用户修改环境的角色、架构、主机、SSH 端口、用户名、凭据、跳板机、工作目录或启用状态
- **THEN** 后端 SHALL 递增该环境的 `configVersion`
- **AND** 后端 SHALL 将当前状态重置为 `UNKNOWN`
- **AND** 后端 SHALL 清空当前连接时间和当前采集时间

#### Scenario: 修改环境展示字段
- **WHEN** 用户只修改环境名称等不影响远程执行的展示字段
- **THEN** 后端 SHALL 保持 `configVersion` 不变
- **AND** 当前连接测试、采集快照和环境状态 SHALL 保持有效

#### Scenario: 环境详情只返回当前版本基础信息
- **WHEN** 前端查询环境详情
- **THEN** 后端 SHALL 只使用与环境当前 `configVersion` 匹配的成功快照生成基础信息
- **AND** 仅存在旧配置版本快照时基础信息 SHALL 为空或明确标识未采集
- **AND** 后端 MUST NOT 把旧版本操作系统、软件包或连接信息作为当前事实返回

#### Scenario: 凭据变更传播到引用环境
- **WHEN** 管理员修改被一个或多个环境引用的凭据内容
- **THEN** 后端 SHALL 使所有受影响环境的有效配置版本递增
- **AND** 后端 SHALL 使这些环境的当前连接测试与采集事实失效

#### Scenario: 跳板机变更传播到下游环境
- **WHEN** 用户修改被其他环境选为跳板机的环境有效配置
- **THEN** 后端 SHALL 使直接依赖该跳板机的环境配置版本递增
- **AND** 后端 SHALL 对这些环境应用相同的状态和采集失效规则
