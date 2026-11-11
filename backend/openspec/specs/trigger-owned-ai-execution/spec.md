## Purpose

定义业务任务按当前触发者使用个人模型配置的持久身份、自动派生与重试继承、执行快照及 worker 恢复规则。

## Requirements

### Requirement: 任务必须持久化触发者配置身份
后端 SHALL 在任务创建事务中持久化操作者、配置主体、源任务或批次关联、已选 profile/runtime 引用；模型配置主体 SHALL 来自当前认证触发者或可信父任务，不得来自客户端自报身份、项目所有者或 worker 服务账号。

#### Scenario: 管理员操作其他人的项目
- **WHEN** admin 通过项目权限校验后手动触发 AI 任务
- **THEN** 后端 SHALL 使用 admin 的个人绑定创建执行，即使项目属于其他用户
- **AND** admin 未配置时 SHALL 明确拒绝，不回退项目所有者或平台配置

#### Scenario: 自动派生与自动重试
- **WHEN** 测试批次完成后派生比较，或既有任务自动重试
- **THEN** 派生任务 SHALL 继承批次原触发者并在派生时解析该用户对应阶段绑定
- **AND** 自动重试 SHALL 保持已持久化主体及 profile/runtime 引用，不重新选择另一人的配置

#### Scenario: 手动重新触发
- **WHEN** 用户 B 有权对用户 A 的历史任务手动重新触发
- **THEN** 后端 SHALL 创建属于 B 配置主体的新执行并解析 B 的绑定，保留 A 的历史执行事实

#### Scenario: 无法确定可信触发者
- **WHEN** 自动派生输入缺少身份或同一批次出现相互冲突的触发者
- **THEN** 后端 SHALL 给出可查询的受控失败或不可用原因，不投递无主 AI 任务，不猜测最后操作者

### Requirement: 创建和执行必须分别校验配置有效性
后端 SHALL 在任务创建时验证触发者绑定并固化引用，在每次 worker 领取时复核账号、项目执行资格、profile 归属与启用状态、runtime 可用性；单次执行尝试 SHALL 使用不可变配置快照。

#### Scenario: Readiness 后配置变化
- **WHEN** readiness 返回可用后，创建前绑定被删除或配置被禁用
- **THEN** 创建事务 SHALL 再次检查并拒绝无效配置，不依据过期 readiness 投递任务

#### Scenario: 排队期间改绑或更新
- **WHEN** 任务已经保存 profile/runtime 引用，随后用户更改阶段绑定或更新该 profile
- **THEN** 原任务 SHALL 继续使用已选引用，在领取时读取该 profile 的有效新版本；新绑定只影响新任务
- **AND** profile 已删除、归属不符或已禁用时 SHALL 失败且不请求上游

#### Scenario: 执行过程中更新凭据
- **WHEN** worker 已冻结本次尝试快照后用户更新凭据
- **THEN** 本次尝试 SHALL 保持原快照，不混用密钥；下一次尝试可读取同一 profile 新版本并记录实际版本
- **AND** 日志、队列与审计 MUST NOT 保存明文密钥或完整配置快照

### Requirement: Worker 恢复必须保持个人身份及幂等边界
worker SHALL 从持久化事实恢复身份和配置引用，执行租约、原子领取、预算、终态和结果归并 SHALL 同时校验任务与 profile 归属；MUST NOT 依赖 HTTP 会话或线程局部身份。

#### Scenario: 重启恢复和重复投递
- **WHEN** worker 重启或收到重复执行消息
- **THEN** 恢复 SHALL 保持原触发者，校验归属后幂等领取，不重复执行已终态任务
- **AND** 迟到结果 MUST NOT 覆盖新租约结果、他人的 run 或较新的个人摘要

#### Scenario: 领取时账号已禁用或项目资格丧失
- **WHEN** 原触发者已被禁用、删除或失去项目执行资格
- **THEN** worker SHALL 在上游调用前受控失败，不切换到 admin 或其他有效账号

#### Scenario: 临时测试覆盖恢复
- **WHEN** 个人兼容测试需要跨 worker 暂存覆盖凭据
- **THEN** 暂存 SHALL 加密、关联所有者与执行、有有效期并在终态清理，不能成为另一用户的可复用配置
