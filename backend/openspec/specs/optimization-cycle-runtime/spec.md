## Purpose

定义优化闭环的源码版本轮次、前序结果聚合、多轮查询和当前确认清单事实，保持完整构建、测试与比较验证链要求，不判断优化收敛或自动触发下游任务。

## Requirements

### Requirement: 优化轮次必须按源码版本落库
系统 SHALL 使用优化轮次记录表达项目多轮优化边界，每个优化轮次 SHALL 绑定一个项目和一个源码版本。

#### Scenario: 首次源码版本创建优化轮次
- **WHEN** 项目首次成功激活源码版本
- **THEN** 后端 SHALL 创建该项目的第一个优化轮次
- **AND** 该轮次 SHALL 绑定当前源码版本
- **AND** `cycleNumber` SHALL 为 1

#### Scenario: 新源码版本创建下一轮
- **WHEN** 项目激活新的源码版本
- **THEN** 后端 SHALL 为该源码版本创建或复用优化轮次
- **AND** 新轮次的 `cycleNumber` SHALL 为该项目已有最大轮次号加 1
- **AND** 后端 MUST NOT 将上一源码版本的迁移输入复制到新轮次

#### Scenario: 同一源码版本不重复创建轮次
- **WHEN** 同一项目同一源码版本已经存在优化轮次
- **THEN** 后端 SHALL 复用已有优化轮次
- **AND** 后端 MUST NOT 创建重复记录

### Requirement: 优化轮次只做数据聚合展示
优化轮次运行时 SHALL 聚合前序节点事实并计算当前验证链是否满足，MUST NOT 将验证链满足等同于业务优化收敛，也 MUST NOT 自动触发下游任务。

#### Scenario: 聚合当前轮次结果
- **WHEN** 用户查询当前优化概览
- **THEN** 后端 SHALL 返回当前 active 源码版本对应的优化轮次
- **AND** 响应 SHALL 聚合该轮次相关迁移输入、报告、复检、源码部署、构建、测试和测试结果比较摘要
- **AND** 构建、测试和比较的当前有效摘要 SHALL 基于同一 Context 和关联链计算，并区分原始历史事实
- **AND** 响应 MUST NOT 返回完整日志、完整报告正文、完整 agent 输入、明文凭据或服务器真实路径

#### Scenario: 不做自动决策
- **WHEN** 构建、测试或测试比较结果显示失败或高风险
- **THEN** 优化轮次 API SHALL 保留原始事实摘要供历史审计，同时返回当前验证链的未满足原因
- **AND** 后端 MUST NOT 在优化轮次生命周期中写入 `PASSED`、`FAILED`、`CONVERGED` 或等效决策结论
- **AND** 后端 MUST NOT 自动创建修复、复检、构建或测试任务

#### Scenario: 子节点尚未运行
- **WHEN** 当前轮次尚未产生报告、复检、构建、测试或测试比较记录
- **THEN** 后端 SHALL 在对应摘要中返回空状态或 `null`
- **AND** 后端 MUST NOT 因缺少子节点数据导致概览查询失败
- **AND** 后端 MUST NOT 仅凭轮次存在认定优化闭环完成

### Requirement: 优化轮次必须支持多轮列表和详情查询
系统 SHALL 提供项目级优化轮次查询能力，使前端能够展示当前轮和历史多轮优化结果。

#### Scenario: 查询优化轮次列表
- **WHEN** 用户调用项目优化轮次列表 API
- **THEN** 后端 SHALL 校验用户对项目有访问权限
- **AND** 响应 SHALL 返回分页轮次列表
- **AND** 每条轮次 SHALL 包含轮次 ID、源码版本摘要、轮次号、展示状态、关键时间和聚合摘要

#### Scenario: 查询优化轮次详情
- **WHEN** 用户查询单个优化轮次详情
- **THEN** 后端 SHALL 校验该轮次属于请求项目
- **AND** 后端 SHALL 校验用户对项目有访问权限
- **AND** 响应 SHALL 返回该轮次完整聚合摘要和相关资源引用

#### Scenario: 越权访问优化轮次
- **WHEN** 用户访问无权限项目的优化轮次列表、概览或详情
- **THEN** 后端 SHALL 返回访问拒绝错误
- **AND** 响应 MUST NOT 泄漏项目、源码版本或轮次是否存在

### Requirement: 优化轮次查询必须支持分页过滤和排序
优化轮次列表 API SHALL 支持分页、过滤和排序，并 SHALL 使用白名单字段。

#### Scenario: 分页查询
- **WHEN** 用户查询优化轮次列表
- **THEN** 请求 SHALL 支持 `page` 和 `pageSize`
- **AND** 响应 SHALL 包含 `items`、`page`、`pageSize`、`total` 和 `hasMore`

#### Scenario: 过滤轮次
- **WHEN** 用户查询优化轮次列表并提交过滤条件
- **THEN** 后端 SHALL 支持按源码版本、轮次状态和创建时间范围过滤
- **AND** 后端 MUST NOT 将任意请求字段拼接到 SQL

#### Scenario: 排序轮次
- **WHEN** 用户查询优化轮次列表并提交排序字段
- **THEN** 后端 SHALL 支持按 `cycleNumber`、`createdAt`、`updatedAt` 和 `startedAt` 排序
- **AND** 后端 SHALL 拒绝不在白名单中的排序字段

### Requirement: 优化闭环必须提供 latest
优化闭环领域 SHALL 提供当前 Context 下的 latest 接口，用于展示当前优化轮次、有效验证事实和后续动作摘要。轮次的新鲜度 SHALL 表达源码归属，子执行有效性 SHALL 单独表达，`CURRENT` MUST NOT 表示验证已经完成。

#### Scenario: 查询优化闭环 latest
- **WHEN** 已登录用户调用 `GET /api/projects/{projectId}/optimization-cycles/latest`
- **THEN** 后端 SHALL 返回统一 latest 外壳
- **AND** `result` SHALL 包含当前源码版本下最新优化轮次、各节点结果聚合、验证事实和 `nextAction`
- **AND** 优化轮次没有独立执行任务时 `execution` SHALL 为 `null`
- **AND** 当前轮次未完成构建或测试时 SHALL 仍可为 `CURRENT`，但 MUST NOT 推荐归档

#### Scenario: 优化闭环 latest 识别旧源码
- **WHEN** 只有旧源码版本的优化轮次存在
- **THEN** 后端 MUST NOT 将该结果标记为 `CURRENT`
- **AND** latest SHALL 返回 `freshness=STALE` 并保留历史轮次只读事实
- **AND** 历史结果 MUST NOT 驱动当前 workflow 完成或当前归档建议

#### Scenario: 无 active 源码时读取历史
- **WHEN** 项目没有 active 源码但存在历史轮次
- **THEN** latest SHALL 显式进入历史查询并返回 `STALE`
- **AND** 后端 MUST NOT 因源码参数为空而省略当前查询过滤并返回 `CURRENT`

#### Scenario: 没有任何轮次
- **WHEN** 当前和历史均不存在优化轮次
- **THEN** latest SHALL 返回 `EMPTY`，且 `result` 和 `execution` 均为 `null`
- **AND** 接口 MUST NOT 以 404 表示结果尚未产生

#### Scenario: 同源码环境变更
- **WHEN** 当前源码轮次存在但环境 ID 或配置版本发生变化
- **THEN** latest 的轮次归属 SHALL 保持 `CURRENT`
- **AND** 旧环境的构建及其下游测试和比较 SHALL 被判定为当前不可复用
- **AND** 当前有效摘要 MUST NOT 展示旧验证链为已通过

### Requirement: 优化闭环完成必须由完整当前验证链证明
系统 SHALL 仅在当前源码和环境的双端 `BOTH` 构建、绑定这些构建的同批次双端测试均成功，且精确绑定这两条测试的比较执行成功并得出 `EQUIVALENT` 时将优化闭环标为 `DONE`。

#### Scenario: 上传源码后轮次已经存在
- **WHEN** 环境和源码已准备且轮次已创建，但未完成当前双端构建
- **THEN** 优化闭环 SHALL 为 `PENDING`
- **AND** 轮次 ACTIVE 状态、报告存在或零 Finding 均 MUST NOT 使其变为 `DONE`

#### Scenario: 单侧成功不能完成闭环
- **WHEN** 仅有 SOURCE_ONLY 成功构建、BOTH 单侧成功构建或同批次单侧成功测试
- **THEN** 对应双端阶段 SHALL 未满足
- **AND** 优化闭环 MUST NOT 为 `DONE`，当前摘要 MUST NOT 把该双端阶段标为 `SUCCEEDED`

#### Scenario: 全部当前证据通过
- **WHEN** 两侧选中 BOTH 构建成功，两侧测试正确关联各自构建且属于同一批次并成功，精确输入比较成功且为 EQUIVALENT
- **THEN** 优化闭环 SHALL 为 `DONE`
- **AND** 当前兼容摘要 SHALL 满足已有前端的构建成功、测试成功、比较 EQUIVALENT 判定，后续动作为归档入口
- **AND** 后端 MUST NOT 因该读取写入收敛结论或改变轮次生命周期

#### Scenario: 比较尚未完成或不通过
- **WHEN** 双端测试通过但精确输入的比较缺失、活动、失败、不可用或没有 EQUIVALENT 结论
- **THEN** 缺失比较 SHALL 映射为 `PENDING`，排队或运行 SHALL 映射为 `RUNNING`
- **AND** 失败、不可用、无结论及 DIFFERENT_ACCEPTABLE、DIFFERENT_BLOCKING、INCONCLUSIVE SHALL 映射为 `BLOCKED`
- **AND** 后端 MUST NOT 推荐当前归档

#### Scenario: 前序尚未满足
- **WHEN** 当前验证链中构建或测试仍缺失、失效或活动，或已失败、取消、超时
- **THEN** 优化闭环 SHALL 以前序最早未满足环节为原因，缺失、失效或活动映射为 `PENDING`，失败终态映射为 `BLOCKED`
- **AND** 后续历史成功比较 MUST NOT 覆盖前序状态

### Requirement: 有效验证记录必须按关联和活动优先规则选择
后端 SHALL 使用统一的确定性选择规则，校验项目、源码、环境 ID/配置版本、构建范围、构建引用、测试批次与角色、比较两侧测试引用；MUST NOT 通过先筛选成功记录或跨批次拼接制造完整验证链。

#### Scenario: 活动执行优先
- **WHEN** 同一当前候选范围内同时存在活动执行和历史终态
- **THEN** 后端 SHALL 优先选择最近创建的活动执行，无活动时才选择最近创建的终态，并以稳定 ID 打破同时间排序并列
- **AND** 测试排序 SHALL 使用任务创建时间，MUST NOT 因排队测试 startedAt 为空而漏选

#### Scenario: 新批次不完整或失败
- **WHEN** 当前源码环境下已有旧成功批次，随后出现新候选批次但仅一侧存在或一侧失败
- **THEN** 后端 SHALL 先选择候选批次再判断完整性和构建引用
- **AND** 后端 MUST NOT 回退旧完整成功批次，也 MUST NOT 拼接旧批次另一侧补齐

#### Scenario: 构建引用不匹配
- **WHEN** 测试所属源码环境匹配，但 buildRunId 不等于该侧当前选中构建
- **THEN** 该测试及其比较 SHALL 不得作为当前通过证据
- **AND** 新候选的引用错误 MUST NOT 触发回退更早成功测试

#### Scenario: 比较仅一侧匹配
- **WHEN** 比较只有一侧源码或测试 ID 匹配，或批次、项目、角色、环境不一致
- **THEN** 后端 MUST NOT 将该比较纳入当前有效摘要
- **AND** 后端 MUST 校验两侧输入，MUST NOT 使用任意一侧源码匹配作为充分条件

#### Scenario: 失败比较遗留成功结论
- **WHEN** 选中比较执行未成功但其历史字段仍保留 EQUIVALENT
- **THEN** 后端 MUST NOT 将该 overallStatus 暴露为当前兼容摘要的通过证据
- **AND** 原始字段 SHALL 在历史事实或比较详情中保留，数据库记录 MUST NOT 因读取被改写

### Requirement: 历史结果失效不得破坏审计记录
源码切换、环境配置变化、重新选择构建或测试后，后端 SHALL 重算验证链的当前资格，保留历史记录和资源引用；MUST NOT 通过删除记录、改写状态或猜填关联字段修复展示。

#### Scenario: 重新构建使下游失效
- **WHEN** 同一源码环境下选中了新的 BOTH 构建记录
- **THEN** 绑定旧构建的测试及其比较 SHALL 不再满足当前验证完成条件
- **AND** 旧成功链 SHALL 仍可通过受权限保护的历史接口查询

#### Scenario: 旧记录缺少必要关联
- **WHEN** 旧记录缺少环境版本、构建 ID、测试批次、角色或必要关联信息
- **THEN** 后端 SHALL 保留其历史可读性并提供不可复用原因
- **AND** 后端 MUST NOT 仅凭时间、状态成功或源码相同将其视为当前有效证据

#### Scenario: 切回旧源码
- **WHEN** 用户重新激活以前的源码并复用已有轮次
- **THEN** 后端 SHALL 根据此刻环境与最新候选重新解析有效性
- **AND** 后端 MUST NOT 恢复此前缓存的 DONE 判断

#### Scenario: 历史列表和详情
- **WHEN** 用户查询非当前源码的历史轮次
- **THEN** 后端 SHALL 保留分页、过滤、排序、原始摘要和资源详情查询
- **AND** 增量验证事实 SHALL 标明不适用于当前验证链，后续动作 MUST NOT 表达当前归档通过

### Requirement: 优化一致性修改必须通过 Docker 单元测试与覆盖率门槛
此次修改 SHALL 在 Docker 中执行可重复的单元测试及覆盖率检查，后端整体和全部受影响模块的行、分支覆盖率 SHALL 分别至少 90%，并保留既有更严格规则。

#### Scenario: 全量和受影响模块验收
- **WHEN** 实施完成并准备交付代码
- **THEN** SHALL 从当前源码构建测试镜像，运行完整 Java 单元测试及 JaCoCo 门槛，以及后端资源 Python 单元测试和独立行/分支门槛
- **AND** 统计 SHALL 包含未加载自有源码，分别列出整体、受影响完整模块的 covered/total、百分比和报告位置
- **AND** SHALL 明确生成代码、第三方排除理由，MUST NOT 排除自有业务逻辑或仅统计变更行

#### Scenario: 测试或覆盖率不达标
- **WHEN** 单元测试失败、任一适用门槛不足或环境阻塞
- **THEN** 验收 SHALL 保持未完成，记录实际命令和失败原因
- **AND** MUST NOT 用构建、集成测试、旧报告或降低门槛代替当前单元测试验收
- **AND** 大规模无关历史缺口 SHALL 报告并请求扩大范围，不自动修改无关项目

### Requirement: 优化概览必须独立表达当前确认清单
优化 API SHALL 通过当前清单 resolver 聚合确认事实与有效性，保持既有双端构建/测试/比较验证链规则，不自动触发任务。

#### Scenario: 确认与修复资格
- **WHEN** 报告存在但未确认、草稿变更或 Context 失效
- **THEN** SHALL 返回当前清单不可用原因和确认事实，不能以旧迁移输入或报告问题总数推断需要修复/已确认。

#### Scenario: 零清单与已完成 AI
- **WHEN** 当前确认为空或 AI 生成完成
- **THEN** SHALL 前者表示无需清单修复，后者仅表示报告事实；两者都不能代替验证链成功或把优化标为 DONE。

#### Scenario: 历史轮次
- **WHEN** 读取旧源码轮次
- **THEN** SHALL 显示当时报告/确认引用与历史事实，不借用当前个人配置或当前清单重新解释历史。

### Requirement: 优化闭环不得以可选分析或 AI 结论替代验证链
后端 SHALL 保持成功双端构建验证、对应有效测试批次和比较结果的闭环门槛；前置 SOURCE_ONLY 是否执行与源码分析是否可启动 SHALL 不替代该验证事实。

#### Scenario: 未执行前置构建
- **WHEN** 用户仅运行源码分析后直接完成当前源码的双端构建验证及对应测试比较
- **THEN** SHALL 按实际验证链判断闭环资格，不要求补做前置 x86 构建。

#### Scenario: 只有源码分析或 AI 确认
- **WHEN** 来源合法 PARTIAL、报告生成成功、AI 确认全部条目或用户提交空清单，但正式验证链未完成
- **THEN** SHALL 保持闭环未完成并返回真实缺失前置，不将报告完成或零待修复解释为构建/测试成功。

#### Scenario: 仅单侧或历史成功
- **WHEN** 只有一侧构建成功，或测试/比较不匹配当前有效双端构建
- **THEN** SHALL 阻止以这些结果认定闭环完成，readiness、overview、latest 与执行使用同一判定。
