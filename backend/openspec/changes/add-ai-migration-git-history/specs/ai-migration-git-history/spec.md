# ai-migration-git-history 新能力

## ADDED Requirements

### Requirement: 迁移运行必须持有任务级 git 仓库并以基线提交初始化

每个迁移运行 SHALL 在持久 workspace 目录（`{workspaceRoot}/runs/{runId}/`）下持有独立 git 仓库；仓库 SHALL 在首次物化时初始化，并在 `source/`（agent 可改）与 `baseline/`（原始副本）物化完成后打基线提交（`type=manual stage=init src=codemig-init reason=first-init`）。后续重试 SHALL 复用同一仓库，MUST NOT 重新 init 或丢弃既有历史。

#### Scenario: 首次运行初始化
- **WHEN** 迁移运行首次创建 workspace
- **THEN** 后端 SHALL `git init` 并完成 `source/` 与 `baseline/` 物化
- **AND** SHALL 产生恰好一条基线提交，提交内树与 `baseline/` 内容一致

#### Scenario: 重试复用仓库
- **WHEN** 同一 runId 的任务失败后重试
- **THEN** 后端 SHALL 在既有仓库上继续（agent 启动前以 marker 提交记录上次中断：`intermediate-user-stop` 或 `intermediate-error`）
- **AND** 既有历史条目 SHALL 完整保留

#### Scenario: 基线完整性
- **WHEN** 执行后校验输入完整性
- **THEN** SHALL 校验基线提交对象未被改写（agent 或外部不得改写历史）
- **AND** 校验失败 SHALL 以确定性错误失败该运行（沿用不可重试语义）

### Requirement: 提交信息必须使用白名单化的结构化四元组

迁移仓库内所有提交的首行 message SHALL 匹配 `Save type:{type} stage:{stage} src:{src} reason:{reason}`；type SHALL 取自 `{manual, ai, marker}`，stage 取自 `{init, before, after, start, end, marker}`，src 取自 `{rvport-agent, rvport-init, rvport-boundary}`，reason 取自 `{first-init, agent-start, agent-finish, intermediate-user-stop, intermediate-error, stage-end-success, stage-end-error, stage-end-cancel}`。白名单外的组合 SHALL 拒绝提交并记录任务日志。提交由 worker 侧统一执行（`user.name` 固定为运行归属任务框架标识），执行器容器内 MUST NOT 执行 git 提交。

#### Scenario: 提交被解析为数据
- **WHEN** 历史查询读取提交
- **THEN** SHALL 依据正则把 message 解析为四元组并映射展示标签（如 `agent-finish` →「agent 完成」）
- **AND** 无法解析的历史遗留提交 SHALL 以原始 message 呈现并标记为未分类

### Requirement: 关键节点必须强制留痕（marker 提交）

agent 启动前、agent 正常结束后、运行终态落定时 SHALL 各产生一条提交；agent 正常结束的提交包含当次全部改动，其余节点 SHALL 允许工作区无改动时以 `--allow-empty` 产生 marker 提交（零改动的失败/取消运行在历史上仍有事件锚点）。取消与失败路径 SHALL 在终态写入前完成 marker 提交且不阻塞终态落库。

#### Scenario: 零改动失败仍可审计
- **WHEN** agent 立即失败且未改动任何文件
- **THEN** 历史 SHALL 仍包含 `agent-start` 与 `stage-end-error` 两条 marker
- **AND** 终态按原失败语义落库，不受 marker 提交影响

### Requirement: 运行结束必须派生完整源码快照与补丁序列产物

运行终态为 SUCCEEDED 时，后端 SHALL 从仓库 HEAD 派生并入库两类新产物：`MIGRATION_SOURCE`（`source/` 全量 tar，含 sha256 校验，对象存储制品）与 `MIGRATION_PATCH_SERIES`（`git format-patch` 基线提交到 HEAD，`--binary` 支持二进制文件，`git am` 可完整重放）。既有 `MIGRATION_DIFF` SHALL 改由 `git diff <base> HEAD --binary` 派生（排除规则等价于现 `--exclude` 集合），三者的生成 MUST NOT 改动条目结果校验合同。

#### Scenario: 快照与补丁可独立还原
- **WHEN** 用户下载 `MIGRATION_SOURCE` 与 `MIGRATION_PATCH_SERIES`
- **THEN** 前者解包即得修改后完整源码
- **AND** 后者在基线源码上 `git am` 后与 HEAD 树内容一致

#### Scenario: 兼容视图
- **WHEN** 既有消费方（前端补丁视图、归档候选）读取 `MIGRATION_DIFF`
- **THEN** 内容 SHALL 与旧 `diff -ruN` 口径兼容（unified=3、等价排除集），无需消费方变更

### Requirement: 历史查询必须基于 reflog 并支持按提交读取文件

`GET /api/ai-migrations/{runId}/history` SHALL 返回基于 HEAD reflog 的提交点列表（时间倒序）：每项含完整与短 hash、四元组解析标签、分类、时间、首行 message、`isCurrent`。被 reset 丢弃但未过 gc 窗口的提交 SHALL 仍可列出与回跳。`GET /api/ai-migrations/{runId}/repo-file?path&ref` SHALL 返回指定 ref 下该文件内容；路径校验 SHALL 沿用 source path 安全规则（拒绝越界与符号链接），`ref` 缺省为 HEAD。

#### Scenario: 回滚后历史仍完整
- **WHEN** 运行被 hard reset 到早期提交
- **THEN** history SHALL 仍包含 reset 前的提交点
- **AND** 各自 `isCurrent` SHALL 唯一指向当前 HEAD

#### Scenario: 越界路径拒绝
- **WHEN** `path` 请求仓库外路径或穿越符号链接
- **THEN** SHALL 返回统一业务错误，MUST NOT 泄露服务器真实路径

### Requirement: 回滚原语必须重建派生产物且不改终态语义

`POST /api/ai-migrations/{runId}/rollback` SHALL 支持 `working-tree`（丢弃未提交改动：checkout + clean）、`commit`（reset --soft 到指定 hash，保留工作区内容差异）、`hard`（reset --hard + clean 到指定 hash）。回滚成功后后端 SHALL 重新派生 `MIGRATION_DIFF` 与 `MIGRATION_PATCH_SERIES`（新版本制品入库，旧制品保留为历史版本）并更新 run 摘要的 `doneCount`/`pendingCount` 计数以反映新 HEAD。回滚 MUST NOT 修改 `ai_migration_runs.status`（SUCCEEDED 运行回滚后仍为 SUCCEEDED），MUST NOT 触碰扫描域与评估报告域数据。仅运行归属人或超级管理员可调用，操作 SHALL 写审计日志。

#### Scenario: 回滚后重取产物
- **WHEN** 用户对 SUCCEEDED 运行执行 `hard` 回滚到基线提交
- **THEN** 后端 SHALL 落库新版本 `MIGRATION_DIFF`（内容为空差异）与 `MIGRATION_PATCH_SERIES`
- **AND** run 状态 SHALL 保持 SUCCEEDED
- **AND** 历史 SHALL 新增 marker 提交记录回滚事件

### Requirement: workspace 清理必须保留仓库与派生所需内容

运行结束后清理 SHALL 仅移除可再生临时物（`.migrate_verify/`、`.prompt.txt`、执行器侧脚本产物）；`source/`、`baseline/`、`.git` 与 input 校验所需文件 SHALL 保留。仓库保留策略 SHALL 可配置（默认保留全部；上限条数触发时按最旧运行整仓删除，删除前 SHALL 确认其产物已入库对象存储）。

#### Scenario: 清理不伤历史
- **WHEN** SUCCEEDED 运行结束触发清理
- **THEN** `git log` 历史完整、`MIGRATION_SOURCE` 可重新派生
- **AND** 临时物不存在
