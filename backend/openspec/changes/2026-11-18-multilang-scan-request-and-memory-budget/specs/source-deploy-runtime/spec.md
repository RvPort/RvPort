## ADDED Requirements

### Requirement: 任务重试 SHALL 复用既有源码部署记录

同一任务（相同 `task_id`）重试进入 `ensureDeployed` 且非 force 时，SHALL 先按 `task_id` 查回最新部署记录；存在且状态非 `SUCCEEDED` 时 SHALL 复用该记录重新执行部署（同一 `remote_path` 的重新部署为幂等操作），MUST NOT 新插入 `source_deploy_runs` 行。既有可复用状态判定（`sourceDeploymentId` 命中 `SUCCEEDED` 直接复用、按四元组查 `PENDING/QUEUED/RUNNING`）保持不变；仅补齐 FAILED 记录在重试场景的复用路径，消除 `source_deploy_runs_task_id_key` 唯一键冲突导致的任务永久失败。

#### Scenario: 失败后重试

- **WHEN** 一次构建验证的源码部署失败（run 标记 FAILED）后任务重试
- **THEN** worker SHALL 复用该 task 的既有部署记录重新部署到同一 remote_path
- **AND** 任务日志 SHALL 记录复用的部署记录 ID
- **AND** 数据库 MUST NOT 出现同 task_id 的第二行部署记录

#### Scenario: 成功记录仍直接复用

- **WHEN** 既有部署记录状态为 SUCCEEDED 且非 force 重试
- **THEN** SHALL 沿用既有直接复用路径，不重新部署

#### Scenario: force 部署不受影响

- **WHEN** 请求携带 force
- **THEN** SHALL 沿既有强制新建路径执行
