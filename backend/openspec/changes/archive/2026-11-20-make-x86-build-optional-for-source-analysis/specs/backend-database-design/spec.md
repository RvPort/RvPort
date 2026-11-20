## MODIFIED Requirements

### Requirement: KB 扫描运行表必须支持异步任务和摘要查询
数据库 SHALL 使用 `kb_scan_runs` 保存 KB 扫描运行事实，支持按项目、源码、执行上下文和状态查询，并持久化新扫描的版本化实际输入计划及可选 x86 构建身份。

#### Scenario: 创建 KB 扫描运行
- **WHEN** 后端创建 KB 扫描任务
- **THEN** 数据库 SHALL 保存 `kb_scan_runs` 记录
- **AND** `project_id` SHALL 关联项目
- **AND** `task_id` SHALL 唯一关联 `tasks.id`
- **AND** `source_revision_id` SHALL 关联被扫描源码版本
- **AND** 新服务 SHALL 完整写入输入计划版本、inputOrigin、源码指纹和请求/实际输入摘要；SOURCE_DISCOVERY 的 `source_environment_id`、`runtime_environment_version` 和 `build_run_id` 可以为空，BUILD_ARTIFACTS SHALL 完整记录实际消费的构建环境关联
- **AND** BUILD_ARTIFACTS 的非空 `build_run_id` SHALL 关联经服务验证与项目、源码、源环境、运行环境版本一致的成功 `SOURCE_ONLY` 构建
- **AND** `status` SHALL 使用 `PENDING`、`QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED`、`CANCELLED` 或 `TIMEOUT`
- **AND** `scope` SHALL 保存扫描范围、排除路径、语言、目标架构和规则集摘要

#### Scenario: 更新运行摘要
- **WHEN** KB 扫描完成或失败
- **THEN** `kb_scan_runs.summary` SHALL 保存 schema version、tool version、rule pack、文件数、finding 数量、severity counts、diagnostics 摘要和 artifact 摘要
- **AND** compile database 降级时 SHALL 保留请求绑定身份及安全原因，并独立保存实际 SOURCE_DISCOVERY 计划和 effectiveInputDigest
- **AND** summary MUST NOT 保存源码工作副本路径、scanner 输出目录、明文凭据或完整 stdout/stderr
- **AND** 执行结果更新 MUST NOT 改写创建时绑定的执行上下文

#### Scenario: 查询 KB 扫描运行
- **WHEN** 创建或维护 `kb_scan_runs`
- **THEN** 数据库 SHALL 保留 `project_id, started_at desc` 和 `source_revision_id` 的既有索引
- **AND** 数据库 SHALL 提供支持项目、源码、源环境、运行环境版本和构建 ID 联合筛选的索引
- **AND** 如实现支持按状态过滤，数据库 MAY 为 `project_id, status, started_at desc` 建立索引
- **AND** repository SHALL 在筛选有效上下文后执行活动优先、创建时间与 ID 稳定排序
- **AND** 计数查询与分页查询 SHALL 使用一致过滤条件

#### Scenario: 旧数据升级
- **WHEN** 数据库从没有扫描上下文列的版本升级
- **THEN** 迁移 SHALL 以可空字段保留旧行；新计划按版本和 inputOrigin 校验必填字段，SOURCE_DISCOVERY 不要求构建关联；旧未标记计划模式的记录继续保持原全空或完整关联约束，不将旧空字段推断成合法源码模式
- **AND** 旧记录 SHALL 保留原状态、finding 和 artifact 关联
- **AND** 迁移 MUST NOT 根据当前环境或 summary 中不完整信息推测回填
- **AND** 新查询 SHALL 将上下文缺失的旧行判为历史，不能作为当前扫描

#### Scenario: 迁移与生成类型验证
- **WHEN** 实施数据库变更
- **THEN** SHALL 在独立 Docker 数据库验证空库初始化、旧数据升级、重复迁移、外键和上下文完整性约束
- **AND** SHALL 在容器内生成 jOOQ 类型并以当前源码完成编译与单元验证
- **AND** 迁移或代码生成验证 MUST NOT 替代业务单元测试和覆盖率门槛

### Requirement: 数据库必须持久化专项分析运行
数据库 SHALL 保存 analyzer type、项目、任务、源码版本、输入计划、可空构建、可空 artifact set、实际消费的可选环境配置版本、协议版本、状态、verdict、退出码、计数、摘要、制品引用和时间字段。

#### Scenario: 保存成功运行
- **WHEN** 工具结果通过协议校验
- **THEN** 运行记录 SHALL 在同一事务中写入终态、计数和结果制品引用

#### Scenario: 保存失败运行
- **WHEN** 输入、工具或结果导入失败
- **THEN** 运行记录 SHALL 保存失败阶段和安全错误摘要
- **AND** MUST NOT 保存成功态 Finding 计数

#### Scenario: 保存源码独立分析
- **WHEN** 无构建引用但 source plan 合法
- **THEN** SHALL 可持久化完整或部分分析、覆盖和缺失证据，不创建占位 build_runs 或伪造 artifact-set；实际存在的关联仍校验项目、源码和外键。

## ADDED Requirements

### Requirement: 分析输入计划必须允许无构建引用并保持关系约束
数据库 SHALL 为 KB 和专项持久化版本化输入计划、requested/effective digest、模式、覆盖与证据缺口；无构建模式允许 buildRunId 为空，禁止以假运行满足外键。

#### Scenario: 源码模式落库
- **WHEN** SOURCE_DISCOVERY 运行没有环境或构建
- **THEN** SHALL 合法保存固定源码与解析计划，读取可区分新合法空关联与历史未知上下文，保持同项目同源码约束和安全摘要。

#### Scenario: 增强模式落库
- **WHEN** BUILD_ARTIFACTS 引用构建和制品
- **THEN** SHALL 约束有效所属项目、源码、版本及 FK；不允许跨项目或错版本关联，不回填猜测历史上下文。

### Requirement: AI 分批复核必须持久化唯一计划与已验证结果
数据库 SHALL 保存报告/attempt/input digest/批次和逐项结果的幂等关系，租约与完整发布原子校验；不保存明文凭据。

#### Scenario: 重复或迟到批次
- **WHEN** 同一计划批次重复写入或旧 attempt 迟到
- **THEN** SHALL 保持已校验结果唯一且拒绝不一致覆盖，只有全部应复核 ID 合法齐备才允许增强报告发布。

#### Scenario: 迁移与并发
- **WHEN** 空库或已有报告历史数据库升级并竞争发布
- **THEN** SHALL 保留历史事实及约束，验证空构建关联、事务回滚与唯一键，Docker 生成 jOOQ 并以并发数据库测试验收。
