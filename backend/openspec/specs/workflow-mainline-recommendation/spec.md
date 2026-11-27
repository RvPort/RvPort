# 工作流主线推荐规范

## Purpose

规定项目 overview 如何依据当前有效主线、报告复核和确认清单事实计算导航推荐，同时保持各阶段独立执行条件、历史可追溯性及后续验证顺序。

## Requirements

### Requirement: 推荐必须依据当前有效主线且独立于阶段展示顺序
后端 SHALL 基于项目基础配置、源码管理、知识库扫描、结果评估和迁移修复的当前有效事实计算 overview 的 `workflow.recommendedStageId`，MUST NOT 通过展示数组首个 RUNNING、BLOCKED 或可执行元素确定推荐。推荐 SHALL 表达导航焦点，阶段执行仍遵守各自权限和 readiness。

#### Scenario: 结果评估与专项分析同时就绪
- **WHEN** 普通报告生成条件已满足且浮点精度、强弱内存序或依赖分析也可执行
- **THEN** 推荐 SHALL 为 report，任意调整 `workflow.stages` 顺序后结果 SHALL 相同
- **AND** 各分析的真实状态和 canStart SHALL 保持不变

#### Scenario: 支线正在运行
- **WHEN** 当前报告已就绪且支线分析或前置 x86 构建处于排队或运行状态
- **THEN** 推荐 SHALL 为 report，支线运行状态 SHALL 在其节点与任务中单独保留

#### Scenario: 项目尚无有效源码
- **WHEN** 项目基础配置有效但尚无活动源码，或当前源码导入仍在执行
- **THEN** 推荐 SHALL 为 assets，不因环境缺失或支线任务抢占

#### Scenario: 已有源码但尚无报告输入
- **WHEN** 项目基础配置和活动源码有效且不存在满足报告生成条件的有效输入
- **THEN** 推荐 SHALL 为 kb，并保留其实际可执行、运行或阻塞事实
- **AND** 系统 MUST NOT 因该推荐而绕过扫描自身的语言、工具或配置校验

### Requirement: 主线优先级不得成为新的执行硬前置
系统 SHALL 保留知识库、内存序、浮点等分析的独立执行条件；依赖分析 SHALL 继续要求有效 x86 构建结果。报告就绪 SHALL 复用现有输入选择策略，不新增 KB、x86 或全部支线完成条件。

#### Scenario: KB 未执行但其他分析已提供有效输入
- **WHEN** KB 缺失、失败或运行中，另一个当前有效分析结果已使报告 readiness 满足
- **THEN** 推荐 SHALL 为 report，不推荐先完成 KB 或 x86

#### Scenario: 有效部分结果已发布
- **WHEN** PARTIAL 结果符合现有合法发布与报告纳入规则
- **THEN** 推荐 SHALL 以真实报告 readiness 为准，不因 PARTIAL 标签强制重扫

#### Scenario: 推荐不阻止独立执行
- **WHEN** 推荐为 report 或 manual，用户请求执行前置条件满足的独立分析或构建验证
- **THEN** 后端 SHALL 继续执行其原有权限及 readiness 校验，不新增 recommendedStageId 必须等于请求阶段的限制

### Requirement: 报告生成事实与人工复核和清单确认必须分别表达
overview SHALL 提供当前报告复核摘要并复用当前清单资格判断。报告生成 DONE MUST NOT 等价于人工复核、清单确认或修复完成。

#### Scenario: 待复核和首次未确认
- **WHEN** 当前有效报告生成成功且有待复核条目，或待复核为零但尚未手动确认
- **THEN** 推荐 SHALL 为 report，摘要 SHALL 分别表达 PENDING_REVIEW 或 UNCONFIRMED
- **AND** report 阶段 SHALL 保留真实生成完成状态，不自动提交清单

#### Scenario: 确认后再次编辑
- **WHEN** 人工编辑使旧确认不再匹配当前草稿
- **THEN** 推荐 SHALL 为 report，confirmationState SHALL 为 NEEDS_CONFIRMATION
- **AND** 即使仍有待复核项，摘要 SHALL 同时保留待复核计数与需重新确认的事实

#### Scenario: 有效非空确认清单
- **WHEN** 当前确认清单匹配源码、Context 和草稿版本且 itemCount 大于零
- **THEN** 推荐 SHALL 为 manual，不因支线未完成或独立构建成功推断人工修复已完成

#### Scenario: 有效空确认清单
- **WHEN** 当前确认清单有效且 itemCount 为零
- **THEN** 摘要 SHALL 为 CONFIRMED_EMPTY，推荐 SHALL 进入后续验证分流而非 manual
- **AND** 系统 MUST NOT 创建修复记录或把未执行的构建、测试标记成功

#### Scenario: 当前报告重新生成中
- **WHEN** 与当前输入身份匹配的报告任务排队或运行，已有旧报告可读
- **THEN** 推荐 SHALL 为 report，摘要 SHALL 为 GENERATING，旧报告和清单资格 SHALL 继续按既有发布与有效性规则处理
- **AND** 旧报告 DONE MUST NOT 掩盖当前生成任务

### Requirement: 推荐不得借用历史或不完整事实推进主线
系统 SHALL 使用当前源码、Context、报告发布状态、草稿和确认版本判断有效性；历史记录与当前推荐 SHALL 分离。缺失记录 SHALL 返回明确不可用摘要，不得使 overview 产生未处理异常。

#### Scenario: Context 改变
- **WHEN** 源码、语言、源架构或被纳入的分析输入改变使旧报告不再适用于当前 Context
- **THEN** 旧报告及确认 SHALL 不再推动 manual 或验证分流
- **AND** 当前输入满足报告生成时 SHALL 推荐 report，否则 SHALL 回到对应主线准备步骤

#### Scenario: 复核数据或确认记录缺失
- **WHEN** 报告存在但复核工作区缺失，或引用的确认记录无法解析
- **THEN** overview SHALL 返回 UNAVAILABLE 及不可用清单资格，不将未知条目数伪装为零
- **AND** 推荐 SHALL 指向 report 的恢复入口，历史读取契约 SHALL 保持不变

#### Scenario: 旧身份的生成任务仍在运行
- **WHEN** 活动生成任务的源码或 Context 与当前输入不匹配
- **THEN** 该任务 MUST NOT 使当前摘要成为 GENERATING 或抢占当前主线推荐

### Requirement: 后续验证推荐必须保持既有顺序和真实性
有效空清单后的推荐 SHALL 先处理构建验证，再处理对应成功构建验证的自动化测试；优化与归档 SHALL 继续遵守已有有效性与 readiness。没有可处理动作时 SHALL 返回 null，不无条件回退 archive。

#### Scenario: 构建验证尚未成功
- **WHEN** 当前空清单有效但缺少对应成功构建验证
- **THEN** 条件具备时 SHALL 推荐 build，必要环境缺失时 SHALL 推荐 env
- **AND** 缺少前置 x86 成功 MUST NOT 单独阻塞构建验证

#### Scenario: 构建成功但缺少匹配测试
- **WHEN** 当前构建验证有效成功而对应测试尚未成功
- **THEN** 推荐 SHALL 为 test，旧版本测试成功 SHALL 不得用于跳过

#### Scenario: 结束与不可执行终端步骤
- **WHEN** 当前后续步骤全部有效完成，或仅剩前置条件不满足且没有可处理入口的终端步骤
- **THEN** 推荐 SHALL 为 null，不把未满足条件的 archive 作为兜底

### Requirement: 推荐接口必须兼容现有阶段事实并提供明确联调依据
系统 SHALL 保留 stage ID、展示顺序、status、canStart、optional 和 currentFixList 的既有语义，增量提供 reportReview 摘要。对接文档 SHALL 要求消费服务端推荐，不以旧 KB/x86 覆盖规则重算。

#### Scenario: DONE 报告仍被推荐
- **WHEN** report.status 为 DONE 但报告尚未确认
- **THEN** recommendedStageId SHALL 为 report，reportReview SHALL 提供未确认或待复核事实
- **AND** 客户端 SHALL 能从契约辨认推荐不是重新生成授权

#### Scenario: KB 与 x86 均未执行
- **WHEN** 无报告输入且 KB 与 x86 状态同为 PENDING，KB 可执行
- **THEN** 后端推荐 SHALL 为 kb，并提供前端旧规则可能改写为 x86-build 的联调说明及夹具

#### Scenario: 权限和只读一致性
- **WHEN** 用户读取有权限项目的 overview
- **THEN** 推荐、复核摘要与当前清单 SHALL 来自同一次只读一致性读取，不调用 Agent 或远程环境
- **AND** 无权限用户 SHALL 沿用现有拒绝规则，不获得报告 ID、确认 ID 或条目计数

### Requirement: 实现必须通过可复现的单元测试和隔离验收
新增及受影响自有代码 SHALL 配备正常、边界和异常路径单元测试。整体及受影响模块的行和分支覆盖率 SHALL 各至少 90%，统计 SHALL 纳入未加载自有源码，JaCoCo 实际采集及报告缺失门禁 SHALL 被验证。

#### Scenario: 顺序无关与状态矩阵回归
- **WHEN** 执行本地 Docker 单元测试
- **THEN** SHALL 验证阶段排列变化、支线运行、报告各种复核状态、历史失效、权限和验证顺序，断言真实推荐与状态未被篡改

#### Scenario: 完整本地 Compose 验收
- **WHEN** 实现交付前进行验收
- **THEN** SHALL 在仓库根目录完整构建 Compose 并以一次 up -d --wait 启动全部默认服务，使用独立容器、网络、数据卷和测试账号
- **AND** SHALL 保存实际命令、健康状态、日志、代表业务结果和覆盖率报告，不连接已有部署数据或真实 SSH 环境
