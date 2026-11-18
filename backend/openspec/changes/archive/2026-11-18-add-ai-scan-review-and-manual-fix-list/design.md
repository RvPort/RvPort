## Context

本设计的项目路径均相对于仓库根目录。唯一 OpenSpec 根是 backend；当前分支 dev。依据 business-baseline.md（用户提供原文）以及本线程后续确定的规则：依赖只纳入未解决的中及以上或未知项；文件树只处理绑定版本的已知源码；只修改后端。

现有 ReportService 创建 REPORT_GENERATE，worker 再读取 CONFIRMED migration_input_refs；ReportFileContextSnapshotter 只保存有发现文件；MigrationInputSourceResolver 已有四类来源，但最新有效性过滤和默认导入不统一。旧 KbAiExtensionAgentRuntime 支持建议文件协议与部分成功，不能直接作为新报告完整成功标准。既有任务身份、租约、SSE、个人模型、受管 Agent、对象存储和项目授权可复用。

基线中的“项目模型配置”在本 change 解释为当前触发者个人阶段配置；原文保留供对照，不恢复项目共享模型。基线和后续补充优先于旧规范；差异由本 change delta 显式处理，不能只在实现中绕开旧要求。

## Goals / Non-Goals

**Goals:**

- 在现有评估报告阶段实现先生成、再人工复核、最后确认清单；报告、草稿、确认记录彼此独立。
- 来源选择、生成前预览、任务创建、当前结果与下游消费使用同一判定策略。
- 完整保留原扫描、AI 建议、人工证据和历史版本；用户不能从报告 API 越权读取别人的项目或模型配置。
- 给前端提供固定契约与可重复本地夹具；完成规定的 Docker 测试和覆盖率。

**Non-Goals:**

- 不修改前端或其它 OpenSpec 根；不新增工作流节点、团队模型、扫描器能力、自动代码修复或生产基础设施。
- 不更新 Agent CLI/第三方代理版本，不访问远程环境，不恢复退役 CI/E2E 设施。
- 不把 AI 判定、空清单、人工排除或报告差异当作代码已修复或优化验证通过。

## Decisions

### 1. 业务对象和数据边界

复用 reports/report_inputs/stored_artifacts，新增专用关系表而非把全部问题放入单个 JSON：

| 对象 | 建议持久化内容 | 约束 |
|---|---|---|
| 报告生成 manifest | sourceRevisionId、源码内容指纹、四类运行 ID/结果版本/上下文、输入策略版本、候选与 AI 输入清单、继承基线 | 创建时固定，worker 不重新选择 latest |
| 原始报告输入 | 类型、来源运行、sourceRefId、原证据、位置、风险语义、内容摘要 | 同一 manifest 内来源唯一；与项目和源码一致 |
| AI 复核记录 | reportId、attempt、inputId、verdict、双语依据/建议、evidence refs、有效性 | 每次尝试每输入最多一条；只发布完整有效尝试 |
| 复核草稿 | reportId、version、条目状态、人工项、继承关系 | 每报告一份；项目内唯一当前可写报告 |
| 人工操作事件 | actor、时间、operationId、前后版本、动作、原因、条目快照 | 追加写，不能用最后状态代替审计 |
| 确认快照 | reportId、draftVersion、confirmationId、条目与排除事实、安全摘要、确认人/时间 | 不可变，reportId+draftVersion 唯一 |
| 当前报告指针 | projectId、sourceRevisionId、reportId、generationSequence | 原子发布，失败/迟到任务不能覆盖 |
| 已知源码清单 | 相对路径、fileKey、hash、大小、类型、可读性、受控制品引用 | 绑定源码版本，零发现文件也在清单内 |

原报告风险摘要保持生成事实；人工状态计数放在 draftSummary，确认快照单独提供。人工条目使用独立 UUID 和 MANUAL 来源，不伪造扫描器 ID，不写回原始 Finding。

替代方案：直接修改 report_inputs 会破坏下载与历史；仅扩展 migration_input_refs.status 无法表达多个报告、继承版本和并发确认，均不采用。

### 2. 四类来源选择与生成资格

引入统一 ReportInputSelectionPolicy。readiness、input-preview、创建事务、有效性查询共用；读取无写入副作用。

- 必须存在当前 active source revision；显式 sourceRevisionId 与当前不同返回 Context 冲突，不从旧版本生成“当前报告”。
- 各来源先校验同项目/同源码和其实际依赖的环境、构建、配置及产物上下文，再选择该上下文内最新成功、已完整发布且未被有效新成功运行替代的结果。
- 知识库以现有 KbScanContextService 的源码/源环境版本/x86 buildRunId 精确匹配，状态 SUCCEEDED；不能回退其他构建。
- 依赖以同源码、实际源/目标环境快照及运行环境配置版本选 SUCCEEDED，不只按源码找历史成功。
- 浮点/内存序选 COMPLETE 或 COMPLETE_WITH_RISK，并核对实际分析模式、来源配置及 artifact-set/build 引用；PARTIAL 不满足本 change 的“成功完成”，也不进入默认报告问题输入，原因明确返回。
- 同上下文的新排队/运行/失败/取消不抹去旧成功事实，也不自动替代仍有效的旧成功；上下文变化则旧成功失效。KB 的新构建本身改变精确上下文，必须重新校验。
- 任一类存在合格运行即允许普通报告；其他类不阻塞。成功零发现仍合格。仅有历史/失败/PARTIAL 或完全无运行时拒绝。
- KB 包含低风险及需人工核对 Finding；浮点包含其有效 Finding；内存序仅正式 FINDING 与 SOURCE_CANDIDATE/INCONCLUSIVE 待核实问题，DISCHARGED 作为分析证据摘要，不伪造需决策问题。
- 普通和增强报告使用相同来源集合；增强仅额外执行 AI。无来源问题与所有问题已人工排除是两种事实，不能都呈现为零扫描发现。

生成资格从“有确认输入”改为“有有效成功分析”；不为读取或生成报告自动发起任何缺失扫描。

### 3. 依赖准入的确定性策略

谓词为 unresolved && (risk in MEDIUM/HIGH/CRITICAL || unknown)，不解析自然语言 recommendation。当前 risk 枚举没有 UNKNOWN，unknown 必须独立表达，不能把它归一为 INFO 后丢弃。

| 当前 targetMatchStatus | 判定 |
|---|---|
| TARGET_INSTALLED、SAME_MAJOR、TARGET_NEWER | 正常已满足且风险低于 MEDIUM、没有结构化未知/冲突证据时排除；仍有中以上风险时按未解决问题纳入 |
| REPO_AVAILABLE、PROVIDES_MATCH、TARGET_PACKAGE_AVAILABLE_SOURCE_UNOWNED | 尚需安装/provider 确认等，不等于已解决；按中以上或未知纳入 |
| TARGET_OLDER、NO_TARGET_VERSION_FOUND、VERSION_MISMATCH、SOURCE_BUILD_REQUIRED、MISSING | 未解决；按中以上或未知纳入 |
| MANUAL_CONFIRM_REQUIRED、缺失或未知状态/风险 | unknown=true，未解决，纳入并返回判定原因 |

“已解决”必须有当前有效运行的结构化已满足证据且没有剩余风险或未知；不能因旧自动修复任务成功、用户排除或标题匹配而判定。存在字段冲突时按未知纳入，保留诊断。明确低风险/INFO/COMMENT 且非未知的未解决项也不进入报告问题或 AI 输入。原依赖结果不删除。

实施将此表映射到 DependencyFactExtractor/结果字段并列举 providerVersionStatus、versionEvidenceConfidence 的现有枚举；不得把所有低 confidence 一律升级为未知，只有影响是否满足的缺失/冲突证据才适用。表驱动单元测试覆盖每种状态与风险组合。

### 4. 输入固定、源码与 Context

创建事务固定分析运行和逐项输入快照、继承来源报告及草稿版本、source revision 的不可变身份。分析结果表需发布后不可变；不能把仅含 ID、随后动态回查可变详情当快照。

源码由固定 archive 内容哈希或固定 Git commit 的本地内容构建版本清单，禁止重新跟随 branch 或读取 active 工作副本替代。若源版本尚无可信内容指纹，先建立持久源码 manifest 再投递任务；在事务外做文件 IO，事务内校验指纹和选择令牌仍一致。无法提供可信旧源码时显式 contextAvailable=false，不伪造内容；普通报告仍保留问题，AI 对无源码项只能基于现有证据给依据不足，不得引用不存在片段。

source manifest 与报告 file context 制品通过对象存储固定；Agent 使用专用副本/只读挂载，仅 output 可写，执行前后验证输入哈希；不能只依赖 prompt 禁止修改。不访问真实 SSH、不允许路径穿越、绝对路径、符号链接逃逸、任意 URL 或宿主命令覆盖。报告文本/源码视为待分析数据，不是平台指令。

ReportContext 指纹含源码与所选有效分析运行/结果版本/相关环境引用以及输入策略版本，不包含用户草稿状态。创建时得到 selectionToken；提交与发布均复核。生成中 Context 变化仍允许保存原输入的历史报告，不允许其成为当前报告。

### 5. AI 模式与完整性

用户再次确认：AI 增强复核复用个人配置中的 Agent runtime + 模型 profile；个人阶段目录将旧 KB_AI_EXTENSION 替换为 REPORT_AI_REVIEW，而不是新增另一套独立模型配置。通用模型/Agent Probe 继续验证文本与 workspace 工具闭环，新复核输出协议由 REPORT_AI_REVIEW 执行校验和回归测试覆盖。无旧数据兼容方案保持不变，初始化后用户重新绑定新阶段，不迁移旧绑定。

mode 显式 STANDARD 或 AI_ENHANCED；readiness 推荐模式取决于当前用户 REPORT_AI_REVIEW 个人绑定可用性，不能跨用户缓存。mode 必填；缺失或未知值返回参数错误，不为旧调用兜底。

任务创建保存 configurationUserId 与选定 profile/runtime 引用；每次领取重新校验账号、项目、归属与启用状态，冻结该次参数和安全版本摘要。自动重试不换人/改绑；手动重新生成使用当前操作者。复用现有 AgentRuntimeAdapterRegistry 和 Codex CHAT/Responses 适配，不能为报告新增平台共享模型。

manifest 分离所有候选与 aiInputIds。仅可可靠继承的人工 EXCLUDED 条目不送 AI，仍保留报告来源事实、排除状态和继承证据。普通模式无模型调用。AI 模式即使实际输入为零仍验证个人配置，但不发模型请求，返回 aiExecution=NOT_REQUIRED、原因 NO_REVIEW_INPUTS，不能伪造执行时长或结论。

每个 AI 输入必须恰有一条合法结果，verdict 为 CONFIRMED_ISSUE / SUSPECTED_FALSE_POSITIVE / INSUFFICIENT_EVIDENCE；支持多证据位置，引用固定源码/依赖事实。必须具备双语依据和建议（建议可为空但原因明确），不能输出任意新 ID 或声称改变内存序原 verdict。缺失、重复、非法 ID、解析错误、越界证据、失败退出或超时均使本次增强任务失败。失败尝试合法部分仅作受控诊断，不发布为成功报告。普通重生成须由用户明确选择。

草稿在整批 AI 结论校验成功后初始化。CONFIRMED_ISSUE 对应 TO_FIX，SUSPECTED_FALSE_POSITIVE 和 INSUFFICIENT_EVIDENCE 对应 PENDING_REVIEW；普通报告新项为 PENDING_REVIEW。初始化不生成确认快照。AI 判定与内存序 FINDING/SOURCE_CANDIDATE/INCONCLUSIVE 原始类别使用独立字段。

### 6. 草稿、操作与框选

扫描问题使用上述初始状态；用户动作 INCLUDE → TO_FIX、EXCLUDE → EXCLUDED、RESTORE → 最近一次有效排除前状态（缺失则 PENDING_REVIEW）。排除原因必填；恢复不会恢复旧确认资格。人工创建明确提交待修复意图，初始 TO_FIX；删除人工项使用 EXCLUDE，保留审计，无硬删除 API。

一个 PATCH 接口接收 items 数组，1 条为单次、多条为批量；同批次允许不同动作，重复 ID 拒绝。校验整个批次归属、当前可写性和 expectedVersion 后原子写入，版本只递增一次。无实际变更的重放不新增事件或递增版本。更改已确认草稿立即使 confirmationState=NEEDS_CONFIRMATION；保留 previousConfirmationId，但 currentConfirmationId=null。

已知源码文件树来自固定版本 manifest，而不是问题路径的并集；包含零发现文本文件。二进制/超限文件可显示元数据和不可读原因。依赖或无法定位问题在全局列表存在，不生成“虚拟源码文件”。全局计数不受 fileKey/page 过滤影响，返回 filteredTotal 单独区分。

框选采用 1-based 行、1-based UTF-16 列、起点包含/终点不含；不允许切断 surrogate pair，换行统一按服务端逻辑行切分，原字节哈希不因换行显示被重写。必填 fileKey、sourceContentHash、range、description、repairReason；title 可显式提供或由描述生成显示标题。服务端取固定内容生成 snippetHash/片段，忽略或拒绝客户端伪造片段。原始人工文本不改写，英文字段可由用户提供；未提供时使用双语固定模板加原文引用，并标记 translationStatus=ORIGINAL_ONLY，不依赖额外模型才能创建。

### 7. 确认与下游

确认事务锁定草稿、校验当前 Context、expectedVersion、无生成切换冲突且全局 pendingReviewCount=0，保存包含全部处置事实和 TO_FIX 子集的不可变快照。空 TO_FIX 也创建确认，confirmationState=CONFIRMED_EMPTY；非空为 CONFIRMED_WITH_ITEMS。同 report+draftVersion 重复确认幂等返回同一记录。

下游通过统一 CurrentFixListResolver 取得可用确认 ID，而非读 migration_input_refs、最新任务或页面状态。未确认/修改后/历史/过期均不能用于创建新的修复相关执行；已有任务持久化其确认 ID，在启动时校验是否仍可执行，失效则受控拒绝，运行中的结果保留原引用但不能冒充当前有效结果。通用上传源码、独立构建和测试能力不被清单无条件禁用；只有以报告清单为依据的流程使用该门槛。

原报告比较仍比较不可变报告事实，集合消失返回 NOT_OBSERVED，不直接称 RESOLVED；清单比较显式使用两份 confirmationId，并区分 EXCLUDED/NO_LONGER_SELECTED/NEW/UNCHANGED/CHANGED/NOT_OBSERVED。只有存在独立可信验证证据时可报告已修复，当前 change 不新增此类自动证明。支持零对零、一侧空；“空快照”与“不支持的旧 schema”分开。源码 recheck 现有入口改读明确确认快照，不回退旧 refs。

优化概览暴露确认状态、待处理数量、可用确认 ID 和原因；CONFIRMED_EMPTY 仅表示无需清单修复，不绕过既有构建/测试验证链，也不自动触发下一节点。

### 8. 继承、幂等与发布

问题身份匹配先按来源类型、规则/分析类别、规范化路径或依赖坐标、符号/稳定 anchor，再比较证据语义摘要、位置、文件内容 hash 和结果策略版本。忽略运行 UUID、时间等非语义字段，不忽略风险/正式性/目标匹配/关键证据变化；不能只按标题或行号匹配。一对多/多对一歧义不继承，返回 MATCH_AMBIGUOUS；实质变化返回 EVIDENCE_CHANGED，缺失位置返回 LOCATION_UNVERIFIABLE。同版本可靠匹配时，显式人工处置、人工提交的清单处置以及可靠继承的人工处置优先。待复核或仅有自动默认值时，使用本次结论。用户首次显式保留 AI 默认待修复项会记录处置、更新版本；重复保留沿用已有决定。跨版本完全不继承。

人工项只在同 revision、同 path/hash 和精确 range/snippetHash 验证成功后继承；不能定位则记录未继承原因和原项引用，不静默消失。已不在当前分析范围的旧扫描问题只存在于继承摘要/旧报告，不重新加入当前候选。

跨来源重复仅分组展示，不在第一版合并决策身份；每一来源问题仍有独立 AI 结果、状态和来源 ID。分组批量操作显式展开条目 ID；counts 明确 rawFindingCount/decisionItemCount，禁止合并导致漏项或虚假降风险。

创建使用 Idempotency-Key，作用域 actor+project+操作；同 key 同规范化请求返回原任务，同 key 不同请求 409。项目同一时刻只允许一个活动报告生成，其他请求返回 REPORT_GENERATION_IN_PROGRESS 与可访问任务引用，不跨用户返回私人配置。用户重试已失败任务必须新请求 key；自动重试保持原 manifest 和身份。

重新生成不锁住整个长任务期间的旧草稿。创建固定继承草稿版本；发布事务检查该版本仍一致、Context 未变、generationSequence 未被替代。旧草稿期间被修改/确认则返回 REPORT_REVIEW_BASE_CHANGED，保留诊断结果并将生成标为失败，旧报告不变；用户重新发起。Context 变化则结果可成功保存为历史，publishState=HISTORICAL_CONTEXT_CHANGED，无可编辑草稿。发布成功才冻结旧草稿并切换当前指针，新草稿始终未确认。

latest 分开 currentReport 与 latestGeneration；失败、进行中、历史成功不遮蔽仍有效的 currentReport。分析新成功结果使 Context 指纹变化，旧报告标为 STALE/只读且不再提供当前清单；生成失败不能复活已失效旧报告。没有变化的同上下文扫描失败不清除旧有效报告。

### 9. 接口和旧能力边界

接口详见 api-contract.md；复用任务查询/SSE、报告生成/历史/下载，不为每个动作新增端点。当前工作台新查询与历史模块查询并存；业务说明双语、枚举技术英文。原始报告下载始终固定；新增确认版本下载使用同一报告 download 接口的 confirmationId 参数，生成/持久保存该确认的独立制品，不动态渲染当前草稿。

旧 KB AI 的创建、readiness、list/detail/artifact 下载路由全部删除，不保留历史查询层、任务类型业务 handler 或旧制品 owner 解码兼容。旧 migration-inputs import/auto-merge/单项 PATCH 及其专用读取接口同时删除，不保留 409 过渡接口；删除 migrationInputRefId/migrationInputStatus/confirmedForNextReport 等旧流程字段及无其他用途的存储、索引同步逻辑。includedInLatestReport 若仍有业务用途，直接按当前报告真实来源引用计算。

保留现有源码 recheck/compare 能力时直接改用新确认快照契约，不承担旧数据解码或旧请求字段回退。新报告 mode 必填，移除省略字段时为旧客户端兜底的 STANDARD 行为。缺个人 REPORT_AI_REVIEW 绑定时提示用户自行配置，不从旧阶段搬迁或回退 admin。现有前端需要按新契约后续适配，本次不修改。

这里的“历史报告”始终指新流程运行后自然产生的前序报告；不要求读取变更前的报告、旧 SBOM_RESULT/MIGRATION_INPUT_REF 或旧 RESOLVED 比较制品。新流程的原始记录、人工审计、只读历史和确认版本下载要求不变。

### 10. 本地验证和独立根

所有代码、数据库迁移、jOOQ、依赖安装、测试与构建在本地 Docker；仅 backend 修改。根目录完整 Compose 构建全部默认服务，以独立项目/env/端口/网络/卷执行一次 up -d --wait，检查 API、worker、日志及业务流程；前端服务仅随默认服务构建启动，不宣称页面已适配。

Java 统计全部自有生产类（含未加载），仅明确排除自动生成 jOOQ；Python/资源/CLI 脚本全部纳入，整体和完整受影响模块/脚本行及分支各至少 90%。不得通过缩小分母、忽略新来源策略或复用旧报告绕过门槛。测试包括所有基线场景、旧状态冲突、并发事务、全新初始化/事务回滚、A/B/admin 权限、真实受管 Agent 对本地模拟上游、源码完整性和零副作用负例。原始报告导出 .migration-reports/，不得提交秘密或运行数据。

## Risks / Trade-offs

- [旧前端依赖节点确认写接口] → 删除旧路由并明确新契约的破坏性变化；只交付后端，不以旧页面可用宣称验收。
- [源码快照体积与路径攻击] → 复用内容寻址制品，分页/区间读取，显式大小限制和 unavailable 原因，路径和 symlink 校验，不把工程生成目录无限复制。
- [“未知”过度纳入] → 依赖准入表与结构化原因，单测覆盖全部枚举，正常低风险不能仅因文本含“确认”被纳入。
- [用户重生成期间修改草稿] → 乐观基线校验，发布冲突明确失败，不覆盖人工操作；不自动重跑产生额外模型调用。
- [新上下文使旧报告失效] → 历史可读且不混用清单；提供明确恢复入口，缺少模型不影响 STANDARD。
- [AI 内容错误或代码提示注入] → 结构化验证、输入只读与 workspace 隔离、证据 hash 校验；模型仍只是建议，最终人工确认。
- [去重误合并] → 第一版只分组展示，保留逐来源身份；不做模糊自动合并。
- [旧规范仍约束确认后生成] → delta 明确移除/替换冲突要求，实施与 sync 均检查规范引用，不能仅增加新 spec 让矛盾并存。

## Migration Plan

本项目仍在开发阶段，用户明确允许抛弃旧数据。本次采用全新初始化，不建设旧数据转换或兼容层。

1. 新版本只支持新报告/草稿/确认 schema 和 REPORT_AI_REVIEW 个人绑定。移除旧 KB AI 与节点确认的接口、handler、专用表/字段/制品读取分支及旧状态枚举；仅删除本功能相关且无其他调用方的路径，不扩展到无关系统。
2. 为指定开发实例说明切换边界：停止其旧 API/worker，清理该实例的数据库、队列及关联对象制品/工作区后重新初始化，不能只清数据库而留下旧任务与悬空制品。新旧 worker 不混跑，不实现旧任务排空续跑、翻译、恢复或逐条取消业务。
3. 明确全实例重置会使账号、项目、源码、分析、模型配置/密钥等开发数据需要重建；不承诺选择性保留或自动迁移。重新建立账号与个人模型配置，用户自行绑定 REPORT_AI_REVIEW；初始化脚本不植入真实密钥。
4. 实施阶段仅在本轮独立本地 Docker 资源演练初始化/重建、事务失败回滚、任务/制品隔离。此数据策略不授权清空共享本地部署或任何远程实例；本轮提案也不执行数据删除。
5. 沿用项目 schema 管理工具建立可重复的空库初始化流程，删除相关废弃 schema 的终态依赖；不为存量数据增加 backfill、双读、schema 版本兼容或旧记录迁移测试。与本功能无关的历史 changelog 不为简化而整体重写。
6. 交付后端切换与初始化说明，标注接口破坏性变化、数据丢失范围及必须重新配置事项。回退到旧程序同样使用匹配版本的干净开发数据，不承诺数据保留式降级。
7. 新版本产生的数据从首次生成起遵循不可变报告、持久草稿、人工审计、确认快照和历史只读规则；重新生成与 Context 失效测试照常保留。实现、单测、90% 覆盖率及本地 Compose 全部通过后再按用户请求 sync/archive。

## Open Questions

无阻塞产品问题。以上为本次可评审技术选择；实施时需核对实际依赖 evidence 枚举和四类 Context 字段映射，若无法满足表中规则必须更新方案与基线映射，不能自行放宽成功状态或身份约束。全部 API 示例和限制见 api-contract.md，验收覆盖见 requirements-mapping.md。
