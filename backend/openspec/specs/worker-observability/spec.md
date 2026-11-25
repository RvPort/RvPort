## Purpose

定义 worker 可观测性和并发控制能力的行为约束，确保管理员能够查看自动接入的 worker、当前运行任务以及每个 worker 的最大并发配置。

## Requirements
### Requirement: Worker 实例必须自动注册并上报心跳
后端 worker 节点 SHALL 在启动后自动注册 worker 实例，并 SHALL 周期性上报心跳、并发状态和当前运行任务数量。

#### Scenario: Worker 启动注册实例
- **WHEN** worker 节点以 worker 或 combined 模式启动
- **THEN** 后端 SHALL 生成或读取稳定的 `workerInstanceId`
- **AND** 后端 SHALL 在 `worker_instances` 中 upsert 对应实例记录
- **AND** 实例记录 SHALL 包含主机名、进程 ID、应用版本、启动时间、并发限制、实际并发和最近心跳时间

#### Scenario: Worker 周期性心跳
- **WHEN** worker 节点处于运行状态
- **THEN** 后端 SHALL 按配置的心跳间隔更新该 worker 的 `lastHeartbeatAt`
- **AND** 后端 SHALL 更新 `activeConcurrency` 和 `activeTaskCount`
- **AND** 心跳写入失败 MUST NOT 直接中断当前正在执行的任务

#### Scenario: Worker 离线判定
- **WHEN** 管理员查询 worker 列表或详情
- **AND** 某 worker 的最近心跳时间超过配置的离线阈值
- **THEN** API SHALL 将该 worker 展示为 `OFFLINE`
- **AND** API MUST NOT 将没有心跳的 worker 继续展示为可接收新任务

### Requirement: Worker 当前任务必须可观测
后端 SHALL 允许超级管理员查看每个 worker 当前正在执行的任务，并 SHALL 通过任务运行句柄和任务记录提供可审计摘要。

#### Scenario: 查询 worker 运行任务
- **WHEN** 超级管理员查询某个 worker 详情
- **THEN** API SHALL 返回该 worker 当前关联的运行中任务列表
- **AND** 每个任务摘要 SHALL 包含任务 ID、项目 ID、项目名称、任务类型、任务状态、进度、消息、开始时间和 runtime handle ID

#### Scenario: Worker 无运行任务
- **WHEN** 超级管理员查询在线但没有运行任务的 worker
- **THEN** API SHALL 返回空运行任务列表
- **AND** `activeTaskCount` SHALL 为 `0`

#### Scenario: 已离线 worker 的历史运行句柄
- **WHEN** 超级管理员查询离线 worker
- **THEN** API MAY 返回仍未完成清理的历史 runtime handle 摘要
- **AND** API SHALL 明确标记 worker 为 `OFFLINE`

### Requirement: 管理员必须能控制单个 worker 的最大并发
后端 SHALL 允许超级管理员为单个 worker 设置任务消费并发限制，并 SHALL 由目标 worker 周期性拉取后动态应用。

#### Scenario: 设置 worker 并发限制
- **WHEN** 超级管理员调用 worker 并发设置 API 并提交合法 `concurrencyLimit`
- **THEN** 后端 SHALL 更新该 worker 的并发限制
- **AND** 目标 worker SHALL 在后续心跳周期内读取并应用新的并发限制
- **AND** API SHALL 返回更新后的期望并发限制

#### Scenario: 升高 worker 并发限制
- **GIVEN** 目标 worker 当前实际并发为 `1`
- **WHEN** 超级管理员将该 worker 的 `concurrencyLimit` 设置为 `4`
- **THEN** 目标 worker MUST 在应用 Spring AMQP listener 配置时避免 `concurrentConsumers` 大于 `maxConcurrentConsumers`
- **AND** 目标 worker SHALL 将实际任务 consumer 并发调整为 `4`
- **AND** listener 的 `prefetch` MUST 仍为 `1`

#### Scenario: 降低 worker 并发限制
- **GIVEN** 目标 worker 当前实际并发为 `4`
- **WHEN** 超级管理员将该 worker 的 `concurrencyLimit` 设置为 `1`
- **THEN** 目标 worker MUST 在应用 Spring AMQP listener 配置时避免 `concurrentConsumers` 与 `maxConcurrentConsumers` 的校验错误
- **AND** 目标 worker SHALL 将实际任务 consumer 并发调整为 `1`
- **AND** listener 的 `prefetch` MUST 仍为 `1`

#### Scenario: 并发限制为零
- **WHEN** 超级管理员将某个 worker 的 `concurrencyLimit` 设置为 `0`
- **THEN** 目标 worker SHALL 暂停接收新的任务队列消息
- **AND** 已经运行中的任务 SHALL 继续执行，除非用户通过任务取消 API 显式取消

#### Scenario: 并发限制恢复为正数
- **WHEN** 超级管理员将某个 worker 的 `concurrencyLimit` 从 `0` 设置为正数
- **THEN** 目标 worker SHALL 恢复接收任务队列消息
- **AND** 实际并发 SHALL 不超过新的 `concurrencyLimit`

#### Scenario: 并发限制超过上限
- **WHEN** 超级管理员提交超过系统 `maxConcurrencyLimit` 的并发限制
- **THEN** 后端 SHALL 返回统一业务错误
- **AND** 后端 MUST NOT 更新该 worker 的并发限制

#### Scenario: 普通用户不能管理 worker
- **WHEN** 非超级管理员调用 worker 列表、详情或并发设置 API
- **THEN** 后端 SHALL 拒绝请求
- **AND** 响应 MUST NOT 泄露 worker 实例、任务或主机信息

### Requirement: Worker prefetch 必须固定为一
后端 worker SHALL 将 RabbitMQ 任务 listener 的 prefetch 固定为 `1`，并 MUST NOT 将其作为可配置、可展示或可编辑字段暴露。

#### Scenario: Worker listener 使用固定 prefetch
- **WHEN** worker 启动任务队列 listener
- **THEN** listener container SHALL 使用 `prefetch=1`
- **AND** 每个 consumer 同时最多持有一条未确认任务消息

#### Scenario: 管理员接口不暴露 prefetch
- **WHEN** 超级管理员查询 worker 列表、worker 详情或更新 worker 并发
- **THEN** 响应 MUST NOT 包含 `prefetchCount`
- **AND** 请求 MUST NOT 接受 `prefetchCount` 作为可修改字段
### Requirement: 失效 Worker 必须按阶段自动收敛和清理
后端 SHALL 周期性检查 Worker 心跳，并 SHALL 将短时离线、确认失联和超过保留期的 Worker 作为不同生命周期阶段处理。

#### Scenario: 短时离线 Worker 不被删除
- **WHEN** Worker 的最近心跳超过离线阈值但尚未超过失联确认时间
- **THEN** 管理 API SHALL 将该 Worker 展示为 `OFFLINE`
- **AND** 后端 MUST NOT 将其运行中任务标记为失败
- **AND** 后端 MUST NOT 删除该 Worker 注册记录

#### Scenario: 确认失联 Worker 的运行句柄被收敛
- **WHEN** Worker 的最近心跳超过失联确认时间
- **AND** 该 Worker 存在状态为 `ACTIVE`、`CANCEL_REQUESTED` 或 `CANCELLING` 的 runtime handle
- **THEN** 后端 SHALL 将这些 runtime handle 标记为 `WORKER_LOST`
- **AND** SHALL 记录终止时间和不包含凭据、命令输入或服务器真实路径的安全错误摘要

#### Scenario: 确认失联 Worker 的运行任务失败
- **WHEN** 被标记为 `WORKER_LOST` 的 runtime handle 对应任务仍为 `RUNNING`
- **THEN** 后端 SHALL 将任务更新为 `FAILED`
- **AND** SHALL 使用统一错误码 `WORKER_LOST`
- **AND** SHALL 设置任务完成时间
- **AND** SHALL 追加一次 `task.failed` 事件并发布对应项目级 SSE 状态变化
- **AND** SHALL 清理该任务的临时 secret payload

#### Scenario: 已终态任务不被失联清理覆盖
- **WHEN** 失联 Worker 的 runtime handle 对应任务已经是 `SUCCEEDED`、`FAILED`、`CANCELLED` 或 `TIMEOUT`
- **THEN** 后端 MUST NOT 修改任务状态或重复发布终态事件
- **AND** 后端 MAY 将仍为非终态的 runtime handle 收敛为 `WORKER_LOST`

#### Scenario: 超过保留期的 Worker 被删除
- **WHEN** Worker 最近心跳超过配置的记录保留期
- **AND** 该 Worker 不存在非终态 runtime handle
- **THEN** 后端 SHALL 删除该 `worker_instances` 注册记录
- **AND** MUST NOT 删除或修改历史任务、任务事件、日志和终态 runtime handle

#### Scenario: 存在非终态句柄时禁止删除 Worker
- **WHEN** Worker 最近心跳超过记录保留期
- **AND** 该 Worker 仍存在 `ACTIVE`、`CANCEL_REQUESTED` 或 `CANCELLING` runtime handle
- **THEN** 后端 MUST NOT 删除该 Worker 注册记录
- **AND** SHALL 优先尝试收敛相关句柄和任务

### Requirement: 失效 Worker 清理必须支持并发幂等
后端 SHALL 允许多个应用实例执行清理调度，并 MUST 保证同一 Worker、runtime handle 和任务不会被重复终止或生成重复事件。

#### Scenario: 多个调度器同时处理同一 Worker
- **WHEN** 两个或更多调度器同时选择同一个失联 Worker
- **THEN** 仅一个处理流程 SHALL 完成对应 runtime handle 和任务的终态迁移
- **AND** 每个任务 MUST 最多新增一次由本次失联收敛产生的 `task.failed` 事件
- **AND** 重复执行清理 SHALL 得到相同的最终状态

#### Scenario: 清理期间 Worker 恢复心跳
- **WHEN** Worker 在候选查询后、状态迁移提交前恢复有效心跳
- **THEN** 后端 SHALL 在提交前重新检查最近心跳
- **AND** MUST NOT 将已恢复 Worker 的任务标记为 `WORKER_LOST`
- **AND** MUST NOT 删除该 Worker 注册记录

#### Scenario: 单个 Worker 清理失败
- **WHEN** 某个失联 Worker 因数据库或事件处理异常未能完成收敛
- **THEN** 后端 SHALL 记录脱敏错误日志
- **AND** SHALL 继续处理本批次中的其他候选 Worker
- **AND** 后续调度 SHALL 能够幂等重试该 Worker

### Requirement: Worker 清理策略必须可配置且有界
后端 SHALL 提供 Worker 清理开关、调度周期、失联确认时间、记录保留时间和批量大小配置，并 SHALL 使用安全默认值限制单轮清理负载。

#### Scenario: 使用默认清理策略
- **WHEN** 部署未显式设置 Worker 清理配置
- **THEN** 后端 SHALL 默认启用清理
- **AND** SHALL 使用 300 秒失联确认时间、24 小时记录保留时间、300000 毫秒调度周期和 100 条批量上限
- **AND** 现有离线展示阈值 SHALL 保持 60 秒

#### Scenario: 禁用自动清理
- **WHEN** `rvport.worker.cleanup.enabled` 被设置为 `false`
- **THEN** 后端 MUST NOT 自动收敛或删除失效 Worker
- **AND** 现有 Worker 心跳和离线查询行为 SHALL 保持可用

#### Scenario: 清理配置时间边界非法
- **WHEN** 失联确认时间不大于离线阈值，或记录保留时间不大于失联确认时间
- **THEN** 后端 SHALL 拒绝使用该配置启动清理调度
- **AND** SHALL 输出不包含敏感信息的明确配置错误

#### Scenario: 单轮候选超过批量上限
- **WHEN** 待收敛或待删除 Worker 数量超过配置的批量大小
- **THEN** 后端 SHALL 在当前调度周期内最多处理配置数量的候选
- **AND** 剩余候选 SHALL 由后续调度继续处理

### Requirement: Worker 失联收尾必须同步关联领域记录
系统 SHALL 在确认当前执行 Worker 失联时，同一数据库事务内结束 Task 和关联的非终态领域运行记录，并保留错误码 WORKER_LOST、安全原因和结束时间。系统 MUST NOT 依赖失联 Worker 的 handler 回调。

#### Scenario: 依赖分析运行中失联
- **WHEN** 当前执行句柄所属 Worker 确认失联且 Task 为 RUNNING
- **THEN** Task 和关联的非终态依赖分析记录 SHALL 变为 FAILED，overview 与 latest 的活动状态 SHALL 收敛，失败原因 SHALL 表明执行结果未知

#### Scenario: 其他任务和多运行记录
- **WHEN** 失联 Task 关联分析、构建、测试、报告或其他领域运行记录
- **THEN** 注册适配器 SHALL 按 taskId 收尾所有关联非终态记录，MUST NOT 更新其他 Task 的运行记录或覆盖已有终态

#### Scenario: 并发清理或领域写入失败
- **WHEN** 多个清理器处理同一 Task，或领域更新抛出数据库异常
- **THEN** 系统 SHALL 保证成功迁移仅发生一次，异常事务整体回滚并可重试，MUST NOT 提交一半状态或重复终态事件

#### Scenario: 心跳恢复或已失去执行归属
- **WHEN** 清理提交前 Worker 已恢复有效心跳，或候选句柄不属于 Task 当前执行尝试
- **THEN** 清理 SHALL 跳过不适用的 Task 终态迁移，MUST NOT 结束其他有效执行

### Requirement: 历史非终态领域记录必须能够幂等收敛
系统 SHALL 提供独立于 Worker 注册记录是否存在的有界周期对账，修复 FAILED、CANCELLED 或 TIMEOUT Task 关联的非终态领域记录，使用原终态原因及可用结束时间，保留历史结果。

#### Scenario: Worker 已经被删除
- **WHEN** Worker 不存在或所有句柄已为终态，但 Task 失败且领域记录仍运行中
- **THEN** 对账 SHALL 找到并修复该记录，不依赖重新触发失联判定

#### Scenario: 重复执行和已成功结果
- **WHEN** 对账重复执行，或关联领域记录已经成功或部分成功
- **THEN** 系统 MUST NOT 改写终态结果、删除制品、重复追加 task.failed 或将成功 Task 的非终态结果伪造成成功

#### Scenario: 分批失败和禁用
- **WHEN** 候选超过批量限制、单项修复失败或 cleanup 被禁用
- **THEN** 系统 SHALL 分批处理并隔离单项失败、记录审计计数，禁用时 MUST NOT 自动修改记录

#### Scenario: 修复后的查询和通知
- **WHEN** 非终态领域记录修复提交成功
- **THEN** 系统 SHALL 发布既有节点变化通知，使 latest、overview 和 readiness 使用修复后的事实；通知失败 MUST NOT 改回数据库状态

### Requirement: Worker 必须上报有时效的报告 AI 执行能力
Worker SHALL 在实际执行容器、身份和安全策略下对报告 AI 沙箱进行有界无凭据自检，并通过既有心跳持久化 runtime/版本及策略身份、能力状态、观测时间与有效期；MUST NOT 调用模型或修改被分析源码。

#### Scenario: 能力观测成功或明确不支持
- **WHEN** 本地能力自检确认支持沙箱或必要系统能力不可用
- **THEN** SHALL 上报真实支持/不支持及稳定脱敏原因，API 不以自身容器能力替代 Worker 事实，不将瞬时工作繁忙表达为缺少沙箱。

#### Scenario: 过期或未探测
- **WHEN** 心跳缺能力字段、自检超时、版本/策略不符或观测过期
- **THEN** SHALL 作为 UNKNOWN，不延长旧探测时间冒充新结果；自检有时限，不长期阻塞心跳和普通任务。

#### Scenario: 多 Worker 聚合
- **WHEN** 查询同队列且有领取资格的在线 Worker 能力
- **THEN** SHALL 全部支持时返回 AVAILABLE，全部明确不支持时返回 UNAVAILABLE；无在线实例、未知、过期或混合支持时返回 UNKNOWN 和适当提示，不暴露主机名、凭据或内部路径。
