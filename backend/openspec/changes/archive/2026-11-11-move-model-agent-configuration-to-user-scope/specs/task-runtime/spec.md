## MODIFIED Requirements

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
