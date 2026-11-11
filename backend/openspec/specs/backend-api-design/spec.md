## Purpose

定义 RvPort 后端 MVP API 设计的接口边界、通用约定、认证用户管理、长任务实时通道和核心领域资源映射要求。
## Requirements
### Requirement: 后端 API 设计文档使用中文并落在 backend 内
后端 API 设计 SHALL 在 backend 目录内维护中文文档，并 SHALL 以根目录 API 设计为底稿进行收敛，而不是原样复制旧接口边界。

#### Scenario: 创建后端 API 设计文档
- **WHEN** 创建正式后端 API 设计文档
- **THEN** 文档 SHALL 位于 `backend/docs/API_DESIGN.md`
- **AND** 说明文字 SHALL 使用中文
- **AND** 接口路径、HTTP 方法、字段名、枚举值和协议关键字 MAY 保留英文

#### Scenario: 复用根目录 API 底稿
- **WHEN** 从根目录 `docs/design/01_API_DESIGN.md` 迁移 API 设计内容
- **THEN** 后端文档 SHALL 先对齐当前数据库设计边界，再保留可复用的模块划分、页面触发和交互说明

### Requirement: API 通用约定必须统一
后端 API 设计 SHALL 定义统一的 base path、响应包络、错误格式、分页格式、时间格式、列表查询能力、字段长度边界和浏览器 Session 安全约定。除非对应 spec 明确说明业务上不是列表查询，所有返回集合或列表数据的接口 SHALL 支持分页、排序和过滤。

#### Scenario: 返回成功响应
- **WHEN** API 请求成功
- **THEN** 响应 SHALL 使用统一响应包络，包含业务数据、请求追踪 ID 和时间戳

#### Scenario: 返回错误响应
- **WHEN** API 请求失败
- **THEN** 响应 SHALL 使用统一错误格式，包含错误码、错误消息、可选字段级详情、请求追踪 ID 和时间戳

#### Scenario: 返回分页列表
- **WHEN** API 返回列表数据
- **THEN** 响应 SHALL 支持 `page`、`pageSize`、`total`、`hasMore` 和 `items`
- **AND** 请求 SHALL 支持 `page` 和 `pageSize`
- **AND** 后端 SHALL 对 `page` 和 `pageSize` 执行边界校验和最大页大小限制

#### Scenario: 列表接口支持过滤
- **WHEN** API 返回列表数据
- **THEN** 请求 SHALL 支持该资源有业务意义的过滤字段
- **AND** 多个过滤条件 SHALL 使用 AND 语义，除非对应接口 spec 明确说明其他语义
- **AND** 过滤字段 SHALL 使用白名单定义
- **AND** 后端 MUST NOT 将客户端传入的任意字段名直接拼接到 SQL

#### Scenario: 列表接口支持排序
- **WHEN** API 返回列表数据
- **THEN** 请求 SHALL 支持 `sortBy` 和 `sortDirection` 或等效排序参数
- **AND** 后端 SHALL 为每个列表接口定义排序字段白名单
- **AND** 后端 SHALL 支持升序和降序
- **AND** 每个列表接口 SHALL 定义稳定默认排序
- **AND** 后端 MUST 拒绝不在白名单内的排序字段

#### Scenario: 列表能力例外
- **WHEN** 某个集合接口由于业务语义、协议语义或数据规模不适合分页、排序或过滤
- **THEN** 对应 capability spec MUST 明确说明例外原因
- **AND** 该接口 MUST 仍然保证响应规模可控

#### Scenario: 认证字段长度边界
- **WHEN** API 接收登录名或明文密码输入
- **THEN** `username` SHALL 限制为最大 64 字符
- **AND** 明文 `password`、`currentPassword`、`newPassword` SHALL 限制为最大 16 字符
- **AND** 超过长度限制时 API SHALL 返回统一参数校验错误

#### Scenario: 使用 Session Cookie 进行浏览器认证
- **WHEN** 前端调用需要登录态的 API
- **THEN** 前端 SHALL 依赖浏览器自动携带 HttpOnly Session Cookie
- **AND** 前端 MUST NOT 通过读取 JWT 恢复 MVP 登录态

#### Scenario: 状态变更请求提交 CSRF token
- **WHEN** 前端使用 Session Cookie 调用状态变更 API
- **THEN** 前端 SHALL 能先获取 CSRF token
- **AND** 状态变更请求 SHALL 通过约定 header 提交 CSRF token

### Requirement: 认证 API 不暴露角色权限模型
MVP 认证 API SHALL 只返回本地用户信息和超级管理员标记，不 SHALL 暴露角色、权限或项目成员模型。

#### Scenario: 获取当前用户
- **WHEN** 前端调用 `GET /api/auth/me`
- **THEN** 响应 SHALL 包含用户 ID、用户名、展示名、邮箱、状态、`isSuperAdmin` 和 `mustChangePassword`
- **AND** 响应 MUST NOT 包含 `roles`、`permissions`、`projectMembers` 或 `passwordHash`

#### Scenario: 用户登录成功
- **WHEN** 用户通过本地账号登录且 `username` 不超过 64 字符、`password` 不超过 16 字符
- **THEN** API SHALL 建立基于服务端 Session 和 Cookie 的登录态
- **AND** 响应 SHALL 返回当前用户信息
- **AND** 响应和日志 MUST NOT 返回明文密码、密码哈希、角色、权限或项目成员信息

#### Scenario: 用户登录输入超过长度
- **WHEN** 用户调用 `POST /api/auth/login` 且 `username` 超过 64 字符或 `password` 超过 16 字符
- **THEN** API SHALL 返回参数校验错误
- **AND** 后端 MUST NOT 执行密码哈希校验

#### Scenario: 用户登录失败
- **WHEN** 用户名不存在、密码错误或用户状态不允许登录
- **THEN** API SHALL 返回认证失败或用户禁用错误
- **AND** 响应和日志 MUST NOT 暴露明文密码
- **AND** 响应 SHOULD NOT 泄漏可被枚举利用的账号存在性细节

#### Scenario: 当前用户修改密码
- **WHEN** 已登录用户调用 `POST /api/auth/change-password` 且 `currentPassword` 和 `newPassword` 均不超过 16 字符
- **THEN** API SHALL 校验当前密码并保存新密码哈希
- **AND** 成功后 SHALL 将用户的 `mustChangePassword` 标记清除
- **AND** API MUST NOT 在响应、日志或审计详情中记录当前密码或新密码

#### Scenario: 当前用户修改密码输入超过长度
- **WHEN** 已登录用户调用 `POST /api/auth/change-password` 且 `currentPassword` 或 `newPassword` 超过 16 字符
- **THEN** API SHALL 返回参数校验错误
- **AND** 后端 MUST NOT 更新密码哈希

#### Scenario: 强制改密用户访问受限
- **WHEN** 已登录用户的 `mustChangePassword` 为 `true`
- **THEN** API SHALL 只允许其访问当前用户查询、修改密码、退出登录和 CSRF token 获取接口
- **AND** 访问其他受保护业务接口时 SHALL 返回 `PASSWORD_CHANGE_REQUIRED` 错误

#### Scenario: 用户退出登录
- **WHEN** 已登录用户调用 `POST /api/auth/logout`
- **THEN** API SHALL 清理当前服务端 Session
- **AND** 后续使用同一登录态访问受保护接口 SHALL 失败

### Requirement: 超级管理员可以管理本地用户
MVP API SHALL 为超级管理员提供配置页用户信息表和本地用户增删改查能力，但 SHALL NOT 通过 API 创建或提升超级管理员。管理员创建用户 SHALL 通过邮箱邀请用户自主设置密码；管理员重置密码 SHALL 继续支持直接设置新密码。

#### Scenario: 超级管理员查看用户列表
- **WHEN** 超级管理员打开配置页用户信息表
- **THEN** 前端 SHALL 能调用 `GET /api/admin/users` 获取分页用户列表
- **AND** 列表项 SHALL 包含用户 ID、用户名、展示名、邮箱、状态、`isSuperAdmin`、`mustChangePassword`、最近登录时间和创建时间
- **AND** 列表接口 SHOULD 支持状态筛选和关键字搜索

#### Scenario: 超级管理员创建普通用户
- **WHEN** 超级管理员调用 `POST /api/admin/users`
- **THEN** API SHALL 创建本地普通用户
- **AND** API SHALL 要求提交唯一 `username` 和唯一非空 `email`
- **AND** API SHALL 生成一次性邀请 token 并发送邀请邮件
- **AND** API MUST NOT 要求或接受 `initialPassword`
- **AND** API MUST NOT 接受 `isSuperAdmin`、`passwordHash`、`roles` 或 `permissions` 作为可写字段

#### Scenario: 超级管理员读取和更新用户
- **WHEN** 超级管理员调用 `GET /api/admin/users/{userId}` 或 `PATCH /api/admin/users/{userId}`
- **THEN** API SHALL 允许读取和更新用户的展示名、邮箱和状态
- **AND** API SHALL 对邮箱执行唯一性校验
- **AND** API MUST NOT 允许通过请求体修改 `username`、`passwordHash`、`isSuperAdmin` 或 `mustChangePassword`

#### Scenario: 超级管理员重置用户密码
- **WHEN** 超级管理员调用 `POST /api/admin/users/{userId}/reset-password`
- **THEN** API SHALL 保存新的密码哈希
- **AND** API SHALL 支持设置 `mustChangePassword=true`，使用户下次登录后必须修改密码
- **AND** API MUST NOT 发送重置密码邮件
- **AND** API MUST NOT 返回明文新密码或密码哈希

#### Scenario: 超级管理员重新发送邀请
- **WHEN** 超级管理员调用 `POST /api/admin/users/{userId}/send-invitation`
- **THEN** API SHALL 为该用户生成新的邀请 token
- **AND** API SHALL 使该用户未消费的旧邀请 token 失效
- **AND** API SHALL 向用户当前邮箱发送邀请邮件

#### Scenario: 超级管理员删除或禁用用户
- **WHEN** 超级管理员从配置页移除用户
- **THEN** API SHALL 将用户状态设置为 `DISABLED`
- **AND** API MUST NOT 物理删除 `users` 记录
- **AND** 历史创建人、审计记录和业务引用 SHALL 继续可追溯

#### Scenario: 普通用户访问用户管理接口
- **WHEN** 非超级管理员访问 `/api/admin/users` 或其子接口
- **THEN** API SHALL 返回 `FORBIDDEN` 错误

### Requirement: API 设计标注用户强制改密字段依赖
用户管理和重置密码 API SHALL 依赖数据库提供 `users.must_change_password` 字段。邀请注册和忘记密码流程 SHALL 使用独立 action token，不通过邮箱验证字段表达状态。

#### Scenario: 用户被邀请创建
- **WHEN** 超级管理员创建用户并发送邀请
- **THEN** 后端 SHALL 创建用户记录并生成邀请 token
- **AND** 后端 SHALL 在用户接受邀请并设置密码后允许其正常登录

#### Scenario: 用户被管理员重置密码
- **WHEN** 超级管理员重置用户密码并要求下次登录改密
- **THEN** 后端 SHALL 将该用户的 `must_change_password` 设置为 `true`

#### Scenario: 用户完成强制改密
- **WHEN** 用户成功修改自己的密码
- **THEN** 后端 SHALL 将 `users.must_change_password` 设置为 `false`

#### Scenario: 不新增邮箱验证字段
- **WHEN** 设计邀请注册和忘记密码流程
- **THEN** API 文档 MUST NOT 要求 `email_verified_at` 或等效邮箱验证字段
- **AND** 邮箱可达性 SHALL 由用户通过邮箱中的 token 完成设置密码动作体现

### Requirement: 当前用户资料 API 不允许修改用户名和邮箱
个人中心 API SHALL 允许用户读取当前用户信息，并 MAY 允许修改展示类字段，但 MUST NOT 允许用户自行修改 `username` 或 `email`。

#### Scenario: 当前用户修改展示信息
- **WHEN** 已登录用户调用当前用户资料更新 API
- **THEN** API MAY 允许修改 `displayName`
- **AND** API MUST NOT 允许修改 `username`、`email`、`isSuperAdmin`、`status`、`mustChangePassword` 或 `passwordHash`

#### Scenario: 当前用户提交邮箱或用户名
- **WHEN** 当前用户资料更新请求包含 `username` 或 `email`
- **THEN** API SHALL 返回校验错误
- **AND** 后端 MUST NOT 修改 `users.username` 或 `users.email`

### Requirement: 用户邀请接受 API 必须设置密码并消费 token
认证 API SHALL 提供邀请接受接口，允许用户通过邮件 token 设置密码并完成账号注册。

#### Scenario: 接受有效邀请
- **WHEN** 用户调用 `POST /api/auth/invitations/accept` 并提交有效邀请 token 和不超过 16 字符的新密码
- **THEN** 后端 SHALL 校验 token 类型、hash、过期时间、消费状态和用户状态
- **AND** 后端 SHALL 保存新密码哈希
- **AND** 后端 SHALL 消费该 token
- **AND** 后端 SHALL 允许用户随后使用账号密码登录

#### Scenario: 邀请设密超过长度
- **WHEN** 用户调用 `POST /api/auth/invitations/accept` 且 `newPassword` 超过 16 字符
- **THEN** API SHALL 返回参数校验错误
- **AND** 后端 MUST NOT 消费邀请 token
- **AND** 后端 MUST NOT 修改用户密码

#### Scenario: 接受无效邀请
- **WHEN** 用户提交不存在、过期、已消费或类型不匹配的邀请 token
- **THEN** API SHALL 返回 token 无效或过期错误
- **AND** 后端 MUST NOT 修改用户密码

### Requirement: 忘记密码 API 必须防止账号枚举
认证 API SHALL 提供用户自行触发的忘记密码流程，并 SHALL 防止通过响应枚举账号是否存在。

#### Scenario: 请求密码重置
- **WHEN** 用户调用 `POST /api/auth/password-resets/request` 并提交用户名或邮箱
- **THEN** API SHALL 返回统一成功响应
- **AND** 若匹配到可用用户，后端 SHALL 生成密码重置 token 并发送邮件
- **AND** 若未匹配用户，后端 MUST NOT 暴露账号不存在

#### Scenario: 确认密码重置
- **WHEN** 用户调用 `POST /api/auth/password-resets/confirm` 并提交有效 token 和不超过 16 字符的新密码
- **THEN** 后端 SHALL 校验 token 类型、hash、过期时间和消费状态
- **AND** 后端 SHALL 保存新密码哈希
- **AND** 后端 SHALL 消费该 token
- **AND** 后端 SHALL 将 `mustChangePassword` 设置为 `false`

#### Scenario: 密码重置输入超过长度
- **WHEN** 用户调用 `POST /api/auth/password-resets/confirm` 且 `newPassword` 超过 16 字符
- **THEN** API SHALL 返回参数校验错误
- **AND** 后端 MUST NOT 消费密码重置 token
- **AND** 后端 MUST NOT 修改用户密码

#### Scenario: 密码重置 token 无效
- **WHEN** 用户提交不存在、过期、已消费或类型不匹配的密码重置 token
- **THEN** API SHALL 返回 token 无效或过期错误
- **AND** 后端 MUST NOT 修改用户密码

### Requirement: 管理员邮箱配置 API 必须受超级管理员保护
后端 API SHALL 为超级管理员提供邮箱服务器配置查询、保存和测试发送接口。

#### Scenario: 查询邮箱配置
- **WHEN** 超级管理员调用 `GET /api/admin/configs/mail`
- **THEN** API SHALL 返回邮箱配置摘要
- **AND** 响应 MUST NOT 返回 SMTP 密码明文

#### Scenario: 保存邮箱配置
- **WHEN** 超级管理员调用 `PATCH /api/admin/configs/mail`
- **THEN** API SHALL 校验并保存邮箱配置
- **AND** SMTP 密码等 secret SHALL 加密保存
- **AND** API MUST NOT 接受非邮箱配置白名单字段

#### Scenario: 测试邮箱配置
- **WHEN** 超级管理员调用 `POST /api/admin/configs/mail:test`
- **THEN** API SHALL 使用当前或请求中的临时邮箱配置发送测试邮件
- **AND** 响应 SHALL 返回脱敏后的成功或失败摘要

### Requirement: 任务完成邮件推送 API 配置必须受控
后端 SHALL 通过受控配置支持任务完成邮件推送开关和默认收件策略。

#### Scenario: 配置任务完成邮件推送
- **WHEN** 超级管理员更新任务邮件通知配置
- **THEN** API SHALL 保存通知开关和允许的通知策略
- **AND** API MUST NOT 允许提交任意未声明配置 key

#### Scenario: 任务完成触发邮件
- **WHEN** 任务进入终态且邮件通知开启
- **THEN** 后端 SHALL 向任务创建人邮箱发送任务完成通知
- **AND** 邮件内容 MUST NOT 包含明文凭据、完整日志或服务器真实敏感路径

### Requirement: 项目 stage 作为导航提示暴露
项目 API SHALL 将项目阶段作为导航提示暴露，并 SHALL 通过 overview 和列表摘要聚合表达项目真实状态。

#### Scenario: 读取项目详情
- **WHEN** 前端读取项目详情
- **THEN** 响应 MAY 返回 `stage` 或 `stageHint`
- **AND** 文档 SHALL 明确该字段不是权威状态机

#### Scenario: 读取项目列表摘要
- **WHEN** 前端调用 `GET /api/projects`
- **THEN** 列表项 SHALL 包含 `highRiskCount`、`latestScanAt`、`latestBuildStatus` 和 `latestTestStatus`
- **AND** 未产生对应领域运行记录时，摘要字段 SHALL 返回 `0` 或 `null`
- **AND** 后端 MUST NOT 只依赖 `projects.stage` 判断项目真实状态

### Requirement: 构建验证 API 必须使用源码部署引用
后端 API 设计 SHALL 将构建验证创建输入建模为源码部署引用，而不是客户端提交远端工作目录。

#### Scenario: 创建构建验证请求
- **WHEN** 前端调用 `POST /api/projects/{projectId}/builds`
- **THEN** 请求体 SHALL 包含 `sourceDeploymentIds`、`script` 和可选 `timeoutSeconds`
- **AND** 请求体 MAY 包含 `sourceRevisionId` 用于显式一致性校验
- **AND** 请求体 MUST NOT 包含任意远端 `workingDirectory` 或 `remotePath`
- **AND** 响应 SHALL 返回每个构建运行的 `buildRunId`、`taskId`、`environmentId`、`sourceRevisionId`、`sourceDeploymentId` 和初始状态

#### Scenario: 构建详情响应
- **WHEN** 前端调用 `GET /api/builds/{buildId}`
- **THEN** 响应 SHALL 包含构建运行状态、退出码、失败分类、任务引用、源码版本摘要、环境摘要、源码部署引用和 merged/stdout/stderr 日志 ID
- **AND** 响应 MUST NOT 返回完整脚本明文、明文凭据、后端本机路径或未脱敏远端命令

#### Scenario: 构建实时状态
- **WHEN** 构建任务状态变化或输出日志摘要
- **THEN** 后端 SHALL 通过项目级 SSE 推送任务事件
- **AND** 运行中密集日志 SHALL 使用任务级日志 SSE
- **AND** 构建完成后历史日志 SHALL 使用任务日志内容读取 API

#### Scenario: 读取项目概览
- **WHEN** 前端调用 `GET /api/projects/{projectId}/overview`
- **THEN** 后端 SHALL 从当前源码版本、环境摘要、任务、扫描运行、SBOM 运行、报告、复检、构建、测试、问题和差异数据聚合当前状态
- **AND** 尚未落地的领域摘要 SHALL 返回空摘要或 0 值
- **AND** 后端 MUST NOT 只依赖 `projects.stage` 判断项目真实状态

### Requirement: 长任务 API 以 PostgreSQL 任务模型为事实来源
所有长任务创建 API SHALL 先创建持久化任务记录，再返回 `taskId` 和初始任务状态。

#### Scenario: 创建长任务
- **WHEN** 前端发起 Git 同步、环境连接测试、环境采集、远程脚本、KB 扫描、SBOM 分析、报告生成、复检、构建、测试或归档
- **THEN** API SHALL 返回 `taskId`、任务类型和初始状态
- **AND** API SHALL 先创建 `tasks` 记录并追加初始 `task_events`
- **AND** 队列载荷 SHALL 只使用 `task_id` 等路由信息
- **AND** API 响应 MUST NOT 返回完整任务输入快照、明文凭据或服务器本地路径

#### Scenario: 查询任务详情
- **WHEN** 前端调用 `GET /api/tasks/{taskId}`
- **THEN** API SHALL 从持久化任务状态返回任务类型、状态、进度、消息、时间、错误摘要和关联业务资源引用
- **AND** API 响应 MUST NOT 返回完整任务输入快照、明文凭据或服务器本地路径

#### Scenario: 查询项目任务列表
- **WHEN** 前端调用 `GET /api/projects/{projectId}/tasks`
- **THEN** API SHALL 返回该项目的分页任务列表
- **AND** 列表项 SHALL 包含任务 ID、任务类型、状态、进度、消息和关键时间字段

#### Scenario: 取消任务
- **WHEN** 前端调用 `POST /api/tasks/{taskId}/cancel`
- **THEN** API SHALL 可靠取消 `PENDING`、`QUEUED` 或 `RUNNING` 任务
- **AND** 对 `RUNNING` 任务 SHALL 将任务业务状态更新为 `CANCELLED` 并投递取消清理消息
- **AND** API MUST NOT 返回明文凭据、服务器真实路径或底层完整命令输入

#### Scenario: 枚举任务状态
- **WHEN** API 文档列出任务状态
- **THEN** 文档 SHALL 包含 `PENDING`、`QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED`、`CANCELLED` 和 `TIMEOUT`

### Requirement: API 使用项目级 SSE 作为实时任务通道
后端 API 设计 SHALL 使用项目级 SSE 作为 MVP 实时任务事件主通道，并 SHALL 区分首次订阅 baseline 与同一页面断线恢复游标，避免新浏览器或旧游标默认补发大量历史事件。

#### Scenario: 建立 SSE 连接
- **WHEN** 前端进入项目工作台
- **THEN** 前端 SHALL 通过 `GET /api/projects/{projectId}/task-events` 建立项目级 SSE 连接
- **AND** SSE 响应内容类型 SHALL 为 `text/event-stream`
- **AND** 未提交任何游标时，后端 SHALL 从当前项目最新事件 ID 之后开始推送新事件

#### Scenario: 使用 overview baseline 建立 SSE
- **WHEN** 前端调用 `GET /api/projects/{projectId}/overview` 获得 `latestTaskEventId`
- **THEN** 前端 SHALL 能调用 `GET /api/projects/{projectId}/task-events?afterEventId={latestTaskEventId}` 只订阅该事件之后的新事件
- **AND** 后端 SHALL 只补发同一项目内 `id > afterEventId` 的事件

#### Scenario: SSE 事件 ID
- **WHEN** 后端发送任务 SSE 事件
- **THEN** SSE 协议层 `id` SHALL 使用递增的 `task_events.id`
- **AND** SSE 协议层 `event` SHALL 使用 `task_events.event_type`
- **AND** SSE data MAY 包含业务追踪字段 `eventId`

#### Scenario: SSE 断线恢复
- **WHEN** 前端未提交 `afterEventId` 且携带 `Last-Event-ID` 重新连接
- **THEN** 后端 SHALL 将 `Last-Event-ID` 解析为 `task_events.id`
- **AND** 后端 SHALL 从持久化任务事件中补发同一项目内 `id` 大于该值的后续事件

#### Scenario: afterEventId 覆盖 Last-Event-ID
- **WHEN** 前端同时提交 `afterEventId` 和 `Last-Event-ID`
- **THEN** 后端 SHALL 以 `afterEventId` 作为本次连接游标
- **AND** 后端 SHALL 忽略本次请求中的 `Last-Event-ID`

#### Scenario: 断线补偿
- **WHEN** SSE 连接不可用或恢复失败
- **THEN** 前端 SHALL 通过 `GET /api/tasks/{taskId}` 和 `GET /api/tasks/{taskId}/logs` 补偿刷新

#### Scenario: 避免重复 SSE 通道
- **WHEN** 设计 MVP API
- **THEN** 文档 SHOULD NOT 将 `GET /api/tasks/{taskId}/events` 作为主实时通道

#### Scenario: 项目级 SSE 隔离
- **WHEN** 用户在两个浏览器或两个页面分别打开不同项目
- **THEN** 每个项目级 SSE 连接 SHALL 只接收对应项目的任务事件
- **AND** 后端 MUST NOT 补发或推送其他项目的任务事件

### Requirement: 项目 overview 必须返回最新任务事件 ID
项目 overview API SHALL 返回稳定项目元数据、当前 Context、完整 Workflow 节点状态、最近任务和当前项目最新任务事件 ID，供前端首屏展示并以该事件 ID 作为项目级 SSE baseline。

#### Scenario: 读取项目概览包含当前 Context 和最近任务
- **WHEN** 有项目访问权限的用户调用 `GET /api/projects/{projectId}/overview`
- **THEN** 响应 SHALL 包含 `project`、`currentContext`、`workflow`、`recentTasks` 和 `latestTaskEventId`
- **AND** `currentContext` SHALL 包含当前源码版本、source 环境、target 环境、项目运行环境版本和双环境就绪状态
- **AND** `recentTasks` SHALL 返回当前项目按 `createdAt` 和任务 ID 倒序排列的最近 5 条任务
- **AND** 当前项目没有任务时 `recentTasks` SHALL 返回空数组
- **AND** 后端 MUST NOT 在该响应中恢复旧版领域空摘要或推荐动作集合

#### Scenario: overview 返回全部 Workflow 节点
- **WHEN** 后端生成项目 overview
- **THEN** `workflow.stages` SHALL 恰好包含 `project`、`env`、`assets`、`kb`、`x86-build`、`rv-dependency-analysis`、`report`、`manual`、`build`、`test`、`optimize` 和 `archive`
- **AND** 每个节点 SHALL 包含稳定 `id`、`status` 和可空 `reasonCode`
- **AND** `status` SHALL 仅使用 `PENDING`、`RUNNING`、`DONE` 或 `BLOCKED`

#### Scenario: overview 优先展示当前运行状态
- **WHEN** 某节点在当前 Context 存在 `PENDING`、`QUEUED` 或 `RUNNING` 任务
- **THEN** 该 Workflow 节点状态 SHALL 为 `RUNNING`
- **AND** 后端 MUST NOT 使用该节点更早的成功或失败结果覆盖运行状态

#### Scenario: overview 返回 latestTaskEventId
- **WHEN** 前端读取项目 overview
- **THEN** `latestTaskEventId` SHALL 为当前项目在该快照读取边界内可见的最新 `task_events.id`
- **AND** 当前项目没有任务事件时 SHALL 返回 `0`
- **AND** 后端 MUST NOT 返回会使前端跳过业务状态变化的“旧业务快照与新事件游标”组合

#### Scenario: 无权限读取 overview
- **WHEN** 用户无权访问目标项目
- **THEN** 后端 SHALL 拒绝返回 overview
- **AND** 后端 MUST NOT 暴露该项目的 Context、Workflow 状态、最近任务或 `latestTaskEventId`

### Requirement: 项目级 SSE 必须校验游标参数
项目级 SSE API SHALL 对 `afterEventId` 和 `Last-Event-ID` 执行统一游标校验。

#### Scenario: afterEventId 非法
- **WHEN** 前端调用项目级 SSE 且 `afterEventId` 不是非负数字
- **THEN** 后端 SHALL 返回统一参数错误
- **AND** 后端 MUST NOT 创建 SSE emitter

#### Scenario: Last-Event-ID 非法
- **WHEN** 前端调用项目级 SSE 且未提交 `afterEventId`，但 `Last-Event-ID` 不是非负数字
- **THEN** 后端 SHALL 返回统一参数错误
- **AND** 后端 MUST NOT 创建 SSE emitter

### Requirement: API 不引入通用 Artifact 资源
MVP API 设计 SHALL NOT 引入面向前端的通用 Artifact API，下载能力 SHALL 由报告、归档、KB、SBOM 和日志等业务资源提供。后端 MAY 使用统一输出制品元数据和对象存储作为业务下载接口的内部实现。

#### Scenario: 下载报告
- **WHEN** 前端下载 HTML 或 JSON 报告
- **THEN** API SHALL 通过报告资源的受控下载接口提供文件
- **AND** SHALL NOT 要求前端调用通用 artifact 资源
- **AND** API MUST NOT 暴露对象存储 bucket、object key 或服务器真实路径

#### Scenario: 下载归档包
- **WHEN** 前端下载项目归档包
- **THEN** API SHALL 通过归档资源的受控下载接口提供文件
- **AND** SHALL NOT 暴露服务器真实路径、对象存储 bucket 或 object key

#### Scenario: 下载任务日志
- **WHEN** 前端下载任务日志
- **THEN** API SHALL 通过任务日志资源的受控下载接口提供文件
- **AND** SHALL NOT 暴露服务器真实路径、对象存储 bucket 或 object key

#### Scenario: 下载 KB 或 SBOM 输出制品
- **WHEN** 前端下载或查询 KB/SBOM 业务输出制品
- **THEN** API SHALL 继续通过 KB/SBOM 业务接口提供能力
- **AND** 前端 MUST NOT 依赖通用 artifact API

### Requirement: API 响应必须隐藏对象存储内部位置
业务 API SHALL 在使用对象存储保存输出制品时隐藏内部存储位置。

#### Scenario: 返回 artifact 摘要
- **WHEN** API 返回报告、KB、SBOM、归档或日志制品摘要
- **THEN** 响应 MAY 包含 artifact ID、artifact type、content type、size bytes、sha256、created at 和安全 summary
- **AND** 响应 MUST NOT 包含 bucket、object key、endpoint、access key、secret key 或服务器真实路径

#### Scenario: 下载对象存储制品
- **WHEN** 用户通过业务下载接口下载对象存储中的制品
- **THEN** 后端 SHALL 先执行对应业务资源的项目访问权限校验
- **AND** 后端 SHALL 代理读取对象并返回内容
- **AND** 后端 MUST NOT 将 MinIO 预签名 URL 作为 MVP 默认下载方式返回给前端

### Requirement: 凭据 API 只允许遮蔽展示
环境和源码相关 API SHALL 允许写入或重新配置凭据，但查询响应 MUST NOT 返回明文秘密。

#### Scenario: 保存 SSH 凭据
- **WHEN** 用户创建或更新 SSH 环境并提交密码或私钥
- **THEN** API SHALL 将明文秘密交给后端加密保存
- **AND** 响应 MUST NOT 返回明文秘密
- **AND** 响应 MUST NOT 返回加密密文

#### Scenario: 读取环境详情
- **WHEN** 前端读取环境详情
- **THEN** 响应 SHALL 只返回凭据是否已配置、凭据类型、名称和遮蔽摘要

#### Scenario: 保存 Git 凭据
- **WHEN** 用户从 Git 同步源码并提交密码、token 或凭据引用
- **THEN** API SHALL 防止 Git URL 中保存 token、用户名或密码
- **AND** 任务输入快照 MUST NOT 包含明文 Git 凭据

### Requirement: 测试 API 限定为 MVP 摘要能力
MVP 测试 API SHALL 只暴露测试命令退出码和汇总计数，不 SHALL 承诺用例级解析、失败用例重跑或结构化报告对比。

#### Scenario: 创建测试任务
- **WHEN** 前端发起自动化测试
- **THEN** API SHALL 返回测试任务 ID，并在完成后提供退出码、通过数、失败数、可选跳过数、状态、时间和日志引用

#### Scenario: 失败用例重跑
- **WHEN** 设计 MVP API
- **THEN** 文档 MUST NOT 承诺 `rerun failed cases` 这类依赖单个测试用例解析的能力

#### Scenario: 测试对比
- **WHEN** 设计测试对比能力
- **THEN** MVP 文档 MAY 只描述摘要级对比
- **AND** MUST NOT 要求解析 JUnit、CTest、pytest 或类似报告

### Requirement: 领域 API 必须映射到已设计的数据模型
后端 API 设计 SHALL 覆盖数据库设计中的核心领域，并保持对外业务命名与内部表模型可映射。

#### Scenario: 设计源码 API
- **WHEN** 定义源码管理接口
- **THEN** API SHALL 覆盖压缩包上传、Git 同步、源码版本列表和当前源码版本切换
- **AND** SHALL 支持修复后源码重新入库
- **AND** 源码查询响应 MUST NOT 暴露服务器本地路径

#### Scenario: 设计环境 API
- **WHEN** 定义环境管理接口
- **THEN** API SHALL 覆盖环境列表、环境创建、环境详情、环境更新、连接测试、环境采集、远程脚本和快照列表
- **AND** 连接测试、环境采集和远程脚本 SHALL 通过任务运行时创建长任务

#### Scenario: 设计跳板机配置
- **WHEN** 定义环境创建和更新接口
- **THEN** API SHALL 支持可选 `jumpHostEnvironmentId`
- **AND** 跳板机 SHALL 是同一项目内另一个已启用环境
- **AND** API MUST NOT 允许自引用或多级跳板链路
- **AND** 环境查询响应 SHALL 返回跳板机的非敏感摘要
- **AND** 任务输入快照 MUST NOT 包含目标环境或跳板机的明文凭据

#### Scenario: 设计项目访问边界
- **WHEN** 设计 MVP 项目、源码和环境 API
- **THEN** API SHALL 要求用户已登录
- **AND** API MUST NOT 引入角色、权限、用户角色映射或项目成员模型

#### Scenario: 设计项目名称重复策略
- **WHEN** 设计项目创建和项目列表 API
- **THEN** API SHALL 允许不同项目使用相同 `name`
- **AND** 项目唯一识别 MUST 使用 `projectId`
- **AND** 后端 MUST NOT 因项目名称重复拒绝创建项目

### Requirement: 任务日志 API 不暴露服务器路径
任务日志 API SHALL 通过任务日志资源提供元数据分页、完成态正文尾部分页读取和受控下载能力，并 SHALL 隐藏服务器真实文件路径。

#### Scenario: 查询日志元数据
- **WHEN** 前端调用 `GET /api/tasks/{taskId}/logs`
- **THEN** API SHALL 返回日志 ID、日志类型、大小、摘要和创建时间
- **AND** 响应 MUST NOT 返回 `file_path` 或服务器真实路径

#### Scenario: 读取日志内容
- **WHEN** 前端调用 `GET /api/tasks/{taskId}/log-content`
- **THEN** API SHALL 返回完成态日志内容响应
- **AND** 响应 SHALL 支持 `merged`、`stdout`、`stderr` 三类日志
- **AND** 不传参数时每类默认返回物理日志文件末尾的最新展示页最多 100 行
- **AND** 每个返回页的 `content` SHALL 按日志产生时间正序排列
- **AND** 响应 MUST NOT 返回服务器真实日志文件路径

#### Scenario: 读取单类或多类不同 offset
- **WHEN** 前端调用 `GET /api/tasks/{taskId}/log-content?types=merged,stdout&mergedOffset=0&stdoutOffset=100&mergedLines=100&stdoutLines=50`
- **THEN** API SHALL 只返回请求的日志类型
- **AND** 每个日志类型 SHALL 使用自己的 offset 和 lines
- **AND** offset SHALL 表示从对应日志文件尾部开始跳过的最新日志行数
- **AND** `offset=0` SHALL 返回该日志类型的最新展示页
- **AND** offset 增大时 SHALL 返回更早的日志页
- **AND** 未设置单类 lines 时 SHALL 使用全局 `lines` 或默认值

#### Scenario: 正序物理日志读取
- **WHEN** 日志文件物理内容为较早日志在文件头、较新日志追加在文件尾
- **THEN** `GET /api/tasks/{taskId}/log-content?types=merged&mergedOffset=0` SHALL 返回文件尾部对应的最新展示页
- **AND** 单页内容 SHALL 保持文件内自然顺序
- **AND** 前端拼接更早页面时 MUST NOT 反转单页日志行

#### Scenario: 下载日志文件
- **WHEN** 前端调用 `GET /api/tasks/{taskId}/logs/{logId}/download`
- **THEN** API SHALL 校验日志属于该任务
- **AND** API SHALL 通过受控下载返回日志内容
- **AND** API MUST NOT 接受客户端传入服务器文件路径作为下载参数

### Requirement: API 必须显式校验项目资源访问权限
所有项目级 API SHALL 在返回项目、源码、环境、任务、任务日志或 SSE 事件之前校验当前用户对所属项目的访问权限。

#### Scenario: 访问项目级资源
- **WHEN** 已登录用户调用带有 `projectId` 的项目、源码、环境、任务列表或 SSE API
- **THEN** 后端 SHALL 校验当前用户可以访问该 `projectId`
- **AND** 普通用户 SHALL 只能访问自己创建的项目
- **AND** 超级管理员 SHALL 能访问任意未软删除项目

#### Scenario: 访问任务和日志资源
- **WHEN** 已登录用户调用只包含 `taskId` 或 `taskId` + `logId` 的任务 API
- **THEN** 后端 SHALL 根据任务所属项目执行项目访问权限校验
- **AND** 日志下载 SHALL 同时校验日志属于该任务

#### Scenario: 拒绝越权访问
- **WHEN** 普通用户访问其他用户项目或其派生资源
- **THEN** API SHALL 返回与资源不存在一致的安全错误
- **AND** API MUST NOT 暴露目标资源是否存在、资源名称、任务状态、日志摘要、源码版本、环境配置或 SSE 事件

#### Scenario: 不引入权限模型字段
- **WHEN** API 返回当前用户、项目、源码、环境、任务或日志数据
- **THEN** API MUST NOT 返回 `roles`、`permissions`、`projectMembers` 或其他 RBAC 字段

### Requirement: 项目级 SSE API 必须在订阅前完成访问校验
项目级 SSE API SHALL 在创建 SSE emitter、注册订阅者或补发历史事件之前完成项目访问权限校验。

#### Scenario: 有权限用户建立 SSE
- **WHEN** 有项目访问权限的用户调用 `GET /api/projects/{projectId}/task-events`
- **THEN** 后端 SHALL 建立 `text/event-stream` 响应
- **AND** 后端 SHALL 只推送该项目的任务事件

#### Scenario: 无权限用户建立 SSE
- **WHEN** 无项目访问权限的用户调用 `GET /api/projects/{projectId}/task-events`
- **THEN** 后端 SHALL 拒绝请求
- **AND** 后端 MUST NOT 创建 SSE emitter
- **AND** 后端 MUST NOT 补发历史任务事件

### Requirement: SBOM API 必须创建和查询分析运行
后端 API SHALL 提供项目级 SBOM 分析创建、运行列表、运行详情、组件结果和采集详情接口，并 SHALL 由后端 SBOM 任务内部负责本轮环境采集。

#### Scenario: 创建 SBOM 分析任务
- **WHEN** 已登录用户调用 `POST /api/projects/{projectId}/sbom-analyses`
- **THEN** 后端 SHALL 校验当前用户有权访问该项目
- **AND** 后端 SHALL 校验源环境和目标环境属于该项目
- **AND** 请求 MUST NOT 要求 `sourceSnapshotId` 或 `targetSnapshotId`
- **AND** 请求 MUST NOT 要求前端提交采集范围或分析选项
- **AND** 后端 SHALL 使用默认全量采集和默认分析策略创建任务
- **AND** 后端 SHALL 创建 `SBOM_ANALYSIS` 任务并返回 `taskId`、任务类型和初始状态
- **AND** 后端 SHALL 创建或准备关联该任务的 SBOM 运行记录，运行记录 MAY 在采集完成前没有源快照或目标快照
- **AND** API 响应 MUST NOT 返回服务器真实路径、完整任务输入快照或明文凭据

#### Scenario: 查询项目 SBOM 分析列表
- **WHEN** 已登录用户调用 `GET /api/projects/{projectId}/sbom-analyses`
- **THEN** 后端 SHALL 校验当前用户有权访问该项目
- **AND** 后端 SHALL 返回该项目下 SBOM 运行分页列表
- **AND** 列表项 SHALL 包含运行 ID、任务 ID、状态、环境摘要、已生成的快照摘要、统计摘要和关键时间字段
- **AND** 当快照尚未生成时，列表项 SHALL 返回空快照摘要或明确的采集中状态，而不是返回错误

#### Scenario: 查询 SBOM 分析详情
- **WHEN** 已登录用户调用 `GET /api/sbom-analyses/{analysisId}`
- **THEN** 后端 SHALL 根据 SBOM 运行所属项目校验访问权限
- **AND** 响应 SHALL 返回运行摘要、任务状态、环境摘要、已生成的快照摘要、统计摘要和警告摘要
- **AND** 当采集失败或分析失败时，响应 SHALL 返回安全的失败阶段和错误摘要
- **AND** 响应 MUST NOT 暴露服务器真实路径

### Requirement: SBOM API 必须提供组件替代结果
后端 API SHALL 提供 SBOM 组件和替代候选结果查询能力，用于前端结果表格展示。

#### Scenario: 查询组件替代结果
- **WHEN** 已登录用户调用 `GET /api/sbom-analyses/{analysisId}/components`
- **THEN** 后端 SHALL 根据 SBOM 运行所属项目校验访问权限
- **AND** 后端 SHALL 返回分页组件结果
- **AND** 响应项 SHALL 包含源侧组件、候选替代项、替代结论、风险、建议和证据摘要

#### Scenario: 过滤组件替代结果
- **WHEN** 前端按风险、替代结论、组件名称或组件类型查询组件结果
- **THEN** 后端 SHALL 支持对应筛选参数
- **AND** 后端 SHALL 只返回当前分析运行内的结果

#### Scenario: 无权限查询组件结果
- **WHEN** 用户查询无权访问项目下的 SBOM 组件结果
- **THEN** 后端 SHALL 返回与资源不存在一致的安全错误
- **AND** 后端 MUST NOT 暴露分析运行、环境、快照或组件是否存在

### Requirement: SBOM API 必须提供采集详情
后端 API SHALL 提供分析所用源快照和目标快照的采集详情摘要，帮助前端解释分析可信度，并 SHALL 支持采集尚未完成的中间状态。

#### Scenario: 查询采集详情
- **WHEN** 已登录用户调用 `GET /api/sbom-analyses/{analysisId}/collection-detail`
- **THEN** 后端 SHALL 根据 SBOM 运行所属项目校验访问权限
- **AND** 响应 SHALL 返回源快照和目标快照的采集时间、OS、kernel、arch、包管理器、软件源摘要、工具版本摘要、组件数量和解析警告
- **AND** 响应 MUST NOT 返回敏感路径、明文凭据或服务器真实路径

#### Scenario: 快照尚未生成
- **WHEN** SBOM 运行仍在源环境采集或目标环境采集阶段
- **THEN** 采集详情响应 SHALL 表达对应侧快照尚未可用
- **AND** 后端 MUST NOT 将快照尚未生成误报为资源不存在

#### Scenario: 环境采集失败
- **WHEN** SBOM 任务的源环境采集或目标环境采集失败
- **THEN** 采集详情响应 SHALL 返回已经成功生成的快照摘要
- **AND** 失败侧 SHALL 返回安全错误摘要和失败阶段
- **AND** 响应 MUST NOT 暴露明文凭据、SSH 连接细节或敏感路径

#### Scenario: 快照包含仓库采集警告
- **WHEN** 目标快照存在仓库清单或 provides 采集警告
- **THEN** 采集详情响应 SHALL 返回可展示的警告摘要
- **AND** 响应 SHALL 不把该警告伪装为分析器失败

### Requirement: SBOM API 必须提供分析结果明细查询
后端 API SHALL 提供 SBOM 分析结果明细查询能力，用于前端对 package、library、tool 和 native file 结果进行分页审计。

#### Scenario: 查询 SBOM 分析结果明细
- **WHEN** 已登录用户调用 `GET /api/sbom-analyses/{analysisId}/results`
- **THEN** 后端 SHALL 根据 SBOM 运行所属项目校验访问权限
- **AND** 响应 SHALL 返回该分析运行下的结果明细分页列表
- **AND** 列表项 SHALL 包含结果 ID、component type、source name、source version、source architecture、source package、target match status、target name、target version、target package、risk level、match strategy、recommendation 和 created at
- **AND** 列表响应 MUST NOT 返回完整 analyzer output 原文

#### Scenario: 分页过滤排序 SBOM 分析结果明细
- **WHEN** 已登录用户查询 SBOM 分析结果明细
- **THEN** 后端 SHALL 支持 `page` 和 `pageSize` 参数
- **AND** 后端 SHALL 支持按 `componentType`、`sourceName`、`sourceVersion`、`sourcePackageName`、`targetMatchStatus`、`targetName`、`targetVersion`、`targetPackageName`、`riskLevel`、`matchStrategy` 和 `recommendation` 过滤
- **AND** 后端 SHALL 支持按 `componentType`、`sourceName`、`sourceVersion`、`targetMatchStatus`、`riskLevel`、`matchStrategy` 和 `createdAt` 排序
- **AND** 默认排序 SHALL 为 `riskLevel DESC, componentType ASC, sourceName ASC`
- **AND** 后端 MUST 拒绝不在白名单内的排序字段

#### Scenario: 无权限查询分析结果明细
- **WHEN** 用户查询无权访问项目下的 SBOM 分析结果明细
- **THEN** 后端 SHALL 返回与资源不存在一致的安全错误
- **AND** 后端 MUST NOT 暴露分析运行、任务、环境、快照或结果明细是否存在

### Requirement: SBOM API 必须提供分析 artifact 查询
后端 API SHALL 提供 SBOM 分析 artifact 查询能力，用于前端审计页面和历史分析追溯。

#### Scenario: 查询 SBOM artifact 列表
- **WHEN** 已登录用户调用 `GET /api/sbom-analyses/{analysisId}/artifacts`
- **THEN** 后端 SHALL 根据 SBOM 运行所属项目校验访问权限
- **AND** 响应 SHALL 返回该分析运行下的 artifact 分页列表
- **AND** 列表项 SHALL 包含 artifact ID、artifact type、schema version、sha256、size bytes、created at 和 summary
- **AND** 列表响应 MUST NOT 返回完整 artifact content

#### Scenario: 分页查询 SBOM artifact
- **WHEN** 已登录用户调用 `GET /api/sbom-analyses/{analysisId}/artifacts?page=1&pageSize=20`
- **THEN** 后端 SHALL 返回分页响应
- **AND** 后端 SHALL 支持 `page` 和 `pageSize` 参数
- **AND** 后端 SHALL 对 `page` 和 `pageSize` 执行边界校验和最大页大小限制

#### Scenario: 过滤 SBOM artifact 列表
- **WHEN** 已登录用户查询 SBOM artifact 列表
- **THEN** 后端 SHALL 支持按 `artifactType`、`schemaVersion`、`taskId`、`sha256`、`createdFrom`、`createdTo`、`minSizeBytes` 和 `maxSizeBytes` 过滤
- **AND** 多个过滤条件 SHALL 使用 AND 语义
- **AND** 过滤结果 SHALL 仅限当前 `analysisId` 对应的 SBOM 运行

#### Scenario: 排序 SBOM artifact 列表
- **WHEN** 已登录用户查询 SBOM artifact 列表
- **THEN** 后端 SHALL 支持按 `artifactType`、`schemaVersion`、`createdAt`、`sizeBytes` 和 `sha256` 排序
- **AND** 后端 SHALL 支持升序和降序
- **AND** 默认排序 SHALL 为 `createdAt DESC, artifactType ASC`
- **AND** 后端 MUST 拒绝不在白名单内的排序字段

#### Scenario: 查询 SBOM artifact 内容
- **WHEN** 已登录用户调用 `GET /api/sbom-analyses/{analysisId}/artifacts/{artifactType}`
- **THEN** 后端 SHALL 根据 SBOM 运行所属项目校验访问权限
- **AND** 后端 SHALL 只返回该分析运行下指定类型的 artifact
- **AND** 响应 SHALL 包含 artifact metadata 和 content JSON
- **AND** 响应 MUST NOT 暴露明文凭据、私钥、token、passphrase、完整任务日志或服务器真实绝对路径

#### Scenario: 查询不存在的 artifact
- **WHEN** 用户查询不存在的 artifact type
- **THEN** 后端 SHALL 返回与资源不存在一致的安全错误
- **AND** 后端 MUST NOT 暴露其他项目或其他分析运行是否存在同类型 artifact

#### Scenario: 无权限查询 artifact
- **WHEN** 用户查询无权访问项目下的 SBOM artifact
- **THEN** 后端 SHALL 返回与资源不存在一致的安全错误
- **AND** 后端 MUST NOT 暴露分析运行、任务、环境、快照或 artifact 是否存在

#### Scenario: 不提供通用任务 artifact API
- **WHEN** 本 change 完成
- **THEN** 后端 SHALL NOT 新增通用 `GET /api/tasks/{taskId}/artifacts`
- **AND** 任务维度 artifact 查询 SHALL 留待后续 change 设计

### Requirement: SBOM API MVP 不实现加入问题清单
本 change 的 SBOM API SHALL 不实现 `components:add-to-issues` 业务联动，避免提前耦合 issue 管理流程。

#### Scenario: 用户尝试调用加入问题清单接口
- **WHEN** 客户端调用 `POST /api/sbom-analyses/{analysisId}/components:add-to-issues`
- **THEN** 后端 MAY 返回未实现或接口不存在
- **AND** 后端 MUST NOT 在本 change 中创建或修改 `issues`、`issue_sources` 或 `issue_events`

### Requirement: 用户 action token 清理任务不得影响认证流程正确性
后端 SHALL 提供用户 action token 历史数据清理任务，但 token 是否有效 MUST 始终由接口实时校验决定。

#### Scenario: 接口实时校验 token 有效性
- **WHEN** 用户接受邀请或确认密码重置
- **THEN** 后端 SHALL 实时校验 token hash、token type、`consumed_at is null`、`expires_at > now()` 和用户状态
- **AND** 后端 MUST NOT 依赖后台清理任务判断 token 是否有效

#### Scenario: 清理任务延迟或失败
- **WHEN** 用户 action token 清理任务未执行、延迟执行或执行失败
- **THEN** 已过期 token 仍然 MUST 被邀请接受和密码重置确认接口拒绝
- **AND** 已消费 token 仍然 MUST 被邀请接受和密码重置确认接口拒绝

#### Scenario: 清理任务执行成功
- **WHEN** 用户 action token 清理任务删除历史 token
- **THEN** 后端 SHALL 只删除 30 天前已经过期或已经消费的 token
- **AND** 后端 MUST NOT 删除仍可能被用户使用的未过期未消费 token

### Requirement: 生产 API 设计不得包含测试辅助接口
后端生产 API 设计 SHALL 明确 `/api/test-support/**` 和旧 `/api/dev/session` 不属于生产 API。

#### Scenario: 查看生产 API 文档
- **WHEN** 开发者查看 `backend/docs/API_DESIGN.md`
- **THEN** 文档 SHALL 不把 `/api/test-support/**` 作为前端可用生产接口
- **AND** 文档 SHALL 不把 `/api/dev/session` 作为可用接口

#### Scenario: 需要测试辅助接口说明
- **WHEN** 文档需要说明 E2E 或开发测试辅助能力
- **THEN** 该说明 SHALL 放入 E2E 或开发测试文档
- **AND** 说明 SHALL 明确该能力不进入默认生产构建产物

### Requirement: Test-support API 不改变生产认证和权限模型
测试辅助 API SHALL NOT 改变生产认证 API、项目访问守卫或超级管理员权限模型。

#### Scenario: 生产认证流程
- **WHEN** 后端以默认生产构建运行
- **THEN** 浏览器登录态 SHALL 继续使用服务端 Session 和 Cookie
- **AND** 用户 SHALL 通过真实认证 API 建立登录态

#### Scenario: 项目权限校验
- **WHEN** 后端以默认生产构建运行
- **THEN** 项目级 API 和 SSE API SHALL 继续执行项目访问权限校验
- **AND** 不存在 test-support API 绕过权限校验

### Requirement: API 必须提供脚本执行记录查询
后端 API SHALL 提供脚本执行记录列表和详情接口，用于前端展示脚本执行历史、当前状态和恢复入口。

#### Scenario: 查询项目脚本执行记录列表
- **WHEN** 已登录用户调用 `GET /api/projects/{projectId}/script-executions`
- **THEN** 后端 SHALL 校验当前用户有权访问该项目
- **AND** 响应 SHALL 返回分页执行记录列表
- **AND** 列表项 SHALL 包含执行记录 ID、任务 ID、环境摘要、脚本类型、脚本 SHA256、脚本长度、状态、退出码、创建时间、开始时间、结束时间和最近日志摘要
- **AND** 响应 MUST NOT 返回脚本正文、明文凭据、服务器真实路径或日志文件路径

#### Scenario: 过滤排序脚本执行记录
- **WHEN** 前端查询脚本执行记录列表
- **THEN** API SHALL 支持 `page` 和 `pageSize`
- **AND** API SHALL 支持按执行状态、脚本类型、环境 ID、创建时间范围过滤
- **AND** API SHALL 支持按创建时间、开始时间、结束时间和状态排序
- **AND** 后端 MUST 拒绝不在白名单内的过滤或排序字段

#### Scenario: 查询脚本执行记录详情
- **WHEN** 已登录用户调用 `GET /api/script-executions/{executionId}`
- **THEN** 后端 SHALL 根据执行记录所属项目校验访问权限
- **AND** 响应 SHALL 返回执行记录摘要、任务摘要、环境摘要、工具执行摘要和日志统计
- **AND** 响应 MUST NOT 返回脚本正文、明文凭据、服务器真实路径或日志文件路径

#### Scenario: 无权限查询脚本执行记录
- **WHEN** 用户查询无权访问项目下的脚本执行记录
- **THEN** API SHALL 返回与资源不存在一致的安全错误
- **AND** 后端 MUST NOT 暴露执行记录、任务、环境或日志是否存在

### Requirement: API 必须提供任务级日志 SSE
后端 API SHALL 提供单任务日志 SSE，用于脚本执行页和执行记录详情页恢复历史日志并接收新增日志。

#### Scenario: 建立任务级日志 SSE
- **WHEN** 前端调用 `GET /api/tasks/{taskId}/log-events`
- **THEN** 后端 SHALL 根据任务所属项目校验访问权限
- **AND** 响应内容类型 SHALL 为 `text/event-stream`
- **AND** 后端 SHALL 先补发历史日志事件，再保持连接推送新增日志事件

#### Scenario: 日志 SSE 事件格式
- **WHEN** 后端发送任务级日志 SSE 事件
- **THEN** SSE 协议层 `id` SHALL 使用递增的 `task_log_events.id`
- **AND** stdout 事件类型 SHALL 为 `task.log.stdout`
- **AND** stderr 事件类型 SHALL 为 `task.log.stderr`
- **AND** 终态事件类型 SHALL 为 `task.succeeded`、`task.failed`、`task.cancelled` 或 `task.timeout`
- **AND** SSE data SHALL 是 JSON

#### Scenario: 日志 SSE 断线恢复
- **WHEN** 前端携带 `Last-Event-ID` 重新连接 `GET /api/tasks/{taskId}/log-events`
- **THEN** 后端 SHALL 将 `Last-Event-ID` 解析为 `task_log_events.id`
- **AND** 后端 SHALL 从持久化日志事件中补发同一任务内 `id` 大于该值的后续日志事件
- **AND** 后端 MUST NOT 补发其他项目或其他任务的日志事件

#### Scenario: 任务级日志 SSE 权限失败
- **WHEN** 无权限用户订阅任务级日志 SSE
- **THEN** 后端 SHALL 拒绝请求
- **AND** 后端 MUST NOT 创建 SSE emitter
- **AND** 后端 MUST NOT 补发历史日志事件

### Requirement: CUSTOM_BASH API 必须保护脚本正文
远程脚本 API SHALL 接收用户提交的 `CUSTOM_BASH` 脚本正文，但 MUST 只在受控存储和 worker 执行期间使用脚本正文。

#### Scenario: 提交 CUSTOM_BASH
- **WHEN** 用户调用 `POST /api/projects/{projectId}/environments/{environmentId}/scripts` 且 `scriptType=CUSTOM_BASH`
- **THEN** 请求 SHALL 包含非空 `script`
- **AND** 后端 SHALL 返回任务和执行记录摘要
- **AND** 响应 MUST NOT 返回脚本正文

#### Scenario: 返回执行记录状态
- **WHEN** API 返回脚本执行记录
- **THEN** API SHALL 只返回 `RUNNING`、`SUCCEEDED` 或 `ERROR` 三类执行记录状态
- **AND** `PENDING` 和 `QUEUED` 任务 SHALL 在执行记录中展示为 `RUNNING`

#### Scenario: 拒绝不安全脚本请求
- **WHEN** `CUSTOM_BASH` 请求缺少脚本正文、脚本过大、脚本类型不合法或环境不可运行
- **THEN** API SHALL 返回统一错误响应
- **AND** 后端 MUST NOT 创建可执行任务或投递队列消息

### Requirement: 环境 API 必须返回环境检查产生的基础信息
环境列表、环境详情、环境快照和环境检查相关 API SHALL 能暴露环境检查生成的非敏感基础信息，供前端在不调用独立环境采集入口的情况下展示 OS、架构、包管理器和检查结果。

#### Scenario: 环境列表返回基础信息
- **WHEN** 已登录用户调用 `GET /api/projects/{projectId}/environments`
- **THEN** 后端 SHALL 校验当前用户有权访问该项目
- **AND** 每个环境项 SHALL 返回最近一次成功环境检查或环境采集产生的基础信息摘要
- **AND** 基础信息摘要 SHALL 包含 OS、OS version、kernel、arch、package manager、基础工具摘要、最近成功检查时间和最近快照 ID
- **AND** 尚无成功环境检查或环境采集时，基础信息摘要 SHALL 返回 `null` 或空摘要
- **AND** 响应 MUST NOT 返回明文凭据、私钥、token、passphrase、完整 `raw_snapshot`、远程敏感路径或日志文件路径

#### Scenario: 环境详情返回基础信息
- **WHEN** 已登录用户调用 `GET /api/environments/{environmentId}`
- **THEN** 后端 SHALL 根据环境所属项目校验访问权限
- **AND** 响应 SHALL 返回该环境最近一次成功环境检查或环境采集产生的基础信息摘要
- **AND** 响应 SHALL 保留凭据遮蔽摘要和跳板机非敏感摘要
- **AND** 响应 MUST NOT 暴露完整快照原文或敏感运行输出

#### Scenario: 环境检查任务完成后可查询基础信息
- **WHEN** 已登录用户创建环境检查任务且任务成功完成
- **THEN** 后端 SHALL 允许后续通过环境列表、环境详情或环境快照 API 查询该次检查产生的基础信息
- **AND** 任务详情 MAY 返回检查任务摘要、状态、进度、错误摘要和关联环境引用
- **AND** 任务详情 MUST NOT 返回完整任务输入快照或明文凭据

#### Scenario: 环境检查失败不改变基础信息响应
- **WHEN** 最近一次环境检查失败
- **THEN** 环境列表和详情 MAY 返回失败后的连接状态
- **AND** 基础信息摘要 SHALL 继续来自上一轮成功检查或成功采集
- **AND** 后端 MUST NOT 用失败任务的空信息覆盖上一轮成功基础信息

### Requirement: 普通环境信息展示不依赖独立采集 API
后端 API SHALL 保持独立环境采集 API 的兼容性，但普通环境页面展示所需的基础信息 SHALL 由环境检查 API 产生。

#### Scenario: 环境检查覆盖普通展示需求
- **WHEN** 前端需要展示环境 OS、架构、包管理器、基础工具和工作目录检查结果
- **THEN** 前端 SHALL 能通过环境检查任务完成后的后端环境查询接口获取这些信息
- **AND** 后端 MUST NOT 要求前端额外调用 `POST /api/projects/{projectId}/environments/{environmentId}/collect` 才能获得普通展示所需基础信息

#### Scenario: 保留重型采集 API
- **WHEN** SBOM 分析、兼容流程或测试需要完整 package、library、native file、repo provide 或 ELF metadata
- **THEN** 后端 MAY 继续提供 `POST /api/projects/{projectId}/environments/{environmentId}/collect`
- **AND** 该 API SHALL 继续执行项目访问权限校验
- **AND** 该 API 的任务输入和响应 MUST NOT 暴露明文凭据或服务器敏感路径

### Requirement: 项目 API 必须暴露源码来源锁定摘要
项目 API SHALL 返回项目源码来源锁定状态，使前端能够判断当前项目应使用压缩包上传还是 Git 同步入口。

#### Scenario: 项目详情返回源码来源锁定
- **WHEN** 已登录用户调用 `GET /api/projects/{projectId}`
- **THEN** 响应 SHALL 包含 `sourceInputType`
- **AND** `sourceInputType` SHALL 为 `ARCHIVE`、`GIT` 或 `null`
- **AND** 后端 SHALL 继续执行项目访问权限校验

#### Scenario: 项目列表和概览返回源码来源锁定
- **WHEN** 已登录用户调用 `GET /api/projects` 或 `GET /api/projects/{projectId}/overview`
- **THEN** 响应 SHALL 包含项目源码来源锁定摘要
- **AND** 未成功入库源码的项目 SHALL 返回 `sourceInputType=null`

### Requirement: 源码 API 必须执行源码来源锁定校验
源码上传和 Git 同步 API SHALL 在创建源码版本或任务前校验项目源码来源锁定状态。

#### Scenario: 锁定 ARCHIVE 后拒绝 Git 同步
- **WHEN** 项目 `sourceInputType=ARCHIVE`
- **AND** 用户调用 `POST /api/projects/{projectId}/sources/git-sync`
- **THEN** API SHALL 返回 `SOURCE_INPUT_TYPE_LOCKED`
- **AND** 响应 SHALL 使用统一错误格式
- **AND** 后端 MUST NOT 创建任务或投递队列消息

#### Scenario: 锁定 GIT 后拒绝压缩包上传
- **WHEN** 项目 `sourceInputType=GIT`
- **AND** 用户调用 `POST /api/projects/{projectId}/sources/archive`
- **THEN** API SHALL 返回 `SOURCE_INPUT_TYPE_LOCKED`
- **AND** 响应 SHALL 使用统一错误格式
- **AND** 后端 MUST NOT 保存源码包或创建源码版本

#### Scenario: 压缩包上传成功响应
- **WHEN** 用户上传有效源码压缩包且后端完成保存、解包和源码版本创建
- **THEN** API SHALL 返回源码版本摘要和导入记录摘要
- **AND** 源码版本摘要 SHALL 包含 `revisionType=ARCHIVE`
- **AND** 响应 MUST NOT 返回 `archivePath`、`workingCopyPath` 或服务器真实路径

#### Scenario: 压缩包上传失败响应
- **WHEN** 压缩包格式不支持、路径不安全、内容为空或源码结构无效
- **THEN** API SHALL 返回明确错误码
- **AND** 后端 MUST NOT 返回服务器临时路径、目标目录或内部异常堆栈

#### Scenario: Git 同步成功后响应源码来源
- **WHEN** Git 同步任务成功完成
- **THEN** 后续项目详情、项目列表和项目概览 SHALL 返回 `sourceInputType=GIT`
- **AND** 源码列表 SHALL 返回新的 `revisionType=GIT` 源码版本摘要

### Requirement: KB 扫描 API 必须创建和查询扫描运行
后端 API SHALL 提供项目级 KB 扫描创建、扫描运行列表、扫描详情、finding 列表和 finding 详情接口。

#### Scenario: 创建 KB 扫描任务
- **WHEN** 已登录用户调用 `POST /api/projects/{projectId}/kb-scans`
- **THEN** 后端 SHALL 校验当前用户有权访问该项目
- **AND** 后端 SHALL 校验请求中的 `sourceRevisionId` 属于该项目，或在未提交时选择当前启用源码版本
- **AND** 后端 SHALL 校验源码版本存在可用工作副本
- **AND** 后端 SHALL 创建 `KB_SCAN` 任务并返回 `taskId`、任务类型和初始状态
- **AND** 后端 SHALL 创建关联该任务的 `kb_scan_runs` 记录
- **AND** API 响应 MUST NOT 返回源码工作副本路径、scanner 输出目录、完整任务输入快照或服务器真实路径

#### Scenario: 查询项目 KB 扫描列表
- **WHEN** 已登录用户调用 `GET /api/projects/{projectId}/kb-scans`
- **THEN** 后端 SHALL 校验当前用户有权访问该项目
- **AND** 后端 SHALL 返回该项目下 KB 扫描运行分页列表
- **AND** 列表项 SHALL 包含 scan id、task id、source revision 摘要、规则版本、状态、summary、开始时间和结束时间
- **AND** 列表接口 SHALL 支持按状态、sourceRevisionId、ruleVersion 和时间范围过滤
- **AND** 列表接口 SHALL 支持按 startedAt、finishedAt、status 和 findings count 排序

#### Scenario: 查询 KB 扫描详情
- **WHEN** 已登录用户调用 `GET /api/kb-scans/{scanId}`
- **THEN** 后端 SHALL 根据扫描运行所属项目校验访问权限
- **AND** 响应 SHALL 返回运行摘要、任务摘要、源码版本摘要、规则包摘要、summary、artifact 摘要和错误摘要
- **AND** 响应 MUST NOT 暴露服务器真实路径、完整 scanner 输入、完整 stdout 或完整 stderr

#### Scenario: 下载 KB 扫描 artifact
- **WHEN** 已登录用户调用 `GET /api/kb-scans/{scanId}/artifacts/{artifactId}/download`
- **THEN** 后端 SHALL 校验 artifact 属于该扫描运行
- **AND** 后端 SHALL 根据扫描运行所属项目校验访问权限
- **AND** 响应 SHALL 使用安全文件名下载 `scan-result.json`、`scan-report.html` 或 `scan.log`
- **AND** 响应 MUST NOT 暴露服务器真实路径、源码工作副本路径或 scanner 输出目录

### Requirement: KB 扫描 artifact 必须支持按格式下载
后端 API SHALL 支持按报告格式下载 KB 扫描 artifact，使前端无需先解析 artifact 列表中的 `artifactId` 即可下载 HTML、JSON 或日志。

#### Scenario: 按 HTML 格式下载 KB 扫描报告
- **WHEN** 已登录用户调用 `GET /api/kb-scans/{scanId}/artifacts:download?format=html`
- **THEN** 后端 SHALL 根据扫描运行所属项目校验访问权限
- **AND** 后端 SHALL 下载该扫描运行的 `SCAN_REPORT_HTML` artifact
- **AND** 响应 SHALL 使用安全文件名和 `text/html` content type
- **AND** 响应 MUST NOT 暴露服务器真实路径、源码工作副本路径或 scanner 输出目录

#### Scenario: 按 JSON 格式下载 KB 扫描结果
- **WHEN** 已登录用户调用 `GET /api/kb-scans/{scanId}/artifacts:download?format=json`
- **THEN** 后端 SHALL 根据扫描运行所属项目校验访问权限
- **AND** 后端 SHALL 下载该扫描运行的 `SCAN_RESULT_JSON` artifact
- **AND** 响应 SHALL 使用安全文件名和 `application/json` content type
- **AND** 响应 MUST NOT 暴露服务器真实路径、源码工作副本路径或 scanner 输出目录

#### Scenario: 按日志格式下载 KB 扫描日志
- **WHEN** 已登录用户调用 `GET /api/kb-scans/{scanId}/artifacts:download?format=log`
- **THEN** 后端 SHALL 根据扫描运行所属项目校验访问权限
- **AND** 后端 SHALL 下载该扫描运行的 `SCAN_LOG` artifact
- **AND** 响应 SHALL 使用安全文件名和 `text/plain` content type
- **AND** 响应 MUST NOT 暴露服务器真实路径、源码工作副本路径或 scanner 输出目录

#### Scenario: 下载格式不支持
- **WHEN** 用户调用按格式下载接口且 `format` 不是 `html`、`json` 或 `log`
- **THEN** 后端 SHALL 返回统一业务错误
- **AND** 后端 MUST NOT 返回 artifact 存储路径、扫描输出目录或内部异常细节

#### Scenario: 指定格式 artifact 不存在
- **WHEN** 用户调用按格式下载接口且该扫描运行没有对应格式 artifact
- **THEN** 后端 SHALL 返回与资源不存在一致的安全错误
- **AND** 后端 MUST NOT 暴露其他 artifact 是否存在或服务器真实路径

#### Scenario: 保留按 artifact ID 下载
- **WHEN** 已登录用户调用 `GET /api/kb-scans/{scanId}/artifacts/{artifactId}/download`
- **THEN** 后端 SHALL 继续按指定 artifact ID 下载文件
- **AND** 后端 SHALL 校验 artifact 属于该扫描运行并校验项目访问权限
- **AND** 响应 MUST NOT 暴露服务器真实路径、源码工作副本路径或 scanner 输出目录

### Requirement: KB finding API 必须支持分页过滤排序
后端 API SHALL 提供 KB finding 分页查询和详情查询能力，用于前端审计扫描结果。

#### Scenario: 查询扫描 finding 列表
- **WHEN** 已登录用户调用 `GET /api/kb-scans/{scanId}/findings`
- **THEN** 后端 SHALL 根据扫描运行所属项目校验访问权限
- **AND** 响应 SHALL 返回该扫描运行下的 finding 分页列表
- **AND** 列表项 SHALL 包含 finding id、rule id、category、severity、title、file path、line range、symbol、status、created at 和 evidence 摘要
- **AND** 响应 MUST NOT 返回服务器真实路径或完整 scanner output 原文

#### Scenario: 过滤 finding 列表
- **WHEN** 前端查询 finding 列表
- **THEN** API SHALL 支持按 severity、category、ruleId、status、filePath、symbol 和 keyword 过滤
- **AND** 多个过滤条件 SHALL 使用 AND 语义
- **AND** 后端 SHALL 只返回当前扫描运行内的 findings
- **AND** 后端 MUST 拒绝不在白名单内的过滤字段

#### Scenario: 排序 finding 列表
- **WHEN** 前端查询 finding 列表
- **THEN** API SHALL 支持按 severity、category、ruleId、filePath、lineStart 和 createdAt 排序
- **AND** 默认排序 SHALL 为 `severity DESC, filePath ASC, lineStart ASC`
- **AND** 后端 MUST 拒绝不在白名单内的排序字段

#### Scenario: 查询 finding 详情
- **WHEN** 已登录用户调用 `GET /api/findings/{findingId}`
- **THEN** 后端 SHALL 根据 finding 所属项目校验访问权限
- **AND** 响应 SHALL 返回 finding 事实详情、扫描运行摘要、源码版本摘要、位置、证据、建议、状态和代码上下文摘要
- **AND** 响应 MUST NOT 暴露服务器真实路径、完整任务输入快照或敏感日志

#### Scenario: 查询 finding 代码上下文
- **WHEN** 已登录用户调用 `GET /api/findings/{findingId}/code-context`
- **THEN** 后端 SHALL 根据 finding 所属项目校验访问权限
- **AND** 后端 SHALL 根据 finding 关联的 `source_revision_id` 和 `file_path` 读取对应源码文件
- **AND** 响应 SHALL 返回 finding id、scan id、source revision id、相对文件路径、文件编码摘要、固定上下文行数、实际上下文起始行、实际上下文结束行、finding 起始行、finding 结束行和代码行数组
- **AND** 代码行数组中每项 SHALL 包含行号、代码文本、是否位于 finding 命中范围、是否为上下文边界外截断提示
- **AND** 响应 MUST NOT 返回服务器真实路径、源码工作副本路径或允许客户端读取任意文件

#### Scenario: 控制 finding 代码上下文范围
- **WHEN** 客户端查询 finding 代码上下文
- **THEN** 后端 SHALL 使用固定上下文边界：命中代码起始行之前最多 20 行，命中代码结束行之后最多 20 行
- **AND** 后端 SHALL 只返回 `finding.line_start - 20` 到 `finding.line_end + 20` 范围内的源码行
- **AND** 当请求范围超出文件边界时，响应 SHALL 返回实际可用的起始行和结束行
- **AND** 响应 SHALL 明确返回 `contextBefore=20` 和 `contextAfter=20`
- **AND** 后端 MUST NOT 因文件上下不足 20 行而返回错误

#### Scenario: Finding 源码文件不可用
- **WHEN** finding 关联的源码工作副本、源码文件或行号不可用
- **THEN** 后端 SHALL 返回安全错误或明确的代码上下文不可用状态
- **AND** 响应 MUST NOT 暴露服务器真实路径、文件系统异常细节或源码工作副本路径

#### Scenario: 无权限查询 finding
- **WHEN** 用户查询无权访问项目下的 finding
- **THEN** 后端 SHALL 返回与资源不存在一致的安全错误
- **AND** 后端 MUST NOT 暴露扫描运行、源码版本或 finding 是否存在

### Requirement: KB 扫描 API MVP 不实现加入问题清单
本 change 的 KB 扫描 API SHALL 不实现 finding 加入问题清单的业务联动。

#### Scenario: 用户尝试加入问题清单
- **WHEN** 客户端调用 `POST /api/kb-scans/{scanId}/findings:add-to-issues`
- **THEN** 后端 MAY 返回未实现或接口不存在
- **AND** 后端 MUST NOT 在本 change 中创建或修改 `issues`、`issue_sources` 或 `issue_events`

### Requirement: 源码上传 API 必须支持 10GB 压缩包请求
后端源码上传 API SHALL 支持最大 10GB 的源码压缩包 multipart 上传，并 SHALL 通过环境变量允许部署时覆盖上限。

#### Scenario: 上传不超过 10GB 的源码包
- **WHEN** 已登录用户调用 `POST /api/projects/{projectId}/sources/archive` 并上传大小不超过 10GB 的受支持源码压缩包
- **THEN** 后端 SHALL 允许 multipart 请求进入源码上传处理流程
- **AND** 后端 SHALL 继续执行项目访问权限校验、源码来源锁定校验、文件格式校验和路径安全校验
- **AND** API 响应 MUST NOT 返回 `archivePath`、`workingCopyPath` 或服务器真实路径

#### Scenario: 上传超过配置上限的源码包
- **WHEN** 用户上传的源码包大小超过后端配置的 multipart 单文件或请求总大小上限
- **THEN** 后端 SHALL 返回统一错误响应
- **AND** 响应 MUST NOT 暴露服务器临时路径、容器内部路径或 multipart parser 内部堆栈

#### Scenario: 通过环境变量覆盖上传上限
- **WHEN** 部署环境设置源码上传 multipart 大小上限环境变量
- **THEN** 后端 SHALL 使用环境变量值覆盖默认 10GB 限制
- **AND** 未设置环境变量时 SHALL 使用 10GB 作为默认上限

### Requirement: Git 同步 API 必须兼容无 .git 后缀仓库 URL
后端 Git 同步 API SHALL 在创建任务前规范化常见 Git 仓库 URL，使缺少 `.git` 后缀的 HTTP(S) 或 SSH 仓库 URL 可以自动兼容。

#### Scenario: HTTP Git URL 自动补齐 .git
- **WHEN** 已登录用户调用 `POST /api/projects/{projectId}/sources/git-sync` 且 `gitUrl` 为 `https://example.com/org/repo`
- **THEN** 后端 SHALL 将任务输入中的 `gitUrl` 规范化为 `https://example.com/org/repo.git`
- **AND** 后端 SHALL 使用规范化后的 URL 创建 Git 同步任务
- **AND** 响应和日志 MUST NOT 暴露明文凭据

#### Scenario: 已有 .git 后缀保持不变
- **WHEN** 用户提交的 `gitUrl` 已经以 `.git` 结尾
- **THEN** 后端 SHALL 保持该 URL 不重复追加 `.git`
- **AND** 后端 SHALL 继续执行内嵌凭据校验

#### Scenario: SSH scp 风格 Git URL 自动补齐 .git
- **WHEN** 用户提交的 `gitUrl` 为 `git@example.com:org/repo`
- **THEN** 后端 SHALL 将任务输入中的 `gitUrl` 规范化为 `git@example.com:org/repo.git`
- **AND** 后端 SHALL 使用规范化后的 URL 创建 Git 同步任务

#### Scenario: Git URL 内嵌凭据仍被拒绝
- **WHEN** 用户提交的 HTTP(S) Git URL 包含用户名、密码或 token
- **THEN** 后端 SHALL 返回 `GIT_URL_CONTAINS_CREDENTIAL`
- **AND** 后端 MUST NOT 创建源码导入记录、任务或凭据

#### Scenario: 不识别或不适合规范化的 Git URL
- **WHEN** 用户提交的 Git URL 带 query、fragment 或不属于可安全识别的仓库 URL 形式
- **THEN** 后端 MAY 保持原 URL 不做 `.git` 自动补齐
- **AND** 后端 SHALL 继续执行非空和安全校验

### Requirement: API 必须提供迁移输入列表和详情
后端 API SHALL 提供源码版本作用域下的迁移输入 reference 列表、详情和状态更新接口。该 API SHALL 作为各分析节点内确认和报告输入审计的通用后端能力，不再作为独立迁移输入合并页面的主入口。

#### Scenario: 查询源码版本迁移输入列表
- **WHEN** 已登录用户调用 `GET /api/projects/{projectId}/source-revisions/{sourceRevisionId}/migration-inputs`
- **THEN** 后端 SHALL 校验当前用户有权访问该项目
- **AND** 后端 SHALL 校验源码版本属于该项目
- **AND** 响应 SHALL 返回分页迁移输入 reference 列表
- **AND** 列表项 SHALL 包含 `id`、`issueTitle`、`sourceType`、`sourceRefId`、`detectedLevel`、`locationLabel`、`recommendationSummary`、`status`、`createdAt` 和 `updatedAt`

#### Scenario: 迁移输入列表过滤排序
- **WHEN** 用户查询迁移输入列表
- **THEN** API SHALL 支持按 `sourceType`、`detectedLevel`、`status` 和关键字过滤
- **AND** API SHALL 支持按 `createdAt`、`updatedAt`、`sourceType`、`detectedLevel`、`status` 和 `issueTitle` 排序
- **AND** 后端 MUST 拒绝不在白名单内的过滤或排序字段

#### Scenario: 查询迁移输入详情
- **WHEN** 已登录用户调用 `GET /api/migration-inputs/{migrationInputId}`
- **THEN** 后端 SHALL 根据 reference 所属项目校验访问权限
- **AND** 响应 SHALL 返回 reference 摘要和按来源类型展开的真实详情
- **AND** 响应 MUST NOT 暴露服务器真实路径、完整任务输入快照或明文凭据

#### Scenario: 更新迁移输入确认状态
- **WHEN** 用户调用 `PATCH /api/migration-inputs/{migrationInputId}` 更新确认状态或备注
- **THEN** 后端 SHALL 校验项目访问权限
- **AND** `status` SHALL 只允许 `CANDIDATE`、`CONFIRMED` 或 `EXCLUDED`
- **AND** 后端 SHALL 更新 reference 状态和更新时间

### Requirement: API 必须支持迁移输入批量导入和自动合并
后端 API SHALL 提供批量导入真实结果记录接口，供各分析节点将本节点结果加入当前源码版本迁移输入。自动合并当前前序结果接口 SHALL 不再作为新主流程入口。

#### Scenario: 批量导入迁移输入
- **WHEN** 已登录用户调用 `POST /api/projects/{projectId}/source-revisions/{sourceRevisionId}/migration-inputs:import`
- **THEN** 请求 SHALL 包含 `sourceType` 和非空 `sourceRefIds`
- **AND** 后端 SHALL 校验项目权限和源码版本归属
- **AND** 后端 SHALL 将合法来源记录导入该源码版本迁移输入集合
- **AND** 后端 SHALL 根据来源结果风险等级设置默认确认状态
- **AND** 响应 SHALL 返回 `createdCount`、`skippedDuplicateCount` 和已创建 reference 摘要

#### Scenario: 自动合并迁移输入已废弃
- **WHEN** 已登录用户调用 `POST /api/projects/{projectId}/source-revisions/{sourceRevisionId}/migration-inputs:auto-merge`
- **THEN** 后端 SHALL 返回 deprecated 业务错误或兼容响应
- **AND** 新前端主流程 MUST NOT 调用该接口准备报告输入

#### Scenario: 新流程拒绝 SBOM 来源导入
- **WHEN** 已登录用户提交 `sourceType=SBOM_RESULT` 导入迁移输入
- **THEN** 后端 SHALL 拒绝请求或返回 deprecated 业务错误
- **AND** 后端 MUST NOT 为新流程创建新的 `SBOM_RESULT` reference

### Requirement: API 报告生成必须只消费确认后的迁移输入
后端报告生成 API SHALL 只使用当前源码版本下已确认的迁移输入 reference 作为报告内容来源。确认状态 MAY 由节点默认规则生成，也 MAY 由用户在各分析节点内人工调整。

#### Scenario: 生成报告时读取确认项
- **WHEN** 用户为某个源码版本生成迁移报告
- **THEN** 后端 SHALL 查询该 `sourceRevisionId` 下 `status=CONFIRMED` 的迁移输入 reference
- **AND** 后端 SHALL 按 `sourceType` 和 `sourceRefId` 回查真实来源详情构造报告内容
- **AND** 后端 MUST NOT 直接把未确认的 KB findings、依赖分析 results 或历史 SBOM result items 写入报告

#### Scenario: 无确认项生成报告
- **WHEN** 当前源码版本没有已确认迁移输入
- **THEN** 后端 SHALL 返回明确业务错误或生成空内容摘要
- **AND** 行为 SHALL 在 API 文档中明确

### Requirement: API 必须支持新建环境前连接预检
后端 API SHALL 提供不依赖 `environmentId` 的项目级环境连接预检接口，使前端能在新建环境提交前验证 SSH 连接配置。

#### Scenario: 创建新建环境连接预检任务
- **WHEN** 已登录用户调用 `POST /api/projects/{projectId}/environments/test-connection` 并提交完整 SSH 连接配置
- **THEN** 后端 SHALL 校验当前用户有权访问该项目
- **AND** 后端 SHALL 校验连接配置字段合法
- **AND** 后端 SHALL 创建 `ENV_TEST_CONNECTION` 任务并返回 `taskId`、任务类型和初始状态
- **AND** 后端 MUST NOT 创建 `environments` 记录
- **AND** API 响应 MUST NOT 返回明文密码、私钥、passphrase、加密密文或完整任务输入快照

#### Scenario: 新建环境预检使用表单明文凭据但不保存正式凭据
- **WHEN** 新建环境连接预检请求提交 `password` 或 `privateKey`/`passphrase`
- **THEN** 后端 SHALL 只在请求处理阶段读取明文凭据
- **AND** 后端 SHALL 将凭据加密保存为任务级临时 secret payload
- **AND** 后端 MUST NOT 将该凭据写入正式 `credentials` 表
- **AND** `tasks.input_snapshot` MUST only contain `temporaryCredentialPayloadId`、校验 hash 和非敏感连接字段
- **AND** API 响应、任务输入、日志、事件和审计 MUST NOT 暴露明文密码、私钥、passphrase 或加密密文
- **AND** 预检任务进入终态后，后端 SHALL 删除对应任务级临时 secret payload，并保留任务状态和日志供查询

#### Scenario: 新建环境预检支持跳板机
- **WHEN** 新建环境连接预检请求包含 `jumpHostEnvironmentId`
- **THEN** 后端 SHALL 校验跳板机环境存在、属于同一项目且已启用
- **AND** 后端 SHALL 拒绝多级跳板链路
- **AND** 任务输入 SHALL 只保存跳板机环境 ID 或安全摘要
- **AND** API 响应 MUST NOT 返回跳板机凭据明文

#### Scenario: 新建环境预检拒绝非法凭据
- **WHEN** 新建环境连接预检请求缺少凭据、引用无效凭据或提交的凭据无法安全加密保存为任务级 secret payload
- **THEN** API SHALL 返回统一错误响应
- **AND** 后端 MUST NOT 创建任务
- **AND** 后端 MUST NOT 保存明文凭据或泄漏凭据内容

#### Scenario: 已创建环境连接复测接口保持兼容
- **WHEN** 已登录用户调用 `POST /api/projects/{projectId}/environments/{environmentId}/test-connection`
- **THEN** 后端 SHALL 继续按已创建环境配置创建连接测试任务
- **AND** 后端 SHALL 校验环境属于 URL 中的项目
- **AND** 响应格式 SHALL 与新建环境预检任务保持一致

### Requirement: API 环境列表必须支持状态过滤
后端环境列表 API SHALL 支持按环境运行状态过滤环境，供 SBOM 配置等前端视图只展示可用环境。

#### Scenario: 按状态查询环境列表
- **WHEN** 已登录用户调用 `GET /api/projects/{projectId}/environments?status=AVAILABLE`
- **THEN** 后端 SHALL 校验当前用户有权访问该项目
- **AND** 后端 SHALL 只返回 `environments.status=AVAILABLE` 的环境
- **AND** 响应 SHALL 继续使用分页响应格式
- **AND** 响应 MUST NOT 返回明文凭据

#### Scenario: 状态过滤与现有过滤条件组合
- **WHEN** 已登录用户调用环境列表接口并同时提交 `role`、`enabled` 和 `status`
- **THEN** 后端 SHALL 使用 AND 语义组合过滤条件
- **AND** 后端 SHALL 保持稳定默认排序

#### Scenario: 非法状态过滤值
- **WHEN** 用户提交不在环境状态枚举内的 `status`
- **THEN** API SHALL 返回统一参数校验错误
- **AND** 后端 MUST NOT 将非法状态值拼接到 SQL

### Requirement: API 必须支持迁移评估报告生成和查询
后端 API SHALL 支持创建、查询和下载迁移评估报告，并 SHALL 对所有报告接口执行项目访问权限校验。

#### Scenario: 创建迁移评估报告
- **WHEN** 已登录用户调用 `POST /api/projects/{projectId}/reports/evaluation`
- **THEN** 后端 SHALL 校验用户有该项目访问权限
- **AND** 请求体 MAY 包含 `sourceRevisionId`
- **AND** 未提交 `sourceRevisionId` 时后端 SHALL 使用项目当前 active source revision
- **AND** 后端 SHALL 校验源码版本属于该项目
- **AND** 响应 SHALL 返回报告生成任务的 `taskId`、任务类型和当前状态

#### Scenario: 创建报告没有源码版本
- **WHEN** 项目没有 active source revision 且请求未提交 `sourceRevisionId`
- **THEN** 后端 SHALL 返回统一业务错误
- **AND** 后端 MUST NOT 创建报告任务

#### Scenario: 查询项目报告列表
- **WHEN** 用户调用 `GET /api/projects/{projectId}/reports/evaluation`
- **THEN** 后端 SHALL 返回该项目的分页报告列表
- **AND** 列表接口 SHALL 支持按 `sourceRevisionId`、`status`、`riskLevel` 和时间范围过滤
- **AND** 列表接口 SHALL 支持按 `generatedAt`、`createdAt`、`status` 和 `riskLevel` 排序
- **AND** 响应 MUST NOT 返回完整报告正文、对象存储内部 key、`htmlPath`、`jsonPath` 或其他服务器真实路径

#### Scenario: 查询最新迁移评估报告
- **WHEN** 用户调用 `GET /api/projects/{projectId}/reports/evaluation/latest`
- **THEN** 后端 SHALL 返回该项目最近生成或创建的评估报告摘要
- **AND** 请求 MAY 支持 `sourceRevisionId` 过滤
- **AND** 没有报告时 SHALL 返回空数据或明确的 not found 业务响应

#### Scenario: 查询报告详情
- **WHEN** 用户调用 `GET /api/reports/{reportId}`
- **THEN** 后端 SHALL 通过 reportId 反查所属项目并校验访问权限
- **AND** 响应 SHALL 包含报告 ID、项目 ID、源码版本 ID、任务 ID、类型、状态、标题、摘要、总体风险、源码版本摘要、制品摘要、生成时间和创建时间
- **AND** 若存在上一份可比较历史报告，响应 SHOULD 包含 latest comparison 入口摘要或 base report 摘要
- **AND** 响应 MUST NOT 返回服务器真实文件路径、bucket 或 object key

#### Scenario: 查询报告输入快照
- **WHEN** 用户调用 `GET /api/reports/{reportId}/inputs`
- **THEN** 后端 SHALL 返回该报告使用的输入快照分页列表
- **AND** 列表接口 SHALL 支持按 `inputType` 和关键字过滤
- **AND** 列表接口 SHALL 支持按 `createdAt`、`inputType` 和快照内风险展示字段排序

#### Scenario: 下载报告
- **WHEN** 用户调用 `GET /api/reports/{reportId}/download?format=HTML` 或 `format=JSON`
- **THEN** 后端 SHALL 校验报告所属项目访问权限
- **AND** 后端 SHALL 从对象存储制品或兼容的历史受控存储中返回对应格式的报告内容
- **AND** 后端 MUST NOT 支持未明确允许的格式
- **AND** 响应 MUST NOT 暴露对象存储 bucket、object key 或服务器真实路径

#### Scenario: 普通用户不能访问其他项目报告
- **WHEN** 普通用户通过 reportId 访问其他用户项目的报告详情、输入快照或下载接口
- **THEN** 后端 SHALL 返回 `FORBIDDEN` 或等效访问拒绝错误
- **AND** 后端 MUST NOT 泄露报告是否存在于其他项目

### Requirement: API 必须提供报告比较入口
后端 API SHALL 将评估报告历史查看和报告比较作为报告页面的主流程能力，并 SHALL 保留源码复检 API 作为兼容入口。

#### Scenario: 创建报告比较
- **WHEN** 前端在报告页面发起当前报告与历史报告比较
- **THEN** 前端 SHALL 调用 `POST /api/reports/{reportId}/comparisons`
- **AND** 请求体 MAY 包含 `baseReportId`
- **AND** 未传入 `baseReportId` 时后端 SHALL 自动选择上一份可比较历史报告
- **AND** 响应 SHALL 返回比较运行 ID、任务 ID、状态、base report 摘要和 target report 摘要

#### Scenario: 查询报告比较结果
- **WHEN** 前端查看报告比较结果
- **THEN** 前端 SHALL 能调用 `GET /api/report-comparisons/{comparisonId}` 获取比较摘要
- **AND** 前端 SHALL 能调用 `GET /api/report-comparisons/{comparisonId}/diff-items` 获取分页差异项
- **AND** 前端 SHALL 能调用 `GET /api/report-comparisons/{comparisonId}/diff-items/{diffItemId}` 获取差异详情
- **AND** 差异列表接口 SHALL 支持分页、排序和过滤

#### Scenario: 源码比较 API 作为兼容能力保留
- **WHEN** 文档描述 `GET /api/projects/{projectId}/source/compare/latest`、`POST /api/projects/{projectId}/rechecks` 或 `POST /api/projects/{projectId}/rechecks/latest`
- **THEN** API 设计 SHALL 标记这些接口为兼容能力
- **AND** 新主流程 SHALL 引导前端使用报告比较 API

### Requirement: 自动化测试 API 必须支持远程测试任务
后端 API SHALL 提供自动化测试任务创建、列表、详情和取消接口，并 SHALL 以项目访问权限作为授权边界。

#### Scenario: 创建自动化测试任务
- **WHEN** 已登录用户调用 `POST /api/projects/{projectId}/tests` 并提交源码版本、执行环境、测试命令或脚本、工作目录、超时配置、可选 `environmentVariables` 和可选 `reportTargets` 数组
- **THEN** 后端 SHALL 校验用户有权访问该项目
- **AND** 后端 SHALL 校验源码版本、执行环境和可选构建运行都属于该项目
- **AND** 后端 SHALL 创建 `AUTOMATED_TEST` 任务并返回 `taskId`、任务类型和初始状态
- **AND** 响应 MUST NOT 返回明文凭据、服务器真实路径或完整任务输入快照

#### Scenario: 创建带多个报告回收目标的测试任务
- **WHEN** 创建测试任务请求包含非空 `reportTargets` 数组
- **THEN** 后端 SHALL 校验数组中的每个报告目标包含合法 `type` 和 `path`
- **AND** `type=FILE` SHALL 表示明确报告文件路径
- **AND** `type=DIRECTORY` SHALL 表示报告输出目录
- **AND** 后端 SHALL 拒绝绝对路径、`..` 越界、空路径和不允许的目录扫描
- **AND** 任务完成后测试详情 SHALL 能返回已回收报告的 artifact 摘要

#### Scenario: 创建不回收报告的测试任务
- **WHEN** 创建测试任务请求未传 `reportTargets` 或传入空数组
- **THEN** 后端 SHALL 创建只回收退出码、stdout 和 stderr 的测试任务
- **AND** 后端 MUST NOT 因缺少报告路径拒绝请求

#### Scenario: 创建带环境变量的测试任务
- **WHEN** 创建测试任务请求包含 `environmentVariables`
- **THEN** 后端 SHALL 校验环境变量名格式和值长度
- **AND** 后端 SHALL 将环境变量作为任务级运行参数传递给 worker
- **AND** API 响应、任务事件和审计摘要 MUST NOT 返回环境变量明文值

#### Scenario: 测试任务依赖构建结果
- **WHEN** 创建测试任务请求包含 `buildRunId`
- **THEN** 后端 SHALL 校验该构建运行已完成且属于同一项目和源码版本
- **AND** 后端 MAY 拒绝未成功的构建运行作为自动化测试前置条件

#### Scenario: 查询项目测试列表
- **WHEN** 已登录用户调用 `GET /api/projects/{projectId}/tests`
- **THEN** 后端 SHALL 返回该项目的分页测试运行列表
- **AND** 列表接口 SHALL 支持按 `sourceRevisionId`、`environmentId`、`status` 和时间范围过滤
- **AND** 列表接口 SHALL 支持按 `startedAt`、`finishedAt`、`status` 和 `createdAt` 排序
- **AND** 响应 MUST NOT 返回服务器真实路径或完整日志

#### Scenario: 查询测试详情
- **WHEN** 已登录用户调用 `GET /api/tests/{testRunId}`
- **THEN** 后端 SHALL 根据测试运行所属项目校验访问权限
- **AND** 响应 SHALL 返回测试运行 ID、项目 ID、源码版本、执行环境摘要、任务 ID、状态、退出码、输出摘要、日志引用摘要和报告 artifact 摘要
- **AND** 响应 MUST NOT 返回服务器真实路径、明文凭据或完整日志

#### Scenario: 取消测试任务
- **WHEN** 已登录用户调用 `POST /api/tests/{testRunId}/cancel`
- **THEN** 后端 SHALL 根据测试运行所属项目校验访问权限
- **AND** 后端 SHALL 复用任务取消机制取消关联任务
- **AND** 响应 SHALL 返回取消后的任务或测试运行摘要

### Requirement: 自动化测试 API 继续保持 MVP 摘要边界
自动化测试 API SHALL 只返回测试运行摘要，不 SHALL 承诺结构化测试报告解析、日志比较或用例级操作。

#### Scenario: 不提供失败用例重跑
- **WHEN** 前端请求失败用例重跑能力
- **THEN** 后端 MUST NOT 在本 change 中新增 `rerun failed cases` API

#### Scenario: 不返回用例级列表
- **WHEN** 前端查询测试详情
- **THEN** 后端 MUST NOT 返回 JUnit、CTest、pytest 或类似报告中的单个测试用例列表
- **AND** 后端 MAY 返回用户命令给出的摘要计数字段

#### Scenario: 不提供日志比较
- **WHEN** 前端请求测试日志比较结果
- **THEN** 后端 MUST NOT 在本 change 中新增测试日志比较 API

### Requirement: 构建 API 必须支持任务级环境变量
构建验证 API SHALL 允许前端为单次构建任务传入环境变量，并 SHALL 在远程构建命令或脚本执行时注入这些变量。

#### Scenario: 创建带环境变量的构建任务
- **WHEN** 已登录用户调用 `POST /api/projects/{projectId}/builds` 且请求包含 `environmentVariables`
- **THEN** 后端 SHALL 校验用户有权访问该项目
- **AND** 后端 SHALL 校验源码版本和执行环境属于该项目
- **AND** 后端 SHALL 校验环境变量名格式、数量、单值长度和总大小
- **AND** 后端 SHALL 将环境变量作为任务级运行参数传递给 worker
- **AND** 环境变量 MUST NOT 写入正式环境配置、凭据表或系统配置

#### Scenario: 构建环境变量脱敏
- **WHEN** 后端返回构建详情、构建列表、任务事件、SSE data、审计摘要或错误信息
- **THEN** 后端 MUST NOT 返回环境变量明文值
- **AND** 名称包含 `TOKEN`、`PASSWORD`、`SECRET`、`KEY` 或 `PASS` 的变量 MUST 被视为敏感变量
- **AND** 后端 SHOULD 只返回环境变量名称、是否已配置和遮蔽摘要

#### Scenario: 构建环境变量非法
- **WHEN** 构建创建请求包含非法变量名、空变量名、超过长度限制的值或超过数量限制的变量集合
- **THEN** API SHALL 返回统一参数校验错误
- **AND** 后端 MUST NOT 创建构建运行记录
- **AND** 后端 MUST NOT 创建或投递构建任务

#### Scenario: 构建详情不暴露任务输入快照
- **WHEN** 已登录用户查询构建详情或项目构建列表
- **THEN** 响应 SHALL 返回构建运行摘要、状态、退出码、日志引用和脱敏环境变量摘要
- **AND** 响应 MUST NOT 返回 `tasks.input_snapshot` 原文
- **AND** 响应 MUST NOT 返回服务器真实路径、明文凭据或环境变量明文值

### Requirement: API 必须以前置项目运行环境作为远程运行环境来源
后端 API SHALL 提供项目运行环境配置接口，并 SHALL 使 SBOM、源码部署、构建验证和自动化测试从该配置读取环境，而不是从业务运行请求读取环境选择。

#### Scenario: 查询项目运行环境配置
- **WHEN** 前端打开环境管理页或项目概览页
- **THEN** 前端 SHALL 能调用 `GET /api/projects/{projectId}/runtime-environments`
- **AND** 响应 SHALL 包含 source 环境、target 环境、配置版本、是否 ready 和缺失项

#### Scenario: 更新项目运行环境配置
- **WHEN** 前端在环境管理页选择源环境、目标环境或同时选择两者
- **THEN** 前端 SHALL 调用 `PATCH /api/projects/{projectId}/runtime-environments`
- **AND** 请求 MAY 只包含 `sourceEnvironmentId`
- **AND** 请求 MAY 只包含 `targetEnvironmentId`
- **AND** 请求 MAY 同时包含 `sourceEnvironmentId` 和 `targetEnvironmentId`
- **AND** 后端 SHALL 校验提交的环境属于当前项目且已启用

#### Scenario: SBOM 创建不再提交环境 ID
- **WHEN** 前端调用 `POST /api/projects/{projectId}/sbom-analyses`
- **THEN** 请求 MUST NOT 要求提交 `sourceEnvironmentId` 或 `targetEnvironmentId`
- **AND** 后端 SHALL 从项目运行环境配置读取双环境
- **AND** 双环境未配置完整时 API SHALL 返回明确业务错误

#### Scenario: 源码部署不再提交环境列表
- **WHEN** 前端调用 `POST /api/projects/{projectId}/source-deployments`
- **THEN** 请求 MUST NOT 要求提交 `environmentIds`
- **AND** 后端 SHALL 将源码部署到项目运行环境配置中的源环境和目标环境
- **AND** 双环境未配置完整时 API SHALL 返回明确业务错误

#### Scenario: 构建验证不再提交源码部署环境选择
- **WHEN** 前端调用 `POST /api/projects/{projectId}/builds`
- **THEN** 请求 MUST NOT 要求提交 `sourceDeploymentIds`
- **AND** 后端 SHALL 基于当前源码版本、项目运行环境配置和成功源码部署记录创建构建运行
- **AND** 缺少可用源码部署记录时 API SHALL 返回明确业务错误

#### Scenario: 自动化测试不再提交环境 ID
- **WHEN** 前端调用 `POST /api/projects/{projectId}/tests`
- **THEN** 请求 MUST NOT 要求提交 `environmentId`
- **AND** 后端 SHALL 从关联构建运行或当前项目运行环境配置确定测试环境
- **AND** API 响应 MUST NOT 暴露明文凭据或服务器真实路径

### Requirement: API 必须支持自动化测试节点的自动比较结果查询
后端 API SHALL 提供测试结果比较列表、详情、差异项查询、取消和原始 agent 输出下载能力，并 SHALL 以项目访问权限作为授权边界。比较任务 SHALL 由自动化测试节点完成后自动创建，不 SHALL 要求用户手动创建。

#### Scenario: 自动化测试完成后可查询比较摘要
- **WHEN** 已登录用户查询自动化测试详情、项目测试列表或项目比较列表
- **THEN** API SHALL 能返回自动化测试节点关联的比较摘要
- **AND** 比较摘要 SHALL 包含比较运行 ID、状态、总体结论、风险等级、置信度、摘要、建议、不可用原因和关键时间字段
- **AND** 响应 MUST NOT 返回完整日志、完整 agent 输入、明文凭据或模型 secret

#### Scenario: 模型未配置时展示比较不可用
- **WHEN** 自动化测试完成但 `TEST_LOG_COMPARE` 阶段未配置可用模型
- **THEN** API SHALL 返回测试结果本身
- **AND** 比较摘要 SHALL 表达 `UNAVAILABLE` 或等效状态
- **AND** 不可用原因 SHOULD 为 `TEST_COMPARE_MODEL_NOT_CONFIGURED`
- **AND** 不可用消息 SHALL 说明测试结果比较功能未配置模型，当前不可用

#### Scenario: 查询项目测试结果比较列表
- **WHEN** 已登录用户调用 `GET /api/projects/{projectId}/test-comparisons`
- **THEN** API SHALL 返回分页比较运行列表
- **AND** 列表接口 SHALL 支持按状态、源码版本、测试运行 ID、环境、风险等级和时间范围过滤
- **AND** 列表接口 SHALL 支持按 `createdAt`、`finishedAt`、`status` 和 `riskLevel` 排序

#### Scenario: 查询测试结果比较详情
- **WHEN** 已登录用户调用 `GET /api/test-comparisons/{comparisonId}`
- **THEN** API SHALL 通过比较运行所属项目校验访问权限
- **AND** 响应 SHALL 返回比较摘要、两侧测试记录摘要、状态、总体结论、风险等级、置信度、建议、agent 输出 artifact 摘要和关键时间字段
- **AND** 响应 MUST NOT 返回完整日志、完整 agent 输入、对象存储内部 key 或服务器真实路径

#### Scenario: 查询测试结果比较差异项
- **WHEN** 已登录用户调用 `GET /api/test-comparisons/{comparisonId}/findings`
- **THEN** API SHALL 返回分页差异项列表
- **AND** 列表接口 SHALL 支持按严重程度、差异类型、置信度和关键字过滤
- **AND** 列表接口 SHALL 支持按序号、严重程度、置信度和创建时间排序

#### Scenario: 取消测试结果比较
- **WHEN** 已登录用户调用 `POST /api/test-comparisons/{comparisonId}/cancel`
- **THEN** API SHALL 校验访问权限
- **AND** 后端 SHALL 复用任务取消机制取消关联 `TEST_LOG_COMPARE` 任务
- **AND** 响应 SHALL 返回取消后的任务或比较运行摘要

#### Scenario: 下载原始 agent 输出
- **WHEN** 已登录用户调用 `GET /api/test-comparisons/{comparisonId}/agent-output`
- **THEN** API SHALL 校验访问权限
- **AND** 后端 SHALL 返回该比较运行保存的原始 agent 输出 artifact
- **AND** 响应 MUST NOT 暴露对象存储 bucket、object key 或服务器真实路径

#### Scenario: 不提供手动创建比较入口
- **WHEN** 前端需要触发自动化测试节点
- **THEN** 前端 SHALL 只调用自动化测试创建接口
- **AND** 后端 MUST NOT 要求前端再调用测试结果比较创建接口
- **AND** 若后端保留内部方法创建比较，外部 API MUST NOT 暴露任意模型参数、prompt 或完整日志输入

#### Scenario: test-support 可手动触发比较
- **WHEN** 后端以 test-support 构建运行，E2E 或开发测试需要绕过完整自动化测试节点手动触发比较
- **THEN** 后端 MAY 提供 test-support only 的 `POST /api/test-support/projects/{projectId}/test-comparisons`
- **AND** 请求 SHALL 只接受 `sourceTestRunId` 和 `targetTestRunId` 等测试夹具所需字段
- **AND** 该接口 MUST NOT 编译进入默认生产 jar
- **AND** 生产环境 MUST NOT 暴露 `/api/test-support/projects/{projectId}/test-comparisons`
- **AND** 该接口 MUST NOT 接受任意模型参数、prompt、完整日志内容或 provider secret

### Requirement: 自动化测试 API 必须暴露自动比较状态
后端 API SHALL 让前端能够在自动化测试节点中展示比较进度、报告或不可用原因。

#### Scenario: 测试详情包含自动比较状态
- **WHEN** 前端查询自动化测试详情或项目测试相关摘要
- **THEN** 后端 SHALL 返回该测试节点关联的比较状态或比较摘要
- **AND** 未配置 `TEST_LOG_COMPARE` 模型时，该摘要 SHALL 表达比较功能不可用
- **AND** 后端 MUST NOT 因比较功能不可用隐藏或拒绝已有测试结果

### Requirement: 模型测试 API 必须返回指定 agent 兼容性分项结果
后端 API SHALL 在模型 profile 测试响应中返回 agent 类型、阶段无关的通用分项 probe 结果和聚合兼容性状态，使前端能够判断模型 Profile 是否能被指定 Agent Runtime 驱动。

#### Scenario: 返回模型测试分项结果
- **WHEN** 已认证配置所有者调用模型 profile Agent 兼容性测试 API
- **THEN** 响应 SHALL 包含每个通用 probe 的名称、状态、错误码、脱敏消息和测试时间
- **AND** 响应 SHALL 包含当前测试的 agent runtime ID 和 agent 类型
- **AND** 响应 SHALL 包含 Agent 响应、workspace 读取、workspace 写入和聚合兼容状态
- **AND** 响应 MUST NOT 包含测试比较、KB AI 扩展或其他业务阶段兼容状态
- **AND** 响应 MUST NOT 返回 API key、授权头、完整 agent 命令或服务器真实路径

#### Scenario: 列表展示最近兼容性状态
- **WHEN** 已认证配置所有者查询模型 profile 列表或详情
- **THEN** 列表项 SHALL 仅返回本人 profile 对每个 Agent Runtime 的最近通用兼容状态、通用检查摘要和测试时间
- **AND** 列表项 MUST NOT 返回 `lastTestCompareCompatible` 或其他阶段兼容性字段
- **AND** 列表项 MUST NOT 返回 provider secret

### Requirement: 自动化测试 API 必须暴露合并日志摘要
自动化测试 API SHALL 在测试详情或日志查询能力中区分 merged log、stdout log 和 stderr log，并 SHALL 将 merged log 作为推荐展示日志。

#### Scenario: 查询测试详情包含 merged log 引用
- **WHEN** 用户查询自动化测试详情
- **THEN** 响应 SHALL 返回 merged log 引用或可查询状态
- **AND** 响应 MAY 返回 stdout/stderr 调试日志引用
- **AND** 响应 MUST NOT 返回服务器真实日志路径

### Requirement: 测试比较 API 必须返回结构化环境统计
测试比较 API SHALL 返回 source 与 target 两侧结构化测试统计，供前端展示测试数量、通过数、失败数、失败项和失败原因。

#### Scenario: 查询比较详情返回环境统计
- **WHEN** 已登录用户调用 `GET /api/test-comparisons/{comparisonId}`
- **THEN** 响应 SHALL 包含 source 与 target 环境的测试统计摘要
- **AND** 每侧摘要 SHALL 包含状态、退出码、测试总数、执行数、通过数、失败数、跳过数、失败项、失败原因、统计依据和置信度
- **AND** 响应 MUST NOT 返回完整日志、完整 agent 输入、API key、对象存储内部 key 或服务器真实路径

### Requirement: API 必须提供优化轮次聚合查询
后端 API SHALL 提供项目级优化轮次概览、列表和详情接口，用于展示多轮优化结果聚合；当前轮次的有效验证摘要 SHALL 复用与项目 overview 和优化 latest 相同的解析规则。

#### Scenario: 查询当前优化概览
- **WHEN** 已登录用户调用 `GET /api/projects/{projectId}/optimization/overview`
- **THEN** 后端 SHALL 校验用户有权访问该项目
- **AND** 响应 SHALL 返回当前 active 源码版本对应的优化轮次
- **AND** 响应 SHALL 包含迁移输入、报告、复检、源码部署、构建、测试和测试结果比较摘要，并区分当前有效验证事实与原始历史事实
- **AND** 响应 MUST NOT 返回完整日志、完整报告正文、完整 agent 输入、明文凭据或服务器真实路径

#### Scenario: 查询优化轮次列表
- **WHEN** 已登录用户调用 `GET /api/projects/{projectId}/optimization/cycles`
- **THEN** 后端 SHALL 返回该项目优化轮次分页列表
- **AND** 列表接口 SHALL 支持按 `sourceRevisionId`、`status` 和时间范围过滤
- **AND** 列表接口 SHALL 支持按 `cycleNumber`、`createdAt`、`updatedAt` 和 `startedAt` 排序
- **AND** 当前轮次 SHALL 使用统一当前摘要，非当前轮次 SHALL 保留历史事实摘要且不参与当前 workflow 完成判断

#### Scenario: 查询优化轮次详情
- **WHEN** 已登录用户调用 `GET /api/projects/{projectId}/optimization/cycles/{cycleId}`
- **THEN** 后端 SHALL 校验该轮次属于该项目
- **AND** 响应 SHALL 返回轮次元数据、源码版本摘要、聚合摘要和相关资源引用
- **AND** 响应 MUST NOT 返回任何子节点的完整日志正文、对象存储内部 key 或服务器真实路径
- **AND** 失效 SHALL 只改变当前资格，MUST NOT 删除历史事实或隐藏整个历史轮次

#### Scenario: 优化轮次 API 不触发任务
- **WHEN** 用户查询优化概览、轮次列表或轮次详情
- **THEN** 后端 MUST NOT 创建扫描、报告、复检、源码部署、构建、测试或测试比较任务
- **AND** API SHALL 只返回已有事实数据的聚合展示和派生验证状态

### Requirement: 归档 API 必须支持当前状态归档和最新归档概览
后端 API SHALL 为归档节点页提供当前状态归档入口、最新归档 overview、归档列表、详情和下载能力。

#### Scenario: 查询归档 overview
- **WHEN** 已登录用户调用 `GET /api/projects/{projectId}/archives/overview`
- **THEN** 后端 SHALL 校验用户有权访问该项目
- **AND** 响应 SHALL 返回最新一条归档记录摘要
- **AND** 响应 SHALL 返回该归档记录包含的 `nodeType`、`candidateType`、标题、格式和数量摘要
- **AND** 若项目尚无归档记录，响应 SHALL 返回空 overview

#### Scenario: 创建当前状态归档
- **WHEN** 已登录用户调用 `POST /api/projects/{projectId}/archives`
- **THEN** 后端 SHALL 校验用户有权访问该项目
- **AND** 后端 SHALL 以当前 active 源码版本创建归档任务
- **AND** 响应 SHALL 返回 `taskId`、归档 ID、初始归档状态和当前源码版本 ID

#### Scenario: 查询归档列表
- **WHEN** 已登录用户调用 `GET /api/projects/{projectId}/archives`
- **THEN** 后端 SHALL 返回分页归档列表
- **AND** 列表接口 SHALL 支持按 `sourceRevisionId`、`status` 和时间范围过滤
- **AND** 列表接口 SHALL 支持按 `createdAt`、`status` 和 `name` 排序

### Requirement: 归档详情 API 必须按归档项展示报告
归档详情 API SHALL 返回归档创建时固化的归档项，使前端能够分别查看不同报告和摘要。

#### Scenario: 查询归档详情
- **WHEN** 已登录用户调用 `GET /api/archives/{archiveId}`
- **THEN** 后端 SHALL 按归档所属项目校验访问权限
- **AND** 响应 SHALL 返回归档元数据、源码版本摘要、状态、summary 和归档项列表
- **AND** 每个归档项 SHALL 包含 `nodeType`、`candidateType`、标题、资源引用、格式列表和安全摘要
- **AND** 响应 MUST NOT 返回对象存储 bucket、object key、服务器真实路径或明文凭据

#### Scenario: 查看归档项内容
- **WHEN** 已登录用户调用 `GET /api/archives/{archiveId}/items/{itemId}?format=HTML|JSON|LOG`
- **THEN** 后端 SHALL 校验归档项属于该归档
- **AND** 后端 SHALL 返回该归档项在创建时固化的对应格式内容
- **AND** 不支持或不存在的格式 SHALL 返回明确业务错误

#### Scenario: 下载归档包
- **WHEN** 已登录用户调用 `GET /api/archives/{archiveId}/download`
- **THEN** 后端 SHALL 按归档所属项目校验访问权限
- **AND** 后端 SHALL 返回该归档记录生成的压缩包
- **AND** 响应 MUST NOT 暴露对象存储 bucket、object key 或服务器真实路径

### Requirement: 归档 API 必须以源码版本隔离当前状态
归档 API SHALL 保证“归档当前状态”只归档当前 active 源码版本下的候选内容。

#### Scenario: 新源码版本隔离归档内容
- **WHEN** 项目已经激活新的源码版本
- **THEN** 创建归档请求 SHALL 使用新的 active `sourceRevisionId`
- **AND** 后端 MUST NOT 将上一源码版本的候选内容加入新归档

#### Scenario: 越权访问归档
- **WHEN** 用户访问无权限项目的归档 overview、列表、详情、归档项内容或下载接口
- **THEN** 后端 SHALL 返回访问拒绝错误
- **AND** 响应 MUST NOT 泄漏归档、项目、源码版本或候选是否存在

### Requirement: API 必须提供依赖分析接口并标记 SBOM deprecated
后端 API SHALL 新增构建事实驱动的依赖分析接口，并 SHALL 将旧 SBOM 分析接口标记为 deprecated 兼容能力。

#### Scenario: 创建依赖分析
- **WHEN** 前端调用 `POST /api/projects/{projectId}/dependency-analyses`
- **THEN** 后端 SHALL 基于当前源码版本和成功 source/x86 构建运行创建 `DEPENDENCY_ANALYSIS` 任务
- **AND** 响应 SHALL 返回依赖分析运行 ID、任务 ID、源码版本 ID、source 构建运行 ID 和初始状态

#### Scenario: 查询依赖分析列表
- **WHEN** 前端调用 `GET /api/projects/{projectId}/dependency-analyses`
- **THEN** 后端 SHALL 返回分页列表
- **AND** 列表 SHALL 支持按 `sourceRevisionId`、状态、风险等级和创建时间过滤
- **AND** 列表 SHALL 支持稳定排序白名单

#### Scenario: 查询依赖分析结果
- **WHEN** 前端调用依赖分析结果列表或详情接口
- **THEN** 响应 SHALL 返回依赖类型、依赖名称、目标匹配状态、风险等级、位置标签、建议和 evidence 摘要
- **AND** 响应 MUST NOT 返回完整构建日志、完整脚本、明文凭据或服务器真实路径

#### Scenario: 构建 API 接收 buildScope
- **WHEN** 前端调用 `POST /api/projects/{projectId}/builds`
- **THEN** 请求体 MAY 包含 `buildScope`
- **AND** 未提交时默认 `BOTH`
- **AND** `SOURCE_ONLY` SHALL 只构建 source/x86 环境

#### Scenario: SBOM API 标记 deprecated
- **WHEN** 文档或响应描述旧 SBOM API
- **THEN** 后端 API 设计 SHALL 标记其为 deprecated
- **AND** 新流程文档 SHALL 引导前端使用依赖分析 API

### Requirement: API 必须提供评估报告页 readiness
后端 API SHALL 提供评估报告页 readiness 接口，用于返回当前源码版本下报告生成条件、输入统计、警告、最新报告和历史比较准备信息；没有 confirmed 输入 SHALL 表示当前报告输入为空，而不是报告生成阻塞条件。

#### Scenario: 查询评估报告 readiness
- **WHEN** 已登录用户调用 `GET /api/projects/{projectId}/reports/evaluation/readiness`
- **THEN** 后端 MUST 校验当前用户对项目有访问权限
- **AND** 请求 MAY 包含 `sourceRevisionId`
- **AND** 未提交 `sourceRevisionId` 时后端 SHALL 使用项目当前 active source revision
- **AND** 响应 SHALL 包含 `canGenerate`、`blockers`、`warnings`、`sourceRevision`、`inputSummary`、`latestReport`、`latestComparableReport` 和 `latestComparison`
- **AND** 响应 MUST NOT 暴露服务器真实路径、对象存储内部 key、明文凭据或完整任务输入快照

#### Scenario: readiness 校验源码版本归属
- **WHEN** 用户提交的 `sourceRevisionId` 不属于 URL 中的项目
- **THEN** 后端 SHALL 返回统一业务错误
- **AND** 后端 MUST NOT 返回该源码版本或所属项目的详情

#### Scenario: 缺少源码版本时返回阻塞原因
- **WHEN** 当前源码版本不存在且请求未指定有效源码版本
- **THEN** 响应 SHALL 返回 `canGenerate=false`
- **AND** `blockers` SHALL 使用稳定错误码表达源码版本缺失
- **AND** 接口 MUST NOT 创建异步报告任务

#### Scenario: 没有 confirmed 输入时允许生成报告
- **WHEN** 当前源码版本存在但 confirmed 输入数量为 0
- **THEN** 响应 SHALL 返回 `canGenerate=true`
- **AND** `inputSummary` SHALL 返回 confirmed 输入数量为 0
- **AND** `blockers` MUST NOT 包含 confirmed 输入缺失错误
- **AND** 后续报告创建接口 SHALL 允许创建生成全零报告的异步任务

### Requirement: API 必须提供评估报告输入预览
后端 API SHALL 提供当前源码版本的报告输入预览列表，供前端在评估报告页展示将进入报告或可人工处理的输入。

#### Scenario: 查询评估报告输入预览
- **WHEN** 已登录用户调用 `GET /api/projects/{projectId}/reports/evaluation/input-preview`
- **THEN** 后端 MUST 校验当前用户对项目有访问权限
- **AND** 请求 MAY 包含 `sourceRevisionId`
- **AND** 未提交 `sourceRevisionId` 时后端 SHALL 使用项目当前 active source revision
- **AND** 响应 SHALL 返回分页数据
- **AND** 每条记录 SHALL 包含 `id`、`sourceType`、`sourceRefId`、`issueTitle`、`detectedLevel`、`locationLabel`、`status`、`recommendationSummary`、`note`、`updatedAt` 和受控来源定位摘要

#### Scenario: 输入预览支持过滤排序
- **WHEN** 用户调用输入预览接口并提交 `sourceType`、`status`、`riskLevel`、`keyword`、`page`、`pageSize`、`sortBy` 或 `sortDirection`
- **THEN** 后端 SHALL 使用分页响应格式
- **AND** 过滤条件 SHALL 使用 AND 语义组合
- **AND** `sortBy` 白名单 SHALL 至少包含 `updatedAt`、`sourceType`、`status`、`detectedLevel`、`issueTitle` 和 `locationLabel`

#### Scenario: 输入预览拒绝非法查询参数
- **WHEN** 用户提交非法 `status`、非法 `sourceType`、非法 `riskLevel` 或非法排序字段
- **THEN** API SHALL 返回统一参数校验错误
- **AND** 后端 MUST NOT 将非法参数拼接到 SQL

### Requirement: API 报告详情必须返回当前输入摘要
后端 API SHALL 在评估报告详情响应中返回当前源码版本输入状态摘要，并 SHALL 保持历史报告快照不可变。

#### Scenario: 报告详情包含当前输入状态
- **WHEN** 已登录用户调用 `GET /api/reports/{reportId}`
- **THEN** 后端 SHALL 返回历史报告 summary、artifact 摘要和源码版本摘要
- **AND** 响应 SHALL 返回 `currentInputSummary`
- **AND** `currentInputSummary` SHALL 包含总数、按状态统计、按来源统计、按风险统计和 LOW candidate 数量

#### Scenario: 报告详情权限校验
- **WHEN** 普通用户通过 reportId 查询其他用户项目的报告详情
- **THEN** 后端 SHALL 返回访问拒绝或项目不存在错误
- **AND** 后端 MUST NOT 泄露报告是否存在于其他项目

### Requirement: 构建和 KB API 必须返回 compile database 使用摘要
后端 API SHALL 在构建详情和 KB 扫描详情中返回安全的 compile database 使用摘要，使前端能展示扫描是否使用了真实构建上下文。

#### Scenario: 构建详情返回 compile database 摘要
- **WHEN** 用户调用 `GET /api/builds/{buildId}`
- **THEN** 响应 SHALL 包含 compile database 可用状态、stored artifact ID、sha256、size bytes、command count 和来源摘要
- **AND** 响应 MUST NOT 返回完整 compile database、远端工作目录、服务器真实路径、bucket 或 object key

#### Scenario: KB 扫描详情返回 compile database 使用情况
- **WHEN** 用户调用 `GET /api/kb-scans/{scanId}`
- **THEN** 响应 SHALL 包含 `compileCommands` 摘要
- **AND** 摘要 SHALL 表达 `USED`、`NOT_AVAILABLE` 或 `FALLBACK`
- **AND** 当状态为 `USED` 时，摘要 SHALL 包含来源 build run ID、stored artifact ID、sha256 和 command count
- **AND** 当状态不是 `USED` 时，摘要 SHALL 包含安全原因码

#### Scenario: KB 创建接口不要求前端提交 compile database
- **WHEN** 用户调用 `POST /api/projects/{projectId}/kb-scans`
- **THEN** 请求体 MUST NOT 要求提交 compile database 路径、文件或 artifact ID
- **AND** 后端 SHALL 在 worker 执行阶段自动解析可用构建上下文

#### Scenario: 无权限访问摘要
- **WHEN** 用户无权访问构建运行或 KB 扫描所属项目
- **THEN** 后端 SHALL 返回与资源不存在一致的安全错误
- **AND** 后端 MUST NOT 暴露 compile database artifact 是否存在

### Requirement: 模型 Profile API 必须拆分连通性测试和 Agent 兼容性测试
后端 API SHALL 为已认证配置所有者提供独立的模型连通性测试 API 和多 agent runtime 通用兼容性测试 API，避免长时间 agent probe 阻塞同步 HTTP 请求，并保持阶段绑定职责独立。

#### Scenario: 模型连通性测试
- **WHEN** 已认证配置所有者调用 `POST /api/me/model-profiles/{profileId}:test-connectivity`
- **THEN** API SHALL 使用已保存配置或请求中的临时覆盖配置执行模型 provider 连通性测试
- **AND** API SHALL 返回 `status`、`message`、`testedAt`、`durationMillis` 和脱敏后的 `details`
- **AND** 后端 SHALL 记录最近一次连通性测试摘要
- **AND** 响应 MUST NOT 返回 API key、授权头、完整 prompt 或服务器真实敏感路径

#### Scenario: 创建多 Agent 测试 Run
- **WHEN** 已认证配置所有者调用 `POST /api/me/model-profiles/{profileId}:test-agents` 并提交 `agentRuntimeIds`
- **THEN** API SHALL 校验 `agentRuntimeIds` 非空且去重后全部指向已启用 agent runtime
- **AND** API SHALL 创建 agent probe run 并立即返回 `runId`、`status`、`profileId`、`agentRuntimes`、`createdAt`
- **AND** 后端 SHALL 在后台执行所选 agent runtime 的通用兼容性测试
- **AND** 后端 MUST NOT 读取 `model_stage_assignments` 决定测试内容或结果
- **AND** 响应 MUST NOT 等待所有 agent runtime probe 完成

#### Scenario: 查询 Agent 测试 Run
- **WHEN** 已认证配置所有者调用 `GET /api/me/model-profile-agent-test-runs/{runId}`
- **THEN** API SHALL 返回 run 聚合状态、模型 profile 摘要、agent runtime 分项状态、通用 probe、脱敏消息、开始时间和结束时间
- **AND** 未完成的 runtime SHALL 返回 `PENDING` 或 `RUNNING`
- **AND** 失败 runtime SHALL 返回脱敏失败原因
- **AND** 响应 MUST NOT 返回业务阶段兼容性字段

#### Scenario: 查询 Profile Agent Probe 结果
- **WHEN** 已认证配置所有者调用 `GET /api/me/model-profiles/{profileId}/agent-probe-results`
- **THEN** API SHALL 返回该 profile 对各 agent runtime 的最近通用兼容性结果
- **AND** 响应 SHALL 支持按 agent 类型、runtime 启用状态、兼容性状态和最近测试时间过滤或排序
- **AND** 兼容性结果 SHALL 以模型 Profile ID 与 Agent Runtime ID 为组合维度

#### Scenario: 列表返回测试摘要
- **WHEN** 已认证配置所有者调用 `GET /api/me/model-profiles`
- **THEN** 列表项 SHALL 返回最近连通性状态、最近连通性测试时间和脱敏连通性消息
- **AND** 列表项 SHALL 返回通用 agent 兼容性摘要或可展开结果引用
- **AND** 如果该 profile 存在 `PENDING` 或 `RUNNING` 的 agent probe run，列表项 SHALL 返回 `activeAgentProbeRun`
- **AND** `activeAgentProbeRun` SHALL 包含 `runId`、`status`、所选 agent runtime 摘要和创建时间
- **AND** 列表项 SHALL NOT 返回 `activeConnectivityRun`，因为连通性测试为同步请求且不创建后台 run
- **AND** 响应 MUST NOT 返回 provider secret 或阶段兼容性字段

#### Scenario: 详情返回活跃 Agent Probe Run
- **WHEN** 已认证配置所有者调用模型 profile 详情 API
- **THEN** API SHALL 返回该 profile 最近连通性摘要、通用 agent 兼容性矩阵摘要和当前活跃 agent probe run
- **AND** 如果不存在未终态 run，`activeAgentProbeRun` SHALL 为空
- **AND** API SHALL NOT 返回活跃连通性 run
- **AND** 前端 SHALL 能依据 `activeAgentProbeRun.runId` 继续查询 run 结果

#### Scenario: 阶段绑定独立于兼容性测试
- **WHEN** 已认证配置所有者查询或保存 `model_stage_assignments`
- **THEN** API SHALL 按认证所有者与阶段保存本人模型 Profile 与受管 Agent Runtime 组合
- **AND** 阶段绑定 SHALL NOT 改变该组合的通用兼容性记录
- **AND** 兼容性测试 SHALL NOT 因组合绑定到不同阶段而产生不同结果结构

#### Scenario: 跨用户访问测试 API
- **WHEN** 任一用户（包括 admin）使用他人的 profile 或 run 调用模型连通性、agent probe run 或结果查询 API
- **THEN** API SHALL 返回与不存在资源一致的 404，不写入结果或调用上游
- **AND** 响应 MUST NOT 泄漏 profile、runtime、run 是否存在或内部执行细节

### Requirement: 任务日志内容 API 必须返回每类日志总行数
后端 API SHALL 在 `GET /api/tasks/{taskId}/log-content` 的每个日志类型响应对象中返回 `totalLines`，用于前端展示行号和计算日志分页状态。

#### Scenario: 响应包含 totalLines 字段
- **WHEN** 前端调用 `GET /api/tasks/{taskId}/log-content`
- **THEN** API SHALL 返回完成态日志内容响应
- **AND** 每个返回的 `logs.<type>` 对象 SHALL 包含 `totalLines`
- **AND** `totalLines` SHALL 为非负整数

#### Scenario: totalLines 与请求分页参数解耦
- **WHEN** 前端调用 `GET /api/tasks/{taskId}/log-content?types=merged&mergedOffset=100&lines=50`
- **THEN** `logs.merged.totalLines` SHALL 表示 merged 日志总逻辑行数
- **AND** `logs.merged.totalLines` MUST NOT 被截断为当前页 `returnedLines`
- **AND** `logs.merged.totalLines` MUST NOT 随 offset 增大而减少，除非日志内容被后台清理或重建

#### Scenario: 多日志类型响应分别返回总行数
- **WHEN** 前端调用 `GET /api/tasks/{taskId}/log-content?types=merged,stdout&mergedOffset=0&stdoutOffset=100`
- **THEN** API SHALL 分别返回 `logs.merged.totalLines` 和 `logs.stdout.totalLines`
- **AND** 两个字段 SHALL 分别对应各自日志类型，不得复用 merged 行数作为 stdout 行数

#### Scenario: 日志仍在运行中时返回快照行数
- **WHEN** 任务仍在运行且日志仍在追加
- **THEN** API SHALL 返回响应时刻可见的 `totalLines`
- **AND** API MUST NOT 为了等待最终行数而阻塞请求

### Requirement: 管理员 API 必须提供 worker 观测和并发控制
后端 API SHALL 提供超级管理员专用 worker 管理接口，用于查看自动接入 worker、查看 worker 当前任务和控制单 worker 最大并发。

#### Scenario: 查询 worker 列表
- **WHEN** 超级管理员调用 `GET /api/admin/workers`
- **THEN** API SHALL 返回分页 worker 列表
- **AND** 每个 worker SHALL 包含实例 ID、主机名、状态、并发限制、实际并发、运行任务数量、启动时间和最近心跳时间
- **AND** 响应 MUST NOT 包含 `prefetchCount`

#### Scenario: 查询 worker 详情
- **WHEN** 超级管理员调用 `GET /api/admin/workers/{workerInstanceId}`
- **THEN** API SHALL 返回 worker 基础信息和当前运行任务列表
- **AND** 运行任务 SHALL 包含任务 ID、项目 ID、项目名称、任务类型、状态、进度、消息、开始时间和 runtime handle ID

#### Scenario: 调整 worker 并发
- **WHEN** 超级管理员调用 `PUT /api/admin/workers/{workerInstanceId}/concurrency`
- **AND** 请求体包含合法 `concurrencyLimit`
- **THEN** API SHALL 更新该 worker 的期望并发限制
- **AND** API SHALL 返回更新后的 worker 并发摘要

#### Scenario: 暂停 worker 接新任务
- **WHEN** 超级管理员将 `concurrencyLimit` 设置为 `0`
- **THEN** API SHALL 接受该请求
- **AND** 后端 SHALL 将该 worker 标记为暂停接收新任务
- **AND** 后端 MUST NOT 取消该 worker 已经运行中的任务

#### Scenario: 非法并发限制
- **WHEN** 请求提交负数、超过系统上限或非数字的 `concurrencyLimit`
- **THEN** API SHALL 返回统一参数或业务错误
- **AND** 后端 MUST NOT 修改原有并发限制

#### Scenario: 非超级管理员访问 worker API
- **WHEN** 非超级管理员访问 worker 管理 API
- **THEN** API SHALL 返回无权限错误
- **AND** 响应 MUST NOT 泄露 worker 是否存在、主机名、任务信息或部署信息

#### Scenario: Worker 列表支持分页排序过滤
- **WHEN** 超级管理员查询 worker 列表
- **THEN** API SHALL 支持分页参数
- **AND** API SHALL 支持按状态、实例 ID、主机名和是否有运行任务过滤
- **AND** API SHALL 支持按最近心跳时间、启动时间和运行任务数量排序

### Requirement: API 必须提供项目级阶段 readiness
后端 API SHALL 提供项目级阶段 readiness 接口，用于返回当前 Context 下指定 workflow 阶段的前置条件事实。

#### Scenario: 查询阶段 readiness
- **WHEN** 已登录用户调用 `GET /api/projects/{projectId}/readiness?stageId={stageId}`
- **THEN** 后端 SHALL 校验用户对项目的访问权限
- **AND** 响应 SHALL 包含 `stageId`、`context` 和 `prerequisites`
- **AND** `context` SHALL 包含当前 `sourceRevisionId` 和当前 `runtimeEnvironmentVersion`
- **AND** `prerequisites` 中每项 SHALL 包含 `key`、`status`、`referenceId` 和 `reasonCode`

#### Scenario: readiness 不返回 UI 动作
- **WHEN** 后端返回阶段 readiness
- **THEN** 响应 MUST NOT 包含 `ready`、`actions`、`buttons`、`buttonLabel` 或 `recommendedAction`
- **AND** 前端 SHALL 基于稳定的 `key` 和 `status` 自行组合 UI 行为

#### Scenario: readiness 状态枚举
- **WHEN** 后端计算任一 prerequisite 状态
- **THEN** `status` MUST 使用 `SATISFIED`、`MISSING`、`RUNNING`、`FAILED`、`STALE` 或 `UNAVAILABLE`
- **AND** `reasonCode` MUST 使用稳定枚举值
- **AND** `reasonCode` MUST NOT 依赖中文展示文案

#### Scenario: readiness 越权访问失败
- **WHEN** 普通用户访问无权限项目的 readiness
- **THEN** 后端 SHALL 返回 `FORBIDDEN` 或 `PROJECT_NOT_FOUND`
- **AND** 后端 MUST NOT 暴露该项目的源码、环境、任务或执行记录信息

### Requirement: 领域 latest 接口必须使用统一响应外壳
需要 latest 的领域接口 SHALL 保留各自领域路径和结果结构，并 SHALL 使用统一 latest 外壳与活动执行优先的记录选择规则。

#### Scenario: 返回当前领域 latest
- **WHEN** 已登录用户调用某个领域 latest 接口
- **THEN** 后端 SHALL 校验用户对项目的访问权限
- **AND** 响应业务数据 SHALL 包含 `context`、`freshness`、`execution` 和 `result`
- **AND** `context` SHALL 包含当前 `sourceRevisionId` 和当前 `runtimeEnvironmentVersion`
- **AND** `result` SHALL 保持领域自己的结构

#### Scenario: 活动执行优先于终态结果
- **WHEN** 当前 Context 下存在状态为 `PENDING`、`QUEUED` 或 `RUNNING` 的对应领域执行
- **THEN** latest 接口 SHALL 返回最近创建的活动执行
- **AND** `execution.status` SHALL 保留任务的精确状态
- **AND** 后端 MUST NOT 返回更早的成功、失败、取消或超时执行作为 latest

#### Scenario: 无活动执行时返回最新终态
- **WHEN** 当前 Context 下不存在活动执行但存在终态执行
- **THEN** latest 接口 SHALL 返回最近创建的终态执行
- **AND** 后端 MUST NOT 跳过失败、取消或超时记录并回退到更早成功记录

#### Scenario: 无执行记录返回空 latest
- **WHEN** 当前 Context 下没有对应领域执行记录
- **THEN** latest 接口 SHALL 成功返回
- **AND** `freshness` SHALL 为 `EMPTY`
- **AND** `execution` SHALL 为 `null`
- **AND** `result` SHALL 为 `null`
- **AND** 后端 MUST NOT 使用 404 表达无 latest

#### Scenario: latest 标识旧 Context
- **WHEN** 领域存在历史记录但没有当前 Context 记录
- **THEN** latest 接口 SHALL 返回 `freshness=STALE` 或 `freshness=EMPTY`
- **AND** 后端 MUST NOT 将旧源码版本或旧运行环境版本的结果标记为 `CURRENT`

### Requirement: 执行类写接口必须重新校验 readiness 事实
所有依赖前置条件的执行类写接口 SHALL 在创建任务前重新读取当前 Context 并校验所需 prerequisite。

#### Scenario: 写接口拒绝已过期前置条件
- **WHEN** 调用方基于旧 readiness 结果提交执行类写请求
- **AND** 后端重新读取后发现前置条件已变为 `STALE`、`FAILED`、`MISSING` 或 `UNAVAILABLE`
- **THEN** 后端 SHALL 拒绝创建任务
- **AND** 错误码 SHALL 为 `PREREQUISITE_NOT_SATISFIED`
- **AND** 错误详情 SHALL 包含 `stageId`、`prerequisite`、`status` 和 `reasonCode`

#### Scenario: 写接口不信任前端判断
- **WHEN** 前端曾经成功查询 readiness
- **THEN** 后端执行写接口时 MUST NOT 复用前端传回的 readiness 判断
- **AND** 后端 SHALL 以数据库当前状态为准

### Requirement: KB finding API 必须返回 schema v2 优先结构
KB finding 列表和详情 API SHALL 以 schema v2 的领域结构作为主要返回合同，不再以 v1 扁平字段作为默认合同。

#### Scenario: 分页查询 finding 列表
- **WHEN** 前端分页查询某个项目的 KB finding 列表
- **THEN** 后端 SHALL 返回可分页、可排序、可过滤的记录
- **AND** 每条记录 SHALL 包含用于列表展示的标题、风险等级、类型、来源、位置摘要和报告纳入状态
- **AND** 每条记录 SHOULD 包含 v2 `fact` 和 `conclusion` 的摘要字段
- **AND** 后端 MUST NOT 要求前端依赖 v1 的 `reason`、`suggestion`、`trace` 扁平结构

#### Scenario: 查询 finding 详情
- **WHEN** 前端查询特定 KB finding 详情
- **THEN** 后端 SHALL 返回完整 v2 finding payload
- **AND** 返回内容 SHALL 包含 `fact`、`conclusion`、`occurrences` 和 `evidenceChain`
- **AND** 返回内容 SHALL 包含当前人工确认状态、报告纳入状态和 AI 扩展建议

#### Scenario: 查询 finding 代码上下文
- **WHEN** 前端查询特定 finding 的代码上下文
- **THEN** 后端 SHALL 使用 v2 primary location 或导入投影位置定位源码
- **AND** 后端 SHALL 返回目标代码行上下各 20 行以内的上下文
- **AND** 返回内容 SHALL 包含每行行号、代码文本和是否高亮
- **AND** 当源码文件不存在或路径越界时 SHALL 返回明确错误

### Requirement: KB artifact 下载 API 必须支持 schema v2 结果
KB 扫描 artifact 下载 API SHALL 能返回 schema v2 的 JSON 和 HTML 报告。

#### Scenario: 下载 schema v2 JSON
- **WHEN** 用户请求下载 KB 扫描 JSON artifact
- **THEN** 后端 SHALL 返回 scanner 产生的 schema v2 `scan-result.json`
- **AND** Content-Type SHOULD 与 JSON 内容匹配

#### Scenario: 下载 schema v2 HTML
- **WHEN** 用户请求下载 KB 扫描 HTML artifact
- **THEN** 后端 SHALL 返回 scanner 产生的 HTML 报告
- **AND** 下载内容 SHALL 与当前任务 artifact 绑定

### Requirement: 后端 API 必须提供任务日志通知 SSE
后端 API SHALL 提供 `GET /api/tasks/{taskId}/log-notifications`，用于通知前端任务日志变化和任务结束。

#### Scenario: 建立带消费游标的通知连接
- **WHEN** 已登录用户调用 `GET /api/tasks/{taskId}/log-notifications?afterEventId=123`
- **THEN** 后端 SHALL 返回 `text/event-stream`
- **AND** `afterEventId` SHALL 表示前端已通过增量 API 成功消费的最后日志事件 ID
- **AND** 后端 SHALL 在当前最新事件 ID 大于该值时发送 `task.log.updated`

#### Scenario: 通知 SSE 的事件契约
- **WHEN** 后端发送 `task.log.updated`
- **THEN** JSON data SHALL 包含 `taskId`、`latestEventId` 和 `status`
- **AND** data MUST NOT 包含日志正文
- **WHEN** 后端发送 `task.log.completed`
- **THEN** JSON data SHALL 额外包含安全的终态消息

#### Scenario: 非法 SSE 游标
- **WHEN** 用户传入负数或非数字 `afterEventId`
- **THEN** 后端 SHALL 返回 HTTP 400 和稳定错误码
- **AND** 后端 MUST NOT 创建 SSE emitter

#### Scenario: 无任务访问权限
- **WHEN** 用户无权访问 taskId 所属项目或任务不存在
- **THEN** 后端 SHALL 使用现有任务访问守卫拒绝请求
- **AND** 后端 MUST NOT 泄露任务状态、日志游标或日志内容

### Requirement: 后端 API 必须提供任务日志增量读取接口
后端 API SHALL 提供 `GET /api/tasks/{taskId}/log-increments`，以单一任务日志事件游标聚合读取 merged、stdout 和 stderr 新增内容。

#### Scenario: 正常增量响应
- **WHEN** 用户调用 `GET /api/tasks/{taskId}/log-increments?afterEventId=123&limit=500`
- **THEN** 响应 SHALL 包含 `taskId`、`fromEventId`、`latestEventId`、`hasMore` 和 `logs`
- **AND** `logs` SHALL 包含 merged、stdout、stderr 三类聚合内容及各自 `returnedEvents`
- **AND** 响应 MUST NOT 包含日志文件路径

#### Scenario: 默认增量参数
- **WHEN** 用户未提交 `afterEventId` 或 `limit`
- **THEN** 后端 SHALL 使用 `afterEventId=0` 和受控的默认事件条数
- **AND** 单次响应仍 SHALL 受最大事件数与最大 UTF-8 内容字节数限制

#### Scenario: 无效增量参数
- **WHEN** 用户提交负数游标、非正数 limit 或超过服务端上限的 limit
- **THEN** 后端 SHALL 返回 HTTP 400 和稳定错误码

#### Scenario: 游标大于当前最新事件
- **WHEN** `afterEventId` 大于当前任务最新日志事件 ID
- **THEN** 后端 SHALL 返回空增量和 `hasMore=false`
- **AND** 后端 MUST NOT 回退到历史日志或其他任务事件

### Requirement: 新日志 API 必须与现有日志内容 API 分工明确
API 文档 SHALL 将通知、运行中增量读取和完成态历史查看定义为三个不同职责。

#### Scenario: 前端查看运行中日志
- **WHEN** 前端打开运行中任务的日志页面
- **THEN** 前端 SHALL 使用 `/log-notifications` 获取变化通知
- **AND** 前端 SHALL 使用 `/log-increments` 获取新增正文

#### Scenario: 前端向上翻阅完成态历史日志
- **WHEN** 前端查看已经完成任务的历史日志并向上翻页
- **THEN** 前端 SHALL 继续使用 `/log-content`
- **AND** `/log-content` 的请求和响应字段 SHALL 保持兼容

### Requirement: API 必须提供两个专项分析领域入口
API SHALL 分别提供浮点精度与内存序分析的创建、latest、运行列表、运行详情、Finding 列表、Finding 详情和 JSON/HTML 下载入口，并 SHALL 对每次访问校验项目权限。

#### Scenario: 项目成员创建分析
- **WHEN** 有权限用户对满足 readiness 的项目创建任一分析
- **THEN** API SHALL 返回对应运行 ID、任务 ID、状态和 stageId

#### Scenario: 越权访问
- **WHEN** 普通用户创建或读取其他用户不可访问项目的分析、Finding 或制品
- **THEN** API SHALL 返回受控拒绝响应
- **AND** MUST NOT 泄漏项目、运行或 Finding 是否存在

### Requirement: 运行与 Finding 列表必须支持分页过滤排序
两个领域的列表 API SHALL 使用统一分页响应，并 SHALL 对允许的过滤和排序字段使用白名单。

#### Scenario: 查询运行列表
- **WHEN** 用户按 status、sourceRevisionId、toolVersion、创建时间范围过滤并按 createdAt、startedAt、finishedAt 或 status 排序
- **THEN** API SHALL 返回稳定分页结果和总数

#### Scenario: 查询 Finding 列表
- **WHEN** 用户按 severity、confidence、status、code、category、filePath、关键字或确认状态过滤并按 severity、path、line、code 或 createdAt 排序
- **THEN** API SHALL 返回稳定分页结果
- **AND** 每条 SHALL 返回 migrationInputRefId、migrationInputStatus、confirmedForNextReport 和 includedInLatestReport

#### Scenario: 非法查询字段
- **WHEN** 用户传入未列入白名单的排序、过滤或分页值
- **THEN** API SHALL 返回统一参数错误
- **AND** MUST NOT 把原始字段拼接到 SQL

### Requirement: API 必须提供专项分析 readiness 和 latest
项目级 readiness SHALL 支持 `floating-point-analysis` 与 `memory-order-analysis`，领域 latest SHALL 使用统一响应外壳并返回当前运行、当前源码、分析模式、BC 覆盖和失效或降级原因。BC 可用性 SHALL 表达证据能力，不得在源码、成功 x86 构建、真实 compile database 和工具均可用时单独阻断任务创建。

#### Scenario: readiness 返回完整前置事实
- **WHEN** 用户查询任一专项分析 stage readiness
- **THEN** 响应 SHALL 列出源码、x86 构建、compile database、工具可用性以及该领域额外 profile 条件
- **AND** 响应 SHALL 返回建议执行模式 `AST_ONLY`、`AST_BC_MIXED` 或 `AST_BC_COMPLETE`
- **AND** 响应 SHALL 返回 BC 状态、计划、可用和失败翻译单元计数及稳定降级原因码

#### Scenario: BC 不可用但 AST 前置满足
- **WHEN** 当前源码、成功 x86 构建、真实 compile database 和分析工具可用，但 BC 缺失、失败、损坏或没有 produced record
- **THEN** readiness 的硬前置条件 SHALL 保持满足
- **AND** 建议执行模式 SHALL 为 `AST_ONLY`
- **AND** 响应 MUST NOT 将 BC 降级描述为整个分析不可用

#### Scenario: 部分 BC 可用
- **WHEN** BC artifact set 仅有部分翻译单元可用
- **THEN** readiness SHALL 返回 `AST_BC_MIXED`
- **AND** SHALL 返回 AST-only 与 AST+BC 的翻译单元分母

#### Scenario: latest 和详情公开实际模式
- **WHEN** 用户查询专项分析 latest、运行详情或列表
- **THEN** 运行响应 SHALL 返回实际 `analysisMode`、BC 状态、BC 覆盖计数和降级原因
- **AND** 无 artifact set 的 AST-only 运行 SHALL 允许 `artifactSetId` 为空

#### Scenario: 执行时重新校验
- **WHEN** readiness 查询后源码、构建、compile database、BC 或工具状态发生变化
- **THEN** 创建 API SHALL 重新校验硬前置事实并重新计算分析模式
- **AND** 仅硬前置不满足时 SHALL 返回 `PREREQUISITE_NOT_SATISFIED`
- **AND** BC 从可用变为不可用时 SHALL 安全降级为 AST-only 并记录原因

### Requirement: Finding 详情必须保留领域证据
浮点 Finding 详情 SHALL 返回 occurrence、evidenceChain、validationPlan、profile/build-context 信息；内存序详情 SHALL 返回 occurrence、evidenceChain、breaks、assumptions、limitations 和 source context。

#### Scenario: 查询完整证据
- **WHEN** 用户读取合法 Finding 详情
- **THEN** API SHALL 返回版本化领域 payload 与公共摘要
- **AND** 路径 SHALL 为仓库相对路径，不得暴露服务器绝对路径

### Requirement: 专项分析 API 必须准确表达旧标准处理结果
专项分析运行和结果 API SHALL 保留工具识别到的语言、基础标准、方言和标准来源，并 SHALL 对不支持的单翻译单元返回稳定诊断。

#### Scenario: 返回旧标准分析摘要
- **WHEN** 项目包含 C89/C99 或 C++98/C++03 翻译单元
- **THEN** 运行详情 SHALL 能返回实际识别的标准及 GNU/ISO 方言摘要
- **AND** MUST NOT 将其显示为 C17 或 C++17

#### Scenario: 单翻译单元不受支持
- **WHEN** 某个编译上下文无法由锁定 Clang 或规则可靠处理
- **THEN** API SHALL 在结果中返回该翻译单元的 unknown 或 unsupported 诊断及双语说明
- **AND** 其他翻译单元结果 SHALL 保持可查询

### Requirement: KB 工作流 API 必须区分执行事实和当前有效性
项目 overview、KB latest、readiness 和 KB 执行写接口 SHALL 复用同一当前构建与扫描上下文规则，不能仅凭源码存在或历史成功扫描认定当前阶段完成。

#### Scenario: 首次上传源码且尚未构建
- **WHEN** 项目已配置环境并上传源码，但没有 x86 构建和 KB 扫描记录
- **THEN** overview 的 KB SHALL 为 `PENDING` 且原因为 `KB_SCAN_MISSING`
- **AND** KB latest SHALL 为 `EMPTY`
- **AND** KB readiness 的 `X86_BUILD` SHALL 为 `MISSING`
- **AND** 创建 KB 扫描 SHALL 返回 `409 PREREQUISITE_NOT_SATISFIED`，错误详情包含 `stageId=kb` 及具有 key、status、reasonCode 的 prerequisite

#### Scenario: 历史成功不能代表当前完成
- **WHEN** 当前只存在旧源码、旧环境版本、旧构建或上下文不完整的成功扫描
- **THEN** overview 的 KB SHALL 为 `PENDING` 且原因为 `KB_SCAN_STALE`
- **AND** KB latest SHALL 返回 `STALE` 并保留历史执行状态
- **AND** readiness SHALL 按当前构建事实独立判断能否发起新扫描

#### Scenario: 当前扫描状态映射
- **WHEN** 存在完整匹配当前成功构建上下文的扫描记录
- **THEN** overview SHALL 将活动扫描映射为 `RUNNING`，成功映射为 `DONE`，失败、取消或超时映射为 `BLOCKED`
- **AND** latest SHALL 返回同一选择规则下的扫描及 `CURRENT`
- **AND** 当前失败扫描 MUST NOT 被更早成功结果覆盖

#### Scenario: 下一步推荐遵守构建顺序
- **WHEN** 环境和源码已就绪，x86 尚未成功，KB 无当前有效执行
- **THEN** overview SHALL 在 KB 之前推荐需要处理的 x86 构建
- **AND** 后端 MUST NOT 将 KB 描述为此时可立即开始的下一步

#### Scenario: 单次响应保持上下文一致
- **WHEN** 生成 overview、KB latest 或 KB readiness 时发生并发上下文变更
- **THEN** 后端 SHALL 通过一致读取或版本重验保证每个响应内部使用同一个上下文判定
- **AND** 不同请求之间真实发生的状态变化 SHALL 通过各自 context 和原因字段表达

#### Scenario: 安全且兼容的结果说明
- **WHEN** API 返回 KB 执行上下文、失效或降级原因
- **THEN** 后端 SHALL 保留既有路径、任务枚举和 latest 外壳，以领域字段表达构建 ID、版本和安全原因码
- **AND** 新增直接展示的说明 SHALL 同时提供中文和英文，例如 `message` 与 `message_en`
- **AND** 响应 MUST NOT 暴露服务器路径、凭据、完整任务输入或对象存储 key
- **AND** 无权限用户 SHALL 在读取这些事实前被拒绝

### Requirement: 优化闭环读取必须保持状态与有效结果一致
项目 overview、优化 overview、latest 及当前轮次摘要 SHALL 使用同一有效验证解析，轮次存在或 latest CURRENT MUST NOT 单独构成优化完成条件。

#### Scenario: 源码就绪但未验证
- **WHEN** 项目刚激活源码且尚无当前双端构建和测试
- **THEN** 项目 overview 的 OPTIMIZE SHALL 为 PENDING，优化 latest SHALL 可返回 CURRENT 轮次及未满足验证事实
- **AND** 对应当前摘要 MUST NOT 含可使现有前端判定本轮验证通过的成功组合

#### Scenario: 同一快照读取
- **WHEN** 读取响应期间并发切换源码、环境配置或写入新的验证任务
- **THEN** 单次响应的 Context、结果引用、新鲜度及状态 SHALL 来自同一一致快照
- **AND** 相同数据库快照下各当前读取入口 SHALL 产生相同验证结论
- **AND** 不同请求间真实数据变更导致的差异 MUST NOT 被误判为同次响应允许混用上下文

#### Scenario: optimize readiness 可查看未完成轮次
- **WHEN** 当前 active 源码存在但验证链未完成
- **THEN** optimize readiness 的 ACTIVE_SOURCE SHALL 保持 SATISFIED，允许前端查看缺失或失败事实
- **AND** 后端 MUST NOT 将“可以查看”表达为 workflow DONE，也 MUST NOT 为查看强制要求比较通过
- **AND** readiness SHALL 保留事实项协议，不新增 UI 按钮或动作字段

### Requirement: 优化修复必须兼容现有前端消费字段
后端 SHALL 保留现有 API 路径、latest 外壳、轮次生命周期枚举及 `summary.build/test`、`summary.testComparison` 的字段类型，通过当前有效摘要修正现有前端判定；本 change MUST NOT 修改前端代码或前端 OpenSpec。

#### Scenario: 当前摘要控制下一步
- **WHEN** 现有前端依次判断 build.latestStatus、test.latestStatus 是否为 SUCCEEDED，以及比较 overallStatus 或 status 是否为 EQUIVALENT
- **THEN** 该成功组合 SHALL 当且仅当后端 OPTIMIZE 为 DONE 时出现
- **AND** 后端 SHALL 提供与同一验证事实一致的 nextAction，但 MUST NOT 依赖旧前端消费新增字段才能完成修复

#### Scenario: 双端汇总必须可追溯
- **WHEN** 后端返回当前双端阶段摘要
- **THEN** latestStatus SHALL 表达双端有效阶段状态，单侧成功且另一侧缺失时 MUST NOT 为 SUCCEEDED
- **AND** latestRunId 及时间 SHALL 引用实际代表记录；空状态的代表引用 SHALL 为空
- **AND** 增量验证事实 SHALL 给出决定状态的安全引用及失效原因，历史统计与当前统计 SHALL 明确区分

#### Scenario: 历史页面兼容
- **WHEN** 现有前端按只读记录方式展示历史轮次
- **THEN** 后端 SHALL 保留历史摘要字段可读，且 STALE latest MUST NOT 进入当前结果
- **AND** 对接说明 SHALL 明确既有历史通用标题不代表当前验证通过，新增原因细节展示不属于本次前端修改

#### Scenario: 新增展示字段
- **WHEN** 后端增加 validation、历史摘要或 nextAction 的业务说明
- **THEN** SHALL 同时提供中文和英文字段及稳定原因码
- **AND** MUST NOT 将日志、服务器路径或异常堆栈作为前端业务说明

#### Scenario: 权限与空数据
- **WHEN** 无权限用户请求优化读取，或有权限用户查询尚无子执行的轮次
- **THEN** 无权限请求 SHALL 被拒绝且不泄露轮次存在性
- **AND** 合法空数据请求 SHALL 保持现有成功外壳与空摘要约定，MUST NOT 发生空指针或伪造成功结果

### Requirement: 优化一致性修改必须完成完整 Docker 运行验收
实施交付 SHALL 从仓库根目录完成全部默认服务的 Compose 构建，并以一次 `docker compose up -d --wait` 启动全部默认服务进行运行验收；所有验证 SHALL 使用独立资源并保留证据。

#### Scenario: 隔离完整运行验收
- **WHEN** 后端单元测试和覆盖率门槛通过
- **THEN** SHALL 使用专用 Compose project、测试 env、网络、数据卷及端口顺序构建并设置适当 CPU、内存和并发限制
- **AND** SHALL 检查所有默认服务健康、日志以及首次源码上传但未构建时的 overview/latest/readiness 和历史查询
- **AND** MUST NOT 连接已有部署数据库或真实 SSH，也 MUST NOT 用旧镜像、旧报告替代当前源码验证

#### Scenario: 完整证据与阶段交接
- **WHEN** 汇总验收结果
- **THEN** SHALL 记录命令、退出码、测试数量、覆盖率统计范围和报告位置、服务状态及实际执行的业务场景
- **AND** 单元测试、隔离数据库验证与 Compose 业务验证 SHALL 分别说明，MUST NOT 宣称未执行的真实端到端验证
- **AND** 任一必需任务未完成时 MUST NOT 提示已具备归档条件

### Requirement: 个人模型配置 API 必须按认证所有者授权
后端 API SHALL 为已认证配置所有者提供模型 profile 管理、安全 agent runtime 目录，以及 `TEST_LOG_COMPARE`、`KB_AI_EXTENSION` 两个阶段的个人模型/agent 绑定接口。旧 `codex-provider` 单例配置 API SHALL NOT 继续作为管理入口保留，受控业务阶段的模型和 agent 选择 SHALL 以个人模型 profile、平台 agent runtime 和个人阶段绑定为事实来源。

#### Scenario: 查询模型 profile 列表
- **WHEN** 已认证配置所有者调用 `GET /api/me/model-profiles`
- **THEN** API SHALL 返回分页模型 profile 列表
- **AND** 响应 SHALL 包含 `id`、`name`、`enabled`、`modelProviderType`、`baseUrl`、`model`、`wireApi`、`timeoutSeconds`、`maxRuntimeSeconds`、`apiKeyConfigured`、`apiKeyMasked`、`lastTestStatus`、`lastTestMessage`、`lastTestAgentType`、`lastTestDetails`、`lastTestedAt`、`createdAt` 和 `updatedAt`
- **AND** 响应 MUST NOT 返回 API key 明文或密文

#### Scenario: 创建或更新模型 profile
- **WHEN** 已认证配置所有者调用 `POST /api/me/model-profiles` 或 `PATCH /api/me/model-profiles/{profileId}`
- **THEN** API SHALL 校验并保存模型 profile
- **AND** `modelProviderType` SHALL 只允许 `OPENAI_COMPATIBLE`
- **AND** `wireApi` SHALL 只允许 `RESPONSES` 或 `CHAT`
- **AND** `baseUrl` SHALL 只允许 `http` 或 `https` URL
- **AND** `model` SHALL 为非空字符串
- **AND** `timeoutSeconds` 和 `maxRuntimeSeconds` SHALL 在后端定义的安全范围内
- **AND** API MUST NOT 接受未声明的任意配置字段
- **AND** 个人 API MUST NOT 接受 `executablePath` 或任意命令覆盖

#### Scenario: 保存模型 profile API key
- **WHEN** 已认证配置所有者保存模型 profile 且请求包含非空 `apiKey`
- **THEN** 后端 SHALL 加密保存 API key
- **AND** API 响应 MUST NOT 返回 API key 明文或密文

#### Scenario: 保留或清空模型 profile API key
- **WHEN** 已认证配置所有者更新模型 profile 且请求不包含 `apiKey`
- **THEN** 后端 SHALL 保留已有 API key
- **WHEN** 已认证配置所有者更新模型 profile 且 `apiKey` 为空字符串
- **THEN** 后端 SHALL 清空已有 API key

#### Scenario: 测试模型 profile
- **WHEN** 已认证配置所有者调用 `POST /api/me/model-profiles/{profileId}:test`
- **THEN** API SHALL 使用已保存配置或请求中的临时覆盖配置执行最小可用性测试
- **AND** 请求 SHALL 支持指定 `agentRuntimeId`
- **AND** 未指定 `agentRuntimeId` 时后端 SHALL 使用本人的 `TEST_LOG_COMPARE` 当前绑定的 agent runtime 或默认受管 Codex runtime
- **AND** 临时覆盖配置中的 API key MUST NOT 写入已保存 profile；跨 worker 暂存 SHALL 加密、限定当前执行并在终态清理
- **AND** 响应 SHALL 返回成功或失败状态、agent 类型、agent runtime ID、分项 probe、脱敏消息和测试时间
- **AND** 后端 SHALL 记录最近一次测试状态、脱敏消息、agent 类型、agent runtime ID、分项结果和测试时间

#### Scenario: 查询和保存阶段模型绑定
- **WHEN** 已认证配置所有者调用 `GET /api/me/model-stage-assignments` 或 `PATCH /api/me/model-stage-assignments`
- **THEN** API SHALL 返回或保存当前用户两个受控阶段到本人模型 profile 和平台 agent runtime 的绑定
- **AND** 阶段名称 SHALL 使用后端白名单枚举，当前允许 `TEST_LOG_COMPARE` 和 `KB_AI_EXTENSION`
- **AND** 绑定目标 SHALL 为本人已启用模型 profile 和已启用 agent runtime
- **AND** 响应 MUST NOT 返回 API key 明文、密文、完整 agent 命令或完整 prompt

#### Scenario: 跨用户访问模型配置
- **WHEN** 任一已认证用户（包括 admin）使用他人 profile 或绑定目标访问个人接口
- **THEN** 资源访问 SHALL 返回与不存在资源一致的 404；跨用户绑定 SHALL 整体拒绝，不产生上游调用
- **AND** 后端 MUST NOT 泄漏任何 provider secret、agent 命令或内部执行细节

#### Scenario: 模型配置 API 不直接暴露模型调用能力
- **WHEN** 前端配置模型 profile、agent runtime 和阶段绑定
- **THEN** 后端 SHALL 只保存、查询、测试配置以及为后续测试结果比较 agent runtime 提供阶段解析
- **AND** 后端 MUST NOT 通过配置 API 提供任意提示词执行或直接 LLM 调用能力

#### Scenario: 普通用户和 admin 使用个人配置
- **WHEN** 已认证且账号有效的普通用户或 admin 管理自己的模型配置
- **THEN** API SHALL 从认证主体确定 owner，限定列表、计数和全部操作，拒绝客户端覆盖身份
- **AND** 同一所有者内名称 SHALL 大小写不敏感唯一，不同用户可以同名

### Requirement: Agent Runtime API 必须提供安全个人选择目录
后端 API SHALL 为已认证用户提供平台统一维护的 Agent Runtime 安全选择目录；个人测试结果 SHALL 按个人 profile 与 runtime 组合查询，不能成为共享 runtime 状态。

#### Scenario: 查询 agent runtime 列表
- **WHEN** 已认证用户调用 `GET /api/me/agent-runtimes`
- **THEN** API SHALL 返回 runtime ID、agent 类型、名称、启用状态及安全能力摘要
- **AND** 响应 MUST NOT 返回内部路径、完整命令、默认参数、私有配置、密钥或共享个人 probe 摘要

#### Scenario: 查询 agent runtime 详情
- **WHEN** 已认证用户调用 `GET /api/me/agent-runtimes/{runtimeId}`
- **THEN** API SHALL 返回该 runtime 的安全选择元数据，不返回全局个人 probe 结果
- **AND** 个人请求 MUST NOT 指定任意可执行路径或修改平台 CLI 安装配置

#### Scenario: 普通用户访问目录
- **WHEN** 普通用户或 admin 查询受管 runtime 目录
- **THEN** 后端 SHALL 允许账号有效的已认证用户读取安全目录，继续执行强制改密码等既有规则
- **AND** 不存在 runtime SHALL 返回受控错误，不泄漏内部执行细节

### Requirement: 原 admin 模型 API 必须彻底移除
后端 SHALL 删除原 `/api/admin/model-profiles`、`/api/admin/model-stage-assignments`、`/api/admin/model-profile-agent-test-runs` 和 `/api/admin/agent-runtimes` 路径族及其全部方法、动作后缀、子资源、专用安全匹配规则与当前 OpenAPI 定义；MUST NOT 保留别名、兼容处理器、重定向或专用迁移响应。

#### Scenario: 已认证调用旧接口
- **WHEN** 请求通过认证与适用 CSRF 校验后调用任一旧模型 API
- **THEN** 后端 SHALL 按未注册 API 返回 404，不调用业务服务、不修改配置或结果、不请求模型
- **AND** 无关 admin 功能及平台受管 CLI 维护 SHALL 保持原有边界

#### Scenario: 客户端使用个人接口
- **WHEN** 客户端调用 `/api/me/model-profiles/{profileId}:test` 等个人接口
- **THEN** 后端 SHALL 验证本人归属并保留主要响应字段、脱敏及双语诊断
- **AND** 单 Agent 入口 SHALL 保持有界同步语义，异步多 runtime 入口 SHALL 返回可轮询 run；前端适配由独立工作完成
