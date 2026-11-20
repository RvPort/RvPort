## Why

知识库与两个专项分析目前被成功 x86 构建、编译数据库及构建身份绑定阻塞，导致只有源码时无法尽早发现潜在问题；报告又排除了合法的部分分析结果。现有报告 AI 复核与人工确认已能收敛候选，应让分析阶段优先召回，同时如实保留证据缺口和真实验证状态。

## What Changes

- 前置 x86 构建成为可选增强步骤；未运行就是未运行，不新增跳过按钮、跳过 API、跳过状态或伪造成功构建。
- **BREAKING**：知识库、浮点、内存序分析改为按固定源码及分析输入能力准入，不再强制成功构建、远程环境或编译数据库。可信产物可增强；没有产物时使用可审计的内部源码分析计划，记录默认解析假设、覆盖分母和不可分析范围。
- 承接 PR #22 的 KB 多语言、源架构和内存预算：KB 保留所选支持语言，专项仅分析自身支持范围；语言、源/目标架构及范围进入固定身份，增强产物必须匹配。补齐内存超限诊断、worker 恢复、部署重试身份校验，以及 JaCoCo 真实采集和报告缺失失败门禁。
- 分析优先召回真实源码线索，允许候选较多或判断不准确；保留低置信、上下文不足及部分结果，不伪造位置、编译参数来源或已证明风险，不修改独立工具的规则实现。
- 依赖分析继续要求当前有效成功 x86 构建及环境；后续构建验证只要求源码、所需环境和有效构建配置，自动化测试仍在相应成功构建验证之后，优化闭环仍要求完整验证链。
- **BREAKING**：统一 readiness、创建与执行、overview、latest、推荐阶段和按实际依赖定义的 Context；可选步骤未运行或失败不阻塞无关源码分析。
- 报告接收当前有效、完整发布的 PARTIAL/无法判定结果，区分未执行、技术失败、分析缺口和范围内零发现；固定产物选择、覆盖和限制，原始扫描事实不被 AI 判断覆盖。
- AI 只复核选定分析产出的条目，输入包含对应版本源码与缺失证据；确认问题默认待修复，疑似误报/证据不足待复核，人工决定优先且最终清单手动提交。多候选完整分批复核，不能静默截断或自动排除未复核项。
- 提供中文后端对接说明、双语业务字段及本地联调夹具。实现须完成 Docker 单元测试、整体和完整受影响模块行/分支各至少 90% 及完整本地 Compose 验收。

## Capabilities

### New Capabilities

- `source-analysis-input-policy`：可选构建、固定源码输入计划、可信增强制品、按实际依赖定义的 Context 和高召回候选边界。

### Modified Capabilities

- `kb-scan-runtime`：源码独立准入、可选编译上下文、固定输入与有效性，清理与已退役节点确认/KB AI 冲突的残留要求。
- `floating-point-analysis-runtime`：无构建与无编译数据库输入、合法部分产出及证据来源。
- `memory-order-analysis-runtime`：无构建数据库的内部源码计划、统一 CLI 与开放范围、候选及未知结果。
- `source-deploy-runtime`：按任务复用部署时校验完整身份，防止重试串用源码、环境、路径或过期租约。
- `build-validation-runtime`：区分可选前置构建与独立后续验证，保持真实运行结果。
- `dependency-analysis-runtime`：维持有效成功构建门槛并与统一状态判断对齐。
- `automated-test-runtime`：保持构建验证先于测试，并修正 readiness 对测试历史及单侧构建的错误替代。
- `backend-api-design`：一致的节点资格、可选性、推荐阶段、来源能力与证据缺口接口。
- `backend-database-design`：可空构建引用、不可变分析输入、覆盖及候选分批复核记录。
- `migration-report-runtime`：合法部分来源、未分析范围和不同来源状态的固定报告输出。
- `report-ai-review`：部分来源准入、真实候选与证据限制、完整分批 AI 复核。
- `report-review-transition`：实际输入变化、同版本人工继承与并发发布的有效性。
- `optimization-cycle-runtime`：不将可选分析构建或 AI 结果替代后续完整验证链。

## Impact

- 唯一 change 和实施根为 backend；保持 dev。根、frontend、tools 各自的 OpenSpec 根和源码不修改；根目录只用于调用既有完整 Compose 构建及隔离验收。
- 主要涉及 src/main/java 下 kb、analysis、buildvalidation、dependency、workflow、project、report/review、automatedtest、optimization、source/deploy、config；以及 src/main/resources/analysis 输入适配脚本、数据库迁移与 Docker 生成的 jOOQ、pom.xml 的测试代理配置、相关测试及 docs。
- 基线包含 fc125e1（PR #22）。与 `2026-11-18-multilang-scan-request-and-memory-budget` 的范围映射见设计；本次仅编辑本 change，不同步、归档或修改该已合并 change 的文档，不把其测试记录当作本次验收。
- 复用现有锁定 CLI/LLVM/Agent 与个人 REPORT_AI_REVIEW 绑定，不新增 Compose 基础设施、不升级工具依赖、不访问或修改远程 dev/test。
- 新数据协议区分执行结果、证据完整性和业务处置；历史产物不回填为新结论。依赖历史成功构建的当前选择规则必须统一，禁止串用旧源码、旧环境或无归属制品。
- 前端本轮不修改；接口扩展及必要破坏性变化须提供字段、枚举、错误码与实例，使后续对接不把 PENDING、PARTIAL 或零问题误判为安全。
