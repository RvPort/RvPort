## ADDED Requirements

### Requirement: 领取失败耗尽投递重试必须可靠收敛
任务运行时 SHALL 在 Worker 注册提交后启动消费，并为领取阶段异常提供有界重试和可靠终态收敛；MUST NOT 留下可识别死信任务永久 QUEUED 的状态。

#### Scenario: 完整重启流程
- **WHEN** Worker 停止期间创建报告任务，API 重启后同任务仍排队，再启动 Worker
- **THEN** SHALL 无需人工重投即可完成处理或以稳定明确原因终止，注册未提交时不得启动消费者。

#### Scenario: 领取错误耗尽预算
- **WHEN** 消息重试耗尽且任务仍为 PENDING/QUEUED、无有效领取者
- **THEN** SHALL 原子标记 FAILED/TASK_DELIVERY_EXHAUSTED，同步关联领域记录、活动生成占位、终态事件及敏感临时数据清理，提交后才确认处理；原有效报告保留。

#### Scenario: 数据库不可用或恢复进程重启
- **WHEN** 最终收尾暂时不能写数据库，或提交/确认之间进程重启
- **THEN** SHALL 将消息可靠保留于既有持久队列/死信恢复路径，有界退避后自动重试，数据库恢复后幂等收敛，不热循环或静默丢弃。

#### Scenario: 重复投递与并发领取
- **WHEN** 同一任务重复投递、另一个 Worker 已持有有效租约或任务已终态
- **THEN** SHALL 不重复执行业务、不修改有效 RUNNING 任务、不重复终态事件；恢复收尾遵循既有 task/handle 锁顺序。

#### Scenario: 正常排队与损坏消息
- **WHEN** Worker 暂停导致正常排队，或消息不能可靠识别数据库任务
- **THEN** SHALL 不仅依据排队时长或不可信消息内容终止业务；损坏消息保留可诊断信息并有界隔离，不能阻塞全部恢复。
