## Purpose

定义报告对四类当前有效分析的选择、依赖准入与风险语义、固定源码及证据，以及使用触发者个人 Agent 和模型进行逐项 AI 复核的完整成功标准。

## Requirements

### Requirement: 报告输入必须来自四类当前有效成功分析
后端 SHALL 统一选择当前项目、active 源码及实际依赖 Context 下的知识库、依赖、浮点、内存序结果；至少一类有效成功运行才可生成，MUST NOT 以旧 reference 确认状态或 Finding 数量代替生成资格。

#### Scenario: 一类成功且零发现
- **WHEN** 唯一有效分析成功完成但零发现
- **THEN** SHALL 允许普通报告及显式空清单确认，记录运行 ID 和零计数；其余来源标为未运行或不可用。

#### Scenario: 没有合格成功运行
- **WHEN** 无 active 源码或四类均未运行、失效、失败、取消、超时或仅 PARTIAL
- **THEN** SHALL 拒绝创建并返回双语原因和恢复事实；历史仍可读，不投递任务。

#### Scenario: 四类状态与上下文
- **WHEN** 查询最新有效结果
- **THEN** KB/依赖 SHALL 要求 SUCCEEDED；专项 SHALL 要求 COMPLETE/COMPLETE_WITH_RISK 且完整发布；分别校验源码、实际环境/构建/artifact-set 及结果版本，不跨上下文回退。

#### Scenario: 新运行尚未成功
- **WHEN** 同一有效 Context 的新运行排队、失败或取消
- **THEN** SHALL 保留仍有效旧成功运行作为可选事实；Context 变化时不得回退旧结果，新有效成功发布后旧运行不再作为当前输入。

### Requirement: 依赖候选必须仅包含未解决的中以上或未知项
后端 SHALL 使用结构化策略判断 unresolved && (MEDIUM/HIGH/CRITICAL 或 unknown)，MUST NOT 按建议文本、人工排除或旧修复任务状态推断已解决。

#### Scenario: 排除已解决及明确低风险
- **WHEN** 当前依赖已满足无剩余问题，或未解决但风险 LOW/INFO/COMMENT 且非未知
- **THEN** SHALL 不进入报告问题或 AI 输入；原始分析保留，成功运行仍满足生成资格。

#### Scenario: 未知独立于风险枚举
- **WHEN** 状态缺失/非法、MANUAL_CONFIRM_REQUIRED 或影响是否满足的证据缺失/冲突
- **THEN** SHALL 作为未知未解决项纳入并返回结构化原因；不得归一成 INFO 后丢失或标为确定风险。

#### Scenario: 可安装不等于已解决
- **WHEN** 结果为 REPO_AVAILABLE、PROVIDES_MATCH 或需源码构建等待处理事实
- **THEN** SHALL 按设计的状态/风险表判断，不因可找到包就直接排除中以上未解决项。

### Requirement: 候选与 AI 输入必须分别固定并保留风险语义
后端 SHALL 创建时固定源码指纹、四类运行、逐项原始证据和输入策略版本；KB 低风险、内存序待核实候选 SHALL 可进入，正常依赖和 DISCHARGED 证据不伪造问题。

#### Scenario: 任务排队期间新扫描发布
- **WHEN** 当前分析输入改变
- **THEN** SHALL 使用原 manifest，不能 worker 执行时重新读取 latest；发布时重新判断当前有效性。

#### Scenario: 人工排除继承
- **WHEN** 旧人工排除可在同版本可靠匹配且证据位置未实质变化
- **THEN** SHALL 保留来源快照与排除审计但从本次 AI 输入跳过；发生实质变化则重新提交 AI 并按本次结论初始化。

#### Scenario: 内存序候选被建议保留
- **WHEN** AI 或用户建议处理 SOURCE_CANDIDATE/INCONCLUSIVE
- **THEN** SHALL 保留原类别，不能升级 FINDING 或计入已证明正式风险。

#### Scenario: 来源重复
- **WHEN** 多个来源看似描述同一问题
- **THEN** SHALL 第一版仅分组展示并保留逐来源身份、证据、处置与计数，不按标题近似丢弃记录。

### Requirement: AI 必须使用触发者个人配置并完整成功
后端 SHALL 使用 REPORT_AI_REVIEW 个人阶段绑定，复用既有持久身份/租约/恢复机制；AI 结论用于初始化未获人工处置的草稿条目，人工决定优先，最终确认须手动提交。

#### Scenario: 配置与模式
- **WHEN** 当前触发者无有效个人绑定
- **THEN** SHALL 默认建议 STANDARD，禁止 AI 创建但不阻止普通报告；有绑定时建议 AI_ENHANCED，读取不调用模型。

#### Scenario: 当前配置与历史测试分离
- **WHEN** 页面查询当前用户的报告 AI 配置状态
- **THEN** SHALL 根据 REPORT_AI_REVIEW 的个人绑定、profile/runtime 启用状态和必填配置判定，不要求 probe 成功，不调用模型；aiBlockers 为空表示配置满足要求，canGenerateAi 还须满足有效分析输入资格，配置成功不承诺实际运行成功。
- **AND** SHALL 可复用 `GET /api/me/model-stage-assignments` 展示当前绑定摘要，不用旧测试成功状态代替当前配置状态。

#### Scenario: 完整输出
- **WHEN** N 个实际 AI 输入执行结束
- **THEN** SHALL 恰有 N 个有效唯一结果，校验枚举、双语依据、证据归属和范围；漏项、重复、未知 ID、非法结构/位置、非零退出或超时均不能发布成功增强报告。

#### Scenario: 依据不足与失败
- **WHEN** 输出合法 INSUFFICIENT_EVIDENCE 或执行发生技术失败
- **THEN** SHALL 分别记录合法建议与任务失败，不能用依据不足掩盖漏项/技术失败，不自动切换普通模式。

#### Scenario: 没有实际 AI 输入
- **WHEN** AI 模式全部跳过或零问题
- **THEN** SHALL 校验个人配置但不调用模型，记录 NOT_REQUIRED 和原因；不能伪造已复核结果。

#### Scenario: 重试恢复
- **WHEN** 自动重试/恢复或另一用户手动重新生成
- **THEN** SHALL 前者保持原主体与固定引用，后者建立新执行使用新操作者；每次领取校验账号、项目及配置归属。

### Requirement: 源码与 Agent 输入必须固定且受控
后端 SHALL 以版本 manifest/内容哈希和制品固化源码，Agent 只读来源、专用 output 可写；MUST NOT 使用当前工作副本替代历史源码或把源码文本当平台指令。

#### Scenario: 源码不可读
- **WHEN** 文件缺失、二进制、超限、编码非法或路径不安全
- **THEN** SHALL 保留问题与稳定原因，普通报告可继续；AI 不得编造源码位置，人工框选不可用。

#### Scenario: 工作区隔离
- **WHEN** 执行 Agent 或下载源码上下文
- **THEN** SHALL 校验项目/文件/版本，限制路径与符号链接，验证输入完整性，拒绝真实源码修改和跨用户工作区读取；输出不泄漏凭据或内部路径。
