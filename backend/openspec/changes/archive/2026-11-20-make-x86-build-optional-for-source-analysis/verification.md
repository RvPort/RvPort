# 源码分析可选构建验收记录

## 当前结论（2026-11-20）

最终单元快照为 `rvport-optional-quality-1120v`，不是下方早期探针或失败轮次。当前源码指纹已与本轮 receipt 再次比对一致。最终 Compose 结果见本文件末尾“最终 Compose 补充”；未记录通过的项目不能由早期结果推断。

- Java：1932 项，0 失败/错误，7 项数据库条件测试在普通单测中跳过；随后独立启用 6+1 项，全部通过且无跳过。
- Python：209 项通过，0 失败。
- Java 整体：行 23999/24879（96.4629%），分支 10565/11541（91.5432%）。
- Python 整体：行 1494/1522（98.1603%），分支 630/655（96.1832%）。
- 全部 Java 自有包及 Python 自有脚本均通过 90% 行/分支门禁，包含未加载源码。仅排除生成 jOOQ 与第三方 node_modules；无分支文件以 0 分母、指标不适用记录。
- 报告目录：backend 根下 `.migration-reports/optional-build/`（忽略且不提交）。最终文件为 `quality-v.log`、`quality-v-gate.json`、`quality-v-receipt.json`、`java-v-jacoco/index.html`、`java-v-jacoco/jacoco.xml`、`java-v-jacoco.exec`、`java-v-surefire/`、`python-v-coverage.json`；数据库为 `schema-final-v.log`、`schema-kb-v.log` 及各自 Surefire 目录。

## 最终执行命令与范围

宿主只调用 Docker、编辑及读取导出结果；每轮容器重新复制当前源码并清理输出，3 GiB / 2 CPU，依次执行。基础测试镜像本轮构建并预取锁定依赖，测试用 `rvport-optional-unit-deps:20261120`，全量测试禁用网络。

容器工作目录 `/workspace/backend`，实际执行：

```sh
python3 src/main/resources/analysis/coverage_gate.py start --receipt /tmp/receipt.json
mvn -B -o -s /opt/maven-cache/settings.xml -Dmaven.repo.local=/opt/maven-cache/repository clean verify
python3 -m coverage run --branch --source=src/main/resources/analysis,src/main/resources/bitcode,src/main/resources/agentruntime,agent-cli -m pytest src/test/python -q -p no:cacheprovider
python3 -m coverage json -o /tmp/python-coverage.json
python3 src/main/resources/analysis/coverage_gate.py verify --receipt /tmp/receipt.json --python-json /tmp/python-coverage.json
```

覆盖率门禁核验本轮源码指纹、测试数、exec/XML/HTML、编译类/源文件/全部包及 Python 全文件清单，再校验比例。6 个实际报告破坏反例（exec 缺失、XML 空、HTML 过旧、Java 缺包、Python 缺文件、全部跳过）均被拒绝，恢复后通过；记录 `coverage-negative.json`。另有相应正常/边界/异常单元回归，未以这些负向测试替代真实采集。

条件数据库测试只连本轮 `rvport-optional-schema-1120`，分别用已初始化的隔离库和 KB 专用空库；显式开启 `RVPORT_MIGRATION_TEST_ENABLED=true`、`RVPORT_REPORT_REVIEW_DB_TEST=true` 并注入该库连接变量。实际 Maven 命令分别为：

```sh
mvn -B -o -s /opt/maven-cache/settings.xml -Dmaven.repo.local=/opt/maven-cache/repository -Dtest=LiquibaseMigrationTests,PersonalModelMigrationTests,SourceAnalysisInputMigrationTests,ReportReviewDatabaseTests clean test
mvn -B -o -s /opt/maven-cache/settings.xml -Dmaven.repo.local=/opt/maven-cache/repository -Dtest=KbScanDatabaseTests clean test
```

jOOQ 在本轮 Docker 从当前迁移生成；迁移验证空库、历史升级、重复执行、可空关系/模式约束、跨项目外键及检查点唯一性。未修改历史迁移来掩盖测试隔离问题。

## 受影响模块最终覆盖率

| Java 完整包 | 行（命中/总数，%） | 分支（命中/总数，%） |
|---|---|---|
| cn/rvport/backend/analysis | 1866/1924，96.9854% | 1205/1294，93.1221% |
| cn/rvport/backend/automatedtest | 377/379，99.4723% | 133/146，91.0959% |
| cn/rvport/backend/automatedtest/runtime | 182/182，100% | 80/84，95.2381% |
| cn/rvport/backend/buildvalidation | 1114/1152，96.7014% | 490/543，90.2394% |
| cn/rvport/backend/buildvalidation/bitcode | 767/798，96.1153% | 433/478，90.5858% |
| cn/rvport/backend/kb | 824/844，97.6303% | 387/420，92.1429% |
| cn/rvport/backend/kb/runtime | 617/635，97.1654% | 397/427，92.9742% |
| cn/rvport/backend/optimization | 445/450，98.8889% | 165/174，94.8276% |
| cn/rvport/backend/project | 494/501，98.6028% | 215/231，93.0736% |
| cn/rvport/backend/project/runtime | 164/165，99.3939% | 72/78，92.3077% |
| cn/rvport/backend/report/review | 1187/1293，91.802% | 1058/1168，90.5822% |
| cn/rvport/backend/source/deploy | 546/567，96.2963% | 187/202，92.5743% |
| cn/rvport/backend/worker | 1028/1064，96.6165% | 311/344，90.407% |
| cn/rvport/backend/workflow | 249/255，97.6471% | 128/135，94.8148% |

| Python 自有脚本（全部统计） | 行（命中/总数，%） | 分支（命中/总数，%） |
|---|---|---|
| agent-cli/opencodex/patch_eof.py | 56/57，98.2456% | 18/19，94.7368% |
| agent-cli/verify_lock.py | 64/65，98.4615% | 41/42，97.619% |
| src/main/resources/agentruntime/agent_process_supervisor.py | 65/66，98.4848% | 21/22，95.4545% |
| src/main/resources/agentruntime/opencodex_guard.py | 189/192，98.4375% | 110/113，97.3451% |
| src/main/resources/agentruntime/opencodex_runtime.py | 163/165，98.7879% | 74/76，97.3684% |
| src/main/resources/agentruntime/report_review_sandbox.py | 79/81，97.5309% | 28/30，93.3333% |
| src/main/resources/analysis/coverage_gate.py | 106/107，99.0654% | 19/20，95% |
| src/main/resources/analysis/prepare_specialized_analysis.py | 358/373，95.9786% | 145/155，93.5484% |
| src/main/resources/analysis/source_analysis_plan.py | 71/71，100% | 47/47，100% |
| src/main/resources/bitcode/produce_bitcode.py | 343/345，99.4203% | 127/131，96.9466% |

## 过程记录（以下不是最终快照）

# 实施与验收记录

## 当前状态

实施进行中。多轮完整单元测试和覆盖率门禁已通过，后续修正仍须重验；完整 Compose 业务验收尚未完成，不具备归档条件。

### 本轮补充（2026-11-20，继续实施）

- `1120n` 完整单元门禁：Java 1928 项（7 条件跳过），行 23938/24821（96.4425%），分支 10494/11467（91.5148%）；Python 209 项通过，全包/逐脚本行分支门禁通过。当前输出 `quality-n-gate.json`、`quality-n-receipt.json`、`java-n-jacoco/`、`java-n-surefire/`、`python-n-coverage.json`；后续测试夹具调整仍需最终重跑。
- Compose 全默认服务已一次 `up -d --wait` 启动成功；Java 镜像 `8bd76da7d2ec283db37bcace6308dc9f8a6dca07ab82e18c307a6f17c4326492`。所有有健康检查的服务 healthy，worker 已正常执行本轮任务。网络、卷、端口均属于 `rvport-optional-1120`，见私有 `compose-up.log`。
- 真实无构建源码：三个 CLI 和普通报告通过。浮点正常文件为 COMPLETE、AST_ONLY、BC MISSING，1/1 TU 完成；正常文件加缺头文件为 PARTIAL、1/2 TU 完成、1 TU 失败，原因为 frontend_nonzero_exit。缺失构建证据未直接造成 PARTIAL。KB 七语言混合与纯 Go 扫描及报告通过，输出 `compose-source-results.json`。
- PARTIAL 零候选生成、手动空确认、JSON 来源缺口一致、重新生成历史下载不变、框选人工条目及同版本继承、并发版本冲突、跨源码草稿只读通过。A/B 普通用户跨项目 readiness/latest/task/report/source/download/confirm 拒绝，普通用户 admin API 拒绝，管理员读取通过。输出 `compose-review-checks.json`。
- 真实 Codex CLI 仅连接 worker 回环模拟模型，180 候选按实际字节预算分 20 批，180/180 校验通过；confirmed 默认 TO_FIX，其他默认 PENDING_REVIEW，待复核阻止确认；模拟漏项失败且旧有效报告保留。输出 `compose-ai-checks.json`。没有调用真实外部模型。
- 独立 Docker SSH 目标验证无前置 SOURCE_ONLY 直接 BOTH，再执行对应双端测试通过，输出 `compose-build-checks.json`。两端是同架构受控夹具，只证明后端部署/批次/顺序，不宣称真实 RISC-V 兼容。
- 条件数据库测试已实际启用。旧 KB 夹具需要先验证 0128 历史升级，再升级到当前 schema；已同步当前输入计划、活动优先排序和事务断言。历史 0093 的全数据库约束名查询使多个测试 schema 共存时干扰，因此 KB 使用另一个空的独立数据库，不修改历史迁移绕过问题。`schema-kb-q.log` 为该测试通过；其余 6 项在 `schema-final-p.log` 通过，需结合其中明确记录的 KB 失败理解，不能把 p 全组标为通过。

- `rvport-optional-quality-1120k`：Java 1926 项（7 条件跳过）、Python 209 项通过，完整行/分支门禁通过，结果 `quality-k-gate.json`、`quality-k.log`。这是中间快照，不代替后续源码的最终验收。
- 运行恢复沿用已持久化实际输入计划；源码/仅数据库降级不会在重试时自动升级。SOURCE_DISCOVERY 的 latest 不因无关构建或环境变化失效，增强结果检查其实际消费的构建/版本；当前可消费结果与最新失败执行分别返回。
- 补齐逐语言文件范围，保留计划计数与未知执行统计；不将 manifest PLANNED 当作已分析。KB 原生扫描算法及工具源码未修改。
- 第三方 Agent 依赖的重新下载等待过长，独立 Compose 的私有 Dockerfile 复用本地缓存的两个 node_modules。主 Agent 与 OpenCodex 锁文件均与当前源码逐字节相同，`verify_lock.py` 验证当前引擎及全部实际依赖版本；镜像构建再次比较锁文件、验证依赖和 OpenCodex 受控补丁哈希。未复用旧 Java、KB 或适配器代码。
- `rvport-optional-tools-rebuild-1120` 在 3 GiB/2 CPU、无网络容器中从当前两个工具源码重新编译全部生产 worker；只复用已验证的 LLVM 21.1.8 与锁定 Python 构建依赖。浮点版本、IR worker、内存序 doctor 和两个 worker 协议检查通过；日志 `tools-rebuild.log`。缓存结果为 `rvport-optional-analysis-tools:1120`，供本轮 Compose COPY 使用。
- 后续 `1120l` 因新增 KB DTO 辅助代码缺少 import 编译失败，已补齐；`1120m` 1928 项中仅旧历史排序 SQL 断言失败，已按新“活动优先、否则最新”语义更新，需重跑。报告 KB 来源排序同步修复为关联 tasks.created_at，避免引用 KB 表不存在的 created_at。

- 浮点完成度仅来自合法工具结果；缺少构建、编译数据库或 bitcode 不直接推导 PARTIAL。报告保存真实 TU planned/completed/failed/unknown 和层状态，未知计数不补零。
- 报告与确认清单共享固定来源证据投影，下载协议分别为 `report-review.v2`、`confirmed-fix-list.v2`；空确认也保留缺口。同源码但关键证据变化时不继承旧人工决定。
- Docker `rvport-optional-quality-1120c` 的完整快照门禁通过：Java 1909 项（7 条件跳过，具体以导出的 gate JSON 为准），Python 207 项通过；后续代码仍在追加验证，不能以此代表最终源码验收。
- 门禁与报告：`.migration-reports/optional-build/quality-c-gate.json`、`quality-c.log`、`python-c-coverage.json`、`java-c-jacoco/`。所有自有 Java 包及 Python 文件均参与；仅生成 jOOQ 和第三方 node_modules 排除。
- 使用该次完整报告在独立 Docker 中实际删除 exec、清空 XML、将 HTML 时间置旧、删除 Java 包、删除 Python 文件统计、替换为全部跳过的 Surefire 报告，6 项均拒绝；恢复后正向核验通过。记录 `coverage-negative.json`，该验证使用真实采集报告，不仅是模拟 XML 单测。
- 全默认 Compose 镜像中的 frontend 和 MinIO 已从当前源码构建，未修改其代码。backend 首轮因 GitHub LLVM 压缩包速度低于 64 KiB/s 失败，尚未启动全部服务。
- 独立 `rvport-optional-toolchain:1120` 重新校验现有第三方 LLVM 缓存，压缩包 SHA-256 与 Dockerfile 的 `b456e58a78fc48d9f0cf3d767762840baf0346806f71d399d1dfffff58c5b489` 完全一致，原始包摘要与锁定值一致；Compose 私有 override 仅复用该第三方依赖阶段，分析工具、Java 和 KB 仍须从当前源码构建。没有以旧业务镜像代替当前验证。

## 基线与边界

- 分支：`dev`；基线：`fc125e11101f496d83ab955917ec2549554c82b0`（PR #22）。
- 仅编辑 backend；根 README、月度报告、架构文档及 docs/assets 的已有用户改动保持原样。
- KB Scanner 已支持直接源码扫描。本次修改其后端前置校验、可选参数和结果适用性，不修改工具源码，不向 KB 传专项内部 TU 投影。
- 原多语言 change 的基础能力继续沿用，归属见 design 第 13 节；本次没有编辑、同步或归档该 change。
- KB 版本 `0.1.0`，输出协议 `2.0`；浮点包 `0.6.0`；内存序包 `0.1.0`；后端锁定 LLVM `21.1.8`。工具版本未升级。
- 浮点请求协议 `1.4`、结果 `2.0`，已有源/目标 profile 为 `x86_64-linux-gnu-clang21@1.0`、`riscv64-linux-gnu-lp64d-clang21@1.0`；内存序当前统一请求为 `2`（随 PR #22 的语言标准语义更新），结果协议 `1`、策略 `bounded-default-v1`，BC 来源 profile 沿用 `source-preserving@1`。原后端请求版本硬编码为 `1`，本次按当前适配器实际产出的协议修正为 `2`。
- 当前单测资源：`rvport-optional-*1120*` 容器、`rvport-optional-unit-1120` 网络；源码只读挂载，测试输出留在容器，导出至 backend 的 `.migration-reports/optional-build/`。未连接部署数据库或真实 SSH。

## JaCoCo 初始实际采集验证（2026-11-20）

以当前 pom 的 `@{argLine} -Xmx512m` 运行本轮新增的 `CoverageCollectionTests` 和原 `KbScanContextServiceTests`。

```sh
mvn -B -s /opt/maven-cache/settings.xml -Dmaven.repo.local=/opt/maven-cache/repository \
  -Dtest=CoverageCollectionTests,KbScanContextServiceTests clean test jacoco:report
```

命令在 `rvport-optional-java-probe-1120b` 中执行，CPU 2 核、内存 3 GiB，使用本轮 Docker 构建的 unit-test 镜像并重新复制当前源码、清理 target。

- 13 项测试，0 失败、0 错误、0 跳过。
- 新测试直接断言测试 JVM 含 JaCoCo javaagent 和 `-Xmx512m`，并通过运行中的代理读取非空执行数据；未只依赖 Maven 日志。
- `jacoco:report` 读取本轮 exec，分析 834 个自有类；新的 exec、XML、HTML 与 Surefire 报告已导出到 `.migration-reports/optional-build/java-probe/`。
- 此处只验证采集链路，定向测试覆盖率不代表项目整体达标。随后完整基线 `clean verify` 为 1863 项测试、0 失败、0 错误、6 条件跳过；行 23052/23917（96.38%）、分支 9580/10480（91.41%）。只有 `source.deploy` 包分支约 88% 未达到门槛，因此 Maven 正确失败；不能记为完整验收通过。报告在 `.migration-reports/optional-build/java-baseline-jacoco/`。
- 第一次离线探针因镜像未预取 Maven clean 插件而失败；在专用网络中补齐容器依赖后重跑通过，没有修改宿主依赖或远程环境。

## Python 初始定向验证

- 当前源码发现计划及专项适配器：13 项通过；使用当前源码只读挂载，分析资源范围包含未加载脚本。定向组合覆盖率 93%（coverage 合并行/分支显示值），不能替代最终分项门槛。
- 覆盖率门禁：8 项通过；验证缺失、空、旧报告、无成功测试、源码变化、缺模块和低覆盖率等拒绝路径。脚本组合覆盖率显示 98%，最终仍须导出行/分支分别统计。
- 浮点与内存序无编译数据库的初步入口探针已取得真实输出。使用缓存工具镜像的结果只用于接口可行性调查，不能替代最终当前工具源码镜像验收；KB 本次真实入口验证仍待执行。

## 统计范围

### V01–V19 验收追踪

下表是实施初期建立的追踪关系；最终逐项证据在末尾汇总，不以此表替代实际测试。

| 场景 | 单元测试归属 | 契约/Compose 验收重点 |
|---|---|---|
| V01 | KbScanService/Runtime、SpecializedAnalysisService/Runtime、source_analysis_plan | 无环境创建三个分析，依赖分析拒绝 |
| V02 | KbScanContextService、StageReadinessService、ReportInputSelectionPolicy | 失败/活动构建不回退旧成功 |
| V03 | SpecializedBitcodeCapabilityResolver、prepare_specialized_analysis | AST、部分/完整 BC 真实 CLI |
| V04 | AnalysisInputPlan、SharedBitcodeArtifactVerifier、KB Runtime | 跨项目、坏哈希、丢制品安全降级 |
| V05 | SourceAnalysisPlanService、source_analysis_plan、专项 Runtime | 缺头、旧标准、超限及空输入 |
| V06 | ReportInputSelectionPolicy、ReportSourceRepository、报告生成服务 | PARTIAL 零候选普通/AI 报告 |
| V07 | KB/专项 latest、报告来源选择 | 本次失败和旧有效结果分离 |
| V08 | 输入计划、Context/清单 resolver | 补构建、环境变化、源码切换 |
| V09 | Report AI Runtime/结果校验与检查点 | 多批、漏项、重启、取消 |
| V10 | ReportReview/确认清单既有测试及新增回归 | 三类结论、人工继承、手动确认 |
| V11 | BuildValidation/AutomatedTest、StageReadiness | 无前置构建 BOTH；单侧/旧测试拒绝 |
| V12 | ProjectWorkflowStatus、StageReadiness、latest/发布事务 | 并发读取与迟到发布 |
| V13 | ProjectAccess、个人模型身份、计划/制品/路径测试 | A/B/admin 与下载隔离 |
| V14 | 任务恢复与归档下载测试 | 全默认服务一次启动及恢复 |
| V15 | KB 参数/结果导入与专项语言边界 | 七语言及纯非 C 工程 |
| V16 | AnalysisInputPlanTests、SourceAnalysisPlanServiceTests | 别名等价、范围隔离、制品架构 |
| V17 | Scanner Runtime/worker lease 与诊断 | 子进程超限、worker 丢失 |
| V18 | SourceDeployExecutorTests/HandlerTests | 失败行重试、身份错误零副作用 |
| V19 | CoverageCollectionTests、test_coverage_gate | 缺失/空/旧/跳过/缺模块负向门禁 |

Java 本次受影响完整包至少包括 `analysis`、`kb`、`kb.runtime`、`workflow`、`project`、`report.review`、`source.deploy`；后续实际修改包继续加入并由全部包门禁统计。数据库迁移另做独立数据库验收，不能替代 Java/Python 单测。

### 本轮输入计划与源码入口验证补充

- KB 真实源码入口探针：容器 `rvport-optional-kb-probe-1120b`，只读挂载当前 `tools/kb-scanner`，使用锁定 Python 依赖，未传编译数据库。退出码 0，生成 JSON/HTML，3 条真实候选与 3 条缺失证据诊断；输出明确 `compiled=false`、`linked=false`、`testsExecuted=false`。结果在 `.migration-reports/optional-build/kb-source-probe/`。此前仅因 `PYTHONPATH` 指向错误的 `src` 子目录失败，修正为工具根目录后通过，没有修改工具代码。
- 浮点正常源码退出 0；缺头文件退出 3，为技术失败，不能发布为成功。内存序无 BC 样例退出 2，输出 `partial/inconclusive`；保留缺少 IR 和源码分析诊断。上述可行性探针不替代完整 Compose 验收。
- `rvport-optional-runtime-unit-1120d`：当前输入模型、源码快照服务、专项创建/执行/仓储与 KB 仓储定向测试 82 项通过、0 跳过，JaCoCo 实际分析 840 类。日志 `runtime-d.log`、覆盖率 `runtime-jacoco.xml` 位于上述独立报告目录。
- 输入模型本体行 43/45、分支 54/58；Identity 行 40/40、分支 56/56；BuildBinding 行 4/4、分支 10/10；Artifact 行 1/1、分支 4/4。均超过 90%，枚举无分支；整个受影响包仍需最终全量验收。
- 随后全量进展测试 1885 项，12 个 KB 运行时回归失败源于旧测试夹具未携带 executionContext。已修正受保护读取，新一轮全量测试正在执行；不得将这轮失败结果记为验收通过。

- Java 自有源码全部纳入；仅排除 `cn/rvport/backend/jooq/**` 自动生成代码。保留 pom 既有整体、全部包和指定类的 90% 行/分支门槛。
- Python：`src/main/resources/analysis`、`src/main/resources/bitcode`、`src/main/resources/agentruntime`、`agent-cli` 的全部自有 Python 源码，包括未加载文件；node_modules 属第三方依赖。
- 最终必须对整体及受影响完整包/脚本分别给出分子、分母和行/分支结果，未达到不得勾选质量或 Compose 交付任务。


## 最终 Compose 补充（2026-11-20）

最终后端镜像：`sha256:509e970c46717ea45a8b486006135693123bde7c93323a1e6e3e01b4fd4516a9`。`compose-backend-build-8.log` 为当前源码构建记录；没有使用旧业务镜像替代本轮源码。最终恢复正常私有 override，去掉故障注入的短租约配置，保留默认 KB 2048 MiB 预算。

从仓库根实际执行（文件均只属于本轮隔离验收，env 含随机凭据不公开）：

```sh
docker compose --env-file backend/.migration-reports/optional-build/compose.env -p rvport-optional-1120 -f docker-compose.yml -f backend/.migration-reports/optional-build/compose.override.yml build backend
docker compose --env-file backend/.migration-reports/optional-build/compose.env -p rvport-optional-1120 -f docker-compose.yml -f backend/.migration-reports/optional-build/compose.override.yml up -d --wait
```

frontend、MinIO 已在此前从当前源码依次构建，其他默认服务使用 Compose 固定版本；本次 backend 最终重建后，再一次启动全部默认服务。7 个服务运行，6 个有健康检查的服务 healthy，worker 无 Docker healthcheck，已通过真实领取/执行任务验证；均未 paused、未 OOMKilled。API 端口 29880、前端端口 29881，数据库等仅隔离网络可达。资源预算与镜像 ID 在 `compose-final-health.json`；日志 `compose-final-api.log`、`compose-final-worker.log` 未发现启动失败或 JVM OOM。任务故障样例的预期错误不冒充服务整体故障。

单测、迁移和镜像构建依次执行并限制资源。最终业务夹具在客户端容器执行，例如：

```sh
docker exec rvport-optional-client-1120 python3 /tmp/optional-fixtures/compose-acceptance.py
docker exec rvport-optional-client-1120 python3 /tmp/optional-fixtures/compose-review-checks.py
docker exec rvport-optional-client-1120 python3 /tmp/optional-fixtures/compose-build-checks.py
docker exec rvport-optional-client-1120 python3 /tmp/optional-fixtures/compose-enhanced-checks.py
docker exec rvport-optional-client-1120 python3 /tmp/optional-fixtures/compose-enhanced-checks.py partial
docker exec rvport-optional-client-1120 python3 /tmp/optional-fixtures/compose-context-checks.py
docker exec rvport-optional-client-1120 python3 /tmp/optional-fixtures/compose-ai-checks.py run
```

跟踪夹具位于 `src/test/fixtures/optional_source_analysis/`，README 明确独立资源、初始化、故障注入和保密约束。仅在本轮 MinIO 暂时删除已备份测试对象，并使用 finally 恢复；未删除元数据，未触及其他实例。临时制品故障脚本和运行结果留在忽略目录。

| 场景 | 本轮结果与证据 |
|---|---|
| V01 | 三个无构建/无环境源码分析成功，依赖分析仍受阻；`compose-source-results.json` |
| V02 | 最新失败 SOURCE_ONLY 不回退旧成功；三个源码分析仍成功；`compose-context-checks.json` |
| V03 | 完整 BC 为 AST_BC_COMPLETE，1 成功/0 失败；部分 BC 为 AST_BC_MIXED，1 成功/1 失败；仅数据库无 BC 为 AST_ONLY；`compose-final-bc-full.json`、`compose-final-bc-partial.json`、`compose-artifact-bc-missing.json` |
| V04 | 存储中数据库消失后三个分析转 SOURCE_DISCOVERY；浮点 2/2 TU COMPLETE，缺口保留。BC 消失退 AST_ONLY；恢复对象成功。跨项目/哈希/producer/ABI/路径异常另由单元回归覆盖；`compose-artifact-compile-missing.json` |
| V05 | 正常源码无 BC 仍 COMPLETE；缺头样例 PARTIAL，1/2 TU 完成、1 失败，真实 reason/layers 保留；完整增强中未编译 other.c 明确 OMITTED / NOT_IN_COMPILE_DATABASE；空/超限/非法路径由单测补齐 |
| V06 | PARTIAL 零候选普通报告、空确认和 AI NOT_REQUIRED 均成功；报告/确认缺口一致；`compose-partial-zero-ai.json`、`compose-review-checks.json` |
| V07 | 无合法输入拒绝；AI 漏项、取消、失联失败保留旧有效报告；单测验证 latest 最新执行与 currentResult 分离 |
| V08 | 补建时保留源码结果并提示 enhancementAvailable；架构变化失效、等价别名恢复；跨源码草稿只读、历史下载字节不变；context/review/full-BC 证据 |
| V09 | 真实 Codex CLI 消费 180 条真实 KB 候选，20 批、180/180 完整验证；漏项失败、取消、失联、迟到拒绝与重新生成恢复；`compose-final-ai-checks.json`、`compose-ai-recovery-checks.json`、`compose-cancel-check.json`、`compose-lost-check.json` |
| V10 | 每批模拟 1 confirmed：20 TO_FIX、160 PENDING_REVIEW；人工继承优先、待复核阻止提交；原始缺口不被覆盖；AI 与 review 证据 |
| V11 | 无 SOURCE_ONLY 直接 BOTH 成功，之后对应两端自动测试成功；无成功双端时阻止测试；`compose-build-checks.json`；单侧/旧批次由单测覆盖 |
| V12 | readiness/overview/latest/创建/执行/报告语义一致；Context 和人工 revision 冲突、旧租约发布拒绝；单测、context/review/lost 证据 |
| V13 | A/B 普通用户横向项目/任务/源码/下载/修改拒绝；普通用户访问 admin API 拒绝、管理员读取成功；计划/制品/路径安全单测；review 证据 |
| V14 | 最终一次全默认服务 up --wait 成功；排队时停 worker，启动后任务成功；故障后任务及固定报告可读；health/up/review/lifecycle 证据 |
| V15 | KB 七语言混合和纯 Go 成功；专项非 C/C++ 范围如实报告；source 证据及语言单元矩阵 |
| V16 | profile 跨架构失效与 amd64/x86 等价恢复；输入摘要固定语言、架构、scope、排除、策略；可信制品上下文/生产者/ABI 匹配单元回归通过 |
| V17 | 1 MiB 子进程退出 134，保留预算和 ANALYSIS_PROCESS_TERMINATED，不猜测 OOM；后续浮点成功；暂停 worker 触发 WORKER_LOST，恢复后旧结果不发布，新任务完整成功；memory/lost/recovery 证据 |
| V18 | 直接 BOTH 的真实部署/测试；同 task 失败行复用、错误身份/受控路径零副作用、双端及并发租约由 SourceDeploy 单元回归覆盖；不以本地同架构证明 RISC-V 实机兼容 |
| V19 | 当前 exec/XML/HTML、1932 测试来源、源码指纹、全模块 90% 门禁均通过；6 项实际破坏报告反例拒绝；quality-v 与 coverage-negative 证据 |

最终验收采用当前源码的真实三个 CLI；模型上游是本地回环协议夹具，不能代表真实模型判断准确率。SSH 仅连接 Docker 网络中的 optional-target，两端为同架构受控目标，不能代表真实 RISC-V 性能或兼容性。上述边界不影响后端输入计划、状态、权限、批次和发布协议的验收。

本轮发现并修复：部分 BC 失败记录的占位源哈希不应阻止可信源码辅助；相对编译路径须依声明目录解析；worker 须先注册后消费；增强模式须列明未编译/未归档源码。中途失败测试和断言纠正均记录在过程日志，不作为最终通过依据。制品在创建前已不可用时直接记录 COMPILE_DATABASE_UNAVAILABLE；创建后绑定制品才发生不可用时记录 OPTIONAL_ARTIFACT_UNAVAILABLE，不能要求两种阶段返回同一原因码。

最终交付检查：backend 根执行 `openspec validate make-x86-build-optional-for-source-analysis --strict` 通过；`openspec instructions apply --change make-x86-build-optional-for-source-analysis --json` 返回 49/49、remaining=0、all_done。change 内 Markdown 本地链接检查通过。当前仍为 dev，未提交、推送、同步主规范或归档；前端、工具源码及远程环境未修改。本轮独立 Compose 保持运行，故障注入已撤销，测试对象已恢复。
