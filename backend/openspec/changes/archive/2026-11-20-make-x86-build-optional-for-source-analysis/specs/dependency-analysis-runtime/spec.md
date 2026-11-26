## MODIFIED Requirements

### Requirement: 依赖分析 readiness 必须校验 x86 构建和目标环境
项目级 readiness 在 `stageId=rv-dependency-analysis` 时 SHALL 返回依赖分析所需前置条件。

#### Scenario: x86 构建已成功且目标环境存在
- **WHEN** 当前 Context 按活动优先、否则最近创建选中的 x86 source-only 构建已成功
- **AND** 项目已配置目标 RISC-V 运行环境
- **THEN** `X86_BUILD` 和 `TARGET_ENVIRONMENT` prerequisites SHALL 返回 `SATISFIED`

#### Scenario: x86 构建失败
- **WHEN** 当前 Context 下最近一次 x86 source-only 构建失败
- **THEN** `X86_BUILD` prerequisite SHALL 返回 `FAILED`
- **AND** `reasonCode` SHALL 为 `X86_BUILD_FAILED`

#### Scenario: x86 构建正在运行
- **WHEN** 当前 Context 下存在运行中的 x86 source-only 构建任务
- **THEN** `X86_BUILD` prerequisite SHALL 返回 `RUNNING`

#### Scenario: 目标环境不可用
- **WHEN** 项目配置了目标环境但环境被禁用或最近连接检查不可用
- **THEN** `TARGET_ENVIRONMENT` prerequisite SHALL 返回 `UNAVAILABLE`

#### Scenario: 源码分析放开不影响依赖门槛
- **WHEN** 当前只有源码分析计划或前置构建未成功
- **THEN** SHALL 拒绝依赖分析创建，无任务副作用，不消费内部解析投影、旧版本或更早成功构建冒充当前构建事实。

#### Scenario: 创建执行查询一致
- **WHEN** readiness 后构建或环境改变
- **THEN** SHALL 创建重新校验、入队固定引用、worker 复核真实依赖，并以同一 Context 规则供 latest 和报告选择，不回退历史满足条件。
