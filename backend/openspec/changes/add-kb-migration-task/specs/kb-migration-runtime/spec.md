# kb-migration-runtime 新能力

## ADDED Requirements

### Requirement: 投影条目必须使用跨运行稳定身份并遵循过滤与映射规则

后端 SHALL 将当前有效 `KB_SCAN` 运行的 findings 投影为 `scan_result.json` 条目集合，条目 `id` SHALL 使用 `evidence.scannerFindingId`，`sourceFindingIds` SHALL 携带当前运行 findings 的数据库 ID；投影 MUST NOT 以每运行变化的 `fingerprint` 或数据库 UUID 作为跨运行身份。

#### Scenario: 过滤不可迁移条目
- **WHEN** 某 finding 的 `evidence.actionable` 为 false、`conclusionStatus` 为 inactive，或 severity 为 info 且非 actionable
- **THEN** SHALL 不产生投影条目，原始 finding 保留
- **AND** 相同 `scannerFindingId` 的重复 finding SHALL 只保留一条

#### Scenario: solver_type 映射
- **WHEN** 投影 `fact.kind`
- **THEN** intrinsic_call 与 vector_type SHALL 映射 intrinsics；macro_invocation 与 macro_replacement SHALL 映射 macros；architecture_probe SHALL 映射 inline_asm 且 `.S`/`.s`/`.asm` 文件归 asm_file；compiler_flag 与 build_argument SHALL 按构建文件类型归 build_makefile 或 build_cmake；header_include SHALL 映射 headers；test_asset SHALL 跳过；未知 kind SHALL 兜底 inline_asm
- **AND** 映射源 MUST NOT 使用 `findings.category`（conclusion.findingType）

#### Scenario: 一对多展开
- **WHEN** 单个 finding 展开为多条目
- **THEN** 子条目 SHALL 共享同一 `sourceFindingIds`

### Requirement: 任务创建必须满足资格条件并固定执行身份

后端 SHALL 仅在当前项目存在有效成功扫描、可投影条目大于零且触发者已绑定 `KB_MIGRATION` 阶段模型时允许创建；inputSnapshot SHALL 内嵌触发者模型选择，执行时 SHALL 复核归属与一致性。

#### Scenario: 无有效扫描或零条目
- **WHEN** 当前扫描失效、失败或投影后条目为零
- **THEN** SHALL 拒绝创建并返回双语原因，不投递任务

#### Scenario: 阶段未绑定
- **WHEN** 触发者未绑定 `KB_MIGRATION` 阶段的模型 profile 或 agent runtime，或绑定已禁用
- **THEN** SHALL 返回明确业务错误
- **AND** 任务请求 MUST NOT 直接提交 base URL、API key 或模型参数

#### Scenario: 防换绑
- **WHEN** 执行时解析到的 taskId、profile 或 runtime 与持久化引用不一致
- **THEN** SHALL 以业务错误失败，不使用新绑定继续

### Requirement: Agent 执行必须在隔离 workspace 内保持输入完整性

后端 SHALL 在独立 workspace 内组装 `input/scan_result.json`、`input/manifest.json`、按投影涉及文件物化的 `source/` 与 `.opencode/agent/` 技能；执行前后 SHALL 对输入文件做 hash 校验；workspace 在产物入库后 SHALL 清理。

#### Scenario: 只物化涉及文件
- **WHEN** 组装 source 树
- **THEN** SHALL 仅物化投影条目引用的文件，缺失文件计入 manifest 且不中断
- **AND** 物化路径 MUST 经服务端路径校验且不跟随符号链接逃逸

#### Scenario: 输入被篡改
- **WHEN** 执行后输入文件 hash 与执行前不一致
- **THEN** SHALL 以业务错误失败并保留日志证据

#### Scenario: stdout 合同
- **WHEN** agent 执行完成
- **THEN** 最终回复 SHALL 仅一行 `DONE` 或 `FAIL: <原因>`，全部结果 MUST 写入 `output/` 文件

### Requirement: 迁移结果必须经过校验并作为产物入库

后端 SHALL 校验 `migration_result.json`：条目 id 必须属于投影集合、不得重复、缺失计数明确；条目状态仅允许 `DONE`、`NO_WORK_NEEDED`、`PENDING`；`migration-diff.patch` SHALL 与结果一同入库并登记归档候选。

#### Scenario: 结果含未知或重复条目
- **WHEN** 结果包含投影集合外的 id 或重复 id
- **THEN** SHALL 计入 unknown/duplicate 计数并在摘要中体现，不静默丢弃

#### Scenario: 校验完全失败
- **WHEN** 结果文件缺失、非法或全部条目缺失
- **THEN** 任务 SHALL FAILED，不产出成功产物

#### Scenario: 部分成功
- **WHEN** 部分条目完成而其余为 PENDING 或 NO_WORK_NEEDED
- **THEN** 任务 SHALL 允许成功并记录分类计数

### Requirement: 失败语义必须区分基础设施错误与不可重试业务错误

后端 SHALL 将模型未配置、零条目、agent 输出非法定义为不可重试错误并直接 FAILED；超时 SHALL 由执行预算强制；取消 SHALL 响应协作取消检查。

#### Scenario: 不可重试错误
- **WHEN** 发生 `KB_MIGRATION_MODEL_NOT_CONFIGURED`、`KB_MIGRATION_NO_ENTRIES` 或 `AGENT_OUTPUT_INVALID`
- **THEN** 任务 SHALL 直接进入 FAILED，不进入重试队列

#### Scenario: 超时与取消
- **WHEN** 执行超出阶段模型配置的运行时限或收到取消
- **THEN** SHALL 终止执行并按超时/取消语义落终态，workspace 清理不阻塞终态写入

### Requirement: 迁移执行不得改变扫描域状态

`KB_MIGRATION` 执行 MUST NOT 修改 findings 状态、扫描运行记录或既有扫描产物；结果仅存在于运行产物与任务日志中。

#### Scenario: 扫描数据只读
- **WHEN** 迁移任务执行完成
- **THEN** findings 表与扫描产物 SHALL 与执行前一致
