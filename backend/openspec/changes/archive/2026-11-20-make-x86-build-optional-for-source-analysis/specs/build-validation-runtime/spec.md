## MODIFIED Requirements

### Requirement: 构建验证必须支持前置 source-only 构建
构建验证运行时 SHALL 在保持默认双环境构建行为不变的前提下，支持用于依赖分析前置的 source/x86 单环境构建范围。

#### Scenario: 默认双环境构建
- **WHEN** 用户创建构建验证且未提交 `buildScope`
- **THEN** 后端 SHALL 使用 `BOTH` 作为默认构建范围
- **AND** 后端 SHALL 为 source/x86 和 target/RISC-V 创建构建运行
- **AND** 每个构建任务执行时 SHALL 自动准备或复用自身环境的源码部署
- **AND** 该行为 SHALL 与现有正式构建验证节点兼容

#### Scenario: 创建 source-only 构建
- **WHEN** 用户创建构建验证并提交 `buildScope=SOURCE_ONLY`
- **THEN** 后端 SHALL 只为项目当前 source/x86 环境创建构建运行
- **AND** 该构建任务执行时 SHALL 自动执行或复用 source 环境源码部署
- **AND** 后端 MUST NOT 为 target/RISC-V 环境创建源码部署或构建运行

#### Scenario: source-only 构建作为依赖分析前置
- **WHEN** source-only 构建成功
- **THEN** 构建运行 SHALL 保存源码版本、source 环境、环境配置版本、构建日志引用、构建脚本摘要和产物事实摘要
- **AND** 这些事实 SHALL 能被依赖分析任务引用

#### Scenario: 拒绝不支持的构建范围
- **WHEN** 构建创建请求提交 MVP 不支持的 `buildScope`
- **THEN** 后端 SHALL 返回统一参数校验错误
- **AND** 后端 MUST NOT 创建构建运行、任务或队列消息

#### Scenario: 正式构建验证仍使用双环境
- **WHEN** 前端进入正式构建验证节点
- **THEN** 前端 SHALL 默认调用 `buildScope=BOTH` 或省略该参数
- **AND** 后端 SHALL 同时验证 source/x86 和 target/RISC-V 构建结果

#### Scenario: 前置构建可选
- **WHEN** 用户没有发起 SOURCE_ONLY 构建
- **THEN** SHALL 保持未执行事实，不创建跳过记录，不阻止三个源码分析或后续 BOTH 构建。

#### Scenario: source-only 无目标环境
- **WHEN** 源码、源环境与脚本有效但目标环境尚未配置
- **THEN** SHALL 允许 SOURCE_ONLY，只准备源端，不以 requireComplete 双环境配置阻塞。


### Requirement: 构建 readiness 必须返回源码和环境事实
项目级 readiness 在 `stageId=x86-build` 或 `stageId=build` 时 SHALL 返回构建所需前置条件。

#### Scenario: 查询 x86 构建 readiness
- **WHEN** 用户调用 `GET /api/projects/{projectId}/readiness?stageId=x86-build`
- **THEN** 后端 SHALL 返回 `ACTIVE_SOURCE` 和 `SOURCE_ENVIRONMENT`
- **AND** 如果已有当前 Context 的源码部署执行记录，后端 SHALL 返回 `SOURCE_DEPLOYMENT`

#### Scenario: 查询双环境构建 readiness
- **WHEN** 用户调用 `GET /api/projects/{projectId}/readiness?stageId=build`
- **THEN** 后端 SHALL 返回 `ACTIVE_SOURCE`、`SOURCE_ENVIRONMENT` 和 `TARGET_ENVIRONMENT`
- **AND** 缺少任一运行环境时对应 prerequisite SHALL 返回 `MISSING`

#### Scenario: 环境配置过期
- **WHEN** 构建相关记录的 `environmentConfigVersion` 不等于项目当前运行环境版本
- **THEN** 对应 prerequisite SHALL 返回 `STALE`
- **AND** `reasonCode` SHALL 为 `RUNTIME_ENVIRONMENT_CHANGED`

#### Scenario: 直接发起后续构建验证
- **WHEN** 源码、所需源/目标环境和有效脚本已具备，但没有前置 x86 成功记录或报告
- **THEN** SHALL 允许 BOTH 验证创建并在任务内准备源码，不要求前置构建、报告或人工确认；配置就绪不承诺实际连接/构建成功，运行失败如实保存。
