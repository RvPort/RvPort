## MODIFIED Requirements

### Requirement: API 必须提供项目级阶段 readiness
后端 API SHALL 提供项目级阶段 readiness 接口，用于返回当前 Context 下指定 workflow 阶段的前置条件事实。

#### Scenario: 查询阶段 readiness
- **WHEN** 已登录用户调用 `GET /api/projects/{projectId}/readiness?stageId={stageId}`
- **THEN** 后端 SHALL 校验用户对项目的访问权限
- **AND** 响应 SHALL 包含 `stageId`、`context` 和 `prerequisites`
- **AND** `context` SHALL 包含当前 `sourceRevisionId` 和当前 `runtimeEnvironmentVersion`
- **AND** `prerequisites` 中每项 SHALL 包含 `key`、`status`、`referenceId`、`reasonCode` 和 `required`；仅 required=true 参与硬门槛，SOURCE_DISCOVERY 的全局环境版本只作显示事实

#### Scenario: readiness 不返回 UI 动作
- **WHEN** 后端返回阶段 readiness
- **THEN** 响应 MUST NOT 包含 `ready`、`actions`、`buttons`、`buttonLabel` 或 `recommendedAction`
- **AND** 前端 SHALL 基于稳定的 `key`、`status` 和 `required` 自行组合 UI 行为

#### Scenario: readiness 状态枚举
- **WHEN** 后端计算任一 prerequisite 状态
- **THEN** `status` MUST 使用 `SATISFIED`、`MISSING`、`RUNNING`、`FAILED`、`STALE` 或 `UNAVAILABLE`
- **AND** `reasonCode` MUST 使用稳定枚举值
- **AND** `reasonCode` MUST NOT 依赖中文展示文案

#### Scenario: readiness 越权访问失败
- **WHEN** 普通用户访问无权限项目的 readiness
- **THEN** 后端 SHALL 返回 `FORBIDDEN` 或 `PROJECT_NOT_FOUND`
- **AND** 后端 MUST NOT 暴露该项目的源码、环境、任务或执行记录信息

### Requirement: API 必须提供专项分析 readiness 和 latest
项目级 readiness SHALL 支持 `floating-point-analysis` 与 `memory-order-analysis`，领域 latest SHALL 使用统一响应外壳并返回当前运行、当前源码、分析模式、BC 覆盖和失效或降级原因。构建、compile database 和 BC SHALL 为可选增强；固定源码、工具及受支持 profile 就绪时 SHALL 允许无数据库源码计划。

#### Scenario: readiness 返回完整前置事实
- **WHEN** 用户查询任一专项分析 stage readiness
- **THEN** 响应 SHALL 区分源码/工具/profile 必需条件与 x86 构建/compile database/BC 可选条件，返回 inputOrigin、解析假设和安全输入摘要
- **AND** 响应 SHALL 返回建议执行模式 `AST_ONLY`、`AST_BC_MIXED` 或 `AST_BC_COMPLETE`
- **AND** 响应 SHALL 返回 BC 状态、计划、可用和失败翻译单元计数及稳定降级原因码

#### Scenario: BC 不可用但 AST 前置满足
- **WHEN** 固定源码和分析工具/profile 可用，compile database 或 BC 缺失、失败、损坏或没有 produced record
- **THEN** readiness 的硬前置条件 SHALL 保持满足
- **AND** 建议执行模式 SHALL 为 `AST_ONLY`
- **AND** 响应 MUST NOT 将 BC 降级描述为整个分析不可用

#### Scenario: 部分 BC 可用
- **WHEN** BC artifact set 仅有部分翻译单元可用
- **THEN** readiness SHALL 返回 `AST_BC_MIXED`
- **AND** SHALL 返回 AST-only 与 AST+BC 的翻译单元分母

#### Scenario: latest 和详情公开实际模式
- **WHEN** 用户查询专项分析 latest、运行详情或列表
- **THEN** 运行响应 SHALL 返回实际 `analysisMode`、BC 状态、BC 覆盖计数和降级原因
- **AND** 无 artifact set 的 AST-only 运行 SHALL 允许 `artifactSetId` 为空

#### Scenario: 执行时重新校验
- **WHEN** readiness 查询后源码、构建、compile database、BC 或工具状态发生变化
- **THEN** 创建 API SHALL 重新校验硬前置事实并重新计算分析模式
- **AND** 仅硬前置不满足时 SHALL 返回 `PREREQUISITE_NOT_SATISFIED`
- **AND** BC 从可用变为不可用时 SHALL 安全降级为 AST-only 并记录原因

#### Scenario: 当前产出与最新执行分离
- **WHEN** 最新分析失败或只有合法发布的 PARTIAL
- **THEN** SHALL 分别返回 latestExecution 与 currentResult；合法 PARTIAL 包含 resultAvailable、analysisComplete、coverage、missingEvidence，不以 DONE 表示证据完整，不用旧 COMPLETE 遮蔽新合法 PARTIAL。


### Requirement: KB 工作流 API 必须区分执行事实和当前有效性
项目 overview、KB latest、readiness 和 KB 执行写接口 SHALL 复用同一实际输入计划与扫描适用性规则，不能仅凭源码存在或历史成功扫描认定当前阶段完成。

#### Scenario: 首次上传源码且尚未构建
- **WHEN** 项目已配置环境并上传源码，但没有 x86 构建和 KB 扫描记录
- **THEN** overview 的 KB SHALL 为 `PENDING` 且原因为 `KB_SCAN_MISSING`
- **AND** KB latest SHALL 为 `EMPTY`
- **AND** KB readiness 的 `X86_BUILD` SHALL 为 `MISSING`
- **AND** X86_BUILD SHALL 为 required=false；固定源码和扫描器可用时 SHALL 允许创建 SOURCE_DISCOVERY，未运行前仍为 PENDING/EMPTY

#### Scenario: 历史成功不能代表当前完成
- **WHEN** 当前只存在旧源码、实际消费的旧配置/旧构建或无法验证计划的扫描；源码模式不因未使用的环境变化失效
- **THEN** overview 的 KB SHALL 为 `PENDING` 且原因为 `KB_SCAN_STALE`
- **AND** KB latest SHALL 返回 `STALE` 并保留历史执行状态
- **AND** readiness SHALL 按当前源码/工具和可选输入事实独立判断能否发起新扫描

#### Scenario: 当前扫描状态映射
- **WHEN** 存在完整匹配当前实际输入上下文的扫描记录
- **THEN** overview SHALL 将活动扫描映射为 `RUNNING`，成功映射为 `DONE`，失败、取消或超时映射为 `BLOCKED`
- **AND** latest SHALL 返回同一选择规则下的扫描及 `CURRENT`
- **AND** 当前失败执行 MUST NOT 被旧成功覆盖；独立 currentResult 可返回仍适用的旧产出并明确其 ID

#### Scenario: 下一步推荐遵守实际依赖
- **WHEN** 环境和源码已就绪，x86 尚未成功，KB 无当前有效执行
- **THEN** overview SHALL 允许推荐可执行的源码分析，前置构建未运行或失败不得抢占全部合法后续步骤
- **AND** 后端 SHALL 返回前置构建 optional=true，并独立表达依赖分析尚不可执行

#### Scenario: 单次响应保持上下文一致
- **WHEN** 生成 overview、KB latest 或 KB readiness 时发生并发上下文变更
- **THEN** 后端 SHALL 通过一致读取或版本重验保证每个响应内部使用同一个上下文判定
- **AND** 不同请求之间真实发生的状态变化 SHALL 通过各自 context 和原因字段表达

#### Scenario: 安全且兼容的结果说明
- **WHEN** API 返回 KB 执行上下文、失效或降级原因
- **THEN** 后端 SHALL 保留既有路径、任务枚举和 latest 外壳，以领域字段表达构建 ID、版本和安全原因码
- **AND** 新增直接展示的说明 SHALL 同时提供中文和英文，例如 `message` 与 `message_en`
- **AND** 响应 MUST NOT 暴露服务器路径、凭据、完整任务输入或对象存储 key
- **AND** 无权限用户 SHALL 在读取这些事实前被拒绝

## ADDED Requirements

### Requirement: 工作流 API 必须区分可选步骤和实际验证链
overview SHALL 保留原工作流状态枚举，并返回 optional、canStart 及实际分析完整性；不新增跳过 API 或状态，不用线性顺序代替真实依赖。

#### Scenario: 前置构建失败但源码分析可运行
- **WHEN** x86 构建未完成而源码/工具就绪
- **THEN** SHALL 保留构建失败/未执行事实、允许三个源码分析，推荐可执行阶段而非持续强制先构建。

#### Scenario: 后续验证与权限
- **WHEN** 查询后续构建、测试或优化资格
- **THEN** SHALL 分别按对应真实输入判断；所有接口先校验项目权限，新增说明提供中英文，不泄露执行路径、密钥或存储 key。

### Requirement: 分析接口必须公开语言范围及安全资源失败事实
API SHALL 保留KB的language字符串和sourceArch请求协议，返回规范化输入身份、逐语言覆盖及不支持/未选择/排除原因，失败诊断与可消费结果分别表达。

#### Scenario: 多语言请求
- **WHEN** 客户端提交现有逗号分隔language与架构别名
- **THEN** SHALL 返回固定effective语言集合和架构摘要，未知语言按既有错误外壳拒绝；不要求前端改传其他形态，不宣称专项具备KB全部语言能力。

#### Scenario: 内存失败查询与SSE
- **WHEN** 读取超限任务详情、latest或恢复SSE
- **THEN** SHALL 返回持久稳定reasonCode和可证实的工具诊断、退出码、phase、预算及双语说明，resultAvailable=false，不泄露路径/密钥；currentResult可独立引用旧有效结果。
