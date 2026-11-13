# task-runtime Specification

## Purpose
TBD - created by archiving change add-task-runtime. Update Purpose after archive.
## Requirements
### Requirement: 任务运行时必须统一创建长任务
任务运行时 SHALL 提供统一任务创建入口，用于创建所有需要异步执行的长任务。

#### Scenario: 创建长任务记录
- **WHEN** 后端业务模块请求创建长任务
- **THEN** 任务运行时 SHALL 在 PostgreSQL 中创建 `tasks` 记录
- **AND** 初始状态 SHALL 为 `PENDING`
- **AND** 任务输入快照 SHALL 保存到 `tasks.input_snapshot`
- **AND** 任务创建人 SHALL 来自当前登录用户或明确的系统执行上下文

#### Scenario: 追加初始任务事件
- **WHEN** 任务运行时成功创建任务记录
- **THEN** 任务运行时 SHALL 追加 `task.created` 事件到 `task_events`
- **AND** 事件 SHALL 关联同一 `project_id` 和 `task_id`

#### Scenario: 返回任务创建结果
- **WHEN** API 成功创建异步任务
- **THEN** 响应 SHALL 返回 `taskId`、任务类型和当前状态
- **AND** 响应 MUST NOT 返回完整输入快照、明文凭据或服务器本地路径

#### Scenario: 个人 AI 执行身份持久化
- **WHEN** 创建 AI 任务或可能自动派生 AI 比较的测试批次
- **THEN** SHALL 持久化认证触发者或可信父任务继承的配置主体、源任务或批次关联；AI 任务同时保存已选 profile/runtime 引用
- **AND** 明确系统执行上下文 MUST NOT 退化为任意服务账号或 admin 凭据兜底，不能用客户端传入身份替代认证

### Requirement: 任务运行时必须先持久化再投递队列
任务运行时 SHALL 以 PostgreSQL 任务记录作为事实来源，并在任务持久化后投递 RabbitMQ。

#### Scenario: 投递任务消息
- **WHEN** 任务记录和初始事件已经提交
- **THEN** 任务运行时 SHALL 通过统一 `TaskQueueClient` 投递 RabbitMQ 消息
- **AND** 消息 SHALL 包含 `taskId`
- **AND** 消息 MAY 包含任务类型、项目 ID 和创建时间
- **AND** 消息 MUST NOT 包含任务输入快照、任务结果、完整日志、任务状态事实或明文凭据

#### Scenario: 标记任务已入队
- **WHEN** RabbitMQ 投递成功
- **THEN** 任务运行时 SHALL 将任务状态更新为 `QUEUED`
- **AND** SHALL 设置 `queued_at`
- **AND** SHALL 追加 `task.queued` 事件

#### Scenario: 修复未投递任务
- **WHEN** 任务长时间停留在 `PENDING`
- **THEN** 任务运行时 SHALL 能通过修复流程重新投递该任务
- **AND** 修复流程 MUST 继续只以 PostgreSQL 中的任务记录为事实来源

### Requirement: Worker 必须幂等消费任务消息
Worker SHALL 使用 PostgreSQL 原子状态变更认领任务，并 SHALL 在处理结果可靠写回后手动确认 RabbitMQ 消息。

#### Scenario: 成功认领任务
- **WHEN** worker 收到任务队列消息
- **THEN** worker SHALL 根据 `taskId` 从 PostgreSQL 读取任务记录和输入快照
- **AND** worker SHALL 仅在任务状态为 `PENDING` 或 `QUEUED` 时将其原子更新为 `RUNNING`
- **AND** worker SHALL 设置 `started_at`
- **AND** worker SHALL 追加 `task.started` 事件

#### Scenario: 重复消息跳过
- **WHEN** worker 收到的任务已经处于 `RUNNING`、`SUCCEEDED`、`FAILED`、`CANCELLED` 或 `TIMEOUT`
- **THEN** worker SHALL 不重复执行业务 handler
- **AND** worker SHALL 确认该 RabbitMQ 消息

#### Scenario: 处理成功
- **WHEN** 任务 handler 成功完成
- **THEN** worker SHALL 将任务状态更新为 `SUCCEEDED`
- **AND** SHALL 设置 `finished_at`
- **AND** SHALL 追加 `task.succeeded` 事件
- **AND** SHALL 在数据库写回完成后确认 RabbitMQ 消息

#### Scenario: 处理失败
- **WHEN** 任务 handler 抛出业务异常或返回失败结果
- **THEN** worker SHALL 将任务状态更新为 `FAILED`
- **AND** SHALL 写入错误码和错误摘要
- **AND** SHALL 追加 `task.failed` 事件
- **AND** SHALL 在失败状态可靠写回后确认 RabbitMQ 消息

### Requirement: Worker 必须通过任务类型分发 handler
任务运行时 SHALL 提供任务 handler 注册和分发机制，使业务模块按任务类型接入 worker 主干。

#### Scenario: 分发到已注册 handler
- **WHEN** worker 认领任务后发现任务类型存在已注册 handler
- **THEN** worker SHALL 调用该 handler 处理任务
- **AND** handler SHALL 从 PostgreSQL 中读取任务输入快照和所需业务数据

#### Scenario: 缺少 handler
- **WHEN** worker 认领任务后发现任务类型没有已注册 handler
- **THEN** worker SHALL 将任务标记为 `FAILED`
- **AND** 错误码 SHALL 为 `TASK_HANDLER_NOT_FOUND`
- **AND** worker SHALL 追加 `task.failed` 事件

### Requirement: 任务运行时必须持久化任务事件
任务运行时 SHALL 将所有任务状态变化、进度变化和日志摘要写入 `task_events`。

#### Scenario: 写入进度事件
- **WHEN** 任务进度发生变化
- **THEN** 任务运行时 SHALL 更新 `tasks.progress` 和可展示消息
- **AND** SHALL 追加 `task.progress` 事件
- **AND** 进度值 SHALL 在 0 到 100 之间

#### Scenario: 写入日志摘要事件
- **WHEN** worker 捕获到适合展示的日志摘要
- **THEN** 任务运行时 SHALL 追加 `task.log` 事件
- **AND** 事件 MAY 包含日志级别和短摘要
- **AND** 事件 MUST NOT 包含完整 stdout 或 stderr 日志

#### Scenario: 写入终态事件
- **WHEN** 任务进入 `SUCCEEDED`、`FAILED`、`CANCELLED` 或 `TIMEOUT`
- **THEN** 任务运行时 SHALL 设置 `finished_at`
- **AND** SHALL 追加对应终态事件

### Requirement: 项目级 SSE 事件必须携带节点和资源元数据
任务运行时 SHALL 在项目级 SSE 事件响应中提供节点和资源元数据，使前端能够判断需要刷新哪个节点、哪类资源或哪条记录。

#### Scenario: 任务事件包含节点元数据
- **WHEN** 后端通过项目级 SSE 推送任务生命周期事件
- **THEN** SSE `data` SHALL 包含 `stageId`
- **AND** `stageId` SHALL 能标识前端工作流节点或等效后端阶段
- **AND** `stageId` MUST NOT 仅依赖前端从展示文本中推断

#### Scenario: 任务事件包含资源元数据
- **WHEN** 任务事件关联某个业务运行记录、报告、扫描、构建、测试或环境
- **THEN** SSE `data` SHALL 包含 `resource`
- **AND** `resource` SHALL 至少包含 `type` 和 `id`
- **AND** `resource` MAY 包含 `mode`、`environmentRole` 或其他用于前端局部刷新的非敏感摘要字段
- **AND** `resource` MUST NOT 包含完整业务结果、完整日志、服务器真实路径、明文凭据、token 或私钥

#### Scenario: 同一任务类型区分不同节点语义
- **WHEN** 同一任务类型可能服务多个节点或运行模式
- **THEN** 后端 SHALL 通过 `stageId`、`resource.mode` 或 `resource.environmentRole` 区分具体语义
- **AND** 前端 MUST NOT 只能依赖 `taskType` 判断刷新范围

### Requirement: 项目级 SSE 必须通知项目上下文变更
任务运行时 SHALL 通过项目级 SSE 通道发布项目上下文变更事件，用于通知源码版本、运行环境选择和环境配置版本等影响后续节点事实来源的变化。

#### Scenario: 源码版本激活后发布上下文事件
- **WHEN** 项目 active source revision 被上传激活、手动激活或 Git 同步任务激活
- **THEN** 后端 SHALL 持久化 `project.context.changed` 事件
- **AND** 事件 SHALL 关联当前 `projectId`
- **AND** 事件 SHALL 标识变化字段包含 active source revision
- **AND** 事件 SHALL 在事务提交后通过项目级 SSE 发布

#### Scenario: 运行环境选择更新后发布上下文事件
- **WHEN** 项目的 source x86 或 target riscv 运行环境选择被更新
- **THEN** 后端 SHALL 持久化 `project.context.changed` 事件
- **AND** 事件 SHALL 标识变化字段包含 runtime environment selection 或 environment config version
- **AND** 事件 SHALL 在事务提交后通过项目级 SSE 发布

#### Scenario: 上下文事件可以没有任务 ID
- **WHEN** 项目上下文变化不是由某个具体任务生命周期直接表达
- **THEN** 后端 SHALL 允许事件不包含 `taskId`
- **AND** 事件响应中的 `taskType` MAY 为空
- **AND** 后端 MUST NOT 为上下文变化伪造业务任务

#### Scenario: 上下文事件触发前端重读事实接口
- **WHEN** 前端收到 `project.context.changed` 事件
- **THEN** 前端 SHALL 能根据事件元数据重读 overview、readiness、节点列表或节点详情
- **AND** 后端 MUST NOT 通过该 SSE 事件返回完整项目详情、完整源码记录、完整环境配置或完整报告

### Requirement: 项目级 SSE 事件必须保持项目隔离
项目级 SSE SHALL 只向有权限访问该项目的连接推送该项目事件，包括任务生命周期事件和项目上下文变更事件。

#### Scenario: 不推送其他项目的上下文事件
- **WHEN** 项目 A 和项目 B 同时存在 SSE 连接
- **AND** 项目 B 的源码版本或运行环境选择发生变化
- **THEN** 项目 A 的 SSE 连接 MUST NOT 收到项目 B 的 `project.context.changed` 事件

#### Scenario: 不补发其他项目的游标后事件
- **WHEN** 前端使用 `afterEventId` 或 `Last-Event-ID` 连接项目 SSE
- **THEN** 后端 SHALL 只按当前 `projectId` 查询游标之后的事件
- **AND** 后端 MUST NOT 因事件 ID 全局递增而返回其他项目事件

### Requirement: 项目级 SSE 必须使用项目事件 baseline
任务运行时 SHALL 提供项目级 SSE 通道，并 SHALL 使用当前项目最新事件 ID 支持首次订阅 baseline；SSE 是轻量变更通知通道，不承担完整历史状态恢复职责。

#### Scenario: 建立项目级 SSE
- **WHEN** 已登录用户请求 `GET /api/projects/{projectId}/task-events`
- **THEN** 后端 SHALL 返回 `text/event-stream`
- **AND** SSE 通道 SHALL 只推送该项目的任务事件和项目上下文变更事件
- **AND** 后端 SHALL 周期性发送心跳以保持连接

#### Scenario: 使用 afterEventId 作为首次订阅 baseline
- **WHEN** 前端请求 `GET /api/projects/{projectId}/task-events?afterEventId={eventId}`
- **THEN** 后端 SHALL 将 `afterEventId` 解析为项目事件数字游标
- **AND** 后端 SHALL 只按当前 `project_id` 查询游标之后的事件
- **AND** 补发顺序 SHALL 按事件数字 ID 升序
- **AND** 后端 MAY 对单次补发数量设置受控上限

#### Scenario: 使用 Last-Event-ID 进行同一页面短线重连
- **WHEN** 前端未提交 `afterEventId` 且携带 `Last-Event-ID` 重新连接
- **THEN** 后端 SHALL 将该值解析为项目事件数字游标
- **AND** 后端 SHALL 只按当前 `project_id` 查询游标之后的事件
- **AND** 后端 MAY 对单次补发数量设置受控上限
- **AND** 前端仍 SHALL 通过 overview、列表或详情接口恢复完整页面状态

#### Scenario: 无游标首次连接使用当前项目最新事件作为 baseline
- **WHEN** 前端请求项目级 SSE 且未提交 `afterEventId` 或 `Last-Event-ID`
- **THEN** 后端 SHALL 查询当前项目最新事件 ID 作为 baseline
- **AND** 后端 SHALL 只推送该 baseline 之后的新事件
- **AND** 后端 MUST NOT 默认补发该项目全部历史事件

#### Scenario: afterEventId 优先于 Last-Event-ID
- **WHEN** 前端同时提交 `afterEventId` 和 `Last-Event-ID`
- **THEN** 后端 SHALL 使用 `afterEventId` 作为游标
- **AND** 后端 SHALL 忽略本次请求中的 `Last-Event-ID`

#### Scenario: 拒绝非法 SSE 游标
- **WHEN** `afterEventId` 或被使用的 `Last-Event-ID` 不是非负数字
- **THEN** 后端 SHALL 返回 `SSE_LAST_EVENT_ID_INVALID` 或等效统一错误
- **AND** 后端 MUST NOT 创建 SSE emitter
- **AND** 后端 MUST NOT 补发历史事件

#### Scenario: 发送 SSE 事件
- **WHEN** 后端向 SSE 连接发送项目事件
- **THEN** SSE `id` SHALL 使用持久化事件数字 ID
- **AND** SSE `event` SHALL 使用持久化事件类型
- **AND** SSE `data` SHALL 包含轻量事件载荷
- **AND** SSE `data` MUST NOT 包含完整业务结果、完整日志、明文凭据、token、私钥或服务器真实路径

#### Scenario: 多项目 SSE 隔离
- **WHEN** 同一用户或不同用户同时打开多个项目的 SSE 连接
- **THEN** 每个 SSE 连接 SHALL 只收到对应 `projectId` 的项目事件
- **AND** 后端 MUST NOT 因全局递增事件 ID 将其他项目事件补发或推送到当前项目连接

### Requirement: 项目 overview 必须提供项目级 SSE baseline
任务运行时 SHALL 提供当前项目最新任务事件 ID，使项目 overview 能作为首次建立 SSE 的事实 baseline。

#### Scenario: 查询最新项目事件 ID
- **WHEN** 后端生成项目 overview
- **THEN** 任务运行时 SHALL 能按 `projectId` 查询当前项目最新 `task_events.id`
- **AND** 没有任务事件时 SHALL 返回 `0`

#### Scenario: 历史异常事件不默认补发
- **WHEN** 项目存在大量历史任务事件
- **AND** 前端首次打开项目且未提交游标
- **THEN** 后端 SHALL 从当前项目最新事件 ID 之后开始推送
- **AND** 后端 MUST NOT 默认补发历史异常事件

### Requirement: 重试必须作为运行过程事件表达
任务重试 SHALL 保持在既有任务状态模型内表达，不得新增独立的 `RETRYING` 任务状态。

#### Scenario: 重试期间状态表达
- **WHEN** worker 判断任务失败可重试
- **THEN** 后端 SHALL 使用 `QUEUED` 或 `RUNNING` 表达任务仍在执行流程中
- **AND** 后端 SHALL 使用 `retry_count`、message 和任务事件表达重试次数
- **AND** 后端 MUST NOT 新增 `RETRYING` 状态

#### Scenario: 达到最大重试次数
- **WHEN** 任务达到最大重试次数仍失败
- **THEN** worker SHALL 将任务标记为 `FAILED`
- **AND** worker SHALL 追加单个终态 `task.failed` 事件

### Requirement: 任务 API 必须提供查询、取消和日志访问
任务运行时 SHALL 提供任务详情、项目任务列表、取消任务、日志列表和日志下载 API。

#### Scenario: 查询项目任务列表
- **WHEN** 前端调用 `GET /api/projects/{projectId}/tasks`
- **THEN** 后端 SHALL 返回该项目的分页任务列表
- **AND** 列表项 SHALL 包含任务 ID、类型、状态、进度、消息和关键时间字段

#### Scenario: 查询任务详情
- **WHEN** 前端调用 `GET /api/tasks/{taskId}`
- **THEN** 后端 SHALL 返回任务类型、状态、进度、消息、时间、错误摘要和关联业务资源引用
- **AND** 响应 MUST NOT 返回完整输入快照、明文凭据或服务器本地路径

#### Scenario: 取消未开始任务
- **WHEN** 前端调用 `POST /api/tasks/{taskId}/cancel` 且任务状态为 `PENDING` 或 `QUEUED`
- **THEN** 后端 SHALL 将任务状态更新为 `CANCELLED`
- **AND** SHALL 设置 `finished_at`
- **AND** SHALL 追加 `task.cancelled` 事件

#### Scenario: 取消运行中任务
- **WHEN** 前端调用 `POST /api/tasks/{taskId}/cancel` 且任务状态为 `RUNNING`
- **THEN** 后端 SHALL 将任务状态更新为 `CANCELLED`
- **AND** SHALL 设置 `finished_at`
- **AND** SHALL 追加 `task.cancelled` 事件
- **AND** SHALL 根据 active runtime handle 投递取消清理消息
- **AND** 后端 MUST NOT 等待底层线程、进程或远程命令完全退出后才返回 API 响应

#### Scenario: 查询任务日志元数据
- **WHEN** 前端调用 `GET /api/tasks/{taskId}/logs`
- **THEN** 后端 SHALL 返回该任务的日志元数据、日志类型、大小、摘要和创建时间
- **AND** 响应 MUST NOT 暴露服务器真实文件路径

#### Scenario: 下载任务日志
- **WHEN** 前端调用 `GET /api/tasks/{taskId}/logs/{logId}/download`
- **THEN** 后端 SHALL 校验日志属于该任务
- **AND** SHALL 通过受控下载返回日志文件
- **AND** 响应 MUST NOT 允许客户端指定服务器文件路径

### Requirement: 任务运行时必须支持完成态日志内容读取
任务运行时 SHALL 提供任务日志内容读取 API，使前端可以在构建或测试完成后按 `merged`、`stdout`、`stderr` 三类读取日志正文；物理日志 SHALL 按产生时间追加，日志分页 offset SHALL 从最新日志向更早日志计算，单页内容 SHALL 按产生时间正序返回。

#### Scenario: 默认读取三类日志最新展示页
- **WHEN** 已登录用户调用 `GET /api/tasks/{taskId}/log-content` 且不传查询参数
- **THEN** 后端 SHALL 根据任务所属项目执行访问守卫
- **AND** 后端 SHALL 返回 `merged`、`stdout` 和 `stderr` 三类日志内容
- **AND** 每类日志 SHALL 默认返回物理文件最后最多 100 行
- **AND** 每类单页内容 SHALL 从较早行到较新行正序排列
- **AND** 响应 MUST NOT 暴露服务器真实日志文件路径

#### Scenario: 只读取单类日志
- **WHEN** 前端调用 `GET /api/tasks/{taskId}/log-content?types=stdout`
- **THEN** 后端 SHALL 只返回 `stdout` 日志内容
- **AND** 响应 SHALL 不包含未请求的 `merged` 或 `stderr` 内容

#### Scenario: 同时读取多类不同 offset
- **WHEN** 前端调用 `GET /api/tasks/{taskId}/log-content?types=merged,stdout,stderr&mergedOffset=0&stdoutOffset=100&stderrOffset=200&lines=100`
- **THEN** 后端 SHALL 按每个日志类型各自的 offset 从最新页向更早日志分页读取
- **AND** 每类日志 SHALL 独立返回 `offset`、`lines`、`returnedLines`、`hasMore` 和 `content`
- **AND** 每类日志的单页内容 SHALL 保持从较早行到较新行的时间正序
- **AND** 某类日志缺失时该类 SHALL 返回空内容和 `hasMore=false`，而不是导致整个请求失败

#### Scenario: 正序追加日志首屏读取最新页
- **WHEN** 日志文件中较早日志位于文件头且较新日志追加在文件尾
- **AND** 前端调用 `GET /api/tasks/{taskId}/log-content?types=merged&mergedOffset=0&lines=100`
- **THEN** 后端 SHALL 返回文件尾部最后最多 100 行
- **AND** 返回内容 SHALL 按文件内自然顺序从较早行排列到最新行
- **AND** 后端 MUST NOT 返回文件头部最早 100 行作为首屏内容

#### Scenario: 从最新页读取更早页面
- **WHEN** 日志文件中较早日志位于文件头且较新日志追加在文件尾
- **AND** 前端调用 `GET /api/tasks/{taskId}/log-content?types=merged&mergedOffset=100&lines=100`
- **THEN** 后端 SHALL 跳过文件末尾最新 100 行并返回其前方最多 100 行
- **AND** 返回页内部 SHALL 保持时间正序
- **AND** `hasMore` SHALL 表示当前页之前是否仍存在更早日志

#### Scenario: 前端拼接多个完成态日志页
- **WHEN** 前端先取得最新页并继续请求更早页面
- **THEN** 前端 SHALL 将更早页面整体插入已有日志之前
- **AND** 前端 MUST NOT 反转任何单页中的日志行
- **AND** 拼接后的完整内容 SHALL 从最早日志排列到最新日志

#### Scenario: 单类行数覆盖全局行数
- **WHEN** 请求同时包含 `lines=100` 和 `stdoutLines=50`
- **THEN** `stdout` SHALL 使用 50 行作为读取数量
- **AND** 未指定单类行数的日志类型 SHALL 使用全局 `lines`

#### Scenario: 到达日志最旧端
- **WHEN** 某类日志请求的 offset 已经超过或到达该日志的总行数
- **THEN** 后端 SHALL 返回空内容或剩余可用内容
- **AND** 该类日志 SHALL 返回 `hasMore=false`

#### Scenario: 不同换行和无末尾换行
- **WHEN** 日志使用 `LF` 或 `CRLF`，或者最后一行没有换行符
- **THEN** 后端 SHALL 按逻辑行正确计算分页窗口
- **AND** 返回内容 MUST NOT 丢失、重复或拆分日志行

#### Scenario: 拒绝非法日志内容参数
- **WHEN** 请求包含未知 `types`、负数 offset、负数 lines 或超过上限的 lines
- **THEN** 后端 SHALL 返回统一参数校验错误
- **AND** 后端 MUST NOT 读取日志文件

#### Scenario: 无权限读取日志内容
- **WHEN** 无权限用户调用 `GET /api/tasks/{taskId}/log-content`
- **THEN** 后端 SHALL 返回与任务不存在一致的安全错误
- **AND** 响应 MUST NOT 暴露任务是否存在、日志类型、日志大小或日志内容

#### Scenario: 日志内容读取不替代 SSE
- **WHEN** 任务仍在运行中
- **THEN** 前端 SHALL 继续使用任务级日志 SSE 获取变化通知并使用增量日志接口读取新增内容
- **AND** `/log-increments` 返回的新增日志 SHALL 保持现有时间正序
- **AND** 完成态日志内容接口 SHALL 只作为日志文件内容读取能力，不承担实时推送职责

### Requirement: Worker handler 必须通过执行上下文处理任务
任务运行时 SHALL 为 worker handler 提供统一任务执行上下文，使 handler 通过该上下文读取任务元数据、解析输入快照、更新进度、追加事件、写入任务日志、检查取消请求并登记可取消资源。

#### Scenario: 创建执行上下文
- **WHEN** worker 成功认领任务并找到已注册 handler
- **THEN** 任务运行时 SHALL 为该任务创建执行上下文
- **AND** 执行上下文 SHALL 包含当前任务 ID、项目 ID、任务类型、当前状态和输入快照访问能力
- **AND** handler SHALL 通过执行上下文处理任务

#### Scenario: 安全解析输入快照
- **WHEN** handler 需要读取任务输入快照
- **THEN** handler SHALL 通过执行上下文解析输入快照
- **AND** 解析失败 SHALL 使任务进入 `FAILED`
- **AND** 失败摘要 MUST NOT 包含原始输入快照、明文凭据或服务器本地路径

#### Scenario: Handler 不直接写任务状态事实
- **WHEN** handler 需要更新任务进度、事件或日志
- **THEN** handler SHALL 使用执行上下文提供的任务运行时方法
- **AND** handler SHOULD NOT 直接写入 `tasks`、`task_events` 或 `task_logs` 表

#### Scenario: Handler 检查取消请求
- **WHEN** handler 进入长时间运行步骤、循环、外部进程等待或远程命令等待
- **THEN** handler SHALL 能通过执行上下文检查任务是否已请求取消
- **AND** handler SHALL 在检测到取消请求后停止后续业务处理

#### Scenario: Handler 登记可取消资源
- **WHEN** handler 启动本地进程或远程命令
- **THEN** handler SHALL 通过执行上下文登记对应 runtime handle 信息
- **AND** handler MUST NOT 将明文凭据或完整命令输入写入 runtime handle 明文字段

#### Scenario: 无请求会话下恢复个人任务
- **WHEN** worker 重启恢复或自动重试个人 AI 任务
- **THEN** 执行上下文 SHALL 从持久化记录恢复原配置主体和引用，复核账号与项目权限后传递给 handler
- **AND** MUST NOT 依赖 ThreadLocal、当前查看者或共享默认配置；归属不符 SHALL 在上游调用前失败

#### Scenario: 手动重试建立新执行
- **WHEN** 当前认证用户手动重新触发已有 AI 任务
- **THEN** SHALL 使用新触发者创建新执行，而不改写原任务配置主体；系统自动重试 SHALL 保持原主体与已选引用

### Requirement: 执行上下文必须统一写入任务进度事件
任务执行上下文 SHALL 提供进度更新能力，并 SHALL 保证任务进度、展示消息和持久化事件一致。

#### Scenario: 写入合法进度
- **WHEN** handler 通过执行上下文提交 0 到 100 之间的进度和展示消息
- **THEN** 任务运行时 SHALL 更新 `tasks.progress` 和 `tasks.message`
- **AND** SHALL 追加 `task.progress` 事件
- **AND** 项目级 SSE SHALL 能推送该进度事件

#### Scenario: 拒绝非法进度
- **WHEN** handler 通过执行上下文提交小于 0 或大于 100 的进度
- **THEN** 任务运行时 SHALL 拒绝该进度更新
- **AND** worker SHALL 将任务标记为 `FAILED`
- **AND** 失败摘要 SHALL 使用安全错误消息

#### Scenario: 多次进度更新
- **WHEN** handler 在一次任务执行过程中多次提交进度
- **THEN** 任务运行时 SHALL 按提交顺序持久化对应进度事件
- **AND** 最终任务详情 SHALL 展示最近一次进度和消息，直到进入终态

### Requirement: 执行上下文必须受控写入任务日志
任务执行上下文 SHALL 支持 handler 写入 stdout、stderr、combined 或 tool 日志，并 SHALL 将完整日志保存为受控本地文件，同时只向事件流暴露短摘要。

#### Scenario: 写入任务日志
- **WHEN** handler 通过执行上下文写入任务日志
- **THEN** 任务运行时 SHALL 在 `RVPORT_LOG_DIR` 管理的目录下创建或追加日志文件
- **AND** SHALL 在 `task_logs` 表登记日志类型、大小、摘要和受控文件路径
- **AND** 任务日志下载 API SHALL 能通过既有受控下载能力访问该日志

#### Scenario: 追加日志摘要事件
- **WHEN** handler 写入适合实时展示的日志内容
- **THEN** 任务运行时 SHALL 追加 `task.log` 事件
- **AND** 事件 SHALL 只包含短摘要
- **AND** 事件 MUST NOT 包含完整 stdout、完整 stderr、明文凭据或服务器真实文件路径

#### Scenario: 空日志内容
- **WHEN** handler 尝试写入空日志内容
- **THEN** 任务运行时 SHALL 使用明确策略处理该请求
- **AND** 该策略 SHALL 不产生无法下载的悬空 `task_logs` 记录

#### Scenario: 日志写入失败
- **WHEN** 日志文件无法创建或写入
- **THEN** 任务运行时 SHALL 将任务标记为 `FAILED`
- **AND** 失败摘要 SHALL 使用安全错误消息
- **AND** 失败摘要 MUST NOT 暴露服务器本地路径

### Requirement: 任务执行上下文必须保护敏感信息边界
任务执行上下文 SHALL 保证任务执行过程中的输入解析、进度消息、日志摘要和失败摘要不泄露明文秘密或服务器内部路径。

#### Scenario: 不泄露明文凭据
- **WHEN** handler 使用执行上下文写入进度、日志摘要或失败摘要
- **THEN** 任务运行时 MUST NOT 在 `task_events`、API 响应或队列消息中暴露密码、token、SSH 私钥、passphrase 或加密密文

#### Scenario: 不泄露服务器真实路径
- **WHEN** 执行上下文创建日志文件或处理任务本地文件
- **THEN** 任务事件和 API 响应 MUST NOT 暴露服务器真实绝对路径
- **AND** 日志下载 SHALL 继续通过受控日志 ID 访问

#### Scenario: 队列消息保持最小载荷
- **WHEN** 任务执行上下文能力被启用
- **THEN** RabbitMQ 任务消息 MUST 继续只包含任务 ID 等路由信息
- **AND** 消息 MUST NOT 包含任务输入快照、日志内容、任务结果或明文凭据

### Requirement: 任务执行上下文必须配套测试覆盖
任务执行上下文相关实现 SHALL 配套单元测试或轻量集成测试，并 SHALL 覆盖正常流程、异常流程和 edge case。

#### Scenario: 覆盖正常流程
- **WHEN** 为任务执行上下文编写测试
- **THEN** 测试 SHALL 覆盖 handler 成功解析输入、更新进度、写入日志和完成任务

#### Scenario: 覆盖异常流程
- **WHEN** 为任务执行上下文编写测试
- **THEN** 测试 SHALL 覆盖输入解析失败、日志写入失败、缺少 handler 和 handler 抛异常等错误路径

#### Scenario: 覆盖 edge case
- **WHEN** 为任务执行上下文编写测试
- **THEN** 测试 SHALL 覆盖空日志、超长日志摘要截断、非法进度、多次进度更新和敏感信息脱敏边界

### Requirement: 任务 API 和 SSE 必须执行项目访问守卫
任务运行时 SHALL 对任务查询、取消、日志访问、日志下载和项目级 SSE 执行项目访问守卫，防止用户通过任务 ID、日志 ID 或 SSE 游标读取其他用户项目事件。

#### Scenario: 查询项目任务列表
- **WHEN** 已登录用户调用 `GET /api/projects/{projectId}/tasks`
- **THEN** 后端 SHALL 校验当前用户有权访问该项目
- **AND** 无权限时 SHALL 返回与项目不存在一致的安全错误

#### Scenario: 查询任务详情
- **WHEN** 已登录用户调用 `GET /api/tasks/{taskId}`
- **THEN** 后端 SHALL 根据 `tasks.project_id` 校验当前用户有权访问任务所属项目
- **AND** 无权限时 SHALL 返回与任务不存在一致的安全错误

#### Scenario: 取消任务
- **WHEN** 已登录用户调用 `POST /api/tasks/{taskId}/cancel`
- **THEN** 后端 SHALL 根据任务所属项目校验当前用户有权访问该任务
- **AND** 无权限时 SHALL 拒绝取消且 MUST NOT 改变任务状态

#### Scenario: 查询任务日志
- **WHEN** 已登录用户调用 `GET /api/tasks/{taskId}/logs`
- **THEN** 后端 SHALL 根据任务所属项目校验当前用户有权访问该任务
- **AND** 无权限时 SHALL 返回与任务不存在一致的安全错误
- **AND** 后端 MUST NOT 返回日志摘要、大小、类型或创建时间

#### Scenario: 下载任务日志
- **WHEN** 已登录用户调用 `GET /api/tasks/{taskId}/logs/{logId}/download`
- **THEN** 后端 SHALL 校验任务可访问且日志属于该任务
- **AND** 无权限、任务不存在、日志不存在或日志不属于该任务时 SHALL 返回安全错误
- **AND** 后端 MUST NOT 返回服务器真实路径

#### Scenario: 建立项目级 SSE
- **WHEN** 已登录用户调用 `GET /api/projects/{projectId}/task-events`
- **THEN** 后端 SHALL 在创建 SSE emitter 前校验当前用户有权访问该项目
- **AND** 无权限时 SHALL 拒绝请求
- **AND** 无权限时 MUST NOT 创建 emitter、注册订阅或补发历史事件

#### Scenario: 使用 Last-Event-ID 恢复 SSE
- **WHEN** 有项目访问权限的用户携带 `Last-Event-ID` 重新连接
- **THEN** 后端 SHALL 只补发同一项目内 `id` 大于该值的事件
- **AND** 后端 MUST NOT 因全局自增事件 ID 补发其他项目事件

### Requirement: 任务运行时必须支持 SBOM_ANALYSIS 任务类型
任务运行时 SHALL 支持 `SBOM_ANALYSIS` 长任务类型，并通过既有 worker handler 机制执行 SBOM 分析。

#### Scenario: 创建 SBOM 分析任务
- **WHEN** SBOM API 请求创建分析
- **THEN** 任务运行时 SHALL 创建任务类型为 `SBOM_ANALYSIS` 的 `tasks` 记录
- **AND** 任务输入快照 SHALL 包含项目 ID、源环境 ID、目标环境 ID、源快照 ID、目标快照 ID、仓库策略和分析选项
- **AND** 任务输入快照 MUST NOT 包含明文凭据、服务器真实路径或完整分析器输入 JSON

#### Scenario: Worker 执行 SBOM handler
- **WHEN** worker 认领 `SBOM_ANALYSIS` 任务
- **THEN** worker SHALL 分发到 SBOM handler
- **AND** handler SHALL 通过任务执行上下文读取输入、更新进度、写入日志和完成任务

#### Scenario: 缺少 SBOM handler
- **WHEN** worker 认领 `SBOM_ANALYSIS` 任务但未注册 handler
- **THEN** 任务 SHALL 按既有缺少 handler 规则失败
- **AND** 错误码 SHALL 表示任务 handler 不存在

### Requirement: SBOM_ANALYSIS 必须写入工具执行记录和任务日志
SBOM_ANALYSIS handler SHALL 将分析器调用记录为 tool run，并保存 stdout/stderr 日志元数据。

#### Scenario: 分析器调用成功
- **WHEN** SBOM 分析器返回成功
- **THEN** 后端 SHALL 写入 `tool_runs`
- **AND** `tool_runs.tool_name` SHALL 使用稳定 SBOM 分析器工具名
- **AND** `tool_runs.exit_code` SHALL 保存分析器退出码
- **AND** `tool_runs.summary` SHALL 保存分析器版本、输入摘要、输出摘要、耗时和解析警告
- **AND** 后端 SHALL 通过 `task_logs` 保存 stdout/stderr 日志文件元数据和安全 excerpt

#### Scenario: 分析器调用失败
- **WHEN** SBOM 分析器进程启动失败、超时或返回非零退出码
- **THEN** 后端 SHALL 写入失败态 `tool_runs`
- **AND** 后端 SHALL 保存 stdout/stderr 日志文件元数据
- **AND** 任务失败摘要 MUST NOT 暴露服务器真实路径或完整工具输入

### Requirement: SBOM_ANALYSIS 必须通过 SSE 推送任务状态
SBOM_ANALYSIS 任务 SHALL 复用项目级 SSE 推送进度、日志摘要和终态事件。

#### Scenario: 推送进度
- **WHEN** SBOM handler 完成输入导出、工具执行、结果解析和结果落库阶段
- **THEN** handler SHALL 通过任务执行上下文写入进度事件
- **AND** 项目级 SSE SHALL 能向有权限用户推送这些事件

#### Scenario: 推送终态
- **WHEN** SBOM_ANALYSIS 任务成功或失败
- **THEN** worker SHALL 写入对应终态事件
- **AND** 前端 SHALL 能通过项目级 SSE 或任务详情查询获得最终状态

### Requirement: 任务运行时必须支持任务级日志 SSE
任务运行时 SHALL 提供任务级日志 SSE，用于单个任务的历史日志补发、实时日志推送和终态通知。

#### Scenario: 建立任务级日志 SSE
- **WHEN** 已登录用户调用 `GET /api/tasks/{taskId}/log-events`
- **THEN** 后端 SHALL 根据任务所属项目执行项目访问守卫
- **AND** 后端 SHALL 返回 `text/event-stream`
- **AND** SSE 通道 SHALL 只推送该任务的日志事件和终态事件
- **AND** 后端 SHALL 周期性发送 heartbeat 以保持连接

#### Scenario: 首次连接补发全部历史日志
- **WHEN** 用户建立任务级日志 SSE 且未提交 `Last-Event-ID`
- **THEN** 后端 SHALL 按日志事件 ID 升序补发该任务已有的全部日志事件
- **AND** 补发完成后 SHALL 保持连接继续推送新增日志事件

#### Scenario: 使用 Last-Event-ID 恢复日志流
- **WHEN** 用户携带 `Last-Event-ID` 重新连接任务级日志 SSE
- **THEN** 后端 SHALL 将该值解析为 `task_log_events.id`
- **AND** 后端 SHALL 只补发同一任务内 `id` 大于该值的日志事件
- **AND** 后端 MUST NOT 因全局自增 ID 补发其他任务或其他项目的日志事件

#### Scenario: 推送 stdout 和 stderr 日志事件
- **WHEN** worker 捕获到任务 stdout 或 stderr 输出
- **THEN** 任务运行时 SHALL 写入 `task_log_events`
- **AND** 任务级日志 SSE SHALL 推送对应事件
- **AND** SSE `id` SHALL 使用 `task_log_events.id`
- **AND** SSE `event` SHALL 使用 `task.log.stdout` 或 `task.log.stderr`
- **AND** SSE `data` SHALL 包含任务 ID、日志类型、内容片段、序号和时间

#### Scenario: 终态任务的日志 SSE
- **WHEN** 用户连接已经终态的任务日志 SSE
- **THEN** 后端 SHALL 先补发历史日志事件
- **AND** 后端 SHALL 发送任务终态事件
- **AND** 后端 MAY 在发送终态事件后关闭 SSE 连接

#### Scenario: 无权限订阅任务日志 SSE
- **WHEN** 无权限用户调用 `GET /api/tasks/{taskId}/log-events`
- **THEN** 后端 SHALL 拒绝请求
- **AND** 后端 MUST NOT 创建 SSE emitter
- **AND** 后端 MUST NOT 补发历史日志事件

### Requirement: 任务运行时必须持久化日志事件
任务运行时 SHALL 将用于实时展示和恢复的日志切片持久化为日志事件，同时继续使用 `task_logs` 保存完整日志文件元数据。

#### Scenario: 写入日志事件
- **WHEN** worker 捕获任务输出内容
- **THEN** 任务运行时 SHALL 将内容切分为有界日志事件
- **AND** 每条日志事件 SHALL 关联 `project_id`、`task_id`、日志类型、序号、内容和创建时间
- **AND** 日志事件 SHALL 按同一任务内写入顺序可排序

#### Scenario: 日志事件内容脱敏
- **WHEN** 任务运行时写入日志事件
- **THEN** 日志事件内容 MUST NOT 包含 SSH 密码、私钥、token、passphrase、加密密文或服务器真实绝对路径
- **AND** 超长内容 SHALL 被切分或截断到受控大小

#### Scenario: 完整日志文件仍可下载
- **WHEN** worker 写入日志事件
- **THEN** 任务运行时 SHALL 继续维护 `task_logs` 元数据和本地完整日志文件
- **AND** 任务日志下载 API SHALL 继续通过日志 ID 受控下载完整日志
- **AND** 任务日志下载 API MUST NOT 依赖客户端提交服务器文件路径

#### Scenario: 日志事件写入失败
- **WHEN** 任务运行时无法写入日志事件
- **THEN** worker SHALL 将任务标记为 `FAILED`
- **AND** 失败摘要 SHALL 使用安全错误信息
- **AND** 失败摘要 MUST NOT 暴露日志文件真实路径或未脱敏日志内容

### Requirement: 任务级日志 SSE 不替代项目级 SSE
任务运行时 SHALL 保留项目级 SSE 作为项目工作台任务状态通道，并 SHALL 使用任务级日志 SSE 承载单任务密集日志流。

#### Scenario: 项目级 SSE 推送任务状态
- **WHEN** 任务创建、入队、开始、进度变化或进入终态
- **THEN** 项目级 SSE SHALL 继续推送对应任务事件
- **AND** 项目级 SSE MAY 包含短日志摘要
- **AND** 项目级 SSE MUST NOT 承载完整或密集 stdout/stderr 日志流

#### Scenario: 脚本执行页订阅任务级日志 SSE
- **WHEN** 前端打开脚本执行页或执行记录详情页
- **THEN** 前端 SHALL 使用任务级日志 SSE 获取历史日志和新增日志
- **AND** 前端 MAY 同时通过任务详情 API 或项目级 SSE 获取任务状态摘要

### Requirement: 任务运行时必须支持 KB_SCAN 任务类型
任务运行时 SHALL 支持 `KB_SCAN` 长任务类型，并通过既有 worker handler 机制执行 KB 扫描。

#### Scenario: 创建 KB_SCAN 任务
- **WHEN** KB 扫描 API 请求创建扫描
- **THEN** 任务运行时 SHALL 创建任务类型为 `KB_SCAN` 的 `tasks` 记录
- **AND** 任务输入快照 SHALL 包含 project id、scan run id、source revision id、规则版本、语言、扫描范围、排除路径和目标架构摘要
- **AND** 任务输入快照 MUST NOT 包含源码工作副本路径、scanner 输出目录、完整 scanner 输入、明文凭据或服务器真实路径

#### Scenario: Worker 执行 KB_SCAN handler
- **WHEN** worker 认领 `KB_SCAN` 任务
- **THEN** worker SHALL 分发到 KB_SCAN handler
- **AND** handler SHALL 通过任务执行上下文读取输入、更新进度、写入日志、登记本地 scanner 进程并完成任务

#### Scenario: 缺少 KB_SCAN handler
- **WHEN** worker 认领 `KB_SCAN` 任务但未注册 handler
- **THEN** 任务 SHALL 按既有缺少 handler 规则失败
- **AND** 错误码 SHALL 表示任务 handler 不存在

### Requirement: KB_SCAN 必须通过 SSE 推送状态和日志
KB_SCAN 任务 SHALL 复用项目级 SSE 推送进度和终态事件，并 SHALL 复用任务级日志 SSE 展示 scanner 日志。

#### Scenario: 推送 KB 扫描进度
- **WHEN** KB_SCAN handler 完成输入校验、输出目录准备、scanner 执行、结果解析和 findings 入库阶段
- **THEN** handler SHALL 通过任务执行上下文写入进度事件
- **AND** 项目级 SSE SHALL 能向有权限用户推送这些事件

#### Scenario: 推送 KB 扫描日志
- **WHEN** KB Scanner 产生 stdout、stderr 或 scan.log 内容
- **THEN** 任务运行时 SHALL 写入任务日志事件
- **AND** 任务级日志 SSE SHALL 能补发历史日志并继续推送新增日志
- **AND** 日志事件 MUST NOT 包含服务器真实路径、源码工作副本路径、明文凭据或完整任务输入快照

#### Scenario: 推送 KB 扫描终态
- **WHEN** KB_SCAN 任务成功、失败、取消或超时
- **THEN** worker SHALL 写入对应终态事件
- **AND** 前端 SHALL 能通过项目级 SSE、任务详情或 KB 扫描详情获得最终状态

### Requirement: KB_SCAN 取消必须终止本地 scanner 进程
任务运行时 SHALL 允许取消正在运行的 KB_SCAN 任务，并 SHALL 通过已登记的 runtime handle 尝试终止本地 scanner 进程。

#### Scenario: 取消运行中 KB_SCAN
- **WHEN** 用户调用 `POST /api/tasks/{taskId}/cancel` 且任务类型为 `KB_SCAN`
- **THEN** 后端 SHALL 按既有运行中任务取消规则将任务状态更新为 `CANCELLED`
- **AND** 后端 SHALL 投递取消清理消息
- **AND** 取消处理 SHALL 根据 active runtime handle 尝试终止 scanner 本地进程
- **AND** 后端 SHALL 将关联 `kb_scan_runs` 标记为 `CANCELLED`

#### Scenario: 取消后不写成功结果
- **WHEN** KB_SCAN 在 scanner 执行或结果导入期间被取消
- **THEN** worker MUST NOT 将任务或 `kb_scan_runs` 标记为 `SUCCEEDED`
- **AND** worker MUST NOT 写入成功态 artifact 或误报完整 findings 导入成功

### Requirement: 任务运行时必须支持迁移报告生成任务
任务运行时 SHALL 支持 `REPORT_GENERATE` 任务类型，用于异步生成迁移评估报告，并 SHALL 允许没有 confirmed 输入的当前源码版本生成全零报告。

#### Scenario: 创建报告生成任务
- **WHEN** 报告 API 创建迁移评估报告
- **THEN** 后端 SHALL 创建类型为 `REPORT_GENERATE` 的任务
- **AND** `tasks.input_snapshot` SHALL 包含 `projectId`、`sourceRevisionId` 和 `reportId`
- **AND** 输入快照 MUST NOT 包含服务器真实报告路径或未脱敏的敏感信息

#### Scenario: 没有 confirmed 输入时生成全零报告
- **WHEN** 当前源码版本没有 confirmed 迁移输入
- **THEN** 后端 SHALL 允许创建并执行 `REPORT_GENERATE` 任务
- **AND** 报告任务 SHALL 生成输入数量和风险统计均为 0 的 HTML 与 JSON 报告
- **AND** 报告任务及报告记录 SHALL 进入 `SUCCEEDED`

#### Scenario: 报告任务状态推送
- **WHEN** 报告生成任务进入 `QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED`、`CANCELLED` 或 `TIMEOUT`
- **THEN** 任务运行时 SHALL 追加对应任务事件
- **AND** 项目级 SSE SHALL 只向该报告所属项目推送事件

#### Scenario: 报告任务因运行时错误失败
- **WHEN** 报告生成 handler 因报告制品保存失败、输入快照写入失败或其他运行时错误而失败
- **THEN** 任务运行时 SHALL 将任务标记为 `FAILED`
- **AND** 任务错误摘要 SHALL 使用脱敏后的业务错误信息

#### Scenario: 取消报告生成任务
- **WHEN** 用户取消尚未完成的 `REPORT_GENERATE` 任务
- **THEN** 任务运行时 SHALL 按现有取消机制更新任务状态
- **AND** 报告记录 SHALL 最终进入 `CANCELLED` 或与任务终态一致的状态

### Requirement: 任务运行时必须支持自动化测试任务
任务运行时 SHALL 支持 `AUTOMATED_TEST` 任务类型，用于异步执行远程自动化测试并回收输出日志。

#### Scenario: 创建自动化测试任务
- **WHEN** 自动化测试 API 创建任务
- **THEN** 任务运行时 SHALL 创建任务类型为 `AUTOMATED_TEST` 的 `tasks` 记录
- **AND** `tasks.input_snapshot` SHALL 包含项目、源码版本、执行环境、可选构建运行、脚本引用、工作目录、超时摘要、环境变量摘要和报告回收目标摘要
- **AND** `tasks.input_snapshot` MUST NOT 包含明文凭据、服务器真实路径或完整日志

#### Scenario: 自动化测试任务事件
- **WHEN** 自动化测试任务进入 `QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED`、`CANCELLED` 或 `TIMEOUT`
- **THEN** 任务运行时 SHALL 追加对应任务事件
- **AND** 项目级 SSE SHALL 只向测试任务所属项目推送事件

#### Scenario: 自动化测试日志事件
- **WHEN** worker 捕获远程测试 stdout/stderr
- **THEN** 任务运行时 SHALL 写入任务日志文件和任务日志事件
- **AND** 日志事件 SHALL 允许前端通过任务日志 SSE 查看运行中输出
- **AND** 日志事件 MUST NOT 包含明文凭据或服务器真实敏感路径

#### Scenario: 自动化测试任务取消
- **WHEN** 用户取消 `AUTOMATED_TEST` 任务
- **THEN** 任务运行时 SHALL 按运行中任务取消机制更新任务状态并投递取消清理消息
- **AND** 取消处理 SHALL 尝试终止正在运行的远程测试命令

### Requirement: 自动化测试 handler 必须通过执行上下文写状态和日志
自动化测试 handler SHALL 复用任务执行上下文更新进度、写日志、写终态和报告错误。

#### Scenario: 更新测试进度
- **WHEN** 自动化测试进入远程执行或结果保存阶段
- **THEN** handler SHALL 通过执行上下文更新任务进度和可展示消息
- **AND** handler SHOULD NOT 直接写入 `tasks` 或 `task_events`

#### Scenario: handler 失败
- **WHEN** 自动化测试 handler 因输入非法、环境不可用、SSH 执行失败或日志保存失败而无法完成
- **THEN** 任务运行时 SHALL 将任务标记为受控终态
- **AND** 错误摘要 MUST 为脱敏后的业务错误信息

### Requirement: 构建和测试任务运行时必须支持任务级环境变量
任务运行时 SHALL 支持构建任务和自动化测试任务携带前端传入的任务级环境变量，并 SHALL 在远程命令或临时脚本执行时注入。

#### Scenario: 构建任务注入环境变量
- **WHEN** 构建任务输入包含 `environmentVariables`
- **THEN** 构建 handler SHALL 在远程构建命令或脚本执行时注入这些变量
- **AND** 变量只作用于当前构建任务
- **AND** 变量注入 MUST 支持空格、引号、等号和其他常见 shell 特殊字符

#### Scenario: 测试任务注入环境变量
- **WHEN** 自动化测试任务输入包含 `environmentVariables`
- **THEN** 自动化测试 handler SHALL 在远程测试命令或脚本执行时注入这些变量
- **AND** 变量只作用于当前测试任务

#### Scenario: 环境变量不泄漏
- **WHEN** 任务运行时保存输入快照、任务事件、日志摘要或错误摘要
- **THEN** 环境变量值 MUST 被脱敏
- **AND** 任务运行时 MUST NOT 将任务级环境变量写入正式环境配置或凭据表
- **AND** 任务运行时 MUST NOT 将环境变量明文写入 RabbitMQ 消息、SSE data 或审计详情

#### Scenario: 构建任务环境变量校验失败
- **WHEN** 构建任务创建阶段发现环境变量输入非法
- **THEN** 任务运行时 MUST NOT 创建可执行构建任务
- **AND** API SHALL 返回统一参数校验错误

#### Scenario: 构建任务执行失败
- **WHEN** 构建 handler 因环境变量注入失败、远程 shell 不支持或 SSH 执行失败而无法完成
- **THEN** 任务运行时 SHALL 将任务标记为 `FAILED`
- **AND** 失败摘要 MUST NOT 包含环境变量明文、远端敏感绝对路径或完整命令输入

### Requirement: 任务运行时必须支持测试结果比较任务类型
任务运行时 SHALL 支持 `TEST_LOG_COMPARE` 长任务类型，用于异步执行测试结果比较。

#### Scenario: 自动创建测试结果比较任务
- **WHEN** 自动化测试节点两侧测试记录完成且比较模型配置可用
- **THEN** 任务类型 SHALL 为 `TEST_LOG_COMPARE`
- **AND** 任务输入 SHALL 只包含比较运行 ID、项目 ID、两条测试运行 ID 和必要路由信息
- **AND** 任务输入 MUST NOT 包含完整日志、完整报告、完整 agent 输入、明文凭据或模型 secret

#### Scenario: 模型不可用时不投递任务
- **WHEN** 自动化测试节点两侧测试记录完成但比较模型未配置或不可用
- **THEN** 后端 SHALL 记录比较不可用摘要
- **AND** 后端 MUST NOT 投递 `TEST_LOG_COMPARE` 任务
- **AND** 自动化测试任务状态 MUST NOT 因比较不可用被改写为失败

#### Scenario: 执行测试结果比较任务
- **WHEN** worker 消费 `TEST_LOG_COMPARE` 任务
- **THEN** worker SHALL 将任务和比较运行状态更新为 `RUNNING`
- **AND** worker SHALL 聚合测试结果并调用 Codex agent
- **AND** worker SHALL 在完成后写回比较运行、差异项和 agent 输出 artifact
- **AND** worker SHALL 在失败时写回受控错误摘要

#### Scenario: 测试结果比较任务事件
- **WHEN** 测试结果比较任务状态或进度变化
- **THEN** 后端 SHALL 追加 `task_events`
- **AND** 项目级 SSE SHALL 只向有权访问该项目的订阅者推送事件

#### Scenario: 取消测试结果比较任务
- **WHEN** `TEST_LOG_COMPARE` 任务被取消
- **THEN** worker SHALL 尝试终止 Codex agent 进程
- **AND** 任务状态 SHALL 更新为 `CANCELLED`
- **AND** 比较运行状态 SHALL 更新为 `CANCELLED`

### Requirement: 任务运行时必须支持依赖分析任务类型
任务运行时 SHALL 支持 `DEPENDENCY_ANALYSIS` 长任务类型，并 SHALL 保留 `SBOM_ANALYSIS` 作为 deprecated 历史任务类型。

#### Scenario: 创建依赖分析任务
- **WHEN** 依赖分析 API 创建任务
- **THEN** 任务运行时 SHALL 创建类型为 `DEPENDENCY_ANALYSIS` 的 `tasks` 记录
- **AND** 任务输入快照 SHALL 包含项目 ID、源码版本 ID、依赖分析运行 ID、source 构建运行 ID、target 环境 ID 和环境配置版本
- **AND** 任务输入快照 MUST NOT 包含完整构建日志、完整脚本、明文凭据或服务器真实路径

#### Scenario: Worker 执行依赖分析 handler
- **WHEN** worker 认领 `DEPENDENCY_ANALYSIS` 任务
- **THEN** worker SHALL 分发到依赖分析 handler
- **AND** handler SHALL 通过任务执行上下文更新进度、写入日志、登记可取消资源并写入终态

#### Scenario: 依赖分析事件
- **WHEN** 依赖分析任务进入 `QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED`、`CANCELLED` 或 `TIMEOUT`
- **THEN** 任务运行时 SHALL 追加任务事件
- **AND** 项目级 SSE SHALL 只向有权访问该项目的连接推送事件

#### Scenario: SBOM 任务类型 deprecated
- **WHEN** 系统遇到历史 `SBOM_ANALYSIS` 任务
- **THEN** 任务运行时 SHALL 保留查询和历史状态展示能力
- **AND** 新迁移主流程 MUST NOT 默认创建 `SBOM_ANALYSIS` 任务

### Requirement: 任务失败必须保留脱敏 root cause
任务运行时 SHALL 在 handler 抛出包含 cause 的业务异常时记录脱敏后的 root cause 信息，使后端日志和任务错误摘要能定位实际失败阶段，同时不泄露敏感信息。

#### Scenario: TaskExecutionException 包含 cause
- **WHEN** worker 捕获到包含 cause 的 `TaskExecutionException`
- **THEN** 后端日志 SHALL 记录任务 ID、任务类型、业务错误码、脱敏业务消息和 cause stack
- **AND** 日志 MUST NOT 包含明文凭据、私钥、token、完整任务输入快照或服务器敏感路径

#### Scenario: 任务失败摘要包含诊断字段
- **WHEN** 任务因 handler 异常进入 `FAILED`
- **THEN** `tasks.error_code` SHALL 保存稳定业务错误码
- **AND** `tasks.error_message` SHALL 保存脱敏后的用户可展示摘要
- **AND** 任务事件或关联业务记录 MAY 保存脱敏后的 `failureStage`、`causeClass` 和 `causeMessage`
- **AND** 这些诊断字段 MUST NOT 暴露明文凭据、私钥、token、完整命令、完整日志或服务器真实路径

#### Scenario: REPORT_GENERATE 失败可定位阶段
- **WHEN** `REPORT_GENERATE` 任务在输入读取、快照写入、报告渲染、artifact 保存或归档候选登记阶段失败
- **THEN** 任务失败摘要或后端日志 SHALL 能区分失败阶段
- **AND** 任务运行时 SHALL 保持任务和报告记录的终态一致

### Requirement: 任务运行时必须支持依赖自动修复任务类型
任务运行时 SHALL 支持 `DEPENDENCY_AUTO_FIX` 长任务类型，并 SHALL 将其纳入现有 worker、日志、事件、SSE、取消和失败处理机制。

#### Scenario: 创建依赖自动修复任务
- **WHEN** 依赖自动修复 API 创建任务
- **THEN** 任务运行时 SHALL 创建类型为 `DEPENDENCY_AUTO_FIX` 的 `tasks` 记录
- **AND** 任务输入快照 SHALL 包含项目 ID、源源码版本 ID、原依赖分析运行 ID、自动修复运行 ID、目标环境 ID 和环境配置版本
- **AND** 任务输入快照 MUST NOT 包含明文凭据、完整安装脚本、完整 SSH 参数或服务器真实路径

#### Scenario: Worker 执行依赖自动修复 handler
- **WHEN** worker 认领 `DEPENDENCY_AUTO_FIX` 任务
- **THEN** worker SHALL 分发到依赖自动修复 handler
- **AND** handler SHALL 通过任务执行上下文更新进度、写入 stdout/stderr/merged 日志、登记可取消资源并写入终态

#### Scenario: 依赖自动修复事件
- **WHEN** 依赖自动修复任务进入 `QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED`、`CANCELLED` 或 `TIMEOUT`
- **THEN** 任务运行时 SHALL 追加任务事件
- **AND** 项目级 SSE SHALL 只向有权访问该项目的连接推送事件

#### Scenario: 取消依赖自动修复任务
- **WHEN** `DEPENDENCY_AUTO_FIX` 任务被取消
- **THEN** worker SHALL 尝试终止当前远端安装命令或关闭 SSH session
- **AND** 自动修复运行和未完成条目 SHALL 更新为 `CANCELLED`
- **AND** 已经安装成功的软件包 MUST NOT 被自动卸载

### Requirement: 任务日志内容响应必须包含总行数
任务运行时 SHALL 在完成态日志内容读取 API 中为每个返回的日志类型提供当前总行数，并 SHALL 在日志写入或落库时维护该总行数。

#### Scenario: 默认读取时返回三类日志总行数
- **WHEN** 已登录用户调用 `GET /api/tasks/{taskId}/log-content` 且不传查询参数
- **THEN** 后端 SHALL 返回 `merged`、`stdout` 和 `stderr` 三类日志内容
- **AND** 每类日志对象 SHALL 包含 `totalLines`
- **AND** `totalLines` SHALL 表示该日志类型在响应时刻的总逻辑行数

#### Scenario: 单类读取时返回该类总行数
- **WHEN** 前端调用 `GET /api/tasks/{taskId}/log-content?types=stdout`
- **THEN** 后端 SHALL 只返回 `stdout` 日志内容
- **AND** `stdout.totalLines` SHALL 表示 stdout 日志总逻辑行数
- **AND** 响应 MUST NOT 包含未请求日志类型的 `totalLines`

#### Scenario: 多类不同 offset 时总行数互不影响
- **WHEN** 前端调用 `GET /api/tasks/{taskId}/log-content?types=merged,stdout,stderr&mergedOffset=0&stdoutOffset=100&stderrOffset=200&lines=100`
- **THEN** 后端 SHALL 按每个日志类型独立返回 `totalLines`
- **AND** `totalLines` MUST NOT 受对应请求 offset 或 lines 影响
- **AND** `hasMore` SHALL 仍基于该日志类型的 offset、lines 和 totalLines 判断是否还有更旧日志

#### Scenario: 运行中日志总行数动态增长
- **WHEN** worker 或任务执行上下文向某个任务日志追加内容并落库
- **THEN** 后端 SHALL 更新该日志类型的总逻辑行数
- **AND** 后续 `log-content` 响应 SHALL 反映更新后的 `totalLines`

#### Scenario: 缺失日志返回零总行数
- **WHEN** 某类日志记录不存在或日志文件不存在
- **THEN** 该类日志响应 SHALL 返回 `totalLines=0`
- **AND** 该类日志响应 SHALL 返回空内容、`returnedLines=0` 和 `hasMore=false`

#### Scenario: 不暴露日志文件实现细节
- **WHEN** 后端返回 `totalLines`
- **THEN** 响应 MUST NOT 暴露服务器真实日志文件路径、文件大小推断路径或未脱敏内容

### Requirement: Worker 必须支持单实例可配置并发消费
任务运行时 SHALL 允许同一个 worker 实例按配置的并发限制启动多个 RabbitMQ consumer，并 SHALL 保持任务认领、ack/nack、重试和取消语义不变。

#### Scenario: 单 worker 并发消费多个任务
- **WHEN** worker 的 `concurrencyLimit` 大于 `1`
- **AND** 任务队列中存在多个可认领任务
- **THEN** worker SHALL 允许多个 consumer 并发认领不同任务
- **AND** 每个任务 SHALL 继续通过 PostgreSQL 原子状态变更从 `PENDING` 或 `QUEUED` 进入 `RUNNING`

#### Scenario: 并发任务独立 ack
- **WHEN** 同一个 worker 内多个任务并发执行
- **THEN** 每条 RabbitMQ 消息 SHALL 在对应任务完成、失败、取消或重试状态可靠写回后独立 ack 或 nack
- **AND** 一个任务失败或重试 MUST NOT 导致其他正在运行任务被 ack、nack 或取消

#### Scenario: 并发任务取消隔离
- **WHEN** 同一个 worker 内多个任务并发运行
- **AND** 用户取消其中一个任务
- **THEN** 取消消息 SHALL 定位到目标 worker
- **AND** worker SHALL 只取消对应 `taskId` 的运行句柄
- **AND** 同 worker 内其他任务 SHALL 继续运行

#### Scenario: 并发限制为零时暂停消费
- **WHEN** worker 读取到 `concurrencyLimit=0`
- **THEN** 任务 listener SHALL 暂停接收新任务消息
- **AND** 已经被 consumer 认领并执行中的任务 SHALL 继续按原生命周期完成

#### Scenario: 动态降低并发不强杀运行任务
- **WHEN** worker 当前运行任务数大于新的 `concurrencyLimit`
- **THEN** worker SHALL 停止或减少后续新任务消费
- **AND** worker MUST NOT 因并发限制降低而强制终止已经运行的任务

### Requirement: Worker 运行模式必须控制任务 listener 启停
任务运行时 SHALL 根据应用运行模式决定是否启动任务队列 listener，避免 API-only 节点消费任务。

#### Scenario: API 模式不消费任务
- **WHEN** 后端以 API 模式启动
- **THEN** 任务队列 listener MUST NOT 启动
- **AND** 该节点 MUST NOT 注册为可执行任务的 worker

#### Scenario: Worker 模式消费任务
- **WHEN** 后端以 worker 模式启动
- **THEN** 任务队列 listener SHALL 启动
- **AND** 该节点 SHALL 注册 worker 实例并上报心跳

#### Scenario: Combined 模式兼容本地开发
- **WHEN** 后端以 combined 模式启动
- **THEN** 后端 MAY 同时启动 HTTP API 和任务队列 listener
- **AND** 该模式 SHALL 保持本地开发可用

### Requirement: 项目级 SSE 必须支持跨进程事件广播
任务运行时 SHALL 在 API 进程和 worker 进程分离部署时，通过跨进程事件广播将已持久化的项目事件通知到持有浏览器 SSE 连接的 API 进程。

#### Scenario: worker 事件跨进程推送到浏览器
- **WHEN** worker 进程写入 `task.queued`、`task.started`、`task.progress`、`task.log`、`task.succeeded` 或 `task.failed` 项目事件
- **AND** 浏览器 SSE 连接存在于 API 进程
- **THEN** worker 进程 SHALL 在事务提交后发布跨进程事件通知
- **AND** API 进程 SHALL 接收该通知并从数据库读取对应 `task_events` 记录
- **AND** API 进程 SHALL 通过本进程 `TaskSseBroker` 将事件推送给该项目的 SSE 连接

#### Scenario: 项目上下文事件跨进程推送
- **WHEN** worker 进程因 Git 同步、源码激活、环境变更或其他异步流程写入 `project.context.changed`
- **THEN** 后端 SHALL 在事务提交后发布跨进程事件通知
- **AND** API 进程 SHALL 将该上下文变更事件推送给对应项目的 SSE 连接
- **AND** 推送内容 SHALL 继续遵守项目事件轻量载荷和敏感信息边界

#### Scenario: Redis 广播只携带最小引用
- **WHEN** 后端发布项目事件跨进程通知
- **THEN** 通知 payload SHALL 只包含定位事件所需的最小引用信息
- **AND** payload SHALL 至少包含 `eventId` 和 `projectId`
- **AND** payload MUST NOT 包含完整业务结果、完整日志、服务器真实路径、明文凭据、token 或私钥

#### Scenario: 数据库仍为项目事件事实来源
- **WHEN** API 进程收到项目事件跨进程通知
- **THEN** API 进程 SHALL 根据 `eventId` 和 `projectId` 从数据库重读事件
- **AND** API 进程 MUST NOT 直接信任 Redis payload 作为 SSE 业务数据
- **AND** 事件不存在、项目不匹配或读取失败时 SHALL 记录脱敏 warning 并丢弃该通知

#### Scenario: Redis 广播失败不改变任务终态
- **WHEN** 任务事件已经成功写入数据库
- **AND** Redis 跨进程通知发布失败
- **THEN** 任务状态和事件事实 SHALL 保持已写入结果
- **AND** 后端 MUST NOT 因 SSE 广播失败将任务标记为失败
- **AND** 后端 SHALL 记录脱敏 warning 供运维排查

#### Scenario: SSE 游标语义保持不变
- **WHEN** 跨进程广播启用
- **THEN** 项目级 SSE SHALL 继续支持既有 `afterEventId`、`Last-Event-ID` 和无游标 baseline 语义
- **AND** 断线恢复 SHALL 继续从数据库按当前 `projectId` 查询游标之后的事件
- **AND** 后端 MUST NOT 将 Redis Pub/Sub 作为历史事件补发来源

### Requirement: 任务级日志 SSE 必须支持跨进程日志广播
任务运行时 SHALL 在 API 进程和 worker 进程分离部署时，通过跨进程日志事件广播将已持久化的任务日志事件通知到持有任务日志 SSE 连接的 API 进程。

#### Scenario: worker 日志事件跨进程推送到浏览器
- **WHEN** worker 进程写入 `task_log_events`
- **AND** 浏览器任务级日志 SSE 连接存在于 API 进程
- **THEN** worker 进程 SHALL 在事务提交后发布跨进程日志事件通知
- **AND** API 进程 SHALL 接收该通知并从数据库读取对应日志事件
- **AND** API 进程 SHALL 通过本进程 `TaskLogSseBroker` 将日志事件推送给该任务的日志 SSE 连接

#### Scenario: 日志广播只携带最小引用
- **WHEN** 后端发布任务日志事件跨进程通知
- **THEN** 通知 payload SHALL 至少包含 `eventId` 和 `taskId`
- **AND** payload MUST NOT 包含日志内容片段、完整 stdout、完整 stderr、服务器真实路径、明文凭据、token 或私钥

#### Scenario: 数据库仍为日志事件事实来源
- **WHEN** API 进程收到任务日志事件跨进程通知
- **THEN** API 进程 SHALL 根据 `eventId` 和 `taskId` 从数据库重读日志事件
- **AND** API 进程 MUST NOT 直接信任 Redis payload 作为日志 SSE 数据
- **AND** 日志事件不存在、任务不匹配或读取失败时 SHALL 记录脱敏 warning 并丢弃该通知

#### Scenario: 日志广播失败不改变任务状态
- **WHEN** 日志事件已经成功写入数据库
- **AND** Redis 跨进程通知发布失败
- **THEN** 日志事件事实 SHALL 保持已写入结果
- **AND** 后端 MUST NOT 因日志 SSE 广播失败将任务标记为失败
- **AND** 用户重新订阅任务级日志 SSE 时 SHALL 能从数据库补发已有日志事件

### Requirement: SSE 跨进程订阅必须按运行模式启用
任务运行时 SHALL 只在能够持有 HTTP SSE 连接的进程中启用 Redis SSE 事件订阅，并允许 worker-only 进程仅发布跨进程通知。

#### Scenario: API 模式启用订阅
- **WHEN** 后端以 API 模式或 combined 模式启动
- **THEN** 后端 SHALL 启用项目事件和任务日志事件的 Redis 订阅
- **AND** 收到通知后 SHALL 通过本进程 SSE broker 推送给本地 emitter

#### Scenario: worker 模式不启用订阅
- **WHEN** 后端以 worker 模式启动
- **THEN** 后端 MUST NOT 启用项目事件或任务日志事件的 Redis 订阅推送
- **AND** worker 进程 SHALL 仍能在事件落库后发布跨进程通知

#### Scenario: 无本地订阅者时丢弃通知
- **WHEN** API 进程收到跨进程事件通知
- **AND** 本进程没有对应项目或任务的 SSE emitter
- **THEN** 后端 SHALL 安静丢弃该通知或仅记录 debug 日志
- **AND** 后端 MUST NOT 因无本地 emitter 读取完整历史事件或创建补偿任务

### Requirement: 环境任务必须绑定环境配置版本并阻止旧任务回写
会读取或更新环境当前事实的任务 SHALL 在创建时绑定环境配置版本，并 SHALL 防止旧配置版本任务晚到完成后覆盖当前状态。

#### Scenario: 创建环境连接测试任务
- **WHEN** 后端为已保存环境创建连接测试任务
- **THEN** 任务输入 SHALL 保存任务创建时的 `environmentConfigVersion`
- **AND** Worker SHALL 使用该版本执行完成回写校验

#### Scenario: 创建环境采集任务
- **WHEN** 后端为已保存环境创建基础信息或依赖信息采集任务
- **THEN** 任务输入 SHALL 保存任务创建时的 `environmentConfigVersion`
- **AND** 新建环境快照 SHALL 保存相同配置版本

#### Scenario: 当前版本任务完成
- **WHEN** 环境任务完成时其 `environmentConfigVersion` 等于环境当前 `configVersion`
- **THEN** 后端 MAY 更新环境当前状态、连接时间、采集时间和当前快照引用
- **AND** 后端 SHALL 按任务结果追加正常生命周期事件

#### Scenario: 旧版本任务晚到成功
- **WHEN** 环境任务完成时其 `environmentConfigVersion` 小于环境当前 `configVersion`
- **THEN** 后端 SHALL 保留任务、日志和历史快照
- **AND** 后端 MUST NOT 更新环境当前状态、连接时间、采集时间或当前基础信息
- **AND** 后端 MUST NOT 使项目当前 `env` 节点变为 `DONE`

#### Scenario: 旧版本任务晚到失败
- **WHEN** 旧配置版本环境任务在配置更新后失败
- **THEN** 后端 SHALL 保存该任务失败事实用于历史审计
- **AND** 后端 MUST NOT 将当前环境状态改为不可用或将当前 Workflow 节点标记为该旧失败导致的 `BLOCKED`

### Requirement: 任务运行时必须提供轻量日志变化通知 SSE
任务运行时 SHALL 提供按任务订阅的日志通知 SSE，并 MUST 只发送日志变化、heartbeat 和任务终态通知，不得通过该 SSE 发送日志正文。

#### Scenario: 运行中任务出现新增日志
- **WHEN** 已授权用户订阅任务日志通知，且该任务产生了游标之后的新日志事件
- **THEN** 后端 SHALL 在最多一个 5 秒检查周期内发送 `task.log.updated`
- **AND** 事件 SHALL 包含任务 ID、最新日志事件 ID 和当前任务状态
- **AND** 事件 MUST NOT 包含 stdout、stderr、完整日志文件内容或服务器路径

#### Scenario: 没有新增日志
- **WHEN** 任务日志最大事件 ID 和任务状态在检查周期内均未变化
- **THEN** 后端 MUST NOT 发送 `task.log.updated`
- **AND** 后端 SHALL 继续发送 heartbeat 保持连接

#### Scenario: 同一任务存在多个连接
- **WHEN** 同一 API 进程持有同一任务的多个日志通知 SSE 连接
- **THEN** 后端 SHALL 按 taskId 复用一次周期检查结果
- **AND** 后端 SHALL 将变化通知广播给该任务的全部有效连接

### Requirement: 日志通知 SSE 必须正确处理任务终态
任务运行时 SHALL 从共享数据库读取任务终态，并 SHALL 在终态通知中提供最终日志游标后关闭连接。

#### Scenario: 运行中任务进入终态且存在最终日志
- **WHEN** 周期检查同时发现任务产生了新增日志并进入 `SUCCEEDED`、`FAILED`、`CANCELLED` 或 `TIMEOUT`
- **THEN** 后端 SHALL 先发送 `task.log.updated`
- **AND** 后端 SHALL 再发送 `task.log.completed`
- **AND** 终态事件 SHALL 包含任务状态和最新日志事件 ID
- **AND** 后端 SHALL 在发送终态事件后完成 SSE 连接

#### Scenario: 订阅已经终态的任务
- **WHEN** 已授权用户订阅已经进入终态的任务
- **THEN** 后端 SHALL 立即发送 `task.log.completed`
- **AND** 后端 MUST NOT 等待下一个 5 秒检查周期
- **AND** 后端 SHALL 随后完成 SSE 连接

#### Scenario: 任务重试时保持连接
- **WHEN** 任务执行失败后重新进入 `QUEUED` 并等待下一次尝试
- **THEN** 日志通知 SSE SHALL 保持连接
- **AND** 后端 MUST NOT 将重试状态当作终态发送 completed 事件

### Requirement: 任务运行时必须提供稳定游标的日志增量读取
任务运行时 SHALL 使用 `task_log_events.id` 作为任务日志的前向增量游标，并 SHALL 将一批事件聚合为 merged、stdout 和 stderr 内容。

#### Scenario: 按游标读取新增日志
- **WHEN** 用户请求某任务 `id > afterEventId` 的日志增量
- **THEN** 后端 SHALL 只查询该任务的日志事件并按 ID 升序处理
- **AND** stdout 和 stderr SHALL 分别包含对应类型内容
- **AND** merged SHALL 按事件 ID 顺序包含两类内容
- **AND** 响应 SHALL 返回本批实际消费到的 `latestEventId`

#### Scenario: 增量超过单批上限
- **WHEN** 游标之后的日志超过事件条数或响应内容字节数上限
- **THEN** 后端 SHALL 只返回不超过上限的完整事件
- **AND** 响应 SHALL 返回 `hasMore=true`
- **AND** 客户端 SHALL 能使用本批 `latestEventId` 继续读取而不重复或遗漏事件

#### Scenario: 游标之后没有日志
- **WHEN** 当前任务不存在 ID 大于 `afterEventId` 的日志事件
- **THEN** 后端 SHALL 返回空的 merged、stdout 和 stderr 内容
- **AND** 响应 SHALL 返回 `hasMore=false`

#### Scenario: 全局事件 ID 中夹有其他任务事件
- **WHEN** 其他任务在当前任务两个日志事件之间写入了全局自增日志事件 ID
- **THEN** 当前任务的增量读取 MUST NOT 返回其他任务的事件或内容
- **AND** 当前任务 SHALL 仍能继续使用其最后实际返回的事件 ID 读取后续内容

### Requirement: 日志恢复必须以增量读取成功游标为准
任务运行时 MUST NOT 将 SSE 通知送达视为日志正文已消费，前端恢复 SHALL 以最后一次成功增量响应的事件游标为准。

#### Scenario: 收到更新通知后增量请求失败
- **WHEN** 前端收到 `task.log.updated` 但增量日志请求失败或连接中断
- **THEN** 前端 SHALL 保留此前成功消费的游标
- **AND** 重新建立日志页连接后 SHALL 再次从该游标拉取增量

#### Scenario: 收到终态通知后仍有未读取日志
- **WHEN** `task.log.completed.latestEventId` 大于前端最后成功消费的事件 ID
- **THEN** 前端 SHALL 在结束动态日志展示前继续读取剩余增量

### Requirement: 旧日志正文 SSE 必须提供迁移期兼容
任务运行时 SHALL 在迁移期保留 `GET /api/tasks/{taskId}/log-events`，并 SHALL 将其标记为废弃，新的调用方必须使用日志通知 SSE 与增量读取 API。

#### Scenario: 旧调用方仍订阅日志正文 SSE
- **WHEN** 已有调用方请求旧 `/log-events` 路由
- **THEN** 后端 SHALL 在本 change 中保持旧接口可用
- **AND** API 文档 SHALL 指示新调用方改用新接口

#### Scenario: 完成态历史日志翻页
- **WHEN** 前端调用 `GET /api/tasks/{taskId}/log-content`
- **THEN** 后端 SHALL 保持现有完成态日志历史分页契约
- **AND** 新增量接口 MUST NOT 改变该接口的 offset 语义

### Requirement: 任务运行时必须支持两类专项分析任务
任务运行时 SHALL 支持 `FLOATING_POINT_ANALYSIS` 与 `MEMORY_ORDER_ANALYSIS`，并 SHALL 返回领域 resource、stageId、源码版本和运行记录关联。

#### Scenario: 创建浮点任务
- **WHEN** 浮点分析 API 创建任务
- **THEN** 任务 SHALL 使用 `stageId=floating-point-analysis`
- **AND** resource SHALL 指向浮点分析运行记录

#### Scenario: 创建内存序任务
- **WHEN** 内存序分析 API 创建任务
- **THEN** 任务 SHALL 使用 `stageId=memory-order-analysis`
- **AND** resource SHALL 指向内存序分析运行记录

### Requirement: 专项分析必须支持日志、取消和跨进程事件
Worker SHALL 通过现有任务日志、取消和项目事件机制运行两个工具，并 SHALL 在 API/Worker 分离部署时通过 Redis 向项目 SSE 广播生命周期变化。

#### Scenario: 运行中输出日志
- **WHEN** 工具向 stdout 或 stderr 输出进度和诊断
- **THEN** Worker SHALL 保存 merged/stdout/stderr 日志
- **AND** SHALL 发布 task.log 或日志可用通知

#### Scenario: 取消内存序分析
- **WHEN** 用户取消正在运行的内存序任务
- **THEN** Worker SHALL 写入 cancel file 并等待协作停止
- **AND** 超过宽限期后 SHALL 终止进程树并写入 CANCELLED

#### Scenario: 风险退出码不触发失败重试
- **WHEN** memory-order 返回协议定义的风险退出码 3
- **THEN** Worker SHALL 将任务标记成功
- **AND** RabbitMQ MUST NOT 重投该消息

### Requirement: 专项分析 latest 必须优先返回运行中任务
领域 latest 与项目 overview SHALL 在当前源码版本存在排队或运行中任务时返回该活动状态，而不是旧的成功或失败结果。

#### Scenario: 旧成功后启动重跑
- **WHEN** 当前源码版本已有成功运行并启动新的同类分析
- **THEN** latest SHALL 返回新运行的 QUEUED 或 RUNNING 状态
- **AND** 旧运行仍可通过历史列表查询

### Requirement: 任务执行写入必须校验当前尝试和终态
系统 SHALL 使用持久化执行尝试身份对状态迁移及业务发布实施原子校验。失联、取消、超时或其他最终终态之后，旧执行 MUST NOT 更新任务为成功或排队、发布当前业务结果或触发后续任务。

#### Scenario: 失联 Worker 迟到成功
- **WHEN** Task 已因 WORKER_LOST 失败，原 Worker 返回成功
- **THEN** 系统 SHALL 拒绝其 Task、领域结果、当前引用、迁移输入及归档候选发布，MUST NOT 发送 task.succeeded

#### Scenario: 迟到失败和日志
- **WHEN** 已失效执行尝试请求失败重试、进度更新或日志追加
- **THEN** 系统 MUST NOT 恢复 QUEUED、覆盖终态原因或改变已结束任务的最终日志游标

#### Scenario: 发布与失联并发
- **WHEN** 业务结果提交和失联收尾竞争同一 Task
- **THEN** 数据库 SHALL 串行化归属校验与写入，失联先提交则拒绝旧发布，已提交的终态领域结果 SHALL 保留，MUST NOT 以写入前单次查询替代原子保护

### Requirement: 执行隔离必须保留正常重试语义
系统 SHALL 区分尝试失败与 Task 最终失败，仅允许有效当前尝试按重试策略重新排队，下一尝试具有不同执行身份。

#### Scenario: 可重试异常未达到上限
- **WHEN** 当前 RUNNING 尝试发生可重试异常且未达到次数上限
- **THEN** 系统 SHALL 正常重新排队并允许下一尝试启动，MUST NOT 因本次尝试失败执行最终失联收尾，旧尝试 MUST NOT 写入下一尝试结果

#### Scenario: 已最终失败后重新执行
- **WHEN** 用户重新运行已失联失败的任务对应业务
- **THEN** 系统 SHALL 创建新的 Task 并保留旧任务终态，不复活旧执行身份

### Requirement: 领域终态一致性必须有独立测试与并发验证
实现 SHALL 为全部新增和受影响自有逻辑补充正常、异常、边界独立单测，mock 外部连接返回值，并在 Linux Docker 执行含未加载源码的整体及受影响模块覆盖率门禁，行及支持的分支 SHALL 至少 90%。

#### Scenario: 完整验收
- **WHEN** 提交本次实现验收
- **THEN** 验证 SHALL 包含各领域适配器、正常重试、重复清理、历史修复、事务回滚、迟到完成及并发竞争，并另行验证 API/Worker 分离部署的状态与 SSE 一致性

#### Scenario: 未达标
- **WHEN** 测试失败、覆盖率不足或必要链路未验证
- **THEN** 任务 SHALL 保持未完成，记录命令、统计范围、排除依据、报告和限制，MUST NOT 以 E2E 或编译成功替代单测
