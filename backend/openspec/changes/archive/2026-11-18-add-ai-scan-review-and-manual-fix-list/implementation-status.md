# 本地实施与验收记录

后端独立 change 位于 dev。最终验收使用全新项目 rvport-review-final-1117，结果见下节。此文保留前期里程碑，早先的覆盖率与未完成项仅描述当时版本。

## 最终实现与验收（2026-11-17）

- 规则、落库、API 与联调读取方式见[结果报告与待修复清单](../../../../docs/report-review.zh-CN.md)。工作台给出全局与过滤统计、原始证据、固定来源位置、人工状态、Context 和可写原因。重新生成基线与当前可用报告可以不同。
- 源码复检和报告比较读取两侧不可变确认。目标确认须适用于当前上下文；历史基线可用于比较。相同确认对复用任务，更换确认不复用旧结果。复检的待修复计数仅统计 TO_FIX，同时保存排除/不再选中等差异证据。旧 KB AI/节点确认入口、扫描完成时的旧同步与专用存储已删除。新 Liquibase 迁移为 0136/0137，jOOQ 由 Docker 生成。
- 验收源标识为 dev 基线 `49bededaa55e8554662ee7346f8361fc97c0039e` 上的工作区内容。生产源码与构建输入（745 文件）清单摘要为 `33b6e186bec150107154dd13c5b71eeafb961dc0f5c28edd0258b1740d8fd0d9`。本轮独立记录位于仓库根 `.migration-reports/report-review-final-1117/`，包括完整文件清单、镜像 ID、日志、JUnit XML 与 JaCoCo HTML/XML。未访问远程环境。
- Docker Java 全量 clean verify 共 1860 项，0 失败、0 错误，6 项条件数据库测试均在独立 PostgreSQL 上另行启用。JaCoCo 整体行 23030/23884（96.42%）、分支 9564/10457（91.46%）。全部 53 个完整生产包参与门禁；有相应指令的包中最低行 90.48%、最低分支 90.00%。核心 report.review 行 1086/1188（91.41%）、分支 977/1076（90.80%）。其他各包的分子、分母见 java-coverage.csv。统计包括未执行的自有生产类，排除生成 jOOQ；外部 Maven 依赖与测试夹具不属于生产类。
- Docker Python pytest 189 项通过。显式列出四个生产目录，完整 8 个自有生产脚本逐文件检查未加载源码与行、分支门禁。总行 1252/1281（97.74%）、分支 530/555（95.50%）。仅第三方包目录不计入自有源码。固定第三方 OpenCodex/Bun 另有协议回归 20 项、67 个断言通过。
- PostgreSQL 单独运行空库及重复 Liquibase 初始化（1 项）、报告/个人配置/KB 数据库测试（5 项）、过期租约和并发事务（7 项），共 13 项，0 失败/错误/跳过。数据库测试与单测统计分开。
- 完整根 Compose 构建及一次 up -d --wait 覆盖 7 个默认服务，业务侧另外核验 worker 心跳 ONLINE。应用与 worker 各限 2 CPU/2 GiB，Java 单测容器 2 CPU/3 GiB，Compose build 并发 1。构建覆盖仅预取锁文件对应的 npm 包并复用固定 LLVM 工具链，本轮编译后端及单测镜像。后端镜像 `ecfa88c7d33a`，单测镜像 `6eb4f19218af`。模型上游为本地回环夹具，真实执行 Codex Chat/Responses、OpenCode Chat、DeepSeek Harness Chat。
- B01–B18 的输入、断言和证据映射见 [requirements-mapping.md](requirements-mapping.md)。四源混合 AI 复核匹配 6 个预期 ID，另 1 项继承人工排除；预期失败、超时及迟到发布均未覆盖有效报告。HTTP 真实验证跨页阻止提交、多行源码框选、登录态退役路由、API/worker 重启后的任务/SSE 恢复。断开 SSE 测试订阅时服务日志出现一次 Broken pipe，原游标续读及任务完成断言均成功。
- 独立项目的全量初始化、端口、队列、源码/制品卷与测试账号隔离；导出证据后仅清理本轮资源。复现和重置方法见[夹具说明](../../../../src/test/fixtures/README.md)。实施完成后的规范同步与归档见下节记录。

### 复现与原始证据

从仓库根运行下列命令；本轮执行的 Compose 覆盖文件位于忽略目录。重新演练需先按夹具说明准备新环境文件和独立网络。单测容器的 Maven 缓存仅从已有本地依赖缓存填充，不导入 target 或覆盖率记录。

```powershell
$r = '.migration-reports/report-review-final-1117'
$p = 'rvport-review-final-1117'
$cfg = @('-p',$p,'--env-file',"$r/compose.env",'-f','docker-compose.yml','-f',"$r/compose.override.yaml")
docker build --target unit-test --build-arg MAVEN_MIRROR_URL=https://repo.maven.apache.org/maven2 -f backend/Dockerfile -t rvport-review-proof-unit:1117 .
docker exec rvport-review-final-gate mvn -B -o -s /opt/maven-cache/settings.xml '-Dmaven.repo.local=/opt/maven-cache/repository' clean verify
docker compose --parallel 1 @cfg build
docker compose @cfg up -d --wait --wait-timeout 220
```

Docker 内 Python 完整命令和实际 Agent 联调命令见夹具说明。原始证据索引为上述目录内的 验收结果.md；final-java.log、final-python-gate.log、final-bun.log 和三个 final-db-*.log 均退出 0。完整服务、业务矩阵、HTTP 重连及迟到发布见 final-compose-up.log、final-services.txt、worker-health.txt、final-standard-review.log、final-personal-review.log、final-http-*.log。清理见 cleanup.log。

### 规范同步与归档（2026-11-18）

按用户授权同步 backend 独立 OpenSpec 根的 14 项 delta，其中新增 report-ai-review、report-review-workspace、report-review-transition，更新现有 11 项能力及 Purpose，保留未涉及条款。当前配置资格与实际运行结果分开：readiness 的 aiBlockers 表达个人 REPORT_AI_REVIEW 配置要求，canGenerateAi 同时要求有效分析输入；配置状态不依赖历史 probe 是否成功。两处残留的旧数据兼容迁移说明已统一为开发实例全新初始化策略。

任务 57/57 完成；归档前 `openspec validate add-ai-scan-review-and-manual-fix-list --strict` 通过，`openspec validate --specs --strict` 通过 44/44。归档目录为 `backend/openspec/changes/archive/2026-11-18-add-ai-scan-review-and-manual-fix-list/`。本次仅同步和整理规范文档，代码、单元测试及 Docker 验收证据沿用上述 2026-11-17 最终记录，未重新运行代码测试。

## 先前实施记录

## 个人模型与 Agent

知识库扫描保留扫描本身；原知识库 AI 建议、worker handler 和读取入口退役。结果评估报告使用 `REPORT_GENERATE`，其中 `AI_ENHANCED` 必须从当前触发者的 `REPORT_AI_REVIEW` 阶段读取其模型 profile 和平台受管 Agent runtime，`STANDARD` 不执行模型。平台不替用户选择默认模型；管理员访问其他人的项目时也使用自己的绑定。新实例重新绑定，不转换旧配置。读取报告不要求查看者具备模型。

入队固定用户、profile 和 runtime 的 UUID。领取时核对任务触发者、报告生成者和报告保存的选择，再校验账号、项目、profile 归属、模型及 Agent 的启用状态；自动重试不重选当前阶段绑定。手动重新生成按新触发者解析。请求体不能替换配置人、密钥、模型或 Agent。

## 本轮本地验证

- 仅使用独立项目 `rvport-report-review-1117`、独立数据库与本地模拟上游；没有访问远端，没有修改前端。完整 Compose 构建使用与产品锁文件相同的 LLVM 散列及 npm tarball，本地构建覆盖只负责预取锁定依赖。更新后一次 `docker compose up -d --wait` 启动七项服务；在独立数据库查询到 ONLINE worker 心跳。
- **触发者隔离：实际队列和 worker 通过。** 在同一测试项目中，所有者先绑定 Codex 和自己的 Chat profile，管理员随后绑定 OpenCode 和自己的 Chat profile 并重新生成。每次均检查模型侧接收的身份、任务创建人和 profile 所有者、发布记录里的 runtime/profile UUID、实际逐项结论。模拟模型只监听独立 worker 的回环地址，拒绝不匹配的夹具密钥，只记录别名，退出后停止。在同一事务中将原 profile 加入任务后换绑并禁用原 profile，实际 worker 自动重试两次仍按原引用拒绝，模型端零新增请求，上一次成功报告保持 CURRENT。证据：`compose-actors-retry.log`。
- **受管 CLI：四条真实执行路径通过。** 在无外网的容器中运行 Codex CHAT、Codex RESPONSES、DeepSeek Harness CHAT、OpenCode CHAT，分别发出 4、4、6、6 次本地请求，全部退出 0。检查实际输入 ID 和输出 verdict。Codex Chat 的代理负责核对监听状态，源码沙箱只包住实际 CLI；CLI 不继承代理目录或上游密钥。证据：`agent-matrix.log`。
- **普通模式：实际队列和 worker 通过。** 无个人绑定仍能生成，重复请求返回原任务；待复核时确认被拒绝，全部排除可确认空清单；在零发现文件框选并新增完整条目会使当前确认失效，重新确认得到非空清单；原清单的 JSON 下载字节保持不变。证据：`compose-standard.log`。
- Maven `clean verify`：1903 项，0 失败、0 错误、6 项条件跳过。JaCoCo 整体行 24068/25028（96.16%）、分支 9924/10970（90.46%）。受影响的 `report.review` 行 942/1104（85.33%）、分支 812/992（81.85%）；`codexprovider`、`project`、`reportcomparison.runtime` 的分支亦有缺口。**质量门槛未通过**。Java 纳入未加载生产类，仅排除生成的 jOOQ。报告：`java-publication-gate.log`、`java-publication-jacoco/index.html`。在独立 PostgreSQL 18.4 上另行启用 ReportReviewDatabaseTests 通过，确认原始事实、持久空清单、后续编辑与事务回滚；证据：`real-database-current.log`。
- Python 全量 188 项通过。coverage 统计 analysis、bitcode、agentruntime 资源与 agent-cli 的 Python 自有源码，整体行 1252/1281（97.74%）、分支 530/555（95.50%）。报告沙箱行 79/81、分支 28/30；OpenCodex 监管器行 163/165、分支 74/76。Python 数据不代表其他语言的脚本已经验收。报告：`python-agent.json`。
- 实际命令（从仓库根运行，Docker 测试容器限制 2 CPU / 3 GiB；Compose 使用独立 env、端口、网络和卷）。本轮导出的报告均在仓库根的 `.migration-reports/report-review-20261117/`，不提交运行数据。

```powershell
docker exec rvport-report-review-work mvn -B -s /opt/maven-cache/settings.xml '-Dmaven.repo.local=/opt/maven-cache/repository' clean verify
docker exec -e PYTHONPATH=/workspace/tools/memory-order-analyzer/python rvport-report-review-work python3 -m coverage run --branch --source=src/main/resources/analysis,src/main/resources/bitcode,src/main/resources/agentruntime,agent-cli -m pytest -q src/test/python
docker compose --parallel 1 --project-name rvport-report-review-1117 --env-file .migration-reports/report-review-20261117/compose.env -f docker-compose.yml -f .migration-reports/report-review-20261117/compose.override.yaml build
docker compose --parallel 1 --project-name rvport-report-review-1117 --env-file .migration-reports/report-review-20261117/compose.env -f docker-compose.yml -f .migration-reports/report-review-20261117/compose.override.yaml up -d --wait --wait-timeout 220
```

## 草稿初始状态专项复验（2026-11-17）

- 本地独立 Compose 项目使用同一成功知识库扫描、同一源码版本和触发者原有的 Codex + GLM-5.3 绑定重新生成。第一次 Agent 执行失败，自动重试一次成功。新报告标记 CURRENT，22 条 AI 结果全部通过完整性校验，草稿查询返回 TO_FIX 22、PENDING_REVIEW 0、EXCLUDED 0、UNCONFIRMED。确认表为零行；旧报告进入历史，原有 22 条待复核仍可追溯。
- Docker 单测命令为 `docker exec rvport-report-review-work mvn -B -o -s /opt/maven-cache/settings.xml -Dmaven.repo.local=/opt/maven-cache/repository clean verify`。Surefire 统计 1929 项，0 失败、0 错误、6 项条件跳过。独立 PostgreSQL 上另跑数据库回归，验证人工处置事件、确认快照与条目归属。使用上节完整 Compose 构建和启动命令，七个默认服务就绪。
- JaCoCo 统计全部 Java 生产类（排除生成的 jOOQ）。整体行 24145/25039（96.43%）、分支 10036/10992（91.30%）；受影响的 report.review 行 1019/1115（91.39%）、分支 924/1014（91.12%）。完整 Maven 门禁仍因 codexprovider、project（含 ProjectWorkflowStatusService）、reportcomparison.runtime 的既有分支覆盖率不足而失败。运行证据位于仓库根 `.migration-reports/report-review-20261117/` 下的 `ai-defaults-verify.log`、`ai-defaults-db.log`、`ai-defaults-jacoco/`、`real-kb/regenerated/`。
